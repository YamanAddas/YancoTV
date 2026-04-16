# YancoTV — Production Plan

## Guiding Principles

1. **Ship working software early.** Every sprint should produce something runnable.
2. **Security is not optional.** Electron hardening is baked in from the first commit.
3. **Abstraction where it earns its keep.** Player interface yes. Factory-pattern-everything no.
4. **IPTV data is messy. Accept it.** Build robust parsers, don't assume clean input.
5. **Desktop and TV are different products.** Shared logic, separate UIs.
6. **Beat TiviMate on desktop.** Downloads, metadata, keyboard UX, multi-view — things Android IPTV players can't do well.

---

## Phase 1 — Windows Feature Complete

**Goal:** Build every feature needed to match and surpass TiviMate, OTT Navigator, and Smarters — on Windows desktop.

### Sprints 1–10 — COMPLETED

| Sprint | What Was Shipped |
|--------|-----------------|
| 1 — Foundation | Hardened Electron, React, Vite, SQLite, Tailwind dark theme |
| 2 — Source Management | M3U URL/file parsing, credential encryption, source CRUD |
| 3 — Xtream & Classification | Xtream Codes API, content classifier, title cleaner, series grouping |
| 4 — Browsing UI | Sidebar nav, Live TV / Movies / Series pages, virtualized grids, category filtering |
| 5 — Playback | IPlayer abstraction, MpvPlayer, controls UI, keyboard shortcuts, mpv bundling |
| 6 — Search, Favorites & History | FTS5 search, favorites toggle, watch history, resume playback |
| 7 — EPG | XMLTV parser (async/streaming/gzip), now/next display, EPG grid page, auto-refresh, per-source + global EPG URLs, EPG settings UI |
| 8 — Player Enhancements | Aspect ratio cycling, playback speed, subtitle/audio track selection, external subtitle loading, channel up/down surfing, player overlay controls |
| 9 — Catch-Up & Timeshift | Catch-up detection (Xtream + M3U), catch-up URL builder, timeshift service (pause/rewind live TV), timeshift controls |
| 10 — Parental Controls | PIN setup (SHA-256 hashed), channel lock/hide, channel name/logo/group overrides, parental settings UI, settings persistence |

**Also shipped alongside Sprints 7–10:** Full keyboard navigation across all menus, category grouping, channel artwork, redesigned Live TV list/grid views with hex capsule rows, 8-tab settings page (General, Playback, Network, Playlist, EPG, Parental, Shortcuts, About), settings key-value store, 19 unit test files.

---

### Sprint 11 — Source Management Enhancements

**Objective:** Stalker Portal support, source editing, and sync UX.

| # | Task | Details |
|---|------|---------|
| 11.1 | Stalker Portal client | MAC-based authentication. Fetch categories, channels, VOD, series via Stalker/Ministra API |
| 11.2 | Stalker response mapping | Map Stalker JSON to unified content types (same as M3U/Xtream) |
| 11.3 | Source editing | Edit existing source: rename, update URL/credentials, change EPG URL |
| 11.4 | Source sync progress UI | Real-time progress bar during sync: "Fetching channels… 1,200 / 5,000" |
| 11.5 | Source re-sync | Manual re-sync button per source. Auto-sync on configurable interval |
| 11.6 | Multi-source merge view | "All Sources" view that merges content across sources, with dedup by stream URL |
| 11.7 | Source priority ordering | Drag-and-drop source order. Higher priority source wins during dedup |
| 11.8 | Source health indicator | Show source status: last sync time, channel count, errors during sync |
| 11.9 | Source type in add form | Add Stalker Portal option to the "Add Source" form (alongside M3U URL, M3U file, Xtream) |

**Deliverable:** All 3 major IPTV protocols supported (M3U, Xtream, Stalker). Sources editable. Sync shows progress.

---

### Sprint 12 — Recording

**Objective:** Record live TV to local storage, with scheduled recording from EPG.

| # | Task | Details |
|---|------|---------|
| 12.1 | Recording service | ffmpeg-based: capture live stream to local MP4/TS file. Start/stop/status tracking |
| 12.2 | Record button in player | "Record" toggle button in live TV player. Red indicator while recording |
| 12.3 | Recording manager page | New page: list all recordings with title, date, duration, size. Play or delete |
| 12.4 | Scheduled recording | From EPG: select a future programme → schedule recording. Stores job in DB |
| 12.5 | Recording scheduler service | Background service that starts/stops recordings at scheduled times |
| 12.6 | Active recording indicator | System tray notification + sidebar badge while recording is active |
| 12.7 | Recording settings | Default storage path, file format (MP4/TS), max concurrent recordings |
| 12.8 | Storage usage display | Show total recording storage used, free disk space |
| 12.9 | Recording nav item | Add "Recordings" to sidebar navigation |

