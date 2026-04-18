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

### Sprints 1–14 — COMPLETED

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
| 11 — Source Management Enhancements | Stalker Portal client (MAC auth, paginated fetch), source editing (inline rename/URL/credentials), drag-and-drop priority reorder, multi-source merge with dedup by stream_url, source health indicators (channel count, sync errors), auto-sync timer, migration 007 |
| 11B — Content Detail Pages | Cinematic hero layout (backdrop blur, hex poster, metadata badges), tab bar (Episodes/Info/Related), season selector, episode cards with progress, InfoTab, RelatedTab HexCard carousels, route wiring for /movies/:id and /series/:id, Motion animations |
| 12 — Recording | ffmpeg-based live recording (start/stop/status), record button in player, recordings page, scheduled recording from EPG, scheduler service, system tray indicator, recording settings, storage usage |
| 13 — Downloading | Download service with progress-aware retry (UND_ERR_SOCKET recovery), asset bundling (poster/backdrop/.nfo/subtitles), nfo-writer (Kodi-compatible XML), subtitle-extractor (ffmpeg embedded track extraction), DownloadsPage UI, download IPC wiring, migration 010 |
| 14 — Metadata Enrichment | TMDb v3 API client (search, details, cast, images, self-throttled), SQLite cache (migration 011), enrichment service merges TMDb onto ContentMetadata, encrypted API key via safeStorage, MetadataSettings tab, wired into content:getDetail IPC |

**Also shipped:** Full keyboard navigation, category grouping, channel artwork, redesigned Live TV hex rows, 8-tab settings page, settings persistence, mpv live vs VOD cache split with auto-reconnect, 38 unit test files (626 tests).

---

### Sprint 11B — Content Detail Pages — DONE

**Objective:** Replace direct-play-on-click with a cinematic detail page for movies and series. Display all available metadata (plot, cast, genre, rating, episodes) from Xtream/Stalker/M3U sources. Design: hybrid of Netflix/Max hero layout + Disney+ conditional tabs + YancoTV hex identity.

#### Design Specification

**Page type:** Full scroll page at `/movies/:id` and `/series/:id`. Shared `ContentDetailPage` component handles both.

**Section 1 — Cinematic Hero (top ~45% of viewport)**

- Full-width backdrop: poster image scaled to cover, blurred (20px), darkened to 30% opacity
- If no poster: solid gradient (surface-950 → surface-900) with faint hex grid pattern overlay
- Bottom gradient overlay: transparent → surface-950 over the bottom 60% of the backdrop
- Top-left: ghost "← Back to Movies/Series" button (preserves grid scroll position via browser history)
- Content area (overlaid on gradient):
  - **Poster**: 240×360 tall image with hex clip-path (rounded-hex shape, larger version of HexCard). Subtle accent-colored glow border (shadow-glow-sm). Fallback: dark surface-800 hex with first letter of title centered
  - **Title**: Large bold text (text-3xl, text-surface-50), right of poster
  - **Metadata hex badges**: Horizontal row of hex-cut pills (capsule with angled hex ends, bg-surface-800/60, border-accent/10). Each badge shows one metadata field: year, rating label, duration, genre. Only rendered if data exists
  - **Rating**: Star icon + number (e.g. "★ 7.8 / 10") in accent color, displayed below badges
  - **Action buttons**: hex-cut shaped buttons below the metadata
    - **Play**: Filled accent button with glow. If watch history exists → "Resume S1:E4" with mini progress bar inside button
    - **Favorite**: Outline button, filled when active

**Section 2 — Sticky Tab Bar**

- Tabs: small hex icon (⬡) before each label, accent-colored active tab with hex-shaped underline bar
- Sticky when scrolled past hero: bg-surface-950/80 + backdrop-blur
- Tab visibility rules (conditional — tabs without data are hidden):
  - **Movie (rich metadata)**: `Info · Related`
  - **Movie (sparse/M3U)**: `Related` only (or no tab bar at all)
  - **Series (rich metadata)**: `Episodes · Info · Related`
  - **Series (sparse/M3U)**: `Episodes · Related`
- If only one tab: skip tab bar entirely, render content directly

**Section 3a — Episodes Tab (series only)**

