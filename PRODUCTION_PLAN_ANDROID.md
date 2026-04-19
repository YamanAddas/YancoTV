# YancoTV Android — Production Plan

**Mission:** Ship an Android TV + phone app that matches every feature of the Electron desktop app — and beats it where mobile-native platforms shine (D-pad, PIP, Chromecast, voice, background audio).

This plan is the single source of truth for mobile. Every mobile commit should map to a task here.

---

## Strategy

**Target platforms (single APK, dual UX):**
- Android TV 9+ (Sony, TCL, Hisense, NVIDIA Shield)
- Google TV (Chromecast with Google TV, newer Sony/TCL)
- Fire TV (Firestick 4K, Cube, Fire TV Stick) — Fire OS is Android-based
- Android phones/tablets 8+ (same APK, responsive layout branched via `Platform.isTV`)

**Approach:** Fresh React Native app + shared TypeScript core. **Not** a port of the Electron UI — TV and touch demand different interaction models. The Electron app stays Windows-first; Android is a sibling that draws from the same core.

**Distribution (end state):**
- Play Store (TV + phone, same listing)
- Amazon Appstore (Fire TV)
- Direct APK sideload (for boxes and beta testing)

---

## Tech Stack (locked in)

| Layer | Choice | Why |
|---|---|---|
| Framework | **React Native 0.85 (`react-native-tvos` fork)** | D-pad focus, TV events, single codebase for TV+phone |
| Language | TypeScript strict | Matches desktop |
| Playback | **react-native-video 6 (ExoPlayer/Media3 backend)** | Native HLS/DASH, hardware decode, DRM-ready |
| Navigation | **React Navigation 7** + TV focus layer | Industry standard, TV-focus extensions available |
| State | **Zustand 5** | Same store shapes as desktop |
| Database | **op-sqlite** (JSI-based, fastest RN SQLite) | Mirrors desktop `better-sqlite3` schema + migrations |
| Data fetching | **TanStack Query 5** | Matches desktop |
| Styling | **StyleSheet + theme module** (from desktop Tailwind palette) | No NativeWind — theme object already ported in [theme.ts](packages/mobile/src/styles/theme.ts) |
| Animations | **Reanimated 3** (add in M4) | 60fps on TV hardware |
| Lists | **FlashList** (Shopify) | 10K+ channels, memory-tight for Fire TV 1GB boxes |
| Forms | Minimal hand-rolled + **Zod** schemas from core | Same Zod schemas as desktop |
| Crash reporting | **Sentry** (`@sentry/react-native`) | Already wired |
| Hex-card clipping | **`@react-native-masked-view/masked-view`** | The SVG clip trick that actually works on RN |
| Secure credentials | **`react-native-keychain`** (Android Keystore) | Replaces Electron `safeStorage` |
| Notifications | **Notifee** | Local reminders for EPG |
| Build | Local Gradle; later EAS Build | Signed APK/AAB |

**Explicitly rejected:**
- Flutter — forks every parser/store, no code sharing with desktop
- Kotlin Compose-only — locks out phones unless we double-build
- NativeWind — marginal value over a theme module we already have
- AsyncStorage as primary store — crashes at ~10K items, wrong tool

---

## Monorepo Structure

