# YancoTV — Native Mobile Production Plan (Kotlin Multiplatform + SwiftUI)

**Mission:** Beat TiviMate on Android TV / Fire TV / Google TV, ship iPhone + iPad alongside, reuse as much as possible between platforms via shared Kotlin business logic.

**Active as of 2026-04-20.** Supersedes [PRODUCTION_PLAN_ANDROID.md](PRODUCTION_PLAN_ANDROID.md) (the React Native plan, now frozen).

---

## Why we switched off React Native

One week of Fire TV black-screen-with-audio — fixed only by bypassing the RN bridge entirely and shipping a native `PlayerActivity` (M4R.Player, commit `09150e9`, 2026-04-20). The fix worked first try. Pattern recognition: every TiviMate-shaped feature we need (mini-preview that keeps playing while you browse, channel zap, PIP, Leanback integration, Android TV launcher channels, voice search) is a custom native bridge in RN and free in Compose. TiviMate, IPTV Smarters, Kodi, VLC — all native. The substrate has to match the competition if we want to beat it.

**Cost accepted:** ~10–12 weeks to reach current M4R parity + surpass it. RN-side M4R shell work (HomeShell, ContentPanel, navigation) is thrown away. `@yancotv/core` TypeScript business logic is ported to Kotlin (~2 weeks).

**What we keep:** the native `PlayerActivity` + `PlayerLauncher` we just shipped — it's Kotlin already. It folds into the new Android app with minor changes (share one ExoPlayer instance between mini-preview and fullscreen).

---

## Stack

### Shared (`packages/shared/`) — Kotlin Multiplatform

| Concern | Choice |
|---|---|
| Language | **Kotlin 2.x** |
| DB | **SQLDelight** (KMP SQLite with generated typed queries, FTS4) |
| HTTP | **Ktor Client** + OkHttp engine on Android, Darwin engine on iOS |
| Concurrency | **Kotlinx Coroutines + Flow** |
| Serialization | **Kotlinx Serialization** (replaces Zod) |
| ViewModels | **Shared ViewModels** exposing `StateFlow` — consumed by Compose on Android, SwiftUI on iOS via KMP framework |
| DI | **Koin** (KMP-native; Hilt is Android-only) |
| Date/time | `kotlinx-datetime` |
| Logging | `kermit` (KMP logger) |

### Android / Android TV (`packages/android/`)

| Concern | Choice |
|---|---|
| UI | **Jetpack Compose** + `androidx.tv.material` for leanback on TV; Material3 on phone/tablet |
| Navigation | **Compose Navigation 3** with adaptive layouts |
| Playback | **Media3 ExoPlayer** (direct, no bridge) — carries over from `PlayerActivity.kt` |
| Codec gap | **FFmpeg ExoPlayer extension** (NDK-built, vendored jniLibs) — MK.9 |
| Image loading | **Coil 3** (KMP-compatible) |
| Credentials | **Android Keystore** direct (EncryptedSharedPreferences) |
| Notifications | **AndroidX WorkManager** + NotificationManager |
| Cast | **Cast SDK** (MediaRouter) |
| Build | Gradle Kotlin DSL, AGP 8.x, min SDK 24, target SDK 35 |
| Signing | Existing keystore (reused from RN app) |

### iOS / iPadOS (`packages/ios/`, lands post-Android)

| Concern | Choice |
|---|---|
| UI | **SwiftUI** (native feel; not Compose Multiplatform) |
| Playback | **AVPlayer** default; **VLCKit** fallback for DTS/TrueHD |
| Cast | **Google Cast iOS SDK** |
| Bindings | Shared Kotlin consumed as `.framework` via Xcode integration |
| Build | Xcode 16+, Swift 6, iOS 17+ / iPadOS 17+ |

### Desktop (unchanged)

Electron + React + TypeScript stays put. `@yancotv/core` TS package keeps shipping desktop. Android/iOS get their **own** Kotlin port of the same logic — two implementations of identical algorithms, both test-covered.

### Explicitly rejected

- **Compose Multiplatform for UI** — phone UI would feel "Android-like" on iOS. TV UI isn't supported on iOS anyway. Shared business logic only; UIs stay platform-native.
- **Flutter** — would throw away even the native Activity work. Also no mature TV library.
- **React Native / NativeScript / Ionic** — what we're leaving.
- **KMM without KMP** — KMM was rebranded into KMP in 2023; "KMP" covers both.

---

## Repo layout

```
YancoTV/                        # pnpm workspace root (desktop stays pnpm)
├── packages/
│   ├── core/                   # @yancotv/core — TypeScript, desktop-only now
│   ├── shared/                 # NEW — Kotlin Multiplatform module (business logic)
│   │   ├── build.gradle.kts
│   │   └── src/
│   │       ├── commonMain/     # Pure Kotlin: parsers, clients, classifier, EPG, catchup
│   │       ├── commonTest/     # KMP unit tests
│   │       ├── androidMain/    # Android-only actuals (platform DB driver, HTTP engine)
│   │       └── iosMain/        # iOS-only actuals (Darwin HTTP engine, iOS SQLite driver)
│   ├── android/                # NEW — Android app (Android Studio project root)
│   │   ├── app/                # Application module
│   │   │   ├── build.gradle.kts
│   │   │   └── src/main/java/com/yancotv/android/
│   │   │       ├── ui/         # Compose screens
│   │   │       ├── tv/         # TV-specific Compose (androidx.tv.material)
│   │   │       ├── phone/      # Phone-specific Compose
│   │   │       ├── player/     # PlayerActivity.kt + ExoPlayer service (ported from RN)
│   │   │       ├── di/         # Koin modules
│   │   │       └── MainActivity.kt
│   │   ├── settings.gradle.kts
│   │   └── gradle/             # Version catalogs
│   ├── ios/                    # NEW (later) — Xcode project
│   │   ├── YancoTV.xcodeproj
│   │   └── YancoTV/
│   │       ├── SwiftUI screens
│   │       └── Player/
│   └── mobile/                 # FROZEN — RN app, kept runnable until Android native reaches parity
├── src/                        # Electron desktop (unchanged)
├── PRODUCTION_PLAN.md          # Desktop plan
├── PRODUCTION_PLAN_NATIVE.md   # THIS FILE
└── PRODUCTION_PLAN_ANDROID.md  # FROZEN (RN, superseded)
```

**Gradle root:** `packages/android/` is its own Android Studio project with Kotlin version catalog. `packages/shared/` is a pure Gradle/KMP module included via `includeBuild` or path.

---

## Milestones — MK.0 → MK.19

> **Next up (2026-04-24):** MK.8 + D-phase are closed. Start MK.12 with **MK.12a (fast wires)** — see the block below for the ~1-week sequence that closes ~60% of the TiviMate control-surface gap before heavier work in MK.12b → MK.18.

