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
│   │   └── ScreenRouter.tsx   # Zustand-based interim (removed after M3)
│   ├── screens/               # One file per screen
│   ├── components/
│   │   ├── cards/             # HexCard, ContentCard, hex-frames
│   │   ├── layout/            # AppLayout, PageHeader, Sidebar
│   │   ├── tv/                # TV-specific (TvButton, focus helpers)
│   │   └── phone/             # Phone-specific (added M8)
│   ├── focus/                 # Focus primitive (rebuilt M3.8)
│   ├── player/                # Video wrapper + IPlayer-equivalent (expanded M4)
│   ├── db/                    # op-sqlite + migrations (added M2)
│   ├── services/              # Keystore, notifications, cast (M7–M8)
│   ├── stores/                # Zustand: nav-store, sources-store, + future
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

## Current State (snapshot)

At the time of this writing:
- Phase 1 (scaffold + debug + release APK + Sentry) **DONE** — commits `29cbbc2`, `7533c24`, `2ad3fad`
- Phase 2 rewrite (theme, layout, hex cards, full player, HTTP client hardening, all screens polished) **DONE but uncommitted** — 13 modified files + 3 untracked folders sitting on master
- Persistence, full navigation, feature parity: all ahead (M1 → M9 in the roadmap)

**If you're opening this repo for the first time:** your first job is to land [M1.1](../../PRODUCTION_PLAN_ANDROID.md#m1--commit-the-phase-2-rewrite-finish-core-extraction-1-week) — commit the Phase 2 rewrite cleanly. Then continue with M1.2 onward.

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
- Do not use `ScreenRouter.tsx` pattern for new screens after M3 lands
- Do not hardcode colors, spacing, or radii — use `theme.ts`
- Do not call mpv-specific APIs or assume ffmpeg presence
- Do not check large binaries into `packages/mobile/` (APK outputs go to the `dist-apk/` directory at the root, which is gitignored)
- Do not leave uncommitted work for more than a day — the current 13-file drift is a cautionary tale
