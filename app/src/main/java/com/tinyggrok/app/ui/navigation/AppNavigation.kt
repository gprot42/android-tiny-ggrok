package com.tinyggrok.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.tinyggrok.app.ui.screens.AboutScreen
import com.tinyggrok.app.ui.screens.ChatScreen
import com.tinyggrok.app.ui.screens.DebugLogsScreen
import com.tinyggrok.app.ui.screens.HistoryScreen
import com.tinyggrok.app.ui.screens.SettingsScreen
import com.tinyggrok.app.ui.screens.UsageScreen
import com.tinyggrok.app.ui.screens.VoiceTranslatorScreen

sealed class Screen(val route: String) {
    object Chat : Screen("chat")
    object Settings : Screen("settings")
    object About : Screen("about")
    object DebugLogs : Screen("debug_logs")
    object History : Screen("history")
    object VoiceTranslator : Screen("voice_translator")
    object Usage : Screen("usage")
}

@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Chat.route
    ) {
        composable(Screen.Chat.route) {
            ChatScreen(
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToDebugLogs = { navController.navigate(Screen.DebugLogs.route) },
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onNavigateToVoiceTranslator = { navController.navigate(Screen.VoiceTranslator.route) },
                onNavigateToUsage = { navController.navigate(Screen.Usage.route) }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAbout = { navController.navigate(Screen.About.route) },
                onNavigateToUsage = { navController.navigate(Screen.Usage.route) }
            )
        }
        composable(Screen.About.route) {
            AboutScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.DebugLogs.route) {
            DebugLogsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.History.route) {
            HistoryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.VoiceTranslator.route) {
            VoiceTranslatorScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToDebugLogs = { navController.navigate(Screen.DebugLogs.route) }
            )
        }
        composable(Screen.Usage.route) {
            UsageScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }
    }
}
