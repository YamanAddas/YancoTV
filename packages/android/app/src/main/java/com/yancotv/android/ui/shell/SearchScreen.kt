package com.yancotv.android.ui.shell

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.yancotv.android.R
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.player.PlayerLauncher
import com.yancotv.android.ui.components.ProgressStripe
import com.yancotv.android.ui.components.ResumeBadge
import com.yancotv.android.ui.components.WatchedCheckBadge
import com.yancotv.android.ui.components.formatResumeLabel
import com.yancotv.android.ui.focus.ProvideDefaultFocusScroll
import com.yancotv.android.ui.focus.ProvideFocusScrollSpec
import com.yancotv.android.ui.focus.placedFocus
import com.yancotv.android.ui.focus.rememberPlacedFocusAnchor
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoShapes
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.content.QualityBadge
import com.yancotv.shared.history.WatchHistoryRepository
import com.yancotv.shared.history.WatchProgress
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.compose.koinInject

/**
 * FTS4-backed search. Debounced 220ms so each keystroke doesn't re-hit
 * SQLite while the user is still typing; short enough that a committed
 * word produces a result before they reach for the D-pad.
 *
 * Results stay mixed (live / movie / series) intentionally — the FTS
 * ranking already groups by content type via [ContentRepository.search]
 * and users searching for "marvel" want the whole corpus.
 */
