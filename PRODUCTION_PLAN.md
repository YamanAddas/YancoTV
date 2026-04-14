# YancoTV — Full Production Plan

## Guiding Principles

1. **Ship working software early.** Every sprint should produce something runnable.
2. **Security is not optional.** Electron hardening is baked in from the first commit.
3. **Abstraction where it earns its keep.** Player interface yes. Factory-pattern-everything no.
4. **IPTV data is messy. Accept it.** Build robust parsers, don't assume clean input.
5. **Desktop and TV are different products.** Shared logic, separate UIs.

---

## Phase 1 — Windows MVP

**Goal:** A working Windows app that connects to IPTV sources, organizes content, and plays streams reliably.

### Sprint 1 — Foundation

**Objective:** Scaffold the project with hardened Electron, build pipeline, and basic window.

| # | Task | Details |
|---|------|---------|
| 1.1 | Project scaffold | `pnpm init`, install Electron, React, TypeScript, Vite, Tailwind |
| 1.2 | Electron main process | Hardened BrowserWindow config: `contextIsolation`, `sandbox`, `nodeIntegration: false` |
| 1.3 | Preload + IPC scaffold | `preload.ts` with `contextBridge`, typed IPC channel definitions in `shared/ipc-channels.ts` |
| 1.4 | Vite config for renderer | HMR dev server, production build targeting Electron renderer |
| 1.5 | React app shell | App.tsx, React Router with placeholder pages (Home, LiveTV, Movies, Series, Settings) |
| 1.6 | Tailwind setup | Config with custom color palette (dark theme, media-app aesthetic) |
| 1.7 | SQLite integration | better-sqlite3 in main process, migration runner, initial schema |
| 1.8 | Dev scripts | `pnpm dev` (concurrent Vite + Electron), `pnpm build`, `pnpm lint` |
| 1.9 | ESLint + Prettier | Strict TypeScript rules, format-on-save config |
| 1.10 | electron-builder config | Windows target (NSIS installer + portable), basic config |

**Deliverable:** Empty app launches, navigates between placeholder pages, database initializes.

---

### Sprint 2 — Source Management + M3U Parsing

**Objective:** Users can add IPTV sources and the app parses M3U playlists.

| # | Task | Details |
|---|------|---------|
| 2.1 | Source data model | Zod schemas for source input validation, TypeScript types |
| 2.2 | Source manager service | CRUD operations for sources in SQLite |
| 2.3 | Credential encryption | Xtream credentials encrypted via Electron safeStorage before DB storage |
| 2.4 | M3U parser | Parse `#EXTINF` lines: extract title, duration, group-title, tvg-id, tvg-logo, stream URL. Handle both local files and remote URLs. |
| 2.5 | M3U edge cases | Handle: BOM markers, Windows/Unix line endings, empty lines, malformed entries, large files (streaming parser, not load-all-in-memory) |
| 2.6 | Content storage | Store parsed entries in `content` table with source linkage |
| 2.7 | IPC handlers for sources | `sources:add`, `sources:getAll`, `sources:remove`, `sources:sync` |
| 2.8 | Settings page UI | Add Source form (M3U URL, M3U file picker, Xtream credentials), source list with delete |
| 2.9 | Source sync indicator | Show last synced time, sync button, loading state |
| 2.10 | Unit tests | M3U parser tests with real-world edge cases |

**Deliverable:** User can add an M3U URL, app fetches and parses it, entries stored in DB.

---

### Sprint 3 — Xtream Codes + Content Classification

**Objective:** Xtream Codes API support and automatic Live/Movie/Series separation.

