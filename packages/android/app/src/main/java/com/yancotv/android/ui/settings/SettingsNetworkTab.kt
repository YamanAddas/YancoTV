package com.yancotv.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.ui.theme.LocalYancoPalette
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Network preferences — User-Agent override + timeouts. The UA field
 * is the most useful on IPTV: some providers reject the default Ktor
 * agent and want VLC / TiviMate / a custom string. Leaving it blank
 * uses the system default (see KtorHttpClient.defaultUserAgent).
 *
 * Timeouts apply to every HTTP request the shared layer makes
 * (catalog fetch, EPG fetch, playback URL resolve). Defaults match
 * what the pre-prefs build hardcoded.
 *
 * MB-117: every text input uses [SettingsClickToEditField] so the IME
 * only opens when the user explicitly presses OK on a field — never
 * on D-pad focus alone.
 */
@Composable
fun SettingsNetworkTab(
    modifier: Modifier = Modifier,
    prefs: AppPreferences = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val state by prefs.networkFlow.collectAsState()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Network",
            color = LocalYancoPalette.current.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )

        SettingsClickToEditField(
            label = "User-Agent override",
            description = "Some IPTV providers reject the default. Paste a custom string here (e.g. `VLC/3.0.20 LibVLC/3.0.20`) or leave blank to use the system default.",
            value = state.userAgentOverride.orEmpty(),
            onValueChange = { input -> scope.launch { prefs.setUserAgent(input.take(256)) } },
            hint = "VLC/3.0.20 LibVLC/3.0.20",
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsClickToEditField(
                label = "Connect timeout (s)",
                value = state.connectTimeoutSec.toString(),
                onValueChange = { input ->
                    val cleaned = input.filter { it.isDigit() }.take(4)
                    cleaned.toIntOrNull()?.takeIf { it in 1..600 }?.let { v ->
                        scope.launch { prefs.setConnectTimeout(v) }
                    }
                },
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            SettingsClickToEditField(
                label = "Read timeout (s)",
                value = state.readTimeoutSec.toString(),
                onValueChange = { input ->
                    val cleaned = input.filter { it.isDigit() }.take(4)
                    cleaned.toIntOrNull()?.takeIf { it in 1..600 }?.let { v ->
                        scope.launch { prefs.setReadTimeout(v) }
                    }
                },
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "Changes apply to new HTTP requests — restart playback or re-sync a source to pick them up for in-flight connections.",
            color = LocalYancoPalette.current.TextMuted,
            fontSize = 11.sp,
        )
    }
}
