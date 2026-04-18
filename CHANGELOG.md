# Changelog

All notable changes to YancoTV are tracked here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
version numbers follow [SemVer](https://semver.org/).

## [0.2.0] — 2026-04-17

First feature-complete release. Covers Sprints 12–21: recording, downloads,
metadata, subtitles, full settings, system integration, stabilization.

### Added

**Media features**

- Live channel recording via bundled ffmpeg, with scheduled recordings (Sprint 12)
- VOD downloads with retry/resume and bundled poster/backdrop/.nfo/subtitles (Sprint 13)
- TMDb metadata enrichment with SQLite-backed cache and MetadataSettings tab (Sprint 14)
- OpenSubtitles auto-search on playback + in-player search, DB-backed cache, safeStorage credentials (Sprint 15)
- Channel zapping, last-channel recall, recent-channels strip, EPG reminders with toasts (Sprint 19)

**System integration**

- System tray with close-to-tray and minimize-to-tray (Sprint 18.1)
- Backup export/import with safeStorage re-encryption on restore
- Manual "Check for updates" (version-compare only; hardcoded HTTPS manifest)
- Crash handler for main and renderer; errors land in log file instead of silent exit
- App icon + About dialog

**Settings**

- Full 8-tab Settings page: General, Playback, Network, Playlist, EPG, Parental, Shortcuts, About (Sprint 17)
- Customizable keyboard shortcuts; per-source user-agent override; proxy support; gamepad input (Sprint 20)
- Buffer, timeout, and auto-reconnect tuning for flaky sources

**Stabilization (Sprint 21)**

- Error-handling pass over IPC handlers, parsers, and network calls
- Unit tests for reminder-service, crash-handler, opensubtitles-client; 725 tests total
- Windows installer polish: publisher metadata, `.m3u`/`.m3u8` file association, named
  Start-menu shortcut, `deleteAppDataOnUninstall: false`
- Portable .exe now actually portable — userData redirects to `YancoTV-Data/`
  next to the exe; no footprint in `%APPDATA%`

### Changed

- Default player engine is mpv via JSON-RPC over named pipes (HTML5 fallback available)
- Player window is an embedded dedicated video stage (HWND-based) for click-through overlays
- EPG auto-refreshes every 12h; reminders scan once per 30s + wake-up timer for near-term fires
- Content detail pages redesigned as cinematic hero/tab/episode layouts
- Groups menu prettifies codes to language names; drag-and-drop reorder; pin/hide preferences
- Typography refresh; Movies and Series use poster grid instead of hex cards

### Security

- Parental PIN moved from unsalted SHA-256 to scrypt + per-PIN 16-byte random salt;
  legacy hashes verified once and transparently upgraded on next successful check
- PIN comparison now uses `timingSafeEqual` (constant-time)
- Brute-force protection: 5 consecutive failures trigger a 30s cooldown that doubles
  to a 5min cap; success resets the counter
- OpenSubtitles/update/mpv-IPC fetches now have explicit AbortController timeouts
- mpv IPC buffer capped at 1 MB to defeat malformed-server memory pressure
- IPC settings handlers reject blocked prefixes (`parental_`, `epg_last_refreshed`)
- Full security audit covering CSP, IPC validation, SQL, credential storage, path
  traversal, command injection, XML parsing, external URLs — all other categories clean

### Fixed

- Migration `.sql` files are now copied into `dist/` during build. Without this,
  every fresh install would boot against an empty schema ("no such table: settings"
  on first SELECT). `build` and `build:main` both include the copy step.
- Channel reorder respects source grouping
- Subtitle cache includes manually downloaded tracks
- mpv IPC timeouts, HWND buffer guards, listener cleanup (29 bug fixes earlier in
  the cycle — see commit `a1298bc`)

### Dropped

- Picture-in-picture mode (Sprint 16) — cut from scope; may return later

---

## [0.1.0] — earlier

Foundation release. Sprints 1–11B: sources, Xtream, Stalker, browsing UI, mpv
playback, search/favorites/history, EPG, catch-up/timeshift, parental controls,
settings persistence, multi-source merge, content detail pages.

See commit history for details.

[0.2.0]: https://github.com/YamanAddas/YancoTV/releases/tag/v0.2.0
[0.1.0]: https://github.com/YamanAddas/YancoTV/releases/tag/v0.1.0