| # | Task | Details |
|---|------|---------|
| 3.1 | Xtream Codes client | API client: authenticate, fetch categories, fetch live/vod/series streams. Handle API errors and timeouts. |
| 3.2 | Xtream response parsing | Map Xtream JSON responses to unified content types |
| 3.3 | Content classifier | Classify M3U entries into live/movie/series using heuristics (duration, group patterns, URL patterns, title patterns) |
| 3.4 | Title cleaner | Regex-based cleaning: strip quality tags, country prefixes, provider noise, numbering. Produce `clean_title` field. |
| 3.5 | Category normalization | Normalize group names across sources for consistent browsing |
| 3.6 | Series grouping | Group series episodes by show → season → episode for M3U sources |
| 3.7 | Content IPC handlers | `content:getLive`, `content:getMovies`, `content:getSeries`, `content:getCategories`, `content:search` |
| 3.8 | Unit tests | Xtream client tests (mocked API), classifier tests, title cleaner tests |

**Deliverable:** Both M3U and Xtream sources parsed, content correctly separated into live/movies/series.

---

### Sprint 4 — Browsing UI

**Objective:** Users can browse organized content across Live TV, Movies, and Series sections.

| # | Task | Details |
|---|------|---------|
| 4.1 | Navigation sidebar | App navigation: Home, Live TV, Movies, Series, Favorites, Search, Settings |
| 4.2 | Home page | Overview: recently watched, favorite channels, content counts per section |
| 4.3 | Live TV page | Category sidebar + channel grid. Show logo, title, current EPG if available. |
| 4.4 | Movies page | Category filter + movie grid with poster/logo, title, year if available |
| 4.5 | Series page | Show grid → season list → episode list drill-down |
| 4.6 | Virtualized lists | react-virtuoso for large lists (thousands of channels/movies) |
| 4.7 | Category filtering | Filter content by group/category within each section |
| 4.8 | Loading states | Skeleton loaders during data fetch, empty states when no content |
| 4.9 | Source switcher | Switch between sources or view all sources merged |
| 4.10 | Dark theme | Consistent dark theme across all pages (media-app aesthetic) |

**Deliverable:** Full browsing experience — navigate between sections, filter by category, scroll through content.

---

### Sprint 5 — Playback

**Objective:** Users can watch live TV and VOD content with stable playback.

| # | Task | Details |
|---|------|---------|
| 5.1 | IPlayer interface | Define player abstraction: play, pause, stop, seek, volume, tracks, events |
| 5.2 | MpvPlayer implementation | Spawn mpv child process, communicate via IPC/JSON protocol or command-line flags |
| 5.3 | mpv window embedding | Embed mpv output in Electron window (--wid flag pointing to a native window handle) |
| 5.4 | Player controls UI | Play/pause, seek bar, volume, fullscreen toggle, subtitle/audio track selection |
| 5.5 | Live TV playback | Channel switching, channel up/down, mini EPG overlay |
| 5.6 | VOD playback | Seek support, resume from last position (watch history integration) |
| 5.7 | Playback error handling | Handle stream failures, timeouts, unsupported formats gracefully |
| 5.8 | Keyboard shortcuts | Space (play/pause), arrows (seek), F (fullscreen), M (mute), Esc (exit player) |
| 5.9 | mpv bundling | Bundle mpv.exe + required DLLs with the app for Windows distribution |
| 5.10 | Player IPC handlers | `player:play`, `player:pause`, `player:stop`, `player:seek`, `player:state` |

**Deliverable:** Click any channel or movie → it plays. Controls work. Stable playback.

---

### Sprint 6 — Search, Favorites & History

**Objective:** Smart search, favorites system, and watch history with resume.

| # | Task | Details |
|---|------|---------|
| 6.1 | Search service | Full-text search across `clean_title`, `group_name`. SQLite FTS5 virtual table. |
| 6.2 | Search UI | Search bar in header, results grouped by type (live/movies/series), keyboard navigable |
| 6.3 | Search result ranking | Prioritize exact matches, then prefix, then contains. Boost favorites. |
| 6.4 | Favorites service | Add/remove favorites, stored in `favorites` table |
| 6.5 | Favorites UI | Heart/star toggle on content items, dedicated Favorites page |
| 6.6 | Watch history service | Record what was watched, position, duration. Auto-update on playback. |
| 6.7 | Watch history UI | Recently watched list on Home page, resume indicators on content items |
| 6.8 | Resume playback | "Continue watching" — resume VOD from last position |
| 6.9 | History management | Clear history, remove individual entries |

