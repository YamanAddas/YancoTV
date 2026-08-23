package com.yancotv.android.ui.settings

import androidx.annotation.RawRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yancotv.android.ui.theme.LocalYancoPalette
import kotlinx.coroutines.launch

/**
 * MB-367 — full-screen viewer for a bundled licence text.
 *
 * Exists because the canonical-URL rows are dead ends on the primary target:
 * Fire TV ships no default browser, so `ACTION_VIEW http…` silently does
 * nothing there. The verbatim texts ship as raw resources (fetched from
 * gnu.org / apache.org / the Sentry repo — never typed by hand) and this
 * dialog is how a TV user actually reads them.
 *
 * D-pad: UP/DOWN scroll a page-third at a time; BACK dismisses (the Dialog's
 * own onDismissRequest). The scroll surface itself takes focus on open so
 * the very first key press works.
 */
@Composable
fun LicenseViewerDialog(title: String, @RawRes textRes: Int, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val body = remember(textRes) {
        runCatching { ctx.resources.openRawResource(textRes).bufferedReader().readText() }
            .getOrElse { "Licence text could not be loaded." }
    }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth(0.86f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(14.dp))
                .background(LocalYancoPalette.current.BackgroundElevated)
                .padding(horizontal = 28.dp, vertical = 20.dp),
        ) {
            Text(
                text = title,
                color = LocalYancoPalette.current.TextPrimary,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Box(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val step = scroll.viewportSize / 3
                        when (event.key) {
                            Key.DirectionDown -> {
                                scope.launch { scroll.animateScrollTo((scroll.value + step).coerceAtMost(scroll.maxValue)) }
                                true
                            }
                            Key.DirectionUp -> {
                                scope.launch { scroll.animateScrollTo((scroll.value - step).coerceAtLeast(0)) }
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                Text(
                    text = body,
                    color = LocalYancoPalette.current.TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(scroll).padding(end = 8.dp),
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
