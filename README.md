# YancoTV

Custom IPTV media application for Windows. Built with Electron, React, TypeScript, and mpv.

## What is YancoTV?

A premium IPTV player that organizes content from M3U playlists and Xtream Codes sources into a clean, browsable interface — separating Live TV, Movies, and Series into proper sections instead of dumping everything into one list.

Built to match and surpass apps like TiviMate — but on Windows desktop.

## Current Features

- **Source Management** — Add M3U files, M3U URLs, or Xtream Codes credentials. Encrypted credential storage via Electron safeStorage.
- **Content Organization** — Automatic separation into Live TV, Movies, and Series using heuristics. Category grouping and filtering.
- **Smart Search** — Full-text search (SQLite FTS5) across all content types, grouped by category.
- **Browsing** — Virtualized grids for 10K+ channels. Category filtering, sorting (provider/name/recent/group), source switching.
- **Playback** — Stable video playback via mpv with full controls: play/pause/seek/volume/mute/speed/aspect ratio.
- **Player Enhancements** — Aspect ratio cycling (original/16:9/4:3/fill/fit), playback speed (0.25x–4x), subtitle and audio track selection, external subtitle loading, channel surfing (up/down).
- **EPG (Electronic Program Guide)** — Full XMLTV support with now/next display on channels, dedicated EPG grid page, auto-refresh, per-source and global EPG URLs.
- **Catch-Up TV** — Watch past programmes on supported channels. Xtream timeshift and M3U catchup pattern support.
- **Timeshift** — Pause and rewind live TV. Go-live button to jump back to real-time.
- **Parental Controls** — PIN lock (SHA-256 hashed). Lock or hide individual channels. Channel name, logo, and group overrides.
- **Favorites** — Add/remove favorites with heart toggle. Dedicated Favorites page.
- **Watch History** — Track what you watch. Resume VOD from last position.
- **Settings** — Organized settings page with 8 tabs: General, Playback, Network, Playlist, EPG, Parental, Shortcuts, About.
- **Keyboard Navigation** — Full keyboard support across all views. Space (play/pause), arrows (seek/navigate), F (fullscreen), M (mute), Ctrl+F (search).

## Planned Features

- Stalker Portal support (MAC-based IPTV providers)
- Recording (live TV to local storage, scheduled from EPG)
- Downloading (VOD content for offline playback)
- TMDb metadata enrichment (posters, descriptions, ratings, cast)
- OpenSubtitles integration (auto-search, download, apply)
- Multi-view / PIP (watch while browsing, split-screen)
- Backup and restore (export/import all user data)
- System tray, auto-update, notifications
- Gamepad support

See [PRODUCTION_PLAN.md](PRODUCTION_PLAN.md) for the full roadmap.

## Tech Stack

- Electron 41+ (hardened)
- React 18 + TypeScript 5 + Tailwind CSS 3
- SQLite (better-sqlite3, WAL mode, FTS5)
- Zustand 5 (state management)
- React Query 5 (async data caching)
- mpv (video playback via JSON-RPC over named pipes)
- Vite 6 (bundler)
- Vitest (unit testing)
- Zod (validation)

## Development

```bash
pnpm install      # Install dependencies
pnpm dev          # Start development (Vite HMR + Electron)
pnpm build        # Production build
pnpm package      # Create Windows installer (NSIS + portable)
pnpm test         # Run unit tests
pnpm lint         # Lint all files
```

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for process architecture, data flow, IPC design, and database schema.

## Project Status

Phase 1 — Windows Feature Complete
- Sprints 1–10 done (foundation through parental controls + settings)
- Sprints 11–20 remaining (Stalker, recording, downloads, metadata, subtitles, multi-view, etc.)
- Phase 2: Stabilization & Release
- Phase 3: UI Redesign
- Phase 4: Android TV
- Phase 5: AI Features

## License

Private / Proprietary
