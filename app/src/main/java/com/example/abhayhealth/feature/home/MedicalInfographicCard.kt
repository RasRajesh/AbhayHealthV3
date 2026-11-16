package com.example.abhayhealth.feature.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import com.example.abhayhealth.domain.model.ParsedMedicalResult
import com.example.abhayhealth.domain.model.BiomarkerStatus
import com.example.abhayhealth.domain.model.OrganSystem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * surrounded by modern digital callout boxes for Blood Pressure, Blood Sugar, Heart Rate, and SpO2,
 * plus interactive telemetry pills for extracted clinical biomarkers.
 */
@Composable
fun MedicalInfographicCard(
    modifier: Modifier = Modifier,
    bloodPressure: String = "120/80 mmHg",
    bloodSugar: String = "95 mg/dL",
    heartRate: String = "72 BPM",
    spO2: String = "98%",
    extractedBiomarkers: List<ParsedMedicalResult> = emptyList(),
    onMetricClick: (String, ParsedMedicalResult?) -> Unit = { _, _ -> },
    onLogVitalClick: () -> Unit = {}
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

    var selectedOrganSystem by remember { mutableStateOf(OrganSystem.ALL) }
    val outOfRangeBiomarkers = remember(extractedBiomarkers) {
        extractedBiomarkers.filter { it.isOutOfRange }
    }
    val outOfRangeCount = outOfRangeBiomarkers.size
    val displayBiomarkers = remember(outOfRangeBiomarkers, selectedOrganSystem) {
        if (selectedOrganSystem == OrganSystem.ALL) {
            outOfRangeBiomarkers
        } else {
            outOfRangeBiomarkers.filter { it.organSystem == selectedOrganSystem }
        }
    }

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
            // Header: Clean unconstrained title and + Log Vital button
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
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981))
                        )
                        Text(
                            text = stringResource(id = R.string.home_infographic_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = softWhite
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = stringResource(id = R.string.home_infographic_status),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                FilledTonalButton(
                    onClick = onLogVitalClick,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = neonBlue.copy(alpha = 0.22f),
                        contentColor = neonBlue
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("+ Log Vital", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Out-of-Range Clinical Warning Banner
            if (outOfRangeCount > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF451A03), // Deep Amber/Red
                    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFF59E0B))
                        )
                        Text(
                            text = "⚠ $outOfRangeCount LAB MARKERS REQUIRE PHYSICIAN REVIEW",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.5.sp
                            ),
                            color = Color(0xFFFDE68A)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Spacer(modifier = Modifier.height(14.dp))

            // High-Tech Telemetry Status Header (3D Silhouette removed per user request)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF090D16),
                border = BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                            text = "LIVE CLINICAL BIOMETRICS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = neonCyan
                        )
                    }

                    Surface(
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = selectedOrganSystem.displayName.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = neonCyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

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
                    onClick = { onMetricClick("Blood Pressure", null) },
                    modifier = Modifier.weight(1f)
                )
                MetricCalloutBox(
                    title = "BLOOD SUGAR",
                    value = bloodSugar,
                    status = "95 mg/dL Fasting",
                    accentColor = Color(0xFF34D399),
                    icon = Icons.Default.Science,
                    onClick = { onMetricClick("Blood Sugar", null) },
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
                    onClick = { onMetricClick("Heart Rate", null) },
                    modifier = Modifier.weight(1f)
                )
                MetricCalloutBox(
                    title = "SPO2 (OXYGEN)",
                    value = spO2,
                    status = "98% Arterial",
                    accentColor = Color(0xFF60A5FA),
                    icon = Icons.Default.Air,
                    onClick = { onMetricClick("SpO2", null) },
                    modifier = Modifier.weight(1f)
                )
            }

            if (extractedBiomarkers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = cardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // Organ System Filter Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OrganSystem.values().forEach { organ ->
                        val count = if (organ == OrganSystem.ALL) {
                            extractedBiomarkers.size
                        } else {
                            extractedBiomarkers.count { it.organSystem == organ }
                        }
                        val isSelected = selectedOrganSystem == organ

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) neonCyan.copy(alpha = 0.22f) else Color(0xFF131D31),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) neonCyan else Color(0xFF1E293B)
                            ),
                            modifier = Modifier.clickable { selectedOrganSystem = organ }
                        ) {
                            Text(
                                text = "${organ.displayName} ($count)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (isSelected) neonCyan else Color(0xFF94A3B8),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "EXTRACTED CLINICAL BIOMARKERS [${selectedOrganSystem.code}]",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        ),
                        color = neonCyan
                    )
                    Text(
                        text = "${displayBiomarkers.size} METRICS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        ),
                        color = Color(0xFF64748B)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    displayBiomarkers.take(25).forEach { bio ->
                        val status = bio.status
                        val statusColor = when (status) {
                            BiomarkerStatus.CRITICAL_HIGH, BiomarkerStatus.CRITICAL_LOW -> Color(0xFFEF4444)
                            BiomarkerStatus.HIGH, BiomarkerStatus.LOW, BiomarkerStatus.QUALITATIVE_ABNORMAL -> Color(0xFFF59E0B)
                            BiomarkerStatus.NORMAL -> Color(0xFF10B981)
                            else -> Color(0xFF94A3B8)
                        }

                        val statusBadge = when (status) {
                            BiomarkerStatus.CRITICAL_HIGH -> "CRIT ▲"
                            BiomarkerStatus.CRITICAL_LOW -> "CRIT ▼"
                            BiomarkerStatus.HIGH -> "HIGH ▲"
                            BiomarkerStatus.LOW -> "LOW ▼"
                            BiomarkerStatus.QUALITATIVE_ABNORMAL -> "ABN ⚠"
                            BiomarkerStatus.NORMAL -> "NORM ✓"
                            else -> ""
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF131D31),
                            border = BorderStroke(
                                1.dp,
                                if (bio.isOutOfRange) statusColor.copy(alpha = 0.7f) else neonBlue.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier.clickable { onMetricClick(bio.testName, bio) }
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = bio.testName.uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = Color(0xFF94A3B8)
                                    )
                                    if (statusBadge.isNotEmpty()) {
                                        Text(
                                            text = statusBadge,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 7.5.sp,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = statusColor
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${bio.value} ${bio.unit}".trim(),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = if (bio.isOutOfRange) statusColor else neonBlue
                                )
                                if (bio.referenceRange.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(1.dp))
                                    Text(
                                        text = "Ref: ${bio.referenceRange}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 7.5.sp
                                        ),
                                        color = Color(0xFF64748B)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
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
