package com.yancotv.android.ui.shell

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yancotv.android.R
import com.yancotv.android.player.subtitles.OpenSubtitlesClient
import com.yancotv.android.player.subtitles.SubtitleResult
import com.yancotv.android.player.subtitles.buildSubtitleQuery
import com.yancotv.android.player.subtitles.subtitleMimeFor
import com.yancotv.android.ui.focus.placedFocus
import com.yancotv.android.ui.focus.rememberPlacedFocusAnchor
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.Radius
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoType
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentMetadata
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * MK.29.3 — a subtitle chosen in the browse preview pane, already resolved
 * to something [com.yancotv.android.player.PlaybackController.stageExternalSubtitle]
 * can hand to ExoPlayer.
 *
 * Resolution (downloading an OpenSubtitles hit into the app cache) happens
 * at *pick* time rather than at Watch time, so a failure surfaces while the
 * user is still looking at the picker instead of producing a silently
 * subtitle-less stream two taps later.
 */
internal data class ResolvedSubtitle(val label: String, val uri: Uri, val mime: String?)

/** Human-readable language name for an ISO code, falling back to the code. */
private fun languageLabel(code: String): String {
    val trimmed = code.trim()
    if (trimmed.isBlank()) return "Unknown"
    val display = runCatching { Locale(trimmed).displayLanguage }.getOrNull().orEmpty()
    return display.takeIf { it.isNotBlank() && !it.equals(trimmed, ignoreCase = true) }
        ?.replaceFirstChar { it.uppercase() }
        ?: trimmed.uppercase()
}

