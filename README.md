# YancoTV

Custom IPTV media player. Three sibling apps on a shared business core. Built to beat TiviMate on Android TV and ship the same experience to Windows, iPhone, and iPad.

## What is YancoTV?

A premium IPTV player that organizes content from M3U playlists, Xtream Codes, and Stalker Portal sources into a clean, browsable interface — Live TV, Movies, and Series in proper sections instead of one flat dump. Full EPG with catch-up and timeshift. Favorites and history with resume. Parental controls. Keyboard, gamepad, and D-pad navigation.

## Apps

- **Desktop (Windows)** — Electron + React + TypeScript + mpv. Feature-complete (v0.2.0). See [PRODUCTION_PLAN.md](PRODUCTION_PLAN.md).
- **Android / Android TV / Fire TV / Google TV** — Native Kotlin + Jetpack Compose + Media3. Active development as of 2026-04-20. See [PRODUCTION_PLAN_NATIVE.md](PRODUCTION_PLAN_NATIVE.md).
- **iOS / iPadOS** — SwiftUI + shared Kotlin framework. Scheduled post-Android-1.0.

The Android and iOS apps share business logic via a Kotlin Multiplatform module (`packages/shared/`). The desktop app uses a parallel TypeScript implementation (`packages/core/`) — two mirrored ports, tests on both sides, neither is the source.

**Frozen:** the earlier React Native app in `packages/mobile/`. Superseded 2026-04-20 after Fire TV bridge issues; kept runnable for reference. No new work except P0 fixes. See [docs/adr/0001-native-pivot.md](docs/adr/0001-native-pivot.md) for why.

## Monorepo layout

```
YancoTV/                         # pnpm workspace root
├── src/                         # Electron desktop app
├── packages/
│   ├── core/                    # @yancotv/core — TypeScript business logic (desktop)
│   ├── shared/                  # Kotlin Multiplatform business logic (Android + iOS)
│   ├── android/                 # Native Android app
│   ├── ios/                     # Native iOS app (lands post-Android-1.0)
│   └── mobile/                  # FROZEN — React Native, kept for reference
├── AGENTS.md / CLAUDE.md        # AI-agent guides (shared rules + Claude-specific)
├── ARCHITECTURE.md              # System architecture
├── PRODUCTION_PLAN.md           # Desktop roadmap
├── PRODUCTION_PLAN_NATIVE.md    # ACTIVE — Native Android + iOS roadmap
├── CHANGELOG.md                 # Desktop releases
├── bugs.md                      # Desktop bug register
└── docs/
    ├── adr/                     # Architecture decision records
    └── incidents/               # Post-mortems
```

## Development

Requires pnpm (enforced via preinstall), Node 22+, and (for native Android) JDK 17+ via Android Studio's bundled JBR.

### Desktop

```bash
pnpm install
pnpm dev             # Vite HMR + tsc + Electron on :5173
pnpm build
pnpm package         # NSIS installer + portable .exe
pnpm test            # Vitest unit (rebuilds better-sqlite3 ABI)
pnpm test:e2e        # Playwright E2E
```

### Android (native)

Open `packages/android/` in Android Studio, or from CLI:

```bash
cd packages/android
./gradlew :app:installDebug       # build + install on connected device
./gradlew :app:assembleRelease    # signed per-ABI APKs
./gradlew :shared:commonTest :shared:androidUnitTest   # KMP tests
```

Release output: `packages/android/app/build/outputs/apk/release/app-<abi>-release.apk`.

### iOS

Scheduled post-Android-1.0. Will open `packages/ios/YancoTV.xcodeproj` in Xcode 16+; shared Kotlin framework built via `./gradlew :shared:linkReleaseFrameworkIosArm64`.

## Current status

- **Desktop:** Phase 1 feature-complete (Sprints 1–20 DONE, Sprint 21 stabilization mostly done; Sprint 21.6 human QA against real sources pending before release sign-off).
- **Android:** MK.0 through MK.8 landed — scaffold, shared core port, SQLDelight schema, sources, shell UI (hero-centric browse), channel list, shared ExoPlayer with mini-to-fullscreen handoff, XMLTV EPG, Catch-up / Timeshift / Favorites / History / Search / Settings / Parental. Target: ~12 weeks from 2026-04-20 to Android 1.0. See [PRODUCTION_PLAN_NATIVE.md](PRODUCTION_PLAN_NATIVE.md) for the full milestone breakdown.
- **iOS:** not started. Begins post-Android-1.0.

## License

Private / Proprietary