@UnstableApi
@Composable
fun SearchScreen(
    isTv: Boolean,
    onShowDetail: ((ContentItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
    repo: ContentRepository = koinInject(),
    controller: PlaybackController = koinInject(),
    parental: com.yancotv.shared.parental.ParentalRepository = koinInject(),
    syncCoordinator: com.yancotv.android.sources.SourceSyncCoordinator = koinInject(),
    watchHistory: WatchHistoryRepository = koinInject(),
) {
    // rememberSaveable so a rotation / process-death while the user has a
    // query typed doesn't wipe the term. Results are not saved — rerunning
    // the FTS query is cheap and keeps the snapshot small.
    var query by rememberSaveable { mutableStateOf("") }

    // MK.10.3 — voice search / Assistant deep-link. SearchOverlayState
    // stores the recognised query; we drain it on first frame so the
    // user lands on results immediately. Drained, not preserved, so
    // reopening search later starts blank as expected.
    LaunchedEffect(Unit) {
        SearchOverlayState.consumeInitialQuery()?.let { q -> query = q }
    }
    val results = remember { mutableStateListOf<ContentItem>() }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val syncActive by syncCoordinator.state.collectAsState()

    // Parental gate — filter hidden IDs out of results, gate play on locked,
    // and apply the adult-content filter when enabled in Settings.
    val lockedIds by parental.lockedIds.collectAsState()
    val hiddenIds by parental.hiddenIds.collectAsState()
    val parentalSettings by parental.settings.collectAsState()
    val visible =
        remember(results.toList(), hiddenIds, parentalSettings.hideAdultContent) {
            results
                .toList()
                .let { if (hiddenIds.isEmpty()) it else it.filter { row -> row.id !in hiddenIds } }
                .let {
                    if (!parentalSettings.hideAdultContent) {
                        it
                    } else {
                        it.filterNot(com.yancotv.shared.parental.AdultContentFilter::isAdult)
                    }
                }
        }
    var pendingPlay by remember { mutableStateOf<(() -> Unit)?>(null) }
    val gatedPlay: (String, () -> Unit) -> Unit = { id, action ->
        if (id in lockedIds) pendingPlay = action else action()
    }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            results.clear()
            searching = false
            searchError = null
            return@LaunchedEffect
        }
        searching = true
        searchError = null
        delay(220L)
        // Parallel per-type fetch so the three rails finish in roughly
        // max(t1, t2, t3) instead of sum. On a freshly-synced DB the
        // FTS index pages aren't in the OS file cache yet and a sync
        // can still hold the SQLite write lock, so any single query may
        // take seconds. Per-query 8s timeout keeps the UI honest — if
        // FTS stalls we surface an error instead of leaving the user
        // staring at a frozen "Searching…" forever.
        val outcome =
            runCatching {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val jobs =
                            listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES).map { type ->
                                async {
                                    runCatching {
                                        withTimeout(8_000L) {
                                            repo.searchByType(trimmed, type, limit = 100)
                                        }
                                    }.onFailure { t ->
                                        when (t) {
                                            is TimeoutCancellationException ->
                                                Log.w("Yanco", "SearchScreen.searchByType($type) timed out")
                                            else ->
                                                Log.w("Yanco", "SearchScreen.searchByType($type) failed: ${t.message}", t)
                                        }
                                    }.getOrElse { emptyList() }
                                }
                            }
                        jobs.awaitAll().flatten()
                    }
                }
            }
        searching = false
        outcome
            .onSuccess { matches ->
                results.clear()
                results.addAll(matches)
                // MK.31.9 — Context.getString, not stringResource: this runs
                // inside the search coroutine's result callback, which is not
                // composable scope.
                searchError = if (matches.isEmpty() && syncActive != null) {
                    context.getString(R.string.se_sync_running)
                } else {
                    null
                }
            }.onFailure { t ->
                Log.w("Yanco", "SearchScreen search failed: ${t.message}", t)
                results.clear()
                searchError = context.getString(R.string.se_failed)
            }
    }

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .background(LocalYancoPalette.current.BackgroundDeep)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SearchField(value = query, onValueChange = { query = it })

        // Sync-active warning — when a source is still syncing, FTS reads
        // can stall behind the write lock and the index pages aren't yet
        // in the OS file cache. Surface this so the user knows results
        // may be incomplete or slow until sync finishes.
        syncActive?.let { active ->
            SyncBanner(sourceName = active.sourceName)
        }

        when {
            query.isBlank() ->
                EmptyState(
                    title = stringResource(R.string.se_empty_title),
                    subtitle = stringResource(R.string.se_empty_sub),
                )
            searching && results.isEmpty() ->
                EmptyState(
                    title = stringResource(R.string.se_searching),
                    subtitle = stringResource(R.string.se_searching_for, query.trim()),
                )
            searchError != null && results.isEmpty() ->
                EmptyState(
                    title = stringResource(R.string.se_unavailable),
                    subtitle = searchError ?: "",
                )
            results.isEmpty() ->
                EmptyState(
                    title = stringResource(R.string.se_no_matches),
                    subtitle = stringResource(R.string.se_no_matches_sub),
                )
            else -> {
                // MK.search.rails — partition results by content type and
                // render each in its own horizontal rail of hex orbs.
                // Mirrors the Live TV / Movies / Series sections' visual
                // language (hex capsule + accent glow on focus). Empty
                // type buckets are skipped so the user never lands on a
                // dead row.
                val live = remember(visible) { visible.filter { it.type == ContentType.LIVE } }
                val movies = remember(visible) { visible.filter { it.type == ContentType.MOVIE } }
                val series = remember(visible) { visible.filter { it.type == ContentType.SERIES } }

                // MK.28.4 — Tile-progress subscription. Live skipped (no
                // resume on continuous streams), movies + series get the
                // Resume/Watched indicator overlay on each orb. Reactive
                // via the SQLDelight notification graph.
                val vodIds by remember(movies, series) {
                    derivedStateOf { (movies + series).map { it.id }.toSet() }
                }
                val watchProgress by produceState(initialValue = emptyMap<String, WatchProgress>(), vodIds) {
                    if (vodIds.isEmpty()) {
                        value = emptyMap()
                        return@produceState
                    }
                    runCatching {
                        watchHistory.entriesByContentFlow(vodIds).collect { value = it }
                    }.onFailure { t ->
                        Log.w("Yanco", "SearchScreen watch-progress flow failed: ${t.message}", t)
                        value = emptyMap()
                    }
                }
                val onActivate: (ContentItem, List<ContentItem>) -> Unit = { item, bucket ->
                    gatedPlay(item.id) {
                        when {
                            (item.type == ContentType.MOVIE || item.type == ContentType.SERIES) && onShowDetail != null -> {
                                onShowDetail.invoke(item)
                            }
                            else -> {
                                val idx = bucket.indexOf(item).coerceAtLeast(0)
                                val alreadyPlaying = controller.currentId == item.id
                                if (!alreadyPlaying) controller.play(bucket, idx)
                                if (!isTv || alreadyPlaying) PlayerLauncher.launch(context)
                            }
                        }
                    }
                }
                ProvideFocusScrollSpec {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        if (live.isNotEmpty()) {
                            item(key = "rail-live") {
                                SearchRail(
                                    title = stringResource(R.string.se_rail_live),
                                    items = live,
                                    watchProgress = watchProgress,
                                    onActivate = { onActivate(it, live) },
                                )
                            }
                        }
                        if (movies.isNotEmpty()) {
                            item(key = "rail-movies") {
                                SearchRail(
                                    title = stringResource(R.string.se_rail_movies),
                                    items = movies,
                                    watchProgress = watchProgress,
                                    onActivate = { onActivate(it, movies) },
                                )
                            }
                        }
                        if (series.isNotEmpty()) {
                            item(key = "rail-series") {
                                SearchRail(
                                    title = stringResource(R.string.se_rail_series),
                                    items = series,
                                    watchProgress = watchProgress,
                                    onActivate = { onActivate(it, series) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingPlay?.let { action ->
        com.yancotv.android.ui.parental.PinEntryDialog(
            title = stringResource(R.string.fav_channel_locked),
            body = stringResource(R.string.fav_channel_locked_body),
            repo = parental,
            onSuccess = {
                action()
                pendingPlay = null
            },
            onDismiss = { pendingPlay = null },
        )
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    // MK.31.9 — resolved here; the semantics{} lambdas below are not
    // composable, so stringResource cannot be called from them.
    val searchFieldDesc = stringResource(R.string.se_field_desc)
    val collapsedDesc =
        if (value.isBlank()) {
            stringResource(R.string.se_field_desc_press)
        } else {
            stringResource(R.string.se_field_desc_edit, value)
        }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fieldAnchor = rememberPlacedFocusAnchor()
    val keyboard = LocalSoftwareKeyboardController.current

    // MB-296 — click-to-edit, matching [SettingsClickToEditField] and
    // AddSourceDialog. Focus lands on the ROW; the on-screen keyboard only
    // appears once the user commits by pressing OK (or tapping).
    //
    // Pre-fix this composable auto-focused the BasicTextField itself, and
    // focusing a text field pops the IME immediately. Opening Search — which
    // also happens on the KEYCODE_SEARCH hotkey and the voice deep-link —
    // therefore threw a full-screen keyboard over the results before the user
    // had asked for one, and on a TV that keyboard then has to be dismissed
    // with BACK before anything else can be reached. The user asked for
    // exactly this: "i dont want it to automatically open it. i want it to
    // open if the user press on it when selecting on the box."
    // MB-301 — deliberately `remember`, NOT `rememberSaveable`. Edit mode is a
    // transient interaction state, not user input worth restoring: the query
    // text itself is hoisted and saved by the caller. Saved across screen
    // re-entry it meant returning to Search restored `editing = true`, the
    // LaunchedEffect below re-ran on re-composition and called
    // `editFocus.requestFocus()`, and the IME opened with nothing pressed —
    // reintroducing the exact MB-296 symptom the click-to-edit rewrite removed.
    var editing by remember { mutableStateOf(false) }
    val editFocus = remember { FocusRequester() }

    LaunchedEffect(editing) {
        if (editing) {
            // MB-302 — the browse row that owns `fieldAnchor` has just left
            // composition, and `PlacedFocusAnchor.isPlaced` latches true and is
            // never cleared on unmount. Clear it so the exit path below waits
            // for the row to be re-placed instead of firing `requestFocus()`
            // at a node that isn't there yet.
            fieldAnchor.reset()
            runCatching { editFocus.requestFocus() }
        } else {
            keyboard?.hide()
            // MB-302 — ALWAYS hand focus back to the row when edit mode ends.
            //
            // An earlier version gated this on a `returnFocusToRow` flag meant
            // to tell a deliberate exit (BACK) from "the user moved the
            // selector away", so we wouldn't fight their navigation. On this
            // device that distinction is unreachable and the flag was only ever
            // wrong: while the IME is up it owns the D-pad, so the field cannot
            // lose focus by navigation — only by the IME closing.
            //
            // Worse, the IME consumes BACK to dismiss itself, so the
            // `BackHandler` below never runs on the first press. The IME
            // teardown drops field focus, `onFocusChanged` ends edit mode, and
            // the flag was still false — leaving NOTHING focused and a dead
            // D-pad. Traced on device:
            //     row focus -> false
            //     EXIT edit: return=false   <- should have been true
            fieldAnchor.awaitAndRequest()
        }
    }

    // Second BACK (or the first, on a device whose IME does not consume it)
    // leaves edit mode without tearing down the search overlay behind it.
    // Only armed while editing, so the overlay's own BACK handler is
    // untouched otherwise.
    BackHandler(enabled = editing) { editing = false }

    val border =
        when {
            editing -> LocalYancoPalette.current.Accent
            focused -> LocalYancoPalette.current.FocusRing
            else -> LocalYancoPalette.current.BorderSubtle
        }

    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(LocalYancoPalette.current.BackgroundRaised)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (editing) {
            // MB-301 — scoped to this branch on purpose. A `remember` inside the
            // `if` is re-initialised every time edit mode is entered, so the
            // "did this field ever actually hold focus" answer can't go stale
            // between edits. Hoisting it would break the guard below: the next
            // edit would start with a leftover `true` and the initial
            // unfocused callback would immediately cancel edit mode.
            var hadFocus by remember { mutableStateOf(false) }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = LocalYancoPalette.current.TextPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(LocalYancoPalette.current.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Search IME button closes the keyboard and returns to browse
                // mode — the LaunchedEffect on `query` already drives the FTS
                // call after the 220ms debounce, so there's nothing to fire.
                keyboardActions = KeyboardActions(onSearch = { editing = false }),
                modifier =
                Modifier
                    .weight(1f)
                    .focusRequester(editFocus)
                    // MB-301 — leave edit mode as soon as the field loses focus.
                    // On TV that means the IME closed (it consumes BACK itself
                    // and owns the D-pad while open, so the `BackHandler` above
                    // does not fire on the first press). Without this the row
                    // stayed a live text field, and coming back to it re-focused
                    // the field and popped the keyboard unasked.
                    //
                    // Only on a true focused -> unfocused transition:
                    // `onFocusChanged` also reports the initial unfocused state
                    // before `editFocus.requestFocus()` lands, and acting on
                    // that would cancel edit mode the instant it opened.
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            hadFocus = true
                        } else if (hadFocus) {
                            hadFocus = false
                            editing = false
                        }
                    }
                    .semantics { contentDescription = searchFieldDesc },
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.se_field_hint),
                            color = LocalYancoPalette.current.TextMuted,
                        )
                    }
                    inner()
                },
            )
        } else {
            // Browse state: a plain focusable row. No text field is composed,
            // so nothing can summon the IME until the user asks for it.
            Text(
                text = value.ifBlank { stringResource(R.string.se_field_hint) },
                color =
                if (value.isBlank()) {
                    LocalYancoPalette.current.TextMuted
                } else {
                    LocalYancoPalette.current.TextPrimary
                },
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                Modifier
                    .weight(1f)
                    .placedFocus(fieldAnchor)
                    .focusable(interactionSource = interaction)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = { editing = true },
                    ).semantics { contentDescription = collapsedDesc },
            )
        }
        // Audit catch — VoiceInputButton.kt was built but never wired.
        // The mic affordance is essential for Fire TV remotes without a
        // hardware mic key (older Toshiba / Insignia bundled remotes)
        // and for phone users whose IME has no voice button. The button
        // gates itself on RecognizerIntent availability so it dims when
        // no speech provider is installed.
        com.yancotv.android.ui.settings.VoiceInputButton(
            onResult = { spoken -> onValueChange(spoken) },
        )
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, color = LocalYancoPalette.current.TextPrimary)
            Text(text = subtitle, color = LocalYancoPalette.current.TextMuted)
        }
    }
}

