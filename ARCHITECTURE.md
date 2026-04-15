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
│                                        ├────────────────┤  │
│                                        │  ffmpeg        │  │
│                                        │  (rec/download)│  │
│                                        └────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## Process Architecture

### Main Process (`src/main/`)

The main process owns all privileged operations:

- **Database access** — SQLite via better-sqlite3, synchronous queries
- **Network requests** — Fetching M3U files, Xtream API calls, Stalker Portal API, EPG data, TMDb API, OpenSubtitles API
- **File system** — Reading local M3U files, saving downloads/recordings, subtitle files
- **Player control** — Spawning and communicating with mpv child process
- **Media processing** — ffmpeg for recording, downloading, timeshift buffering, subtitle extraction
- **Credential storage** — Electron safeStorage for encrypting Xtream/Stalker credentials
- **Background jobs** — EPG refresh, recording scheduler, download queue, metadata matching

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
User enters M3U URL, Xtream credentials, or Stalker Portal info
  → Renderer calls window.api.sources.add(input)
    → IPC invoke → Main process handler
      → Validates input with Zod
      → Fetches M3U / calls Xtream API / calls Stalker API
      → Parses content (M3U parser, Xtream client, or Stalker client)
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

### Recording a Live Stream

```
User clicks Record or scheduled recording triggers
  → Main process RecordingService
    → Spawns ffmpeg: ffmpeg -i <stream_url> -c copy <output_file>
    → Monitors ffmpeg process for progress/errors
    → Updates recording status in DB
    → Broadcasts progress events to renderer via IPC
  → Renderer shows recording indicator + progress
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

`MpvPlayer` implements this interface. Future Android builds use `ExoPlayerAdapter` implementing the same contract.

## Main Process Services

| Service | File | Purpose |
|---------|------|---------|
| Database | `db.ts` | SQLite init, migrations, FTS5 index management |
| Source Manager | `source-manager.ts` | CRUD for IPTV sources, sync orchestration |
| M3U Parser | `m3u-parser.ts` | Parse M3U/M3U8 playlists |
| Xtream Client | `xtream-client.ts` | Xtream Codes API integration |
| Stalker Client | `stalker-client.ts` | Stalker/Ministra Portal API (planned — Sprint 11) |
| Content Classifier | `content-classifier.ts` | Classify M3U entries into live/movie/series |
| Title Cleaner | `title-cleaner.ts` | Clean provider noise from titles |
| EPG Service | `epg-service.ts` | XMLTV parsing, EPG fetch/refresh, programme queries |
| Recording Service | `recording-service.ts` | ffmpeg-based live recording + scheduler (planned — Sprint 12) |
| Download Manager | `download-manager.ts` | Queue-based VOD download manager (planned — Sprint 13) |
| Metadata Service | `metadata-service.ts` | TMDb API integration, title matching (planned — Sprint 14) |
| Subtitle Service | `subtitle-service.ts` | OpenSubtitles API, subtitle file management (planned — Sprint 15) |
| Timeshift Service | `timeshift-service.ts` | Live TV pause/rewind buffer (planned — Sprint 9) |
| Parental Service | `parental-service.ts` | PIN management, channel locking (planned — Sprint 10) |
| Settings Service | `settings-service.ts` | Key-value settings store (planned — Sprint 17) |
| Backup Service | `backup-service.ts` | Export/import user data (planned — Sprint 18) |
| Notification Service | `notification-service.ts` | In-app toasts, programme reminders (planned — Sprint 19) |

## Database Schema (SQLite)

### Core Tables

```sql
-- IPTV sources (M3U URLs, local files, Xtream credentials, Stalker portals)
CREATE TABLE sources (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  type TEXT NOT NULL CHECK(type IN ('m3u_url', 'm3u_file', 'xtream', 'stalker')),
  url TEXT,
  file_path TEXT,
  username_encrypted BLOB,
  password_encrypted BLOB,
  mac_address_encrypted BLOB,  -- Stalker Portal MAC address
  epg_url TEXT,                 -- Per-source EPG URL
  last_synced INTEGER,
  is_active INTEGER DEFAULT 1,
  sort_order INTEGER DEFAULT 0,
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
  tvg_id TEXT,                    -- EPG mapping ID
  channel_number INTEGER,         -- User-assigned channel number
  is_hidden INTEGER DEFAULT 0,    -- Hidden by user
  is_locked INTEGER DEFAULT 0,    -- Locked behind PIN
  catchup_supported INTEGER DEFAULT 0,
  catchup_days INTEGER DEFAULT 0,
  custom_group TEXT,              -- User-defined group override
  custom_name TEXT,               -- User display name override
  custom_logo_url TEXT,           -- User logo override
  metadata_json TEXT,             -- TMDb metadata cache
  sort_order INTEGER DEFAULT 0,   -- User sort order within group
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

-- User data
CREATE TABLE favorites (
  id TEXT PRIMARY KEY,
  content_id TEXT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
  added_at INTEGER NOT NULL
);

CREATE TABLE watch_history (
  id TEXT PRIMARY KEY,
  content_id TEXT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
  episode_id TEXT REFERENCES episodes(id),
  position_seconds INTEGER DEFAULT 0,
  duration_seconds INTEGER,
  watched_at INTEGER NOT NULL
);

-- EPG data
CREATE TABLE epg_programmes (
  id TEXT PRIMARY KEY,
  channel_tvg_id TEXT NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  start_time INTEGER NOT NULL,
  end_time INTEGER NOT NULL,
  category TEXT,
  icon_url TEXT
);

-- Recordings
CREATE TABLE recordings (
  id TEXT PRIMARY KEY,
  content_id TEXT REFERENCES content(id) ON DELETE SET NULL,
  title TEXT NOT NULL,
  file_path TEXT NOT NULL,
  file_size INTEGER,
  duration_seconds INTEGER,
  status TEXT NOT NULL CHECK(status IN ('scheduled', 'recording', 'completed', 'failed')),
  scheduled_start INTEGER,
  scheduled_end INTEGER,
  started_at INTEGER,
  completed_at INTEGER,
  created_at INTEGER NOT NULL
);

-- Downloads
CREATE TABLE downloads (
  id TEXT PRIMARY KEY,
  content_id TEXT REFERENCES content(id) ON DELETE SET NULL,
  episode_id TEXT REFERENCES episodes(id) ON DELETE SET NULL,
  title TEXT NOT NULL,
  stream_url TEXT NOT NULL,
  file_path TEXT NOT NULL,
  file_size INTEGER,
  progress REAL DEFAULT 0,
  status TEXT NOT NULL CHECK(status IN ('queued', 'downloading', 'paused', 'completed', 'failed')),
  created_at INTEGER NOT NULL,
  completed_at INTEGER
);

-- App settings (key-value)
CREATE TABLE settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

-- Custom channel groups
CREATE TABLE custom_groups (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  sort_order INTEGER DEFAULT 0,
  is_locked INTEGER DEFAULT 0,
  created_at INTEGER NOT NULL
);

CREATE TABLE custom_group_channels (
  group_id TEXT NOT NULL REFERENCES custom_groups(id) ON DELETE CASCADE,
  content_id TEXT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
  sort_order INTEGER DEFAULT 0,
  PRIMARY KEY (group_id, content_id)
);

-- Subtitle cache
CREATE TABLE subtitle_cache (
  id TEXT PRIMARY KEY,
  content_id TEXT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
  language TEXT NOT NULL,
  file_path TEXT NOT NULL,
  source TEXT NOT NULL CHECK(source IN ('opensubtitles', 'manual', 'extracted')),
  created_at INTEGER NOT NULL
);

-- Indexes
CREATE INDEX idx_content_source ON content(source_id);
CREATE INDEX idx_content_type ON content(type);
CREATE INDEX idx_content_group ON content(group_name);
CREATE INDEX idx_content_clean_title ON content(clean_title);
CREATE INDEX idx_content_tvg_id ON content(tvg_id);
CREATE INDEX idx_content_hidden ON content(is_hidden);
CREATE INDEX idx_epg_channel_time ON epg_programmes(channel_tvg_id, start_time);
CREATE INDEX idx_watch_history_content ON watch_history(content_id);
CREATE INDEX idx_recordings_status ON recordings(status);
CREATE INDEX idx_downloads_status ON downloads(status);
CREATE INDEX idx_subtitle_cache_content ON subtitle_cache(content_id);
```

## Content Classification Logic

### From Xtream Codes API
Straightforward — the API separates live, VOD, and series into distinct endpoints.

### From Stalker Portal API
Similar to Xtream — separate endpoints for IPTV channels, VOD, and series.

### From M3U Files
Heuristic-based:
1. **Duration check:** `-1` or `0` duration → likely live
2. **Group-title patterns:** Groups containing "VOD", "Movie", "Film" → movie. Groups containing "Series", "Episode", "Season" → series.
3. **URL patterns:** `.ts` streams → likely live. `.mp4`, `.mkv` → likely VOD.
4. **Title patterns:** Titles matching `S\d+E\d+` or `Season \d+` → series.
5. **Fallback:** Unclassified content defaults to live (most common in M3U).

## Security Model

| Concern | Mitigation |
|---------|-----------|
| Untrusted playlist data | Validate/sanitize all parsed M3U/Xtream/Stalker data with Zod before storage |
| Credential storage | Encrypt with Electron safeStorage API before writing to DB |
| IPC boundary | Typed channels, input validation on every handler, no raw ipcRenderer exposure |
| Remote content | Never load provider URLs in BrowserWindow. Streams go to mpv only |
| Renderer sandbox | `sandbox: true`, `contextIsolation: true`, `nodeIntegration: false` |
| URL handling | Validate all URLs before passing to mpv or network calls |
| Parental PIN | Store hashed (not plaintext). Rate-limit attempts |
| API keys | TMDb/OpenSubtitles keys stored via safeStorage, not in config files |

## Directory Conventions

- `src/main/services/` — One file per service, each responsible for a single domain
- `src/main/ipc/` — One file per IPC namespace (sources, player, content, epg, recordings, downloads, etc.)
- `src/renderer/pages/` — Route-level components
- `src/renderer/components/` — Reusable UI components
- `src/renderer/hooks/` — Custom React hooks (including IPC wrappers)
- `src/renderer/stores/` — Zustand store definitions
- `src/shared/types/` — TypeScript interfaces/types used by both processes
