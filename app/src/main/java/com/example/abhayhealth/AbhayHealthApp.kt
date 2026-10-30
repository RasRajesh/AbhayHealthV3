package `in`.abhayhealth.app

import `in`.abhayhealth.app.R

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import `in`.abhayhealth.app.core.designsystem.theme.AbhayHealthTheme
import `in`.abhayhealth.app.R.core.navigation.AbhayHealthNavHost
import `in`.abhayhealth.app.core.storage.datastore.UserPreferences
import `in`.abhayhealth.app.data.repository.FirebaseAuthRepository
import `in`.abhayhealth.app.data.repository.PatientProfileRepositoryImpl
import `in`.abhayhealth.app.data.repository.UserPreferencesRepositoryImpl
import `in`.abhayhealth.app.domain.usecase.CompleteOnboardingUseCase
import `in`.abhayhealth.app.domain.usecase.GetStartupDestinationUseCase
import `in`.abhayhealth.app.feature.auth.AuthViewModel
import `in`.abhayhealth.app.feature.home.HomeViewModel
import `in`.abhayhealth.app.feature.me.MeViewModel
import `in`.abhayhealth.app.feature.medicines.MedicinesViewModel
import `in`.abhayhealth.app.feature.onboarding.OnboardingViewModel
import `in`.abhayhealth.app.feature.profile.ProfileViewModel
import `in`.abhayhealth.app.feature.records.ExtractionReviewViewModel
import `in`.abhayhealth.app.feature.records.MedicalRecordDatabase
import `in`.abhayhealth.app.feature.records.MedicalRecordRepository
import `in`.abhayhealth.app.feature.records.RecordsViewModel
import `in`.abhayhealth.app.feature.splash.SplashViewModel
import `in`.abhayhealth.app.feature.trends.TrendsViewModel

@Composable
fun AbhayHealthApp(
    context: Context = LocalContext.current
) {
    // Composition root dependency graph initialization
    val userPreferences = remember(context) { UserPreferences(context.applicationContext) }
    val userPreferencesRepository = remember(userPreferences) { UserPreferencesRepositoryImpl(userPreferences) }
    val authRepository = remember { FirebaseAuthRepository(context.applicationContext) }
    val patientProfileRepository = remember(context) { PatientProfileRepositoryImpl(context.applicationContext) }

    val medicalRecordDatabase = remember(context) { MedicalRecordDatabase.getInstance(context.applicationContext) }
    val medicalRecordRepository = remember(medicalRecordDatabase) {
        MedicalRecordRepository(
            medicalRecordDatabase.medicalRecordDao(),
            medicalRecordDatabase.extractedInfoDao()
        )
    }

    val getStartupDestinationUseCase = remember(userPreferencesRepository, authRepository, patientProfileRepository) {
        GetStartupDestinationUseCase(userPreferencesRepository, authRepository, patientProfileRepository)
    }
    val completeOnboardingUseCase = remember(userPreferencesRepository) {
        CompleteOnboardingUseCase(userPreferencesRepository)
    }

    val splashViewModel = remember(getStartupDestinationUseCase) { SplashViewModel(getStartupDestinationUseCase) }
    val onboardingViewModel = remember(completeOnboardingUseCase) { OnboardingViewModel(completeOnboardingUseCase) }
    val authViewModel = remember(authRepository) { AuthViewModel(authRepository) }
    val profileViewModel = remember(patientProfileRepository, authRepository) {
        ProfileViewModel(patientProfileRepository, authRepository)
    }
    val extractionReviewViewModel = remember(medicalRecordRepository, authRepository) {
        ExtractionReviewViewModel(medicalRecordRepository, authRepository)
    }
    val homeViewModel = remember(userPreferencesRepository) { HomeViewModel(userPreferencesRepository) }
    val recordsViewModel = remember(medicalRecordRepository, authRepository, medicalRecordDatabase) {
        RecordsViewModel(medicalRecordRepository, authRepository, medicationDao = medicalRecordDatabase.medicationDao())
    }
    val trendsViewModel = remember(medicalRecordRepository, authRepository) { TrendsViewModel(medicalRecordRepository, authRepository) }
    val medicinesViewModel = remember(medicalRecordDatabase, authRepository) {
        MedicinesViewModel(medicalRecordDatabase.medicationDao(), authRepository)
    }
    val meViewModel = remember(userPreferencesRepository, authRepository, medicalRecordRepository, patientProfileRepository) {
        MeViewModel(userPreferencesRepository, authRepository, medicalRecordRepository, patientProfileRepository)
    }

    AbhayHealthTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            AbhayHealthNavHost(
                splashViewModel = splashViewModel,
                onboardingViewModel = onboardingViewModel,
                authViewModel = authViewModel,
                profileViewModel = profileViewModel,
                extractionReviewViewModel = extractionReviewViewModel,
                homeViewModel = homeViewModel,
                recordsViewModel = recordsViewModel,
                trendsViewModel = trendsViewModel,
                medicinesViewModel = medicinesViewModel,
                meViewModel = meViewModel
            )
        }
    }
}
