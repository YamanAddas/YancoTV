# YancoTV — Architecture

## Three Apps, Two Cores

> **Accuracy note, 2026-09-04.** This document said "Two Apps, One Core" and described the React
> Native app across eleven sections while the *actively developed* native Android app had none. It
> also listed six services as "not yet implemented" that had all shipped. Both are corrected below.
> Where a section still describes the frozen RN app it now says so in its own heading.

YancoTV ships as three sibling apps over **two** independent business cores.

| App | Where | Core it runs on | Status |
|---|---|---|---|
| Desktop (Electron, Windows) | `src/` | `packages/core/` (TypeScript) | Shipping, v0.3.8 |
| Android / Android TV / Fire TV | `packages/android/` | `packages/shared/` (Kotlin Multiplatform) | **Active development**, v1.6.7 |
| React Native | `packages/mobile/` | `packages/core/` (TypeScript) | **Frozen 2026-04-20**, reference only |

The two cores are **not mirrors of each other** and are not required to match — see the two-ports
note in [AGENTS.md](AGENTS.md). iOS is developed in a separate repository
(`YamanAddas/YancoTV-iOS`) and shares `packages/shared/`; there is no `packages/ios/` in this tree.

The diagram below is the original TypeScript-core picture. It is accurate for **desktop and the
frozen RN app**; the Android app does not use `@yancotv/core` at all.

```
┌──────────────────────────────────────────────────────────────────────┐
│                        @yancotv/core                                 │
│                                                                      │
│  Types · Zod schemas · M3U parser · XMLTV parser · Xtream client     │
│  Stalker client · Content classifier · Title cleaner · Catchup URLs  │
│  HTTP client interface · Zustand store factories · Parental PIN      │
│                                                                      │
│  Pure TypeScript. No UI. No platform I/O. Only `zod` as dep.         │
└───────────────┬──────────────────────────────────┬───────────────────┘
                │                                  │
    consumes    ▼                                  ▼    consumes
┌───────────────────────────────┐   ┌──────────────────────────────────┐
│  Electron Desktop (Windows)   │   │  React Native Mobile             │
│                               │   │  (Android TV + Google TV +       │
│  src/main  +  src/renderer    │   │   Fire TV + phone/tablet)        │
│  better-sqlite3 · mpv · ffmpeg│   │  op-sqlite · ExoPlayer · Media3  │
│                               │   │  react-native-keychain · Notifee │
└───────────────────────────────┘   └──────────────────────────────────┘
```

**Why this shape:**
- Every parser/client/classifier has one implementation, one test suite
- Platform apps own their UI, persistence driver, player backend, and OS integration — and nothing else
- New platform (iOS? web?) means a new consumer of the same core

