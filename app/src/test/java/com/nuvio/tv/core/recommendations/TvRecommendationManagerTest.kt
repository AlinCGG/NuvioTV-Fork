package com.nuvio.tv.core.recommendations

import android.content.Context
import android.content.pm.PackageManager
import androidx.tvprovider.media.tv.WatchNextProgram
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class TvRecommendationManagerTest {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)
    private val programBuilder: ProgramBuilder = mockk(relaxed = true)
    private val program: WatchNextProgram = mockk(relaxed = true)
    private val layout: LayoutPreferenceDataStore = mockk(relaxed = true)
    private lateinit var manager: TvRecommendationManager

    @Before
    fun setUp() {
        every { context.packageManager } returns packageManager
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) } returns true
        every { programBuilder.watchNextId(any()) } answers { "wn_${firstArg<WatchProgress>().contentId}" }
        every { programBuilder.buildWatchNextProgram(any()) } returns program
        every { layout.customPosterUrlPattern } returns flowOf("")
        every { layout.customPosterEnabledScreens } returns flowOf(emptySet())
        TvRecommendationManager.isPlaybackActive.value = false
        manager = TvRecommendationManager(context, programBuilder, layout)
    }

    @After
    fun tearDown() {
        TvRecommendationManager.isPlaybackActive.value = false
        unmockkAll()
    }

    @Test
    fun `a paused player writes Watch Next straight away`() = runTest {
        manager.updateSingleWatchNextProgram(progress("tt1", 60_000L))

        verify(exactly = 1) { programBuilder.upsertWatchNextProgram(program, "wn_tt1") }
    }

    @Test
    fun `while frames are on screen the write waits and the latest position wins`() = runTest {
        TvRecommendationManager.isPlaybackActive.value = true

        manager.updateSingleWatchNextProgram(progress("tt1", 60_000L))
        manager.updateSingleWatchNextProgram(progress("tt1", 120_000L))
        verify(exactly = 0) { programBuilder.upsertWatchNextProgram(any(), any()) }

        TvRecommendationManager.isPlaybackActive.value = false

        verify(timeout = 5_000, exactly = 1) { programBuilder.upsertWatchNextProgram(program, "wn_tt1") }
        verify(exactly = 1) { programBuilder.buildWatchNextProgram(match { it.position == 120_000L }) }
    }

    @Test
    fun `a title finished during playback is not written back after the stop`() = runTest {
        TvRecommendationManager.isPlaybackActive.value = true
        manager.updateSingleWatchNextProgram(progress("tt1", 60_000L))

        manager.onProgressRemoved("tt1")
        TvRecommendationManager.isPlaybackActive.value = false

        verify(timeout = 1_000, exactly = 1) { programBuilder.removeWatchNextByContentId("tt1") }
        verify(exactly = 0) { programBuilder.upsertWatchNextProgram(any(), any()) }
    }

    @Test
    fun `an unchanged position is not written twice`() = runTest {
        manager.updateSingleWatchNextProgram(progress("tt1", 60_000L))
        manager.updateSingleWatchNextProgram(progress("tt1", 60_500L))

        verify(exactly = 1) { programBuilder.upsertWatchNextProgram(program, "wn_tt1") }
    }

    private fun progress(contentId: String, position: Long) = WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = "Title",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = position,
        duration = 5_400_000L,
        lastWatched = 1_700_000_000_000L
    )
}
