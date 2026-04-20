# YancoTV — Claude Code Project Guide

## Project Overview

YancoTV is a custom IPTV media application shipped across three sibling apps:

- **Desktop (Electron)** — Windows-first, mpv + SQLite, feature-complete (v0.2.0). TypeScript + `@yancotv/core`.
- **Android / Android TV (native Kotlin + KMP)** — *in active development as of 2026-04-20.* Kotlin 2.x + Jetpack Compose + `androidx.tv.material` for TV, Material3 for phone. Media3 ExoPlayer. Shared KMP business module for parsers, clients, classifier, EPG. See [PRODUCTION_PLAN_NATIVE.md](PRODUCTION_PLAN_NATIVE.md). Lives in `packages/android/` + `packages/shared/`.
- **iOS / iPadOS (SwiftUI + KMP)** — *post-Android-1.0.* Native SwiftUI consuming the same `packages/shared/` Kotlin framework. Scheduled in MK.iOS.* milestones.

**FROZEN:**
- **`packages/mobile/` (React Native)** — superseded 2026-04-20 after one week of Fire TV bridge issues. No new features, no bug fixes except P0. Kept runnable until native Android reaches parity, then archived. See [PRODUCTION_PLAN_ANDROID.md](PRODUCTION_PLAN_ANDROID.md) for the frozen RN plan.

**Shared business logic** lives in two parallel implementations: `packages/core/` (TypeScript, for desktop) and `packages/shared/` (Kotlin Multiplatform, for Android/iOS). Algorithms and tests mirror each other; neither is the "source." Double-porting was deemed cheaper than a cross-language toolchain.

**The mission:** beat TiviMate on Android TV, then ship iPhone + iPad with the same shared core.

## Monorepo Layout

```
YancoTV/                              # pnpm workspace root
├── CLAUDE.md                         # This file — monorepo guide
├── ARCHITECTURE.md                   # Process/data architecture for both apps
├── PRODUCTION_PLAN.md                # Desktop roadmap (Phases 1–5, mostly DONE)
├── PRODUCTION_PLAN_NATIVE.md         # ACTIVE — Native Android (Kotlin+KMP) + iOS (SwiftUI) plan
├── PRODUCTION_PLAN_ANDROID.md        # FROZEN — React Native roadmap (superseded 2026-04-20)
├── CHANGELOG.md                      # Desktop release notes
├── bugs.md                           # Active desktop bug register
├── SKILLS.md                         # Tech stack + domain knowledge reference
├── README.md
├── pnpm-workspace.yaml
├── package.json                      # Desktop app + workspace root
├── src/                              # Electron desktop app
├── tests/                            # Desktop unit + E2E tests
├── scripts/                          # Desktop build scripts
└── packages/
    ├── core/                         # @yancotv/core — TypeScript business logic (desktop)
    ├── shared/                       # Kotlin Multiplatform business logic (Android + iOS)  — NEW, MK.0
    ├── android/                      # Native Android app (Kotlin + Compose + Media3)       — NEW, MK.0
    ├── ios/                          # Native iOS app (SwiftUI + KMP framework)             — LATER, post-Android-1.0
    └── mobile/                       # FROZEN — old React Native app, kept runnable until native Android reaches parity
```

Desktop isn't under `packages/desktop/` yet — cosmetic, deferred. Desktop consumes `@yancotv/core` via pnpm `workspace:*`. Android/iOS consume `packages/shared/` via Gradle (not pnpm).

## Plans

| Plan | Status | Scope |
|---|---|---|
| [PRODUCTION_PLAN.md](PRODUCTION_PLAN.md) | Phase 1 complete; Phase 3/4/5 ahead | Desktop feature roadmap |
| [PRODUCTION_PLAN_NATIVE.md](PRODUCTION_PLAN_NATIVE.md) | **ACTIVE — MK.0 starting** | Native Android (Kotlin + KMP) + iOS (SwiftUI) plan |
| [PRODUCTION_PLAN_ANDROID.md](PRODUCTION_PLAN_ANDROID.md) | **FROZEN 2026-04-20** | React Native roadmap — superseded |

