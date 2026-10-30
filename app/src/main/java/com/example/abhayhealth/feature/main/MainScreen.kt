package `in`.abhayhealth.app

import `in`.abhayhealth.app.R.feature.main

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import `in`.abhayhealth.app.core.navigation.BottomNavItem
import `in`.abhayhealth.app.core.navigation.NavRoutes
import `in`.abhayhealth.app.feature.home.HomeScreen
import `in`.abhayhealth.app.feature.home.HomeViewModel
import `in`.abhayhealth.app.feature.me.MeScreen
import `in`.abhayhealth.app.feature.me.MeViewModel
import `in`.abhayhealth.app.feature.medicines.MedicinesScreen
import `in`.abhayhealth.app.feature.medicines.MedicinesViewModel
import `in`.abhayhealth.app.feature.records.RecordsScreen
import `in`.abhayhealth.app.feature.records.RecordsViewModel
import `in`.abhayhealth.app.feature.trends.TrendsScreen
import `in`.abhayhealth.app.feature.trends.TrendsViewModel

@Composable
fun MainScreen(
    homeViewModel: HomeViewModel,
    recordsViewModel: RecordsViewModel,
    trendsViewModel: TrendsViewModel,
    medicinesViewModel: MedicinesViewModel,
    meViewModel: MeViewModel,
    onEditProfileClick: () -> Unit,
    onRecordReviewClick: (Long) -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                BottomNavItem.items.forEach { item ->
                    val title = stringResource(id = item.titleRes)
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = title
                            )
                        },
                        label = { Text(text = title) },
                        selected = currentRoute == item.route,
                        onClick = {
                            if (currentRoute != item.route) {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoutes.MainTab.HOME,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(NavRoutes.MainTab.HOME) {
                HomeScreen(
                    viewModel = homeViewModel,
                    onAddRecordClick = {
                        navController.navigate(NavRoutes.MainTab.RECORDS) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(NavRoutes.MainTab.RECORDS) {
                RecordsScreen(
                    viewModel = recordsViewModel,
                    onReviewClick = onRecordReviewClick
                )
            }
            composable(NavRoutes.MainTab.TRENDS) {
                TrendsScreen(viewModel = trendsViewModel)
            }
            composable(NavRoutes.MainTab.MEDICINES) {
                MedicinesScreen(viewModel = medicinesViewModel)
            }
            composable(NavRoutes.MainTab.ME) {
                MeScreen(
                    viewModel = meViewModel,
                    onEditProfileClick = onEditProfileClick,
                    onSignOutClick = onSignOutClick
                )
            }
        }
    }
}
