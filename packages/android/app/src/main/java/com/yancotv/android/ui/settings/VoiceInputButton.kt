package com.yancotv.android.ui.settings

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.R
import com.yancotv.android.ui.theme.LocalYancoPalette

/**
 * Voice button that launches the platform speech recognizer. Fire TV's voice
 * remote routes to the Amazon speech service; phones route to Google's. The
 * recognized phrase is handed back via [onResult], overwriting whatever was
 * in the field.
 *
 * No runtime permission is needed — [RecognizerIntent.ACTION_RECOGNIZE_SPEECH]
 * hands off to a system activity that owns the mic.
 */
@Composable
fun VoiceInputButton(modifier: Modifier = Modifier, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val available =
        remember {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            context.packageManager.resolveActivity(intent, 0) != null
        }

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val spoken =
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    .orEmpty()
            if (spoken.isNotBlank()) onResult(spoken)
        }

    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) LocalYancoPalette.current.Accent else LocalYancoPalette.current.BackgroundRaised
    val border = if (focused) LocalYancoPalette.current.FocusRing else LocalYancoPalette.current.BorderSubtle
    val alphaValue = if (available) 1f else 0.4f

    Box(
        modifier =
        modifier
            .height(40.dp)
            .widthIn(min = 56.dp)
            .alpha(alphaValue)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .focusable(enabled = available, interactionSource = interaction)
            .clickable(
                enabled = available,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
            ) {
                val intent =
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
                    }
                launcher.launch(intent)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.voice_input),
            color = if (focused) LocalYancoPalette.current.TextPrimary else LocalYancoPalette.current.TextMuted,
            fontSize = 12.sp,
        )
    }
}
