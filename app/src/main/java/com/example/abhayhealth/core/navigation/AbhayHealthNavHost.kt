package `in`.abhayhealth.app

import `in`.abhayhealth.app.R.core.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import `in`.abhayhealth.app.domain.model.AuthState
import `in`.abhayhealth.app.feature.auth.AuthViewModel
import `in`.abhayhealth.app.feature.auth.EnterPhoneScreen
import `in`.abhayhealth.app.feature.auth.VerifyOtpScreen
import `in`.abhayhealth.app.feature.auth.WelcomeScreen
import `in`.abhayhealth.app.feature.home.HomeViewModel
import `in`.abhayhealth.app.feature.main.MainScreen
import `in`.abhayhealth.app.feature.me.MeViewModel
import `in`.abhayhealth.app.feature.medicines.MedicinesViewModel
import `in`.abhayhealth.app.feature.onboarding.OnboardingScreen
import `in`.abhayhealth.app.feature.onboarding.OnboardingViewModel
import `in`.abhayhealth.app.feature.profile.ProfileEditScreen
import `in`.abhayhealth.app.feature.profile.ProfileSetupScreen
import `in`.abhayhealth.app.feature.profile.ProfileViewModel
import `in`.abhayhealth.app.feature.records.ExtractionReviewScreen
import `in`.abhayhealth.app.feature.records.ExtractionReviewViewModel
import `in`.abhayhealth.app.feature.records.RecordsViewModel
import `in`.abhayhealth.app.feature.splash.SplashScreen
import `in`.abhayhealth.app.feature.splash.SplashViewModel
import `in`.abhayhealth.app.feature.trends.TrendsViewModel

@Composable
fun AbhayHealthNavHost(
    splashViewModel: SplashViewModel,
    onboardingViewModel: OnboardingViewModel,
    authViewModel: AuthViewModel,
    profileViewModel: ProfileViewModel,
    extractionReviewViewModel: ExtractionReviewViewModel,
    homeViewModel: HomeViewModel,
    recordsViewModel: RecordsViewModel,
    trendsViewModel: TrendsViewModel,
    medicinesViewModel: MedicinesViewModel,
    meViewModel: MeViewModel,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val authState by authViewModel.authState.collectAsState()

    // Observe global authentication state for seamless navigation transitions
    LaunchedEffect(authState) {
        when (authState) {
            is AuthState.CodeSent -> {
                if (navController.currentDestination?.route != NavRoutes.VERIFY_OTP) {
                    navController.navigate(NavRoutes.VERIFY_OTP)
                }
            }
            is AuthState.Authenticated -> {
                val currentRoute = navController.currentDestination?.route
                if (currentRoute == NavRoutes.WELCOME || currentRoute == NavRoutes.VERIFY_OTP) {
                    profileViewModel.loadProfileForCurrentUser()
                    navController.navigate(NavRoutes.PROFILE_SETUP) {
                        popUpTo(NavRoutes.WELCOME) { inclusive = true }
                        popUpTo(NavRoutes.SPLASH) { inclusive = true }
                    }
                }
            }
            is AuthState.Unauthenticated -> {
                val currentRoute = navController.currentDestination?.route
                if (currentRoute == NavRoutes.MAIN || currentRoute == NavRoutes.PROFILE_EDIT || currentRoute?.startsWith("extraction_review") == true) {
                    navController.navigate(NavRoutes.WELCOME) {
                        popUpTo(NavRoutes.MAIN) { inclusive = true }
                    }
                }
            }
            else -> {}
        }
    }

    NavHost(
        navController = navController,
        startDestination = NavRoutes.SPLASH,
        enterTransition = { fadeIn() + slideInHorizontally { it / 4 } },
        exitTransition = { fadeOut() + slideOutHorizontally { -it / 4 } },
        popEnterTransition = { fadeIn() + slideInHorizontally { -it / 4 } },
        popExitTransition = { fadeOut() + slideOutHorizontally { it / 4 } },
        modifier = modifier
    ) {
        composable(NavRoutes.SPLASH) {
            SplashScreen(
                viewModel = splashViewModel,
                onNavigateNext = { targetRoute ->
                    navController.navigate(targetRoute) {
                        popUpTo(NavRoutes.SPLASH) {
                            inclusive = true
                        }
                    }
                }
            )
        }

        composable(NavRoutes.ONBOARDING) {
            OnboardingScreen(
                viewModel = onboardingViewModel,
                onOnboardingFinished = {
                    navController.navigate(NavRoutes.WELCOME) {
                        popUpTo(NavRoutes.ONBOARDING) {
                            inclusive = true
                        }
                    }
                }
            )
        }

        composable(NavRoutes.WELCOME) {
            WelcomeScreen(
                onContinueClick = {
                    navController.navigate(NavRoutes.ENTER_PHONE)
                }
            )
        }

        composable(NavRoutes.ENTER_PHONE) {
            EnterPhoneScreen(
                viewModel = authViewModel,
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        composable(NavRoutes.VERIFY_OTP) {
            VerifyOtpScreen(
                viewModel = authViewModel,
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        composable(NavRoutes.PROFILE_SETUP) {
            ProfileSetupScreen(
                viewModel = profileViewModel,
                onSetupFinished = {
                    navController.navigate(NavRoutes.MAIN) {
                        popUpTo(NavRoutes.PROFILE_SETUP) { inclusive = true }
                    }
                }
            )
        }

        composable(NavRoutes.PROFILE_EDIT) {
            val userPhone = (authState as? AuthState.Authenticated)?.user?.phoneNumber
            ProfileEditScreen(
                viewModel = profileViewModel,
                userPhone = userPhone,
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = NavRoutes.EXTRACTION_REVIEW,
            arguments = listOf(navArgument("recordId") { type = NavType.LongType })
        ) { backStackEntry ->
            val recordId = backStackEntry.arguments?.getLong("recordId") ?: -1L
            LaunchedEffect(recordId) {
                if (recordId != -1L) {
                    extractionReviewViewModel.loadRecordAndInfo(recordId)
                }
            }
            ExtractionReviewScreen(
                viewModel = extractionReviewViewModel,
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        composable(NavRoutes.MAIN) {
            MainScreen(
                homeViewModel = homeViewModel,
                recordsViewModel = recordsViewModel,
                trendsViewModel = trendsViewModel,
                medicinesViewModel = medicinesViewModel,
                meViewModel = meViewModel,
                onEditProfileClick = {
                    profileViewModel.loadProfileForCurrentUser()
                    navController.navigate(NavRoutes.PROFILE_EDIT)
                },
                onRecordReviewClick = { recordId ->
                    navController.navigate(NavRoutes.extractionReview(recordId))
                },
                onSignOutClick = {
                    authViewModel.signOut()
                }
            )
        }
    }
}
