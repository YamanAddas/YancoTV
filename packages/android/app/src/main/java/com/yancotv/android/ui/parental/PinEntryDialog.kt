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
import androidx.compose.ui.res.stringResource
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
import com.yancotv.android.R
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
    // Resolved here, not at the assignment site: `error` is set from
    // inside the verify coroutine, where `stringResource` is unavailable.
    val lockedText = stringResource(R.string.pin_locked)
    val incorrectText = stringResource(R.string.pin_incorrect)

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
                    // MB-395 family — `!working` used to disable the field for
                    // the sub-second verify window, dropping focus from under
                    // the caret. The verify coroutine reads a captured copy of
                    // `pin`, so live edits during it are harmless. Lockout
                    // still disables: that's a lasting state, entered while
                    // focus is on the Unlock button, not here.
                    enabled = lockoutSec <= 0,
                    label = { Text(stringResource(R.string.pin_label)) },
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
                        text = stringResource(R.string.pin_too_many, lockoutSec),
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
                // MB-395 family — a Material3 TextButton that disables while
                // focused falls out of the focus system, so on TV a wrong PIN
                // (`working` flip + the `pin = ""` reset dropping the length
                // gate) hopped focus from Unlock onto Cancel — one accidental
                // CENTER away from closing the gate. Only the lockout (a
                // lasting, explained state) may disable; `working` and the
                // short-PIN case are guarded in onClick instead: verifyPin on
                // a short/blank PIN just reports "Incorrect PIN", which is
                // honest feedback rather than a dead control.
                enabled = lockoutSec <= 0,
                onClick = {
                    if (working) return@TextButton
                    // Short/blank PIN: feedback without calling verifyPin —
                    // a verify would count toward the brute-force lockout,
                    // and a fat-fingered CENTER on an empty field shouldn't.
                    if (pin.length < MIN_PIN_LENGTH) {
                        error = incorrectText
                        return@TextButton
                    }
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
                            error = if (stillLocked) lockedText else incorrectText
                            pin = ""
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.pin_unlock), color = LocalYancoPalette.current.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.common_cancel),
                    color = LocalYancoPalette.current.TextPrimary,
                )
            }
        },
    )
}

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 8
