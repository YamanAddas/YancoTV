# packages/shared — Claude Code Guide

Kotlin Multiplatform business logic. Consumed by [`../android/`](../android/) today, by `packages/ios/` post-Android-1.0. Mirror of `packages/core/` (TypeScript, desktop) — algorithms match, tests match, neither is the source.

**Before editing under this package, invoke the `native-android-mk` skill** for schema/threading rules. For IPTV protocol questions, invoke `iptv-domain`.

## Stack

- **Kotlin 2.x** + **Kotlinx Coroutines** + **Kotlinx Serialization** + **kotlinx-datetime**
- **Ktor Client** (OkHttp engine on Android, Darwin engine on iOS)
- **SQLDelight** (generated typed queries, FTS4)
- **Koin** DI, **Kermit** logging
- Platform-specifics via `expect`/`actual` in `androidMain/` and `iosMain/`

## Hard rules

1. **`commonMain/` is pure Kotlin.** No `android.*`, no `java.nio`, no `NSString`. Everything platform goes through `expect`/`actual` in `androidMain/` / `iosMain/`.
2. **Ktor + Kotlinx Serialization only.** Do NOT add Retrofit, Moshi, Gson — they don't compile for iOS.
3. **SQLDelight is the only persistence surface.** No Room, no ObjectBox. All queries live in `commonMain/sqldelight/`.
4. **All DB timestamps are milliseconds** (`Clock.System.now().toEpochMilliseconds()`). Exception: `watch_history.position_seconds` / `duration_seconds` — those model media offsets, not wall-clock. Document the unit on every new timestamp column: `-- ms since epoch`.
5. **`positionFor(contentId)` returns content-level OR null — never an episode row.** Series containers must not resume to an arbitrary episode's offset.
6. **Inject platform I/O via interfaces**, not direct imports. `HttpClient`, `Logger`, `Clock` — never hard-wire Android's OkHttp or Java `System.currentTimeMillis`.
7. **ViewModels expose `StateFlow<T>`.** Consumers (Compose, SwiftUI) subscribe. No LiveData (Android-only).
8. **Mirror test changes.** Updating a parser or classifier? Update the test here AND the TypeScript equivalent in `packages/core/` in the same PR. The two implementations are both "canonical" and must not drift.

## Layout

```
src/
├── commonMain/kotlin/com/yancotv/shared/
│   ├── types/              # ContentItem, Source, Episode, EPG, Playable
│   ├── parsers/            # M3uParser, XmltvParser
│   ├── xtream/             # XtreamClient
│   ├── stalker/            # StalkerClient
│   ├── content/            # Classifier, TitleCleaner, ContentDetailService
│   ├── catchup/            # UrlBuilder
│   ├── db/                 # Repos wrapping SQLDelight queries
│   ├── http/               # HttpClient interface
│   ├── viewmodel/          # Shared StateFlow-exposing VMs
│   └── playback/           # Playable sealed type + toPlayable() ports
├── commonMain/sqldelight/  # Schema + queries
├── commonTest/             # KMP unit tests
├── androidMain/            # AndroidSqliteDriver, OkHttp engine
├── androidUnitTest/        # JVM-flavored tests
└── iosMain/                # NativeSqliteDriver, Darwin engine (lands with MK.iOS)
```

## Build / test

```bash
./gradlew :shared:build
./gradlew :shared:commonTest            # KMP tests
./gradlew :shared:androidUnitTest       # Android-flavored tests (e.g. JVM XMLTV parser)
```

From repo root or `packages/android/`. `JAVA_HOME` must point at Android Studio's JBR.

Cross-ref: [AGENTS.md](../../AGENTS.md), [PRODUCTION_PLAN_NATIVE.md](../../PRODUCTION_PLAN_NATIVE.md), [packages/core/](../core/) (TypeScript mirror).
