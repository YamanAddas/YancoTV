# YancoTV — Tech Stack & Skills Reference

YancoTV ships as two sibling apps — an Electron desktop and a React Native mobile build — on top of a shared TypeScript core. This reference covers the tech surface of both.

---

## Shared Core (`@yancotv/core`)

Platform-agnostic TypeScript consumed by both desktop and mobile via `workspace:*`. Everything here is pure TS with `zod` as the only runtime dep.

| Module | Purpose |
|---|---|
| M3U parser | Streaming parser (BOM, attributes, catchup tags, EPG URL extraction) |
| XMLTV parser | Async streaming EPG parser (gzip, chunked yields) |
| Xtream client | Xtream Codes API (auth, live/VOD/series streams, URL builders) |
| Stalker client | Stalker/Ministra Portal API (MAC-based auth, paginated fetch) |
| Content classifier | Heuristic live/movie/series classification |
| Title cleaner | Provider-noise stripping, year/season/episode extraction |
| Catchup URL builder | Xtream timeshift + M3U catchup pattern expansion |
| Parental PIN | scrypt hashing (shared params) |
| Types + Zod schemas | Source, ContentItem, Episode, EpgProgramme, Result<T>, etc. |
| HTTP client interface | Platform-agnostic contract; desktop and mobile each implement |

**Discipline:** if it's needed on both platforms, it lives here first. No duplicated parsers, no forked classifier logic.

---

## Desktop — Core Technologies

| Layer | Technology | Purpose |
|---|---|---|
| Shell | Electron 41+ (hardened) | Window management, IPC, system integration |
| Frontend | React 18 | UI rendering in sandboxed renderer |
| Language | TypeScript 5 (strict) | Type safety across main + renderer |
| Styling | Tailwind CSS 3 | Utility-first CSS |
| State | Zustand 5 | Hook-based state in renderer |
| Database | better-sqlite3 (WAL + FTS5) | Synchronous SQLite in main process |
| Playback | mpv via JSON-RPC over named pipes | Stream playback (HLS, DASH, MPEG-TS) |
| Media tools | ffmpeg | Recording, downloading, subtitle extraction |
| Bundler | Vite 6 (renderer), esbuild (preload), tsc (main) | Fast dev + prod builds |
| Packaging | electron-builder | NSIS + portable Windows installers |
| Testing | Vitest + Playwright | Unit + E2E |
| Linting | ESLint + Prettier | Code quality |
| Package manager | pnpm | Workspace tooling (enforced via preinstall) |
| Validation | Zod | IPC input validation + parsed playlist validation |
| Routing | React Router 7 | Renderer routing |
| Data fetching | TanStack Query 5 | Caching async IPC calls |
| Virtualization | react-virtuoso | Large list rendering (10K+ channels) |
| Animations | Motion (framer-motion successor) | Content Detail page transitions |

---

## Mobile — Core Technologies

| Layer | Technology | Purpose |
|---|---|---|
| Framework | React Native 0.85 (`react-native-tvos` fork) | Single APK for TV + phone |
| Language | TypeScript 5 (strict) | Same typing story as desktop |
| Playback | react-native-video 6 (ExoPlayer/Media3 backend) | HLS, DASH, MPEG-TS on Android |
| Navigation | React Navigation 7 (drawer on TV / tabs on phone) | Installed in M3 |
| State | Zustand 5 | Same store shapes as desktop |
| Database | op-sqlite (JSI-based) | WAL + FTS5; byte-identical schema to desktop (M2) |
| Data fetching | TanStack Query 5 | Caching DB + HTTP calls |
| Styling | StyleSheet + `src/styles/theme.ts` | No NativeWind, no styled-components |
| Animations | Reanimated 3 | M4+ animations, hex card transitions |
| Lists | FlashList (Shopify) | Virtualized grids/rails for 10K+ items |
| Credentials | react-native-keychain | Android Keystore-backed credential encryption |
| Notifications | Notifee | EPG reminders (M6) |
| Hex clipping | `@react-native-masked-view/masked-view` | SVG clipPath doesn't work on RN Views |
| Crash reporting | Sentry | Already wired |
| Build | local Gradle (EAS later) | Debug + signed release APK |

