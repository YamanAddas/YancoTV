# YancoTV Android — Production Plan

## Strategy

**Target platforms:**
- Android TV 9+ (Sony, TCL, Hisense, NVIDIA Shield)
- Google TV (Chromecast with Google TV, newer Sony/TCL)
- Fire TV (Firestick 4K, Cube, Fire TV Stick) — Fire OS is Android-based
- Android phones/tablets 8+ (same APK, different layout)

**Approach:** Fresh React Native app + shared TypeScript core. Not a port of the Electron UI — TV demands a different interaction model. The Electron app stays Windows-first; Android is a sibling, not a successor.

**Distribution:**
- Play Store (TV + phone, same listing)
- Amazon Appstore (Fire TV)
- Direct APK sideload (for boxes)

## Tech Stack

| Layer | Choice | Why |
|---|---|---|
| Framework | **React Native 0.76+ with react-native-tvos fork** | D-pad focus, TV events, single codebase for TV+phone |
| Language | TypeScript strict | Matches Electron app |
| Playback | **react-native-video (ExoPlayer/Media3 backend)** | Native HLS/DASH, hardware decode, DRM-ready |
| Navigation | **React Navigation 7** + custom TV focus layer | Industry standard, has TV-focus extensions |
| State | **Zustand 5** | Same stores as Electron — direct reuse |
| Database | **op-sqlite** (JSI-based, fastest RN SQLite) | Mirrors `better-sqlite3` schema/migrations |
| Data fetching | **TanStack Query 5** | Same as Electron |
| Styling | **NativeWind 4** (Tailwind for RN) | Matches Electron mental model |
| Animations | **Reanimated 3 + Moti** | 60fps on TV hardware |
| Lists | **FlashList** (Shopify) | 10K+ channels, memory-tight for Fire TV 1GB boxes |
| Forms | **React Hook Form + Zod** | Same Zod schemas as Electron |
| Crash reporting | **Sentry** | RN SDK, cross-platform |
| Build | **EAS Build** (Expo's service) or local Gradle | Signed APK/AAB |

**Explicitly rejected:**
- Flutter — would fork every parser/store, no code sharing
- Kotlin Compose-only — locks out phones unless we double-build
- Capacitor/Ionic — not serious for video on TV
- Native Android Leanback in pure Java — 5x dev time, zero shared code

## Monorepo Structure

```
YancoTV/  (pnpm workspace root)
├── package.json                     # workspace root + desktop app (Phase 0 lite)
├── pnpm-workspace.yaml              # NEW
├── src/                             # desktop app stays at root for now
├── tests/                           # desktop tests stay at root
├── packages/
│   ├── core/                        # NEW — shared business logic
│   │   ├── src/
│   │   │   ├── types/               # ← will move from src/shared/types/
│   │   │   ├── schemas/             # ← will move from src/shared/schemas/
│   │   │   ├── parsers/
│   │   │   │   ├── m3u-parser.ts    # platform-agnostic
│   │   │   │   └── xmltv-parser.ts  # platform-agnostic (fetch/gunzip injected)
│   │   │   ├── clients/
│   │   │   │   ├── xtream-client.ts
│   │   │   │   └── stalker-client.ts
│   │   │   ├── content/
│   │   │   │   ├── classifier.ts
│   │   │   │   └── title-cleaner.ts
│   │   │   ├── catchup/
│   │   │   │   └── url-builder.ts
│   │   │   └── stores/              # Zustand store factories (no DOM deps)
│   │   └── package.json
│   └── mobile/                      # NEW — React Native TV + phone app (Phase 1+)
│       ├── android/
│       ├── src/
│       │   ├── App.tsx
│       │   ├── navigation/
│       │   ├── screens/
│       │   ├── components/{tv,phone}/
│       │   ├── player/
│       │   ├── db/                  # op-sqlite + migrations
│       │   ├── services/            # platform-specific glue
│       │   └── focus/               # D-pad focus utilities
│       └── package.json
```

**Note on desktop relocation:** Original plan called for moving `src/` under `packages/desktop/`. Deferred — it's mostly cosmetic (lots of path updates, high regression risk). The real payoff comes from `packages/core`, which is purely additive. We can relocate later if the root-level app becomes awkward.

## Phase Breakdown

### **Phase 0 — Core Extraction** (2 weeks)
Move platform-agnostic code to `@yancotv/core`. Electron app consumes it. Zero feature work; pure refactor. Every test still passes.

- 0.1 Set up pnpm workspace + `packages/core` skeleton
- 0.2 Move types + Zod schemas (pure — safest first)
- 0.3 Move parsers (M3U, XMLTV) — abstract `fetch`/`gunzip` via injected deps
- 0.4 Move Xtream/Stalker clients
- 0.5 Move content-classifier, title-cleaner, catchup URL builder
- 0.6 Move Zustand store factories (state shape only — platform binds DB/IPC)
- 0.7 Update Electron imports, ensure 725/725 unit tests pass
- 0.8 Publish core as `workspace:*` dependency

### **Phase 1 — Mobile Foundation** (3 weeks)
- 1.1 RN project init (react-native-tvos template), TV + phone variants
- 1.2 NativeWind, Reanimated, FlashList, Navigation set up
- 1.3 op-sqlite integration + migration runner (reuse SQL files from desktop)
- 1.4 Zustand stores wired to op-sqlite
- 1.5 react-native-video POC — play HLS, MPEG-TS live, VOD MP4
- 1.6 Build signed debug APK, sideload on real Fire TV Stick
- 1.7 Crash reporting (Sentry) + error boundary

### **Phase 2 — Sources & Content** (3 weeks)
- 2.1 Add source flow (M3U URL, Xtream, Stalker MAC) — reuses `@yancotv/core` clients
- 2.2 Sync pipeline, FTS5 search index
- 2.3 Credential storage via Android Keystore (replaces Electron safeStorage)
- 2.4 Source management screen
- 2.5 Multi-source dedup/merge (from core)

### **Phase 3 — TV UX Foundation** (4 weeks) — the make-or-break phase
- 3.1 Focus engine: custom `<Focusable>` primitive, `useFocusGroup` hook, spatial navigation
- 3.2 Leanback-style rows (horizontal carousels) for Home, Live, Movies, Series
- 3.3 Hero focus animations (scale, elevate, glow) — Reanimated worklets, 60fps
- 3.4 D-pad shortcuts: back-to-home, channel up/down, quick info (info button)
- 3.5 Side drawer (left edge focus) with category switcher
- 3.6 On-screen keyboard optimized for D-pad input (prediction, voice fallback)
- 3.7 Content detail page — TV version of Sprint 11B design
- 3.8 Settings screens restructured for D-pad

### **Phase 4 — Playback** (2 weeks)
- 4.1 Player screen: ExoPlayer full-screen, minimal chrome
- 4.2 On-screen OSD: progress bar, now-playing, program title
- 4.3 D-pad controls: OK = play/pause, left/right = seek ±10s, up = info
- 4.4 Track selection (audio, subtitles) via side sheet
- 4.5 HTML5-style fallback detection
- 4.6 Background audio toggle (for music channels on phones)

### **Phase 5 — EPG, Catchup, Timeshift** (2 weeks)
- 5.1 EPG grid view (TV layout: time columns, channel rows, virtualized)
- 5.2 Now/next strip in player OSD
- 5.3 Catch-up URL building (reuses core)
- 5.4 Timeshift (ExoPlayer pause-buffer)
- 5.5 Reminders (local notifications via Notifee)

### **Phase 6 — Favorites, History, Parental** (1 week)
- 6.1 Favorites page + toggle
- 6.2 Continue watching row on home
- 6.3 PIN-protected parental controls, Android Keystore-backed hash

### **Phase 7 — Phone UX** (2 weeks)
- 7.1 Responsive layouts — bottom tab nav on phones, drawer on tablets
- 7.2 Touch-first browse (grid > row), swipe gestures
- 7.3 Picture-in-picture (Android native PIP API)
- 7.4 Landscape player lock
- 7.5 Chromecast sender (cast to Google TV/Chromecast)

### **Phase 8 — "Amazing" Layer** (3 weeks)
- 8.1 **Voice search** — Google Assistant integration (Android TV `MediaSession` + search intent)
- 8.2 **Recommendations channel** — Android TV home channel row
- 8.3 **Live TV channels integration** — register with Android TV's Live TV app
- 8.4 Hero animations, page transitions, parallax backdrops
- 8.5 Ambient backdrop art on channel detail (TMDb-sourced)
- 8.6 Haptic feedback on remotes that support it (NVIDIA Shield)
- 8.7 Splash + launch screen with branded animation

### **Phase 9 — Distribution & Polish** (2 weeks)
- 9.1 ProGuard/R8 optimization, APK size audit (<40MB target)
- 9.2 Signed release build (separate upload key, kept offline)
- 9.3 Play Console setup — TV + phone listing, screenshots, promo
- 9.4 Amazon Appstore submission (Fire TV)
- 9.5 Sideload APK signed + hosted for direct install
- 9.6 Crash-free sessions monitoring (Sentry dashboards)
- 9.7 Manual QA on 5 devices: Fire TV Stick 4K, Chromecast with GTV, NVIDIA Shield, Android phone, Android tablet

## What Transfers, What Doesn't

**Straight reuse (via `@yancotv/core`):** M3U parser, XMLTV parser, Xtream/Stalker clients, content classifier, title cleaner, catchup URL logic, all Zod schemas, all types, Zustand store shapes, all SQL migrations, all 725 unit tests.

**Rewritten for Android:** UI (completely), player (mpv → ExoPlayer), credential store (safeStorage → Keystore), file downloads (ffmpeg child process → WorkManager + Media3 downloader), system tray/notifications (Electron → Notifee), window management (irrelevant).

**New to mobile:** D-pad focus engine, voice search, Cast, PIP, Leanback integration, recommendations channel, touch gestures.

**Dropped for Android V1:** ffmpeg recording (replaced by ExoPlayer downloader for VOD; live DVR is Phase 10+), multi-view.

## Risks & Trade-offs

| Risk | Mitigation |
|---|---|
| react-native-tvos lags main RN by ~1 release | Pin version, upgrade quarterly, don't chase bleeding edge |
| Fire TV has 1GB RAM on older sticks | FlashList + aggressive image cache eviction, test on cheapest device early |
| Codec support varies per device | ExoPlayer covers 95%; surface clear errors for the 5% |
| Android Keystore API changes per version | Use `react-native-keychain` which abstracts this |
| Google Play TV review is strict (no sideloaded content UI) | Legit M3U sources, user-provided only — same legal posture as TiviMate |
| Focus bugs are the #1 TV app complaint | Invest heavily in Phase 3, get it right before adding features |

## Timeline

**Total: ~24 weeks (~6 months)** of focused solo work.

- Phases 0–2: 8 weeks (foundation, data)
- Phases 3–4: 6 weeks (TV UX, playback — the differentiators)
- Phases 5–7: 5 weeks (feature parity on mobile)
- Phases 8–9: 5 weeks (polish + ship)

Buffer: add 20% (+5 weeks). Real target: **7–7.5 months to Play Store release**.

## Sprint Status

- **Phase 0.1:** IN PROGRESS (workspace + core skeleton)
- All other phases: PLANNED
