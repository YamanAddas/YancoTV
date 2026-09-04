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
4. ✅ **MK.22 — Motion polish** — Sprint A shipped 2026-04-28 (`94bb577`): closed MB-221 (sidebar focus → expand felt-lag) + MB-222 (OnNowTile frozen clock). Sprint B shipped 2026-04-28 (`41a83ac`): 7 polish fixes (Settings tab focus retry skip for cheap tabs, HexSurface 5-spring collapse, tab shadow tween, hero crossfade timing, CategoryRail debounce, remove `remember(index)` wrappers, hero backdrop debounce). User confirmed "yes its better" hands-on.
5. ✅ **MK.23 — Test hardening** — Sprint C shipped 2026-04-28 (3 commits, `3ab8311` → `0ce8333`): PlaybackController.persistResumePoint regression suite (11 tests via extracted `resumePointDecision` pure function), BulkContentWriter.abortSource cross-source FK survival, FavoritesRepository multi-list (10 tests covering MK.13.4). Sprint D shipped 2026-04-28 (7 commits, `a51907c` → `88d67c1`): finishSource failure path, SourceSyncCoordinator re-entrancy (with refactor to drop Context dependency), syncSource cancellation, WatchHistory orphan rows, EPG re-sync vs `recording_schedules.programme_id` SET NULL, v9 → v10 migration, schedule.recording_id SET NULL.
6. ✅ **MK.24 — Audit follow-ups + heap-pressure bug — COMPLETE 2026-04-28**. All 6 sprints shipped: H (4 MB filings) + E (4 test gap closures, closes MB-225 + MB-226) + I (heap-pressure root-cause + 3 fixes, MB-230 first-fix) + F (closed by audit, no items) + G.1 (5 per-hop migration tests, closes MB-227) + G.2 (corruption-recovery extraction + 9 tests, closes MB-228). ~14 commits. 6 follow-up items tracked as 24.I.X.1–6 (heap-pressure soak items — pick up only if F3 Sentry probe fires in the wild). MB-229 still blocked on MB-230 verification.
7. ✅ **Polish sweep — complete 2026-04-28**. MK.20 follow-ups all shipped: multi-word region parsing + 10 missing 2-letter codes (`5eef8a0`); pin-a-bucket end-to-end with data layer + tree-builder support + Settings UI in `SettingsGroupsTab` + 9 new tree-builder tests (`1b875bf`). Subsumes the original "MB-208 / MB-209 / MB-210 receiver-path test hardening" item — those tests are largely covered by MK.23 Sprint D + recording-subsystem tests from 2026-04-27.
8. ✅ **MB-224 — Set up CI** — shipped 2026-04-28. `.github/workflows/android-tests.yml` runs `:shared:testDebugUnitTest`, `:app:testDebugUnitTest`, `:app:assembleDebug` on every push to `master` + every PR targeting master. Java 17 Temurin + Android SDK 35 + Gradle cache keyed on wrapper + build files. Side-fix: `gradlew` exec bit was missing in git tracking (Windows authorship); fixed via `git update-index --chmod=+x` + workflow `chmod +x` belt-and-suspenders. Linux runner re-enables SQLDelight build-time migration verification that's Windows-disabled locally — extra safety net.

After this queue clears, return to Stage 5 default order (5.2 sideload auto-update, 5.3 a11y audit, …).

The MK.* numbering below stays as a reference catalog; what's authoritative going forward is the **5-stage dependency + risk ordering** in this section.

### Status (reconciled 2026-08-22) — READ THIS ONE

> **1.6.0 PUBLISHED 2026-08-23.** Continues the 1.x line from 1.5.3 as
> **1.6.0 (versionCode 24)** and published to `YamanAddas/yancotv-releases`
> (tag `1.6.0-24`, `update.json` bumped so installed 1.5.3 devices are
> offered it). Signed with the release keystore (cert parity with 1.5.3
> verified, so installs succeed), sha256-verified, all session fixes
> confirmed in the shipped DEX. (A brief 0.9.0/23 build was published then
> withdrawn when the version line was kept at 1.x.) MB-371 remains the
> headline known issue.
>
> **Addendum (reconciled 2026-08-23, end of the overnight session).** The
> pre-release audit series MB-364..MB-370 plus MB-372 is closed; MK.11.2 and
> the MK.15 fragment shipped, completing the v1.0 feature scope; MB-355 and
> MB-356 are field-verified on hardware (the scheduled-recording fire at
> 03:44:30 exercised every stage, refused by the provider's 1-connection
> limit with an honest `stream_458`). The register holds THREE open entries:
> **MB-371** (guide lane LEFT/RIGHT — parked, three failed attempts
> documented, touch works), **MB-368** (fat-APK ABI split — deliberate,
> needs an updater change first), and hosted copies of the policy/licence
> documents folded into MB-367/372 as owner actions. Known release blockers:
> none. Known remaining risk: no phone hardware has touched this code, and
> MB-355's bytes-through-sink sliver on device waits on a free provider
> connection.

The 2026-04-25 block below is preserved for history and is **four months stale**;
it predates MK.26–MK.35 entirely. It was actively misleading during the
2026-08-21/22 session (Stage 4.6 shows no tick yet Stage 5.5 describes it as
shipped), so this section supersedes it.

**Reconciled against git and `bugs.md` with a detector script, not by eye** —
176 register entries cross-referenced against every commit that names them.

**Shipped since the April block:** MK.21 (Settings redesign), MK.22 (motion),
MK.23 (test hardening), MK.24 (audit follow-ups), MK.19.8 (backup/restore),
MK.26 (cast: LAN handoff + Google Cast), MK.27 (phone UX adaptation) + MK.27.HF1,
MK.28 (full-app audit sweep), MK.29 (browse preview + TV type ramp), MK.30
(settings scroll / expiry / update awareness), MK.31 (localization: EN/AR/FR/ES +
RTL), MK.33 (multi-playlist categories), MK.34 (player chrome "Midnight Lounge"),
MK.35 (Home rails + the 1.4.0→1.5.0 upgrade gate). Stage 4.6 (theme + accent
picker) shipped — the unticked row in the Stage 4 table is wrong.

**MK.25 (player UX pass) is NOT a shipped milestone and NOT untouched.** Much of
its intent arrived through bug-fix work instead — seek flash (MB-341), dock time
labels + episode kicker (MB-340), autoplay-next + up-next card (MB-343), dock
auto-hide (MB-345), type labels (MB-346) — while the milestone's own slices stay
written as planned. Treat the MK.25 tables as a menu of what is still missing
(scrub mode, preview thumbnails, skip-intro), not as untouched scope.

**Genuinely remaining for v1.0 — RE-VERIFIED AGAINST THE CODE 2026-08-22.**

The six-slice list this section carried until now was wrong, and wrong in the
same direction every time: it listed as "remaining" work that had already
shipped through bug-fix and polish passes. That stale list was consulted four
separate times in one session before anyone checked it against the tree. Each
row below was re-verified by grepping for the actual implementation, not by
reading this document.

| Slice | Verified state | Evidence |
|---|---|---|
| MK.10 — TV launcher recommendations + voice search | **SHIPPED** | `RecommendationsSync` + `RecommendationsWorker` exist and are wired (`YancoApp` enqueues periodic + one-shot); `MainActivity` handles `ACTION_SEARCH` via `SearchManager.QUERY`; `res/xml/searchable.xml` present; deep links handled |
| MK.13.4 — multi-favorite-list UI | **SHIPPED** | `FavoritesScreen` renders `FavoriteListsTabBar`, tracks `selectedListId` and filters rows by `listId`; `ChannelActionsMenu` assigns lists |
| MK.17.1a–17.5 — network + playback prefs | **SHIPPED** | `SettingsNetworkTab` carries UA presets (`net_preset`), custom UA, test connection, connect/read timeouts; `AppPreferences` has `setDecoderFallback` + `setBufferProfile` |
| MK.18.2 — default external player per content type | **SHIPPED** | `ExternalPlayerBucket { LIVE, MOVIE, SERIES }` + `setDefaultExternalPlayer`, surfaced in `SettingsPlaybackTab`; the enum's own KDoc cites MK.18.2 |
| MK.15 — EPG display options | **SHIPPED 2026-08-23** | days forward/back, timeline granularity, jump-to-now chip, programme dialog, catch-up — and now guide row height as a user setting (`GuideRowHeight` compact/standard/comfortable, Settings → EPG → Density, all four locales). STANDARD is 56dp, exactly the previous hardcoded value, so it ships as a visual no-op. |
| MK.11.1 — phone PIP | **SHIPPED** | `onUserLeaveHint` + `enterPictureInPictureMode(params)`, API-gated, `supportsPictureInPicture` in the manifest |
| MK.11.2 — phone gesture controls | **SHIPPED 2026-08-23** | `PlayerGestures` (pure, 11 tests, negative-controlled) + `GestureHudOverlay`; swipe left-half vertical = brightness, right-half vertical = volume, horizontal = seek. Phone only — `onScroll` returns early on `isTvDevice()`, so nothing is classified on TV. |

**Remaining scope for v1.0 — NONE.** Both fragments shipped 2026-08-23:

1. ~~MK.11.2 — phone gesture controls~~ **shipped.**
2. ~~MK.15 fragment — guide row height as a setting~~ **shipped.**

Every feature slice this plan tracked for v1.0 is now built. What stands
between here and a 1.0 release is verification, not construction: the
"SHIPPED" rows above mean the implementation exists and is wired, not that
each has been exercised on hardware. A QA pass across the shipped surface is
the honest next milestone — tonight's session found MB-361, MB-362 and
MB-363 by simply trying to use the app, and there is no reason to think that
well is dry.

**Caveat, stated deliberately:** "SHIPPED" above means *the implementation exists
and is wired*, verified by reading the code. It does NOT mean each feature has
been exercised end-to-end on hardware. Treat these as "built, needs a QA pass",
not "proven".

Plus **Stage 5.7 distribution, partially done**: GitHub Releases + `update.json`
work end to end — **confirmed on hardware 2026-08-22**, a Google TV running 1.4.0
was offered 1.5.3 by the in-app checker and updated successfully with data
intact. Play Console and Amazon Appstore listings do not exist.

> **Release trap worth keeping:** publishing the GitHub Release alone does
> nothing for existing installs. They poll `update.json` on the Pages branch, and
> until that file is bumped they are told they are already current. 1.5.3 was
> published and briefly invisible to every 1.4.0 device for exactly this reason.

**Open bugs after reconciliation: 6.** MB-229 (High — unblocked 2026-08-22 when
MB-230 closed; needs a device retest), MB-200, MB-344, MB-352 (Medium), MB-211,
MB-348 (Low). MB-230 was closed by this pass: it had sat Open for a month on a
device soak that had already happened and was recorded in the register's own
header.

**Blocking everything, and not a plan item:** GitHub Actions has refused every run
since 2026-08-21 — "recent account payments have failed or your spending limit
needs to be increased". Migrations `11.sqm` through `14.sqm` have therefore never
been through `verifyCommonMainYancoDbMigration`; the substitute is
`UpgradeFrom140Test` (MK.35.4). This is the largest unmitigated risk in the tree
and only the account owner can clear it.

### Status (per 2026-04-25 audit) — SUPERSEDED, kept for history

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

(MK.11.3 Cast + MK.18.3/4/5: **revisited 2026-06-15 → MK.26.** Handoff (18.5) revived as Track A primary; Cast (11.3 / 18.3) revived as Track B secondary; DLNA (18.4) stays dropped. See MK.26.)

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
| 5.2 | **Sideload auto-update check** — split into 3 sub-slices. **5.2.1 ✅ shipped 2026-04-28** (`2202460`): `UpdateChecker` pure-logic poller in `:shared/commonMain` + 11 table-driven tests. Polls a custom `update.json` (chosen over GitHub Releases API to avoid coupling update logic to tag-naming conventions). **5.2.2 ✅ shipped 2026-04-28** (`79c39b4` + `3c09162` + UI iterations through `bc47ef0`): `UpdateRepository` (mutex-coalesced, in-memory `StateFlow<UpdateInfo?>`) + `UpdateCheckWorker` (24h periodic + one-shot `KEY_FORCE` bypass) + `BuildConfig.UPDATE_ENDPOINT` from `local.properties` + Settings → About panel showing "v X.Y.Z available" / "You're on the latest version" with auto-check toggle, "Check now" button, and "Open release page" fallback (`Intent.ACTION_VIEW`). **5.2.3 ✅ shipped 2026-04-28:** `UpdateInstaller` singleton (`StateFlow<State>` of Idle / Downloading(pct) / ReadyToInstall / Failed) downloads APK via shared OkHttp into `getExternalFilesDir(null)/updates/yancotv-<versionCode>.apk` with whole-percent progress throttling, then hands off to system PackageInstaller via FileProvider content:// URI + `ACTION_VIEW` + `application/vnd.android.package-archive`. `REQUEST_INSTALL_PACKAGES` declared in manifest; on Android 8+ a `canRequestPackageInstalls()` gate routes the user to `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` if not yet granted. About-tab banner morphs through Download → Cancel → Install / Retry; "Open release page" fallback stays available in every state for browser sideload. End-to-end ship-readiness verification deferred to user on-device test. | new |
| 5.3 | **Accessibility audit** ✅ shipped 2026-04-28 — TalkBack semantic-role pass across every interactive surface in the app. Audited 27 files / 44 distinct control sites; added explicit `role = Role.{Button,Switch,Tab,DropdownList}` to every custom `Row`/`Box` + `.clickable` so TalkBack announces the control type instead of just "double-tap to activate". Settings primitives (`SettingsButton`, `SettingsRow`, `SettingsToggleRow`, `SettingsChip`, `SettingsSelect`) cover most settings tabs by composition; per-tab inline controls (Appearance theme/accent swatches, Groups visibility/pin chips, Recordings storage picker, Text field overlays) updated individually. Player primitives (`TransportButton`, `SecondaryChip`, `VodPlayerChrome` taps, Player options menu rows) all carry `Role.Button`; sidebar items + category pills carry `Role.Tab`; toggles carry `Role.Switch`; selects carry `Role.DropdownList`. Guide grid: `JumpToNowButton`, `ChannelCell` (`combinedClickable`), `ProgrammeBlock` all wired. Bulk transformation done with a Python regex to keep the 27-file edit reviewable; `SettingsScreen.kt` already had the role set via `Modifier.semantics` and was left untouched. AsyncImage audit: all 12 sites have explicit `contentDescription` (real string for standalone tiles, `null` for decorative images paired with adjacent text labels). D-pad traversal: validated continuously through real-use across all prior Stage 3-4 work, so no separate sweep needed. | new |
| 5.4 | **Performance audit** ✅ shipped 2026-04-28 — release-build matrix on AFTDCT31. **Cold start passed cleanly:** debug 11.3s → release 1.75s p50 (budget ≤ 2.5s, comfortably under after warm-up). **EPG scroll gap closed in two slices:** slice 1 (`edaa41d`) moved the now-line indicator's `hScroll.value` read into `Modifier.offset { }` so it's a layout-time-only event (was forcing whole-grid recomposition every scroll frame); slice 2 (`03215e5`) virtualised programme lanes via `BoxWithConstraints` + `derivedStateOf` filter so each `ChannelRow` only emits programmes intersecting the viewport ± 60 min buffer (was 750–2250 `ProgrammeBlock` instances in the layout tree). Vertical p95 went 450 → 57 ms (8× total tail improvement); user-confirmed feels smooth in real use. Two residual gap items (per-cell `TextLayoutResult` caching + `RGB_565` channel logos) tracked in `packages/android/PERFORMANCE.md` for a follow-up perf sprint after distribution-readiness work; not v1.0-blocking. | MK.19 perf piece |
| 5.5 | **Placeholder audit** ✅ shipped 2026-04-28 — every "COMING IN MK.XX" string is gone from shipped code paths. Audit findings: `SettingsAppearanceTab` filled by Stage 4.6 (theme + accent picker, real content); `SettingsRecordingsTab` filled by Stage 3.1 (SAF storage picker, recording defaults, real content); orphan tabs (`SettingsSubtitlesTab`, `SettingsNotificationsTab`, `SettingsStorageTab`) unwired from sidebar in `ae6dc25` and have no remaining callers — files kept per plan for post-v1 hookup. Player-options sheet panels all wired with real content (`PlayerOptionsPanels.kt`): SLEEP, RECORD, FAVORITES, EXTERNAL each have shipped logic; CAST and LOOK never landed in the enum (Cast was dropped 2026-04-25 — now revived 2026-06-15 as MK.26, but via a MediaRouteButton + LAN-handoff picker, NOT this old player-options CAST tab; LOOK absorbed into Settings → Appearance). One stale code comment in `VodPlayerDock.kt:471` calling FAV a "COMING IN MK.XX" stub fixed in this slice — FAV chip routes to the wired FavoritesPanelContent. | new |
| 5.6 | **Privacy policy + ToS + content rating** ✅ shipped 2026-04-29. Code surface: `CrashReportPrefs` (SharedPreferences-backed, no Koin since SentryInit runs before Koin starts) + `Sentry.beforeSend` / `beforeBreadcrumb` callbacks gating every event on the toggle; `RecordingDisclaimer` + `rememberRecordingDisclaimerGate` Composable wrapped at all three user-initiated record entry points (player options Record panel, Guide → Schedule recording, Guide → Schedule series); Settings → About has a new **Privacy** section with "Send crash reports" toggle + "Privacy policy" + "Terms of service" links (open URLs in system browser, currently pointing at `example.invalid` placeholders until docs are hosted). Doc artifacts at repo root: [PRIVACY.md](PRIVACY.md), [TERMS.md](TERMS.md), [CONTENT_RATING.md](CONTENT_RATING.md) — drafts user reviews + customises (contact email, jurisdiction) before any public store submission. | new |
| 5.7 | **Distribution pipeline** — Play Console listing (TV + phone), Amazon Appstore (Fire TV), GitHub Releases signed APK + `update.json` endpoint feeding 5.2. | MK.19.2, 19.3, 19.4 |
| 5.8 | **Manual QA matrix** ✅ checklist shipped 2026-04-29 — [QA_MATRIX.md](QA_MATRIX.md). Lightweight per-device + per-feature smoke-test checklist for the friend-group beta. Fire TV AFTDCT31 + phone HT74J0206349 are the primary targets verified hands-on through Stage 1–5 development; Google TV (192.168.68.52, adb-discoverable but never authorised), Fire TV Stick 4K, mid-range Android TV box are tier-2/3 targets to fan out to during the friend-group beta. This is a CONTINUOUS phase from now on — your testers ARE the QA matrix; the file just gives them (and you) a structured way to log what's working + what isn't. | MK.19.6 (expanded) |

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
| Chromecast (MK.11.3 / MK.18.3) | ~~**Dropped permanently**~~ — **REVISED 2026-06-15 → MK.26 Track B** (secondary/droppable, app-less Chromecasts only); IPTV-feasibility concern confirmed (needs custom receiver + proxy, never zero-lag) |
| TIF live-channels integration (MK.10.2) | **Deferred — post-v1 study.** Fire TV doesn't support TIF, value is Google-Android-TV-only |
| DLNA / UPnP (MK.18.4) | **Dropped permanently** — built for stored media not live streams; usage pattern doesn't need it (reconfirmed by 2026-06-15 red-team) |
| Cross-device handoff (MK.18.5) | ~~**Deferred — post-v1 study.**~~ — **REVISED 2026-06-15 → MK.26 Track A (PRIMARY)** — LAN-only "play on my TV" handoff, the zero-lag all-content path |
| MK.10 TIF replacement | **Recommendations channel + voice search deep link only** |
| Recording (MK.14) scope | **HLS + MPEG-TS both, in v1.0.** HLS-only is not "complete" |
| Definition-of-Done per feature | **Adopted** — see above |
| No timeline | **Adopted** — work proceeds at user's pace |

### Post-v1 ideas register

Flagged here so future-us doesn't re-litigate from scratch. Both depend on architectural shifts that are out of scope for v1.0.

- **TIF (TV Input Framework)** — inject YancoTV channels into the Android TV system Live Channels app. Pros: voice search tunes directly, channel up/down works system-wide, surfaces in Google TV "Live" recommendations. Cons: Fire TV doesn't support TIF (zero value on the canonical test target); massive scope (`TvInputService`, channel/program metadata sync into TIF DB, surface session for video, EPG re-ingestion, parental re-wired); maintenance burden as TIF data desyncs from app data. Revisit only if Google TV becomes the primary target.

- **Cross-device handoff** — **NOTE 2026-06-15:** this entry conflated two directions. The *phone→TV "play on my TV"* direction is now **MK.26 Track A** (LAN-only, no cloud). What remains post-v1 is only the *TV↔phone resume-continuity* (away case) below. ~~pause on TV, resume on phone where you left off. Pros: nice continuity UX for VOD/series. Cons: needs either a cloud backend (Supabase/Firebase + privacy policy + GDPR if EU users) or LAN-only (loses the home/away use case which is the only reason handoff is interesting); marginal value for live TV (most IPTV usage). Revisit only if a cloud backend gets added for other reasons.~~ The away-case continuity still needs a cloud backend — revisit only if one gets added for other reasons.

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
| MK.6.3 | `PlayerActivity.kt` (port) — fullscreen; attaches **the same** `ExoPlayer` to its `PlayerView` by swapping the output Surface (`setVideoSurface` / `clearVideoSurface`; the plan originally said `switchTargetView()`, but the shipped code uses Surface-swapping) → no rebuffer | Enter on focused channel expands to fullscreen with zero rebuffer |
| MK.6.4 | Back from fullscreen → the Surface is handed back to the mini slot (symmetric `clearVideoSurface` / `setVideoSurface`); player keeps playing | no black frame, no audio gap |
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
| MK.9.4 | **Crash watchdog** — `Player.Listener.onPlayerError` catches FFmpeg native crashes, releases the current `ExoPlayer`, rebuilds it on the same `PlaybackService` path with a platform-only `RenderersFactory` (preserves Architecture rule 4: still one ExoPlayer at a time, never two simultaneously), retries `prepare()` once; if that also fails, surfaces error overlay (no hard-crash of `PlayerActivity`). The mini-preview ↔ fullscreen Surface-swap path (`setVideoSurface` / `clearVideoSurface`) stays valid because the new player is bound to the same service. | Force-fault the FFmpeg renderer in a debug build → playback recovers on platform decoder; mini-preview and fullscreen continue to share the rebuilt instance |
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
| ~~MK.11.3~~ | ~~Chromecast sender via Media3's `CastPlayer`~~ — **DROPPED 2026-04-25 → REVIVED 2026-06-15 as MK.26 Track B** (secondary/droppable). The feasibility concern was confirmed (needs Custom Web Receiver + server-side proxy, never zero-lag), so Cast is scoped to app-less Chromecasts, movies/series-first; the LAN handoff (Track A) is the primary path | MK.26 Track B |

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
| ~~MK.18.3~~ | ~~Chromecast sender via Media3 `CastPlayer`~~ — **DROPPED 2026-04-25 → REVIVED 2026-06-15 as MK.26 Track B** (secondary). See MK.11.3 + MK.26 | — | MK.26 Track B |
| ~~MK.18.4~~ | ~~DLNA / UPnP~~ — **DROPPED PERMANENTLY** (re-confirmed 2026-04-25 **and again by 2026-06-15 red-team**: raw TS fails DLNA compliance, renderers reject live HLS, Fire TV has no renderer). Built for stored media, not live streams | — | N/A |
| ~~MK.18.5~~ | ~~Cross-device handoff~~ — **DEFERRED 2026-04-25 → REVIVED 2026-06-15 as MK.26 Track A (PRIMARY)**, LAN-only "play on my TV" (no cloud → no GDPR). The away-case resume-continuity stays post-v1. See MK.26 | — | MK.26 Track A |

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
| MK.20.3.2 | `BrowseSection` and `GuideScreen` switch on the preference: off → existing flat `groups()` call (Slice 20.1 already gives provider order); on → call `groupsHierarchical()` and flatten visible nodes per current expand/collapse state. Expand state held in a `rememberSaveable` list-saver Set per section — survives rotation/recreation while the section stays mounted; a Live ↔ Movies type swap resets it because the shell remounts `BrowseSection` under `key(contentType)` (corrected 2026-07-25, MB-284; cross-type persistence deferred to MK.27.B). | 🟡 glue | Toggle on → rail shows `▶ Arabic (12)` and `▶ USA (8)` rows; toggle off → flat rail unchanged |
| MK.20.3.3 | `CategoryRail` row composable branches on `CategoryNode` type. Parents render as a single pill with `▶`/`▼` glyph + count badge. CENTER on a parent toggles expand. Children render indented (or with a leading `↳` glyph — pick one in design pass). Per the MK.8 cascade-nav rule: own a fresh `PlacedFocusAnchor` inside `key(expandedSet.hashCode())` so expand/collapse remounts the visible-list scope cleanly. Refresh `requestFocus()` lands on the previously-focused row's new index after expand or, if it was a now-hidden child, falls back to its parent. | 🔴 new | All three MK.8 cascade-nav flows pass on Fire TV (sidebar→rail RIGHT, rail→content RIGHT/CENTER, type swap remount) |
| MK.20.3.4 | `GroupPreferences` interaction rules: (a) leaf with `is_hidden=true` is skipped during tree build; (b) parent with all children hidden is omitted entirely (no empty dropdown); (c) `is_pinned` on a leaf floats it to root level (out of its parent bucket) — semantics: pinning is "promote to top" and parents are visual grouping, not membership; (d) pinning a parent is not yet supported (out of scope this slice — file [bugs.md MB-211](bugs.md) "pin a whole language bucket" follow-up if user asks). | 🟡 glue | Hide a child → not visible; hide all of Arabic's children → Arabic parent disappears; pin a child → moves to top of root list, retains pin glyph |
| MK.20.3.5 | Test: snapshot-style unit test on the visible-flatten helper — asserts that `(tree, expandedSet, hiddenSet, pinnedSet) → List<CategoryRow>` produces the expected ordering for ≥6 cases (all collapsed, one expanded, parent fully hidden, pinned-child-floats, single-child-collapse, mixed). | 🔴 new | Helper is pure / pinned by tests so refactors can't regress the rules |
| MK.20.3.6 | Manual Fire TV verification on a real provider M3U: enable toggle, confirm Arabic / English / USA / Canada parents materialize, expand/collapse navigates cleanly with D-pad, hidden + pinned interactions match 20.3.4. Capture before/after for the user. | — | Hands-on green check |

**Risky slice.** Focus model is the bulk of the cost. ~4–6 hours including the cascade-nav audit + manual Fire TV pass.

### MK.20 polish-sweep status (2026-04-28) — ✅ complete

| Item | Status | Notes |
|---|---|---|
| Multi-word region names | ✅ done | `5eef8a0` — replaced single-word regex with delimiter-scan + catalog lookup. "Saudi Arabia | …", "South Korea | …", "Hong Kong | …", etc now bucket correctly. |
| Missing 2-letter codes (BG/CZ/HR/HU/IS/KZ + ...) | ✅ done | `5eef8a0` — added 10 codes. Catalog now ~62 regions + 12 languages. |
| Pin-a-bucket | ✅ done | `1b875bf` — `pinnedParentsFlow` per ContentType in `AppPreferences`; `CategoryTreeBuilder.build` extended with `pinnedParentCodes` parameter (parents float to top in pin-list order, single-child collapsed buckets ignored, lowercase code OR displayName lookup); `SettingsGroupsTab` adds "Pinned categories" section visible when smart grouping enabled; `BrowseSection` + `GuideScreen` thread the pinned codes through. 9 tree-builder tests cover the rules. Hands-on Fire TV verify deferred per user's "test later" instruction. |

### Out of scope for MK.20 (file as MB-* / future MK if asked)

- In-app catalog editor ("rename AR to العربية"): defer to a later milestone. Static catalog file is enough for v1.
- Pinning a whole parent bucket (mentioned in 20.3.4): see status table above.
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
| MK.21.5 | **Per-source "auto-sync on app start" toggle** + backing schema (`9.sqm` v9 → v10 added `auto_sync_on_start INTEGER NOT NULL DEFAULT 0`) + threading through `Source` / `SourceRepository` / `BackupFileV1.SourceRecord` / backup exporter + importer | `f157250` | MainActivity reads sources where the flag is set on `onCreate`, kicks `SourceSyncCoordinator.start` for each (sequential — coordinator gates concurrent syncs). **Caveat:** the schema-add broke 10 `:shared:androidUnitTest` files until 2026-04-28 (MB-223). Note: SQLDelight numbers `.sqm` files by destination version, so `9.sqm` is the v9 → v10 hop (current schema version is 10). |
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
| 22.A.1 | **Drop the `delay(120)` preamble** in `LaunchedEffect(section)` that gates `panelFocus` flip + `mainContentFocus.requestFocus()`. Original "wait for old composable to leave" justification is obsolete now that section content uses `key()`. If a beat is still needed, replace with a single `withFrameNanos { }`. | [HomeScreen.kt:184-199](packages/android/app/src/main/java/com/yancotv/android/ui/shell/HomeScreen.kt) | ✅ done | Sidebar starts visibly expanding within one frame of focus arrival; no perceptible "not responding" gap. |
| 22.A.2 | **Replace `animateDpAsState(spring(0.85f, 320f))` with `tween(180, FastOutSlowIn)`** on the sidebar width. Width-only animation; no need for spring physics or its overshoot tail. | [AppSidebar.kt:100-105](packages/android/app/src/main/java/com/yancotv/android/ui/shell/AppSidebar.kt) | ✅ done | Sidebar opens in 180 ms with no overshoot. Visually settles cleanly at 260 dp. |
| 22.A.3 | **Drop the per-row `AnimatedVisibility(expandHorizontally / shrinkHorizontally)`** on every row label — that's ~9 simultaneous layout-shifting animations contending with the parent width animation. Replace with static `if (showLabel) Text(...)` plus a single shared alpha driven from the parent width animation progress. | [AppSidebar.kt:415-419](packages/android/app/src/main/java/com/yancotv/android/ui/shell/AppSidebar.kt) | ✅ done | Sidebar expand renders one width animation + one shared alpha curve, not 10 concurrent layout passes. |
| 22.A.4 | **Hoist the accent-bar animation** to one instance keyed off `current` only; **switch row foreground from `animateColorAsState` to a plain ternary** — focus colour change at 10 ft doesn't need interpolation. | [AppSidebar.kt:325-338](packages/android/app/src/main/java/com/yancotv/android/ui/shell/AppSidebar.kt) | ✅ done | One accent-bar animation instance; row foreground colors swap instantly on focus change. |
| 22.A.5 | **Fix `OnNowTile` frozen clock** (MB-222). Lift `nowSec = remember { System.currentTimeMillis() / 1000 }` out of the per-tile composable; add a single `LaunchedEffect` ticking every 30 s in `HomeContent` and pass down as parameter. Mirrors `GuideScreen.kt:330-335`'s shape. | [HomeContent.kt:897](packages/android/app/src/main/java/com/yancotv/android/ui/shell/HomeContent.kt:897) | ✅ done | Programme progress bars on Home advance over time. Snapshot at t=0 vs t=30s shows different progress percentage. |

**Hands-on verification gate (Fire TV AFTDCT31):** open Home → press LEFT to land on sidebar collapsed → arrow up/down to a different section → press CENTER. Sidebar expands within ~200 ms (was ~370 ms). Repeat 3× to feel the consistency. Wait 60 s on Home with at least one favorited live channel: an On Now tile's progress bar must visibly advance.

### Slice 22.B — Polish (Sprint B)

Lower-leverage but real. Each item is independently shippable.

