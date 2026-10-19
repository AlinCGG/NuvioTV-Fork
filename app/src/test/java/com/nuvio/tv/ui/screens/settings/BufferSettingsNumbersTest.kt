package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferSettingsNumbersTest {
    private val gb = 1024L * 1024L * 1024L

    @Test
    fun `the seconds line shows what the size holds of an 80 Mbit s remux`() {
        assertEquals(15, BufferSettingsNumbers.secondsHint(150, 280))
        assertEquals(8, BufferSettingsNumbers.secondsHint(86, 45))
        assertEquals(100, BufferSettingsNumbers.secondsHint(1000, 280))
        assertEquals(12, BufferSettingsNumbers.secondsHint(125, 45))
    }

    @Test
    fun `the seconds line stays away when the setting is not longer than the size holds`() {
        assertNull(BufferSettingsNumbers.secondsHint(1000, 45))
        assertNull(BufferSettingsNumbers.secondsHint(150, 15))
        assertNull(BufferSettingsNumbers.secondsHint(2000, 120))
    }

    @Test
    fun `the back buffer note shows what the player keeps inside the size`() {
        // Native memory preset: 12 s back buffer, 200 s minimum.
        assertEquals(BufferSettingsNumbers.BackBufferKept(5, 50), BufferSettingsNumbers.backBufferKept(12_000, 150, true, 200_000))
        assertEquals(BufferSettingsNumbers.BackBufferKept(12, 120), BufferSettingsNumbers.backBufferKept(12_000, 1000, true, 200_000))
        assertEquals(BufferSettingsNumbers.BackBufferKept(1, 12), BufferSettingsNumbers.backBufferKept(12_000, 62, true, 200_000))
        assertEquals(BufferSettingsNumbers.BackBufferKept(3, 33), BufferSettingsNumbers.backBufferKept(12_000, 100, true, 200_000))
        // Custom buffers without native memory on a 2 GB box with 2 x 16 MB chunks: 150 - 64 = 86 MB.
        assertEquals(BufferSettingsNumbers.BackBufferKept(3, 29), BufferSettingsNumbers.backBufferKept(5_000, 86, false, 15_000))
        // A short back buffer that fits stays as set.
        assertEquals(BufferSettingsNumbers.BackBufferKept(5, 50), BufferSettingsNumbers.backBufferKept(5_000, 1000, false, 15_000))
    }

    @Test
    fun `with native memory the back buffer is also held to half the minimum buffer`() {
        assertEquals(BufferSettingsNumbers.BackBufferKept(8, 75), BufferSettingsNumbers.backBufferKept(12_000, 1000, true, 15_000))
        assertEquals(BufferSettingsNumbers.BackBufferKept(12, 120), BufferSettingsNumbers.backBufferKept(12_000, 1000, false, 15_000))
    }

    @Test
    fun `nothing is kept at the smallest size`() {
        assertNull(BufferSettingsNumbers.backBufferKept(12_000, 50, true, 200_000))
        assertNull(BufferSettingsNumbers.backBufferKept(0, 1000, true, 200_000))
    }

    @Test
    fun `the stutter line follows the automatic size`() {
        assertEquals(150, BufferSettingsNumbers.stutterLimitMb(true, true, 150, 250))
        assertEquals(75, BufferSettingsNumbers.stutterLimitMb(false, true, 86, 175))
        assertEquals(250, BufferSettingsNumbers.stutterLimitMb(true, false, 150, 250))
    }

    @Test
    fun `the buffer's own colour leaves an automatic size alone`() {
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.bufferStatus(150, true, 150, 325))
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.bufferStatus(150, false, 150, 325))
        assertEquals(MemoryUsageStatus.WARNING, BufferSettingsNumbers.bufferStatus(250, false, 150, 325))
        assertEquals(MemoryUsageStatus.DANGER, BufferSettingsNumbers.bufferStatus(400, false, 150, 325))
        // Managed custom buffers: the buffer is the automatic size less the chunks.
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.bufferStatus(86, true, 75, 225))
    }

    private val fireStick4kMax = 1_710_032L * 1024L
    private val box3gb = 2_936_000L * 1024L
    private val am9 = 3_801_816L * 1024L

    private fun card(bufferMb: Int, automatic: Boolean, stutterMb: Int, dangerMb: Int, totalMb: Int, ram: Long) =
        BufferSettingsNumbers.cardStatus(
            BufferSettingsNumbers.bufferStatus(bufferMb, automatic, stutterMb, dangerMb),
            BufferSettingsNumbers.totalStatus(totalMb, ram)
        )

    @Test
    fun `default settings stay green on 2, 3 and 4 GB boxes`() {
        // Fire Stick 4K Max: 150 MB buffer and thumbnails, with and without native memory and Custom network 2 x 16 MB.
        assertEquals(MemoryUsageStatus.SAFE, card(150, false, 150, 325, 150 + 50, fireStick4kMax))
        assertEquals(MemoryUsageStatus.SAFE, card(150, true, 150, 325, 150 + 95, fireStick4kMax))
        assertEquals(MemoryUsageStatus.SAFE, card(150, true, 150, 325, 150 + 96 + 95, fireStick4kMax))
        assertEquals(MemoryUsageStatus.SAFE, card(86, true, 75, 225, 86 + 64 + 95, fireStick4kMax))
        // 3 GB: 500 MB automatic buffer, thumbnails, Custom network 4 x 16 MB.
        assertEquals(MemoryUsageStatus.SAFE, card(500, true, 500, 650, 500 + 125, box3gb))
        assertEquals(MemoryUsageStatus.SAFE, card(500, true, 500, 650, 500 + 160 + 125, box3gb))
        // 4 GB: 1000 MB automatic buffer, thumbnails, Custom network 4 x 16 MB.
        assertEquals(MemoryUsageStatus.SAFE, card(1000, true, 1000, 1200, 1000 + 125, am9))
        assertEquals(MemoryUsageStatus.SAFE, card(1000, true, 1000, 1200, 1000 + 160 + 125, am9))
    }

    @Test
    fun `16 connections of 32 MB on a 4 GB box turn the card red`() {
        assertEquals(MemoryUsageStatus.DANGER, card(1000, true, 1000, 1200, 1000 + 1088 + 125, am9))
        assertEquals(MemoryUsageStatus.DANGER, BufferSettingsNumbers.totalStatus(2213, am9))
    }

    @Test
    fun `an automatic buffer still counts at its shown size in the total`() {
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.bufferStatus(1000, true, 1000, 1200))
        // 16 x 16 MB beside the 1000 MB automatic buffer.
        assertEquals(MemoryUsageStatus.WARNING, card(1000, true, 1000, 1200, 1000 + 544 + 125, am9))
        // Fire Stick, 150 MB automatic buffer with 224 MB of downloads and 4K thumbnails.
        assertEquals(MemoryUsageStatus.WARNING, card(150, true, 150, 325, 150 + 224 + 95, fireStick4kMax))
    }

    @Test
    fun `the total sets the amber and red lines by the box's memory`() {
        // Fire Stick 4K Max: 869 MB beyond the system share.
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.totalStatus(434, fireStick4kMax))
        assertEquals(MemoryUsageStatus.WARNING, BufferSettingsNumbers.totalStatus(435, fireStick4kMax))
        assertEquals(MemoryUsageStatus.WARNING, BufferSettingsNumbers.totalStatus(608, fireStick4kMax))
        assertEquals(MemoryUsageStatus.DANGER, BufferSettingsNumbers.totalStatus(609, fireStick4kMax))
        // 3 GB: 2067 MB beyond it.
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.totalStatus(1033, box3gb))
        assertEquals(MemoryUsageStatus.WARNING, BufferSettingsNumbers.totalStatus(1034, box3gb))
        assertEquals(MemoryUsageStatus.DANGER, BufferSettingsNumbers.totalStatus(1447, box3gb))
        // AM9: 2912 MB beyond it.
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.totalStatus(1456, am9))
        assertEquals(MemoryUsageStatus.WARNING, BufferSettingsNumbers.totalStatus(1457, am9))
        assertEquals(MemoryUsageStatus.WARNING, BufferSettingsNumbers.totalStatus(2038, am9))
        assertEquals(MemoryUsageStatus.DANGER, BufferSettingsNumbers.totalStatus(2039, am9))
        // Unknown memory is taken as 2 GB.
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.totalStatus(624, 0L))
        assertEquals(MemoryUsageStatus.WARNING, BufferSettingsNumbers.totalStatus(625, 0L))
    }

    @Test
    fun `a user buffer past its own lines keeps the card at least that colour`() {
        assertEquals(MemoryUsageStatus.DANGER, card(400, false, 150, 325, 400 + 95, fireStick4kMax))
        assertEquals(MemoryUsageStatus.WARNING, card(1100, false, 1000, 1200, 1100 + 125, am9))
        assertEquals(MemoryUsageStatus.DANGER, card(1100, false, 1000, 1200, 1100 + 1088, am9))
    }

    @Test
    fun `the connection count line is for boxes with 2 GB or less above 2 connections`() {
        assertTrue(BufferSettingsNumbers.connectionCountTooHigh((1.9 * gb).toLong(), 4))
        assertTrue(BufferSettingsNumbers.connectionCountTooHigh((1.9 * gb).toLong(), 3))
        assertFalse(BufferSettingsNumbers.connectionCountTooHigh((1.9 * gb).toLong(), 2))
        assertFalse(BufferSettingsNumbers.connectionCountTooHigh((3.8 * gb).toLong(), 4))
        assertFalse(BufferSettingsNumbers.connectionCountTooHigh(0L, 4))
        assertTrue(BufferSettingsNumbers.connectionCountTooHigh((1.9 * gb).toLong(), 16))
        assertTrue(BufferSettingsNumbers.connectionCountTooHigh((1.9 * gb).toLong(), 8))
        assertFalse(BufferSettingsNumbers.connectionCountTooHigh((3.8 * gb).toLong(), 16))
    }

    @Test
    fun `custom buffers without native memory stop at 1600 MB`() {
        assertEquals(1600, BufferSettingsNumbers.CUSTOM_ENGINE_MAX_MB)
    }
}
