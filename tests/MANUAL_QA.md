# Manual QA Checklist — YancoTV 0.2.0

Sprint 21.6 deliverable. This document is the human-driven test plan that
complements the 725 unit tests and 22 E2E tests. Run this before cutting
a release, ideally against a packaged build (`pnpm package`) on a clean
Windows profile.

## Pre-flight

- [ ] Clean `%APPDATA%/YancoTV` (or test on a user profile that has never
      run YancoTV) so fresh-install paths are exercised.
- [ ] Have ready: at least one working **M3U URL**, one **M3U file**, one
      **Xtream Codes** account, one **Stalker Portal** endpoint. Note the
      credentials somewhere scratch — you'll re-enter them.
- [ ] Have a **bad** source of each kind on hand (expired Xtream account,
      invalid M3U URL, unreachable Stalker portal) for error-path checks.
- [ ] TMDb API key for metadata enrichment; OpenSubtitles account for
      subtitle search.

## 1. Source management

- [ ] Add M3U via URL — status goes pending → syncing → ready, channel
      counts show up.
- [ ] Add M3U via file picker — same flow.
- [ ] Add Xtream source — live/movie/series counts all populate.
- [ ] Add Stalker source — MAC auth succeeds, channels appear.
- [ ] Add a **bad** source of each kind — clear error toast, source row
      shows unhealthy state, app does not crash.
- [ ] Edit source (rename, change URL) — re-syncs correctly.
- [ ] Drag-and-drop reorder sources — order persists after restart.
- [ ] Delete source — confirmation prompt, data actually removed.
- [ ] With 3+ sources active, verify multi-source merge (same channel
      from two sources should not double up).

## 2. Content browsing

- [ ] Live TV page: grid renders, empty state shown when no sources,
      group sidebar works, sort dropdown works.
- [ ] Movies page: poster grid, sort/filter, metadata overlays.
- [ ] Series page: poster grid, click-through to detail with season
      selector, episode list.
- [ ] Favorites: toggle heart on item, appears on Favorites page,
      toggle off removes it.
- [ ] Content detail pages (series + movie): hero, tabs, info, related,
      animations all render without jank.
- [ ] Group menu prettifies language codes (e.g. "en" → "English"),
      pin/hide persists, drag-to-reorder groups works.
- [ ] Recent channels strip appears on Live TV home and cycles through
      recently played channels.

## 3. Playback (mpv)

- [ ] Click a live channel — mpv loads, video is visible (not hidden
      behind Chromium compositor), audio plays.
- [ ] Pause / resume (Space) — both sides respond instantly.
- [ ] Volume up / down / mute (arrows + M) — persists across channel
      change.
- [ ] Subtitle track switch (if present in stream).
- [ ] Audio track switch (if multi-audio stream).
- [ ] Fullscreen toggle (F11) during playback — sidebar/overlay hide
      and restore correctly.
- [ ] Channel zapping (up/down arrows while focused on live channel) —
      smooth channel change without UI freeze.
- [ ] Last-channel recall (configured key) — jumps back to previous
      channel.
- [ ] Yank cable mid-stream — auto-reconnect kicks in per Network
      settings, stream resumes without manual intervention.

## 4. HTML5 fallback

- [ ] Toggle player engine to HTML5 in Playback settings — playback
      still works (may lose some tracks/subs). Toggle back to mpv.

## 5. EPG

- [ ] EPG auto-refresh runs on startup (check logs).
- [ ] Now/Next shows on live channel cards.
- [ ] Guide page (channels × time) grid scrolls smoothly with 500+
      channels; current-time indicator is at the right position.
- [ ] Click program → program details modal.
- [ ] Set a reminder for a program starting within 2 minutes →
      toast + optional tray notification fires within ~1s of program
      start.

## 6. Catch-up / Timeshift

- [ ] On a catch-up-capable channel, click a past program → stream
      starts at the correct timestamp.
- [ ] Pause live TV for 30+ seconds → mpv buffer holds up → resume
      plays from pause point.
- [ ] Rewind live TV 60s → picture goes back, then seek forward to
      live.

## 7. Recording

- [ ] Start "Record now" on a live channel → .ts (or configured ext)
      file lands in recordings folder, Recordings page shows in-progress
      indicator.
- [ ] Stop recording → file is playable in an external player.
- [ ] Schedule a recording 2 minutes in the future → ffmpeg starts on
      time, stops on time.
- [ ] Schedule two overlapping recordings → both record without
      clobbering each other.
- [ ] Delete recording from Recordings page → file is removed from disk.

## 8. Downloads (VOD)

- [ ] Queue a movie download — progress bar updates, asset bundle
      (poster, backdrop, .nfo, subs if available) lands in the same
      folder.
- [ ] Pause → resume a download → picks up from where it left off.
- [ ] Kill network mid-download → retries per Network settings.
- [ ] Play a finished download from Downloads page.
- [ ] Delete a download — media + asset bundle all removed.

## 9. Metadata

- [ ] Enter TMDb API key in Metadata settings → movies and series
      start showing posters/backdrops/overviews.
- [ ] Second visit to the same title is instant (cache hit).
- [ ] With no TMDb key, app still works (no enrichment but no crashes).

## 10. Subtitles

- [ ] Sign in to OpenSubtitles in Subtitle settings — credentials persist
      across restart (verify via safeStorage, not plaintext).
