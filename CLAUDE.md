# YancoTV — Claude Code Project Guide

## Project Overview

YancoTV is a custom IPTV media application. Windows-first desktop app built with Electron + React + TypeScript. Not a fork — built from scratch with selective use of open-source components.

## Tech Stack

- **Runtime:** Electron 41+ (hardened)
- **Frontend:** React 18+ / TypeScript 5+ / Tailwind CSS 3
- **State:** Zustand 5
- **Database:** SQLite via better-sqlite3 (synchronous, WAL mode)
- **Playback:** mpv (via child process, behind IPlayer abstraction, JSON-RPC over named pipes)
- **Media tools:** ffmpeg (recording, downloading, subtitle extraction)
- **Package manager:** pnpm (enforced via preinstall hook)
- **Bundler:** Vite 6 (renderer), esbuild (preload), tsc (main)
- **Packaging:** electron-builder (NSIS + portable)
- **Testing:** Vitest (unit)
- **Linting:** ESLint + Prettier
- **Validation:** Zod (IPC input validation)
- **Animations:** Motion (framer-motion successor)
- **Data fetching:** React Query (TanStack Query 5)
- **Routing:** React Router 7
- **Virtualization:** react-virtuoso (large list rendering)

## Project Structure

```
YancoTV/
├── CLAUDE.md                        # This file
├── ARCHITECTURE.md                  # Process architecture, data flow, design patterns
├── PRODUCTION_PLAN.md               # Full roadmap (Phases 1–5), sprint breakdown
├── README.md                        # Project overview for users/contributors
├── package.json
├── electron-builder.yml
├── vite.config.ts
├── vitest.config.ts
├── tsconfig.json                    # Root config with project references
├── tsconfig.main.json               # Main process (ES2022, CommonJS)
├── tsconfig.renderer.json           # Renderer process (ES2022, ESNext modules)
├── tailwind.config.js
├── postcss.config.js
├── .eslintrc.json
├── .prettierrc
├── scripts/
│   ├── download-mpv.ps1             # Download mpv.exe into app bundle
│   └── run-tests.js                 # Test runner with better-sqlite3 ABI rebuild
├── src/
│   ├── main/                        # Electron main process
│   │   ├── index.ts                 # App entry, window creation, DB init
│   │   ├── preload.ts               # Preload script (IPC bridge via contextBridge)
│   │   ├── ipc/
│   │   │   └── index.ts             # All IPC handler registration (sources, content, player, etc.)
│   │   ├── services/                # Backend business logic
│   │   │   ├── db.ts                # SQLite init, WAL mode, migration runner, FTS5 management
│   │   │   ├── source-manager.ts    # Source CRUD, editing, reorder, health, credential encryption
│   │   │   ├── source-sync.ts       # Sync orchestration (M3U/Xtream/Stalker), auto-sync timer
│   │   │   ├── stalker-client.ts    # Stalker/Ministra Portal API (MAC auth, paginated fetch)
│   │   │   ├── m3u-parser.ts        # Streaming M3U parser (BOM, attributes, catchup tags)
│   │   │   ├── xtream-client.ts     # Xtream Codes API client (auth, streams, series, URLs)
│   │   │   ├── content-classifier.ts # Classify entries into live/movie/series via heuristics
│   │   │   ├── content-store.ts     # Content storage/retrieval, FTS5 search, batch operations
│   │   │   ├── title-cleaner.ts     # Strip provider noise, extract year/season/episode/show name
│   │   │   ├── xmltv-parser.ts      # Async streaming XMLTV parser (gzip, chunked yields)
│   │   │   ├── epg-service.ts       # EPG fetch/store/query, now/next, auto-refresh timer
│   │   │   ├── catchup-service.ts   # Catch-up URL building (Xtream timeshift, M3U patterns)
│   │   │   ├── timeshift-service.ts # Live TV pause/rewind state, mpv buffer args
│   │   │   ├── parental-service.ts  # PIN (SHA-256), channel lock/hide/override
│   │   │   ├── settings-service.ts  # Key-value settings store (SQLite-backed)
│   │   │   ├── credential-store.ts  # Electron safeStorage wrapper for credential encryption
│   │   │   ├── favorites-store.ts   # Favorites as join table with timestamps
│   │   │   ├── history-store.ts     # Watch history with resume positions, throttled updates
│   │   │   └── migrations/          # Versioned SQL migration files
│   │   │       ├── 001-initial-schema.sql
│   │   │       ├── 002-fts5-search.sql
│   │   │       ├── 003-sort-order.sql
│   │   │       ├── 004-epg-enhancements.sql
│   │   │       ├── 005-parental-controls.sql
│   │   │       ├── 006-epg-indexes.sql
│   │   │       └── 007-source-management-enhancements.sql
│   │   └── player/
│   │       ├── player.interface.ts   # IPlayer abstraction (play, pause, seek, tracks, events)
│   │       ├── mpv-player.ts         # mpv implementation (spawn, control, events, tracks)
│   │       ├── mpv-ipc.ts            # JSON-RPC over named pipes, retry with backoff
│   │       └── mpv-path.ts           # Locate mpv.exe in PATH or app bundle
│   ├── renderer/                     # React frontend (sandboxed renderer process)
│   │   ├── index.html
│   │   ├── main.tsx                  # React root, store init, event listeners
│   │   ├── App.tsx                   # React Router setup, page routing
│   │   ├── types/
│   │   │   └── global.d.ts          # window.api type declarations
│   │   ├── vite-env.d.ts
│   │   ├── pages/
│   │   │   ├── HomePage.tsx
│   │   │   ├── LiveTvPage.tsx        # Grid/list of live channels with EPG now/next
│   │   │   ├── MoviesPage.tsx        # VOD movies grid with sorting/filtering
│   │   │   ├── SeriesPage.tsx        # Series grid with season/episode selection
│   │   │   ├── FavoritesPage.tsx
│   │   │   ├── SearchPage.tsx        # FTS5 search results (live/movie/series tabbed)
│   │   │   ├── GuidePage.tsx         # EPG grid view (channels x time)
│   │   │   └── SettingsPage.tsx      # Tabbed settings (8 tabs)
│   │   ├── components/
│   │   │   ├── Layout.tsx            # Page wrapper with sidebar/header
│   │   │   ├── Sidebar.tsx           # Navigation menu
│   │   │   ├── ContentGrid.tsx       # Virtualized grid for 10K+ items
│   │   │   ├── HexCard.tsx           # Content card with title, logo, badges
│   │   │   ├── ChannelHexRow.tsx     # Hex card row with animation
│   │   │   ├── CategorySidebar.tsx   # Category filter sidebar
│   │   │   ├── SortDropdown.tsx      # Sort order selector
│   │   │   ├── EmptyState.tsx        # No results / empty state
│   │   │   ├── SourceList.tsx        # Manage sources (add/remove/sync)
│   │   │   ├── SourceSwitcher.tsx    # Switch between active sources
│   │   │   ├── AddSourceForm.tsx     # M3U/Xtream source input form
│   │   │   ├── PinModal.tsx          # Parental PIN entry dialog
│   │   │   └── settings/
│   │   │       ├── GeneralSettings.tsx
│   │   │       ├── PlaybackSettings.tsx
│   │   │       ├── NetworkSettings.tsx
│   │   │       ├── PlaylistSettings.tsx
│   │   │       ├── EpgSettings.tsx
│   │   │       ├── ParentalSettings.tsx
│   │   │       ├── ShortcutsSettings.tsx
│   │   │       └── AboutSettings.tsx
│   │   ├── hooks/
│   │   │   ├── use-category-groups.ts  # Group content by category
│   │   │   ├── use-epg.ts             # Fetch now/next, cached via React Query
│   │   │   └── use-player-shortcuts.ts # Keyboard event listener (Space, arrows, F, M, etc.)
│   │   ├── stores/                     # Zustand stores
│   │   │   ├── player-store.ts         # Player state, actions, history recording, event listeners
│   │   │   ├── settings-store.ts       # Settings with defaults, typed getters, optimistic updates
│   │   │   ├── favorites-store.ts      # Favorites as Set, toggle/isFavorite helpers
│   │   │   └── parental-store.ts       # PIN verification, parental UI state
│   │   ├── utils/
│   │   │   └── category-grouping.ts    # Group content items by group_name
│   │   ├── styles/
│   │   │   └── global.css              # Tailwind imports, resets, custom utilities
│   │   └── assets/
│   │       ├── yancotv_logo.png
│   │       └── hex-frames/
│   │           ├── hex-frame.svg
│   │           ├── hex-frame-hover.svg
│   │           └── hex-frame-locked.svg
│   └── shared/                         # Shared between main and renderer
│       ├── constants.ts                # APP_NAME, APP_VERSION, DB_FILE_NAME, window dimensions
│       ├── ipc-channels.ts             # Single source of truth for all IPC channel names
│       ├── types/
│       │   ├── index.ts                # Re-exports all shared types
│       │   ├── source.ts               # SourceType, Source, AddSourceInput
│       │   ├── content.ts              # ContentType, SortOption, ContentItem, Episode
│       │   ├── epg.ts                  # EpgProgramme, NowNext, NowNextMap, EpgGuideChannel, etc.
│       │   └── result.ts               # Result<T> generic error/success wrapper
│       └── schemas/
│           └── source.ts               # Zod schema for AddSourceInput validation
├── tests/
│   └── unit/
│       ├── helpers/
│       │   └── test-db.ts              # In-memory SQLite setup for tests
│       ├── content-classifier.test.ts
│       ├── content-classifier-extended.test.ts
│       ├── title-cleaner.test.ts
│       ├── title-cleaner-extended.test.ts
│       ├── m3u-parser.test.ts
│       ├── xmltv-parser.test.ts
│       ├── xtream-client.test.ts
│       ├── source-schema.test.ts
│       ├── source-manager.test.ts
│       ├── shared-types.test.ts
│       ├── favorites-store.test.ts
│       ├── history-store.test.ts
│       ├── settings-service.test.ts
│       ├── parental-service.test.ts
│       ├── catchup-service.test.ts
│       ├── timeshift-service.test.ts
│       ├── category-grouping.test.ts
│       └── ipc-wiring.test.ts
└── (no docs/ or tests/e2e/ directories yet)
```