**Deliverable:** Users can record live TV on-demand or schedule from EPG. Recordings browsable and playable.

---

### Sprint 13 — Downloading

**Objective:** Download VOD content for offline playback.

| # | Task | Details |
|---|------|---------|
| 13.1 | Download manager service | Queue-based: add downloads, track progress, handle failures, pause/resume |
| 13.2 | ffmpeg download integration | Download HTTP/HLS/DASH streams to local MP4 via ffmpeg. Progress tracking via ffmpeg output parsing |
| 13.3 | Download button on content | Download icon on movie/episode cards and detail views |
| 13.4 | Download queue page | New page: list queued/active/completed downloads with progress bars, speed, ETA |
| 13.5 | Resume interrupted downloads | Detect partial files, resume from where ffmpeg left off |
| 13.6 | Local library | Browse downloaded content. Play locally without internet. Show file size and date |
| 13.7 | Download settings | Default save directory, concurrent download limit (1–5), preferred quality |
| 13.8 | Download nav item | Add "Downloads" to sidebar navigation |
| 13.9 | Storage management | Delete downloaded files, show storage usage |

**Deliverable:** Users can download movies/episodes for offline playback. Queue with progress tracking.

---

### Sprint 14 — Metadata Enrichment

**Objective:** TMDb integration for rich movie/series info — posters, descriptions, ratings.

| # | Task | Details |
|---|------|---------|
| 14.1 | TMDb API client | Search movies/shows by cleaned title + optional year. Fetch details, images, cast, genres |
| 14.2 | Metadata matching engine | Fuzzy title matching with confidence scoring. Auto-match above threshold, flag low-confidence for review |
| 14.3 | Poster display | Replace provider logos with TMDb posters where matched. Graceful fallback to logo |
| 14.4 | Content detail page | New page for movies/shows: poster, backdrop, description, genres, rating, year, cast, episodes |
| 14.5 | Manual match correction | "Wrong match? Search again" — user can search TMDb manually and link correct entry |
| 14.6 | Metadata cache | Cache TMDb results in `metadata_json` column. Avoid repeated API calls |
| 14.7 | Batch matching | Background job: auto-match unmatched movies/series after source sync. Rate-limited |
| 14.8 | TMDb API key config | Settings field for user's TMDb API key |

**Deliverable:** Movies and series show rich metadata — posters, descriptions, ratings, genres, cast info.

---

### Sprint 15 — Subtitles (Advanced)

**Objective:** OpenSubtitles integration and subtitle preference management.

| # | Task | Details |
|---|------|---------|
| 15.1 | OpenSubtitles API client | Search subtitles by title, year, season/episode, language. Download subtitle files |
| 15.2 | Auto-search on playback | When a movie/episode starts, auto-search for subtitles in preferred language |
| 15.3 | Subtitle search UI | "Search subtitles" button in player → results list → download + apply |
| 15.4 | Subtitle preferences | Default subtitle language(s) (e.g. EN, AR). Auto-apply setting. Subtitle directory |
| 15.5 | Subtitle cache | Cache downloaded subtitles locally, mapped to content ID. Re-use on replay |
| 15.6 | Embedded subtitle extraction | Extract embedded subs from streams via ffmpeg when available |

**Deliverable:** Subtitles auto-searched and applied. Users can search/download from OpenSubtitles. Preferences remembered.

---

### Sprint 16 — Multi-View & PIP

**Objective:** Watch multiple channels simultaneously — desktop advantage over mobile IPTV apps.

