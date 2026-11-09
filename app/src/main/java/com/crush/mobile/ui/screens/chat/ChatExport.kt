package com.crush.mobile.ui.screens.chat

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat-side save/copy surface (owner-directed follow-up to the Gate-2 export
 * proof, which closed the CODE side: Review -> Export a copy, byte-exact on
 * device). The user asked for the Claude/ChatGPT shape - the model's
 * generated content is directly copyable and saveable from the chat.
 *
 * In Chat mode the engine cannot create files at all (deny-ALL tool policy,
 * S-01, in the capabilities ledger), so there is no generated FILE to
 * download - what the model generates is TEXT in the transcript: fenced code
 * blocks and whole markdown messages. These helpers turn that text into a
 * file only where THE USER says: a SAF CreateDocument picker, the same
 * launcher pattern the Review screen device-proved (bf5019a).
 *
 * BOUNDARY, stated precisely: this is app-side only. The engine never sees
 * these chips, S-01's fail-closed chat policy is untouched, no tool fires,
 * nothing lands in any workspace, and no Review card is created. The bytes
 * written are exactly the text the model emitted, so a save is byte-exact by
 * construction - the same semantics the Review export holds for disk files.
 */

/**
 * The EXACT block text the model emitted - never the display-trimmed copy.
 * CODE_MAX_LINES bounds layout per frame; the saved artifact is the full
 * block, or the truncation would silently lie inside a file with our name
 * on it.
 */
internal fun codeBlockContent(block: MdBlock.Code): String =
    block.lines.joinToString("\n")

/**
 * Suggested display name for one code block, from the fence's language tag.
 * The tag is a HINT, not a promise: the picker's name field stays editable,
 * so the user can rename before the write. An unfamiliar tag falls back to
 * the sanitized tag itself (sanitized to filename-safe characters and
 * length-capped - a fence tag is model output, so never trust its shape),
 * and a missing tag collapses to "txt".
 */
internal fun codeSaveName(lang: String?): String {
    val ext = when (lang?.trim()?.lowercase(Locale.US)) {
        "python", "py", "python3" -> "py"
        "javascript", "js", "node", "jsx" -> "js"
        "typescript", "ts", "tsx" -> "ts"
        "kotlin", "kt" -> "kt"
        "java" -> "java"
        "json" -> "json"
        "yaml", "yml" -> "yml"
        "bash", "sh", "shell", "zsh", "shell-session", "console" -> "sh"
        "markdown", "md" -> "md"
        "html" -> "html"
        "css" -> "css"
        "rust", "rs" -> "rs"
        "go", "golang" -> "go"
        "c" -> "c"
        "cpp", "c++" -> "cpp"
        "csharp", "cs", "c#" -> "cs"
        "swift" -> "swift"
        "ruby", "rb" -> "rb"
        "php" -> "php"
        "sql" -> "sql"
        "xml" -> "xml"
        "toml" -> "toml"
        "diff" -> "diff"
        null, "" -> "txt"
        else -> lang.orEmpty().trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_-]"), "").take(12)
            .ifBlank { "txt" }
    }
    return "code.$ext"
}

/**
 * Suggested display name for a whole assistant message saved as Markdown.
 * Timestamped (clock format HHmmss) so successive saves from one
 * conversation do not silently overwrite each other. The name is a
 * suggestion only - the picker stays editable.
 */
internal fun messageSaveName(): String =
    "message-" +
        LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss", Locale.US)) +
        ".md"

/** One save waiting between the launch call and the picker's reply. */
private data class PendingChatSave(val content: String, val successToast: String)

/**
 * The SAF write pocket for the chat surface: call the returned lambda with
 * (suggestedName, content) and the picker opens; the reply writes the exact
 * bytes and answers with one Toast, mirroring ReviewScreen's proven
 * contract - the pending payload rides composition state because the
 * launcher reply callback cannot take call-time arguments:
 * - user-cancel / dismissed picker: the pending save clears silently; no
 *   Toast, no error - a cancel is a decision, not a failure.
 * - destination refuses a stream / I/O error: one precise failure sentence,
 *   never a silent loss.
 */
@Composable
internal fun rememberChatTextSaver(): (name: String, content: String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingChatSave?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val save = pending
        pending = null
        if (uri == null || save == null) return@rememberLauncherForActivityResult
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                try {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: return@withContext "Save failed: the destination refused a write stream."
                    stream.use { it.write(save.content.toByteArray(Charsets.UTF_8)) }
                    null
                } catch (e: Exception) {
                    "Save failed: ${e.message ?: e.javaClass.simpleName}."
                }
            }
            Toast.makeText(context, failure ?: save.successToast, Toast.LENGTH_SHORT).show()
        }
    }
    return { name, content ->
        pending = PendingChatSave(content, "Saved $name.")
        launcher.launch(name)
    }
}

/**
 * Copy text verbatim to the clipboard with one plain-language Toast. Toast
 * from a click handler (main thread here) is the accepted pattern for
 * chat-surface feedback - the screen has no transient-sentence slot like the
 * Review screen's.
 */
internal fun copyToClipboard(
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    context: Context,
    text: String,
    toast: String,
) {
    clipboard.setText(AnnotatedString(text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

/**
 * The whole-message row under a completed assistant bubble: "Copy message",
 * "Save as Markdown", and - P6 Batch C - "Use this plan in Code". Buttons,
 * not gestures: long-press surfaces hide from new users on a phone, and the
 * Review screen's TextButton grammar is what the app already reads.
 * Rendered only for completed assistant text - a streaming bubble saves
 * half-drawn content and a failed turn's recovery affordance is Retry, not
 * save.
 */
@Composable
internal fun MessageActionsRow(
    text: String,
    /**
     * P6 Batch C (plan c1): when the caller offers the handoff entry, a
     * third chip - "Use this plan in Code" - opens the consent sheet with
     * [text] as the summary draft; null keeps the row exactly as it was.
     * The chip renders inside THIS row, so it carries the
     * completed-assistant gate by construction - [handoffEntryVisible] is
     * that gate extracted verbatim as the named predicate the call site
     * composes.
     */
    onUsePlanInCode: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val saver = rememberChatTextSaver()
    Row(modifier = Modifier.padding(top = 2.dp, start = 2.dp)) {
        TextButton(
            onClick = {
                copyToClipboard(clipboard, context, text, "Copied message.")
            },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Text("Copy message", style = MaterialTheme.typography.labelSmall)
        }
        TextButton(
            onClick = { saver(messageSaveName(), text) },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Text("Save as Markdown", style = MaterialTheme.typography.labelSmall)
        }
        onUsePlanInCode?.let { usePlan ->
            TextButton(
                onClick = usePlan,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("Use this plan in Code", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