```
YancoTV/  (pnpm workspace root)
├── package.json                     # workspace root + desktop app
├── pnpm-workspace.yaml
├── src/                             # desktop app (Electron)
├── tests/                           # desktop tests
├── CLAUDE.md                        # monorepo guide
├── PRODUCTION_PLAN.md               # desktop roadmap
├── PRODUCTION_PLAN_ANDROID.md       # this file
├── ARCHITECTURE.md                  # process/data architecture (both apps)
├── packages/
│   ├── core/                        # shared business logic (Phase 0)
│   │   └── src/
│   │       ├── types/               # ContentItem, Source, Episode, EPG types
│   │       ├── schemas/             # Zod validators
│   │       ├── parsers/             # m3u, xmltv
│   │       ├── xtream/              # Xtream client
│   │       ├── stalker/             # Stalker client
│   │       ├── content/             # classifier, title-cleaner
│   │       ├── catchup/             # catch-up URL builder
│   │       ├── http/                # HttpClient interface
│   │       └── logger.ts
│   └── mobile/                      # React Native TV + phone app
│       ├── android/                 # native project (generated, committed)
│       ├── src/
│       │   ├── App.tsx
│       │   ├── navigation/          # React Navigation stacks (M3)
│       │   ├── screens/             # Home, Live, Movies, Series, Detail, Player, Search, Favorites, Guide, Settings, Sources
│       │   ├── components/
│       │   │   ├── cards/           # HexCard (MaskedView), ContentCard
│       │   │   ├── layout/          # AppLayout, Sidebar, PageHeader, TabBar
│       │   │   ├── tv/              # TV-specific (TvButton, focus helpers)
│       │   │   └── phone/           # Phone-specific (bottom tabs, gestures)
│       │   ├── focus/               # Focus primitive + spatial navigation
│       │   ├── player/              # react-native-video wrapper + IPlayer parity
│       │   ├── db/                  # op-sqlite + migrations (M2)
│       │   ├── services/            # Keystore, notifications, cast, etc.
│       │   ├── stores/              # Zustand stores (mirror desktop shapes)
│       │   ├── http/                # fetch-http-client
│       │   ├── storage/             # AsyncStorage (settings only, not content)
│       │   └── styles/              # theme (from desktop palette)
│       ├── PRODUCTION_PLAN_ANDROID.md  # symlink/reference to root
│       └── package.json
```

---

## Current Reality (where we actually are)

**Snapshot as of 2026-04-18.** M1 substantially done. M2 (op-sqlite persistence) and M3 (React Navigation 7) both landed — MB-11 (re-sync every launch) fixed at root; MB-12 (VLC crash) obsoleted by dropping the VLC integration attempt entirely. **The V1 player is `react-native-video` 6 / Media3** — see Decision Log for why VLC was taken off the V1 path. Next up is **M4 browse parity**.

| Phase | Status | Evidence |
|---|---|---|
| 0.1 Workspace + core skeleton | DONE | commit `888f897` |
| 0.2 Types + schemas moved | DONE | `packages/core/src/types`, `schemas` |
| 0.3 Parsers moved | DONE | `packages/core/src/parsers` |
| 0.4 Xtream + Stalker moved | DONE | commit `86c45ed` |
| 0.5 Content + catchup moved | DONE | `packages/core/src/content`, `catchup` |
| 0.6 Zustand factories moved | DONE | commit `94b7eac` (M1.8) |
| 0.7 Desktop still imports cleanly | DONE | 725/725 tests pass |
| 1.1 RN TV scaffold | DONE | commit `29cbbc2` |
| 1.2 Debug APK | DONE | commit `7533c24` |
| 1.3 Release APK + Sentry | DONE | commit `2ad3fad` |
| **M1.1 Commit Phase 2 rewrite** | **DONE** | commits `5f0edbf`, `6a08dcd`, then hardening commits through `9b98eeb` |
| **M1.2 Real hex clipping (MaskedView)** | **DONE** | commit `5990c0c` |
| **M1.3 XMLTV parser in core (pako)** | **DONE** | commit `69e0cff` |
| **M1.4 title-cleaner full parity** | **DONE** | core has cleanTitle/extractYear/extractSeasonEpisode/extractShowName; desktop re-exports |
| **M1.5 content-classifier full parity** | **DONE** | core exports classifyEntry + normalizeCategory; desktop re-exports |
| **M1.6 catchup URL builder full parity** | **DONE** | core exports buildXtreamTimeshiftUrl + buildM3uCatchupUrl; desktop consumes |
| **M1.7 Parental PIN hashing in core** | **DONE** | commit `0fc5da6` |
| **M1.8 Zustand store factories in core** | **DONE** | commit `94b7eac` |
| **M2 op-sqlite + migrations** | **DONE** | commits `c44599b` (content + FTS), `cac9cde` (favorites), `b45b974` (history), `0b93214` (sources), `b8bec53` (settings). MB-11 fixed at root. |
| **M3 React Navigation 7** | **DONE** | commit `a87c726` — native-stack root with permanent drawer on TV + bottom tabs on phone; nav-store/ScreenRouter deleted |
| **VLC player swap** | DROPPED FROM V1 | 2026-04-18 research showed no maintained RN VLC library works on RN 0.85 + tvos fork without native-module work; see Decision Log |

**What's installed on the user's test device right now:** needs a fresh APK built from `a87c726` — verifies SQLite persistence + the new nav stack before layering M4 on top.

