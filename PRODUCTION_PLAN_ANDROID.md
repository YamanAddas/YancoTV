# YancoTV Android — Production Plan

**Mission:** Ship an Android TV + phone app that feels like TiviMate / Smarters in responsiveness, wears the YancoTV desktop visual language, and reuses our `@yancotv/core` business logic.

This plan is the single source of truth for mobile. Every mobile commit should map to a task here.

---

## Reboot Notice — 2026-04-19

**M1 through M5 as originally scoped shipped a desktop-shaped React Native app.** It works feature-wise (sources add, SQLite persists, favorites/history/search all functional), but the shell is wrong for TV: stack+drawer+tabs nav, seven top-level destinations, a dashboard home, a stacked player, 2:3 poster pages with tabs, hex SVG cards paint-heavy on Android GPU, and all content held in JS memory with per-screen filtering. Result: sluggish navigation, two-press back semantics, audio-only on codec-gap channels, and a dashboard nobody asked for.

**As of 2026-04-19 we pivot.** The new plan is a shell-first reboot along TiviMate's shape, with a stricter "delete-before-add" rule. See [Audit 2026-04-19](#audit-2026-04-19) below for the full list of what's being ripped out.

**What still stands:**
- `@yancotv/core` (parsers, clients, classifier, title-cleaner, parental, store factories, schemas) — unchanged.
- op-sqlite + all migrations + content/favorites/history/settings stores — unchanged.
- React Navigation 7 root (but collapsed to a single screen — drawer + bottom-tabs deleted).
- Metro React-singleton resolver override — stays.
- Android manifest + Leanback intent-filter + keystore signing — stays.
- `@react-native-masked-view/masked-view` — kept for the one place we still want a mask (not hex cards anymore).

**What's being deleted:** see "Audit 2026-04-19 → Delete list."

---

## Strategy

**Target platforms (single APK, dual UX):**
- Android TV 9+ (Sony, TCL, Hisense, NVIDIA Shield)
- Google TV (Chromecast with Google TV, newer Sony/TCL)
- Fire TV (Firestick 4K, Cube, Fire TV Stick) — Fire OS is Android-based
- Android phones/tablets 8+ (same APK, responsive branches via `Platform.isTV`)

**Approach:** Fresh React Native app + shared TypeScript core. **Not** a port of the Electron UI — TV and touch demand different interaction models. Desktop remains Windows-first; Android is a sibling that draws from the same core.

**Shape target: TiviMate's shell, YancoTV's look.** Single screen. Left rail (categories). Right panel (channel list or grid). Top-right (now-playing + mini player). Focus-driven D-pad navigation. No stack transitions between Live / Movies / Series — they're state changes, not screens.

---

## Tech Stack (post-reboot)

| Layer | Choice | Status |
|---|---|---|
| Framework | **React Native 0.85 (`react-native-tvos` fork)** | in place |
| Language | TypeScript strict | in place |
| Playback | **Native Android `PlayerActivity`** hosting Media3 ExoPlayer directly. RN bridges via `PlayerLauncher` NativeModule; JS never mounts `<Video>`. | **LANDED 2026-04-20** (M4R.Player) — replaced react-native-video |
| Codec gap fix | **ExoPlayer FFmpeg extension** (HEVC-main10, AC3/EAC3, DTS, TrueHD) registered on the native Activity's `DefaultRenderersFactory` | **NEW — M8R** |
| Navigation | **React Navigation 7**, stack-only, one top-level screen | reduced — drawer + bottom-tabs deleted |
| State | **Zustand 5** | in place |
| Database | **op-sqlite** (JSI) | in place |
| Data fetching | **TanStack Query 5** | in place |
| Image caching | **react-native-fast-image** or **expo-image** | **NEW — M4** |
| Styling | **StyleSheet + theme module** | in place (theme unchanged) |
| Animations | **Reanimated 3** | M7 (was M8) |
| Lists | **FlashList** (Shopify) | in place — extend to every list post-reboot |
| Focus | **One `<Focusable>` + `TVFocusGuideView`** — rebuilt in M4 | single primitive |
| Crash reporting | **Sentry** | in place |
| Secure credentials | **react-native-keychain** (Android Keystore) | M7 |
| Notifications | **Notifee** | M6 |
| Build | Local Gradle; later EAS Build | in place |

**Explicitly rejected (unchanged):** Flutter, Kotlin Compose-only, NativeWind, AsyncStorage as primary store.

**Explicitly removed from this plan (post-reboot):**
- Hex-card UI on mobile — desktop-only henceforth.
- Dashboard "Home" screen — not how TV apps boot.
- "Continue Watching" rail as its own destination — folds into the one shell.
- Search as a top-level tab — becomes an overlay triggered by the search key / button.
- Sources as a top-level tab — buried under Settings.
- Content-detail as its own stacked screen — becomes a right-side info panel.

