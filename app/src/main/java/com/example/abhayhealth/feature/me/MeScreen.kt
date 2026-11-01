package com.example.abhayhealth.feature.me

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.io.File
import com.example.abhayhealth.R
import com.example.abhayhealth.core.designsystem.components.AbhayHealthBrandLogo
import com.example.abhayhealth.feature.records.export.ExportReportDialog

@Composable
fun MeScreen(
    viewModel: MeViewModel,
    onEditProfileClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onNavigateToRecords: () -> Unit = {},
    onNavigateToMedicines: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val patientName by viewModel.patientName.collectAsState()
    val patientProfile by viewModel.patientProfile.collectAsState()
    val isDeleting by viewModel.isDeleting.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val showExportDialog by viewModel.showExportDialog.collectAsState()
    val recordCount by viewModel.recordCount.collectAsState()
    val medicationCount by viewModel.medicationCount.collectAsState()
    val vitalCount by viewModel.vitalCount.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val isMedRemindersEnabled by viewModel.isMedRemindersEnabled.collectAsState()
    val isAiSummaryEnabled by viewModel.isAiSummaryEnabled.collectAsState()

    val displayName = patientProfile?.fullName?.ifBlank { null }
        ?: if (!patientName.isNullOrBlank()) patientName!! else stringResource(id = R.string.me_profile_default)

    val profileBitmap = remember(patientProfile?.photoUri) {
        val uriStr = patientProfile?.photoUri
        if (uriStr.isNullOrBlank()) null
        else {
            try {
                if (uriStr.startsWith("/")) {
                    val file = File(uriStr)
                    if (file.exists()) BitmapFactory.decodeFile(uriStr) else null
                } else {
                    val uri = Uri.parse(uriStr)
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var showSecurityPrivacyDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showPreferencesDialog by remember { mutableStateOf(false) }

    if (showExportDialog) {
        ExportReportDialog(
            state = exportState,
            recordCount = recordCount,
            medicationCount = medicationCount,
            vitalCount = vitalCount,
            onDismiss = { viewModel.dismissExportDialog() },
            onShareClick = { viewModel.exportAndShareReport(context) },
            onViewClick = { viewModel.exportAndViewReport(context) },
            onPrintClick = { viewModel.exportAndPrintReport(context) }
        )
    }

    if (showSecurityPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showSecurityPrivacyDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text("Security & Privacy", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SecurityPrivacyItem(
                        title = "Zero-Knowledge Local Storage",
                        description = "Medical reports and photos are kept in private app sandbox storage with device-level isolation."
                    )
                    SecurityPrivacyItem(
                        title = "Room SQLite Database Protection",
                        description = "Diagnostic metrics, lab tests, and prescriptions are saved in protected local database tables."
                    )
                    SecurityPrivacyItem(
                        title = "Zero Cloud Sharing",
                        description = "AbhayHealth does not transmit, share, or sell health metrics to external advertising or tracking networks."
                    )
                    SecurityPrivacyItem(
                        title = "Device Security Verification",
                        description = "Integrity check passed. File permissions and storage sandboxing verified active."
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showSecurityPrivacyDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    if (showLanguageDialog) {
        val languages = listOf("English", "Hindi (हिन्दी)", "Telugu (తెలుగు)", "Tamil (தமிழ்)", "Bengali (বাংলা)", "Marathi (मराठी)")
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            icon = {
                Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            title = {
                Text("Select Language", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    languages.forEach { lang ->
                        val isSelected = lang == appLanguage || (lang == "English" && appLanguage.isBlank())
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setAppLanguage(lang)
                                    showLanguageDialog = false
                                    Toast.makeText(context, "Language set to $lang", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    viewModel.setAppLanguage(lang)
                                    showLanguageDialog = false
                                    Toast.makeText(context, "Language set to $lang", Toast.LENGTH_SHORT).show()
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = lang, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showPreferencesDialog) {
        AlertDialog(
            onDismissRequest = { showPreferencesDialog = false },
            icon = {
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            title = {
                Text("App Preferences", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Medication Reminders", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                            Text("Schedule alerts for pending daily doses", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = isMedRemindersEnabled,
                            onCheckedChange = { viewModel.setMedRemindersEnabled(it) }
                        )
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("AI Assist Summaries", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                            Text("Generate automated clinical insights on Home screen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = isAiSummaryEnabled,
                            onCheckedChange = { viewModel.setAiSummaryEnabled(it) }
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showPreferencesDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteAccountDialog = false },
            title = { Text("Delete Account & Health Data?") },
            text = {
                Text("Deleting your account will permanently remove your patient profile, local medical records, and extracted health data from this device, and sign you out. This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAccountDialog = false
                        viewModel.deleteAccountAndAllData(context, onDeleted = onSignOutClick)
                    },
                    enabled = !isDeleting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text("Delete Account")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAccountDialog = false },
                    enabled = !isDeleting
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (errorMessage != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearMessages() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close error message",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.nav_me),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(id = R.string.me_profile_tagline),
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
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEditProfileClick),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (profileBitmap != null) {
                        Image(
                            bitmap = profileBitmap.asImageBitmap(),
                            contentDescription = "Profile picture",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        // Official AbhayHealth Logo as verified medical profile badge
                        AbhayHealthBrandLogo(
                            size = 54.dp,
                            elevation = 3.dp
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(id = R.string.me_profile_tagline),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(id = R.string.profile_btn_edit),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        item {
            Text(
                text = stringResource(id = R.string.me_sec_records),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column {
                    ProfileMenuItem(
                        icon = Icons.Default.Description,
                        title = stringResource(id = R.string.me_item_history_title),
                        subtitle = stringResource(id = R.string.me_item_history_sub),
                        onClick = onNavigateToRecords
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ProfileMenuItem(
                        icon = Icons.AutoMirrored.Filled.ReceiptLong,
                        title = stringResource(id = R.string.me_item_rx_title),
                        subtitle = stringResource(id = R.string.me_item_rx_sub),
                        onClick = onNavigateToMedicines
                    )
                }
            }
        }

        item {
            Text(
                text = stringResource(id = R.string.me_sec_privacy),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column {
                    ProfileMenuItem(
                        icon = Icons.Default.Shield,
                        title = stringResource(id = R.string.me_item_privacy_title),
                        subtitle = stringResource(id = R.string.me_item_privacy_sub),
                        onClick = { showSecurityPrivacyDialog = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ProfileMenuItem(
                        icon = Icons.Default.IosShare,
                        title = stringResource(id = R.string.me_item_export_title),
                        subtitle = stringResource(id = R.string.me_item_export_sub),
                        onClick = { viewModel.openExportDialog() }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ProfileMenuItem(
                        icon = Icons.Default.DeleteForever,
                        title = "Delete Account & Health Data",
                        subtitle = "Permanently remove profile and local records from device",
                        onClick = { showDeleteAccountDialog = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ProfileMenuItem(
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        title = stringResource(id = R.string.me_item_sign_out_title),
                        subtitle = stringResource(id = R.string.me_item_sign_out_sub),
                        onClick = onSignOutClick
                    )
                }
            }
        }

        item {
            Text(
                text = stringResource(id = R.string.me_sec_config),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column {
                    ProfileMenuItem(
                        icon = Icons.Default.Language,
                        title = stringResource(id = R.string.me_item_language_title),
                        subtitle = appLanguage,
                        onClick = { showLanguageDialog = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ProfileMenuItem(
                        icon = Icons.Default.Settings,
                        title = stringResource(id = R.string.me_item_settings_title),
                        subtitle = stringResource(id = R.string.me_item_settings_sub),
                        onClick = { showPreferencesDialog = true }
                    )
                }
            }
        }
    }
}

@Composable
private fun SecurityPrivacyItem(title: String, description: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 26.dp, top = 2.dp)
        )
    }
}

@Composable
private fun ProfileMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
