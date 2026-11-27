package com.example.videoenhancer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.videoenhancer.gl.EnhancerRenderer
import com.example.videoenhancer.player.TrackSelectionHelper
import com.example.videoenhancer.player.smb.SmbDataSource
import com.example.videoenhancer.player.youtube.YouTubeStreamExtractor
import com.example.videoenhancer.ui.EnhancerUiState
import com.example.videoenhancer.ui.PlayerScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var exoPlayer: ExoPlayer? = null
    private var enhancerRenderer: EnhancerRenderer? = null
    private var uiState by mutableStateOf(EnhancerUiState())

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        enableImmersiveFullscreen()
        requestMaxDisplayRefreshRate()

        val player = buildSmbEnabledExoPlayer()
        exoPlayer = player

        val renderer = EnhancerRenderer(
            context = applicationContext,
            onVideoInputSurfaceReady = { inputSurface ->
                runOnUiThread {
                    if (inputSurface.isValid) {
                        exoPlayer?.setVideoSurface(inputSurface)
                    }
                }
            },
            onStatsUpdated = { outputFps, gpuFrameTimeMs ->
                runOnUiThread {
                    uiState = uiState.copy(currentOutputFps = outputFps, gpuFrameTimeMs = gpuFrameTimeMs)
                }
            },
            onError = { errMsg ->
                runOnUiThread {
                    uiState = uiState.copy(videoTitle = errMsg)
                }
            }
        )
        enhancerRenderer = renderer

        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                renderer.onVideoSizeChanged(videoSize.width, videoSize.height)
            }

            override fun onTracksChanged(tracks: Tracks) {
                val videoFormat = tracks.groups
                    .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                    ?.getTrackFormat(0)

                if (videoFormat != null && videoFormat.frameRate > 0f) {
                    renderer.sourceFps = videoFormat.frameRate
                }

                uiState = uiState.copy(
                    sourceFps = videoFormat?.frameRate?.takeIf { it > 0f } ?: uiState.sourceFps,
                    audioTracks = TrackSelectionHelper.getAudioTracks(tracks),
                    subtitleTracks = TrackSelectionHelper.getSubtitleTracks(tracks)
                )
            }

            override fun onCues(cueGroup: CueGroup) {
                val lines = cueGroup.cues.mapNotNull { it.text?.toString()?.takeIf { t -> t.isNotBlank() } }
                uiState = uiState.copy(subtitleCues = lines)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                uiState = uiState.copy(isPlaying = isPlaying)
                updateKeepScreenOn(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    uiState = uiState.copy(durationMs = player.duration.coerceAtLeast(0L))
                }
            }
        })

        lifecycleScope.launch {
            while (isActive) {
                exoPlayer?.let { p ->
                    if (p.isPlaying) {
                        uiState = uiState.copy(
                            currentPositionMs = p.currentPosition.coerceAtLeast(0L),
                            durationMs = p.duration.coerceAtLeast(0L)
                        )
                    }
                }
                delay(250)
            }
        }

        handleIncomingIntent(intent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PlayerScreen(
                    uiState = uiState,
                    onSurfaceTextureAvailable = { st, w, h -> renderer.onSurfaceTextureAvailable(st, w, h) },
                    onSurfaceTextureSizeChanged = { w, h -> renderer.onSurfaceChanged(w, h) },
                    onSurfaceTextureDestroyed = { renderer.onSurfaceDestroyed() },
                    onSelectVideoUri = { uri, explicitTitle -> playVideoUri(uri, explicitTitle) },
                    onSelectYouTubeUrl = { url -> playYouTubeUrl(url) },
                    onPlayPauseToggle = {
                        exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
                    },
                    onSeekTo = { posMs ->
                        exoPlayer?.seekTo(posMs)
                        uiState = uiState.copy(currentPositionMs = posMs)
                    },
                    onMultiplierChange = { mult ->
                        renderer.interpolationMultiplier = mult
                        uiState = uiState.copy(interpolationMultiplier = mult)
                    },
                    onUpscaleModeChange = { mode ->
                        renderer.upscaleMode = mode
                        uiState = uiState.copy(upscaleMode = mode)
                    },
                    onSharpnessChange = { sharp ->
                        renderer.sharpness = sharp
                        uiState = uiState.copy(sharpness = sharp)
                    },
                    onSplitPositionChange = { split ->
                        renderer.splitPosition = split
                        uiState = uiState.copy(splitPosition = split)
                    },
                    onSelectTrack = { track ->
                        exoPlayer?.let { TrackSelectionHelper.selectTrack(it, track) }
                    },
                    onDisableSubtitles = {
                        exoPlayer?.let { TrackSelectionHelper.disableSubtitles(it) }
                    },
                    onSubtitleFontSizeChange = { sizeSp ->
                        uiState = uiState.copy(subtitleFontSizeSp = sizeSp)
                    },
                    onSubtitleBottomMarginChange = { marginDp ->
                        uiState = uiState.copy(subtitleBottomMarginDp = marginDp)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    if (YouTubeStreamExtractor.isYouTubeUrl(uri.toString())) {
                        playYouTubeUrl(uri.toString())
                    } else {
                        playVideoUri(uri, null)
                    }
                }
            }
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                    val urlMatch = Regex("https?://\\S+").find(sharedText)?.value
                    if (urlMatch != null && YouTubeStreamExtractor.isYouTubeUrl(urlMatch)) {
                        playYouTubeUrl(urlMatch)
                    }
                }
            }
        }
    }

    private fun attachActiveVideoSurface(player: ExoPlayer) {
        val surface = enhancerRenderer?.currentInputSurface
        if (surface != null && surface.isValid) {
            player.setVideoSurface(surface)
        }
    }

    private fun playVideoUri(uri: Uri, explicitTitle: String?) {
        val player = exoPlayer ?: return
        val title = explicitTitle ?: resolveDisplayTitle(uri)
        uiState = uiState.copy(videoTitle = title, currentPositionMs = 0L, durationMs = 0L, subtitleCues = emptyList())
        attachActiveVideoSurface(player)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }

    @OptIn(UnstableApi::class)
    private fun playYouTubeUrl(url: String) {
        val player = exoPlayer ?: return
        uiState = uiState.copy(videoTitle = "YouTube ストリーム解析中...", subtitleCues = emptyList())

        lifecycleScope.launch {
            YouTubeStreamExtractor.buildMergingMediaSource(url, maxHeight = 1080, prefer30FpsForInterpolation = true)
                .onSuccess { extracted ->
                    enhancerRenderer?.sourceFps = extracted.sourceFps
                    uiState = uiState.copy(
                        videoTitle = "${extracted.title} [${extracted.selectedResolution}]",
                        sourceFps = extracted.sourceFps,
                        currentPositionMs = 0L,
                        durationMs = 0L
                    )
                    attachActiveVideoSurface(player)
                    player.setMediaSource(extracted.mediaSource)
                    player.prepare()
                    player.playWhenReady = true
                }
                .onFailure { error ->
                    uiState = uiState.copy(videoTitle = "取得エラー: ${error.localizedMessage ?: "再生できません"}")
                }
        }
    }

    private fun resolveDisplayTitle(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return cursor.getString(idx)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    }

    @OptIn(UnstableApi::class)
    private fun buildSmbEnabledExoPlayer(): ExoPlayer {
        val defaultFactory = DefaultDataSource.Factory(this)
        val hybridFactory = DataSource.Factory {
            object : DataSource {
                private var activeSource: DataSource? = null
                private var listener: TransferListener? = null

                override fun addTransferListener(transferListener: TransferListener) {
                    listener = transferListener
                }

                override fun open(dataSpec: DataSpec): Long {
                    val source = if (dataSpec.uri.scheme.equals("smb", ignoreCase = true)) {
                        SmbDataSource()
                    } else {
                        defaultFactory.createDataSource()
                    }
                    listener?.let { source.addTransferListener(it) }
                    activeSource = source
                    return source.open(dataSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    activeSource!!.read(buffer, offset, length)

                override fun getUri(): Uri? = activeSource?.uri

                override fun close() {
                    activeSource?.close()
                    activeSource = null
                }
            }
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 2_500, 5_000)
            .build()

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(hybridFactory))
            .setLoadControl(loadControl)
            .build()
    }

    private fun requestMaxDisplayRefreshRate() {
        val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display
        else @Suppress("DEPRECATION") windowManager.defaultDisplay ?: return

        val currentMode = currentDisplay.mode
        val highestMode = currentDisplay.supportedModes
            .filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
            .maxByOrNull { it.refreshRate }

        if (highestMode != null) {
            val params = window.attributes
            params.preferredDisplayModeId = highestMode.modeId
            window.attributes = params
        }
    }

    private fun enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun updateKeepScreenOn(keepOn: Boolean) {
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveFullscreen()
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
        enhancerRenderer?.shutdown()
        enhancerRenderer = null
    }
}
