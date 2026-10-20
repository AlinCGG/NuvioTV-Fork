package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.player.BufferSizing
import com.nuvio.tv.core.player.thumbnail.MemoryGovernor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The Automatic speed-up for the stream that is playing: the policy, the bytes the player has received, and the
 * limits for this box. The memory the extra downloads hold is taken off the buffer budget while they run.
 */
internal object AutoSpeedup {
    private const val TAG = "AutoSpeedup"
    private const val MB = 1024L * 1024L
    private const val ROOM_READ_MS = 2_000L
    private const val SWAP_MIN_MB = 50L
    private const val MAX_FILE_BITRATE = 400_000_000L

    private const val QUICK_FIRST_BYTE_MS = 400L
    private const val FIRST_BYTE_SAMPLES = 2

    /** [quickHoldBytes] = the piece used instead of [holdBytes] when the server sends its first byte quickly. */
    data class Limits(val maxDownloads: Int, val holdBytes: Long, val quickHoldBytes: Long = holdBytes)

    /** Bytes received from the network for the stream that is playing, on either path. */
    val received = AtomicLong()

    /** Readers on the chunk session now; above 0 the extra downloads are really running. */
    val multiOpen = AtomicInteger()

    @Volatile
    private var url: String? = null

    @Volatile
    private var policy: AutoSpeedupPolicy? = null

    @Volatile
    var limits: Limits = limitsFor(0L)
        private set

    val downloads: Int get() = policy?.downloads ?: 1

    val active: Boolean get() = policy != null

    fun limitsFor(totalRamBytes: Long): Limits = when {
        totalRamBytes in 1 until 2_560L * MB -> Limits(maxDownloads = 4, holdBytes = 16L * MB, quickHoldBytes = 8L * MB)
        totalRamBytes in 1 until 3_584L * MB -> Limits(maxDownloads = 5, holdBytes = 16L * MB)
        totalRamBytes > 0L -> Limits(maxDownloads = 6, holdBytes = 16L * MB)
        else -> Limits(maxDownloads = 1, holdBytes = 16L * MB)
    }

    /** A slow first byte costs a share of every piece, so smaller pieces only pay off on a quick server. */
    fun pieceFor(limits: Limits, firstByteMs: Long): Long =
        if (firstByteMs in 0 until QUICK_FIRST_BYTE_MS) limits.quickHoldBytes else limits.holdBytes

    /** The piece size of the chunk session for this stream, fixed once the chunk session first opens. */
    @Volatile
    var pieceBytes: Long = limits.holdBytes
        private set

    private val pieceLock = Any()
    private var pieceFixed = true
    private var firstByteMs = -1L
    private var firstByteSamples = 0

    /** How long a request on the single download took to answer, while the piece size is still open. */
    fun onFirstByte(ms: Long) {
        synchronized(pieceLock) {
            if (pieceFixed || policy == null || ms < 0L) return
            firstByteMs = if (firstByteMs < 0L) ms else minOf(firstByteMs, ms)
            pieceBytes = pieceFor(limits, firstByteMs)
            policy?.pieceBytes = pieceBytes
            if (++firstByteSamples >= FIRST_BYTE_SAMPLES) fixPieceLocked()
        }
    }

    /** Fixes the piece size (the larger one while nothing was measured) and returns it. */
    fun fixPiece(): Long = synchronized(pieceLock) {
        if (!pieceFixed) fixPieceLocked()
        pieceBytes
    }

    private fun fixPieceLocked() {
        pieceFixed = true
        logOn()
    }

    private fun logOn() {
        val firstByte = if (firstByteMs >= 0L) " firstByteMs=$firstByteMs" else ""
        Log.i(TAG, "AUTO_SPEEDUP: on maxDownloads=${limits.maxDownloads} holdMb=${pieceBytes / MB}$firstByte")
    }

    @Volatile
    private var roomReadAtMs = Long.MIN_VALUE

