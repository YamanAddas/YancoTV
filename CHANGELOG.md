# Changelog

All notable changes to YancoTV are tracked here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
version numbers follow [SemVer](https://semver.org/).

## [0.3.7] — 2026-05-27

### Added

- **Channel zap works in mini mode now.** PageUp/PageDown switches
  channels while the docked mini-player is showing — previously only
  theater mode honoured the keys. The floating "next channel" preview
  badge (top-center, z-[900]) appears the same way in both modes;
  releases auto-commit after 2 s of no further presses.

## [0.3.6] — 2026-05-27

### Fixed

- **Mini → theater flash on mpv.** Expanding from mini used to show a
  ~50 ms gap where the menu had hidden + MiniPlayer had unmounted but
  the mpv video child window hadn't yet grown to full size and the
  controls overlay hadn't appeared. Main process now runs the video
  resize + overlay show synchronously and only then broadcasts
  `MODE='theater'`; the renderer's `expand()` for mpv backend waits
  for that broadcast before committing local mode (with an optimistic
  fallback if the IPC rejects). By the time the menu hides, the
  theater chrome is already on screen — no visible gap.

### Internal

- New `presentationMode` flag in `video-window.ts` so `syncBounds` can
  choose between `customBounds` (mini) and the full parent content
  area (theater) without clearing the stash on every transition. This
  lets minimise from theater land the video at the right mini rect
  atomically, without waiting for `MiniPlayer`'s mount-effect to
  re-push bounds. `PLAYER_STOP` resets both `presentationMode` and
  `customBounds` so the next play() starts from a clean slate.

## [0.3.5] — 2026-05-27

### Added

- **Draggable mini-player.** Click + drag the card to move it anywhere
  in the viewport — useful when the default bottom-right spot covers
  channels you want to click in the grid. Position persists to
  `localStorage` across launches and is clamped back inside the
  viewport if the window is later resized smaller. Click-vs-drag is
  decided by a 5px mouse-move threshold so casual clicks still
  trigger expand-to-theater. For mpv backend, the video child
  window's bounds are pushed on every frame of the drag so the
  embedded video follows the card without lag. Hover hint updated
  to show both gestures ("Click to expand · Drag to move").

## [0.3.4] — 2026-05-27

Bundle of small UX wins + the architectural cleanup that prevents the
0.3.0 packaging crash from happening again.

### Added

- Play/Pause and Mute controls directly in the mini-player top bar.
  Used to require expanding to theater just to pause; now both live
  next to the Close button. Buttons only render when a stream is
  actually active.

### Changed

- `@yancotv/core` no longer re-exports its store factories from its
  main entry. Renderer-side store imports moved to
  `@yancotv/core/stores` (the subpath was already declared in
  `package.json` `exports`). This prevents the Electron main process
  from transitively pulling `zustand` → `react` just by importing
  parsers, which is what caused the `ERR_MODULE_NOT_FOUND` crash in
  0.3.0. Future renderer-only deps added to core will no longer leak
  into the main-process require graph.

### Fixed

- `backup-service` silently dropped credentials when the OS keyring
  refused a decrypt (rotated master key, profile copy between
  machines, safeStorage reset). Backup file went out incomplete with
  zero warning to the user. `exportBackupToFile` now collects
  per-source decryption failures and returns them as `warnings: []`
  to the renderer; AdvancedSettings surfaces them in an amber-tinted
  list under the export button so the user knows to re-enter the
  affected credentials after a restore.
- `HomePage` had three `.then()` chains with no `.catch()`, so a
  main-process IPC failure would bubble up as an unhandled promise
  rejection. Added `.catch(() => {})` on all three plus a
  `cancelled` guard so a slow response doesn't `setState` after the
  page unmounts.

## [0.3.3] — 2026-05-27

### Changed

- The icon-only Back arrow at the top-left of theater mode is now a
  labeled "Browse" pill button. Users were hitting Close (X) — which
  stops the stream — to get back to the channel grid because the
  Back arrow's "keeps playing in mini" tooltip was hover-only and
  easy to miss. Same action (minimize to mini, keeps playing); just
  obvious which button does what now.

## [0.3.2] — 2026-05-27

Two mini-player UX fixes after testing 0.3.1.

### Fixed

- Switching channels closed the mini-player instead of switching the
  stream. Root cause: when mpv is already running, `play(new-url)`
  sends `loadfile … replace`, which makes mpv emit an `end-file`
  event for the OUTGOING file (reason `redirect` on modern mpv).
  The default end-file handler interpreted any non-`stop`/`quit`
  reason as a drop and flipped `status` to `stopped`, which the
  renderer's mpv-state listener treated as "tear down the player".
  Each pending `loadfile` now queues a suppression in
  `pendingLoadfileEndFiles`; the matching end-file is consumed
  silently and the next `file-loaded` flips status straight to
  `playing`. A counter (not a boolean) so rapid channel-flipping
  doesn't lose suppressions.
- The mini-player card showed no feedback during the gap between
  click and first painted frame — looked frozen. Added a centered
  buffering / reconnecting spinner (dim backdrop + accent ring)
  shown whenever `status === 'buffering' || 'reconnecting'`. mpv's
  embedded child window paints over it once the first frame lands.

### Internal

- `play()` in the mpv loadfile branch now broadcasts an explicit
  `status='buffering'` + new `currentUrl` state-change before the
  loadfile command, so both renderers see the transition starting
  even though they already set the same optimistically.

## [0.3.1] — 2026-05-27

Hotfix for three packaging bugs in 0.3.0 that crashed (or silently
degraded) the installer build on launch.

### Fixed

- `react` and `react-dom` were in root `devDependencies`, so
  electron-builder excluded them from the asar. Main process
  `require('@yancotv/core')` transitively loads `zustand` (added to
  core in commit `94b7eac`, after the 0.2.0 build), whose ESM main
  entry re-exports `zustand/react`, which `import`s `react`. Missing
  package → `ERR_MODULE_NOT_FOUND: Cannot find package 'react'` in
  the main process, app-fatal. Both packages moved to `dependencies`
  along with `zustand` itself (which was also dev-scoped).
- `overlay-window.ts` resolved `overlay.html` via
  `path.join(__dirname, '../../renderer/overlay.html')` — but the
  compiled file lives at `dist/main/main/player/overlay-window.js`,
  so two-dots-twice landed at `dist/main/renderer/overlay.html`
  (doesn't exist) instead of `dist/renderer/overlay.html`. Bumped to
  three levels up. Pre-existing bug exposed by 0.3.0 because the
  mini-player redesign now drives the overlay window through real
  presentation transitions instead of relying on `PLAYER_PLAY`'s
  auto-show.
- Video stage + controls overlay child windows were created inside
  `mainWindow.on('ready-to-show')`, which fires *after* the
  renderer's first paint — and the auto-play `useEffect` already
  fires inside that paint, so `PLAYER_PLAY` could race past the
  child-window creation. `getVideoWindowHandle()` returned null and
  mpv fell through to its standalone window with no embedded video
  and no overlay controls — the exact "mpv plays in a separate
  window with no controls" symptom the user reported on 0.2.0 (the
  race was pre-existing; auto-play just exposed it on every launch).
  Child windows now spawn inside `createWindow()` itself; they start
  hidden and stay that way until the renderer's MiniPlayer /
  PlayerContainer drives them via the presentation IPCs.

## [0.3.0] — 2026-05-27

Mini-player redesign. Auto-played streams no longer swallow the menu —
they dock bottom-right while the sidebar and page content stay
interactive. Plus an audit-driven round of P1/P2 cleanup.

### Added

- Docked mini-player: a new `mini` player mode runs alongside `theater`
  and `idle`. Auto-play on launch (when "Remember last channel" is on)
  lands in mini by default; the user clicks expand to enter full theater.
- Theater `Back` (and `Escape`) now minimise to mini instead of stopping;
  a new explicit `Close` (X) button is the only path to fully stop the
  stream.
- Error overlay in the player has a visible "Back to menu" button
  instead of the discoverability-hostile "Press Escape" hint.
- mpv backend repositions its embedded video child window over the mini
  card via a new `PLAYER_SET_VIDEO_BOUNDS` IPC + `ResizeObserver`, so
  the embedded video itself shrinks to fit (not just an audio-only
  placeholder).

### Changed

- `play()` defaults to mini mode for fresh streams; theater is preserved
  only if the user is already in theater when they tune. App lands on
  the menu, not on a full-screen player.
- Toaster now renders in theater too so recording errors etc. surface
  while watching.
- Sidebar version display pulls from `app.getVersion()` IPC (with the
  shared constant as fallback) so the displayed version tracks
  `package.json` rather than drifting.
- Sidebar `Ctrl+F` + `Ctrl+B` handlers collapsed to a single stable
  listener that doesn't rebind on every sidebar toggle.
- Video element is mounted once at Layout level (new `VideoStage`
  wrapper) and reshaped between mini and theater, so playback continues
  uninterrupted across mode switches.

### Fixed

- Auto-play on launch used to slam the app into theater mode, which
  hid the sidebar, page content, and toaster — leaving the user
  staring at a blank window with audio. On mpv backend the controls
  overlay would occasionally lose z-order on cold Windows 11 starts,
  making it look like there were no controls at all. Mini-mode default
  fixes both.
- Cross-window mode sync: a new `PLAYER_MODE_BROADCAST` IPC keeps the
  main-window store and the controls-overlay store aligned, so
  `Back`/`Esc` in the overlay's TheaterControls now correctly
  propagates to the main window's MiniPlayer.
- Backend race: auto-play could fire before `checkMpv()` resolved,
  routing mpv content through the html5 path. Now gated on
  `backend !== 'none'` and fires at most once per launch.
- `PLAYER_PLAY` no longer auto-shows the controls overlay; the
  renderer drives overlay visibility via `setPresentation`. Eliminates
  a brief full-screen theater chrome flash on mini-mode auto-play.
- Overlay window no longer seeds `mode='theater'` on load; was causing
  a transparent fullscreen overlay to silently cover the menu on cold
  start before any stream existed.
- `stop()` exits OS fullscreen before flipping React state so the menu
  doesn't re-render inside a still-fullscreen window.
- Recording-failed paths in TheaterControls + LiveTvPage replaced
  `window.alert()` (which blocked the renderer thread) with toast
  notifications.
- `SearchPage` debounce timer + in-flight IPC response are torn down
  on unmount; a slow query result can no longer setState on a dead
  component or stomp a newer query.
- `ContentDetailPage` guards its `Promise.all` fetch against
  setState-after-unmount when the user navigates away mid-load.
- Auto-play bails if the user has already kicked off (or stopped) a
  stream while the channel-detail fetch was in flight.
- Emoji glyphs in `Sidebar` suggestion icons and `SearchPage` filter
  chips replaced with SVGs — emoji rendering is unreliable across
  font stacks.

### Internal

- 3 new IPC channels (`PLAYER_SET_PRESENTATION`,
  `PLAYER_SET_VIDEO_BOUNDS`, `PLAYER_MODE_BROADCAST`) for the
  multi-window mini-player coordination. Channel count test bumped
  from 147 to 150.
- `VideoPlayer` survives mini↔theater transitions thanks to the new
  `VideoStage` wrapper — no codec/stream restart on expand.

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