---

## Audit 2026-04-19

Ground truth: **6,525 LOC across 24 screens/components**, most of it porting desktop "page" thinking that doesn't belong in a TV app.

### What was ported from desktop that shouldn't have been

1. **Three navigators stacked** (native-stack + drawer + bottom-tabs, ~237 LOC) — every category switch is a React Navigation transition.
2. **Seven top-level destinations** (Home, Live, Movies, Series, Search, Favorites, Sources). TiviMate has **one**.
3. **Separate LiveTvScreen / MoviesScreen / SeriesScreen** (~486 LOC) doing the same grid with a different `type` filter.
4. **HomeScreen "dashboard"** (348 LOC) — desktop PC thinking; TV apps boot into content.
5. **PlayerScreen as a stacked route** (807 LOC) — back-press fights between hiding controls and popping the stack.
6. **ContentDetailScreen + DetailHero + 4 tabs** (1,163 LOC) — movie-app detail pages belong on phones, not TV.
7. **SearchScreen as a tab + 3 result rails** (513 LOC). TV native: press search key → overlay → results.
8. **SourcesScreen as a top-level destination** (505 LOC).
9. **PageHeader "eyebrow + title + subtitle"** magazine styling — wasted vertical pixels on 10-foot UI.
10. **HexCard + hex-frames + MaskedView on every live tile** (600+ LOC). Kills GPU on Android.
11. **CategorySidebar at 605 LOC** — over-featured (language grouping, multi-select, pin/hide).

### Runtime issues compounding the shell

12. Whole channel list held in Zustand, filtered in JS on every screen mount.
13. Stores hydrate eagerly at boot — sources + favorites + history + recent + group-prefs + search-history + settings, before first frame.
14. `useEffect` on focus reloads favorites + history every time Home regains focus.
15. No list virtualization on hero rails until 2026-04-19.
16. No image caching layer — every scroll re-decodes JPEGs.
17. No focus memory across category switches.
18. Boot blocks on SQLite + hydration gate instead of cached-first render.
19. Media3 only — HEVC-main10, AC3, EAC3, DTS, TrueHD fail → audio-only channels.
20. No backpress discipline — PlayerScreen eats first back, second back pops.
21. Console + Sentry overhead in release bundle.
22. No `InteractionManager` usage — heavy work (re-bucket, re-filter) runs during animations.
23. `removeClippedSubviews` on TV can strip focus targets off-screen.

### Delete list (~4,000 LOC net out)

- `src/screens/HomeScreen.tsx`
- `src/screens/MoviesScreen.tsx`
- `src/screens/SeriesScreen.tsx`
- `src/screens/FavoritesScreen.tsx`
- `src/screens/SearchScreen.tsx` (replaced by an overlay component)
- `src/screens/SourcesScreen.tsx` (replaced by a Settings modal)
- `src/screens/ContentDetailScreen.tsx`
- `src/components/detail/DetailHero.tsx`
- `src/components/detail/DetailTabBar.tsx`
- `src/components/detail/EpisodesTab.tsx`
- `src/components/detail/InfoTab.tsx`
- `src/components/detail/RelatedTab.tsx`
- `src/components/detail/SeasonPicker.tsx`
- `src/components/cards/HexCard.tsx`
- `src/components/cards/hex-frames.ts`
- `src/components/layout/PageHeader.tsx`
- `src/components/layout/SortDropdown.tsx`
- `src/components/layout/CategorySidebar.tsx` (rebuilt leaner as part of the shell)
- `src/components/tv/TvButton.tsx`
- `src/navigation/TvDrawerContent.tsx`
- Phone-specific bottom-tab code inside `RootNavigator.tsx`

### Rebuild list (~1,500 LOC net in)

- `src/shell/HomeShell.tsx` — single screen, left rail + right panel + top-right info/player.
- `src/shell/LeftRail.tsx` — flat category list (Live / Movies / Series / Favorites / a favorited-group picker).
- `src/shell/ContentPanel.tsx` — FlashList driven by paged SQL, no JS filter.
- `src/shell/InfoPanel.tsx` — right-side "what's on / metadata" panel replacing the detail screen.
- `src/shell/MiniPlayer.tsx` — corner-docked persistent player that expands to fullscreen.
- `src/shell/SearchOverlay.tsx` — modal overlay triggered by the search key / remote button.
- `src/shell/SettingsModal.tsx` — parental, sources management, EPG config, shortcuts.
- `src/db/queries.ts` — paged SQL helpers (`listByType(type, limit, offset, groupId?)`, `searchFts(q)`).
- `src/focus/Focusable.tsx` — rebuilt as the real primitive.
- `src/focus/focus-memory.ts` — last-focused-per-group store.
- `src/image/cached-image.tsx` — wraps fast-image / expo-image with a single prop surface.

