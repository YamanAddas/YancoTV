# @yancotv/mobile — Claude Code Guide

This is the mobile-specific project guide. For monorepo context see the [root CLAUDE.md](../../CLAUDE.md). For the roadmap, see [PRODUCTION_PLAN_ANDROID.md](../../PRODUCTION_PLAN_ANDROID.md).

## What This Is

A single React Native app that ships as one APK to:
- Android TV / Google TV (Sony, TCL, Hisense, NVIDIA Shield, Chromecast with Google TV)
- Fire TV (Firestick 4K, Cube, Fire TV Stick)
- Android phones and tablets (8+)

TV and phone share one codebase. UI branches via `Platform.isTV` at the navigator and component level only — never inside business logic.

**Mission:** feature parity with the Electron desktop app, then surpass it on mobile-native capabilities.

## Tech Stack

| Layer | Choice |
|---|---|
| Framework | React Native 0.85 (`react-native-tvos` fork) |
| Language | TypeScript 5 strict |
| Playback | react-native-video 6 (ExoPlayer/Media3) |
| Navigation | React Navigation 7 (installed in M3) |
| State | Zustand 5 |
| Database | op-sqlite (installed in M2) |
| Data fetching | TanStack Query 5 |
| Styling | StyleSheet + theme module (src/styles/theme.ts) |
| Animations | Reanimated 3 (added in M4) |
| Lists | FlashList (Shopify) |
| Crash | Sentry (already wired) |
| Credentials | react-native-keychain (M7) |
| Notifications | Notifee (M6) |
| Hex clipping | @react-native-masked-view/masked-view (M1.2) |
| Build | local Gradle → EAS later |

## Project Layout

```
packages/mobile/
├── android/                   # Native project (committed)
│   ├── app/build.gradle       # versionCode, minSdk=24
│   ├── app/src/main/AndroidManifest.xml  # Leanback launcher declared
│   └── gradle.properties      # AsyncStorage_db_size_in_MB=64, 2GB JVM heap
├── src/
│   ├── index.js               # Entry, Sentry bootstrap
│   ├── App.tsx                # Error boundary, hydration gate, splash
│   ├── sentry.ts
│   ├── navigation/
│   │   ├── RootNavigator.tsx  # React Navigation 7 stack (M3)
│   │   └── TvDrawerContent.tsx # Permanent drawer content for TV
│   ├── screens/               # One file per screen
│   ├── components/
│   │   ├── cards/             # HexCard, ContentCard, hex-frames
│   │   ├── layout/            # PageHeader (sidebar now lives in navigation/)
│   │   ├── tv/                # TV-specific (TvButton, focus helpers)
│   │   └── phone/             # Phone-specific (added M8)
│   ├── focus/                 # Focus primitive (rebuilt M3.8)
│   ├── player/                # Video wrapper + IPlayer-equivalent (expanded M4)
│   ├── db/                    # op-sqlite + migrations
│   ├── services/              # Keystore, notifications, cast (M7–M8)
│   ├── stores/                # Zustand: sources-store, favorites-store, history-store
│   ├── http/fetch-http-client.ts
│   ├── storage/               # AsyncStorage wrappers (small keys only post-M2)
│   ├── styles/theme.ts        # Ported from desktop palette
│   └── assets/
├── package.json
├── tsconfig.json              # Paths: @/* → src/*, @yancotv/core → ../core/src
├── metro.config.js
└── PRODUCTION_PLAN_ANDROID.md  (root-level reference)
```

## Commands

```bash
# From packages/mobile
pnpm start              # Metro on :8081
pnpm android            # Build debug + install on connected device
pnpm typecheck          # tsc --noEmit
pnpm lint
pnpm test               # Jest
```

Release APK:
```bash
cd android && ./gradlew assembleRelease
# Output: android/app/build/outputs/apk/release/app-release.apk
```

Sideload to a TV box:
```bash
adb connect <tv-ip>:5555
adb install -r app-release.apk
```

Metro troubleshooting:
```bash
pnpm start --reset-cache
```

## Current State (snapshot — 2026-04-18)

- Phase 1 (scaffold + debug + release APK + Sentry) **DONE** — commits `29cbbc2`, `7533c24`, `2ad3fad`
- M1.1 Phase 2 rewrite (theme, layout, hex cards, full player, HTTP client, all screens) **DONE** — commit `5f0edbf` through `9b98eeb`
- M1.2 real hex clipping via MaskedView **DONE** — `5990c0c`
- M1.3 XMLTV extracted to core with pako **DONE** — `69e0cff`
- M1.7 Parental PIN hashing in core **DONE** — `0fc5da6`
- M1.8 Zustand store factories in core **DONE** — `94b7eac`
- M1.4 / M1.5 / M1.6 (title-cleaner, classifier, catchup URL-builder full parity) **DEFERRED** to M7 (needs settings UI to matter)
- **M2 op-sqlite + migrations** **DONE** — `c44599b` (content + FTS) · `cac9cde` (favorites) · `b45b974` (history) · `0b93214` (sources) · `b8bec53` (settings). MB-11 fixed at root; `KEY_CHANNELS` AsyncStorage path deleted.
- **M3 React Navigation 7** **DONE** — `a87c726`. Root native-stack (Main / Detail / Player). Main = permanent Drawer on TV, bottom tabs on phone. `nav-store.ts`, `ScreenRouter.tsx`, old `AppLayout.tsx` + `Sidebar.tsx` all deleted.
- **M4 browse parity — NEXT** (ContentGrid, CategorySidebar, full ContentDetail, resume playback)

