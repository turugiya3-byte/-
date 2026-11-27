package com.example.videoenhancer.ui

import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.videoenhancer.player.MediaTrackInfo
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class UpscaleMode(val label: String) {
    OFF("OFF"),
    FSR_REALISTIC("FSR 1.0"),
    ANIME_4K("Anime4K")
}

enum class SubtitleSizePreset(val label: String, val sizeSp: Float) {
    SMALL("小 (16sp)", 16f),
    MEDIUM("中 (20sp)", 20f),
    LARGE("大 (26sp)", 26f)
}

data class EnhancerUiState(
    val videoTitle: String = "動画を選択してください",
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val sourceFps: Float = 24.0f,
    val currentOutputFps: Float = 0.0f,
    val gpuFrameTimeMs: Float = 0.0f,
    val interpolationMultiplier: Float = 2.0f,
    val upscaleMode: UpscaleMode = UpscaleMode.FSR_REALISTIC,
    val sharpness: Float = 0.65f,
    val splitPosition: Float = 0.5f,
    val audioTracks: List<MediaTrackInfo> = emptyList(),
    val subtitleTracks: List<MediaTrackInfo> = emptyList(),
    val subtitleCues: List<String> = emptyList(),
    val subtitleFontSizeSp: Float = SubtitleSizePreset.MEDIUM.sizeSp,
    val subtitleBottomMarginDp: Float = 28f
)

