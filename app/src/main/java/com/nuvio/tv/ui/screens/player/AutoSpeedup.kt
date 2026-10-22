package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.player.BufferSizing
import com.nuvio.tv.core.player.thumbnail.ConnectionLedger
import com.nuvio.tv.core.player.thumbnail.MemoryGovernor
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
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

    private const val QUICK_FIRST_BYTE_MS = 600L

    /**
     * What the extra downloads leave free above Android's low-memory line. Smaller than the buffer's margin: Android
     * closes idle apps to keep that line.
     */
    const val MARGIN_BYTES = 25L * MB
    private const val FIRST_BYTE_SAMPLES = 2

    /**
     * [quickHoldBytes] = the piece used instead of [holdBytes] when the server sends its first byte quickly: pieces
     * in flight are taken off the buffer, so the smallest piece that still keeps the downloads busy leaves the most
     * film in memory. [smallBox] = a 2 GB box: a start that holds no more than two 16 MB downloads.
     * [readAheadMaxBytes] = the most the read-ahead may hold on this box.
     */
    data class Limits(
        val maxDownloads: Int,
        val holdBytes: Long,
        val quickHoldBytes: Long = holdBytes,
        val smallBox: Boolean = false,
        val readAheadMaxBytes: Long = AutoSpeedupPolicy.READ_AHEAD_MAX_BYTES
    )

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

    /** Pieces fetched beyond one per download while memory allows, see [AutoSpeedupPolicy.readAheadPieces]. */
    @Volatile
    var readAhead: Int = 0
        private set

    private var readAheadRoomAtMs = Long.MIN_VALUE

    /**
     * Pieces the chunk session may have under way or waiting: one per download plus the read-ahead, never more than
     * [MAX_IN_FLIGHT] requests at once, where debrid servers started answering "too many requests".
     */
    fun depth(): Int = ConnectionLedger.app.playbackRoom(url, wantedDepth())

    /** Requests playback would like at once, before the server's shared budget ([ConnectionLedger.playbackRoom]). */
    private fun wantedDepth(): Int = minOf(downloads + if (downloads > 1) readAhead else 0, maxOf(downloads, MAX_IN_FLIGHT))

    private fun noteRequests() = ConnectionLedger.app.notePlaybackDownloads(url, wantedDepth())

    /** The most pieces the chunk session is ever asked to hold for this piece size. */
    fun maxDepth(pieceBytes: Long): Int =
        maxOf(limits.maxDownloads + 1, (limits.readAheadMaxBytes / pieceBytes.coerceAtLeast(1L)).toInt())

    @Volatile
    private var hintUrl: String? = null

    @Volatile
    private var hintBps = 0L

    /** The bitrate [guessBitrate] found for [streamUrl], used by [begin] before the first byte arrives. */
    fun startHint(streamUrl: String, bitrateBps: Long) {
        hintUrl = streamUrl
        hintBps = bitrateBps
    }

    /**
     * A film's bitrate in bits per second known before its first byte: the add-on's size over the title's runtime,
     * else 80 Mbit/s for a name that says remux, or 4K with lossless audio outside a web release; 0 otherwise.
     */
    fun guessBitrate(sizeBytes: Long?, runtimeMinutes: Int?, vararg names: String?): Long {
        if (sizeBytes != null && sizeBytes > 0L && runtimeMinutes != null && runtimeMinutes >= MIN_GUESS_RUNTIME_MIN) {
            val bps = sizeBytes * 8L / (runtimeMinutes * 60L)
            if (bps in 1..MAX_FILE_BITRATE) return bps
        }
        val text = names.filterNotNull().joinToString(" ")
        if (REMUX.containsMatchIn(text)) return GUESSED_REMUX_BPS
        if (UHD.containsMatchIn(text) && LOSSLESS_AUDIO.containsMatchIn(text) && !WEB.containsMatchIn(text)) {
            return GUESSED_REMUX_BPS
        }
        return 0L
    }

    private const val MIN_GUESS_RUNTIME_MIN = 20
    const val MAX_IN_FLIGHT = 8
    private const val GUESSED_REMUX_BPS = 80_000_000L
    private val REMUX = Regex("remux", RegexOption.IGNORE_CASE)
    private val UHD = Regex("""2160p|\buhd\b|\b4k\b""", RegexOption.IGNORE_CASE)
    private val LOSSLESS_AUDIO = Regex("""truehd|dts[-. ]?hd[-. ]?ma|dts[-. ]?x\b""", RegexOption.IGNORE_CASE)
    private val WEB = Regex("web[-. ]?(dl|rip)", RegexOption.IGNORE_CASE)

    /**
     * Recomputed only on a fresh memory reading, so one reading never counts twice. While seek thumbnails are
     * generating it also leaves them the MemAvailable they need ([thumbFloorBytes]).
     */
    fun updateReadAhead(roomBytes: Long, thumbFloorBytes: () -> Long = { SeekThumbnails.memoryFloorBytes() }) {
        if (roomReadAtMs == readAheadRoomAtMs && downloads > 1) return
        readAheadRoomAtMs = roomReadAtMs
        val floor = if (downloads > 1) thumbFloorBytes() else 0L
        val room = if (floor > 0L) {
            val avail = MemoryGovernor.readMeminfo()?.availableMb?.let { it * MB } ?: -1L
            AutoSpeedupPolicy.readAheadRoom(roomBytes, avail, floor)
        } else {
            roomBytes
        }
        val next = AutoSpeedupPolicy.readAheadPieces(readAhead, room, pieceBytes, downloads, limits.readAheadMaxBytes)
        if (next != readAhead) {
            val thumbs = if (floor > 0L) " thumbFloorMb=${floor / MB}" else ""
            Log.i(TAG, "AUTO_SPEEDUP: read-ahead $readAhead->$next downloads=$downloads roomMb=${room / MB}$thumbs")
            readAhead = next
            noteRequests()
        }
    }

    val active: Boolean get() = policy != null

    fun limitsFor(totalRamBytes: Long): Limits = when {
        totalRamBytes in 1 until 2_560L * MB ->
            Limits(maxDownloads = 6, holdBytes = 16L * MB, quickHoldBytes = 8L * MB, smallBox = true)
        totalRamBytes in 1 until 3_584L * MB ->
            Limits(maxDownloads = 8, holdBytes = 16L * MB, quickHoldBytes = 8L * MB, readAheadMaxBytes = 192L * MB)
        totalRamBytes > 0L ->
            Limits(maxDownloads = 10, holdBytes = 24L * MB, quickHoldBytes = 8L * MB, readAheadMaxBytes = 256L * MB)
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

    /** Free memory above Android's low-memory line and [MARGIN_BYTES], read at most every [ROOM_READ_MS]. */
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
        else free.availBytes - free.thresholdBytes - MARGIN_BYTES

    @Volatile
    private var hostKey: String? = null

    @Volatile
    private var remembered: HostSpeeds.Entry? = null

    /**
     * A new stream starts on one download; the same stream again (a subtitle reload) keeps what it learnt. What the
     * same server delivered before ([HostSpeeds]) picks the piece size at once and the downloads a heavy film starts on.
     */
    fun begin(streamUrl: String, totalRamBytes: Long) {
        if (policy != null && streamUrl == url) return
        end()
        limits = limitsFor(totalRamBytes)
        url = streamUrl
        hostKey = HostSpeeds.keyOf(streamUrl)
        remembered = hostKey?.let { HostSpeeds.get(it) }
        received.set(0L)
        roomReadAtMs = Long.MIN_VALUE
        readAheadRoomAtMs = Long.MIN_VALUE
        readAhead = 0
        swapReadAtMs = Long.MIN_VALUE
        swapLowNow = false
        synchronized(pieceLock) {
            pieceBytes = limits.holdBytes
            firstByteMs = -1L
            firstByteSamples = 0
            policy = AutoSpeedupPolicy(limits.maxDownloads, pieceBytes, limits.smallBox)
            pieceFixed = limits.quickHoldBytes == limits.holdBytes
            val known = remembered
            if (known != null) {
                policy?.knownPerDownloadBps = known.perDownloadBps
                if (known.firstByteMs >= 0L) {
                    firstByteMs = known.firstByteMs
                    pieceBytes = pieceFor(limits, known.firstByteMs)
                    policy?.pieceBytes = pieceBytes
                    pieceFixed = true
                }
                Log.i(TAG, "AUTO_SPEEDUP: remembered host=$hostKey perDownloadKbps=${known.perDownloadBps * 8 / 1000} " +
                    "downloads=${known.downloads} firstByteMs=${known.firstByteMs}")
            }
            val guessed = if (hintUrl == streamUrl) hintBps else 0L
            if (guessed >= AutoSpeedupPolicy.START_THREE_BPS && limits.maxDownloads > 1) {
                if (known == null || known.firstByteMs < 0L) {
                    // No first byte timed yet: the smaller piece, which the slow servers it suits less still carry.
                    pieceBytes = limits.quickHoldBytes
                    policy?.pieceBytes = pieceBytes
                }
                pieceFixed = true
                val start = AutoSpeedupPolicy.startDownloads(guessed, limits.maxDownloads, pieceBytes, limits.smallBox,
                    known?.perDownloadBps ?: 0L)
                policy?.preset(start)
                Log.i(TAG, "AUTO_SPEEDUP: start guessed downloads=$start fileKbps=${guessed / 1000}")
            }
            if (pieceFixed) logOn()
        }
    }

    fun end() {
        val p = policy
        val wasOn = p != null
        val key = hostKey
        if (p != null && key != null && p.measuredPerDownloadBps > 0L) {
            val entry = HostSpeeds.Entry(p.measuredPerDownloadBps, p.peakKept.coerceAtLeast(1),
                if (firstByteMs >= 0L) firstByteMs else remembered?.firstByteMs ?: -1L, System.currentTimeMillis())
            HostSpeeds.put(key, entry)
            Log.i(TAG, "AUTO_SPEEDUP: saved host=$key perDownloadKbps=${entry.perDownloadBps * 8 / 1000} " +
                "downloads=${entry.downloads}")
        }
        policy = null
        url = null
        hostKey = null
        remembered = null
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
        p.roomCap = ConnectionLedger.app.playbackRoom(url, limits.maxDownloads)
        p.onSample(sample.copy(bytes = received.get(), multiActive = multiOpen.get() > 0))
        p.lastChange?.let { Log.i(TAG, "AUTO_SPEEDUP: $it aheadMs=${sample.aheadMs} roomCap=${p.roomCap}") }
        if (p.downloads != before) noteRequests()
        return p.downloads != before
    }
}
