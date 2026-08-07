package com.gaylord.duit.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate

@Composable
fun BotanicalMark(modifier: Modifier = Modifier) {
    val stem = MaterialTheme.colorScheme.primary
    val leaf = MaterialTheme.colorScheme.primaryContainer
    val bloom = MaterialTheme.colorScheme.tertiary

    Canvas(modifier = modifier) {
        drawCircle(
            color = leaf.copy(alpha = 0.72f),
            radius = size.minDimension / 2,
        )
        drawLine(
            color = stem,
            start = Offset(size.width * 0.45f, size.height * 0.82f),
            end = Offset(size.width * 0.55f, size.height * 0.24f),
            strokeWidth = size.minDimension * 0.045f,
            cap = StrokeCap.Round,
        )
        rotate(
            degrees = -30f,
            pivot = Offset(size.width * 0.42f, size.height * 0.55f),
        ) {
            drawOval(
                color = stem,
                topLeft = Offset(size.width * 0.18f, size.height * 0.46f),
                size = androidx.compose.ui.geometry.Size(
                    size.width * 0.33f,
                    size.height * 0.16f,
                ),
            )
        }
        rotate(
            degrees = 28f,
            pivot = Offset(size.width * 0.58f, size.height * 0.39f),
        ) {
            drawOval(
                color = stem,
                topLeft = Offset(size.width * 0.52f, size.height * 0.31f),
                size = androidx.compose.ui.geometry.Size(
                    size.width * 0.30f,
                    size.height * 0.15f,
                ),
            )
        }
        drawCircle(
            color = bloom,
            radius = size.minDimension * 0.085f,
            center = Offset(size.width * 0.56f, size.height * 0.22f),
        )
    }
}