**Deliverable:** Search finds content fast. Favorites saved. Watch history tracks and resumes.

---

### Sprint 7 — EPG

**Objective:** Electronic Program Guide for live TV channels.

| # | Task | Details |
|---|------|---------|
| 7.1 | XMLTV parser | Parse XMLTV format: channels, programmes, start/stop times, titles, descriptions |
| 7.2 | EPG data service | Fetch EPG from URL (configured per source or globally), store in `epg_programmes` table |
| 7.3 | EPG ↔ Channel mapping | Match EPG channels to live TV content via `tvg-id` |
| 7.4 | Now/Next display | Show current + next programme on channel grid items |
| 7.5 | EPG guide view | Full programme guide: time grid with channels and programmes |
| 7.6 | EPG refresh | Auto-refresh EPG data on schedule (configurable interval) |
| 7.7 | EPG detail view | Click a programme → show description, time, category |

**Deliverable:** Live TV channels show what's on now/next. Full EPG grid available.

---

### Sprint 8 — Polish & Packaging

**Objective:** App is polished, packaged, and ready for daily use.

| # | Task | Details |
|---|------|---------|
| 8.1 | Error handling audit | Review all IPC handlers, parsers, network calls for proper error handling |
| 8.2 | Performance optimization | Profile renderer, optimize re-renders, ensure smooth scrolling with 10K+ items |
| 8.3 | Settings page | Theme preference, default source, EPG URL, mpv path override, data directory |
| 8.4 | App icon + branding | Custom app icon, window title, about dialog |
| 8.5 | Auto-start behavior | Remember window size/position, restore last view on launch |
| 8.6 | Logging | electron-log for main process, structured logs with levels |
| 8.7 | Windows installer | electron-builder NSIS installer, desktop shortcut, uninstaller |
| 8.8 | Portable build | Standalone .exe that runs without installation |
| 8.9 | E2E tests | Playwright tests for critical flows: add source → browse → play |
| 8.10 | Manual testing pass | Full manual test with real IPTV sources |

**Deliverable:** Installable, polished Windows app. Phase 1 complete.

---

## Phase 2 — Windows Advanced Features

**Goal:** Elevate the app with metadata enrichment, subtitles, downloading, and recording.

### Sprint 9 — Metadata Enrichment

| # | Task | Details |
|---|------|---------|
| 9.1 | TMDb API integration | Search movies/shows by cleaned title, fetch metadata (poster, description, year, genres, rating) |
| 9.2 | Metadata matching engine | Match IPTV content to TMDb entries with fuzzy matching, confidence scoring |
| 9.3 | Poster display | Replace provider logos with TMDb posters where matched |
| 9.4 | Detail pages | Movie/show detail page: poster, description, genres, rating, episodes |
| 9.5 | Manual match correction | User can manually search and link correct TMDb entry if auto-match is wrong |
| 9.6 | Metadata cache | Cache TMDb results in SQLite to avoid repeated API calls |

### Sprint 10 — Subtitles

| # | Task | Details |
|---|------|---------|
| 10.1 | Embedded subtitle display | Show embedded subtitles from streams via mpv |
| 10.2 | External subtitle loading | Load .srt/.vtt files from disk, pass to mpv |
| 10.3 | OpenSubtitles API client | Search subtitles by movie/show title, filter by language (EN, AR) |
| 10.4 | Subtitle download + apply | Download subtitle file, apply to current playback |
| 10.5 | Subtitle preferences | Default subtitle language, auto-search on playback start |
| 10.6 | Subtitle UI | Subtitle track selector in player controls, download button when none found |

### Sprint 11 — Downloading

