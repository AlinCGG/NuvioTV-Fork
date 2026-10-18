package com.nuvio.tv.ui.screens.player

/**
 * How many downloads a progressive stream may use at once with Custom network off. One more is tried only while the
 * film runs short, kept only when it raises the delivered rate by [GAIN_PERCENT] % (or the buffer starts to grow),
 * and handed back once the buffer has been comfortable for a while. One more is only tried while the box has the memory
 * it would hold free above its low-memory line and its swap is not nearly used up, and one is handed back as soon as
 * free memory drops below that line.
 */
internal class AutoSpeedupPolicy(val maxDownloads: Int, private val pieceBytes: Long = 0L) {

    /**
     * One player sample. [bytes] (received from the network) and [rebuffers] are running totals for the stream.
     * [bufferFull] = the buffer holds all the memory it may, so a flat buffer says nothing about the network.
     * [roomBytes] = free memory above the box's low-memory line and margin; below zero the box is short.
     * [swapLow] = the box's swap is nearly used up: no download is added, none is handed back for it.
     */
    data class Sample(
        val nowMs: Long,
        val aheadMs: Long,
        val loading: Boolean,
        val rebuffers: Int,
        val bytes: Long,
        val memoryShort: Boolean = false,
        val playing: Boolean = true,
        val bufferFull: Boolean = false,
        val multiActive: Boolean = true,
        val roomBytes: Long = Long.MAX_VALUE,
        val swapLow: Boolean = false
    )

    @Volatile
    var downloads: Int = 1
        private set

    /** What the last change was, or that one more was held back for swap, for the log; null otherwise. */
    var lastChange: String? = null
        private set

    private val samples = ArrayDeque<Sample>()
    private var trialStartMs = -1L
    private var trialFromMs = -1L
    private var trialLengthMs = TRIAL_MS
    private var trialNeedsSwitch = false
    private var trialBaselineBps = 0L
    private var nextStepAtMs = 0L
    private var blockedUntilMs = 0L
    private var comfortableSinceMs = -1L
    private var lastDropAtMs = Long.MIN_VALUE / 2
    private var keepForFilm = false
    private var heldForSwap = false

    fun onSample(s: Sample): Int {
        lastChange = null
        // A seek empties the buffer at once; what came before says nothing about the new position.
        val previous = samples.lastOrNull()
        if (previous != null && s.aheadMs < previous.aheadMs - SEEK_DROP_MS) samples.clear()
        samples.addLast(s)
        while (samples.size > 1 && s.nowMs - samples.first().nowMs > HISTORY_MS) samples.removeFirst()
        if (!s.swapLow) heldForSwap = false

        if (s.memoryShort) {
            if (downloads > 1) change(1, s.nowMs, "memory short")
            trialStartMs = -1L
            blockedUntilMs = s.nowMs + MEMORY_BLOCK_MS
            return downloads
        }

        if (s.roomBytes < 0L && downloads > 1) {
            if (s.nowMs >= nextStepAtMs) {
                trialStartMs = -1L
                blockedUntilMs = maxOf(blockedUntilMs, s.nowMs + MEMORY_BLOCK_MS)
                change(downloads - 1, s.nowMs, "memory low roomMb=${s.roomBytes / MB}")
            }
            return downloads
        }

        if (trialStartMs >= 0L) {
            if (trialFromMs < 0L) {
                // The extra download only counts from the moment the stream is actually on the chunk session.
                if (!trialNeedsSwitch || s.multiActive) {
                    trialFromMs = s.nowMs
                } else if (s.nowMs - trialStartMs >= SWITCH_WAIT_MS) {
                    trialStartMs = -1L
                    blockedUntilMs = s.nowMs + FAILED_BLOCK_MS
                    change(downloads - 1, s.nowMs, "never switched")
                }
            } else if (s.nowMs - trialFromMs >= trialLengthMs) {
                finishTrial(s)
            }
            return downloads
        }

        val starving = starving(s)
        if (starving) {
            comfortableSinceMs = -1L
            val mayAdd = downloads < maxDownloads && s.nowMs >= nextStepAtMs && s.nowMs >= blockedUntilMs &&
                s.roomBytes >= lentBytes(downloads + 1, pieceBytes) - lentBytes(downloads, pieceBytes)
            if (mayAdd && s.swapLow) {
                if (!heldForSwap) lastChange = "held downloads=$downloads swap low"
                heldForSwap = true
                return downloads
            }
            if (mayAdd) {
                trialBaselineBps = rateBps(s.nowMs - STARVE_WINDOW_MS, s.nowMs)
                trialStartMs = s.nowMs
                trialFromMs = -1L
                trialNeedsSwitch = downloads == 1
                trialLengthMs = trialLength(trialBaselineBps)
                if (s.nowMs - lastDropAtMs < KEEP_AFTER_DROP_MS) keepForFilm = true
                change(downloads + 1, s.nowMs, "trial baselineKbps=${trialBaselineBps * 8 / 1000}")
            }
            return downloads
        }

        if (downloads > 1 && !keepForFilm && comfortable(s)) {
            if (comfortableSinceMs < 0L) comfortableSinceMs = s.nowMs
            if (s.nowMs - comfortableSinceMs >= COMFORTABLE_MS) {
                lastDropAtMs = s.nowMs
                comfortableSinceMs = s.nowMs
                change(downloads - 1, s.nowMs, "comfortable")
            }
        } else {
            comfortableSinceMs = -1L
        }
        return downloads
    }

