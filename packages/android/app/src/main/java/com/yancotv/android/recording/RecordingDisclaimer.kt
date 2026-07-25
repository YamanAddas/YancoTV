package com.yancotv.android.recording

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Stage 5.6 — first-time legal acknowledgement for recording IPTV
 * streams.
 *
 * The plan calls for "explicit user acknowledgement on first record
 * action" to back the Terms-of-Service disclaimer that recording
 * broadcast content may be subject to copyright + broadcast laws in
 * the user's jurisdiction. YancoTV doesn't host or distribute content;
 * the user is responsible for ensuring their use complies with local
 * law. We surface this exactly once, at the moment they first try to
 * record, then never again — same model TiviMate / IPTV Smarters use.
 *
 * State lives in a simple [android.content.SharedPreferences] flag
 * (no Koin, no DB) so the check is a synchronous in-memory read at
 * every Record click. Once acknowledged, future calls to [request]
 * pass through immediately.
 *
 * Usage at any record entry point (player options, Guide, channel
 * actions, etc):
 *
 * ```kotlin
 * val gate = rememberRecordingDisclaimerGate()
 * // ... onClick:
 * gate { startActualRecording() }
 * ```
 *
 * The gate composable owns its own dialog state and renders the
 * AlertDialog when needed. Multiple callsites can each call
 * `rememberRecordingDisclaimerGate()` independently — they share the
 * same persisted "acknowledged" pref so the dialog only ever appears
 * once across the whole app.
 */
object RecordingDisclaimerPrefs {
    private const val PREFS_NAME = "yanco_legal"
    private const val KEY_ACK = "recording_disclaimer_acknowledged"

    fun isAcknowledged(context: Context): Boolean = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_ACK, false)

    fun acknowledge(context: Context) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACK, true)
            .apply()
    }
}

/**
 * Composable gate. Returns a function callers invoke with the
 * "actually-start-recording" lambda. The gate consults
 * [RecordingDisclaimerPrefs] — if already acknowledged, the lambda
 * runs immediately; otherwise it's queued behind a one-time
 * AlertDialog rendered by this composable.
 */
@Composable
fun rememberRecordingDisclaimerGate(): (action: () -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val current = pending
    if (current != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Recording broadcast content") },
            text = {
                Text(
                    "Recording IPTV streams may be subject to copyright and broadcast laws in your country. " +
                        "By proceeding you acknowledge that you're responsible for ensuring your use complies with local law. " +
                        "YancoTV doesn't host or distribute content — it only plays back what you bring to it. " +
                        "You can review the full terms in Settings → About → Terms of service.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        RecordingDisclaimerPrefs.acknowledge(ctx)
                        pending = null
                        current()
                    },
                ) {
                    Text("I understand")
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            },
        )
    }

    return remember(ctx) {
        { action ->
            if (RecordingDisclaimerPrefs.isAcknowledged(ctx)) {
                action()
            } else {
                pending = action
            }
        }
    }
}
