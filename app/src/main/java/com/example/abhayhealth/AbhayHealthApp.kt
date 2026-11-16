package com.example.abhayhealth

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.example.abhayhealth.core.designsystem.theme.AbhayHealthTheme
import com.example.abhayhealth.core.navigation.AbhayHealthNavHost
import com.example.abhayhealth.core.storage.datastore.UserPreferences
import com.example.abhayhealth.data.remote.FirestoreHealthRepository
import com.example.abhayhealth.data.repository.FirebaseAuthRepository
import com.example.abhayhealth.data.repository.PatientProfileRepositoryImpl
import com.example.abhayhealth.data.repository.UserPreferencesRepositoryImpl
import com.example.abhayhealth.data.sync.FirestoreHealthSyncManager
import com.example.abhayhealth.domain.model.AuthState
import com.example.abhayhealth.domain.usecase.CompleteOnboardingUseCase
import com.example.abhayhealth.domain.usecase.GetStartupDestinationUseCase
import com.example.abhayhealth.feature.assistant.AiAssistantViewModel
import com.example.abhayhealth.feature.auth.AuthViewModel
import com.example.abhayhealth.feature.auth.EnterPhoneScreen
import com.example.abhayhealth.feature.home.HomeViewModel
import com.example.abhayhealth.feature.me.MeViewModel
import com.example.abhayhealth.feature.medicines.MedicinesViewModel
import com.example.abhayhealth.feature.onboarding.OnboardingViewModel
import com.example.abhayhealth.feature.profile.ProfileViewModel
import com.example.abhayhealth.feature.records.ExtractionReviewViewModel
import com.example.abhayhealth.feature.records.MedicalRecordDatabase
import com.example.abhayhealth.feature.records.MedicalRecordRepository
import com.example.abhayhealth.feature.records.RecordsViewModel
import com.example.abhayhealth.feature.splash.SplashViewModel
import com.example.abhayhealth.feature.symptoms.SymptomLogRepository
import com.example.abhayhealth.feature.trends.TrendsViewModel
import com.example.abhayhealth.feature.vitals.VitalReadingRepository
import java.util.Locale