**Known bugs:**

- MB-11 channels re-sync every launch: **FIXED** (M2 — op-sqlite content store).
- MB-12 VLC build crash: **OBSOLETE** — VLC dropped from V1 scope; see Decision Log below.

**Player decision (2026-04-18):** V1 ships on `react-native-video` 6 / Media3. VLC was explored and dropped: no maintained RN VLC library works on RN 0.85 + tvos without native-module work (razorRun's lib autolink-breaks under RN 0.83+; jboz's Kotlin rewrite is 5-star unproven; no TheWidlarzGroup/Expo/community VLC module exists). Clean-path options are a custom Fabric wrapper over `libvlc-all:3.6.0` (3–5 days + ~40–90 MB APK) or the ExoPlayer FFmpeg decoder extension (clone `androidx/media`, NDK-build) — neither earns its keep for V1 vs Media3, which already handles ~95% of IPTV streams (what TiviMate / IPTV Smarters actually ship on). The 5% codec gap (AC3/EAC3/DTS/TrueHD) is a post-V1 ticket driven by real user reports, not speculative work.

**Working directive (user, 2026-04-18):** "rebuild from zero for these things. i don't want patching. i want clean building." Applied to op-sqlite persistence (M2) and React Navigation (M3). No patch-package entries.

**Next-session start point:** M4.1 — `ContentGrid` on FlashList, replacing the FlatList grid in `ChannelListScreen.tsx`. Virtualized, dynamic columns from window width, TV-focus-aware. Then M4.2 `CategorySidebar` + M4.3 full `LiveTvScreen`. Build one task per commit.

## Architecture Rules (Mobile — non-negotiable)

