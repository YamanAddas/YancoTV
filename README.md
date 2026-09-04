# YancoTV

Custom IPTV media player. Three sibling apps on a shared business core. Built to beat TiviMate on Android TV and ship the same experience to Windows, iPhone, and iPad.

## What is YancoTV?

A premium IPTV player that organizes content from M3U playlists, Xtream Codes, and Stalker Portal sources into a clean, browsable interface — Live TV, Movies, and Series in proper sections instead of one flat dump. Full EPG with catch-up and timeshift. Favorites and history with resume. Parental controls. Keyboard, gamepad, and D-pad navigation.

## Apps

- **Desktop (Windows)** — Electron + React + TypeScript + mpv. Feature-complete (**v0.3.8**, last release 2026-05-27). See [PRODUCTION_PLAN.md](PRODUCTION_PLAN.md).
- **Android / Android TV / Fire TV / Google TV** — Native Kotlin + Jetpack Compose + Media3. Active development as of 2026-04-20. See [PRODUCTION_PLAN_NATIVE.md](PRODUCTION_PLAN_NATIVE.md).
- **iOS / iPadOS** — SwiftUI + shared Kotlin framework, in a **separate repo** (`YamanAddas/YancoTV-iOS`) that forks this monorepo and shares `packages/shared/`.

The Android and iOS apps share business logic via a Kotlin Multiplatform module (`packages/shared/`). The desktop app uses a separate TypeScript implementation (`packages/core/`). The two cores are **independent and are not required to match** — each carries what its own apps need.

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

Developed in the separate `YamanAddas/YancoTV-iOS` repository, not here — this tree has no `packages/ios/`. That repo forks this monorepo, so it also carries `packages/shared/`, and improvements made there are merged back (MK.36).

## Current status

*Reviewed 2026-09-04. The previous text here said Android was at "MK.0 through MK.8" and iOS was
"not started"; both had been stale for months.*

- **Desktop:** feature-complete at **v0.3.8** (2026-05-27). Sprints 1–20 done, Sprint 21
  stabilization mostly done; Sprint 21.6 human QA against real sources still pending before release
  sign-off. Six services the architecture doc listed as "planned" have all shipped.
- **Android:** **MK.37** in progress; MK.0–MK.36 landed. Shipping as **v1.6.7** (versionCode 31) on
  Fire TV and Chromecast with Google TV. Beyond the MK.8 list this now includes recordings, backup
  and restore, full i18n across four locales with RTL, cast, TMDb metadata, OpenSubtitles, an
  in-app updater, and a portrait phone shell. See
  [PRODUCTION_PLAN_NATIVE.md](PRODUCTION_PLAN_NATIVE.md).
- **iOS:** under active development in the separate `YamanAddas/YancoTV-iOS` repository, ahead of
  this tree on shared-core work. MK.36 merged that shared core back into this repo.

## License

Private / Proprietary