Each milestone ends in a tagged APK (and later TestFlight build) + a commit series. "Delete-before-add" rule from the RN plan carries over.

### **MK.0 — Scaffold** *(~2 days)*

| # | Task | DoD |
|---|---|---|
| MK.0.1 | Create `packages/shared/` KMP module — Gradle Kotlin DSL, targets android + ios, Koin + SQLDelight + Ktor + Serialization + Coroutines wired | `./gradlew :shared:build` green; empty `commonMain/kotlin/com/yancotv/shared/Platform.kt` returns a string on both targets |
| MK.0.2 | Create `packages/android/` Android Studio project — Compose + `androidx.tv.material` + Hilt-vs-Koin decision locked (Koin for KMP), min SDK 24, existing keystore referenced | `./gradlew :app:assembleDebug` green; installs on Fire TV + phone |
| MK.0.3 | `MainActivity.kt` with a Compose "Hello YancoTV" that detects TV vs phone via `UiModeManager` and branches | Debug APK launches on both form factors, shows correct branch |
| MK.0.4 | Port `PlayerActivity.kt` + `PlayerLauncherModule` → plain Android `PlayerActivity` (no RN bridge; callers invoke it directly via Intent) | Hard-code a test stream; Activity plays it when launched from `MainActivity` |
| MK.0.5 | Commit + tag `native-v0.0.0-scaffold` | tag pushed |

### **MK.1 — Shared core port** *(~1.5 weeks)*

| # | Task | DoD |
|---|---|---|
| MK.1.1 | `types/` — `ContentItem`, `Source`, `Episode`, EPG types as Kotlin `data class` with Kotlinx Serialization | parity with `packages/core/src/types/` |
| MK.1.2 | `m3u-parser.kt` — port of desktop M3U parser | 30+ unit tests mirrored from desktop suite |
| MK.1.3 | `xtream/XtreamClient.kt` — same method surface, Ktor-backed | ditto |
| MK.1.4 | `stalker/StalkerClient.kt` | ditto |
| MK.1.5 | `classifier.kt` + `title-cleaner.kt` | ditto |
| MK.1.6 | `xmltv-parser.kt` | ditto |
| MK.1.7 | `catchup/url-builder.kt` | ditto |
| MK.1.8 | `http/HttpClient.kt` + Ktor engine per platform | both targets can GET a URL |
| MK.1.9 | `logger.kt` via kermit | both targets log |

**Ship criterion:** the full desktop 725-test suite's parsing/client/classifier tests have Kotlin equivalents passing on both `commonTest` + Android JVM target.

### **MK.2 — Persistence** *(~3 days)*

| # | Task | DoD |
|---|---|---|
| MK.2.1 | SQLDelight schemas ported from `src/main/services/migrations/001–011.sql` | `./gradlew :shared:generateSqlDelightInterface` succeeds; schema matches desktop |
| MK.2.2 | Room-free Android SQLite driver config via SQLDelight's `AndroidSqliteDriver` | Android instrumentation test opens DB |
| MK.2.3 | FTS4 table + trigger-sync port | full-text search test passes |
| MK.2.4 | Migrations runner — version table, forward-only migrations | upgrade test from v1 → latest passes |

### **MK.3 — Sources** *(~3 days)*

| # | Task | DoD |
|---|---|---|
| MK.3.1 | `SourceRepository.kt` in shared — add / remove / list / sync | unit tests with in-memory DB |
| MK.3.2 | Credential storage via `androidx.security.crypto.EncryptedSharedPreferences` (Keystore-backed) | Xtream/Stalker credentials round-trip |
| MK.3.3 | `SourceSyncService.kt` (Android) wrapping shared repo, exposing progress via Flow | background sync via WorkManager triggers |

### **MK.4 — Shell UI** *(~1 week)*

| # | Task | DoD |
|---|---|---|
| MK.4.1 | `HomeScreen.kt` Compose — adaptive: TV layout = 3-column (rail + filter + content + right column for mini-preview/info); phone layout = stacked with drawer | both form factors render |
| MK.4.2 | `AppSidebar.kt` — global nav (Home / Live TV / Guide / Movies / Series / Favorites / Recordings / Downloads / Settings / Sources) | D-pad navigates; last-selected persists |
| MK.4.3 | `CategoryFilterPanel.kt` — middle column with filter input + group list | TV inline; phone in drawer |
| MK.4.4 | `ContentPanel.kt` — `LazyColumn`/`LazyVerticalGrid` driven by paged SQLDelight queries | scroll 50k rows at 60fps on Fire TV 4K |
| MK.4.5 | `InfoPanel.kt` — right-side now/next + metadata | updates on focus change |
| MK.4.6 | Focus memory — `Modifier.tvFocusTarget()` + per-group save-key | last-focused cell restored |
| MK.4.7 | Theme port — colors, spacing, radii from desktop palette into `Theme.kt` + `MaterialTheme` | visual match vs desktop |

**Ship criterion:** installable APK, shell boots in <1s on Fire TV 4K, D-pad walks the full UI, content panel scrolls a real 50k-item source.

### **MK.5 — Channel list + image loading** *(~3 days)*

| # | Task | DoD |
|---|---|---|
| MK.5.1 | Paged SQLDelight query helpers: `listByType(type, groupId?, limit, offset)`, `searchFts(q, limit, offset)` | cursor-based scroll in `ContentPanel` |
| MK.5.2 | Coil 3 + disk LRU + memory cache + crossfade | logos/posters don't re-decode on scroll |
| MK.5.3 | Quality badge parser (regex from M4R.D.4) → Compose badge chips | real channel titles render correct pills |

### **MK.6 — Playback (shared ExoPlayer, mini ↔ fullscreen)** *(~4 days)*

| # | Task | DoD |
|---|---|---|
| MK.6.1 | `PlaybackService.kt` (Media3 `MediaSessionService`) owns a single `ExoPlayer` + `MediaSession` | player survives config changes; resumes after background |
| MK.6.2 | `MiniPlayerView.kt` — `PlayerView` in the top-right slot of `HomeShell`, binds to the shared `ExoPlayer` | plays in the corner while user browses |
| MK.6.3 | `PlayerActivity.kt` (port) — fullscreen; attaches **the same** `ExoPlayer` to its `PlayerView` via `PlayerView.switchTargetView()` → no rebuffer | Enter on focused channel expands to fullscreen with zero rebuffer |
| MK.6.4 | Back from fullscreen → `switchTargetView()` returns surface to mini slot; player keeps playing | no black frame, no audio gap |
| MK.6.5 | D-pad Up/Down on fullscreen zaps channels (preview via brief `seekTo(0)` on swap) | TiviMate-style zap |

