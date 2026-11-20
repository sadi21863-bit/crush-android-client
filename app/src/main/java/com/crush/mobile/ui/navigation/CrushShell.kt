package com.crush.mobile.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.crush.mobile.ui.theme.CrushSpacing
import com.crush.mobile.ui.theme.CrushType

/**
 * The adaptive app shell (V2-3, brief 4.6 / V2-A2).
 *
 * - under 600dp: bottom bar, three always-labelled destinations;
 * - 600-839dp: navigation rail;
 * - 840dp and up: permanent extended rail (icon + label, 240dp);
 * - Onboarding, Review and Diagnostics render without shell chrome: they are
 *   not primary destinations, and Review must not offer an escape hatch that
 *   skips the back-to-conversation contract.
 *
 * The shell is the only place that navigates between the three primary
 * destinations. Deep links, Review and Diagnostics keep their existing push
 * semantics in [NavGraph].
 */
@Composable
fun CrushShell(
    navController: NavHostController,
    /** The launch route, used for the single frame before the graph reports. */
    fallbackRoute: String,
    content: @Composable () -> Unit,
) {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: fallbackRoute

    BoxWithConstraints(Modifier.fillMaxSize()) {
        when (shellLayoutFor(route, maxWidth.value)) {
            ShellLayout.Hidden -> content()

            ShellLayout.BottomBar -> Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = { CrushBottomBar(route, navController) },
            ) { padding ->
                // The bar consumed the bottom inset; without consuming it here
                // the chat composer would add a second navigation-bar gap above
                // the shell bar (device-visible on API 29).
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding)
                ) {
                    content()
                }
            }

            ShellLayout.Rail -> Row(Modifier.fillMaxSize()) {
                CrushRail(route, navController)
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }

            ShellLayout.ExtendedRail -> Row(Modifier.fillMaxSize()) {
                CrushExtendedRail(route, navController)
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        }
    }
}

@Composable
private fun CrushBottomBar(route: String?, navController: NavController) {
    NavigationBar(
        modifier = Modifier.testTag("shell-bottom-bar"),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        PrimaryDestination.entries.forEach { destination ->
            val selected = destination.route == route
            NavigationBarItem(
                selected = selected,
                onClick = { navigatePrimary(navController, destination) },
                // Filled icon + label + indicator fill: three signals, because
                // the graphite accent cannot separate selected by hue.
                icon = {
                    Icon(
                        imageVector = if (selected) {
                            destination.filledIcon
                        } else {
                            destination.outlinedIcon
                        },
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(destination.labelRes)) },
            )
        }
    }
}

@Composable
private fun CrushRail(route: String?, navController: NavController) {
    NavigationRail(
        modifier = Modifier.testTag("shell-rail"),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        PrimaryDestination.entries.forEach { destination ->
            val selected = destination.route == route
            NavigationRailItem(
                selected = selected,
                onClick = { navigatePrimary(navController, destination) },
                icon = {
                    Icon(
                        imageVector = if (selected) {
                            destination.filledIcon
                        } else {
                            destination.outlinedIcon
                        },
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(destination.labelRes), style = CrushType.metadata) },
                alwaysShowLabel = true,
            )
        }
    }
}

@Composable
private fun CrushExtendedRail(route: String?, navController: NavController) {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(240.dp)
            .testTag("shell-extended-rail"),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(vertical = CrushSpacing.md)
        ) {
            Text(
                text = stringResource(com.crush.mobile.R.string.app_name),
                style = CrushType.section,
                modifier = Modifier.padding(
                    horizontal = CrushSpacing.lg,
                    vertical = CrushSpacing.sm,
                ),
            )
            PrimaryDestination.entries.forEach { destination ->
                NavigationDrawerItem(
                    label = { Text(stringResource(destination.labelRes)) },
                    icon = {
                        Icon(
                            imageVector = if (destination.route == route) {
                                destination.filledIcon
                            } else {
                                destination.outlinedIcon
                            },
                            contentDescription = null,
                        )
                    },
                    selected = destination.route == route,
                    onClick = { navigatePrimary(navController, destination) },
                    modifier = Modifier.padding(
                        horizontal = CrushSpacing.md,
                        vertical = 2.dp,
                    ),
                )
            }
        }
    }
}
