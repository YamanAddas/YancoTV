---
name: native-android-mk
description: Use before any edit, write, review, test, or debug work under packages/android/ or packages/shared/. Covers YancoTV native Android, Android TV, Fire TV, Kotlin, Jetpack Compose, androidx.tv.material, Media3 ExoPlayer, SQLDelight, Kotlin Multiplatform shared code, PlaybackController, BrowseShell, ContentDetailScreen, focus primitives, semantics, resume points, schema units, and MK.* milestone discipline.
---

# Native Android MK

Use this skill as the pre-flight checklist for YancoTV native work. It keeps the Android app aligned with `PRODUCTION_PLAN_NATIVE.md` and prevents repeats of the MK.8 audit bug cluster.

## First Actions

1. Read the root `AGENTS.md` and the closest package `AGENTS.md`.
2. Check `PRODUCTION_PLAN_NATIVE.md` for the current `MK.*` task and bug IDs.
3. Keep edits scoped to one `MK.*` subtask unless the user explicitly asks otherwise.
4. If touching IPTV parsing, Xtream, Stalker, XMLTV, catch-up, classification, or title cleaning, also use the `iptv-domain` skill.
5. Do not edit `CLAUDE.md`, `packages/**/CLAUDE.md`, or `.claude/**` unless the user explicitly asks to update Claude Code guidance.

## Android Rules

- `PlaybackController` owns the single `ExoPlayer`. Never instantiate another `ExoPlayer`.
- `PlaybackController` is main-thread-only. Any repository work it owns must dispatch internally to `Dispatchers.IO`; callers must not wrap controller calls in IO.
- Before every `controller.play(...)` launch site, check `controller.currentId == target.id`. If it is already playing, call `PlayerLauncher.launch(context)` and do not re-prepare the `MediaItem`.
- Persist the outgoing resume point before every queue or media transition: `stop()`, `next()`, `previous()`, and `play()` when the item changes. Lifecycle hooks are not enough.
- Episode playback uses the typed `PlaybackController.play(Playable.Episode)` path. Do not use `play(list, index)` for episodes.
- Series containers are not playable. Only `Channel`, `Movie`, and `Episode` can become `Playable`.
- `MainActivity.onStop` must persist when mini-preview can host VOD, not only `PlayerActivity`.

## Compose And TV

- Never call a `packages/shared` repository directly from a Compose lambda. SQLDelight can block; launch repo mutations on `Dispatchers.IO` or hoist into `LaunchedEffect` with `withContext(Dispatchers.IO)`.
- TV focus targets use `androidx.tv.material`. Do not use Material3 clickables as TV focus targets.
- Use `PlacedFocusAnchor` plus `Modifier.placedFocus(anchor)` for focus-on-open flows.
- `FocusTrap` is a 0-dp `Spacer` with `.focusable()`, not `.clickable()`.
- Every `key(...)` boundary that scopes focus state must hold its OWN `rememberPlacedFocusAnchor()` and `FocusRequester`. Do not hoist them above the boundary. The `4a8a46e` cascade fix landed because the previous `key(contentType)` hoisted the requesters above, leaving them bound to unmounted nodes after a type swap. Pattern:
  ```
  key(contentType) {
      val coverflowFocus = remember { FocusRequester() }
      val pillAnchor = rememberPlacedFocusAnchor()
  }
  ```
  Tested by `PlacedFocusAnchorTest`. The wiring layer is verified by the cascade-nav smoke test below.

## Cascade-nav smoke test

Run before merging anything that touches `HomeScreen`, `BrowseSection`, `CategoryRail`, or the sidebar. About 60 seconds on Fire TV. Failure of any flow means a `4a8a46e`-class regression.

1. Sidebar to Categories RIGHT — from sidebar focused on Live, press D-pad RIGHT once. Focus must land on the active category pill.
2. Categories to Content RIGHT or CENTER — with a non-All pill focused (for example "Sports"), press RIGHT or CENTER. Focus must move into the coverflow AND the selected group must commit.
3. Live to Movies type swap — from Movies sidebar item, after the previous flows had you in Live's coverflow, press CENTER on Movies. The categories rail must remount and focus must land on Movies' "All" pill (not a stale Live node).
- Use `rememberSaveable` for user input, focused IDs, scroll offsets, search queries, and form fields.
- Every user-visible `AsyncImage` needs `contentDescription`, or `null` only when paired with nearby text.
- Every custom interactive `Row` or `Box` needs `Modifier.semantics { contentDescription = ... }`.
- Wrap DB reads at composable entry points in `try/catch`, log with Kermit, and render an empty or error state.

## Shared KMP Rules

- `packages/shared/src/commonMain` stays pure Kotlin: no `android.*`, `java.nio`, `NSString`, or platform I/O.
- Inject platform behavior via interfaces or `expect`/`actual`: `HttpClient`, `Logger`, `Clock`, storage, and platform services.
- Use Ktor plus Kotlinx Serialization. Do not add Retrofit, Moshi, or Gson.
- SQLDelight is the only native database surface.
- All wall-clock timestamps are milliseconds from `Clock.System.now().toEpochMilliseconds()`.
- The only seconds fields are media offsets: `watch_history.position_seconds` and `watch_history.duration_seconds`.
- Add `-- ms since epoch` comments for new timestamp columns.
- `positionFor(contentId)` returns a content-level row or `null`; never fall back to an arbitrary episode row.
- Shared state that must refresh across screens belongs in `StateFlow`, usually backed by SQLDelight `asFlow()`.

## Display And Data Hygiene

- Never render `ContentItem.sourceId`, `tvg_id`, raw `group_name`, or foreign keys as user-facing labels. Resolve display names through the proper repository or cleaner.
- Credentials never go to SQLite, settings files, or logs. Android uses Keystore.
- Do not duplicate business logic between desktop services, Android UI, and shared KMP. Extract to `packages/core` and `packages/shared` mirrors when logic crosses platforms.
- If parser/classifier behavior changes in shared KMP, mirror the relevant TypeScript core tests and Kotlin tests in the same change.

## Verification

Prefer the smallest verification loop that proves the change:

- Shared logic: `./gradlew :shared:commonTest :shared:androidUnitTest`
- Android compile: `./gradlew :app:compileDebugKotlin`
- Android build: `./gradlew :app:assembleDebug`
- Fire TV install when device behavior matters: connect `192.168.68.66:5555` (DHCP — verify with `adb devices -l`), then `./gradlew :app:installDebug`.
  With more than one device attached, `installDebug` targets them ALL and dies on signature mismatch against a TV running release — install one at a time with `adb -s <serial> install -r <apk>` and confirm the output says `Success`

Set `JAVA_HOME` to `C:\Program Files\Android\Android Studio\jbr` for Gradle work on this machine.

## Commit Discipline

- One native commit should map to one `MK.*` subtask.
- Cross-check the written task spec before commit. If implementation intentionally differs, update the plan text in the same change.
- Do not push without an explicit user request.
