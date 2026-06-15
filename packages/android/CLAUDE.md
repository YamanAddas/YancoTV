# packages/android — Claude Code Guide

Native Android + Android TV / Fire TV / Google TV. Jetpack Compose + Media3 ExoPlayer. Shared logic is in [`../shared/`](../shared/).

**Before editing anything under this package, invoke the `native-android-mk` skill** — it encodes the MK.8 audit lessons (threading, schema units, resume-point, two-tap activation, semantics). Skip it and we re-ship the 29 bugs from commit `ae6a7cf`.

## Stack (quick reference)

- **Kotlin 2.x** + **Jetpack Compose** + **`androidx.tv.material`** on TV / **Material3** on phone
- **Media3 ExoPlayer** direct (no bridge). One instance shared between `MiniPlayer` and `PlayerActivity` by swapping the output Surface (`setVideoSurface` / `clearVideoSurface`) — not `PlayerView.switchTargetView()`
- **Coil 3** images, **Koin** DI, **Kermit** logging
- **Compose Navigation 3** with adaptive layouts
- **WorkManager** for background (EPG reminders, source sync)
- Min SDK 24, Target SDK 35, AGP 8.x

## Hard rules (native-specific)

1. **One `ExoPlayer`.** `PlaybackController` owns the single instance. Never `new ExoPlayer(...)` outside it.
2. **`PlaybackController` is main-thread-only.** Repo calls it owns (`history`, future `favorites`) dispatch to `Dispatchers.IO` inside — callers don't know.
3. **Never call a `packages/shared/` repo directly from a Compose lambda.** SQLDelight blocks. Wrap in `rememberCoroutineScope().launch(Dispatchers.IO) { ... }` or `LaunchedEffect { withContext(Dispatchers.IO) ... }`.
4. **Compose TV focus targets use `androidx.tv.material` clickables.** Material3 clickables don't integrate with leanback focus correctly.
5. **Every `AsyncImage` needs `contentDescription`** (or `= null` with a paired text label). TalkBack / TV reader is silent otherwise.
6. **Every non-Material3 interactive control needs `Modifier.semantics { contentDescription = ... }`.** Custom `Row`/`Box` + `.clickable` is silent by default.
7. **DB reads at composable entry points need try/catch.** A corrupted `content` or `watch_history` row crashes the whole screen otherwise. Log via Kermit, render empty state.
8. **Before every `controller.play(...)` call site, check `controller.currentId == target.id` first.** If already playing, go straight to fullscreen via `PlayerLauncher.launch(context)` — don't re-prepare the `MediaItem`.
9. **`rememberSaveable` for user-input state.** `remember` loses search queries, form fields, focused IDs, scroll offsets across rotation/process death.
10. **One MK sub-task per commit.** MK.8.3+8.4+8.5 together cost us 29 bugs. Smaller commits. Self-audit before committing.

## Canonical flows

**Launch the player from any screen:**
```kotlin
if (controller.currentId == item.id) {
    PlayerLauncher.launch(context)       // already playing → just open fullscreen
} else {
    controller.play(listOf(item), 0)     // different item → queue + prepare
    PlayerLauncher.launch(context)
}
```

**Episode playback (series detail):**
```kotlin
// Use the typed episode path — not controller.play(list, index).
val playable = episode.toPlayable(series) ?: return
controller.play(playable)                // Playable.Episode overload
PlayerLauncher.launch(context)
```

**Mutate state from a click:**
```kotlin
val scope = rememberCoroutineScope()
Button(onClick = {
    scope.launch(Dispatchers.IO) {
        favoritesRepo.toggle(item.id)
    }
})
```

## Build / install

```bash
cd packages/android
./gradlew :app:assembleDebug                    # debug APK
./gradlew :app:installDebug                     # build + install (Fire TV on 192.168.68.56:5555)
./gradlew :app:assembleRelease                  # signed per-ABI splits
./gradlew :shared:testDebugUnitTest             # shared module unit tests (JVM target)
./gradlew :shared:allTests                      # all KMP targets aggregate
```

`JAVA_HOME` must point at Android Studio's JBR: `C:\Program Files\Android\Android Studio\jbr`.

## Where things live

- `app/src/main/java/com/yancotv/android/ui/shell/` — adaptive screens (HomeScreen, BrowseShell, ContentDetailScreen)
- `app/src/main/java/com/yancotv/android/player/` — PlaybackController, PlayerActivity, PlayerLauncher
- `app/src/main/java/com/yancotv/android/ui/focus/` — TV focus primitives (PlacedFocusAnchor)
- `app/src/main/java/com/yancotv/android/di/` — Koin modules
- `../shared/src/commonMain/` — business logic (parsers, clients, types, repos)

Cross-ref: [AGENTS.md](../../AGENTS.md) for cross-tool rules, [PRODUCTION_PLAN_NATIVE.md](../../PRODUCTION_PLAN_NATIVE.md) for milestones.
