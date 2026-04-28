package com.yancotv.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yancotv.android.player.ExternalPlayer
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.BufferProfile
import com.yancotv.android.prefs.DefaultExternalPlayer
import com.yancotv.android.prefs.ExternalPlayerBucket
import com.yancotv.android.prefs.ResizeMode
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Playback preferences. Each section maps cleanly onto a [SettingsRow]
 * frame around either a chip strip ([SettingsChipRow]) or one of the
 * existing toggles / fields. Buffer + decoder + resize have first-class
 * support; external-player picks per content bucket nest under their
 * own section since the option set varies with the installed apps.
 */
@Composable
fun SettingsPlaybackTab(
    modifier: Modifier = Modifier,
    prefs: AppPreferences = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val snapshot by prefs.playbackFlow.collectAsState()
    val externalSnap by prefs.externalPlayerFlow.collectAsState()
    val ctx = LocalContext.current
    val installed = remember(ctx) { ExternalPlayer.installed(ctx) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 32.dp),
    ) {
        SettingsSection(
            title = "Video",
            sub = "Aspect-ratio handling and decoder behaviour. Picks apply on the next stream load.",
        ) {
            SettingsRow(
                label = "Resize mode",
                hint = "Fit preserves aspect ratio with letterboxing; Fill stretches to the screen edges; Zoom crops to fill without letterboxing.",
                content = {
                    SettingsChipRow(
                        options = ResizeMode.values().toList(),
                        selected = snapshot.resizeMode,
                        label = { it.displayName },
                        onSelect = { mode -> scope.launch { prefs.setResizeMode(mode) } },
                    )
                },
            )
            SettingsRowSpacer()
            SettingsRow(
                label = "Buffer profile",
                hint = "Trades startup speed against rebuffer resilience. Restart playback to apply.",
                content = {
                    SettingsChipRow(
                        options = BufferProfile.values().toList(),
                        selected = snapshot.bufferProfile,
                        label = { it.displayName },
                        onSelect = { profile -> scope.launch { prefs.setBufferProfile(profile) } },
                    )
                },
            )
            SettingsRowSpacer()
            SettingsToggleRow(
                label = "Enable decoder fallback",
                description = "If a hardware decoder fails to start, retry on the software decoder. Turn off to surface decoder errors directly (debugging only).",
                checked = snapshot.enableDecoderFallback,
                onCheckedChange = { scope.launch { prefs.setDecoderFallback(it) } },
            )
        }

        SettingsSection(
            title = "Continuity",
            sub = "What YancoTV does between two pieces of content.",
        ) {
            SettingsToggleRow(
                label = "Auto-play next episode",
                description = "When a series episode ends, automatically continue to the next episode in the same season.",
                checked = snapshot.autoPlayNext,
                onCheckedChange = { scope.launch { prefs.setAutoPlayNext(it) } },
            )
        }

        SettingsSection(
            title = "Languages",
            sub = "Defaults applied when a stream ships multiple audio or subtitle tracks.",
        ) {
            SettingsClickToEditField(
                label = "Preferred audio language",
                description = "Two- or three-letter ISO 639 code (e.g. en, eng, fre).",
                value = snapshot.audioLanguage,
                onValueChange = { scope.launch { prefs.setAudioLanguage(it.take(6).lowercase()) } },
                keyboardType = KeyboardType.Ascii,
                hint = "—",
            )
            SettingsRowSpacer()
            SettingsClickToEditField(
                label = "Preferred subtitle language",
                description = "ISO 639 code. Blank = subtitles off by default.",
                value = snapshot.subtitleLanguage,
                onValueChange = { scope.launch { prefs.setSubtitleLanguage(it.take(6).lowercase()) } },
                keyboardType = KeyboardType.Ascii,
                hint = "—",
            )
        }

        SettingsSection(
            title = "Default player",
            sub = "Hand off playback to a third-party app per content type. Internal keeps the in-app player; only installed apps appear in the picker.",
        ) {
            ExternalPlayerBucket.values().forEachIndexed { idx, bucket ->
                if (idx > 0) SettingsRowSpacer()
                val current =
                    when (bucket) {
                        ExternalPlayerBucket.LIVE -> externalSnap.live
                        ExternalPlayerBucket.MOVIE -> externalSnap.movie
                        ExternalPlayerBucket.SERIES -> externalSnap.series
                    }
                SettingsRow(
                    label = bucketLabel(bucket),
                    kicker = bucketKicker(bucket),
                    content = {
                        val available =
                            buildList {
                                add(DefaultExternalPlayer.INTERNAL)
                                addAll(
                                    DefaultExternalPlayer.values()
                                        .filter { it.app != null && it.app in installed },
                                )
                            }
                        SettingsChipRow(
                            options = available,
                            selected = current,
                            label = { choice ->
                                if (choice == DefaultExternalPlayer.INTERNAL) {
                                    "Internal"
                                } else {
                                    choice.displayName
                                }
                            },
                            onSelect = { choice ->
                                scope.launch { prefs.setDefaultExternalPlayer(bucket, choice) }
                            },
                        )
                    },
                )
            }
        }
    }
}

private fun bucketLabel(bucket: ExternalPlayerBucket): String =
    when (bucket) {
        ExternalPlayerBucket.LIVE -> "Live TV"
        ExternalPlayerBucket.MOVIE -> "Movies"
        ExternalPlayerBucket.SERIES -> "Series &amp; episodes"
    }

private fun bucketKicker(bucket: ExternalPlayerBucket): String =
    when (bucket) {
        ExternalPlayerBucket.LIVE -> "LIVE"
        ExternalPlayerBucket.MOVIE -> "VOD"
        ExternalPlayerBucket.SERIES -> "EPISODES"
    }
