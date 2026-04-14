# YancoTV

Custom IPTV media application for Windows. Built with Electron, React, TypeScript, and mpv.

## What is YancoTV?

A premium IPTV player that organizes content from M3U playlists and Xtream Codes sources into a clean, browsable interface — separating Live TV, Movies, and Series into proper sections instead of dumping everything into one list.

## Features

- **Source Management** — Add M3U files, M3U URLs, or Xtream Codes credentials
- **Content Organization** — Automatic separation into Live TV, Movies, and Series
- **Smart Search** — Search across all content types
- **EPG Support** — Electronic Program Guide for live TV channels
- **Title Cleanup** — Automatic cleaning of messy provider metadata
- **Playback** — Stable video playback via mpv
- **Favorites & History** — Track what you watch and save what you like
- **Subtitles** — External subtitle loading + OpenSubtitles integration (EN/AR)
- **Download & Record** — Save VOD content and record live streams

## Tech Stack

- Electron (hardened)
- React + TypeScript + Tailwind CSS
- SQLite (better-sqlite3)
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

Phase 1 — Windows MVP (in progress)

## License

Private / Proprietary
