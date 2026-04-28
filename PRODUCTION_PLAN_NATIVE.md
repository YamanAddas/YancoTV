# YancoTV — Native Mobile Production Plan (Kotlin Multiplatform + SwiftUI)

**Mission:** Beat TiviMate on Android TV / Fire TV / Google TV, ship iPhone + iPad alongside, reuse as much as possible between platforms via shared Kotlin business logic.

**Active as of 2026-04-20.** Supersedes [PRODUCTION_PLAN_ANDROID.md](PRODUCTION_PLAN_ANDROID.md) (the React Native plan, now frozen).

---

## Why we switched off React Native

One week of Fire TV black-screen-with-audio — fixed only by bypassing the RN bridge entirely and shipping a native `PlayerActivity` (M4R.Player, commit `09150e9`, 2026-04-20). The fix worked first try. Pattern recognition: every TiviMate-shaped feature we need (mini-preview that keeps playing while you browse, channel zap, PIP, Leanback integration, Android TV launcher channels, voice search) is a custom native bridge in RN and free in Compose. TiviMate, IPTV Smarters, Kodi, VLC — all native. The substrate has to match the competition if we want to beat it.

**Cost accepted:** RN-side M4R shell work (HomeShell, ContentPanel, navigation) is thrown away. `@yancotv/core` TypeScript business logic is ported to Kotlin. (Original 2026-04-20 estimate of ~10–12 weeks is preserved in git history; per 2026-04-25 decision, work proceeds at user's pace with no week budget.)

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
| Crash + error reporting | **Sentry SDK** (Android + KMP shared) — Stage 1.3 |
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

## v1.0 Roadmap — locked 2026-04-25

**v1.0 = a packed, signed, distributed APK that's complete and safe for daily personal use or store publishing.** No timeline — work proceeds at user's pace.

### Active work queue (last updated 2026-04-28)

User-set ordering for the immediate next sessions (overrides Stage 5 default order):

1. ✅ **MK.19.8 / Stage 5.1 — Backup / restore** — slices 19.8.1 → 19.8.6 shipped 2026-04-27. **19.8.7 two-Fire-TV verification deferred** until second device is in hand.
2. ✅ **MK.13.3 — Channel actions: custom-logo SAF picker** — **dropped** 2026-04-27 (`e88c7f2`). UX decision: SAF picker was the wrong shape for the use case; revisit only if user requests.
3. ✅ **MK.21 — Settings redesign** — Concept A "Configure" layout shipped across ~14 commits (`fd0dd2d` → `01690cb`, 2026-04-27 → 2026-04-28). Hex-cut sidebar + content pane, breadcrumb, unified `SettingsSection` / `SettingsRow` / `SettingsToggleRow` / `SettingsAccentButton` / `SettingsClickToEditField` primitives, source detail screen, per-source auto-sync toggle (MB-220 fix landed alongside this), EPG tab redesign (drops the embedded `GuideSyncPanel` card), scroll-bottom safety margin (root cause: `BringIntoViewSpec` was pinning the focused row to the panel border; fixed in `87fc40d` with a density-aware safe-margin spec). Footer (version + fake SYNCED chip) dropped 2026-04-28 (`01690cb`).
4. **MK.22 — Motion polish** — Sprint A (felt-lag fix: MB-221 sidebar expand + MB-222 OnNowTile clock) then Sprint B (polish: 200ms tab focus retry, HexSurface 5-spring collapse, hero crossfade timing). See "MK.22 — Motion polish" section below.
5. **MK.23 — Test hardening** — Sprint C (Critical: PlaybackController.persistResumePoint, BulkContentWriter.abortSource cross-source FK survival, FavoritesRepository multi-list) then Sprint D (High/Medium: SourceSyncCoordinator re-entrancy, syncSource cancellation, migration v8/v9, reminders FK SET NULL on EPG re-sync). See "MK.23 — Test hardening" section below.
6. **Polish sweep** — MK.20 follow-ups (multi-word region names, missing 2-letter codes BG/CZ/HR/HU/IS/KZ/etc., pin-a-bucket), plus any UX leftovers from prior milestones. Subsumes the original "MB-208 / MB-209 / MB-210 receiver-path test hardening" item — those tests are largely covered by MK.23 Sprint D and the existing recording-subsystem tests added 2026-04-27.

After this queue clears, return to Stage 5 default order (5.2 sideload auto-update, 5.3 a11y audit, …).

The MK.* numbering below stays as a reference catalog; what's authoritative going forward is the **5-stage dependency + risk ordering** in this section.

### Status (per 2026-04-25 audit)

**Shipped:** MK.0–MK.8, MK.9.1–9.5 (Stage 1.2 closed 2026-04-25), Stage 1.3 Sentry (2026-04-25), Stage 1.4 R8 baseline (2026-04-25), Stage 1.5 DB migration tests + corruption recovery (2026-04-25), Stage 1.6 perf budget + baseline (2026-04-25), Stage 2.1–2.6 schema backbone v3 → v8 (2026-04-26), MK.12a/b, MK.13.1–13.3, MK.16.shell, MK.16.1, MK.16.sheet, MK.16.player.vod.dock, MK.16.player.vod.chrome, MK.17.1, MK.18.1. **Stages 1 + 2 complete.** D-phase complete. 133 commits pushed to `origin/master` 2026-04-25.

**Not started:** MK.10 (Recommendations + voice search only — TIF deferred), MK.11.1/2 (PIP + gestures), MK.13.4, MK.14, MK.15, MK.16.2–16.6, MK.17.1a/2/3/4/5, MK.18.2, MK.19.

**Pending verification:** none. MB-14 hands-on regression closed 2026-04-25. **Stage 1 complete.**

**Dropped or deferred:** see Locked decisions table below.

### Ordering principles (challenge any of these before deviating)

1. **Foundational before features.** Schema, observability, R8, theme — land before features built on top.
2. **Risk-front-loaded.** The biggest unknown ships first. If FFmpeg can't be made to work, find out before stacking 6 features on it.
3. **Observability before complex features.** Sentry in early — every feature after gets crash + error reports for free.
4. **R8 baseline early.** Reflection libs (Media3, Koin, Kermit, SQLDelight, Ktor) break under R8 in subtle ways. Land R8 once, keep it green continuously.
5. **Schema migrations bundled.** All v1.0 migrations land together — one test pass, one upgrade path. No fragmenting migrations across features.
6. **No UI on stubs.** Recording UI doesn't get built before the recording service is real.
7. **Per-feature definition-of-done.** Every Stage 3+ feature lands R8-tested, Sentry-instrumented, TalkBack-checked, D-pad-checked, no new placeholders introduced.

### Stage 1 — Foundation

| # | Task | Maps to |
|---|---|---|
| 1.1 | ✅ Push 133 commits to `origin/master` (done 2026-04-25) | — |
| 1.2 | ✅ **MK.9 FFmpeg ExoPlayer extension** + crash watchdog + platform-decoder fallback. Closes MB-14. **Shipped 2026-04-25** (`8f7551f` + `62a7ebe` + watchdog/buffer/surface fixes from MB-119): vendored sources + JNI shim live under `app/src/main/`, `DefaultRenderersFactory` registers `FfmpegAudioRenderer` with `EXTENSION_RENDERER_MODE_ON` (hw decoder preferred, FFmpeg only when MediaCodec rejects) in `PlaybackController` (no `PlaybackService` — controller owns the player), R8 keep rules in `proguard-rules.pro`, watchdog releases-and-rebuilds with `EXTENSION_RENDERER_MODE_OFF` on FFmpeg-package errors (one rebuild per session), unit tests pin the classifier. ABIs scoped to `arm64-v8a` + `armeabi-v7a` (x86 / x86_64 dropped — emulator-only). ExperimentalFfmpegVideoRenderer was deliberately removed (software HEVC ANRs on Fire TV-class hardware). **Hands-on regression closed 2026-04-25:** user reports no remaining audio-only / no-audio channels on the live source. | MK.9 |
| 1.3 | ✅ **Sentry SDK integrated** (2026-04-25). `io.sentry:sentry-android` meta-package wired into `YancoApp.onCreate` via `SentryInit.install`; DSN in `local.properties` → `BuildConfig.SENTRY_DSN`; auto-init `ContentProvider` disabled in manifest; `SentryKermitWriter` bridges shared-KMP Kermit logs to breadcrumbs/events; native FFmpeg crashes captured by `sentry-native` signal handler. Org `catbyte`, project `yancotv-androidtv`. Performance tracing OFF (Stage 1.3 = crash + error reporting per plan). Mapping upload deferred to 1.4; opt-out toggle deferred to 5.6. | MK.19.5 (re-platformed) |
| 1.4 | ✅ **R8 / ProGuard baseline** (2026-04-25, `2f277db`). `isMinifyEnabled = true` + `isShrinkResources = true` for release builds. Keep rules in `proguard-rules.pro` cover FFmpeg JNI bridge, Kotlinx Serialization synthetic `$$serializer` companions for `:shared` types, Sentry reflection, SQLDelight adapters, Koin module DSL. Verified end-to-end on AFTDCT31: release APK launches, `Sentry initialised — env=release`, `Loaded FfmpegAudioRenderer`. APK size 29 MB → 15 MB (-48%). Real signing config still uses debug keystore (Stage 5.7). Mapping upload to Sentry for release-build symbolication is the natural follow-up — needs a Sentry auth token in `local.properties`. | MK.19.1 |
| 1.5 | ✅ **DB migration test harness + corruption recovery** (2026-04-25). `MigrationTest.kt` in `:shared/androidUnitTest`: hand-crafted v2 fixture, seeds content row, runs `YancoDb.Schema.migrate(driver, 2, 3)`, asserts override columns are NULL on existing rows; plus empty-DB and fresh-create sanity tests. `SourcesBackup` in `:shared/androidMain`: dumps `sources` table to `<filesDir>/sources-backup.json` on every successful open. `DatabaseFactory.android.kt` wraps open in try/catch — on failure, deletes DB + sidecars (`.db-journal`, `.db-wal`, `.db-shm`), creates fresh, restores sources. Encrypted credential blobs ride along; Android Keystore survives DB deletion so restored sources work without re-auth. Five backup-tests pin round-trip + atomic-replace. Build-time `verifyMigrations` was tried but Windows JBR + sqlite-jdbc native-link issue blocks it; runtime tests cover the same ground. **2026-04-26 follow-up:** SQLDelight 2.0.2 still auto-creates `:shared:verifyCommonMainYancoDbMigration` whenever `.sqm` files exist regardless of DSL config, so `gradlew clean build` was failing on Windows even after Stage 1.5 closed. Disabled the task on Windows hosts via a `tasks.matching { ... }.enabled = false` guard in `:shared:build.gradle.kts`; Linux/macOS CI keeps the build-time verification. `gradlew clean build` is now green on the canonical Windows dev box. | new (was implicit) |
| 1.6 | ✅ **Performance budget set + measured** (2026-04-25). [`packages/android/PERFORMANCE.md`](packages/android/PERFORMANCE.md) commits the four-axis budget (cold start ≤2.5s, zap p95 ≤400ms, EPG ≤5% jank / 60fps) and the AFTDCT31 baseline. `ZapLatencyTracer` (debug-only) instruments D-pad → first-frame in `PlayerActivity` for the zap measurement. **All four metrics currently fail the budget on debug build:** cold start 11.3s median, zap p95 ~2.7s (0/18 samples within budget), EPG vertical scroll 76.5% jank, EPG horizontal scroll 93.7% jank / ~4 fps. Gap analysis + per-metric reproduction commands committed; Stage 3+ feature work owns closing each gap, Stage 5.4 re-runs the matrix on release builds as the v1.0 gate. | new |

### Stage 2 — Schema backbone

Bundle all v1.0 schema migrations in one commit series, run upgrade tests once, move on. No feature in Stage 3 or 4 starts before this stage closes.

**Stage 2 closed 2026-04-26.** Schema went v3 → v8 across five `.sqm`
migrations (`3.sqm` … `7.sqm`); `Stage2MigrationTest` exercises the
full v3 → current path against a realistic seeded fixture and asserts
every backfill, default, and new table contract.

| # | Task | Maps to |
|---|---|---|
| 2.1 | ✅ `RecordingSchedules.sq` + `recordings.format` (`3.sqm`, v3 → v4). State machine for armed schedules decoupled from in-flight recording status; soft FKs (`ON DELETE SET NULL`) on content / programme / recording links. | MK.14.3 schema half |
| 2.2 | ✅ `FavoriteLists.sq` + `favorites.list_id` (`4.sqm`, v4 → v5). Default list seeded with stable id `'default'` (is_default=1) at fresh-create AND migration; legacy favorites backfilled to that list. UI guards default-list deletion. | MK.13.4 schema half |
| 2.3 | ✅ `Sources.referer` (`5.sqm`, v5 → v6). `user_agent` already on the genesis schema; this commit adds the missing companion for providers that gate playback on the Referer header. | MK.17.5 schema half |
| 2.4 | ✅ `Sources.epg_priority INTEGER DEFAULT 0` (`6.sqm`, v6 → v7). The plan entry's "EpgSources.sq" was a typo — followed MK.15.7's intent and put the column on the existing `sources` table. Index DESC for the EPG-merge "highest priority for tvg_id" pattern. | MK.15.7 schema half |
| 2.5 | ✅ `BackupMetadata.sq` (`7.sqm`, v7 → v8). Local record of user-initiated full-app exports — file URI, label, schema_version, SHA-256 checksum, size, per-class record counts. Distinct from Stage 1.5's silent `sources-backup.json` corruption-recovery file. | new |
| 2.6 | ✅ `Stage2MigrationTest` covers v3 → current with seeded source / content / epg / recording / favorite. Plus empty-DB sanity + fresh-create default-list assertion. 333 unit tests pass after Stage 2. | new |

### Stage 3 — Heavy features (touch playback core)

| # | Task | Maps to |
|---|---|---|
| 3.1 | **MK.14 Recording — full HLS *and* MPEG-TS.** Foreground service (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`), MediaStore writes, WorkManager schedules, EPG long-press hook, recordings browser, playback-conflict handling, recording sheet panel. **Storage management ships in this stage (not deferred):** user-set max-disk cap (default 16 GB), auto-cleanup oldest-first when cap hit, per-recording size shown in browser, low-storage warning before scheduled record fires. **Spec the "record while playing? while another channel plays?" interaction questions BEFORE writing code.** MPEG-TS is non-negotiable — Xtream catch-up is mostly TS; HLS-only is not "complete". | MK.14.1–14.7 (re-scoped) |
| 3.2 | **MK.11.1/2 Phone PIP + gesture controls.** `enterPictureInPictureMode` on phone player. Gesture seek / volume / brightness. | MK.11.1, MK.11.2 |

(MK.11.3 Cast and MK.18.3/4/5 dropped — see Locked decisions below.)

### Stage 4 — Surface features

| # | Task | Maps to |
|---|---|---|
| 4.1 | **MK.15 EPG display options** — days forward/back, timeline duration, row height, now-line + jump-to-now, programme details dialog, catch-up badge + play-from-here, multi-EPG conflict priority UI. (Schema 2.4 already migrated.) | MK.15.1–15.7 |
| 4.2 | **MK.17.1a–17.5 Network + playback prefs** — UA preset dropdown, test connection, HW decoder pref + fallback, buffer tuning presets, per-source UA UI. (Schema 2.3 already migrated.) | MK.17.1a, 17.2, 17.3, 17.4, 17.5 |
| 4.3 | **MK.18.2 default external player per content type.** | MK.18.2 |
| 4.4 | **MK.13.4 Multi-favorite-lists UI** — tabs in FavoritesScreen, list picker on add-to-favorites. (Schema 2.2 already migrated.) **Reconciles with MK.13.1's reactive star:** `isFavoriteFlow(contentId)` redefined as "favorited in any list"; star toggles add/remove from the user's "default list" pref, with long-press surfacing the list picker. | MK.13.4 UI half |
| 4.5 | **MK.10 TV launcher minimal** — Recommendations channel via `androidx.tvprovider`, voice search deep link via Google Assistant, on-screen channel zap polish. **TIF dropped → post-v1 study.** | MK.10.1, MK.10.3, MK.10.4 |
| 4.6 | **MK.16.2–16.6 Theme polish** — additional themes (start with Midnight Sapphire as #2), accent picker (4 presets), font-size scale (90/100/110/125%), channel-number toggle + grouping, app-icon variants via `activity-alias`. | MK.16.2–16.6 |

### Stage 5 — Ship-readiness

| # | Task | Maps to |
|---|---|---|
| 5.1 | **Backup / restore** — sources + favorites + history + channel prefs + recording schedules. Export to JSON (Keystore-wrapped credentials), import on fresh install. Tested by full reinstall on a second Fire TV. | new |
| 5.2 | **Sideload auto-update check** — polls GitHub Releases tag list at boot (cached 24h), prompts on new tag, downloads signed APK, then routes through Android's `ACTION_INSTALL_PACKAGE` with `REQUEST_INSTALL_PACKAGES` permission. On Android 8+ the user is sent to system "install unknown apps" screen for YancoTV the first time; document this in the prompt copy. Auto-update is opt-in (default on, toggleable in Settings → About). | new |
| 5.3 | **Accessibility audit** — final TalkBack + D-pad sweep across every Stage 3–4 surface. Per-feature DoD covers most of this; 5.3 is the catch-everything pass. | new |
| 5.4 | **Performance audit** — verify against Stage 1 budget on Fire TV Lite, fix regressions. | MK.19 perf piece |
| 5.5 | **Placeholder audit** — every "COMING IN MK.XX" string is gone from shipped code. **Specifically:** `SettingsAppearanceTab` is filled by Stage 4.6; `SettingsRecordingsTab` by Stage 3.1. Tabs not filled by any v1.0 stage (`SettingsSubtitlesTab`, `SettingsNotificationsTab`, `SettingsStorageTab`) are removed from the Settings nav in this stage — schema and screens stay in tree for post-v1, just unwired from sidebar. Player-options sheet stub panels (SLEEP / FAV / EXT / CAST / LOOK) are wired or removed: SLEEP via MK.12b.1, FAV via MK.13.1, EXT via MK.18.1 (already done), LOOK via MK.16.2 picker; CAST tab is removed (Cast was dropped). | new |
| 5.6 | **Privacy policy + ToS + content rating questionnaire.** Required by Play Console + Amazon Appstore. **Privacy policy must explicitly disclose Sentry** — crash reports, device model, OS version, stack traces leave the device to Sentry's SaaS (region selectable; pick EU if any EU users). User-toggleable opt-out in Settings → About → "Send crash reports". Recording legal posture: explicit user acknowledgement on first record action. | new |
| 5.7 | **Distribution pipeline** — Play Console listing (TV + phone), Amazon Appstore (Fire TV), GitHub Releases signed APK + `update.json` endpoint feeding 5.2. | MK.19.2, 19.3, 19.4 |
| 5.8 | **Manual QA matrix** — Fire TV Stick 4K, AFTDCT31 (192.168.68.56), phone HT74J0206349, Google TV emulator, mid-range Android TV. Extended soak with real streams + recording E2E (run until no new bugs surface for several sessions; no fixed duration per no-timeline rule). | MK.19.6 (expanded) |

### Definition of Done — every Stage 3+ feature

A feature is not "done" until all of these pass on its commit:

- ✅ R8 release build green (`./gradlew :app:assembleRelease`)
- ✅ Sentry-instrumented (errors flow to Sentry; new error class registered if needed)
- ✅ TalkBack pass (every new interactive surface has `contentDescription` or proper semantics)
- ✅ D-pad pass (every new focusable surface walks correctly with hardware remote on Fire TV)
- ✅ Fire TV soak test (≥30 min real-stream playback with the new feature exercised)
- ✅ No new "COMING IN MK.XX" placeholder strings introduced
- ✅ Migration test green (if schema touched)
- ✅ Performance budget honored (if hot path touched)

This is the only way "complete v1.0" doesn't end with a 3-week regression-fixing tail.

### Locked decisions (2026-04-25)

| Decision | Status |
|---|---|
| Sentry over Firebase Crashlytics | **Adopted** |
| Chromecast (MK.11.3 / MK.18.3) | **Dropped permanently** — receiver feasibility uncertain for IPTV streams; install YancoTV directly on every TV |
| TIF live-channels integration (MK.10.2) | **Deferred — post-v1 study.** Fire TV doesn't support TIF, value is Google-Android-TV-only |
| DLNA / UPnP (MK.18.4) | **Dropped permanently** — built for stored media not live streams; usage pattern doesn't need it |
| Cross-device handoff (MK.18.5) | **Deferred — post-v1 study.** Requires either a cloud backend (out of scope) or LAN-only (loses home/away use case) |
| MK.10 TIF replacement | **Recommendations channel + voice search deep link only** |
| Recording (MK.14) scope | **HLS + MPEG-TS both, in v1.0.** HLS-only is not "complete" |
| Definition-of-Done per feature | **Adopted** — see above |
| No timeline | **Adopted** — work proceeds at user's pace |

### Post-v1 ideas register

Flagged here so future-us doesn't re-litigate from scratch. Both depend on architectural shifts that are out of scope for v1.0.

- **TIF (TV Input Framework)** — inject YancoTV channels into the Android TV system Live Channels app. Pros: voice search tunes directly, channel up/down works system-wide, surfaces in Google TV "Live" recommendations. Cons: Fire TV doesn't support TIF (zero value on the canonical test target); massive scope (`TvInputService`, channel/program metadata sync into TIF DB, surface session for video, EPG re-ingestion, parental re-wired); maintenance burden as TIF data desyncs from app data. Revisit only if Google TV becomes the primary target.

- **Cross-device handoff** — pause on TV, resume on phone where you left off. Pros: nice continuity UX for VOD/series. Cons: needs either a cloud backend (Supabase/Firebase + privacy policy + GDPR if EU users) or LAN-only (loses the home/away use case which is the only reason handoff is interesting); marginal value for live TV (most IPTV usage). Revisit only if a cloud backend gets added for other reasons.

---

## Milestone reference catalog (MK.0 → MK.19)

> **As of 2026-04-25 the 5-stage map above is authoritative.** This section preserves the detailed task definitions, DoD criteria, and historical notes — Stage tasks reference MK.* IDs from here.

Each milestone ends in a tagged APK (and later TestFlight build) + a commit series. "Delete-before-add" rule from the RN plan carries over.

### **MK.0 — Scaffold**

| # | Task | DoD |
|---|---|---|
| MK.0.1 | Create `packages/shared/` KMP module — Gradle Kotlin DSL, targets android + ios, Koin + SQLDelight + Ktor + Serialization + Coroutines wired | `./gradlew :shared:build` green; empty `commonMain/kotlin/com/yancotv/shared/Platform.kt` returns a string on both targets |
| MK.0.2 | Create `packages/android/` Android Studio project — Compose + `androidx.tv.material` + Hilt-vs-Koin decision locked (Koin for KMP), min SDK 24, existing keystore referenced | `./gradlew :app:assembleDebug` green; installs on Fire TV + phone |
| MK.0.3 | `MainActivity.kt` with a Compose "Hello YancoTV" that detects TV vs phone via `UiModeManager` and branches | Debug APK launches on both form factors, shows correct branch |
| MK.0.4 | Port `PlayerActivity.kt` + `PlayerLauncherModule` → plain Android `PlayerActivity` (no RN bridge; callers invoke it directly via Intent) | Hard-code a test stream; Activity plays it when launched from `MainActivity` |
| MK.0.5 | Commit + tag `native-v0.0.0-scaffold` | tag pushed |

### **MK.1 — Shared core port**

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

### **MK.2 — Persistence**

| # | Task | DoD |
|---|---|---|
| MK.2.1 | SQLDelight schemas ported from `src/main/services/migrations/001–011.sql` | `./gradlew :shared:generateSqlDelightInterface` succeeds; schema matches desktop |
| MK.2.2 | Room-free Android SQLite driver config via SQLDelight's `AndroidSqliteDriver` | Android instrumentation test opens DB |
| MK.2.3 | FTS4 table + trigger-sync port | full-text search test passes |
| MK.2.4 | Migrations runner — version table, forward-only migrations | upgrade test from v1 → latest passes |

### **MK.3 — Sources**

| # | Task | DoD |
|---|---|---|
| MK.3.1 | `SourceRepository.kt` in shared — add / remove / list / sync | unit tests with in-memory DB |
| MK.3.2 | Credential storage via `androidx.security.crypto.EncryptedSharedPreferences` (Keystore-backed) | Xtream/Stalker credentials round-trip |
| MK.3.3 | `SourceSyncService.kt` (Android) wrapping shared repo, exposing progress via Flow | background sync via WorkManager triggers |

### **MK.4 — Shell UI**

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

### **MK.5 — Channel list + image loading**

| # | Task | DoD |
|---|---|---|
| MK.5.1 | Paged SQLDelight query helpers: `listByType(type, groupId?, limit, offset)`, `searchFts(q, limit, offset)` | cursor-based scroll in `ContentPanel` |
| MK.5.2 | Coil 3 + disk LRU + memory cache + crossfade | logos/posters don't re-decode on scroll |
| MK.5.3 | Quality badge parser (regex from M4R.D.4) → Compose badge chips | real channel titles render correct pills |

### **MK.6 — Playback (shared ExoPlayer, mini ↔ fullscreen)**

| # | Task | DoD |
|---|---|---|
| MK.6.1 | `PlaybackService.kt` (Media3 `MediaSessionService`) owns a single `ExoPlayer` + `MediaSession` | player survives config changes; resumes after background |
| MK.6.2 | `MiniPlayerView.kt` — `PlayerView` in the top-right slot of `HomeShell`, binds to the shared `ExoPlayer` | plays in the corner while user browses |
| MK.6.3 | `PlayerActivity.kt` (port) — fullscreen; attaches **the same** `ExoPlayer` to its `PlayerView` via `PlayerView.switchTargetView()` → no rebuffer | Enter on focused channel expands to fullscreen with zero rebuffer |
| MK.6.4 | Back from fullscreen → `switchTargetView()` returns surface to mini slot; player keeps playing | no black frame, no audio gap |
| MK.6.5 | D-pad Up/Down on fullscreen zaps channels (preview via brief `seekTo(0)` on swap) | TiviMate-style zap |

**Ship criterion:** tap channel in `ContentPanel` → plays in mini slot → Enter fullscreens seamlessly → Back returns to mini. No rebuffer. No black frame.

### **MK.7 — EPG**

| # | Task | DoD |
|---|---|---|
| MK.7.1 | XMLTV fetch + parse in shared | populates `epg_programmes` table |
| MK.7.2 | `NowNextRow.kt` ribbon under channel rows | updates every minute |
| MK.7.3 | `GuideScreen.kt` — 2D LazyGrid (channels × time), 6h window | scrolls 200 channels × 24h at 60fps |
| MK.7.4 | Programme reminders via `AlarmManager` + NotificationManager | tap notification → opens channel + plays |

### **MK.8 — Catch-up, Timeshift, Favorites, History, Search, Settings, Parental**

| # | Task | DoD |
|---|---|---|
| MK.8.1 | Catch-up URL resolution via shared `CatchupUrlBuilder` | past programme → plays catchup stream |
| MK.8.2 | Timeshift — ExoPlayer DVR buffer window | pause/rewind live TV works |
| MK.8.3 | Favorites — pinned group at top of category rail | star toggle on focused cell |
| MK.8.4 | Watch history + resume badge in mini-preview | resumes VOD at last position |
| MK.8.5 | Search — FTS4-backed overlay (TV remote search key + phone Ctrl-K / search bar) | results render in <100ms |
| MK.8.6 | `SettingsScreen.kt` — Sources, Playback, Network, EPG, Parental, Shortcuts, About | matches desktop coverage |
| MK.8.7 | Parental PIN — shared hashing port + Keystore-wrapped storage + channel lock/hide/override | PIN gate works |

### **MK.9 — Codec gap (FFmpeg ExoPlayer extension)** — Stage 1 priority

> **Now Stage 1.2 in the v1.0 roadmap above.** Closes MB-14 (~30% of streams currently audio-only). Sources unparked into `app/src/main/` 2026-04-25 (`8f7551f`); watchdog landed in `62a7ebe`. Scope completed: extension wired through `PlaybackController` (not a `PlaybackService` — the parked notes assumed one), platform-decoder fallback via `EXTENSION_RENDERER_MODE_OFF`-rebuild on confirmed FFmpeg-package errors, R8 keep rules pre-staged for Stage 1.4. ABIs `arm64-v8a` + `armeabi-v7a` only; x86/x86_64 dropped (emulator-only). MK.9.6 (10-channel regression on real source) still pending.

| # | Task | DoD |
|---|---|---|
| MK.9.1 | Clone `androidx/media` at matching tag; build FFmpeg decoder extension via NDK for armeabi-v7a + arm64-v8a + x86_64 | `.so` artifacts produced |
| MK.9.2 | Vendor libs into `packages/android/app/src/main/jniLibs/`; APK splits per ABI | APK ships all three ABIs via splits, sub-60MB each |
| MK.9.3 | Register `FfmpegAudioRenderer` / `FfmpegVideoRenderer` in `PlaybackService`'s `DefaultRenderersFactory` with `EXTENSION_RENDERER_MODE_PREFER` | extension preferred over platform codecs |
| MK.9.4 | **Crash watchdog** — `Player.Listener.onPlayerError` catches FFmpeg native crashes, releases the current `ExoPlayer`, rebuilds it on the same `PlaybackService` path with a platform-only `RenderersFactory` (preserves Architecture rule 4: still one ExoPlayer at a time, never two simultaneously), retries `prepare()` once; if that also fails, surfaces error overlay (no hard-crash of `PlayerActivity`). The `MiniPlayerView`/`PlayerView.switchTargetView()` path stays valid because the new player is bound to the same service. | Force-fault the FFmpeg renderer in a debug build → playback recovers on platform decoder; mini-preview and fullscreen continue to share the rebuilt instance |
| MK.9.5 | **R8 keep rules for native libs + JNI surfaces** — registered in same commit so release build doesn't strip the native registrations | `./gradlew :app:assembleRelease` plays the same regression streams as debug |
| MK.9.6 | Regression test against 10 real IPTV channels that were audio-only on the RN build (MB-14 register) | all 10 render picture; MB-14 closed |

### **MK.10 — TV UX + launcher integration**

| # | Task | DoD |
|---|---|---|
| MK.10.1 | Android TV recommendations channel (`androidx.tvprovider`) | recent + continue-watching cards on TV launcher home |
| ~~MK.10.2~~ | ~~Android TV "Live channels" integration (`TIF`)~~ — **DEFERRED post-v1** (decision 2026-04-25). Fire TV doesn't support TIF; value is Google-Android-TV-only and scope is massive. See "Post-v1 ideas register" above | N/A in v1.0 |
| MK.10.3 | Voice search via Google Assistant → deep link to Search screen | "Hey Google, play CNN on YancoTV" works. **Fire TV / Alexa limitation (2026-04-27 hands-on):** the remote's voice button always routes to Alexa system-wide; Alexa doesn't query third-party apps without the Alexa Skills SDK + Amazon cert (separate program). Our manifest hook is correct for Google Assistant on Google TV / Android TV, but on Fire TV the user uses the in-app overlay instead (KEYCODE_SEARCH already opens it). Future: add an in-app mic button that fires RECOGNIZE_SPEECH directly so Fire TV users get voice input without going through Alexa. |
| MK.10.4 | Leanback on-screen channel zap UI polish | feels smoother than TiviMate |

### **MK.11 — Phone-native features**

| # | Task | DoD |
|---|---|---|
| MK.11.1 | Phone PIP (`enterPictureInPictureMode`) | home button during playback → floating PIP |
| MK.11.2 | Gesture seek / volume / brightness on phone player | feels native |
| ~~MK.11.3~~ | ~~Chromecast sender via Media3's `CastPlayer`~~ — **DROPPED PERMANENTLY** (decision 2026-04-25). Default Cast receiver feasibility uncertain for IPTV streams (raw TS over HTTP with custom UA may not work without a Custom Web Receiver). User pattern: install YancoTV directly on every TV instead. Schema doesn't change so revisitable post-v1 if a new use case emerges | N/A in v1.0 |

---

## MK.12 → MK.18 — TiviMate gap-close (added 2026-04-24)

Post-MK.11 audit found that YancoTV Android has a solid core (one shared `ExoPlayer`, reactive SQLDelight repos, KMP business logic) but a thin user-facing control surface vs TiviMate 5.1.6. MK.12–MK.18 close that gap. Every sub-task is labelled:

- 🟢 **wire** — code already exists, connect it (fast)
- 🟡 **glue** — schema/repo exists, needs service + UI
- 🔴 **new** — greenfield

Old MK.12 (Distribution + QA + launch) moved to **MK.19** — unchanged scope.

**Red-team cuts already applied** (see bottom of this block): audio delay downgraded to "external player", auto-series recording replaced with manual series bind, DLNA cut, SOCKS proxy cut, cross-source user groups deferred to phase 2. (Original 2026-04-24 estimate was ~4 focused weeks; per 2026-04-25 no-timeline decision, the budget is dropped — use the 5-stage map at the top of this file for ordering.)

**Schema-migration budget:** 6 SQLDelight migrations expected across this block (`Content.sq`, `Sources.sq`, `Favorites.sq` + new tables `channel_prefs`, `favorite_lists`, `recording_schedules`). Each migration lands in the same commit as its `commonTest` upgrade test on a populated fixture. Apply the native-android-mk skill's "schema units" rule (ms vs seconds) on every new timestamp column.

### **MK.12 — In-player control surface**

Single biggest UX jump. Today the player overlay is zap bar + quick info; TiviMate users live in the player menu. Split into two sub-phases so fast wires ship first.

**MK.12a — fast wires** (ship first, each 🟢 except 12a.1)

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.12a.1 | ~~Player MENU / `KEYCODE_MENU` → single Compose bottom-sheet overlay (`ComposeView` child of `PlayerActivity`'s root). Sheet hosts 12a.2–12a.5 + 12b tasks~~ — **DONE `9c40c15`** (2026-04-24). Long-press CENTER deferred: needs `event.startTracking()` + `onKeyLongPress` plumbing that conflicts with the short-press show-controller path | 🔴 new | ✅ |
| MK.12a.2 | ~~Audio track picker via `TrackSelectionParameters`; persist pick to `AppPreferences.audioLang` (key already exists)~~ — **DONE `fdbe117`** (2026-04-24) | 🟢 wire | ✅ |
| MK.12a.3 | ~~Subtitle track picker + "off" + "load external file" row. External file triggers `MediaItem` rebuild → gated behind `currentId` / resume-point persistence check per native-android-mk rule~~ — **DONE** (2026-04-24). `SubtitlesView` in the sheet: Off row, each embedded `C.TRACK_TYPE_TEXT` track, and a "Load external file…" row that fires the SAF `OpenDocument` picker. External URI flows through `PlaybackController.applyExternalSubtitle` which captures `currentPosition`, calls `persistResumePoint()`, then rebuilds the `MediaItem` with a `SubtitleConfiguration` (MIME sniffed from extension) and re-seeks. LIVE short-circuits — subs for live IPTV isn't a real workflow | 🟢 wire | ✅ |
| MK.12a.4 | ~~Playback speed picker (0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 2.0×) via `player.setPlaybackSpeed()`; persisted per content-type~~ — **DONE** (2026-04-24). `PlaybackController.loadCurrent` resets LIVE to 1.0× and restores persisted speed on VOD/Episodes. Sheet reads live `player.playbackParameters.speed` so the ● marker stays in sync | 🟢 wire | ✅ |
| MK.12a.5 | ~~Aspect-ratio quick-cycle (Fit / Fill / Zoom / 16:9 / 4:3). Extend `AppPreferences.ResizeMode` enum; `PlayerView.resizeMode` already reactive~~ — **DONE `b1d09d3`** (2026-04-24). Sheet picker + `AspectRatioFrameLayout.setAspectRatio` for forced 16:9 / 4:3. Remote hotkey deferred — no obvious unbound Fire TV key | 🟢 wire | ✅ |

**MK.12b — heavier items**

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.12b.1 | Sleep timer (15 / 30 / 45 / 60 min / end-of-program / off). Coroutine ticker owned by `PlaybackController`; "end-of-program" reads `EpgRepository.currentProgramme(channelId).endTimeMs` | 🔴 new | Timer visible in sheet; fires `player.pause()` at expiry; cancellable |
| MK.12b.2 | **`channel_prefs` SQLDelight table + repo** — keyed on `content_id`; columns `audio_lang`, `subtitle_lang`, `speed`, `resize_mode`, `updated_at` (ms). Replaces the global-prefs fallback in 12a.2/12a.4/12a.5 with per-channel memory | 🔴 new | Channel A remembers Arabic audio, Channel B remembers English; global pref is the default when `channel_prefs` row is null |
| MK.12b.3 | ~~Audio delay~~ — **cut**; documented in sheet as "use external player (MK.18) for sync issues". Media3 has no first-class setter, `AudioProcessor` insert is week-plus with side effects | — | N/A (out of scope) |

**Innovation beyond TiviMate:** per-channel preference memory (12b.2). TiviMate resets audio/sub selection each session.

### **MK.13 — Channel ops + favorites reach**

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.13.1 | Add-to-favorites button in MK.12 bottom sheet; reactive star in zap bar + channel rows via `FavoritesRepository.isFavoriteFlow(contentId)` | 🟢 wire | Toggle from player → FavoritesScreen updates without navigation round-trip (MK.8 rule applies) |
| MK.13.2 | **Schema: `Content.sq` migration `0002_content_overrides.sqm`** — add `name_override TEXT`, `logo_override TEXT` nullable. Read-through in `ContentItem.displayTitle` / `displayLogoUrl`. Both optional, source-of-truth remains M3U fields | 🟡 glue | Rename + custom logo round-trip; upgrade test from schema v1 |
| MK.13.3 | Extend `ChannelActionsMenu` from 3 items → full set: Favorite / Rename / Custom logo (URL paste or pick from device) / Lock (existing) / Hide (existing) / Share stream URL. Drop "Cancel" row (Back handles it) | 🟡 glue | All 6 actions reach the repo; semantics applied per native-android-mk (contentDescription on every row) |
| MK.13.4 | **Multi-favorite-lists** — new tables `favorite_lists(id, name, sort_order)` + add `list_id FK` to `Favorites.sq`. Migration seeds a default list (`id=1, name="Favorites"`) and sets all existing favorites to it | 🔴 new | FavoritesScreen shows tab bar of lists; add-to-favorites from MK.13.1 prompts which list when >1 exists |
| MK.13.5 | ~~Move to cross-source user group~~ — **deferred to MK.20+** | — | Out of scope for v1 gap-close |

**Innovation beyond TiviMate:** multi-favorite-lists (13.4). TiviMate has one flat favorites list.

### **MK.14 — Recording + scheduling** — Stage 3 priority (HLS + MPEG-TS in v1.0)

> **Re-scoped 2026-04-25:** MPEG-TS recording moved into v1.0 (was phase 2). Without TS support, recording is broken on Xtream catch-up which is mostly TS — that's not "complete." DASH + encrypted segments stay phase 2.

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.14.1 | `RecordingService` (`ForegroundService` type `mediaProjection`-less variant, `FOREGROUND_SERVICE_TYPE_DATA_SYNC`) — HLS segment tee via OkHttp interceptor; writes to `MediaStore.Video` (scoped storage); updates `Recordings.sq` status column | 🟡 glue | 5-min HLS recording lands on disk; row moves `started → running → done`; file plays back via existing `PlaybackController` |
| MK.14.2 | "Record now" button in MK.12 bottom sheet + ongoing notification with Stop action | 🔴 new | Tap → service starts; notification persists until Stop; PlaybackController unaffected |
| MK.14.3 | **Schema: `recording_schedules.sq`** — `id, content_id, start_at_ms, end_at_ms, padding_pre_s, padding_post_s, repeat_rule (NONE/DAILY/WEEKLY), created_at_ms`. `WorkManager` one-shot `OneTimeWorkRequest` per schedule at `start_at_ms − padding_pre_s` | 🟡 glue | Schedule a recording for T+2 min → fires → completes |
| MK.14.4 | Record-from-EPG long-press in GuidePanel programme cell (uses 14.3's schema with pre/post paddings from prefs, default 0/+60s) | 🔴 new | Long-press any programme → "Record" row → schedule row created |
| MK.14.5 | `RecordingsScreen` in main nav — list / play / delete. Play via existing `PlaybackController.play(filePath)` | 🟡 glue | Screen shows past + in-progress recordings; delete removes row + file |
| MK.14.6 | ~~Auto series recording (XMLTV `episode-num` heuristic)~~ — **replaced** with manual series binding: user long-presses a programme → "Record all programmes with this title on this channel" → creates N schedules based on EPG lookahead window | 🔴 new | Binding one series produces ≥1 scheduled row for next 7 days |
| MK.14.7 | **MPEG-TS support** — segment tee for `.ts` HTTP streams (the dominant Xtream catch-up format). DASH + encrypted segments stay phase 2 with a disabled-state tooltip | 🔴 new (2026-04-25 re-scope) | TS streams record cleanly; DASH/encrypted streams show disabled record button with reason text |
| MK.14.8 | **Live recording via Media3 `TeeDataSource` (architectural pivot, 2026-04-26)** — replaces the second-HTTP-GET path that fails on single-stream IPTV accounts. New `RecordingDataSink` (writes ExoPlayer's existing byte stream to disk via `androidx.media3.datasource.DataSink`); `TeeingDataSourceFactory` wraps `OkHttpDataSource.Factory` with `TeeDataSource`; `PlaybackController` swaps to teeing factory; `RecordingService` routes live URL → tee path, catch-up URL (different from currently-playing) → existing `MpegTsRecorder`/`HlsRecorder` fresh-GET path; `RecordPanel` no longer calls `controller.stop()`/`activity.finish()` — player keeps playing while recording. | 🔴 new (2026-04-26 pivot) | Record-while-watching reliably saves bytes regardless of provider's concurrent-stream cap; player keeps playing; recording-in-progress file plays in `RecordingsScreen` while still being written |

**Innovation beyond TiviMate:** recording stays device-local (no cloud cost) + optional SMB push to NAS via `jcifs-ng` (post-record hook, MK.14.9 follow-up if time). TiviMate Premium's cloud archive costs $0 here. **Beyond TiviMate's policy of "buy a 2-connection plan to record-while-watching":** MK.14.8 lets users on 1-stream plans record-while-watching by tapping into ExoPlayer's existing data flow, no second HTTP connection needed.

#### MK.14.8 — Implementation plan (2026-04-26 pivot)

**Why we're pivoting.** The current `MpegTsRecorder`/`HlsRecorder` open a fresh HTTP GET to the same channel URL the player is already streaming. Most Xtream-style IPTV providers cap at 1 stream per account at the server. The recorder's parallel GET either hangs in `performGet` (server holds the slot for the player) or kicks the player off; both observed on the user's Fire TV provider. Five rounds of bandages (cancellation re-throw, `streamLive` flag, release-and-record, grace periods of 1 s and 5 s) all ride a fundamentally fragile second-connection assumption. The right answer is to *not open a second connection* — tap the bytes the player is already pulling.

**Architecture.**

```
ExoPlayer
   ↓ uses
DefaultDataSource.Factory (routes by URI scheme)
   ↓ for http(s)://
TeeingDataSourceFactory (NEW)
   ↓ wraps each created source in
TeeDataSource(upstream = OkHttpDataSource, sink = RecordingDataSink)
   ↓ when ExoPlayer reads bytes
upstream.read(buf, off, len) → returns N bytes
   ↓ Tee then calls
RecordingDataSink.write(buf, off, N)
   ↓ if active (begin() was called)
appends bytes to FileOutputStream(recordingFile)
```

**Components.**

| Component | File | Purpose |
|---|---|---|
| `RecordingDataSink` | `packages/android/app/src/main/java/com/yancotv/android/recording/RecordingDataSink.kt` (new) | Implements `androidx.media3.datasource.DataSink`. `begin(file)` opens a `FileOutputStream`; `end()` flushes + closes; `open(DataSpec)` and `close()` are **no-ops** (per-DataSpec lifecycle from Tee shouldn't end the user-driven recording — relevant for HLS where each segment is a separate DataSpec). Thread-safe via `synchronized` (writes from ExoPlayer's load thread; begin/end from RecordingService's IO scope). |
| `TeeingDataSourceFactory` | `packages/android/app/src/main/java/com/yancotv/android/recording/TeeingDataSourceFactory.kt` (new) | Implements `androidx.media3.datasource.DataSource.Factory`. Wraps an upstream factory + the singleton `RecordingDataSink`. `createDataSource()` returns `TeeDataSource(upstreamFactory.createDataSource(), recordingSink)`. |
| `PlaybackController` | (modify) | Inject `RecordingDataSink` via Koin. Swap data-source chain: `OkHttpDataSource.Factory` → `TeeingDataSourceFactory` → `DefaultDataSource.Factory`. Only HTTP traffic gets tee'd; file/asset/content URIs (used for playing back finished recordings) bypass it via DefaultDataSource's scheme routing. |
| `RecordingService` | (modify) | Inject `RecordingDataSink` and `PlaybackController`. New `handleStartLive(input, output)` path: `recordingSink.begin(output) → markStarted → register a no-op job in activeJobs so handleStop's lookup still works`. Decision: if `input.sourceUrl == controller.currentItem.value?.streamUrl` → live tee; else → existing `MpegTsRecorder`/`HlsRecorder` fresh-GET path (catch-up, scheduled). Modified `handleStop`: tee path calls `recordingSink.end()` then measures file size; fresh-GET path keeps `cancelAndJoin` flow. |
| `RecordPanel` (in `PlayerOptionsSheet.kt`) | (modify) | Remove `controller.stop()`, remove `activity.finish()`, remove the toast about "player paused." Replace with: "Recording started · keep watching or open Recordings". `onBack()` still dismisses sheet. Player continues. |
| `RecordingService.handleStart` | (modify) | Drop the `delay(GRACE_BEFORE_RECORD_MS)` for the live path (no second connection means no grace needed). Keep delay only for fresh-GET catch-up path if needed (probably also not — there's no concurrent player on a catch-up URL). |

**`RecordingDataSink` API contract (verified against `androidx.media3.datasource.TeeDataSource` source on `release` branch, 2026-04-26):**

- `TeeDataSource.open(DataSpec)` calls `upstream.open(dataSpec)` → if bytes > 0, calls `dataSink.open(...)`.
- `TeeDataSource.read(buf, off, len)` calls `upstream.read(...)`, then if read > 0, calls `dataSink.write(buf, off, bytesRead)`.
- `TeeDataSource.close()` closes upstream in `try`, closes dataSink in `finally`.

For continuous MPEG-TS (the user's case), ExoPlayer opens once, reads continuously, closes once. For HLS, one open/close per segment. Our DataSink's no-op `open`/`close` lets it survive across segments and write a concatenated file.

**Edge cases (v1.0 acceptable behavior).**

| Case | Behavior |
|---|---|
| User changes channel while recording | Tee continues writing into the recording file; bytes are now from the new channel. **Limitation**: file ends up with a glitchy switch. v1.0 mitigation: when `controller.play()` is called with a different MediaItem AND a recording is active, refuse with toast "Stop recording first" (or auto-stop the recording — TBD in implementation). |
| User exits player (BACK) | ExoPlayer is released; DataSource closes; no more bytes flow. Tee.close() fires our DataSink.close() — which is a no-op. Recording row stays in `RECORDING` status with whatever bytes were captured. User has to explicitly Stop from Recordings tab to finalize the row. |
| User pauses player | ExoPlayer keeps the DataSource open and keeps reading-ahead until buffer is full, then idles. Tee continues writing whatever ExoPlayer reads. Recording effectively pauses growing once buffer fills, resumes when user unpauses. Acceptable. |
| Buffer-ahead bytes captured | ExoPlayer's read-ahead means the recording captures bytes for the next ~15 s of playback when Record is pressed. So the recording starts *slightly ahead* of the visible playhead. Acceptable — TiviMate has the same behavior. |
| Multiple concurrent recordings | The current UI permits only one record-from-channel at a time (RecordPanel checks `activeForChannel`). With one DataSink singleton, only one live recording can be active. Catch-up recordings (fresh-GET path) can run concurrently with a live recording — they don't share the DataSink. |

**What we keep (does not regress):**

- `MpegTsRecorder` / `HlsRecorder` — kept for catch-up URLs (different from current playback) and future scheduled recordings (MK.14.3) where there's no player running.
- `RecordingService.handleStop` → `cancelAndJoin` → measure-file → `markCompleted`/`markFailed` flow. Same logic, just gated on whether the path is tee vs fresh-GET.
- `RecordingsScreen` UI, MIME=`video/mp2t` playback fix, focus-bleed fix, cancellation re-throws — all stay.

**Tests.**

- `RecordingDataSinkTest` (commonTest if pure Kotlin or androidUnitTest if wraps `DataSink` directly):
  - `beginThenWriteThenEndProducesFileWithBytes`
  - `writeBeforeBeginIsDropped` (no NPE, no file)
  - `multipleOpenCloseCyclesFromTeeDontTerminateRecording` (simulates HLS segment lifecycle)
  - `endIsIdempotent`
- Hands-on Fire TV smoke (after build):
  1. Open live UHD channel → wait for picture → MENU → RECORD → "Record this channel" → toast.
  2. Verify player KEEPS PLAYING (regression check vs current Option B).
  3. Open Recordings tab in another section of the app → row visible, size growing in real-time (`file.length()` reflects bytes ExoPlayer is reading).
  4. Tap Play on the in-progress row → second ExoPlayer instance plays the local file from offset 0. Should work because the `_rec_` MIME fix is in.
  5. Back to Recordings → tap Stop → row goes to Saved with full byte count.
  6. Play the finished recording → confirms it plays end-to-end.

**Estimated cost.** ~250 lines new code (RecordingDataSink + TeeingDataSourceFactory + 2 tests), ~80 lines of edits to PlaybackController/RecordingService/RecordPanel, ~30 lines test. **2–3 hours** end-to-end including the verify cycle on Fire TV. (Earlier estimate of "~half a day with HLS handled properly" assumed I'd also be writing the HLS-segment-concatenation in this same change — for our user's MPEG-TS-only provider that's not on the critical path; HLS Just Works because our DataSink no-ops `open`/`close`.)

**Out of scope for MK.14.8** (deferred to follow-ups):

- Refusing channel-change during recording with a clean error UX (out: ship simple "stop first" toast if needed; full refuse-flow can be MK.14.8a).
- Auto-stop recording on player release (out: rely on user explicit stop for v1.0).
- Real-time size updates in RecordingsScreen rows (out: file size is read on stop; live polling is MK.14.5a if users complain).
- HLS-segment-concatenation correctness audit (out: covered when we exercise an HLS provider; MK.14.7 territory).

**Migration / cleanup.**

- Revert the `controller.stop()` + `activity.finish()` + `delay(GRACE_BEFORE_RECORD_MS)` Option B path in this same commit so we don't carry both architectures simultaneously.
- Keep the cancellation re-throw fixes in MpegTsRecorder / HlsRecorder — they remain correct for catch-up.

**Verified API references (fetched 2026-04-26 from `androidx/media` `release` branch):**
- `TeeDataSource(upstream: DataSource, dataSink: DataSink)` constructor.
- `DataSink.open(DataSpec) / write(byte[], offset, length) / close()` methods + `DataSink.Factory.createDataSink()`.
- `Media3 1.6.0` (March 2025 release) included MPEG-TS extractor fixes — recording playback won't regress because we already pin a recent Media3 build via Gradle catalog.

### **MK.15 — EPG display + timeline prefs**

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

### **MK.16 — Appearance, themes, typography**

**`Theme.kt` is currently `object YancoPalette` with `val` colors → cannot swap at runtime.** Refactor to state-driven theme **before** MK.15.3, MK.16.2+.

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.16.shell | ~~**Settings shell redesign to Concept A**~~ — **DONE `<pending>`** (2026-04-24). Two-panel hex-cut layout (380dp sidebar + content pane, `CutCornerCardLarge`) replaces the prior 208dp rail. Sidebar renders all 14 tabs as 58dp hex-nav rows with icon tile, mono subtitle, ordinal, active-state gradient bar + chevron. Content pane gets a hex-chip breadcrumb and padded scroll region. 5 new placeholder tab bodies (`SettingsAppearanceTab`, `SettingsSubtitlesTab`, `SettingsRecordingsTab`, `SettingsNotificationsTab`, `SettingsStorageTab`) share a `SettingsPlaceholder` scaffolding so later milestones plug into a stable chassis. 11 new line-weight icons in `YancoIcons` (Theme / Subtitles / Signal / Link / Grid / Shield / Record / Bell / Hdd / Key / Info / Cloud) ported from `docs/design/design_handoff_yancotv/designs/ds/ds.jsx`. Focus model: `focusRestorer` on the rail, D-pad RIGHT hands off to tab body | 🟢 wire | ✅ |
| MK.16.1 | ~~**Theme refactor**~~ — **DONE `<pending>`** (2026-04-24). `YancoPalette` is now a `data class` (new `Palette.kt`), baseline instance `FrostedEmerald`, `LocalYancoPalette = staticCompositionLocalOf { FrostedEmerald }`. New `ThemeController` owns `StateFlow<ThemeId>` + `paletteFor(id)`; Koin-registered in `AppModules`. `YancoTheme` collects the flow and pushes the resolved palette through `CompositionLocalProvider` + rebuilds Material3 / `androidx.tv.material3` colour schemes keyed on palette via `remember`. 35 consumer files had 650 call sites rewritten (`YancoPalette.X` → `LocalYancoPalette.current.X`); 5 non-composable sites (2 module-level `Brush` vals in `FeatureHero` / `HomeContent`, a `remember {}` lambda in `AppSidebar`, a `Canvas { drawScope }` in `ContentRail`, a `colorsFor()` helper in `QualityChips`) hoisted or marked `@Composable`. `ChannelSurfOverlay` + `PlayerOptionsSheet` `ComposeView`s still render un-wrapped; they hit the `staticCompositionLocalOf` default (`FrostedEmerald`), matching pre-refactor rendering. Pref persistence (AppPreferences-backed `themeIdFlow`) + wrapping the player overlays ships with MK.16.2 | 🟢 wire | ✅ |
| MK.16.2 | 4 built-in themes — Frosted Emerald (existing baseline), Midnight Sapphire, Warm Amber, Monochrome. Picker in `SettingsScreen` new "Appearance" tab | 🔴 new | Each theme renders without color conflicts; focus ring and accent remain accessible |
| MK.16.3 | Accent picker — 4 presets (emerald / sapphire / amber / red). ~~Custom hex~~ **cut**; presets cover 90% | 🔴 new | Accent overlays on selected base theme |
| MK.16.4 | Font size scale (90 / 100 / 110 / 125 %) via `LocalDensity` override | 🟢 wire | Scales apply to all typography |
| MK.16.5 | Channel number display toggle + digit-grouping format. `KEY_SHOW_NUMBERS` pref exists; format is new | 🟡 glue | Numbers render only when enabled |
| MK.16.6 | App icon variants via `activity-alias` in manifest — 3 alternates (Emerald default, Mono, Amber) | 🔴 new | Icon change requires relaunch (standard Android behavior); documented |
| MK.16.sheet | ~~**Player-options sheet → Concept A port**~~ — **DONE `<pending>`** (2026-04-24). `PlayerOptionsSheet.kt` rewritten end-to-end: right-anchored 720dp side sheet with dark-emerald semi-opaque backdrop (true `RenderEffect.createBlurEffect` skipped — Fire TV stick-class GPU too slow for per-frame blur), sheet head with hex-capsule `PLAYER OPTIONS` badge + kicker + title + sub + 10-tab hex-capsule strip (2 rows × 5 tabs, hex-cut corners via a new `GenericShape`-based `hexRowShape(corner)` composable helper), scrollable body per-panel, foot hint bar. `SheetMode` enum grew from 5 (OPTIONS / AUDIO / SUBS / ASPECT / SPEED) to 10 panel modes (AUDIO / SUBS / SPEED / ASPECT / SLEEP / RECORD / FAV / EXT / CAST / LOOK) with tab-label / kicker / title / sub metadata on each variant. Wired panels (AUDIO / SUBS / SPEED / ASPECT) preserve every MK.12a.2–.5 wire under new hex-row visuals — speed gets a gradient "CURRENT" callout card; audio tracks get a language-code hex tile leading element. Stub panels (SLEEP / RECORD / FAV / EXT / CAST / LOOK) render a uniform "COMING IN MK.XX" accent-tinted callout — no backing logic, placeholder chassis only. `PlayerActivity` dropped its root-OPTIONS BACK-routing; sheet opens directly on AUDIO and BACK dismisses from any tab (no root-list hop — the tab strip IS the overview). Smoke-tested on Fire TV: MENU opens sheet, tab cycling via DPAD, BACK dismisses, zero `AndroidRuntime` FATALs | 🟢 wire | ✅ |
| MK.16.player.vod.dock | ~~**VOD player — controller-visible dock (Concept A port)**~~ — **DONE `<pending>`** (2026-04-24). New Compose file `VodPlayerDock.kt` renders, bottom-anchored over a vertical-gradient scrim: metadata block (kicker `NOW PLAYING · YANCO.VOD` + 44sp gradient-adjacent title from `ContentItem.cleanTitle` + chip row: type badge PREMIUM-toned + groupName MUTED) + progress row (mono played time + hex-clipped 8dp track with buffered layer + played layer, focusable with `onPreviewKeyEvent` seeking ±10 s on DPAD LEFT/RIGHT + mono duration) + transport row (5 hex-cut `TransportButton`s: PREV / -10 / PLAY-PAUSE 88dp primary focused / +10 / NEXT, plus a 6-chip `SecondaryChip` strip: CC / AUDIO / SPEED / ASPECT / FAV / MENU → `showSheet()`) + remote hint strip. `PlayerActivity` flips `playerView.useController = false` in `onItemChanged` when the current item is non-live and mounts the dock via a new `vod_dock_stub` ViewStub (`player_vod_dock.xml` is a ComposeView stub target); OK/CENTER toggles dock visibility for VOD while LIVE keeps `playerView.showController()`; 500 ms progress ticker reads `currentPosition` / `bufferedPosition` / `duration` into a `VodDockProgress`; 4 s auto-hide job (matches Media3's `CONTROLLER_TIMEOUT_MS`) resets on every `onUserInteraction` callback from the dock; `dispatchKeyEvent` guard extended to `!dockVisible` so Compose focus traversal gets the dock's key events; `onKeyDown` BACK dismisses the dock; `onStop` cancels both dock tickers. Keeps BUFFERING / ERROR overlays from `.chrome` on top with their existing state machine. Deliberately **does not** port: scrub-preview thumbnail grid (→ `MK.16.player.vod.scrub`), chapter ticks (no data source), center "PLAYING · 1.00X" pill (cosmetic), dynamic secondary chip labels (wired when sheet panels feed them back), HOLD for 2× gesture, favorites state (→ `MK.16.player.vod.metadata`). Build + install + launch smoke-tested on Fire TV (192.168.68.56:5555), zero `AndroidRuntime` FATALs; OK-press / auto-hide / seek / secondary-chip navigation to be re-verified by user with a real VOD item | 🟢 wire | ✅ |
| MK.16.player.vod.chrome | ~~**VOD player — buffering + error chrome (Concept A port)**~~ — **DONE `<pending>`** (2026-04-24). New Compose file `VodPlayerChrome.kt` hosts two full-screen overlay states (`VodChromeState { NONE / BUFFERING / ERROR }`) behind a `ComposeView` ViewStub in `activity_player.xml` replacing the old XML `streamErrorOverlay`. BUFFERING: hex-cut badge with Y glyph (static — rotation animation deferred to MK.16.player.vod.controls) + "BUFFERING" kicker pill + "Tuning the stream" headline + live diagnostic tiles (BITRATE / BUFFER / LATENCY / RES. pulled from `ExoPlayer.videoFormat/audioFormat/bufferedPosition`) + action row (RETRY / PLAYBACK OPTIONS → cross-opens sheet / CANCEL). ERROR: hex icon with × + `ERR · errorCodeName · N` kicker (Error tone) + code-mapped title ("Can't reach the stream" / "Server refused the request" / "Stream not found" / "This device can't decode the stream" / "Couldn't open this stream") + description + monospace diagnostic block (source / stream / remote / attempt — all blank for this slice, populated by MK.16.player.vod.metadata) + action row (RETRY / SWITCH TO 1080P stub / TRY ANOTHER SOURCE stub / REPORT ISSUE stub / BACK). `PlayerActivity` drives state via `Player.Listener`: `STATE_READY` sets `hasBeenReady=true` and clears any non-error chrome; `STATE_BUFFERING` after-ready schedules a 500 ms debounce job and then shows BUFFERING (skipped if error is up); `showStreamError()` populates ERROR + requests focus + hides Media3 controller. `onItemChanged` resets `hasBeenReady` so each fresh MediaItem re-gates the initial prepare's BUFFERING. `dispatchKeyEvent` untouched; `onKeyDown` chrome-first: BACK dismisses BUFFERING, BACK on ERROR dismisses + finishes (user's "I'm done with this stream"), other keys fall through to Compose focus. Overlay does NOT wrap in `YancoTheme` — falls back to FrostedEmerald via `LocalYancoPalette` default per MK.16.1 precedent. Per-commit scope discipline: metadata overlay, controls-dock replacement, scrub preview, next-up bumper, end card each get their own follow-up commit | 🟢 wire | Build + install + launch + STATE_READY + BACK teardown smoke-tested on Fire TV (192.168.68.56:5555) with zero `AndroidRuntime` FATALs; buffering-debounce and error-overlay trigger require physical network interruption — to be re-verified by user |

**Innovation beyond TiviMate:** on Android 12+ phone, the default theme respects **Material You** (wallpaper-derived accent); TV always stays branded.

### **MK.17 — Network wiring + advanced playback**

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

### **MK.18 — External player + Cast**

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.18.1 | ~~"Open in external" action in MK.12 sheet — `Intent.ACTION_VIEW` with package hints for VLC / MX Player / Just Player; detect installed apps via `PackageManager`~~ — **DONE `f15ffb8`** (2026-04-24) | 🔴 new | ✅ |
| MK.18.2 | Persist default external player per content-type (Live / Movie / Series) in prefs | 🟡 glue | Live defaults to VLC, VOD to internal (example); honored on next launch |
| ~~MK.18.3~~ | ~~Chromecast sender via Media3 `CastPlayer`~~ — **DROPPED PERMANENTLY** (decision 2026-04-25). See MK.11.3 for full reasoning. Sheet's CAST tab is removed in Stage 5.5 (placeholder audit) | — | N/A in v1.0 |
| ~~MK.18.4~~ | ~~DLNA / UPnP~~ — **DROPPED PERMANENTLY** (re-confirmed 2026-04-25). Built for stored media, not live streams; older smart TVs without Cast aren't a target | — | N/A |
| ~~MK.18.5~~ | ~~Cross-device handoff~~ — **DEFERRED post-v1** (decision 2026-04-25). Requires either a cloud backend (out of scope) or LAN-only (loses home/away use case which is the only reason handoff is interesting). See "Post-v1 ideas register" above | — | N/A in v1.0 |

---

### **MK.19 — Distribution + QA + launch**

> **Re-platformed 2026-04-25:** Sentry replaces Firebase Crashlytics (now Stage 1.3 — observability comes BEFORE the heavy features, not after). MK.19.6 device matrix expanded to match real test fleet. Backup/restore + auto-update check + privacy/ToS added to Stage 5.

| # | Task | DoD |
|---|---|---|
| MK.19.1 | R8/ProGuard config, APK-size audit. Now Stage 1.4 (lands early so every feature is release-tested continuously). Keep rules for Media3 reflection, Koin module classes, Kermit, SQLDelight serializers, Ktor engines | `./gradlew :app:assembleRelease` runs; APK plays end-to-end; per-ABI splits under 60MB |
| MK.19.2 | Play Console listing (TV + phone), screenshots, description, content rating, privacy policy URL | internal track first |
| MK.19.3 | Amazon Appstore submission (Fire TV) | pending review |
| MK.19.4 | GitHub Releases sideload APK (signed, versioned) + `update.json` endpoint feeding Stage 5.2 auto-update check | one-click install + auto-update prompt |
| MK.19.5 | **Sentry SDK** wired (Android + KMP shared). Replaces previously-planned Firebase Crashlytics (decision 2026-04-25). Now Stage 1.3 — observability lands BEFORE complex features so every feature gets crash + error reports for free | crash-free sessions tracked; new error classes registered as features land |
| MK.19.6 | Manual QA matrix — **real test fleet:** Fire TV Stick 4K, AFTDCT31 (192.168.68.56), phone HT74J0206349, Google TV emulator, mid-range Android TV. 1-week soak with real streams + recording E2E. | `packages/android/tests/MANUAL_QA.md` |
| MK.19.7 | **Privacy policy + ToS + content rating questionnaire** (new, 2026-04-25). Required by Play Console + Amazon Appstore. Recording legal posture: explicit user acknowledgement on first record action — copyright disclaimer | URLs published, Play Console + Amazon submissions pass review |
| MK.19.8 | **Backup / restore** (new, Stage 5.1). Sources, favorites, history, channel prefs, recording schedules → JSON export with Keystore-wrapped credentials. Tested by full reinstall on a second Fire TV | Round-trip preserves all user data |
| MK.19.9 | **Sideload auto-update check** (new, Stage 5.2). Polls GitHub Releases tag list at boot (cached 24h), prompts on new tag, downloads signed APK | Install older build → next boot prompts to update |

**Ship criterion for v1.0:** installable via Play Store + Fire TV Appstore + direct APK. Sentry-tracked, R8-hardened, performance-budget-honored. All RN-plan feature parity reached + TiviMate-style mini preview works + TV launcher minimal (Recommendations + voice search) live + recording (HLS + MPEG-TS) ships + per-feature DoD passes everywhere.

---

## MK.20 — Smart categories (provider order + language/region grouping) — added 2026-04-27

**Why.** Two related complaints converge on the same UI surface (the category rail used by Live / Movies / Series / Guide):

1. Today `distinctGroupsForType` and `distinctGuideGroups` `ORDER BY group_name` (alphabetical). Providers ship groups in a deliberate order — favourites first, then sports, then geos — and we throw that away. Result: the user re-scrolls past the same alphabetical wall every time. Provider order should be the default.
2. IPTV M3Us are heavily prefixed by language/region: `AR | beIN Sports`, `EN | BBC One`, `US| ESPN`, `CA - TSN`. Today every prefixed group is a flat sibling. We want to bucket them into collapsible parents (`Arabic`, `English`, `USA`, `Canada`…) with the parent label being the resolved full name, not the raw code. Toggleable from settings so users with non-prefixed lists keep the flat view.

**Red-team verified state (2026-04-27).** Three findings reshaped the original Slice plan:

- `Classifier.normalizeCategory()` *exists* and strips `^[A-Z]{2,3}[:|]` prefixes, but it is **not wired into any production write path** ([BulkContentWriter.kt:354](packages/shared/src/commonMain/kotlin/com/yancotv/shared/sources/BulkContentWriter.kt:354) inserts `e.groupTitle.ifBlank { null }` raw). The prefix is *already* sitting in `content.group_name` for every existing row. **No migration, no parser change, no re-fetch needed to recover it.**
- `content.sort_order` is already populated per row in provider arrival order (M3U + Xtream + Stalker paths all do `sortOrder++` after each bind). `MIN(sort_order)` per `group_name` gives provider-order-of-groups for free. **No `group_order` column needed.**
- `GroupPreferences` table already ships `is_hidden`, `is_pinned`, `custom_name` per (type, group_name). Hiding/pinning/renaming is solved infrastructure — MK.20 extends it, doesn't reinvent it.

This collapses MK.20 to almost-pure read-side work. One small migration only if the toggle moves from `AppPreferences` (file-backed) into a DB-backed setting.

### Slice 20.1 — Provider order as the default group sort

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.20.1.1 | Replace `ORDER BY group_name` with `GROUP BY group_name ORDER BY MIN(sort_order)` in `distinctGroupsForType` ([Content.sq:105](packages/shared/src/commonMain/sqldelight/com/yancotv/shared/db/Content.sq:105)) and `distinctGuideGroups` ([Content.sq:323](packages/shared/src/commonMain/sqldelight/com/yancotv/shared/db/Content.sq:323)). Existing `idx_content_sort_order(source_id, type, sort_order)` covers it; no new index. | 🟢 wire | Live / Movies / Series / Guide rails render groups in M3U order; flicker-free |
| MK.20.1.2 | Delete `prioritizedGroupsFor()` in [BrowseShell.kt:65](packages/android/app/src/main/java/com/yancotv/android/ui/shell/BrowseShell.kt:65) and its call site. Hardcoded Arabic/EN/UK/US tier-floating conflicts with provider order; the catalog in 20.2 replaces its intent properly. Existing `is_pinned` from `GroupPreferences` keeps user-driven floating intact. | 🟢 wire | No hardcoded language strings in `BrowseShell.kt`; pinned groups still float to top via `GroupPreferences.is_pinned` |
| MK.20.1.3 | Test: extend [ContentRepositoryTest.kt](packages/shared/src/androidUnitTest/kotlin/com/yancotv/shared/content/ContentRepositoryTest.kt) — insert 3 groups out of alphabetical order (`"Sports"`, `"AR Movies"`, `"News"`) with ascending `sort_order`, assert `groups()` returns insertion order not alphabetical. | 🟢 wire | One test pinning the contract |

**No schema change. No UI change. ~1–2 hours.** Lowest-risk slice; ships independently.

### Slice 20.2 — Prefix catalog + repository-side bucketing

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.20.2.1 | New file `packages/shared/src/commonMain/kotlin/com/yancotv/shared/content/PrefixCatalog.kt` — pure data, no I/O. Static map of `code → ResolvedLabel(displayName, kind)` where `kind ∈ {Language, Region}`. Seed: `ar→Arabic, en→English, fr→French, es→Spanish, de→German, tr→Turkish, ru→Russian, it→Italian, pt→Portuguese, nl→Dutch, pl→Polish, sv→Swedish, hi→Hindi, ur→Urdu, fa→Persian, ku→Kurdish, he→Hebrew` (languages); `us→USA, uk→UK / United Kingdom, ca→Canada, au→Australia, nz→New Zealand, sa→Saudi Arabia, ae→UAE, eg→Egypt, ma→Morocco, dz→Algeria, qa→Qatar, kw→Kuwait, lb→Lebanon, sy→Syria, iq→Iraq, jo→Jordan, ye→Yemen, ir→Iran, pk→Pakistan, in→India, tr→Türkiye` (regions). Conflicts (`ca`=Canada vs Catalan, `tr`=Türkiye vs Turkish): pick region by default — IPTV M3Us overwhelmingly use country codes. | 🔴 new | Catalog covers ≥30 entries; loaded from a single Kotlin file; trivial to extend |
| MK.20.2.2 | New `parsePrefix(groupName: String): ParsedGroup` extractor in same package. Handles real-world shapes: `"AR\| Foo"`, `"\|AR\| Foo"`, `"[AR] Foo"`, `"AR - Foo"`, `"AR: Foo"`, `"AR Foo"` (space-only, only when followed by capital), `"Arabic \| Foo"` (full-word, matches catalog display name case-insensitive). Returns `ParsedGroup(prefix: String?, resolved: ResolvedLabel?, remainder: String, originalName: String)`. Unmatched groups return `prefix=null`. | 🔴 new | Returns correct triple for ≥10 prefix shapes covered by tests |
| MK.20.2.3 | Test: new `PrefixCatalogTest.kt` in `commonTest` — table-driven; covers all delimiter shapes, full-word matches, code-collision cases (`CA` resolves to Canada not Catalan), and unmatched cases (`"Sports"` → `prefix=null`). | 🔴 new | Table covers ≥20 fixtures from real M3U samples |
| MK.20.2.4 | Repository extension: `ContentRepository.groupsHierarchical(type)` returns `List<CategoryNode>` where `CategoryNode = sealed { Leaf(groupName), Parent(label, kind, children: List<Leaf>, prefixCode) }`. Built by calling existing `groups(type)` (now provider-ordered from 20.1.1), running `parsePrefix` on each, bucketing matches under their resolved parent (parent ordered by `min(child sortOrder)` to preserve provider order at the parent level too), leaving unmatched as top-level `Leaf`s. | 🟡 glue | Returns valid tree; preserves provider order; single-child parents collapse to `Leaf` (avoid useless dropdown) |
| MK.20.2.5 | Test: `ContentRepositoryTest.kt` — fixtures with mixed prefixed + unprefixed groups, assert correct hierarchy + ordering + single-child collapse rule. | 🟡 glue | One test per rule |

**No schema change. ~2–3 hours.** Pure read-side, no UI yet — Slice 20.3 consumes the tree.

### Slice 20.3 — Settings toggle + collapsible rail UI

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.20.3.1 | New preference `AppPreferences.smartCategoryGrouping: Flow<Boolean>` (default **off** until catalog is field-tested). Settings screen row: "Smart category grouping" with subtitle "Bucket categories by language / region (e.g. AR \| → Arabic)". | 🟢 wire | Toggle persists; default off |
| MK.20.3.2 | `BrowseSection` and `GuideScreen` switch on the preference: off → existing flat `groups()` call (Slice 20.1 already gives provider order); on → call `groupsHierarchical()` and flatten visible nodes per current expand/collapse state. Expand state held in `rememberSaveable(parentCode) { mutableStateOf(false) }` per parent — survives rotation, not process death (per-screen scope). | 🟡 glue | Toggle on → rail shows `▶ Arabic (12)` and `▶ USA (8)` rows; toggle off → flat rail unchanged |
| MK.20.3.3 | `CategoryRail` row composable branches on `CategoryNode` type. Parents render as a single pill with `▶`/`▼` glyph + count badge. CENTER on a parent toggles expand. Children render indented (or with a leading `↳` glyph — pick one in design pass). Per the MK.8 cascade-nav rule: own a fresh `PlacedFocusAnchor` inside `key(expandedSet.hashCode())` so expand/collapse remounts the visible-list scope cleanly. Refresh `requestFocus()` lands on the previously-focused row's new index after expand or, if it was a now-hidden child, falls back to its parent. | 🔴 new | All three MK.8 cascade-nav flows pass on Fire TV (sidebar→rail RIGHT, rail→content RIGHT/CENTER, type swap remount) |
| MK.20.3.4 | `GroupPreferences` interaction rules: (a) leaf with `is_hidden=true` is skipped during tree build; (b) parent with all children hidden is omitted entirely (no empty dropdown); (c) `is_pinned` on a leaf floats it to root level (out of its parent bucket) — semantics: pinning is "promote to top" and parents are visual grouping, not membership; (d) pinning a parent is not yet supported (out of scope this slice — file [bugs.md MB-211](bugs.md) "pin a whole language bucket" follow-up if user asks). | 🟡 glue | Hide a child → not visible; hide all of Arabic's children → Arabic parent disappears; pin a child → moves to top of root list, retains pin glyph |
| MK.20.3.5 | Test: snapshot-style unit test on the visible-flatten helper — asserts that `(tree, expandedSet, hiddenSet, pinnedSet) → List<CategoryRow>` produces the expected ordering for ≥6 cases (all collapsed, one expanded, parent fully hidden, pinned-child-floats, single-child-collapse, mixed). | 🔴 new | Helper is pure / pinned by tests so refactors can't regress the rules |
| MK.20.3.6 | Manual Fire TV verification on a real provider M3U: enable toggle, confirm Arabic / English / USA / Canada parents materialize, expand/collapse navigates cleanly with D-pad, hidden + pinned interactions match 20.3.4. Capture before/after for the user. | — | Hands-on green check |

**Risky slice.** Focus model is the bulk of the cost. ~4–6 hours including the cascade-nav audit + manual Fire TV pass.

### Out of scope for MK.20 (file as MB-* / future MK if asked)

- In-app catalog editor ("rename AR to العربية"): defer to a later milestone. Static catalog file is enough for v1.
- Pinning a whole parent bucket (mentioned in 20.3.4): file as MB-* if user asks.
- Forced-alphabetical override: don't add until requested. Provider order + smart grouping are already two axes; a third makes the mental model muddy.
- `Search` rails: no hierarchy. FTS is type-rail driven; categories don't apply.
- Stalker portals: groups arrive via `category_id → category_title`; same `parsePrefix` approach works on the title string. Confirm during 20.2.4 against a Stalker fixture.

### Open questions before starting

1. **Default toggle state**: I propose **off** — ship the catalog, let user flip on, expand catalog over time, flip default later. (User-confirmable on slice 20.3 start.)
2. **Settings location**: New "Categories" section, or under existing "Display"? — recommend new section so future per-rail toggles have a home.
3. **Apply to Guide rail too?**: Recommended yes — same UX consistency rule the user has applied elsewhere. GuideScreen already uses `CategoryRail`, so 20.3.2 covers it for free.

### Cost (honest)

| Slice | Estimate | Risk |
|---|---|---|
| 20.1 | 1–2 h | Low — query change + delete + 1 test |
| 20.2 | 2–3 h | Low — pure data + parser + tests |
| 20.3 | 4–6 h | Medium — focus audit dominates; expect 1–2 hands-on Fire TV passes |
| **Total** | **7–11 h** | — |

Each slice is independently shippable; recommended order is 20.1 → 20.2 → 20.3.

---

## MK.21 — Settings redesign — shipped 2026-04-27 → 2026-04-28

**Concept A "Configure" layout** per Claude Design's Frosted Emerald spec. Promised in the 2026-04-27 active work queue, scoped and shipped across 14 commits — back-filled here so the catalog reflects what landed.

### Slices (all ✅ shipped)

| # | Task | Commit(s) | Notes |
|---|---|---|---|
| MK.21.1 | Settings shell — 380 dp hex-cut sidebar + content pane, breadcrumb, hex-nav rail of 12 tabs (Subtitles/Notifications/Storage placeholders dropped from sidebar; files stay in tree for post-v1) | `fd0dd2d` (escape semantics), `b89918e` (RIGHT-commits-tab + BACK), `ae6dc25` (drop placeholder tabs), `01690cb` (drop fake SYNCED footer) | `SettingsScreen.kt` |
| MK.21.2 | Shared primitives: `SettingsSection`, `SettingsRow`, `SettingsSlider`, `SettingsKicker`, `SettingsSelect`, `SettingsChipRow`, `SettingsToggleRow`, `SettingsClickToEditField`, `SettingsAccentButton` (with translucent variant), `SettingsOutlinedButton`, `SettingsDangerButton`, `SettingsInlineSwitch`, `SettingsChip` | `4828b33` and earlier | `SettingsPrimitives.kt`, `SettingsButton.kt`, `SettingsTextField.kt`, `SettingsToggleRow.kt` |
| MK.21.3 | Per-tab redesigns onto the primitives — General, Appearance, Playback, Network, EPG, Backup, About, Shortcuts, Parental, Recordings, Groups | `4828b33`, `bf6bcf7`, `87fc40d`, several earlier | EPG tab dropped the embedded `GuideSyncPanel` card and inlined diagnostics + override URL as native `SettingsSection` rows (`87fc40d`) |
| MK.21.4 | Sources list redesign + `SourceDetailScreen` — row-as-card opens detail pane; per-source URL/credentials/EPG/UA/Referer editor; SAVE/SYNC/DELETE compact buttons; `PlacedFocusAnchor` on BACK button | several | Detail pane replaces the list view via `selectedSourceId` `rememberSaveable`; row click opens, BACK clears |
| MK.21.5 | **Per-source "auto-sync on app start" toggle** + backing schema (`9.sqm` v8 → v9 added `auto_sync_on_start INTEGER NOT NULL DEFAULT 0`) + threading through `Source` / `SourceRepository` / `BackupFileV1.SourceRecord` / backup exporter + importer | `f157250` | MainActivity reads sources where the flag is set on `onCreate`, kicks `SourceSyncCoordinator.start` for each (sequential — coordinator gates concurrent syncs). **Caveat:** the schema-add broke 10 `:shared:androidUnitTest` files until 2026-04-28 (MB-223). |
| MK.21.6 | Unified Settings escape semantics — LEFT and BACK both mean "escape one level up" (tab content → active tab in sidebar; tab sidebar → main app sidebar); per-row `leftExitsTo()` boundaries via `LocalActiveSettingsTabFocus` so chip-row LEFT navigation stays in-row but the moment LEFT crosses the row boundary it lands on the active tab | `fd0dd2d`, `b89918e` | Replaces a buggy `moveFocus(Left)` that returned false silently when there was no spatial neighbour |
| MK.21.7 | Symmetric hex breadcrumb chips (`YancoShapes.HexCapsuleSoft`) replacing the asymmetric `ChipBevel`; logo + "Settings" wordmark via `Arrangement.SpaceBetween` (logo right, title left) | several | 64 dp logo height calibrated for 3 m readability on Fire TV |
| MK.21.8 | **Scroll-bottom safety margin** — `BringIntoViewSpec` was bringing the focused row's trailing edge to *exactly* `containerSize`, pinning the focused last row to the panel border. The per-tab `bottom = 80.dp` lived inside the scroll content but BringIntoView only positions the focused element; padding never scrolled into view. | `87fc40d` | Replaced with `rememberSafeMarginBringIntoViewSpec()` (density-aware, leaves 32 dp gap above/below focused row). All 12 tabs now use `padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 80.dp)` consistently. |

### Out of scope (post-v1 / future MK)

- Subtitles / Notifications / Storage tabs — placeholder bodies remain in tree but are not wired into the sidebar. Re-enable when underlying features ship (e.g. notifications channel post-MK.10, storage manager when recording UX matures).
- Two-Fire-TV verification of the auto-sync-on-start flow — same blocker as MK.19.8.7 (need second device).

---

## MK.22 — Motion polish — investigated 2026-04-28

**Why now.** User-reported lag, headlined by the main app sidebar (HomeScreen) feeling slow on focus — when collapsed and the user navigates back to it, expansion doesn't fire immediately. Motion auditor (general-purpose subagent, run 2026-04-28) traced the headline complaint and surveyed the rest of the motion surface.

### Investigation findings (motion auditor 2026-04-28)

The audit produced a prioritized punch list of 16 items across P0/P1/P2. The one root cause behind the user's complaint is **MB-221** — a 120 ms hard delay before the sidebar focus flip + a spring tail on the width animation + 9 simultaneous per-row label animations. Total perceived: ~370 ms gap before the sidebar feels open, of which ~250 ms is fixable with three small edits.

The audit also caught a real correctness bug: **MB-222** — `OnNowTile.nowSec` is captured once at first composition and never updates, so live-programme progress bars never advance.

The remaining items group into Sprint B (polish):
- Settings tab focus retry ladder (`SettingsScreen.kt:184-202`) — 3-frame `withFrameNanos` chain + 2 retries on every tab click; cheap tabs (Appearance, About, Shortcuts, Backup) don't need both retries.
- `HexSurface` runs 5 parallel springs per focusable card — collapse to 2 (one shared scale/translate/elevation, hard-switch border).
- `SettingsScreen.TabItem` shadow elevation pops 0 → 18 dp instantly while the scale tweens smoothly — visual mismatch.
- `HomeContent.kt` hero cross-fade at 420 ms fadeIn / 280 ms fadeOut feels heavy because both layers paint full-bleed AsyncImage + 3 gradients + text simultaneously.
- `CategoryRail` pill `LaunchedEffect(focused)` triggers `onSelect(group)` on every focus tick — D-pad arrow-spam churns the StateFlow + DB query.
- `HomeContent.kt:715, 747, 779` `remember(index) { Modifier.wheelItemTransform(...) }` defeats the purpose of `remember` (closes over `listState`) — code-hygiene fix.

Full audit report archived in commit log (parent of `acc86e9`); top items reproduced inline below.

### Slice 22.A — Felt-lag fix (Sprint A) — closes MB-221 + MB-222

Highest-leverage, smallest-effort. The single biggest user-visible win is fix #1 — that alone removes 120 ms of dead air. Together with #2 + #3 the sidebar should feel ~250 ms faster.

| # | Task | File:line | Status | DoD |
|---|---|---|---|---|
| 22.A.1 | **Drop the `delay(120)` preamble** in `LaunchedEffect(section)` that gates `panelFocus` flip + `mainContentFocus.requestFocus()`. Original "wait for old composable to leave" justification is obsolete now that section content uses `key()`. If a beat is still needed, replace with a single `withFrameNanos { }`. | [HomeScreen.kt:184-199](packages/android/app/src/main/java/com/yancotv/android/ui/shell/HomeScreen.kt) | 🔴 new | Sidebar starts visibly expanding within one frame of focus arrival; no perceptible "not responding" gap. |
| 22.A.2 | **Replace `animateDpAsState(spring(0.85f, 320f))` with `tween(180, FastOutSlowIn)`** on the sidebar width. Width-only animation; no need for spring physics or its overshoot tail. | [AppSidebar.kt:100-105](packages/android/app/src/main/java/com/yancotv/android/ui/shell/AppSidebar.kt) | 🔴 new | Sidebar opens in 180 ms with no overshoot. Visually settles cleanly at 260 dp. |
| 22.A.3 | **Drop the per-row `AnimatedVisibility(expandHorizontally / shrinkHorizontally)`** on every row label — that's ~9 simultaneous layout-shifting animations contending with the parent width animation. Replace with static `if (showLabel) Text(...)` plus a single shared alpha driven from the parent width animation progress. | [AppSidebar.kt:415-419](packages/android/app/src/main/java/com/yancotv/android/ui/shell/AppSidebar.kt) | 🔴 new | Sidebar expand renders one width animation + one shared alpha curve, not 10 concurrent layout passes. |
| 22.A.4 | **Hoist the accent-bar animation** to one instance keyed off `current` only; **switch row foreground from `animateColorAsState` to a plain ternary** — focus colour change at 10 ft doesn't need interpolation. | [AppSidebar.kt:325-338](packages/android/app/src/main/java/com/yancotv/android/ui/shell/AppSidebar.kt) | 🔴 new | One accent-bar animation instance; row foreground colors swap instantly on focus change. |
| 22.A.5 | **Fix `OnNowTile` frozen clock** (MB-222). Lift `nowSec = remember { System.currentTimeMillis() / 1000 }` out of the per-tile composable; add a single `LaunchedEffect` ticking every 30 s in `HomeContent` and pass down as parameter. Mirrors `GuideScreen.kt:330-335`'s shape. | [HomeContent.kt:897](packages/android/app/src/main/java/com/yancotv/android/ui/shell/HomeContent.kt:897) | 🔴 new | Programme progress bars on Home advance over time. Snapshot at t=0 vs t=30s shows different progress percentage. |

**Hands-on verification gate (Fire TV AFTDCT31):** open Home → press LEFT to land on sidebar collapsed → arrow up/down to a different section → press CENTER. Sidebar expands within ~200 ms (was ~370 ms). Repeat 3× to feel the consistency. Wait 60 s on Home with at least one favorited live channel: an On Now tile's progress bar must visibly advance.

### Slice 22.B — Polish (Sprint B)

Lower-leverage but real. Each item is independently shippable.

| # | Task | File:line | Status | DoD |
|---|---|---|---|---|
| 22.B.1 | **Skip the Settings tab focus-retry ladder for cheap tabs.** Today every tab click runs `withFrameNanos { } × 2` then `moveFocus(Right)`, then a one-frame retry, then a second `moveFocus(Right)`. Cheap tabs (Appearance, About, Shortcuts, Backup, Recordings, Parental) don't need it. Alternative: bind a `FocusRequester` to the first focusable in each tab body and `requestFocus()` directly. | [SettingsScreen.kt:184-202](packages/android/app/src/main/java/com/yancotv/android/ui/settings/SettingsScreen.kt) | 🔴 new | Settings tab swap latency drops to ~16 ms for cheap tabs (was ~50 ms minimum). |
| 22.B.2 | **Collapse `HexSurface`'s 5 parallel springs into 2.** Today every focusable card runs scale + translate + elevation + shellBorder springs plus inner-fill colour flip. Collapse to one `animateFloatAsState` driving scale + translate + elevation via a derived value; keep border as a hard switch. | [HexSurface.kt:79-99](packages/android/app/src/main/java/com/yancotv/android/ui/components/HexSurface.kt) | 🔴 new | Focus traversal across a rail of HexSurface tiles drops from 5 springs/tile to 2. |
| 22.B.3 | **Tween the Settings tab shadow elevation** so the 0 → 18 dp transition matches the smooth scale tween. Or drop to a static low value (8 dp selected, 0 unfocused). Current pop-in reads as "row scales smoothly, halo flashes". | [SettingsScreen.kt:433-438](packages/android/app/src/main/java/com/yancotv/android/ui/settings/SettingsScreen.kt) | 🔴 new | Tab focus animation has consistent timing across scale + shadow. |
| 22.B.4 | **Cut hero cross-fade durations** in `HomeContent`'s `AnimatedContent` between hero slides — `tween(420)` fadeIn + `tween(280)` fadeOut → `tween(240)` / `tween(200)`. Or switch to `crossfade` (single shared opacity). | [HomeContent.kt:451-457](packages/android/app/src/main/java/com/yancotv/android/ui/shell/HomeContent.kt) | 🔴 new | Hero swap feels snappier without losing the cross-fade. |
| 22.B.5 | **Debounce `CategoryRail` pill `LaunchedEffect(focused) { onFocused() }`** by 100 ms inside `BrowseSection.onSelect` so D-pad arrow-spam scrolling pills doesn't churn the StateFlow + DB query for every pill the focus passes through. | [CategoryRail.kt:343, 366-374](packages/android/app/src/main/java/com/yancotv/android/ui/shell/CategoryRail.kt) | 🔴 new | Rapid arrow-key traversal across 10 pills triggers 1 commit at the end, not 10 mid-traversal. |
| 22.B.6 | **Remove the `remember(index)` wrapper on `Modifier.wheelItemTransform(...)`** in 3 rails — the modifier returns a stable lambda-driven `graphicsLayer`; the `remember` adds nothing and closes over `listState` confusingly. Code-hygiene only. | [HomeContent.kt:715, 747, 779](packages/android/app/src/main/java/com/yancotv/android/ui/shell/HomeContent.kt) | 🔴 new | Three line removals; no behaviour change. |
| 22.B.7 | **Tighten hero backdrop debounce** from 300 ms to 180 ms in `FeatureHero` — the AUTO_PREVIEW_DEBOUNCE_MS=400 ms already gates audio; the image swap can be quicker. | [FeatureHero.kt:155-159](packages/android/app/src/main/java/com/yancotv/android/ui/shell/FeatureHero.kt) | 🔴 new | Hero image refreshes ~120 ms faster as user moves through the rail. |

### Out of scope for MK.22 (file as MB-* / future MK if asked)

- **HomeContent outer `verticalScroll(Column)` → `LazyColumn`** (motion audit P1 #7). Rails currently re-evaluate `layoutInfo` on every vertical scroll because the outer `verticalScroll` keeps every rail in composition. Switching to `LazyColumn` lets off-screen rails fully unmount but is a non-trivial refactor (focus model, scroll restoration, hero pinning). Ship MK.22 felt-lag and polish first; re-evaluate after.
- Per-tile `wheelItemTransform` micro-tuning — current pattern is documented as correct, just heavy on Fire TV class hardware.
- TalkBack / a11y motion-reduction respect — Stage 5.3 covers this.

### Cost (honest)

| Slice | Estimate | Risk |
|---|---|---|
| 22.A (Sprint A) | 30–60 min | Low — 5 small edits, all reverts of existing animation overhead. Hands-on Fire TV verification dominates. |
| 22.B (Sprint B) | 1.5–3 h | Low-medium — 7 edits, each independently shippable. Re-test Settings tab swap on Fire TV after 22.B.1. |
| **Total** | **2–4 h** | — |

Sprint A is one bundled commit (the 5 fixes hit different files but solve one user complaint). Sprint B is 7 small commits.

---

## MK.23 — Test hardening — investigated 2026-04-28

**Why now.** MB-220 was a Critical-class silent-data-loss bug that lived in the codebase for months without being caught. Fix landed 2026-04-28 with two new tests, but the audit asked the obvious question: what other latent bugs of the same family is the test suite NOT catching?

### Investigation findings (test auditor 2026-04-28)

**Inventory:** 37 test files / 428 `@Test` methods on `:shared` (`packages/shared`); 8 test files on Android app proper (`packages/android/app/src/test/`); zero on `androidTest/`. Strong coverage on parsers / classifiers / URL builders / recording sub-units. **Thin coverage on the Android-app side** (PlaybackController, sync orchestration, lifecycle wiring) and on the cascading-FK family that produced MB-220.

The audit produced a prioritized list of 25 missing-coverage gaps:

- **5 Critical** (silent data-loss / corruption class — same family as MB-220)
- **7 High** (user-visible breakage)
- **9 Medium** (edge cases)
- **4 Low** (polish / correctness drift)

Top three highest-leverage to ship first:
1. `PlaybackController.persistResumePoint` — gates resume across the whole app, **zero tests today**.
2. `BulkContentWriter.abortSource` cross-source FK survival — direct MB-220 sibling.
3. `FavoritesRepository` multi-list (Stage 2.2 / MK.13.4 surface) — entirely unguarded.

Full audit report archived in commit log (parent of `acc86e9`).

### Slice 23.C — Critical tests (Sprint C)

Three commits, each independently bisectable.

| # | Task | Surface | Status | DoD |
|---|---|---|---|---|
| 23.C.1 | **`PlaybackController.persistResumePoint` regression suite.** Fake `WatchHistoryRepository` capturing `upsert` calls; drive the controller through the full transition matrix and assert the contract: episode flow writes (seriesId, episodeId), movie writes itemId, LIVE flow writes nothing, `_rec_` prefix writes nothing, `< 5L` minimum guard holds. Also covers `applyExternalSubtitle` resume-after-subtitle-load: capture position → swap → seek to captured offset. | [PlaybackController.kt:815](packages/android/app/src/main/java/com/yancotv/android/player/PlaybackController.kt) | 🔴 new | At least 6 test methods covering: episode persist, movie persist, LIVE skip, `_rec_` skip, `< 5L` guard, subtitle-swap resume. New file `:app/src/test/.../PlaybackControllerPersistResumePointTest.kt`. |
| 23.C.2 | **`BulkContentWriter.abortSource` cross-source FK survival.** Seed source A (with favorite + history) + source B; force `writeM3uChunk` to throw on B mid-chunk; assert source A's favorites + history intact, FK back ON afterwards (write a content row, delete it, observe cascade fires). Same family as MB-220 — guards against future refactors that might leave FK off across sources. | [BulkContentWriter.kt:196](packages/shared/src/commonMain/kotlin/com/yancotv/shared/sources/BulkContentWriter.kt) | 🔴 new | Test in `BulkContentWriterTest.kt`. After abort: A's data intact, `PRAGMA foreign_keys` reads 1, cascade fires on a fresh `DELETE FROM content WHERE id = ?`. |
| 23.C.3 | **`FavoritesRepository` multi-list (MK.13.4) surface.** Zero tests today. Cover: `createList` returns a stable id and trims whitespace; `addToList` is idempotent on collision; `removeFromList` is list-scoped (doesn't touch other lists); `deleteList("default")` is a silent no-op (the `WHERE is_default = 0` guard); `deleteList(custom)` cascades to its members; `setListSortOrder` updates `updated_at`; `byListFlow` reactivity (`turbine`-style — collect, write from another coroutine, assert second emission). | [FavoritesRepository.kt:154-223](packages/shared/src/commonMain/kotlin/com/yancotv/shared/favorites/FavoritesRepository.kt) | 🔴 new | At least 7 tests in `FavoritesRepositoryTest.kt`. The `deleteList("default") is no-op` test is the load-bearing one — guards against schema changes that might drop the guard. |

### Slice 23.D — High + Medium tests (Sprint D)

Each row a single commit. Order doesn't matter strongly; pick by which surface you next touch.

| # | Task | Surface | Status | DoD |
|---|---|---|---|---|
| 23.D.1 | **`BulkContentWriter.finishSource` failure path.** Inject a driver wrapper that throws on `INSERT INTO content_fts SELECT…`; assert the catch block re-creates the trigger + re-enables FK + favorites for live content unchanged + `PRAGMA foreign_keys = 1` afterwards. | `BulkContentWriter.kt:148` | 🔴 new | One test asserting all four post-conditions on the catch path. |
| 23.D.2 | **`SourceSyncCoordinator.start()` re-entrancy.** Pure unit on the coordinator with a fake repo whose `syncSource` flow stays open; second `start()` is a no-op + only one `repo.syncSource` invocation observed. Currently rejected by `if (_state.value != null)` but unpinned. | `SourceSyncCoordinator.kt` | 🔴 new | Test with two rapid `start()` calls; assert second returns early, repo invoked once. |
| 23.D.3 | **`SourceRepository.syncSource` cancellation mid-flight.** Start syncSource, collect a few progress emits, cancel scope; assert `bulk.abortSource()` ran, `PRAGMA foreign_keys` is back ON, no partial content rows for that source, favorites for OTHER sources untouched. | `SourceRepository.kt` | 🔴 new | One integration test in `SourceRepositoryTest.kt`. |
| 23.D.4 | **`WatchHistoryRepository.recent` ignores stray episode rows.** Insert an episode row with `content_id` pointing at a non-existent series; call `recent()`; assert empty list returned without exception. (Pre-MB-220 this scenario could happen post-CASCADE; post-fix it shouldn't, but defending the join is cheap.) | `WatchHistoryRepository.kt` | 🔴 new | One test in `WatchHistoryRepositoryTest.kt`. |
| 23.D.5 | **EPG re-sync vs reminders FK.** Insert reminder pointing at programme P; run an EPG full re-write that includes P with the same id; assert reminder still pointing at P (or at minimum reminder row survives — the schema has `ON DELETE SET NULL` so the worst case is the FK going null, not the row vanishing). | `BulkEpgWriterTest.kt` | 🔴 new | One test with a seeded reminder + a programme that survives the rewrite. |
| 23.D.6 | **Schema migration v8 → v9 dedicated test.** Today `Stage2MigrationTest` runs v3 → current as one bundle; v9's `auto_sync_on_start` ride-alongs aren't pinned. Seed v8 fixture with rows lacking the column; migrate to v9; assert column exists with default 0; query `WHERE auto_sync_on_start = 1` returns 0 rows; insert a row with `auto_sync_on_start = 1`; query returns it. | `MigrationTest.kt` (or new file) | 🔴 new | One test class focused on the v8→v9 hop. |
| 23.D.7 | **`RecordingScheduleRepository` `schedule.recording_id` FK SET NULL.** Insert recording R, schedule S referencing R, delete R via `recordingsQueries.deleteById`, assert `schedules.selectById(S).recording_id == null`. Pins MB-211's deferred dead-FK contract — the column is currently dead but the FK is latent. | `RecordingScheduleRepositoryTest.kt` | 🔴 new | One test pinning the SET NULL behaviour. |

### Out of scope for MK.23 (file as future MK if pursued)

- **Compose-test cascade-nav smoke** (sidebar→rail RIGHT, rail→content RIGHT, type swap remount) — `.claude/skills/native-android-mk/SKILL.md` documents this as a manual test. Could be `composeTestRule`-automated but adds Robolectric / `androidTest` infra weight. Defer until the focus model touches a refactor.
- **DB driver corruption-recovery integration test** (truncate WAL mid-write, assert `DatabaseFactory.create()` cleans + recovers from `SourcesBackup`). High value but needs a Robolectric harness; defer.
- **`XmltvParser` malformed-input fixtures** (timezone variants, CDATA, broken `<programme>` tags) — Medium-priority polish; file as separate work.
- **Catchup URL building DST boundary tests** — Medium; file as separate.
- **`ContentIds.m3u` FNV-1a 32-bit collision stress** — Low; file as separate.
- **`redactCredentials` end-to-end on `last_sync_error` write path** — Medium; one targeted test, but the redaction primitive is well-tested already.

### Cost (honest)

| Slice | Estimate | Risk |
|---|---|---|
| 23.C.1 (PlaybackController) | 1.5–2 h | Medium — needs a fake `WatchHistoryRepository` + a way to drive the controller without ExoPlayer. The controller is main-thread-only so test harness must dispatch on Main. |
| 23.C.2 (abortSource FK) | 30–45 min | Low — additive to existing `BulkContentWriterTest`. |
| 23.C.3 (multi-list) | 1–1.5 h | Low — additive to existing `FavoritesRepositoryTest`. Turbine usage if reactive cases included. |
| 23.D (7 tasks) | 4–6 h | Low — each is a single test; expect 30–45 min per. |
| **Total** | **7–10 h** | — |

Sprint C should ship as 3 separate commits; Sprint D as 7 small commits or 2-3 themed bundles depending on cadence.

---

## MK.19.8 — Backup / restore (full app state) — investigated 2026-04-27

**Why now.** First entry in the user-set active work queue (set 2026-04-27). Gates two real scenarios: (a) buying a second Fire TV and not having to re-enter every source / favorite / recording schedule by hand, (b) factory-resetting or replacing the canonical Fire TV and not losing months of curation.

### Investigation findings — what the codebase already gives us

- **Schema is at v8.** `BackupMetadata.sq` (Stage 2.5, `7.sqm`) already ships an empty user-export tracker: `id, file_uri, label, schema_version, checksum (SHA-256 hex), size_bytes, record_counts (JSON map), notes, created_at`. **No schema migration needed for MK.19.8.**
- **A non-user-facing backup pattern exists.** [SourcesBackup.kt](packages/shared/src/androidMain/kotlin/com/yancotv/shared/db/SourcesBackup.kt) silently dumps `<filesDir>/sources-backup.json` after every successful DB open as the corruption-recovery safety net. Format + version-guard + insert pattern are reusable but the credential-handling story is wrong for portable backup (see below).
- **Desktop has a full backup service we can mirror.** [src/main/services/backup-service.ts](src/main/services/backup-service.ts) ships v1 JSON with sources/favorites/history/settings/parental/group-prefs and a `(sourceId, streamUrl, title, tvgId)` re-link pattern for content-id-bearing rows. Mirror its schema so a future desktop ↔ Android transfer is plausible without protocol negotiation.
- **No user-facing export/import UI exists yet** — greenfield on the Android side for the SAF flow + restore semantics.
- **SAF picker pattern is established** in [RecordingStorageResolver.kt](packages/android/app/src/main/java/com/yancotv/android/recording/RecordingStorageResolver.kt) (custom-folder picker for recordings — `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`). Reuse the same shape for backup-folder selection.

### Backup data inventory

**Back up** (user-curated state):
- `sources` — credentials need special handling, see below
- `favorites` + `favorite_lists` — re-link by `(sourceId, streamUrl, title, tvgId)`
- `watch_history` — re-link the same way
- `recording_schedules` — re-link by `(content_id, programme_id, title, stream_url)` AND check programme_id still resolves on import
- `recordings` — file_path URIs exported; on import validate `DocumentFile.exists()` before creating row, drop orphans
- `content.name_override`, `content.logo_override` (MK.13.2 user renames + logo overrides) — exported as `(sourceId, streamUrl, name_override, logo_override)` tuples, applied post-resync
- `channel_overrides` (custom_number, custom_group from Parental.sq) — same re-link strategy
- `locked_channels`, `hidden_channels` (parental — by content_id, re-link via stream_url)
- `group_preferences` — by (content_type, group_key); group_key is the raw `group_name` string from the M3U so it's directly portable
- `settings` table — entire key-value dump (~20 keys: theme, accent, font scale, smart grouping, audio_lang, etc.)
- `reminders` — optional v1; user-created EPG reminders
- `downloads` — **skip** v1 (ephemeral; user can re-queue)

**Skip** (cache / re-fetched on source sync):
- `content` (rows themselves — only the override columns are kept)
- `epg_programmes`, `episodes`, `subtitle_cache`, `tmdb_cache`

**Skip** (system / Keystore — unrecoverable across devices):
- PIN hash (lives in Android Keystore via [AndroidPinHasher.kt](packages/shared/src/androidMain/kotlin/com/yancotv/shared/parental/AndroidPinHasher.kt) — NOT in DB).
- Recording files themselves (the bytes on disk). Files in `MediaStore.Video` survive uninstall on Android 11+; SAF custom folders survive too. App-private dir is wiped. The `recordings` row tracks the file path; on a NEW device, neither survive — restore drops orphans.

### The credential portability problem (single biggest design call)

[AndroidKeystoreCredentialStore.kt](packages/shared/src/androidMain/kotlin/com/yancotv/shared/sources/AndroidKeystoreCredentialStore.kt) encrypts `username_encrypted` / `password_encrypted` / `mac_address_encrypted` with an **AES/GCM key generated on-device, never exported, sometimes living in the StrongBox TEE**. Result: ciphertexts in the `sources` table are useless on any other device.

Three options, with honest trade-offs:

| Option | Export cost | Restore UX | Security | Recommendation |
|---|---|---|---|---|
| **A. Decrypt-then-export plaintext** (matches desktop's safeStorage flow) | Trivial — call `decrypt()` per source, write strings into JSON | Works on any device; user double-clicks restore and is done | Backup file on disk has plaintext IPTV creds — Downloads / Drive / email leakage risk | Default for "local / personal" backup, with a giant warning row in the dialog |
| **B. Re-encrypt with user-supplied password** (PBKDF2 → AES-GCM, mirror PIN hasher's primitives) | ~30 lines: PBKDF2 the password, encrypt creds, write ciphertext + salt + iter count to JSON | User must remember the backup password; if forgotten the file is unrecoverable | Strong; the file is portable AND safe to leave on a USB stick | Default for "transfer between devices"; offer alongside Option A |
| **C. Strip credentials, force re-auth on restore** | Smallest export | Worst — user re-enters every source from scratch | Strong (no creds in file) | Reject as default; surface only as a fallback when Keystore decrypt fails |

**Recommended UX:** the export dialog has two paths — "Quick backup (no password, contains credentials)" and "Encrypted backup (password protected)". Both run the same body; only the credential-encryption step differs. Restore auto-detects the file shape (presence of `encryption.kdf` field) and prompts for password when needed. Failed decryption → fall back to Option C and warn the user.

### File format (v1 — mirror desktop)

```json
{
  "schemaVersion": 1,
  "appVersion": "0.1.0-mk0",
  "dbSchemaVersion": 8,
  "createdAt": "2026-04-27T19:42:00Z",
  "encryption": {
    "kdf": "pbkdf2-sha256",
    "iterations": 100000,
    "salt": "<hex>"
  } | null,
  "records": {
    "sources": [...],
    "favoriteLists": [...],
    "favorites": [{ "sourceId", "streamUrl", "title", "tvgId", "listId", "addedAt" }],
    "watchHistory": [{ "sourceId", "streamUrl", "title", "tvgId", "episodeStreamUrl"?, "positionSeconds", "durationSeconds", "watchedAt" }],
    "recordingSchedules": [...],
    "recordings": [{ "fileUri", "title", "format", "startedAt", ... }],
    "contentOverrides": [{ "sourceId", "streamUrl", "nameOverride", "logoOverride" }],
    "channelOverrides": [{ "sourceId", "streamUrl", "customNumber", "customGroup" }],
    "lockedChannels": [{ "sourceId", "streamUrl" }],
    "hiddenChannels": [{ "sourceId", "streamUrl" }],
    "groupPreferences": [{ "contentType", "groupKey", "sortOrder", "isHidden", "isPinned", "customName" }],
    "settings": [{ "key", "value" }],
    "reminders": [...]
  },
  "recordCounts": { "sources": N, ... },
  "checksum": "<sha256-of-records-canonical-json>"
}
```

`schemaVersion` (the backup format) is independent of `dbSchemaVersion` (the SQLDelight version). Restore guards on both: refuse if `dbSchemaVersion > YancoDb.Schema.version`; warn if `<` and rely on auto-migration after row insert.

### Restore semantics

Two modes (mirror desktop):
- **Merge** (default) — upsert each row by primary key. New favorites add to existing; existing rows updated.
- **Replace** — wipe the relevant tables first, then bulk-insert. Sources are NEVER auto-wiped (user could lose access to streams during the operation if the source-id-keyed re-link fails). Replace mode wipes favorites/history/schedules/overrides/parental; sources upsert by id.

Re-link behavior:
- **Sources are restored first.** Their stable `id` is preserved (UUIDs).
- All content-keyed records (favorites, history, schedules, overrides) have the foreign content_id stripped from the JSON; on import, after sources are restored, the import loop re-resolves content_id by `(source_id, stream_url)` lookup against the local `content` table.
- If the matching `content` row doesn't exist yet (source hasn't been re-synced), the import buffers the record and re-tries after the next source-sync completion. Surface unresolved-after-sync rows in a "couldn't link" report screen.

### Slices

| # | Task | Bucket | DoD |
|---|---|---|---|
| MK.19.8.1 | ✅ **`BackupExporter` in `packages/shared/`** — pure (no Android types). Takes `YancoDb` + `CredentialStore` + optional password; returns a `BackupFileV1` data class. Skips schema-only / cache tables. Computes record counts. SHA-256 checksum over canonical JSON of `records`. Shipped `92877e4`. | 🔴 new | Round-trips against fixture DB; checksum stable across runs |
| MK.19.8.2 | ✅ **`BackupImporter`** in same package — consumes `BackupFileV1`, applies merge or replace mode. Re-link pass for content-id-keyed records. Returns `RestoreReport(restored, skipped, unlinked)`. Schema-version guard. Shipped `a65d205`. | 🔴 new | Fixture round-trip is lossless; lower-version fixture migrates correctly via SQLDelight; higher-version fixture rejected |
| MK.19.8.3 | ✅ **Settings → Backup section** Compose UI in [packages/android/](packages/android/). Two row groups: "Export" (label input, password optional, SAF Save dialog) and "Import" (SAF Open dialog, mode picker, password if encrypted, dry-run preview before commit). Toast / dialog on completion with restore report counts. Shipped `e5e8df7` + UX follow-ups `b37ef3b` / `455792c` / `4fdfe0c` (focus / picker / default-folder) + 2026-04-27 close-out trio (multi-frame focus retry; Download/YancoTV picker fallback; tappable recent backups + keep buttons focusable while busy). | 🔴 new | TalkBack labels per native-android-mk; D-pad path tested on Fire TV |
| MK.19.8.4 | ✅ **Re-link buffer wired to source-sync completion** — `BackupImporter` exposes `pendingLinks: Flow<Unlinked>` consumed by a `SourceSyncObserver` that fires on every source sync completion to retry resolution. After 3 sync passes with no progress, surface "couldn't link" report. Shipped `e5e8df7`. | 🟡 glue | Restore on a fresh install where sources haven't synced yet completes; favorites materialise after the first source sync |
| MK.19.8.5 | ✅ **`BackupMetadata` rows persisted on every export.** UI shows last-3 backups in Settings → Backup with "Open in file manager" + "Delete from device" rows. Shipped `e5e8df7`; recent rows became tappable restore-source shortcuts in the 2026-04-27 close-out. | 🟢 wire | Row written; UI lists; matches BackupMetadata.sq schema (Stage 2.5) |
| MK.19.8.6 | ✅ **Tests** — consolidated into a single [BackupRoundTripTest.kt](packages/shared/src/androidUnitTest/kotlin/com/yancotv/shared/backup/BackupRoundTripTest.kt) (12 cases) plus [BackupExporterSmokeTest.kt](packages/shared/src/androidUnitTest/kotlin/com/yancotv/shared/backup/BackupExporterSmokeTest.kt). All four scenario buckets covered: round-trip (plaintext + password), schema guard (newer-than-current rejected), checksum guard (tampered records rejected), credential modes A/B/C (plaintext / password / wrong-password-decrypt-fallback-with-warning), re-link buffer (favorites + content overrides + channel overrides + parental locked/hidden + recording content_id). Single-file consolidation chosen over four separate files since scenarios share the same `seedSource`/`seedContent` fixture pattern. | 🔴 new | All four green; round-trip sets compared by record-count + checksum |
| MK.19.8.7 | ⏸ **Manual two-Fire-TV verification.** Export from canonical AFTDCT31, install fresh on a second device, import (option A first then option B), confirm sources/favorites/history/recordings re-link cleanly after source resync. **User-driven** — schedule once second device is available. Why a second device specifically: cross-device Keystore-key boundary (per-device AES/GCM keys; Option B credentials only meaningfully exercise against a fresh Keystore), real cold cache for the 19.8.4 re-link buffer, recording content:// URI orphan path. Same-device clear-data masks all three. | — | Hands-on green |

### Out of scope for MK.19.8 (file as MB-* / future MK if asked)

- **Cloud sync** (Google Drive / Dropbox auto-export). Defer to post-v1; would need OAuth + privacy policy + GDPR considerations.
- **Selective export** ("just my favorites"). Full-app only in v1; partial exports add UI surface for marginal value.
- **Cross-platform desktop ↔ Android transfer** verified end-to-end. The schema mirrors desktop, but actually testing both sides round-tripping is its own session.
- **Restoring recording files themselves** (just the metadata). Files survive only if MediaStore-stored on Android 11+ (system handles persistence) or in a SAF custom folder the user chose.
- **PIN restore.** Skipped by design — Keystore won't release the hash. User sets a new PIN on the new device.

### Open questions before slice MK.19.8.1 starts

1. **Default credential-handling mode**: Option A (plaintext) or Option B (password)? My pick: **A as default with a clear warning, B available via toggle.** Most users back up to a personal Drive folder or USB stick where Option A is fine; B is for the security-conscious / shared-cloud case.
2. **Replace mode**: include or skip in v1? My pick: **skip — merge only.** Replace adds destructive-action UX (confirm dialog, undo path); merge covers the realistic restore flow ("new device, want my stuff back"). Add later if users complain.
3. **Re-link retries**: how many sync passes before we give up? My pick: **3, then surface to user.** Most catalogs sync in <30s; 3 passes is generous.
4. **Where does Backup live in Settings?** Suggest **its own top-level "Backup" tab** (sibling to General / Playback / Network / Recording). Settings redesign (queue item 3) will revisit; for v1 backup ships with a Backup tab.

### Cost (honest)

| Slice | Estimate | Risk |
|---|---|---|
| 19.8.1 | 3–4 h | Medium — JSON schema design + canonical serialization for stable checksums |
| 19.8.2 | 4–6 h | High — re-link buffer is the tricky part; schema-version guards need tests |
| 19.8.3 | 3–4 h | Medium — SAF flows + dialogs + TalkBack |
| 19.8.4 | 2–3 h | Medium — coupling to source-sync completion observer |
| 19.8.5 | 1 h | Low |
| 19.8.6 | 2–3 h | Low — fixtures + assertions |
| 19.8.7 | — | hands-on, deferred until second Fire TV available |
| **Total** | **15–21 h** | Highest-cost milestone since MK.14. Build incrementally; ship 19.8.1+2+6 first (no UI), then 19.8.3+4+5 (UI + lifecycle wiring) |

Recommended order: 19.8.1 → 19.8.2 → 19.8.6 (test the engine before building UI) → 19.8.3 → 19.8.5 → 19.8.4 → 19.8.7.

---

## Red-team summary (MK.12 → MK.18)

What survived and what got cut. Full reasoning captured in the 2026-04-24 planning session; updated 2026-04-25 with no-timeline + permanent-drop decisions.

**Already cut in the plan above (2026-04-24 + 2026-04-25):**
- Audio delay (MK.12b.3) — Media3 has no setter; external player covers it
- Auto series recording via XMLTV heuristic (MK.14.6) — replaced with manual series bind
- ~~MPEG-TS / DASH / encrypted recording (MK.14.7) — phase 2~~ — **OVERRIDDEN 2026-04-25:** MPEG-TS recording is in v1.0 (HLS-only is not "complete"); DASH + encrypted segments stay phase 2
- Cross-source user groups (MK.13.5) — phase 2
- SOCKS proxy (MK.17.6) — phase 2
- Connection profiles (MK.17) — phase 2
- DLNA / UPnP (MK.18.4) — **DROPPED PERMANENTLY** (re-confirmed 2026-04-25)
- Cross-device handoff (MK.18.5) — **DEFERRED post-v1 study** (2026-04-25)
- Custom hex accent (MK.16.3) — 4 presets only
- Chromecast (MK.11.3 / MK.18.3) — **DROPPED PERMANENTLY** (2026-04-25)
- TIF live-channels (MK.10.2) — **DEFERRED post-v1 study** (2026-04-25)

**Ordering constraints** (deviate = rework):
1. **MK.12a ships before MK.12b** — the fast wires alone close ~60% of the visible gap and surface whether the bottom-sheet UX itself works before committing to heavier items.
2. **MK.16.1 (theme refactor) ships before MK.15.3 and MK.16.2+** — driving row-height and palettes from state requires the `object → data class + CompositionLocal` refactor landed first.
3. **MK.17.1 ships as its own commit, first in MK.17** — it's a latent P0 that makes existing Settings real.

**Schema-migration discipline** (6 migrations across this block):
- Each migration lands in the same commit as a `commonTest` upgrade test with populated fixture rows.
- Every new timestamp column documents `-- ms since epoch` per native-android-mk rule.
- Manual populated-DB check on Fire TV with ≥5k content rows before moving on.

**If time shrinks** — N/A. Per 2026-04-25 decision, v1.0 = complete. No timeline pressure to budget against. If a feature genuinely doesn't work or adds disproportionate scope mid-implementation, escalate to the user before cutting.

**Known risk zones:**
- HLS recording (MK.14.1) — segment tee handles most streams; TS-discontinuities and encrypted segments are known failure modes, flagged in the plan with phase-2 deferral.
- `MediaItem` rebuilds in 12a.3 (external subs) and 14.1 (recording interceptor) must persist resume point before re-prepare, per native-android-mk "resume-point" rule — regression-test both.
- Theme refactor (16.1) blast radius = every `YancoPalette.*` reference. Mitigation: do it in one commit with a scripted rewrite, run full smoke before MK.16.2.

---

## D — Debugging & hardening (post-MK.11, runs in parallel with MK.12)

Standing phase started 2026-04-24 after the cascade-nav focus bug shipped in `4a8a46e`. Goal: surface regressions in tooling instead of in user manual-test reports, before MK.12 distribution. **Scope was red-teamed** — `:macrobenchmark` and a `scripts/logcat.sh` were rejected as overkill / wrong-platform. Crashlytics and Sentry were rejected at the time as overkill for a personal app; **the Sentry rejection was reversed 2026-04-25** (now Stage 1.3) once observability was reframed as a foundation for every Stage 3+ feature, not optional polish. D.4's local crash log stays — it complements Sentry as an offline fallback.

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
| D.4 | `Thread.setDefaultUncaughtExceptionHandler` in `YancoApp.onCreate` writing last-crash to `filesDir/crash.log` + Kermit. Originally framed as "no Sentry" — **superseded 2026-04-25:** Sentry ships in Stage 1.3 alongside this; D.4's local crash log remains as an offline fallback (captures crashes when network is down or before Sentry SDK init) | Force a crash → next launch reads `filesDir/crash.log` and surfaces it (or just logs it for now) | **DONE** `1e69da0` — `CrashReporter` singleton; atomic write (tmp→rename); reads+clears on next launch via `Log.e(TAG="YancoCrash")`; uses `android.util.Log` not Kermit (runs before shared module init) |
| D.5 | Behavioural tests for the two skill-checklist landmines: (a) `positionFor(contentId)` returns null for a series container with no content-level row (never an episode row), (b) `controller.currentId == target.id` short-circuit at every `controller.play(` call site — write a test fixture that calls each launch site twice with the same item, asserts the second call doesn't re-prepare the `MediaItem` | Both tests in `:shared:commonTest` (positionFor) and `app/src/test/` (currentId guard) | **DONE** `a8bc63a` — (a) already covered by `WatchHistoryRepositoryTest.positionFor_ignoresEpisodeRowsWhenNoContainerRow` (MB-41 guard). (b) extracted `resolveActivation(currentId, targetId, isTv) → ActivationAction` into `BrowseShell.kt`; updated `FavoritesScreen` two call sites to use it; `ActivationGuardTest` (6 tests) pins TV first-tap/second-tap/null and phone single-tap/already-playing/different-item routing. `PlaybackController.play()` also has the same guard internally (lines 153-157) — belt + suspenders. Auto-preview paths guarded via `resolveAutoPreviewIndex()` (already tested). |
| D.6 | Audit pass — read `logger/AndroidLogger.kt` (currently routes shared-module Logger to `android.util.Log`; CLAUDE.md claims "Kermit logging" but Android side doesn't actually use Kermit), grep all `BackHandler {` sites for missing back-stack handling, grep `controller.play(` sites for the two-tap guard, grep `AsyncImage(` for missing `contentDescription` | Punch list of findings written into a follow-up `D.7` task per finding | **DONE** — Audit clean across all three categories. (1) BackHandler: 7 instances (HomeScreen ×3, BrowseShell ×3, CoverflowSectionScreen ×1), all properly enabled-guarded with real handlers — no empty blocks. (2) controller.play(): 15 call sites, 15/15 guarded — HomeScreen uses explicit `currentId != target.id`, auto-preview uses `resolveAutoPreviewIndex()`, FavoritesScreen uses `resolveActivation()`, SearchScreen has inline alreadyPlaying guard. (3) AsyncImage: 15 call sites, 15/15 have `contentDescription` param (13 explicit null with paired text, 2 semantic string descriptions). No D.7 punch list needed. |

**Explicitly out-of-scope (red-teamed and rejected):**
- `:macrobenchmark` Gradle module + baseline-profile generator — weeks of yak-shaving for a personal IPTV app with no perf complaint on file. Use `dumpsys gfxinfo com.yancotv.android framestats` + the Compose recomposition reports above. Revisit only if a real jank report lands.
- Crashlytics — `google-services.json` bloat + Firebase boot cost. **(Sentry was previously in this bucket; reversed 2026-04-25 — now Stage 1.3. See `MK.19.5` and the decision log.)**
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
| MB-34 release telemetry | Replaced by **Sentry** in Stage 1.3 / MK.19.5 (decision 2026-04-25 — was Crashlytics) |

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

## Timeline

**Removed by user decision 2026-04-25.** Work proceeds at user's pace — sessions resume when user is rested. The 5-stage roadmap at the top of this file replaces week-based estimates. Historical estimates from before 2026-04-25 are preserved in git history if a back-reference is ever needed.

iOS / iPadOS milestones (`MK.iOS.*`) remain a separate post-Android-v1.0 block — see iOS section below.

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
| ~~Firebase Crashlytics on Android, Sentry stays on desktop~~ — **SUPERSEDED 2026-04-25** by the "Sentry replaces Crashlytics on Android" row below | Crashlytics was free + Play Console integrated; reversed once Sentry's KMP support and unified two-platform observability outweighed the Firebase-specific free tier | 2026-04-20 |
| RN app frozen, not deleted | Reference during rewrite; archive after Android ships | 2026-04-20 |
| `@yancotv/core` TS stays as-is | Double-port cost (~2 weeks) cheaper than a cross-language build toolchain | 2026-04-20 |
| **Sentry replaces Crashlytics on Android** (supersedes 2026-04-20 decision above) | Sentry has better KMP support, doesn't tie us to Firebase, works on desktop already so one platform fewer to learn. Crashlytics requires `google-services.json` and Firebase boot-up cost not worth it for a personal app | 2026-04-25 |
| **Chromecast (MK.11.3 / MK.18.3) dropped permanently** | Default Cast receiver feasibility uncertain for IPTV streams (raw TS over HTTP with custom UA may not work). Custom Web Receiver is a separate project. User pattern: install YancoTV on every TV directly | 2026-04-25 |
| **TIF (MK.10.2) deferred to post-v1 study** | Fire TV doesn't support TIF (zero value on canonical test target); scope is `TvInputService` + parallel channel/program DB + EPG re-ingestion. Revisit only if Google TV becomes primary target | 2026-04-25 |
| **DLNA / UPnP (MK.18.4) dropped permanently** | Built for stored media not live streams; many DLNA renderers reject HLS/MPEG-TS or transcode badly. Older smart TVs aren't a target audience | 2026-04-25 |
| **Cross-device handoff (MK.18.5) deferred to post-v1 study** | Requires either a cloud backend (out of scope, GDPR implications) or LAN-only sync (loses home/away use case). Marginal value for live TV. Revisit only if cloud backend gets added for other reasons | 2026-04-25 |
| **No timeline / week estimates on the active plan** | Work proceeds at user's pace; sessions resume when rested. Estimates create false-precision pressure that doesn't match the actual cadence | 2026-04-25 |
| **Definition-of-Done per Stage 3+ feature** (R8 + Sentry + TalkBack + D-pad + Fire TV soak + no-new-placeholders) | Avoids the regression-fixing tail at the end of v1.0. Catches reflection breakage, accessibility regressions, and stub-shipping inline as features land | 2026-04-25 |
| **Schema migrations bundled into Stage 2** | Fragmenting migrations across features causes migration-A-vs-migration-B conflicts; one upgrade test pass over a single schema bump is safer | 2026-04-25 |
| **MK.9 (FFmpeg) is Stage 1, not late-stage** | MB-14 leaves ~30% of streams audio-only; UX polish on broken playback is wasted. Risk-front-loading: if NDK / R8 keep rules / ABI splits go sideways, we want to know before stacking 6 features on top | 2026-04-25 |
| **MK.14 Recording = HLS + MPEG-TS, both, in v1.0** (overrides 2026-04-24 phase-2 deferral of MPEG-TS) | "HLS-only recording" ships broken on Xtream catch-up which is mostly TS. Recording must work on the streams the user actually has, or it's not "complete" | 2026-04-25 |