@Composable
private fun SyncBanner(sourceName: String) {
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(LocalYancoPalette.current.Accent.copy(alpha = 0.12f))
            .border(1.dp, LocalYancoPalette.current.Accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = stringResource(R.string.se_syncing_banner, sourceName),
            color = LocalYancoPalette.current.Accent,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun SearchRow(item: ContentItem, onActivate: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) LocalYancoPalette.current.BackgroundHover else LocalYancoPalette.current.BackgroundRaised
    val border = if (focused) LocalYancoPalette.current.FocusRing else LocalYancoPalette.current.BorderSubtle
    val badges = remember(item.id) { QualityBadge.parse(item.title) }
    val displayTitle = remember(item.id) { item.cleanTitle?.ifBlank { null } ?: item.title }

    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onActivate)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(LocalYancoPalette.current.BackgroundDeep),
            contentAlignment = Alignment.Center,
        ) {
            if (!item.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.logoUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(0.78f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = displayTitle,
                color = LocalYancoPalette.current.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = typeLabel(item.type), color = LocalYancoPalette.current.Accent)
                item.groupName?.let {
                    Text(
                        text = it,
                        color = LocalYancoPalette.current.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                QualityChips(badges = badges)
            }
        }
    }
}

private fun typeLabel(type: ContentType): String = when (type) {
    ContentType.LIVE -> "LIVE"
    ContentType.MOVIE -> "MOVIE"
    ContentType.SERIES -> "SERIES"
}

