package com.opencode.chat.ui.screens.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.chat.util.AppLog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Something went wrong, send us this."
 *
 * Optimised for a non-technical user on a phone they cannot debug: no jargon
 * required to read it, one button to produce the artefact a developer needs, and
 * no state in which it can crash. The log tail is capped and selectable rather
 * than dumped whole, because 512KB of AppLog output pasted into WhatsApp is a
 * block nobody reads.
 *
 * Scaffold + TopAppBar with a back arrow, matching SettingsScreen, so the two
 * secondary screens feel like one place in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // null means loading. Held as state rather than a remember{} of the loaded
    // value so a refresh can put it back to null without a second holder.
    var report by remember { mutableStateOf<DiagnosticsReport?>(null) }
    var capturedAt by remember { mutableStateOf("") }

    // Load on a background dispatcher (loadDiagnostics switches to IO itself),
    // then stamp. Stamped at read time, not at composition time: the load is a
    // disk read plus possibly a loopback probe, so the two can differ by a
    // second and a header that lies about when the data was collected is
    // worthless.
    LaunchedEffect(Unit) {
        report = loadDiagnostics(context)
        capturedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
    }

    val current = report

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "If the app is misbehaving, copy the block below and send it to " +
                    "the developer. It contains no API key.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (current == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // size(), not height(): the indicator has a default
                    // min size, and constraining only the height turns the
                    // circle into an ellipse.
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("Collecting…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                CrashSection(current.crash)

                StatusSection(current)

                // remember(), not a plain val: this concatenates up to 200 log
                // lines into a string. Rebuilding it on every recomposition
                // would re-copy the whole tail each time the snackbar state
                // changes, on the device least able to afford it.
                val block = remember(current, capturedAt) {
                    buildReport(current, capturedAt)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            // Platform ClipboardManager rather than
                            // LocalClipboardManager: the Compose one is on a
                            // deprecation path, and this screen is exactly where
                            // a removed API would be discovered by a user.
                            val ok = runCatching {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                cm.setPrimaryClip(
                                    ClipData.newPlainText("OpenCode Chat diagnostics", block)
                                )
                            }.onFailure {
                                AppLog.e("Diagnostics", "clipboard write failed", it)
                            }.isSuccess

                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (ok) "Diagnostics copied - paste them into a message"
                                    else "Could not copy. Long-press the text below instead."
                                )
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Text("  Copy")
                    }
                    OutlinedButton(
                        onClick = {
                            runCatching { share(context, block) }
                                .onFailure {
                                    AppLog.e("Diagnostics", "share failed", it)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "No app available to share with. Use Copy instead."
                                        )
                                    }
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Text("  Share")
                    }
                }

                LogSection(current)
            }
        }
    }
}

/**
 * The crash, first and loudest.
 *
 * Placed above the status block deliberately: a user opening this screen after
 * a crash wants the stack trace, not the device model. Rendered as a plain
 * outlined card rather than an error-coloured one so it does not read as "the
 * app is currently broken".
 */
@Composable
private fun CrashSection(crash: RecordedCrash?) {
    if (crash == null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("No crash recorded", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                // Deliberately does NOT claim "nothing has crashed". Crash
                // archiving is installed the first time this screen opens, so a
                // blank section honestly means "nothing recorded since we
                // started watching", not "nothing went wrong". Overstating it
                // would send a developer looking for a crash that the app simply
                // never had the chance to write down.
                Text(
                    "Nothing has been recorded since this screen was last opened. " +
                        "If the app seems stuck, closes itself, or stops responding, " +
                        "that is a different problem - send this page anyway, the log " +
                        "below is what matters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    "  Last crash",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                crash.capturedAt,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    crash.text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
    }
}

@Composable
private fun StatusSection(report: DiagnosticsReport) {
    Text(
        "Status",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            StatusRow("App version", "${report.appVersionName} (${report.versionCode})")
            StatusRow(
                "Android",
                "${report.androidRelease} (API ${report.sdkInt})"
            )
            StatusRow("Device", "${report.manufacturer} ${report.model}")
            StatusRow("Processor ABIs", report.abis.joinToString(", ").ifBlank { "none" })
            StatusRow(
                "Engine file",
                if (report.engineBinaryPresent) "installed" else "MISSING"
            )
            StatusRow("Engine", report.engineStateLabel)
            StatusRow(
                "Engine responding",
                when (report.engineResponding) {
                    null -> "not checked"
                    true -> "yes"
                    false -> "no"
                }
            )
            report.engineProbeNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // A silent gap is worse than a stated one: an uncollected field reads
            // as a healthy one.
            report.notes.forEach {
                Text(
                    "! $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LogSection(report: DiagnosticsReport) {
    Text(
        "Recent log",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (report.logTotalLines == 0) {
                    "Nothing recorded yet"
                } else {
                    "Last ${report.logTail.size} of ${report.logTotalLines} lines"
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (report.logTotalLines == 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "This is normal on a fresh install. The log starts writing as " +
                        "soon as the app does anything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        // Monospace because AppLog is fixed-width columns and
                        // proportional glyphs turn a stack trace into mush.
                        text = report.logTail.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        // Stack frames are wider than a phone. Scrolling
                        // horizontally beats wrapping, which destroys the
                        // alignment that makes a trace readable.
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

/**
 * Fires the system share sheet. Clipboard remains the guaranteed path because
 * share needs a handler app installed, and this screen must work on a phone
 * with nothing that accepts text/plain.
 */
private fun share(context: Context, block: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "OpenCode Chat diagnostics")
        putExtra(Intent.EXTRA_TEXT, block)
    }
    val chooser = Intent.createChooser(intent, "Send diagnostics").apply {
        // Required when the context is not an Activity. Compose hands out the
        // Activity context here, but the wrapper walk makes that an assumption
        // rather than a dependency on where the screen is hosted.
        if (context.findActivity() == null) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    context.startActivity(chooser)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}