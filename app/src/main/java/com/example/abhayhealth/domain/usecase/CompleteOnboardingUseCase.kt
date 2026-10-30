package `in`.abhayhealth.app.domain.usecase

import `in`.abhayhealth.app.domain.repository.UserPreferencesRepository

class CompleteOnboardingUseCase(
    private val repository: UserPreferencesRepository
) {
    suspend operator fun invoke(patientName: String = "") {
        if (patientName.isNotBlank()) {
            repository.setPatientName(patientName)
        }
        repository.setOnboardingCompleted(true)
    }
}