- Season selector: hex-cut pill dropdown `[Season 1 ▾]` with styled dropdown (surface-800 bg, accent border, episode count per season)
- Episode cards: rounded-xl, border-accent/5, bg-surface-900/30
  - Left: episode number in hex badge (small hex shape, accent-tinted, bold number)
  - Center: episode title (bold, surface-100) + description (surface-400, 1-2 lines, truncated). Fallback: "Episode {n}" if no title
  - Right: duration ("45m" or "1h 23m", surface-500)
  - Bottom: accent-colored progress bar if partially watched (from watch_history)
  - Hover: subtle lift (translate-y-0.5), glow border (border-accent/20), play icon appears
  - Currently playing: hex badge pulses with accent glow, persistent accent left-border

**Section 3b — Info Tab**

- Description: text-surface-300, max 4 lines with "Show more" expand
- Info card: rounded-xl, border-accent/5, bg-surface-900/30, p-6
- Sections (only rendered if data exists — never show empty fields):
  - Cast: label with hex icon prefix (⬡), comma-separated actor names
  - Director: label with hex icon prefix
  - Genre: hex-cut pill tags (clickable — filters Related tab)
  - Release date/year

**Section 3c — Related Tab**

- Two horizontal rows of HexCard (poster size, reusing existing component):
  - "From {group_name}" — same category
  - "From same source" — same source_id, different group
- Clicking a related card navigates to that item's detail page

**Animations (Motion / framer-motion)**

- Hero backdrop fades in (300ms), poster slides up from below (400ms spring), metadata badges stagger left-to-right (100ms delay each)
- Tab switch: content cross-fades (200ms)
- Episode card hover: 150ms lift + glow transition
- Play button: subtle persistent accent glow pulse (CSS animation)
- Back navigation: page slides out right, grid slides back from left

**Graceful degradation:** The design scales from rich (Xtream with full TMDb-like metadata) to sparse (M3U with just title + poster). At minimum: hex poster + title + play button + related grid. Still looks clean.

#### Implementation Tasks

| # | Task | Details |
|---|------|---------|
| 11B.1 | Content metadata IPC | New IPC channel `content:getDetail` — returns ContentItem with parsed metadata_json fields (plot, cast, director, genre, rating, releaseDate) as typed object, plus watch history position. New IPC channel `content:getRelated` — returns items from same group_name and same source |
| 11B.2 | Content detail types | Extend shared types: `ContentDetail` interface with parsed metadata fields. `ContentMetadata` type for the parsed metadata_json blob. Update `Episode` type if needed |
| 11B.3 | ContentDetailPage component | Shared page component for `/movies/:id` and `/series/:id`. Fetches content detail + episodes (series) + related content via React Query. Manages tab state |
| 11B.4 | Hero section component | `DetailHero` — backdrop with blur/gradient, hex-framed poster, title, metadata badges, rating, action buttons. Handles missing data gracefully |
| 11B.5 | Tab bar component | `DetailTabs` — conditional tabs with hex-styled active indicator, sticky scroll behavior with frosted glass bg |
| 11B.6 | Episodes tab component | `EpisodesTab` — season selector dropdown, episode card list with hex number badges, progress bars from watch history, play-on-click |
| 11B.7 | Info tab component | `InfoTab` — description with expand/collapse, cast, director, genre pills, release year. Only renders sections that have data |
| 11B.8 | Related tab component | `RelatedTab` — two HexCard carousels (same group, same source). Click navigates to detail page |
| 11B.9 | Route wiring | Add `/movies/:id` and `/series/:id` routes in App.tsx. Update MoviesPage and SeriesPage to navigate to detail on card click instead of playing directly. Series removes inline episode modal |
| 11B.10 | Animations | Motion enter/exit transitions for hero, tabs, episode cards. CSS glow pulse for play button |
| 11B.11 | Keyboard navigation | Arrow keys navigate episode list, Enter plays, Escape goes back, Tab cycles between sections |

**Deliverable:** Clicking a movie or series opens a cinematic detail page with all available metadata, episode browser, and related content. Premium streaming app feel with YancoTV hex identity.

---

### Sprint 12 — Recording — DONE

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

### Sprint 13 — Downloading — DONE

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

### Sprint 14 — Metadata Enrichment — DONE

**Objective:** TMDb integration for richer movie/series info — enhanced posters, backdrops, descriptions, ratings.

Note: The content detail page UI was built in Sprint 11B using provider metadata (Xtream/Stalker). This sprint enriches that data with TMDb.

