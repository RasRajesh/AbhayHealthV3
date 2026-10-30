package com.example.abhayhealth.feature.medicines

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.abhayhealth.core.common.DefaultDispatcherProvider
import com.example.abhayhealth.core.common.DispatcherProvider
import com.example.abhayhealth.domain.model.AuthState
import com.example.abhayhealth.domain.repository.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PrescriptionMedication(
    val id: String,
    val name: String,
    val dosage: String,
    val timeSlot: String, // MORNING (08:30), AFTERNOON (13:30), EVENING (19:00), NIGHT (21:30)
    val isTaken: Boolean = false
)

class MedicinesViewModel(
    private val medicationDao: MedicationDao? = null,
    private val authRepository: AuthRepository? = null,
    private val context: Context? = null,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider()
) : ViewModel() {

    private val _medications = MutableStateFlow<List<PrescriptionMedication>>(emptyList())
    val medications: StateFlow<List<PrescriptionMedication>> = _medications.asStateFlow()

    private val _showAddDialog = MutableStateFlow(false)
    val showAddDialog: StateFlow<Boolean> = _showAddDialog.asStateFlow()

    private val _showRemindersDialog = MutableStateFlow(false)
    val showRemindersDialog: StateFlow<Boolean> = _showRemindersDialog.asStateFlow()

    private var observeJob: Job? = null

    init {
        context?.let { ctx ->
            MedicationNotificationHelper.createNotificationChannel(ctx)
        }
        observeAuthState()
    }

    private fun observeAuthState() {
        val authRepo = authRepository ?: return
        viewModelScope.launch(dispatcherProvider.main) {
            authRepo.authState.collectLatest { state ->
                when (state) {
                    is AuthState.Authenticated -> {
                        observeMedicationsForUid(state.user.uid)
                    }
                    else -> {
                        observeJob?.cancel()
                        _medications.value = emptyList()
                        _showAddDialog.value = false
                        _showRemindersDialog.value = false
                    }
                }
            }
        }
    }

    private fun observeMedicationsForUid(uid: String) {
        val dao = medicationDao ?: return
        observeJob?.cancel()
        observeJob = viewModelScope.launch(dispatcherProvider.main) {
            dao.getMedicationsByAccount(uid).collectLatest { list ->
                _medications.value = list.map { entity ->
                    PrescriptionMedication(
                        id = entity.id,
                        name = entity.name,
                        dosage = entity.dosage,
                        timeSlot = entity.timeSlot,
                        isTaken = entity.isTaken
                    )
                }

                // Sync alarms with schedule
                context?.let { ctx ->
                    for (entity in list) {
                        MedicationAlarmScheduler.scheduleMedicationAlarm(
                            context = ctx,
                            medicationId = entity.id,
                            name = entity.name,
                            dosage = entity.dosage,
                            timeSlot = entity.timeSlot
                        )
                    }
                }
            }
        }
    }

    fun openAddDialog() { _showAddDialog.value = true }
    fun closeAddDialog() { _showAddDialog.value = false }

    fun openRemindersDialog() { _showRemindersDialog.value = true }
    fun closeRemindersDialog() { _showRemindersDialog.value = false }

    fun addMedication(name: String, dosage: String, timeSlot: String) {
        if (name.isBlank()) return
        val uid = authRepository?.currentUser?.uid ?: return
        val dao = medicationDao ?: return

        val entity = PrescriptionMedicationEntity(
            accountUid = uid,
            name = name.trim(),
            dosage = dosage.trim().ifBlank { "1 Tablet" },
            timeSlot = timeSlot
        )

        viewModelScope.launch(dispatcherProvider.main) {
            withContext(dispatcherProvider.io) {
                dao.insertOrUpdate(entity)
            }

            // Immediately schedule alarm for this newly added medication
            context?.let { ctx ->
                MedicationAlarmScheduler.scheduleMedicationAlarm(
                    context = ctx,
                    medicationId = entity.id,
                    name = entity.name,
                    dosage = entity.dosage,
                    timeSlot = entity.timeSlot
                )
            }

            _showAddDialog.value = false
        }
    }

    fun toggleDoseTaken(id: String) {
        val uid = authRepository?.currentUser?.uid ?: return
        val dao = medicationDao ?: return

        viewModelScope.launch(dispatcherProvider.main) {
            withContext(dispatcherProvider.io) {
                dao.toggleDoseTaken(id, uid)
            }
        }
    }

    fun removeMedication(id: String) {
        val uid = authRepository?.currentUser?.uid ?: return
        val dao = medicationDao ?: return

        // Cancel scheduled alarm when removed
        context?.let { ctx ->
            MedicationAlarmScheduler.cancelMedicationAlarm(ctx, id)
        }

        viewModelScope.launch(dispatcherProvider.main) {
            withContext(dispatcherProvider.io) {
                dao.deleteMedication(id, uid)
            }
        }
    }

    fun triggerTestReminder(medicineName: String = "Paracetamol", dosage: String = "500 mg - 1 Tab") {
        context?.let { ctx ->
            MedicationNotificationHelper.showMedicationNotification(
                context = ctx,
                medicationId = "test_med_${System.currentTimeMillis()}",
                name = medicineName,
                dosage = dosage,
                timeSlot = "TEST ALERT (Now)"
            )
        }
    }

    fun rescheduleAllAlarms() {
        context?.let { ctx ->
            MedicationAlarmScheduler.rescheduleAllAlarms(ctx)
        }
    }
}
