package com.example.abhayhealth.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Retained for route backwards-compatibility; delegates directly to the Google Sign-In screen.
 */
@Composable
fun VerifyOtpScreen(
    viewModel: AuthViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    EnterPhoneScreen(
        viewModel = viewModel,
        onBackClick = onBackClick,
        modifier = modifier
    )
}