| # | Task | Details |
|---|------|---------|
| 14.1 | TMDb API client | Search movies/shows by cleaned title + optional year. Fetch details, images, cast, genres |
| 14.2 | Metadata matching engine | Fuzzy title matching with confidence scoring. Auto-match above threshold, flag low-confidence for review |
| 14.3 | Poster/backdrop display | Replace provider logos with TMDb posters and backdrops on detail page. Graceful fallback to provider logo |
| 14.4 | Manual match correction | "Wrong match? Search again" — user can search TMDb manually and link correct entry |
| 14.5 | Metadata cache | Cache TMDb results in `metadata_json` column. Avoid repeated API calls |
| 14.6 | Batch matching | Background job: auto-match unmatched movies/series after source sync. Rate-limited |
| 14.7 | TMDb API key config | Settings field for user's TMDb API key |

**Deliverable:** Detail pages enriched with TMDb posters, backdrops, cast photos, and extended descriptions.

---

### Sprint 15 — Subtitles (Advanced) — DONE

**Objective:** OpenSubtitles integration and subtitle preference management.

| # | Task | Details | Status |
|---|------|---------|--------|
| 15.1 | OpenSubtitles API client | REST v1 client w/ JWT auth, anonymous fallback, safeStorage-encrypted credentials | DONE |
| 15.2 | Auto-search on playback | `autoSearchSubtitles` fires on PLAYER_PLAY for VOD when `opensubtitles.autoSearch` enabled | DONE |
| 15.3 | Subtitle search UI | In-player SubtitlesTab → OpenSubtitles section with search/results/download | DONE |
| 15.4 | Subtitle preferences | PlaybackSettings: default language, auto-search toggle, credentials, cache clear | DONE |
| 15.5 | Subtitle cache | SQLite-backed `subtitle_cache` keyed by (content_id, episode_id, language); stale-file purge | DONE |
| 15.6 | Embedded subtitle extraction | ffmpeg extraction in Sprint 13 asset bundler | DONE (Sprint 13) |

**Deliverable:** Subtitles auto-searched and applied. Users can search/download from OpenSubtitles. Preferences remembered.

---

### Sprint 16 — Multi-View & PIP — DROPPED FROM THIS VERSION

Cut from Phase 1 scope. Not shipping in this version. May be revisited in a later release.

---

### Sprint 17 — Settings & Configuration — DONE

**Objective:** Comprehensive settings page covering all configurable aspects of the app.

All tasks completed:
- 17.1 Settings architecture — Key-value store in SQLite (`settings-service.ts`), typed getter/setter IPC (`settings:*`), Zustand store (`settings-store.ts`)
- 17.2 General settings — `GeneralSettings.tsx` component
- 17.3 Playback settings — `PlaybackSettings.tsx` component
- 17.4 EPG settings — `EpgSettings.tsx` component
- 17.5 Recording settings — storage path, max concurrent, max duration
- 17.6 Download settings — directory, concurrency, preferred quality, asset bundling toggles
- 17.7 Subtitle settings — default languages, auto-search toggle, appearance overrides (scale/color/opacity)
- 17.8 Parental settings — `ParentalSettings.tsx` component
- 17.9 Network settings — `NetworkSettings.tsx` component
- 17.10 Advanced settings — mpv path override, debug logging toggle, hw accel
- 17.11 Settings persistence — Settings persisted via SQLite key-value store

**Deliverable:** All app configuration accessible from one organized settings page. Persisted across restarts.

---

### Sprint 18 — System Features — DONE

**Objective:** Desktop integration, backup/restore, auto-update, and logging.

| # | Task | Details | Status |
|---|------|---------|--------|
| 18.1 | System tray | Tray icon with context menu (show, play/pause, quit); minimize/close-to-tray settings | DONE |
| 18.2 | Launch on startup | Deferred (low priority) | DEFERRED |
| 18.3 | Auto-update | Update check via `update-service.ts`, version comparison, release notes surfaced in AboutSettings | DONE |
| 18.4 | Backup export | `backup-service.ts` — exports sources, favorites, history, settings, customizations as JSON | DONE |
| 18.5 | Backup import/restore | Import JSON → restore. Merge/replace options | DONE |
| 18.6 | Structured logging | Deferred — console.* with timestamps is sufficient for current phase | DEFERRED |
| 18.7 | Crash handling | `crash-handler.ts` + renderer ErrorBoundary; unhandled errors logged + user-friendly dialog | DONE |
| 18.9 | About dialog | Version/build info/credits, check-for-updates button (AboutSettings) | DONE |
| 18.10 | App icon & branding | Custom `icon.ico`, generator script `scripts/generate-icon.js` | DONE |

