package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One progressive stream with Custom network off: a single download while [downloads] says 1, the chunk session
 * (limited live to [downloads]) while it says more. The path changes between reads, only on a piece boundary so the
 * chunk session starts on a fresh piece instead of fetching again what was already read, and only after a long
 * sequential run, so a short side read never changes path. The new path is opened at the boundary ahead of time, so
 * its first piece downloads while the old path finishes the current one. [received] counts what arrives on the
 * single path; the chunk session counts its own. [multiOpen] counts instances reading from the chunk session.
 */
@UnstableApi
internal class AutoSpeedupDataSource(
    private val single: DataSource.Factory,
    private val multi: DataSource.Factory,
    private val pieceBytes: () -> Long,
    private val downloads: () -> Int,
    private val received: AtomicLong,
    private val onMultiClosed: () -> Unit = {},
    private val multiOpen: AtomicInteger = AtomicInteger(),
    private val onFirstByte: (Long) -> Unit = {}
) : DataSource {
    private class Pending(val multi: Boolean, val atRead: Long, val source: DataSource, val opened: Future<*>)

    private val listeners = mutableListOf<TransferListener>()
    private var spec: DataSpec? = null
    private var current: DataSource? = null
    private var multiActive = false
    private var readThisOpen = 0L
    private var switchAfter = MIN_RUN_BYTES
    private var pending: Pending? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        dropPending()
        spec = dataSpec
        readThisOpen = 0L
        switchAfter = MIN_RUN_BYTES
        val wantMulti = downloads() > 1
        if (!wantMulti) onMultiClosed()
        val source = create(wantMulti)
        current = source
        val startNs = System.nanoTime()
        val length = source.open(dataSpec)
        if (!wantMulti) onFirstByte((System.nanoTime() - startNs) / 1_000_000L)
        setMulti(wantMulti)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val toBoundary = maybeSwitch()
        val source = current ?: throw IOException("Not open")
        val capped = if (toBoundary in 1L until length.toLong()) toBoundary.toInt() else length
        val read = source.read(buffer, offset, capped)
        if (read > 0) {
            readThisOpen += read
            if (!multiActive) received.addAndGet(read.toLong())
        }
        return read
    }

    /** Switches when on a piece boundary; otherwise returns the bytes left to the next one (0 = no switch wanted). */
    private fun maybeSwitch(): Long {
        val wantMulti = downloads() > 1
        if (wantMulti == multiActive) {
            dropPending()
            return 0L
        }
        if (readThisOpen < switchAfter) return 0L
        val open = spec ?: return 0L
        val position = open.position + readThisOpen
        val piece = pieceBytes()
        val toBoundary = (piece - position % piece) % piece
        val boundaryRead = readThisOpen + toBoundary
        if (open.length != C.LENGTH_UNSET.toLong() && boundaryRead >= open.length) return 0L
        var next = pending
        if (next != null && (next.multi != wantMulti || next.atRead != boundaryRead)) {
            dropPending()
            next = null
        }
        if (next == null) {
            val source = create(wantMulti)
            val nextSpec = open.subrange(boundaryRead)
            next = Pending(wantMulti, boundaryRead, source, OPENER.submit { source.open(nextSpec) })
            pending = next
        }
        if (toBoundary > 0L) return toBoundary
        pending = null
        try {
            next.opened.get()
        } catch (e: Exception) {
            Log.w(TAG, "AUTO_SPEEDUP: switch to ${pathName(wantMulti)} failed, staying", e)
            runCatching { next.source.close() }
            switchAfter = readThisOpen + RETRY_AFTER_BYTES
            return 0L
        }
        val old = current
        val oldWasMulti = multiActive
        current = next.source
        spec = open.subrange(readThisOpen)
        readThisOpen = 0L
        switchAfter = MIN_RUN_BYTES
        setMulti(wantMulti)
        Log.i(TAG, "AUTO_SPEEDUP: path=${pathName(wantMulti)} at=$position")
        runCatching { old?.close() }
        if (oldWasMulti) onMultiClosed()
        return 0L
    }

    /** A path opened ahead that is no longer wanted is closed after its open finishes, on the same thread. */
    private fun dropPending() {
        val stale = pending ?: return
        pending = null
        OPENER.execute { runCatching { stale.opened.get() }; runCatching { stale.source.close() } }
    }

    private fun setMulti(on: Boolean) {
        if (on != multiActive) {
            if (on) multiOpen.incrementAndGet() else multiOpen.decrementAndGet()
        }
        multiActive = on
    }

    private fun create(multiPath: Boolean): DataSource =
        (if (multiPath) multi else single).createDataSource()
            .also { source -> listeners.forEach(source::addTransferListener) }

    private fun pathName(multiPath: Boolean) = if (multiPath) "multi" else "single"

    override fun getUri(): Uri? = current?.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        dropPending()
        val source = current
        val wasMulti = multiActive
        current = null
        setMulti(false)
        try {
            source?.close()
        } finally {
            if (wasMulti && downloads() <= 1) onMultiClosed()
        }
    }

    class Factory(
        private val single: DataSource.Factory,
        private val multi: DataSource.Factory,
        private val pieceBytes: () -> Long,
        private val downloads: () -> Int,
        private val received: AtomicLong,
        private val onMultiClosed: () -> Unit = {},
        private val multiOpen: AtomicInteger = AtomicInteger(),
        private val onFirstByte: (Long) -> Unit = {}
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            AutoSpeedupDataSource(single, multi, pieceBytes, downloads, received, onMultiClosed, multiOpen, onFirstByte)
    }

    companion object {
        private const val TAG = "AutoSpeedup"
        const val MIN_RUN_BYTES = 4L * 1024L * 1024L
        const val RETRY_AFTER_BYTES = 16L * 1024L * 1024L
        private val OPENER = Executors.newSingleThreadExecutor { r ->
            Thread(r, "auto-speedup-open").apply { isDaemon = true }
        }
    }
}