Mirrored from [PRODUCTION_PLAN_ANDROID.md § Architecture Rules](../../PRODUCTION_PLAN_ANDROID.md#architecture-rules-mobile):

1. **No duplicated business logic.** Parsers, clients, classifier, title-cleaner, EPG, catchup, parental hashing, store factories — all live in `@yancotv/core`. If you need it on both platforms, put it in core first.
2. **Persistence goes through op-sqlite.** AsyncStorage is ONLY for small app-level keys (hydration flags, last-screen). Never for content, EPG, or favorites.
3. **All navigation through React Navigation.** No ad-hoc store-based routers after M3.
4. **All focus through one primitive.** One `<Focusable>` or `TVFocusGuideView` wrapper — never per-screen custom behavior.
5. **No credentials in AsyncStorage or SQLite in plaintext.** Use `react-native-keychain` (Android Keystore) for username/password/MAC.
6. **Single-source theme.** Every color comes from `src/styles/theme.ts`. No inline hex.
7. **TV vs phone branching at the navigator and component level only.** No `if (isTV)` inside stores, services, or data modules.
8. **No emoji glyphs in UI.** SVG icons (`react-native-svg`) for everything cross-platform.
9. **Zustand stores mirror desktop shapes.** If desktop has `player-store.play(url, title, contentId)`, mobile has the same signature. Makes core extraction trivial.
10. **No ffmpeg subprocess assumptions.** Mobile uses Media3 for anything desktop does via ffmpeg. Live recording is dropped V1.

## TV vs Phone Handling

The single APK adapts via:

- **Manifest:** Both `LEANBACK_LAUNCHER` and standard `LAUNCHER` intent-filters declared. `android.software.leanback` required=false, `android.hardware.touchscreen` required=false.
- **Code:** `Platform.isTV` from `react-native-tvos` branches the root navigator shape (drawer on TV, bottom tabs on phone) and gates TV-only components (`TvButton`, focus memory).
- **Never:** don't branch inside a store action, a parser, an HTTP call, or a SQL query.

TV focus:
- Every screen needs an explicit first-focus element via `hasTVPreferredFocus`
- Every horizontal rail inside a vertical scroll needs a `TVFocusGuideView` wrapper so D-pad can escape cleanly
- Use `useFocusEffect` to restore focus when returning from navigation

## Persistence Model (post-M2)

```
AsyncStorage (small keys only)
  └── app:hydrated, app:last-screen, app:theme (if we add light mode later)

op-sqlite (@ yancotv-mobile.db)
  ├── sources                 # same schema as desktop
  ├── content                 # same schema
  ├── episodes
  ├── favorites
  ├── watch_history
  ├── epg_programmes
  ├── locked_channels, hidden_channels, channel_overrides
  ├── settings                # key-value
  └── content_fts             # FTS5 virtual table

Android Keystore (via react-native-keychain)
  └── source credentials (username, password, MAC address)
```

Migrations are copied verbatim from `src/main/services/migrations/` into `packages/mobile/src/db/migrations/` during M2. Do not maintain two sources of schema — port any schema change to both folders in the same commit.

## HTTP Client

`src/http/fetch-http-client.ts` implements the `HttpClient` interface from `@yancotv/core`:

- XMLHttpRequest-based (not fetch — more control, no polyfill needed)
- URL redaction for password fields in logs + errors
- `pingHost` preflight before sync (prevents hangs on unreachable providers)
- User-Agent defaults to VLC 3.0.20
- Response size limit 50MB
- Retry logic added in M7.9

Use it via:
```ts
import { xhrGet, pingHost } from '@/http/fetch-http-client';
```

Xtream + Stalker clients consume it automatically:
```ts
import { XtreamClient } from '@yancotv/core';
const client = new XtreamClient(fetchHttpClient);
```

## Playback

`PlayerScreen.tsx` uses react-native-video. Key gotchas:

- Root container must be `<View>`, never `<Pressable>` — Pressable changes the Android SurfaceView z-order and the overlay eats touches
- `viewType={1}` forces TextureView (fallback for older Fire TV)
- Buffer config: 15–50s window, 2.5s threshold (tuned for IPTV HLS)
- Stream type detection from URL (`.m3u8` → HLS, `.mpd` → DASH, else MPEG-TS)
- Auto-hide controls after 4s of no input
- `BackHandler` wired; cleanup timer on unmount

Track selection (audio, subtitles) goes through react-native-video's built-in APIs, not desktop's mpv properties. This changes in M4 (player abstraction mirrors desktop `IPlayer`).

## State Stores

Zustand stores hydrate from AsyncStorage on App boot via `App.tsx`'s hydration gate. No persistence of content arrays — they're rehydrated from SQLite (post-M2) or re-fetched.

Never persist 10K-item arrays to AsyncStorage. This caused an `SQLITE_FULL` crash on Android before `gradle.properties: AsyncStorage_db_size_in_MB=64` was added. Persist only the source metadata; load content on demand.

## Testing

- **Unit tests (Jest):** stores, parsers via core, URL builders, classifier
- **Manual QA:** `packages/mobile/tests/MANUAL_QA.md` is created in M9 and executed against 5 real devices before each release
- **E2E (Detox):** optional, smoke flow only after M9

The 725-test core suite (desktop-side) validates every shared module — don't duplicate those tests here.

## Common Tasks

### Add a new screen
1. Create `src/screens/XxxScreen.tsx`
2. Wire into `RootNavigator.tsx` (post-M3)
3. Add to relevant nav group (drawer on TV, tab on phone, or stack-pushed from parent)
4. Ensure it has a first-focus element for TV
5. Add entry to the parity matrix in `PRODUCTION_PLAN_ANDROID.md` if it's new vs. desktop

### Add a new shared module
1. Start in `packages/core/src/` — write the pure-TypeScript version first
2. Add unit tests in `tests/unit/` at the root (desktop runs them)
3. Import from `@yancotv/core` in both `src/main/services/` and `packages/mobile/src/`
4. Delete any duplicated desktop-side or mobile-side code

### Debug a TV focus issue
1. Enable `DEBUG_FOCUS` overlay (add in M3 if not there) — draws the focused element's outline
2. Check if the screen has a `hasTVPreferredFocus` on mount
3. Look for `removeClippedSubviews` on FlatList — can strip focus targets off-screen
4. Wrap troublesome horizontal rails in `TVFocusGuideView` with `destinations={[...]}` so D-pad knows where to go

### Debug a playback issue
1. Check stream type detection in `PlayerScreen.tsx` (log `detectStreamType(url)`)
2. Set `useTextureView` to true via `viewType={1}` for older hardware
3. Check the error object keys (react-native-video error shape varies by platform version)
4. Confirm `cleartextTraffic` still true in manifest (many IPTV providers are HTTP-only)

## What NOT To Do

- Do not add features not in [PRODUCTION_PLAN_ANDROID.md](../../PRODUCTION_PLAN_ANDROID.md) without updating the plan first
- Do not duplicate logic from `src/main/services/` — put it in `@yancotv/core` and consume there
- Do not introduce NativeWind, styled-components, or another styling lib — we use StyleSheet + theme
- Do not load content into AsyncStorage
- Do not reintroduce a Zustand-based router or an `AppLayout` shell — navigation goes through `RootNavigator.tsx`
- Do not hardcode colors, spacing, or radii — use `theme.ts`
- Do not call mpv-specific APIs or assume ffmpeg presence
- Do not check large binaries into `packages/mobile/` (APK outputs go to the `dist-apk/` directory at the root, which is gitignored)
- Do not leave uncommitted work for more than a day — the current 13-file drift is a cautionary tale