**Android/iOS work is driven by [PRODUCTION_PLAN_NATIVE.md](PRODUCTION_PLAN_NATIVE.md).** Every native commit maps to a `MK.*` milestone task. No ad-hoc feature work. **No work in `packages/mobile/` (RN)** except P0 bug fixes.

## The `@yancotv/core` Discipline

If it's platform-agnostic, it lives in `packages/core`. Both apps import from `@yancotv/core`.

**Already in core:**
- Types (`ContentItem`, `Source`, `Episode`, EPG types)
- Zod schemas
- M3U parser
- Xtream + Stalker API clients
- Content classifier (base), title-cleaner (partial)
- Catchup URL builder
- HTTP client interface (`HttpClient`)

**Scheduled to move into core (M1):**
- XMLTV parser (desktop-only today)
- Full title-cleaner + classifier parity
- Parental PIN hashing
- Zustand store factories (shape, not bindings)

**Will NOT go into core:**
- Anything that touches `better-sqlite3`, Electron APIs, `mpv`, `ffmpeg`, native mobile modules
- UI components (platforms render differently)

Rule: if you're tempted to duplicate logic between `src/main/services/` and `packages/mobile/src/`, stop and extract to core first.

## Desktop — Tech Stack

- **Runtime:** Electron 41+ (hardened)
- **Frontend:** React 18+ / TypeScript 5+ / Tailwind CSS 3
- **State:** Zustand 5
- **Database:** SQLite via better-sqlite3 (synchronous, WAL mode, FTS5)
- **Playback:** mpv via child process behind `IPlayer` abstraction (JSON-RPC over named pipes)
- **Media tools:** ffmpeg (recording, downloading, subtitle extraction)
- **Bundler:** Vite 6 (renderer), esbuild (preload), tsc (main)
- **Packaging:** electron-builder (NSIS + portable)
- **Testing:** Vitest (unit), Playwright (E2E)
- **Validation:** Zod
- **Routing:** React Router 7
- **Virtualization:** react-virtuoso

## Android (Native) — Tech Stack

