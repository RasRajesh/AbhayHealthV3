package com.example.abhayhealth.feature.me

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.abhayhealth.core.common.DefaultDispatcherProvider
import com.example.abhayhealth.core.common.DispatcherProvider
import com.example.abhayhealth.domain.repository.AuthRepository
import com.example.abhayhealth.domain.repository.PatientProfileRepository
import com.example.abhayhealth.domain.repository.UserPreferencesRepository
import com.example.abhayhealth.feature.medicines.MedicationDao
import com.example.abhayhealth.feature.records.DocumentImportManager
import com.example.abhayhealth.feature.records.MedicalRecordRepository
import com.example.abhayhealth.feature.records.export.ExportReportState
import com.example.abhayhealth.feature.records.export.MedicalReportExportManager
import com.example.abhayhealth.feature.vitals.VitalReadingRepository
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MeViewModel(
    userPreferencesRepository: UserPreferencesRepository,
    private val authRepository: AuthRepository,
    private val medicalRecordRepository: MedicalRecordRepository,
    private val patientProfileRepository: PatientProfileRepository,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val medicationDao: MedicationDao? = null,
    private val vitalReadingRepository: VitalReadingRepository? = null
) : ViewModel() {

    val patientName: StateFlow<String?> = userPreferencesRepository.patientName.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _exportState = MutableStateFlow<ExportReportState>(ExportReportState.Idle)
    val exportState: StateFlow<ExportReportState> = _exportState.asStateFlow()

    private val _showExportDialog = MutableStateFlow(false)
    val showExportDialog: StateFlow<Boolean> = _showExportDialog.asStateFlow()

    private val _recordCount = MutableStateFlow(0)
    val recordCount: StateFlow<Int> = _recordCount.asStateFlow()

    private val _medicationCount = MutableStateFlow(0)
    val medicationCount: StateFlow<Int> = _medicationCount.asStateFlow()

    private val _vitalCount = MutableStateFlow(0)
    val vitalCount: StateFlow<Int> = _vitalCount.asStateFlow()

    private val _patientProfile = MutableStateFlow<com.example.abhayhealth.domain.model.PatientProfile?>(null)
    val patientProfile: StateFlow<com.example.abhayhealth.domain.model.PatientProfile?> = _patientProfile.asStateFlow()

    init {
        observeProfile()
    }

    private fun observeProfile() {
        val uid = authRepository.currentUser?.uid?.ifBlank { null } ?: "local_user"
        viewModelScope.launch(dispatcherProvider.main) {
            patientProfileRepository.getProfile(uid).collect {
                _patientProfile.value = it
            }
        }
    }

    fun openExportDialog() {
        val uid = authRepository.currentUser?.uid?.ifBlank { null } ?: "local_user"
        refreshCounts(uid)
        _exportState.value = ExportReportState.Idle
        _showExportDialog.value = true
    }

    fun dismissExportDialog() {
        _showExportDialog.value = false
        _exportState.value = ExportReportState.Idle
    }

    private fun refreshCounts(uid: String) {
        viewModelScope.launch(dispatcherProvider.main) {
            try {
                medicalRecordRepository.getRecords(uid).first().let {
                    _recordCount.value = it.size
                }
                medicationDao?.getMedicationsByAccount(uid)?.first()?.let {
                    _medicationCount.value = it.size
                }
                vitalReadingRepository?.getAllReadings(uid)?.first()?.let {
                    _vitalCount.value = it.size
                }
            } catch (_: Exception) {}
        }
    }

    fun exportAndShareReport(context: Context) {
        performExport(context) { manager, file, patientName ->
            manager.shareReport(file, patientName)
        }
    }

    fun exportAndViewReport(context: Context) {
        performExport(context) { manager, file, _ ->
            manager.viewReport(file)
        }
    }

    fun exportAndPrintReport(context: Context) {
        performExport(context) { manager, file, _ ->
            manager.printReport(file)
        }
    }

    private fun performExport(context: Context, onReady: (MedicalReportExportManager, File, String) -> Unit) {
        val uid = authRepository.currentUser?.uid
        if (uid.isNullOrEmpty()) {
            _exportState.value = ExportReportState.Error("Please sign in to export medical history.")
            return
        }

        val medDao = medicationDao
        val vitalRepo = vitalReadingRepository
        if (medDao == null || vitalRepo == null) {
            _exportState.value = ExportReportState.Error("Database services unavailable.")
            return
        }

        viewModelScope.launch(dispatcherProvider.main) {
            _exportState.value = ExportReportState.Generating
            try {
                val exportManager = MedicalReportExportManager(
                    context = context.applicationContext,
                    medicalRecordRepository = medicalRecordRepository,
                    medicationDao = medDao,
                    vitalReadingRepository = vitalRepo,
                    patientProfileRepository = patientProfileRepository
                )
                val file = exportManager.generateReport(uid)
                val profile = try { patientProfileRepository.getProfile(uid).first() } catch (_: Exception) { null }
                val patientName = profile?.fullName ?: "Patient"

                _exportState.value = ExportReportState.Success(file)
                onReady(exportManager, file, patientName)
            } catch (e: Exception) {
                _exportState.value = ExportReportState.Error(e.message ?: "Failed to generate PDF report.")
            }
        }
    }

    fun deleteAccountAndAllData(context: Context, onDeleted: () -> Unit) {
        if (_isDeleting.value) return

        val uid = authRepository.currentUser?.uid ?: run {
            authRepository.signOut()
            onDeleted()
            return
        }

        _isDeleting.value = true
        _errorMessage.value = null

        // Step 1: Attempt Firebase Auth account deletion FIRST before destroying local records
        authRepository.deleteAccount { success, error ->
            if (success) {
                // Step 2: Delete local health records and files only after account deletion succeeds
                viewModelScope.launch(dispatcherProvider.main) {
                    try {
                        val records = medicalRecordRepository.getRecords(uid).first()
                        records.forEach { record ->
                            medicalRecordRepository.deleteRecord(record)
                            DocumentImportManager.deleteFile(record.localFilePath, context, record, uid)
                        }
                        patientProfileRepository.deleteProfile(uid)
                    } catch (_: Exception) {
                        // Ignore local cleanup exception
                    } finally {
                        _isDeleting.value = false
                        onDeleted()
                    }
                }
            } else {
                // Step 3: On deletion failure, preserve local health data and notify user
                _isDeleting.value = false
                _errorMessage.value = error ?: "Could not delete account. Your local health data has been preserved."
            }
        }
    }

    fun clearMessages() {
        _errorMessage.value = null
    }
}