| # | Task | Details |
|---|------|---------|
| 11.1 | Download manager service | Queue-based download manager, track progress, handle failures |
| 11.2 | ffmpeg download integration | Download HTTP/HLS streams to local MP4 via ffmpeg |
| 11.3 | Download UI | Download button on movies/episodes, download queue view, progress bars |
| 11.4 | Local library | Browse downloaded content, play locally without internet |
| 11.5 | Download settings | Default save directory, concurrent download limit, quality preference |

### Sprint 12 — Recording

| # | Task | Details |
|---|------|---------|
| 12.1 | Recording service | Start/stop recording of live streams via ffmpeg |
| 12.2 | Recording UI | Record button in live TV player, recording indicator, stop button |
| 12.3 | Scheduled recording | Set a timer to record a channel at a specific time (tied to EPG) |
| 12.4 | Recording library | Browse recorded content, play locally |
| 12.5 | Recording management | Delete recordings, storage usage display |

---

## Phase 3 — Android TV (Future)

**Goal:** Purpose-built Android TV client sharing core logic with the Windows app.

| # | Task | Details |
|---|------|---------|
| 13.1 | Android project setup | Kotlin, Jetpack Compose for TV, ExoPlayer |
| 13.2 | Port parsing logic | Reimplement M3U parser and Xtream client in Kotlin (or shared KMP module) |
| 13.3 | Room database | SQLite schema ported to Room, same structure |
| 13.4 | TV navigation | D-pad focus management, leanback-style browsing |
| 13.5 | ExoPlayer integration | Implement IPlayer contract with ExoPlayer |
| 13.6 | TV-optimized UI | 10-foot UI, large text, focus indicators, remote-friendly |
| 13.7 | Firestick optimization | Performance tuning for lower-powered devices |
| 13.8 | APK build pipeline | Gradle build, signed APK output, sideload-ready |

---

## Phase 4 — Intelligence (Future)

**Goal:** Auto-generated subtitles and advanced content matching.

| # | Task | Details |
|---|------|---------|
| 14.1 | Whisper integration | Local Whisper model or API for speech-to-text on VOD content |
| 14.2 | Auto-subtitle generation | Generate English subtitles from audio track |
| 14.3 | Translation | Translate generated subtitles EN↔AR |
| 14.4 | Advanced content matching | ML-based title matching for better metadata accuracy |
| 14.5 | Smart categorization | Auto-detect miscategorized content, suggest corrections |

---

## Milestone Summary

| Milestone | What You Get | Sprints |
|-----------|-------------|---------|
| **M1 — Skeleton** | App launches, navigates, DB works | Sprint 1 |
| **M2 — Sources** | Add M3U/Xtream sources, content parsed | Sprints 2–3 |
| **M3 — Browse** | Full browsing UI with categories | Sprint 4 |
| **M4 — Watch** | Playback works for all content types | Sprint 5 |
| **M5 — Use Daily** | Search, favorites, history, EPG | Sprints 6–7 |
| **M6 — Ship It** | Polished, packaged, installable | Sprint 8 |
| **M7 — Premium** | Metadata, subtitles, download, record | Sprints 9–12 |
| **M8 — TV** | Android TV app | Sprint 13 |
| **M9 — Smart** | Auto-subtitles, AI matching | Sprint 14 |

---

## Risk Mitigation

| Risk | Mitigation |
|------|-----------|
| mpv embedding is tricky on Windows | Prototype `--wid` embedding in Sprint 5 early. Fallback: separate mpv window. |
| Large playlists (50K+ entries) | Streaming M3U parser, virtualized lists, SQLite FTS5, paginated IPC responses. |
| Xtream API inconsistencies | Defensive parsing with Zod, graceful handling of missing fields, provider-specific quirks. |
| Provider metadata is garbage | Title cleaner handles 80% of cases. TMDb matching handles the rest in Phase 2. |
| Electron app size | Tree-shake renderer, lazy-load pages, monitor bundle size. mpv + ffmpeg add ~100MB — acceptable. |
| Security vulnerabilities | Hardened from day one. No shortcuts. Security audit before Phase 1 ship. |

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