### Android manifest essentials

- `<category android:name="android.intent.category.LEANBACK_LAUNCHER" />` for Android TV launcher visibility
- `<category android:name="android.intent.category.LAUNCHER" />` for phone home-screen visibility
- `<uses-feature android:name="android.software.leanback" android:required="false" />`
- `<uses-feature android:name="android.hardware.touchscreen" android:required="false" />`
- `android:usesCleartextTraffic="true"` (many IPTV providers are HTTP-only)
- `gradle.properties`: `AsyncStorage_db_size_in_MB=64`, 2GB JVM heap for builds

---

## Domain Knowledge Areas

### IPTV Protocols & Formats

- **M3U/M3U8:** Extended M3U playlist format. `#EXTINF:` lines carry metadata; following line is the stream URL. Key attributes: `group-title`, `tvg-logo`, `tvg-id`, `tvg-name`, `catchup`, `catchup-source`, `catchup-days`.
- **Xtream Codes API:** REST over `/player_api.php` with `username`, `password`, `action` params. Actions: `get_live_categories`, `get_live_streams`, `get_vod_categories`, `get_vod_streams`, `get_series_categories`, `get_series`, `get_series_info`. Returns JSON.
- **Stalker/Ministra Portal:** MAC-address-based auth. Endpoints under `/portal.php` or `/server/load.php`. Returns JSON. Paginated channel fetch. Distinct session token flow.
- **EPG (XMLTV):** XML with `<channel>` and `<programme>` elements. Start/stop times in local format `20260418120000 +0000`. Often served gzipped (`.xml.gz`).
- **Stream protocols:** HLS (`.m3u8`), DASH (`.mpd`), MPEG-TS (`.ts`), plain MP4/MKV. Desktop: mpv handles all. Mobile: ExoPlayer/Media3 handles HLS/DASH/TS natively; MP4/MKV via Media3 progressive source.

### Content Classification

- **Live TV:** No defined duration (M3U `-1` or missing), grouped under category names like "US | Sports", "UK | News".
- **Movies (VOD):** Single file with duration. Xtream: `get_vod_streams` endpoint. M3U heuristics: group-title contains VOD/Movie/Film, URL extension `.mp4`/`.mkv`.
- **Series:** Multi-episode. Xtream: dedicated series endpoints. M3U: regex on title for `S\d+E\d+` or `Season \d+` patterns.

### Title Cleaning Patterns

- Quality tags: `[HD]`, `(4K)`, `FHD`, `SD`, `H.265`, `HEVC`
- Country/language prefixes: `US:`, `UK |`, `AR -`, `EN |`
- Provider tags: `[MULTI]`, `[BACKUP]`, `(NEW)`, `*NEW*`
- Numbering: leading `001.`, `123 |`
- Trailing dots, duplicate spaces, bracket noise

### Subtitle Ecosystem

- **OpenSubtitles REST API:** Search by file hash or title; language filtering (EN, AR). Rate-limited — cache results in SQLite.
- **Formats:** SRT (most common), VTT (web), ASS/SSA (styled). Desktop mpv supports all. Mobile react-native-video supports SRT + WebVTT via `textTracks` prop.
- **Future:** Whisper (OpenAI / local) for auto-generated subtitles; translation APIs for AR↔EN.

---

## External APIs

| API | Purpose | Desktop phase | Mobile milestone |
|---|---|---|---|
| Xtream Codes | Source ingest | Sprint 3 (DONE) | M1 (DONE) |
| Stalker Portal | Source ingest | Sprint 11 (DONE) | M1 (DONE) |
| XMLTV feeds | EPG data | Sprint 7 (DONE) | M6 |
| TMDb | Movie/show metadata, posters, cast | Sprint 14 (DONE) | M7 |
| OpenSubtitles | Subtitle search/download | Sprint 15 (DONE) | M7 |
| Whisper (later) | Auto-subtitle generation | Phase 5 | Post-release |

