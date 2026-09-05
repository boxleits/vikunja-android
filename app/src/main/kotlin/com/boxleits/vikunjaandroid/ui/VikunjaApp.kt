package com.boxleits.vikunjaandroid.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.boxleits.vikunjaandroid.ui.agenda.AgendaScreen
import com.boxleits.vikunjaandroid.ui.navigation.VikunjaDestinations
import com.boxleits.vikunjaandroid.ui.onboarding.OnboardingScreen
import com.boxleits.vikunjaandroid.ui.outline.OutlineScreen
import com.boxleits.vikunjaandroid.ui.settings.SettingsScreen

@Composable
fun VikunjaApp(appViewModel: AppViewModel = hiltViewModel()) {
    val isConfigured by appViewModel.isConfigured.collectAsStateWithLifecycle()

    when (isConfigured) {
        true -> MainScaffold()
        false -> OnboardingScreen()
        null -> Unit
    }
}

@Composable
private fun MainScaffold() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = { VikunjaBottomBar(navController) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = VikunjaDestinations.OUTLINE,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(VikunjaDestinations.OUTLINE) {
                OutlineScreen(onOpenSettings = { navController.navigate(VikunjaDestinations.SETTINGS) })
            }
            composable(VikunjaDestinations.AGENDA) {
                AgendaScreen(onOpenSettings = { navController.navigate(VikunjaDestinations.SETTINGS) })
            }
            composable(VikunjaDestinations.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun VikunjaBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == VikunjaDestinations.OUTLINE,
            onClick = { navController.navigateToTab(VikunjaDestinations.OUTLINE) },
            icon = { Icon(Icons.Filled.List, contentDescription = null) },
            label = { Text("Outline") },
        )
        NavigationBarItem(
            selected = currentRoute == VikunjaDestinations.AGENDA,
            onClick = { navController.navigateToTab(VikunjaDestinations.AGENDA) },
            icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
            label = { Text("Agenda") },
        )
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
