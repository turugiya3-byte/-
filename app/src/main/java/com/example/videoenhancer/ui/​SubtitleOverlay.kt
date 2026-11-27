package com.example.videoenhancer.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class GestureMode {
    NONE,
    HORIZONTAL_SEEK,
    VERTICAL_BRIGHTNESS,
    VERTICAL_VOLUME
}

private data class GestureIndicatorState(
    val icon: ImageVector,
    val label: String
)

@Composable
fun PlayerGestureLayer(
    currentPositionMs: Long,
    durationMs: Long,
    splitPosition: Float,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var boxWidthPx by remember { mutableFloatStateOf(1f) }
    var boxHeightPx by remember { mutableFloatStateOf(1f) }

    var activeMode by remember { mutableStateOf(GestureMode.NONE) }
    var indicator by remember { mutableStateOf<GestureIndicatorState?>(null) }

    // ドラッグ開始時の基準値
    var startPositionMs by remember { mutableLongStateOf(0L) }
    var pendingSeekMs by remember { mutableLongStateOf(0L) }
    var startBrightness by remember { mutableFloatStateOf(0.5f) }
    var startVolumeFloat by remember { mutableFloatStateOf(0.5f) }
    var accumulatedDx by remember { mutableFloatStateOf(0f) }
    var accumulatedDy by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                boxWidthPx = it.width.toFloat().coerceAtLeast(1f)
                boxHeightPx = it.height.toFloat().coerceAtLeast(1f)
            }
            .pointerInput(currentPositionMs, durationMs, splitPosition) {
                detectDragGestures(
                    onDragStart = { offset ->
                        activeMode = GestureMode.NONE
                        accumulatedDx = 0f
                        accumulatedDy = 0f

                        // Before/After比較ハンドルの周辺(±36px)を掴んだ場合はジェスチャーを譲る
                        val splitX = splitPosition * boxWidthPx
                        if (splitPosition in 0.01f..0.99f && abs(offset.x - splitX) < 36f) {
                            return@detectDragGestures
                        }

                        startPositionMs = currentPositionMs
                        pendingSeekMs = currentPositionMs

                        // 現在のアプリウィンドウ輝度を取得（未設定時は0.5fから開始）
                        val currentWinBright = activity?.window?.attributes?.screenBrightness ?: -1f
                        startBrightness = if (currentWinBright in 0.0f..1.0f) currentWinBright else 0.5f

                        // 現在のメディア音量割合 (0.0f 〜 1.0f) を取得
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        startVolumeFloat = curVol.toFloat() / maxVol.toFloat()

                        // 開始X座標が左半分なら輝度候補、右半分なら音量候補として準備
                        activeMode = if (offset.x < boxWidthPx * 0.5f) {
                            GestureMode.VERTICAL_BRIGHTNESS
                        } else {
                            GestureMode.VERTICAL_VOLUME
                        }
                    },
                    onDrag = { change, dragAmount ->
                        if (activeMode == GestureMode.NONE) return@detectDragGestures
                        change.consume()

                        accumulatedDx += dragAmount.x
                        accumulatedDy += dragAmount.y

                        // 初動の移動方向で「水平シーク」か「垂直(輝度/音量)」かを確定ロック
                        if (abs(accumulatedDx) > 24f && abs(accumulatedDx) > abs(accumulatedDy) * 1.3f) {
                            activeMode = GestureMode.HORIZONTAL_SEEK
                        }

                        when (activeMode) {
                            GestureMode.HORIZONTAL_SEEK -> {
                                if (durationMs > 0L) {
                                    // 画面幅いっぱいのスワイプで最大90秒シーク
                                    val deltaMs = ((accumulatedDx / boxWidthPx) * 90_000f).toLong()
                                    pendingSeekMs = (startPositionMs + deltaMs).coerceIn(0L, durationMs)
                                    val diffSec = (pendingSeekMs - startPositionMs) / 1000L
                                    val sign = if (diffSec >= 0) "+" else ""
                                    indicator = GestureIndicatorState(
                                        icon = if (diffSec >= 0) Icons.Default.FastForward else Icons.Default.FastRewind,
                                        label = "${formatMillis(pendingSeekMs)} (${sign}${diffSec}秒)"
                                    )
                                }
                            }

                            GestureMode.VERTICAL_BRIGHTNESS -> {
                                // 上方向スワイプ(accumulatedDy < 0)で明るくする
                                val delta = -accumulatedDy / (boxHeightPx * 0.75f)
                                val newBright = (startBrightness + delta).coerceIn(0.01f, 1.0f)
                                activity?.window?.let { win ->
                                    val params = win.attributes
                                    params.screenBrightness = newBright
                                    win.attributes = params
                                }
                                val percent = (newBright * 100f).roundToInt()
                                indicator = GestureIndicatorState(
                                    icon = Icons.Default.BrightnessHigh,
                                    label = "輝度: $percent%"
                                )
                            }

                            GestureMode.VERTICAL_VOLUME -> {
                                // 上方向スワイプ(accumulatedDy < 0)で音量を上げる
                                val delta = -accumulatedDy / (boxHeightPx * 0.75f)
                                val newVolRatio = (startVolumeFloat + delta).coerceIn(0.0f, 1.0f)
                                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                val targetVol = (newVolRatio * maxVol).roundToInt()
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                                val percent = (newVolRatio * 100f).roundToInt()
                                indicator = GestureIndicatorState(
                                    icon = Icons.Default.VolumeUp,
                                    label = "音量: $percent%"
                                )
                            }

                            GestureMode.NONE -> Unit
                        }
                    },
                    onDragEnd = {
                        if (activeMode == GestureMode.HORIZONTAL_SEEK && durationMs > 0L) {
                            onSeekTo(pendingSeekMs)
                        }
                        activeMode = GestureMode.NONE
                        indicator = null
                    },
                    onDragCancel = {
                        activeMode = GestureMode.NONE
                        indicator = null
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // スワイプ中のみ画面中央に半透明バッジを表示
        indicator?.let { state ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = state.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = state.label,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

private fun formatMillis(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
}