    /** Long enough for two of the new download's pieces at the old rate, so slow links are not judged on one. */
    private fun trialLength(baselineBps: Long): Long {
        if (pieceBytes <= 0L || baselineBps <= 0L) return TRIAL_MS
        return (TRIAL_SETTLE_MS + 2 * pieceBytes * 1000L / baselineBps).coerceIn(TRIAL_MS, MAX_TRIAL_MS)
    }

    private fun finishTrial(s: Sample) {
        val from = trialFromMs + TRIAL_SETTLE_MS
        val trialBps = rateBps(from, s.nowMs)
        val playedThrough = samples.filter { it.nowMs >= from }.all { it.playing }
        val grew = playedThrough && s.aheadMs - aheadAt(from) >= GROWTH_PASS_MS
        val waited = loadingShare(from, s.nowMs) < LOADING_PASS_PERCENT
        val faster = trialBaselineBps > 0L && trialBps * 100 >= trialBaselineBps * (100 + GAIN_PERCENT)
        trialStartMs = -1L
        val detail = "kbps=${trialBps * 8 / 1000} baselineKbps=${trialBaselineBps * 8 / 1000}"
        if (faster || grew || waited) {
            nextStepAtMs = s.nowMs + STEP_GAP_MS
            lastChange = "kept downloads=$downloads $detail grew=$grew waited=$waited"
        } else {
            blockedUntilMs = s.nowMs + FAILED_BLOCK_MS
            change(downloads - 1, s.nowMs, "no gain $detail")
        }
    }

    private fun change(to: Int, nowMs: Long, why: String) {
        lastChange = "downloads ${downloads}->$to $why"
        downloads = to.coerceIn(1, maxDownloads.coerceAtLeast(1))
        nextStepAtMs = maxOf(nextStepAtMs, nowMs + STEP_GAP_MS)
    }

    /** Short of buffer and not gaining while the player loads all the time, or a rebuffer in the window. */
    private fun starving(s: Sample): Boolean {
        val start = s.nowMs - STARVE_WINDOW_MS
        val oldest = samples.lastOrNull { it.nowMs <= start } ?: return false
        if (s.rebuffers > oldest.rebuffers) return true
        if (s.aheadMs >= STARVE_AHEAD_MS || samples.any { it.nowMs >= start && it.bufferFull }) return false
        if (samples.any { it.nowMs >= start && !it.loading }) return false
        return s.aheadMs - oldest.aheadMs <= GROWTH_STARVE_MS
    }

    /** An idle loader alone is not enough: with the buffer capped by memory it idles with only a few seconds ahead. */
    private fun comfortable(s: Sample): Boolean =
        s.aheadMs >= COMFORTABLE_AHEAD_MS ||
            (s.aheadMs >= COMFORTABLE_IDLE_AHEAD_MS && loadingShare(s.nowMs - STARVE_WINDOW_MS, s.nowMs) < LOADING_PASS_PERCENT)

    private fun rateBps(fromMs: Long, toMs: Long): Long {
        val a = samples.lastOrNull { it.nowMs <= fromMs } ?: samples.firstOrNull() ?: return 0L
        val b = samples.lastOrNull { it.nowMs <= toMs } ?: return 0L
        val ms = b.nowMs - a.nowMs
        return if (ms <= 0L) 0L else (b.bytes - a.bytes).coerceAtLeast(0L) * 1000L / ms
    }

    private fun aheadAt(ms: Long): Long = (samples.lastOrNull { it.nowMs <= ms } ?: samples.first()).aheadMs

    private fun loadingShare(fromMs: Long, toMs: Long): Int {
        val inRange = samples.filter { it.nowMs in fromMs..toMs }
        if (inRange.isEmpty()) return 100
        return inRange.count { it.loading } * 100 / inRange.size
    }

    companion object {
        private const val MB = 1024L * 1024L

        /** Memory the extra downloads hold at [downloads]: each one in flight plus the piece being read. */
        fun lentBytes(downloads: Int, pieceBytes: Long): Long = if (downloads > 1) (downloads + 1) * pieceBytes else 0L

        const val GAIN_PERCENT = 15
        const val STARVE_AHEAD_MS = 15_000L
        const val STARVE_WINDOW_MS = 8_000L
        const val GROWTH_STARVE_MS = 500L
        const val TRIAL_MS = 10_000L
        const val MAX_TRIAL_MS = 30_000L
        const val SWITCH_WAIT_MS = 30_000L
        const val TRIAL_SETTLE_MS = 3_000L
        const val GROWTH_PASS_MS = 2_000L
        const val LOADING_PASS_PERCENT = 75
        const val STEP_GAP_MS = 2_000L
        const val FAILED_BLOCK_MS = 120_000L
        const val MEMORY_BLOCK_MS = 60_000L
        const val COMFORTABLE_AHEAD_MS = 30_000L
        const val COMFORTABLE_IDLE_AHEAD_MS = 10_000L
        const val COMFORTABLE_MS = 60_000L
        /** Running short again this soon after a download was handed back: keep the count for the rest of the film. */
        const val KEEP_AFTER_DROP_MS = 180_000L
        const val HISTORY_MS = 40_000L
        const val SEEK_DROP_MS = 2_000L
    }
}