@Composable
fun PlayerScreen(
    uiState: EnhancerUiState,
    onSurfaceCreated: (SurfaceHolder) -> Unit,
    onSurfaceChanged: (Int, Int) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onSelectVideoUri: (Uri, String?) -> Unit,
    onSelectYouTubeUrl: (String) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onMultiplierChange: (Float) -> Unit,
    onUpscaleModeChange: (UpscaleMode) -> Unit,
    onSharpnessChange: (Float) -> Unit,
    onSplitPositionChange: (Float) -> Unit,
    onSelectTrack: (MediaTrackInfo) -> Unit,
    onDisableSubtitles: () -> Unit,
    onSubtitleFontSizeChange: (Float) -> Unit,
    onSubtitleBottomMarginChange: (Float) -> Unit
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var showSmbDialog by remember { mutableStateOf(false) }
    var showYouTubeDialog by remember { mutableStateOf(false) }
    var showTrackDialog by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var viewportWidthPx by remember { mutableFloatStateOf(1f) }

    val localVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { onSelectVideoUri(it, null) }
    }

    LaunchedEffect(controlsVisible, uiState.isPlaying) {
        if (controlsVisible && uiState.isPlaying && !showFilterSheet && !showSmbDialog && !showYouTubeDialog && !showTrackDialog) {
            delay(4000)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewportWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                controlsVisible = !controlsVisible
            }
    ) {
        // 1. OpenGL ES 描画先
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = onSurfaceCreated(holder)
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                            onSurfaceChanged(width, height)
                        override fun surfaceDestroyed(holder: SurfaceHolder) = onSurfaceDestroyed()
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. スワイプジェスチャー層（左右シーク / 左上下輝度 / 右上下音量）
        PlayerGestureLayer(
            currentPositionMs = uiState.currentPositionMs,
            durationMs = uiState.durationMs,
            splitPosition = uiState.splitPosition,
            onSeekTo = onSeekTo
        )

        // 3. 縁取り字幕オーバーレイ
        val effectiveBottomPadding = if (controlsVisible) {
            (uiState.subtitleBottomMarginDp + 90f).dp
        } else {
            uiState.subtitleBottomMarginDp.dp
        }
        SubtitleOverlay(
            cueLines = uiState.subtitleCues,
            fontSize = uiState.subtitleFontSizeSp.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = effectiveBottomPadding)
        )

        // 4. Before / After 比較ハンドル
        if (uiState.splitPosition in 0.01f..0.99f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .offset { IntOffset((uiState.splitPosition * viewportWidthPx).roundToInt() - 24.dp.roundToPx(), 0) }
                    .width(48.dp)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            val newPos = (uiState.splitPosition + dragAmount / viewportWidthPx).coerceIn(0.0f, 1.0f)
                            onSplitPositionChange(newPos)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text("ORIG", color = Color.LightGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.CompareArrows, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text("AI", color = Color(0xFF4ADE80), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 5. FPS & GPU負荷 HUD
        val targetFps = uiState.sourceFps * uiState.interpolationMultiplier
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = String.format(Locale.US, "SRC %.1f → OUT %.1f FPS (%.1fx)", uiState.sourceFps, uiState.currentOutputFps, uiState.interpolationMultiplier),
                color = Color(0xFF4ADE80),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = String.format(Locale.US, "TARGET: %.1f FPS | GPU: %.2f ms | %s", targetFps, uiState.gpuFrameTimeMs, uiState.upscaleMode.name),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // 6. 操作オーバーレイ
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
            ) {
                // 上部バー
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = uiState.videoTitle,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )

                    FilledTonalButton(
                        onClick = { localVideoLauncher.launch(arrayOf("video/*")) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("ローカル", fontSize = 11.sp)
                    }

                    FilledTonalButton(
                        onClick = { showSmbDialog = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("NAS", fontSize = 11.sp)
                    }

                    FilledTonalButton(
                        onClick = { showYouTubeDialog = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.PlayCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("YouTube", fontSize = 11.sp)
                    }
                }

                // 中央再生ボタン
                IconButton(
                    onClick = onPlayPauseToggle,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // 下部バー
                val multipliers = listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(formatTimeMs(uiState.currentPositionMs), color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Slider(
                            value = if (uiState.durationMs > 0) uiState.currentPositionMs.toFloat() / uiState.durationMs.toFloat() else 0f,
                            onValueChange = { onSeekTo((it * uiState.durationMs).toLong()) },
                            modifier = Modifier.weight(1f)
                        )
                        Text(formatTimeMs(uiState.durationMs), color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("補間:", color = Color.LightGray, fontSize = 11.sp)
                            multipliers.forEach { mult ->
                                FilterChip(
                                    selected = uiState.interpolationMultiplier == mult,
                                    onClick = { onMultiplierChange(mult) },
                                    label = { Text(if (mult == 1.0f) "OFF" else "${mult}x", fontSize = 11.sp) },
                                    modifier = Modifier.height(28.dp)
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("超解像:", color = Color.LightGray, fontSize = 11.sp)
                            UpscaleMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = uiState.upscaleMode == mode,
                                    onClick = { onUpscaleModeChange(mode) },
                                    label = { Text(mode.label, fontSize = 11.sp) },
                                    modifier = Modifier.height(28.dp)
                                )
                            }
                            IconButton(onClick = { showTrackDialog = true }) {
                                Icon(Icons.Default.Subtitles, contentDescription = "音声・字幕", tint = Color.White)
                            }
                            IconButton(onClick = { showFilterSheet = true }) {
                                Icon(Icons.Default.Tune, contentDescription = "詳細設定", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // ダイアログ群
        if (showSmbDialog) {
            SmbBrowserDialog(
                onDismiss = { showSmbDialog = false },
                onSelectVideo = { smbUri, fileName ->
                    showSmbDialog = false
                    onSelectVideoUri(smbUri, fileName)
                }
            )
        }

        if (showYouTubeDialog) {
            YouTubeUrlDialog(
                onDismiss = { showYouTubeDialog = false },
                onSubmitUrl = { url ->
                    showYouTubeDialog = false
                    onSelectYouTubeUrl(url)
                }
            )
        }

        if (showTrackDialog) {
            TrackSelectionDialog(
                audioTracks = uiState.audioTracks,
                subtitleTracks = uiState.subtitleTracks,
                onSelectTrack = onSelectTrack,
                onDisableSubtitles = onDisableSubtitles,
                onDismiss = { showTrackDialog = false }
            )
        }

        if (showFilterSheet) {
            FilterDetailDialog(
                uiState = uiState,
                onSharpnessChange = onSharpnessChange,
                onSplitPositionChange = onSplitPositionChange,
                onSubtitleFontSizeChange = onSubtitleFontSizeChange,
                onSubtitleBottomMarginChange = onSubtitleBottomMarginChange,
                onDismiss = { showFilterSheet = false }
            )
        }
    }
}

@Composable
private fun YouTubeUrlDialog(
    onDismiss: () -> Unit,
    onSubmitUrl: (String) -> Unit
) {
    var urlInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("YouTube URL を再生") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("YouTubeの動画URLを貼り付けると、映像と音声を抽出して超解像＆フレーム補間で再生します。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("https://www.youtube.com/watch?v=...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = urlInput.isNotBlank(),
                onClick = { onSubmitUrl(urlInput.trim()) }
            ) {
                Text("抽出して再生")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

@Composable
private fun FilterDetailDialog(
    uiState: EnhancerUiState,
    onSharpnessChange: (Float) -> Unit,
    onSplitPositionChange: (Float) -> Unit,
    onSubtitleFontSizeChange: (Float) -> Unit,
    onSubtitleBottomMarginChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("フィルター・字幕詳細調整") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("超解像シャープネス強度: ${String.format(Locale.US, "%.2f", uiState.sharpness)}", style = MaterialTheme.typography.titleSmall)
                    Slider(value = uiState.sharpness, onValueChange = onSharpnessChange, valueRange = 0.0f..1.0f)
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Before / After 分割比較表示", style = MaterialTheme.typography.titleSmall)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onSplitPositionChange(0.0f) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text("全面AI", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = { onSplitPositionChange(0.5f) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text("50%比較", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = { onSplitPositionChange(1.0f) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text("全面元映像", fontSize = 12.sp)
                        }
                    }
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("字幕フォントサイズ: ${uiState.subtitleFontSizeSp.roundToInt()} sp", style = MaterialTheme.typography.titleSmall)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SubtitleSizePreset.entries.forEach { preset ->
                            FilterChip(
                                selected = abs(uiState.subtitleFontSizeSp - preset.sizeSp) < 0.5f,
                                onClick = { onSubtitleFontSizeChange(preset.sizeSp) },
                                label = { Text(preset.label, fontSize = 12.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Slider(value = uiState.subtitleFontSizeSp, onValueChange = onSubtitleFontSizeChange, valueRange = 12.0f..36.0f)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("字幕表示位置（下端マージン）: ${uiState.subtitleBottomMarginDp.roundToInt()} dp", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { onSubtitleBottomMarginChange(28f) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text("標準に戻す", fontSize = 11.sp)
                        }
                    }
                    Slider(value = uiState.subtitleBottomMarginDp, onValueChange = onSubtitleBottomMarginChange, valueRange = 8.0f..140.0f)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        }
    )
}

private fun formatTimeMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}
