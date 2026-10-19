package com.nuvio.tv.ui.screens.player

import java.util.Locale

/** Downloads the playing stream uses at once, for the stats HUD. */
internal data class PlaybackLanes(val connections: Int, val chunkBytes: Long, val auto: Boolean) {
    /** Null when there is only one download, so a line that adds it stays as it was. */
    val label: String?
        get() {
            if (connections <= 1 || chunkBytes <= 0L) return null
            val auto = if (auto) " · auto" else ""
            return "$connections × ${chunkSizeLabel(chunkBytes)}$auto"
        }

    companion object {
        private const val KB = 1024L
        private const val MB = 1024L * 1024L

        @Volatile private var manual: PlaybackLanes? = null
        @Volatile private var autoChunkBytes = 0L

        fun useManual(connections: Int, chunkBytes: Long) {
            autoChunkBytes = 0L
            manual = PlaybackLanes(connections, chunkBytes, auto = false)
        }

        fun useAuto(chunkBytes: Long) {
            manual = null
            autoChunkBytes = chunkBytes
        }

        fun clear() {
            manual = null
            autoChunkBytes = 0L
        }

        fun current(): PlaybackLanes? {
            manual?.let { return it }
            val chunk = autoChunkBytes
            if (chunk <= 0L || !AutoSpeedup.active) return null
            val downloads = if (AutoSpeedup.multiOpen.get() > 0) AutoSpeedup.downloads else 1
            return PlaybackLanes(downloads, chunk, auto = true)
        }

        /** The Speed row's text with the downloads added when there is more than one. */
        fun speedText(speed: String, lanes: PlaybackLanes?): String =
            lanes?.label?.let { "$speed · $it" } ?: speed

        /** The Connections row: downloads and chunk size on the chunk path, else a plain single stream. */
        fun connectionsText(lanes: PlaybackLanes?): String {
            if (lanes == null || lanes.chunkBytes <= 0L) return "1 · single stream"
            val auto = if (lanes.auto) " · auto" else ""
            return "${lanes.connections.coerceAtLeast(1)} × ${chunkSizeLabel(lanes.chunkBytes)}$auto"
        }

        fun chunkSizeLabel(bytes: Long): String = when {
            bytes < MB -> "${(bytes + KB / 2) / KB} KB"
            bytes % MB == 0L -> "${bytes / MB} MB"
            else -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / MB)
        }
    }
}
