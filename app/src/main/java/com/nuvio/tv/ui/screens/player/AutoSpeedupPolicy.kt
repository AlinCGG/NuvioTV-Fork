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
internal class AutoSpeedupPolicy(
    val maxDownloads: Int,
    pieceBytes: Long = 0L,
    private val smallBox: Boolean = maxDownloads <= 4
) {

    @Volatile
    var pieceBytes: Long = pieceBytes

    /** What one download carried from this server before ([HostSpeeds]), 0 when unknown. */
    @Volatile
    var knownPerDownloadBps: Long = 0L

    /** Downloads the server's shared request budget leaves playback now (thumbnails take the rest). */
    @Volatile
    var roomCap: Int = Int.MAX_VALUE

    /** The most downloads a try kept for this film. */
    var peakKept: Int = 1
        private set

    /** What one download carried in the last kept try of this film, 0 while nothing was kept. */
    val measuredPerDownloadBps: Long get() = perDownloadBps

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
    private var singleRateBps = 0L
    private var trialHeavy = false
    private var presetPending = false

    /** Starts the stream on [start] downloads before the first byte, judged on the first samples like a remux start. */
    fun preset(start: Int) {
        if (start <= 1) return
        downloads = start.coerceAtMost(maxDownloads)
        presetPending = true
        triedAny = true
    }
    private var perDownloadBps = 0L
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
            // One at a time: the read-ahead has already gone, and dropping straight to one stalls a remux.
            if (downloads > 1 && s.nowMs >= nextStepAtMs) change(downloads - 1, s.nowMs, "memory short")
            trialStartMs = -1L
            blockedUntilMs = s.nowMs + MEMORY_BLOCK_MS
            return downloads
        }

        if (!s.userBudget && s.roomBytes < 0L && downloads > 1) {
            // Below the margin but above Android's line: never under what the film needs; past the line is memoryShort.
            if (s.nowMs >= nextStepAtMs && downloads > comfortableFloor(s)) {
                trialStartMs = -1L
                blockedUntilMs = maxOf(blockedUntilMs, s.nowMs + MEMORY_BLOCK_MS)
                change(downloads - 1, s.nowMs, "memory low roomMb=${s.roomBytes / MB}")
            }
            return downloads
        }

        if (presetPending) {
            presetPending = false
            trialBaselineBps = 0L
            trialStartMs = s.nowMs
            trialFromMs = s.nowMs
            trialNeedsSwitch = false
            trialBackTo = 1
            trialNeedRate = s.fileBitrate / 8
            trialHeavy = true
            trialLengthMs = TRIAL_MS
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
            var start = startDownloads(s.fileBitrate, maxDownloads, pieceBytes, smallBox, knownPerDownloadBps)
            while (!s.userBudget && start > 1 && s.roomBytes < lentBytes(start, pieceBytes)) start--
            if (start > 1 && (s.fileBitrate >= START_THREE_BPS || knownPerDownloadBps > 0L)) {
                // One download cannot carry a remux on a TV box; measuring it first only costs the buffer a stop.
                startTrial(s, start, TRIAL_MS, "start heavy fileKbps=${s.fileBitrate / 1000}" + budgetTag(s))
                trialNeedRate = s.fileBitrate / 8
                trialHeavy = true
                return downloads
            }
            if (start > 1 && singleMeasured(s)) {
                val singleBps = rateBps(s.nowMs - START_MEASURE_MS, s.nowMs)
                val loadingPercent = loadingShare(s.nowMs - START_MEASURE_MS, s.nowMs)
                val needBps = s.fileBitrate / 8
                val detail = "fileKbps=${s.fileBitrate / 1000} singleKbps=${singleBps * 8 / 1000}"
                if (loadingPercent < LOADING_PASS_PERCENT || singleBps * 100 >= needBps * START_SINGLE_ENOUGH_PERCENT) {
                    startJudged = true
                    lastChange = "start not needed $detail loading=$loadingPercent%"
                } else {
                    singleRateBps = singleBps
                    startTrial(s, start, TRIAL_MS, "start $detail" + budgetTag(s))
                    trialNeedRate = needBps
                    return downloads
                }
            }
        }

        val starving = starving(s)
        if (starving) {
            comfortableSinceMs = -1L
            // Short again after handing one back: straight back to what this film needed, not one at a time.
            val ceiling = minOf(maxDownloads, roomCap)
            val target = if (keepForFilm && peakKept > downloads + 1) peakKept.coerceAtMost(ceiling) else downloads + 1
            val needBytes = lentBytes(target, pieceBytes) - lentBytes(downloads, pieceBytes)
            val idle = s.idleBytes.coerceAtLeast(0L)
            val usable = if (s.userBudget || s.roomBytes > Long.MAX_VALUE - idle) Long.MAX_VALUE else s.roomBytes + idle
            val wanted = downloads < ceiling && s.nowMs >= nextStepAtMs
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
                if (downloads == 1 && baseline > 0L) singleRateBps = baseline
                startTrial(s, target, length,
                    "trial baselineKbps=${baseline * 8 / 1000}" + (if (early) " early" else "") + budgetTag(s))
            }
            return downloads
        }

        if (downloads > comfortableFloor(s) && !keepForFilm && comfortable(s)) {
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
        trialHeavy = false
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
        // A resting loader only vouches for a try that is not slower than before it.
        val waited = loadingShare(from, s.nowMs) < LOADING_PASS_PERCENT &&
            (trialBaselineBps <= 0L || trialBps >= trialBaselineBps)
        val faster = if (trialBaselineBps > 0L) {
            trialBps * 100 >= trialBaselineBps * (100 + GAIN_PERCENT)
        } else {
            trialNeedRate > 0L && trialBps >= trialNeedRate
        }
        trialStartMs = -1L
        val detail = "kbps=${trialBps * 8 / 1000} baselineKbps=${trialBaselineBps * 8 / 1000}"
        val heavy = trialHeavy
        trialHeavy = false
        // A remux start stays: one download was never measured, and the memory and comfort rules still hand back.
        if (faster || grew || waited || heavy) {
            if (downloads > 1 && trialBps > 0L) perDownloadBps = trialBps / downloads
            peakKept = maxOf(peakKept, downloads)
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

    /**
     * The fewest downloads a comfortable buffer hands back to: 2 while one download, measured alone or as its share of
     * a kept try, carries under [START_SINGLE_ENOUGH_PERCENT] % of the film's rate, or nothing was measured for a
     * remux; else 1.
     */
    private fun comfortableFloor(s: Sample): Int {
        if (s.fileBitrate <= 0L) return 1
        val one = if (singleRateBps > 0L) singleRateBps else if (perDownloadBps > 0L) perDownloadBps else knownPerDownloadBps
        // Nothing measured yet: a remux is assumed too heavy for one download, as its start already assumed.
        if (one <= 0L) return if (s.fileBitrate >= START_THREE_BPS) 2 else 1
        return if (one * 100 < s.fileBitrate / 8 * START_SINGLE_ENOUGH_PERCENT) 2 else 1
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
         * Memory stops the extra downloads only once the box is past its own low-memory line, or close to it while
         * Android says memory is critical. Android's earlier warnings are left to the buffer: Fire OS sends them to
         * every app with hundreds of MB free and closes idle apps on its own to make room. [warned] is null without a
         * buffer keeper; then Android's [lowMemory] decides.
         */
        fun memoryShort(warned: Boolean?, warnedCritical: Boolean, roomBytes: Long, lowMemory: Boolean): Boolean {
            if (warned == null) return lowMemory
            return roomBytes < -AutoSpeedup.MARGIN_BYTES || (warnedCritical && roomBytes < 0L)
        }

        /**
         * Pieces the chunk session may fetch beyond one per download, from free memory read just now while the
         * [current] ones are already held: they keep the downloads busy while the player's buffer is full and cover
         * dips. Grows by up to [READ_AHEAD_STEP] while more than [READ_AHEAD_KEEP_BYTES] stays free, so the server
         * never sees a burst of requests, shrinks below it, never past
         * [maxBytes] in all (the box's share, [READ_AHEAD_MAX_BYTES] on a 2 GB box) or 3 per download.
         */
        fun readAheadPieces(
            current: Int,
            roomBytes: Long,
            pieceBytes: Long,
            downloads: Int,
            maxBytes: Long = READ_AHEAD_MAX_BYTES
        ): Int {
            if (downloads <= 1 || pieceBytes <= 0L) return 0
            val spare = roomBytes - READ_AHEAD_KEEP_BYTES
            val step = if (spare >= 0L) (spare / pieceBytes).toInt() else -(((-spare) + pieceBytes - 1) / pieceBytes).toInt()
            val max = minOf((maxBytes / pieceBytes).toInt() - downloads, downloads * 3).coerceAtLeast(0)
            return (current + minOf(step, READ_AHEAD_STEP)).coerceIn(0, max)
        }

        /**
         * [roomBytes] lowered while seek thumbnails are generating, so the read-ahead leaves [thumbFloorBytes] of
         * MemAvailable ([availBytes], below zero when unknown) for their decodes.
         */
        fun readAheadRoom(roomBytes: Long, availBytes: Long, thumbFloorBytes: Long): Long =
            if (thumbFloorBytes <= 0L || availBytes < 0L) roomBytes else minOf(roomBytes, availBytes - thumbFloorBytes)

        const val READ_AHEAD_KEEP_BYTES = 25L * MB
        const val READ_AHEAD_STEP = 2
        const val READ_AHEAD_MAX_BYTES = 112L * MB

        /**
         * Downloads a film starts on from its average bitrate. One long download on a TV box's network carried about
         * 30 to 85 Mbit/s in the box tests, enough for 1080p and streaming 4K (under 40) but not for a 4K remux.
         * Never above 3; more are added one at a time while the film runs short. On a [smallBox] (2 GB) the start
         * holds no more than two 16 MB downloads did ([SMALL_BOX_START_BYTES]), since three 16 MB downloads ran the
         * Fire Stick short of memory: 3 with 8 MB pieces, 2 with 16 MB. When this server's rate per download is known
         * ([perDownloadBps]), the film starts on as many as carry [START_SINGLE_ENOUGH_PERCENT] % of its rate, up to
         * [maxDownloads] (a 2 GB box still within [SMALL_BOX_START_BYTES]).
         */
        fun startDownloads(
            fileBitrate: Long,
            maxDownloads: Int,
            pieceBytes: Long = 0L,
            smallBox: Boolean = maxDownloads <= 4,
            perDownloadBps: Long = 0L
        ): Int {
            val known = perDownloadBps > 0L && fileBitrate > 0L
            val want = when {
                known -> ((fileBitrate / 8 * START_SINGLE_ENOUGH_PERCENT / 100 + perDownloadBps - 1) / perDownloadBps).toInt()
                fileBitrate >= START_THREE_BPS -> 3
                fileBitrate >= START_TWO_BPS -> 2
                else -> 1
            }
            val cap = if (smallBox) smallBoxStartCap(pieceBytes, if (known) maxDownloads else START_MAX)
                else if (known) maxDownloads else START_MAX
            return minOf(want, cap, maxDownloads).coerceAtLeast(1)
        }

        private fun smallBoxStartCap(pieceBytes: Long, most: Int): Int {
            if (pieceBytes <= 0L) return 2
            var n = most.coerceAtLeast(2)
            while (n > 2 && lentBytes(n, pieceBytes) > SMALL_BOX_START_BYTES) n--
            return n
        }

        const val SMALL_BOX_START_BYTES = 48L * MB
        const val START_TWO_BPS = 40_000_000L
        const val START_THREE_BPS = 60_000_000L
        const val START_MAX = 3
        /** One download carrying this share of the film's rate is enough; below it the film starts on more. */
        const val START_SINGLE_ENOUGH_PERCENT = 130
        const val START_SETTLE_MS = 2_000L
        const val START_MEASURE_MS = 4_000L
        /** Ahead within this of the Max buffer counts as full: the player tops up from 3 s below it. */
        const val FULL_SLACK_MS = 4_000L
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
        const val FAILED_BLOCK_MS = 30_000L
        const val MEMORY_BLOCK_MS = 15_000L
        const val COMFORTABLE_AHEAD_MS = 30_000L
        const val COMFORTABLE_IDLE_AHEAD_MS = 10_000L
        const val COMFORTABLE_MS = 60_000L
        /** Running short again this soon after a download was handed back: keep the count for the rest of the film. */
        const val KEEP_AFTER_DROP_MS = 180_000L
        const val HISTORY_MS = 40_000L
        const val SEEK_DROP_MS = 2_000L
    }
}
