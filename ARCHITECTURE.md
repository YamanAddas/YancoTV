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
│                                        └────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## Process Architecture

### Main Process (`src/main/`)

The main process owns all privileged operations:

- **Database access** — SQLite via better-sqlite3, synchronous queries
- **Network requests** — Fetching M3U files, Xtream API calls, EPG data
- **File system** — Reading local M3U files, saving downloads/recordings
- **Player control** — Spawning and communicating with mpv child process
- **Credential storage** — Electron safeStorage for encrypting Xtream credentials

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
      → Validates input with Zod
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

  // Events
  on(event: 'state-change', handler: (state: PlayerState) => void): void;
  on(event: 'time-update', handler: (position: number) => void): void;
  on(event: 'error', handler: (error: Error) => void): void;

  destroy(): Promise<void>;
}
```

`MpvPlayer` implements this interface. Future Android builds use `ExoPlayerAdapter` implementing the same contract.

## Database Schema (SQLite)

### Core Tables

```sql
-- IPTV sources (M3U URLs, local files, Xtream credentials)
CREATE TABLE sources (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  type TEXT NOT NULL CHECK(type IN ('m3u_url', 'm3u_file', 'xtream')),
  url TEXT,
  file_path TEXT,
  username_encrypted BLOB,
  password_encrypted BLOB,
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
  clean_title TEXT,              -- Cleaned/normalized title
  group_name TEXT,               -- Category/group from provider
  stream_url TEXT NOT NULL,
  logo_url TEXT,
  tvg_id TEXT,                   -- EPG mapping ID
  metadata_json TEXT,            -- Extra metadata (year, rating, etc.)
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
  category TEXT
);

-- Indexes
CREATE INDEX idx_content_source ON content(source_id);
CREATE INDEX idx_content_type ON content(type);
CREATE INDEX idx_content_group ON content(group_name);
CREATE INDEX idx_content_clean_title ON content(clean_title);
CREATE INDEX idx_epg_channel_time ON epg_programmes(channel_tvg_id, start_time);
CREATE INDEX idx_watch_history_content ON watch_history(content_id);
```

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

## Security Model

| Concern | Mitigation |
|---------|-----------|
| Untrusted playlist data | Validate/sanitize all parsed M3U/Xtream data with Zod before storage |
| Credential storage | Encrypt with Electron safeStorage API before writing to DB |
| IPC boundary | Typed channels, input validation on every handler, no raw ipcRenderer exposure |
| Remote content | Never load provider URLs in BrowserWindow. Streams go to mpv only. |
| Renderer sandbox | `sandbox: true`, `contextIsolation: true`, `nodeIntegration: false` |
| URL handling | Validate all URLs before passing to mpv or network calls |

## Directory Conventions

- `src/main/services/` — One file per service, each responsible for a single domain
- `src/main/ipc/` — One file per IPC namespace (sources, player, content, etc.)
- `src/renderer/pages/` — Route-level components
- `src/renderer/components/` — Reusable UI components
- `src/renderer/hooks/` — Custom React hooks (including IPC wrappers)
- `src/renderer/stores/` — Zustand store definitions
- `src/shared/types/` — TypeScript interfaces/types used by both processes
