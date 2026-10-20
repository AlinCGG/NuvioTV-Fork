package com.nuvio.tv.ui.screens.player

/**
 * How many downloads a progressive stream may use at once with Custom network off. One more is tried only while the
 * film runs short, kept only when it raises the delivered rate by [GAIN_PERCENT] % (or the buffer starts to grow),
 * and handed back once the buffer has been comfortable for a while, or as full as the settings and memory let it get
 * with the loader resting. One more is only tried while the box has the memory it would hold free above its low-memory
 * line and its swap is not nearly used up, and one is handed back as soon as free memory drops below that line. A film
 * whose bitrate is known and high first measures one download; only when that stays under [START_SINGLE_ENOUGH_PERCENT]
 * % of the film's rate does it go to [startDownloads], judged like any other try against what the single download
 * managed; if they do not help, the film goes back to one and climbs the usual way if it runs short.
 */
internal class AutoSpeedupPolicy(val maxDownloads: Int, pieceBytes: Long = 0L) {

    @Volatile
    var pieceBytes: Long = pieceBytes


    /**
     * One player sample. [bytes] (received from the network) and [rebuffers] are running totals for the stream.
     * [bufferFull] = the buffer holds all the memory it may, so a flat buffer says nothing about the network.
     * [roomBytes] = free memory above the box's low-memory line and margin; below zero the box is short.
     * [idleBytes] = memory the buffer holds but does not use; adding a download shrinks the buffer and frees it.
     * [userBudget] = the user set the buffer's memory target: the downloads are lent out of it, so free memory is not
     * checked to add one or hand one back; only [memoryShort] and [swapLow] still stop them.
     * [swapLow] = the box's swap is nearly used up: no download is added, none is handed back for it.
     * [fileBitrate] = the film's average bitrate in bits per second from its size and length, 0 while unknown.
     * [maxBufferMs] = the most buffer ahead the player loads (the user's Max buffer), 0 while unknown.
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
        val swapLow: Boolean = false,
        val fileBitrate: Long = 0L,
        val idleBytes: Long = 0L,
        val userBudget: Boolean = false,
        val maxBufferMs: Long = 0L
    )

    @Volatile
    var downloads: Int = 1
        private set

    /** What the last change was, or that a wanted one was held back, for the log; null otherwise. */
    var lastChange: String? = null
        private set

    private val samples = ArrayDeque<Sample>()
    private var trialStartMs = -1L
    private var trialFromMs = -1L
    private var trialLengthMs = TRIAL_MS
    private var trialNeedsSwitch = false
    private var trialBaselineBps = 0L
    private var trialBackTo = 1
    private var trialNeedRate = 0L
    private var triedAny = false
    private var startJudged = false
    private var firstSampleMs = -1L
    private var nextStepAtMs = 0L
    private var blockedUntilMs = 0L
    private var comfortableSinceMs = -1L
    private var lastDropAtMs = Long.MIN_VALUE / 2
    private var keepForFilm = false
    private var heldForSwap = false
    private var heldFor: String? = null

