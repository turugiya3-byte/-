package com.example.videoenhancer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SubtitleOverlay(
    cueLines: List<String>,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 20.sp
) {
    if (cueLines.isEmpty()) return
    val combinedText = cueLines.joinToString("\n").trim()
    if (combinedText.isEmpty()) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Text(
            text = combinedText,
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontSize = fontSize,
                lineHeight = fontSize * 1.28f,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                drawStyle = Stroke(miter = 10f, width = 6f, join = StrokeJoin.Round),
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.85f),
                    offset = Offset(2f, 3f),
                    blurRadius = 4f
                )
            )
        )
        Text(
            text = combinedText,
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontSize = fontSize,
                lineHeight = fontSize * 1.28f,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF8FAFC)
            )
        )
    }
}
