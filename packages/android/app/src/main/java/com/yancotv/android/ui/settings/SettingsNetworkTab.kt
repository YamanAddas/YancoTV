package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.ui.theme.YancoPalette
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
 */
@Composable
fun SettingsNetworkTab(
    modifier: Modifier = Modifier,
    prefs: AppPreferences = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val state by prefs.networkFlow.collectAsState()

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Network",
            color = YancoPalette.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )

        PrefCard(
            title = "User-Agent override",
            description = "Some IPTV providers reject the default. Paste a custom string here (e.g. `VLC/3.0.20 LibVLC/3.0.20`) or leave blank to use the system default.",
        ) {
            var draft by remember(state.userAgentOverride) { mutableStateOf(state.userAgentOverride.orEmpty()) }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(256); scope.launch { prefs.setUserAgent(draft) } },
                singleLine = true,
                placeholder = { Text("VLC/3.0.20 LibVLC/3.0.20", color = YancoPalette.TextMuted) },
                textStyle = TextStyle(color = YancoPalette.TextPrimary, fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = YancoPalette.TextPrimary,
                    unfocusedTextColor = YancoPalette.TextPrimary,
                    focusedBorderColor = YancoPalette.Accent,
                    unfocusedBorderColor = YancoPalette.BackgroundHover,
                    cursorColor = YancoPalette.Accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeoutField(
                label = "Connect timeout (s)",
                value = state.connectTimeoutSec,
                onCommit = { scope.launch { prefs.setConnectTimeout(it) } },
                modifier = Modifier.weight(1f),
            )
            TimeoutField(
                label = "Read timeout (s)",
                value = state.readTimeoutSec,
                onCommit = { scope.launch { prefs.setReadTimeout(it) } },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "Changes apply to new HTTP requests — restart playback or re-sync a source to pick them up for in-flight connections.",
            color = YancoPalette.TextMuted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun TimeoutField(
    label: String,
    value: Int,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(value) { mutableStateOf(value.toString()) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(YancoPalette.BackgroundRaised)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = label, color = YancoPalette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = draft,
            onValueChange = { input ->
                val cleaned = input.filter { it.isDigit() }.take(4)
                draft = cleaned
                cleaned.toIntOrNull()?.takeIf { it in 1..600 }?.let(onCommit)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(color = YancoPalette.TextPrimary, fontSize = 13.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = YancoPalette.TextPrimary,
                unfocusedTextColor = YancoPalette.TextPrimary,
                focusedBorderColor = YancoPalette.Accent,
                unfocusedBorderColor = YancoPalette.BackgroundHover,
                cursorColor = YancoPalette.Accent,
            ),
        )
    }
}

@Composable
private fun PrefCard(
    title: String,
    description: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(YancoPalette.BackgroundRaised)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = YancoPalette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(description, color = YancoPalette.TextMuted, fontSize = 11.sp)
        content()
    }
}
