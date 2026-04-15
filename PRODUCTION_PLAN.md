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

### Sprints 1–6 — COMPLETED

| Sprint | What Was Shipped |
|--------|-----------------|
| 1 — Foundation | Hardened Electron, React, Vite, SQLite, Tailwind dark theme |
| 2 — Source Management | M3U URL/file parsing, credential encryption, source CRUD |
| 3 — Xtream & Classification | Xtream Codes API, content classifier, title cleaner, series grouping |
| 4 — Browsing UI | Sidebar nav, Live TV / Movies / Series pages, virtualized grids, category filtering |
| 5 — Playback | IPlayer abstraction, MpvPlayer, controls UI, keyboard shortcuts, mpv bundling |
| 6 — Search, Favorites & History | FTS5 search, favorites toggle, watch history, resume playback |

---

### Sprint 7 — EPG (Electronic Program Guide)

**Objective:** Full programme guide for live TV — the single biggest missing feature.

| # | Task | Details |
|---|------|---------|
| 7.1 | XMLTV parser | Parse XMLTV/gzip format: channels, programmes, start/stop times, titles, descriptions, categories, icons |
| 7.2 | EPG data service | Fetch EPG from URL (configured per source or globally), decompress gzip, store in `epg_programmes` table |
| 7.3 | EPG ↔ channel mapping | Match EPG channels to live TV content via `tvg-id` field |
| 7.4 | Now/Next display | Show current + next programme info on Live TV channel grid items and channel list |
| 7.5 | EPG grid view | Full programme guide page: horizontal time axis, vertical channel list, scrollable in both directions |
| 7.6 | EPG detail popup | Click a programme cell → show title, description, start/end time, category, duration |
| 7.7 | EPG auto-refresh | Background refresh on configurable interval (default 12h). Show last update time in settings |
| 7.8 | EPG settings UI | Add EPG URL field to source config. Global EPG URL fallback. Refresh interval selector |
| 7.9 | Multiple EPG sources | Support one EPG URL per source + a global fallback EPG URL. Merge data from all sources |
| 7.10 | EPG IPC + channels | `epg:getCurrent`, `epg:getGuide`, `epg:refresh`, `epg:getForChannel` IPC handlers |

**Deliverable:** Live TV shows "now/next" on every mapped channel. Full EPG grid available as a dedicated page. Auto-refreshes.

---

### Sprint 8 — Player Enhancements

**Objective:** Bring player controls up to par with TiviMate — aspect ratio, speed, track selection, channel surfing.

| # | Task | Details |
|---|------|---------|
| 8.1 | Aspect ratio toggle | Cycle through: original, 16:9, 4:3, fill, fit. Send `video-aspect-override` to mpv |
| 8.2 | Playback speed control | 0.25x–4x speed. Send `speed` property to mpv. Show current speed indicator |
| 8.3 | Mute button UI | Mute/unmute toggle button in player overlay (keyboard M already works) |
| 8.4 | Fullscreen button UI | Fullscreen toggle button in player overlay (keyboard F already works) |
| 8.5 | Subtitle track selector | Panel listing available subtitle tracks. Select/disable. Wire to `IPlayer.setSubtitleTrack()` |
| 8.6 | Audio track selector | Panel listing available audio tracks. Select track. Wire to `IPlayer.setAudioTrack()` |
| 8.7 | External subtitle loading | "Load subtitle file" button → file picker → `.srt`, `.vtt`, `.ass` → `IPlayer.addSubtitleFile()` |
| 8.8 | Subtitle appearance | Font size, color, background opacity settings. Send as mpv `sub-*` properties |
| 8.9 | Channel up/down | Next/previous channel in current filtered list. Page Up / Page Down keys |
| 8.10 | Channel info overlay | Press Enter/OK during playback → show channel name, logo, now/next EPG info (auto-hide after 5s) |
| 8.11 | Volume OSD | Visual volume indicator on screen when adjusting (auto-fade) |

**Deliverable:** Full player controls matching desktop media players. Subtitle/audio track switching works. Channel surfing with up/down keys.

---

### Sprint 9 — Catch-Up & Timeshift

**Objective:** Watch past programmes and pause/rewind live TV.

| # | Task | Details |
|---|------|---------|
| 9.1 | Catch-up detection | Parse Xtream `timeshift` field and M3U `catchup` / `catchup-days` tags. Flag channels that support catch-up |
| 9.2 | Catch-up URL builder | Build catch-up stream URLs based on provider format (Xtream API `timeshift.php`, append-style, shift-style) |
| 9.3 | Catch-up playback | Click a past programme in EPG → play catch-up stream. Seek within the programme |
| 9.4 | Catch-up indicator | Visual badge on channels that support catch-up. "Watch from start" option on currently airing shows |
| 9.5 | Catch-up in EPG grid | Past programmes on catch-up channels are clickable (non-catch-up channels grey out past items) |
| 9.6 | Timeshift service | Local live stream buffering via ffmpeg. Configurable buffer duration (default 30 min) |
| 9.7 | Timeshift controls | Pause, rewind, fast-forward live TV. "Go live" button to jump back to real-time |
| 9.8 | Timeshift buffer config | Settings: enable/disable timeshift, buffer duration, buffer storage path |

