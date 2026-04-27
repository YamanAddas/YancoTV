package com.yancotv.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.UserAgentPreset
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.sources.SourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Network preferences — UA preset dropdown + custom escape hatch
 * (MK.17.1a), test-connection probe (MK.17.2), HTTP timeouts.
 */
@Composable
fun SettingsNetworkTab(
    modifier: Modifier = Modifier,
    prefs: AppPreferences = koinInject(),
    sources: SourceRepository = koinInject(),
    http: HttpClient = koinInject(),
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

        UserAgentPicker(
            currentValue = state.userAgentOverride,
            onPick = { preset ->
                if (preset == UserAgentPreset.SYSTEM) {
                    scope.launch { prefs.setUserAgent("") }
                } else if (preset != UserAgentPreset.CUSTOM) {
                    scope.launch { prefs.setUserAgent(preset.value.orEmpty()) }
                }
            },
        )

        // The Custom row only shows when the active preset is Custom — keeps
        // the screen clean when a preset is selected. The user lands here by
        // typing or by picking "Custom…" from the dropdown.
        val activePreset = UserAgentPreset.matchValue(state.userAgentOverride)
        if (activePreset == UserAgentPreset.CUSTOM) {
            SettingsClickToEditField(
                label = "Custom User-Agent",
                description = "Verbatim string. Some IPTV providers require an exact match.",
                value = state.userAgentOverride.orEmpty(),
                onValueChange = { input -> scope.launch { prefs.setUserAgent(input.take(256)) } },
                hint = "VLC/3.0.20 LibVLC/3.0.20",
            )
        }

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

        TestConnectionRow(scope = scope, sources = sources, http = http)

        Text(
            text = "Changes apply to new HTTP requests — restart playback or re-sync a source to pick them up for in-flight connections.",
            color = LocalYancoPalette.current.TextMuted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun UserAgentPicker(
    currentValue: String?,
    onPick: (UserAgentPreset) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = UserAgentPreset.matchValue(currentValue)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "User-Agent",
            color = LocalYancoPalette.current.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Preset known-good strings. Pick \"Custom…\" to type a verbatim UA.",
            color = LocalYancoPalette.current.TextMuted,
            fontSize = 11.sp,
        )
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(6.dp),
        ) {
            Text(text = active.displayName)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            UserAgentPreset.values().forEach { preset ->
                DropdownMenuItem(
                    text = { Text(text = preset.displayName) },
                    onClick = {
                        expanded = false
                        onPick(preset)
                    },
                )
            }
        }
    }
}

@Composable
private fun TestConnectionRow(
    scope: kotlinx.coroutines.CoroutineScope,
    sources: SourceRepository,
    http: HttpClient,
) {
    var status by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = {
                if (running) return@OutlinedButton
                running = true
                status = "Testing…"
                scope.launch {
                    val result = probeFirstActiveSource(sources, http)
                    status = result
                    running = false
                }
            },
            shape = RoundedCornerShape(6.dp),
        ) {
            Text(text = if (running) "Testing…" else "Test connection")
        }
        status?.let { s ->
            Text(
                text = s,
                color = LocalYancoPalette.current.TextMuted,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * MK.17.2 — picks the first active source's URL and probes it via the
 * shared [HttpClient]. Returns a one-line status the UI can render.
 *
 * Implemented as a small GET (capped at 1 KB) instead of HEAD — Ktor's
 * shared interface doesn't expose HEAD and HEAD coverage on IPTV
 * endpoints is spotty (some Xtream panels return 405 on HEAD even
 * when GET works). The 1 KB cap keeps the probe cheap and avoids
 * dragging full M3U / Xtream catalogs.
 */
private suspend fun probeFirstActiveSource(
    sources: SourceRepository,
    http: HttpClient,
): String =
    withContext(Dispatchers.IO) {
        runCatching {
            val target =
                sources
                    .getAll()
                    .firstOrNull { it.isActive && !it.url.isNullOrBlank() }
                    ?: return@runCatching "No active source with a URL configured."
            val url = target.url!!
            val started = System.currentTimeMillis()
            http.getBytes(
                url = url,
                options = HttpRequestOptions(timeoutMs = 10_000L, maxResponseBytes = 1_024L),
            )
            val elapsed = System.currentTimeMillis() - started
            "OK · ${target.name} · ${elapsed} ms"
        }.getOrElse { t ->
            "Failed · ${t.message ?: t::class.simpleName}"
        }
    }