---

## Current Reality (2026-04-19)

| Phase | Status | Evidence |
|---|---|---|
| 0.x Core extraction (types, schemas, parsers, Xtream, Stalker, content, catchup, parental, stores, schemas) | DONE | commits `888f897`, `86c45ed`, `69e0cff`, `0fc5da6`, `94b7eac` |
| 1.x RN scaffold + debug + release APK + Sentry | DONE | commits `29cbbc2`, `7533c24`, `2ad3fad` |
| M1 Phase 2 rewrite + MaskedView hex + core modules | DONE (but hex about to be deleted) | `5f0edbf`–`9b98eeb`, `5990c0c` |
| M2 op-sqlite persistence + migrations | DONE | `c44599b`, `cac9cde`, `b45b974`, `0b93214`, `b8bec53` |
| M3 React Navigation 7 root | DONE (but collapsing to single screen in M4) | `a87c726` |
| M4 browse parity (ContentGrid, CategorySidebar, Movies, Series, Detail, resume) | LANDED AS SCAFFOLDING — being reshaped by the reboot | `3857172`, `734db05`, `a070863`, `68757ac`, `95c26e2` |
| M5 search + favorites + history | LANDED AS SCAFFOLDING — features kept, UI reshaped | session 2026-04-19 |
| Perf stabilization (flat channel tiles, FlatList windowing, render-to-texture on live tiles) | DONE (checkpoint) | session 2026-04-19 |
| **Desktop `ERR_UNSUPPORTED_DIR_IMPORT` from core** | **FIXED 2026-04-19** | all `@yancotv/core` internal re-exports now use explicit `.js` extensions for Node 22 ESM compliance |

---

## Roadmap — Reboot milestones M4R → M10

Each milestone produces a runnable APK with a verifiable new capability. Commit at the end of each. Delete-before-add.

### **M4R — Shell reboot** *(2 weeks, highest priority)*

**Goal:** Rip the desktop-shaped shell out; replace with a single-screen TiviMate-shaped layout. This is the milestone that makes the app feel right. Every subsequent milestone assumes this landed.

