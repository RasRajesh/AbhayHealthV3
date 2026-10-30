package `in`.abhayhealth.app.feature.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.abhayhealth.app.core.navigation.NavRoutes
import `in`.abhayhealth.app.domain.usecase.GetStartupDestinationUseCase
import `in`.abhayhealth.app.domain.usecase.StartupDestination
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SplashViewModel(
    private val getStartupDestinationUseCase: GetStartupDestinationUseCase
) : ViewModel() {

    private val _startDestination = MutableStateFlow<String?>(null)
    val startDestination: StateFlow<String?> = _startDestination.asStateFlow()

    init {
        determineDestination()
    }

    private fun determineDestination() {
        viewModelScope.launch {
            // Branding display time
            delay(1000)
            getStartupDestinationUseCase().collect { destination ->
                _startDestination.value = when (destination) {
                    StartupDestination.Onboarding -> NavRoutes.ONBOARDING
                    StartupDestination.Welcome -> NavRoutes.WELCOME
                    StartupDestination.ProfileSetup -> NavRoutes.PROFILE_SETUP
                    StartupDestination.Main -> NavRoutes.MAIN
                }
            }
        }
    }
}
