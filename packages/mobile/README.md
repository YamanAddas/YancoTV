# @yancotv/mobile

Android TV + Google TV + Fire TV + Android phone/tablet client for YancoTV, built on React Native TV. Single APK — TV and phone share one codebase and adapt at the navigator/component level.

**Mission:** feature parity with the [Electron desktop app](../../README.md), then surpass it on mobile-native capabilities (D-pad focus, picture-in-picture, Chromecast, voice search, home-launcher integration).

## Documentation Map

- **[PRODUCTION_PLAN_ANDROID.md](../../PRODUCTION_PLAN_ANDROID.md)** — Full roadmap, milestones M1→M9, parity matrix. The source of truth for what to build.
- **[CLAUDE.md](CLAUDE.md)** — Mobile project guide for agents + contributors (architecture rules, tech choices, common tasks).
- **[Root CLAUDE.md](../../CLAUDE.md)** — Monorepo-wide guide.
- **[ARCHITECTURE.md](../../ARCHITECTURE.md)** — System architecture for both desktop and mobile.

## Current State

**As of commit `f4a657c` (2026-04-19):** M4R reboot in flight. Phases 0–M3 landed on master (core extraction, op-sqlite + migrations, React Navigation 7). M4R.0 perf checkpoint + M4R.1 delete + M4R.2 navigator collapse + M4R.4/M4R.5 paged-SQL LeftRail+ContentPanel + M4R.7 persistent MiniPlayer are landed. Next: M4R.8 InfoPanel.

The reboot collapsed the navigator from 3 stacks + 7 screens to **one `Shell` route + one `FullscreenPlayer` route**, and rebuilt content rendering on paged SQL (never hydrating 10K+ rows into Zustand). See [PRODUCTION_PLAN_ANDROID.md § Reboot Notice](../../PRODUCTION_PLAN_ANDROID.md) for the full M4R → M10R plan.

Current APK supports: add M3U / Xtream / Stalker sources, browse channels via paged SQL, play video, persistent MiniPlayer surface. Missing: search overlay, favorites, EPG, settings UI, codec gap fix — land across M4R.8 through M10R.

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
├── navigation/      React Navigation 7 — collapsed to Shell + FullscreenPlayer routes (M4R.2)
├── screens/         FullscreenPlayer.tsx (only remaining screen — the rest became shell panels)
├── shell/           HomeShell, LeftRail, ContentPanel, MiniPlayer, SourcesModal (M4R core)
├── components/
│   └── cards/       ChannelTile (flat) — hex cards removed on mobile 2026-04-19
├── focus/           Focusable primitive + focus-memory (M4R.10)
├── player/          react-native-video wrapper + PersistentPlayerHost
├── db/              op-sqlite + migrations + queries.ts (paged SQL, M4R.4)
├── services/        Platform glue: Keystore, notifications, cast (M6R–M9R)
├── stores/          Zustand — shell-store, player-store, sources-store (state only, no bulk data)
├── http/            fetch-http-client (XMLHttpRequest-based)
├── storage/         AsyncStorage — hydration flags + last-view ONLY
└── styles/theme.ts  Ported from desktop palette
```

All platform-agnostic business logic lives in [`@yancotv/core`](../core) — parsers, API clients, classifiers, types, schemas. Import from `@yancotv/core`, not from relative paths.

## Milestone Map (post-2026-04-19 reboot)

| Milestone | Scope | Status |
|---|---|---|
| M0–M3 | Core extraction, op-sqlite, React Navigation 7 scaffolding | DONE |
| M4R | Shell reboot — collapsed navigator, paged SQL, persistent MiniPlayer, cached-first boot | IN PROGRESS (through M4R.7) |
| M5R | Groups + EPG ribbon + Favorites | PLANNED |
| M6R | EPG + Catch-up + Timeshift | PLANNED |
| M7R | Settings + Parental + Polish | PLANNED |
| M8R | Codec gap — FFmpeg ExoPlayer extension NDK build | PLANNED |
| M9R | TV UX + Phone-native features (PIP, Cast, gestures, voice) | PLANNED |
| M10R | Distribution (Play Store / Fire TV / sideload) + QA | PLANNED |

The pre-reboot M4–M9 milestones are superseded. See [PRODUCTION_PLAN_ANDROID.md § Reboot Notice](../../PRODUCTION_PLAN_ANDROID.md) for the full task breakdown.

## License

Private / Proprietary
