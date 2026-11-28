package com.crush.mobile.ui.screens.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * H-03: a bounded ticking clock for relative-time labels. A one-shot
 * `remember { System.currentTimeMillis() }` freezes "now" for the lifetime of
 * the composition, so a row can read "now" indefinitely. The tick is display
 * only — no execution or engine truth is derived from it.
 */
@Composable
fun rememberTickingNowMs(intervalMs: Long = 60_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            delay(intervalMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}
