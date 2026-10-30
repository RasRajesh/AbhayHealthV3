package `in`.abhayhealth.app.data.repository

import `in`.abhayhealth.app.core.storage.datastore.UserPreferences
import `in`.abhayhealth.app.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow

class UserPreferencesRepositoryImpl(
    private val userPreferences: UserPreferences
) : UserPreferencesRepository {

    override val isOnboardingCompleted: Flow<Boolean> = userPreferences.isOnboardingCompleted

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        userPreferences.setOnboardingCompleted(completed)
    }

    override val patientName: Flow<String?> = userPreferences.patientName

    override suspend fun setPatientName(name: String) {
        userPreferences.setPatientName(name)
    }
}
