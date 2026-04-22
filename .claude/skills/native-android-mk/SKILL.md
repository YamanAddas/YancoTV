---
name: native-android-mk
description: ALWAYS invoke before any edit, write, or review under packages/android/ or packages/shared/. Covers the MK.* milestone work for native Android/TV/Fire TV — Kotlin, Jetpack Compose, androidx.tv.material, Media3 ExoPlayer, SQLDelight, Kotlin Multiplatform. Encodes the MK.8 audit checklist (threading, schema units, resume-point, two-tap activation, semantics, accessibility, commit hygiene) distilled from 29 bugs shipped in one commit. Use when working on PlaybackController, HomeScreen, BrowseShell, ContentDetailScreen, SQLDelight schema, episode/series playback, focus primitives, or any MK.* sub-task.
---

# Native Android (MK.*) Checklist

Self-audit every edit under `packages/android/` or `packages/shared/` against these. They are distilled from the **MK.8 audit (2026-04-21)** — commit `ae6a7cf` introduced 29 bugs (MB-35…MB-63) in one shot. Follow these and we don't re-ship them.

## Threading

- **Never call a `packages/shared/` repository directly from a Compose lambda.** SQLDelight blocks. Click handlers that mutate state wrap in `rememberCoroutineScope().launch(Dispatchers.IO) { ... }`, OR hoist into `LaunchedEffect { withContext(Dispatchers.IO) ... }`.
- **`PlaybackController` is main-thread-only.** Any repo field it holds (`history`, future `favorites`) dispatches to IO *inside* the controller via `scope.launch(Dispatchers.IO)`. Caller must not wrap. `persistResumePoint()` and `loadCurrent()` are called from lifecycle hooks — they cannot block.

## Schema units

- **All DB timestamps are milliseconds.** Every `clock()` call in `packages/shared/` writes raw `Clock.System.now().toEpochMilliseconds()`. Do NOT divide by 1000. Applies to `content.created_at`, `epg_programmes.start_time/end_time`, `favorites.added_at`, `watch_history.watched_at`, `sources.last_synced`.
- **Exception:** `watch_history.position_seconds` and `duration_seconds` — these are media offsets, not wall-clock. Seconds on purpose.
- **When adding a timestamp column, document the unit** as a SQLDelight comment: `-- ms since epoch`.

## Resume-point persistence

- **`positionFor(contentId)` returns a content-level row or null — never an episode row.** Series containers must not seek to an arbitrary episode's offset. Correct fallback when no `episode_id IS NULL` row exists is `null`, not "first row we found".
- **Every transition that loads a new `MediaItem` persists the outgoing resume point first.** Includes `stop()`, `next()`, `previous()`, and `play()` when the queue changes. Lifecycle hooks (`onPause`, `onStop`) catch *some* transitions — they miss zap-through-player and queue-replace.
- **`MainActivity.onStop` must also persist** if a mini-preview can host VOD. Not just `PlayerActivity`.
- **New `MediaItem` = new ExoPlayer buffer.** Always check `controller.currentId == target.id` before calling `play(...)` again — if equal, it's a no-op; just `PlayerLauncher.launch(context)` to open fullscreen.

## Reactive state across screens

- **Favorite / history / parental state flows through a `StateFlow` in `shared/`**, not a per-screen `LaunchedEffect(Unit) { reload() }`. Toggling a favorite in InfoPanel must refresh FavoritesScreen without a navigation round-trip. Use SQLDelight's `asFlow()` on the underlying query.
- **User-input state uses `rememberSaveable`**, not `remember`. Search queries, form fields, focused IDs, scroll offsets — anything annoying to retype after rotation or process death.

## Two-tap TV activation

- **Every launch site checks `controller.currentId == target.id` first.** If already playing, go straight to fullscreen via `PlayerLauncher.launch(context)` — do NOT call `controller.play(...)` again (re-creates the `MediaItem`, rebuffers).
- Grep `controller.play(` periodically — confirm every call site is guarded. HomeScreen does this; FavoritesScreen missed it the first time (MB-48, MB-49).

## Display IDs vs display names

- **Never render `ContentItem.sourceId` as user-visible text.** It's a UUID/slug. Look up `sources.name` via `SourceRepository.nameFor(sourceId)` and display that. Same rule for `tvg_id`, raw `group_name`, any FK.

## Focus primitives

- **Use `PlacedFocusAnchor` + `Modifier.placedFocus(anchor)`** for focus-on-open flows. It waits for `onPlaced` before requesting focus — the delay-ladder pattern is a race and has silently failed in production.
- **`FocusTrap` is a 0-dp `Spacer` with `.focusable()`**, not `.clickable`. CENTER presses on a clickable trap swallow child `onClick`s (the episode-row freeze bug).
- **TV focus targets use `androidx.tv.material`** — Material3 clickables don't integrate with leanback focus.

## Accessibility

- **Every user-visible `AsyncImage` needs `contentDescription`** (or explicit `= null` with a paired text label). TalkBack / TV reader announces nothing otherwise.
- **Every non-Material3 interactive control needs `Modifier.semantics { contentDescription = ... }`.** Custom `Row`/`Box` + `.clickable` is silent by default.

## Error handling

- **DB reads at composable entry points need try/catch.** A corrupted row in `content` or `watch_history` crashes the whole screen otherwise. Log via Kermit and render an empty state.

## Plan-spec discipline

- **Cross-check each MK task against its written spec before committing.** MK.8.3 called for a pinned "Favorites" group at the top of the category rail — we built a standalone page. MK.8.5 called for a global search overlay + KEYCODE_SEARCH remote hotkey + Ctrl-K phone shortcut — we built a sidebar destination. If you deviate, update the plan text in the same commit with a note explaining why.

## Commit hygiene

- **One MK sub-task per commit, not three.** MK.8.3 + 8.4 + 8.5 shipped together; the bug count scaled with commit size.
- **Before committing a shell screen, re-read this checklist.** Five minutes beats a 29-bug register entry.
- **Don't push without an explicit ask.** Build + install + commit is fine; `git push` is not.

## Playable type

- Series containers are **not** `Playable`. Only `Channel`, `Movie`, `Episode` are. `ContentItem.toPlayable()` returns null for series containers and blank URLs — callers must short-circuit.
- Episode playback uses `PlaybackController.play(episode: Playable.Episode)` (the typed overload), NOT `play(list, index)` — the episode's own stable `id` must be the history key, not a synthetic `"series:ep"`.

## Reference

- [packages/android/CLAUDE.md](../../../packages/android/CLAUDE.md) — Android package guide
- [packages/shared/CLAUDE.md](../../../packages/shared/CLAUDE.md) — KMP shared guide
- [PRODUCTION_PLAN_NATIVE.md](../../../PRODUCTION_PLAN_NATIVE.md) — `MK.*` milestones + bug register (MB-*)
