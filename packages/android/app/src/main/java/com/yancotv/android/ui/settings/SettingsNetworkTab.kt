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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.UserAgentPreset
import com.yancotv.android.ui.focus.snapToTopNearStart
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.sources.SourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Network preferences — User-Agent picker, HTTP timeouts, test-connection
 * probe. The User-Agent picker is rendered as a horizontal chip strip
 * (one chip per known preset + a Custom… escape hatch) so every option
 * is reachable in a single D-pad sweep — the previous Material3
 * `DropdownMenu` anchored a popup that ate leanback focus and made
 * RIGHT-from-the-trigger land on whatever followed it in the layout.
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
    val activePreset = UserAgentPreset.matchValue(state.userAgentOverride)

    // MK.30.6 — hoisted so snapToTopNearStart can see the same state.
    val tabScroll = rememberScrollState()

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(tabScroll)
            .snapToTopNearStart(tabScroll)
            .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 80.dp),
    ) {
        SettingsSection(
            title = "User-Agent",
            sub = "Some IPTV providers gate playlist or stream access by the request's User-Agent. Pick a known-good preset, or paste a custom string.",
        ) {
            SettingsRow(
                label = "Preset",
                hint = "Applied to every outgoing HTTP request from YancoTV. Switching is instant for new connections.",
                content = {
                    SettingsChipRow(
                        options = UserAgentPreset.values().toList(),
                        selected = activePreset,
                        label = { stringResource(it.labelRes) },
                        onSelect = { preset ->
                            scope.launch {
                                if (preset == UserAgentPreset.SYSTEM) {
                                    prefs.setUserAgent("")
                                } else if (preset != UserAgentPreset.CUSTOM) {
                                    prefs.setUserAgent(preset.value.orEmpty())
                                }
                            }
                        },
                    )
                },
            )
            if (activePreset == UserAgentPreset.CUSTOM) {
                SettingsRowSpacer()
                SettingsClickToEditField(
                    label = "Custom User-Agent",
                    description = "Verbatim string. Some providers require an exact match.",
                    value = state.userAgentOverride.orEmpty(),
                    onValueChange = { input -> scope.launch { prefs.setUserAgent(input.take(256)) } },
                    hint = "VLC/3.0.20 LibVLC/3.0.20",
                )
            }
        }

        SettingsSection(
            title = "Timeouts",
            sub = "How long YancoTV waits before giving up on a slow source. Applied to playlists, EPG fetches and stream probes.",
        ) {
            SettingsRow(
                label = "Connect timeout",
                hint = "How long to wait for the TCP connection to open before retrying.",
                content = {
                    SettingsSlider(
                        value = state.connectTimeoutSec,
                        range = 1..120,
                        unit = " s",
                        presets = listOf(5, 15, 30, 60),
                        onValueChange = { sec ->
                            scope.launch { prefs.setConnectTimeout(sec) }
                        },
                    )
                },
            )
            SettingsRowSpacer()
            SettingsRow(
                label = "Read timeout",
                hint = "Max idle time on an open connection before reconnecting.",
                content = {
                    SettingsSlider(
                        value = state.readTimeoutSec,
                        range = 1..600,
                        unit = " s",
                        presets = listOf(10, 30, 60, 180),
                        onValueChange = { sec ->
                            scope.launch { prefs.setReadTimeout(sec) }
                        },
                    )
                },
            )
        }

        SettingsSection(
            title = "Diagnostics",
            sub = "Probe a source URL to confirm credentials and reachability.",
        ) {
            TestConnectionRow(scope = scope, sources = sources, http = http)
        }

        // MK.26.A.3 — manual pairing for "Play on TV" (the in-player sender).
        // Automatic discovery is MK.26.A.2; until then the user enters the
        // TV's LAN address here. Port is fixed at HandoffServer.DEFAULT_PORT.
        SettingsSection(
            title = "Play on TV",
            sub = "Send playback to your TVs. On a TV this shows its pairing code; on a phone, set the TV's address and code below.",
        ) {
            val pairedHost by prefs.pairedTvHostFlow.collectAsState()
            val pairingCode by prefs.handoffPairingCodeFlow.collectAsState()
            SettingsClickToEditField(
                label = "Paired TV address",
                description = "Your TV's IPv4 address or hostname. Leave blank to unpair. Port 8731 is fixed.",
                value = pairedHost.orEmpty(),
                onValueChange = { input -> scope.launch { prefs.setPairedTvHost(input.trim().take(64)) } },
                hint = "192.168.68.56",
            )
            SettingsRowSpacer()
            SettingsClickToEditField(
                label = "Pairing code",
                description = "On a TV: the code to type into your phone. On a phone: enter your TV's code here.",
                value = pairingCode.orEmpty(),
                onValueChange = { input -> scope.launch { prefs.setHandoffPairingCode(input.trim().uppercase().take(12)) } },
                hint = "K7P2QX",
            )
        }
    }
}

@Composable
private fun TestConnectionRow(scope: kotlinx.coroutines.CoroutineScope, sources: SourceRepository, http: HttpClient) {
    var status by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val palette = LocalYancoPalette.current

    SettingsRow(
        label = "Test connection",
        hint = "Sends a small GET to your first active source. The credentials, URL and timeouts above are all exercised.",
        right = {
            SettingsAccentButton(
                onClick = {
                    if (running) return@SettingsAccentButton
                    running = true
                    status = "Testing…"
                    scope.launch {
                        val result = probeFirstActiveSource(sources, http)
                        status = result
                        running = false
                    }
                },
                size = ButtonSize.Compact,
            ) {
                Text(text = if (running) "Testing…" else "Run test")
            }
        },
        content =
        status?.let { result ->
            {
                val statusColor =
                    when {
                        result.startsWith("OK") -> palette.Accent
                        result.startsWith("Failed") -> palette.Error
                        else -> palette.TextSecondary
                    }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsKicker(text = "STATUS")
                    Text(
                        text = result,
                        color = statusColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    )
}

private suspend fun probeFirstActiveSource(sources: SourceRepository, http: HttpClient): String = withContext(Dispatchers.IO) {
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
        "OK · ${target.name} · $elapsed ms"
    }.getOrElse { t ->
        "Failed · ${t.message ?: t::class.simpleName}"
    }
}
