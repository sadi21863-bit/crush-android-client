package com.opencode.chat.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * The ONE line a host must add to reach this feature.
 *
 * Deliberately a separate file so the integration surface is a single symbol
 * with its instructions attached, rather than something to be discovered by
 * reading DiagnosticsScreen.
 *
 * ## Integration
 *
 * **1. Add a route** to `NavGraph.kt`:
 *
 * ```kotlin
 * // sealed class Screen
 * object Diagnostics : Screen("diagnostics")
 * ```
 *
 * **2. Add the destination.** This is the line that matters:
 *
 * ```kotlin
 * composable(Screen.Diagnostics.route) {
 *     DiagnosticsRoute(onBack = { navController.popBackStack() })
 * }
 * ```
 *
 * **3. Add a button** wherever it belongs (Settings' About card is the obvious
 * spot) that navigates to it:
 *
 * ```kotlin
 * TextButton(onClick = { navController.navigate(Screen.Diagnostics.route) }) {
 *     Text("Diagnostics")
 * }
 * ```
 *
 * ## Optional but recommended
 *
 * Install the crash archive at startup so crashes from BEFORE the first visit
 * to this screen are preserved too. In `OpenCodeChatApp.onCreate`, before
 * `AppLog.init(this)`:
 *
 * ```kotlin
 * DiagnosticsRecorder.install(this)
 * ```
 *
 * Without it the screen still works and still shows the crash that killed the
 * current session - but only if the user happens to open Diagnostics before the
 * next crash. See [DiagnosticsRecorder] for why `app.log` alone cannot do this.
 */
@Composable
fun DiagnosticsRoute(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // Installed from here rather than from the Application so that adding the
    // nav line above is genuinely sufficient to get the whole feature. The cost
    // is that a crash before the first visit is only in app.log, which
    // AppLog.init truncates on the next launch - hence the optional
    // OpenCodeChatApp line above.
    LaunchedEffect(Unit) { DiagnosticsRecorder.install(context) }

    DiagnosticsScreen(onBack = onBack)
}