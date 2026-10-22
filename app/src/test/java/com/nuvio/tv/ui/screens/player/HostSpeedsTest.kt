package com.nuvio.tv.ui.screens.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostSpeedsTest {

    @After
    fun tearDown() = HostSpeeds.clearForTest()

    @Test
    fun `a numbered machine is left out so a CDN region shares what it learnt`() {
        assertEquals("japn.tb-cdn.pw", HostSpeeds.keyOf("https://nexus-115.japn.tb-cdn.pw/dl/a.mkv"))
        assertEquals("download.real-debrid.com", HostSpeeds.keyOf("https://syd4-4.download.real-debrid.com/d/x"))
        assertEquals("weur.tb-cdn.st", HostSpeeds.keyOf("https://store-059.weur.tb-cdn.st/x"))
        assertEquals("example.com", HostSpeeds.keyOf("https://example.com/a"))
        assertEquals("cdn.example.com", HostSpeeds.keyOf("https://cdn.example.com/a"))
        assertNull(HostSpeeds.keyOf("not a link"))
    }

    @Test
    fun `an entry survives being written and read back`() {
        val e = HostSpeeds.Entry(3_750_000L, 6, 730L, 1_000L)
        assertEquals(e, HostSpeeds.decode(HostSpeeds.encode(e)))
        assertNull(HostSpeeds.decode("1,2,3"))
        assertNull(HostSpeeds.decode("0,2,3,4"))
    }

    @Test
    fun `an entry older than the keep time is ignored`() {
        HostSpeeds.put("japn.tb-cdn.pw", HostSpeeds.Entry(10_000_000L, 4, 300L, 0L))
        assertEquals(4, HostSpeeds.get("japn.tb-cdn.pw", 1_000L)?.downloads)
        assertNull(HostSpeeds.get("japn.tb-cdn.pw", HostSpeeds.KEEP_MS))
    }
}
