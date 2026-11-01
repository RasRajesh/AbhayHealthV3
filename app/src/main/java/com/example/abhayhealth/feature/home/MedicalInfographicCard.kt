package com.example.abhayhealth.feature.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.abhayhealth.R

/**
 * High-tech Medical Infographic Data Visualization.
 * Features a central translucent 3D human body silhouette with isometric perspective,
 * surrounded by modern digital callout boxes for Blood Pressure, Blood Sugar, Heart Rate, and SpO2.
 */
@Composable
fun MedicalInfographicCard(
    bloodPressure: String = "120/80 mmHg",
    bloodSugar: String = "95 mg/dL",
    heartRate: String = "72 BPM",
    spO2: String = "98%",
    onMetricClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val darkSlateBg = Color(0xFF0F172A)
    val neonBlue = Color(0xFF38BDF8)
    val neonCyan = Color(0xFF06B6D4)
    val softWhite = Color(0xFFF8FAFC)
    val cardBorder = Color(0xFF1E293B)

    // Pulsing animation for high-tech HUD effect
    val infiniteTransition = rememberInfiniteTransition(label = "hudPulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseProgress"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("medical_infographic_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = darkSlateBg),
        border = BorderStroke(1.dp, neonBlue.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Header: Status badge & Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(neonCyan)
                        )
                        Text(
                            text = stringResource(id = R.string.home_infographic_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = softWhite
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(id = R.string.home_infographic_status),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }

                Surface(
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, neonBlue.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = stringResource(id = R.string.home_infographic_badge),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        ),
                        color = neonBlue,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Central Translucent 3D Silhouette + Connecting Telemetry Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF090D16))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                TranslucentBodyCanvas(
                    pulse = pulseProgress,
                    neonBlue = neonBlue,
                    neonCyan = neonCyan
                )

                // High-tech overlay coordinates & perspective tags
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                ) {
                    Text(
                        text = "SYS_ISO // 3D-MODEL",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        ),
                        color = neonCyan.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                    Text(
                        text = "SCALE 1:1 • BIOMETRICS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        ),
                        color = Color(0xFF64748B),
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Surrounding Digital Callout Boxes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCalloutBox(
                    title = "BLOOD PRESSURE",
                    value = bloodPressure,
                    status = "120/80 Optimal",
                    accentColor = Color(0xFF38BDF8),
                    icon = Icons.Default.Speed,
                    onClick = { onMetricClick("Blood Pressure") },
                    modifier = Modifier.weight(1f)
                )
                MetricCalloutBox(
                    title = "BLOOD SUGAR",
                    value = bloodSugar,
                    status = "95 mg/dL Fasting",
                    accentColor = Color(0xFF34D399),
                    icon = Icons.Default.Science,
                    onClick = { onMetricClick("Blood Sugar") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCalloutBox(
                    title = "HEART RATE",
                    value = heartRate,
                    status = "72 BPM Resting",
                    accentColor = Color(0xFFF43F5E),
                    icon = Icons.Default.MonitorHeart,
                    onClick = { onMetricClick("Heart Rate") },
                    modifier = Modifier.weight(1f)
                )
                MetricCalloutBox(
                    title = "SPO2 (OXYGEN)",
                    value = spO2,
                    status = "98% Arterial",
                    accentColor = Color(0xFF60A5FA),
                    icon = Icons.Default.Air,
                    onClick = { onMetricClick("SpO2") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Renders the central translucent 3D human body silhouette with isometric perspective,
 * isometric floor grid, scanning core, and glowing biometric telemetry locator nodes.
 */
@Composable
private fun TranslucentBodyCanvas(
    pulse: Float,
    neonBlue: Color,
    neonCyan: Color
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        // 1. Isometric Floor Grid
        val floorY = h * 0.82f
        val gridLines = 7
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)

        for (i in 0..gridLines) {
            val yOffset = floorY + (i * 6f)
            val spread = (w * 0.42f) + (i * 12f)
            drawLine(
                color = neonBlue.copy(alpha = 0.12f + (i * 0.02f)),
                start = Offset(cx - spread, yOffset),
                end = Offset(cx + spread, yOffset),
                strokeWidth = 1f
            )
        }

        // Isometric perspective diagonal projection lines
        for (i in -4..4) {
            val startX = cx + (i * 28f)
            val endX = cx + (i * 62f)
            drawLine(
                color = neonCyan.copy(alpha = 0.14f),
                start = Offset(startX, floorY - 10f),
                end = Offset(endX, h - 4f),
                strokeWidth = 1.2f,
                pathEffect = dashEffect
            )
        }

        // 2. Isometric Base Pedestal Rings
        drawOval(
            color = neonCyan.copy(alpha = 0.22f),
            topLeft = Offset(cx - 70f, floorY - 14f),
            size = Size(140f, 32f),
            style = Stroke(width = 2f)
        )
        drawOval(
            color = neonBlue.copy(alpha = 0.12f),
            topLeft = Offset(cx - 95f, floorY - 20f),
            size = Size(190f, 44f),
            style = Stroke(width = 1.5f, pathEffect = dashEffect)
        )

        // 3. Central Translucent 3D Human Body Silhouette
        // Proportions mapped to canvas height (approx 180dp vertical scale)
        val headY = h * 0.12f
        val headRadius = 14f

        // Head Silhouette
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(neonCyan.copy(alpha = 0.55f), neonBlue.copy(alpha = 0.18f)),
                center = Offset(cx, headY),
                radius = headRadius
            ),
            radius = headRadius,
            center = Offset(cx, headY)
        )
        drawCircle(
            color = neonCyan.copy(alpha = 0.8f),
            radius = headRadius,
            center = Offset(cx, headY),
            style = Stroke(width = 1.8f)
        )

        // Body Anatomical Path (Neck, Shoulders, Torso, Arms, Pelvis, Legs)
        val bodyPath = Path().apply {
            // Neck
            moveTo(cx - 5f, headY + headRadius)
            lineTo(cx - 26f, headY + headRadius + 12f) // Left shoulder
            lineTo(cx - 40f, headY + headRadius + 44f) // Left upper arm
            lineTo(cx - 46f, headY + headRadius + 75f) // Left forearm / wrist
            lineTo(cx - 36f, headY + headRadius + 75f)
            lineTo(cx - 28f, headY + headRadius + 48f) // Inner left arm
            lineTo(cx - 22f, headY + headRadius + 60f) // Left waist
            lineTo(cx - 24f, headY + headRadius + 85f) // Left hip
            lineTo(cx - 20f, floorY - 10f)              // Left leg to ankle
            lineTo(cx - 6f, floorY - 10f)               // Left foot base
            lineTo(cx - 4f, headY + headRadius + 90f)  // Crotch
            lineTo(cx + 4f, headY + headRadius + 90f)
            lineTo(cx + 6f, floorY - 10f)               // Right foot base
            lineTo(cx + 20f, floorY - 10f)              // Right leg to ankle
            lineTo(cx + 24f, headY + headRadius + 85f) // Right hip
            lineTo(cx + 22f, headY + headRadius + 60f) // Right waist
            lineTo(cx + 28f, headY + headRadius + 48f) // Inner right arm
            lineTo(cx + 36f, headY + headRadius + 75f)
            lineTo(cx + 46f, headY + headRadius + 75f) // Right forearm / wrist
            lineTo(cx + 40f, headY + headRadius + 44f) // Right upper arm
            lineTo(cx + 26f, headY + headRadius + 12f) // Right shoulder
            lineTo(cx + 5f, headY + headRadius)
            close()
        }

        // Translucent gradient fill for the 3D body silhouette
        drawPath(
            path = bodyPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    neonCyan.copy(alpha = 0.40f),
                    neonBlue.copy(alpha = 0.22f),
                    Color(0xFF1E3A8A).copy(alpha = 0.28f)
                ),
                startY = headY,
                endY = floorY
            )
        )

        // Holographic Outer Contour Stroke
        drawPath(
            path = bodyPath,
            color = neonCyan.copy(alpha = 0.75f),
            style = Stroke(width = 2f, cap = StrokeCap.Round)
        )

        // 4. Glowing Neural / Spinal Core Line
        val spineStart = headY + headRadius + 6f
        val spineEnd = headY + headRadius + 85f
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White, neonCyan, neonBlue.copy(alpha = 0.3f)),
                startY = spineStart,
                endY = spineEnd
            ),
            start = Offset(cx, spineStart),
            end = Offset(cx, spineEnd),
            strokeWidth = 2.5f
        )

        // 5. Horizontal Moving Laser Scanning Beam
        val scanY = headY + (floorY - headY) * pulse
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    neonCyan.copy(alpha = 0.85f),
                    Color.White.copy(alpha = 0.95f),
                    neonCyan.copy(alpha = 0.85f),
                    Color.Transparent
                ),
                startX = cx - 70f,
                endX = cx + 70f
            ),
            start = Offset(cx - 70f, scanY),
            end = Offset(cx + 70f, scanY),
            strokeWidth = 2.2f
        )

        // 6. Biometric Telemetry Locator Nodes with Glowing Beacon Rings
        // Node 1: Heart (Left chest) -> Heart Rate
        val heartPos = Offset(cx - 10f, headY + headRadius + 32f)
        drawBeaconNode(heartPos, Color(0xFFF43F5E), pulse)

        // Node 2: Abdomen (Metabolic) -> Blood Sugar
        val sugarPos = Offset(cx + 4f, headY + headRadius + 56f)
        drawBeaconNode(sugarPos, Color(0xFF34D399), pulse)

        // Node 3: Right Forearm / Wrist -> Blood Pressure
        val bpPos = Offset(cx + 41f, headY + headRadius + 70f)
        drawBeaconNode(bpPos, Color(0xFF38BDF8), pulse)

        // Node 4: Left Arm / Lung -> SpO2
        val spo2Pos = Offset(cx - 28f, headY + headRadius + 36f)
        drawBeaconNode(spo2Pos, Color(0xFF60A5FA), pulse)

        // 7. Connector Telemetry Leader Lines from Nodes to Bounds
        // Leader line 1: Blood Pressure (toward bottom right)
        drawLeaderLine(
            start = bpPos,
            mid = Offset(bpPos.x + 24f, bpPos.y - 12f),
            end = Offset(w - 20f, bpPos.y - 12f),
            color = Color(0xFF38BDF8).copy(alpha = 0.6f)
        )

        // Leader line 2: Heart Rate (toward left)
        drawLeaderLine(
            start = heartPos,
            mid = Offset(heartPos.x - 30f, heartPos.y - 10f),
            end = Offset(20f, heartPos.y - 10f),
            color = Color(0xFFF43F5E).copy(alpha = 0.6f)
        )

        // Leader line 3: Blood Sugar (toward bottom right)
        drawLeaderLine(
            start = sugarPos,
            mid = Offset(sugarPos.x + 36f, sugarPos.y + 16f),
            end = Offset(w - 20f, sugarPos.y + 16f),
            color = Color(0xFF34D399).copy(alpha = 0.6f)
        )

        // Leader line 4: SpO2 (toward top left)
        drawLeaderLine(
            start = spo2Pos,
            mid = Offset(spo2Pos.x - 30f, spo2Pos.y + 18f),
            end = Offset(20f, spo2Pos.y + 18f),
            color = Color(0xFF60A5FA).copy(alpha = 0.6f)
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBeaconNode(
    center: Offset,
    accentColor: Color,
    pulse: Float
) {
    // Outer expanding glow ring
    drawCircle(
        color = accentColor.copy(alpha = (1f - pulse) * 0.45f),
        radius = 8f + (pulse * 8f),
        center = center,
        style = Stroke(width = 1.5f)
    )
    // Static base ring
    drawCircle(
        color = Color.White,
        radius = 5f,
        center = center
    )
    // Bright core
    drawCircle(
        color = accentColor,
        radius = 3.5f,
        center = center
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLeaderLine(
    start: Offset,
    mid: Offset,
    end: Offset,
    color: Color
) {
    val path = Path().apply {
        moveTo(start.x, start.y)
        lineTo(mid.x, mid.y)
        lineTo(end.x, end.y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = 1.4f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
        )
    )
    drawCircle(
        color = color,
        radius = 2.5f,
        center = end
    )
}

/**
 * Modern High-Tech Callout Box displaying an individual health metric
 * with neon accent, clean typography, icon, and status badge.
 */
@Composable
private fun MetricCalloutBox(
    title: String,
    value: String,
    status: String,
    accentColor: Color,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .testTag("metric_callout_${title.lowercase().replace(" ", "_")}"),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF131D31),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color(0xFF94A3B8)
                )

                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                ),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = accentColor.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
