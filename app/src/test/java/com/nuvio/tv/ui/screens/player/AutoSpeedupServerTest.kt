package com.nuvio.tv.ui.screens.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSpeedupServerTest {

    private val mb = 1024L * 1024L

    @After
    fun tearDown() {
        AutoSpeedup.end()
        HostSpeeds.clearForTest()
    }

    @Test
    fun `the real server behind an add-on link sets the start`() {
        HostSpeeds.put("japn.tb-cdn.pw", HostSpeeds.Entry(50_000_000L / 8, 3, 300L, System.currentTimeMillis()))
        val link = "https://addon.example.org/playback/abc"
        AutoSpeedup.startHint(link, 96_000_000L)
        AutoSpeedup.begin(link, 1_900L * mb)
        assertEquals(5, AutoSpeedup.downloads)
        AutoSpeedup.onResolved("https://nexus-115.japn.tb-cdn.pw/dl/file.mkv")
        assertEquals(3, AutoSpeedup.downloads)
    }

    @Test
    fun `an unknown real server keeps the start`() {
        val link = "https://addon.example.org/playback/abc"
        AutoSpeedup.startHint(link, 96_000_000L)
        AutoSpeedup.begin(link, 1_900L * mb)
        AutoSpeedup.onResolved("https://nexus-115.zafr.tb-cdn.to/dl/file.mkv")
        assertEquals(5, AutoSpeedup.downloads)
    }

    @Test
    fun `a start that was already judged is not set again`() {
        val policy = AutoSpeedupPolicy(6, 8L * mb, smallBox = true)
        policy.preset(5)
        assertTrue(policy.restartStart(3))
        assertEquals(3, policy.downloads)
        assertFalse(policy.restartStart(3))
    }
}
