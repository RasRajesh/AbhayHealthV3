package com.example.abhayhealth.feature.trends

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.abhayhealth.R
import com.example.abhayhealth.core.designsystem.components.AbhayHealthBrandLogo
import com.example.abhayhealth.core.designsystem.components.EmptyStateContent
import com.example.abhayhealth.feature.symptoms.AddSymptomDialog
import com.example.abhayhealth.feature.symptoms.SymptomTrendsComponent

@Composable
fun TrendsScreen(
    viewModel: TrendsViewModel,
    modifier: Modifier = Modifier
) {
    val labTrends by viewModel.labTrends.collectAsState()
    val vitalsTrends by viewModel.vitalsTrends.collectAsState()
    val allTrends by viewModel.biomarkerTrends.collectAsState()
    val symptoms by viewModel.symptoms.collectAsState()
    val showAddSymptomDialog by viewModel.showAddSymptomDialog.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf(
        stringResource(id = R.string.trends_tab_labs),
        stringResource(id = R.string.trends_tab_vitals),
        stringResource(id = R.string.trends_tab_symptoms),
        stringResource(id = R.string.trends_tab_adherence)
    )

    if (showAddSymptomDialog) {
        AddSymptomDialog(
            onDismiss = { viewModel.dismissAddSymptomDialog() },
            onSave = { name, severity, score, notes, triggers, duration ->
                viewModel.logSymptom(name, severity, score, notes, triggers, duration)
            }
        )
    }

    val displayedTrends = when (selectedTabIndex) {
        0 -> labTrends.ifEmpty { allTrends.filter { it.category == "LABS" } }
        1 -> vitalsTrends.ifEmpty { allTrends.filter { it.category == "VITALS" } }
        else -> emptyList()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.trends_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(id = R.string.trends_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            // Official AbhayHealth brand launcher logo
            AbhayHealthBrandLogo(
                size = 46.dp,
                elevation = 2.dp
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(text = title) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTabIndex) {
            2 -> {
                // Symptom Tracking & Frequency Tab
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        SymptomTrendsComponent(
                            symptoms = symptoms,
                            onAddSymptomClick = { viewModel.openAddSymptomDialog() },
                            onDeleteSymptomClick = { viewModel.deleteSymptom(it) }
                        )
                    }
                }
            }
            3 -> {
                // Adherence Tab
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            EmptyStateContent(
                                title = stringResource(id = R.string.trends_empty_adherence_title),
                                description = stringResource(id = R.string.trends_empty_adherence_desc),
                                icon = Icons.AutoMirrored.Filled.TrendingUp
                            )
                        }
                    }
                }
            }
            else -> {
                if (displayedTrends.isEmpty()) {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                when (selectedTabIndex) {
                                    0 -> EmptyStateContent(
                                        title = stringResource(id = R.string.trends_empty_labs_title),
                                        description = stringResource(id = R.string.trends_empty_labs_desc),
                                        icon = Icons.Default.Science
                                    )
                                    else -> EmptyStateContent(
                                        title = stringResource(id = R.string.trends_empty_vitals_title),
                                        description = stringResource(id = R.string.trends_empty_vitals_desc),
                                        icon = Icons.Default.MonitorHeart
                                    )
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(
                            items = displayedTrends,
                            key = { it.biomarkerName }
                        ) { trend ->
                            BiomarkerTrendCard(trend = trend)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BiomarkerTrendCard(trend: BiomarkerTrend) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = trend.biomarkerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (!trend.clinicalGuidelineTag.isNull_or_blank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = trend.clinicalGuidelineTag!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Text(
                    text = "${trend.latestValue} ${trend.unit}".trim(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!trend.referenceRange.isNull_or_blank()) {
                Text(
                    text = "Ref: ${trend.referenceRange}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = "${trend.points.size} records tracked",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

private fun String?.isNull_or_blank(): Boolean = this == null || this.isBlank()