| # | Task | File:line | Status | DoD |
|---|---|---|---|---|
| 22.B.1 | **Skip the Settings tab focus-retry ladder for cheap tabs.** Today every tab click runs `withFrameNanos { } × 2` then `moveFocus(Right)`, then a one-frame retry, then a second `moveFocus(Right)`. Cheap tabs (Appearance, About, Shortcuts, Backup, Recordings, Parental) don't need it. Alternative: bind a `FocusRequester` to the first focusable in each tab body and `requestFocus()` directly. | [SettingsScreen.kt:184-202](packages/android/app/src/main/java/com/yancotv/android/ui/settings/SettingsScreen.kt) | ✅ done | Settings tab swap latency drops to ~16 ms for cheap tabs (was ~50 ms minimum). |
| 22.B.2 | **Collapse `HexSurface`'s 5 parallel springs into 2.** Today every focusable card runs scale + translate + elevation + shellBorder springs plus inner-fill colour flip. Collapse to one `animateFloatAsState` driving scale + translate + elevation via a derived value; keep border as a hard switch. | [HexSurface.kt:79-99](packages/android/app/src/main/java/com/yancotv/android/ui/components/HexSurface.kt) | ✅ done | Focus traversal across a rail of HexSurface tiles drops from 5 springs/tile to 2. |
| 22.B.3 | **Tween the Settings tab shadow elevation** so the 0 → 18 dp transition matches the smooth scale tween. Or drop to a static low value (8 dp selected, 0 unfocused). Current pop-in reads as "row scales smoothly, halo flashes". | [SettingsScreen.kt:433-438](packages/android/app/src/main/java/com/yancotv/android/ui/settings/SettingsScreen.kt) | ✅ done | Tab focus animation has consistent timing across scale + shadow. |
| 22.B.4 | **Cut hero cross-fade durations** in `HomeContent`'s `AnimatedContent` between hero slides — `tween(420)` fadeIn + `tween(280)` fadeOut → `tween(240)` / `tween(200)`. Or switch to `crossfade` (single shared opacity). | [HomeContent.kt:451-457](packages/android/app/src/main/java/com/yancotv/android/ui/shell/HomeContent.kt) | ✅ done | Hero swap feels snappier without losing the cross-fade. |
| 22.B.5 | **Debounce `CategoryRail` pill `LaunchedEffect(focused) { onFocused() }`** by 100 ms inside `BrowseSection.onSelect` so D-pad arrow-spam scrolling pills doesn't churn the StateFlow + DB query for every pill the focus passes through. | [CategoryRail.kt:343, 366-374](packages/android/app/src/main/java/com/yancotv/android/ui/shell/CategoryRail.kt) | ✅ done | Rapid arrow-key traversal across 10 pills triggers 1 commit at the end, not 10 mid-traversal. |
| 22.B.6 | **Remove the `remember(index)` wrapper on `Modifier.wheelItemTransform(...)`** in 3 rails — the modifier returns a stable lambda-driven `graphicsLayer`; the `remember` adds nothing and closes over `listState` confusingly. Code-hygiene only. | [HomeContent.kt:715, 747, 779](packages/android/app/src/main/java/com/yancotv/android/ui/shell/HomeContent.kt) | ✅ done | Three line removals; no behaviour change. |
| 22.B.7 | **Tighten hero backdrop debounce** from 300 ms to 180 ms in `FeatureHero` — the AUTO_PREVIEW_DEBOUNCE_MS=400 ms already gates audio; the image swap can be quicker. | [FeatureHero.kt:155-159](packages/android/app/src/main/java/com/yancotv/android/ui/shell/FeatureHero.kt) | ✅ done | Hero image refreshes ~120 ms faster as user moves through the rail. |

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
| 23.C.1 | **`PlaybackController.persistResumePoint` regression suite.** Fake `WatchHistoryRepository` capturing `upsert` calls; drive the controller through the full transition matrix and assert the contract: episode flow writes (seriesId, episodeId), movie writes itemId, LIVE flow writes nothing, `_rec_` prefix writes nothing, `< 5L` minimum guard holds. Also covers `applyExternalSubtitle` resume-after-subtitle-load: capture position → swap → seek to captured offset. | [PlaybackController.kt:815](packages/android/app/src/main/java/com/yancotv/android/player/PlaybackController.kt) | ✅ done | At least 6 test methods covering: episode persist, movie persist, LIVE skip, `_rec_` skip, `< 5L` guard, subtitle-swap resume. New file `:app/src/test/.../PlaybackControllerPersistResumePointTest.kt`. |
| 23.C.2 | **`BulkContentWriter.abortSource` cross-source FK survival.** Seed source A (with favorite + history) + source B; force `writeM3uChunk` to throw on B mid-chunk; assert source A's favorites + history intact, FK back ON afterwards (write a content row, delete it, observe cascade fires). Same family as MB-220 — guards against future refactors that might leave FK off across sources. | [BulkContentWriter.kt:196](packages/shared/src/commonMain/kotlin/com/yancotv/shared/sources/BulkContentWriter.kt) | ✅ done | Test in `BulkContentWriterTest.kt`. After abort: A's data intact, `PRAGMA foreign_keys` reads 1, cascade fires on a fresh `DELETE FROM content WHERE id = ?`. |
| 23.C.3 | **`FavoritesRepository` multi-list (MK.13.4) surface.** Zero tests today. Cover: `createList` returns a stable id and trims whitespace; `addToList` is idempotent on collision; `removeFromList` is list-scoped (doesn't touch other lists); `deleteList("default")` is a silent no-op (the `WHERE is_default = 0` guard); `deleteList(custom)` cascades to its members; `setListSortOrder` updates `updated_at`; `byListFlow` reactivity (`turbine`-style — collect, write from another coroutine, assert second emission). | [FavoritesRepository.kt:154-223](packages/shared/src/commonMain/kotlin/com/yancotv/shared/favorites/FavoritesRepository.kt) | ✅ done | At least 7 tests in `FavoritesRepositoryTest.kt`. The `deleteList("default") is no-op` test is the load-bearing one — guards against schema changes that might drop the guard. |

### Slice 23.D — High + Medium tests (Sprint D)

Each row a single commit. Order doesn't matter strongly; pick by which surface you next touch.

| # | Task | Surface | Status | DoD |
|---|---|---|---|---|
| 23.D.1 | **`BulkContentWriter.finishSource` failure path.** Inject a driver wrapper that throws on `INSERT INTO content_fts SELECT…`; assert the catch block re-creates the trigger + re-enables FK + favorites for live content unchanged + `PRAGMA foreign_keys = 1` afterwards. | `BulkContentWriter.kt:148` | ✅ done | One test asserting all four post-conditions on the catch path. |
| 23.D.2 | **`SourceSyncCoordinator.start()` re-entrancy.** Pure unit on the coordinator with a fake repo whose `syncSource` flow stays open; second `start()` is a no-op + only one `repo.syncSource` invocation observed. Currently rejected by `if (_state.value != null)` but unpinned. | `SourceSyncCoordinator.kt` | ✅ done | Test with two rapid `start()` calls; assert second returns early, repo invoked once. |
| 23.D.3 | **`SourceRepository.syncSource` cancellation mid-flight.** Start syncSource, collect a few progress emits, cancel scope; assert `bulk.abortSource()` ran, `PRAGMA foreign_keys` is back ON, no partial content rows for that source, favorites for OTHER sources untouched. | `SourceRepository.kt` | ✅ done | One integration test in `SourceRepositoryTest.kt`. |
| 23.D.4 | **`WatchHistoryRepository.recent` ignores stray episode rows.** Insert an episode row with `content_id` pointing at a non-existent series; call `recent()`; assert empty list returned without exception. (Pre-MB-220 this scenario could happen post-CASCADE; post-fix it shouldn't, but defending the join is cheap.) | `WatchHistoryRepository.kt` | ✅ done | One test in `WatchHistoryRepositoryTest.kt`. |
| 23.D.5 | **EPG re-sync vs `recording_schedules.programme_id` FK SET NULL.** Plan originally said "reminders FK" but `reminders` has no FK on `programme_id` (it's a plain TEXT). The actual SET NULL contract is on `recording_schedules.programme_id` — verify a destructive EPG re-write nulls out the link without cascade-deleting the schedule. ✅ Shipped `2052174`. | `BulkEpgWriterTest.kt` | ✅ done | Test seeds programme P, schedule referencing P, runs replaceAll with empty programme set; asserts schedule survives + programme_id is NULL. |
| 23.D.6 | **Schema migration v8 → v9 dedicated test.** Today `Stage2MigrationTest` runs v3 → current as one bundle; v9's `auto_sync_on_start` ride-alongs aren't pinned. Seed v8 fixture with rows lacking the column; migrate to v9; assert column exists with default 0; query `WHERE auto_sync_on_start = 1` returns 0 rows; insert a row with `auto_sync_on_start = 1`; query returns it. | `MigrationTest.kt` (or new file) | ✅ done | One test class focused on the v8→v9 hop. |
| 23.D.7 | **`RecordingScheduleRepository` `schedule.recording_id` FK SET NULL.** Insert recording R, schedule S referencing R, delete R via `recordingsQueries.deleteById`, assert `schedules.selectById(S).recording_id == null`. Pins MB-211's deferred dead-FK contract — the column is currently dead but the FK is latent. | `RecordingScheduleRepositoryTest.kt` | ✅ done | One test pinning the SET NULL behaviour. |

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

## MK.24 — Audit follow-ups — planned 2026-04-28 (red-teamed revision)

**Why now.** The Sprint A/B/C/D verification audits surfaced 8 follow-up items that were not in the original sprint scopes. The user wants all of them addressed but explicitly across **multiple sessions** to keep each session's working set small (the 29-bug MK.8 commit and the MB-220-hidden-by-MB-223 incident are the canonical examples of what large-bundle, single-session work produces). This milestone splits the follow-ups into three small sprints — E, F, G — each independently mergeable and each testable on Fire TV before the next starts.

> **Sprint H (MB filings) shipped 2026-04-28** in the same session this plan was written. MB-225 / MB-226 / MB-227 / MB-228 are filed `Open · planned`. No remaining work in H.

### Sprint scope ground rules

- **Strict one-sprint-per-session for G.1 and G.2.** Highest blast radius (DB layer + schema). Do not chain.
- **Soft one-sprint-per-session for E and F.** Both are isolated, low-blast-radius (test-only / UI-only). Bundling E + F in one session is acceptable if the session has the runway; if E surfaces unexpected scope (see read-the-file-first below), stop at E.
- **One commit per item where possible.** Bundle only when items share a single file edit and a single test file edit.
- **Each sprint ends with `:shared:testDebugUnitTest` + `:app:testDebugUnitTest` + `:app:installDebug` green.** No rolling debt.
- **Do not deviate.** If something else looks worth fixing mid-sprint, file it as MK.24.X (or a new MB-*) and keep going. The rule that produced 29 bugs in MK.8 was "while I'm in here, also...".
- **Read-the-file-first gate** (E.3, E.4, F.1, G.2 all have unverified assumptions baked into their plan-text — see per-task notes below). Open the source file at sprint start, confirm the assumption, then code. If the assumption is wrong, update this plan in the same session before sprinting.

### Slice 24.E — Test gap closures (Sprint E)

Two confirmed test gaps + two items pending a read-the-file-first check that may shrink, retarget, or downgrade.

| # | Task | Surface | Status | DoD |
|---|---|---|---|---|
| 24.E.1 | **D.3 mid-chunk cancellation test.** Strengthens the existing `SourceRepository.syncSource cancellation` test by asserting cancellation lands *during* a chunk emit (not just before/after). | `SourceRepositoryTest.kt` | ✅ done | `2e0430e` — one new test (`cancelling syncSource after first chunk written runs abortSource and restores FK`) feeds a 600-entry M3U so the chunk loop emits its first WRITING progress, then cancels via CompletableDeferred + outside-job pattern. Assertions: source A's favorite intact, FK back ON (cascade-fire probe), source B wiped by abortSource. A `sawWriting` invariant guards against this becoming a duplicate of D.3. |
| 24.E.2 | **D.2 lifecycle teardown verification.** Verify `_state.value` returns to null on BOTH success AND failure paths, *and* a fresh `start()` afterwards is observed by the fake repo (one repo invocation, not zero). | `SourceSyncCoordinatorTest.kt` | ✅ done | `a71c8ab` — replaced the vestigial "start after previous run completes is allowed" placeholder (body never actually called start a second time) with two real tests: `start completed then restarted observes second invocation and clears state between runs` (DONE path via `flowOf(DONE)`) and `start failed then restarted clears state and allows second invocation` (exception path via `flow { throw }`). Both assert `_state.value == null` after teardown + invocation count goes 1 → 2 across two start() calls. |
| 24.E.3 | **`PlaybackController` two-tap no-op test — pure-function extraction.** Read-the-file-first gate confirmed the guard exists at BOTH layers: call-site (per skill checklist) AND controller-level defense-in-depth (`PlaybackController.play` line 470 + `play(episode)` line 503). Controller's KDoc explicitly documents the same-id no-op. Extract pure decision (analog to MK.23.C.1's `resumePointDecision`) so it's testable without standing up an ExoPlayer. | `PlaybackController.kt` + new `PlayLaunchDecisionTest.kt` | ✅ done | `ae56b4d` — extracted `playLaunchDecision(list, startIndex, currentId)` + `episodeLaunchDecision(episode, currentId)` returning `Reject` / `SameTarget` / `NewTarget`. Production `play()` and `play(episode)` `when`-branch on the decision; side effects (queue swap, persistResumePoint, loadCurrent) live at the call site. 14 test cases covering all branches. |
| 24.E.4 | **`reminders.programme_id` design pin — schema comment, not test.** Read-the-file-first gate confirmed: `BulkEpgWriter` doesn't touch reminders by construction (no FK = no cascade), and `selectByProgrammeId` / `deleteByProgrammeId` are explicit user-action queries, not implicit triggers. A test that can only fail when the schema is already broken is low-value. Ship a load-bearing schema comment instead. | `Reminders.sq` | ✅ done | `d390eff` — added a multi-line comment block on `CREATE TABLE reminders` documenting why the column is deliberately not FK (reminder fires must outlive provider programme-id churn; UI falls back on (channel_tvg_id, title, start_time) when the programme row is gone), with the `MK.24.E.4 + MB-226` reference line a future "tidy up" refactor must explicitly delete to add the FK. |

**Estimate (final):** ~2.5 h. E.1 ~45 min, E.2 ~30 min, E.3 ~1 h (incl. pure-function refactor), E.4 ~15 min. Within the 2–4 h band on the red-teamed revision.

### Slice 24.F — Motion polish (Sprint F)

| # | Task | Surface | Status | DoD |
|---|---|---|---|---|
| 24.F.1 | **`CategoryRail` debounce stable-lambda wrap — verified non-bug.** Read `CategoryRail.kt` (line 348-353) + parents (`BrowseSection.kt:211`, `GuideScreen.kt:442`). The hypothesized stale-closure problem doesn't manifest here: both `onSelect` callers write to a stable `MutableState` reference (`onSelect = { selectedGroup = it }`), so even though fresh-lambda-per-composition wraps a stale closure, the captured `MutableState` delegate is the same instance and the captured `group` value is stable per-key in `items(groups, key = { it })`. Wrapping with `rememberUpdatedState` would be premature defensive coding with no observable benefit. | `CategoryRail.kt` + parents | ✅ done · skipped | No code change. Plan-text hypothesis falsified by file read. |
| 24.F.2 | **Re-audit motion polish backlog — no items found.** Spawned an Explore-agent audit over `HomeScreen.kt`, `HomeContent.kt`, `AppSidebar.kt`, `BrowseSection.kt`, `CategoryRail.kt`, `HexSurface.kt`. Audit confirmed every MK.22.A/B fix is correctly in place: `withFrameNanos` not `delay(120)`, sidebar width `tween(180, FastOutSlowInEasing)` not spring, single shared `labelAlpha` not per-row `AnimatedVisibility`, ticking `nowSec` LaunchedEffect not stale `remember { ... }`, hero crossfade `tween(240/200)` not 420/280, HexSurface single progress animation not 4N concurrent springs, `CategoryRail` 100ms focus debounce. The "6 P2 items the original audit listed" claim in the plan-text was stale carry-over from a pre-MK.22.A/B audit summary; those items shipped in MK.22.A/B. | various | ✅ done · no items | No code change. Files are production-ready post-MK.22. |

**Estimate (final):** F closed by audit + read-the-file gate, ~30 min total. Zero code changes shipped — exactly what the read-the-file-first rule is for. Saved 1–2 h of speculative wrapping.

### Slice 24.G — DB hardening (Sprint G)

The two test gaps `MK.23 — Out of scope` deferred. Both are higher-cost than Sprint E items because they need richer test scaffolding. **G.1 and G.2 each need their own session** (one in, one out). Strict one-per-session rule.

| # | Task | Surface | Status | DoD |
|---|---|---|---|---|
| 24.G.1 | **Per-migration isolation tests for `3.sqm` … `7.sqm`.** Today `Stage2MigrationTest` exercises v3 → current as one bundle; `MigrationTest` covers v2 → v3 + v9 → v10 in isolation. The middle hops were unguarded. | `MigrationTest.kt` (5 test methods + cumulative schema helpers) | ✅ done | `bf12d65`. 5 tests added: `migrationV3ToV4AddsRecordingSchedulesAndRecordingsFormat`, `migrationV4ToV5SeedsDefaultListAndBackfillsLegacyFavoritesListId` (load-bearing — 2-favorites fixture catches "first row only" backfill bugs), `migrationV5ToV6AddsSourcesRefererAsNullForExistingRows`, `migrationV6ToV7AddsSourcesEpgPriorityDefaultingToZero`, `migrationV7ToV8CreatesBackupMetadataTable`. Each test uses a hand-crafted source-version schema fixture; some reads use direct driver SQL because the generated query API expects the CURRENT (v10) shape (e.g. `recording_schedules.selectAll` expects `series_key`). 582 LOC added; ~1.5 h actual (well under the 4–6 h estimate because the existing v9→v10 + Stage2 tests had reusable fixture patterns). **8.sqm not isolated** — its only addition (`series_key`) is already implicitly covered by `Stage2MigrationTest` reaching the current schema; file as follow-up if a regression there matters. Closes MB-227. |
| 24.G.2 | **DB driver corruption-recovery integration test.** Read-the-file gate confirmed `DatabaseFactory.android.kt` is heavily Android-bound (`Context.getDatabasePath`, `AndroidSqliteDriver`, `androidx.sqlite.db.SupportSQLiteDatabase`) — full end-to-end with the production factory needs Robolectric (rejected per plan). Shipped pure-function extraction + integration test at the JVM seam instead. | `DatabaseFactory.android.kt` (refactor) + `DatabaseRecoveryDecision.kt` (new) + `DatabaseRecoveryTest.kt` (new) | ✅ done | `92bfafc`. Pure functions: `decideRecoveryAction(saved, currentSchemaVersion): RecoveryAction` (sealed FreshOnly / Restore / RefuseRestore) + `dbArtifactPaths(dbFile): List<File>`. Production `recoverWithFreshDb` `when`-branches on the decision; `deleteDbArtifacts` delegates to `dbArtifactPaths`. 9 tests: 5 decision-table cases, 2 sidecar enumeration, 1 load-bearing integration test (write garbage bytes → JdbcSqliteDriver fails to open → cleanup deletes all 4 artefacts → fresh DB at same path queryable + insert+select round-trips), 1 clean-disk no-op test. **Deferred (Robolectric only):** end-to-end through production `DatabaseFactory.create()` with a real Android `Context`. The decision-table + integration tests pin all the load-bearing logic; production wiring is a thin adapter. Closes MB-228. ~2 h actual vs 3–6 h estimate (read-the-file gate ruled out the bigger refactor early). |

**Estimate (red-teamed):** G.1 = 4–6 h (fixture authoring is the cost driver, ~45–60 min per hop × 5). G.2 = 3–6 h (3–4 h if pure-Java `DatabaseFactory`; 5–6 h if Android-API refactor needed). **Total: 7–12 h. Run as two separate sessions.**

### Sprint sequencing (revised 2026-04-28)

✅ **All MK.24 sprints complete 2026-04-28.** Order shipped: H → E → I → F → G.1 → G.2. Remaining items are 24.I.X.1–6 follow-ups (heap-pressure soak instrumentation — picked up only if F3 Sentry probe fires in the wild) and MB-229 retest (blocked on MB-230 soak verification).

If a session ends mid-sprint (build red, hands-on regression found, scope creep, plan-text assumption falsified), ship what's green, document the cut line in this plan, and resume in the next session.

### Cost summary (red-teamed)

| Sprint | Items | Estimate | Net production code touched |
|---|---|---|---|
| E | 4 tests (E.3 + E.4 may shrink to 0) | 2–4 h | 0 lines (tests + 0–1 schema comment) |
| F | 1 conditional + ≤3 from re-audit | 1–2 h | 0–6 small UI files |
| G.1 | 5 migration tests | 4–6 h | 0 lines (tests only) |
| G.2 | 1 refactor + 1 integration test (scope conditional on read) | 3–6 h | ~30–80 lines `DatabaseFactory.android.kt` + new test file |
| **Total** | **8 audit findings (some may shrink)** | **12–18 h across 3–4 sessions** | **<100 lines production code if G.2 stays small; up to ~200 if G.2 refactor expands** |

**Honest framing:** if E.3 drops, E.4 drops, F.1 is a non-bug, and G.2 stays JVM-only, total comes in at the low end (~10 h). If all the gates expand scope (E.3 needs extraction, F.1 confirmed, G.2 needs refactor), high end (~18 h). Plan for the middle (~14 h) and adjust per-sprint based on the file-read gate outcome.

### Slice 24.I — OOM / heap-pressure root-cause investigation (Sprint I, refocused 2026-04-28)

**Original scope was MB-229 (recording shows "recording AND failed").** Step I.1 + I.2 (device-state capture + DB query) immediately upgraded the picture: the recording subsystem isn't the bug. The YancoTV process is hitting heap exhaustion (376/384 MB pinned at 98%, 101 consecutive `GC freed 0` events, `am dumpheap` can't even complete) and the recording UI desync is a downstream symptom — every Compose flow and DB write and file write stalls when the GC can't make progress. Filed as **MB-230 Critical**; MB-229 demoted to "blocked on MB-230, retest after."

**Why this is bigger than MB-229's recording-subsystem scope:** the same heap-pressure failure mode silently breaks every other feature too. Scheduled recordings, source sync, EPG refresh, settings writes — anything that needs an allocation under load will stall in the same window. Fixing this lifts the floor for every other feature.

**Strict order — same gate-before-fix discipline:**

| # | Step | Status | DoD |
|---|---|---|---|
| 24.I.1 | **Initial state capture (DONE).** Pulled DB twice (pre and during recording attempt — both 314 MB), captured PID-filtered logcat showing the GC thrash, attempted `am dumpheap` (returned 0 bytes — process unresponsive). Force-stopped the zombie process. Confirmed: recordings + recording_schedules tables empty despite RecordingService claiming 12+ min foreground runtime; output file 0 bytes. Forensic data at `D:/tmp/mb-229-capture/`. | ✅ done | Captures saved; root cause re-classified from recording-subsystem to heap-pressure. |
| 24.I.2 | **Baseline a clean process.** Force-stop, relaunch the app (Home only — don't touch any feature). Capture `dumpsys meminfo com.yancotv.android` immediately. Note Java Heap, Native Heap, Graphics, Code, Total PSS. This is the "empty room" measurement — anything above this on a healthy app is feature work, anything pathological is leak. | planned | Baseline numbers logged in this plan + saved as `D:/tmp/mb-229-capture/meminfo-baseline.txt`. |
| 24.I.3 | **Walk the suspected surfaces and meminfo each.** One screen at a time: Home → Browse Live → Browse Movies → Guide → Sources → Settings. Wait 5 s after each navigation, capture `dumpsys meminfo`, save with the screen name. Identify the inflection: which screen takes Java Heap from baseline to >200 MB? More than one? Which one's hottest? **Do NOT touch playback yet** — adding ExoPlayer + decoder allocations confounds the leak signal. | planned · gated on I.2 | Per-screen meminfo files + a one-paragraph diagnosis: "X screen jumps Y MB; suspect Z." |
| 24.I.4 | **Capture baseline hprof while responsive.** Pulled `am dumpheap <pid>` while Java Heap was at 13 MB (clean post-launch state). Saved as `/d/tmp/mb-229-capture/yancotv-baseline.hprof` (32 MB Android-format hprof, 33 MB after `hprof-conv`). NOTE: this is a **healthy** dump, not the broken state. We can't reproduce the 376 MB heap in a single session — it's a slow accumulation over hours of use. The baseline is useful as a reference point for a future high-heap dump diff but not actionable on its own. | ✅ done | Baseline hprof saved. Dominator analysis at this size shows nothing surprising; deferred until we capture a high-heap dump (see "Sprint I follow-ups" below). |
| 24.I.5 | **Hypothesis written from observed evidence.** No single dominant retention root — this is a "1000 cuts" leak. Evidence: (a) **idle Java heap stable at 12 MB for 90s** = no background-timer leak; (b) **all main screens 18-22 MB Java** = no single-screen retention bug, pagination is correctly scoped; (c) **30 navigation cycles add +9 MB Java + 17 MB Native + 13 MB Graphics** = slow per-interaction accumulation; (d) **30s idle reclaims most Java growth** = GC works under normal pressure; (e) **`onTrimMemory(RUNNING_CRITICAL)` releases -0.5 MB Java, Graphics + Native unchanged** = app has no `onTrimMemory` hook and Coil bitmap cache doesn't honor memory-pressure signals; (f) **`AndroidEpgImporter` streams to temp file + XmlPullParser + 500-row flush** = EPG sync is well-designed, NOT the leak; (g) **content + EPG queries all use `*Paged` variants** = DB layer is NOT the leak; (h) **`OkHttpClient()` constructed fresh in `YancoImageLoader.kt:41`** = duplicate OkHttp instance (separate from PlaybackController's) with its own connection pool, dispatcher, DNS cache, TLS sessions. **Diagnosis:** over a long session, slow per-navigation accumulation + Coil bitmap cache that doesn't release on pressure + duplicate OkHttp infrastructure compound past the 384 MB heap budget. Once Java heap >95%, GC runs back-to-back freeing 0 bytes (we observed 101 such events on the user's broken process), every coroutine that needs an allocation stalls, and the recording UI shows a frozen "recording AND failed" state because the reactive Compose flows can't update. | ✅ done | Hypothesis committed. Pinned by per-screen meminfo + idle test + trim-memory test data in `/d/tmp/mb-229-capture/`. |
| 24.I.6 | **Fix scope: 3 fixes (F1/F2/F3) targeting the confirmed gaps.** **F1 — Add `onTrimMemory(int level)` override in `YancoApp`** that clears half the Coil memory cache on `TRIM_MEMORY_RUNNING_LOW` and all of it on `RUNNING_CRITICAL` / `COMPLETE`. Closes the directly-observed gap where `RUNNING_CRITICAL` released only 0.5 MB. Cost ~30 min, high confidence. **F2 — Reuse a single shared `OkHttpClient` for Coil** instead of `OkHttpClient()` constructed fresh in `YancoImageLoader.kt:41`. Eliminates duplicate connection pool, dispatcher thread executor, DNS cache, TLS session cache. Cost ~30 min, medium confidence (clean win, but expected savings only ~5-10 MB). **F3 — Heap-watermark Sentry breadcrumb** running on a coroutine in `YancoApp` polling `Runtime.getRuntime().totalMemory() / maxMemory()` every 60 s. When > 75% emit a breadcrumb with `dumpsys meminfo`-equivalent snapshot; when > 90% emit a Sentry event. Makes future heap-pressure occurrences self-reporting — if F1+F2 don't close MB-230, F3 will catch the next occurrence with rich state. Cost ~45 min, pure observability (can't break anything). | ✅ done | Scope committed. Total Sprint I.7 implementation cost ~1.5-2 h. |
| 24.I.7 | **Ship F1 + F2 + F3.** Shipped 2026-04-28 in 2 commits (F1+F3 bundled in YancoApp because they share the file end-of-class; F2 standalone refactor across 4 files). **F1 verified on Fire TV:** `am send-trim-memory RUNNING_CRITICAL` releases **-3 MB Java** post-fix (vs pre-fix -0.5 MB — **6× the Java reclaim**). Graphics held as expected (decoded bitmap native references stay until Compose releases them on next recomposition). **F2 verified by build:** shared `OkHttpClient` from Koin DI threaded through `AndroidEpgImporter` (use-case timeouts via `newBuilder()`) + `YancoImageLoader` (passes shared instance to `OkHttpNetworkFetcherFactory`). Eliminates the duplicate connection pool, dispatcher thread executor, DNS cache, TLS session cache between EPG sync and Coil. **F3 deployed:** 60s heap-watermark probe with two-tier hysteresis (WATCH at >75%, ALERT at >90%). Cannot verify in a single session because heap is at 12-19 MB (well below the WATCH threshold); will fire in the wild on the next long session. **Soak-test** + scheduled-recording retest deferred to user's regular use. | ✅ done | Commits `f9d966f` (F2) + `a71edfe` (F1+F3). Test suites green. APK on Fire TV. Trim-test confirms -3 MB Java drop. Heap-watermark probe armed for first wild occurrence. |

### Sprint I follow-ups (deferred, NOT shipping in I.7)

These are real items that were deliberately scoped out of the first fix iteration. Pick up if F1+F2+F3 don't close MB-230 (= heap still climbs past 75% within a normal session window) OR if F3's Sentry probe surfaces a specific high-heap pattern.

| # | Item | Why deferred | When to revisit |
|---|---|---|---|
| 24.I.X.1 | **Capture a high-heap hprof + dominator analysis.** Need to reproduce the 376 MB state first — can't do that in a single session, requires hours of normal use. F3's Sentry probe makes this easier: when the next heap-pressure event hits, the breadcrumb fires and we can prompt the user to dump heap before force-stop. | A single session can't push heap past ~30 MB; the baseline dump at 13 MB shows nothing surprising. Without a high-heap dump the dominator analysis is speculative. | After F1+F2+F3 ship: if F3 fires in the wild, ask the user to capture an hprof at that moment, then run dominator analysis with shark-cli or Android Studio Profiler. |
| 24.I.X.2 | **Switch Coil's bitmap config to `RGB_565` for logos** (not posters / hero images). Halves bitmap memory for ~80% of cached images. | Requires visual-quality review on Fire TV — `RGB_565` shows banding on smooth gradients. Logos are usually flat-color, posters are not, so we'd need a per-call-site policy. Cost ~1 h to wire + 30 min visual check. | If F1's `onTrimMemory` clearing doesn't sufficiently bound Graphics under sustained scroll. |
| 24.I.X.3 | **EPG-sync retention deep-dive.** `AndroidEpgImporter` is well-designed (streams to temp file, XmlPullParser, flushes 500 at a time) but the surrounding worker plumbing (`EpgSyncWorker`) hasn't been audited for retained refs. WorkManager + Koin + coroutine scopes can hold refs across job boundaries. | Speculative — no direct evidence EPG sync is the leak. EPG syncs run periodically via WorkManager and could plausibly retain a parsed-list ref between cycles, but we'd need to capture a heap dump immediately post-sync to confirm. | If F3's Sentry breadcrumbs show heap spikes that correlate with EPG sync run timestamps. |
| 24.I.X.4 | **MB-229 recording-subsystem retest** (the original Sprint I scope). Once heap is healthy, schedule a recording and confirm UI shows a single coherent state. If the dual-state UI persists, escalate to a real recording-subsystem race (the original 4-hypothesis path: schedule/recording row divergence, stale UI subscription, MB-208 family resurfacing, MB-219 boot-recovery interaction). | Blocked on MB-230 being closed. The dual-state UI was a downstream symptom of heap pressure; healthy heap likely resolves it transitively. | After F1+F2+F3 ship and Fire TV soak-test confirms bounded heap. |
| 24.I.X.5 | **Native heap + connection-pool audit (Ktor + OkHttp + ExoPlayer).** Three separate HTTP stacks today: Ktor (shared/), OkHttp in PlaybackController, OkHttp in Coil (F2 deduplicates the last one but Ktor is still separate). Each has its own connection pool + thread executor + DNS cache. Native heap grew 17 MB / 30 cycles in the stress test — likely OkHttp-side. | F2 collapses Coil + PlaybackController OkHttp; the Ktor side is a bigger refactor (touches shared/ and platform abstractions). Defer until evidence shows it's load-bearing for MB-230. | If post-F2 the Native heap still grows on every navigation cycle. |
| 24.I.X.6 | **Compose recomposition retention audit.** Long-lived screens (HomeShell, BrowseShell) may hold derived-state refs across navigation that don't get GC'd until the screen leaves the backstack. Worth profiling with the Compose Layout Inspector + Allocation Tracker. | Speculative — we observed +9 MB Java per 30 nav cycles, partially recoverable. Could be Compose retention or could be normal allocator churn. Without a high-heap dump (24.I.X.1) we can't tell. | After 24.I.X.1 captures a high-heap dump that shows Compose-related dominators. |

**Estimate (honest):** I.7 = ~1.5–2 h (F1+F2+F3 implementation + 3 commits + verify). Total Sprint I once committed: ~4 h actual (1 h investigation + scoping + 3 h docs + ship). Within original 3–8 h band on the low end because the diagnosis was clearer than feared (the missing `onTrimMemory` hook was directly observable in the dumpsys experiment).

**Out of scope for Sprint I (becomes new MK if pursued):** MB-212 fix. The deferral-reason-stale flag still applies; pick up alongside the next recording-subsystem touch (likely after I.X.4 retest).

---

### Out of scope for MK.24 (file as new MK if pursued)

- **Compose-test cascade-nav smoke automation** — still defers to manual cascade-nav smoke per the skill checklist.
- **Robolectric harness adoption** — Sprint G.2 was scoped specifically to *not* require it. If the integration test ergonomics push that decision, file as a separate MK and bring it into the next planning round.
- **CI pipeline (`MB-224`)** — scheduled at Stage 5.7. Sprints E/F/G all run locally with `:shared:testDebugUnitTest` + `:app:testDebugUnitTest`.

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
- DLNA / UPnP (MK.18.4) — **DROPPED PERMANENTLY** (re-confirmed 2026-04-25; reconfirmed by 2026-06-15 red-team)
- Cross-device handoff (MK.18.5) — ~~**DEFERRED post-v1 study** (2026-04-25)~~ → **REVIVED 2026-06-15 → MK.26 Track A (primary)**
- Custom hex accent (MK.16.3) — 4 presets only
- Chromecast (MK.11.3 / MK.18.3) — ~~**DROPPED PERMANENTLY** (2026-04-25)~~ → **REVIVED 2026-06-15 → MK.26 Track B (secondary)**
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
| MK.iOS.0-pre | ✅ **Shipped 2026-08-10** — unrot the declared iOS targets: write the two missing `iosMain` actuals, add a compile gate so they can't rot again. Detail below. |
| MK.iOS.0 | Xcode project scaffold, Kotlin `shared` framework imported, SwiftUI "Hello" screen |
| MK.iOS.1 | SwiftUI shell — adaptive for iPhone vs iPad (split view on iPad) |
| MK.iOS.2 | Sources + credentials via iOS Keychain |
| MK.iOS.3 | Playback — AVPlayer default, VLCKit fallback for DTS/TrueHD |
| MK.iOS.4 | EPG / catchup / favorites / search reusing shared KMP |
| MK.iOS.5 | PIP + AirPlay + Chromecast |
| MK.iOS.6 | App Store submission |

### MK.iOS.0-pre — iOS target rot + detector (shipped 2026-08-10)

**What was broken.** `:shared` has declared `iosX64` / `iosArm64` / `iosSimulatorArm64` since MK.0.1, but *nothing ever compiled them* — the Android build doesn't, and CI runs on `ubuntu-latest` where Kotlin/Native can't build Apple targets. So when MK.19.8 added `BackupCipher` and `sha256Hex` as `expect` in `commonMain`, the matching `iosMain` actuals were never written. A missing `actual` is a hard compile error **on that target only**, which is exactly why an Android-green build and a green CI never surfaced it. `iosMain` sat at 3 files / 37 lines against 6 `expect` declarations.

**What shipped.**

