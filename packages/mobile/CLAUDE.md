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
| Playback | react-native-video 6 (ExoPlayer/Media3) + FFmpeg ExoPlayer extension (M8R — codec gap) |
| Navigation | React Navigation 7 — collapsed to `Shell` + `FullscreenPlayer` in M4R |
| State | Zustand 5 |
| Database | op-sqlite |
| Data fetching | TanStack Query 5 |
| Styling | StyleSheet + theme module (src/styles/theme.ts) |
| Animations | Reanimated 3 |
| Lists | FlashList (Shopify) + paged SQL windows |
| Image cache | `CachedImage` wrapper (M4R.11) — all `<Image>` routed through it |
| Crash | Sentry |
| Credentials | react-native-keychain (M7R) |
| Notifications | Notifee (M6R) |
| Hex visuals | **Outline-only** — stroked `react-native-svg` `<Polygon>` for the channel-logo frame. No `MaskedView`, no clipping children into a hex path (that's what killed perf on 2026-04-12). Rows stay rectangular; only the logo container is hex-framed. See M4R.D in [PRODUCTION_PLAN_ANDROID.md](../../PRODUCTION_PLAN_ANDROID.md). |
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
│   ├── App.tsx                # Error boundary, cached-first boot, splash (rewritten M4R.6)
│   ├── sentry.ts
│   ├── navigation/
│   │   └── RootNavigator.tsx  # Collapsed in M4R.2 to `Shell` + `FullscreenPlayer` routes only
│   ├── shell/                 # NEW in M4R — the single state-driven surface
│   │   ├── HomeShell.tsx      # Left rail + content panel + persistent MiniPlayer
│   │   ├── AppSidebar.tsx     # Global nav (Home / Live TV / TV Guide / Movies / Series / Favorites / Recordings / Downloads / Settings) + Search + Sources (M4R.D.1, replaced LeftRail)
│   │   ├── CategoryFilterPanel.tsx # Middle column: "Filter groups" input + group list w/ counts. TV inline, phone drawer (M4R.D.2)
│   │   ├── ContentPanel.tsx   # Paged SQL-backed FlashList
│   │   ├── InfoPanel.tsx      # Right-side context: now/next, metadata, actions
│   │   ├── MiniPlayer.tsx     # Persistent corner surface; expands to fullscreen
│   │   ├── SearchOverlay.tsx  # Modal overlay, not a separate screen
│   │   └── SettingsModal.tsx  # Modal overlay
│   ├── screens/
│   │   └── FullscreenPlayer.tsx   # The ONLY other route post-M4R
│   ├── components/
│   │   ├── cards/             # ChannelTile (flat), ContentCard. HexCard REMOVED mobile-side.
│   │   ├── layout/            # PageHeader only; AppLayout/Sidebar deleted in M4R.1
│   │   ├── tv/                # TvButton, focus helpers
│   │   └── phone/             # Phone-specific (added M9R)
│   ├── focus/                 # Rebuilt M4R.10 — Focusable primitive + focus-memory module
│   ├── image/                 # NEW M4R.11 — CachedImage wrapper (disk + memory LRU)
│   ├── player/                # Video wrapper; FFmpeg extension jniLibs land in M8R
│   ├── db/                    # op-sqlite + migrations + queries.ts (paged SQL, M4R.4)
│   ├── services/              # Keystore, notifications, cast (M6R–M9R)
│   ├── stores/                # Zustand: sources-store, favorites-store, history-store, shell-store
│   ├── http/fetch-http-client.ts
│   ├── storage/               # AsyncStorage — hydration flags + last-view ONLY
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

## Current State (snapshot — 2026-04-19, REBOOT)

**As of commit `f4a657c` (master):** M4R.0 (perf checkpoint, flat tiles) + M4R.1 (delete) + M4R.2 (navigator collapse) + M4R.4/M4R.5 (paged SQL LeftRail + ContentPanel) + M4R.7 (persistent MiniPlayer + hidden-when-empty slot) + Sources modal are all landed on master. **Next up: M4R.8 (InfoPanel)**, then M4R.6 (cached-first boot, MB-15 fix), M4R.10 (focus primitive rebuild), M4R.11 (CachedImage wrapper).

Phases 0–M3 landed previously. M4.1 `ContentGrid` (FlashList) + M4.2 `CategorySidebar` (language grouping) were landed then the shell buckled under real-device testing; the 2026-04-19 audit reset the plan to the M4R reboot.

**What's done and kept:**
- `@yancotv/core` — parsers, clients, classifier, XMLTV, PIN hashing, store factories. Stable.
- op-sqlite persistence (content, FTS, favorites, history, sources, settings) — stable.
- React Navigation 7 wiring — kept, but routes collapse in M4R.2.
- Sentry crash reporting — stable.
- Release APK pipeline (Gradle, signing) — stable.

**What's being rebuilt in M4R (in flight):**
- Navigation shape — drawer + bottom-tabs + 4 stack screens collapse to one `Shell` + one `FullscreenPlayer` (M4R.2).
- Most screens deleted — `HomeScreen`, `LiveTvScreen`, `MoviesScreen`, `SeriesScreen`, `SearchScreen`, `FavoritesScreen`, `ChannelDetailScreen` all go. Their logic folds into `HomeShell` + overlays (M4R.1).
- Layout components deleted — `AppLayout`, `Sidebar`, old `PageHeader`, old `DetailHero`, old `DetailTabBar` (M4R.1).
- Player — `PlayerScreen` is replaced by `FullscreenPlayer` backed by persistent `MiniPlayer` surface (M4R.7). Fixes MB-13 (double-back) and the "where did the picture go" state confusion.
- Rendering model — paged SQL queries feed FlashList, no more hydrating 10K items into Zustand (M4R.4).
- Boot — cached-first path, removes the blocking hydration gate (M4R.6). Fixes MB-15.
- Focus — `Focusable` primitive + `focus-memory` module rewritten from scratch (M4R.10).
- Images — new `CachedImage` wrapper; every `<Image>` routes through it (M4R.11).
- Hex cards — deleted mobile-side. `ChannelTile` flat rectangle shipped 2026-04-19.

**Known bugs (mobile bug register):**

- **MB-13** player takes two back presses to close; surface state desyncs between routes — fix in M4R.7 via persistent MiniPlayer + single fullscreen route.
- **MB-14** HEVC-main10 / AC3 / EAC3 / DTS / TrueHD decode as audio-only on ~30% of streams — fix in M8R via FFmpeg ExoPlayer extension (NDK build, vendored jniLibs).
- **MB-15** first-frame blocked by hydration gate — fix in M4R.6.
- **MB-16** SearchScreen crashes during fast typing — **FIXED 2026-04-19** (FlatList virtualization). Search path rebuilt entirely as `SearchOverlay` in M4R.
- **MB-17** navigation sluggish across the whole app — fix in M4R (paged SQL + collapsed navigator + CachedImage).
- **MB-18** desktop Electron boot `ERR_UNSUPPORTED_DIR_IMPORT` — **FIXED 2026-04-19** (explicit `.js` extensions across `@yancotv/core` internal imports).

**Player decision (unchanged, 2026-04-18 → reaffirmed 2026-04-19):** V1 ships on `react-native-video` 6 / Media3. The codec gap that made users hate the app (audio-only streams) is closed with the ExoPlayer FFmpeg decoder extension in M8R — clone `androidx/media`, NDK-build `decoder_ffmpeg` for armeabi-v7a / arm64-v8a / x86_64, vendor the libs into `android/app/src/main/jniLibs/`. Not a Fabric VLC wrapper — wrong tradeoff (40–90 MB APK, unmaintained autolink path).

**Working directive (user, confirmed again 2026-04-19):** "rebuild from zero for these things. i don't want patching. i want clean building." The M4R reboot follows this to the letter — delete first, rebuild second. See the delete-before-add rule in the architecture list.

**Next-session start point:** commit the 2026-04-19 perf checkpoint (flat ChannelTile, SearchScreen FlatList, desktop ESM fix, plan + CLAUDE.md rewrites) as one logical commit, then start M4R.0 (perf checkpoint verification) → M4R.1 (delete) → M4R.2 (collapse navigator). See [PRODUCTION_PLAN_ANDROID.md](../../PRODUCTION_PLAN_ANDROID.md) § "M4R Shell reboot" for the task list.

## Architecture Rules (Mobile — non-negotiable, 14 rules as of 2026-04-19 reboot)

Mirrored from [PRODUCTION_PLAN_ANDROID.md § Architecture Rules](../../PRODUCTION_PLAN_ANDROID.md#architecture-rules-mobile):

1. **No duplicated business logic.** Parsers, clients, classifier, title-cleaner, EPG, catchup, parental hashing, store factories — all live in `@yancotv/core`. If you need it on both platforms, put it in core first.
2. **Persistence goes through op-sqlite.** AsyncStorage is ONLY for small app-level keys (hydration flags, last-view). Never for content, EPG, favorites, or lists of any size.
3. **One screen, state-driven.** Post-M4R the navigator holds two routes only: `Shell` and `FullscreenPlayer`. Panels (AppSidebar, ContentPanel, InfoPanel, MiniPlayer) are state-driven regions of `HomeShell` — not stack screens. Overlays (search, settings) are modals, not screens.
4. **Paged SQL for content lists.** `ContentPanel` and every other list backed by content/EPG tables uses `db/queries.ts` with `LIMIT/OFFSET` (or keyset) paging. Never hydrate 10K+ rows into Zustand. Zustand holds UI state, cursors, and selection — not bulk data.
5. **Persistent MiniPlayer surface.** Playback mounts on a single React Native SurfaceView that lives through navigation. Expanding to `FullscreenPlayer` does NOT unmount the player. Fixes the double-back problem.
6. **Cached-first boot.** First frame paints from cached last-view immediately. Hydration, SQLite migrations, and EPG refresh run in the background — they never block paint. No more "SQLite migration thing takes long time" boot.
7. **Every `<Image>` goes through `CachedImage`.** The `src/image/CachedImage.tsx` wrapper does disk + memory LRU. No raw `<Image source={{uri}} />` in components. Fixes logo/poster jank during scroll.
8. **All focus through one primitive.** One `<Focusable>` or `TVFocusGuideView` wrapper. Focus memory lives in `src/focus/focus-memory.ts`, not in individual screens.
9. **No credentials in AsyncStorage or SQLite in plaintext.** Use `react-native-keychain` (Android Keystore) for username/password/MAC.
10. **Single-source theme.** Every color comes from `src/styles/theme.ts`. No inline hex.
11. **TV vs phone branching at the shell/component level only.** No `if (Platform.isTV)` inside stores, services, data modules, or core.
12. **No emoji glyphs in UI.** SVG icons (`react-native-svg`) for everything cross-platform.
13. **Zustand stores mirror desktop shapes.** If desktop has `player-store.play(url, title, contentId)`, mobile has the same signature. Core extraction stays trivial.
14. **Delete-before-add.** When a screen, component, or store is being replaced, the old file is deleted in the same commit as the replacement. No "old + new side by side for now" drift. M4R.1 is a pure deletion commit on purpose.

**Codec gap (M8R):** Media3 alone ships ~95% of IPTV streams. The remaining ~30% that show audio-only on real provider feeds (HEVC-main10, AC3/EAC3, DTS, TrueHD) are closed with the ExoPlayer FFmpeg decoder extension — clone `androidx/media`, NDK-build `decoder_ffmpeg` for armeabi-v7a / arm64-v8a / x86_64, vendor libs into `android/app/src/main/jniLibs/`. Not a VLC Fabric wrapper (wrong tradeoff).

## TV vs Phone Handling

The single APK adapts via:

- **Manifest:** Both `LEANBACK_LAUNCHER` and standard `LAUNCHER` intent-filters declared. `android.software.leanback` required=false, `android.hardware.touchscreen` required=false.
- **Code:** `Platform.isTV` from `react-native-tvos` branches the `HomeShell` layout (TV: left rail + content panel + info panel + mini-player; phone: stacked with drawer-like overlays in M9R) and gates TV-only components (`TvButton`, focus memory). It does NOT change the set of routes — the route graph is identical on both form factors.
- **Never:** don't branch inside a store action, a parser, an HTTP call, a SQL query, or `@yancotv/core`.

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

Post-M4R Zustand holds **only**: selection state (active category, active item, active source), UI flags (overlay open, focus memory cursor), and the shell layout. Content and EPG stream out of SQLite via `db/queries.ts` paged cursors — never cached whole in Zustand.

Hydration: cached last-view (active category, selected item) lands synchronously from AsyncStorage so `HomeShell` paints immediately. SQLite + EPG refresh kick off in the background. No blocking hydration gate.

Never persist 10K-item arrays to AsyncStorage. This caused an `SQLITE_FULL` crash on Android before `gradle.properties: AsyncStorage_db_size_in_MB=64` was added.

## Testing

- **Unit tests (Jest):** stores, parsers via core, URL builders, classifier
- **Manual QA:** `packages/mobile/tests/MANUAL_QA.md` is created in M9 and executed against 5 real devices before each release
- **E2E (Detox):** optional, smoke flow only after M9

The 725-test core suite (desktop-side) validates every shared module — don't duplicate those tests here.

## Common Tasks

### Add a new "screen" (post-M4R, this usually means a panel or overlay — not a route)
1. **Ask first: is it a route?** The only routes are `Shell` and `FullscreenPlayer`. If the answer is no, it's either a panel in `HomeShell` or a modal overlay.
2. **Panel:** add under `src/shell/` (e.g. a new sub-panel of `ContentPanel`). Wire into `HomeShell` layout and shell-store selection state.
3. **Overlay:** add as a modal under `src/shell/` (see `SearchOverlay`, `SettingsModal`). Triggered from shell-store flags.
4. **Truly a new route:** justify it in a plan update first. New routes unmount the `MiniPlayer` surface by default and need an explicit reason.
5. Ensure it has a first-focus element for TV.
6. Add entry to the parity matrix in `PRODUCTION_PLAN_ANDROID.md` if it's new vs. desktop.

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
- Do not load content or EPG arrays into AsyncStorage or Zustand — paged SQL only
- Do not reintroduce a Zustand-based router, an `AppLayout` shell, or per-content-type screens (`LiveTvScreen`, `MoviesScreen`, etc.) — the shell is one `HomeShell`
- Do not reintroduce full hex clipping on mobile (`MaskedView`, `@react-native-masked-view/masked-view` on list items) — that caused the 2026-04-12 GPU regression. Hex **outlines** on the logo container only are fine (M4R.D) — stroked SVG polygon, no child clipping.
- Do not add raw `<Image>` — always `CachedImage`
- Do not unmount the player when switching panels — `MiniPlayer` is a persistent surface
- Do not block paint on SQLite migrations, EPG refresh, or network — cached-first boot
- Do not hardcode colors, spacing, or radii — use `theme.ts`
- Do not call mpv-specific APIs or assume ffmpeg subprocess — Android uses Media3 + FFmpeg decoder extension (M8R), not a subprocess
- Do not check large binaries into `packages/mobile/` (APK outputs go to `dist-apk/` at the root, gitignored)
- Do not leave uncommitted work for more than a day — Phase 2's 13-file drift and the shell buckling under it are both cautionary tales
- Do not "patch" the M4R rebuild items — user directive is "rebuild from zero, don't patch." Delete first, rebuild second, in the same commit (rule 14).
