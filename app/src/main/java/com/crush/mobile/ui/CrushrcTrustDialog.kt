package com.crush.mobile.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.crush.mobile.data.project.TrustVerdict.TrustReason

/**
 * The F04 crushrc trust dialog, SHARED by the two consent surfaces that the
 * S-02 central gate drives (2026-11-05):
 *
 * 1. the Projects row tap (screen-local, via ProjectsViewModel), which is
 *    the S5/A05-measured original shape; and
 * 2. the root-level host for a BLOCKED last-binding restore (MainActivity),
 *    so a cold boot lands the same informed consent instead of executing a
 *    planted/changed crushrc.
 *
 * The base copy is BYTE-IDENTICAL to the S5-measured A05 dialog (title
 * included, folder label named in the body) - host-owned security copy, not
 * to be reworded casually. Exactly one appended paragraph is allowed, driven
 * by the verdict's reason shape ([crushrcTrustNote]); the plain
 * first-consent case (and its newer noConfig-recorded twin) gets no note and
 * therefore the measured text untouched.
 */
@Composable
fun CrushrcTrustDialog(
    folderLabel: String,
    reasonNote: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This folder carries a crushrc") },
        text = {
            Text(
                ("\"%s\" contains a project-root crushrc. The embedded " +
                    "engine RUNS that file as a Bash script (twice per " +
                    "attach) with this app's permissions, upstream, " +
                    "without telling you. Opening this project will " +
                    "run it.\n\n" +
                    "If you did not write it, treat it as hostile code " +
                    "and cancel.")
                    .format(folderLabel) +
                    (reasonNote?.let { "\n\n$it" } ?: "")
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Run it and open") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * The verdict-shape → one appended dialog paragraph mapping. The plain
 * first-consent shape says it all already (the base copy); the two
 * re-approval shapes need the one fact the base copy cannot carry: WHERE
 * this folder stands relative to the consent the user already gave.
 */
fun crushrcTrustNote(kind: TrustReason): String? = when (kind) {
    TrustReason.PRESENT_NO_CONSENT -> null
    TrustReason.CHANGED_SINCE_CONSENT ->
        "The crushrc CHANGED since your last approval - the earlier " +
            "consent no longer covers these bytes. Review the contents " +
            "and re-approve."
    TrustReason.REMOVED_SINCE_CONSENT ->
        "The crushrc approved earlier is now gone from this folder. " +
            "Re-check the folder before approving - what runs on the next " +
            "open may not be what you verified."
}
