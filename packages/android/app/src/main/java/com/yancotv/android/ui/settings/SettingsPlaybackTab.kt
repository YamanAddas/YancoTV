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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yancotv.android.R
import com.yancotv.android.player.ExternalPlayer
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.BufferProfile
import com.yancotv.android.prefs.DefaultExternalPlayer
import com.yancotv.android.prefs.ExternalPlayerBucket
import com.yancotv.android.prefs.ResizeMode
import com.yancotv.android.ui.focus.snapToTopNearStart
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
fun SettingsPlaybackTab(modifier: Modifier = Modifier, prefs: AppPreferences = koinInject()) {
    val scope = rememberCoroutineScope()
    val snapshot by prefs.playbackFlow.collectAsState()
    val externalSnap by prefs.externalPlayerFlow.collectAsState()
    val ctx = LocalContext.current
    val installed = remember(ctx) { ExternalPlayer.installed(ctx) }

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
            title = stringResource(R.string.pb_sec_video),
            sub = stringResource(R.string.pb_sec_video_sub),
        ) {
            SettingsRow(
                label = stringResource(R.string.pb_resize_mode),
                hint = stringResource(R.string.pb_resize_mode_hint),
                content = {
                    SettingsChipRow(
                        options = ResizeMode.values().toList(),
                        selected = snapshot.resizeMode,
                        label = { stringResource(it.labelRes) },
                        onSelect = { mode -> scope.launch { prefs.setResizeMode(mode) } },
                    )
                },
            )
            SettingsRowSpacer()
            SettingsRow(
                label = stringResource(R.string.pb_buffer_profile),
                hint = stringResource(R.string.pb_buffer_profile_hint),
                content = {
                    SettingsChipRow(
                        options = BufferProfile.values().toList(),
                        selected = snapshot.bufferProfile,
                        label = { stringResource(it.labelRes) },
                        onSelect = { profile -> scope.launch { prefs.setBufferProfile(profile) } },
                    )
                },
            )
            SettingsRowSpacer()
            SettingsToggleRow(
                label = stringResource(R.string.pb_decoder_fallback),
                description = stringResource(R.string.pb_decoder_fallback_desc),
                checked = snapshot.enableDecoderFallback,
                onCheckedChange = { scope.launch { prefs.setDecoderFallback(it) } },
            )
        }

        SettingsSection(
            title = stringResource(R.string.pb_sec_continuity),
            sub = stringResource(R.string.pb_sec_continuity_sub),
        ) {
            SettingsToggleRow(
                label = stringResource(R.string.pb_autoplay_next),
                description = stringResource(R.string.pb_autoplay_next_desc),
                checked = snapshot.autoPlayNext,
                onCheckedChange = { scope.launch { prefs.setAutoPlayNext(it) } },
            )
        }

        SettingsSection(
            title = stringResource(R.string.pb_sec_languages),
            sub = stringResource(R.string.pb_sec_languages_sub),
        ) {
            SettingsClickToEditField(
                label = stringResource(R.string.pb_audio_language),
                description = stringResource(R.string.pb_audio_language_desc),
                value = snapshot.audioLanguage,
                onValueChange = { scope.launch { prefs.setAudioLanguage(it.take(6).lowercase()) } },
                keyboardType = KeyboardType.Ascii,
                hint = "—",
            )
            SettingsRowSpacer()
            SettingsClickToEditField(
                label = stringResource(R.string.pb_subtitle_language),
                description = stringResource(R.string.pb_subtitle_language_desc),
                value = snapshot.subtitleLanguage,
                onValueChange = { scope.launch { prefs.setSubtitleLanguage(it.take(6).lowercase()) } },
                keyboardType = KeyboardType.Ascii,
                hint = "—",
            )
        }

        SettingsSection(
            title = stringResource(R.string.pb_sec_default_player),
            sub = stringResource(R.string.pb_sec_default_player_sub),
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
                            // MK.31.3 — INTERNAL used to be special-cased to a
                            // hardcoded "Internal" that duplicated its own
                            // displayName; one resource now serves both.
                            label = { choice -> stringResource(choice.labelRes) },
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

@Composable
private fun bucketLabel(bucket: ExternalPlayerBucket): String = when (bucket) {
    ExternalPlayerBucket.LIVE -> stringResource(R.string.pb_bucket_live)
    ExternalPlayerBucket.MOVIE -> stringResource(R.string.pb_bucket_movie)
    ExternalPlayerBucket.SERIES -> stringResource(R.string.pb_bucket_series)
}

@Composable
private fun bucketKicker(bucket: ExternalPlayerBucket): String = when (bucket) {
    ExternalPlayerBucket.LIVE -> stringResource(R.string.pb_kicker_live)
    ExternalPlayerBucket.MOVIE -> stringResource(R.string.pb_kicker_vod)
    ExternalPlayerBucket.SERIES -> stringResource(R.string.pb_kicker_episodes)
}