    @Volatile
    private var room = Long.MAX_VALUE

    /** Free memory above Android's low-memory line and the system margin, read at most every [ROOM_READ_MS]. */
    fun roomBytes(nowMs: Long, read: () -> BufferSizing.FreeMemory?): Long {
        if (roomReadAtMs == Long.MIN_VALUE || nowMs - roomReadAtMs >= ROOM_READ_MS) {
            room = roomAboveFloor(read())
            roomReadAtMs = nowMs
        }
        return room
    }

    @Volatile
    private var swapReadAtMs = Long.MIN_VALUE

    @Volatile
    private var swapLowNow = false

    /** SwapFree under [SWAP_MIN_MB] on a box that has swap, read at most every [ROOM_READ_MS]. */
    fun swapLow(nowMs: Long, read: () -> MemoryGovernor.Meminfo?): Boolean {
        if (swapReadAtMs == Long.MIN_VALUE || nowMs - swapReadAtMs >= ROOM_READ_MS) {
            swapLowNow = read()?.let { swapLow(it.swapTotalMb, it.swapFreeMb) } ?: false
            swapReadAtMs = nowMs
        }
        return swapLowNow
    }

    /** A box without swap, or with unknown swap, is never held back. */
    fun swapLow(swapTotalMb: Long, swapFreeMb: Long): Boolean = swapTotalMb > 0L && swapFreeMb < SWAP_MIN_MB

    /** Unknown free memory never blocks a download. */
    fun roomAboveFloor(free: BufferSizing.FreeMemory?): Long =
        if (free == null) Long.MAX_VALUE
        else free.availBytes - free.thresholdBytes - BufferSizing.SYSTEM_MARGIN_MB * MB

    /** A new stream starts on one download; the same stream again (a subtitle reload) keeps what it learnt. */
    fun begin(streamUrl: String, totalRamBytes: Long) {
        if (policy != null && streamUrl == url) return
        end()
        limits = limitsFor(totalRamBytes)
        url = streamUrl
        received.set(0L)
        roomReadAtMs = Long.MIN_VALUE
        swapReadAtMs = Long.MIN_VALUE
        swapLowNow = false
        synchronized(pieceLock) {
            pieceBytes = limits.holdBytes
            firstByteMs = -1L
            firstByteSamples = 0
            policy = AutoSpeedupPolicy(limits.maxDownloads, pieceBytes)
            pieceFixed = limits.quickHoldBytes == limits.holdBytes
            if (pieceFixed) logOn()
        }
    }

    fun end() {
        val wasOn = policy != null
        policy = null
        url = null
        if (wasOn) ParallelRangeDataSource.releaseRetainedSessionIfIdle()
    }

    /**
     * The film's average bitrate in bits per second, from the size the server gave (else the add-on's) over the length;
     * 0 while either is unknown, or when the figures cannot both be right.
     */
    fun fileBitrate(serverBytes: Long?, addonBytes: Long?, durationMs: Long): Long {
        val bytes = serverBytes?.takeIf { it > 0L } ?: addonBytes
        val bps = PlayerBitrateEstimator.fileBitrateBps(bytes, durationMs)?.toLong() ?: return 0L
        return if (bps <= MAX_FILE_BITRATE) bps else 0L
    }

    /** Bytes the extra downloads may hold now: each one in flight plus the piece being read. */
    fun lentBytes(): Long = AutoSpeedupPolicy.lentBytes(downloads, pieceBytes)

    /** Returns true when the number of downloads changed. */
    fun onPlayerSample(sample: AutoSpeedupPolicy.Sample): Boolean {
        val p = policy ?: return false
        val before = p.downloads
        p.onSample(sample.copy(bytes = received.get(), multiActive = multiOpen.get() > 0))
        p.lastChange?.let { Log.i(TAG, "AUTO_SPEEDUP: $it aheadMs=${sample.aheadMs}") }
        return p.downloads != before
    }
}
