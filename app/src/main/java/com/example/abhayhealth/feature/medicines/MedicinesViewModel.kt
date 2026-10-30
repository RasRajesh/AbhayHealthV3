package `in`.abhayhealth.app

import `in`.abhayhealth.app.R.feature.medicines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.abhayhealth.app.core.common.DefaultDispatcherProvider
import `in`.abhayhealth.app.core.common.DispatcherProvider
import `in`.abhayhealth.app.domain.model.AuthState
import `in`.abhayhealth.app.domain.repository.AuthRepository
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
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider()
) : ViewModel() {

    private val _medications = MutableStateFlow<List<PrescriptionMedication>>(emptyList())
    val medications: StateFlow<List<PrescriptionMedication>> = _medications.asStateFlow()

    private val _showAddDialog = MutableStateFlow(false)
    val showAddDialog: StateFlow<Boolean> = _showAddDialog.asStateFlow()

    private var observeJob: Job? = null

    init {
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
            }
        }
    }

    fun openAddDialog() { _showAddDialog.value = true }
    fun closeAddDialog() { _showAddDialog.value = false }

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

        viewModelScope.launch(dispatcherProvider.main) {
            withContext(dispatcherProvider.io) {
                dao.deleteMedication(id, uid)
            }
        }
    }
}