| # | Task | Details |
|---|------|---------|
| 16.1 | PIP mode | Minimize current playback to floating corner window. Browse/search while watching |
| 16.2 | PIP controls | Resize, reposition, close PIP. Click to expand back to full player |
| 16.3 | Split-screen 2-up | Side-by-side two channels. Independent audio selection (which channel's audio plays) |
| 16.4 | Split-screen 4-up | 2x2 grid of four channels. Click one to select audio source |
| 16.5 | Multi-view layout picker | UI to select layout: single, PIP, 2-split, 4-grid |
| 16.6 | Quick-add to multi-view | "Add to multi-view" option on channel items |

**Deliverable:** PIP mode for browsing while watching. Split-screen for 2 or 4 simultaneous channels.

---

### Sprint 17 — Settings & Configuration (PARTIALLY COMPLETE)

**Objective:** Comprehensive settings page covering all configurable aspects of the app.

**Already done (shipped with Sprints 7–10):**
- 17.1 Settings architecture — Key-value store in SQLite (`settings-service.ts`), typed getter/setter IPC (`settings:*`), Zustand store (`settings-store.ts`)
- 17.2 General settings — `GeneralSettings.tsx` component
- 17.3 Playback settings — `PlaybackSettings.tsx` component
- 17.4 EPG settings — `EpgSettings.tsx` component
- 17.8 Parental settings — `ParentalSettings.tsx` component
- 17.9 Network settings — `NetworkSettings.tsx` component
- 17.11 Settings persistence — Settings persisted via SQLite key-value store
- Settings page with 8 tabs (General, Playback, Network, Playlist, EPG, Parental, Shortcuts, About)

**Remaining tasks:**

| # | Task | Details |
|---|------|---------|
| 17.5 | Recording settings | Storage path, file format, max concurrent recordings (depends on Sprint 12) |
| 17.6 | Download settings | Save directory, concurrent downloads, preferred quality (depends on Sprint 13) |
| 17.7 | Subtitle settings | Default languages, auto-search toggle, OpenSubtitles API key, appearance (depends on Sprint 15) |
| 17.10 | Advanced settings | mpv path override, data directory, debug logging toggle, hardware acceleration |

**Deliverable:** All app configuration accessible from one organized settings page. Persisted across restarts.

---

### Sprint 18 — System Features

**Objective:** Desktop integration, backup/restore, auto-update, and logging.

| # | Task | Details |
|---|------|---------|
| 18.1 | System tray | Minimize to system tray. Tray icon with context menu (show, play/pause, quit) |
| 18.2 | Launch on startup | Option to start YancoTV when Windows boots. Registry entry via Electron |
| 18.3 | Auto-update | electron-updater: check for updates, download, prompt to install. Update channel setting |
| 18.4 | Backup export | Export all user data (sources, favorites, history, settings, channel customizations) to JSON/zip file |
| 18.5 | Backup import/restore | Import backup file → restore all data. Merge or replace options |
| 18.6 | Structured logging | electron-log: info/warn/error with timestamps, service context. Configurable level |
| 18.7 | Log export | "Export logs" button in settings → save log file for debugging |
| 18.8 | Crash handling | Catch unhandled errors in main + renderer. Log them. Show user-friendly error dialog |
| 18.9 | About dialog | App version, build info, credits, license. Check-for-updates button |
| 18.10 | App icon & branding | Custom app icon (taskbar, installer, about), window title |

**Deliverable:** YancoTV behaves like a proper Windows app — tray icon, auto-update, backup/restore, logging.

---

### Sprint 19 — Search, Channel UX & Notifications

**Objective:** Enhanced search and quality-of-life channel features.

| # | Task | Details |
|---|------|---------|
| 19.1 | Search type filter | Filter search results by type (live/movie/series) via toggle buttons |
| 19.2 | Search autocomplete | Suggest titles as user types, based on existing content |
| 19.3 | Search history | Track recent searches, show as suggestions in empty search state |
| 19.4 | Channel zapping | Quick preview: arrow through channels with 2-second preview before committing |
| 19.5 | Last channel recall | Keyboard shortcut to jump back to previously watched channel |
| 19.6 | Auto-play on launch | Setting: auto-play last watched channel when app starts |
| 19.7 | Recent channels strip | Quick-access bar of last 5–10 channels on Live TV page |
| 19.8 | Programme reminders | Set reminder on an EPG programme → notification + optional auto-tune when it starts |
| 19.9 | Notification system | In-app notification toasts for: reminders, recording started/finished, download complete, sync done |

**Deliverable:** Search is smarter with filters and autocomplete. Channel surfing is faster. Notifications keep users informed.

---

### Sprint 20 — Network Configuration & Keyboard Navigation (PARTIALLY COMPLETE)

**Objective:** Stream reliability settings and full keyboard accessibility.

**Already done (shipped with Sprints 7–10):**
- 20.6 Full keyboard navigation — Arrow/Tab/Enter navigation across all views with visible focus indicators
- Shortcuts reference page (`ShortcutsSettings.tsx`)
- Network settings UI (`NetworkSettings.tsx`)

**Remaining tasks:**

| # | Task | Details |
|---|------|---------|
| 20.1 | Buffer size config | Adjustable buffer: low latency (live sports) vs. stability (slow connections). Maps to mpv `cache-secs` |
| 20.2 | Stream timeout | Configurable timeout before showing error. Default 15s |
| 20.3 | Auto-reconnect | On stream drop: auto-retry with exponential backoff (1s, 2s, 4s, max 30s). Reconnect indicator |
| 20.4 | User-Agent override | Per-source custom User-Agent string. Some providers require specific UA |
| 20.5 | Proxy support | HTTP/SOCKS5 proxy config in settings. Applied to all network requests + mpv streams |
| 20.7 | Customizable shortcuts | Settings page for rebinding keyboard shortcuts. Default presets |
| 20.8 | Gamepad support | Basic D-pad/gamepad input mapping (prep for future TV mode) |

**Deliverable:** Stream reliability configurable. Full keyboard/gamepad navigation across entire app.

---

## Phase 2 — Stabilization & Release

**Goal:** Harden everything, fix bugs, optimize performance, prepare for daily use.

### Sprint 21 — Stabilization

| # | Task | Details |
|---|------|---------|
| 21.1 | Error handling audit | Review all IPC handlers, parsers, network calls, player operations for proper error handling |
| 21.2 | Performance profiling | Profile renderer: fix unnecessary re-renders, optimize virtualized lists with 50K+ items |
| 21.3 | Memory leak detection | Monitor main + renderer memory over extended use. Fix leaks in player, EPG refresh, download manager |
| 21.4 | E2E tests | Playwright tests for critical flows: add source → browse → play → record → download |
| 21.5 | Unit test coverage | Add tests for new services: EPG parser, catch-up, recording, download manager, Stalker client |
| 21.6 | Manual testing pass | Full test with multiple real IPTV sources (M3U, Xtream, Stalker). Edge cases and error recovery |
| 21.7 | Windows installer polish | electron-builder NSIS installer: desktop shortcut, start menu, uninstaller, file associations |
| 21.8 | Portable build | Standalone .exe that runs without installation. Verify settings/DB portability |
| 21.9 | Security audit | Review CSP, IPC validation, credential storage, URL handling. Pen-test the preload bridge |
| 21.10 | Release prep | Version bumping, changelog, build pipeline verification, code signing (if available) |

**Deliverable:** Stable, tested, installable Windows app. Ready for daily use.

---

## Phase 3 — UI Redesign

**Goal:** Fresh visual design pass after all features are built and stable.

This phase is user-driven. All functionality will be complete from Phase 1. The redesign focuses on:

- Visual refresh (new color palette, spacing, typography)
- Refined page layouts with all features integrated
- Animations and transitions
- Responsive layouts for different window sizes
- Accessibility polish (contrast, focus rings, screen reader labels)

No new backend work — purely frontend/visual.

---

## Phase 4 — Cross-Platform

**Goal:** Bring YancoTV to Android TV, then other platforms.

### Sprint 22 — Android TV App

| # | Task | Details |
|---|------|---------|
| 22.1 | Android project setup | Kotlin, Jetpack Compose for TV, ExoPlayer |
| 22.2 | Port parsing logic | Reimplement M3U parser, Xtream client, Stalker client in Kotlin (or shared KMP module) |
| 22.3 | Room database | SQLite schema ported to Room, same structure |
| 22.4 | TV navigation | D-pad focus management, leanback-style browsing |
| 22.5 | ExoPlayer integration | Implement IPlayer contract with ExoPlayer |
| 22.6 | TV-optimized UI | 10-foot UI, large text, focus indicators, remote-friendly |
| 22.7 | Firestick optimization | Performance tuning for lower-powered devices |
| 22.8 | APK build pipeline | Gradle build, signed APK output, sideload-ready |

---

## Phase 5 — Intelligence

**Goal:** Auto-generated subtitles and advanced content matching.

### Sprint 23 — AI Features

| # | Task | Details |
|---|------|---------|
| 23.1 | Whisper integration | Local Whisper model or API for speech-to-text on VOD content |
| 23.2 | Auto-subtitle generation | Generate English subtitles from audio track |
| 23.3 | Translation | Translate generated subtitles EN <-> AR |
| 23.4 | Advanced content matching | ML-based title matching for better metadata accuracy |
| 23.5 | Smart categorization | Auto-detect miscategorized content, suggest corrections |

---

## Milestone Summary

| Milestone | What You Get | Sprints | Status |
|-----------|-------------|---------|--------|
| **M1 — Skeleton** | App launches, navigates, DB works | Sprint 1 | DONE |
| **M2 — Sources** | Add M3U/Xtream sources, content parsed | Sprints 2–3 | DONE |
| **M3 — Browse** | Full browsing UI with categories | Sprint 4 | DONE |
| **M4 — Watch** | Playback works for all content types | Sprint 5 | DONE |
| **M5 — Daily Use** | Search, favorites, history | Sprint 6 | DONE |
| **M6 — Guide** | EPG grid, now/next, enhanced player | Sprints 7–8 | DONE |
| **M7 — Power Features** | Catch-up, timeshift, parental controls, channel management | Sprints 9–10 | DONE |
| **M8 — All Sources** | Stalker Portal, source editing, sync progress | Sprint 11 | — |
| **M9 — Media Manager** | Recording + downloading = full media management | Sprints 12–13 | — |
| **M10 — Premium** | Rich metadata, subtitles, multi-view | Sprints 14–16 | — |
| **M11 — Polished** | Settings, system features, search UX, network config | Sprints 17–20 | Partial |
| **M12 — Ship It** | Stabilized, tested, release-ready | Sprint 21 | — |
| **M13 — Redesigned** | Fresh UI with all features | Phase 3 | — |
| **M14 — TV** | Android TV app | Phase 4 | — |
| **M15 — Smart** | AI subtitles, content matching | Phase 5 | — |

---

## Feature Comparison Target

| Feature | TiviMate | YancoTV Target |
|---------|----------|----------------|
| Platforms | Android TV only | Windows + Android TV (Phase 4) |
| Source types | M3U, Xtream, Stalker | M3U, Xtream, Stalker |
| EPG | Full grid + now/next | Full grid + now/next |
| Catch-up | Yes | Yes |
| Timeshift | Yes | Yes |
| Recording | Yes + scheduled | Yes + scheduled from EPG |
| Multi-view | Up to 9 | PIP + 2/4 split |
| Parental controls | PIN lock | PIN lock + hidden channels |
| Downloads | No | **Yes — VOD download manager** |
| Metadata | Provider-only | **TMDb enrichment — posters, ratings, cast** |
| Subtitles | Basic | **OpenSubtitles + auto-search + appearance config** |
| Search | Basic | **FTS5 + autocomplete + filters + history** |
| Backup | Yes | Yes — export/import JSON |
| Auto-update | Play Store | electron-updater |
| Keyboard/Desktop UX | Remote-only | **Full keyboard + gamepad + mouse** |
| Appearance | Themes + fonts | Themes + fonts + layouts |

**Bold** = areas where YancoTV will surpass TiviMate.

---

## Risk Mitigation

| Risk | Mitigation |
|------|-----------|
| mpv embedding is tricky on Windows | Already working (Sprint 5). Fallback: separate mpv window |
| Large playlists (50K+ entries) | Streaming M3U parser, virtualized lists, SQLite FTS5, paginated IPC |
| Xtream API inconsistencies | Defensive parsing with Zod, graceful handling of missing fields |
| Stalker Portal API variations | Research multiple Stalker implementations, build flexible response mapping |
| Timeshift buffer disk usage | Configurable buffer size, auto-cleanup, storage warnings |
| TMDb rate limiting | Cache aggressively, batch requests, respect rate limits, queue background matching |
| ffmpeg recording reliability | Monitor ffmpeg process health, auto-restart on crash, validate output files |
| Multi-view performance | Limit to 4 simultaneous streams, monitor CPU/GPU/memory, quality auto-adjust |
| Provider metadata is garbage | Title cleaner handles 80% of cases. TMDb matching handles the rest |
| Electron app size | Tree-shake renderer, lazy-load pages. mpv + ffmpeg add ~100MB — acceptable |
| Security vulnerabilities | Hardened from day one. No shortcuts. Security audit in Sprint 21 |

---

## Decision Log

| Decision | Rationale | Date |
|----------|-----------|------|
| Electron + React for Windows | Fast iteration, rich UI, large ecosystem. Desktop-first. | 2026-04-14 |
| mpv for playback | Handles every stream format. Battle-tested. Native performance. | 2026-04-14 |
| SQLite for storage | Zero-config, embedded, fast. No server needed. | 2026-04-14 |
| Player abstraction (IPlayer) | Decouple from mpv for future Android ExoPlayer port. | 2026-04-14 |
| Separate Android TV client | TV UX is fundamentally different. Shared logic, separate frontend. | 2026-04-14 |
| Zustand over Redux | Simpler API, less boilerplate, sufficient for this app's state needs. | 2026-04-14 |
| Vite over Webpack | Faster dev server, simpler config, native ESM support. | 2026-04-14 |
| pnpm over npm/yarn | Faster installs, strict dependency resolution, disk efficient. | 2026-04-14 |
| Build all features before redesign | Functionality first, then visual polish. Avoids rework. | 2026-04-14 |
| Beat TiviMate on desktop-specific features | Downloads, metadata, keyboard UX, PIP — things Android can't do well. | 2026-04-14 |
