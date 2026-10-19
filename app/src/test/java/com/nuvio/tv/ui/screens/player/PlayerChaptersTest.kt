package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import com.nuvio.tv.core.player.dvmkv.ChaptersMetadata
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerChaptersTest {

    private val chapters = listOf(
        PlayerChapter(1, 0L, "Overture"),
        PlayerChapter(2, 60_000L, "One Day More"),
        PlayerChapter(3, 120_000L, null),
    )

    @Test
    fun `current chapter follows the position`() {
        assertEquals(0, PlayerChapters.indexAt(chapters, 0L))
        assertEquals(0, PlayerChapters.indexAt(chapters, 59_999L))
        assertEquals(1, PlayerChapters.indexAt(chapters, 60_000L))
        assertEquals(2, PlayerChapters.indexAt(chapters, 500_000L))
        assertEquals(-1, PlayerChapters.indexAt(chapters.drop(1), 10_000L))
    }

    @Test
    fun `next goes to the following chapter and stops at the last`() {
        assertEquals(2, PlayerChapters.next(chapters, 5_000L)?.number)
        assertEquals(3, PlayerChapters.next(chapters, 61_000L)?.number)
        assertNull(PlayerChapters.next(chapters, 130_000L))
        assertEquals(2, PlayerChapters.next(chapters.drop(1), 10_000L)?.number)
    }

    @Test
    fun `previous restarts the chapter after three seconds, else goes one back`() {
        assertEquals(2, PlayerChapters.previous(chapters, 70_000L)?.number)
        assertEquals(1, PlayerChapters.previous(chapters, 62_000L)?.number)
        assertEquals(1, PlayerChapters.previous(chapters, 1_000L)?.number)
        assertNull(PlayerChapters.previous(chapters.drop(1), 10_000L))
    }

    @Test
    fun `timeline gaps leave out the first chapter`() {
        assertEquals(listOf(0.25f, 0.5f), PlayerChapters.timelineFractions(chapters, 240_000L))
        assertEquals(emptyList<Float>(), PlayerChapters.timelineFractions(chapters, 0L))
    }

    @Test
    fun `name in the viewer's language wins and numbered names are dropped`() {
        val names = listOf("Overture" to "eng", "Ouverture" to "fre", "Ouvertüre" to "de")
        assertEquals("Ouverture", PlayerChapters.pickName(names, Locale.FRENCH))
        assertEquals("Ouvertüre", PlayerChapters.pickName(names, Locale.GERMAN))
        assertEquals("Overture", PlayerChapters.pickName(names, Locale.JAPANESE))
        assertNull(PlayerChapters.pickName(listOf("Chapter 01" to "eng"), Locale.ENGLISH))
        assertNull(PlayerChapters.pickName(listOf("第 01 章" to "en"), Locale.ENGLISH))
        assertNull(PlayerChapters.pickName(listOf("Kapitel 3" to "ger"), Locale.GERMAN))
        assertEquals("Scene 1", PlayerChapters.pickName(listOf("Scene 1" to "und"), Locale.ENGLISH))
        assertNull(PlayerChapters.pickName(emptyList(), Locale.ENGLISH))
    }

    @Test
    fun `metadata becomes numbered chapters in milliseconds`() {
        val metadata = ChaptersMetadata(
            listOf(
                ChaptersMetadata.Chapter(0L, C.TIME_UNSET, listOf(ChaptersMetadata.Name("Prologue", "eng"))),
                ChaptersMetadata.Chapter(90_500_000L, C.TIME_UNSET, emptyList()),
            )
        )
        assertEquals(
            listOf(PlayerChapter(1, 0L, "Prologue"), PlayerChapter(2, 90_500L, null)),
            PlayerChapters.fromMetadata(metadata, Locale.ENGLISH)
        )
    }
}