## Commands

```bash
pnpm install          # Install dependencies (pnpm enforced via preinstall)
pnpm dev              # Run in dev mode (Vite HMR + tsc + Electron, port 5173)
pnpm build            # Build for production (tsc main + esbuild preload + vite renderer)
pnpm build:main       # Build main process only
pnpm build:renderer   # Build renderer only
pnpm package          # Build + create Windows installer (NSIS + portable)
pnpm test             # Run unit tests (rebuilds better-sqlite3 ABI first)
pnpm test:watch       # Run tests in watch mode
pnpm test:e2e         # Run e2e tests (NOT YET SET UP — no Playwright config or test files)
pnpm lint             # Lint all src/ files
pnpm lint:fix         # Auto-fix lint issues
pnpm format           # Format all src/ files with Prettier
pnpm audit            # Security audit
```

## Current Sprint Status

- **Sprints 1–6:** DONE (foundation, sources, Xtream, browsing UI, playback, search/favorites/history)
- **Sprint 7:** DONE (EPG — XMLTV parser, now/next, guide grid, auto-refresh, settings)
- **Sprints 8–10:** DONE (player enhancements, catch-up/timeshift, parental controls, settings persistence)
- **Sprint 11:** DONE (Stalker Portal client, source editing, drag-and-drop reorder, multi-source merge/dedup, health indicators, auto-sync)
- **Sprint 11B:** DONE (cinematic content detail pages — hero, tabs, episodes, info, related, animations)
- **Sprint 12:** DONE (ffmpeg recording, scheduled recording, recordings page, system tray indicator)
- **Sprint 13:** DONE (download service with retry/resume, asset bundling — poster/backdrop/.nfo/subtitles, DownloadsPage)
- **Sprint 14:** DONE (TMDb API client + enrichment service, SQLite cache, MetadataSettings tab)
- **Sprint 15:** DONE (OpenSubtitles client, auto-search on playback, in-player search UI, SQLite-backed cache, safeStorage credentials)
- **Sprint 16+:** NOT STARTED (multi-view/PIP, system features, etc.)