**V1 path from here:** M4 (browse parity) → M5 (search/favorites/history) → M6 (EPG + catchup) → M7 (settings + subs + parental) → M9 (QA) → release APK. Player stays on `react-native-video` 6 / Media3.

---

## Roadmap — Milestones M1 → M9

Each milestone produces a runnable APK with a verifiable new capability. Commit at the end of each.

### **M1 — Commit the Phase 2 rewrite. Finish core extraction.** *(1 week)*

**Goal:** Lock in the good work that's sitting uncommitted, then move the remaining platform-agnostic code into `@yancotv/core`. No new mobile screens this milestone.

| # | Task | File(s) | DoD |
|---|---|---|---|
| M1.1 | Commit Phase 2 rewrite in reviewable chunks | all currently-modified files | feat(mobile): Phase 2 — theme, layout, hex cards, video playback |
| M1.2 | Replace HexCard borderRadius approximation with real hex clip | `packages/mobile/src/components/cards/HexCard.tsx` | Use `@react-native-masked-view/masked-view` + SVG hex mask; visually matches desktop |
| M1.3 | Extract xmltv-parser to core | `packages/core/src/parsers/xmltv-parser.ts` | Async streaming, gzip via `pako`. Desktop imports from core. 725 tests still pass |
| M1.4 | Extend title-cleaner in core | `packages/core/src/content/title-cleaner.ts` | Port every regex from desktop. Desktop imports from core |
| M1.5 | Extend content-classifier in core | `packages/core/src/content/classifier.ts` | Full parity with desktop heuristics |
| M1.6 | Extract catchup URL builder | `packages/core/src/catchup/url-builder.ts` | Xtream timeshift + M3U patterns |
| M1.7 | Extract parental PIN hashing | `packages/core/src/parental/pin.ts` | Use WebCrypto subtle (works in Node via `globalThis.crypto` and in RN via `react-native-quick-crypto`). Salted scrypt → timing-safe compare |
| M1.8 | Define `Zustand store factories` in core | `packages/core/src/stores/*` | Platform-agnostic shape; DB adapter injected. Desktop rewires; mobile consumes in M4 |

**Ship criterion:** Desktop still passes all tests. Mobile APK still runs with no regression. Core has +5 modules. Git log shows the Phase 2 rewrite landed cleanly.

---

### **M2 — Persistence: op-sqlite + migrations.** *(1 week)*

**Goal:** Mobile gets the same database the desktop has. Favorites/history/EPG/parental all become possible after this.

| # | Task | DoD |
|---|---|---|
| M2.1 | Install `op-sqlite`, verify Hermes + TV builds | APK boots, can open a DB |
| M2.2 | Port migrations 001–007 from desktop | Same schema. SQL copied verbatim where possible; FTS5 virtual tables confirmed working on Android |
| M2.3 | Build `db.ts` (init, migration runner, pragmas) | Mirrors `src/main/services/db.ts` interface |
| M2.4 | Build `content-store`, `favorites-store`, `history-store` on mobile | Same public API as desktop services (no IPC — direct calls) |
| M2.5 | Move channel persistence out of AsyncStorage into SQLite | No more 10K-item JSON blobs; `sources-store` only holds source metadata |
| M2.6 | `settings-service` on mobile (key/value SQLite) | Parity with desktop |
| M2.7 | Unit tests for each store (Jest + in-memory SQLite) | Baseline mobile test suite |

**Ship criterion:** Add a source, resync, and all content persists across app restart. No performance cliff at 10K+ channels. Works on Fire TV Stick 4K.

---

### **M3 — Navigation stack. Dual layout (TV drawer / phone tabs).** *(1 week)*

**Goal:** Real navigation. Hardware back works. Phone and TV get the right chrome.

| # | Task | DoD |
|---|---|---|
| M3.1 | Install React Navigation 7 (`@react-navigation/native`, `native-stack`, `drawer`, `bottom-tabs`) | Base shell boots |
| M3.2 | Build root navigator that branches on `Platform.isTV` | TV: left drawer sidebar (matches desktop). Phone: bottom tab bar |
| M3.3 | Port every screen onto the stack | Home, Live, Movies, Series, Favorites, Search, Guide, Sources, Settings, Detail, Player |
| M3.4 | Replace `nav-store` / `ScreenRouter` with navigator hooks | `useNavigation()`, `useRoute()` everywhere |
| M3.5 | Android hardware back button wired per screen | No accidental app-exit from detail pages |
| M3.6 | Deep link scheme `yancotv://...` registered | `yancotv://live/:id` plays a channel |
| M3.7 | TV focus memory across navigations | Return from Detail → Live TV restores focus to the card you came from |
| M3.8 | Delete dead `src/focus/Focusable.tsx` or rebuild as the real primitive | Single source of truth for focusable elements |

