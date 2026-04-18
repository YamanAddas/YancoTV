# @yancotv/mobile

Android TV + Google TV + Fire TV + Android phone/tablet client for YancoTV, built on React Native TV. Single APK — TV and phone share one codebase and adapt at the navigator/component level.

**Mission:** feature parity with the [Electron desktop app](../../README.md), then surpass it on mobile-native capabilities (D-pad focus, picture-in-picture, Chromecast, voice search, home-launcher integration).

## Documentation Map

- **[PRODUCTION_PLAN_ANDROID.md](../../PRODUCTION_PLAN_ANDROID.md)** — Full roadmap, milestones M1→M9, parity matrix. The source of truth for what to build.
- **[CLAUDE.md](CLAUDE.md)** — Mobile project guide for agents + contributors (architecture rules, tech choices, common tasks).
- **[Root CLAUDE.md](../../CLAUDE.md)** — Monorepo-wide guide.
- **[ARCHITECTURE.md](../../ARCHITECTURE.md)** — System architecture for both desktop and mobile.

## Current State

- Phase 0 (shared core extraction): Xtream + Stalker + M3U + classifier + types already in `@yancotv/core`
- Phase 1 (scaffold + debug APK + release APK + Sentry): DONE
- Phase 2 rewrite (theme, layout, hex cards, full player, all screens): **done but sitting uncommitted** — M1.1 lands it
- M1 → M9 roadmap covers persistence, navigation, browse parity, search/favorites/history, EPG, settings/parental, TV polish, and distribution

Current working APK supports: add M3U / Xtream / Stalker sources, browse channels, play video. Missing everything else (search, favorites, EPG, detail pages, resume, settings UI) until the milestones land.

## Prerequisites

- **Node.js 20+**, **pnpm 9+**
- **Java JDK 17** (Android build)
- **Android Studio** + SDK Platform 34+ installed
- `ANDROID_HOME` env var set, `adb` on PATH
- Device or emulator:
  - Phone: any Android 8+ emulator
  - TV: Android TV emulator, Fire TV Stick (4K / Cube / non-4K), NVIDIA Shield, or Chromecast with Google TV

## Install + Build

```bash
# From workspace root
pnpm install

# From packages/mobile
pnpm start            # Metro bundler on :8081

# In another terminal
pnpm android          # Build + install debug APK on connected device
```

Release APK:

```bash
cd android && ./gradlew assembleRelease
# Output: android/app/build/outputs/apk/release/app-release.apk
```

Sideload to a TV box over Wi-Fi:

```bash
adb connect <tv-ip-address>:5555
adb install -r app-release.apk
```

## Dev Commands

| Command | What it does |
|---|---|
| `pnpm start` | Start Metro bundler on port 8081 |
| `pnpm start --reset-cache` | Start Metro with cleared cache (fixes weird resolution issues) |
| `pnpm android` | Build debug APK + install on connected device |
| `pnpm typecheck` | TypeScript-only check |
| `pnpm lint` | ESLint over `src/` |
| `pnpm test` | Jest unit tests |

## Architecture Summary

```
src/
├── navigation/      React Navigation 7 stacks (set up in M3)
├── screens/         Top-level route components
├── components/
│   ├── cards/       HexCard, ContentCard
│   ├── layout/      AppLayout, Sidebar, PageHeader
│   ├── tv/          TV-optimized (D-pad focus)
│   └── phone/       Phone-optimized touch UX (M8)
├── focus/           Focus primitive + spatial navigation
├── player/          react-native-video wrapper
├── db/              op-sqlite + migrations (M2)
├── services/        Platform glue: Keystore, notifications, cast
├── stores/          Zustand stores (mirror desktop shapes)
├── http/            fetch-http-client (XMLHttpRequest-based)
├── storage/         AsyncStorage wrappers (small keys only post-M2)
└── styles/theme.ts  Ported from desktop palette
```

All platform-agnostic business logic lives in [`@yancotv/core`](../core) — parsers, API clients, classifiers, types, schemas. Import from `@yancotv/core`, not from relative paths.

## Milestone Map

| Milestone | Scope | Status |
|---|---|---|
| M1 | Commit Phase 2 + finish core extraction | IN PROGRESS |
| M2 | op-sqlite + migrations | PLANNED |
| M3 | React Navigation + dual layout (TV drawer / phone tabs) | PLANNED |
| M4 | Browse parity + Content Detail page + playback resume | PLANNED |
| M5 | Search + Favorites + History | PLANNED |
| M6 | EPG + Catch-up + Timeshift | PLANNED |
| M7 | Settings (8 tabs) + Parental + Polish | PLANNED |
| M8 | TV UX polish + Phone-native features (PIP, Cast, gestures) | PLANNED |
| M9 | Distribution (Play Store / Fire TV / sideload) + QA | PLANNED |

See [PRODUCTION_PLAN_ANDROID.md](../../PRODUCTION_PLAN_ANDROID.md) for the full task breakdown per milestone.

## License

Private / Proprietary
