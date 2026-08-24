# YancoTV (native Android / TV) — Changelog

Native Android/Fire TV/Google TV releases. Desktop (Electron) releases are in
the repo-root [CHANGELOG.md](../../CHANGELOG.md).

Versioning: `versionName` is the human label; `versionCode` only ever
increases (the in-app updater and Android's package manager both rely on it).

## 1.6.4 (versionCode 28) — 2026-08-23

### Fixed
- **The Guide now shows all your channels**, not just the ones that happen to
  have EPG data (it was showing roughly 6% of a large channel list). Channels
  with no programme data appear with a "No information" row and still play.
  (MB-382)

Also includes internal robustness fixes for M3U catch-up, Stalker catalogue
paging, and content classification (these only affect M3U/Stalker sources) and
a desktop catch-up window fix — no visible change for Xtream users.

### Known issues
- On a TV remote you can't yet move left/right across a long programme block in
  the Guide; touch works. (MB-371)
- Casting works for H.264 content; H.265/HEVC isn't transcoded yet.

## 1.6.3 (versionCode 27) — 2026-08-23

A content-visibility and recording-correctness release, from an audit prompted
by categories that showed partial content.

### Fixed
- **Categories a provider split by a country/language prefix now show together.**
  e.g. `JAMES BOND 007` and `DE - JAMES BOND 007` were separate lists (the
  English one had a single film); they now merge into one "James Bond 007" with
  all of them. Category names lose their country prefix and same-named
  categories merge across languages (TiviMate-style). (MB-383)
- **Recording no longer captures the wrong channel if you change channel while
  recording.** (MB-376)
- **Scheduled recordings record the channel you picked** — not an arbitrary
  same-EPG-id channel, which previously could fail on a one-stream provider.
  (MB-381)
- **Catch-up plays the exact channel you opened**, not another that shares its
  EPG id. (MB-380)
- **Channel-surf (up/down in the player) covers your current category**, not
  just the first 200 channels of the whole list. (MB-378)
- **Channels no longer display as a bare "|"** when their name cleans down to
  punctuation. (MB-377)
- **Now/next EPG labels in the grid follow what you're browsing** instead of
  stopping at the first 60 channels. (MB-379)

### Known issues
- The Guide lists only channels that have EPG data (~5% of a large channel
  list); the rest are in Live TV browse. A fix is scoped. (MB-382)
- A provider category that mixes movies and series still appears split across
  the Movies and Series tabs. (MB-375)
- On a TV remote you can't yet cross a long programme block in the guide; touch
  works. (MB-371)

## 1.6.2 (versionCode 26) — 2026-08-23

Patch on 1.6.1. Fixes a content-visibility bug found in field testing.

### Fixed
- **Large categories no longer stop at 1,000 items.** The browse list capped
  at 1,000 entries per category, so a big category (some run to thousands)
  showed only its first slice — the rest were reachable only through search.
  Categories now load in full as you scroll. Internally, tile "resume"
  progress moved to a whole-table lookup so the cap could be lifted without
  tripping SQLite's variable limit on older devices. (MB-374)

### Known issues
- A provider category that mixes movies and series appears split across the
  Movies and Series tabs (each tab shows its own half); both halves are still
  reachable via the other tab and via search. (MB-375)
- On the TV remote you cannot yet cross a long programme block in the guide;
  touch works. (MB-371)

## 1.6.1 (versionCode 25) — 2026-08-23

Patch on 1.6.0. Fixes one OTA-relevant issue found while verifying the update
path.

### Fixed
- **App updates no longer drop pending recording alarms.** Android clears an
  app's alarms when its package is replaced; the app now re-arms every
  scheduled recording on `MY_PACKAGE_REPLACED`, the same way it already does
  on reboot — so a recording scheduled before an OTA update still fires.
  (MB-373)

## 1.6.0 (versionCode 24) — 2026-08-23

Continues the 1.x line from 1.5.3. A large data-safety and stability release:
recording actually works now, silent write-loss is fixed, and cold start no
longer wipes the catalogue. Still short of a 1.0 "everything verified" claim —
see Known issues.

### Fixed
- **Recordings wrote 0 bytes and reported success.** The streaming transport
  buffered the whole HTTP body before returning, so a live stream never
  reached the recorder's write loop. Fixed and verified end-to-end on
  hardware — a scheduled recording now saves a real file. (MB-355)
- **Writes were silently lost.** A database transaction spanning coroutine
  suspension could orphan the primary connection, dropping favourites, watch
  history, resume points and recording state until restart. (MB-356)
- **Every cold start wiped and rebuilt the whole catalogue** (~15 minutes with
  an empty app). Sync-on-start now honours the source's interval. (MB-363)
- **Guide navigation:** D-pad RIGHT from a channel now enters its programmes,
  and UP/DOWN move between channels inside the timeline. (MB-361, MB-362)
- **Phone playback:** the video player was landscape on browse but pinned
  upright in the player, so a reverse-landscape phone played upside-down.
  Consistent now. (MB-354)
- Player chrome, duration formats and gesture labels are now localized in
  Arabic, French and Spanish. (MB-359, MB-366, MB-370)
- Deleting the bundled sample source is now permanent. (MB-352)

### Added
- **Phone swipe controls** in the player: brightness (left), volume (right),
  seek (horizontal) — phone only, inert on TV. (MK.11.2)
- **Guide row height** setting: Compact / Standard / Comfortable. (MK.15)
- **Open-source licences** and privacy/terms, readable in-app. (MB-367, MB-372)

### Security / release hygiene
- The updater now verifies the downloaded APK's SHA-256 and refuses
  non-HTTPS download links. (MB-369)
- Release builds fail fast if crash reporting is misconfigured, and archive
  their R8 mapping for later crash de-obfuscation. (MB-364)
- Android lint is now a CI gate.

### Known issues
- **On the TV remote you cannot yet cross a long programme block in the guide**
  to reach a later programme; a fix is scoped (convert the lane to a lazy
  row). Touch works, and scheduling from a reachable programme works. (MB-371)
- Phone build is unverified on real phone hardware.
- App updates clear pending recording alarms until the app is next opened. (MB-373)
