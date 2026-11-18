package com.crush.mobile.ui.screens.review

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crush.mobile.CrushMobileApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Review surface's HOST (P4 v1; Phase 2b.3 extraction).
 *
 * It owns the container-scoped history, the coroutine scope, the SAF export
 * launcher, and the byte copy. The render tree lives in [ReviewContent],
 * which receives a [ReviewUiState] and two callbacks.
 *
 * DATA SOURCE IS CONTAINER-SCOPED ON PURPOSE: this screen holds no engine
 * connection and no ChatViewModel. The chat ViewModel writes turn cards; this
 * screen reads and reverts through
 * [com.crush.mobile.domain.file.ReviewHistory], whose gates refuse superseded
 * cards and mid-run surgery. Honest caps are part of the copy: history lives
 * in process memory and clears when the app dies.
 */
@Composable
fun ReviewRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val history = remember {
        (context.applicationContext as CrushMobileApp).container.reviewHistory
    }
    val cards by history.cards.collectAsStateWithLifecycle()
    val runInFlight by history.runInFlight.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Export state (P4 proof loop): which file is awaiting the SAF picker, and
    // the last export outcome as one transient sentence. Export is a READ
    // operation on disk now - byte streams in and out verbatim; it belongs to
    // the host, not to the history store, whose notes are reserved for what
    // the store itself did.
    var exportTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val (rootPath, relPath) = exportTarget ?: return@rememberLauncherForActivityResult
        exportTarget = null
        if (uri == null) return@rememberLauncherForActivityResult // user cancelled
        scope.launch(Dispatchers.IO) {
            val message = runCatching {
                val src = File(rootPath, relPath)
                if (!src.isFile) return@runCatching "File is gone; nothing to export."
                val out = context.contentResolver.openOutputStream(uri)
                    ?: return@runCatching "Could not open the destination."
                out.use { sink -> src.inputStream().use { source -> source.copyTo(sink) } }
                "Exported a copy of ${relPath.substringAfterLast('/')}."
            }.getOrElse { "Could not export: ${it.message ?: "I/O error"}" }
            withContext(Dispatchers.Main) { exportMessage = message }
        }
    }

    ReviewContent(
        state = ReviewUiState(
            cards = cards,
            runInFlight = runInFlight,
            exportMessage = exportMessage,
        ),
        onBack = onBack,
        onRevert = { cardId, path ->
            scope.launch { history.revert(cardId, path) }
        },
        onExport = { rootPath, path ->
            exportTarget = rootPath to path
            exportLauncher.launch(path.substringAfterLast('/'))
        },
    )
}
