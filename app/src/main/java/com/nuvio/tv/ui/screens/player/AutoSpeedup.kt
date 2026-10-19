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

    data class Limits(val maxDownloads: Int, val holdBytes: Long)

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
        totalRamBytes in 1 until 2_560L * MB -> Limits(maxDownloads = 4, holdBytes = 16L * MB)
        totalRamBytes in 1 until 3_584L * MB -> Limits(maxDownloads = 5, holdBytes = 16L * MB)
        totalRamBytes > 0L -> Limits(maxDownloads = 6, holdBytes = 16L * MB)
        else -> Limits(maxDownloads = 1, holdBytes = 16L * MB)
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
        policy = AutoSpeedupPolicy(limits.maxDownloads, limits.holdBytes)
        Log.i(TAG, "AUTO_SPEEDUP: on maxDownloads=${limits.maxDownloads} holdMb=${limits.holdBytes / MB}")
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
    fun lentBytes(): Long = AutoSpeedupPolicy.lentBytes(downloads, limits.holdBytes)

    /** Returns true when the number of downloads changed. */
    fun onPlayerSample(sample: AutoSpeedupPolicy.Sample): Boolean {
        val p = policy ?: return false
        val before = p.downloads
        p.onSample(sample.copy(bytes = received.get(), multiActive = multiOpen.get() > 0))
        p.lastChange?.let { Log.i(TAG, "AUTO_SPEEDUP: $it aheadMs=${sample.aheadMs}") }
        return p.downloads != before
    }
}
