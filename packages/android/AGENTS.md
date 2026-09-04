# packages/android - Codex Guide

Native Android, Android TV, Fire TV, and Google TV app. Use this with the root `AGENTS.md`.

Before editing, reviewing, testing, or debugging this package, use the `native-android-mk` skill.

## Stack

- Kotlin 2.x, Jetpack Compose, Media3 ExoPlayer, Koin, Kermit, Coil 3, WorkManager.
- TV focus and interactive surfaces use `androidx.tv.material`.
- Phone surfaces may use Material3 where appropriate.
- Shared business logic comes from `../shared`.

## Non-Negotiables

- One `ExoPlayer`, owned by `PlaybackController`. Do not instantiate another.
- `PlaybackController` is main-thread-only; internal repository writes dispatch to IO inside the controller.
- Before `controller.play(...)`, check `controller.currentId == target.id`; if already playing, only launch fullscreen.
- Do not call shared repositories directly from Compose lambdas on the main thread.
- Use `PlacedFocusAnchor` / `Modifier.placedFocus(anchor)` for focus-on-open flows.
- Use `androidx.tv.material` for TV focus targets; Material3 clickables are not TV focus targets.
- Every visible `AsyncImage` has a content description or an intentional paired-label null.
- Custom clickable rows/boxes need semantics content descriptions.
- DB reads at composable entry points need try/catch, Kermit logging, and a safe empty/error state.
- User-input state uses `rememberSaveable`.

## Canonical Player Flow

```kotlin
if (controller.currentId == item.id) {
    PlayerLauncher.launch(context)
} else {
    controller.play(listOf(item), 0)
    PlayerLauncher.launch(context)
}
```

Episode playback uses the typed episode overload:

```kotlin
val playable = episode.toPlayable(series) ?: return
controller.play(playable)
PlayerLauncher.launch(context)
```

## Verification

Run from `packages/android` with `JAVA_HOME` set to Android Studio JBR:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
```

Fire TV target: `192.168.68.66:5555` (DHCP — confirm with `adb devices -l`, `model:AFTDCT31`).

Do not push without an explicit user request.
