# YancoTV

Custom IPTV media application for Windows. Built with Electron, React, TypeScript, and mpv.

## What is YancoTV?

A premium IPTV player that organizes content from M3U playlists and Xtream Codes sources into a clean, browsable interface — separating Live TV, Movies, and Series into proper sections instead of dumping everything into one list.

Built to match and surpass apps like TiviMate — but on Windows desktop.

## Current Features (Phase 1 — In Progress)

- **Source Management** — Add M3U files, M3U URLs, or Xtream Codes credentials. Encrypted credential storage.
- **Content Organization** — Automatic separation into Live TV, Movies, and Series using heuristics.
- **Smart Search** — Full-text search (SQLite FTS5) across all content types, grouped by category.
- **Browsing** — Virtualized grids for 10K+ channels. Category filtering, sorting, source switching.
- **Playback** — Stable video playback via mpv with play/pause/seek/volume controls.
- **Favorites** — Add/remove favorites with heart toggle. Dedicated Favorites page.
- **Watch History** — Track what you watch. Resume VOD from last position.
- **Keyboard Shortcuts** — Space (play/pause), arrows (seek), F (fullscreen), M (mute), Ctrl+F (search).
- **Title Cleanup** — Automatic cleaning of messy provider metadata.

## Planned Features

- EPG (Electronic Program Guide) with full grid view
- Catch-up TV and timeshift (pause/rewind live)
- Recording (live TV) and downloading (VOD)
- Parental controls (PIN lock)
- Stalker Portal support
- TMDb metadata enrichment (posters, descriptions, ratings)
- OpenSubtitles integration
- Multi-view / PIP (watch while browsing)
- Backup and restore
- System tray, auto-update, and more

See [PRODUCTION_PLAN.md](PRODUCTION_PLAN.md) for the full roadmap.

## Tech Stack

- Electron (hardened)
- React + TypeScript + Tailwind CSS
- SQLite (better-sqlite3)
- Zustand (state management)
- mpv (video playback)
- ffmpeg (recording/downloading)
- Vite (bundler)

## Development

```bash
pnpm install      # Install dependencies
pnpm dev          # Start development
pnpm build        # Production build
pnpm package      # Create Windows installer
pnpm test         # Run tests
```

## Project Status

Phase 1 — Windows Feature Complete (Sprints 1–6 done, Sprints 7–21 remaining)

## License

Private / Proprietary
