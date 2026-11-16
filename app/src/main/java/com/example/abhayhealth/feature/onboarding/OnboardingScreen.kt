package com.example.abhayhealth.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.abhayhealth.feature.auth.AuthViewModel
import com.example.abhayhealth.feature.auth.EnterPhoneScreen

/**
 * Onboarding screen delegates to the unified Get Started & Phone Number authentication screen.
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    modifier: Modifier = Modifier,
    authViewModel: AuthViewModel? = null,
    onOnboardingFinished: () -> Unit = {}
) {
    if (authViewModel != null) {
        EnterPhoneScreen(
            viewModel = authViewModel,
            onboardingViewModel = viewModel,
            onBackClick = null,
            modifier = modifier
        )
    }
}
