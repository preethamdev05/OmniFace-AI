package com.omniface.ai.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 3D Quantum Biometric Energy Orb.
 * Renders the glowing multi-chromatic sphere with quantum orbital rings,
 * spherical 3D illumination, and ambient violet-cyan luminescence as seen in the mockups.
 */
@Composable
fun BiometricEnergyOrb(
    modifier: Modifier = Modifier,
    size: Dp = 140.dp,
    showRings: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "OrbPulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val ringRotation1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringRotation1"
    )

    val ringRotation2 by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringRotation2"
    )

    val coreGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.70f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "coreGlowAlpha"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val canvasWidth = this.size.width
            val canvasHeight = this.size.height
            val center = Offset(canvasWidth / 2f, canvasHeight / 2f)
            val sphereRadius = (canvasWidth.coerceAtMost(canvasHeight) / 2f) * 0.78f * pulseScale

            // 1. Outer Ambient Radial Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x556366F1),
                        Color(0x338B5CF6),
                        Color(0x1506B6D4),
                        Color.Transparent
                    ),
                    center = center,
                    radius = sphereRadius * 1.45f
                ),
                radius = sphereRadius * 1.45f,
                center = center
            )

            // 2. Base 3D Sphere Surface with multi-stop radial gradient (light source at top-left)
            val lightSource = Offset(center.x - sphereRadius * 0.35f, center.y - sphereRadius * 0.35f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF93C5FD).copy(alpha = coreGlowAlpha),
                        Color(0xFF38BDF8),
                        Color(0xFF6366F1),
                        Color(0xFF4338CA),
                        Color(0xFF1E1B4B)
                    ),
                    center = lightSource,
                    radius = sphereRadius * 1.15f
                ),
                radius = sphereRadius,
                center = center
            )

            // 3. Quantum Orbital Rings (if enabled)
            if (showRings) {
                // Ring 1: Tilted cyan-violet ellipse
                rotate(degrees = ringRotation1, pivot = center) {
                    val ovalWidth = sphereRadius * 1.95f
                    val ovalHeight = sphereRadius * 0.65f
                    drawOval(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0x9938BDF8),
                                Color(0xCCFFFFFF),
                                Color(0x99A855F7),
                                Color(0x2238BDF8)
                            )
                        ),
                        topLeft = Offset(center.x - ovalWidth / 2f, center.y - ovalHeight / 2f),
                        size = Size(ovalWidth, ovalHeight),
                        style = Stroke(width = 2.5f)
                    )
                }

                // Ring 2: Reverse angled ring with magenta-cyan gradient
                rotate(degrees = ringRotation2 + 45f, pivot = center) {
                    val ovalWidth = sphereRadius * 1.85f
                    val ovalHeight = sphereRadius * 0.55f
                    drawOval(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0x88C084FC),
                                Color(0xEE818CF8),
                                Color(0xBB06B6D4),
                                Color(0x18C084FC)
                            )
                        ),
                        topLeft = Offset(center.x - ovalWidth / 2f, center.y - ovalHeight / 2f),
                        size = Size(ovalWidth, ovalHeight),
                        style = Stroke(width = 2.0f)
                    )
                }
            }

            // 4. Internal Curved Biometric Luminous Arc
            val arcPath = Path().apply {
                moveTo(center.x - sphereRadius * 0.65f, center.y + sphereRadius * 0.15f)
                cubicTo(
                    center.x - sphereRadius * 0.2f, center.y + sphereRadius * 0.65f,
                    center.x + sphereRadius * 0.2f, center.y + sphereRadius * 0.65f,
                    center.x + sphereRadius * 0.65f, center.y + sphereRadius * 0.15f
                )
            }
            drawPath(
                path = arcPath,
                brush = Brush.horizontalGradient(
                    listOf(
                        Color(0x11FFFFFF),
                        Color(0xDDFFFFFF),
                        Color(0xAA38BDF8),
                        Color(0x11FFFFFF)
                    )
                ),
                style = Stroke(width = 3.5f, cap = StrokeCap.Round)
            )

            // 5. Specular Highlights on top crest
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.85f),
                        Color.White.copy(alpha = 0.35f),
                        Color.Transparent
                    ),
                    center = lightSource,
                    radius = sphereRadius * 0.38f
                ),
                radius = sphereRadius * 0.38f,
                center = lightSource
            )
        }
    }
}