@Composable
fun AbhayHealthApp(
    context: Context = LocalContext.current
) {
    val appContext = remember(context) { context.applicationContext }

    // Top-level authentication & localization dependencies (no Firestore listeners attached here)
    val userPreferences = remember(appContext) { UserPreferences(appContext) }
    val userPreferencesRepository = remember(userPreferences) { UserPreferencesRepositoryImpl(userPreferences) }
    val authRepository = remember(appContext) { FirebaseAuthRepository(appContext) }
    val completeOnboardingUseCase = remember(userPreferencesRepository) {
        CompleteOnboardingUseCase(userPreferencesRepository)
    }
    val onboardingViewModel = remember(completeOnboardingUseCase) { OnboardingViewModel(completeOnboardingUseCase) }
    val authViewModel = remember(authRepository) { AuthViewModel(authRepository) }

    val authState by authViewModel.authState.collectAsState()
    val authenticatedUser = when (val state = authState) {
        is AuthState.Authenticated -> state.user
        AuthState.Unauthenticated -> null
        else -> authRepository.currentUser
    }

    val appLanguage by userPreferencesRepository.appLanguage.collectAsState(initial = "English")
    val isTelugu = appLanguage.contains("Telugu", ignoreCase = true) || appLanguage.startsWith("te", ignoreCase = true)
    val targetLocale = remember(isTelugu) {
        if (isTelugu) Locale.forLanguageTag("te") else Locale.forLanguageTag("en")
    }

    val currentConfig = LocalConfiguration.current
    val localizedConfiguration = remember(currentConfig, targetLocale) {
        Configuration(currentConfig).apply {
            setLocale(targetLocale)
            setLayoutDirection(targetLocale)
        }
    }

    val localizedContext = remember(context, localizedConfiguration) {
        object : android.content.ContextWrapper(context) {
            private val configContext = context.createConfigurationContext(localizedConfiguration)
            override fun getResources(): android.content.res.Resources = configContext.resources
            override fun getAssets(): android.content.res.AssetManager = configContext.assets
        }
    }

    CompositionLocalProvider(
        LocalConfiguration provides localizedConfiguration,
        LocalContext provides localizedContext
    ) {
        AbhayHealthTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                if (authenticatedUser == null) {
                    // Unauthenticated root: zero Firestore queries or listeners instantiated
                    EnterPhoneScreen(
                        viewModel = authViewModel,
                        onboardingViewModel = onboardingViewModel,
                        onBackClick = null
                    )
                } else {
                    val uid = authenticatedUser.uid
                    key(uid) {
                        val firestoreHealthRepository = remember(appContext) {
                            FirestoreHealthRepository(appContext)
                        }
                        val patientProfileRepository = remember(appContext) {
                            PatientProfileRepositoryImpl(appContext)
                        }
                        val medicalRecordDatabase = remember(appContext) {
                            MedicalRecordDatabase.getInstance(appContext)
                        }
                        val medicalRecordRepository = remember(medicalRecordDatabase) {
                            MedicalRecordRepository(
                                medicalRecordDatabase.medicalRecordDao(),
                                medicalRecordDatabase.extractedInfoDao()
                            )
                        }
                        val vitalReadingRepository = remember(medicalRecordDatabase) {
                            VitalReadingRepository(medicalRecordDatabase.vitalReadingDao())
                        }
                        val symptomLogRepository = remember(medicalRecordDatabase) {
                            SymptomLogRepository(medicalRecordDatabase.symptomLogDao())
                        }
                        val syncManager = remember(
                            uid,
                            firestoreHealthRepository,
                            patientProfileRepository,
                            userPreferencesRepository,
                            medicalRecordDatabase,
                            appContext
                        ) {
                            FirestoreHealthSyncManager(
                                firestoreRepo = firestoreHealthRepository,
                                profileRepository = patientProfileRepository,
                                userPreferencesRepository = userPreferencesRepository,
                                medicalRecordDao = medicalRecordDatabase.medicalRecordDao(),
                                extractedInfoDao = medicalRecordDatabase.extractedInfoDao(),
                                medicationDao = medicalRecordDatabase.medicationDao(),
                                vitalReadingDao = medicalRecordDatabase.vitalReadingDao(),
                                symptomLogDao = medicalRecordDatabase.symptomLogDao(),
                                appContext = appContext
                            )
                        }
                        val cloudSyncStatus by syncManager.syncStatus.collectAsState()

                        val syncScope = rememberCoroutineScope()
                        DisposableEffect(uid, syncManager) {
                            syncManager.start(
                                scope = syncScope,
                                userId = uid,
                                email = authenticatedUser.email,
                                displayName = authenticatedUser.displayName
                            )
                            onDispose {
                                syncManager.stop()
                            }
                        }

                        val getStartupDestinationUseCase = remember(
                            userPreferencesRepository,
                            authRepository,
                            patientProfileRepository
                        ) {
                            GetStartupDestinationUseCase(
                                userPreferencesRepository,
                                authRepository,
                                patientProfileRepository
                            )
                        }
                        val splashViewModel = remember(getStartupDestinationUseCase) {
                            SplashViewModel(getStartupDestinationUseCase)
                        }
                        val profileViewModel = remember(
                            patientProfileRepository,
                            authRepository,
                            userPreferencesRepository,
                            firestoreHealthRepository
                        ) {
                            ProfileViewModel(
                                profileRepository = patientProfileRepository,
                                authRepository = authRepository,
                                userPreferencesRepository = userPreferencesRepository,
                                firestoreHealthRepository = firestoreHealthRepository
                            )
                        }
                        val extractionReviewViewModel = remember(
                            medicalRecordRepository,
                            authRepository,
                            medicalRecordDatabase,
                            vitalReadingRepository,
                            firestoreHealthRepository
                        ) {
                            ExtractionReviewViewModel(
                                medicalRecordRepository = medicalRecordRepository,
                                authRepository = authRepository,
                                medicationDao = medicalRecordDatabase.medicationDao(),
                                vitalReadingRepository = vitalReadingRepository,
                                firestoreHealthRepository = firestoreHealthRepository
                            )
                        }
                        val homeViewModel = remember(
                            userPreferencesRepository,
                            authRepository,
                            medicalRecordRepository,
                            medicalRecordDatabase,
                            patientProfileRepository,
                            vitalReadingRepository,
                            firestoreHealthRepository
                        ) {
                            HomeViewModel(
                                userPreferencesRepository = userPreferencesRepository,
                                authRepository = authRepository,
                                medicalRecordRepository = medicalRecordRepository,
                                medicationDao = medicalRecordDatabase.medicationDao(),
                                patientProfileRepository = patientProfileRepository,
                                vitalReadingRepository = vitalReadingRepository,
                                firestoreHealthRepository = firestoreHealthRepository
                            )
                        }
                        val recordsViewModel = remember(
                            medicalRecordRepository,
                            authRepository,
                            medicalRecordDatabase,
                            vitalReadingRepository,
                            patientProfileRepository,
                            firestoreHealthRepository
                        ) {
                            RecordsViewModel(
                                medicalRecordRepository = medicalRecordRepository,
                                authRepository = authRepository,
                                medicationDao = medicalRecordDatabase.medicationDao(),
                                vitalReadingRepository = vitalReadingRepository,
                                patientProfileRepository = patientProfileRepository,
                                firestoreHealthRepository = firestoreHealthRepository
                            )
                        }
                        val trendsViewModel = remember(
                            medicalRecordRepository,
                            authRepository,
                            symptomLogRepository,
                            vitalReadingRepository,
                            firestoreHealthRepository
                        ) {
                            TrendsViewModel(
                                medicalRecordRepository = medicalRecordRepository,
                                authRepository = authRepository,
                                symptomLogRepository = symptomLogRepository,
                                vitalReadingRepository = vitalReadingRepository,
                                firestoreHealthRepository = firestoreHealthRepository
                            )
                        }
                        val medicinesViewModel = remember(
                            medicalRecordDatabase,
                            authRepository,
                            appContext,
                            firestoreHealthRepository
                        ) {
                            MedicinesViewModel(
                                medicationDao = medicalRecordDatabase.medicationDao(),
                                authRepository = authRepository,
                                context = appContext,
                                firestoreHealthRepository = firestoreHealthRepository
                            )
                        }
                        val meViewModel = remember(
                            userPreferencesRepository,
                            authRepository,
                            medicalRecordRepository,
                            patientProfileRepository,
                            medicalRecordDatabase,
                            vitalReadingRepository,
                            firestoreHealthRepository
                        ) {
                            MeViewModel(
                                userPreferencesRepository = userPreferencesRepository,
                                authRepository = authRepository,
                                medicalRecordRepository = medicalRecordRepository,
                                patientProfileRepository = patientProfileRepository,
                                medicationDao = medicalRecordDatabase.medicationDao(),
                                vitalReadingRepository = vitalReadingRepository,
                                firestoreHealthRepository = firestoreHealthRepository
                            )
                        }
                        val aiAssistantViewModel = remember(
                            appContext,
                            userPreferencesRepository,
                            authRepository,
                            medicalRecordRepository,
                            medicalRecordDatabase,
                            patientProfileRepository,
                            vitalReadingRepository
                        ) {
                            AiAssistantViewModel(
                                context = appContext,
                                userPreferencesRepository = userPreferencesRepository,
                                authRepository = authRepository,
                                medicalRecordRepository = medicalRecordRepository,
                                medicationDao = medicalRecordDatabase.medicationDao(),
                                patientProfileRepository = patientProfileRepository,
                                vitalReadingRepository = vitalReadingRepository
                            )
                        }

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
                            meViewModel = meViewModel,
                            aiAssistantViewModel = aiAssistantViewModel,
                            syncStatus = cloudSyncStatus
                        )
                    }
                }
            }
        }
    }
}