| # | Change | Notes |
|---|---|---|
| MK.iOS.0-pre.1 | [`Sha256.ios.kt`](packages/shared/src/iosMain/kotlin/com/yancotv/shared/backup/Sha256.ios.kt) — `CC_SHA256` | Empty input takes an explicit NULL-pointer path; `Pinned.addressOf(0)` throws on an empty `ByteArray` and empty-records backups are a real case |
| MK.iOS.0-pre.2 | [`BackupCipher.ios.kt`](packages/shared/src/iosMain/kotlin/com/yancotv/shared/backup/BackupCipher.ios.kt) — `CCKeyDerivationPBKDF` + one-shot GCM + `SecRandomCopyBytes` | **Not CryptoKit** — it is Swift-only and Kotlin/Native imports Objective-C/C only. Uses `CCCryptorGCMOneshotEncrypt`/`Decrypt`, not the deprecated `CCCryptorGCM`/`…Final` (documented auth-tag bugs) |
| MK.iOS.0-pre.3 | [`BackupCipherParityTest.kt`](packages/shared/src/commonTest/kotlin/com/yancotv/shared/backup/BackupCipherParityTest.kt) — 20 tests in `commonTest` | Compiling proves the bindings *resolve*, not that they're *correct*. A silent KDF divergence makes a password-protected backup unrestorable across platforms |
| MK.iOS.0-pre.4 | `:shared:checkIosCompile` + `iosTest` source set | Compiles main + test klibs for all three targets. Not wired into `check` — that would break Windows dev builds and the ubuntu CI job. Fails with a readable message off-Mac |
| MK.iOS.0-pre.5 | `ios` job on `macos-latest` in [android-tests.yml](.github/workflows/android-tests.yml) | The detector. Runs `checkIosCompile`, then the parity suite on a simulator |

**Wire-format contract** (both platforms must agree byte for byte or cross-platform restore breaks): PBKDF2-HMAC-SHA256 over the **UTF-8** bytes of the password, 256-bit output; AES-256-GCM, 96-bit IV, 128-bit tag; hex of `iv(12) || ciphertext || tag(16)`, lowercase. Java's `Cipher.doFinal` returns `ciphertext||tag` as one blob, which is why `androidMain` writes `hex(iv) + hex(doFinal(…))` while `iosMain` concatenates three parts.

**Verification status — read this before trusting it.** The iOS sources have **never been compiled**; they were written on a Windows host where Kotlin/Native cannot build Apple targets. Unverified: whether `CCCryptorGCMOneshot*` is bound in Kotlin/Native's `platform.CoreCrypto` klib at all (it may sit in a private SPI header), the exact `CValuesRef` / `size_t` parameter typing, and whether `CC_SHA256_DIGEST_LENGTH` binds as `Int`. The failure mode is a **compile error on first Mac run, not silent bad ciphertext** — which is the point of MK.iOS.0-pre.4/.5. Also unverified: `--tests` filtering on a Kotlin/Native test task, and that `macos-latest` is arm64 (so `iosSimulatorArm64Test` is the right task). Expect a nudge on the first run.

What *was* verified, on JVM: 661 tests / 53 classes / 0 skipped / 0 failures on `--rerun-tasks`; a negative control (corrupt a vector → test fails → revert); `PBKDF2_C1` and `PBKDF2_C4096` match the **published** PBKDF2-HMAC-SHA256 vectors, so `androidMain` is confirmed standards-conformant rather than merely self-consistent; ktlint clean (it scans `iosMain` from Windows, so Linux CI already gates iOS style); `:shared:build` still green.

**Seam checked:** `BackupImporter` wraps both calls in `runCatching`, which catches `Throwable` — so Android's `AEADBadTagException` and iOS's `IllegalStateException` are handled identically. No divergence in the wrong-password fallback path.

### Correction to the 2026-04-20 sharing estimate

The decision log records "KMP shares ~60% of code vs two full ports." Measured 2026-08-10: `commonMain` is **13,635 lines against 52,778 in `packages/android/`** — roughly **20%**, not 60%.

Cause: hard rule 7 below says "ViewModels live in `shared/` exposing `StateFlow`." **There are zero ViewModels in the codebase.** UI state lives in `mutableStateOf` inside composables — 212 occurrences across 30 UI files, versus 7 files using `StateFlow`. That state logic is welded to Compose and cannot cross to SwiftUI.

In fairness the 20% understates reusability: a chunk of `packages/android/` is TV-specific (focus engine, coverflow shell, `androidx.tv.material`) that an iPhone/iPad app wouldn't want. But it cuts both ways — it means the iOS UI is a fresh SwiftUI design, not a port. Every new screen that puts state in `mutableStateOf` instead of a shared holder adds to the eventual iOS bill. Not retro-fixing this now; flagging it so MK.iOS.1 is scoped against the real number.

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

## MK.25 — Player UX innovation pass — planned 2026-05-04

**Why now.** Two user-reported bugs surfaced the broader gap: pressing RIGHT to skip 10s shows a "Tuning the stream" overlay (terminology lies on VOD; the overlay is for live tuner re-lock), and seeking has no feedback so the user can't tell if a press registered. While investigating, an audit confirmed the player ships with the **dock + chrome scaffolding** but missing every modern streaming-player affordance the user expects from YouTube TV / Netflix / Plex / Prime: no fast-forward modes, no seek flash, no buffered-range visualization, no episode context for series, no resume-from prompt, no autoplay countdown bumper, no skip-intro/credits, no scrub-mode, no preview thumbnails. Some of these are deferred MK.16.player.vod.* slices; most are greenfield.

This milestone consolidates them into a single planning thread so they can ship together, in priority order.

### Companion bug-fix commits (already shipped 2026-05-04, **before** MK.25 starts)

These are NOT MK.25 work — they're the bug-fix predecessors that motivated this plan. Listed here for the fresh chat's context.

| Commit | Fixes |
|---|---|
| `584ccca` | Episode resume reads `positionForEpisode` (was always 0). |
| `51f1e20` | Autoplay-next wired to `STATE_ENDED` + autoplay pref + `nextEpisodeAfter` lookup. |
| `c6874dd` (earlier) | Sub-panel parent-of navigation + subtitle external-sub state. |

The **autoplay countdown bumper** in this milestone (D.1) builds on `51f1e20`'s plumbing.

### Read-the-file-first gates (CRITICAL)

Before writing code in any slice, open the cited file and confirm the assumption — exactly the discipline that saved Sprint F. Specific gates:

- **A.1** — `PlayerActivity.kt:260–271` (the `STATE_BUFFERING` debounce). Confirm the trigger is content-type-agnostic; my plan-text assumes there's no `isLive` gate. If a gate already exists, A.1 is a non-bug.
- **A.2** — `PlayerActivity.kt:1842–1887` (RIGHT/LEFT seek branches). Confirm there's no existing flash UI being suppressed by the silent-seek path; if it exists, slice this is "wire it up" not "build it."
- **B.2** — `VodPlayerDock.kt`. Confirm the dock currently reads `cleanTitle` only; the UI doesn't already have an episode subtitle field that's just hidden. Audit said it doesn't, but verify before designing.
- **C.1** — Read `PlayerActivity.kt` for **any** existing long-press handling on LEFT/RIGHT (current `dispatchKeyEvent` long-press logic targets CENTER for the player options popup; there may be more). Conflict-check.
- **C.2** — Confirm DPAD UP isn't already handling chrome show / channel-zap-up. If it is, the scrub-mode entry needs a different key.
- **D.1** — The position-watcher timing. Confirm whether `Player.Listener` events suffice or if a periodic poll is needed (probably the latter — `onPositionDiscontinuity` only fires on seeks/transitions, not natural progress).
- **D.2** — User-pref schema. Confirm whether `PlaybackPrefs` has room for skip-intro flags or needs a schema bump.

### Slice scope ground rules

- **One slice per session for C.1, C.2, D.1, D.3.** Highest blast radius (key handling, motion, async UI). Do not chain.
- **Soft one-slice-per-session for A, B.x, C.4, D.2.** All isolated. Bundling A.1 + A.2 in one session is fine — that's ~45 min.
- **Each slice ends with `:app:installDebug` green on Fire TV.** Manual smoke per the slice's DoD.
- **Skip-the-cleverness rule.** Don't introduce a new abstraction layer unless a second consumer materializes. The dock has one consumer (PlayerActivity). Resist building a generic chrome state-machine.
- **One commit per item where possible.** Bundle only when a single edit touches a single file pair (e.g. ContentRepository + ContentRepositoryTest).

---

### Slice 25.A — Stop the bleeding (closes the user's reported pains)

**Why first.** Two specific complaints from the 2026-05-04 session: (1) "Tuning the stream" on every VOD seek, (2) silent seeks with no visual feedback. A.1 + A.2 ship together as one cohesive UX touch; ~1 commit.

| # | Task | Surface | Status | DoD |
|---|---|---|---|---|
| 25.A.1 | **Suppress "Tuning" overlay on seek-induced re-buffers.** Track `lastSeekAtMs` in PlayerActivity; in `onPlaybackStateChanged(STATE_BUFFERING)`, if `now - lastSeekAtMs < 1500ms`, **skip** the debounce-and-show. Real network stalls (no recent seek) still surface the overlay. The 1500ms window is large enough to swallow a typical keyframe re-align (~500ms) plus jitter, small enough that a real stall right after a seek surfaces within an acceptable window. **Red team:** simpler "gate to LIVE only" suppresses real VOD network stalls — rejected. The seek-window approach preserves the genuine-stall feedback. | `PlayerActivity.kt` (set lastSeekAtMs in the LEFT/RIGHT seek branches; check it in the listener) | planned | Manual: VOD seek → no "Tuning" overlay. Pause Wi-Fi mid-VOD playback for >2s → overlay appears as before. |
| 25.A.2 | **Seek flash overlay.** Transient `+10s` / `-10s` badge on the right/left edge for 600ms after a seek key press. Coalesces multi-press: if RIGHT is pressed 3× within 600ms, badge reads `+30s`, not three sequential `+10s`. Doesn't pull focus, doesn't show the dock — it's a non-blocking overlay. **Red team:** TV remotes spam keys; without coalescing the user sees the badge thrash. State: `seekFlashJob: Job` + `accumulatedSeekMs: Int` reset 600ms after the last press. **Red team #2:** new overlay shouldn't conflict with chrome auto-show; ensure z-order is above dock but below error/buffering chrome. | `PlayerActivity.kt` (compose-state-driven flash; new SeekFlashOverlay composable) | planned | Manual: single press shows `+10`; rapid 3 presses show `+30`; 600ms after last press, fade. No focus pulled. |

**Estimate:** A.1 ~20 min, A.2 ~45 min. **Total ~1.5 h, one commit.**

---

### Slice 25.B — Dock + chrome upgrade (information density)

Make the dock useful so the user has visibility without drilling into the options menu.