**Deliverable:** Users can watch past programmes on supported channels. Can pause and rewind live TV.

---

### Sprint 10 — Parental Controls & Channel Management

**Objective:** PIN protection and user control over channel organization.

| # | Task | Details |
|---|------|---------|
| 10.1 | PIN setup | Settings: set/change/remove 4-digit PIN. Store hashed in DB (not plaintext) |
| 10.2 | App lock | Optional: require PIN on app launch |
| 10.3 | Category lock | Lock entire categories/groups behind PIN. PIN prompt when accessing locked category |
| 10.4 | Channel lock | Lock individual channels behind PIN |
| 10.5 | Hidden channels | Hide channels/groups from browse views entirely (different from locked — invisible, not just gated) |
| 10.6 | Custom channel groups | Create user-defined groups. Drag channels into custom groups |
| 10.7 | Manual channel sort | Reorder channels within a group by drag-and-drop or move up/down |
| 10.8 | Channel numbers | Assign numbers to channels. Quick-tune by typing number |
| 10.9 | Channel name/logo override | Edit channel display name or override logo URL per channel |
| 10.10 | Parental controls IPC | `parental:verifyPin`, `parental:setPin`, `parental:lockChannel`, `parental:hideChannel` |

**Deliverable:** Parents can lock content behind PIN. Users can customize channel order, groups, names, and visibility.

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

### Sprint 17 — Settings & Configuration

**Objective:** Comprehensive settings page covering all configurable aspects of the app.

| # | Task | Details |
|---|------|---------|
| 17.1 | Settings architecture | Key-value settings store in SQLite. Typed getter/setter IPC. Settings Zustand store |
| 17.2 | General settings | Default source, language/locale, start page preference |
| 17.3 | Playback settings | Default volume, resume threshold, auto-play, buffer size, aspect ratio default |
| 17.4 | EPG settings | Global EPG URL, refresh interval, guide days to show |
| 17.5 | Recording settings | Storage path, file format, max concurrent recordings |
| 17.6 | Download settings | Save directory, concurrent downloads, preferred quality |
| 17.7 | Subtitle settings | Default languages, auto-search toggle, OpenSubtitles API key, appearance |
| 17.8 | Parental settings | PIN management (already built in Sprint 10, surface here) |
| 17.9 | Network settings | Proxy config, User-Agent override, connection timeout, retry count |
| 17.10 | Advanced settings | mpv path override, data directory, debug logging toggle, hardware acceleration |
| 17.11 | Settings persistence | Remember window size/position, last viewed page, sidebar state |

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

### Sprint 20 — Network Configuration & Keyboard Navigation

**Objective:** Stream reliability settings and full keyboard accessibility.

| # | Task | Details |
|---|------|---------|
| 20.1 | Buffer size config | Adjustable buffer: low latency (live sports) vs. stability (slow connections). Maps to mpv `cache-secs` |
| 20.2 | Stream timeout | Configurable timeout before showing error. Default 15s |
| 20.3 | Auto-reconnect | On stream drop: auto-retry with exponential backoff (1s, 2s, 4s, max 30s). Reconnect indicator |
| 20.4 | User-Agent override | Per-source custom User-Agent string. Some providers require specific UA |
| 20.5 | Proxy support | HTTP/SOCKS5 proxy config in settings. Applied to all network requests + mpv streams |
| 20.6 | Full keyboard navigation | Arrow/Tab/Enter navigation across all views. Visible focus indicators |
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

| Milestone | What You Get | Sprints |
|-----------|-------------|---------|
| **M1 — Skeleton** | App launches, navigates, DB works | Sprint 1 |
| **M2 — Sources** | Add M3U/Xtream sources, content parsed | Sprints 2–3 |
| **M3 — Browse** | Full browsing UI with categories | Sprint 4 |
| **M4 — Watch** | Playback works for all content types | Sprint 5 |
| **M5 — Daily Use** | Search, favorites, history | Sprint 6 |
| **M6 — Guide** | EPG grid, now/next, enhanced player | Sprints 7–8 |
| **M7 — Power Features** | Catch-up, timeshift, parental controls, channel management | Sprints 9–10 |
| **M8 — All Sources** | Stalker Portal, source editing, sync progress | Sprint 11 |
| **M9 — Media Manager** | Recording + downloading = full media management | Sprints 12–13 |
| **M10 — Premium** | Rich metadata, subtitles, multi-view | Sprints 14–16 |
| **M11 — Polished** | Settings, system features, search UX, network config | Sprints 17–20 |
| **M12 — Ship It** | Stabilized, tested, release-ready | Sprint 21 |
| **M13 — Redesigned** | Fresh UI with all features | Phase 3 |
| **M14 — TV** | Android TV app | Phase 4 |
| **M15 — Smart** | AI subtitles, content matching | Phase 5 |

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