**Ship criterion:** tap channel in `ContentPanel` → plays in mini slot → Enter fullscreens seamlessly → Back returns to mini. No rebuffer. No black frame.

### **MK.7 — EPG** *(~1 week)*

| # | Task | DoD |
|---|---|---|
| MK.7.1 | XMLTV fetch + parse in shared | populates `epg_programmes` table |
| MK.7.2 | `NowNextRow.kt` ribbon under channel rows | updates every minute |
| MK.7.3 | `GuideScreen.kt` — 2D LazyGrid (channels × time), 6h window | scrolls 200 channels × 24h at 60fps |
| MK.7.4 | Programme reminders via `AlarmManager` + NotificationManager | tap notification → opens channel + plays |

### **MK.8 — Catch-up, Timeshift, Favorites, History, Search, Settings, Parental** *(~1.5 weeks)*

| # | Task | DoD |
|---|---|---|
| MK.8.1 | Catch-up URL resolution via shared `CatchupUrlBuilder` | past programme → plays catchup stream |
| MK.8.2 | Timeshift — ExoPlayer DVR buffer window | pause/rewind live TV works |
| MK.8.3 | Favorites — pinned group at top of category rail | star toggle on focused cell |
| MK.8.4 | Watch history + resume badge in mini-preview | resumes VOD at last position |
| MK.8.5 | Search — FTS4-backed overlay (TV remote search key + phone Ctrl-K / search bar) | results render in <100ms |
| MK.8.6 | `SettingsScreen.kt` — Sources, Playback, Network, EPG, Parental, Shortcuts, About | matches desktop coverage |
| MK.8.7 | Parental PIN — shared hashing port + Keystore-wrapped storage + channel lock/hide/override | PIN gate works |

### **MK.9 — Codec gap (FFmpeg ExoPlayer extension)** *(~1 week)*

| # | Task | DoD |
|---|---|---|
| MK.9.1 | Clone `androidx/media` at matching tag; build FFmpeg decoder extension via NDK for armeabi-v7a + arm64-v8a + x86_64 | `.so` artifacts produced |
| MK.9.2 | Vendor libs into `packages/android/app/src/main/jniLibs/` | APK ships all three ABIs via splits |
| MK.9.3 | Register `FfmpegAudioRenderer` / `FfmpegVideoRenderer` in `PlaybackService`'s `DefaultRenderersFactory` with `EXTENSION_RENDERER_MODE_PREFER` | extension preferred over platform codecs |
| MK.9.4 | Regression test against 10 real IPTV channels that were audio-only on the RN build | all 10 render picture |

### **MK.10 — TV UX + launcher integration** *(~1 week)*

| # | Task | DoD |
|---|---|---|
| MK.10.1 | Android TV recommendations channel (`androidx.tvprovider`) | recent + continue-watching cards on TV launcher home |
| MK.10.2 | Android TV "Live channels" integration (`TIF`) | channels register so OS Live TV app finds them |
| MK.10.3 | Voice search via Google Assistant → deep link to Search screen | "Hey Google, play CNN on YancoTV" works |
| MK.10.4 | Leanback on-screen channel zap UI polish | feels smoother than TiviMate |

### **MK.11 — Phone-native features** *(~1 week)*

| # | Task | DoD |
|---|---|---|
| MK.11.1 | Phone PIP (`enterPictureInPictureMode`) | home button during playback → floating PIP |
| MK.11.2 | Gesture seek / volume / brightness on phone player | feels native |
| MK.11.3 | Chromecast sender via Media3's `CastPlayer` | cast to Google TV / Chromecast |

---

## MK.12 → MK.18 — TiviMate gap-close (added 2026-04-24)

Post-MK.11 audit found that YancoTV Android has a solid core (one shared `ExoPlayer`, reactive SQLDelight repos, KMP business logic) but a thin user-facing control surface vs TiviMate 5.1.6. MK.12–MK.18 close that gap. Every sub-task is labelled:

- 🟢 **wire** — code already exists, connect it (fast)
- 🟡 **glue** — schema/repo exists, needs service + UI
- 🔴 **new** — greenfield

Old MK.12 (Distribution + QA + launch) moved to **MK.19** — unchanged scope.

**Red-team cuts already applied** (see bottom of this block): audio delay downgraded to "external player", auto-series recording replaced with manual series bind, DLNA cut, SOCKS proxy cut, cross-source user groups deferred to phase 2. Total post-cut estimate: **~4 focused weeks** for MK.12–MK.18.

**Schema-migration budget:** 6 SQLDelight migrations expected across this block (`Content.sq`, `Sources.sq`, `Favorites.sq` + new tables `channel_prefs`, `favorite_lists`, `recording_schedules`). Each migration lands in the same commit as its `commonTest` upgrade test on a populated fixture. Apply the native-android-mk skill's "schema units" rule (ms vs seconds) on every new timestamp column.

### **MK.12 — In-player control surface** *(~1 week)*

Single biggest UX jump. Today the player overlay is zap bar + quick info; TiviMate users live in the player menu. Split into two sub-phases so fast wires ship first.

