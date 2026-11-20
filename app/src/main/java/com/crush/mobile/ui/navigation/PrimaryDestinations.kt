package com.crush.mobile.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController

/**
 * The three primary destinations of the V2 shell (brief 4.6, V2-A2).
 *
 * Order is the bar/rail order. Selection is carried by a filled icon AND the
 * always-visible label, never colour alone: the graphite accent cannot carry
 * state by hue (V2-A8).
 */
enum class PrimaryDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val filledIcon: ImageVector,
    val outlinedIcon: ImageVector,
) {
    Chat(
        route = Screen.Chat.route,
        labelRes = com.crush.mobile.R.string.nav_chat,
        filledIcon = Icons.Filled.Forum,
        outlinedIcon = Icons.Outlined.Forum,
    ),
    Projects(
        route = Screen.Projects.route,
        labelRes = com.crush.mobile.R.string.nav_projects,
        filledIcon = Icons.Filled.Folder,
        outlinedIcon = Icons.Outlined.Folder,
    ),
    Settings(
        route = Screen.Settings.route,
        labelRes = com.crush.mobile.R.string.nav_settings,
        filledIcon = Icons.Filled.Settings,
        outlinedIcon = Icons.Outlined.Settings,
    );

    companion object {
        fun fromRoute(route: String?): PrimaryDestination? =
            entries.firstOrNull { it.route == route }
    }
}

/** Breakpoints from brief 4.6, expressed as a pure function so tests pin them. */
enum class ShellLayout { Hidden, BottomBar, Rail, ExtendedRail }

fun shellLayoutFor(route: String?, widthDp: Float): ShellLayout {
    if (PrimaryDestination.fromRoute(route) == null) return ShellLayout.Hidden
    return when {
        widthDp < 600f -> ShellLayout.BottomBar
        widthDp < 840f -> ShellLayout.Rail
        else -> ShellLayout.ExtendedRail
    }
}

/**
 * Switch primary destination without growing the back stack.
 *
 * The stack always keeps Chat at its root and at most one of Projects/Settings
 * above it, so Back returns to the conversation and repeated bar taps can never
 * produce duplicate destinations (brief 4.6; Gate-1 E5 recorded the oscillation
 * a plain push caused for project rows).
 *
 * State is saved for the destination being left and restored on return, so a
 * scroll position survives a detour. Callers must pass the graph's real
 * controller; this function never navigates to [Screen.Onboarding], Review or
 * Diagnostics.
 */
fun navigatePrimary(navController: NavController, destination: PrimaryDestination) {
    if (navController.currentDestination?.route == destination.route) return
    navController.navigate(destination.route) {
        popUpTo(Screen.Chat.route) {
            saveState = true
            inclusive = false
        }
        launchSingleTop = true
        restoreState = true
    }
}
