package `in`.abhayhealth.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.abhayhealth.app.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(
    userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    val patientName: StateFlow<String?> = userPreferencesRepository.patientName.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )
}
