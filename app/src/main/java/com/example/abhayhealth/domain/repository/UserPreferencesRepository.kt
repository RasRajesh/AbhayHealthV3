package `in`.abhayhealth.app.domain.repository

import kotlinx.coroutines.flow.Flow

interface UserPreferencesRepository {
    val isOnboardingCompleted: Flow<Boolean>
    suspend fun setOnboardingCompleted(completed: Boolean)
    val patientName: Flow<String?>
    suspend fun setPatientName(name: String)
}
