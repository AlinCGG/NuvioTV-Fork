package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Thumbnail lanes while the film plays give way to playback's buffer. */
class PlaybackHeadroomTest {
    private fun PlaybackHeadroom.playing(atMs: Long, aheadMs: Long, loading: Boolean = true): Boolean =
        note(atMs, playing = true, buffering = false, loading = loading, aheadMs = aheadMs, toEnd = false)

    @Test fun aComfortableBufferLeavesEveryLane() {
        val h = PlaybackHeadroom()
        assertFalse(h.playing(0L, 20_000L))
        assertEquals(ThumbFetchLanes.MAX, h.lanes)
        assertEquals(3, PlaybackHeadroom(max = 3).lanes)
    }

    @Test fun aLowBufferMeansNoneAtOnce() {
        val h = PlaybackHeadroom()
        h.playing(0L, 20_000L)
        assertTrue(h.playing(1_000L, 4_100L))
        assertEquals(0, h.lanes)
        assertEquals("buffer 4.1 s", h.why)
    }

    @Test fun bufferingMeansNone() {
        val h = PlaybackHeadroom()
        assertTrue(h.note(0L, playing = true, buffering = true, loading = true, aheadMs = 30_000L, toEnd = false))
        assertEquals(0, h.lanes)
        assertEquals("playback buffering", h.why)
    }

    @Test fun aBufferFallingFastWhileLoadingMeansNone() {
        val h = PlaybackHeadroom()
        h.playing(0L, 20_000L)
        h.playing(2_000L, 18_500L)
        h.playing(4_000L, 17_000L)
        assertEquals(ThumbFetchLanes.MAX, h.lanes)
        assertTrue(h.playing(5_000L, 15_900L))
        assertEquals(0, h.lanes)
        assertEquals("buffer 15.9 s, down 4.1 s in 6 s", h.why)
    }

    @Test fun aBufferDrainingWhilePlaybackDoesNotLoadIsNormal() {
        val h = PlaybackHeadroom()
        h.playing(0L, 20_000L, loading = false)
        h.playing(3_000L, 17_000L, loading = false)
        h.playing(5_000L, 15_000L, loading = false)
        assertEquals(ThumbFetchLanes.MAX, h.lanes)
        // Loading starts again: the drain before it is not counted.
        h.playing(6_000L, 14_000L)
        h.playing(8_000L, 13_000L)
        assertEquals(ThumbFetchLanes.MAX, h.lanes)
    }

    @Test fun aSlowFallWhileLoadingKeepsTheLanes() {
        val h = PlaybackHeadroom()
        for (i in 0..10) h.playing(i * 1_000L, 20_000L - i * 300L)
        assertEquals(ThumbFetchLanes.MAX, h.lanes)
    }

    @Test fun onlyFallsWithinTheWindowCount() {
        val h = PlaybackHeadroom()
        h.playing(0L, 20_000L)
        h.playing(7_000L, 17_000L)
        h.playing(10_000L, 15_500L)
        assertEquals(ThumbFetchLanes.MAX, h.lanes)
    }

    @Test fun stepsBackUpOneAtATimeOnceTheBufferIsBack() {
        val h = PlaybackHeadroom()
        h.playing(0L, 20_000L, loading = false)
        h.playing(1_000L, 3_000L, loading = false)
        assertEquals(0, h.lanes)
        assertFalse(h.playing(2_000L, 9_000L))
        assertEquals(0, h.lanes)
        assertTrue(h.playing(4_000L, 9_000L))
        assertEquals(1, h.lanes)
        h.playing(5_000L, 10_000L, loading = false)
        assertEquals(1, h.lanes)
        h.playing(7_000L, 10_000L, loading = false)
        assertEquals(2, h.lanes)
        // Trouble again: none at once.
        h.playing(7_500L, 5_000L, loading = false)
        assertEquals(0, h.lanes)
    }

    @Test fun aBufferJustAboveLowIsNotBackYet() {
        val h = PlaybackHeadroom()
        h.playing(0L, 3_000L)
        for (t in 1..10) h.playing(t * 3_000L, 7_000L, loading = false)
        assertEquals(0, h.lanes)
        h.playing(40_000L, 8_000L, loading = false)
        assertEquals(1, h.lanes)
    }

    @Test fun pausedOrBufferedToTheEndIsNotLimited() {
        val h = PlaybackHeadroom()
        h.playing(0L, 2_000L)
        assertTrue(h.note(1_000L, playing = false, buffering = false, loading = true, aheadMs = 2_000L, toEnd = false))
        assertEquals(ThumbFetchLanes.MAX, h.lanes)
        h.playing(2_000L, 2_000L)
        assertEquals(0, h.lanes)
        h.note(3_000L, playing = true, buffering = false, loading = false, aheadMs = 2_000L, toEnd = true)
        assertEquals(ThumbFetchLanes.MAX, h.lanes)
    }

    @Test fun secondsForTheLog() {
        assertEquals("4.1 s", PlaybackHeadroom.seconds(4_150L))
        assertEquals("0.0 s", PlaybackHeadroom.seconds(-5L))
        assertEquals("12.0 s", PlaybackHeadroom.seconds(12_000L))
    }
}
