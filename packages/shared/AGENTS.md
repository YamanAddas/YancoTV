# packages/shared - Codex Guide

Kotlin Multiplatform business logic for native Android today and iOS later. Use this with the root `AGENTS.md`.

Before editing, reviewing, testing, or debugging this package, use the `native-android-mk` skill. If the work touches IPTV protocols or classification, also use the `iptv-domain` skill.

## Stack

- Kotlin 2.x, Coroutines, Kotlinx Serialization, kotlinx-datetime.
- Ktor client with platform engines.
- SQLDelight for all native persistence.
- Koin and Kermit.
- Platform-specific code lives in `androidMain` and `iosMain` through `expect`/`actual`.

## Non-Negotiables

- `commonMain` is pure Kotlin. No `android.*`, `java.nio`, `NSString`, or direct platform I/O.
- Use Ktor plus Kotlinx Serialization. Do not add Retrofit, Moshi, or Gson.
- SQLDelight is the only persistence surface. No Room or ObjectBox.
- Wall-clock timestamps are milliseconds. Use `Clock.System.now().toEpochMilliseconds()`.
- Only `watch_history.position_seconds` and `duration_seconds` are seconds because they are media offsets.
- New timestamp schema columns need `-- ms since epoch`.
- `positionFor(contentId)` returns a content-level row or `null`, never an episode row. Also returns `null` for rows at ≥95% of duration so the player restarts finished titles instead of seeking to credits. Same rule for `positionForEpisode(episodeId)`.
- Platform behavior is injected through interfaces or `expect`/`actual`.
- ViewModels expose `StateFlow<T>`.
- Parser, classifier, title-cleaner, and catch-up changes need mirrored tests in `packages/core` when the TypeScript port is affected.

## Verification

Run from `packages/android` or the repo root as appropriate:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :shared:commonTest :shared:androidUnitTest
.\gradlew.bat :shared:build
```

Do not push without an explicit user request.