| # | Task | Surface | Status | DoD |
|---|---|---|---|---|
| 25.B.1 | **Time remaining + ends-at clock.** Dock label: `12:34 / 45:00 — Ends at 21:47`. Compute on the fly from `currentPosition` + `duration` + `System.currentTimeMillis() + (duration - currentPosition)`. Update on the existing per-second tick. | `VodPlayerDock.kt` | planned | VOD shows position / duration / wall-clock end. Live shows position / duration only (no wall-clock — would lie on indefinite live streams). |
| 25.B.2 | **Episode context for series.** When `controller.currentEpisode.value` is non-null, render `S01E02 — Episode Title` above the existing dock title. Series name stays in the existing title slot. **Red team:** today the dock reads `cleanTitle` which for episodes is the joined `Series — S01E02 — Title` string from `EpisodeInfo.toPlayable`. Need to render parts separately — read `currentEpisode.title` (the episode-only piece) + `seasonNumber` / `episodeNumber` instead of the joined cleanTitle. | `VodPlayerDock.kt` (read `controller.currentEpisode`) | planned | Series episode in the dock shows: `Show Name` (large), `S01E02 — Episode Title` (small kicker). Movies / live unchanged. |
| 25.B.3 | **Buffered-range visualization on the progress bar.** Render `Player.bufferedPosition` as a lighter band behind the playhead. **Red team:** `bufferedPosition` is `Long` ms; need to clamp to duration; for Live timeshifted streams the meaning differs (needs read-the-file-first on Live's progress-bar semantics — Live should probably keep the existing rendering). | `VodPlayerDock.kt` (progress bar paint) | planned | VOD progress bar visibly shows buffered-ahead band. Pause + wait → band grows; resume + scrub forward → band shrinks. |
| 25.B.4 | **Quick action buttons on the dock.** Single-press affordances for: subtitle toggle (cycles last-used / off, NOT a panel), audio cycle (next track), speed cycle (1× → 1.25× → 1.5× → 1× etc.). Promotes the most common controls out of the player options popup. **Red team:** these gestures already exist as LEFT/RIGHT cycles on the popup rows; this slice just exposes them as dock buttons. Don't duplicate the cycle logic — call into `cycleAudioTrack` / `cycleTextTrack` / `cycleSpeed` from `PlayerOptionsPanels.kt`. **Red team #2:** focus order on the dock matters; CENTER on a quick-action row must NOT toggle dock visibility. | `VodPlayerDock.kt` | planned | Three buttons render right of the transport controls. Each works on a single CENTER press. None reopens the options popup. |

**Estimate:** B.1 ~30 min, B.2 ~45 min, B.3 ~45 min, B.4 ~1.5 h. **Total ~3.5 h, 3–4 commits (B.1 + B.2 can bundle; B.3, B.4 standalone).**

---

### Slice 25.C — Real seek UX (the meatier work)

Fast-forward modes, scrub mode, position tooltip, resume prompt. Highest blast radius — strict one-slice-per-session.

| # | Task | Surface | Status | DoD |
|---|---|---|---|---|
| 25.C.1 | **Long-press LEFT/RIGHT = continuous fast-forward / rewind.** Hold RIGHT → seek +10s every 250ms; after 2s of holding, accelerate to +20s every 250ms; after 4s, +40s. Symmetric for LEFT. Release → exits FF mode. Visual indicator at top: `▶▶ 4×` (i.e. seeking at 4× real-time). **Red team:** original plan said "use `setPlaybackSpeed`" — that's WRONG. setPlaybackSpeed plays content at 4× speed with audio pitched up, not fast-forward. Real FF on TV is repeated jumps; that's what this slice does. **Red team #2:** the existing single-press +10 (A.1 / A.2) and this long-press behavior must coexist — short-press fires once on UP, long-press fires repeated jumps from DOWN until UP. Use `KeyEvent.repeatCount` or a hand-rolled job that starts on first DOWN and cancels on UP. | `PlayerActivity.kt` (key handler) + `VodPlayerChrome.kt` (FF-mode badge) | planned | Tap RIGHT once → +10s flash (A.2). Hold RIGHT 1s → continuous skip with `▶▶ 4×` badge. Release → resume normal. |
| 25.C.2 | **Scrub mode via DPAD UP.** UP = show chrome AND focus the progress bar (skipping the usual default-row focus). LEFT/RIGHT in scrub mode = fine seek (5s default; configurable). CENTER commits (no-op visually, just exits scrub). BACK cancels (returns to original position). The progress bar paints a "scrub head" distinct from the playhead while scrubbing. **Read-the-file-first gate:** confirm DPAD UP isn't currently handling something else. **Red team:** "BACK cancels by returning to original position" requires capturing `entryPosition` at scrub-mode start; the current player has no such state — needs a small state holder. | `PlayerActivity.kt` + `VodPlayerDock.kt` | planned | UP from no-chrome → chrome appears, progress bar focused. LEFT/RIGHT moves scrub head. CENTER commits. BACK reverts. Scrub head visible distinct from playhead while scrubbing. |
| 25.C.3 | **Position tooltip while scrubbing.** Float a `23:45` timestamp above the scrub head. Updates as the user moves it. Gated behind C.2 (scrub-mode is the only state with a scrub head). | `VodPlayerDock.kt` | planned | Tooltip renders above scrub head; updates with LEFT/RIGHT presses. |
| 25.C.4 | **Resume-from prompt.** On a fresh load with `resumePosition > 30s` AND `< duration - 30s`, render a non-blocking 5-second overlay: `Resume from 12:34 ✓ — Watch from start (DOWN)`. Default action (no input) is resume; pressing DOWN restarts at 0. Auto-fades after 5s. **Red team:** original plan said "5s prompt before playback starts" — that delays playback. This revision instead loads at the resume offset (current behavior, today's commit `584ccca`) and shows the overlay non-blocking; the user keeps watching while it's visible. | `PlayerActivity.kt` (loading-time overlay) | planned | Open VOD with saved offset >30s → overlay appears for 5s; pressing DOWN seeks to 0; otherwise fades. Open VOD without saved offset → no overlay. |

**Estimate:** C.1 ~2.5 h, C.2 ~3 h, C.3 ~45 min, C.4 ~1 h. **Total ~7 h, 4 commits.**

---

### Slice 25.D — Smart features (selective)

Greenfield. Ship the ones that match the user's priorities. D.3 is genuinely 5–8 h — defer unless explicitly asked.

| # | Task | Surface | Status | DoD |
|---|---|---|---|---|
| 25.D.1 | **Autoplay-next countdown bumper.** Last 15s of an episode (when `(duration - position) <= 15000`): render a bottom-right overlay with next episode title + `Up next in 10s — press BACK to cancel` countdown. CENTER plays now; BACK cancels (and the existing `STATE_ENDED` autoplay path from `51f1e20` is the safety net for dropped bumpers / inaccurate durations). **Red team:** position polling — `Player.Listener` doesn't fire on natural progression. Need a periodic check (`scope.launch { while (active) { delay(1000); check } }`) tied to STATE_READY entry. **Red team #2:** a user who pauses at 14:55s shouldn't see a frozen 10s countdown — pause the countdown on `STATE_READY` flip-off. | `PlayerActivity.kt` + new `UpNextBumper.kt` composable | planned | Last 15s of episode shows bumper. CENTER plays next now. BACK cancels for current episode (no re-trigger this session). End of episode without interaction → autoplay fires (today's path). |
| 25.D.2 | **Skip-intro / skip-credits offsets.** Global pref: `skipIntroSec: Int = 0`, `skipCreditsSec: Int = 0`. When in the first `skipIntroSec` seconds of an episode, render a `Skip intro →` button (CENTER seeks past it). Symmetric for credits in the last `skipCreditsSec` seconds. **Red team:** "user-toggleable global offset" assumes all episodes have identical-length intros — they often don't. v1 acceptable; smarter detection (chapter metadata, fingerprint) is a deferred follow-up. | `AppPreferences.kt` (schema bump? read-the-file-first) + `PlayerActivity.kt` | planned | Settings → Playback shows two sliders. With `skipIntroSec=60` set, the first 60s of an episode shows the skip button; CENTER seeks to 60s. |
| 25.D.3 | **Seek-preview thumbnails.** On first VOD play, generate a sprite atlas (one frame every 10s) via `MediaMetadataRetriever`, persist to `cacheDir/scrub-thumbs/<contentId>/atlas.jpg` + meta JSON. While in C.2 scrub mode, render the thumbnail under the scrub head. **Red team:** for HLS / fragmented streams the retriever needs ranged GETs per segment — slow, and can fail. Wrap generation in `runCatching`; absence of thumbs falls back to the C.3 timestamp tooltip. **Red team #2:** atlas generation can run minutes for a 2h movie; do it on a background WorkManager job after first START_READY, not eagerly. | New file `ScrubThumbExtractor.kt` + `VodPlayerDock.kt` | deferred (5–8 h) | Atlas generates on first play. Subsequent scrubs render thumbnail under scrub head. Generation failure (HLS) silently falls back to tooltip. |
| 25.D.4 | **MediaSession / lock-screen polish.** Bigger album art, episode title, transport controls. Currently MediaSession is owned by `PlaybackService` but not audited. **Read-the-file-first gate:** confirm what MediaSession metadata is currently published before scoping. | `PlaybackService` / wherever MediaSession lives | planned | Lock screen shows: episode title, series name, album art (logoUrl), play/pause/skip transport. |

**Estimate:** D.1 ~2.5 h, D.2 ~2 h, D.3 ~5–8 h (deferred), D.4 ~1 h (assuming MediaSession exists; could blow up if missing). **Total ~5.5 h excl. D.3.**

---

### Recommended sequencing

1. **A** (~1.5 h, 1 commit) — closes the two reported user complaints.
2. **B.1 + B.2 + B.3 bundle** (~2 h, 1 commit) — coherent dock-info upgrade.
3. **C.4 + D.1** (~3.5 h, 2 commits) — completes today's resume + autoplay fixes with their UI counterparts.
4. **C.1** (~2.5 h, 1 commit) — fast-forward modes, the highest-impact seek polish.
5. **C.2 + C.3 bundle** (~3.5 h, 1 commit) — scrub mode (architecturally one feature).
6. **B.4** (~1.5 h, 1 commit) — dock quick actions; defer until B.1–3 prove themselves on real use.
7. **D.2** (~2 h, 1 commit) — skip intro/credits.
8. **D.4** (~1 h ± expansion, 1 commit) — MediaSession polish (file-read gates the scope).
9. **D.3** (~5–8 h) — capstone, only if the user asks.

**Total without D.3: ~17.5 h across 7–9 commits. Across multiple sessions per the strict-one-slice-per-session rule for C.1 / C.2 / D.1.**

### Cost summary (red-teamed)

| Slice | Items | Estimate | Net production code touched | Risk |
|---|---|---|---|---|
| A | 2 (suppress overlay + seek flash) | ~1.5 h | ~80 lines `PlayerActivity.kt` + new SeekFlashOverlay | Low — both isolated to PlayerActivity |
| B | 4 (dock info upgrade) | ~3.5 h | ~150 lines `VodPlayerDock.kt` | Low — UI only, no state changes |
| C | 4 (FF, scrub, tooltip, resume prompt) | ~7 h | ~300 lines across PlayerActivity + dock + new UpNextBumper | **High** — key handling state machine, focus mgmt |
| D.1 | 1 (autoplay bumper) | ~2.5 h | ~120 lines new composable + position watcher | Medium — async timing |
| D.2 | 1 (skip intro / credits) | ~2 h | ~60 lines + pref schema | Low |
| D.4 | 1 (MediaSession) | ~1 h ± | TBD on file-read | Medium — could expand |
| D.3 | 1 (thumbnails) | ~5–8 h | ~250 lines new + cache infra | **High** — IO-bound, format compat |

### Open risks / what could derail

1. **Long-press handling state machine (C.1) interferes with the existing CENTER long-press for player options popup.** Read-the-file-first will catch it but the fix may need a generic long-press dispatcher.
2. **Scrub-mode focus (C.2) collides with the existing dock's focus targets.** The dock already has focus interactions; a scrub-bar focus-with-state requires care to not break the dock's quick-action flow (B.4) when both ship.
3. **HLS / fragmented streams break thumbnail extraction (D.3) and possibly buffered-range visualization (B.3).** B.3 is the smaller risk — `bufferedPosition` is well-defined for HLS; D.3 may need `runCatching` + fallback.
4. **MediaSession (D.4) could be missing entirely**, in which case the slice is "stand it up" not "polish it" — 4–6 h instead of 1.
5. **User-toggleable skip-intro (D.2) without per-show offsets is a half-feature.** It'll work for the user's typical content but won't satisfy power users; document the limitation and treat as v1.

### Files a fresh chat should open first

- `packages/android/app/src/main/java/com/yancotv/android/player/PlayerActivity.kt` — the heart. `dispatchKeyEvent`, `playerListener`, dock toggle.
- `packages/android/app/src/main/java/com/yancotv/android/player/VodPlayerDock.kt` — the VOD dock layout.
- `packages/android/app/src/main/java/com/yancotv/android/player/VodPlayerChrome.kt` — the buffering / error overlays. Look for the `"Tuning the stream"` string.
- `packages/android/app/src/main/java/com/yancotv/android/player/PlaybackController.kt` — `currentEpisode`, position state, `loadCurrent` (just gained the `positionForEpisode` branch in `584ccca`).
- `packages/android/app/src/main/java/com/yancotv/android/prefs/AppPreferences.kt` — `PlaybackPrefs`, `autoPlayNext` already there; D.2 needs `skipIntroSec` / `skipCreditsSec`.
- `packages/android/app/src/main/java/com/yancotv/android/player/options/PlayerOptionsPanels.kt` — `cycleAudioTrack`, `cycleTextTrack`, `cycleSpeed` for B.4.

### Test discipline

- **Pure-function helpers go in unit tests.** A.2's seek-flash accumulator (state: `accumulatedMs: Int`, `lastPressAt: Long`) → table-driven. C.1's FF-tier decision (`elapsedMs → seekStepMs`) → table-driven. D.1's bumper-trigger decision (`(position, duration) → showBumper: Bool`) → table-driven.
- **No new instrumented tests** unless absolutely needed. Manual smoke per slice DoD on Fire TV.
- **Smoke checklist on every slice's APK install:**
  - Cold-start a movie. Resume offset honored (regression test for `584ccca`).
  - Seek RIGHT × 3 fast. No "Tuning the stream" overlay (regression test for A.1).
  - Pause Wi-Fi mid-VOD for 3s. Buffering overlay appears (regression test that A.1 didn't over-suppress).
  - Open a series, watch to end. Autoplay fires next episode (regression test for `51f1e20`).

### Out of scope for MK.25 (file as new MK if pursued)

- **Volume / brightness gestures** for phone (MK.11.2 territory; not started).
- **Chromecast / mirroring** — ~~dropped permanently~~ → Chromecast revived 2026-06-15 as **MK.26 Track B**; mirroring stays out.
- **Subtitle styling** (font, color, opacity) — separate concern; lives in subtitle stack not player UX.
- **Picture-in-Picture** — MK.11.2.
- **A-B repeat / loop region** — power-user feature; defer.
- **Statistics overlay** (bitrate, codec, frame drops) — debug feature; file as MK.dev or similar.

---

## MK.26 — Cast to TV: LAN companion handoff (primary) + Google Cast (secondary) — planned 2026-06-15

**Why now.** User asked for "stream to TV" parity — cast live TV, movies, and series to a TV — "the best, no lag, no mistakes." A two-workflow research + adversarial red-team pass (2026-06-14/15, 20 agents) established the hard constraints and **reverses the 2026-04-25 permanent-drop of Chromecast (MK.11.3 / MK.18.3) and revives the deferred cross-device handoff (MK.18.5)** — but with a different *primary* transport than originally assumed. Findings:

- **Google Cast cannot carry YancoTV's live catalog without a server we operate.** The Cast Web Receiver is an HTTPS browser page: it silently drops `User-Agent`, can't set `Referer`, blocks cleartext `http://`, requires CORS providers don't send, treats AC-3/E-AC-3 as HDMI passthrough-only (silent video), and rejects HEVC-in-TS. Xtream live + timeshift are always raw MPEG-TS. So every nontrivial stream would have to route through a server-side transcode/remux proxy, and standard HLS is structurally **~10–20s behind live** regardless. "No lag" over Cast is physically impossible.
- **Fire TV — the canonical device — is not a Cast receiver at all.** Amazon Fling reached end-of-support 2026-03-05; its successor Matter Casting is app-launch-only. No Google Cast path reaches Fire TV.
- **The winning transport is a LAN companion handoff.** Because the user installs YancoTV on every TV, the phone can tell the YancoTV *already running on the TV* "play content X at position Y." The TV's own ExoPlayer fetches the original URL and plays it exactly as if opened locally — raw TS, AC-3, HEVC, `.mkv`, provider headers, cleartext, all of it — at **zero added lag** (the stream never relays through the phone). Two independent red-team agents confirmed this is the only zero-added-lag, all-content path. This is how Plex / Jellyfin / Kodi do "play on my TV."

**Strategy.** Track A (handoff) is PRIMARY and ships standalone — it delivers the full ask (live + movies + series, zero lag) to every Fire TV / Android TV the user owns. Track B (Google Cast) is SECONDARY and droppable — it only adds reach to app-less Chromecasts the user doesn't control, movies/series-first, live as a costly/laggy stretch. The two tracks are independent; **B must not gate A.**

**Accepted coverage gaps (do not paper over).**
- Generic non-Cast smart TVs (Roku, older Samsung/LG without built-in Cast, dumb TVs) have **no app path** for live IPTV. Honest answer: a ~$35 Google TV / Fire stick running YancoTV (becomes the zero-lag handoff path), or the TV's own IPTV app. Do NOT ship cast-to-any-smart-TV.
- Bare Chromecast: movies/series via Cast; live H.264-only at ~10–20s lag via the proxy; HEVC / AC-3 / `.mkv` need server-side transcode.

### Read-the-file-first gates (CRITICAL)

Before writing code in any slice, open the cited file and confirm the assumption.

- **A.1** — `PlaybackController.kt:521` (`play(list, startIndex, fromStart)`) + `:568` (`play(episode, fromStart)`). The receiver maps a handoff envelope onto these EXACT calls — confirm signatures unchanged; reuse, don't fork.
- **A.1 / A.4** — `PlaybackController.kt:849–865` (resume-read branch: `positionForEpisode(id)` / `positionFor(id)` return SECONDS, then `× 1000`). The envelope carries SECONDS; convert at the receiver boundary only. `episodes.duration` column unit is UNDOCUMENTED (bare INTEGER; `EpisodeInfo.duration:String?` vs `Episode.duration:Double?` disagree) — do NOT use it as a duration without verifying against the Xtream parser; prefer `watch_history.duration_seconds` / `player.duration`.
- **A.4** — `PlaybackController.kt:245–269` (OkHttp UA/Referer interceptor) — these headers do NOT travel with a Cast load but MUST travel in the handoff envelope (handoff's whole edge). Confirm `currentSourceNet` staging at `:772–788`.
- **A.4** — the 3-path `CleartextAllowlistInterceptor` (`AppModules.kt:83-92,111,131,214`) — confirm the receiver's player OkHttp seeds the allow-list from the handed-off host.
- **B.1** — surfaces bind `playerView.player = controller.player` where `var player` is typed **`ExoPlayer`** (`PlaybackController.kt:198`), NOT the `Player` interface. Track B must widen this to `Player` so a `CastPlayer` can be swapped in — high-regression change against the mini↔theater + FFmpeg-rebuild paths just stabilized in 0.3.6–0.3.8. (Surface handoff is `setVideoSurface`/`clearVideoSurface` on the shared player, NOT `switchTargetView` — see `MiniPlayer.kt:66/76`, `PlayerActivity.kt:559/688`.) Read those first.
- **B.1** — `YancoApp.kt:46-229` init order: any eager `CastContext` init MUST come after `startKoin` (`:94`) and be guarded by `GoogleApiAvailability` (Fire TV AFTDCT31 has no Play Services → eager init crashes at launch).
- **B.x** — `themes.xml` — `MediaRouteButton` needs an AppCompat-parented theme. `PlayerActivity` has one (`PlayerTheme`, `:7`); `MainActivity` runs Material `Theme.YancoTV` (`:3`) — reparent or use a Compose MediaRouter affordance.

### Slice scope ground rules

- **One slice per session for A.1, A.2, B.1.** Highest blast radius (new service, discovery, player-type widening). Do not chain.
- **Each slice ends with `:app:installDebug` green on Fire TV (AFTDCT31) + phone (HT74J0206349).** Manual smoke per DoD; logcat + user visual check (no `adb screencap` per project rule).
- **One MK sub-task per commit.** File bugs in `bugs.md` starting **MB-231**.
- **Track A ships fully before Track B starts.** They share only the `PlaybackController` seam.

---

### Track A — LAN companion handoff (PRIMARY)

**Why first.** Delivers the entire requirement — live TV + movies + series, zero added lag — to every Fire TV / Android TV the user owns. No Cast SDK, no DRM, no proxy, no HTTPS host, no $5 registration. Revives MK.18.5 with a LAN-only design (no cloud → no GDPR surface).

| # | Task | Surface | Status | DoD |
|---|---|---|---|---|
| 26.A.1 | **Receiver play-service on the TV.** Bound/foreground in-app HTTP+WebSocket listener that accepts a play-command envelope `{schemaVersion, pairingToken, items[], startIndex, contentType, headers{ua,referer}, resumePositionSeconds, isLive}` and routes it to the EXISTING `PlaybackController.play(list, startIndex, fromStart)` / `play(episode)`. **Red team:** MUST route through the existing single `PlaybackController` Koin singleton — never instantiate a second ExoPlayer (one-player rule). Convert `resumePositionSeconds × 1000` at the boundary only. | `HandoffReceiverService.kt` + `HandoffServer.kt` (Ktor CIO) + `shared/.../handoff/` DTO + resolver | ✅ code-complete · awaiting Fire TV smoke | Phone POSTs an envelope → TV starts playback. **Code-complete + `:app:assembleDebug` green; 23 shared unit tests green.** Deviations from original DoD: (a) exact-position seek deferred to A.3 — A.1 plays from the TV's own resume row, not the sender's offset (avoids racing `loadCurrent`); (b) "no 2nd ExoPlayer" is structural (service only calls `play()` on the injected singleton), not a written assertion. On-device smoke pending: POST to `http://<tv-ip>:8731/handoff/play` with `pairingToken:"yanco-dev"`. |
| 26.A.2 | **Discovery + pairing.** `NsdManager` DNS-SD advertise `_yancotv._tcp` on the TV, browse on the phone; first-class **manual pairing fallback** (enter IP / 6-digit code) because router multicast suppression + Fire OS mDNS quirks are the #1 field failure. Optional DIAL/SSDP cold-launch to wake YancoTV on the TV if not running (launch + ≤4 KB payload only). **Red team:** reject Google Nearby Connections — it needs Play Services on BOTH ends; Fire OS has none. | new `HandoffDiscovery.kt` | planned | Phone lists reachable TVs by name; manual-IP path works on a multicast-suppressed AP. |
| 26.A.3 | **Sender UI on phone.** "Play on [TV]" target picker in the player + content-detail surfaces; builds the envelope from `controller.currentItem` / `currentEpisode` + live position (`player.currentPosition` ms → seconds); surfaces connect/handoff errors. | new `HandoffTargetSheet.kt`; reads `PlaybackController` state | planned | From a playing item, pick a TV → it plays there from the same spot. Errors shown, never silent. |
| 26.A.4 | **Header fidelity + credential safety.** UA/Referer ride in the envelope (Cast can't carry them — handoff's edge); receiver applies them to its player OkHttp + seeds the cleartext allow-list from the handed-off host; `redactCredentials` on every log/error/on-screen render of the URL; require `pairingToken` on every command; bind the listener to LAN only. | `HandoffReceiverService.kt`, cleartext interceptor seam | planned | Gated provider plays on the TV; logcat shows redacted URLs; unpaired peer is rejected. |
| 26.A.5 | **Fire TV de-risk + threat-model addendum.** Device-test `NsdManager` advertise/browse on AFTDCT31 (no GMS); validate manual fallback + multicast-suppressed AP; add a `docs/security/AUDIT_NOTES.md` entry for "credentialed stream URL crosses the LAN to a paired peer" (parallel to MB-203 cleartext). | test/hardening + `docs/security/AUDIT_NOTES.md` | planned | Handoff verified on Fire TV; AUDIT_NOTES addendum merged. |

**Rough effort: ~8–13 days (native tends to overrun — treat as a range, not a floor).**

---

### Track B — Google Cast (SECONDARY, droppable)

**Why second / optional.** Adds reach to app-less Chromecast / Google-TV devices the user does NOT control (their own TVs run YancoTV → Track A covers them). Movies/series cast via the Default Receiver as-is; live raw-TS / AC-3 / HEVC / gated / cleartext needs an ON-DEVICE smart proxy (NOT a hosted server — see the corrected architecture below). Screen mirroring is the universal content-agnostic fallback.

| # | Task | Surface | Status | DoD |
|---|---|---|---|---|
| 26.B.1 | **Cast VOD to a bare Chromecast (raw Cast SDK, isolated from Media3).** `play-services-cast-framework` + `mediarouter`; `CastOptionsProvider` (Default Receiver `CC1AD845`, no registration); `CastController` (Koin, `GoogleApiAvailability`-gated, dark on Fire OS) loads the current item on a connected session via `RemoteMediaClient` + pauses local; "Cast to Chromecast" row in the Play-on-TV panel. **Shipped WITHOUT the media3 bump / CastPlayer — the Chromecast is its own remote player, so Media3 + the vendored FFmpeg renderer are untouched.** | `cast/CastOptionsProvider.kt`, `cast/CastController.kt`, `AppModules.kt`, `PlayerOptionsPanels.kt`, deps | ✅ shipped (`341001f`, `1fa73c0`) · awaiting on-device test | On a Chromecast: H.264/AAC `.mp4`-over-HTTPS movie casts. Dark on Fire TV. |
| 26.B.2 (Phase 1) | **On-device smart proxy → Default Receiver — header-inject + remux + AC-3→AAC.** Phone runs a LAN HTTP server (bound to the Wi-Fi IP) that fetches the provider stream with UA/Referer, remuxes raw TS→HLS, transcodes AC-3→AAC, adds CORS + Content-Type + Range, and hands the Default Receiver a plain-`http://<wifi-ip>/…m3u8`. Verified: the Default Receiver loads plain-http LAN media from a native sender (NO custom receiver, NO $5 reg, NO hosting). Covers the majority of channels (H.264 + AC-3). | new `CastProxyServer.kt` (clone `HandoffServer`), `CastStreamClassifier` (shared), reuse the OkHttp UA interceptor, `CastController` points at the proxy URL; remux/audio engine | planned | A gated/cleartext H.264+AC-3 live channel casts to a stock Chromecast via the phone proxy. |
| 26.B.3 (Phase 2 · GATED) | **HEVC channels — hardware transcode.** HEVC-in-TS → H.264 via Android `MediaCodec` (HW decode→Surface→HW encode; software realtime HEVC encode is NOT feasible). Capability check + thermal/battery guards + "can't cast this one — use Mirror" fallback. | extends `CastProxyServer` | deferred | An HEVC channel casts, or cleanly falls back to mirror. |
| 26.B.4 | **Screen-mirror fallback ("Send to TV — mirror").** Guided flow over the OS "Cast screen" feature (apps can't trigger it — Remote Display API is deprecated). DRM-free YancoTV content mirrors fine; universal backstop for channels the proxy can't keep up with. | guided UI only | planned | User can mirror any playing channel to a Chromecast / Google TV. |

**Rough effort: B.1 ✅ done; B.2 ~2–3 wks; B.3 ~1.5–2 wks (HW codec + device validation); B.4 ~2–4 days.** (Native overruns — honest ranges.)

**Corrected architecture (2026-06-15 research dig — SUPERSEDES the earlier "hosted server + custom receiver" assumption in the old B.2/B.3 rows.):** the proper app-less path is an ON-DEVICE proxy → the Default Media Receiver, NOT a rented HTTPS server. Adversarially verified: the "HTTPS/CORS required" rule is a *browser-sender* policy; the Default Receiver loads plain-http LAN media from a native `CastContext` sender (every "cast local files" app — go2tv, LocalCast, BubbleUPnP, pychromecast — rides this). So no custom receiver, no $5 registration, no hosting, and provider credentials never leave the user's devices. The proxy must be SMART (header-inject + TS→HLS remux + selective transcode + CORS), not a dumb byte pipe. **Reuse:** `HandoffServer` (embedded Ktor server template), the `PlaybackController` OkHttp UA/Referer interceptor + `TeeingDataSourceFactory` (upstream fetch with headers). **The one real dependency decision (B.2):** the remux/transcode engine — ffmpeg-kit is RETIRED (Jan 2025); options are (a) a maintained ffmpeg-kit fork (heavy native lib, APK bloat, maintenance + codec-patent exposure on encode) or (b) Android `MediaCodec` + `MediaMuxer` + the app's already-vendored FFmpeg *audio* decoder (fewer deps, more code). Decide before building the engine. **Dead ends (verified — do not attempt):** custom Web Receiver as v1 (HTTPS mixed-content vs plain-http LAN media; buys nothing extra), AirPlay-from-Android (no sender lib), Matter Casting (closed/attested), Cast Remote Display API (deprecated — an app cannot trigger a mirror), software realtime HEVC encode (infeasible).

---

### Recommended sequencing

1. **A.1 → A.5** (Track A in full) — ships the primary, all-content, zero-lag feature to the user's fleet.
2. **B.1 only as far as movies/series** — opportunistic Chromecast reach, behind a flag.
3. **B.2 / B.3** — gate behind proven demand for bare-Chromecast casting; may never be worth the proxy opex.

### Top risks

- **R-A1 — router/AP multicast suppression kills NSD** (the #1 field failure) → ship manual IP/code pairing as a first-class path (A.2), not a fallback afterthought.
- **R-A2 — Fire OS mDNS quirks** on AFTDCT31 (no GMS) → A.5 device-test gate before "done".
- **R-A3 — one-ExoPlayer rule on the receiver** → route through the existing `PlaybackController` singleton; test asserts no 2nd player.
- **R-A4 — seconds↔ms resume mismatch** → convert at the receiver boundary only; table-driven unit test (mirror `ResumePointDecisionTest`).
- **R-A5 — open LAN listener leaks credentialed URLs** → pairing token on every command, LAN-only bind, `redactCredentials` everywhere; file MB-231 if review finds a gap.
- **R-B1 — proxy = single point of failure + linear cost** → scope B to VOD/series first; gate live transcode.
- **R-B2 — `Player`-type widening regresses local playback** → flag-guard B.1; full mini-player / zap / FFmpeg-rebuild regression before merge.
- **R-B3 — AC-3 silence / HEVC-in-TS look "intermittent"** → capability-detect per stream; show an honest "not castable — use handoff" message, not a black screen.

### MK.26.B cast hardening — 2026-06-16 audit (6-dimension workflow + adversarial verify)

After Cast was proven working end-to-end on real hardware (`6c8d2a6`), a hardening audit (correctness/
lifecycle, latency, memory/disk, reliability, resume/UX, security/threading) ran with adversarial
verification of every high/blocker finding.

**Shipped in the hardening pass** (safe, on-device-verifiable): **orphan-cache startup sweep**
(`cacheDir/cast-proxy` wiped in `YancoApp.onCreate` — audit CAST-DISK-5); **server-bind guarded**
(`startServer` returns false on `BindException` → clean `NotReady` instead of an uncaught crash);
`@Volatile` proxy fields; **`failCast` now stops the proxy** (receiver-reject reached it without an
`onSessionEnded`, leaking ffmpeg+server — CAST-SEC-7); playlist-ready gated on an actual `.ts`
reference (not just file existence) + 50ms poll.

**Tried + reverted:** VOD resume via `-ss` input seek (+`-avoid_negative_ts make_zero`) and `-hls_time`
4→2. On-device the `-ss`/`-c:v copy` combo gave the receiver a few seconds then stalled it at
`buffered=0` (the copy-seek timestamp-discontinuity hazard the audit flagged); reverted to the
known-good stream config. Resume is **MB-240** below.

**Deferred backlog** (real, verified — but need deliberate impl + on-device race/VPN testing; the
feature is secondary/droppable so weighted accordingly):

- **MB-235 · High · cast start/stop lifecycle race.** `CastProxy.start()` (IO, blocks ≤25s in
  `awaitMaster`) and `stop()` (main, Cast SDK callbacks) mutate `server/session/dir` with no mutual
  exclusion, and the `loadCurrent` coroutine is untracked — a Stop→Cast / reconnect can leak an
  ffmpeg+Ktor server holding port 8732 (next cast fails) and feeds MB-230. **Fix carefully:** a naive
  `cancelAndJoin` on the main callback thread would ANR (`awaitMaster` uses `Thread.sleep`, not
  cancellable). Needs a generation token + cooperative `awaitMaster` bail + non-blocking job cancel +
  async (`scope.launch`) `proxy.stop()` off-main (which also fixes **CAST-SEC-1**: stop()'s
  `server.stop` + recursive delete currently block the UI thread).
- **MB-234 · High · VOD cache unbounded.** `-hls_list_size 0` keeps every segment; no `-re`, so a 2h
  movie remuxes wholesale to `cacheDir` (GBs). **Fix:** size-watchdog ceiling (~1.5GB → `failCast`,
  version-independent) and/or `-readrate 1.5` (verify the gnutls fork supports `-readrate` first).
- **MB-236 · Medium · cast memory + HEVC.** (a) the HLS server does `respondBytes(f.readBytes())` —
  whole-segment heap copy per request (MB-230 intersection); switch to streaming. (b) HEVC is `-c:v
  copy`'d → receiver plays audio-only with a stuck overlay and no error; add a pre-flight
  `videoFormat` codec check (fail fast) or a `RemoteMediaClient` stall watchdog.
- **MB-237 · Medium · `wifiIpv4()` wrong NIC.** Returns the first non-loopback IPv4 — picks VPN
  (`tun0`)/cellular/tether ahead of `wlan0`, so the Chromecast gets an unreachable URL. **Fix:**
  `ConnectivityManager` `TRANSPORT_WIFI`/`ETHERNET` link address, fall back to the current heuristic.
- **MB-238 · Medium · no "Preparing" feedback.** The overlay shows "Casting" immediately, then blocks
  ≤25s before the receiver gets anything → users tap Stop on a cast about to succeed. Add a
  `Preparing` overlay state before `Active`.
- **MB-239 · Medium (governance) · LAN media server is unauthenticated** (wildcard CORS, fixed port).
  Any LAN host can pull the remuxed content (not credentials) while casting. **Fix:** random
  per-session path token; promote the [AUDIT_NOTES](docs/security/AUDIT_NOTES.md) cast foot-note to a
  proper accepted-risk decision row. (Path-traversal guard + host-bind were verified **sound**.)
- **MB-240 · Medium · VOD resume from watch position.** Casting a half-watched movie restarts at 0
  (the user's named bug). The fast fix (`-ss` input seek so ffmpeg starts at the resume point) stalls
  the receiver under `-c:v copy` (timestamp discontinuity → `buffered=0`); the safe fix (receiver-side
  `setCurrentTime`) makes the receiver wait for ffmpeg to race past the resume offset (bad for large
  positions on the event playlist). **Needs:** either a clean copy-seek incantation validated on real
  hardware (e.g. `-ss` + `-copyts`/`-start_at_zero` tuning, or seek to the nearest keyframe and trim),
  or accept `setCurrentTime` for small offsets only. Reverted from the 2026-06-16 pass.
- **Low / deferred:** wiring `CastStreamClassifier` for direct-cast (downgraded — needs a codec prober
  that doesn't exist; only helps the https+no-headers+H.264/AAC minority, since IPTV is mostly
  gated/cleartext that must keep the proxy); ffmpeg native-lib pre-warm; subtitle/multi-audio passthrough.

### MK.26.B.3 — seekable + resumable cast (correct architecture) — designed 2026-06-16

Supersedes **MB-240** (resume) and the cast-seek work. A research workflow (Plex/Jellyfin/Emby + Google
Cast docs + red-team of our proxy) established the **definitive root cause** and the correct design.

**Root cause (confirmed against Google's spec):** the Default Media Receiver decides VOD-vs-LIVE *solely*
by the presence of `#EXT-X-ENDLIST` — it explicitly ignores `#EXT-X-PLAYLIST-TYPE`. Our proxy runs ffmpeg
with `-hls_playlist_type event`, which **never writes ENDLIST** while encoding, and races the encode ahead
with no finite EXTINF sum. So the receiver classifies the cast as **LIVE**: duration = -1, no scrubber,
`COMMAND_SEEK` withheld → `RemoteMediaClient.seek`/`setCurrentTime` are genuine no-ops (not sender bugs),
and a live stream loads at segment 0 (no resume). That one fact produced *all three* symptoms. (The earlier
`-ss`+`-c:v copy` stall was an orthogonal copy-seek/keyframe issue.) Every prior cast "fix" was a band-aid
on a live-shaped manifest.

**How real apps do it:** "manifest-first VOD HLS + on-demand segments." They do NOT let ffmpeg author the
playlist — they hand-write a complete VOD media playlist up front from the known runtime (full `#EXTINF`
list + `#EXT-X-PLAYLIST-TYPE:VOD` + `#EXT-X-ENDLIST`), so it's finite + seekable instantly; segments are
transcoded lazily when the receiver GETs them. Seek == "receiver requests a far-ahead segment → server
transcodes from that offset"; resume == "load with `currentTime=N`". Custom receivers exist only for
branding/auth — the **Default Receiver seeks fine on a proper VOD manifest** (no Cast Console registration
needed).

**The architecture for us** (keep Default Receiver + `-c:v copy`; we vendor the non-GPL `main-tls`
ffmpeg-kit fork which has **no libx264** and no reliable `h264_mediacodec` encoder → re-encoding every
segment is *unbuildable*, and seekability doesn't require it):
- **Piece 0 (ships first, small):** **direct-cast** the `CastStreamClassifier` REMUX subset (H.264+AAC,
  natively-playable container) — skip the proxy, load the *original* URL with `setStreamDuration` +
  `setCurrentTime(resumeMs)`. Native seek/resume/scrub, zero phone CPU.
- **Piece 1 (load-bearing):** manifest-first VOD HLS with **copy-safe keyframe segments** for streams that
  must be repackaged: (1) **FFprobe** for duration + the real video **keyframe timestamps**; (2) hand-author
  `master.m3u8` in Kotlin — VOD + ENDLIST, **variable `#EXTINF` equal to the real keyframe gaps** (so
  `-c:v copy` cuts land exactly on keyframes — no desync); serve *that*, never ffmpeg's file; (3) on a GET
  for an uncached `seg_N`, spawn a bounded `ffmpeg -ss kf[N] -i url -t (kf[N+1]-kf[N]) -c:v copy -c:a aac
  -copyts -avoid_negative_ts make_zero -output_ts_offset kf[N] -f mpegts` → cache (LRU) + a 2-3 concurrency
  semaphore; (4) `setStreamDuration`; (5) resume + the overlay seek bar now work via `setCurrentTime`/
  `RemoteMediaClient.seek` for free. Replaces the single-session proxy with a small process pool
  (coordinate with **MB-235**).
- **Piece 2 (deferred, dependency-gated):** re-encode for HEVC — only when a fork with libx264 / verified
  `h264_mediacodec` is swapped in. Until then HEVC stays MIRROR.

**Mandatory de-risk before building Piece 1:** an on-device probe — serve a *static, fully-pre-transcoded*
short VOD `.m3u8` (full `#EXTINF` + `#EXT-X-ENDLIST`, `-c:v copy` segments) from the Ktor route and confirm
on a real Chromecast/Google TV that the Default Receiver shows a working scrubber + seek. Isolates "is the
manifest theory right" from "does on-demand work." Effort: **large**. ✅ DONE 2026-06-16 — proven on
"Yaman's Google TV": complete VOD playlist gives scrubber + seek + resume + reverse-continuity.

#### Status 2026-06-16 — Piece 1 attempt #1 (continuous-head) shipped to phone, then RED-TEAMED as unsound for long movies. PICK UP HERE NEXT SESSION.

**What's on the phone now** (`CastProxy.kt`, uncommitted→committed this session): a *fast-start* variant that
**deviates from the keyframe-map design above**. It takes `durationMs` from the local player (no probe →
instant start), hand-authors a **FIXED 4s grid** playlist, and produces segments with a single **continuous
`-c:v copy -hls_time 4 -copyts` head** that input-seeks to the play offset; a seek relaunches the head; a
lead-cap stops/resumes it; eviction keeps a window around the play position. User-tested: **fast start ✅,
no crash ✅, seek ✅, playback "between smooth and stutter."**

**Red-team verdict (28-agent workflow, 8 confirmed findings)** — the fixed grid is **fundamentally unsound in
copy mode**, and this is the root of the stutter + would fail the *3–4 hr movie* requirement:
- **#1 (root):** `-c:v copy` can only cut on the source's **keyframes** (movies: ~6–10s GOP), so real
  segments are ~GOP-length while the playlist declares 4s. The error **accumulates** (scrubber vs picture
  drift) and the **segment count mismatches** (playlist lists `dur/4`, head emits `dur/GOP`) → playback
  breaks near the end of long movies. Short / frequent-keyframe clips mostly mask it (why the test "worked").
- **#2/#7:** every lead-cap resume relaunches with `-ss + -copyts` → keyframe-snapped **PTS overlap** at the
  seam, no `#EXT-X-DISCONTINUITY` → a hitch every ~2.5 min.
- **#6/#8:** lead-cap enforced only on request (head freewheels → disk spike when receiver pauses); eviction
  centers on non-monotonic `lastReqSeg` (back-seek yanks the forward buffer + cancels the head early).

**Fixed + committed this session (safe, no-architecture-change):** cold-start concurrency race (CREATED-vs-
RUNNING → coverage now keyed off `headBase`); serve-vs-evict TOCTOU (read bytes before evict, guard read);
`seg_*.ts.tmp` leak (swept on relaunch).

**CONFIRMED NEXT STEP — implement the keyframe-map design above (Piece 1 as originally specified):** FFprobe
the real keyframe timestamps → author **variable `#EXTINF` = keyframe gaps** so segment N ↔ media time is
exact and the count matches → relaunch/cut **only on keyframe boundaries** (clean seams, no drift). The only
cost is the probe in the start path; **minimize it** — run it in parallel with warming the first segment +
show a brief "Preparing…", and **measure on the user's real provider** (the earlier 9.5s was archive.org +
a now-removed *duration* probe; duration comes from the player, so it's a single keyframe probe). The user's
dealbreaker is the *minutes-long* wait, not a couple seconds. Fold the deferred #6/#8 fixes into this rewrite
(`-readrate`/`-t` to bound the producer; monotonic play-frontier for lead + eviction).

#### BREAKTHROUGH 2026-06-16 (later) — two-tier: Piece 0 DIRECT-CAST shipped + works; the proxy is the narrow fallback.

After the keyframe-map manifest proved correct but the proxy SEGMENT engine hit wall after wall (HLS muxer with
`-c:v copy` ignores `-hls_time` and cuts at EVERY keyframe → fixed-grid drift; per-keyframe boundaries → segments
too small, receiver won't play; segment muxer `-segment_times` ignored → 103s first segment; per-segment extraction
→ "status 2002"), a 5-agent research workflow (web + Jellyfin/Plex source) settled it:
- **"status 2002" = `CastStatusCodes.CANCELED` (SENDER-side), NOT a receiver media reject.** A real reject is
  `2100` (FAILED) + a `MediaError.detailedErrorCode` (905 LOAD_FAILED / 316 HLS_SEGMENT_PARSING / 412 …). Default
  sender load timeout is 0, so a slow segment does NOT trip it. We had been MISDIAGNOSING every proxy failure
  because `CastController` only logged `status.statusCode`, never `mediaError.detailedErrorCode` — now fixed.
- **The manifest side (keyframe-derived variable-`#EXTINF` complete VOD playlist) is already the Jellyfin/Plex
  model and is CORRECT.** Their `ComputeSegments` derives EXTINF FROM probed keyframes (never a fixed grid) — our
  `keyframeBoundaries()` already does this. The drift was the fixed grid; the keyframe version is sound.
- **The right architecture is DIRECT-CAST-FIRST.** For H.264 + AAC in `.mp4` over **https** with no provider
  headers, hand the receiver the ORIGINAL url + `setStreamDuration` + `setCurrentTime`; it fetches + Range-seeks
  natively (native scrubber + resume, zero phone CPU, no proxy, no drift). The proxy is only for the streams that
  can't: cleartext `http://`, header-gated, raw-TS, AC-3/E-AC-3, non-MP4 container.

**SHIPPED this session — Piece 0 (Tier 0 direct-cast):** `CastController.loadCurrent` reads
`controller.player.videoFormat/audioFormat.sampleMimeType` on main; if H.264+AAC && https && `.mp4`/`.m4v` &&
no source UA/Referer → loads the ORIGINAL url with `contentType=video/mp4`, BUFFERED, duration + resume. **User-
verified on "Yaman's Google TV": starts fast, seeks cleanly, plays to end, reverse-continuity intact.** Also added
the `detailedErrorCode` logging. Decision rule + caveats: non-faststart MP4 has no auto-fallback yet; codec read
needs local playback to have started.

**STILL OPEN — Tier 1 proxy (incompatible streams), now with a CLEAR roadmap (no more guessing):** keep the
keyframe manifest; replace per-segment-on-demand's fragility with the research's fixes — (1) **pre-warm seg 0-2 to
disk before `load()`** so the first GET hits a warm file; (2) add **`-mpegts_flags resend_headers -pat_period 0.1`**
so every TS leads with PAT/PMT (the real fix for HLS_SEGMENT_PARSING); (3) use **either** `-copyts -start_at_zero`
**or** `-output_ts_offset`, never both; (4) set `hlsVideoSegmentFormat=MPEG2_TS` + `contentType=
application/vnd.apple.mpegurl` on the proxy LoadRequest; (5) guarantee exactly ONE `load()` per attempt and no
teardown racing the pending load (the 2002=CANCELED cause); (6) add a manifest-vs-real-duration ffprobe diff as the
red-team gate. Jellyfin uses a SINGLE continuous head (kill+restart on seek), not per-segment — reconsider that vs.
pre-warmed per-segment. Full research in the session transcript + memory [[project_cast_mk26]].

### Out of scope for MK.26 (file as MB-* / future MK if pursued)

- **DLNA / UPnP** — stays DROPPED. Red-team reconfirmed: raw TS fails DLNA compliance, most renderers reject live HLS, Fire TV has no renderer — it doesn't even close the Fire TV gap.
- **AirPlay / Miracast / screen-mirroring** — no production-viable Android sender; mirroring is last-resort only.
- **Cast-to-arbitrary-smart-TV for live IPTV** — physically unreliable; the answer is a $35 stick or the TV's native app.
- **Matter Casting to Fire TV** — app-launch only; ~native-app effort for no streaming gain.
- **TV↔phone resume-continuity** (the *other* half of the old MK.18.5) — still post-v1; the away case needs a cloud backend.

<!-- When done: add "✅ ALL MK.26 slices complete YYYY-MM-DD. Order shipped: A.1 → ..." File bugs in bugs.md as | MB-231 | Native | <Sev> | <summary> | **Open · planned** | 2026-06-15 | <ref> |. -->

---

## Architecture rules (native)

1. **Shared Kotlin is pure business logic.** No `android.*` imports in `commonMain/`. Platform-specific code goes in `androidMain/` / `iosMain/` via `expect`/`actual`.
2. **SQLDelight is the only persistence surface for content, EPG, favorites, history.** No `SharedPreferences` / `DataStore` for content rows.
3. **Credentials in Android Keystore (EncryptedSharedPreferences) / iOS Keychain.** Never plaintext.
4. **One `ExoPlayer` instance** shared between mini-preview and fullscreen by swapping the output Surface on the single shared player — `setVideoSurface` / `clearVideoSurface` (symmetric both ways), NOT `PlayerView.switchTargetView()` (which the code does not use). Do not instantiate a second player. (MK.26 Track B's `CastPlayer` swap rides this same single-player invariant.)
5. **Compose for TV uses `androidx.tv.material`** for focus + surfaces. Never reuse `material3` clickables as focus targets on TV.
6. **`UiModeManager.UI_MODE_TYPE_TELEVISION`** is the TV detection source of truth. Don't use screen-size heuristics.
7. **ViewModels live in `shared/`** exposing `StateFlow<T>`. Compose screens `collectAsState()`; SwiftUI binds via KMP-generated helpers.
8. **No Retrofit, no Moshi, no Gson.** Ktor + Kotlinx Serialization only, so iOS target can compile.
9. **Delete-before-add** carries over — when a shared module replaces a platform-specific stub, delete the stub in the same commit.
10. **Desktop is unaffected.** `packages/core/` TypeScript keeps shipping Electron. Do not try to make Kotlin the source for desktop too — double port is cheaper than a single cross-language stack.

---

## MK.27 — Phone UX adaptation (touch + small-screen + single-pane selector) — planned 2026-06-15

The app is a single **TV/D-pad-first Compose shell rendered unchanged on a phone.** `isTv`
(`UiModeManager`, computed in `MainActivity`) only picks one-tap-vs-two-tap activation, the
fullscreen-launch decision, and the theme — it **never branches the layout.** So every screen
renders its TV multi-pane, remote-driven form on a ~380–420dp phone: cramped panes, controls
that only respond to a D-pad, overlays with no touch dismissal. Surfaced 2026-06-15 when the user
moved to phone testing for MK.26. Diagnosed by a 4-agent investigation workflow (2026-06-15).

**Root cause (one sentence):** there is no form-factor-aware layout layer; `isTv` gates behaviour
but not structure, and the focus/selector model assumes a remote.

**User's headline request:** "wherever the selector is, the menu/category it's in shall be ALWAYS
full screen until the selector moves (back or forward)." That is a **single-pane navigator** — the
opposite of today's simultaneous-pane cascade. The existing `PanelFocus` enum
(Sidebar → Categories → Content) is the right spine to build it on.

### Slices

| Slice | Scope | Notes |
|---|---|---|
| **27.A — Cast/player touch fixes** ✅ partial (MB-233 + cast sender overlay shipped 2026-06-15) | Cast/handoff no longer strands the stuck options menu; LAN returns to the app on success; **a live Cast session now covers the locally-paused video with a "Casting to &lt;device&gt;" overlay + a Stop control** instead of a frozen frame (`CastController.sessionState` → `PlayerActivity.applyCastOverlay`; Stop ends the session and resumes local playback). | Remaining: a visible touch close/"Done" on the options popup; `YancoTheme(isTv = !isTvDevice())` for the popup. |
| **27.B — Phone single-pane navigator** (the selector request) | On phone (compact width) render exactly ONE pane full-screen keyed off `PanelFocus`: Sidebar OR CategoryRail OR Content, never simultaneously. Forward (tap/OK) advances + swaps; BACK steps back (Content→Categories→Sidebar→exit, the existing back-chain). Hide the sidebar (width 0) when Content owns the screen. Keep the TV multi-pane path behind `isTv`. | LARGE. Reuses `PanelFocus`, the per-pane `BackHandler`s, `PlacedFocusAnchor`. Make **taps** the primary driver (commit a category on click, not on focus-landing). Add on-screen back/forward affordances. Run the cascade-nav smoke test after. |
| **27.C — Settings phone mode** ✅ layout shipped 2026-06-15 | Single-pane master/detail on a PHONE — full-width tab list → drill into one tab body full-width with a touch Back; the TV two-pane (`SettingsTwoPaneLayout`) is byte-for-byte unchanged. Gated on **form factor** (`smallestScreenWidthDp < 600 && !isTv`), NOT slot width — a width breakpoint left a *landscape* phone (wide slot) on the cramped two-pane; sw is orientation-independent so the phone collapses in both orientations. sw=411 confirmed. **Correction (MB-354, 2026-08-22): the "verified on-device portrait" claim in this row was wrong** — the app has never run in portrait. `MainActivity` declares `sensorLandscape` and `PlayerActivity` declared `landscape`, so every screen is landscape-only on a phone. Portrait support was reviewed and **explicitly cancelled** (owner decision, 2026-08-22): the shell is laid out landscape-first throughout and a TV-first app gains nothing from rotating. What the audit did find was a real phone defect — the two activities disagreed, so a phone held in reverse-landscape browsed upright and then played video upside-down. Both are `sensorLandscape` now. **Remaining:** the `moveFocus(Right)` tab→content silent no-op (MB-108) → explicit per-tab `FocusRequester` (27.F); per-tab horizontal padding still 32dp (27.D). | Branch is one check at `SettingsScreen` entry; per-row primitives untouched. |
| **27.D — Phone typography + spacing scale** | One phone scale off the same form-factor branch: section/title fonts down ~15–25%, smaller tab rows + logo, page padding 48→~12–16dp, per-tab padding 32→~16dp. | Today all dims are hard-coded "read at 3 m on Fire TV." |
| **27.E — Player overlay + dialog touch affordances** | Touch entry + on-screen close for: channel-surf list, quick-info, the options-row "< >" quick-cycle (tappable prev/next or drop the misleading hint). VOD dock phone layout (shrink 44sp title + 88dp orbs, wrap/scroll the transport+chip row, drop "OK HIDE / BACK" hints). Add-source dialog responsive width (forces `widthIn(min = 560.dp)` — wider than a phone). | Several overlays are remote-key-only with no touch path. Numeric channel-zap stays TV-only (acceptable). |
| **27.F — Selector bug fixes** | (1) `BrowseSection.awaitAndRequest` can deadlock on Sidebar→Categories if the selected pill's identity changes (stale anchor) — `withTimeoutOrNull` fallback or re-key the anchor. (2) Settings tab→content `moveFocus(Right)` silent no-op (MB-108) — explicit requester. (3) Detail-close focus-restoration effects gated on `isTv`. | Real focus bugs found during the audit; fix regardless of phone work. |

**Sequencing:** 27.A (mostly done) → 27.C + 27.D (Settings — the user's named pain, self-contained)
→ 27.B (the big navigator — the selector request) → 27.E → 27.F. 27.B is the largest and most
invasive (shell + cascade-nav); do it on its own with the smoke test.

### Release-polish pass (2026-06-15)

Final pre-release sweep before tagging the Cast/handoff build as the new app version. A 4-agent
release-readiness audit found exactly **one hard blocker** (the R8 release-build failure) plus a set
of ship-with-caveats items. Shipped this pass:

- **R8 release blocker (THE gate)** — the embedded Ktor server (`io.ktor.util.debug.IntellijIdeaDebugDetector`)
  references JVM-only `java.lang.management.*`; `:app:minifyReleaseWithR8` aborted on the missing
  classes. Fixed with `-dontwarn` rules in `proguard-rules.pro`. `assembleRelease` now green.
- **Cast-failure feedback** — `CastController.loadCurrent` showed nothing on the phone when a cast
  failed. Now toasts on every failure path — proxy-unavailable, null `RemoteMediaClient`, a
  synchronous `load()` throw, **and** the async receiver-side rejection (the common case — `load()`
  returns a `PendingResult` that does not throw, so this needed a `setResultCallback`; caught in the
  release-blocker audit, CAST-1). Each path also resumes the local player it paused, so a failed cast
  doesn't strand the phone on a frozen frame (CAST-2).
- **Receiver `startForeground` hardened** — wrapped in `runCatching` so an Android-14+ `mediaPlayback`
  FGS prerequisite failure degrades (stop receiver) instead of crashing the app.
- **`SecureRandom` pairing code** — handoff pairing code was `kotlin.random.Random`; now cryptographic.
- **Version → 1.2.0** (`versionCode 8`), `bugs.md` banner refreshed, `AUDIT_NOTES` entry for the new
  LAN handoff network surface.

**Receiver forces the internal player (fixed in the polish pass, was deferred):**
`PlayerLauncher.launch` gained a `forceInternal` flag and `surfaceFullscreenPlayer()` passes it, so a
handoff always plays on this TV's own player and keeps the provider UA/Referer overrides instead of
bouncing to a user-configured external app (which would drop the headers — release-audit HRS-5).

**Deferred to MK.26 Phase 2 / later (documented, not blocking a release):**

- **Handoff resume-position fidelity** — the sender's exact position is carried in the outcome but not
  seeked-to (A.3 scope, still open).
- **HEVC over Cast** — `CastProxy` copies HEVC video; fails on the Default Receiver until the Phase-2
  hardware transcode.
- **`CastProxy` LAN-bind robustness** — binds on the Wi-Fi IPv4; no retry/fallback if the interface
  flaps mid-cast.
- **Chrome-behind-popup / `YancoTheme(isTv=false)` for the options popup** — already tracked under 27.A.

**Release gate (NOT shipped — user action):** `MB-230` / `MB-229` Critical heap items remain Open. The
new on-device ffmpeg cast proxy adds memory pressure; run a **1+ hour Fire TV soak** with casting
exercised before tagging 1.2.0 as the published build.

### Release-polish pass (2026-06-18) — v1.3.0

Cuts a new version on top of v1.2.0 to capture the Cast Tier 0 breakthrough + the touch-scrubber +
post-1.2.0 hardening. No new code in this pass — version bump, audit-notes addendum, release-record
only. The 10 commits shipped since v1.2.0:

- **MK.26 Tier 0 direct-cast** (`482aaba`) — H.264 + AAC + MP4 + HTTPS streams with no source UA/Referer
  skip the proxy and hand the receiver the ORIGINAL URL with `setStreamDuration` + `setCurrentTime`.
  Native scrubber, native resume, zero phone CPU, no transcode, no drift. **User-verified** on a real
  Chromecast — starts fast, seeks cleanly, plays to end, reverse-continuity to local intact.
- **MK.26 Tier 1 proxy progress** (`883b116`, `94870cc`, `750d425`, `39b9908`) — MK.26.B.3 Piece 1
  keyframe-map manifest + per-segment proxy. **Status: WIP** — works for some VODs, fails for several
  raw-TS / AC-3 / HEVC paths. When it fails, `CastController.failCast` toasts "Couldn't prepare this
  video for casting" and resumes local playback (no black screen, no strand). The next milestone
  (pre-warm seg 0-2, `-mpegts_flags resend_headers`, single-`load()` contract) is documented in
  MK.26.B.3 above.
- **MK.26 Cast end-to-end + hardening** (`6c8d2a6`, `070254d`, `3ac3d5b`) — TLS ffmpeg fork, HLS
  plumbing, crash fixes, "Casting to <device>" overlay + Stop, orphan-process sweep, bind guard, leak fix.
- **VOD player touch scrubber** (`f8017c9`) — drag/tap the progress bar to seek on phone.
- **MK.26 handoff fixes** (`f600fa3`) — handoff forces the internal player on the receiver (HRS-5).

Shipped this pass:
- `versionCode 9`, `versionName 1.3.0`.
- `AUDIT_NOTES.md` — new "MK.26 Track B — On-device Cast proxy + Tier 0 direct-cast" entry
  documenting the per-session LAN network surface and the Tier-0 / Tier-1 split. Existing handoff
  entry trimmed to point at the new entry.

**Deferred (documented, not blocking the release):**

- **MK.26.B.3 Tier 1 proxy hardening** — pre-warm seg 0-2 before `load()`; `-mpegts_flags resend_headers
  -pat_period 0.1`; resolve `-copyts -start_at_zero` vs `-output_ts_offset`; set `hlsVideoSegmentFormat=
  MPEG2_TS`; one-`load()`-per-attempt contract; manifest-vs-real-duration ffprobe diff. Roadmap is
  written; the work is unblocked.
- **MK.26 A.5 Fire TV de-risk + AUDIT_NOTES addendum for handoff** — handoff entry shipped 2026-06-15;
  the on-device Fire TV smoke on `_yancotv._tcp` + multicast-suppressed-AP test is still A.5 scope.
- **HEVC over Cast** — still copies; fails on the Default Receiver until the Phase-2 hardware transcode
  (B.3 phase 2, gated).
- **MB-230 / MB-229 1h Fire TV heap soak** — same accepted-risk position as v1.2.0. The proxy
  hardening + the Tier 0 path (which transfers ZERO bytes through the phone) should lower memory
  pressure on the most common case, but a real soak hasn't been run on this build.

**Release gate (NOT shipped — accepted-risk for v1.3.0):** the MB-230/MB-229 heap soak. Same posture
as v1.2.0. Users casting MP4/HTTPS movies (the Tier 0 path) are well-tested; users casting raw-TS live
through Tier 1 either get a clean toast or a longer-running proxy session — flag if reports of OOM or
hangs surface.

---

## MK.27.HF1 — Fire OS 6 startup compatibility (MB-241) — 2026-07-22

Critical release hotfix for the v1.3.7 startup crash on Fire OS 6 / Android 7.1 (API 25).

- [x] Reproduce the crash and capture the exact `NoSuchAlgorithmException` / initializer stack.
- [x] Verify the installed APK matches the public v1.3.7 SHA-256 and the local release signer.
- [x] Replace the API-26-only PBKDF2 factory initialization with a platform-first API 24/25 fallback.
- [x] Reuse the same KDF path for PIN hashing and encrypted backups without changing stored formats.
- [x] Pass RFC vectors, shared Android tests, and the minified signed release build; hotfix files are lint-clean
  (the repository-wide lint gate still has pre-existing failures in unrelated Cast/Handoff/Cleartext/History files).
- [x] Verify the APK package/version/signature, install with `adb install -r`, and pass repeated cold launches.

Safety boundary: this is an in-place, same-signature APK update. Do not uninstall, clear app data, alter the
database, downgrade the package, unlock the bootloader, or write firmware/recovery partitions.

---

## MK.28 — Full-app audit + fix sweep (insets / touch / TV-focus / threading) — 2026-07-25

> **Numbering note:** a few code comments that predate this section carry "MK.28.x" / "MK.30" / "MK.32"
> sub-slice labels with no matching plan section (session-local numbering from earlier work — tile
> progress, settings search, quick-start CTA). This section claims MK.28 canonically; those comments
> refer to already-shipped work and are unrelated.

User-driven audit (user report: "the screen extended to the lower edge where android controls are and I
wasn't able to press on a button", plus a request to find everything else and make remote + touch smooth).
A 14-agent workflow — 7 finder dimensions (window insets, touch UX, TV focus, threading, player lifecycle,
accessibility, state/back-nav), each adversarially verified — produced **46 confirmed findings, 0 refuted**.
The reported bug is **MB-242 (P0)**: targetSdk 35 makes Android 15+ enforce edge-to-edge and the shell had
zero WindowInsets handling; on TV boxes with overlay nav bars the same edge controls are covered.

Fix sweep shipped same-day as MK.28.1–MK.28.8 (one commit each) + this register. IDs MB-242…MB-285.

### Fixed in this sweep

| MB | P | Fix | Slice |
|---|---|---|---|
| MB-242 | P0 | Edge-to-edge insets: explicit `enableEdgeToEdge()` + `WindowInsets.safeDrawing` padding on the shell's interactive layer, search-overlay panel, and detail overlay; backgrounds stay full-bleed; insets are zero on TV so the Fire TV layout is unchanged | 28.1 |
| MB-243 | P1 | PlayerActivity display-cutout mode `SHORT_EDGES` (video fills the notch on Android 9–14) + corner chrome (Back / zap bar / recording pill) offset by cutout insets so the camera hole never covers them on 15+ | 28.1 |
| MB-244 | P2 | `windowSoftInputMode` adjustPan → adjustResize; safeDrawing (includes IME) resizes the shell instead of the keyboard covering fields | 28.1 |
| MB-246 | P1 | ExoPlayer audio focus (`setAudioAttributes(USAGE_MEDIA, handleAudioFocus=true)`) — no more mixing over other apps, pauses on calls/assistant | 28.2 |
| MB-247 | P1 | Stream-error RETRY reachable on TV: CENTER paths gated on the chrome overlay; LIVE zap keys still work under it | 28.2 |
| MB-248 | P1 | `onStop` surface detach typed (`clearVideoSurfaceView(own SurfaceView)`) — stops stripping the MiniPlayer's re-attached surface on BACK from fullscreen; MB-119 sync-detach guarantee preserved | 28.2 |
| MB-249 | P2 | Keep-screen-on playback-gated in PlayerActivity (window flag + layout `keepScreenOn` removed) — paused/error/sleep-timer lets the display sleep | 28.2 |
| MB-250 | P1 | Guide reminder isSet/set/cancel off the main thread (blocked behind whole-import EPG transaction = ANR-class) | 28.3 |
| MB-251 | P2 | Parental setPin/removePin/verifyPin call sites dispatch DB work to IO | 28.3 |
| MB-252 | P2 | ReminderAlarmReceiver.markFired via goAsync + IO (receiver-ANR window during EPG import) | 28.3 |
| MB-253 | P2 | SourceSyncCoordinator.activeJob race: @Volatile, cleared before the state gate and only by its own job — Cancel can no longer become a silent no-op | 28.3 |
| MB-255 | P1 | Phone Settings BackHandler no longer swallows BACK forever (self-disables at root list) | 28.4 |
| MB-256 | P1 | Launch-intent replay guard (recreation / recents no longer re-fires voice-search overlay or deep-link playback; QUERY extra consumed) | 28.4 |
| MB-257 | P1 | AddSourceDialog full form + visibility `rememberSaveable` (survives app-switch for credentials + SAF picker round-trip) | 28.4 |
| MB-258 | P2 | Remaining user-input state saveable: EPG URL drafts ×2, PIN setup fields, Favorites dialog flags | 28.4 |
| MB-259 | P2 | SourceDetailScreen seed-once (recreation no longer silently reverts unsaved edits); `dirty` saveable | 28.4 |
| MB-260 | P2 | Open detail page persisted by id + re-hydrated after recreation | 28.4 |
| MB-261 | P2 | Recreation no longer kills the mini-preview (stop-on-section-change gated on genuine change) or clobbers restored panelFocus | 28.4 |
| MB-262 | P1 | Search overlay traps D-pad focus (`focusGroup` + `exit = Cancel`, SeasonPickerOverlay pattern) — no more invisible activation of the shell behind the scrim | 28.5 |
| MB-263 | P1 | ContentDetailScreen same trap — LEFT from Play no longer lands on the hidden sidebar / switches sections invisibly | 28.5 |
| MB-264 | P1 | SettingsBackupTab focus-retry rewritten: actually requests focus, and the unbounded per-frame effect-restart chain (constant CPU churn, MB-229/230 aggravator) is gone | 28.5 |
| MB-265 | P2 | Guide window-regain focus restore (BACK from fullscreen player no longer leaves a dead selector) | 28.5 |
| MB-266 | P1 | Coverflow tap-to-select: tapping a non-centered orb selects it (preview pane / Favorite CTA / auto-preview finally follow touch); tap on centered orb activates; TV CENTER unchanged | 28.6 |
| MB-267 | P1 | Pagination keys on scroll frontier too — touch can now load past item 100 of a category | 28.6 |
| MB-268 | P1 | Orb touch long-press opens the channel context menu (rename/logo/lock/hide/share had no phone path) via combinedClickable | 28.6 |
| MB-269 | P2 | BACK from an empty category no longer exits the app (back-chain armed on state, not just focus) | 28.6 |
| MB-270 | P2 | Pressed-state feedback on orbs (first touch feedback in the app; app-wide rollout tracked below) | 28.6 |
| MB-271 | P2 | SettingsSlider touch: tap-to-jump + drag-to-scrub on the track | 28.6 |
| MB-272 | P1 | Fullscreen live player: Channels button in phone chrome opens the surf overlay (zapping had zero touch path); tap-on-video dismisses the open panel | 28.7 |
| MB-273 | P2 | VOD dock remote-hint strip (OK HIDE / BACK glyphs) TV-only | 28.7 |
| MB-274 | P2 | Quick-info (stream stats) touch entry: long-press the More button | 28.7 |
| MB-275 | P1 | Detail-page favorite toggle announces state to TalkBack ("In favorites" / "Favorite", FeatureHero/MB-59 pattern) | 28.8 |
| MB-276 | P2 | `selected` semantics on CategoryRail pill, sidebar rows, settings tabs, favorites list tabs, source-type chips, accent chips | 28.8 |
| MB-277 | P2 | ParentPinRow + GroupRow as `toggleable(Role.Switch)` (state finally announced) | 28.8 |
| MB-278 | P2 | SettingsSlider semantics: label, `progressBarRangeInfo`, `setProgress` action | 28.8 |
| MB-279 | P2 | Live regions: EPG sync status/errors + PIN dialog errors announce (first `liveRegion` uses in the app) | 28.8 |
| MB-280 | P2 | Coverflow orb descriptions include locked / watched / in-progress state | 28.8 |
| MB-281 | P2 | TvLongClickable exposes an `onLongClick` semantics action so the context menu is reachable under TalkBack | 28.8 |
| MB-282 | P2 | playHandoff header staging commits only on NewTarget — rejected/same-id handoffs (and local re-taps of a playing handoff stream) no longer rewire the live stream's UA/Referer | 28.8 |
| MB-283 | P2 | Home "Recently added" re-reads after sync completion + hidden-set changes; On Now / Up Next re-pairs every 5 min so ended programmes leave the rail | 28.8 |
| MB-284 | P2 | BrowseSection expand state genuinely `rememberSaveable` (survives rotation); MK.20.3.2 spec text corrected — cross-type persistence explicitly deferred to MK.27.B | 28.8 |
| MB-285 | P2 | TextFaint raised from ~1.9:1 to ~3:1 contrast across all four palettes (was used for real instructional text) | 28.8 |

### Filed open (deliberately not fixed in this sweep)

| MB | P | What | Where it lands |
|---|---|---|---|
| MB-245 | P2 | Full type-ramp contrast rework (TextMuted is also below 4.5:1; MB-285 only lifted the worst tier to large-text AA). Needs an on-device visual pass with the user | Appearance polish w/ user sign-off |
| ~~MB-254~~ | P2 | ~~RecordingScheduleReceiver main-thread schedule writes can block behind the whole-import EPG transaction~~ — **FIXED 2026-07-28 as MK.30.5.** Both schedule receivers goAsync onto IO; MB-208 ordering and the MB-214 cancellation guard preserved. Root cause (the import holding one write transaction) filed separately as **MB-315, still open** | Done |
| — | P1 | Coverflow scroll-follows-selection on phone (snap fling + centered-item derivation — the full wheel metaphor; MB-266 tap-to-select is the shipped interim) | MK.27.B |
| — | P2 | Double-tap seek + swipe volume/brightness in the phone player | MK.11.2 (already planned) |
| — | P2 | Pressed-state feedback app-wide (remaining primitives: YancoButton family, SettingsChip/Row/Toggle, HexPillRow, sidebar rows, guide blocks, surf rows) | MK.27.D/E |
| — | P2 | Phone BACK at Settings root now falls through to the shell chain (sidebar-focus → exit); a real section back-stack is the better phone model | MK.27.B |

### Verification status (honest)

- Every slice compile-verified (`:app:compileDebugKotlin` green per commit). **No device was reachable
  this session** — Fire TV timed out at both known IPs (.56 / .74), no USB phone.
- **Required before calling MK.28 closed:**
  1. Cascade-nav smoke test (3 flows, ~60 s) on Fire TV — 28.4/.5/.6 touched HomeScreen, BrowseSection,
     CoverflowSectionScreen.
  2. Android 15 phone pass: bottom-edge controls tappable (MB-242), keyboard resize (MB-244), notch
     chrome (MB-243), tap-select browse (MB-266..268), Settings BACK (MB-255).
  3. TalkBack spot-check of MB-275…281.
- `:app:testDebugUnitTest` green after the full sweep. `:shared` untouched this session (its suite not
  re-run; the working tree carries the separate uncommitted MK.27.HF1 crypto refactor, deliberately
  left alone).

---

## MK.29 — Browse preview redesign + pre-play subtitles + TV type ramp — 2026-07-26

User-driven, three reports in one message: (1) movie/series preview art was cropped, (2) the meta
beside it should be title → description → actions, with a subtitles dropdown that carries into
playback, (3) "the fonts are too large and not optimized correctly to its surrounding … recalculate
all the geometry."

All measurements in this section were taken on the connected **Fire TV Stick AFTMM** via
`uiautomator dump`, not estimated: viewport **960 x 540 dp** (1920x1080 @ densityDpi 320), device at
**fontScale 1.0** (confirmed — 2 lines of the 40/48 sp `DisplayM` measured 190 px vs 192 predicted).

### Slices

| Slice | What | Register |
|---|---|---|
| MK.29.1 | Poster-shaped VOD preview frame (2:3, `ContentScale.Fit`, `BoxWithConstraints`-sized); LIVE keeps the wide MiniPlayer box | MB-303 |
| MK.29.2 | Meta column restructured: title → facts line → plot → actions. Plot via `ContentDetailService` (cache-first, 450 ms dwell debounce + per-session id→metadata cache, so spinning the wheel issues zero provider calls) | — |
| MK.29.3 | Pre-play subtitle picker (provider `subtitles[]` + OpenSubtitles), staged into `PlaybackController` and consumed by `loadCurrent` | — |
| MK.29.4 | Compressed TV type ramp + geometry recalibration; 149 literals across 31 files normalised | MB-304 |
| MK.29.5 | Brand marks: badge-only `ic_logo_mark`, adaptive launcher icon rebuilt transparent, TV banner regains the wordmark, `GenIcons.java` rewritten | MB-305 |
| MK.29.6 | Player SUBTITLES panel: external subtitle as observable state (fixes the false "Off"), real track labels, viewport-derived panel height, two-line rows | MB-306 |

### MK.29.3 — design notes

- **Why staging, not `applyExternalSubtitle`.** That method operates on `_currentItem`, which at pick
  time is still the *previous* title. Calling it after `play()` would prepare the stream once without
  the subtitle and immediately re-prepare with — a visible double buffer on every subtitled start.
  `stageExternalSubtitle(contentId, uri, mime)` parks the pick; `loadCurrent` promotes it after every
  transition path has run its `_externalSubtitle = null` reset and *before* `buildMediaItem`, so the
  first prepare already carries the subtitle. Consumed one-shot and id-matched, so an abandoned pick
  can never attach to an unrelated later stream. Text tracks are un-disabled before `setMediaItem`,
  the same ordering rule `applyExternalSubtitle` documents.
- **Watch routing.** Movie → plays (new `onPlayNow`, parental-gated, with the hard-rule-8
  already-playing guard). Series → opens episodes; a series container has no stream, so "which
  episode's subtitle" has no pre-play answer. The orb's own OK press still opens detail for both.
- **Accepted limitation, surfaced in the UI.** Subtitle tracks muxed *inside* the stream cannot be
  enumerated before ExoPlayer opens the media. They stay in the player's SUBTITLES panel and the
  picker's footer says so.
- **Quota.** The anonymous OpenSubtitles key allows **5 downloads/day**. Search is unlimited;
  only committing to a track spends one. Download happens at *pick* time so a failure (including
  hitting that ceiling) surfaces in the sheet instead of producing a silently subtitle-less stream
  two presses later.

### MK.29.4 — the ramp is compressed, not scaled

| role | before | after | after, arcmin @ 3 m / 55" |
|---|---|---|---|
| Caption / Overline | 11 sp | 12 sp | 17.4 |
| Body | 13 sp | 14 sp | 20.3 |
| BodyLong / Label | 14 sp | 15 sp | 21.8 |
| TitleS | 15 sp | 16 sp | 23.2 |
| TitleM | 18 sp | 19 sp | 27.6 |
| TitleL | 22 sp | 23 sp | 33.4 |
| DisplayS | 30 sp | 26 sp | 37.7 |
| DisplayM | 40 sp | 30 sp | 43.5 |
| DisplayCinematic | 44 sp | 34 sp | 49.3 |

Two problems pulling opposite ways, which is why no single multiplier could fix it: the floor was
under the readability threshold *and* the ceiling was out of proportion to its container. Ratio
4.4x → 2.8x. This closes the item MB-300 filed as "remaining: the per-role TV type ramp".

Geometry: `heroHeight` 520 → 330 dp (520 was **96% of the TV viewport**), `detailPosterWidth`
240 → 200, detail hero content offset tokenised (`220.dp` literal → `detailHeroContentOffset`),
`posterAspect` / `posterSlotWeight` added. Four dead tokens (`groupsWidth`, `infoWidth`,
`rowHeight`, `rowHeightWithEpg`) documented as **not** recalibrated — they have no call sites and
must be re-derived before first use.

### MK.29.5 — the brand rule

**The asset follows the shape of the slot.** Square or near-square → badge alone
(`ic_logo_mark`). Wide → badge + wordmark (`ic_logo`). Never anisotropically scaled; every
call site uses `ContentScale.Fit`.

| Slot | Shape | Asset |
|---|---|---|
| Sidebar, collapsed (92dp) | square | `ic_logo_mark` |
| Sidebar, expanded (260dp) | wide | `ic_logo` |
| Settings header (`height(64.dp)`, free width) | wide | `ic_logo` |
| About tab (`size(140.dp)`) | square | `ic_logo_mark` |
| Notification `setSmallIcon` | square, alpha-only + tinted | `ic_logo_mark` |
| Launcher (adaptive + legacy) | square, masked | badge |
| TV banner (320x180) | wide | badge + wordmark |

Source is `drawable-xhdpi/ic_logo.png`, **not** `yancotv_logo.png` at the repo root — the root
file is larger (1536x1024) but flat-rendered on an opaque grey backdrop, so its alpha bbox is the
whole canvas. The badge is extracted by mirroring the flourish-free left half about x=137
(pixels, not just alpha — see MB-305 for why the alpha-only attempt was rejected).

`tools/GenIcons.java` was rewritten rather than superseded by a side script: it is the documented
generator, and its old center-crop-to-square is precisely what produced the rectangular plate.
Left alone, the next run of it would revert this slice. Regenerating is
`java tools/GenIcons.java` from the repo root; it was re-run from a clean `git checkout` of
`res/` to confirm it reproduces the committed assets.

**Follow-up, not done:** the notification small icon would ideally be a dedicated monochrome
vector. `ic_logo_mark`'s alpha tints to a solid hex silhouette, which is legible and a large
improvement on the lockup, but it is not purpose-drawn for a 24dp status-bar slot.

### Verification status (honest)

- `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:ktlintCheck` green. `:app:assembleRelease`
  (R8 + resource shrink) green; installed to the AFTMM as a **signed update** — the device runs a
  release build, so a debug APK cannot update it and uninstalling would have destroyed the user's
  sources/history. No fatals in logcat after install.
- **Not done by me:** on-device navigation. Driving the remote with `input keyevent` while the user
  was on the TV escaped the app into a Fire OS page; the user stopped it and the rest is a **user
  visual check**.
- **Required before calling MK.29 closed:**
  1. Movies + Series browse: whole poster visible, title/description/actions read in proportion.
  2. Subtitles picker: opens, lists, a pick survives into playback with subtitles on screen.
  3. Watch on a movie plays directly; OK on the orb still opens detail; Series reads "Episodes".
  4. Cascade-nav smoke test (3 flows) — `CoverflowSectionScreen`, `BrowseSection`, `HomeScreen` all touched.
  5. A read of Settings / Guide / Recordings at the new ramp, since 149 literals moved.
  6. MK.29.6: pick a subtitle pre-play, open MENU → SUBTITLES **immediately**. It must show the
     pick (with "Loading…" if the sidecar is still in flight), never "Off". Then confirm the row
     turns into a named track, and that "Off" actually stays off across the next episode / resume.
  7. MK.29.5: sidebar badge collapsed vs expanded, About tab, and the Fire TV home-row banner.
     The launcher icon itself needs a **home-screen** look — it was verified as pixels
     (rendered at 1:1 and under a simulated 72/108 circular mask) and as a packaged resource
     (`aapt2 dump resources` lists `drawable/ic_logo_mark`, `mipmap/ic_launcher*`), but never
     on a launcher surface.

---

---

## MK.30 — Settings scroll integrity + source expiry + update awareness — 2026-07-28

User request, four items. The localization item became MK.31 (its own milestone — the
string-extraction surface is ~613 literals, an order of magnitude past anything here).

> **Numbering note (extends the MK.28 note above):** MK.30 is now canonically claimed by this
> section. The stray `MK.30` / `MK.32` sub-slice labels in code comments predate it (session-local
> numbering from the settings-search / tile-progress / quick-start-CTA work) and are unrelated.

**User reports, verbatim:**

1. *"i was in playback section of settings and when i went down in it then up again. the top of the
   menu was cut. i think i noticed this behavior in multiple places. i want the settings section to
   be managed well so it doesnt have these kind of problems."*
2. *"in sources, there is no place that i found that tells us the date that the list would expire."*
3. *"i want the user to know that there is a new update whenever i push a new update to the latest
   release spot."*

### MK.30.1 — clipped section headers in Settings (MB-307) — shipped

Root cause was `rememberSafeMarginBringIntoViewSpec` in `SettingsScreen.kt`. Its `SAFETY_MARGIN_DP
= 32` headroom is smaller than a `SettingsSection` header (title 19sp + 6dp + subtitle + 16dp
≈ 80dp), so travelling back up parked the first row 32dp from the viewport top, declared itself
satisfied, and stopped — with the header still clipped and nothing focusable above it to scroll to.
Permanent until the tab remounted.

Replaced by `ui/focus/FocusScrollSpec.kt`: a pure, unit-tested `focusScrollDistance` behind a
`BringIntoViewSpec`, with three further defects fixed (a focusable taller than the viewport was
never scrolled into view at all; a row too tall to satisfy either guard fell through both to "no
scroll" and stayed clipped; no epsilon, so sub-pixel residue could re-trigger a scroll). Provided
once in `SettingsScreen` rather than inside `ContentPane`, which had left the phone drilled-in body
and tab-hosted dialogs on Compose's flush-to-edge default.

**Over-requesting at the top of the content is intentional.** The scroll clamps to 0 — which is
exactly the wanted outcome, header and container top-padding both revealed — and Compose's
bring-into-view animation cancels itself once a step stops being consumed.

### MK.30.2 — app-wide focus-scroll audit (MB-308) — shipped

Surveyed all 21 focus-driven scroll containers outside Settings for the same defect. Two changes to
the primitive first: headroom capped at 25% of the viewport (one 96dp value cannot serve both a
~400dp Settings pane and the heightIn-capped player options menu), and `ProvideDefaultFocusScroll`
added, because `LocalBringIntoViewSpec` is **axis-agnostic** — one spec serves the vertical scroll
it was tuned for *and* any horizontal rail nested inside it, where leading headroom would shove the
focused card off the leading edge.

| Surface | Verdict |
|---|---|
| `HomeContent` | fixed — `RailHeader` above every `WheelRow` |
| `FavoritesScreen` | fixed — 3 `SectionHeader` items |
| `RecordingsScreen` | fixed — 3 `SectionHeader` items |
| `SearchScreen` | fixed — per-rail titles; its rail `LazyRow` opted back out |
| `ContentDetailScreen` | fixed — hero + `episodes_header` |
| `PlayerOptionsMenu` | fixed — the OPTIONS caption |
| `VodPlayerChrome` | fixed — error headline above the action buttons |
| `WheelRow` | already immune — provides its own `CenterBringIntoViewSpec`, so nearest-provider keeps every coverflow / poster rail byte-identical |
| `AppSidebar`, `CategoryRail`, Guide list, `ChannelSurfOverlay`, `PreviewSubtitlePicker` | no leading non-focusable content — nothing to clip |
| `PlayerOptionsPanels` | its `PanelHeader` sits outside the scroll viewport; scrolling cannot clip it |
| `CategoryChipBar`, coverflow `LazyRow`, Guide hScroll x2, Favorites tab strip | horizontal, and not nested under any wrapped surface |

### MK.30.3 — provider account expiry in Sources (MB-309) — shipped

`XtreamUserInfo.expDate` had been parsed since the client was written; `syncXtream` checked the
handshake for errors and threw the payload away. Schema `11.sqm` (v11 → v12) adds
`sources.expires_at`, nullable with **no default** — a default would render as a real expiry date on
every source that has never synced. NULL legitimately covers three cases the wire format cannot
distinguish: m3u sources have no account metadata, an xtream source has not re-synced yet, and
Xtream omits `exp_date` for non-expiring accounts.

Written via a dedicated `setExpiresAt`, not as a field on `updateSyncResult` — that runs on the
failure path too and would clobber a known expiry with NULL on every errored sync. A stale expiry
beats none. `parseXtreamExpiry` normalises the field (nominally Unix seconds in a string, unreliable
in practice: `"0"`, `"Unlimited"`, ISO dates, and already-in-ms values all seen; all unparseable
shapes map to null rather than a confidently wrong date).

**Xtream only.** `StalkerAuthInfo` does not parse an expiry today (Stalker's `account_info` exposes
`expire_billing_date` — a follow-up if wanted), and M3U URLs have no expiry concept at all.

Also fixed a latent test bug this exposed: `MigrationTest`'s v9 → v10 hop read its deliberately-v10
fixture through `selectById`, which is `SELECT *` against the *current* schema. It only ever passed
because `10.sqm` added an index and no column; the next additive migration was always going to break
it.

### MK.30.4 — update awareness outside Settings (MB-310) — shipped

The machinery was ~80% built (checker, repository, periodic worker already scheduled, installer,
About banner). The gap was that nothing *told* the user. Added a deduped system notification from
the worker plus a badge on the sidebar's Settings row.

**Found while red-teaming:** the badge would have been blank on almost every cold start.
`UpdateRepository.info` is in-memory by design and `schedulePeriodic` uses `KEEP`, so on an
established install no check runs at launch — badge and banner would sit empty for up to a day after
a release shipped. Added `enqueueStartupCheck`, throttled to once per 6h, deliberately without
`KEY_FORCE` so opting out of auto-check opts out of this too.

---

## MK.31 — Localization: Arabic / French / Spanish / English + full RTL — started 2026-07-28

User request: *"lets translate the app to arabic, french and spanish beside english and have the
user be able to change the app language."* Arabic scope decided with the user: **full RTL
mirroring**, not Arabic-text-in-LTR-layout.

### Scope measured before starting

There were **zero** `stringResource` calls in the app. Every UI string was a Kotlin literal:

- ~613 unique literals (930 occurrences) under `ui/`
- 11 `displayName` enums outside `ui/` feeding chip labels (`prefs/AppPreferences.kt`,
  `ui/theme/ThemeController.kt`, `player/ExternalPlayer.kt`)
- user-visible strings in `packages/shared/` (sync phase labels, source sub-lines, error text)

### MK.31.1 — locale infrastructure + picker (MB-311) — shipped

`AppCompatDelegate.setApplicationLocales` is the documented answer and is **not sufficient here**.
Its pre-API-33 backport applies the locale through `AppCompatDelegate`, which only exists on
AppCompat components — `MainActivity` is a `ComponentActivity` whose theme descends from
`android:Theme.Material.NoActionBar`, and promoting it would force a Theme.AppCompat migration
across the whole shell for an unrelated reason. **Fire TV is API 28, i.e. exactly the case the
backport exists to cover**, so this is the normal path, not an edge case.

Resolution: apply the locale directly via `LocaleController.wrap()` from `attachBaseContext` on both
activities — base-class- and API-agnostic. `setApplicationLocales` is still called alongside, and on
API 33+ the choice is mirrored into the platform `LocaleManager`, so AppCompat screens and the
system per-app-language screen agree with our state.

`wrap()` uses `Configuration.setLocales`, **not** `setLocale` — the latter leaves `layoutDirection`
untouched, which is precisely what would make Arabic render LTR while claiming to be Arabic.

Language is the one preference **not** in the SQLDelight settings table: `attachBaseContext` runs
before an Activity is usable and can precede a ready Koin graph, so the read must work with nothing
but a Context. A one-key SharedPreferences file has no initialisation order to get wrong.

### MK.31.2 — RTL layout + focus-direction pass (MB-312) — shipped

MK.31.1 made Arabic actually set `Configuration.layoutDirection` (via
`setLocales`, not `setLocale`), so the shell **already mirrors**. These were live
bugs the moment the picker shipped, not future work.

The audit's good news: the codebase already uses logical layout modifiers
throughout. Zero hits across the whole tree for `padding(left =)`,
`Alignment.Absolute*`, `absoluteOffset`, or `TextAlign.Left/Right`, so Compose
mirrors the layout for free. The problem was narrowly the hardcoded D-pad
handlers, which *cannot* mirror: `Key.DirectionLeft/Right` are hardware key
codes, and `FocusDirection.Left/Right` are physical too.

`ui/focus/DirectionalNav.kt` introduces **startward** ("back out toward the
sidebar") and **endward** ("go deeper"), resolved against `LocalLayoutDirection`,
plus `Modifier.onStartwardKey` / `onEndwardKey`. The callback returns whether it
consumed the press — a real per-site decision, since pane-escape handlers must
consume while the coverflow must fall through to the `LazyRow` mid-wheel.

Converted (all previously physical): `AppSidebar` exit, `CategoryRail` exit +
commit-and-enter, Guide channel-column escape, coverflow leading-orb escape,
coverflow Watch CTA, Settings sidebar exit, Settings tab select-and-enter,
`ContentPane` `focusProperties.exit`, `leftExitsTo` → `startExitsTo`, the
`moveFocus` that lands focus in the content pane, and the player option cycler.

> `focusProperties`' RTL-aware `start` / `end` slots are **not** a substitute for
> the `exit` lambda. `start` always redirects; `exit` fires only when no in-group
> target exists — which is exactly what lets chip rows and sliders keep their own
> horizontal navigation.

**Two deliberate non-conversions**, documented in place so a future sweep for
`Key.DirectionLeft` doesn't "fix" them:

- **`VodPlayerDock` seek stays physical.** Media timelines don't mirror under
  RTL — platform playback UI and every mainstream video app in Arabic keep the
  scrubber left-to-right, and LEFT = rewind is muscle memory independent of
  reading direction. Mirroring it would make Arabic users seek backwards.
- **The settings slider needed the opposite treatment** and was broken on all
  three axes: its fill is drawn from `Alignment.CenterStart`, which *is*
  mirrored, so under RTL the minimum sits at the right edge — while the keys and
  the `x / width` touch mapping stayed physical. Pressing "left" would have
  raised the value as the fill shrank rightward, and tapping the visually-empty
  end jumped to the minimum. Keys are now logical; the touch mapping is extracted
  to a pure, tested `sliderValueForX`.

Tests pin that **LTR behaviour is byte-identical** to the hardcoded keys it
replaced — the property that matters most before device verification.

### MK.31.3 — enum labels + Settings tab names (MB-313) — shipped

Chosen for leverage, not size: the seven `displayName` enums were the structural
blocker for every later batch, feeding chip labels across General, Playback,
Network and the player's option panels. Extracting tab bodies while the chips
inside them stay hardcoded buys nothing.

`SettingsTab`, `OpenOn`, `ChannelNumberFormat`, `ResizeMode`, `BufferProfile`,
`DefaultExternalPlayer`, `UserAgentPreset` now carry `@StringRes val labelRes`.
`displayName` is removed, not kept alongside, so nothing can keep reading the
untranslated value.

Not every value is prose. Numerals (`001`), aspect notation (`16:9`) and
third-party product names (VLC, MX Player, Kodi, ExoPlayer) are
`translatable="false"` and live only in `values/`. The consistency test enforces
that in both directions and asserts some exist, so a broken regex can't make the
check pass vacuously.

Two API changes fell out of doing it properly:

- `SettingsChipRow`'s `label` lambda became `@Composable (T) -> String` — it is
  already invoked from composable scope, so this costs nothing.
- `searchTabs` / `searchSettings` take a `tabLabelOf` resolver rather than
  reading `tab.label`. They stay plain functions (unit-testable), but tab
  matching now runs against the **localized** name, so an Arabic user searching
  in Arabic finds the tab.

`semantics {}` and `remember {}` are not composable, so affected TalkBack
descriptions and resolvers are computed just above the modifier chain — the
natural-looking inline `stringResource` does not compile there.

### MK.31.4–31.17 — full string extraction — shipped

Settings chrome and bodies, the per-setting search index, the shell, the player,
detail, and dialogs. 21 → ~710 keys across all four locales.

### MK.31.18 — `packages/shared` strings via a typed result (MB-331) — shipped

`SyncProgress.message: String?` → `detail: SyncDetail?`, a sealed interface of 11
cases. `commonMain` cannot reach `R.string` (AGENTS.md hard rule #1 — `androidx`
there breaks the iOS target), so the shared module reports *what happened* and
`android/sources/SyncDetailText.kt` decides *how to say it*. iOS maps the same
sealed type to its own `Localizable.strings` when that target lands.

`SyncDetail.Failure` keeps a `String` deliberately: provider HTTP bodies and
exception messages are not a closed set. Both mapper overloads funnel it through
`redactCredentials` (MB-292) rather than trusting call sites. `describeFailure` is
injected into `SourceSyncCoordinator` so that class needs no `Context` and its JVM
test needs no Android runtime. `packages/core` has no `SyncProgress`, so AGENTS.md
rule #8 does not apply.

### MK.31.19–31.25 — corrective sweep (MB-332) — shipped

The per-area passes above reported clean but were verified with ad-hoc greps that
silently dropped long strings and anything containing an em-dash. A proper
detector (over-report, then hand-triage) found **~190 user-visible strings still
hardcoded across 30 files** — dialog interiors, error/status text, and the bodies
of the "later milestone" placeholder tabs. Final count: 1024 keys in `values/`,
967 in each of ar/fr/es.

**The lesson worth keeping:** a sweep driven by reading the top of each file finds
section headers and primary labels and misses everything else. Anything that
claims to be exhaustive needs a detector whose output you can diff against the
code, and the detector needs testing against a string you *know* is there.

Six real bugs surfaced, none of them translations:

| Bug | Why it mattered |
|---|---|
| `SettingsBackupTab` picked its error colour with `status.startsWith("Export failed")` | Translating the string makes the match never fire — every failure would render in the muted colour in ar/fr/es. Error-ness now travels as a boolean beside the text |
| `hc_watched_of_total` declared `%1$d` but was fed `formatMmSs()` output | `IllegalFormatConversionException` on the home screen's continue-watching tile, in every locale. Caught by lint's `StringFormatMatches`; `assembleRelease` and 933 unit tests both passed |
| `app_font_scale_hint` carries a literal "100 %" | Correct fr/es typography, but lint reads `% e` as a malformed conversion. `formatted="false"` now states the no-args contract |
| `RecordingsScreen.metaLine` pinned `Locale.US` | Arabic UI rendered "Mar 3" in Latin digits beside sibling lines using the app locale |
| `HomeContent.formatClock` hand-rolled a 12-hour clock with literal "AM"/"PM" + `Locale.ROOT` | English meridiem markers and Latin digits in an Arabic UI. Replaced with `SimpleDateFormat("h:mm a")` on the default locale |
| `HomeContent.secondaryLine` fell back to `item.type.name.lowercase()` | Raw enum name — "Live"/"Movie"/"Series" read as English in every locale |

Deliberately left literal, each checked individually: the player's monospace
diagnostic readouts (`res`/`codec`/`bitrate`/`buffer`, and the VOD error panel's
`source`/`stream`/`remote`/`attempt`) — technical identifiers a user matches
against provider docs; animation `label =` arguments, which Compose never renders;
`ExternalPlayerApp.displayName` (VLC / MX Player / Just Player are product names);
`"—"` placeholders, which are notation; **endonyms in the language picker** —
"العربية", never "Arabic", because that screen is the one place a user may land
while the app is in a language they cannot read; and provider/transport error
text, where only the frame around it is localized.

`ThemeId` / `AccentId` moved to `@StringRes`. The persistence contract is
untouched: `fromKey` has always matched `it.name`, the Kotlin identifier, so a
translated label cannot corrupt a stored preference.

### Open — MB-314: horizontal gradients do not mirror

`Brush.horizontalGradient` is not layout-direction aware (15 call sites,
including the sidebar row fill and the `SettingsSection` header hairline). Under
RTL the directional ones point away from the accent rail.

Deliberately deferred to the device pass rather than fixed blind: they are purely
visual, need eyes to validate, and several are symmetric (transparent →
colour → transparent) where reversing is a no-op — so a blanket fix would be
churn. Fixing them without a screen risks making them worse with no way to check.

### Register

| Slice | What | Register |
|---|---|---|
| MK.30.1 | Settings focus-scroll spec rewritten as a tested primitive (`ui/focus/FocusScrollSpec.kt`); header-clearing headroom, tall-focusable handling, epsilon guard; provided once for both TV and phone layouts | MB-307 |
| MK.30.2 | App-wide audit of 21 scroll containers; 7 fixed, 1 horizontal rail opted out, rest surveyed and cleared with reasons; headroom capped proportionally so one spec serves every viewport size | MB-308 |
| MK.30.3 | `sources.expires_at` (`11.sqm`, v11 → v12) + `parseXtreamExpiry` + expiry in the Sources list sub-line and detail screen; `MigrationTest` v9 hop de-coupled from the current schema | MB-309 |
| MK.30.4 | Deduped update notification from the periodic worker + sidebar badge + launch-time check (the badge was otherwise blank on cold start) + deep-link into Settings → About | MB-310 |
| MK.31.1 | `LocaleController` / `AppLanguage` / `locales_config.xml` / ar+fr+es resource sets / Settings → General picker; `attachBaseContext` wrapping because the AppCompat backport cannot reach a `ComponentActivity` | MB-311 |
| MK.31.2 | `ui/focus/DirectionalNav.kt` startward/endward primitives; 11 physical D-pad handlers converted; slider keys + touch mapping made logical; seek deliberately left physical | MB-312 |
| MK.31.3 | 7 `displayName` enums + `SettingsTab` labels → `@StringRes`; `translatable="false"` for numerals / notation / product names; search matches localized tab names | MB-313 |
| MK.31.4–.17 | Settings chrome + bodies, search index, shell, player, detail, dialogs — 21 → ~710 keys | MB-313 |
| MK.31.18 | `SyncDetail` sealed type replaces `SyncProgress.message`; Android maps it to resources, iOS will map the same cases; `Failure` stays free-text and always redacted; `describeFailure` injected so the coordinator needs no `Context` | MB-331 |
| MK.31.19–.25 | Corrective sweep after the ad-hoc greps proved unreliable: ~190 strings across 30 files, → 1024 keys / 967 per locale. Six non-translation bugs fixed (prefix-match error colouring, a `%1$d`-fed-a-String crash, `formatted="false"`, two `Locale.US`/`Locale.ROOT` leaks, a raw-enum fallback) | MB-332 |
| — | **OPEN:** `Brush.horizontalGradient` does not mirror under RTL (15 sites) — deferred to the device pass, see MK.31 above | MB-314 |

**Verification status (updated 2026-07-31).** Every slice is build-, lint- and
unit-test-green: 933 tests, `ktlintCheck` clean, `:app:assembleRelease` (R8 +
resource shrinking) green, and Android lint at zero
`MissingTranslation` / `ExtraTranslation` / `StringFormat` / `MissingQuantity`
findings. Locale key parity is checked programmatically, not by eye: 1024 keys in
`values/`, 967 in each of ar/fr/es, with the delta accounted for exactly by the 57
`translatable="false"` entries plus `app_name`, and no missing or extra keys in any
locale.

Lint's remaining **18 errors are pre-existing** — 10 `RestrictedApi` in
`RecommendationsSync.kt`, 4 `UnsafeOptInUsageError`, 4
`ProduceStateDoesNotAssignValue`. Confirmed by stashing the working tree and
re-running lint against the committed tree, not assumed.

**Exercised on a Google TV (Chromecast, `192.168.50.129:45723`, API 34):**

- Release APK installs over the release-signed build and launches with no
  `FATAL EXCEPTION`, no `Resources$NotFoundException`, no format-conversion
  exception.
- `aapt2 dump resources` on the shrunk release APK confirms all four locales
  survive R8 + resource shrinking across a sample of the new keys.
- **Arabic end-to-end.** Switching the app language in Settings → General flips
  the *whole shell*, not just Settings: sidebar (`الرئيسية` / `القنوات المباشرة`
  / `دليل البرامج` / …), rails (`متابعة المشاهدة`, `لك`), and CTAs
  (`شاهد الآن`). Numbers render Arabic-Indic (`بقي ٤٣ د`), which is the runtime
  proof that `%1$d` resolves through the app locale. Provider-supplied strings
  correctly stay untouched ("FRANCE NETFLIX", "Virgin River").
- **RTL mirroring.** The sidebar is reached with D-pad **RIGHT** under Arabic and
  LEFT moves away from it — MK.31.2's logical-direction work behaving correctly
  on hardware.

**Still unverified anywhere, and why:**

- **Fire TV (API 28) has not been touched.** It is the only target where the
  AppCompat locale-backport gap and the software-decoder limits appear; the API-34
  Chromecast cannot stand in for it.
- MK.30.1 / .2 / .6 focus-scroll behaviour needs a scroll pass through every
  Settings tab plus the `native-android-mk` cascade-nav smoke test, run twice —
  English for regression, Arabic for mirroring.
- **Source expiry** (MK.30.3) needs a live Xtream re-sync to populate
  `expires_at`; no synced Xtream source was available.
- **Update notification + sidebar badge** (MK.30.4) needs a release actually
  published to the releases repo.
- **MK.30.5 recording fix** needs a scheduled recording that fires *during* an EPG
  refresh — that collision is the bug, so a recording that merely fires proves
  nothing.
- **Native-speaker review of `values-ar/strings.xml`.** ~2,800 translation strings
  were machine-generated in-session and one authoring error was already caught and
  fixed (المطهر, "the purifier", for المظهر, "Appearance"). Assume more.

## MK.33 — Multi-playlist categories — started 2026-07-31

User-reported, 2026-07-31: with two playlists loaded there was no way to tell
whose channels were whose, and the ADD SOURCE button could not be reached to add
the second one in the first place.

### MK.33.0 — ADD SOURCE unreachable (MB-333) — shipped

See the commit for the focus analysis. Short version: the button is a sibling of
the LazyColumn, spatial search escalates past it to the ContentPane boundary, and
MK.30.7's `Up -> Cancel` end-stop then pins focus on the row. Fixed with a
two-way `onPreviewKeyEvent` bridge. `focusProperties { up = … }` does **not**
work here in either chain position — that is written up in the commit message and
in the parameter docs on `SourceListRow`, because it looks like the obvious fix.

### MK.33.1 — categories bucketed per playlist — shipped

**What TiviMate does, and what we took from it.** TiviMate keeps each playlist's
groups separate by default and offers a *Merge* toggle (Playlist settings → Group
channels) to unify same-named groups across playlists, plus *Hide duplicates*.
Switching playlists is a separate "Playlists" section in its menu. The lesson
worth taking is the mental model — with more than one playlist a user thinks
*provider first, category second*. The lesson NOT worth copying is the
implementation: TiviMate needs a second menu surface because its category list is
flat. Ours is not — MK.20.3 already shipped a collapsible Parent/Leaf rail — so a
playlist is just a `CategoryNode.SourceParent` in the rail we already have. No
new surface, and the expand/collapse focus behaviour is already tested.

YancoTV previously behaved as if Merge were permanently ON: `distinctGroupsForType`
groups by `group_name` across the whole catalogue, so two providers' "Sports"
collapsed into one row.

**Shape.** One level deep, matching the prefix tree: playlist → its groups, in
provider order, each dropdown leading with an "All" entry for everything that
playlist contributes. Only engages when **more than one** playlist supplies rows
of the current type; a single playlist keeps the existing flat / prefix-bucketed
rail untouched.

**Decisions (user, 2026-07-31):**

- **Playlist bucketing replaces prefix bucketing** when >1 playlist, rather than
  nesting inside it. Playlist → Arabic → Sports is three deep, and a three-deep
  tree on a 380dp rail driven by a D-pad at 3m is not navigable. Prefix bucketing
  still applies whenever one playlist supplies the type.
- **Group preferences stay global** — hide/pin/rename remain keyed on
  `(content_type, group_key)` with no source dimension, so hiding a junk group
  hides it in every playlist. No migration. Revisit if it proves wrong.
- **No Merge toggle for now.** Today's behaviour *is* the merged mode, so the
  toggle is additive and much cheaper to add once the separated path exists.

**Why the selection key is an encoded string and not a sealed type.** A category
selection is a single `String` throughout the shell, with two reserved synthetic
values already (`__all__`, `__favorites__`). A scoped key extends that convention
(`SourceScopedGroup`, separator U+001F — every printable candidate including `|`,
`:`, `/` and `-` appears in real provider group titles). The rail's `Leaf` already
separates `label` from `groupName`, so a scoped leaf is an ordinary leaf with an
encoded key and **the rail needed no changes at all**. A sealed type would have
meant a custom `Saver`, new comparison paths in the rail, and a signature change
on every screen that passes the selection down. All the risk is concentrated in
one pure encode/decode pair, which is where the tests are.

**Also fixed on the way:** rail expansion was keyed on the parent's visible
*label*, so two playlists the user named the same would have expanded together.
Now keyed on the row key. This changes the persisted expand-state format; a stale
saved set matches nothing and the rail opens collapsed, which is harmless.

**Register**

| Slice | What | Register |
|---|---|---|
| MK.33.0 | ADD SOURCE reachable by remote again — two-way key bridge to row 0, UP end-stop on the button | MB-333 |
| MK.33.1 | `SourceScopedGroup` selection keys, `CategoryNode.SourceParent`, `SourceCategoryTreeBuilder`, 5 source-scoped SQL queries, `groupsBySource`, rail expansion keyed on row key | MB-334 |

**Verification status.** 958 tests green (+23 for this slice), ktlint clean,
release APK built and installed on the Google TV. Verified on hardware for the
single-playlist case in-session (Live TV rail unchanged: Favorites / All / flat
provider-ordered groups, group commit still filters the coverflow).
**Multi-playlist path verified by the user on device, 2026-07-31:** a second
list was added (the MB-333 fix unblocked reaching ADD SOURCE), synced, and the
rail bucketed the categories per playlist as designed — user's words: "it worked
and sorted the way i want."

**Known gap, deliberately not addressed.** `CategoryChipBar` — the phone twin of
the rail — is dead code (defined, never called), so there is no phone path to keep
in sync. If it is ever revived it will need the scoped-key handling; it cannot
nest, so it would need a different presentation.

## MK.34 — Player chrome redesign ("Midnight Lounge") — started 2026-08-19

User-supplied design brief plus two reference photographs, approved 2026-08-19.
Replaces the oversized playback overlay and the options menu with a compact
glass system: a three-level overlay (Now Playing block, slim timeline ribbon,
floating control dock) capped at roughly the lower 28% of the video, and a
detached glass side sheet for options. Hexagons stay as the signature control
shape; playback behaviour, remote navigation and stream handling are untouched.

**Brief written for the web, implemented in Compose.** The specification is in
CSS/DOM terms — `clip-path`, `backdrop-filter`, `scrollWidth`, `dir="auto"`,
`aria-label`, `prefers-reduced-motion`. Each maps: `GenericShape` for the hex,
theme tokens for the custom properties, `Modifier.basicMarquee` for the overflow
scroll, `Modifier.semantics` for the ARIA roles, `ANIMATOR_DURATION_SCALE` for
reduced motion. The intent is followed exactly; the mechanism is Compose.

**Two constraints found during Phase 1 inspection, both reported to the user:**

- **Real backdrop blur is impossible on the primary test device.**
  `Modifier.blur` / `RenderEffect` are API 31+; the Fire TV AFTDCT31 is API 28
  and minSdk is 24. Blurring live video also rules out a pre-blurred snapshot.
  Plan: genuine blur on 31+, layered translucent gradient below it. "Film
  perceptible through smoked glass" still holds; only the softening is lost.
- **There is no channel/broadcaster field for VOD.** The reference shows
  "TRT 1" leading the metadata line, but that mark is burned into the video.
  The app holds only the Xtream category (`TURKISH YERLI DIZILER` for this
  title). The brief's own regression case is therefore unsatisfiable as
  written — its expected output contains a token absent from its input.
  **User decision 2026-08-19: omit the segment when unknown**, render it only
  where the channel is genuinely known (LIVE), and never substitute the
  category. Nothing invented.

### MK.34.1 — Now Playing metadata normalization — shipped

Pure kernel (`NowPlayingMetadata.kt`) splitting a provider string into a title
plus ordered segments, so the dock stops rendering
`Tozkoparan İskender — TR - Tozkoparan İskender (2021) (TR) - S01E03 - 3. Bölüm`
into a 34sp title. Structured `season`/`episode` from `Playable.Episode` beat
regex parsing; the regex path exists only for items that never went through it.
13 tests including the brief's regression case, the unspaced `S01E03` form the
provider actually ships, Arabic preservation, and a tr-TR locale case.

Both negative controls corrected a wrong first guess, which is recorded here
because the corrections are the useful part: the repeated-title filter does NOT
fail the brief's own case (the repeat sits mid-string and `episodeName` takes
the last candidate) so a last-token test was added to pin it; and the
`Locale.ROOT` argument fails nothing at all, because Kotlin's `lowercase()` is
already locale-independent — unlike Java's `toLowerCase()`. It is kept as
documentation with a test guarding the rewrite that would genuinely break it.

### MK.34.2–34.9 — surface language, overlay, dock, sheet, RTL — shipped

`MidnightGlass` (tokens + hexagon + glass, theme-derived after user instruction),
the three-level overlay, the floating dock, the slim timeline ribbon, the
detached options sheet, and the LTR pinning that stopped RTL half-mirroring.
Each slice measured on a Fire TV before commit.

**The recurring defect was units.** Every number in the brief is a physical pixel
at 1920x1080; dp is what Compose invites. The same mistake landed three times —
the dock at 2x, the metadata line larger than the title, the sheet at 2x and then
pinned to its clamp floor — and each was caught only by measuring pixels on the
TV. That is what MK.34.10 exists to end.

**Not achievable, documented rather than faked:** backdrop blur. Compose has no
backdrop filter, `Modifier.blur` blurs a composable's own content, and these
panels sit over a `SurfaceView` that is not in their draw pass. An architecture
limit, not a version gate.

### MK.34.10 — chrome decisions extracted into testable kernels — shipped

The brief's verification phase asks for eight tests, seven of which check
on-screen behaviour. This project has no instrumented-test stack and adding one
is a larger change than the feature, so the RULES were extracted instead:
`PlayerChromeMetrics` (sizing arithmetic), `dockControlOrder` (focus order, now
the object the dock actually renders from), `marqueeMode`, `optionCategoryFor`,
`shouldRestoreDockOnOptionsClose`, `pinsLeftToRight`. 26 tests, asserted in the
brief's own pixel units.

Negative controls confirm they catch the real defects: reproducing the 2x dp bug
fails the hero-band test, the halved sheet ratio fails the clamp-ceiling test,
and a reshuffled control order fails the order test. Each of those cost a
build-install-`uiautomator` cycle to find the first time.

**Known limit, stated rather than implied:** these pin rules, not pixels. A
purely visual regression — a wrong colour, a broken gradient — would still pass.
The visual evidence is the geometry measured on AFTDCT31 in each slice's commit.

## MK.35 — Home that shows something — started 2026-08-19

User: "i want it innovated and actually fixed to show the right things."

Investigation found two problems, one a confirmed defect and one structural.

**Confirmed: "Recently added" never worked.** It sorts by `content.created_at`,
but a sync is a full replacement — `BulkContentWriter.prepareSource` runs
`DELETE FROM content WHERE source_id = ?` and the chunked re-INSERT stamps a
fresh `created_at` on every row. So created_at records when the last sync ran,
not when an item arrived. On the user's 272,419-item Xtream catalogue refreshing
every ~52 minutes, the rail showed whichever 60 rows the provider's API returned
last and reshuffled hourly. Nothing in it was ever new.

**Structural: Home is empty unless you have history.** Six rails, and four only
appear once the user has watched or starred something (hero + continue watching
need history; Favorites needs stars; On Now + Tonight need starred LIVE
channels). With 53,167 live channels and 174,000 movies freshly synced, Home
showed a hero, continue-watching, and one broken rail. No discovery at all.

**User decisions (2026-08-19):** fix "Recently added" properly rather than drop
it; add category browsing; add recent LIVE channels. Recent-live is a real gap —
`resumePointDecision` returns null for LIVE by design, so a live channel can
never enter watch history and therefore never appears on Home unless favourited.
Desktop already has a recent-channels store (`core-stores-recent-channels`);
native has none.

### MK.35.1 — first-seen stamping, so "Recently added" means something — shipped

New `content_first_seen` side table (migration 12.sqm) keyed on the deterministic
`ContentIds.*` values the re-INSERT recreates. **No FK to content(id)**,
deliberately: surviving the sync's DELETE is the entire purpose, and an
`ON DELETE CASCADE` would reintroduce the bug one layer down. Stamped in
`finishSource` with one bulk `INSERT OR IGNORE`, so existing titles keep their
original timestamp and only new content_ids get a row.

`from_initial_import` is load-bearing rather than informational. Without it a
fresh install stamps all 272k items within one second and ordering by first-seen
produces 60 arbitrary titles — the same broken rail with a new column behind it.
The first sync is excluded, so the rail is EMPTY after a fresh install and fills
only when a later sync genuinely brings something.

Existing installs get an empty table and **no backfill**, which is correct rather
than lazy: backfilling from created_at would stamp everything with the last
sync's timestamp, the exact meaningless value this exists to stop trusting.

4 tests, and the two that matter assert emptiness. `BulkContentWriter` gained an
injected clock, matching the module convention (`WatchHistoryRepository`,
`SourceRepository`, `EpgRepository` all take `clock: () -> Long`); `shared` has
no kotlinx-datetime on its compile path, so a default was not an option.

Migration verification is SKIPPED on Windows hosts — CI's Linux runner is the
gate for `12.sqm`.

### MK.35.3 — recent live channels — shipped

New `recent_channels` table (13.sqm), FK-free for the same reason as
content_first_seen. Recorded from `persistResumePoint`, which is already called
at every moment that ends a viewing, so there is no new lifecycle path to keep in
sync. Dwell clock is `elapsedRealtime`, not wall clock — a device resyncing its
clock mid-programme would otherwise compute a nonsense duration.

**The 30 s threshold is the feature.** The browse coverflow auto-previews LIVE
channels on focus after a 400 ms debounce, so recording on play would make the
rail a replay of the user's scrolling. Six times the 5 s VOD resume threshold
because the costs are asymmetric: a wrong resume point costs one seek, a wrong
recent-channel entry misleads Home until it is pushed out. 6 tests written
against the scroll-past case specifically.

### MK.35.2 — category rails on Home — shipped

Up to three rails built from the catalogue itself, which nothing on Home did
before. **Pins win, size is the fallback.** The user could already pin, hide and
rename categories in Settings and none of it reached Home — pinning something and
not seeing it there reads as the setting being broken, not as a missing feature.

Size rather than provider order for the fallback: `distinctGroupsForType` orders
by MIN(sort_order), which is right for Browse (a complete list the user scans)
and wrong for Home (three rails chosen for them) — provider order surfaces
whichever bucket sits first in the playlist, as likely to be a near-empty test
category as anything worth watching. New `topGroupsForType` query.

`pickCategoryRails` is pure and has 11 tests, including the contradictory-settings
case (pinned AND hidden → hidden wins) and case-insensitive de-duplication,
because providers ship the same category under different capitalisation across
playlists and the naive version renders it twice.

Device-verified: rails render with real provider categories and content.

**Known limit:** group renames are stored (`group_preferences.custom_name`) but
`AppPreferences` exposes no flow for them, so rails show the provider's name.
`pickCategoryRails` already takes a `displayNames` map and is tested for it —
wiring is a one-liner once a rename flow exists.

### MK.35.4 — the 1.4.0 -> 1.5.0 upgrade gate — shipped

MK.35.1 and MK.35.3 each added a schema hop (`12.sqm`, `13.sqm`) and `11.sqm`
went out in the same release. **None of the three has ever been through
SQLDelight's build-time `verifyCommonMainYancoDbMigration` task**, which is
disabled on Windows (sqlite-jdbc / JBR native-link failure) and could not run in
CI either — Actions has not allocated a runner for this repo since 2026-08-21.
Shipping three unverified migrations to a device holding a 345 MB catalogue and
the user's entire watch history is the largest single risk in v1.5.0.

`UpgradeFrom140Test` is the substitute gate. It builds a database at **schema
v11** — what 1.4.0 actually shipped, since that release carried migrations only
through `10.sqm` — and walks it to the current version.

The fixture is generated, not hand-written: the CREATE statements from the `.sq`
files at commit `f7f38a7`, with SQLDelight's `AS kotlin.X` annotations stripped.
Indexes, the FTS4 virtual table and the `content_ai` trigger included. That
faithfulness is the point — the headline assertion compares every `sqlite_master`
entry between an upgraded database and a freshly created one, which is what the
disabled Gradle task exists to do, and it only means something if the starting
shape is real.

Column ORDER is exempt from that comparison, deliberately and with the reasoning
recorded in the test: `ALTER TABLE ADD COLUMN` can only append, so `expires_at`
lands last on every upgraded device while `Sources.sq` declares it mid-table.
Safe only because SQLDelight expands `SELECT *` into an explicit named column
list at codegen time (so the generated `cursor.getLong(19)` indexes the
projection, not the table) and because no column-list-free `INSERT INTO t VALUES
(...)` exists in the repo. Both were checked before the exemption was written; if
either stops holding, the exemption is wrong.

The other tests pin the upgrade's user-visible contract, which no existing test
touched:

* **"Recently added" is EMPTY right after the upgrade, not full.** `12.sqm`
  deliberately does not backfill. The tempting alternative — seeding
  `content_first_seen` from `content.created_at` — would stamp all 272,419
  existing titles with the last sync's timestamp and announce the entire
  catalogue as new.
* **The first post-upgrade sync is stamped as an initial import.**
  `content_first_seen` is empty, so `finishSource` must conclude "first import"
  despite the app having been installed for months with watch history behind it.
  Drives the real `BulkContentWriter`, not a copy of its rule.
* **The second sync surfaces only genuinely new titles**, proving the stamp
  survives the DELETE + re-INSERT every sync performs.
* **A watched live channel survives the sync that replaces its content row**, and
  a permanently dropped channel stops rendering while its row is kept.

Negative controls run, each reverted: dropping an index from `12.sqm` reddens
only the structural test; inverting the initial-import flag reddens exactly the
two tests that depend on it; adding a backfill to `12.sqm` reddens the empty-rail
test. Nothing passed for free.

Corroborated on hardware rather than only in the JVM: the Fire TV upgraded a real
1.4.0 database in place during this milestone. `user_version` read from the
SQLite header is 14, the 345 MB catalogue and watch history are intact, and Home
renders.

**Known limit:** this is a stand-in, not the real gate. It asserts what the
authors thought to assert, whereas `verifyCommonMainYancoDbMigration` compares
against a generated snapshot of every version. Re-enabling it — on CI, or on a
non-Windows machine — remains the durable fix.

## MK.36 — reconciling the shared core with the iOS fork — started 2026-09-03

**Why this milestone exists.** `YamanAddas/YancoTV-iOS` is not an iOS-only repo — it is a
fork of this whole monorepo, and this repo's `master` is a strict ancestor of it. Ninety-eight
commits accumulated there, and a large minority of them are **not iOS code**: they advance
`packages/shared/` (the KMP core both platforms run on) and `packages/core/` (its TypeScript
twin). Four schema migrations, a name-based EPG index, playlist-divider detection, browse-row
facts, Unicode letterform folding, and a query-plan fix worth 3m22s -> 0.09s all landed there
and none of them exist here. These are improvements this repo's apps want and do not have — the
reason to merge is the value of the work, not a parity obligation — and the cost of untangling two
diverging histories of the *same* files grows with every session on either side.

**Scope of 36.1 (this slice): take the shared core, wire nothing.** No `app/` changes. The
merge is deliberately inert at the UI layer so that a regression, if there is one, is
attributable to the core and not to new wiring landing at the same time.

### MK.36.1 — import the shared core, wire nothing — shipped 2026-09-03

Taken wholesale from `ios/master` (`24b36acf`):
`packages/shared/src/{commonMain,commonTest,androidMain,androidUnitTest}`, plus the
TypeScript mirrors `packages/core/src/content/title-cleaner.ts`, `packages/core/src/xtream/`
and their tests. The TS half is taken here for a specific reason, not for symmetry:
`title-cleaner.ts` and `TitleCleaner.kt` are two implementations of one *user-visible contract* —
what a clean title is — and a title that reads one way on the TV and another on the desktop is a
bug the user sees. Where a port only carries capability its own apps use, it is free to differ.

**Why the app compiles untouched.** Every public API change is additive — new functions, new
data-class fields carrying defaults. `ContentRepository` gains `groupTallies`,
`searchByTypePaged`, `searchCountByType`; `WatchHistoryRepository` gains `allProgress` and
`entriesByEpisode` (both non-Flow readers SwiftUI needs) and extracts `resolveEpisodeProgress`
so the two readers cannot drift; `ContentMetadata` and `EpisodeInfo` gain optional fields.
Nothing was removed or re-signed.

**What this switches on by itself — the whole list:**

- **Search stops timing out.** `searchFts` / `searchFtsByTypePaged` / `countSearchFtsByType`
  now lead with `content_fts` and `CROSS JOIN` to `content`, forcing FTS to drive the plan
  instead of letting SQLite pick a scan of `content`. Measured on the fork: 3m22s -> 0.09s.
  This is not a latent iOS-only win — `SearchScreen` already carries a
  `searchByType(...) timed out` warning log, so this repo has been hitting the bad plan in
  production. `searchByType` delegates to `searchByTypePaged`, so the call site needed no edit.
- **6,903 live channels stop rendering as `": SKY SPORT ..."`.** `TitleCleaner` strips
  separators the quality-tag strips orphaned at an edge (`EDGE_SEPARATORS`), and `16.sqm`
  repairs `clean_title` values already written, so no resync is required. `.` and `,` are
  deliberately outside the set — trimming a trailing dot would turn `M.A.S.H.` into `M.A.S.H`.
  The MB-377 fallback still runs *after* the new trim (verified by reading the ordering in
  `cleanTitle`), so a title that reduces to nothing keeps its raw form.
- **Channel numbers, TMDB ids and trailers reach the database.** All three came down with the
  bulk Xtream response and were discarded — measured on a real account, 0 of 53,207 live rows
  carried a channel number. Applies after one resync: `BulkContentWriter` clears a source's
  rows before writing, so existing rows are replaced rather than skipped.

**Schema 15 -> 19, and how it was gated.** `verifyCommonMainYancoDbMigration` is disabled on
Windows (see MK.35.4), so the substitute gates carried this:

| Gate | Range walked | Result |
|---|---|---|
| `UpgradeFrom140Test` | v11 (what 1.4.0 shipped) -> `Schema.version` | 7 tests, green |
| `MigrationTest` | full chain | 15 tests, green |
| `Stage2MigrationTest` | v3 -> `Schema.version`, `episodes` added to the fixture for `19.sqm` | 3 tests, green |

Full suite: **824 tests, 0 failures, 0 errors**. `:app:assembleDebug` green.

### MK.36.1 correction — the search claim was wrong on Android — measured 2026-09-03

MK.36.1's commit message and the row above say search "stops timing out" and cite the fork's
3m22s -> 0.09s. **That does not reproduce on Android, and the reasoning behind it was wrong.**

Measured on the Chromecast against the real 274,097-row catalogue, both query plans built and
installed in turn, every run from a cold start with the sync verified idle:

| query | pre-fix plan (`FROM content JOIN content_fts`) | shipped plan (`FROM content_fts CROSS JOIN content`) |
|---|---|---|
| `the` | 2179 / 2780 / 3966 ms | 3169 / 3694 / 3856 ms — and 2888 / 4178 / 4786 on a re-run |
| `a` | 568 / 582 / 1046 ms | 591 / 601 / 1221 ms |
| `sport` | 153 / 282 / 312 ms (warm) | 1147 / 1365 / 1367 ms (cold, first after install) |

The two plans are indistinguishable, and where they differ the *old* one is marginally ahead. The
`sport` row is not a regression — it is a cache artefact: that query ran first after an install in
one set and second in the other, and the FTS pages are not in the OS file cache on a cold start.
Which is itself the lesson — the only comparable measurements are like-for-like in cache state.

**Why the fork's number is real but not ours.** The catastrophic plan was
`countSearchFtsByType`, and its own comment in `Content.sq` says so: "This is the query that made
the CROSS JOIN above mandatory." **That query has no Android call site.** `SearchScreen` calls
`searchByType` only; the count exists for the iOS paged-search UI, which shows how many results are
being held back. Android never ran the query that took 3m22s.

**The evidence cited for the opposite was misread.** MK.36.1 pointed at the existing
`SearchScreen.searchByType(...) timed out` warning as proof Android had been hitting the bad plan.
That log covers any 8-second overrun, and `SearchScreen`'s own comment names the likelier cause:
"a sync can still hold the SQLite write lock, so any single query may take seconds". A timeout
during a sync is not evidence of a bad query plan.

**The change stays.** It is harmless on Android, load-bearing on iOS, and one query set for both
platforms is the point of rule 8. What changes is the claim: this was not an Android win, and
MK.36.1 should not be read as having delivered one.

**What Android search actually costs**, now that `SearchScreen` logs successful queries as well as
failures (added in the same commit as this correction): 44 ms for a narrow term (`bein`), 116-209 ms
for `movie`, and 2.9-4.8 s worst case for `the` — the broadest term in the set — against a
274,097-row catalogue. Every run finished inside the 8 s timeout and no timeout fired in 27
measured queries.

### MK.36.3 — playlist banner rows are not channels — shipped 2026-09-03

909 of 273,869 rows on the owner's account are provider *headings* dressed as channels —
`##### beIN SP⚽RTS ᴴᴰ #####`, `### ARABIC 24/7 4K UHD 3840P ###`. They carry a stream URL because
a flat M3U has nowhere else to put a section marker, `isPlayable` says yes to all of them, and they
sort to the front of their group. One was the first tile on Home: tapping the first channel on the
screen opened a spinner that never resolved, and it cost 48 seconds of a recording test and a wrong
conclusion about the recorder before anyone read the name.

**Filtered at write time, not read time.** The iOS side wired `isPlaylistDivider` into
`YancoServices.ios.kt` as a post-read filter. That does not port: browse here is paged, so a filter
applied after the page is fetched drifts `count()` against the rows actually returned — ask for 100,
render 97, and the pager's arithmetic is wrong from then on. Filtering at the write is also the
honest model: these rows are not content, and storing them was the bug.

Applied at every entry point that writes a title-bearing live row:
`BulkContentWriter.writeLiveChunk` / `writeM3uChunk` / `writeStalkerLiveChunk`, and
`ContentWriter.writeM3u` / `appendXtreamLive`. `ContentWriter.writeStalker` is deliberately
untouched — it has no callers left; if it is ever revived, it needs the same line.

**The signatures did not change.** The first attempt renamed the list parameter to `rawItems`,
which broke 16 named-argument call sites in `BulkContentWriterTest` and would have been a
gratuitous API break for anyone calling with `items = `. The shipped form is
`@Suppress("NAME_SHADOWING") val items = items.filterNot { isPlaylistDivider(it.name) }` — public
signature untouched, and every batching/transaction body byte-identical, which matters because this
is the code path that emptied a catalogue in MB-353.

**Effect is deferred to the next sync.** Existing rows stay until the source re-syncs, since the
write path is the only thing that changed. Auto-sync-on-start sources clear on the next launch. A
migration was considered and rejected: the rule needs "three or more of the *same* character at
*both* ends", which in SQL is a dozen OR'd GLOBs over 274k rows for a cosmetic gain.

**A difference, not a gap (owner's call, 2026-09-04).** `packages/core/` has no
`isPlaylistDivider`, so desktop still shows banner rows. This was first written up here as a rule 8
divergence with a follow-up slice, **MK.36.3b — that framing was wrong and the slice is dropped.**
The two ports are independent and are not required to match; each carries what its own apps need.
Banner filtering is something Android wanted and desktop has not asked for. If desktop does want it
later it is a normal piece of work, not a debt this milestone left behind. (AGENTS.md's two-ports
note and rule 8 were rewritten to say so, so the next session does not re-file it.)

Worth keeping either way: the desktop toolchain could not be brought up in this session
(`pnpm install` fails resolving `mpegts.js@1.8.0`), so anything written for `packages/core/` here
would have shipped untested.

Verified: 826 shared tests, 0 failures (2 new — one asserts the two banners are dropped **and**
that `### SPORTS`, `Ping-Pong -- Live` and `BBC News` all survive; the first draft of that assertion
was wrong, not the filter). `:app:assembleDebug` and `:shared:lintDebug` green.

### MK.36.4 — the same title carries the same facts on every list — shipped 2026-09-03

The browse orb's second line was the provider's **group name** — "EN - NEW RELEASE" — which says
which shelf a title sits on, not anything about the title. Year and rating are already on the row
and cost nothing to read: measured on the live account, **164,224 of 175,064 movies (94%) carry a
non-zero rating** and **135,471 (77%) carry a `(YYYY)`**. Movies and series now show `2019 · ★ 7.4`,
falling back to the group name so a row with neither is not left blank. LIVE is untouched — its
second line is the EPG now-title, which is the right fact for a channel.

`PreviewFactsLine` moved onto `rowFacts` in the same commit, because the point is consistency: the
pane and the orb beneath it must not disagree about the same title. It also fixes three wrong reads
the pane had been making on its own:

- `releaseDate.take(4)` assumes `2023-04-01`. Providers also send `01/04/2023`, which rendered as
  **"01/0"**. `RowFacts.yearFromDate` takes the first plausible four-digit year instead, and
  `RowFactsTest` now pins all three shapes.
- A movie with no `releaseDate` showed no year, though the year is in the title on 77% of them —
  and it has to be read off the **raw** title, since `cleanTitle` strips bracketed content and takes
  `(2019)` with it.
- A provider writes `"0"` for "not rated". That rendered as a confident **"★ 0"**; `RowFacts`
  treats zero as absent.

**Parsed per orb, not per page.** `rowFacts` is batched upstream because iOS pays a bridge crossing
per call — a cost that does not exist here, while `visible` grows without bound as the user pages.
A LazyRow only composes what is on screen, so a `remember` keyed on the row's identity parses a
handful of rows at a time. Pure JSON over data already in memory, no I/O, so it is safe in
composition (the same shape as the existing `parsePreviewMetadata`).

**Spoken, not only shown.** The facts are appended to the orb's `contentDescription`. That
description explicitly overrides merged descendant text — the MB-280 lesson — so the caption is
never announced on its own, and adding a year and rating that TalkBack could not read would have
been a half-fix.

Rating is formatted with an explicit `Locale.getDefault()`, so an Arabic reader sees Arabic-Indic
digits alongside the rest of the UI rather than a lone Latin number. Same rule the D.1a lint pass
applied to time codes.

Verified: 828 shared tests (2 new), 0 failures; `:app:testDebugUnitTest`, `:app:assembleDebug` and
`:app:lintDebug` all green — lint clean on `DefaultLocale`.

**Not verified on device.** Home's own rail tile was left alone; it is a separate composable and
belongs in its own slice.

### MB-401 — the coverflow orb's caption line never reaches the screen — FIXED 2026-09-03

Found while verifying MK.36.4 on the Chromecast (1920x1080 @ density 2 = 960x540 dp).

`ContentOrb` renders three children in a `Column` of fixed `.height(OrbHeight)` = 200 dp: the
140 dp art box, the title, and a caption. **The caption is never laid out.** A `uiautomator` dump of
the Movies wheel contains a `TextView` for every orb title and none for any caption — no text node
anywhere in the tree carries the `·` separator except the preview pane's own facts line.

The content is correct; only the rendering is missing. The same value reaches the artwork's
`contentDescription`, which reads `Roza, 2022  ·  ★ 3.5` and `Parçalı Yıllar, 2026  ·  ★ 8.0` — so
the data path is sound and TalkBack announces it. It is the visible line that is lost.

Arithmetic: the art box is 140 dp, plus `Space.sm` twice and two text lines is roughly 192 dp
against the 200 dp box — which fits only while the orb is at scale 1. The centred orb is drawn at
`scaleBase = 1.18f`, i.e. 236 dp inside a wheel band that gets `weight(0.38f)` of a 540 dp viewport
(~205 dp). Measured on the device, the centred title's own baseline sits at y=1061 of 1080.

**Not introduced by MK.36.4.** That commit changed only *which string* `sub` holds; the render
condition (`if (sub.isNotBlank())`) and every layout constant are untouched, and the group name it
replaced was equally non-blank. The line has been getting clipped for as long as the fixed height
and the centre scale have coexisted.

**Cause, established by experiment rather than arithmetic.** The first reading — that the fixed
200 dp `Column` had no room for a third child — was wrong; the content measures ~187 dp and fits.
Forcing the caption to a literal `"XCAPX"` with the `isNotBlank` guard removed and reinstalling
proved the `Text` is emitted and still never appears, which rules out the content and points at the
container. The wheel band took `weight(0.38f)` of a 1080 p viewport — about 390 px — while the orb
`Column` is a fixed 200 dp, i.e. 400 px. The band clipped the Column's bottom, and the caption is
exactly what sits there.

**Fix: 0.62/0.38 becomes 0.58/0.42.** The band is now ~454 px, clearing the 400 px orb with room for
the 1.18x centre scale. The preview pane could afford the 4%: measured, its content ended at y=528
of the ~670 px it had. Chosen over shrinking `OrbWidth`, which would have made the artwork smaller
on every device to work around a container that was simply too short.

Verified on the Chromecast after the change: `2022  ·  ★ 3.5` and `2026  ·  ★ 8.0` now render as
real text nodes under their orbs, the poster keeps its exact 2:3 ratio (300x450), and preview
content ends at y=584 with the wheel starting at y=623 — nothing clipped at either end.

`weight` in a `Column` allocates height and both children are `fillMaxWidth()`, so no horizontal
dimension can change; an apparent difference in where the action buttons wrap between two dumps was
the sidebar being expanded in one (456 px) and collapsed in the other (120 px).

**Not verified on Fire TV.** 0.42 of a 540 dp viewport is 227 dp against the orb's 200 dp, so the
arithmetic clears there too, but no Fire TV was reachable this session.

### MB-402 — 59% of a full sync is deleting rows that are about to be rewritten — fixed 2026-09-04

Measured end to end on the Chromecast (sabrina: 4x Cortex-A53 @ 1.9 GHz, 32-bit, 2 GB RAM, eMMC),
one manual sync of the owner's 274k-item Xtream account, 2026-09-03:

| phase | wall clock | share |
|---|---|---|
| fetch account + categories | 16 s | 2.6% |
| **`clearIfFirstWrite`** — 274,097 rows in 275 batches | **367 s** | **59%** |
| write 273,193 rows | 205 s | 33% |
| `finishSource` — rebuild the whole FTS index | 27 s | 4.3% |
| finalise | 7 s | 1.1% |
| **total** | **10 min 21 s** | |

**Nothing here is a bug; it is two correct fixes composing badly.** MB-353 moved the destructive
delete after the replacement rows are in hand, so a failed sync cannot empty the catalogue. MB-315
broke that delete into 1000-row transactions so it cannot hold the write lock for two minutes and
starve a favourite toggle. Each is right. Together they produce an incremental delete that is now
the single most expensive phase of a sync — six minutes spent removing rows that are rewritten two
minutes later.

**The lead worth measuring first.** `finishSource` rebuilds the *entire* FTS index for the source in
one `INSERT … SELECT` and that costs **27 s**. The clear removes rows from that same index
incrementally, and an earlier investigation (in `BulkContentWriter`'s companion) attributed 62 s of
a 110 s Fire TV clear to FTS maintenance. If a whole-index drop-and-rebuild is ~27 s while
incremental removal is minutes, the FTS half of the clear may be avoidable outright. That earlier
note benchmarked *unqualified vs predicated* `DELETE` (0.41 s vs 0.47 s on a 60k table) and
concluded there was no fast path — but it did not compare *incremental deletion* against
*drop and rebuild*, which is the question this data raises.

The second half — `content` itself carrying seven indexes, i.e. ~1.9 M index insertions for a 274k
catalogue — is untested too. `BulkContentWriter`'s header says indexes are deliberately not dropped
because "B-tree inserts are fast"; that assumption has never been measured on eMMC.

**Hardware is part of it and not all of it.** The same code runs on iOS via the same
`SourceRepository.syncSource`, on hardware with ARM64 cores and NVMe. A 3-5x difference is expected
before any code question arises. Six minutes of deletion is not explained by that alone.

**Root cause — a regression of a fix, not a new problem.** `content_fts` is an fts4 virtual
table, and fts4 indexes no column, so `WHERE content_id IN (...)` can only be answered by scanning
every row of the index. The schema comment on the deleted `content_ad` trigger already records this
exact blow-up from MK.6.d — "each trigger fire scanned the entire FTS -> O(N^2) for an N-row wipe" —
and says FTS "is now cleared explicitly in a single bulk statement". MB-315 then moved the clear
into 1000-row batches for write-lock fairness, and in doing so put that single statement back inside
a loop. 275 batches x 274,097 index rows is ~75M row visits: the 367 s.

**Fix.** The FTS wipe is hoisted out of the loop and runs once, as `deleteFtsBySource` always
intended; only the `content` delete stays batched, where `idx_content_source` makes each batch an
index range scan. Lock time is not traded away: 367 s over 275 batches is ~1.3 s per batch and a
batch's cost IS one full index scan, so the single hoisted statement holds the write lock for about
as long as one batch already did. Ordering is unchanged and still load-bearing — the subquery reads
`content`, so it must run before the rows go.

**Evidence.**

- A test asserts the FTS delete executes **once** per clear via a counting `SqlDriver`. Correctness
  tests could never have caught this: the batched version deleted the same rows, just slowly, so
  every existing assertion passed. The statement count is the bug, so the statement count is what is
  asserted. **Negative control run:** with the fix reverted, that test fails; with it applied, it
  passes.
- A second test pins the risk the fix introduces. The hoisted statement lost its `LIMIT`, and an
  unbounded delete on a shared table is the shape that destroys someone else's data — so a second
  source's index rows must survive the first source's clear. It counts `content_fts` **directly**
  rather than through `searchFts`, which CROSS JOINs to `content` and therefore cannot see an
  orphaned index row at all; asserting through search would have passed whether the row was deleted
  or merely stranded.
- A scaling probe (JVM, in-memory, since removed) timed the clear at 16k / 32k / 64k rows:

  | rows | before | after |
  |---|---|---|
  | 16,000 | 227 ms | 198 ms |
  | 32,000 | 559 ms | 400 ms |
  | 64,000 | 1,385 ms | 856 ms |

  Growth per doubling is **x2.47 before** (exponent ~1.30) and **x2.08 after** (~1.06), and the gap
  widens with size — 1.15x, 1.40x, 1.62x. That is the signature of removing a quadratic term.

**What is NOT verified, and it is the number that matters.** No sync has been run on the Chromecast
against the real 274k catalogue, so the 367 s has not been re-measured on hardware. The JVM probe
runs against an in-memory database whose entire index sits in page cache; on eMMC, where the scan
is real I/O against an index far larger than cache, the quadratic term should dominate far more
heavily than these numbers suggest — but "should" is not a measurement. The device run was attempted
and abandoned: the shell's focus was stuck on the Home search field and neither BACK, LEFT nor DOWN
would move it, so the sync could not be triggered without force-stopping the app — which is exactly
the action that damaged the catalogue once already. Trigger one manual sync and the `clear[...]`
log line carries the answer.

### What is deliberately NOT switched on, and why each needs real work

Naming these here because the merge makes them *look* available. They are not.

- **EPG name matching is the big one, and it is not free.** `EpgNameKey` and
  `epg_channel_names` arrive, but nothing on Android will ever populate that table.
  `EpgRepository.refresh()` builds the index and **Android does not call it** — it has
  `AndroidEpgImporter`, which exists precisely because the shared path materialises the whole
  XMLTV body and OOMs on Fire TV (MB-230). Three separate blockers: the importer parses only
  `<programme>` and never reads `<channel>`, so it has no `display-name` data at all; the index
  is never built; and its memory strategy drops programmes whose channel id is not already in
  the user's `tvg_id` set, which discards exactly the channels name-matching exists for, before
  matching could help. Compounding it, `EpgNameKey.uniqueIndex` needs per-channel programme
  counts to break ties, which are only known at end of stream — while the widened id set is
  needed *before* programmes stream past. Tractable, because the importer already streams to a
  temp file and can read it twice, but it is a redesign of the import filter under the heap
  constraint that caused MB-230. Own slice, own device verification.
- **`UpNextDecision` is a duplicate, not a feature.** `com.yancotv.shared.playback.UpNextDecision`
  is byte-identical to this repo's `com.yancotv.android.player.UpNextDecision` except
  `internal object` -> `object`. It is our own file lifted to `commonMain`. The correct action
  is to delete the Android copy and repoint imports — a dedupe, gaining nothing but removing a
  drift surface.
- **`PlaylistDividers` needs a different call site here.** 909 of 273,869 rows on the real
  account are banner rows (`##### beIN SP⚽RTS ᴴᴰ #####`), all `live`, all reported playable,
  all sorting to the front of a group — one of them was the first tile on Home, and tapping it
  opened a spinner that never resolved. The rule is sound and tested. But its only call site is
  `YancoServices.ios.kt`, filtering *after* the read, which on a paged browse would drift the
  count against the page. Android should push it into SQL or apply it at write time.
- **`DownloadsRepository` is capability without a consumer.** There is no download manager,
  service or UI on Android. Merging it costs nothing and does nothing until someone builds the
  Android half.

**Not verified in this slice:** nothing ran on a device — no Fire TV or phone was reachable on
the network. Specifically outstanding: the wall-clock cost of `16.sqm` on a real 273,869-row
catalogue at first launch after upgrade (a full scan computing `TRIM` twice per row, updating
~6,900), and the search-plan improvement measured on Android hardware rather than inferred from
the fork's numbers. Both are device checks, not code changes.

## MK.37 — portrait, and the form-factor layer under it — started 2026-09-03

**Why now.** MB-354 recorded portrait as "explicitly cancelled" (owner decision, 2026-08-22) on two
grounds: the shell is laid out landscape-first throughout, and a TV app gains nothing from rotating.
The second is still true and is unaffected by any of this — a television never reports a rotation.
The first was true and is what this milestone removes. The owner reversed the decision on
2026-09-03 after seeing the iOS port handle portrait properly.

**What the iOS port actually solved**, and what is worth taking:

1. **Measure the lane, do not switch on a device class.** Sizes are clamped proportions of the
   width left after the rail, not a `compact ? a : b` pair. An iPhone SE and a 17 Pro Max are both
   "compact" and were getting byte-identical tiles across 110 pt of width.
2. **Composition follows the shape of the lane, not the device.** Wide-and-short gets the
   television layout; a tall lane gets a grid. This is why it also fixed *iPad portrait*, which had
   the same voids as the phone.
3. **Navigation chooses on the scarce axis.** A rail whenever height is constrained, a bottom bar
   only when the window is both narrow and tall.
4. **Each tall screen gets a different composition, not a squeezed one** — wheel becomes a grid,
   guide grid becomes a now/next list, the 240 dp category panel becomes a pull-down drawer.
5. **The plist has to allow rotation at all** — iOS had written the landscape layout and could never
   reach it.

### MK.37.A — the measurement layer and the rotation unlock — shipped 2026-09-03

`ui/theme/ShellMetrics.kt` plus `LocalShellMetrics`, measured once at the shell root in
`HomeScreen`. **Nothing reads it yet, deliberately.** The alternative is one commit that changes
every surface at once on the one form factor that is already shipping; screens adopt it one at a
time from 37.B, each with its own TV pass.

**One improvement over the port it comes from.** iOS spells the sidebar/panel/grid rule out three
times — `RootShell`, `CoverflowScreen`, `GuideScreen` — with a comment in each asking the reader to
keep them in step. Here the decisions are properties of the metrics object (`usesSidebar`,
`usesCoverflow`), so two screens cannot disagree about the shape of the window they are in.

**A rule that does NOT port as written.** iOS needs `verticalSizeClass == .compact` in the sidebar
test because UIKit reports an iPhone in landscape as *compact* width whatever its real size, so a
width-only rule fails on every phone. Android reports real dp: a mainstream phone in landscape is
~869 dp and passes the width test on its own. The short-viewport clause is still needed, but only
for the *small* phone — 568x320, narrow **and** short, where a width-only rule would spend ~140 dp
of a 320 dp-tall window on chrome. `ShellMetricsTest` pins both cases; the first draft of that test
asserted the iOS behaviour and failed, which is how the difference was found.

**Rotation.** `MainActivity` moves from `sensorLandscape` to `fullUser` — the viewer's own rotation
lock is still honoured. `configChanges` now carries the orientation set so a rotation does **not**
recreate the activity. That is not an optimisation: state restoration is uneven across the shell —
`HomeContent` has zero `rememberSaveable` against twelve plain `remember`, `ContentDetailScreen`
none against six — so a recreating rotation would drop scroll positions, the selected category and
open detail state. `uiMode` and locale are deliberately excluded: those *should* recreate, and
MB-256's launch-intent replay guard depends on it. `PlayerActivity` stays `sensorLandscape` —
portrait playback is its own slice.

Verified: `ShellMetricsTest` (13 cases), full `:app:` and `:shared:` suites, `:app:lintDebug`, all
green. Installed release on the Chromecast: renders, no crash, and the sidebar geometry is
unchanged — every item still 104 px tall in a 120 px rail. (The y-offsets differ between the two
dumps because the rail's scroll position differs between launches: Home was clipped at the top
before, Settings at the bottom after. Heights and widths are identical, which is the part that
would move if the layer had leaked into rendering.)

**Not verified: portrait itself.** No phone was reachable this session, and the Chromecast cannot
rotate. The layer is unit-tested at phone and tablet viewports, but nothing has been *looked at* in
portrait on real hardware. That is the first thing 37.B should do.

### MK.37.B — the phone gets a bottom bar, and the shell chooses — shipped 2026-09-03

`HomeScreen` now branches on `ShellMetrics.usesSidebar`: rail on TV, tablet and any short window;
a bottom bar when the window is both narrow and tall.

**The diff is deliberately small.** The existing `Row(AppSidebar, content)` is untouched — it gains a
`Column` around it, the sidebar call gains an `if`, and the bar is appended. On television the
Column has a single weighted child, which lays out identically to the bare Row it replaced. Measured
on the Fire TV after the change: `Home [40,160][160,264]`, `Live TV [40,268][160,372]`,
`Movies [40,484][160,588]` — byte-identical to the pre-MK.37 baseline.

**`SectionFlowBar`** is the iOS bar ported: one accent hexagon that *travels* rather than appearing
and vanishing, with every cell lifting, brightening and scaling by its continuous distance from the
indicator, and a finger able to drag along the bar with the destination committing on release.
`Spring.DampingRatioNoBouncy` — a navigation bar used forty times an hour should be machined, not
bouncy. Five destinations plus a More sheet for the other four; `AppSection.compactPrimary` /
`compactOverflow` own that split because it is a fact about the destinations, not about one widget.

**Insets are split.** With the bar present the content Row insets for top and sides only and the bar
takes the bottom, so the bed runs under the gesture bar instead of floating above a stripe of
background. The TV branch keeps the original full `safeDrawing`, which resolves to zero there anyway.

**Reuse rather than re-draw.** `iconFor` moved from private to internal so the bar shows the same
glyph the rail does; copying the mapping is how the two drift. The `More` glyph is a new hand-rolled
icon in the existing line-weight family — three round-capped strokes, not filled circles, because a
filled glyph beside eight stroked ones reads as a different product. `section_more` is translated in
all four shipped locales; an untranslated string would have fallen back to English mid-Arabic.

Verified on hardware:

| | window | result |
|---|---|---|
| Pixel XL portrait | 1440x2560 (411x731 dp) | bar renders, six slots across the full width, sidebar gone, content reclaims the 280 px the rail held |
| Pixel XL landscape | 2560x1440 (731x411 dp) | rail returns, bar gone — the rule holds in both directions on one device |
| Fire TV AFTDCT31 | 1920x1080 | sidebar geometry byte-identical to baseline, no bar |
| Chromecast | 1920x1080 | unchanged (its rail reads 456 px wide only because focus was left in it and it expands on focus) |

The selected cell sits 7 px higher than its neighbours on the phone, which is the `-2.dp * nearness`
lift at density 3.5 — the indicator's wave is doing what it should.

**Known, and 37.D's job.** Tiles are still 770 px wide — 53% of a 1440 px screen — because
`ShellDim.posterTile` is a Fire-TV number and nothing reads `ShellMetrics.tileWidth` yet. The right
edge of every rail is still clipped mid-tile; it is *less* clipped than before only because the rail's
280 px came back. Navigation is fixed in this slice; sizing is not.

**Known, not introduced here.** In landscape the rail wants 9 x 52 dp = 468 dp against a 411 dp
window, so the last destinations sit below the fold. `AppSidebar` already scrolls
(`verticalScroll`), so they are reachable — but a phone in landscape having to scroll a navigation
rail is worth revisiting when 37.D touches sizing.

### MK.37.C — the category drawer and the portrait grid — shipped 2026-09-04

The two compositions a tall lane needs, ported from the iOS shell the owner asked us to match.

**`CategoryDrawer`** — the category surface for a phone held upright. `CategoryRail` is a 240 dp
standing column, 60% of a phone's portrait width, so portrait cannot have it; and a horizontal strip
alone is not the answer either, because a real account here ships **855 live categories** and a
strip shows four at a time with no sense of how far the rest run. Collapsed it is one line you can
scroll sideways; pulled open it becomes a grid where a flick covers rows rather than columns.

Three detents — "just the strip", "enough to browse without losing the content behind", and "the
whole list". Verified on the Pixel XL: collapsed shows 3 tiles reaching y=378, full shows **20 tiles
reaching y=1860 of 2560 — 73% of the screen**, with the content grid still visible beneath.

**Detents are proportional here, and that is a deliberate divergence.** iOS hard-codes 114 / 300 /
560, which is safe because iPhones are all roughly 844 pt tall. Android is not: on a 568 dp phone a
fixed 560 dp drawer is **99% of the screen** and buries the content it exists to filter. Half and
Full are fractions of the window (0.40 / 0.72) clamped at both ends. Collapsed stays absolute
because it is sized by what it must *show* — header, one name-over-count tile, grabber — not by what
the window can spare. Measured 526 dp on the 731 dp test phone, which matches the reference
screenshots' proportion.

**Both layers stay mounted and cross-fade on how far open the drawer is.** Choosing between strip
and grid with an `if` cost the iOS original one of two things: deciding on the *live* height rebuilt
the subtree mid-gesture and the drag snagged; deciding on the *settled* height left the categories
missing for the whole length of the pull ("اول سحبة مابتطالع"). Moving only opacity has neither
problem — nothing is created or destroyed while the finger is down, and the grid is lazy, so
collapsed it builds only the row that fits.

The whole collapsed strip drags, not just the grabber; reaching for a 46x18 target on a border is
not how anyone opens a drawer. Safe because the strip scrolls *horizontally*, so a vertical drag has
nothing to collide with.

**The tile is typography, not a container** — no fill, no border. The name is the object at full
contrast, the count small and muted underneath, and selection is a 2 dp accent rule down the leading
edge plus the name in accent. The bevelled accent-washed chip this replaces was rejected outright
("تصميم الفريم تبع أسماء الكاتيجوريز و لونو كتير سيء ومرفوض"). The rule is measured by the text
beside it rather than the row it sits in: as a plain sibling it had a width and no height, so while
the drawer was dragged it ran the drawer's full height.

**`ContentGrid`** — three columns of `ContentOrb`, replacing preview-over-wheel wherever the lane is
tall. `ContentOrb` gained `art` and `slot` parameters: it was hard-wired to `OrbWidth` (140 dp, a
Fire TV number, 34% of a phone's width) and to the 200 dp band whose fixed height clipped the
caption off every orb in MB-401. A grid cell asks for art plus its two label lines and nothing more.
Every cell draws at rest — the wheel's depth transform is about a focused centre and a grid has no
centre.

`BrowseSection` chooses between `Row(rail, coverflow)` and `Column(drawer, grid)` on
`ShellMetrics.usesSidebar`; `CoverflowSectionScreen` chooses grid over wheel on `usesCoverflow`.
Both read the shell's rule, so a rail and a drawer can never both appear. Category counts come from
`ContentRepository.groupTallies` — one of the queries MK.36.1 brought over — loaded off the main
thread per hard rule 3.

Verified on hardware:

| | result |
|---|---|
| Pixel XL portrait | drawer + 3-column grid; three detents all reachable; tap toggles, drag settles to nearest |
| Fire TV AFTDCT31 | `Home [40,160][160,264]` — byte-identical to baseline; no drawer composed; 0 fatals |
| Chromecast | unchanged; no drawer composed; 0 fatals |

`cd_categories` / `cd_favorites` / `cd_all` are translated in all four shipped locales.

**Still open.** Home's rails and the detail page keep the Fire TV numbers — a Home tile is still
770 px of a 1440 px screen. That is 37.D, and it is the last thing making portrait look stretched.

### MK.37.C.2 — the browse grid derives its own column count — shipped 2026-09-04

The owner looked at MK.37.C on a real phone and said the tiles were still too big, and to account
for Android phones coming in many sizes. Both were right, and the second is the more serious: the
grid was pinned at **three columns**. That is the exact "one number for every screen" mistake this
milestone exists to remove, sitting inside the milestone's own new code. Three is reasonable on a
411 dp phone, cramped on a 320 dp one and absurd on a tablet — and because the count was fixed, the
*tile size* swung with the screen instead of the count doing.

The rule now lives in `ShellMetrics` beside the others: `gridColumns` is the lane over a target edge
floored at two, `gridCell` is what is left once gutters and gaps come out, and `gridLabelBlock`
scales with the cell rather than sitting at a constant 64 dp.

The target is **88 dp**, not the iOS 132. iOS derives its count the same way — `max(2, width / 132)`
— which is why an iPhone shows two columns and a Pro Max three. But at that target a 411 dp Android
phone lands on three columns of 115 dp tiles, which is what was on screen when they were called too
big.

**Measured on the Pixel XL by resizing the window, not by arithmetic:**

| window | columns | tile |
|---|---|---|
| 320 dp | 3 | 100 dp |
| 411 dp | 4 | 95.7 dp |
| 440 dp | 4 | 102 dp |

The tile stays within seven points across the range and the count absorbs the difference, which is
the principle `tileWidth` already states in its own comment: past a point a wider screen should show
more tiles, not bigger ones. On the test phone the tile went 127 dp to 96 dp and the visible count
9 to 16.

Fire TV re-checked after the change: `Home [40,160][160,264]`, no grid composed, 0 fatals.

### MK.37.C.3 — three columns, and names that do not get chopped — shipped 2026-09-04

MK.37.C.2 fixed the wrong thing. Told the tiles were too big, it drove the column target down to
88 dp, which gave a 411 dp phone **four** columns — and that was worse than the size it set out to
fix. At four columns a channel name has about 84 dp to live in, so nearly every one truncated:
`2GB Sydney`, `3AW Melb…`, `3Cat Excl…` sitting shoulder to shoulder read as a wall of clipped text.
The owner's word for it was "زحمة ومشرشح" — crowded and ragged — against a reference build that is
"glassy ومرتب اكتر".

Two things were actually wrong, and neither was the tile size.

**The column target was undercutting the reference.** iOS targets 132 and shows three columns on the
phone in the screenshots. 88 was chosen to make tiles smaller and instead made them narrower, which
is not the same thing. The target is 120 now: three columns of 115 dp on a 411 dp phone, three of
121 dp on a 430 dp one — what the reference actually draws.

**Names were being chopped rather than travelling.** The reference uses `MarqueeText` for the orb
title; this port used `maxLines = 1` with an ellipsis. That matters more here than in most apps
because provider channel names are long *and* near-identical — `3Cat Exclusiu 1 / 2 / 3` differ only
in the character an ellipsis removes first. The title now uses `basicMarquee`, so a name too long
for its tile travels and the row stays legible.

Measured on the Pixel XL after the change: three columns, and the names render whole —
`2GB Sydney`, `3AW Melbourne`, `3Cat Exclusiu 1`, `3Cat Exclusiu 2`, `3Cat Exclusiu 3` all
distinguishable, where the previous build showed them run together and clipped.

The grid tests moved with the intent rather than being widened to pass: one now asserts a normal
phone gets **three** columns and carries the four-column mistake in its comment, and the size band
excludes the 320 dp phone explicitly, because the two-column floor puts it at 138 dp — which is what
the reference does on a small iPhone too.

Fire TV re-checked: `Home [40,160][160,264]`, `Live TV [40,268][160,372]`, 0 fatals.

**Still open:** the surface itself. The reference reads glassier — a softer bed under the artwork and
more air between rows — and this slice changed geometry and text, not the material. That is worth a
look of its own rather than being folded in here.

### MK.37.C.4 — the orb's surface, and two scaling bugs behind it — shipped 2026-09-04

The owner compared the grid to the reference build: "glassy ومرتب اكتر" against "زحمة ومشرشح".
MK.37.C.3 fixed the geometry and the text; this is the material. Two defects, both the same shape —
a constant tuned against the television orb, applied to every size once MK.37 started deriving tile
sizes from the lane.

**The lit treatment was gated on focus, and touch never moves Compose focus.** The orb's 28 dp glow
and its focus ring only ever appear on a focused node. On a television one orb always has focus, so
the rail reads as lit objects; **on a phone nothing is ever focused**, so every orb in the grid drew
in the resting state — a 6 dp shadow that disappears against `BackgroundDeep`, and a flat 1 dp
stroke. A sheet of flat cut-outs, which is exactly what "مشرشح" describes. Every orb now gets a
gradient edge — a stroke that reads as a bevel catching light from the upper left, the same story
`HexSurface` tells on the rectangular cards — and the resting bed went 6 dp to 12 dp so an unfocused
orb sits on a surface rather than on flat black.

**Live logos were clipped by the hexagon's own diagonal.** `HexCapsule` cuts 28% of the edge at top
and bottom, and a provider logo is a square image with the mark running to nearly its full width
(measured: V Sport ships 96x96 with the wordmark at 98%). The inset was a fixed **16 dp** — correct
for the 140 dp television orb and too small for everything else, because the cut is a *fraction* of
the tile and 16 dp is not. On the phone's 115 dp tile the logo needs **23.2 dp**, so the outer
letters were running into the diagonal: TNT keeping two of three rings, "ULTRAHD" ending at
"ULTRAH". `hexLogoInset` derives it as `clamp(art * 0.28, 10, 36) * 0.72`, which is where a centred
square clears the diagonal at every height it occupies. The reference documents this exact complaint
against its own build — "الايقونات شكلها غلط" — and fixes it the same way.

`HexLogoInsetTest` pins that the inset scales, that a phone tile needs more than the old constant,
and that it clamps at both ends.

Verified: Pixel XL renders without a crash at 23.2 dp inset; Fire TV `Home [40,160][160,264]` and
Chromecast both unchanged, 0 fatals.

**Not ported:** the reference also beds each card in a faint glow taken from its genre hue, so a rail
of off-centre cards reads as lit objects in their own colours. That needs the genre-hue mapping
brought across as well and is a bigger piece than a surface tweak.

### MK.37.D — Home sized from the lane it has — shipped 2026-09-04

Home's rails, hero and gutter were the last surfaces still drawing at Fire TV numbers. A rail tile
was `ShellDim.posterTile` — 220 dp, which is 53% of a 411 dp phone — so a phone rail showed one and
a half tiles with the rest off the right edge. All of it now reads `LocalShellMetrics`.

The structure was already right: Android's Home carries the same rails as the iOS one (FOR YOU,
AGAIN, ON AIR, YOUR LIBRARY, BROWSE x2) and the same hero. Only the sizing was wrong, so this slice
changes numbers and not composition.

**A regression caught by arithmetic before it reached the television.** `heroHeight` was
`lane * 0.82`, taken from the iOS port. That is right on a tall window and badly wrong on a wide
short one: a Fire TV's 868 dp lane gives 712, clamped to 380 — a **60 dp jump over the 320 the shell
has always drawn, and 70% of a 540 dp viewport spent on one card**. The formula now takes the
smaller of `lane * 0.82` and `windowHeight * 0.60`, because whatever the lane, a hero should not
take more than about three fifths of the fold. Two tests pin it: the Fire TV number must stay within
20 dp of the shipped 320, and no viewport may exceed 61% of its own height.

This is the failure mode `ShellMetrics` exists to prevent, reappearing inside `ShellMetrics` itself
— a proportion derived from one axis, applied to a window whose other axis is the scarce one.

**Measured on device rather than assumed.** On the Chromecast against the real catalogue:

| | before | after |
|---|---|---|
| rail tile | 220 dp | **220 dp — unchanged** |
| page gutter | 40 dp (`Space.section`) | 43.4 dp |
| hero | 320 dp | 324 dp by arithmetic — neither TV's Home had `heroSlides`, so it could not be observed |

The gutter is the only visible television change and it is 3.4 dp, which is the cost of one rule
instead of a constant. Tile width — the number that would actually have shown — is identical.

On the Pixel XL the rail tile is now **185 dp (647 px of 1440)**, down from 220 dp, which is
`lane * 0.45` and exactly the iOS formula on the same lane.

**Not observable this session:** the hero. Neither television's Home produced `heroSlides` (the Fire
TV has no sources at all and the Chromecast's account showed none), and the phone's sample sources
carry no continue-watching or EPG-now data. The arithmetic and the unit tests cover it; a device
look is still owed.

### MK.37.E — the guide as a list where a timeline will not fit — shipped 2026-09-04

A guide grid is channels down and time across, and it needs width. At the shipped density a phone in
portrait has room for **about ninety minutes of one channel** — not a guide, but a very wide list
with most of it off-screen and a horizontal scrollbar standing in for the information.

`GuideList` gives a tall window the same facts arranged for its shape: one row per channel, what is
on now with how far through it is, and what is next. The timeline is the part that does not fit;
"what is on" is what people came for, and it does.

Branched on `ShellMetrics.usesCoverflow` — the same property the shell and the browse screen read,
so the three cannot disagree about the shape of the window they are in.

**Three pure helpers carry all of the row's logic, and all three are tested.** `nowProgramme` uses a
**half-open** window (`start until end`): a programme ending exactly now is over and the one starting
exactly now is on, where treating both ends as inclusive shows two programmes as current for one
second every half hour. `programmeProgress` guards a zero or negative span, because providers ship
rows where a programme ends when it starts and a divide-by-zero on a progress bar is not a trade
worth making. Times are unix **seconds** throughout, matching `epg_programmes` and MB-390.

Long programme titles marquee rather than truncate, for the reason MK.37.C.3 established: a
truncated title usually loses the episode or match that identifies it.

**Deliberately not carried over.** The grid's per-programme affordances — tapping a *future* block
for a reminder, a *past* one for catch-up — have no target in a list, which shows one programme per
channel rather than an evening of them. A row here plays the channel. Those stay on the grid for
wide windows; inventing a long-press menu for them belongs in its own slice rather than being
smuggled into a layout change.

Verified: Pixel XL portrait renders the list, full-width rows, the no-guide branch showing correctly
for channels the sample sources carry no EPG for. Chromecast still renders the **grid** — time
headers present, no list text, 0 fatals. `GuideListTest` covers the seam, the gap, the empty channel
and the zero-length programme.

### MK.37.F — the player rotates, and its controls become touchable — shipped 2026-09-04

`PlayerActivity` moves from `sensorLandscape` to `fullUser`. MB-354 had pinned both activities to
`sensorLandscape` to fix a real defect — they disagreed, so a phone held in reverse-landscape
browsed upright and then played video upside-down. That fix stands; what changed is that
MainActivity became `fullUser` in MK.37.A, so pinning the player would have recreated the same
disagreement from the other side: browse in portrait, then a forced rotation on every play.
`fullUser` honours the viewer's own rotation lock, so anyone who wants video landscape-only gets it
from the system switch they already use. A television never reports a rotation, so this is inert
there. `configChanges` already carried the orientation set, so the player has never recreated on a
rotation and does not start now — which matters more here than in the shell, because a recreation
mid-stream would rebuild the ExoPlayer and rebuffer.

**And a defect that predates portrait entirely.** `PlayerChromeMetrics` sizes every control as a
fraction of screen width against a 1920 px television, with floors underneath. The floors were
written for a **D-pad** — the file says so: they stop a focus target shrinking below what a remote
user can pick out at three metres. A finger has a different minimum, and Android's is 48 dp.

Measured across the ratios:

| | hero | transport | secondary |
|---|---|---|---|
| Fire TV, 960 dp | 41.5 | 28.0 | 26.0 |
| phone landscape, 731 dp | **40 (floor)** | **27 (floor)** | **25 (floor)** |
| phone portrait, 411 dp | **40 (floor)** | **27 (floor)** | **25 (floor)** |

So on a phone in **landscape** — shipping today, before any of this — every control already sat on
a floor, and transport at 27 dp is a little over half the minimum touch target on the one form
factor where controls are touched rather than focused. Portrait did not create that; it only makes
every control sit there.

`hexSizeDp` now takes a `touch` flag and raises the floor to 48 dp for the three control variants.
`MENU_ICON` is left alone — it is the glyph inside a larger control, not a target.

**The gate is on `isTv`, not on width, and there is a test that says why.** A television is 960 dp
wide and its hero comes out at 41.5 dp — *below* 48 — so the touch floor would move the
television's own numbers if it were applied there, and any rule deciding "touch" from a width
threshold would eventually catch a wide TV.

Verified on the Pixel XL, playing a real file in portrait: window `1440x2560`, `PlayerActivity`
focused, 0 fatals, and every control measured at exactly 48 dp — Rewind, Pause, Forward, CC, Back,
More options, Cast; AUDIO at 53.7 dp. Both televisions relaunched at `1920x1080` with 0 fatals.

**Found while verifying, not fixed here:** `ContentDetailScreen` is still on the television's
two-pane layout in portrait — its content sits at x=952 of a 1440 px screen, pushed into the right
half with the left half empty. MK.37.D covered Home's rails and hero but not the detail page. It
needs the same `usesCoverflow`-style split the browse screen got (one column when the lane is tall)
and is its own slice.

### MK.37.G — the detail page, and a rating spotted on the television — shipped 2026-09-04

Found while verifying MK.37.F: `ContentDetailScreen` was still drawing the television's two-pane
identity block in portrait. Its content started at **x=952 of a 1440 px screen**, pushed into the
right-hand sliver with the left half empty. The arithmetic says exactly why — gutter 48 + poster 200
+ gap 24 = 272 dp, leaving the title column **91 dp of 411**.

Poster **beside** the title on a wide lane, **above** it on a tall one, chosen on
`ShellMetrics.usesCoverflow` — the same property the shell, the browse screen and the guide read. A
single `DetailIdentity` wrapper takes the children either way, so the block cannot drift between the
two shapes. Gutter, backdrop, content offset and poster all come off the metrics now.

**A regression caught by arithmetic before it was ever read.** `detailHeroHeight` was added in
MK.37.D as `windowHeight * 0.38` and nothing consumed it — which is the only reason it did not ship:
on the Fire TV that gives **220 dp against the 330 the detail page has always drawn**, a third of the
backdrop gone. A height-only fraction cannot reproduce a number chosen for a wide short screen. It
now takes the smaller of `windowHeight * 0.61` (the television's own 330 of 540) and `lane * 0.56`
(which stops a tall window handing the backdrop most of the fold — 230 dp on a phone rather than
446). `detailPosterWidth` is `lane * 0.24` clamped, which lands the television exactly on its
shipped 200 dp and gives a phone 120.

**And a real bug spotted on the television while verifying this.** The detail page's meta line read
`★ 0`. It was doing its own three wrong reads — the same three MK.36.4 fixed in the preview pane and
never applied here: `releaseDate.take(4)` prints "01/0" for the `01/04/2023` rows providers also
send; a movie with no `releaseDate` showed no year though 77% carry a `(YYYY)` in the title; and a
provider writes "0" for "not rated", which rendered as a confident star-zero. It reads `rowFacts`
now, like every other surface.

Verified: Pixel XL portrait — content starts at **x=72**, title 787 px wide, actions spanning the
full width, and no star-zero. Chromecast — content at x=535, which is gutter 43.4 + poster 200 +
gap 24 in dp, so the side-by-side layout is preserved; 0 fatals.

### MK.37.H — a way out of the detail page, and a splash worth looking at — shipped 2026-09-04

Two things reported from the phone.

**The detail page looked like it had no way out.** `ActionRow` was a plain `Row` of five buttons. A
non-wrapping row overflows *silently* — the last child, **Back**, was laid out past the right edge of
a 411 dp screen with nothing to indicate it existed. It is a `FlowRow` now and wraps onto a second
line; measured on the Pixel XL, Back sits at x=139..229, y=1765, fully on screen.

**The dismissal itself was never broken, and that is worth recording rather than glossing.** Both
paths were tested on the device before changing anything: the hardware key and the gesture-nav edge
swipe each returned to the grid, because `HomeScreen` owns that `BackHandler` (`ContentDetailScreen`
has none of its own — the only one in that file belongs to the season picker). So no handler was
added. What was broken is that a viewer could not *see* a way out, and concluding there wasn't one is
the correct reading of that screen.

**The splash logo.** MB-350 had already fixed a real bug here — the theme pointed at the 16:9
badge+wordmark lockup and the splash API masks its icon to a **circle**, cutting both ends off. Its
fix shrank the lockup until its diagonal fit the mask: 132x74 dp inside a 160 dp circle. That stopped
the clipping and left the logo small and adrift in a large empty circle, which is what was asked to be
redesigned.

A round mask wants a round subject. The system splash now draws `ic_logo_mark` — the 96x96 hexagon
badge — at 108 dp, so it *fills* the circle instead of being inscribed in it, and there is nothing
wide left to cut. MB-350's transparent 240 dp square underneath is kept and is still load-bearing:
without it the drawable's intrinsic size is the bitmap's and the platform scales the icon to fill the
canvas, pushing the mark back out to the mask's edge.

The wordmark is not lost — it moves to where it can be set as artwork. The platform splash draws one
masked icon and nothing else; it cannot show a wordmark beside a progress indicator. `BrandSplash`
draws both, in Compose, on the cinematic backdrop the shell already paints, so the hand-off to Home
is a change of content rather than a change of scene. The wordmark is `lane * 0.62` clamped, like
everything else in the shell.

**Gated on real work, never a timer.** The splash covers the first read of the source list and
disappears when it returns. A splash held open by `delay()` costs the viewer time on every launch;
this one is a single frame on a warm start and covers a wait that was already happening on a cold
one. `runCatching` means the gate releases whether the read returns or throws, so it can never strand
the app on a logo.

Verified: Pixel XL cold launch ~5.1 s reaching Home (the earlier phone measurement was 8.0 s), 0
fatals, never stuck on the splash. Fire TV 2265 ms and Chromecast 2001 ms — both **faster** than the
3827/3291 ms measured before, since the splash frame draws ahead of the shell — both reaching the
shell, 0 fatals.

### MK.37.H.1 — the More sheet was never rendered — shipped 2026-09-04

Tapping **More** ran the bar's indicator out to its slot and back and opened nothing. That is
exactly what it looked like from the outside — "بترجع المؤشر لمكان ما كان" — and the animation was
the only part working as designed.

`SectionFlowBar` has been setting `showOverflow` since MK.37.B, and **nothing read it**.
`SectionOverflowSheet` was written in the same commit and never called. The flag flipped, the
indicator did its return animation because the bar treats the overflow slot as a non-destination,
and the four hidden destinations — Guide, Recordings, Search, Settings — were unreachable on a phone
for four commits.

**Nothing could have caught this but using it.** The compiler is satisfied: a public composable that
is never called is not an unused symbol. Lint is satisfied for the same reason. `showOverflow` is
written and never read, which a warning *could* in principle flag, but not through a
`rememberSaveable` delegate. The gap between "the code exists" and "the code runs" is only closed by
driving the app, and MK.37.B's device pass checked that the bar *rendered* — six slots, correct
labels, indicator lifting — without ever pressing the sixth one.

Rendered like the search overlay directly above it rather than as a Material sheet, so it dims the
shell the same way, dismisses on a scrim tap and on BACK, and is anchored to the bottom where the bar
it belongs to lives. A tap-swallowing box under the sheet stops a choice falling through to the scrim.

Verified on the Pixel XL: More opens the sheet with all four destinations (Guide, Recordings, Search,
Settings); choosing Settings navigates there and dismisses the sheet; BACK dismisses it and stays in
the app on Home. Fire TV and Chromecast both reach the shell with no bottom bar composed at all,
0 fatals.

### MK.37.H.2 — the bar pointed at the wrong tab in an overflow section — shipped 2026-09-04

With Settings (or Guide / Recordings / Search) on screen, the bar's hexagon sat under whichever tab
had been pressed *before* More. The bar was pointing at a place the viewer was not.

`items.indexOf(current)` returns -1 for the four overflow destinations, because none of them is in
the bar, and `coerceAtLeast(0)` turned that into **Home**. The settle effect then declined to move
at all — it was guarded on `items.contains(current)`, false for exactly these sections — so the
marker simply stayed where it last was. Both halves had to be wrong for the bug to appear, which is
why it survived MK.37.B's device pass.

The overflow destinations now resolve to the More slot, and the settle effect has no `contains`
guard: an overflow section *has* a slot, and refusing to move for it is what stranded the marker.

`commit()` also stops bouncing. Tapping More used to run the hexagon out and immediately back, which
reads as the press being rejected — and while the sheet was going unrendered (MK.37.H.1) that bounce
was the *only* response the press produced. It now travels to More and stays; if the sheet is
dismissed without a choice, the settle effect returns it to the real section.

Verified on the Pixel XL by isolating each half: tapping More moves the marker to More and it stays;
tapping Movies moves it to Movies (so the animation itself was never at fault); and with Settings
open the lifted cell is **More**. Fire TV and Chromecast compose no bottom bar at all, 0 fatals.

**A verification note worth keeping.** The first run of this fix reported failure, and the fix was
fine — `./gradlew` had been invoked from the repo root, where it does not exist, so the command
failed silently and a **stale APK** was installed and measured. A build step that produces no
`BUILD SUCCESSFUL` line is not a build; grep for it rather than trusting the install that follows.

### MK.37.H.3 — the last settings tab sat 18dp above the section bar — shipped 2026-09-04

Reported as menus in Settings hiding below the fold and not coming up even at the end of a scroll.

Measured rather than assumed, and the first sweep was **wrong in the app's favour**: it tapped tab
coordinates captured before scrolling, so after the first tab every subsequent tap missed and six
tabs reported the identical last item at the identical y. A detector that returns "ok" six times
from one screen is not a pass. Re-locating each tab on a fresh dump before tapping gave six
genuinely distinct screens, all of which scrolled clear.

So the reported symptom does not reproduce as clipping: all **12** tabs are reachable and every tab
body scrolls clear of the bar (359–492px). But the margin at the end of the master list was
**18dp** — `About` bottoming at 2231 against a bar top of 2296 on a Pixel XL. Reachable, and only
just. That margin is consumed by a larger font scale, a taller nav-bar inset, or one more entry in
`SettingsTab`, and it is the one place in Settings where the last thing on the list is *nearly*
under the bar.

The list's symmetric 12dp padding is now 12dp top / 48dp bottom. Padding inside a `verticalScroll`
adds to the scrollable extent, so this buys travel rather than whitespace: clearance went 65px →
191px, a gain of 126px against a predicted 36dp × 3.5 = 126px. The arithmetic matching exactly is
what confirms the mechanism rather than a coincidence of scroll position.

Television is untouched by construction — `SettingsPhoneLayout` is reachable only when
`smallestScreenWidthDp < COMPACT_SETTINGS_SW_DP && !isTv`.

**Two install traps hit while verifying this, both of which fake a "fix didn't work" result.**
`installDebug` installs to *every* connected device, so with two TVs on release attached it fails on
signature mismatch and the phone never gets the build — the failure reads as if the phone rejected
it. And an `adb install` whose output is not printed is not a confirmed install. The first
re-measurement here returned byte-identical numbers, which is the signature of measuring the old
build, not of an ineffective change. Install to one device with `adb -s`, and print the result.

### MB-403 — the hidden-channels panel was unreachable on a television — fixed 2026-09-04

Reported from the sofa: in Settings → Parental, DOWN stopped at "Hide adult-tagged content" and the
panel beneath it never came up.

Scrolling in a settings tab is driven by **focus traversal**, not by a finger. When nothing is
hidden, the hidden-channels panel holds no focusable child — the "Unhide all" button is gated on
`hiddenIds`, the row list on `hiddenItems`, and what remains is two `Text` nodes. So D-pad DOWN from
the adult toggle had nowhere to go, the scroll never advanced, and the panel stayed below the fold
however long DOWN was held. The tab's `verticalScroll` was present and correct the whole time; a
scroll container is not reachable just because it exists.

Measured before: focus reached `[863,750][1769,940]` and then **five further DOWN presses moved
nothing**. After: the fourth press moves focus to `[904,827][1728,936]` and "No channels are hidden"
scrolls into view at y=880..936.

The panel is made focusable **only while it is inert**. With hidden channels present the button and
rows are real focus stops, and an outer focusable node would add a dead stop in front of them —
the failure `SettingsBackupTab` documents at its own `.focusable()` sites. That branch is correct by
construction but was **not** exercised on a device; hiding a channel and re-walking the tab is worth
doing before this pattern is copied anywhere else.

**Why touch testing could never have found this.** A drag scrolls a pane whether or not anything in
it can take focus, so the phone sweep walked to the end of Parental and reported it healthy. Two
earlier detectors in this same investigation also reported healthy for their own reasons (stale tap
coordinates; BACK handing focus to the sidebar so DOWN counted sidebar movement). Three green
results, one real bug — **on a TV the question is not "does the content scroll" but "can focus get
there"**, and only a D-pad walk answers it.

### MK.38 — live TV gets the same dock as film and episode — slice 1 shipped and device-verified

Reported by the owner: "البلاير تبع لايف تي في عالاندرويد تي في مالو نفس البلاير تبع الأفلام
والمسلسلات" — the live player is not the same player as the one for films and series.

**What it was.** `PlayerActivity` ran two control surfaces. VOD got `VodPlayerDock`, the Compose
dock; live got Media3's built-in controller. The code said why:

```kotlin
// LIVE keeps Media3's built-in controller (needed for the zap bar /
// program-progress overlays to ride alongside it).
playerView.useController = (item == null) || isLive
```

So the split existed to give two XML overlays something to attach to — not because any control
meant something different on a channel.

**How iOS solved it** (`PlayerScreen.swift`): one dock for both, with three adaptations — a `LivePill`
badge, the progress ribbon hidden, and ±10 disabled. The owner's note on that: the hiding was
because the bar could not fit everything, and the intended fix is a **More** button that surfaces
what does not fit rather than dropping it. iOS will be brought to whatever this lands on.

**What this does.** One dock, both kinds. `dockControlOrder(hasNext, isLive)` drops only `NEXT` —
the next EPISODE, which a channel does not have. ±10 stay, and deliberately: the player holds a
back-buffer, so rewinding inside it is real, and a stream that refuses the seek leaves the position
alone rather than breaking.

Where the seek ribbon sits on a film, live shows `LiveProgrammeRow` — what is on, how far through,
and what follows. That is **better than hiding it**, which is what iOS does today: the space is the
right place for the one thing a live viewer wants, and the app already computes it for the old XML
row. The row keeps its height with no guide data so the dock does not jump when zapping between a
film and a channel, and it is not focusable — a D-pad stop that cannot do anything costs a press.

`renderProgramProgress()` now feeds both the old XML row and the dock from **one** computation.
`epg_programmes.start_time` is XMLTV epoch seconds, and a second site doing its own clock arithmetic
is how two halves of one screen come to disagree about how far through a programme you are.

Five call sites moved from `controllerVisible` to `dockVisible`, and the chrome decision collapsed
from a `when` over content type to `currentItem != null && dockVisible`. That `when` was the reason
the two surfaces had to be kept in step by hand.

**Verified:** 506 app + 830 shared tests, 0 failures. Three new tests pin that live differs from VOD
by `NEXT` alone — anything else moving means the docks have drifted apart again. ktlintCheck green,
`assembleRelease` builds, installs on the Fire TV, app launches with 0 fatals and the sidebar at its
usual `[40,160][160,264]`.

**Device pass — Google TV, the owner's own catalogue, 2026-09-04.** The Fire TV has no source
configured and the phone's debug seed points at streams that 404, so neither could reach a playing
live item. The Google TV has the owner's real list; the pass ran there, on a release build.

It failed the first time, and the reason was not in anything the commit changed. **Two further sites
branched on `ContentType.LIVE`** and sent live to `playerView.showController()` — the CENTER key-UP
short press, and the `KEYCODE_DPAD_CENTER, KEYCODE_ENTER` case in `onKeyDown`. Once `useController`
went false for live, both became silent no-ops: pressing OK on a channel did nothing at all. The
sweep that found the other five sites grepped `controllerVisible`, which neither of these mentions —
they decide on content type instead. Fixed in `f11286e3`.

Then confirmed on screen, on `V SPORT ᵁᴴᴰ ³⁸⁴⁰ᴾ`: the dock renders with the green transport cluster
(ours, not Media3's), `NEXT` is absent while -10/+10 are present, and the programme row carries
now-title, `Up next`, the progress track and `57 min left • 185 min`.

**A method note worth keeping.** Two of my `uiautomator` dumps read `com.google.android.apps.tv.launcherx`
and I read them as "the dock did not appear" — the device had returned to the home screen and I was
pressing CENTER at the launcher. A negative device result is worth nothing until the dump's `package`
attribute says the app is on screen. The owner's photograph is what settled it.

D-pad focus was then checked on the same channel: the dock opens on play/pause, RIGHT walks
transport → CC → … → ⋯, and both edges hold — LEFT stops at -10 and RIGHT stops at ⋯ rather than
escaping into the video surface. Live → VOD swaps cleanly: a film renders the same dock with NEXT
and the seek ribbon, a channel with neither and the programme row instead.

**The claim about the zap bar was wrong**, and it was wrong here before it was wrong in the code.
The section said the zap bar and programme row "ride with the dock now". They did not. Their clock
had been Media3's `ControllerVisibilityListener`, and setting `useController = false` on live stopped
that listener firing, so the zap bar's visibility was decided at channel-change time — when the dock
is always closed — and never reconsidered. It could not appear at all. `MK.38.3` deletes both
overlays rather than reviving them: every fact they carried (channel name, LIVE badge, now, next,
progress) is already in the dock, in one place instead of two. See that commit for the
`ArrayStoreException` the removal uncovered.

### MK.38.2 — the dock measures itself

The owner's refinement, brought over from iOS: a **More** button that surfaces what does not fit,
rather than hiding controls. Android's version of that button already existed — `⋯` opens the
options root, and the root already holds Subtitles, Audio, Speed, Aspect and Favourites — so the
work was to pin it and drop around it, not to add a second `⋯`.

What was actually broken is that nothing had ever measured the row. Compose's `Row` does not wrap or
scroll: past the edge it measures children at **zero width**, so a control is not clipped, it is
absent. And `⋯` is last in the order, so the first thing a narrow screen deleted was the only
control that could reach what it had deleted.

Not hypothetical. Every size in the brief is a fraction of a 1920 px television, so on a phone they
all sit on their floors:

| Screen (landscape) | Locale | Row needs | Row has | Result |
|---|---|---|---|---|
| 731 dp | English | ~579 dp | 635 dp | fits |
| 731 dp | Spanish | ~610 dp | 635 dp | fits, barely |
| 640 dp | English | ~571 dp | 544 dp | **27 dp cut** |
| 640 dp | Spanish | ~601 dp | 544 dp | **57 dp cut** |

Spanish is not an edge case, it is `VELOCIDAD` where English has `SPEED`.

`fitDockControls` is a pure function over widths, drop order least-useful-first
(favourite → aspect → speed → audio → subtitles), with transport and `⋯` pinned at every width.
14 tests. The width formula moved to `PlayerChromeMetrics` so the calculation and `DockSecondary`
cannot disagree — and the device confirmed the formula exactly: CC 52 px, AUDIO 81 px, FIT 61 px,
gap 15 px, which are the numbers the fit calculation uses. At 1920 there is ~500 dp of headroom, so
nothing drops on a television and the TV render is unchanged.

**Measured on the owner's Pixel XL, 2026-09-04 — and it was not insurance, it was a live bug.**

The player carries `android:screenOrientation="fullUser"`, so a phone renders it in **portrait**
whenever the phone is held that way. That is 411 dp of width, not the 731 dp of landscape, and the
row needs ~558 dp. The overflow path is therefore on the ordinary phone case, today.

With the fix, on `Big Buck Bunny` at 1440x2560 / density 560:

    -10   ⏸   +10   |   CC   ⋯          AUDIO, SPEED, FIT, FAV → the menu

Every control measured 168 px = 48 dp (the touch floor), gaps 18 px = 5 dp, available 295 dp,
rendered 274 dp — and adding AUDIO would reach 333 dp. `fitDockControls` predicted exactly that set.

Then the same phone with only the measurement removed (one line, `fit.shown` back to
`dockControlOrder`), to see what was actually shipping:

| Control | Bounds without the fit |
|---|---|
| SPEED | `[1203,2343][1237,2511]` — **34 px wide**, crushed against the edge |
| FIT, FAV | **absent from the tree** |
| **MENU** | **`[0,0][0,0]`** |

So on a phone in portrait the ⋯ control was a zero-size node: not clipped, not small — gone, along
with three of the controls it exists to stand in for, and nothing in the tree said so.

**One correction to an earlier draft of this paragraph, which overstated it.** A phone was not
stranded: `updatePlayerChrome` puts a *More options* button in the player's top bar on phones, and
it opens the same options root, so Audio / Speed / Aspect / Favourites remained reachable — just not
from the dock, and not by any route the dock suggested. The claim that there was "no workaround" was
wrong, and the dump that proved the bug also showed `More options` sitting at `[1034,70][1202,238]`
in the same frame. On a television the top bar is hidden and there would have been no second route —
but on a television the row fits, so the case never arises. What the bug actually cost is the dock's
own promise: five controls advertised, four of them silently not there.

**Arabic, same phone.** The fit is identical to English — `-10`, play, `+10`, divider, `CC`, `⋯`,
with `الصوت` / `السرعة` / `الملاءمة` / favourite in the menu — and the bounds match English to the
pixel, because the row is pinned LTR (directional controls do not mirror) and `CC` is
`translatable="false"`. Everything around it does mirror: the metadata block right-aligns, the phone
chrome swaps corners, and the clock reads `٨:٠٣`.

Two defects only an Arabic phone could show, both fixed in `MK.38.4`: `vd_pause` read `إيقاف مأقت`
(the hamza belongs on the waw — `مؤقت`), and the -10 / +10 controls drew hardcoded ASCII numerals
next to that Arabic-Indic clock. **Still outstanding, and a native speaker's call:** the
`vd_rewind_10` / `vd_forward_10` accessibility strings keep a Western `10` inside Arabic text. It is
never rendered, and a TTS voice says عشرة either way, so it was left alone rather than edited on a
guess.

**Also seen on glass, at a size the dock cannot fit.** `wm size 1280x720` on the Google TV (reset
immediately after) rendered: `-10`, play, `+10`, NEXT, `⋯` — all five secondaries dropped, the
divider dropped with them because it then separated nothing, and `⋯` survived. That is exactly what
`DockFitTest` asserts, now observed rather than argued.

One honest caveat on that run: the size override also changed the effective density, so the controls
came out *larger* in dp than the phone case (hero ≈ 80 dp against 48 on a phone) rather than the
640 dp figures in the table above. It exercised the overflow branch hard — harder than a phone
would — but it is not a measurement of the phone numbers. Those still rest on the width formula,
which the 1920 render did confirm exactly: CC 52 px, AUDIO 81 px, FIT 61 px, gap 15 px.

### Remaining slices

| Slice | Scope |
|---|---|
| **37.B** | Shell navigation: `usesSidebar` drives rail vs bottom bar; port the travelling-hexagon `SectionFlowBar`. **Start by putting 37.A on a phone and looking at it.** |
| **37.C** | Browse portrait: grid composition + a pull-down category drawer (855 categories do not fit a strip). The dead `CategoryChipBar` is either the seed for this or should be deleted. |
| **37.D** | Home + detail: hero and rails off the measured lane; detail one-column when the lane is tall. |
| **37.E** | Guide portrait: now/next list instead of the timeline grid. |
| **37.G** | Device pass: phone portrait + landscape, Fire TV and Google TV byte-compare. |

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
| **Chromecast (MK.11.3 / MK.18.3) dropped permanently** — **PARTIALLY REVERSED 2026-06-15** (→ MK.26 Track B) | Default Cast receiver feasibility uncertain for IPTV streams (raw TS over HTTP with custom UA may not work). Custom Web Receiver is a separate project. User pattern: install YancoTV on every TV directly. **2026-06-15:** concern CONFIRMED by red-team (raw TS / AC-3 / HEVC-in-TS / UA-gated / cleartext all fail the default receiver; needs custom receiver + server-side proxy, never zero-lag) — so Cast is revived only as a *secondary, droppable* transport for app-less Chromecasts, movies/series-first. See MK.26 + rows below | 2026-04-25 |
| **TIF (MK.10.2) deferred to post-v1 study** | Fire TV doesn't support TIF (zero value on canonical test target); scope is `TvInputService` + parallel channel/program DB + EPG re-ingestion. Revisit only if Google TV becomes primary target | 2026-04-25 |
| **DLNA / UPnP (MK.18.4) dropped permanently** | Built for stored media not live streams; many DLNA renderers reject HLS/MPEG-TS or transcode badly. Older smart TVs aren't a target audience | 2026-04-25 |
| **Cross-device handoff (MK.18.5) deferred to post-v1 study** — **REVERSED 2026-06-15** (→ MK.26 Track A, PRIMARY) | Requires either a cloud backend (out of scope, GDPR implications) or LAN-only sync (loses home/away use case). Marginal value for live TV. **2026-06-15:** the *phone→TV "play on my TV"* direction needs only LAN (no cloud → no GDPR) and red-team confirmed it's the only zero-added-lag, all-content path (incl. Fire TV) — so it's revived as the PRIMARY cast transport. The "marginal for live TV" note was wrong: handoff is the *best* live-TV path. The *TV↔phone resume-continuity* (away case) stays post-v1 | 2026-04-25 |
| **No timeline / week estimates on the active plan** | Work proceeds at user's pace; sessions resume when rested. Estimates create false-precision pressure that doesn't match the actual cadence | 2026-04-25 |
| **Definition-of-Done per Stage 3+ feature** (R8 + Sentry + TalkBack + D-pad + Fire TV soak + no-new-placeholders) | Avoids the regression-fixing tail at the end of v1.0. Catches reflection breakage, accessibility regressions, and stub-shipping inline as features land | 2026-04-25 |
| **Schema migrations bundled into Stage 2** | Fragmenting migrations across features causes migration-A-vs-migration-B conflicts; one upgrade test pass over a single schema bump is safer | 2026-04-25 |
| **MK.9 (FFmpeg) is Stage 1, not late-stage** | MB-14 leaves ~30% of streams audio-only; UX polish on broken playback is wasted. Risk-front-loading: if NDK / R8 keep rules / ABI splits go sideways, we want to know before stacking 6 features on top | 2026-04-25 |
| **MK.14 Recording = HLS + MPEG-TS, both, in v1.0** (overrides 2026-04-24 phase-2 deferral of MPEG-TS) | "HLS-only recording" ships broken on Xtream catch-up which is mostly TS. Recording must work on the streams the user actually has, or it's not "complete" | 2026-04-25 |
| **Cast-to-TV approved as MK.26 — reverses the 2026-04-25 Chromecast drop + handoff deferral** | Two research + adversarial red-team workflows (2026-06-14/15, 20 agents) established: Google Cast can't carry raw-TS / AC-3 / UA-gated / cleartext live without a server-side proxy and is never zero-lag; Fire TV isn't a Cast receiver (Fling EOL 2026-03-05, Matter Casting is app-launch-only); the LAN companion handoff to YancoTV-on-the-TV is zero-lag for ALL content. User wants live + movies + series cast | 2026-06-15 |
| **LAN companion handoff is the PRIMARY cast transport (MK.26 Track A)** | Reuses the TV's native player — handles raw TS / AC-3 / HEVC / `.mkv` / headers / cleartext at zero added lag; LAN-only, no cloud, no GDPR. Matches the user's "install YancoTV on every TV" pattern. Google Cast is SECONDARY / droppable (MK.26 Track B) for app-less Chromecasts only | 2026-06-15 |
| **Generic non-Cast smart TVs (Roku, old Samsung/LG, dumb TVs) are an accepted coverage gap** | No reliable Android-app path for live IPTV (not Cast receivers, no DLNA push for live TS, Android can't send AirPlay). Workaround: a ~$35 Google TV / Fire stick running YancoTV, or the TV's own IPTV app. DLNA stays dropped (reconfirmed by red-team) | 2026-06-15 |
