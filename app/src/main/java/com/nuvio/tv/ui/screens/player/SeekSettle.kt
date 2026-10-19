package com.nuvio.tv.ui.screens.player

/**
 * A run of seek keys while the video plays. Playback holds where it is and every key goes on from the pending
 * target, so quick presses add up. The run ends with one seek [SETTLE_MS] after the last key, at once on OK, or
 * without a seek on Back.
 */
internal class SeekSettle {
    /** Playback position when the run began; null while no run is open. */
    var fromMs: Long? = null
        private set
    var presses = 0
        private set

    val open: Boolean get() = fromMs != null

    /** A seek key went down while playback stood at [playbackMs]. */
    fun press(playbackMs: Long) {
        if (fromMs == null) fromMs = playbackMs
        presses++
    }

    /** The last press turned out to be a TV's backlog after a hold, not a press of its own. */
    fun undoPress() {
        if (presses > 0) presses--
    }

    /** Closes the run; null when none was open. */
    fun end(): Run? {
        val from = fromMs ?: return null
        val run = Run(from, presses)
        fromMs = null
        presses = 0
        return run
    }

    class Run(val fromMs: Long, val presses: Int)

    companion object {
        const val SETTLE_MS = 2_000L

        /**
         * Where a step of [deltaMs] lands: from the pending target while one is open, else from where playback is.
         * [gridStep] snaps the step to the thumbnail grid when thumbnails are on.
         */
        fun target(
            pendingMs: Long?,
            playbackMs: Long,
            deltaMs: Long,
            durationMs: Long,
            gridStep: (Long, Long) -> Long? = { _, _ -> null }
        ): Long {
            val base = pendingMs ?: playbackMs.coerceAtLeast(0L)
            return (gridStep(base, deltaMs) ?: (base + deltaMs))
                .coerceAtLeast(0L)
                .coerceAtMost(durationMs)
        }
    }
}