    fun onSample(s: Sample): Int {
        lastChange = null
        // A seek empties the buffer at once; what came before says nothing about the new position.
        val previous = samples.lastOrNull()
        if (previous != null && s.aheadMs < previous.aheadMs - SEEK_DROP_MS) samples.clear()
        samples.addLast(s)
        while (samples.size > 1 && s.nowMs - samples.first().nowMs > HISTORY_MS) samples.removeFirst()
        if (!s.swapLow) heldForSwap = false
        if (firstSampleMs < 0L) firstSampleMs = s.nowMs

        if (s.memoryShort) {
            if (downloads > 1) change(1, s.nowMs, "memory short")
            trialStartMs = -1L
            blockedUntilMs = s.nowMs + MEMORY_BLOCK_MS
            return downloads
        }

        if (!s.userBudget && s.roomBytes < 0L && downloads > 1) {
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
                    change(trialBackTo, s.nowMs, "never switched")
                }
            } else if (s.nowMs - trialFromMs >= trialLengthMs) {
                finishTrial(s)
            }
            return downloads
        }

        if (!triedAny && !startJudged && s.fileBitrate > 0L && downloads == 1 && s.nowMs >= blockedUntilMs && !s.swapLow) {
            var start = startDownloads(s.fileBitrate, maxDownloads)
            while (!s.userBudget && start > 1 && s.roomBytes < lentBytes(start, pieceBytes)) start--
            if (start > 1 && singleMeasured(s)) {
                val singleBps = rateBps(s.nowMs - START_MEASURE_MS, s.nowMs)
                val loadingPercent = loadingShare(s.nowMs - START_MEASURE_MS, s.nowMs)
                val needBps = s.fileBitrate / 8
                val detail = "fileKbps=${s.fileBitrate / 1000} singleKbps=${singleBps * 8 / 1000}"
                if (loadingPercent < LOADING_PASS_PERCENT || singleBps * 100 >= needBps * START_SINGLE_ENOUGH_PERCENT) {
                    startJudged = true
                    lastChange = "start not needed $detail loading=$loadingPercent%"
                } else {
                    startTrial(s, start, TRIAL_MS, "start $detail" + budgetTag(s))
                    trialNeedRate = needBps
                    return downloads
                }
            }
        }

        val starving = starving(s)
        if (starving) {
            comfortableSinceMs = -1L
            val needBytes = lentBytes(downloads + 1, pieceBytes) - lentBytes(downloads, pieceBytes)
            val idle = s.idleBytes.coerceAtLeast(0L)
            val usable = if (s.userBudget || s.roomBytes > Long.MAX_VALUE - idle) Long.MAX_VALUE else s.roomBytes + idle
            val wanted = downloads < maxDownloads && s.nowMs >= nextStepAtMs
            val blocked = s.nowMs < blockedUntilMs
            val mayAdd = wanted && !blocked && usable >= needBytes
            if (wanted && !mayAdd) {
                val state = "$downloads " + if (blocked) "blocked" else "room"
                if (state != heldFor) {
                    heldFor = state
                    lastChange = "held downloads=$downloads roomMb=${s.roomBytes / MB} idleMb=${idle / MB} " +
                        "needMb=${needBytes / MB} blockedS=${(blockedUntilMs - s.nowMs).coerceAtLeast(0L) / 1000} " +
                        "userBudget=${s.userBudget}"
                }
            }
            if (mayAdd && s.swapLow) {
                if (!heldForSwap) lastChange = "held downloads=$downloads swap low"
                heldForSwap = true
                return downloads
            }
            if (mayAdd) {
                val baseline = rateBps(s.nowMs - STARVE_WINDOW_MS, s.nowMs)
                val early = !triedAny && s.nowMs - firstSampleMs < EARLY_MS && s.aheadMs < EARLY_LOW_AHEAD_MS
                val length = if (early) EARLY_TRIAL_MS else trialLength(baseline)
                if (s.nowMs - lastDropAtMs < KEEP_AFTER_DROP_MS) keepForFilm = true
                startTrial(s, downloads + 1, length,
                    "trial baselineKbps=${baseline * 8 / 1000}" + (if (early) " early" else "") + budgetTag(s))
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

    private fun startTrial(s: Sample, to: Int, lengthMs: Long, why: String) {
        trialBaselineBps = rateBps(s.nowMs - STARVE_WINDOW_MS, s.nowMs)
        trialStartMs = s.nowMs
        trialFromMs = -1L
        trialNeedsSwitch = downloads == 1
        trialBackTo = downloads
        trialNeedRate = 0L
        trialLengthMs = lengthMs
        triedAny = true
        change(to, s.nowMs, why)
    }

    private fun budgetTag(s: Sample): String = if (s.userBudget) " userBudget=true" else ""

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
        val faster = if (trialBaselineBps > 0L) {
            trialBps * 100 >= trialBaselineBps * (100 + GAIN_PERCENT)
        } else {
            trialNeedRate > 0L && trialBps >= trialNeedRate
        }
        trialStartMs = -1L
        val detail = "kbps=${trialBps * 8 / 1000} baselineKbps=${trialBaselineBps * 8 / 1000}"
        if (faster || grew || waited) {
            nextStepAtMs = s.nowMs + STEP_GAP_MS
            lastChange = "kept downloads=$downloads $detail grew=$grew waited=$waited"
        } else {
            blockedUntilMs = s.nowMs + if (trialNeedRate > 0L) STEP_GAP_MS else FAILED_BLOCK_MS
            change(trialBackTo, s.nowMs, "no gain $detail")
        }
    }

    private fun change(to: Int, nowMs: Long, why: String) {
        lastChange = "downloads ${downloads}->$to $why"
        heldFor = null
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

    /**
     * A long buffer, or a resting loader with 10 s ahead or with the buffer as full as it can get: at the user's Max
     * buffer, or at the memory it may hold. Handing a download back then gives its memory to the buffer; if the film
     * runs short again a download comes back and stays for the film.
     */
    private fun comfortable(s: Sample): Boolean {
        if (s.aheadMs >= COMFORTABLE_AHEAD_MS) return true
        val from = s.nowMs - STARVE_WINDOW_MS
        if (loadingShare(from, s.nowMs) >= LOADING_PASS_PERCENT) return false
        if (s.aheadMs >= COMFORTABLE_IDLE_AHEAD_MS) return true
        return samples.any {
            it.nowMs >= from && (it.bufferFull || (it.maxBufferMs > 0L && it.aheadMs >= it.maxBufferMs - FULL_SLACK_MS))
        }
    }

    /** One download has run long enough, after the stream settled, to say what it carries. */
    private fun singleMeasured(s: Sample): Boolean =
        s.nowMs - firstSampleMs >= START_SETTLE_MS + START_MEASURE_MS &&
            samples.first().nowMs <= s.nowMs - START_MEASURE_MS

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

        /**
         * Downloads a film starts on from its average bitrate. One long download on a TV box's network carried about
         * 30 to 85 Mbit/s in the box tests, enough for 1080p and streaming 4K (under 40) but not for a 4K remux.
         * Never above 3, so TorBox's 4 connections still leave one for thumbnails, and 2 on boxes allowed 4 (2 GB),
         * where three 16 MB downloads ran the Fire Stick short of memory.
         */
        fun startDownloads(fileBitrate: Long, maxDownloads: Int): Int {
            val want = when {
                fileBitrate >= START_THREE_BPS -> 3
                fileBitrate >= START_TWO_BPS -> 2
                else -> 1
            }
            val cap = if (maxDownloads <= 4) 2 else START_MAX
            return minOf(want, cap, maxDownloads).coerceAtLeast(1)
        }

        const val START_TWO_BPS = 40_000_000L
        const val START_THREE_BPS = 60_000_000L
        const val START_MAX = 3
        /** One download carrying this share of the film's rate is enough; below it the film starts on more. */
        const val START_SINGLE_ENOUGH_PERCENT = 130
        const val START_SETTLE_MS = 2_000L
        const val START_MEASURE_MS = 6_000L
        /** Ahead within this of the Max buffer counts as full. */
        const val FULL_SLACK_MS = 1_000L
        /** The first try of a film that is already short this early is judged sooner. */
        const val EARLY_MS = 90_000L
        const val EARLY_LOW_AHEAD_MS = 5_000L
        const val EARLY_TRIAL_MS = 7_000L
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