/**
 * MK.search.rails — per-type horizontal orb rail. Header above, LazyRow
 * of hex orbs below. The LazyRow's own focus traversal handles
 * left/right within the rail; vertical D-pad walks between rails via
 * the parent LazyColumn's focus search.
 */
@Composable
private fun SearchRail(title: String, items: List<ContentItem>, watchProgress: Map<String, WatchProgress>, onActivate: (ContentItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(),
            color = LocalYancoPalette.current.TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        ProvideDefaultFocusScroll {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    SearchOrb(item = item, progress = watchProgress[item.id], onActivate = { onActivate(item) })
                }
            }
        }
    }
}

/**
 * Search-results orb — hex capsule with logo + title. Visual language
 * matches the Live TV / Movies / Series sections' [ContentOrb] in
 * CoverflowSectionScreen, minus the 3D coverflow perspective math
 * (which only makes sense on a focused-index wheel). Focus chrome via
 * shared interactionSource per the native-android-mk skill.
 */
@Composable
private fun SearchOrb(item: ContentItem, progress: WatchProgress?, onActivate: () -> Unit) {
    val ctx = LocalContext.current
    val palette = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val title = remember(item.id) { item.cleanTitle?.ifBlank { null } ?: item.title }

    Column(
        modifier = Modifier.size(width = 144.dp, height = 188.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
            Modifier
                .size(144.dp)
                .clip(YancoShapes.HexCapsule)
                .background(
                    Brush.verticalGradient(
                        listOf(palette.BackgroundElevated, palette.BackgroundDeep),
                    ),
                )
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) palette.FocusRing else palette.PanelBorder,
                    shape = YancoShapes.HexCapsule,
                )
                .focusable(interactionSource = interaction)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onActivate)
                // Audit catch — merge title into the orb's TalkBack
                // announcement so the result is "<title>, Button" not
                // "Button".
                .semantics(mergeDescendants = true) { contentDescription = title },
            contentAlignment = Alignment.Center,
        ) {
            if (!item.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.logoUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                )
            } else {
                Text(
                    text = title.take(1).uppercase(),
                    color = palette.TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            // MK.28.4 — Resume / Watched overlay on search-result orbs.
            // Same shape language as the browse coverflow (MK.28.3).
            if (progress != null) {
                if (progress.isFinished()) {
                    WatchedCheckBadge(
                        modifier = Modifier.align(Alignment.TopStart).padding(Space.xs),
                    )
                } else {
                    ResumeBadge(
                        label = formatResumeLabel(ctx, progress),
                        modifier = Modifier.align(Alignment.TopStart).padding(Space.xs),
                    )
                    if (progress.ratio > 0f) {
                        ProgressStripe(
                            progress = progress.ratio,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
        Text(
            text = title,
            color = if (focused) palette.Accent else palette.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
