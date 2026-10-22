package com.nuvio.tv.core.player.thumbnail

/**
 * Thumbnail fetch lanes while the film plays, by playback's buffer. None at once while it buffers, when the seconds
 * ahead fall below [LOW_AHEAD_MS], or when they fall [FAST_FALL_MS] within [FALL_WINDOW_MS] while playback is loading
 * (its downloads are not keeping up); then one more every [STEP_UP_MS] once at least [BACK_AHEAD_MS] are ahead again.
 * Paused or buffered to the end: no limit from here.
 */
internal class PlaybackHeadroom(private val max: Int = ThumbFetchLanes.MAX) {
    companion object {
        const val LOW_AHEAD_MS = 6_000L
        const val BACK_AHEAD_MS = 8_000L
        const val FALL_WINDOW_MS = 6_000L
        const val FAST_FALL_MS = 4_000L
        const val STEP_UP_MS = 3_000L
        private const val NONE = Long.MIN_VALUE

        fun seconds(ms: Long): String = ms.coerceAtLeast(0L).let { "${it / 1000}.${it % 1000 / 100} s" }
    }

    var lanes = max
        private set

    /** For the log: what the last sample said. */
    var why = "buffer not seen yet"
        private set

    private val times = ArrayDeque<Long>()
    private val aheads = ArrayDeque<Long>()
    private var changedAtMs = NONE
    private var troubleAtMs = NONE

    /** One look at playback. True when [lanes] changed. */
    fun note(nowMs: Long, playing: Boolean, buffering: Boolean, loading: Boolean, aheadMs: Long, toEnd: Boolean): Boolean {
        val before = lanes
        if (loading && playing && !buffering) {
            times.addLast(nowMs)
            aheads.addLast(aheadMs)
            while (times.isNotEmpty() && nowMs - times.first() > FALL_WINDOW_MS) {
                times.removeFirst()
                aheads.removeFirst()
            }
        } else {
            times.clear()
            aheads.clear()
        }
        val fell = (aheads.maxOrNull() ?: aheadMs) - aheadMs
        when {
            buffering -> trouble(nowMs, "playback buffering")
            !playing -> {
                lanes = max
                why = "not playing"
            }
            toEnd -> {
                lanes = max
                why = "buffered to the end"
            }
            aheadMs < LOW_AHEAD_MS -> trouble(nowMs, "buffer ${seconds(aheadMs)}")
            fell >= FAST_FALL_MS ->
                trouble(nowMs, "buffer ${seconds(aheadMs)}, down ${seconds(fell)} in ${FALL_WINDOW_MS / 1000} s")
            else -> {
                why = "buffer ${seconds(aheadMs)}"
                if (lanes < max && aheadMs >= BACK_AHEAD_MS && since(troubleAtMs, nowMs) && since(changedAtMs, nowMs)) {
                    lanes++
                }
            }
        }
        if (lanes == before) return false
        changedAtMs = nowMs
        return true
    }

    private fun trouble(nowMs: Long, what: String) {
        lanes = 0
        why = what
        troubleAtMs = nowMs
    }

    private fun since(atMs: Long, nowMs: Long): Boolean = atMs == NONE || nowMs - atMs >= STEP_UP_MS
}
