# ADR 0001 — Pivot from React Native to Native Android + iOS

**Status:** Accepted (2026-04-20)
**Supersedes:** [PRODUCTION_PLAN_ANDROID.md](../../PRODUCTION_PLAN_ANDROID.md) (React Native roadmap, frozen)

## Context

Phases 0 through M3R of the React Native mobile app landed: shared core extraction, op-sqlite, React Navigation 7, drawer + tabs shell. M4R.1–M4R.2 landed flat `ContentGrid` + `CategorySidebar`. Then the shell buckled under real-device usage on Fire TV Stick 4K:

- Boot stalls on the hydration gate.
- Navigation is sluggish — visible frame drops switching screens.
- Player takes two back-presses to close.
- Search crashes while typing.
- **~30% of channels decode audio-only** (HEVC-main10 / AC3 / EAC3 / DTS / TrueHD codec gap — react-native-video doesn't ship the FFmpeg extension).

A week of bridge debugging ended with commit `09150e9` (M4R.Player, 2026-04-20): the fix for Fire TV black-screen-with-audio was a native `PlayerActivity` + `PlayerLauncher` NativeModule that bypasses the RN bridge entirely. Media3 ExoPlayer, direct. It worked first try.

Pattern recognition: every TiviMate-shaped feature we need next — mini-preview that keeps playing while you browse, channel zap, PIP, Leanback integration, Android TV launcher channels, voice search — is either a custom native bridge in RN, or free in Compose / Media3. TiviMate, IPTV Smarters, Kodi, VLC are all native. The substrate has to match the competition if we want to beat it.

## Decision

**Stop work on the React Native app. Rewrite Android (and build iOS alongside) as native.**

- **Android:** Kotlin 2.x + Jetpack Compose + `androidx.tv.material` (TV) / Material3 (phone) + Media3 ExoPlayer direct. Lives at `packages/android/`.
- **iOS / iPadOS:** SwiftUI + Kotlin Multiplatform framework. Scheduled post-Android-1.0. Lives at `packages/ios/`.
- **Shared business logic:** Kotlin Multiplatform at `packages/shared/` — types, parsers, clients, classifier, title-cleaner, EPG, catchup URL builder, SQLDelight schema, repos, StateFlow view models. Consumed by Android via Gradle and iOS as an Xcode framework.
- **Desktop:** unchanged. Electron + React + `packages/core/` (TypeScript) keeps shipping.

## Consequences

**Cost accepted.** ~12 weeks to reach current M4R parity + surpass it on Android. RN-side M4R shell work (HomeShell, ContentPanel, navigation, focus, virtualized rails) is thrown away. `@yancotv/core` TypeScript business logic gets ported to Kotlin (~2 weeks in MK.1) — the desktop keeps its TS implementation; the two are mirrored ports with mirrored tests. iOS adds another ~6–8 weeks post-Android-1.0.

**What we keep.** The native `PlayerActivity` + `PlayerLauncher` from `09150e9` — Kotlin already. Folds into the new Android app with minor changes (share one `ExoPlayer` instance between mini-preview and fullscreen via `PlayerView.switchTargetView()`).

**What we lose.** Weeks of RN shell development. The mobile app loses its "in-production" status and resets to "in rewrite" on the product side until MK.12 ships.

**What we gain.**

- TV launcher channels, voice search, PIP, Leanback integration as first-class features instead of custom native bridges.
- No more bridge-debug cycles. The 30% codec gap is closable in MK.9 (FFmpeg ExoPlayer extension, NDK-built).
- A single KMP framework powering Android **and** iOS. Two ports instead of three.
- Compose + SwiftUI feel native on their platforms — phone UI wouldn't feel "Android-like" on iOS with a cross-platform UI kit.

## Alternatives considered

- **Stay on React Native, fix the bridge.** Rejected: the fixes already in flight are 80% custom native code. We'd pay the native cost anyway and ship a worse product wrapped in JS.
- **Flutter.** Rejected: throws away the native Activity work we just shipped; no mature TV UI library; still wrong substrate for native TV feel.
- **Compose Multiplatform for UI.** Rejected: phone UI would feel "Android-like" on iOS, and TV UI isn't supported on iOS anyway. Shared business logic only; UIs stay platform-native.
- **Keep RN but rewrite player layer as native.** Partially what we did in M4R.Player — but it means every future feature is the same song: native module, bridge glue, platform fork. The mobile app becomes 80% native anyway, hidden behind a JS veneer.

## References

## Amendment — 2026-09-04: the ports are not mirrors

"The two are mirrored ports with mirrored tests" above described the intent at the time of the
pivot. **The owner has since decided otherwise:** `packages/core/` and `packages/shared/` are
independent, each carries what its own apps need, and a capability landing in one is not a debt
owed by the other.

This is not a loosening of standards; it removes work that bought nothing. The original text is
left as written because an ADR records what was decided when, and porting for symmetry has since
produced concrete waste — see `bugs.md` MB-200, where an M3U streaming rewrite was reasonable on a
Fire TV's 384 MB heap and pointless on Electron's gigabytes, and MK.36.3b, a follow-up slice filed
purely to close a gap nobody had asked to close.

What still crosses both ports is anything the *user* can see the difference in — a title that
cleans one way on the TV and another on the desktop is a bug, not a divergence. The operative rule
now lives in AGENTS.md (the two-ports note and rule 8).


- Commit `09150e9` — M4R.Player: native `PlayerActivity` + `PlayerLauncher` (the fix that made the decision obvious)
- [PRODUCTION_PLAN_NATIVE.md](../../PRODUCTION_PLAN_NATIVE.md) — active native roadmap (MK.0 → MK.12 → MK.iOS.*)
- [PRODUCTION_PLAN_ANDROID.md](../../PRODUCTION_PLAN_ANDROID.md) — frozen RN roadmap, reference only
- [packages/mobile/CLAUDE.md](../../packages/mobile/CLAUDE.md) — frozen RN app state at commit `fe6819e`
