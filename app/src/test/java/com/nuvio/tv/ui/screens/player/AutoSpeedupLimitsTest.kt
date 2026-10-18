package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.BufferSizing
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSpeedupLimitsTest {

    private val mb = 1024L * 1024L

    @Test
    fun `two gigabyte boxes go up to four`() {
        assertEquals(AutoSpeedup.Limits(4, 16L * mb), AutoSpeedup.limitsFor(1_900L * mb))
        assertEquals(4, AutoSpeedup.limitsFor(2_559L * mb).maxDownloads)
    }

    @Test
    fun `three gigabyte boxes go up to five`() {
        assertEquals(AutoSpeedup.Limits(5, 16L * mb), AutoSpeedup.limitsFor(2_560L * mb))
        assertEquals(AutoSpeedup.Limits(5, 16L * mb), AutoSpeedup.limitsFor(2_900L * mb))
    }

    @Test
    fun `four gigabyte boxes and up go up to six`() {
        assertEquals(AutoSpeedup.Limits(6, 16L * mb), AutoSpeedup.limitsFor(3_584L * mb))
        assertEquals(AutoSpeedup.Limits(6, 16L * mb), AutoSpeedup.limitsFor(3_800L * mb))
        assertEquals(AutoSpeedup.Limits(6, 16L * mb), AutoSpeedup.limitsFor(8_000L * mb))
    }

    @Test
    fun `unknown memory keeps one download`() {
        assertEquals(1, AutoSpeedup.limitsFor(0L).maxDownloads)
    }

    @Test
    fun `room is free memory above the low-memory line and the margin`() {
        val free = BufferSizing.FreeMemory(availBytes = 400L * mb, thresholdBytes = 144L * mb, lowMemory = false)
        assertEquals((400L - 144L - BufferSizing.SYSTEM_MARGIN_MB) * mb, AutoSpeedup.roomAboveFloor(free))
        val short = BufferSizing.FreeMemory(availBytes = 180L * mb, thresholdBytes = 144L * mb, lowMemory = false)
        assertEquals(true, AutoSpeedup.roomAboveFloor(short) < 0L)
        assertEquals(Long.MAX_VALUE, AutoSpeedup.roomAboveFloor(null))
    }

    @Test
    fun `lent memory is each download plus the piece being read`() {
        assertEquals(0L, AutoSpeedupPolicy.lentBytes(1, 16L * mb))
        assertEquals(48L * mb, AutoSpeedupPolicy.lentBytes(2, 16L * mb))
        assertEquals(80L * mb, AutoSpeedupPolicy.lentBytes(4, 16L * mb))
    }

    @Test
    fun `swap is low under 50 MB, only on boxes that have swap`() {
        assertEquals(true, AutoSpeedup.swapLow(swapTotalMb = 512L, swapFreeMb = 0L))
        assertEquals(true, AutoSpeedup.swapLow(swapTotalMb = 512L, swapFreeMb = 49L))
        assertEquals(false, AutoSpeedup.swapLow(swapTotalMb = 512L, swapFreeMb = 50L))
        assertEquals(false, AutoSpeedup.swapLow(swapTotalMb = 0L, swapFreeMb = 0L))
    }
}
