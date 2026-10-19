package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Settings switch: off hides the button, the timeline gaps and the chapter name; on reads them from the playing file again. */
internal fun PlayerRuntimeController.applyChaptersEnabled(enabled: Boolean) {
    if (enabled == chaptersEnabled) return
    chaptersEnabled = enabled
    val chapters = if (enabled) _exoPlayer?.currentTracks?.let { PlayerChapters.fromTracks(it) }.orEmpty() else emptyList()
    _uiState.update { it.copy(chapters = chapters, showChaptersPanel = it.showChaptersPanel && chapters.isNotEmpty()) }
}

internal fun PlayerRuntimeController.showChaptersPanel() {
    if (_uiState.value.chapters.isEmpty()) return
    _uiState.update {
        it.copy(
            showChaptersPanel = true,
            showControls = true,
            showEpisodesPanel = false,
            showSourcesPanel = false,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false
        )
    }
}

internal fun PlayerRuntimeController.dismissChaptersPanel() {
    _uiState.update { it.copy(showChaptersPanel = false) }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.selectChapter(startMs: Long) {
    _uiState.update { it.copy(showChaptersPanel = false) }
    onEvent(PlayerEvent.OnSeekTo(startMs))
}

/** Next or previous chapter from where playback is; a short pill names the chapter. */
internal fun PlayerRuntimeController.jumpChapter(forward: Boolean) {
    val chapters = _uiState.value.chapters
    if (chapters.isEmpty() || _playbackTimeline.value.isLive) return
    val position = _playbackTimeline.value.playbackPosition
    val target = if (forward) PlayerChapters.next(chapters, position) else PlayerChapters.previous(chapters, position)
    if (target == null) return
    onEvent(PlayerEvent.OnSeekTo(target.startMs))
    _uiState.update { it.copy(showAspectRatioIndicator = true, aspectRatioIndicatorText = chapterLabel(context, target)) }
    hideAspectRatioIndicatorJob?.cancel()
    hideAspectRatioIndicatorJob = scope.launch {
        delay(2000)
        _uiState.update { it.copy(showAspectRatioIndicator = false) }
    }
}
