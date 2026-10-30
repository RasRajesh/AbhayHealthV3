package `in`.abhayhealth.app.feature.medicines

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.abhayhealth.app.R
import `in`.abhayhealth.app.core.designsystem.components.EmptyStateContent

@Composable
fun MedicinesScreen(
    viewModel: MedicinesViewModel,
    modifier: Modifier = Modifier
) {
    val medications by viewModel.medications.collectAsState()
    val showAddDialog by viewModel.showAddDialog.collectAsState()

    if (showAddDialog) {
        AddMedicationDialog(
            onDismiss = { viewModel.closeAddDialog() },
            onAdd = { name, dosage, slot ->
                viewModel.addMedication(name, dosage, slot)
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
        item {
            Column {
                Text(
                    text = stringResource(id = R.string.meds_title),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(id = R.string.meds_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { viewModel.openAddDialog() },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(id = R.string.meds_btn_add))
                }

                OutlinedButton(
                    onClick = { viewModel.openAddDialog() },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(imageVector = Icons.Default.Alarm, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(id = R.string.meds_btn_reminders))
                }
            }
        }

        item {
            Text(
                text = stringResource(id = R.string.meds_section_schedule),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        if (medications.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    EmptyStateContent(
                        title = stringResource(id = R.string.meds_empty_schedule_title),
                        description = stringResource(id = R.string.meds_empty_schedule_desc),
                        icon = Icons.Default.Medication,
                        actionLabel = stringResource(id = R.string.meds_btn_add),
                        onActionClick = { viewModel.openAddDialog() }
                    )
                }
            }
        } else {
            val slots = listOf("MORNING (08:30)", "AFTERNOON (13:30)", "EVENING (19:00)", "NIGHT (21:30)")
            slots.forEach { slot ->
                val slotMeds = medications.filter { it.timeSlot.equals(slot, ignoreCase = true) }
                if (slotMeds.isNotEmpty()) {
                    item {
                        Text(
                            text = slot,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(
                        items = slotMeds,
                        key = { it.id }
                    ) { med ->
                        MedicationCard(
                            medication = med,
                            onToggle = { viewModel.toggleDoseTaken(med.id) },
                            onDelete = { viewModel.removeMedication(med.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MedicationCard(
    medication: PrescriptionMedication,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (medication.isTaken) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (medication.isTaken) "Dose taken" else "Mark dose taken",
                    tint = if (medication.isTaken) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = medication.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${medication.dosage} • ${medication.timeSlot}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete medication",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AddMedicationDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var dosage by remember { mutableStateOf("") }
    var selectedSlot by remember { mutableStateOf("MORNING (08:30)") }

    val slots = listOf("MORNING (08:30)", "AFTERNOON (13:30)", "EVENING (19:00)", "NIGHT (21:30)")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Prescription Medicine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Medicine Name (e.g. Paracetamol)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = dosage,
                    onValueChange = { dosage = it },
                    label = { Text("Dosage (e.g. 500 mg - 1 Tablet)") },
                    singleLine = true
                )
                Text("Schedule Slot", style = MaterialTheme.typography.labelLarge)
                slots.forEach { slot ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                    ) {
                        Checkbox(
                            checked = selectedSlot == slot,
                            onCheckedChange = { if (it) selectedSlot = slot }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(slot, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, dosage, selectedSlot) },
                enabled = name.isNotBlank()
            ) {
                Text("Add Medicine")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
