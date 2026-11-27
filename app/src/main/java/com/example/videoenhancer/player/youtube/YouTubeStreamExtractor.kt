package com.example.videoenhancer.player.youtube

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.atomic.AtomicBoolean

data class ExtractedYouTubeMedia(
    val title: String,
    val selectedResolution: String,
    val sourceFps: Float,
    val mediaSource: MediaSource
)

object YouTubeStreamExtractor {

    private val isInitialized = AtomicBoolean(false)

    private fun ensureInitialized() {
        if (isInitialized.compareAndSet(false, true)) {
            NewPipe.init(OkHttpDownloader.instance, Localization("ja", "JP"))
        }
    }

    fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com/") || lower.contains("youtu.be/")
    }

    @OptIn(UnstableApi::class)
    suspend fun buildMergingMediaSource(
        youtubeUrl: String,
        maxHeight: Int = 1080,
        prefer30FpsForInterpolation: Boolean = true
    ): Result<ExtractedYouTubeMedia> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, youtubeUrl)

            val videoStream = selectBestVideoStream(streamInfo.videoOnlyStreams, maxHeight, prefer30FpsForInterpolation)
                ?: return@withContext Result.failure(IllegalStateException("再生可能な映像ストリームが見つかりません"))

            val audioStream = selectBestAudioStream(streamInfo.audioStreams)
                ?: return@withContext Result.failure(IllegalStateException("再生可能な音声ストリームが見つかりません"))

            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .setAllowCrossProtocolRedirects(true)

            val progressiveFactory = ProgressiveMediaSource.Factory(httpFactory)
            val videoSource = progressiveFactory.createMediaSource(MediaItem.fromUri(Uri.parse(videoStream.content)))
            val audioSource = progressiveFactory.createMediaSource(MediaItem.fromUri(Uri.parse(audioStream.content)))

            val mergedSource = MergingMediaSource(true, true, videoSource, audioSource)
            val detectedFps = if (videoStream.fps > 0) videoStream.fps.toFloat() else 30.0f

            Result.success(
                ExtractedYouTubeMedia(
                    title = streamInfo.name ?: "YouTube Video",
                    selectedResolution = videoStream.resolution ?: "${videoStream.height}p",
                    sourceFps = detectedFps,
                    mediaSource = mergedSource
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun selectBestVideoStream(streams: List<VideoStream>, maxHeight: Int, prefer30Fps: Boolean): VideoStream? {
        val candidates = streams.filter { it.content.isNotBlank() && it.height in 144..maxHeight }
        if (candidates.isEmpty()) return streams.firstOrNull { it.content.isNotBlank() }

        return candidates.sortedWith(
            compareByDescending<VideoStream> { it.height }
                .thenByDescending { if (prefer30Fps && it.fps <= 30) 1 else 0 }
                .thenByDescending { it.format?.suffix?.equals("mp4", ignoreCase = true) == true }
        ).firstOrNull()
    }

    private fun selectBestAudioStream(streams: List<AudioStream>): AudioStream? =
        streams.filter { it.content.isNotBlank() }.maxByOrNull { it.averageBitrate }
}
