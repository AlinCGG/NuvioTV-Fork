package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSpeedupPolicyTest {

    private var now = 0L
    private var bytes = 0L
    private var ahead = 5_000L
    private var rebuffers = 0
    private val mb = 1024L * 1024L
    private val log = mutableListOf<String>()

    /** Plays [seconds] at [kbps] delivered, half-second samples, buffer moving by [aheadPerSecondMs]. */
    private fun AutoSpeedupPolicy.play(
        seconds: Int,
        kbps: Long,
        loading: Boolean = true,
        aheadPerSecondMs: Long = 0L,
        memoryShort: Boolean = false,
        playing: Boolean = true,
        bufferFull: Boolean = false,
        multiActive: Boolean = true,
        roomMb: Long? = null,
        swapLow: Boolean = false
    ): Int {
        repeat(seconds * 2) {
            now += 500L
            bytes += kbps * 1000L / 8L / 2L
            ahead = (ahead + aheadPerSecondMs / 2L).coerceAtLeast(0L)
            onSample(
                AutoSpeedupPolicy.Sample(
                    now, ahead, loading, rebuffers, bytes, memoryShort, playing, bufferFull, multiActive,
                    roomBytes = roomMb?.let { it * mb } ?: Long.MAX_VALUE,
                    swapLow = swapLow
                )
            )
            lastChange?.let { log += it }
        }
        return downloads
    }

    @Test
    fun `steady film with a growing buffer never adds a download`() {
        val policy = AutoSpeedupPolicy(3)
        assertEquals(1, policy.play(60, 80_000, aheadPerSecondMs = 200))
    }

    @Test
    fun `starving film tries a second download`() {
        val policy = AutoSpeedupPolicy(3)
        assertEquals(2, policy.play(9, 30_000))
    }

    @Test
    fun `second download is kept when it is 15 percent faster`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000)
        assertEquals(2, policy.play(11, 36_000))
    }

    @Test
    fun `second download is handed back when it does not help`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000)
        assertEquals(1, policy.play(11, 32_000))
        // Blocked for two minutes after a failed try, even while still short.
        assertEquals(1, policy.play(60, 30_000))
    }

    @Test
    fun `a growing buffer during the try counts as a gain`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000)
        assertEquals(2, policy.play(11, 30_000, aheadPerSecondMs = 400))
    }

    @Test
    fun `keeps climbing while each step helps, up to the box limit`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 20_000)
        assertEquals(2, policy.play(11, 30_000))
        assertEquals(3, policy.play(11, 40_000))
        assertEquals(3, policy.play(30, 40_000))
    }

    @Test
    fun `a rebuffer starts a try even with buffer left`() {
        val policy = AutoSpeedupPolicy(3)
        ahead = 20_000L
        policy.play(9, 60_000, aheadPerSecondMs = 100)
        rebuffers = 1
        assertEquals(2, policy.play(1, 60_000))
    }

    @Test
    fun `memory pressure goes straight back to one download`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000)
        policy.play(11, 40_000)
        assertEquals(1, policy.play(1, 40_000, memoryShort = true))
    }

    @Test
    fun `comfortable buffer hands a download back after a minute`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000)
        policy.play(11, 40_000)
        ahead = 35_000L
        assertEquals(2, policy.play(50, 40_000, loading = false))
        assertEquals(1, policy.play(15, 40_000, loading = false))
    }

    @Test
    fun `an idle loader with little buffer ahead keeps the download`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000)
        policy.play(11, 40_000)
        ahead = 5_000L
        assertEquals(2, policy.play(120, 40_000, loading = false))
        ahead = 12_000L
        assertEquals(1, policy.play(65, 40_000, loading = false))
    }

    @Test
    fun `running short soon after a hand-back keeps the count for the film`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000)
        policy.play(11, 40_000)
        ahead = 35_000L
        policy.play(65, 40_000, loading = false)
        ahead = 5_000L
        policy.play(9, 30_000)
        policy.play(11, 40_000)
        ahead = 35_000L
        assertEquals(2, policy.play(120, 40_000, loading = false))
    }

    @Test
    fun `never above the limit`() {
        val policy = AutoSpeedupPolicy(2)
        policy.play(9, 20_000)
        policy.play(11, 40_000)
        assertEquals(2, policy.play(30, 40_000))
    }

    @Test
    fun `a seek that empties the buffer is not taken for a film running short`() {
        val policy = AutoSpeedupPolicy(3)
        ahead = 14_000L
        policy.play(20, 80_000, loading = false)
        ahead = 0L
        assertEquals(1, policy.play(12, 80_000, aheadPerSecondMs = 500))
    }

    @Test
    fun `a try during an outage is not kept`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 0)
        assertEquals(1, policy.play(11, 0))
    }

    @Test
    fun `a buffer growing only because playback stopped is not a gain`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000)
        assertEquals(1, policy.play(11, 30_000, aheadPerSecondMs = 1_000, playing = false))
    }

    @Test
    fun `a full buffer is never taken for a film running short`() {
        val policy = AutoSpeedupPolicy(3)
        assertEquals(1, policy.play(30, 60_000, bufferFull = true))
    }

    @Test
    fun `the try is timed from the switch, not from the decision`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000, multiActive = false)
        // Still waiting for the piece boundary: slower, but not judged yet.
        assertEquals(2, policy.play(8, 20_000, multiActive = false))
        assertEquals(2, policy.play(11, 40_000))
    }

    @Test
    fun `a switch that never happens is given up`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000, multiActive = false)
        assertEquals(1, policy.play(31, 30_000, multiActive = false))
    }

    @Test
    fun `slow links get a longer try`() {
        // 32 MB pieces at 30 Mbit/s: two pieces take about 17 s, so the try runs about 20 s.
        val policy = AutoSpeedupPolicy(3, pieceBytes = 32L * 1024 * 1024)
        policy.play(9, 30_000)
        assertEquals(2, policy.play(15, 30_000))
        assertEquals(1, policy.play(10, 30_000))
    }

    @Test
    fun `no second download without room for its pieces`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        // The first extra download holds three 16 MB pieces.
        assertEquals(1, policy.play(30, 30_000, roomMb = 47))
        assertEquals(2, policy.play(1, 30_000, roomMb = 48))
    }

    @Test
    fun `each further download needs room for one more piece`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        policy.play(9, 80_000, roomMb = 200)
        assertEquals(2, policy.play(11, 100_000, roomMb = 200))
        assertEquals(2, policy.play(11, 100_000, roomMb = 15))
        assertEquals(3, policy.play(1, 100_000, roomMb = 16))
    }

    @Test
    fun `below the low-memory line a download is handed back one at a time`() {
        val policy = AutoSpeedupPolicy(3, pieceBytes = 16L * mb)
        policy.play(9, 80_000, roomMb = 500)
        policy.play(11, 100_000, roomMb = 500)
        assertEquals(3, policy.play(12, 125_000, roomMb = 500))
        policy.play(3, 125_000, roomMb = 500)
        assertEquals(2, policy.play(1, 125_000, roomMb = -1))
        assertEquals(1, policy.play(3, 125_000, roomMb = -1))
    }

    @Test
    fun `after a low-memory hand-back no try for a minute even with room again`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        policy.play(9, 80_000, roomMb = 500)
        assertEquals(1, policy.play(2, 80_000, roomMb = -1))
        assertEquals(1, policy.play(55, 80_000, roomMb = 500))
        assertEquals(2, policy.play(10, 80_000, roomMb = 500))
    }

    @Test
    fun `a box that stays short of memory never adds a download`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        assertEquals(1, policy.play(300, 20_000, roomMb = -60))
    }

    @Test
    fun `no new download while swap is nearly used up`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        assertEquals(1, policy.play(60, 30_000, roomMb = 500, swapLow = true))
        assertEquals(listOf("held downloads=1 swap low"), log)
        assertEquals(2, policy.play(1, 30_000, roomMb = 500))
    }

    @Test
    fun `low swap keeps the downloads already running`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        policy.play(9, 80_000, roomMb = 500)
        assertEquals(2, policy.play(11, 100_000, roomMb = 500))
        assertEquals(2, policy.play(30, 100_000, roomMb = 500, swapLow = true))
        assertEquals(3, policy.play(1, 100_000, roomMb = 500))
    }

    @Test
    fun `the swap hold is logged again after swap recovers`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        policy.play(20, 30_000, roomMb = -60, swapLow = true)
        assertEquals(emptyList<String>(), log)
        policy.play(5, 30_000, roomMb = 500, swapLow = true)
        policy.play(1, 30_000, roomMb = -60)
        policy.play(5, 30_000, roomMb = 500, swapLow = true)
        assertEquals(listOf("held downloads=1 swap low", "held downloads=1 swap low"), log)
    }
}
