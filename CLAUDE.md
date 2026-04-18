# YancoTV — Claude Code Project Guide

## Project Overview

YancoTV is a custom IPTV media application, shipped as two sibling apps driven by a shared TypeScript core:

- **Desktop (Electron)** — Windows-first, mpv + SQLite, feature-complete (v0.2.0)
- **Mobile (React Native)** — Android TV + Google TV + Fire TV + Android phone/tablet, single APK, in active development toward full desktop parity

Not a fork — both apps are built from scratch with selective use of open-source components, sharing business logic via `@yancotv/core`.

**The mission for mobile:** match every feature of the desktop app, then surpass it on mobile-native capabilities (D-pad, PIP, Chromecast, voice search, home-launcher integration).

## Monorepo Layout

```
YancoTV/                              # pnpm workspace root
├── CLAUDE.md                         # This file — monorepo guide
├── ARCHITECTURE.md                   # Process/data architecture for both apps
├── PRODUCTION_PLAN.md                # Desktop roadmap (Phases 1–5, mostly DONE)
├── PRODUCTION_PLAN_ANDROID.md        # Mobile roadmap — milestones M1–M9
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
    ├── core/                         # @yancotv/core — shared business logic
    └── mobile/                       # @yancotv/mobile — React Native app
```

The desktop app is not yet moved under `packages/desktop/` — deferred as cosmetic. Both apps consume `@yancotv/core` via pnpm `workspace:*`.

## Two Plans, One Product

| Plan | Status | Scope |
|---|---|---|
| [PRODUCTION_PLAN.md](PRODUCTION_PLAN.md) | Phase 1 complete; Phase 3/4/5 ahead | Desktop feature roadmap |
| [PRODUCTION_PLAN_ANDROID.md](PRODUCTION_PLAN_ANDROID.md) | M1 in progress | Mobile feature roadmap toward desktop parity + beyond |

**Mobile work is driven by [PRODUCTION_PLAN_ANDROID.md](PRODUCTION_PLAN_ANDROID.md).** Every mobile commit should map to a milestone task there. No ad-hoc feature work.

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

## Mobile — Tech Stack

- **Framework:** React Native 0.85 (`react-native-tvos` fork) — one codebase for TV + phone
- **Language:** TypeScript 5 strict
- **Playback:** react-native-video 6 (ExoPlayer/Media3 backend)
- **Navigation:** React Navigation 7 (M3 milestone — not yet integrated)
- **State:** Zustand 5 (same shapes as desktop)
- **Database:** op-sqlite (JSI-based) — ports desktop schema + migrations (M2 milestone)
- **Data fetching:** TanStack Query 5
- **Styling:** StyleSheet + theme module (ported from desktop Tailwind palette)
- **Animations:** Reanimated 3 (added in M4)
- **Lists:** FlashList (Shopify)
- **Crash reporting:** Sentry
- **Secure credentials:** react-native-keychain (Android Keystore)
- **Hex-card clipping:** `@react-native-masked-view/masked-view` (the only way SVG clips work on RN Views)
- **Notifications:** Notifee (EPG reminders — M6)
- **Build:** local Gradle → EAS Build later

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

## Mobile Project Structure

