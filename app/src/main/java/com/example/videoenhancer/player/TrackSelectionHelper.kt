package com.example.videoenhancer.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import java.util.Locale

data class MediaTrackInfo(
    val groupIndex: Int,
    val trackIndex: Int,
    val trackType: @C.TrackType Int,
    val displayName: String,
    val isSelected: Boolean
)

object TrackSelectionHelper {

    fun getAudioTracks(tracks: Tracks): List<MediaTrackInfo> =
        extractTracksByType(tracks, C.TRACK_TYPE_AUDIO)

    fun getSubtitleTracks(tracks: Tracks): List<MediaTrackInfo> =
        extractTracksByType(tracks, C.TRACK_TYPE_TEXT)

    private fun extractTracksByType(tracks: Tracks, targetType: @C.TrackType Int): List<MediaTrackInfo> {
        val result = mutableListOf<MediaTrackInfo>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != targetType) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                result.add(
                    MediaTrackInfo(
                        groupIndex = groupIndex,
                        trackIndex = trackIndex,
                        trackType = targetType,
                        displayName = buildReadableLabel(format, result.size + 1, targetType),
                        isSelected = group.isTrackSelected(trackIndex)
                    )
                )
            }
        }
        return result
    }

    fun selectTrack(player: Player, trackInfo: MediaTrackInfo) {
        val group = player.currentTracks.groups.getOrNull(trackInfo.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(trackInfo.trackType, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(trackInfo.trackIndex)))
            .build()
    }

    fun disableSubtitles(player: Player) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
    }

    private fun buildReadableLabel(format: Format, fallbackIndex: Int, trackType: @C.TrackType Int): String {
        val parts = mutableListOf<String>()
        val langCode = format.language
        if (!langCode.isNullOrBlank() && langCode != C.LANGUAGE_UNDETERMINED) {
            val displayLang = Locale.forLanguageTag(langCode).getDisplayLanguage(Locale.getDefault())
            if (displayLang.isNotBlank()) parts.add(displayLang)
        }
        if (!format.label.isNullOrBlank()) parts.add(format.label!!)
        if (trackType == C.TRACK_TYPE_AUDIO && format.channelCount > 0) {
            parts.add(
                when (format.channelCount) {
                    1 -> "1.0ch"
                    2 -> "2.0ch"
                    6 -> "5.1ch"
                    8 -> "7.1ch"
                    else -> "${format.channelCount}ch"
                }
            )
        }
        return if (parts.isEmpty()) {
            "${if (trackType == C.TRACK_TYPE_AUDIO) "音声" else "字幕"} トラック $fallbackIndex"
        } else {
            parts.distinct().joinToString(" · ")
        }
    }
}
