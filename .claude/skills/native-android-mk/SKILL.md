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

- **Most DB timestamps are milliseconds.** Every `clock()` call in `packages/shared/` writes raw `Clock.System.now().toEpochMilliseconds()`. Do NOT divide by 1000. Applies to `content.created_at`, `favorites.added_at`, `watch_history.watched_at`, `sources.last_synced`.
- **Exception 1 — `watch_history.position_seconds` / `duration_seconds`:** media offsets, not wall-clock. Seconds on purpose.
- **Exception 2 — `epg_programmes.start_time` / `end_time`: XMLTV epoch SECONDS, not ms** (matches xmltv.dtd; `XmltvParser` emits `epochSeconds`). The guide and catch-up compare against `clock() / 1000`. **Do NOT "correct" these to ms** — the schema comment says seconds, the data is ~1.78e9, and flipping them to ms silently breaks catch-up and empties the guide. (MB-390 — this bullet used to wrongly list EPG under the ms rule.)
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
- **Every `key(...)` boundary that scopes focus state holds its OWN `rememberPlacedFocusAnchor()` and `FocusRequester`.** Don't hoist them above the boundary. Cascade fix `4a8a46e`: hoisting the `coverflowFocus` requester above `key(contentType)` meant after Live → Movies the requester was still bound to Live's now-unmounted node; `requestFocus()` silently no-op'd and the type-swap appeared to "swallow" the press. Pattern:
  ```kotlin
  key(contentType) {
      // owned here — fresh per type swap
      val coverflowFocus = remember { FocusRequester() }
      val pillAnchor = rememberPlacedFocusAnchor()
      // ...
  }
  ```
  Tested by `PlacedFocusAnchorTest` (the primitive); the wiring layer is in the smoke-test list below.

## Cascade-nav smoke test (do before merging anything that touches HomeScreen / BrowseSection / CategoryRail / sidebar)

Three flows. ~60 seconds total on Fire TV. If any one is silent or lands on the wrong node, you re-introduced one of the 4 bugs from `4a8a46e`:

1. **Sidebar → Categories RIGHT** — from sidebar focused on Live, press D-pad RIGHT once. Focus must land on the active category pill in the rail. (Bug shape: focus stays in sidebar, sidebar collapses anyway.)
2. **Categories → Content (RIGHT or CENTER)** — with a non-All pill focused (e.g. "Sports"), press RIGHT or CENTER. Focus must move into the coverflow AND the selected group must commit (coverflow shows the filtered set, not All). (Bug shape: focus moves but coverflow still shows All; or focus stays on pill and only group commits.)
3. **Live → Movies type swap** — from Movies sidebar item, after the previous flows had you in Live's coverflow, press D-pad CENTER on Movies. The categories rail must remount with focus landing on **Movies' "All" pill** — never a stale node from Live. (Bug shape: pill rail visually opens but no pill is focused; or focus is on a phantom Live pill.)

## Layout direction (RTL)

The app ships Arabic, so half of these rules are load-bearing on every screen
with something laid out horizontally.

**Compose mirrors the RENDER for free and mirrors NOTHING about input.** `Row`,
`Alignment.*Start` / `*End`, `Modifier.offset` (not `absoluteOffset`) and
`fillMaxWidth(fraction)` inside a default-aligned `Box` all flip under RTL
without anyone deciding they should. `PointerEvent.position.x` and
`KEYCODE_DPAD_LEFT` / `RIGHT` never do — they are physical, always. So a
component gets a mirrored picture and unmirrored decisions, and disagrees with
itself only in Arabic, where nobody is looking.

**This has now happened twice**, in the same shape, a milestone apart:

- **MK.31.2** — `SettingsSlider`: fill drawn from `Alignment.CenterStart` (which
  mirrors), key and touch input physical. Pressing "left" raised the value while
  the fill shrank rightward.
- **MB-416** — `SectionFlowBar`: `Row` and indicator mirrored, tap and drag
  divided a raw `position.x`. Every tab activated its mirror — `المزيد` at the
  left end opened Home, `الرئيسية` at the right opened the More sheet. LTR was
  never affected, which is why it shipped.

### The rule

Decide, per component, and write the decision in the file:

1. **Interactive, and bound to a timeline** — pin it LTR
   (`CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)`)
   and keep the keys physical. A media scrubber does not mirror in any
   mainstream player, and LEFT = rewind is muscle memory independent of reading
   direction. `VodDockProgressRow` does this (MK.34.9) and says why.
2. **Interactive, and not a timeline** — mirror the render (the default) and
   make the input logical to match: `flowBarSlotAt` / `flowBarDragTarget` for
   pointer x, `startwardKey()` / `endwardKey()` for D-pad.
3. **Passive indicator** (resume progress on a card, a programme's elapsed bar,
   a download) — let it mirror. That is what the platform's own `ProgressBar`
   does under RTL, and there is no input to disagree with it.

Half-mirrored is worse than either choice: the viewer presses one way and
watches the thing move the other.

### Checking it

`Modifier.offset` and `fillMaxWidth(fraction)` were both confirmed
direction-aware by measurement on a device, not by reading the docs — drag the
flow bar in Arabic and the indicator lands under the slot you dragged to. When
in doubt, set the phone to Arabic and look; the dump's `bounds` tell you which
way the render went, and a tap tells you which way the input went.

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