```
packages/mobile/
├── android/                     # Native project (committed)
├── src/
│   ├── index.js                 # Entry, Sentry init
│   ├── App.tsx                  # Error boundary, hydration gate, splash
│   ├── sentry.ts
│   ├── navigation/
│   │   ├── RootNavigator.tsx    # React Navigation 7 — set up in M3
│   │   └── ScreenRouter.tsx     # Interim Zustand-based (replaced in M3)
│   ├── screens/
│   │   ├── HomeScreen.tsx
│   │   ├── LiveTvScreen.tsx     # Empty — built in M4
│   │   ├── ChannelListScreen.tsx
│   │   ├── ChannelDetailScreen.tsx  # Rebuilt as ContentDetailScreen in M4
│   │   ├── PlayerScreen.tsx
│   │   └── SourcesScreen.tsx
│   ├── components/
│   │   ├── cards/               # HexCard, ContentCard, hex-frames
│   │   ├── layout/              # AppLayout, PageHeader, Sidebar
│   │   ├── tv/                  # TvButton
│   │   └── phone/               # (added in M8)
│   ├── focus/                   # Focusable primitive (rebuilt in M3.8)
│   ├── player/                  # (expanded in M4)
│   ├── db/                      # op-sqlite + migrations (M2)
│   ├── services/                # Keystore, notifications, cast (M7–M8)
│   ├── stores/
│   │   ├── nav-store.ts         # Removed in M3
│   │   └── sources-store.ts     # Wired to SQLite in M2
│   ├── http/fetch-http-client.ts
│   ├── storage/                 # AsyncStorage (small keys only post-M2)
│   ├── styles/theme.ts          # Ported from desktop palette
│   └── assets/
└── package.json
```

## Core Project Structure

```
packages/core/src/
├── index.ts                     # Barrel exports
├── types/                       # ContentItem, Source, Episode, EPG, M3uEntry...
├── schemas/                     # Zod validators
├── parsers/                     # m3u-parser (xmltv-parser coming in M1)
├── xtream/                      # XtreamClient
├── stalker/                     # StalkerClient
├── content/                     # classifier (title-cleaner expanded in M1)
├── catchup/                     # URL builder
├── http/                        # HttpClient interface + errors
└── logger.ts                    # Logger interface, NOOP_LOGGER
```

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

### Mobile

```bash
cd packages/mobile
pnpm start            # Metro bundler
pnpm android          # Build + install on connected device (phone or TV)
pnpm typecheck        # tsc --noEmit
pnpm lint             # ESLint src/
pnpm test             # Jest unit tests
```

Release APK:
```bash
cd packages/mobile/android && ./gradlew assembleRelease
# Output: packages/mobile/android/app/build/outputs/apk/release/app-release.apk
```

### Core

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

### Mobile (M1 in progress — parity milestones ahead)

- Phase 0 (core extraction): mostly done through commit `86c45ed`
- Phase 1 (mobile foundation): scaffold + debug + release APK done (`29cbbc2`, `7533c24`, `2ad3fad`)
- Phase 2 rewrite (theme, layout, hex cards, full player, all screens): **uncommitted on master** — 13 files pending. M1 first task is to commit this.
- Persistence, navigation, and full feature parity: M1 → M9 ahead

See [PRODUCTION_PLAN_ANDROID.md](PRODUCTION_PLAN_ANDROID.md) for the full mobile roadmap and parity matrix.

## Architecture Rules

### Shared Core (`@yancotv/core`) — NON-NEGOTIABLE

- **No UI code.** No React, no React Native, no Electron, no DOM.
- **No platform I/O.** No `better-sqlite3`, no `fs`, no native mobile modules. Inject what you need via interfaces (`HttpClient`, `Logger`).
- **Pure TypeScript.** Only dependency: `zod` (and peer interfaces).
- **Deterministic, testable.** Every new module gets unit tests that run in both the desktop and mobile test suites.

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

### Mobile — Architecture Rules

See [PRODUCTION_PLAN_ANDROID.md § Architecture Rules](PRODUCTION_PLAN_ANDROID.md#architecture-rules-mobile) and [packages/mobile/CLAUDE.md](packages/mobile/CLAUDE.md) for the mobile-specific list (persistence, navigation, focus, credentials, theme).

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
- **Adding a feature to mobile?** Read [PRODUCTION_PLAN_ANDROID.md](PRODUCTION_PLAN_ANDROID.md) — find the milestone, follow the tasks. If it's not in the plan, add it to the plan first.
- **Cross-cutting change (parser, client, types)?** It belongs in `packages/core`. Update both consumers in the same PR.
- **Debugging mobile?** See [packages/mobile/CLAUDE.md](packages/mobile/CLAUDE.md) for mobile-specific tips.
