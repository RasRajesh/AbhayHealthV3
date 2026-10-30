package `in`.abhayhealth.app.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.abhayhealth.app.domain.usecase.CompleteOnboardingUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OnboardingViewModel(
    private val completeOnboardingUseCase: CompleteOnboardingUseCase
) : ViewModel() {

    private val _patientName = MutableStateFlow("")
    val patientName: StateFlow<String> = _patientName.asStateFlow()

    fun onPatientNameChanged(name: String) {
        _patientName.value = name
    }

    fun completeOnboarding(onSuccess: () -> Unit) {
        viewModelScope.launch {
            completeOnboardingUseCase(_patientName.value.trim())
            onSuccess()
        }
    }
}
