package `in`.abhayhealth.app

import `in`.abhayhealth.app.R.feature.me

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.abhayhealth.app.core.common.DefaultDispatcherProvider
import `in`.abhayhealth.app.core.common.DispatcherProvider
import `in`.abhayhealth.app.domain.repository.AuthRepository
import `in`.abhayhealth.app.domain.repository.PatientProfileRepository
import `in`.abhayhealth.app.domain.repository.UserPreferencesRepository
import `in`.abhayhealth.app.feature.records.DocumentImportManager
import `in`.abhayhealth.app.feature.records.MedicalRecordRepository
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
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider()
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
