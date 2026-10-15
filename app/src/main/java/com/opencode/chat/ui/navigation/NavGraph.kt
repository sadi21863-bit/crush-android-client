package com.opencode.chat.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.opencode.chat.ui.screens.chat.ChatScreen
import com.opencode.chat.ui.screens.onboarding.OnboardingScreen
import com.opencode.chat.ui.screens.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Chat : Screen("chat")
    object Settings : Screen("settings")
    object Models : Screen("models")
    object Diagnostics : Screen("diagnostics")
}

@Composable
fun NavGraph(
    navController: NavHostController,
    hasApiKey: Boolean,
    /**
     * True while the lock overlay demands authentication.
     *
     * Passed down rather than read from a singleton so the chat layer can refuse
     * to bootstrap. Composing the chat tree behind a lock screen is fine for
     * RENDERING, but the ViewModel it creates immediately boots the engine and
     * decrypts the key - so without this the lock was purely cosmetic.
     */
    isLocked: Boolean = false,
    startDestination: String = if (hasApiKey) Screen.Chat.route else Screen.Onboarding.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onCompleted = {
                    navController.navigate(Screen.Chat.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Chat.route) {
            ChatScreen(
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                isLocked = isLocked
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenDiagnostics = { navController.navigate(Screen.Diagnostics.route) },
            )
        }

        // Reachable from Settings only. A user who has hit a bug needs to be
        // able to hand over evidence, and the release build is non-debuggable so
        // neither logcat nor run-as is available to them.
        composable(Screen.Diagnostics.route) {
            com.opencode.chat.ui.screens.diagnostics.DiagnosticsRoute(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
