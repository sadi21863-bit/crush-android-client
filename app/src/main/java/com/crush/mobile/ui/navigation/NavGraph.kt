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

    /**
     * P4 v1 (2026-11-07): every completed turn's measured changes with the
     * per-file revert. Pushed PLAINLY on top of chat (no popUpTo): back must
     * return to the bound conversation, and the underlying chat engine state
     * must not be torn down by a review detour.
     */
    object Review : Screen("review")
}

@Composable
fun NavGraph(
    navController: NavHostController,
    /**
     * Where this graph starts.
     *
     * DECIDED ONCE BY THE HOST at launch ([com.crush.mobile.ui.LaunchRouteState]),
     * never derived from live settings: changing it rebuilds the NavHost and
     * discards the back stack (Finding 12 - a provider switch in Settings used
     * to eject the user to Onboarding). Everything after launch is explicit
     * navigation between these routes.
     */
    startDestination: String,
    /**
     * True while the lock overlay demands authentication.
     *
     * Passed down rather than read from a singleton so the chat layer can refuse
     * to bootstrap. Composing the chat tree behind a lock screen is fine for
     * RENDERING, but the ViewModel it creates immediately boots the engine and
     * decrypts the key - so without this the lock was purely cosmetic.
     */
    isLocked: Boolean = false,
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
                onOpenReview = { navController.navigate(Screen.Review.route) },
                isLocked = isLocked
            )
        }

        // Reachable from the chat sheet (under Projects) and from the chat
        // rail's "Review all turns" link. Reads the container-scoped history;
        // holds no engine connection of its own (see ReviewRoute's doc).
        composable(Screen.Review.route) {
            com.crush.mobile.ui.screens.review.ReviewRoute(
                onBack = { navController.popBackStack() }
            )
        }

        // A primary destination in the V2-3 shell (V2-A2), not a sheet
        // shortcut. The Projects surface is the ADR-0001 registry's in-app
        // reader; opening a row returns to chat bound to that row's engine
        // instance.
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
                onOpenProject = {
                    navController.navigate(Screen.Chat.route) {
                        popUpTo(Screen.Chat.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                // V2-5 D10: Review changes from a project home binds the
                // project through the same trust gate, then pushes Review.
                // Back returns to Projects; the project remains the active
                // binding for the next Code chat.
                onOpenReview = { navController.navigate(Screen.Review.route) },
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
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
