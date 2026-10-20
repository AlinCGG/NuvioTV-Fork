package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class AutoSpeedupDataSourceTest {

    private val file = ByteArray(12 * 1024 * 1024) { (it * 31 + 7).toByte() }
    private val uri: Uri = mockk(relaxed = true)

    private inner class FakeSource(val name: String, val log: MutableList<String>, val fail: Boolean = false) : DataSource {
        private var pos = 0
        private var end = 0
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long {
            if (fail) throw IOException("refused")
            log += "$name@${dataSpec.position}"
            pos = dataSpec.position.toInt()
            end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) file.size else (pos + dataSpec.length).toInt()
            return (end - pos).toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= end) return C.RESULT_END_OF_INPUT
            val n = minOf(length, end - pos, 64 * 1024)
            System.arraycopy(file, pos, buffer, offset, n)
            pos += n
            return n
        }
        override fun getUri(): Uri? = null
        override fun close() { log += "$name closed" }
    }

    private fun readAll(source: DataSource, spec: DataSpec, onEachRead: (Long) -> Unit = {}): ByteArray {
        source.open(spec)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = source.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
            onEachRead(out.size().toLong())
        }
        source.close()
        return out.toByteArray()
    }

    @Test
    fun `switches to the multi path mid-run without losing or repeating a byte`() {
        val log = mutableListOf<String>()
        var downloads = 1
        val received = AtomicLong()
        var multiClosed = 0
        val source = AutoSpeedupDataSource(
            single = { FakeSource("single", log) },
            multi = { FakeSource("multi", log) },
            pieceBytes = { MB },
            downloads = { downloads },
            received = received,
            onMultiClosed = { multiClosed++ }
        )
        val bytes = readAll(source, DataSpec(uri)) { read ->
            downloads = when {
                read >= 9L * 1024 * 1024 -> 1
                read >= 1L * 1024 * 1024 -> 2
                else -> 1
            }
        }
        assertArrayEquals(file, bytes)
        // Single-path bytes only: the chunk session counts its own as they arrive.
        assertEquals(4 * MB + 3 * MB, received.get())
        // Only after a 4 MB run and on a piece boundary, both ways.
        assertEquals("single@0", log[0])
        assertEquals("multi@${4 * 1024 * 1024}", log[1])
        assertEquals("single closed", log[2])
        assertEquals("single@${9 * MB}", log[3])
        assertEquals("multi closed", log[4])
        // At the open on the single path, and when the multi path is left.
        assertEquals(2, multiClosed)
    }

    @Test
    fun `a failed switch keeps reading from the open path`() {
        val log = mutableListOf<String>()
        var downloads = 1
        val source = AutoSpeedupDataSource(
            single = { FakeSource("single", log) },
            multi = { FakeSource("multi", log, fail = true) },
            pieceBytes = { MB },
            downloads = { downloads },
            received = AtomicLong()
        )
        val bytes = readAll(source, DataSpec(uri)) { read -> if (read >= 1L * 1024 * 1024) downloads = 2 }
        assertArrayEquals(file, bytes)
        // One try, closed again; the next would wait another 16 MB, past the end of this file.
        assertEquals(listOf("single@0", "multi closed", "single closed"), log)
    }

    @Test
    fun `short side reads never switch`() {
        val log = mutableListOf<String>()
        var downloads = 1
        val source = AutoSpeedupDataSource(
            single = { FakeSource("single", log) },
            multi = { FakeSource("multi", log) },
            pieceBytes = { MB },
            downloads = { downloads },
            received = AtomicLong()
        )
        source.open(DataSpec.Builder().setUri(uri).setPosition(1000).setLength(200_000).build())
        downloads = 2
        val buf = ByteArray(64 * 1024)
        while (source.read(buf, 0, buf.size) != C.RESULT_END_OF_INPUT) Unit
        source.close()
        assertEquals(listOf("single@1000", "single closed"), log)
    }

    @Test
    fun `only opens on the single path report how long the server took`() {
        val log = mutableListOf<String>()
        var downloads = 1
        val times = mutableListOf<Long>()
        val source = AutoSpeedupDataSource(
            single = { FakeSource("single", log) },
            multi = { FakeSource("multi", log) },
            pieceBytes = { MB },
            downloads = { downloads },
            received = AtomicLong(),
            onFirstByte = { times += it }
        )
        source.open(DataSpec(uri))
        source.close()
        downloads = 2
        source.open(DataSpec(uri))
        source.close()
        assertEquals(1, times.size)
        assertEquals(true, times[0] >= 0L)
    }

    @Test
    fun `the switch waits for the next piece boundary`() {
        val log = mutableListOf<String>()
        var downloads = 1
        var soFar = 0L
        var soFarAtMultiOpen = -1L
        val source = AutoSpeedupDataSource(
            single = { FakeSource("single", log) },
            multi = { soFarAtMultiOpen = soFar; FakeSource("multi", log) },
            pieceBytes = { 3 * MB },
            downloads = { downloads },
            received = AtomicLong()
        )
        val bytes = readAll(source, DataSpec(uri)) { read -> soFar = read; if (read >= MB) downloads = 2 }
        assertArrayEquals(file, bytes)
        assertEquals(listOf("single@0", "multi@${6 * MB}", "single closed", "multi closed"), log)
        // Opened ahead, as soon as the 4 MB run was done, not when the reader reached the boundary.
        assertEquals(4 * MB, soFarAtMultiOpen)
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
