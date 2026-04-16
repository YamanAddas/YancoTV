# YancoTV — Architecture

## System Overview

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

### Planned (Not Yet Implemented)

| Service | File | Sprint | Purpose |
|---------|------|--------|---------|
| Recording Service | `recording-service.ts` | 12 | ffmpeg-based live recording + scheduler |
| Download Manager | `download-manager.ts` | 13 | Queue-based VOD download manager |
| Metadata Service | `metadata-service.ts` | 14 | TMDb API integration, title matching |
| Subtitle Service | `subtitle-service.ts` | 15 | OpenSubtitles API, subtitle file management |
| Backup Service | `backup-service.ts` | 18 | Export/import user data |
| Notification Service | `notification-service.ts` | 19 | In-app toasts, programme reminders |

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