See [PRODUCTION_PLAN_NATIVE.md § Stack](PRODUCTION_PLAN_NATIVE.md#stack) for the full rationale. Summary:

**Shared (`packages/shared/`, KMP):**
- **Language:** Kotlin 2.x
- **DB:** SQLDelight (KMP, FTS4)
- **HTTP:** Ktor Client
- **Concurrency:** Kotlinx Coroutines + Flow
- **Serialization:** Kotlinx Serialization
- **DI:** Koin (KMP-native; not Hilt — Hilt is Android-only)
- **Logging:** kermit

**Android (`packages/android/`):**
- **UI:** Jetpack Compose + `androidx.tv.material` (TV) / Material3 (phone)
- **Navigation:** Compose Navigation 3 with adaptive layouts
- **Playback:** Media3 ExoPlayer direct (no bridge). One `ExoPlayer` shared between mini-preview and fullscreen via `PlayerView.switchTargetView()`. FFmpeg ExoPlayer extension (NDK-built) for codec gap (MK.9)
- **Images:** Coil 3
- **Credentials:** Android Keystore / EncryptedSharedPreferences
- **Background:** WorkManager + NotificationManager (EPG reminders, source sync)
- **Crash reporting:** Firebase Crashlytics (replaces Sentry on Android; Sentry stays for desktop)
- **Cast:** MediaRouter (Cast SDK)
- **Build:** Gradle Kotlin DSL, AGP 8.x, min SDK 24, target SDK 35

## iOS (Native, post-Android-1.0) — Tech Stack

- **UI:** SwiftUI (native feel, not Compose Multiplatform)
- **Playback:** AVPlayer primary, VLCKit fallback for DTS/TrueHD
- **Shared logic:** `packages/shared/` Kotlin framework imported via Xcode
- **Cast:** Google Cast iOS SDK

## Mobile — React Native (FROZEN 2026-04-20)

`packages/mobile/` is frozen. No new features, no bug fixes except P0. Reference only. See [packages/mobile/CLAUDE.md](packages/mobile/CLAUDE.md) for its frozen state.

## Desktop Project Structure

```
src/
├── main/                        # Electron main process
│   ├── index.ts                 # App entry, window creation, DB init
│   ├── preload.ts               # contextBridge IPC bridge
│   ├── ipc/index.ts             # All IPC handler registration
│   ├── services/                # Backend business logic
│   │   ├── db.ts                # SQLite init, migrations, FTS5
│   │   ├── source-manager.ts
│   │   ├── source-sync.ts
│   │   ├── stalker-client.ts
│   │   ├── m3u-parser.ts
│   │   ├── xtream-client.ts
│   │   ├── content-classifier.ts
│   │   ├── content-store.ts
│   │   ├── title-cleaner.ts
│   │   ├── xmltv-parser.ts
│   │   ├── epg-service.ts
│   │   ├── catchup-service.ts
│   │   ├── timeshift-service.ts
│   │   ├── parental-service.ts
│   │   ├── settings-service.ts
│   │   ├── credential-store.ts  # Electron safeStorage
│   │   ├── favorites-store.ts
│   │   ├── history-store.ts
│   │   ├── recording-service.ts
│   │   ├── download-manager.ts
│   │   ├── tmdb-client.ts
│   │   ├── opensubtitles-client.ts
│   │   └── migrations/          # 001 → 011 SQL files
│   └── player/
│       ├── player.interface.ts
│       ├── mpv-player.ts
│       ├── mpv-ipc.ts
│       └── mpv-path.ts
├── renderer/                    # React frontend (sandboxed)
│   ├── main.tsx
│   ├── App.tsx                  # Router setup
│   ├── pages/                   # HomePage, LiveTvPage, MoviesPage, SeriesPage,
│   │                            # FavoritesPage, SearchPage, GuidePage,
│   │                            # SettingsPage, RecordingsPage, DownloadsPage,
│   │                            # ContentDetailPage
│   ├── components/
│   │   ├── player/              # VideoPlayer, PlayerContainer, SettingsPanel
│   │   ├── settings/            # 13 tab components
│   │   ├── Layout.tsx
│   │   ├── Sidebar.tsx
│   │   ├── ContentGrid.tsx
│   │   ├── HexCard.tsx
│   │   ├── ChannelHexRow.tsx
│   │   ├── CategorySidebar.tsx
│   │   ├── SortDropdown.tsx
│   │   ├── PinModal.tsx
│   │   ├── DetailHero.tsx
│   │   ├── EpisodesTab.tsx
│   │   ├── InfoTab.tsx
│   │   ├── RelatedTab.tsx
│   │   ├── SourceList.tsx
│   │   └── Toaster.tsx
│   ├── hooks/
│   ├── stores/                  # player, settings, favorites, parental, recent-channels, toast
│   ├── utils/
│   └── styles/global.css
└── shared/                      # Shared between main + renderer
    ├── constants.ts
    ├── ipc-channels.ts          # Single source of truth for IPC names
    ├── types/
    └── schemas/
```

## Native Android Project Structure (`packages/android/` + `packages/shared/`)

```
packages/shared/                         # KMP module — Kotlin, consumed by Android + iOS
├── build.gradle.kts
└── src/
    ├── commonMain/kotlin/com/yancotv/shared/
    │   ├── types/                       # ContentItem, Source, Episode, EPG
    │   ├── parsers/                     # M3uParser, XmltvParser
    │   ├── xtream/                      # XtreamClient (Ktor-backed)
    │   ├── stalker/                     # StalkerClient
    │   ├── content/                     # Classifier, TitleCleaner
    │   ├── catchup/                     # UrlBuilder
    │   ├── db/                          # SQLDelight schema + queries
    │   ├── http/                        # HttpClient interface
    │   ├── viewmodel/                   # Shared ViewModels exposing StateFlow
    │   └── logger/                      # kermit wrapper
    ├── commonTest/                      # KMP unit tests
    ├── androidMain/                     # Android actuals (AndroidSqliteDriver, OkHttp engine)
    └── iosMain/                         # iOS actuals (NativeSqliteDriver, Darwin engine)

packages/android/                        # Android Studio project
├── app/
│   ├── build.gradle.kts
│   └── src/main/java/com/yancotv/android/
│       ├── MainActivity.kt              # Compose entry
│       ├── ui/                          # Shared Compose screens (adaptive)
│       ├── tv/                          # TV-specific (androidx.tv.material)
│       ├── phone/                       # Phone-specific (Material3)
│       ├── player/
│       │   ├── PlayerActivity.kt        # Ported from RN app's native module
│       │   └── PlaybackService.kt       # Media3 MediaSessionService (one ExoPlayer)
│       └── di/                          # Koin modules
├── settings.gradle.kts
└── gradle/libs.versions.toml            # Version catalog

packages/ios/                            # Xcode project (lands post-Android-1.0)
└── YancoTV/
    ├── YancoTV.xcodeproj
    └── Sources/
        ├── App.swift
        ├── Shell/                       # SwiftUI adaptive screens
        └── Player/
```

## Core Project Structures

**`packages/core/`** (TypeScript — desktop only):
```
packages/core/src/
├── index.ts                     # Barrel exports
├── types/                       # ContentItem, Source, Episode, EPG, M3uEntry
├── schemas/                     # Zod validators
├── parsers/                     # m3u-parser, xmltv-parser
├── xtream/, stalker/            # Clients
├── content/                     # classifier, title-cleaner
├── catchup/                     # URL builder
├── http/                        # HttpClient interface + errors
└── logger.ts
```

**`packages/shared/`** (Kotlin — Android + iOS): same shape in Kotlin, see above. Both implementations mirror each other; neither is the source.

## Frozen: React Native Project Structure (`packages/mobile/`)

Kept runnable for reference. See [packages/mobile/CLAUDE.md](packages/mobile/CLAUDE.md) for details. Do not modify except for P0 bugs.

## Commands

### Root / Desktop

```bash
pnpm install          # Install all workspace deps (pnpm enforced via preinstall)
pnpm dev              # Desktop: Vite HMR + tsc + Electron (port 5173)
pnpm build            # Desktop production build
pnpm package          # Desktop Windows installer (NSIS + portable)
pnpm test             # Desktop unit tests (rebuilds better-sqlite3 ABI)
pnpm test:e2e         # Desktop Playwright E2E
pnpm lint             # Lint desktop src/
```

### Android (native — active)

Open `packages/android/` in Android Studio, or from CLI:
```bash
cd packages/android
./gradlew :app:assembleDebug                  # Debug APK
./gradlew :app:installDebug                   # Build + install on connected device
./gradlew :app:assembleRelease                # Signed release APK (per-ABI splits)
./gradlew :shared:build                       # Build KMP shared module
./gradlew :shared:commonTest :shared:androidUnitTest   # Run shared tests
```
Release APK output: `packages/android/app/build/outputs/apk/release/app-<abi>-release.apk`

### iOS (native — post-Android-1.0)

Open `packages/ios/YancoTV.xcodeproj` in Xcode 16+. Shared Kotlin framework builds via `./gradlew :shared:linkReleaseFrameworkIosArm64` (etc.) into `packages/shared/build/xcode-frameworks/`.

### Mobile (RN — FROZEN)

```bash
cd packages/mobile
pnpm android          # Still builds; use only to reproduce a P0 RN bug
```
Do not run other commands — the RN app is in maintenance-only mode.

### Core (TypeScript, desktop)

```bash
cd packages/core
pnpm typecheck        # tsc --noEmit
```

## Current Status

### Desktop (Phase 1 feature-complete, stabilization in flight)

- Sprints 1–11: DONE (foundation, sources, Xtream, browsing, playback, search/favorites/history, EPG)
- Sprint 11B: DONE (cinematic content detail pages)
- Sprint 12: DONE (ffmpeg recording + scheduled)
- Sprint 13: DONE (downloads + asset bundling)
- Sprint 14: DONE (TMDb enrichment)
- Sprint 15: DONE (OpenSubtitles)
- Sprint 16: DROPPED (multi-view / PIP)
- Sprint 17: DONE (settings polish — 8 tabs)
- Sprint 18: DONE (tray, auto-update check, backup, crash handler, icon)
- Sprint 19: DONE (search UX, channel zapping, reminders, toasts)
- Sprint 20: DONE (buffer/timeout/reconnect, UA, proxy, shortcuts, gamepad)
- Sprint 21: 21.1–21.5 + 21.7–21.10 DONE; 21.6 human QA against real sources pending

See [PRODUCTION_PLAN.md](PRODUCTION_PLAN.md) for the full desktop roadmap and [bugs.md](bugs.md) for the active desktop bug register.

### Mobile — NATIVE REWRITE IN FLIGHT (2026-04-20)

**React Native app frozen. Rewriting in Kotlin + KMP + Compose.** After a week of Fire TV black-screen-with-audio was fixed only by bypassing the RN bridge with a native `PlayerActivity` (M4R.Player, commit `09150e9`, 2026-04-20), the decision was made to commit to native for Android and to add iOS/iPadOS as a sibling via KMP. The RN plan (M4R → M10R) is frozen. The new native plan uses `MK.0` → `MK.12` milestones.

- **MK.0** Scaffold (0.5 wk) — `packages/shared/` KMP module + `packages/android/` Android Studio project
- **MK.1** Shared core port (1.5 wk) — TS `@yancotv/core` → Kotlin, parser + client test parity
- **MK.2** Persistence (0.5 wk) — SQLDelight schema port + FTS4 + migrations
- **MK.3** Sources (0.5 wk) — Android Keystore + Xtream/Stalker sync
- **MK.4** Shell UI (1 wk) — Compose adaptive layout (TV leanback + phone Material3)
- **MK.5** Channel list + Coil image cache (0.5 wk)
- **MK.6** Playback — shared ExoPlayer, mini ↔ fullscreen via `switchTargetView()` (0.75 wk)
- **MK.7** EPG — XMLTV + Guide grid + reminders (1 wk)
- **MK.8** Features — Catch-up, Timeshift, Favorites, History, Search, Settings, Parental (1.5 wk)
- **MK.9** Codec gap — FFmpeg ExoPlayer extension NDK build (1 wk)
- **MK.10** TV launcher integration + voice search (1 wk)
- **MK.11** Phone PIP + gesture seek + Chromecast (1 wk)
- **MK.12** Distribution + QA — Play Store + Fire TV Appstore + GitHub Releases (1 wk)

**Target:** ~12 weeks to Android 1.0. iOS (`MK.iOS.*`) lands post-Android-1.0, ~6–8 additional weeks.

See [PRODUCTION_PLAN_NATIVE.md](PRODUCTION_PLAN_NATIVE.md) for full task lists, stack rationale, architecture rules, and decision log.

## Architecture Rules

### Shared Core (`@yancotv/core`) — NON-NEGOTIABLE

- **No UI code.** No React, no React Native, no Electron, no DOM.
- **No platform I/O.** No `better-sqlite3`, no `fs`, no native mobile modules. Inject what you need via interfaces (`HttpClient`, `Logger`).
- **Pure TypeScript.** Only dependency: `zod` (and peer interfaces).
- **Deterministic, testable.** Every new module gets unit tests that run in both the desktop and mobile test suites.
- **Build `@yancotv/core` to `dist/` before running or bundling the desktop main.** `@yancotv/core` is ESM (`"type": "module"`) and Electron 41 bundles Node 24, whose loader rejects directory imports and will not map `.js` specifiers to `.ts` files. Internal re-exports use explicit `.js` extensions (`export * from './types/index.js'`) that resolve against emitted artifacts in `packages/core/dist/`. Root `package.json` runs `pnpm build:core` ahead of `dev:electron`, `build:main`, and `build`, and `packages/core/package.json` routes `main`/`types`/`exports.default` at `./dist/*.js` while keeping `exports.source` + `exports.react-native` on `./src/*.ts` so Metro still reads TS sources directly. Skipping the build crashes Electron boot with `ERR_MODULE_NOT_FOUND` (MB-18, 2026-04-20).

### Desktop — Electron Security (NON-NEGOTIABLE)

- `contextIsolation: true` — always
- `nodeIntegration: false` — always
- `sandbox: true` — for renderer
- Never load remote/untrusted URLs in BrowserWindow
- All main↔renderer communication goes through typed IPC via preload
- Preload exposes ONLY specific API methods, never raw `ipcRenderer`
- Define all IPC channels in `src/shared/ipc-channels.ts` — single source of truth
- Validate all data crossing IPC (Zod schemas)

### Desktop — Player Abstraction

- All playback goes through `IPlayer` in `src/main/player/player.interface.ts`
- Never call mpv directly from renderer or services
- mpv communication uses JSON-RPC over named pipes (`mpv-ipc.ts`)

### Desktop — Database

- SQLite via better-sqlite3 in main process only (WAL mode)
- Renderer accesses data exclusively through IPC
- Migrations managed via versioned SQL files in `src/main/services/migrations/`
- FTS5 virtual table with trigger-based sync
- Never store credentials in plaintext — use Electron safeStorage (`credential-store.ts`)

### Native Android / iOS — Architecture Rules

See [PRODUCTION_PLAN_NATIVE.md § Architecture rules](PRODUCTION_PLAN_NATIVE.md#architecture-rules-native). Key rules:

- **Shared Kotlin is pure business logic** — no `android.*` in `commonMain/`. Platform-specifics via `expect`/`actual` in `androidMain/` / `iosMain/`.
- **SQLDelight is the only persistence surface** for content/EPG/favorites/history.
- **One `ExoPlayer` instance** shared between mini-preview and fullscreen via `PlayerView.switchTargetView()`. Never instantiate a second player.
- **Compose for TV uses `androidx.tv.material`** — don't reuse Material3 clickables as TV focus targets.
- **Credentials via Android Keystore / iOS Keychain** — never plaintext.
- **ViewModels live in `shared/`** exposing `StateFlow<T>`.
- **Ktor + Kotlinx Serialization only** — no Retrofit/Moshi/Gson (iOS can't compile them).
- **Desktop unaffected** — `packages/core/` TypeScript keeps shipping Electron. Don't cross-compile.

### Code Conventions (Both Apps)

- TypeScript strict — no `any` unless unavoidable
- Functional React components with hooks only — no class components
- Named exports only (exception: route-level page components if framework requires default)
- Error handling: `Result<T>` in service layer, try/catch at IPC or module boundaries
- File naming: kebab-case for files, PascalCase for components, camelCase for functions/vars
- One component per file
- No emoji in committed code or UI (UI needs SVG icons — text-rendering is unreliable cross-device)

### What NOT To Do

- Do not duplicate business logic between desktop and mobile — extract to core
- Do not add features not in the relevant production plan without explicit approval
- Do not couple desktop code to mpv directly — use `IPlayer`
- Do not couple mobile code to specific native modules outside `services/` — wrap them
- Do not store IPTV credentials in plaintext
- Do not use `webSecurity: false`, disable `contextIsolation`, or loosen Electron sandbox
- Do not introduce premature abstractions — three similar call sites beats a generic helper
- Do not leave uncommitted work drifting for more than a day (see M1.1 in the mobile plan for the current penalty this has cost us)

## Where to Start

- **Adding a feature to the desktop app?** Read [PRODUCTION_PLAN.md](PRODUCTION_PLAN.md), find the sprint it belongs to (or propose a new one), then [ARCHITECTURE.md](ARCHITECTURE.md) for service placement.
- **Adding a feature to Android (native)?** Read [PRODUCTION_PLAN_NATIVE.md](PRODUCTION_PLAN_NATIVE.md) — find the `MK.*` milestone, follow the tasks. If it's not in the plan, add it to the plan first. Code lives in `packages/android/` or `packages/shared/`.
- **Adding a feature to iOS?** Same plan, `MK.iOS.*` milestones. Code in `packages/ios/` + `packages/shared/`. Not started until Android 1.0 ships.
- **Cross-cutting shared logic (parser, client, types)?** Two implementations: `packages/core/` (TS) for desktop + `packages/shared/` (Kotlin) for Android/iOS. Update both in the same PR with mirrored tests.
- **RN app?** [PRODUCTION_PLAN_ANDROID.md](PRODUCTION_PLAN_ANDROID.md) is frozen; see [packages/mobile/CLAUDE.md](packages/mobile/CLAUDE.md). Don't add features or non-P0 fixes.
