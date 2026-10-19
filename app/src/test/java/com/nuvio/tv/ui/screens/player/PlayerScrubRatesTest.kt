package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScrubRatesTest {

    @Test
    fun tapMovesTenSecondsEitherWay() {
        assertEquals(10_000L, PlayerScrubRates.tapDeltaMs(forward = true))
        assertEquals(-10_000L, PlayerScrubRates.tapDeltaMs(forward = false))
    }

    @Test
    fun glideSpeed_startsSlowThenDoublesSmoothlyUpToTheCap() {
        assertEquals(30.0, PlayerScrubRates.glideSpeed(0L), 1e-9)
        assertEquals(30.0, PlayerScrubRates.glideSpeed(400L), 1e-9)
        assertEquals(30.0, PlayerScrubRates.glideSpeed(1_000L), 1e-9)
        assertEquals(60.0, PlayerScrubRates.glideSpeed(3_500L), 1e-9)
        assertEquals(120.0, PlayerScrubRates.glideSpeed(6_000L), 1e-9)
        assertEquals(240.0, PlayerScrubRates.glideSpeed(8_500L), 1e-9)
        assertEquals(480.0, PlayerScrubRates.glideSpeed(11_000L), 1e-9)
        assertEquals(480.0, PlayerScrubRates.glideSpeed(60_000L), 1e-9)
        var last = 0.0
        for (t in 0L..15_000L step 16L) {
            val v = PlayerScrubRates.glideSpeed(t)
            assertTrue(v >= last)
            last = v
        }
    }

    @Test
    fun glideSpeed_isAboutFiftyTimesAtThreeSeconds() {
        assertEquals(52.2, PlayerScrubRates.glideSpeed(3_000L), 0.1)
    }

    @Test
    fun tvRemote_startsTheSameAndStopsAtFourHundred() {
        for (t in listOf(0L, 1_000L, 3_500L, 6_000L, 8_500L)) {
            assertEquals(PlayerScrubRates.glideSpeed(t), PlayerScrubRates.glideSpeed(t, tvRemote = true), 1e-9)
        }
        assertEquals(400.0, PlayerScrubRates.glideSpeed(11_000L, tvRemote = true), 1e-9)
        assertEquals(400.0, PlayerScrubRates.glideSpeed(60_000L, tvRemote = true), 1e-9)
    }

    private fun reachAfter(holdMs: Long): Double {
        var position = 10_000.0          // the first press is a tap
        var t = 400L                     // the glide starts at the first key repeat
        while (t < holdMs) {
            position += PlayerScrubRates.glideSpeed(t) * 16
            t += 16
        }
        return position
    }

    @Test
    fun heldSeek_movesAboutOneMinuteInTwoSeconds() {
        val reach = reachAfter(2_000L)
        assertTrue("reach $reach", reach in 55_000.0..70_000.0)
    }

    @Test
    fun heldSeek_crossesATwoHourFilmInAboutTwentyThreeSeconds() {
        var t = 400L
        var position = 10_000.0
        while (position < 7_200_000.0) {
            position += PlayerScrubRates.glideSpeed(t) * 16
            t += 16
        }
        assertTrue("reached 2 h after $t ms", t in 21_000L..25_000L)
    }
}
