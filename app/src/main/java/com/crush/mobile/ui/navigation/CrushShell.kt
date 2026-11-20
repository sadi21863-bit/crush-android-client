package com.crush.mobile.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
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
import androidx.compose.ui.platform.LocalDensity
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
    /**
     * V2-A12: a project-bound chat route is Crush Code, not Crush Chat.
     * The shell highlights the Code destination and tapping Crush Chat resets
     * the binding to the legacy Chat workspace.
     */
    codeActive: Boolean = false,
    /**
     * Return false to veto the Chat tab switch (a project run is in flight).
     * The shell then neither navigates nor changes the binding.
     */
    onSelectChat: (() -> Boolean)? = null,
    content: @Composable () -> Unit,
) {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: fallbackRoute

    BoxWithConstraints(Modifier.fillMaxSize()) {
        when (shellLayoutFor(route, maxWidth.value)) {
            ShellLayout.Hidden -> content()

            ShellLayout.BottomBar -> {
                // The keyboard must get the space, not the shell bar. With
                // adjustResize the window shrinks; a persistent bottom bar sits
                // between the composer and the IME and steals ~80dp of the
                // typing surface (owner-reported 2026-11-20). Rails are on the
                // side and stay put.
                val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        if (!imeVisible) {
                            CrushBottomBar(
                                route = route,
                                navController = navController,
                                codeActive = codeActive,
                                onSelectChat = onSelectChat,
                            )
                        }
                    },
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
            }

            ShellLayout.Rail -> Row(Modifier.fillMaxSize()) {
                CrushRail(route, navController, codeActive, onSelectChat)
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }

            ShellLayout.ExtendedRail -> Row(Modifier.fillMaxSize()) {
                CrushExtendedRail(route, navController, codeActive, onSelectChat)
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        }
    }
}

/** V2-A12: Chat and Code share the `chat` route; the binding decides which. */
private fun isSelected(
    destination: PrimaryDestination,
    route: String?,
    codeActive: Boolean,
): Boolean = when (destination) {
    PrimaryDestination.Chat -> destination.route == route && !codeActive
    PrimaryDestination.Projects ->
        destination.route == route ||
            (codeActive && route == Screen.Chat.route)
    PrimaryDestination.Settings -> destination.route == route
}

@Composable
private fun CrushBottomBar(
    route: String?,
    navController: NavController,
    codeActive: Boolean,
    onSelectChat: (() -> Boolean)?,
) {
    NavigationBar(
        modifier = Modifier.testTag("shell-bottom-bar"),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        PrimaryDestination.entries.forEach { destination ->
            val selected = isSelected(destination, route, codeActive)
            NavigationBarItem(
                selected = selected,
                onClick = {
                    val proceed = if (destination == PrimaryDestination.Chat) {
                        onSelectChat?.invoke() ?: true
                    } else {
                        true
                    }
                    if (proceed) navigatePrimary(navController, destination)
                },
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
private fun CrushRail(
    route: String?,
    navController: NavController,
    codeActive: Boolean,
    onSelectChat: (() -> Boolean)?,
) {
    NavigationRail(
        modifier = Modifier.testTag("shell-rail"),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        PrimaryDestination.entries.forEach { destination ->
            val selected = isSelected(destination, route, codeActive)
            NavigationRailItem(
                selected = selected,
                onClick = {
                    if (destination == PrimaryDestination.Chat) onSelectChat?.invoke()
                    navigatePrimary(navController, destination)
                },
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
private fun CrushExtendedRail(
    route: String?,
    navController: NavController,
    codeActive: Boolean,
    onSelectChat: (() -> Boolean)?,
) {
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
                val selected = isSelected(destination, route, codeActive)
                NavigationDrawerItem(
                    label = { Text(stringResource(destination.labelRes)) },
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
                    selected = selected,
                    onClick = {
                        if (destination == PrimaryDestination.Chat) onSelectChat?.invoke()
                        navigatePrimary(navController, destination)
                    },
                    modifier = Modifier.padding(
                        horizontal = CrushSpacing.md,
                        vertical = 2.dp,
                    ),
                )
            }
        }
    }
}