---

## Platform Considerations

### Windows (Desktop — Phase 1)

- mpv: ship `mpv.exe` + `mpv-2.dll` bundled (downloaded via `scripts/download-mpv.ps1`)
- ffmpeg: bundle `ffmpeg.exe` for recording/downloading
- SQLite: native module via better-sqlite3; ABI rebuild on install
- Packaging: NSIS installer + portable `.exe` via electron-builder
- Auto-update: electron-updater (Sprint 18)

### Android TV + Google TV + Fire TV (Mobile — Phase 4, M1→M9)

- Single APK adapts via `Platform.isTV` branching at navigator/component level
- **D-pad focus:** every screen declares a first-focus element via `hasTVPreferredFocus`; rails wrap in `TVFocusGuideView` so D-pad escapes cleanly; `useFocusEffect` restores focus on navigation
- **Playback:** ExoPlayer/Media3 via react-native-video 6. `viewType={1}` forces TextureView (fallback for older Fire TV Stick)
- **Player screen root must be `<View>`, never `<Pressable>`** — Pressable changes Android SurfaceView z-order and the overlay eats touches
- Buffer config: 15–50s window, 2.5s threshold (tuned for IPTV HLS)
- Leanback launcher intent-filter required for TV launchers to list the app
- Fire TV quirk: stricter memory limits, older WebView — test on real Firestick 4K, not just emulator

### Android Phone + Tablet (Mobile — Phase 4)

- Same APK, bottom-tab navigator instead of drawer (`Platform.isTV === false`)
- Touch-first interaction replaces D-pad focus — FlashList with pull-to-refresh, swipe gestures (M8)
- Picture-in-Picture (PIP), Google Cast (M8) — mobile-native wins over desktop
- Voice search via Google Assistant / Android Voice (M8)

### Cross-platform (desktop + mobile)

- Database schema is byte-identical — migration SQL files copy verbatim from `src/main/services/migrations/` into `packages/mobile/src/db/migrations/`
- Zustand store action signatures match one-for-one (e.g. `player.play(url, title, contentId)`)
- Zod input validation lives in core; both apps validate against the same rules
- `IPlayer` interface is the contract — desktop implements via mpv, mobile via react-native-video

---

## Testing

### Desktop

- **Vitest:** unit tests for parsers, clients, classifier, title cleaner, services. ABI rebuild runs via `scripts/run-tests.js` before the suite.
- **Playwright:** E2E for app-launch, navigation, search, settings, keyboard shortcuts.
- Target: 725+ passing tests (as of 2026-04).

### Mobile

- **Jest:** unit tests for mobile-specific stores, URL helpers. The 725-test core suite validates shared modules — don't duplicate those tests here.
- **Manual QA:** `packages/mobile/tests/MANUAL_QA.md` (created in M9) executed against 5 real devices before each release (Firestick 4K, Cube, Shield, Chromecast with GTV, Pixel phone).
- **E2E (Detox):** optional, smoke flow only, after M9.

---

## References

- [CLAUDE.md](CLAUDE.md) — Monorepo guide (shared core rules, project structure)
- [packages/mobile/CLAUDE.md](packages/mobile/CLAUDE.md) — Mobile project guide (architecture rules, common tasks)
- [ARCHITECTURE.md](ARCHITECTURE.md) — System architecture (both apps)
- [PRODUCTION_PLAN.md](PRODUCTION_PLAN.md) — Desktop roadmap (Sprints 1–21)
- [PRODUCTION_PLAN_ANDROID.md](PRODUCTION_PLAN_ANDROID.md) — Mobile roadmap (M1–M9)