**MK.12a — fast wires** (ship first, ~3 days, each 🟢 except 12a.1)

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.12a.1 | ~~Player MENU / `KEYCODE_MENU` → single Compose bottom-sheet overlay (`ComposeView` child of `PlayerActivity`'s root). Sheet hosts 12a.2–12a.5 + 12b tasks~~ — **DONE `9c40c15`** (2026-04-24). Long-press CENTER deferred: needs `event.startTracking()` + `onKeyLongPress` plumbing that conflicts with the short-press show-controller path | 🔴 new | ✅ |
| MK.12a.2 | ~~Audio track picker via `TrackSelectionParameters`; persist pick to `AppPreferences.audioLang` (key already exists)~~ — **DONE `fdbe117`** (2026-04-24) | 🟢 wire | ✅ |
| MK.12a.3 | ~~Subtitle track picker + "off" + "load external file" row. External file triggers `MediaItem` rebuild → gated behind `currentId` / resume-point persistence check per native-android-mk rule~~ — **DONE** (2026-04-24). `SubtitlesView` in the sheet: Off row, each embedded `C.TRACK_TYPE_TEXT` track, and a "Load external file…" row that fires the SAF `OpenDocument` picker. External URI flows through `PlaybackController.applyExternalSubtitle` which captures `currentPosition`, calls `persistResumePoint()`, then rebuilds the `MediaItem` with a `SubtitleConfiguration` (MIME sniffed from extension) and re-seeks. LIVE short-circuits — subs for live IPTV isn't a real workflow | 🟢 wire | ✅ |
| MK.12a.4 | ~~Playback speed picker (0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 2.0×) via `player.setPlaybackSpeed()`; persisted per content-type~~ — **DONE** (2026-04-24). `PlaybackController.loadCurrent` resets LIVE to 1.0× and restores persisted speed on VOD/Episodes. Sheet reads live `player.playbackParameters.speed` so the ● marker stays in sync | 🟢 wire | ✅ |
| MK.12a.5 | ~~Aspect-ratio quick-cycle (Fit / Fill / Zoom / 16:9 / 4:3). Extend `AppPreferences.ResizeMode` enum; `PlayerView.resizeMode` already reactive~~ — **DONE `b1d09d3`** (2026-04-24). Sheet picker + `AspectRatioFrameLayout.setAspectRatio` for forced 16:9 / 4:3. Remote hotkey deferred — no obvious unbound Fire TV key | 🟢 wire | ✅ |

**MK.12b — heavier items** (~4 days)

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.12b.1 | Sleep timer (15 / 30 / 45 / 60 min / end-of-program / off). Coroutine ticker owned by `PlaybackController`; "end-of-program" reads `EpgRepository.currentProgramme(channelId).endTimeMs` | 🔴 new | Timer visible in sheet; fires `player.pause()` at expiry; cancellable |
| MK.12b.2 | **`channel_prefs` SQLDelight table + repo** — keyed on `content_id`; columns `audio_lang`, `subtitle_lang`, `speed`, `resize_mode`, `updated_at` (ms). Replaces the global-prefs fallback in 12a.2/12a.4/12a.5 with per-channel memory | 🔴 new | Channel A remembers Arabic audio, Channel B remembers English; global pref is the default when `channel_prefs` row is null |
| MK.12b.3 | ~~Audio delay~~ — **cut**; documented in sheet as "use external player (MK.18) for sync issues". Media3 has no first-class setter, `AudioProcessor` insert is week-plus with side effects | — | N/A (out of scope) |

**Innovation beyond TiviMate:** per-channel preference memory (12b.2). TiviMate resets audio/sub selection each session.

### **MK.13 — Channel ops + favorites reach** *(~3 days)*

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.13.1 | Add-to-favorites button in MK.12 bottom sheet; reactive star in zap bar + channel rows via `FavoritesRepository.isFavoriteFlow(contentId)` | 🟢 wire | Toggle from player → FavoritesScreen updates without navigation round-trip (MK.8 rule applies) |
| MK.13.2 | **Schema: `Content.sq` migration `0002_content_overrides.sqm`** — add `name_override TEXT`, `logo_override TEXT` nullable. Read-through in `ContentItem.displayTitle` / `displayLogoUrl`. Both optional, source-of-truth remains M3U fields | 🟡 glue | Rename + custom logo round-trip; upgrade test from schema v1 |
| MK.13.3 | Extend `ChannelActionsMenu` from 3 items → full set: Favorite / Rename / Custom logo (URL paste or pick from device) / Lock (existing) / Hide (existing) / Share stream URL. Drop "Cancel" row (Back handles it) | 🟡 glue | All 6 actions reach the repo; semantics applied per native-android-mk (contentDescription on every row) |
| MK.13.4 | **Multi-favorite-lists** — new tables `favorite_lists(id, name, sort_order)` + add `list_id FK` to `Favorites.sq`. Migration seeds a default list (`id=1, name="Favorites"`) and sets all existing favorites to it | 🔴 new | FavoritesScreen shows tab bar of lists; add-to-favorites from MK.13.1 prompts which list when >1 exists |
| MK.13.5 | ~~Move to cross-source user group~~ — **deferred to MK.20+** | — | Out of scope for v1 gap-close |

**Innovation beyond TiviMate:** multi-favorite-lists (13.4). TiviMate has one flat favorites list.

### **MK.14 — Recording + scheduling** *(~1 week, HLS-only in phase 1)*

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.14.1 | `RecordingService` (`ForegroundService` type `mediaProjection`-less variant, `FOREGROUND_SERVICE_TYPE_DATA_SYNC`) — HLS segment tee via OkHttp interceptor; writes to `MediaStore.Video` (scoped storage); updates `Recordings.sq` status column | 🟡 glue | 5-min HLS recording lands on disk; row moves `started → running → done`; file plays back via existing `PlaybackController` |
| MK.14.2 | "Record now" button in MK.12 bottom sheet + ongoing notification with Stop action | 🔴 new | Tap → service starts; notification persists until Stop; PlaybackController unaffected |
| MK.14.3 | **Schema: `recording_schedules.sq`** — `id, content_id, start_at_ms, end_at_ms, padding_pre_s, padding_post_s, repeat_rule (NONE/DAILY/WEEKLY), created_at_ms`. `WorkManager` one-shot `OneTimeWorkRequest` per schedule at `start_at_ms − padding_pre_s` | 🟡 glue | Schedule a recording for T+2 min → fires → completes |
| MK.14.4 | Record-from-EPG long-press in GuidePanel programme cell (uses 14.3's schema with pre/post paddings from prefs, default 0/+60s) | 🔴 new | Long-press any programme → "Record" row → schedule row created |
| MK.14.5 | `RecordingsScreen` in main nav — list / play / delete. Play via existing `PlaybackController.play(filePath)` | 🟡 glue | Screen shows past + in-progress recordings; delete removes row + file |
| MK.14.6 | ~~Auto series recording (XMLTV `episode-num` heuristic)~~ — **replaced** with manual series binding: user long-presses a programme → "Record all programmes with this title on this channel" → creates N schedules based on EPG lookahead window | 🔴 new | Binding one series produces ≥1 scheduled row for next 7 days |
| MK.14.7 | ~~MPEG-TS / DASH / encrypted-segment support~~ — **phase 2**; surface "HLS-only in v1" in the record button's disabled-state tooltip for non-HLS streams | — | Button disabled with reason-text on non-HLS |

**Innovation beyond TiviMate:** recording stays device-local (no cloud cost) + optional SMB push to NAS via `jcifs-ng` (post-record hook, MK.14.8 follow-up if time). TiviMate Premium's cloud archive costs $0 here.

### **MK.15 — EPG display + timeline prefs** *(~4 days)*

Current `SettingsEpgTab` only wraps sync. TiviMate has ~15 display options; we wire the 80% that use existing data.

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.15.1 | EPG days forward / back (1–14 each; two `IntPref`s in `AppPreferences`) | 🟢 wire | `EpgRepository.programmesInRange` already filters; settings tab slider updates visible window |
| MK.15.2 | Timeline duration visible (30 / 60 / 90 / 120 / 180 min) | 🟢 wire | `GuidePanel` density multiplier; presets only, no free slider |
| MK.15.3 | Row height (Compact / Normal / Spacious) — dp-driven via theme (needs MK.16.1 first) | 🟢 wire | Pref persists; applied on `GuidePanel` recomposition |
| MK.15.4 | Now-line + "jump to now" button in guide | 🟢 wire | Uses existing `nowMs` state; scrolls guide grid to current x |
| MK.15.5 | Programme details dialog — synopsis / categories / cast. XMLTV fields already parsed in shared, just unused in UI | 🟢 wire | CENTER on programme cell opens dialog; Back closes |
| MK.15.6 | Catch-up badge on past programmes + "play from here" — `CatchupService` + `UrlBuilder` exist in shared, wiring only. Source must advertise catch-up in `sources.catchup_type` | 🟢 wire | Past programme with catchup shows badge; play builds URL via existing `UrlBuilder` |
| MK.15.7 | Multi-EPG merge conflict resolution — add `Sources.sq` column `epg_priority INTEGER DEFAULT 0`; when two sources provide programmes for the same `tvg_id`, higher priority wins | 🔴 new | UI to reorder sources for EPG priority; conflict test with two fixtures passes |

**Depends on MK.16.1** (theme refactor) for 15.3.

**Innovation beyond TiviMate:** adaptive timeline zoom — D-pad ↑/↓ in guide (pinch on phone) changes visible duration live; TiviMate is fixed at 90 min.

### **MK.16 — Appearance, themes, typography** *(~3 days)*

**`Theme.kt` is currently `object YancoPalette` with `val` colors → cannot swap at runtime.** Refactor to state-driven theme **before** MK.15.3, MK.16.2+.

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.16.1 | **Theme refactor** — convert `YancoPalette` from `object` → `data class`; pass via a new `LocalYancoPalette` `CompositionLocalProvider`; drive from `StateFlow<ThemeId>` in a new `ThemeController`. Existing direct `YancoPalette.*` call sites rewritten to `LocalYancoPalette.current.*` | 🔴 new | Build green; switching `ThemeId` in ThemeController recomposes entire UI to new palette without restart |
| MK.16.2 | 4 built-in themes — Frosted Emerald (existing baseline), Midnight Sapphire, Warm Amber, Monochrome. Picker in `SettingsScreen` new "Appearance" tab | 🔴 new | Each theme renders without color conflicts; focus ring and accent remain accessible |
| MK.16.3 | Accent picker — 4 presets (emerald / sapphire / amber / red). ~~Custom hex~~ **cut**; presets cover 90% | 🔴 new | Accent overlays on selected base theme |
| MK.16.4 | Font size scale (90 / 100 / 110 / 125 %) via `LocalDensity` override | 🟢 wire | Scales apply to all typography |
| MK.16.5 | Channel number display toggle + digit-grouping format. `KEY_SHOW_NUMBERS` pref exists; format is new | 🟡 glue | Numbers render only when enabled |
| MK.16.6 | App icon variants via `activity-alias` in manifest — 3 alternates (Emerald default, Mono, Amber) | 🔴 new | Icon change requires relaunch (standard Android behavior); documented |

**Innovation beyond TiviMate:** on Android 12+ phone, the default theme respects **Material You** (wallpaper-derived accent); TV always stays branded.

### **MK.17 — Network wiring + advanced playback** *(~3 days)*

**P0 latent bug found in audit**: `SettingsNetworkTab` writes to `AppPreferences.networkFlow` but `PlaybackController` hardcodes `CONNECT_TIMEOUT_SEC=15L`, `READ_TIMEOUT_SEC=30L`, UA `VLC/3.0.20 LibVLC/3.0.20`. Settings today are decorative. **MK.17.1 ships first as its own commit.**

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.17.1 | ~~**Wire `AppPreferences.networkFlow` into `PlaybackController`**~~ — **DONE `cff752d`** (2026-04-24). OkHttp interceptor reads `prefs.networkFlow.value` per request: UA via header rewrite, connect + read timeouts via `Chain.withConnectTimeout/withReadTimeout`. ExoPlayer stays constructed once | 🟢 wire | ✅ |
| MK.17.1a | **UA preset dropdown** — replace free-text UA field with dropdown of 5–7 known-good IPTV UAs (VLC default, ExoPlayer, Kodi, Smart TV generic, Chrome Android) + "Custom…" escape hatch that reveals the existing text field. Typos silently fail today; presets fix that | 🟡 glue | Dropdown selection writes through to `AppPreferences.setUserAgent`, next stream request carries the selected UA (verify via logcat) |
| MK.17.2 | "Test connection" button in Network tab — HEAD request to first active source using current settings; reports status / latency / UA echoed | 🔴 new | Misconfig caught before playback fails |
| MK.17.3 | HW decoder preference + fallback toggle — `DefaultRenderersFactory.setEnableDecoderFallback(true)` + `setExtensionRendererMode()` surfacing | 🟢 wire | Toggle forces SW decode; visible in quick-info overlay |
| MK.17.4 | Buffer tuning — 3 presets (Low-latency / Balanced / Stable) mapped to `DefaultLoadControl.Builder` params. No free sliders | 🟢 wire | Preset change rebuilds `ExoPlayer` LoadControl on next `prepare()` |
| MK.17.5 | Per-source `user_agent` + `referer` columns on `Sources.sq`. When set, overrides the global UA from 17.1 for that source's streams | 🟡 glue | Per-source UA test with two sources passes |
| MK.17.6 | ~~SOCKS proxy~~ — **cut**. HTTP proxy only if user demand surfaces | — | Out of scope |

**Innovation beyond TiviMate:** "connection profiles" is **deferred to phase 2**; v1 ships per-source UA override which already covers the main use case.

### **MK.18 — External player + Cast** *(~2 days after cuts)*

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.18.1 | "Open in external" action in MK.12 sheet — `Intent.ACTION_VIEW` with package hints for VLC / MX Player / Just Player; detect installed apps via `PackageManager` | 🔴 new | Launches external player with current stream URL; falls back to chooser if none installed |
| MK.18.2 | Persist default external player per content-type (Live / Movie / Series) in prefs | 🟡 glue | Live defaults to VLC, VOD to internal (example); honored on next launch |
| MK.18.3 | Chromecast sender via Media3 `CastPlayer` — MK.11.3 scope continuation. `MediaRouteButton` in MK.12 sheet | 🔴 new | Cast button appears when a receiver is visible; session transfer with current `MediaItem` + position |
| MK.18.4 | ~~DLNA / UPnP~~ — **cut**. 5% user value, 2-week library bloat cost | — | Out of scope |
| MK.18.5 | Cross-device handoff — **deferred to phase 2**. Schema already supports it (shared `watch_history` in SQLDelight on each device); needs QR-signed-blob transport layer | — | Out of scope for v1 gap-close |

---

### **MK.19 — Distribution + QA + launch** *(~1 week)* *(was MK.12)*

| # | Task | DoD |
|---|---|---|
| MK.19.1 | R8/ProGuard config, APK-size audit. Inherits the D.3 deferral — keep rules for Media3 reflection, Koin module classes, Kermit, SQLDelight serializers, Ktor engines | `./gradlew :app:assembleRelease` runs; APK plays end-to-end; per-ABI splits under 60MB |
| MK.19.2 | Play Console listing (TV + phone), screenshots, description | internal track first |
| MK.19.3 | Amazon Appstore submission (Fire TV) | pending review |
| MK.19.4 | GitHub Releases sideload APK (signed, versioned) | one-click install |
| MK.19.5 | Firebase Crashlytics wired (replaces Sentry on Android; Sentry keeps desktop) | crash-free sessions ≥ 99.5% |
| MK.19.6 | Manual QA checklist against 5 devices: Fire TV Stick 4K, Insignia Fire TV, NVIDIA Shield, Pixel phone, Android tablet | `packages/android/tests/MANUAL_QA.md` |

**Ship criterion for v1.0:** installable via Play Store + Fire TV Appstore + direct APK. Crash-free ≥99.5% over 7 days. All RN-plan feature parity reached + TiviMate-style mini preview works + TV launcher integration live + TiviMate control-surface parity (MK.12–MK.18) shipped.

---

## Red-team summary (MK.12 → MK.18)

What survived, what got cut, and what stands if time shrinks. Full reasoning captured in the 2026-04-24 planning session.

**Already cut in the plan above:**
- Audio delay (MK.12b.3) — Media3 has no setter; external player covers it
- Auto series recording via XMLTV heuristic (MK.14.6) — replaced with manual series bind
- MPEG-TS / DASH / encrypted recording (MK.14.7) — phase 2
- Cross-source user groups (MK.13.5) — phase 2
- SOCKS proxy (MK.17.6) — phase 2
- Connection profiles (MK.17) — phase 2
- DLNA / UPnP (MK.18.4) — out
- Cross-device handoff (MK.18.5) — phase 2
- Custom hex accent (MK.16.3) — 4 presets only

**Ordering constraints** (deviate = rework):
1. **MK.12a ships before MK.12b** — the fast wires alone close ~60% of the visible gap in ~3 days and surface whether the bottom-sheet UX itself works before committing to heavier items.
2. **MK.16.1 (theme refactor) ships before MK.15.3 and MK.16.2+** — driving row-height and palettes from state requires the `object → data class + CompositionLocal` refactor landed first.
3. **MK.17.1 ships as its own commit, first in MK.17** — it's a latent P0 that makes existing Settings real.

**Schema-migration discipline** (6 migrations across this block):
- Each migration lands in the same commit as a `commonTest` upgrade test with populated fixture rows.
- Every new timestamp column documents `-- ms since epoch` per native-android-mk rule.
- Manual populated-DB check on Fire TV with ≥5k content rows before moving on.

**If time shrinks, cut in this order:**
1. MK.14 phase 1 entirely (recording is a 1-week slot; users have external tools)
2. MK.16.6 (alternate app icons — cosmetic)
3. MK.18.3 (Cast — keep only if MK.11.3 already landed)
4. MK.17.5 (per-source UA — useful but rare)
5. MK.13.4 (multi-favorite-lists — nice-to-have; can default to single list)

**Keep even under pressure:**
- MK.17.1 — fixes latent bug
- MK.16.1 — unlocks future work
- MK.12a — the single biggest UX jump
- MK.13.1 + MK.13.2 — favorites from player + rename/logo are top user requests

**Known risk zones:**
- HLS recording (MK.14.1) — segment tee handles most streams; TS-discontinuities and encrypted segments are known failure modes, flagged in the plan with phase-2 deferral.
- `MediaItem` rebuilds in 12a.3 (external subs) and 14.1 (recording interceptor) must persist resume point before re-prepare, per native-android-mk "resume-point" rule — regression-test both.
- Theme refactor (16.1) blast radius = every `YancoPalette.*` reference. Mitigation: do it in one commit with a scripted rewrite, run full smoke before MK.16.2.

---

## D — Debugging & hardening (post-MK.11, runs in parallel with MK.12)

Standing phase started 2026-04-24 after the cascade-nav focus bug shipped in `4a8a46e`. Goal: surface regressions in tooling instead of in user manual-test reports, before MK.12 distribution. **Scope was red-teamed** — `:macrobenchmark`, Crashlytics, Sentry, and a `scripts/logcat.sh` were all rejected as overkill / wrong-platform / not-needed-for-personal-app.

**Active hooks (already wired):**
- LeakCanary 2.14 — `debugImplementation` only (single-process, the optional `androidx.work:work-multiprocess` integration is intentionally absent)
- StrictMode in `YancoApp.onCreate` behind `BuildConfig.DEBUG` — `penaltyLog` only, never `penaltyDeath` (Coil/Media3/WorkManager init paths trip false positives we still need to triage)
- Compose compiler stability + recomposition reports — opt-in via `-PcomposeCompilerReports=true -PcomposeCompilerMetrics=true`, output at `app/build/compose_compiler/`

**Compose baseline (2026-04-24, commit `7fa1bf9`)** — every D.* iteration that touches UI should re-run reports and compare:
- 480 restartable composables, 218 skippable (45%) — half recompose unnecessarily
- 14 inferred unstable classes, 186 unstable args — `@Stable`/`@Immutable` candidates
- StrongSkipping: ON (Kotlin 2.1.0 + Compose plugin 2.1.0)

| # | Task | DoD | Status |
|---|---|---|---|
| D.0 | Wire LeakCanary + StrictMode + Compose compiler reports flag (`buildFeatures { buildConfig = true }` for the DEBUG gate) | App boots clean, LeakCanary `InternalLeakCanary.invoke` runs at start, StrictMode logs at `adb logcat -s StrictMode:*`, reports generate to `app/build/compose_compiler/` on the opt-in flag | **DONE** `7fa1bf9` |
| D.1 | Run `./gradlew :app:lint` once, capture baseline (`lintBaseline = file("lint-baseline.xml")` in `android.lint{}`) so every NEW warning fails the build. Then add ktlint via `org.jlleitschuh.gradle.ktlint`, run `./gradlew ktlintFormat` once, commit the mechanical reformat as a SEPARATE commit so `git blame` stays clean | One lint baseline file checked in; ktlint config + formatted tree in two distinct commits | **DONE** — D.1a `6c31685` (lint baseline 135 issues + PiP NewApi @RequiresApi fix), D.1a-fixes `419ee85` (triaged 15 cheap real bugs in place; baseline 135→120; lessons in CLAUDE.md `1984c20`), D.1b `a8779af` (ktlint plugin per-module — root `subprojects {}` can't see `libs` accessor in Kotlin DSL) + `4c0b50d` (mechanical reformat across 127 files, Compose `@Composable PascalCase` triggers non-fixable warnings, ignoreFailures=true accepts them) + `5a8e7e3` (`.git-blame-ignore-revs` so the reformat doesn't poison blame). Catalog keys: `ktlintCli` + `ktlintPlugin` (camelCase, flat — `ktlint` + `ktlint-plugin` collide via hyphen-nesting). When flipping `ignoreFailures=false` later, add `.editorconfig` rule disabling `function-naming` for `@Composable`. |
| D.2 | **Reframed (2026-04-24)** from instrumented `:androidTest` to JVM unit tests + skill checklist after red-team — Compose UI test scaffolding is 2–4h for a single-tester personal app; the underlying mechanism (`PlacedFocusAnchor`'s await-then-request contract) is fully testable in JVM and the wiring layer (`key(contentType)` boundary) is more durable as a checklist + smoke-test entry than as instrumented tests. Existing test pattern in `BrowseShellLogicTest` already established this trade-off. | `PlacedFocusAnchorTest` (6 tests, all green) covers: suspends-until-placed, fires-immediately-when-already-placed, reset semantics, multiple concurrent calls, idempotent markPlaced. Native-android-mk skill gained: (1) "every `key(...)` boundary that scopes focus state holds its own anchor + requester" rule with the `4a8a46e` worked example, (2) 3-step cascade-nav smoke test for the human-loop check before merging HomeScreen/BrowseSection touches. Also fixed pre-existing test breakage in `BrowseShellLogicTest` from the `f432524` rename of `shouldStopLivePreviewForSection` → `shouldStopPlaybackOnSectionChange`. | **DONE** — see commit |
| D.3 | Turn on R8 in release: `isMinifyEnabled = true`, `isShrinkResources = true`. Discover + write keep rules for Media3 reflection, Koin module classes, Kermit, SQLDelight serializers, Ktor engines. | `./gradlew :app:assembleRelease` runs; resulting APK boots, plays a stream end-to-end, persists resume point. Per-ABI splits still under 60MB | **DEFERRED → MK.19.1** (renumbered from MK.12.1 when MK.12→18 gap-close was inserted 2026-04-24) — same DoD, same work, right home for it. Not needed for stability; needed before any store submission. Removed as a D-phase blocker 2026-04-24. |
| D.4 | `Thread.setDefaultUncaughtExceptionHandler` in `YancoApp.onCreate` writing last-crash to `filesDir/crash.log` + Kermit. NO Crashlytics, NO Sentry — owns its own data, no DSN management for a personal app | Force a crash → next launch reads `filesDir/crash.log` and surfaces it (or just logs it for now) | **DONE** `1e69da0` — `CrashReporter` singleton; atomic write (tmp→rename); reads+clears on next launch via `Log.e(TAG="YancoCrash")`; uses `android.util.Log` not Kermit (runs before shared module init) |
| D.5 | Behavioural tests for the two skill-checklist landmines: (a) `positionFor(contentId)` returns null for a series container with no content-level row (never an episode row), (b) `controller.currentId == target.id` short-circuit at every `controller.play(` call site — write a test fixture that calls each launch site twice with the same item, asserts the second call doesn't re-prepare the `MediaItem` | Both tests in `:shared:commonTest` (positionFor) and `app/src/test/` (currentId guard) | **DONE** `a8bc63a` — (a) already covered by `WatchHistoryRepositoryTest.positionFor_ignoresEpisodeRowsWhenNoContainerRow` (MB-41 guard). (b) extracted `resolveActivation(currentId, targetId, isTv) → ActivationAction` into `BrowseShell.kt`; updated `FavoritesScreen` two call sites to use it; `ActivationGuardTest` (6 tests) pins TV first-tap/second-tap/null and phone single-tap/already-playing/different-item routing. `PlaybackController.play()` also has the same guard internally (lines 153-157) — belt + suspenders. Auto-preview paths guarded via `resolveAutoPreviewIndex()` (already tested). |
| D.6 | Audit pass — read `logger/AndroidLogger.kt` (currently routes shared-module Logger to `android.util.Log`; CLAUDE.md claims "Kermit logging" but Android side doesn't actually use Kermit), grep all `BackHandler {` sites for missing back-stack handling, grep `controller.play(` sites for the two-tap guard, grep `AsyncImage(` for missing `contentDescription` | Punch list of findings written into a follow-up `D.7` task per finding | **DONE** — Audit clean across all three categories. (1) BackHandler: 7 instances (HomeScreen ×3, BrowseShell ×3, CoverflowSectionScreen ×1), all properly enabled-guarded with real handlers — no empty blocks. (2) controller.play(): 15 call sites, 15/15 guarded — HomeScreen uses explicit `currentId != target.id`, auto-preview uses `resolveAutoPreviewIndex()`, FavoritesScreen uses `resolveActivation()`, SearchScreen has inline alreadyPlaying guard. (3) AsyncImage: 15 call sites, 15/15 have `contentDescription` param (13 explicit null with paired text, 2 semantic string descriptions). No D.7 punch list needed. |

**Explicitly out-of-scope (red-teamed and rejected):**
- `:macrobenchmark` Gradle module + baseline-profile generator — weeks of yak-shaving for a personal IPTV app with no perf complaint on file. Use `dumpsys gfxinfo com.yancotv.android framestats` + the Compose recomposition reports above. Revisit only if a real jank report lands.
- Crashlytics / Sentry — DSN management, GDPR'd SDKs, `google-services.json` bloat. D.4's local crash log is the cheaper win.
- `scripts/logcat.sh` — wrong shell for a Windows-first repo (`scripts/` has `.ps1` + `.js`, no `.sh`). Personal-shell territory, not repo territory.

**Reading order for a cold reader picking this up:** [packages/android/CLAUDE.md](packages/android/CLAUDE.md) → [`.claude/skills/native-android-mk/SKILL.md`](.claude/skills/native-android-mk/SKILL.md) → this section → `git log --oneline 7fa1bf9^..` to see what D.0 actually changed → `app/build/compose_compiler/app_debug-module.json` for the latest baseline numbers.

---

## iOS / iPadOS (MK.iOS.* — post-Android 1.0)

Scoped as a separate milestone block once Android ships. Rough shape:

| Phase | Scope |
|---|---|
| MK.iOS.0 | Xcode project scaffold, Kotlin `shared` framework imported, SwiftUI "Hello" screen |
| MK.iOS.1 | SwiftUI shell — adaptive for iPhone vs iPad (split view on iPad) |
| MK.iOS.2 | Sources + credentials via iOS Keychain |
| MK.iOS.3 | Playback — AVPlayer default, VLCKit fallback for DTS/TrueHD |
| MK.iOS.4 | EPG / catchup / favorites / search reusing shared KMP |
| MK.iOS.5 | PIP + AirPlay + Chromecast |
| MK.iOS.6 | App Store submission |

---

## What's frozen in `packages/mobile/` (the RN app)

- **No new features.**
- **No bug fixes** except P0 crashes or data-loss bugs.
- **No plan advances** — `PRODUCTION_PLAN_ANDROID.md` is frozen at its current state. All open `MB-*` entries in [bugs.md](bugs.md) that target RN milestones are deferred; the Kotlin rewrite resolves them by construction (different codebase).
- **Kept runnable** until `packages/android/` reaches parity + ships an internal-track build. Then archived (not deleted — keep for reference).
- **No Metro upgrades, no RN upgrades, no dep bumps.** Pin everything.

## RN bug register — migration status

Updated in [bugs.md](bugs.md):

| ID | Status under Kotlin rewrite |
|---|---|
| MB-14 codec gap | Resolved by MK.9 |
| MB-15 hydration gate | N/A — Compose boots direct from SQLDelight |
| MB-17 nav sluggish | N/A — Compose navigation |
| MB-19 PhoneLayout broken | N/A — Compose adaptive layouts |
| MB-20 `Platform.isTV` misdetect | N/A — `UiModeManager` on Android |
| MB-21 SafeArea missing | N/A — `WindowInsets` in Compose |
| MB-22 dead conditional | RN only — won't touch |
| MB-23 `hasTVPreferredFocus` | N/A — Compose focus system |
| MB-24 visual-design gap | Addressed in MK.4.7 theme port + MK.5.3 badges |
| MB-30 FFmpeg extension | Scheduled MK.9 |
| MB-31 ABI splits | Scheduled MK.9 (ABI splits in Android Gradle) |
| MB-32 stream-type detection | Ported to Kotlin in MK.1.4 / MK.6 |
| MB-33 player error UI | Scheduled inside MK.6 |
| MB-34 release telemetry | Replaced by Firebase Crashlytics in MK.12.5 |

---

## Architecture rules (native)

1. **Shared Kotlin is pure business logic.** No `android.*` imports in `commonMain/`. Platform-specific code goes in `androidMain/` / `iosMain/` via `expect`/`actual`.
2. **SQLDelight is the only persistence surface for content, EPG, favorites, history.** No `SharedPreferences` / `DataStore` for content rows.
3. **Credentials in Android Keystore (EncryptedSharedPreferences) / iOS Keychain.** Never plaintext.
4. **One `ExoPlayer` instance** shared between mini-preview and fullscreen via `PlayerView.switchTargetView()`. Do not instantiate a second player.
5. **Compose for TV uses `androidx.tv.material`** for focus + surfaces. Never reuse `material3` clickables as focus targets on TV.
6. **`UiModeManager.UI_MODE_TYPE_TELEVISION`** is the TV detection source of truth. Don't use screen-size heuristics.
7. **ViewModels live in `shared/`** exposing `StateFlow<T>`. Compose screens `collectAsState()`; SwiftUI binds via KMP-generated helpers.
8. **No Retrofit, no Moshi, no Gson.** Ktor + Kotlinx Serialization only, so iOS target can compile.
9. **Delete-before-add** carries over — when a shared module replaces a platform-specific stub, delete the stub in the same commit.
10. **Desktop is unaffected.** `packages/core/` TypeScript keeps shipping Electron. Do not try to make Kotlin the source for desktop too — double port is cheaper than a single cross-language stack.

---

## Timeline — estimated

| Block | Weeks | Cumulative |
|---|---|---|
| MK.0 Scaffold | 0.5 | 0.5 |
| MK.1 Core port | 1.5 | 2 |
| MK.2 Persistence | 0.5 | 2.5 |
| MK.3 Sources | 0.5 | 3 |
| MK.4 Shell UI | 1 | 4 |
| MK.5 Channel list + images | 0.5 | 4.5 |
| MK.6 Playback (shared ExoPlayer) | 0.75 | 5.25 |
| MK.7 EPG | 1 | 6.25 |
| MK.8 Features | 1.5 | 7.75 |
| MK.9 Codec gap | 1 | 8.75 |
| MK.10 TV launcher | 1 | 9.75 |
| MK.11 Phone-native | 1 | 10.75 |
| MK.12 In-player control surface | 1 | 11.75 |
| MK.13 Channel ops + favorites reach | 0.6 | 12.35 |
| MK.14 Recording (HLS v1) | 1 | 13.35 |
| MK.15 EPG display + timeline | 0.8 | 14.15 |
| MK.16 Appearance + themes | 0.6 | 14.75 |
| MK.17 Network wiring + advanced playback | 0.6 | 15.35 |
| MK.18 External player + Cast | 0.4 | 15.75 |
| MK.19 Distribution | 1 | 16.75 |
| **Android v1.0 total** | | **~17 weeks** |
| MK.iOS.* | 6–8 | 23–25 |

---

## Decision log (native branch)

| Decision | Rationale | Date |
|---|---|---|
| Switch off React Native to native Android Kotlin + KMP | RN bridge structurally wrong for TV-class apps — every TiviMate feature needs a custom native bridge; native Activity fix already proved the pattern (2026-04-20). Goal is to beat TiviMate, substrate must match | 2026-04-20 |
| KMP (not pure Kotlin) from day one | iOS/iPad roadmap is right behind Android; KMP shares ~60% of code vs two full ports | 2026-04-20 |
| Native UIs (Compose + SwiftUI), not Compose Multiplatform | TV UI isn't supported in Compose Multiplatform. Sharing phone UI between Android and iOS produces an uncanny-valley iOS app. Native feel matters for a media app | 2026-04-20 |
| SQLDelight over Room | Room is Android-only; SQLDelight is KMP and generates typed queries for both targets | 2026-04-20 |
| Koin over Hilt | Hilt is Android-only; Koin is KMP-native and works on iOS | 2026-04-20 |
| Ktor over Retrofit | Retrofit is Android-only | 2026-04-20 |
| Firebase Crashlytics on Android, Sentry stays on desktop | Crashlytics is free + integrates with Play Console. Sentry on desktop already paid for | 2026-04-20 |
| RN app frozen, not deleted | Reference during rewrite; archive after Android ships | 2026-04-20 |
| `@yancotv/core` TS stays as-is | Double-port cost (~2 weeks) cheaper than a cross-language build toolchain | 2026-04-20 |