/**
 * Pre-play subtitle picker.
 *
 * Two sources are merged, provider first:
 *
 *  - **Provider tracks** — the `subtitles[]` array Xtream returns from
 *    `get_vod_info`, already on [ContentMetadata]. Direct URLs, no download,
 *    no quota. Most providers ship none, which is why the second source
 *    exists.
 *  - **OpenSubtitles** — searched once when the sheet opens, using the same
 *    title-cleaning pipeline the in-player search uses so noisy IPTV names
 *    ("AR-SUBS-Inception 1080p x265") still match.
 *
 * What this deliberately cannot list: subtitle tracks muxed *inside* the
 * stream. Those only become enumerable once ExoPlayer has opened the media,
 * which by definition hasn't happened yet. They stay available from the
 * player's own SUBTITLES panel once playback starts, and the footer says so
 * rather than leaving the user to wonder why a track they know exists is
 * missing here.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PreviewSubtitleOverlay(
    item: ContentItem,
    metadata: ContentMetadata?,
    preferredLanguage: String,
    selected: ResolvedSubtitle?,
    onDismiss: () -> Unit,
    onSelect: (ResolvedSubtitle?) -> Unit,
    client: OpenSubtitlesClient = koinInject(),
) {
    BackHandler { onDismiss() }
    val palette = LocalYancoPalette.current
    val scope = rememberCoroutineScope()
    val firstRowAnchor = rememberPlacedFocusAnchor()

    val providerTracks =
        remember(metadata) {
            metadata?.subtitles.orEmpty().filter { it.url.isNotBlank() }
        }
    var online by remember(item.id) { mutableStateOf<List<SubtitleResult>>(emptyList()) }
    var searching by remember(item.id) { mutableStateOf(true) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    // Non-null while a pick is being downloaded — blocks a second concurrent
    // pick and drives the row's "Downloading…" label.
    var resolving by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.id, preferredLanguage) {
        searching = true
        error = null
        val bundle = buildSubtitleQuery(item, null)
        if (bundle == null || bundle.query.isBlank()) {
            searching = false
            return@LaunchedEffect
        }
        val found =
            withContext(Dispatchers.IO) {
                runCatching {
                    client.search(
                        query = bundle.query,
                        season = bundle.season,
                        episode = bundle.episode,
                        languages = preferredLanguage.ifBlank { "en" },
                        type = bundle.type,
                    )
                }.onFailure {
                    Log.w("Yanco", "PreviewSubtitleOverlay search failed: ${it.message}", it)
                }.getOrNull()
            }
        if (found == null) {
            error = "Couldn't reach the subtitle service"
        } else {
            online = found
        }
        searching = false
    }

    // Re-assert focus as the sheet moves through loading → loaded: each
    // state composes a different first row, and a request that landed on
    // the spinner row would be orphaned when results replace it.
    LaunchedEffect(searching, online, error) { firstRowAnchor.awaitAndRequest() }

    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .focusGroup()
            .focusProperties { exit = { FocusRequester.Cancel } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
            Modifier
                .widthIn(min = 280.dp, max = 520.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 440.dp)
                .clip(RoundedCornerShape(Radius.panel))
                .background(palette.BackgroundRaised)
                .border(1.dp, palette.PanelBorder, RoundedCornerShape(Radius.panel))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Text(
                text = stringResource(R.string.cf_subtitles),
                color = palette.TextPrimary,
                style = YancoType.LabelStrong,
                modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
            )
            PanelDivider()

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                item(key = "sub:off") {
                    SubtitleOptionRow(
                        label = stringResource(R.string.ps_off),
                        detail = null,
                        selected = selected == null,
                        enabled = resolving == null,
                        modifier = Modifier.placedFocus(firstRowAnchor),
                        onClick = {
                            onSelect(null)
                            onDismiss()
                        },
                    )
                }
                itemsIndexed(providerTracks, key = { i, t -> "prov:$i:${t.url}" }) { _, track ->
                    val label = languageLabel(track.language)
                    SubtitleOptionRow(
                        label = label,
                        detail = stringResource(R.string.ps_from_provider),
                        selected = selected?.uri?.toString() == track.url,
                        enabled = resolving == null,
                        onClick = {
                            // Direct URL — ExoPlayer side-loads it itself, so
                            // there is nothing to download and no quota cost.
                            onSelect(
                                ResolvedSubtitle(
                                    label = label,
                                    uri = Uri.parse(track.url),
                                    mime = subtitleMimeFor(track.url),
                                ),
                            )
                            onDismiss()
                        },
                    )
                }
                if (searching) {
                    item(key = "sub:searching") {
                        SubtitleStatusRow(text = stringResource(R.string.ps_searching))
                    }
                }
                error?.let { message ->
                    item(key = "sub:error") { SubtitleStatusRow(text = message) }
                }
                if (!searching && error == null && online.isEmpty() && providerTracks.isEmpty()) {
                    item(key = "sub:none") {
                        SubtitleStatusRow(text = stringResource(R.string.ps_none_found))
                    }
                }
                itemsIndexed(online, key = { _, r -> "os:${r.fileId}" }) { _, result ->
                    val label =
                        buildString {
                            append(languageLabel(result.language))
                            if (result.hearingImpaired) append(" [CC]")
                            if (result.aiTranslated) append(" [AI]")
                        }
                    val busy = resolving == "os:${result.fileId}"
                    SubtitleOptionRow(
                        label = label,
                        detail =
                        when {
                            busy -> "Downloading…"
                            else -> result.release.take(44).ifBlank { result.fileName.take(44) }
                        },
                        selected = selected?.label == label,
                        enabled = resolving == null || busy,
                        onClick = {
                            if (resolving != null) return@SubtitleOptionRow
                            resolving = "os:${result.fileId}"
                            scope.launch {
                                val outcome =
                                    withContext(Dispatchers.IO) {
                                        runCatching { client.download(result.fileId) }
                                    }
                                resolving = null
                                outcome
                                    .onSuccess { dl ->
                                        onSelect(
                                            ResolvedSubtitle(
                                                label = label,
                                                uri = Uri.fromFile(dl.file),
                                                mime = subtitleMimeFor(dl.file.name),
                                            ),
                                        )
                                        onDismiss()
                                    }.onFailure { t ->
                                        Log.w("Yanco", "PreviewSubtitleOverlay download failed: ${t.message}", t)
                                        // Surfaced in-sheet: the anonymous
                                        // OpenSubtitles key allows 5 downloads
                                        // a day, and "nothing happened" is the
                                        // worst possible way to communicate
                                        // hitting that ceiling.
                                        error = t.message ?: "Download failed"
                                    }
                            }
                        },
                    )
                }
            }

            PanelDivider()
            Text(
                text = stringResource(R.string.ps_embedded_note),
                color = palette.TextMuted,
                style = YancoType.Caption,
                modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
            )
        }
    }
}

@Composable
private fun PanelDivider() {
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalYancoPalette.current.PanelBorder),
    )
}

@Composable
private fun SubtitleStatusRow(text: String) {
    Text(
        text = text,
        color = LocalYancoPalette.current.TextMuted,
        style = YancoType.Body,
        modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
    )
}

@Composable
private fun SubtitleOptionRow(label: String, detail: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg =
        when {
            focused -> palette.BackgroundHover
            selected -> palette.Accent.copy(alpha = 0.12f)
            else -> Color.Transparent
        }
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .background(bg)
            .focusable(interactionSource = interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ).semantics {
                // Role.RadioButton alone announces the control but not which
                // one is active; the merged descendant text has no notion of
                // selection either.
                contentDescription = buildString {
                    append(label)
                    detail?.let { append(", $it") }
                    if (selected) append(", selected")
                }
            }.padding(horizontal = Space.lg, vertical = Space.md),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (focused || selected) palette.Accent else palette.TextPrimary,
                style = YancoType.Label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    text = it,
                    color = palette.TextMuted,
                    style = YancoType.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Text(text = "✓", color = palette.Accent, style = YancoType.Label)
        }
    }
}