**Ship criterion:** Every desktop page has a mobile counterpart reachable from nav. Phone feels like a phone; TV feels like a TV.

---

### **M4 — Browse parity: ContentGrid, CategorySidebar, Detail page.** *(2 weeks)*

**Goal:** Match the desktop browsing experience. This is where mobile starts to feel like YancoTV and not a scaffolded RN app.

| # | Task | DoD |
|---|---|---|
| M4.1 | `ContentGrid` component built on FlashList | Virtualized, handles 10K+, dynamic column count from window width, TV focus-aware |
| M4.2 | `CategorySidebar` with language grouping + multi-select + pin/hide | Mirrors desktop `CategorySidebar.tsx` behavior |
| M4.3 | `LiveTvScreen`: grid + category sidebar + sort dropdown + now/next overlay stub | Grid scrolls smoothly on Fire TV Stick; sidebar focusable via D-pad |
| M4.4 | `MoviesScreen` + `SeriesScreen` with same patterns, poster cards | Visual parity with desktop |
| M4.5 | `ContentDetailScreen` with Hero + Tabs (Info / Episodes / Related) | Match [Sprint 11B](PRODUCTION_PLAN.md#sprint-11b--content-detail-pages--done) spec. Reanimated hero fade-in |
| M4.6 | Episodes tab with season picker, progress bars | Reads from history store (M2) |
| M4.7 | Related tab: two HexCard horizontal rails | Same-group + same-source queries |
| M4.8 | Playback resume | `onProgress` throttled write to `history_store`; on re-open, resume prompt |
| M4.9 | Route Movie/Series card taps to detail, not direct-play | Matches desktop flow |
| M4.10 | Zustand store factories from core wired to mobile DB adapter | `favorites-store` + `recent-channels-store` consume core factories. `player-store` and `settings-store` factories don't exist in core yet — extraction deferred until M7 settings UI lands and surfaces a real second consumer |

**Ship criterion:** Brand-new user adds a source, browses movies, opens one, reads plot/cast, resumes from where they left off. It feels like the desktop.

---

### **M5 — Search + Favorites + History.** *(1 week)*

**Goal:** Daily-use features. After M5, someone could actually live in the mobile app.

| # | Task | DoD |
|---|---|---|
| M5.1 | FTS5 search index and query | Same shape as desktop `content:search` |
| M5.2 | `SearchScreen` with type filter tabs (All/Live/Movies/Series) | Debounced input, 60 results per type |
| M5.3 | Search history persisted, shown in empty state | Clear + remove individual entries |
| M5.4 | Search autocomplete in header (phone) / sidebar (TV) | Matches desktop UX |
| M5.5 | `FavoritesScreen` wired to `favorites_store` | Toggle from cards, from detail, from player |
| M5.6 | Home screen "Continue Watching" row from history | Touch = resume, long-press = detail |
| M5.7 | Home screen "Recent channels" strip (live only) | Zap-back affordance |

**Ship criterion:** Power-user features land. Search speed under 50ms on 10K items.

---

### **M6 — EPG + Catch-up + Timeshift.** *(2 weeks)*

**Goal:** Live TV parity. Guide grid, now/next overlays, past-program playback.

| # | Task | DoD |
|---|---|---|
| M6.1 | XMLTV fetch + parse + insert into SQLite | Reuses core `xmltv-parser` (M1.3). Background via `react-native-background-fetch` |
| M6.2 | Now/Next batch query + overlay on Live TV cards | Reads from `epg_programmes` |
| M6.3 | `GuideScreen` — virtualized grid (channels × time) | FlashList with 2D scroll. 6-hour horizontal window, scrollable time axis |
| M6.4 | D-pad navigation in Guide grid | Arrow keys move cell; Enter plays; page-left/right shifts time window |
| M6.5 | Catch-up URL resolution (Xtream + M3U patterns) | Core `catchup/url-builder` |
| M6.6 | Play-from-EPG: click past programme → play catch-up URL | Works on supported channels; clear "not available" otherwise |
| M6.7 | Timeshift: pause/rewind live TV via ExoPlayer buffer | Buffer config from settings; go-live button |
| M6.8 | EPG settings screen (URL, refresh interval, clear cache) | Matches desktop `EpgSettings.tsx` |
| M6.9 | Programme reminders via Notifee | Tap notification → play channel |

**Ship criterion:** "Show me what's on tonight, record me a reminder, let me rewind this football match 30s" — all work.

---

### **M7 — Settings + Parental + Polish.** *(1 week)*

**Goal:** Every knob the desktop has, grouped into tabs the user can actually find.

| # | Task | DoD |
|---|---|---|
| M7.1 | `SettingsScreen` with 8 tabs matching desktop (General/Playback/Network/Playlist/EPG/Parental/Shortcuts/About) | Wired to settings-service |
| M7.2 | Parental: PIN setup, channel lock/hide, channel overrides | Uses core parental hashing (M1.7). Android Keystore wraps the hash |
| M7.3 | PIN modal component | Same behavior as desktop — rate-limited on PIN retries |
| M7.4 | Playback settings: default buffer, timeout, auto-reconnect, preferred audio/subtitle language | Values feed into react-native-video config |
| M7.5 | Network settings: UA override, proxy (if supported in ExoPlayer HttpDataSource) | Flag unsupported options with disabled state |
| M7.6 | Backup export/import: JSON dump of sources/favorites/history/settings | Saved via `react-native-fs` to Download folder |
| M7.7 | Toast system for notifications | Replaces console feedback from sync/play failures |
| M7.8 | Replace emoji glyphs in Sidebar with SVG icons (`react-native-svg`) | No font-dependent rendering across device brands |
| M7.9 | Retry logic in `fetch-http-client` | 3 tries, exponential backoff, only on 5xx + network errors |

**Ship criterion:** Settings page screenshot sits next to the desktop's and you can't tell which is which, feature-wise.

---

### **M8 — TV UX Polish. Phone-native features.** *(2 weeks)*

**Goal:** Stop feeling like a port, start feeling native. TV gets leanback polish; phone gets PIP/Cast/gestures.

| # | Task | DoD |
|---|---|---|
| M8.1 | `TVFocusGuideView` wrappers for every horizontal rail | No dead-end D-pad navigation |
| M8.2 | Focus-based scale + glow animations on cards (Reanimated) | 60fps on Fire TV Stick 4K |
| M8.3 | Quick-info overlay on D-pad "info" button in live TV | Now/next card without leaving the grid |
| M8.4 | Channel up/down via D-pad on player screen | Zap preview before commit |
| M8.5 | Phone: picture-in-picture (Android PIP API) | Auto-enter on home-button press during playback |
| M8.6 | Phone: swipe-to-seek, swipe-to-adjust-volume/brightness gestures | Feels like a video app |
| M8.7 | Phone: landscape player lock + orientation handling | Auto-landscape on play |
| M8.8 | Chromecast sender (`react-native-google-cast`) | Cast to Google TV / Chromecast |
| M8.9 | Android TV recommendations channel | Recent + continue-watching surface on TV launcher home |
| M8.10 | Android TV "Live TV" integration | Register channels so OS's Live TV app can find them |
| M8.11 | Haptic feedback on supported remotes (Shield) | Subtle focus confirmation |

**Ship criterion:** App wins a "feels native" gut check on three devices: Fire TV Stick 4K, Pixel phone, NVIDIA Shield.

---

### **M9 — Distribution + Manual QA.** *(2 weeks)*

**Goal:** Ship it.

| # | Task | DoD |
|---|---|---|
| M9.1 | ProGuard/R8 optimization; APK size audit | Target <40MB |
| M9.2 | Signed release build with upload key kept offline | Keystore not in repo |
| M9.3 | Manual QA checklist against 5 real devices | Document in `packages/mobile/tests/MANUAL_QA.md` mirroring desktop's |
| M9.4 | E2E smoke via Detox (optional if time) | `add source → browse → play → favorite` flow |
| M9.5 | Play Console setup — TV + phone listing, screenshots, store listing | Private/internal track first |
| M9.6 | Amazon Appstore submission | Fire TV |
| M9.7 | Sideload APK signed + hosted (GitHub Releases) | For IPTV-box users who don't want Play |
| M9.8 | Sentry dashboards: crash-free sessions target ≥99.5% | Release gate |
| M9.9 | First external beta (10 users) | Feedback loop established |

**Ship criterion:** Installable via Play Store + Fire TV + direct APK. Crash-free sessions ≥99.5% over 7 days.

---

## Desktop ↔ Mobile Feature Parity Matrix

| Desktop Feature | Mobile Target | Milestone | Notes |
|---|---|---|---|
| Live TV browsing | Same + D-pad + now/next | M4 + M6 | Hex cards via MaskedView |
| Movies browsing | Same | M4 | Poster cards |
| Series with episode browsing | Same | M4 | Season selector in Detail |
| Search (FTS5 + type filter + history) | Same | M5 | Debounced, autocomplete |
| Favorites | Same | M5 | Toggle from card/detail/player |
| Watch history + resume | Same | M4 + M5 | Throttled onProgress |
| EPG XMLTV + now/next + Guide grid | Same | M6 | Virtualized 2D list |
| Catch-up TV (Xtream + M3U) | Same | M6 | Core URL builder |
| Timeshift (pause/rewind live) | Same | M6 | ExoPlayer buffer |
| Parental PIN + lock/hide + overrides | Same | M7 | Android Keystore-backed |
| Sources: M3U / Xtream / Stalker | Already works | Done in M1 commit | Credential storage → Keychain in M7.2 |
| Multi-source merge + dedup | Same | M2 | Via SQLite |
| Source auto-sync timer | Same | M2 + M6 | Background-fetch on mobile |
| Settings (8 tabs) | Same | M7 | |
| System tray | N/A — notifications instead | M6.9 | Notifee |
| Auto-update | Play Store | M9 | Sideload users get in-app nag |
| Backup export/import | Same | M7.6 | |
| Recording (live → ffmpeg) | **Dropped V1**; use Media3 DVR in V2 | — | Mobile doesn't spawn ffmpeg |
| Downloads (VOD → disk) | Media3 download manager | Post-M9 | V2 — not blocking release |
| TMDb metadata | Same | M4 or M7 | Reuses desktop TMDb client if extracted to core |
| OpenSubtitles | Same | M7 | Same client; UI in player screen |
| mpv tracks / codec picker | N/A — ExoPlayer handles | — | Use Media3 track selection UI |
| Gamepad | D-pad already covers it | M3 + M8 | |
| Multi-view / PIP | Phone PIP yes; multi-view dropped | M8.5 | |

**Where mobile is going to BEAT desktop:**
- Portability (take it anywhere)
- Chromecast / Google TV live-channel integration
- Voice search via Google Assistant
- True picture-in-picture on phones
- Gesture-based volume/brightness/seek
- Home-launcher recommendations row
- Better standby behavior (no Electron process churn)

---

## Architecture Rules (Mobile)

Non-negotiable:

1. **No duplicated business logic.** Parsers, clients, classifiers, title-cleaners, EPG, catchup, parental hashing, store factories — all live in `@yancotv/core`. If you need it on both platforms, put it in core.
2. **Persistence goes through op-sqlite.** AsyncStorage is ONLY for small app-level keys (hydration flags, last-screen). Never for content, EPG, or favorites.
3. **All navigation through React Navigation.** No ad-hoc store-based routers.
4. **All focus through one primitive.** One `<Focusable>` or `TVFocusGuideView` wrapper — not per-screen custom behavior.
5. **No credentials in AsyncStorage or SQLite in plaintext.** Use `react-native-keychain` (Android Keystore) for username/password/MAC.
6. **Single-source theme.** Every color comes from [`src/styles/theme.ts`](packages/mobile/src/styles/theme.ts); no inline hex anywhere.
7. **TV vs phone branching at the navigator and component level only.** No `if (isTV)` scattered across business logic.
8. **No emoji glyphs in UI.** SVG icons (`react-native-svg`) for everything cross-platform.
9. **Zustand stores mirror desktop shapes.** If desktop has `player-store.play(url, title, contentId)`, mobile has the same signature. Makes future core extraction trivial.
10. **No ffmpeg subprocess assumptions.** Mobile uses Media3 for anything desktop would do via ffmpeg (downloads, later DVR). Recording live TV is dropped V1.

---

## Testing Strategy

- **Unit tests (Jest, `packages/mobile/tests/`):** stores, parsers (via core), URL builders, classifier
- **Core tests stay authoritative.** 725 desktop tests continue to validate every shared module
- **Manual QA:** `packages/mobile/tests/MANUAL_QA.md` created in M9, executed against 5 real devices before each release
- **E2E (Detox, optional):** only for the golden-path smoke flow after M9

---

## Known Bugs to Fix Before V1

These were found in the mobile audits (2026-04-18). All land in the relevant milestone:

| ID | Bug | Fix Milestone | Status |
|---|---|---|---|
| MB-1 | HexCard not actually hex-clipped (borderRadius approximation) | M1.2 | FIXED `5990c0c` |
| MB-2 | No playback resume — every relaunch starts at 0:00 | M4.8 | FIXED `32223c4` |
| MB-3 | Sources form doesn't disable during sync; dup submissions possible | M1 (small fix) | FIXED (M4 cleanup pass) |
| MB-4 | Source error state never auto-clears | M1 (small fix) | FIXED (M4 cleanup pass) |
| MB-5 | HTTP client has zero retry logic on transient failures | M7.9 | open |
| MB-6 | `RootNavigator.tsx` is empty placeholder | M3.1 | FIXED `a87c726` |
| MB-7 | `Focusable.tsx` is dead code | M3.8 | OBSOLETE — rebuilt as the real primitive in M3.8 and consumed by SeasonPicker / SortDropdown |
| MB-8 | Settings screen is in nav enum but unreachable | M7.1 | open (settings screen doesn't exist yet) |
| MB-9 | `LiveTvScreen.tsx` is empty | M4.3 | FIXED `3857172` |
| MB-10 | Sidebar emoji/Unicode glyphs may not render across Android fonts | M7.8 | FIXED (M4 cleanup pass) — TvDrawerContent now uses `react-native-svg` icons |
| **MB-11** | All channels re-sync on every launch — content was a JSON blob in AsyncStorage with silent write-failure on 10k+ catalogs. Root cause: content shouldn't live in AsyncStorage at all. | M2 (ported to op-sqlite; `KEY_CHANNELS` path deleted) | **FIXED** commits `c44599b` + `0b93214` |
| **MB-12** | App crashed navigating to list screens on the VLC build (2026-04-18). Was `react-native-vlc-media-player@1.0.98` autolinking under RN 0.85 + tvos. | N/A — VLC dropped from V1; see Decision Log | **OBSOLETE** 2026-04-18 (VLC no longer in scope; current master uses react-native-video) |

---

## Risks & Trade-offs

| Risk | Mitigation |
|---|---|
| react-native-tvos lags main RN by ~1 release | Pin version. Upgrade quarterly. Don't chase bleeding edge |
| Fire TV Stick (older) has 1GB RAM | FlashList + aggressive image-cache eviction. Test on cheapest device early in each milestone |
| Codec support varies per device | ExoPlayer covers 95%; surface clear errors for the 5% |
| Android Keystore API changes per version | Use `react-native-keychain` which abstracts this |
| Play Console TV review is strict (no sideloaded-content UI) | Legit M3U sources, user-provided only — same legal posture as TiviMate |
| Focus bugs are the #1 TV app complaint | Invest heavily in M3 + M8. Get focus right before adding features |
| op-sqlite + Hermes + TV stack compatibility | Validate in M2.1 early. Fallback: `react-native-sqlite-storage` |
| Pnpm workspace + Metro resolution | Keep `metro.config.js` aware of workspace paths. Regression-test on every RN upgrade |

---

## Timeline

| Milestone | Duration | Cumulative |
|---|---|---|
| M1 — Commit + core extraction | 1 wk | 1 wk |
| M2 — op-sqlite + migrations | 1 wk | 2 wks |
| M3 — Navigation + dual layout | 1 wk | 3 wks |
| M4 — Browse parity + Detail | 2 wks | 5 wks |
| M5 — Search + Favorites + History | 1 wk | 6 wks |
| M6 — EPG + Catch-up + Timeshift | 2 wks | 8 wks |
| M7 — Settings + Parental + Polish | 1 wk | 9 wks |
| M8 — TV UX + Phone-native | 2 wks | 11 wks |
| M9 — Distribution + QA | 2 wks | 13 wks |

**Total: ~13 weeks focused solo work.** Buffer +25% for real-world drag → **~16 weeks (4 months) to Play Store + Fire TV release**.

---

## Working Rhythm

- One milestone = one commit series + one tagged APK (`mobile-v0.1.M3`, etc.)
- Each milestone ships something a user can open and use — no "foundation-only" milestones after M3
- Bugs found during a milestone get filed in this doc's Known Bugs table and land in the relevant future milestone, not hotfixed inline
- Desktop and mobile plans move in lockstep for shared-core changes — whoever lands a core change is responsible for updating both consumers

---

## Decision Log (Mobile-specific)

| Decision | Rationale | Date |
|---|---|---|
| React Native TV over Kotlin Compose | Shared core + phone coverage in one APK | 2026-03-* |
| op-sqlite over WatermelonDB | FTS5 support matches desktop schema 1:1 | 2026-04-18 |
| Drop mpv — use ExoPlayer via react-native-video | Platform-native, no subprocess | 2026-03-* |
| Drop ffmpeg recording from V1 | No subprocess on Android; Media3 DVR is a later story | 2026-04-18 |
| Drop multi-view from V1 | Dropped from desktop too; not worth the mobile complexity | 2026-04-18 |
| Hex-clip via MaskedView, not SVG on View | SVG clipPath on View doesn't work in RN. MaskedView does | 2026-04-18 |
| StyleSheet + theme module over NativeWind | Already ported. NativeWind would add a compilation step for marginal value | 2026-04-18 |
| Credentials via react-native-keychain, not safeStorage | Desktop safeStorage has no RN equivalent; Keychain wraps Keystore | 2026-04-18 |
| Settings-only AsyncStorage; content in op-sqlite | AsyncStorage crashed at 10K items (SQLITE_FULL) | 2026-04-18 |
| Commit the Phase 2 rewrite before any new screens | 13-file uncommitted drift is a tax on every future change | 2026-04-18 |
| **V1 ships on `react-native-video` 6 / Media3. VLC dropped from V1 scope.** | Post-M3 survey of the RN VLC landscape (2026-04-18) found no maintained library that works cleanly on RN 0.85 + `react-native-tvos` + new-arch: razorRun's `react-native-vlc-media-player` is RN 0.83+ autolink-broken (MB-12); `jboz/react-native-vlc-media-player-view` is a 5-star unproven Kotlin rewrite; no `@thewidlarzgroup` / Expo / react-native-community VLC module exists. The clean paths are a custom Fabric wrapper over `libvlc-all:3.6.0` (3–5 days native work + ~40–90 MB APK inflation) or the ExoPlayer FFmpeg decoder extension (cloning `androidx/media`, NDK-build, patch to react-native-video). Neither earns its keep for V1: Media3 already handles ~95% of IPTV streams — which is what TiviMate and IPTV Smarters ship on. The 5% codec gap (AC3/EAC3/DTS/TrueHD) is a post-V1 problem to address when real user reports come in, not speculative work blocking every other milestone. | 2026-04-18 |
| Clean rebuild of persistence + navigation instead of patching | User directive 2026-04-18: "rebuild from zero for these things. i dont want patching. i want clean building." Applied to: op-sqlite persistence (M2, landed), React Navigation (M3, landed). No patch-package entries, no band-aid stores. (VLC rebuild scoped out — see row above.) | 2026-04-18 |
| Defer M1.4 / M1.5 / M1.6 (title-cleaner + classifier + catchup URL-builder full parity) | The Phase-0 versions in core are enough for what mobile renders today; the full desktop rules only surface in UI that doesn't exist yet (settings overrides, catchup EPG). Re-picks up in M7 when settings screen lands. | 2026-04-18 |
| **Reverse:** M1.4/M1.5/M1.6 actually landed at Phase-0-extraction time | 2026-04-19 audit found the core modules already had full desktop parity (title-cleaner: cleanTitle/extractYear/extractSeasonEpisode/extractShowName; classifier: classifyEntry+normalizeCategory; catchup: buildXtreamTimeshiftUrl+buildM3uCatchupUrl). The "deferred" marking was a documentation lag. Plan corrected. | 2026-04-19 |