See [CLAUDE.md § The `@yancotv/core` Discipline](CLAUDE.md#the-yancotvcore-discipline) for the rules on what belongs in core.

> **Non-negotiable:** `@yancotv/core` is pure ESM. Every internal relative import must use an explicit `.js` extension (`export * from './types/index.js'`, `import { X } from './xtream/client.js'`). Node 22's loader rejects extensionless specifiers at runtime — omitting the extension compiles clean but crashes Electron boot with `ERR_UNSUPPORTED_DIR_IMPORT` (caused MB-18 on 2026-04-19). Metro strips the extension for the mobile side, so the same source works on both apps. See [packages/core/README.md § ESM gotcha](packages/core/README.md#esm-gotcha--explicit-js-extensions-required).

## Desktop System Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     Electron App                            │
│                                                             │
│  ┌──────────────────┐    IPC (typed)    ┌────────────────┐  │
│  │  Renderer Process │◄────────────────►│  Main Process  │  │
│  │                   │                  │                │  │
│  │  React + Tailwind │   preload.ts     │  Services      │  │
│  │  Zustand stores   │   (bridge)       │  Database      │  │
│  │  React Router     │                  │  Player ctrl   │  │
│  │  React Query      │                  │  File I/O      │  │
│  └──────────────────┘                  └───────┬────────┘  │
│                                                │            │
│                                        ┌───────▼────────┐  │
│                                        │  mpv process   │  │
│                                        │  (child proc)  │  │
│                                        │  JSON-RPC/pipe │  │
│                                        └────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## Process Architecture

### Main Process (`src/main/`)

The main process owns all privileged operations:

- **Database access** — SQLite via better-sqlite3, synchronous queries, WAL mode
- **Network requests** — Fetching M3U files, Xtream API calls, EPG data
- **File system** — Reading local M3U files
- **Player control** — Spawning and communicating with mpv child process via named pipes
- **Credential storage** — Electron safeStorage for encrypting Xtream credentials
- **Background jobs** — EPG auto-refresh timer, source auto-sync timer

The renderer NEVER accesses these directly. Everything goes through IPC.

### Renderer Process (`src/renderer/`)

The renderer is a sandboxed web page. It:

- Renders the UI with React
- Manages UI state with Zustand
- Calls main process services through the `window.api` bridge (exposed by preload)
- Uses React Query for caching and managing async IPC calls

### Preload Script (`src/main/preload.ts`)

The preload script is the security boundary. It exposes a typed `window.api` object using `contextBridge.exposeInMainWorld`. Example:

```typescript
// preload.ts
contextBridge.exposeInMainWorld('api', {
  sources: {
    getAll: () => ipcRenderer.invoke('sources:getAll'),
    add: (source: AddSourceInput) => ipcRenderer.invoke('sources:add', source),
    remove: (id: string) => ipcRenderer.invoke('sources:remove', id),
  },
  player: {
    play: (url: string) => ipcRenderer.invoke('player:play', url),
    pause: () => ipcRenderer.invoke('player:pause'),
    stop: () => ipcRenderer.invoke('player:stop'),
    seek: (seconds: number) => ipcRenderer.invoke('player:seek', seconds),
  },
  // ... other namespaced methods
});
```

## Data Flow

### Adding a Source

```
User enters M3U URL or Xtream credentials
  → Renderer calls window.api.sources.add(input)
    → IPC invoke → Main process handler
      → Validates input with Zod schema
      → Fetches M3U / calls Xtream API
      → Parses content (M3U parser or Xtream client)
      → Classifies content (live / movie / series)
      → Cleans titles
      → Stores in SQLite
      → Returns result via IPC
    → Renderer updates UI via React Query invalidation
```

### Playing a Stream

```
User clicks a channel/movie
  → Renderer calls window.api.player.play(streamUrl)
    → IPC invoke → Main process handler
      → PlayerController.play(url) via IPlayer interface
        → MpvPlayer spawns/commands mpv child process
        → mpv opens stream in embedded window or overlay
      → Returns playback status
    → Renderer shows player UI controls
    → Player events (position, duration, state) sent via IPC push
```

## Player Abstraction

```typescript
// src/main/player/player.interface.ts
interface IPlayer {
  play(url: string, options?: PlayOptions): Promise<void>;
  pause(): Promise<void>;
  resume(): Promise<void>;
  stop(): Promise<void>;
  seek(seconds: number): Promise<void>;
  setVolume(level: number): Promise<void>;
  getState(): PlayerState;

  // Tracks
  getSubtitleTracks(): SubtitleTrack[];
  setSubtitleTrack(id: number): Promise<void>;
  addSubtitleFile(path: string): Promise<void>;
  getAudioTracks(): AudioTrack[];
  setAudioTrack(id: number): Promise<void>;

  // Display
  setAspectRatio(ratio: string): Promise<void>;
  setSpeed(speed: number): Promise<void>;

  // Events
  on(event: 'state-change', handler: (state: PlayerState) => void): void;
  on(event: 'time-update', handler: (position: number) => void): void;
  on(event: 'error', handler: (error: Error) => void): void;

  destroy(): Promise<void>;
}
```

`MpvPlayer` implements this interface via JSON-RPC over named pipes (`mpv-ipc.ts`). Future Android builds would use `ExoPlayerAdapter` implementing the same contract.

## Main Process Services

### Implemented

| Service | File | Purpose |
|---------|------|---------|
| Database | `db.ts` | SQLite init, WAL mode, migrations, FTS5 index management |
| Source Manager | `source-manager.ts` | CRUD for IPTV sources, credential encryption, Zod validation |
| Source Sync | `source-sync.ts` | Sync orchestration (M3U/Xtream/Stalker), progress broadcasting, auto-sync timer, health tracking |
| Stalker Client | `stalker-client.ts` | Stalker/Ministra Portal API (MAC-based auth, paginated fetch, stream URL building) |
| M3U Parser | `m3u-parser.ts` | Streaming M3U/M3U8 parser (BOM, attributes, catchup tags, EPG URL extraction) |
| Xtream Client | `xtream-client.ts` | Xtream Codes API (auth, live/VOD/series streams, URL building) |
| Content Classifier | `content-classifier.ts` | Classify M3U entries into live/movie/series via heuristics |
| Content Store | `content-store.ts` | Content storage/retrieval, FTS5 search, batch operations with progress |
| Title Cleaner | `title-cleaner.ts` | Strip provider noise, extract year/season/episode/show name |
| XMLTV Parser | `xmltv-parser.ts` | Async streaming XMLTV parser (gzip, chunked yields every 2000 programmes) |
| EPG Service | `epg-service.ts` | EPG fetch/store/query, now/next/batch, guide data, auto-refresh timer |
| Catch-up Service | `catchup-service.ts` | Build catch-up stream URLs (Xtream timeshift, M3U catchup patterns) |
| Timeshift Service | `timeshift-service.ts` | Live TV pause/rewind state, mpv buffer args, state broadcasting |
| Parental Service | `parental-service.ts` | PIN (SHA-256 hashed), channel lock/hide, name/logo/group overrides |
| Settings Service | `settings-service.ts` | Key-value settings store backed by SQLite |
| Credential Store | `credential-store.ts` | Electron safeStorage wrapper for OS-level credential encryption |
| Favorites Store | `favorites-store.ts` | Favorites as join table to content with timestamps |
| History Store | `history-store.ts` | Watch history with resume positions, grouped by content+episode, throttled updates |

### Shipped since this table was written

Every row below was once listed here as "Planned (Not Yet Implemented)". **All six shipped**, four
of them under a different filename than was planned — which is why the old table read as a to-do
list for work that already existed. Each was confirmed present *and* reachable: the file exists and
the feature has live IPC channels in `src/shared/ipc-channels.ts`.

| Service | Planned as | Actually shipped as | IPC channels |
|---------|-----------|---------------------|--------------|
| Recording | `recording-service.ts` | `recording-service.ts` | 10 |
| Downloads | `download-manager.ts` | `download-service.ts` | 13 |
| Metadata / TMDb | `metadata-service.ts` | `tmdb-service.ts` + `tmdb-client.ts` | 6 |
| Subtitles | `subtitle-service.ts` | `subtitle-cache-service.ts` + `subtitle-extractor.ts` + `opensubtitles-client.ts` | 13 |
| Backup | `backup-service.ts` | `backup-service.ts` | 3 |
| Reminders | `notification-service.ts` | `reminder-service.ts` | 8 |

Other services present in `src/main/services/` and not described anywhere above: `asset-fetcher`,
`content-classifier`, `crash-handler`, `db`, `ffmpeg-path`, `group-preferences-store`, `m3u-parser`,
`nfo-writer`, `node-http-client`, `source-manager`, `source-sync`, `stalker-client`, `tray-service`,
`update-service`, `xtream-client` — **35 files** plus a `migrations/` directory. `ls src/main/services/` is the authority; this
document is a map, not an index.

## Database Schema (SQLite)

The schema is managed through 7 migration files. Below reflects what is actually deployed.

### Core Tables

```sql
-- IPTV sources (M3U URLs, local files, Xtream credentials, Stalker portals)
-- NOTE: Original CHECK(type IN ('m3u_url','m3u_file','xtream')) cannot be altered in SQLite.
-- Stalker inserts bypass it via PRAGMA ignore_check_constraints in source-manager.ts.
CREATE TABLE sources (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  type TEXT NOT NULL,  -- 'm3u_url', 'm3u_file', 'xtream', 'stalker'
  url TEXT,
  file_path TEXT,
  username_encrypted BLOB,
  password_encrypted BLOB,
  mac_address_encrypted BLOB,  -- Sprint 11: Stalker MAC address
  epg_url TEXT,
  priority INTEGER NOT NULL DEFAULT 0,  -- Sprint 11: source ordering for dedup
  channel_count INTEGER NOT NULL DEFAULT 0,  -- Sprint 11: health tracking
  last_sync_error TEXT,  -- Sprint 11: last sync error message
  auto_sync_interval INTEGER NOT NULL DEFAULT 0,  -- Sprint 11: per-source sync interval (hours)
  last_synced INTEGER,
  is_active INTEGER DEFAULT 1,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

-- Channels, movies, series entries
CREATE TABLE content (
  id TEXT PRIMARY KEY,
  source_id TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
  type TEXT NOT NULL CHECK(type IN ('live', 'movie', 'series')),
  title TEXT NOT NULL,
  clean_title TEXT,
  group_name TEXT,
  stream_url TEXT NOT NULL,
  logo_url TEXT,
  tvg_id TEXT,
  metadata_json TEXT,
  sort_order INTEGER DEFAULT 0,
  created_at INTEGER NOT NULL
);

-- Series episode tracking
CREATE TABLE episodes (
  id TEXT PRIMARY KEY,
  content_id TEXT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
  season_number INTEGER,
  episode_number INTEGER,
  title TEXT,
  stream_url TEXT NOT NULL,
  duration INTEGER
);

-- User favorites
CREATE TABLE favorites (
  id TEXT PRIMARY KEY,
  content_id TEXT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
  added_at INTEGER NOT NULL
);

-- Watch history with resume positions
CREATE TABLE watch_history (
  id TEXT PRIMARY KEY,
  content_id TEXT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
  episode_id TEXT REFERENCES episodes(id),
  position_seconds INTEGER DEFAULT 0,
  duration_seconds INTEGER,
  watched_at INTEGER NOT NULL
);

-- EPG programme data
CREATE TABLE epg_programmes (
  id TEXT PRIMARY KEY,
  channel_tvg_id TEXT NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  start_time INTEGER NOT NULL,
  end_time INTEGER NOT NULL,
  category TEXT,
  icon_url TEXT,
  source_id TEXT
);

-- App settings (key-value)
CREATE TABLE settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

-- Parental controls
CREATE TABLE locked_channels (
  content_id TEXT PRIMARY KEY REFERENCES content(id) ON DELETE CASCADE,
  locked_at INTEGER NOT NULL
);

CREATE TABLE hidden_channels (
  content_id TEXT PRIMARY KEY REFERENCES content(id) ON DELETE CASCADE,
  hidden_at INTEGER NOT NULL
);

CREATE TABLE channel_overrides (
  content_id TEXT PRIMARY KEY REFERENCES content(id) ON DELETE CASCADE,
  custom_name TEXT,
  custom_logo_url TEXT,
  custom_number INTEGER,
  custom_group TEXT,
  updated_at INTEGER NOT NULL
);

-- FTS5 full-text search
CREATE VIRTUAL TABLE content_fts USING fts5(
  title, clean_title, group_name,
  content=content, content_rowid=rowid
);
-- Triggers keep content_fts in sync with content table

-- Key indexes
CREATE INDEX idx_sources_priority ON sources(priority ASC);
CREATE INDEX idx_content_source ON content(source_id);
CREATE INDEX idx_content_type ON content(type);
CREATE INDEX idx_content_group ON content(group_name);
CREATE INDEX idx_content_clean_title ON content(clean_title);
CREATE INDEX idx_content_tvg_id ON content(tvg_id);
CREATE INDEX idx_epg_channel_time ON epg_programmes(channel_tvg_id, start_time);
CREATE INDEX idx_epg_source ON epg_programmes(source_id);
CREATE INDEX idx_watch_history_content ON watch_history(content_id);
```

### Tables Planned (Not Yet in Migrations)

These will be added when their respective sprints are implemented:

- `recordings` — Sprint 12
- `downloads` — Sprint 13
- `custom_groups` / `custom_group_channels` — Sprint 10 (partial — overrides exist, custom groups do not)
- `subtitle_cache` — Sprint 15

## Content Classification Logic

### From Xtream Codes API
Straightforward — the API separates live, VOD, and series into distinct endpoints.

### From M3U Files
Heuristic-based:
1. **Duration check:** `-1` or `0` duration → likely live
2. **Group-title patterns:** Groups containing "VOD", "Movie", "Film" → movie. Groups containing "Series", "Episode", "Season" → series.
3. **URL patterns:** `.ts` streams → likely live. `.mp4`, `.mkv` → likely VOD.
4. **Title patterns:** Titles matching `S\d+E\d+` or `Season \d+` → series.
5. **Fallback:** Unclassified content defaults to live (most common in M3U).

## IPC Channel Namespaces

All channels defined in `src/shared/ipc-channels.ts`:

- `sources:*` — Source management (getAll, add, remove, update, reorder, sync, syncProgress)
- `content:*` — Content browsing (getLive, getMovies, getSeries, getCategories, search, getEpisodes)
- `player:*` — Playback control (play, pause, resume, stop, seek, setVolume, toggleMute, setSpeed, setAspectRatio, toggleFullscreen, getTracks, setSubtitleTrack, setAudioTrack, state)
- `player:*Changed/timeUpdate/error` — Main->renderer push events
- `favorites:*` — Favorites operations (getAll, add, remove, getIds)
- `history:*` — Watch history (getRecent, record, updatePosition, getPosition, remove, clear)
- `epg:*` — EPG operations (refresh, getNowNext, getNowNextBatch, getGuide, getForChannel, getStats, setGlobalUrl, getSettings, refreshProgress)
- `catchup:*` — Catch-up (getUrl, checkSupport)
- `timeshift:*` — Timeshift control (activate, deactivate, getState, state)
- `settings:*` — App settings (getAll, set, setMany)
- `parental:*` — Parental controls (getSettings, setPin, verifyPin, removePin, updateSetting, lockChannel, unlockChannel, getLockedIds, isLocked, hideChannel, unhideChannel, getHiddenIds, setOverride, removeOverride, getOverrides)
- `db:status` — Database health check
- `dialog:openM3uFile` — File picker for M3U
- `app:getVersion` — App version

## Security Model

| Concern | Mitigation |
|---------|-----------|
| Untrusted playlist data | Validate/sanitize all parsed M3U/Xtream data with Zod before storage |
| Credential storage | Encrypt with Electron safeStorage API before writing to DB |
| IPC boundary | Typed channels, input validation on every handler, no raw ipcRenderer exposure |
| Remote content | Never load provider URLs in BrowserWindow. Streams go to mpv only |
| Renderer sandbox | `sandbox: true`, `contextIsolation: true`, `nodeIntegration: false` |
| URL handling | Validate all URLs before passing to mpv or network calls |
| Parental PIN | Store SHA-256 hashed (not plaintext). Rate-limit attempts |

## Key Design Patterns

1. **Result Type** — All risky operations return `Result<T>` for type-safe error handling (no thrown exceptions in service layer)
2. **Service Layer** — Business logic in `src/main/services/*`, accessed only via IPC by renderer
3. **Zustand Stores** — Renderer state with optimistic updates; main-process events broadcast via IPC
4. **Progress Callbacks** — Long operations (source sync, EPG refresh) broadcast progress to renderer in real-time
5. **Batch Processing** — Content storage, EPG insertion yield to event loop every N items to keep IPC responsive
6. **FTS5 Search** — Full-text search with per-type queries (live/movie/series chunked to 60 results each)
7. **Credential Encryption** — Electron safeStorage wrapping for OS-level encryption of credentials
8. **Player Abstraction** — IPlayer interface allows future implementations without renderer changes
9. **Migration System** — SQL files in `src/main/services/migrations/` run on app startup (idempotent)
10. **Category Normalization** — Consistent category names across different providers via regex substitution

## Directory Conventions

- `src/main/services/` — One file per service, each responsible for a single domain
- `src/main/ipc/` — IPC handler registration (currently consolidated in `index.ts`)
- `src/renderer/pages/` — Route-level components
- `src/renderer/components/` — Reusable UI components (settings sub-components in `settings/` subdirectory)
- `src/renderer/hooks/` — Custom React hooks
- `src/renderer/stores/` — Zustand store definitions
- `src/renderer/utils/` — Utility functions
- `src/shared/types/` — TypeScript interfaces/types used by both processes
- `src/shared/schemas/` — Zod validation schemas

---

## Native Android System Overview

The actively developed app, and the one this document previously said nothing about. It shares
**no code** with the desktop app: its business logic is `packages/shared/` (Kotlin Multiplatform),
not `@yancotv/core`.

| Concern | Choice | Where |
|---|---|---|
| UI | Jetpack Compose; `androidx.tv.material` for TV focus targets, Material3 on phone | `packages/android/app/src/main/java/com/yancotv/android/ui/` |
| Playback | Media3 ExoPlayer, **exactly one instance**, owned by `PlaybackController` | `.../android/player/PlaybackController.kt` |
| Persistence | SQLDelight over SQLite — the only DB surface on native | `packages/shared/src/commonMain/sqldelight/` (20 `.sq` files) |
| Networking | Ktor + kotlinx.serialization (no Retrofit/Moshi/Gson — they do not compile for iOS) | `packages/shared/.../http/` |
| DI | Koin | `.../android/di/` |
| Images | Coil 3 | — |
| Background work | WorkManager (EPG reminders, source sync) | `.../android/work/` |
| Crash reporting | Sentry | `packages/android/local.properties` holds the DSN |
| Credentials | Android Keystore. Never SQLite, never a settings file, never a log | — |

**Two rules that look like duplication and are not.**

- **One ExoPlayer.** The mini-preview and fullscreen player are the *same* player with its output
  Surface swapped (`setVideoSurface` / `clearVideoSurface`, symmetric on entry **and** exit). Not
  `PlayerView.switchTargetView()`, which this codebase does not call. ADR 0001 predates this and
  names `switchTargetView()`; the ADR records what was decided then, the rule here is what the code
  does now.
- **`AndroidEpgImporter`, not the shared `EpgRepository.refresh()`.** The shared path materialises
  the whole XMLTV body and exhausts a Fire TV's heap (MB-230), so Android streams the import to a
  temp file instead. This is a deliberate platform divergence, not drift — and it is why the shared
  name-matching index (`epg_channel_names`) is never populated on Android.

**Sync path, worth knowing before touching it.** `SourceRepository.syncSource` → `BulkContentWriter`
(`packages/shared/.../sources/BulkContentWriter.kt`). Two constraints there have already caused data
loss and a performance regression respectively, and they interact: the destructive clear runs only
*after* replacement rows are in hand (MB-353), and it is broken into bounded transactions so it
cannot hold the write lock long enough to starve a UI write (MB-315). `content_fts` is an **fts4**
virtual table, which indexes no column — anything shaped like `WHERE content_id IN (...)` scans the
entire index, so it must never be put inside a loop (MB-402).

**Schema units, the trap.** Timestamps are **milliseconds**, except `watch_history.position_seconds`
/ `duration_seconds` (media offsets) and `epg_programmes.start_time` / `end_time`, which are XMLTV
epoch **seconds** and are compared against `clock() / 1000`. "Correcting" the EPG columns to
milliseconds empties the guide silently — that was MB-390.

**Adaptive layout.** Window shape is measured once into `ShellMetrics` and read through
`LocalShellMetrics`; `usesSidebar` / `usesCoverflow` are the single source of truth for whether a
window gets the TV sidebar or the phone bottom bar. Nothing else may re-derive that from a width.

---

> **The eleven sections below describe the FROZEN React Native app** (`packages/mobile/`,
> superseded 2026-04-20 — see [docs/adr/0001-native-pivot.md](docs/adr/0001-native-pivot.md)). They
> are kept because the RN app is still runnable for reference. **They do not describe the shipping
> Android app** — that is the section above. Several file names in them are also stale: the RN app
> uses `*-store.ts`, not the `*-repo.ts` these tables name, and there is no
> `packages/mobile/src/services/keychain.ts`.

## Mobile System Overview (frozen RN app)

The mobile app ships a single React Native APK that targets Android TV, Google TV, Fire TV, and Android phones/tablets. Unlike the desktop app, there is no process boundary between UI and services — everything runs inside the JS bundle plus native Android modules.

```
┌──────────────────────────────────────────────────────────────┐
│                 React Native APK (single bundle)             │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  JS Bundle (Hermes)                                    │  │
│  │                                                        │  │
│  │  React Native UI  +  Zustand stores  +  TanStack Query │  │
│  │  React Navigation 7  (drawer on TV / tabs on phone)    │  │
│  │                                                        │  │
│  │  @yancotv/core: parsers · clients · classifier · types │  │
│  │  fetch-http-client (XMLHttpRequest-based)              │  │
│  └──────────┬───────────────────┬─────────────────┬───────┘  │
│             │ JSI               │ bridge          │ bridge   │
│             ▼                   ▼                 ▼          │
│  ┌────────────────┐   ┌────────────────┐  ┌───────────────┐  │
│  │  op-sqlite     │   │ExoPlayer/Media3│  │ Keystore      │  │
│  │  (native)      │   │ (via RN-Video) │  │ (Keychain)    │  │
│  │                │   │                │  │               │  │
│  │  same schema   │   │  IPlayer parity│  │ credential    │  │
│  │  as desktop    │   │  mirror        │  │ encryption    │  │
│  └────────────────┘   └────────────────┘  └───────────────┘  │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

**Key differences vs. desktop:**
- No IPC — the renderer-equivalent calls services directly through JSI and JS imports
- Persistence driver is op-sqlite (JSI-based, synchronous-friendly) instead of better-sqlite3
- Playback backend is Android's Media3/ExoPlayer via react-native-video 6 instead of an mpv child process
- OS-level credential encryption goes through Android Keystore via react-native-keychain (no Electron safeStorage)
- One navigator switches drawer (TV) vs. bottom tabs (phone) off `Platform.isTV`; UI components branch on the same flag

## Mobile Process Model (frozen RN app)

The React Native runtime is split across the JavaScript bundle (Hermes engine) and the native Android layer. Our code runs in four zones:

| Zone | Owns | Example |
|---|---|---|
| JS: platform-agnostic (`@yancotv/core`) | Parsers, API clients, classifiers, Zod schemas, pure functions | `XtreamClient.getStreams()` |
| JS: mobile-specific (`packages/mobile/src/`) | UI, navigation, stores, screens, React Native glue | `PlayerScreen.tsx` |
| Native: third-party modules | op-sqlite, react-native-video, react-native-keychain, masked-view, Sentry | op-sqlite JSI bridge |
| Native: Android platform (`android/app/src/main/`) | Manifest (Leanback + standard launcher), Gradle config, MainActivity | `AndroidManifest.xml` |

There is NO separate "main process" — the JS bundle owns all business logic and orchestrates the native modules directly.

## Mobile Data Flow (frozen RN app)

### Adding a Source

```
User enters M3U URL or Xtream credentials (AddSourceForm)
  → sources-store.addSource(input)
    → Validates input with Zod schema (from @yancotv/core)
    → fetch-http-client fetches M3U / calls Xtream API
    → M3U parser or XtreamClient (from @yancotv/core) parses response
    → content-classifier classifies entries (live / movie / series)
    → title-cleaner normalizes titles
    → op-sqlite inserts into content table (batched)
    → react-native-keychain encrypts credentials and stores under key `source:<id>`
    → Store emits state update → UI re-renders
```

### Playing a Stream

```
User clicks a channel (ChannelListScreen or Content Detail)
  → player-store.play(url, title, contentId)
    → history-store records start position (throttled)
    → PlayerScreen mounts with route params
    → react-native-video <Video> component opens stream
      → ExoPlayer/Media3 handles HLS/DASH/MPEG-TS natively
    → onProgress callback → history-store.updatePosition (throttled)
    → onError → Sentry capture + user-visible toast
```

No IPC hop. No process boundary. The UI, the store, and the DB call all happen in the same JS runtime; only the video surface + persistence engine cross into native.

## Mobile Services (frozen RN app)

Everything the desktop does in `src/main/services/` has a mobile counterpart. Some live in `@yancotv/core` (shared), others in `packages/mobile/src/` (platform-specific).

| Desktop service | Mobile counterpart | Location | Milestone |
|---|---|---|---|
| `m3u-parser.ts` | Same | `@yancotv/core` | Already extracted |
| `xtream-client.ts` | Same | `@yancotv/core` | Already extracted |
| `stalker-client.ts` | Same | `@yancotv/core` | Already extracted |
| `content-classifier.ts` | Same | `@yancotv/core` | Already extracted |
| `title-cleaner.ts` | Same | `@yancotv/core` | Already extracted |
| `xmltv-parser.ts` | Same (streaming via byte chunks, no Node stream dep) | `@yancotv/core` | M1.4 |
| `catchup-service.ts` | Same (pure URL builder) | `@yancotv/core` | M1.4 |
| `parental-service.ts` | PIN hashing in core; DB ops mobile-local | `@yancotv/core` + `packages/mobile/src/db/` | M1.4 + M7.5 |
| `db.ts` (better-sqlite3 init) | `packages/mobile/src/db/index.ts` (op-sqlite init + WAL) | mobile | M2 |
| `content-store.ts` | `packages/mobile/src/db/content-repo.ts` | mobile | M4 |
| `source-manager.ts` | `packages/mobile/src/stores/sources-store.ts` + repo | mobile | M1 + M4 |
| `source-sync.ts` | `packages/mobile/src/services/source-sync.ts` | mobile | M4 |
| `epg-service.ts` | `packages/mobile/src/services/epg-service.ts` | mobile | M6 |
| `timeshift-service.ts` | `packages/mobile/src/services/timeshift-service.ts` | mobile | M6 (playback buffer config via react-native-video) |
| `favorites-store.ts` | `packages/mobile/src/db/favorites-repo.ts` + store | mobile | M5 |
| `history-store.ts` | `packages/mobile/src/db/history-repo.ts` + store | mobile | M4–M5 |
| `credential-store.ts` (safeStorage) | `packages/mobile/src/services/keychain.ts` (react-native-keychain) | mobile | M7 |
| `recording-service.ts` | **DROPPED v1** (Media3 can't drive arbitrary ffmpeg recording on-device without a large native module; deferred) | — | Post-release |
| `download-manager.ts` | `packages/mobile/src/services/download-service.ts` (Media3 HLS download API) | mobile | Post-release |
| `metadata-service.ts` (TMDb) | Same (pure HTTP) | `@yancotv/core` | M7 |
| `subtitle-service.ts` | Same client; file I/O mobile-local (RNFS) | `@yancotv/core` + mobile | M7 |
| `settings-service.ts` | `packages/mobile/src/db/settings-repo.ts` | mobile | M7 |

## Mobile Player Abstraction (frozen RN app)

The desktop `IPlayer` interface (see above) is mirrored on mobile. The mobile implementation (`packages/mobile/src/player/rn-video-player.ts`, M4) wraps react-native-video:

```typescript
// packages/mobile/src/player/rn-video-player.ts (M4)
class RnVideoPlayer implements IPlayer {
  play(url: string, options?: PlayOptions): Promise<void>;     // sets <Video source={url}>
  pause(): Promise<void>;                                       // paused prop
  seek(seconds: number): Promise<void>;                         // ref.seek()
  setSubtitleTrack(id: number): Promise<void>;                  // selectedTextTrack prop
  getAudioTracks(): AudioTrack[];                               // from onLoad event
  setSpeed(speed: number): Promise<void>;                       // rate prop
  // ...same contract as desktop
}
```

Benefits:
- Screens and stores never import react-native-video directly — they talk to `IPlayer`
- Swapping backends (e.g. to a VLC-based player for broader codec support) is a one-file change
- The existing desktop `player-store` logic ports over with only the `IPlayer` constructor wiring changed

## Mobile Persistence Model (frozen RN app)

Post-M2 the app has three storage tiers:

```
AsyncStorage (small keys only — 64MB cap configured in gradle.properties)
  └── app:hydrated, app:last-screen, app:theme, ephemeral UI state

op-sqlite @ yancotv-mobile.db (WAL mode, FTS5 enabled)
  ├── sources             ── same schema as desktop (migration 001–007 mirrored)
  ├── content             ── same schema, same FTS5 trigger pattern
  ├── episodes
  ├── favorites
  ├── watch_history
  ├── epg_programmes
  ├── locked_channels, hidden_channels, channel_overrides
  ├── settings
  └── content_fts         ── FTS5 virtual table

Android Keystore (via react-native-keychain)
  └── source credentials  ── username / password / MAC keyed by `source:<id>`
```

**Schema mirroring rule:** the `packages/mobile/src/db/migrations/` folder contains byte-identical copies of `src/main/services/migrations/*.sql`. Any schema change ports to both folders in the same commit. There is no divergent-schema story — both apps read the same shape.

**Why AsyncStorage stays in the picture:** it's the fastest hydration path for small Zustand state (which tab was active, whether the DB is ready). Content lists never touch it — historically, persisting 10K channels there triggered `SQLITE_FULL` until the 64MB cap was raised, and op-sqlite closes that door entirely.

## Mobile State Management (frozen RN app)

Same Zustand stores, same shapes as desktop — only the backing service differs:

| Store | Desktop reads from | Mobile reads from |
|---|---|---|
| `sources-store` | IPC → `source-manager.ts` | Direct → `sources-repo.ts` + `keychain.ts` |
| `player-store` | IPC → `IPlayer` in main | Direct → `RnVideoPlayer` (same `IPlayer` contract) |
| `favorites-store` | IPC → `favorites-store.ts` in main | Direct → `favorites-repo.ts` |
| `history-store` | IPC → `history-store.ts` in main | Direct → `history-repo.ts` |
| `settings-store` | IPC → `settings-service.ts` | Direct → `settings-repo.ts` |
| `parental-store` | IPC → `parental-service.ts` | Direct → `parental-repo.ts` + core PIN hashing |

Action signatures match one-for-one (e.g. `play(url, title, contentId)` exists on both). Any new store action lands in both apps in the same PR — that's the discipline that keeps future core extraction cheap.

Hydration lives in `App.tsx`'s hydration gate: AsyncStorage restores small keys first, op-sqlite opens on a background task, and the UI unblocks once both resolve. Long-lived content arrays are NEVER persisted to AsyncStorage — they're queried on demand.

## Mobile Navigation (frozen RN app)

React Navigation 7, installed in M3. The root navigator branches once on `Platform.isTV`:

```
                         RootNavigator
                              │
          ┌───────────────────┴───────────────────┐
          ▼  isTV                            !isTV ▼
   TvDrawerNavigator                  PhoneTabNavigator
   ├── Home                           ├── Home
   ├── Live TV                        ├── Browse (stack: Live/Movies/Series)
   ├── Movies                         ├── Search
   ├── Series                         ├── Favorites
   ├── Search                         └── Settings
   ├── Favorites
   ├── Guide (EPG)
   ├── Settings
   └── (Player overlays as modal stack on both)
```

After M3 the ad-hoc `ScreenRouter.tsx` / `nav-store.ts` pattern is deleted. All navigation flows through React Navigation's `navigate()` / linking config / deep links. Phase 2's manual router was a scaffolding crutch — no new screens should reach for it.

## Mobile Focus Model (frozen RN app)

TV focus is handled by a single primitive — either a `<Focusable>` wrapper or `TVFocusGuideView` from `react-native-tvos`. Rules:

- Every screen declares a first-focus element via `hasTVPreferredFocus` on mount
- Every horizontal rail inside a vertical scroller wraps in `TVFocusGuideView` with `destinations={[...]}` so D-pad doesn't "fall through" the ScrollView edge
- `useFocusEffect` restores focus when returning from navigation (React Navigation drops it by default)
- `FlatList`/`FlashList` with `removeClippedSubviews=false` inside focusable rails — otherwise off-screen items lose their focus target on scroll

No screen rolls its own focus logic — all TV-specific behavior goes through the shared primitive. Debugging focus? Enable the focus-overlay switch (added in M3.8) that outlines the currently-focused element in red.

## Mobile Security Model (frozen RN app)

| Concern | Mitigation |
|---|---|
| Credential storage | `react-native-keychain` backed by Android Keystore; no plaintext in SQLite or AsyncStorage |
| HTTP traffic to IPTV providers | `android:usesCleartextTraffic="true"` because many providers are HTTP-only; acceptable because user supplies their own credentials for their own sources |
| Untrusted playlist data | Same Zod validation layer as desktop (shared via `@yancotv/core`) |
| Remote content loading | Streams go only to the ExoPlayer surface, never to a WebView |
| PIN enforcement | Hashed via scrypt (shared core); DB-backed lock list; attempts rate-limited in-store |
| Sentry | Breadcrumbs scrub URL params with known credential keys (`password`, `mac`, `token`) via the same redactor used by fetch-http-client |

No Electron-specific concerns apply (no `contextIsolation`, no preload bridge to harden) — but the native-module surface adds its own risks: any third-party module added ships with native code that we can't fully audit. Every native dep landing in package.json needs a brief review of its permissions footprint in the Android manifest.

## Mobile Build & Distribution (frozen RN app)

```
pnpm android                → local Gradle build (debug APK)
cd android && ./gradlew assembleRelease   → signed release APK
```

Distribution targets (M9):
- **Google Play** (phone + TV listings share one app; TV listing requires Leanback launcher intent-filter in manifest — already present)
- **Fire TV app store** (Amazon Appstore — separate submission, same APK)
- **Sideload** for users on TV boxes without a store (`adb install -r app-release.apk`)

No EAS / no cloud build yet — local Gradle is sufficient through M8. EAS becomes attractive once we need per-device-class build variants (e.g. an ExoPlayer extension build for older Fire TV Stick hardware).

## Cross-Platform Invariants

Things that MUST stay identical between desktop and mobile:

1. **Database schema** — byte-identical migration SQL in both apps
2. **Core API surface** — `@yancotv/core` exports the same types, parsers, clients to both
3. **Zustand store signatures** — action names and signatures match (e.g. `player.play(url, title, contentId)`)
4. **Zod schemas** — input validation lives in core; both apps validate against the same rules
5. **Parental PIN format** — scrypt params identical so a backup from desktop can restore on mobile (M9)
6. **Settings keys** — same key names in `settings` table so export/import works across platforms

Things that MAY diverge:
- UI layouts (obviously — hex grid on desktop, bottom-tab phone view, D-pad-first TV drawer)
- Player feature surface (mpv supports things ExoPlayer doesn't and vice versa — `IPlayer` exposes the intersection; extras are per-platform extensions)
- Recording (desktop only, v1)
- Background tasks (desktop uses setInterval; mobile wraps in JS-level timers and hands off to Notifee/Media3 for OS-level scheduling)

When in doubt, push shared behavior into `@yancotv/core` and keep platform glue thin.
