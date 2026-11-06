package com.crush.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.crush.mobile.ui.screens.chat.ChatScreen
import com.crush.mobile.ui.screens.onboarding.OnboardingScreen
import com.crush.mobile.ui.screens.projects.ProjectsScreen
import com.crush.mobile.ui.screens.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Chat : Screen("chat")
    object Settings : Screen("settings")
    object Models : Screen("models")
    object Diagnostics : Screen("diagnostics")
    object Projects : Screen("projects")
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
                onOpenProjects = { navController.navigate(Screen.Projects.route) },
                isLocked = isLocked
            )
        }

        // Reachable from the chat sheet (next to Settings). The Projects
        // surface is the ADR-0001 registry's in-app reader; opening a row
        // returns to chat bound to that row's engine instance.
        //
        // The row-open REPLACES the whole stack back to a single chat entry:
        // navigate with popUpTo(chat, inclusive) instead of a bare push. A
        // bare push stacks Projects UNDER the new chat ("[chat, Projects,
        // chat]"), so back key oscillates Projects<->chat forever and can
        // never exit (measured on device, 2026-11-06 - capabilities ledger,
        // Gate-1 E5). With the replace, back-from-chat exits the task and
        // back-from-Projects returns to the one chat; the fresh entry still
        // re-bootstraps that row's engine binding (unchanged semantics).
        composable(Screen.Projects.route) {
            ProjectsScreen(
                onBack = { navController.popBackStack() },
                onOpenProject = {
                    navController.navigate(Screen.Chat.route) {
                        popUpTo(Screen.Chat.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
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
            com.crush.mobile.ui.screens.diagnostics.DiagnosticsRoute(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