**Deliverable:** YancoTV behaves like a proper Windows app — tray icon, auto-update, backup/restore, crash safety.

---

### Sprint 19 — Search, Channel UX & Notifications — DONE

**Objective:** Enhanced search and quality-of-life channel features.

All tasks completed:
- 19.1 Search type filter — live/movie/series toggles in SearchPage
- 19.2 Search autocomplete — sidebar autocomplete suggestions
- 19.3 Search history — tracked and shown in empty state
- 19.4 Channel zapping — arrow through channels with preview before commit
- 19.5 Last channel recall — keyboard shortcut to jump back
- 19.6 Auto-play on launch — setting to auto-tune last watched channel
- 19.7 Recent channels strip — last 5–10 channels on Live TV page
- 19.8 Programme reminders — EPG reminders with optional auto-tune
- 19.9 Notification system — in-app toast system for reminders/recording/download/sync events

**Deliverable:** Smarter search, faster channel surfing, users informed via toasts.

---

### Sprint 20 — Network Configuration & Keyboard Navigation — DONE

**Objective:** Stream reliability settings and full keyboard accessibility.

All tasks completed:
- 20.1 Buffer size config — mpv `cache-secs` driven by `playback_buffer_size` setting
- 20.2 Stream timeout — `network_connection_timeout` applied to mpv + HTTP fetchers
- 20.3 Auto-reconnect — exponential backoff (1s→30s max) on `end-file` with `reason='error'`, `reconnecting` status surfaced in UI
- 20.4 User-Agent override — per-source `user_agent` column applied via `PlayOptions.userAgent`
- 20.5 Proxy support — HTTP/SOCKS proxy applied to Electron session + mpv args
- 20.6 Full keyboard navigation — Arrow/Tab/Enter with focus rings across all views
- 20.7 Customizable shortcuts — full rebinding UI in `ShortcutsSettings.tsx` via `shortcuts-registry.ts`
- 20.8 Gamepad support — D-pad/A/B input mapping

**Deliverable:** Stream reliability configurable, full keyboard/gamepad navigation across the app.

---

## Phase 2 — Stabilization & Release

**Goal:** Harden everything, fix bugs, optimize performance, prepare for daily use.

### Sprint 21 — Stabilization (IN PROGRESS)

| # | Task | Details | Status |
|---|------|---------|--------|
| 21.1 | Error handling audit | Review all IPC handlers, parsers, network calls, player operations for proper error handling | DONE |
| 21.2 | Performance profiling | Profile renderer: fix unnecessary re-renders, optimize virtualized lists with 50K+ items | |
| 21.3 | Memory leak detection | Monitor main + renderer memory over extended use. Fix leaks in player, EPG refresh, download manager | |
| 21.4 | E2E tests | Playwright tests for critical flows: add source → browse → play → record → download | |
| 21.5 | Unit test coverage | Add tests for new services: reminder-service, crash-handler, opensubtitles-client (720 tests, +32 this sprint) | DONE |
| 21.6 | Manual testing pass | Full test with multiple real IPTV sources (M3U, Xtream, Stalker). Edge cases and error recovery | |
| 21.7 | Windows installer polish | electron-builder NSIS installer: desktop shortcut, start menu, uninstaller, file associations | |
| 21.8 | Portable build | Standalone .exe that runs without installation. Verify settings/DB portability | |
| 21.9 | Security audit | Review CSP, IPC validation, credential storage, URL handling. Pen-test the preload bridge. Findings: parental PIN moved to salted scrypt + timing-safe compare + brute-force cooldown. All other categories verified clean | DONE |
| 21.10 | Release prep | Version bumping, changelog, build pipeline verification, code signing (if available) | |

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
| **M8 — All Sources** | Stalker Portal, source editing, sync progress, health indicators | Sprint 11 | DONE |
| **M8B — Content Detail** | Cinematic movie/series detail pages with metadata, episodes, related | Sprint 11B | — |
| **M9 — Media Manager** | Recording + downloading = full media management | Sprints 12–13 | — |
| **M10 — Premium** | TMDb enrichment, subtitles | Sprints 14–15 | DONE |
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
| Multi-view | Up to 9 | Not in this version |
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
