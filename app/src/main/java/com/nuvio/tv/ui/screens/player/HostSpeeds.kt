package com.nuvio.tv.ui.screens.player

import android.content.SharedPreferences
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * What Automatic speed-up learnt per server: the rate one download carried, the downloads a film kept and the wait for
 * the first byte. The next film from the same server starts from it instead of measuring again. Kept [KEEP_MS].
 */
internal object HostSpeeds {
    data class Entry(val perDownloadBps: Long, val downloads: Int, val firstByteMs: Long, val savedAtMs: Long)

    const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    private const val PREFIX = "host:"

    @Volatile
    private var prefs: SharedPreferences? = null
    private val cache = ConcurrentHashMap<String, Entry>()

    fun attach(store: SharedPreferences) {
        if (prefs == null) prefs = store
    }

    /**
     * The server a link belongs to. A numbered first label (nexus-115, syd4-4, store-059) names one machine of a CDN
     * region; the region is what decides the speed, so it is left out.
     */
    fun keyOf(url: String?): String? {
        val host = runCatching { URI(url ?: return null).host }.getOrNull()?.lowercase() ?: return null
        val labels = host.split('.')
        return if (labels.size > 2 && MACHINE.matches(labels[0])) labels.drop(1).joinToString(".") else host
    }

    fun get(key: String, nowMs: Long = System.currentTimeMillis()): Entry? {
        val e = cache[key] ?: prefs?.getString(PREFIX + key, null)?.let(::decode)?.also { cache[key] = it } ?: return null
        return e.takeIf { nowMs - it.savedAtMs in 0 until KEEP_MS }
    }

    fun put(key: String, entry: Entry) {
        cache[key] = entry
        prefs?.edit()?.putString(PREFIX + key, encode(entry))?.apply()
    }

    fun encode(e: Entry): String = "${e.perDownloadBps},${e.downloads},${e.firstByteMs},${e.savedAtMs}"

    fun decode(text: String): Entry? {
        val p = text.split(',')
        if (p.size != 4) return null
        val bps = p[0].toLongOrNull() ?: return null
        val n = p[1].toIntOrNull() ?: return null
        val fb = p[2].toLongOrNull() ?: return null
        val at = p[3].toLongOrNull() ?: return null
        return if (bps > 0L && n > 0) Entry(bps, n, fb, at) else null
    }

    internal fun clearForTest() {
        cache.clear()
        prefs = null
    }

    private val MACHINE = Regex("[a-z]+[0-9]*-[0-9]+")
}