See [PRODUCTION_PLAN.md](PRODUCTION_PLAN.md) for the full roadmap.

## Not Yet Implemented (Planned)

These are listed in PRODUCTION_PLAN.md but do not exist in code yet:

- `backup-service.ts` — Export/import user data (Sprint 18)
- `notification-service.ts` — In-app toasts + programme reminders (Sprint 19)
- E2E tests with Playwright (Sprint 21)
- `docs/` directory
- `src/assets/icon.ico` — App icon for electron-builder (referenced in `electron-builder.yml` but missing)

## Architecture Rules

### Electron Security (NON-NEGOTIABLE)

- `contextIsolation: true` — always
- `nodeIntegration: false` — always
- `sandbox: true` — for renderer
- Never load remote/untrusted URLs in BrowserWindow
- All main<->renderer communication goes through typed IPC via preload
- Preload script exposes ONLY specific API methods, never raw `ipcRenderer`
- Define all IPC channels in `src/shared/ipc-channels.ts` — single source of truth
- Validate all data crossing the IPC boundary (Zod schemas)

### Player Abstraction

- All playback goes through the `IPlayer` interface in `src/main/player/player.interface.ts`
- Never call mpv directly from renderer or services — always through the interface
- mpv communication uses JSON-RPC over named pipes (`mpv-ipc.ts`)
- This allows swapping mpv for another backend later without touching the rest of the app

### Database

- SQLite via better-sqlite3 in the main process only (WAL mode)
- Renderer accesses data exclusively through IPC calls
- Migrations managed via versioned SQL files in `src/main/services/migrations/`
- FTS5 virtual table for full-text search with trigger-based sync
- Never store credentials in plaintext — use Electron safeStorage API (`credential-store.ts`)

### State Management

- Zustand stores in renderer for UI state (`player-store`, `settings-store`, `favorites-store`, `parental-store`)
- Main process events broadcast to renderer via IPC push channels
- React Query for caching async IPC calls (EPG data)
- Optimistic updates in stores, lazy loading from main process

### Code Conventions

- TypeScript strict mode — no `any` unless absolutely unavoidable
- Functional React components with hooks only — no class components
- Tailwind for styling — no CSS modules, no styled-components
- Named exports only — no default exports (except page-level components if needed by router)
- Error handling: use Result types for service layer, try/catch at IPC boundary
- File naming: kebab-case for files, PascalCase for components, camelCase for functions/variables
- One component per file

### What NOT To Do

- Do not use `webSecurity: false`
- Do not disable `contextIsolation`
- Do not use `shell.openExternal` with unvalidated URLs
- Do not render provider HTML/content in BrowserWindow
- Do not store M3U/Xtream credentials in plaintext files
- Do not couple any service directly to mpv — use the player interface
- Do not add features not in the production plan without explicit approval
- Do not add unnecessary abstractions — keep it simple until complexity is earned
