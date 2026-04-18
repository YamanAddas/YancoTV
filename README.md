# YancoTV

Custom IPTV media application. Two sibling apps sharing a common core:

- **Desktop** — Windows-first, built with Electron + React + TypeScript + mpv. Feature-complete (v0.2.0).
- **Mobile** — Android TV + Google TV + Fire TV + Android phone/tablet, built with React Native. In active development toward full desktop parity.

## What is YancoTV?

A premium IPTV player that organizes content from M3U playlists, Xtream Codes, and Stalker Portal sources into a clean, browsable interface — separating Live TV, Movies, and Series into proper sections instead of dumping everything into one list.

Desktop is built to match and surpass apps like TiviMate on Windows. Mobile takes that same experience onto every Android screen — TV, tablet, and phone.

## Monorepo Layout

```
YancoTV/                              # pnpm workspace root
├── CLAUDE.md                         # Monorepo guide
├── ARCHITECTURE.md                   # System architecture (both apps)
├── PRODUCTION_PLAN.md                # Desktop roadmap
├── PRODUCTION_PLAN_ANDROID.md        # Mobile roadmap (M1→M9)
├── CHANGELOG.md                      # Desktop release notes
├── src/                              # Electron desktop app
├── tests/                            # Desktop tests
└── packages/
    ├── core/                         # @yancotv/core — shared TypeScript business logic
    └── mobile/                       # @yancotv/mobile — React Native TV + phone app
```

`@yancotv/core` holds every platform-agnostic piece: parsers (M3U, XMLTV), API clients (Xtream, Stalker), content classifier, title cleaner, catch-up URL builder, types, Zod schemas. Both apps consume it via `workspace:*`.

## Desktop — Current Features

- **Source Management** — Add M3U files, M3U URLs, Xtream Codes credentials, Stalker Portal MACs. Encrypted credential storage via Electron safeStorage. Multi-source merge + dedup.
- **Content Organization** — Automatic separation into Live TV, Movies, and Series. Category grouping, language-grouped sidebar, sort/filter.
- **Smart Search** — Full-text search (SQLite FTS5) with type filter, autocomplete, history.
- **Browsing** — Virtualized grids for 10K+ channels, cinematic content detail pages (hero + Info/Episodes/Related tabs).
- **Playback** — Stable video playback via mpv: play/pause/seek/volume/mute/speed, aspect ratio cycling, subtitle + audio track selection, external subtitle loading, channel surfing.
- **EPG** — Full XMLTV support with now/next, Guide grid page, auto-refresh, per-source + global EPG URLs.
- **Catch-Up TV** — Xtream timeshift and M3U catchup pattern support.
- **Timeshift** — Pause and rewind live TV.
- **Recording** — ffmpeg-based live recording, scheduled from EPG.
- **Downloads** — VOD download manager with retry/resume, asset bundling (poster/backdrop/.nfo/subtitles).
- **Metadata Enrichment** — TMDb integration: posters, backdrops, cast, descriptions.
- **Subtitles** — OpenSubtitles auto-search + download + appearance config.
- **Parental Controls** — PIN lock (salted scrypt), lock/hide channels, name/logo/group overrides.
- **Favorites + History** — Persistent favorites, watch history with resume position.
- **System Integration** — System tray, auto-update check, backup export/import, crash handler.
- **Settings** — 8 organized tabs (General, Playback, Network, Playlist, EPG, Parental, Shortcuts, About).
- **Full Keyboard + Gamepad** — Every action reachable without a mouse.

See [PRODUCTION_PLAN.md](PRODUCTION_PLAN.md) for the full desktop roadmap and [CHANGELOG.md](CHANGELOG.md) for release notes.

## Mobile — Current State

Phase 1 scaffold + debug/release APK + Sentry are done. Phase 2 rewrite (theme, layout, hex cards, full player, all screens) is in hand. The M1→M9 roadmap takes the app from "working shell" to full desktop parity + mobile-native wins:

- **M1** Commit Phase 2 + finish core extraction
- **M2** op-sqlite + migrations (same schema as desktop)
- **M3** React Navigation 7 + dual layout (TV drawer / phone tabs)
- **M4** Browse parity + Content Detail page + playback resume
- **M5** Search + Favorites + History
- **M6** EPG + Catch-up + Timeshift
- **M7** Settings (8 tabs) + Parental + Polish
- **M8** TV UX polish + Phone-native features (PIP, Cast, gestures, voice)
- **M9** Distribution (Play Store / Fire TV / sideload) + QA

See [PRODUCTION_PLAN_ANDROID.md](PRODUCTION_PLAN_ANDROID.md) for the full mobile roadmap, parity matrix, and architecture rules.

## Desktop — Tech Stack

- Electron 41+ (hardened)
- React 18 + TypeScript 5 + Tailwind CSS 3
- SQLite (better-sqlite3, WAL mode, FTS5)
- Zustand 5 (state), React Query 5 (async caching)
- mpv via JSON-RPC over named pipes
- ffmpeg (recording, downloads, subtitle extraction)
- Vite 6 (bundler), Vitest + Playwright (tests), Zod (validation)

## Mobile — Tech Stack

- React Native 0.85 (`react-native-tvos` fork)
- TypeScript 5 strict, Zustand 5, TanStack Query 5
- react-native-video 6 (ExoPlayer/Media3 backend)
- React Navigation 7 (M3), op-sqlite (M2), FlashList, Reanimated 3
- react-native-keychain (Android Keystore for credentials)
- `@react-native-masked-view/masked-view` for hex-card clipping
- Sentry crash reporting

## Development

### Desktop

```bash
pnpm install      # Install all workspace deps
pnpm dev          # Electron + Vite HMR + tsc watch
pnpm build        # Production build
pnpm package      # Windows installer (NSIS + portable)
pnpm test         # Vitest unit tests
pnpm test:e2e     # Playwright E2E
pnpm lint
```

### Mobile

```bash
cd packages/mobile
pnpm start              # Metro on :8081
pnpm android            # Build + install debug APK on connected device
pnpm typecheck
```

Release APK:
```bash
cd packages/mobile/android && ./gradlew assembleRelease
# Output: packages/mobile/android/app/build/outputs/apk/release/app-release.apk
```

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for:
- Process architecture (Electron main/renderer, React Native bundler/native)
- Data flow (IPC on desktop, op-sqlite direct access on mobile)
- Shared core boundaries and platform abstractions
- Database schema (mirrored between platforms)

## Project Status

**Desktop:** Phase 1 feature-complete (Sprints 1–15 + 17–20 DONE). Sprint 21 stabilization mostly done; Sprint 21.6 human QA against real IPTV sources pending before release sign-off.

**Mobile:** M1 in progress. Phase 2 rewrite sits uncommitted as the first task.

## License

Private / Proprietary
