package com.omniface.ai.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * Ambient Neon Sparkline Wave canvas component.
 * Renders the multi-frequency glowing sine wave seen in the Verification Speed
 * and Ledger card mockups.
 */
@Composable
fun NeonSparklineWave(
    modifier: Modifier = Modifier,
    height: Dp = 100.dp,
    waveColor1: Color = Color(0xFF6366F1),
    waveColor2: Color = Color(0xFF06B6D4),
    waveColor3: Color = Color(0xFFA855F7),
    showFill: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "NeonWaveAnimation")

    val phaseShift1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phaseShift1"
    )

    val phaseShift2 by infiniteTransition.animateFloat(
        initialValue = (2 * Math.PI).toFloat(),
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phaseShift2"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val width = size.width
            val height = size.height
            val midY = height * 0.55f

            // Primary Wave Path
            val path1 = Path()
            val fillPath1 = Path()
            val step = 4f
            var first = true

            fillPath1.moveTo(0f, height)

            for (x in 0..width.toInt() step step.toInt()) {
                val xNorm = (x / width) * 2f * Math.PI.toFloat()
                // Complex harmonics for organic biometric wave
                val yOffset = (sin(xNorm * 1.5f + phaseShift1) * 0.28f +
                        sin(xNorm * 3.0f - phaseShift2) * 0.12f) * height

                val y = midY + yOffset
                if (first) {
                    path1.moveTo(x.toFloat(), y)
                    fillPath1.lineTo(x.toFloat(), y)
                    first = false
                } else {
                    path1.lineTo(x.toFloat(), y)
                    fillPath1.lineTo(x.toFloat(), y)
                }
            }

            fillPath1.lineTo(width, height)
            fillPath1.close()

            // Optional ambient gradient fill under the wave
            if (showFill) {
                drawPath(
                    path = fillPath1,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            waveColor1.copy(alpha = 0.20f),
                            waveColor2.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        startY = midY - height * 0.3f,
                        endY = height
                    )
                )
            }

            // Glow Stroke 1 (Thick blurred layer)
            drawPath(
                path = path1,
                brush = Brush.horizontalGradient(
                    colors = listOf(waveColor1.copy(alpha = 0.45f), waveColor2.copy(alpha = 0.45f), waveColor3.copy(alpha = 0.45f))
                ),
                style = Stroke(width = 6.0f, cap = StrokeCap.Round)
            )

            // Sharp Foreground Wave Stroke 1
            drawPath(
                path = path1,
                brush = Brush.horizontalGradient(
                    colors = listOf(waveColor1, waveColor2, waveColor3)
                ),
                style = Stroke(width = 2.5f, cap = StrokeCap.Round)
            )

            // Secondary Wave Path (Secondary frequency harmonic)
            val path2 = Path()
            var first2 = true
            for (x in 0..width.toInt() step step.toInt()) {
                val xNorm = (x / width) * 2f * Math.PI.toFloat()
                val yOffset = (sin(xNorm * 2.0f - phaseShift2 * 0.8f) * 0.20f +
                        sin(xNorm * 4.0f + phaseShift1 * 0.5f) * 0.08f) * height

                val y = midY + yOffset
                if (first2) {
                    path2.moveTo(x.toFloat(), y)
                    first2 = false
                } else {
                    path2.lineTo(x.toFloat(), y)
                }
            }

            // Secondary Wave Stroke
            drawPath(
                path = path2,
                brush = Brush.horizontalGradient(
                    colors = listOf(waveColor3.copy(alpha = 0.6f), waveColor1.copy(alpha = 0.6f), waveColor2.copy(alpha = 0.6f))
                ),
                style = Stroke(width = 1.75f, cap = StrokeCap.Round)
            )
        }
    }
}
