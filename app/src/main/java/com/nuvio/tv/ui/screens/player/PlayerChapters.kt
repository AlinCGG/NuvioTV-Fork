package com.nuvio.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.player.dvmkv.ChaptersMetadata
import java.util.Locale

/** A chapter as the player shows it; [title] is null when the file only numbers its chapters. */
data class PlayerChapter(val number: Int, val startMs: Long, val title: String?)

internal object PlayerChapters {

    /** Previous goes to the start of the current chapter first when playback is further in than this. */
    const val RESTART_WINDOW_MS = 3_000L

    private val numberedOnly = Regex(
        "^(chapter|chap\\.?|ch\\.?|chapitre|kapitel|capitolo|cap[ií]tulo|hoofdstuk|rozdzia[lł]|глава)\\s*0*\\d+$|^第\\s*0*\\d+\\s*章$",
        RegexOption.IGNORE_CASE
    )

    /** Matroska files often use the ISO 639-2/B codes; Locale gives the /T ones. */
    private val bibliographic = mapOf(
        "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces",
        "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl",
        "mac" to "mkd", "mao" to "mri", "may" to "msa", "per" to "fas", "rum" to "ron", "slo" to "slk",
        "tib" to "bod", "wel" to "cym"
    )

    @OptIn(UnstableApi::class)
    fun fromTracks(tracks: Tracks, locale: Locale = Locale.getDefault()): List<PlayerChapter> {
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                val chapters = group.getTrackFormat(i).metadata?.chapters() ?: continue
                return fromMetadata(chapters, locale)
            }
        }
        return emptyList()
    }

    @OptIn(UnstableApi::class)
    fun fromMetadata(metadata: ChaptersMetadata, locale: Locale = Locale.getDefault()): List<PlayerChapter> =
        metadata.chapters.mapIndexed { index, chapter ->
            PlayerChapter(
                number = index + 1,
                startMs = chapter.startUs / 1000,
                title = pickName(chapter.names.map { it.text to it.language }, locale)
            )
        }

    /** The name in the viewer's language when the file has one, else the first; generic "Chapter 3" names are dropped. */
    fun pickName(names: List<Pair<String, String?>>, locale: Locale): String? {
        val usable = names.filter { (text, _) -> text.isNotBlank() }
        if (usable.isEmpty()) return null
        val wanted = setOfNotNull(
            locale.language.lowercase(Locale.ROOT).takeIf { it.isNotEmpty() },
            runCatching { locale.isO3Language.lowercase(Locale.ROOT) }.getOrNull()?.takeIf { it.isNotEmpty() }
        )
        val chosen = usable.firstOrNull { (_, language) ->
            language?.lowercase(Locale.ROOT)?.substringBefore('-')?.let { bibliographic[it] ?: it } in wanted
        } ?: usable.first()
        return chosen.first.trim().takeUnless { numberedOnly.matches(it) }
    }

    /** Index of the chapter playing at [positionMs], or -1 before the first one. */
    fun indexAt(chapters: List<PlayerChapter>, positionMs: Long): Int {
        var found = -1
        for (i in chapters.indices) {
            if (chapters[i].startMs <= positionMs) found = i else break
        }
        return found
    }

    fun next(chapters: List<PlayerChapter>, positionMs: Long): PlayerChapter? =
        chapters.getOrNull(indexAt(chapters, positionMs) + 1)

    fun previous(chapters: List<PlayerChapter>, positionMs: Long): PlayerChapter? {
        val index = indexAt(chapters, positionMs)
        if (index < 0) return null
        val current = chapters[index]
        return if (positionMs - current.startMs > RESTART_WINDOW_MS) current else chapters.getOrNull(index - 1) ?: current
    }

    /** Start of each chapter but the first, as a share of [durationMs], for the timeline. */
    fun timelineFractions(chapters: List<PlayerChapter>, durationMs: Long): List<Float> {
        if (durationMs <= 0L) return emptyList()
        return chapters.drop(1).map { it.startMs.toFloat() / durationMs }.filter { it > 0f && it < 1f }
    }

    @OptIn(UnstableApi::class)
    private fun Metadata.chapters(): ChaptersMetadata? {
        for (i in 0 until length()) {
            val entry = get(i)
            if (entry is ChaptersMetadata) return entry
        }
        return null
    }
}
