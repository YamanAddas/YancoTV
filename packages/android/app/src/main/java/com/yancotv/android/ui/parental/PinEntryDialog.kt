package com.yancotv.android.ui.parental

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.parental.ParentalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reusable PIN-entry gate used by:
 *  - Settings → Parental tab (for Change / Remove PIN).
 *  - PlaybackController → locked-channel play (MK.8.7).
 *  - Any "Require PIN for Settings" guarded flow.
 *
 * Shows a password-masked text field, a live lockout countdown, and the
 * canonical Confirm / Cancel button pair. The caller provides a verify
 * lambda — typically `repo::verifyPin` — so unit tests can inject a
 * fake. On success, [onSuccess] runs and the dialog dismisses.
 *
 * Lockout handling: if the repository reports a non-zero remaining
 * cooldown, the Confirm button disables and the title line shows the
 * seconds remaining, ticking down every second. This matches the
 * desktop UX and makes the brute-force deterrent visible.
 */
@Composable
fun PinEntryDialog(title: String, body: String? = null, repo: ParentalRepository, onSuccess: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var lockoutSec by remember { mutableStateOf(0) }

    // Lockout ticker — polls the repo every second. Cheap (reads an in-memory
    // long inside a mutex) and keeps the UI accurate even if the lockout
    // started in a different screen.
    LaunchedEffect(Unit) {
        while (true) {
            val ms = repo.lockoutRemainingMs()
            lockoutSec = ((ms + 999L) / 1000L).toInt()
            delay(500L)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalYancoPalette.current.BackgroundRaised,
        title = {
            Text(text = title, color = LocalYancoPalette.current.TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (body != null) {
                    Text(text = body, color = LocalYancoPalette.current.TextMuted, fontSize = 12.sp)
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        // Keep the field numeric-only to match the Android
                        // IME numeric keyboard and discourage long passwords —
                        // this is a PIN, not a password.
                        pin = it.filter(Char::isDigit).take(MAX_PIN_LENGTH)
                        error = null
                    },
                    singleLine = true,
                    enabled = !working && lockoutSec <= 0,
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    textStyle =
                    TextStyle(
                        color = LocalYancoPalette.current.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedTextColor = LocalYancoPalette.current.TextPrimary,
                        unfocusedTextColor = LocalYancoPalette.current.TextPrimary,
                        focusedBorderColor = LocalYancoPalette.current.Accent,
                        unfocusedBorderColor = LocalYancoPalette.current.BackgroundHover,
                        cursorColor = LocalYancoPalette.current.Accent,
                    ),
                )
                // MK.28.8 (MB-279) — live region so TalkBack announces
                // "Incorrect PIN" / the lockout countdown; without it the
                // field clears and the Unlock button disables in silence.
                if (lockoutSec > 0) {
                    Text(
                        text = "Too many attempts. Try again in ${lockoutSec}s.",
                        color = LocalYancoPalette.current.Error,
                        fontSize = 12.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else if (error != null) {
                    Text(
                        text = error!!,
                        color = LocalYancoPalette.current.Error,
                        fontSize = 12.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && lockoutSec <= 0 && pin.length >= MIN_PIN_LENGTH,
                onClick = {
                    if (working) return@TextButton
                    working = true
                    scope.launch {
                        // MK.28.3 (MB-251) — verifyPin reads the settings row
                        // (blocking) before its offloaded hash; lockout check
                        // reads again. Both off-main.
                        val ok = withContext(Dispatchers.IO) { repo.verifyPin(pin) }
                        working = false
                        if (ok) {
                            onSuccess()
                        } else {
                            // Check whether a lockout just started so we
                            // show the right error rather than "incorrect".
                            val stillLocked =
                                withContext(Dispatchers.IO) { repo.lockoutRemainingMs() > 0L }
                            error = if (stillLocked) "Locked" else "Incorrect PIN"
                            pin = ""
                        }
                    }
                },
            ) {
                Text("Unlock", color = LocalYancoPalette.current.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = LocalYancoPalette.current.TextPrimary)
            }
        },
    )
}

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 8