- [ ] On playback start, auto-search runs; candidate subs appear in the
      in-player subtitle picker.
- [ ] Download a candidate → subtitle renders in the player.
- [ ] Second open of same title → cached result, no re-fetch.

## 11. Parental controls

- [ ] Set a PIN → PIN is stored (verify `settings.parental_pin_hash`
      starts with `scrypt:` — **not** a 64-char hex string).
- [ ] Lock a channel → opens PIN modal before playback.
- [ ] Hide a channel → channel disappears from grids until PIN unlocks.
- [ ] Correct PIN → playback starts; wrong PIN → clear error message.
- [ ] Enter wrong PIN 5 times in a row → 30-second cooldown activates,
      UI shows countdown.
- [ ] After cooldown expires, correct PIN works again (counter reset).
- [ ] Change PIN → old PIN required, new PIN takes effect immediately.
- [ ] Remove PIN → asks for current PIN, then locks/hides are disabled.
- [ ] **Legacy-hash upgrade:** If you have a pre-0.2.0 profile, launch
      0.2.0, enter the old PIN once — verify in DB the hash has upgraded
      to `scrypt:...` without requiring the user to reset.

## 12. Search

- [ ] Sidebar search: autocomplete appears after 200 ms of typing, up
      to 6 suggestions, arrow-key navigation works, Enter picks.
- [ ] Full search page: FTS5 returns across Live/Movies/Series, filter
      chips work, recent searches persist across restart.
- [ ] Empty query on /search shows "Type to search" prompt.
- [ ] Nonsense query shows "No results for …" state.

## 13. Settings (all 8 tabs)

- [ ] **General** — theme, clock toggle, startup behavior.
- [ ] **Playback** — engine (mpv/HTML5), hardware decoding, default
      volume.
- [ ] **Network** — per-source user agent override actually reaches the
      HTTP request (verify via mitmproxy or DevTools network panel),
      proxy URL honoured, buffer/timeout/auto-reconnect actually change
      mpv args.
- [ ] **Playlist** — auto-sync interval, dedup mode.
- [ ] **EPG** — XMLTV URL override, refresh interval.
- [ ] **Parental** — see section 11.
- [ ] **Shortcuts** — rebind a key; new binding is active immediately
      and persists across restart; reset to defaults works.
- [ ] **About** — version **0.2.0**, "Check for updates" hits the
      release manifest, credits render.

## 14. System integration

- [ ] Minimize → tray: window vanishes from taskbar, tray icon remains.
- [ ] Close (X) → tray: window vanishes, app stays alive (verify in
      Task Manager).
- [ ] Tray right-click menu — Show, Quit all work.
- [ ] Backup export → .json lands on disk, opens cleanly.
- [ ] Backup import on a second machine/profile — credentials are
      re-encrypted under safeStorage (open DB, verify the imported
      credential is NOT equal to the exported one byte-for-byte).
- [ ] Trigger a renderer error (e.g. `throw` from DevTools console) →
      error surfaces in log file (`%APPDATA%/YancoTV/logs/main.log`),
      app does not silently die.
- [ ] App icon is YancoTV icon (not Electron default) in: taskbar,
      Alt-Tab, Start menu, title bar.

## 15. Keyboard shortcuts

- [ ] Ctrl+F focuses sidebar search from any page.
- [ ] Ctrl+B toggles sidebar width.
- [ ] Arrow keys navigate sidebar items when focused.
- [ ] During playback: Space, ←/→, ↑/↓, M, F, F11, Esc all behave per
      Shortcuts settings; custom-rebound keys work.
- [ ] Gamepad (if available): face buttons map to play/pause, D-pad
      navigates.

## 16. Portable mode

- [ ] Run `YancoTV-Portable-0.2.0.exe` from `D:\Somewhere\Else\`.
- [ ] Verify `D:\Somewhere\Else\YancoTV-Data\` is created with `db/`,
      `logs/`, Chromium caches inside.
- [ ] Verify **nothing** is written under `%APPDATA%\YancoTV`.
- [ ] Add a source, quit, copy the whole folder (exe + `YancoTV-Data/`)
      to another location or USB stick → launch → source is still there.
- [ ] Portable + NSIS install on the same machine do not interfere
      (different userData roots).

## 17. NSIS installer

- [ ] Run `YancoTV-Setup-0.2.0.exe` on a machine without YancoTV.
- [ ] Installer shows Yaman as publisher.
- [ ] Start Menu has a "YancoTV" entry (not "YancoTV 0.2.0" or generic).
- [ ] Double-clicking a local `.m3u` file opens it in YancoTV.
- [ ] Uninstaller runs; by design, userData is **not** deleted
      (`deleteAppDataOnUninstall: false`). Re-install and verify the
      previous settings/sources come back.

## 18. Fresh-install database

- [ ] On a brand-new `%APPDATA%\YancoTV`, first launch does NOT produce
      `no such table: settings` in the log. This regressed once (migration
      `.sql` files were not being copied into `dist/`); the smoke check
      is "app loads to Home screen with empty state, no errors in log".

## Sign-off

- [ ] All sections above: every box ticked OR ticket filed for the
      failure with log/screenshot.
- [ ] No CRITICAL or HIGH regressions open.
- [ ] CHANGELOG.md matches what was actually tested.

Tester: __________________   Date: __________   Build: 0.2.0 ________