| # | Task | DoD |
|---|---|---|
| M4R.0 | Commit the 2026-04-19 perf checkpoint (flat channel tiles, list windowing, hex SVG stripped) | green typecheck + installed APK feels visibly faster than yesterday's build |
| M4R.1 | Delete every file in the [Delete list](#delete-list-4000-loc-net-out) | `pnpm typecheck` still passes; app still boots (with a blank new shell) |
| M4R.2 | Collapse `RootNavigator.tsx` to a single stack: `Shell` + `Fullscreen`. No drawer. No bottom tabs. Hardware back on `Shell` = leave app | boots straight into shell |
| M4R.3 | `HomeShell.tsx` = left rail + right panel + top-right slot. 3-column layout on TV, stacked on phone | compiles and renders empty panels |
| M4R.4 | `LeftRail.tsx` = flat category list: Live, Movies, Series, Favorites, (later) Groups | focusable, remembers last-selected |
| M4R.5 | `ContentPanel.tsx` = FlashList driven by paged SQL, not Zustand `channels.filter(...)` | `db/queries.listByType(type, limit, offset)` returns a page per scroll tick |
| M4R.6 | Boot path: open SQLite → if content rows exist, render shell immediately; refresh sources in background | cold-boot to first render < 1s on Fire TV 4K |
| M4R.7 | `MiniPlayer.tsx` = re-entry tile that re-launches native `PlayerActivity` for the last-played track (no persistent `<Video>` surface) | tapping the tile fires an Intent; playback is a native Activity |
| M4R.Player | **Native Android `PlayerActivity` + `PlayerLauncher` NativeModule** — AppCompat Activity hosts Media3 ExoPlayer, OkHttpDataSource, PlayerView (SurfaceView). `PlayerLauncherModule.launch({url,title,userAgent})` fires an Intent; JS never mounts `<Video>`. Handles BACK/MENU/PLAY_PAUSE key events natively. Activity is `exported="false"`. `react-native-video` removed from deps; patch deleted. | **LANDED 2026-04-20** (`09150e9`) — picture renders on Fire TV (v7a) + phone (arm64). Closes MB-25..MB-29. |
| M4R.8 | `InfoPanel.tsx` = right-side now/next + description for the currently focused row | replaces the deleted `ContentDetailScreen` |
| M4R.9 | `SearchOverlay.tsx` = modal triggered by the TV remote's search button / a Ctrl-K shortcut on phone | results are a single FlashList, SQL-FTS backed |
| M4R.10 | Focus primitive rebuilt in `src/focus/Focusable.tsx` + `focus-memory.ts` | last-focused cell per group is restored on return |
| M4R.11 | `CachedImage` wrapper using `react-native-fast-image` (or `expo-image`) | every `<Image>` in shell pipes through it; scroll no longer re-decodes |

**Ship criterion:** New user opens app, sees channel list within 1s. Pressing Up/Down moves through categories. Pressing Enter on a channel plays it in the corner, Enter again fullscreens it. Back from fullscreen shrinks to corner; back from corner leaves the app. No dashboard, no detail stack, no "Continue Watching" rail — just content.

---

### **M4R.D — Design parity with desktop** *(~5 days, inserts between M4R.7 and M4R.8)*

**Goal:** Match the desktop look shown in the 2026-04-19 photo — three-column shell with hex-framed channel rows, accent cyan focus, quality badges. This is a direction reversal on the earlier "no hex on mobile" rule, scoped narrowly: **hex is allowed as an outline only (stroked SVG polygon, no masking, no clipping of children)**. The GPU cost that killed perf on 2026-04-12 came from `MaskedView` + `@react-native-masked-view/masked-view` clipping every list item — stroked outlines on 50k items are cheap.

| # | Task | DoD |
|---|---|---|
| M4R.D.1 | Expand `LeftRail.tsx` into `AppSidebar.tsx` — logo-in-hex-badge at top, `SearchButton` (opens `SearchOverlay`), full global nav (Home / Live TV / TV Guide / Movies / Series / Favorites / Recordings / Downloads / Settings), Sources button at bottom. Global nav items live in `shell-store` as `navTarget`; content-type categories move into the new `CategoryFilterPanel` | D-pad Down walks the full nav; focus memory remembers last nav item; visually mirrors the desktop sidebar |
| M4R.D.2 | New `CategoryFilterPanel.tsx` — middle column between sidebar and content. Top: "Filter groups" `SearchInput` that filters the list below in-memory. Body: scrollable list of groups with counts sourced from `db/queries.groupsForType(type)`. "All" pinned at top with total count. Active group highlights cyan | on TV: walks with D-pad Left/Right from AppSidebar into the filter panel, then into ContentPanel; on phone: this panel is hidden and surfaces as a **Drawer** triggered from a filter-chevron on `ContentPanel` header |
| M4R.D.3 | `HexChannelRow.tsx` — replaces flat `ChannelTile` for Live TV rows. Three parts, side-by-side: (a) hex-outlined logo container ~64×64 built from `react-native-svg` `<Polygon>` with `stroke={colors.accent}` `fill="none"`, channel logo `<CachedImage>` centered inside at normal opacity (no clipping); (b) channel name + number text; (c) `QualityBadgePills` on the right. Row container has a subtle 1px cyan border at 18% opacity — no hex edge on the row itself (row stays rectangular; only the logo frame is hex) | visually matches photo on real device; scroll stays ≥55 FPS on Fire TV 4K with 50k rows |
| M4R.D.4 | `QualityBadgePills.tsx` — parses channel title with a regex (`/\b(4K|UHD|2160p|1440p|1080p|FHD|720p|HD|SD)\b/gi`, TiviMate-style) and renders 1–3 pills per match. Pills are themed by tier (cyan for 4K/UHD, amber for HD, neutral for SD) | known test titles produce expected badge sets; unknown titles render no pills |
| M4R.D.5 | Theme pass — port the desktop gradient background into `theme.ts` (`colors.bg` becomes two stops: top `#0a0a1a`, bottom `#151528` or whatever the desktop palette produces), accent matches desktop cyan exactly (read from `src/renderer/styles` + `tailwind.config.js`), active-nav green matches desktop | side-by-side APK vs desktop screenshot in daylight: feels like the same app |

**Ship criterion:** Side-by-side photo of the mobile APK and the 2026-04-19 desktop screenshot looks like the same product on two form factors — hex-outlined logos, cyan accents, three-column TV layout, phone drawer for groups, quality pills on 4K/UHD channels.

**Non-goals:**
- Do NOT reintroduce `MaskedView` or clip children through a hex path anywhere.
- Do NOT hex the row outline itself — only the logo container. A hex-edged 50k-row list is a rerun of the 2026-04-12 perf regression.
- Do NOT scope TV Guide / Recordings / Downloads / Settings screens into this milestone — those are M5R.2 / M6R / M7R. M4R.D.1 only wires their **nav entries**; each nav target lands a "coming in MxR" placeholder panel for now.

---

### **M5R — Groups + EPG ribbon + Favorites flow** *(1 week)*

**Goal:** Features from the old M5 fold back in inside the new shell.

| # | Task | DoD |
|---|---|---|
| M5R.1 | Category rail shows real groups (from `group-parser` in core) under Live/Movies/Series | D-pad Right enters group list; Left returns to category rail |
| M5R.2 | Now/Next ribbon under each channel row (reuses `use-now-next.ts`, backed by `epg_programmes`) | updates every minute; placeholder text when no EPG |
| M5R.3 | Favorites = a pinned group at the top of the category rail | star button on focused cell toggles |
| M5R.4 | "Recent channels" = a pinned group under Favorites (auto-maintained by `recent-channels-store`) | zap-back works from anywhere in shell |
| M5R.5 | Long-press / Menu key on a cell opens the `InfoPanel` in "full detail" mode (metadata, related, play buttons) | replaces old Detail screen functionality |
| M5R.6 | Resume playback: `MiniPlayer` auto-offers a "Resume at X:XX?" badge when opening VOD that has history | reads from `watch_history` store |

**Ship criterion:** Daily-use flows all work inside the single shell. No navigation transitions for any of them.

---

### **M6R — EPG + Catch-up + Timeshift** *(2 weeks)*

**Goal:** Live TV parity.

| # | Task | DoD |
|---|---|---|
| M6R.1 | XMLTV fetch + parse + insert into SQLite (reuses core `xmltv-parser`, background via `react-native-background-fetch`) | table populated; parse logs on Fire TV |
| M6R.2 | Guide overlay — D-pad Up on a playing channel opens a full-screen guide grid (channels × time, FlashList 2D) | 6-hour horizontal window, scrollable |
| M6R.3 | Catch-up URL resolution via core `catchup/url-builder` | click past programme → plays catchup stream |
| M6R.4 | Timeshift: pause/rewind live TV via ExoPlayer buffer | buffer config from settings; go-live button |
| M6R.5 | Programme reminders via Notifee | tap notification → play channel |

**Ship criterion:** "What's on tonight, remind me, let me rewind 30s" — all work inside the shell.

---

### **M7R — Settings + Parental + Image/Animation polish** *(1 week)*

**Goal:** Settings modal, parental lock, and the first pass of 60fps polish.

| # | Task | DoD |
|---|---|---|
| M7R.1 | `SettingsModal.tsx` with sections: Sources, Playback, Network, EPG, Parental, Shortcuts, About | opened from top-right gear / menu button |
| M7R.2 | Parental PIN (core hashing + Android Keystore wrap) | PIN set, channel lock/hide, channel overrides |
| M7R.3 | Sources management inside Settings (was top-level SourcesScreen) | add/remove/sync |
| M7R.4 | Reanimated focus glow + subtle scale on category rail + content panel cells | 60fps on Fire TV Stick 4K |
| M7R.5 | Retry logic in `fetch-http-client` (was MB-5) | 3 tries, exponential backoff, only on 5xx + network errors |
| M7R.6 | Backup export/import | JSON dump of sources/favorites/history/settings to Downloads |

**Ship criterion:** Every knob the desktop has is reachable from the shell's Settings modal.

---

### **M8R — Codec gap: ExoPlayer FFmpeg extension** *(1 week)*

**Goal:** Kill the "audio only / no picture" problem on HEVC-main10, AC3, EAC3, DTS, TrueHD channels.

| # | Task | DoD |
|---|---|---|
| M8R.1 | Clone `androidx/media`, checkout tag matching our Media3 version | local build succeeds |
| M8R.2 | Build the FFmpeg decoder extension (`libraries/decoder_ffmpeg`) via NDK for armeabi-v7a + arm64-v8a + x86_64 | `.so` artifacts produced |
| M8R.3 | Vendor the extension into `packages/mobile/android/app/src/main/jniLibs` + register `FfmpegAudioRenderer` / `FfmpegVideoRenderer` in `PlayerActivity`'s `DefaultRenderersFactory` | decoders available |
| M8R.4 | Wire `DefaultRenderersFactory.setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)` inside `PlayerActivity.startPlayer()` | FFmpeg decoder picked on unsupported codecs |
| M8R.5 | Regression test against 10 real IPTV channels that were audio-only before | all 10 render picture |
| M8R.6 | APK-size audit; gate per-ABI splits if the increase is large | target < 60 MB per split |

**Ship criterion:** Channels that were audio-only on 2026-04-19 play picture. No regression on channels that already worked.

---

### **M9R — TV UX + Phone-native features** *(2 weeks)*

**Goal:** Stop feeling like a port, start feeling native.

| # | Task | DoD |
|---|---|---|
| M9R.1 | Channel up/down via D-pad on fullscreen player | zap preview before commit |
| M9R.2 | Quick-info overlay on "info" button in live TV | now/next card without leaving fullscreen |
| M9R.3 | Phone: picture-in-picture (Android PIP API) | auto-enter on home-button during playback |
| M9R.4 | Phone: swipe-to-seek, swipe-to-adjust-volume/brightness | feels like a video app |
| M9R.5 | Chromecast sender (`react-native-google-cast`) | cast to Google TV / Chromecast |
| M9R.6 | Android TV recommendations channel | recent + continue-watching on TV launcher home |
| M9R.7 | Android TV "Live TV" integration | register channels so OS Live TV app finds them |
| M9R.8 | Voice search via Google Assistant / Leanback | spoken query opens `SearchOverlay` |

**Ship criterion:** "Feels native" gut check on three devices: Fire TV Stick 4K, Pixel phone, NVIDIA Shield.

---

### **M10R — Distribution + Manual QA** *(2 weeks)*

**Goal:** Ship it.

| # | Task | DoD |
|---|---|---|
| M10R.1 | ProGuard/R8 optimization; APK size audit | target < 60 MB per split |
| M10R.2 | Signed release build with upload key kept offline | keystore not in repo |
| M10R.3 | Manual QA checklist against 5 real devices | `packages/mobile/tests/MANUAL_QA.md` |
| M10R.4 | Play Console setup — TV + phone listing, screenshots | private/internal track first |
| M10R.5 | Amazon Appstore submission | Fire TV |
| M10R.6 | Sideload APK on GitHub Releases | signed, versioned |
| M10R.7 | Sentry dashboards: crash-free sessions ≥ 99.5% | release gate |
| M10R.8 | First external beta (10 users) | feedback loop established |

**Ship criterion:** Installable via Play Store + Fire TV + direct APK. Crash-free ≥ 99.5% over 7 days.

---

## Desktop ↔ Mobile Feature Parity Matrix (post-reboot)

| Desktop Feature | Mobile Target | Milestone | Notes |
|---|---|---|---|
| Live TV browsing | Same + D-pad + now/next, in single shell | M4R + M5R | Flat tiles, not hex |
| Movies browsing | Same, via category rail state | M4R | Poster cards in right panel |
| Series w/ episode browsing | Same, via InfoPanel "full detail" mode | M5R.5 | Season selector inside panel |
| Search | Overlay (remote search key / Ctrl-K) | M4R.9 | FTS5-backed |
| Favorites | Pinned group in left rail | M5R.3 | Toggle from focused cell |
| Watch history + resume | Resume badge in MiniPlayer | M5R.6 | Throttled onProgress |
| EPG XMLTV + now/next + Guide grid | Ribbon + overlay grid | M5R + M6R | Virtualized 2D FlashList |
| Catch-up TV | Same | M6R | Core URL builder |
| Timeshift | Same | M6R | ExoPlayer buffer |
| Parental PIN + lock/hide + overrides | Same, via Settings modal | M7R | Android Keystore-backed |
| Sources: M3U / Xtream / Stalker | Already works, moves into Settings | M7R.3 | Credential storage via Keychain |
| Multi-source merge + dedup | Same | M2 (done) | SQLite |
| Source auto-sync timer | Same | M6R (background-fetch) | |
| Settings (8 tabs) | Collapsed into one modal with sections | M7R.1 | |
| Recording (live → ffmpeg) | **Dropped V1**; Media3 DVR in V2 | — | No subprocess on Android |
| Downloads (VOD → disk) | Media3 download manager | Post-M10R | Not blocking release |
| TMDb metadata | Same | M5R.5 (info panel) | Reuses desktop TMDb client if extracted to core |
| OpenSubtitles | Same | M7R or later | Same client; player UI |
| mpv tracks / codec picker | N/A — ExoPlayer handles | — | Media3 track selection UI |
| Codec gap (HEVC-main10, AC3/EAC3/DTS/TrueHD) | FFmpeg ExoPlayer extension | M8R | Clean path; no patch-package |
| Gamepad | D-pad covers it | M4R + M9R | |
| Multi-view / PIP | Phone PIP yes; multi-view dropped | M9R.3 | |

**Mobile-native wins over desktop:**
- Portability
- Chromecast / Google TV live-channel integration (M9R.6–M9R.7)
- Voice search (M9R.8)
- True PIP on phones (M9R.3)
- Gesture volume/brightness/seek (M9R.4)
- Home-launcher recommendations (M9R.6)
- Lower idle power (no Electron process churn)

---

## Architecture Rules (Mobile — post-reboot)

Non-negotiable:

1. **No duplicated business logic.** Parsers, clients, classifiers, title-cleaners, EPG, catchup, parental hashing, store factories — all in `@yancotv/core`. Core internal re-exports use explicit `.js` extensions (Node 22 ESM requires them).
2. **Persistence goes through op-sqlite.** AsyncStorage only for small app-level keys.
3. **One screen, state-driven.** The root stack has two entries only: `Shell` and `Fullscreen`. No drawer, no bottom tabs. Category / content-type / selected-group are Zustand state, not routes.
4. **Paged SQL for content lists.** No `channels.filter(...)` in JS. `db/queries.ts` returns windows.
5. **Persistent `MiniPlayer` surface.** The player is always mounted on `Shell`. Back semantics: fullscreen → corner, corner → nothing. Exit app is from category rail only.
6. **Single focus primitive.** `src/focus/Focusable.tsx` + `TVFocusGuideView` wrappers. No per-screen custom behavior.
7. **Every `<Image>` goes through `CachedImage`.** No raw `<Image>`.
8. **No credentials in AsyncStorage or SQLite plaintext.** `react-native-keychain` → Android Keystore.
9. **Single-source theme.** Every color from `src/styles/theme.ts`; no inline hex.
10. **TV vs phone branching at the shell level only.** No `Platform.isTV` inside stores, services, or data modules.
11. **No emoji glyphs in UI.** SVG icons (`react-native-svg`).
12. **Zustand stores mirror desktop shapes.**
13. **No ffmpeg subprocess assumptions on mobile.** Codec gap handled by the FFmpeg ExoPlayer extension (M8R), not a shell-out.
14. **Delete-before-add.** Any new module that duplicates an existing one triggers a decision: replace, not coexist.

---

## Testing Strategy

- **Unit tests (Jest):** stores, SQL helpers, URL builders, classifier (via core).
- **Core tests stay authoritative.** 725 desktop tests keep validating shared modules.
- **Manual QA:** `packages/mobile/tests/MANUAL_QA.md` written in M10R, executed on 5 devices before each release.
- **E2E (Detox, optional):** golden-path smoke flow after M10R.

---

## Known Bugs / Fix Register

| ID | Bug | Fix Milestone | Status |
|---|---|---|---|
| MB-1 | HexCard not hex-clipped | M1.2 | FIXED `5990c0c`, then DELETED (hex removed on mobile) |
| MB-2 | No playback resume | M4.8 | FIXED `32223c4` |
| MB-3 | Sources form dup-submit | M4 cleanup | FIXED |
| MB-4 | Source error state never clears | M4 cleanup | FIXED |
| MB-5 | HTTP client has no retry | M7R.5 | open |
| MB-6 | `RootNavigator.tsx` empty placeholder | M3.1 | FIXED `a87c726` (collapsing in M4R.2) |
| MB-7 | `Focusable.tsx` dead | M4R.10 | REBUILDING |
| MB-8 | Settings unreachable | M7R.1 | open |
| MB-9 | LiveTvScreen empty | M4.3 | FIXED `3857172` (deleted in M4R.1) |
| MB-10 | Sidebar emoji/Unicode glyphs | M7.8 | FIXED (TvDrawerContent now uses SVG; whole file deleted in M4R.1) |
| MB-11 | Channels re-sync every launch | M2 | FIXED `c44599b` + `0b93214` |
| MB-12 | VLC autolink crash | N/A | OBSOLETE — VLC dropped |
| **MB-13** | Two back presses needed to close player (controls eat first press) | M4R.7 | open — fixes via persistent `MiniPlayer` shell |
| **MB-14** | Audio-only on HEVC-main10 / AC3 / EAC3 / DTS channels | M8R | open — requires FFmpeg ExoPlayer extension |
| **MB-15** | Boot blocks on hydration + migrations before first frame | M4R.6 | open — cached-first render |
| **MB-16** | Search screen crashes while typing (non-virtualized rails) | M4R.9 | MITIGATED 2026-04-19 (ScrollView→FlatList), fully fixed when search becomes an overlay with a single FlashList |
| **MB-17** | Channel navigation feels sluggish | M4R.0 + M4R.5 | MITIGATED 2026-04-19 (flat tiles + windowing), fully fixed once paged SQL replaces JS filter |
| **MB-18** | Desktop `ERR_UNSUPPORTED_DIR_IMPORT` from `@yancotv/core` on Node 22 | — | FIXED 2026-04-19 — all core internal re-exports use explicit `.js` extensions |

---

## Risks & Trade-offs

| Risk | Mitigation |
|---|---|
| Reboot deletes working code | Deliberate — feedback memory says "rebuild from zero, don't patch." Desktop parity matrix ensures we're not losing features, only shells |
| FFmpeg ExoPlayer extension is a non-trivial native build | Standard path for IPTV apps (TiviMate ships it). Budget 1 week. Fallback: ship without it; users self-select channels that work |
| react-native-tvos lags main RN | Pin version. Upgrade quarterly |
| Fire TV Stick (old) has 1GB RAM | Paged SQL + FlashList + CachedImage; test on cheapest device every milestone |
| Focus bugs are the #1 TV app complaint | Invest in M4R.10 + M9R; single focus primitive |
| op-sqlite + Hermes + TV compatibility | Validated in M2 |
| Play Console TV review strictness | User-provided M3U sources only — same legal posture as TiviMate |

---

## Timeline (post-reboot)

| Milestone | Duration | Cumulative |
|---|---|---|
| M4R — Shell reboot | 2 wk | 2 wk |
| M5R — Groups + EPG ribbon + Favorites flow | 1 wk | 3 wk |
| M6R — EPG + Catch-up + Timeshift | 2 wk | 5 wk |
| M7R — Settings + Parental + Polish | 1 wk | 6 wk |
| M8R — Codec gap (FFmpeg) | 1 wk | 7 wk |
| M9R — TV UX + Phone-native | 2 wk | 9 wk |
| M10R — Distribution + QA | 2 wk | 11 wk |

**Total: ~11 weeks focused solo work.** Buffer +25% for real-world drag → **~14 weeks (3.5 months) to Play Store + Fire TV release** on the rebooted shell.

---

## Working Rhythm

- One milestone = one commit series + one tagged APK (`mobile-v0.2.M4R`, etc.).
- Each milestone ships something a user can open and use — no "foundation-only" milestones after M4R.
- Bugs found during a milestone get filed in the Known Bugs table and land in the relevant future milestone, not hotfixed inline.
- Desktop and mobile plans move in lockstep for shared-core changes — whoever lands a core change updates both consumers in the same PR.
- **Delete-before-add** is enforced at PR level: every net-add over +200 LOC on mobile requires a matching delete or an explicit "this is new surface" note in the commit.

---

## Decision Log (Mobile-specific)

| Decision | Rationale | Date |
|---|---|---|
| React Native TV over Kotlin Compose | Shared core + phone coverage in one APK | 2026-03-* |
| op-sqlite over WatermelonDB | FTS5 support matches desktop 1:1 | 2026-04-18 |
| Drop mpv — use ExoPlayer via react-native-video | Platform-native, no subprocess | 2026-03-* |
| Drop ffmpeg recording from V1 | No subprocess on Android; Media3 DVR later | 2026-04-18 |
| Drop multi-view from V1 | Dropped from desktop too | 2026-04-18 |
| StyleSheet + theme over NativeWind | Already ported | 2026-04-18 |
| Credentials via react-native-keychain | Desktop safeStorage has no RN equivalent | 2026-04-18 |
| AsyncStorage settings-only; content in op-sqlite | AsyncStorage crashed at 10K items | 2026-04-18 |
| V1 ships on react-native-video 6 / Media3 (VLC dropped) | No maintained RN VLC library works on RN 0.85 + tvos fork | 2026-04-18 |
| **Dropped react-native-video — native Android `PlayerActivity` instead** | After a week chasing black-screen-with-audio on Fire TV through RN bridge views (TextureView patches, `viewType={TEXTURE}`, transparentModal, persistent `<Video>` surfaces), the RN bridge itself was the problem. Stepping over it to a dedicated AppCompat Activity hosting Media3 ExoPlayer + PlayerView directly (TiviMate/Smarters pattern) rendered picture on first try. JS fires an Intent via `PlayerLauncher` NativeModule; JS never mounts `<Video>`. `react-native-video` dep + patch deleted | 2026-04-20 |
| **Reboot: single-screen TiviMate-shaped shell, delete desktop-ported screens** | Desktop "page" thinking (stack+drawer+tabs, seven destinations, dashboard, stacked player, 2:3 detail tabs, hex SVG cards) is wrong for TV. Result: sluggish navigation, double-back semantics, codec-gap audio-only, dashboard nobody asked for. User directive 2026-04-19: full audit + start over. New shape: left rail + right panel + persistent MiniPlayer. Search and Sources become overlays/settings. Delete-before-add enforced. | 2026-04-19 |
| **Flat channel tiles on mobile; hex is desktop-only** | Hex SVG + MaskedView per tile was a 20+ ms/card paint cost on Android GPU. TiviMate / Smarters / Stremio all ship flat rectangular tiles with centered logo. Desktop keeps hex | 2026-04-19 |
| **Codec gap fixed via ExoPlayer FFmpeg extension (M8R)** | Cleanest path. Same strategy TiviMate ships. ~15 MB APK growth, ~1 week native build | 2026-04-19 |
| **Core barrel imports use explicit `.js` extensions** | Node 22 ESM + `"type": "module"` rejects extensionless directory/file imports. TS `moduleResolution: "bundler"` accepts `.js` on `.ts` sources. Fixes desktop `ERR_UNSUPPORTED_DIR_IMPORT` on boot | 2026-04-19 |
| Defer full-parity title-cleaner / classifier / catchup URL-builder | Phase 0 versions in core are enough for what mobile renders today; full rules surface in UI that doesn't exist yet | 2026-04-18 |
