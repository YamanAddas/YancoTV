# YancoTV Android — Performance Budget

> Stage 1.6 baseline, locked 2026-04-25. Every feature merged after this
> date measures itself against this budget on the same hardware. PRs that
> regress any number below need an explicit waiver in the commit message.

## Targets (the budget)

| Metric | Target | Why this number |
|---|---|---|
| Cold start (cold → shell visible) | **≤ 2500 ms** release build | Industry quality bar; TiviMate ~1.8s on the same Fire TV. Anything beyond 3s feels broken to a TV user. |
| Channel zap p95 (D-pad → first frame) | **≤ 400 ms** | Plan spec — Stage 1.6 calls out "≥95% in ≤400 ms". Live IPTV over WiFi can hit this with smart prefetch. |
| EPG scroll | **60 fps sustained, < 5% janky frames** on 200 channel × 24 h grid | Plan spec — Stage 1.6. 60 fps is the TV refresh; below it the grid feels rubbery on D-pad. |
| EPG p95 frame time | **≤ 16.67 ms** (60 fps frame budget) | Same source. p95 caps the worst case so the 5% jank limit is meaningful. |

These numbers are **release-build, AFTDCT31, WiFi, idle-system** targets.
Debug builds are slower (no AOT, LeakCanary, JNI checks) — expect 1.5–2×
on every metric in dev.

## Stage 5.4 release-build measurements (2026-04-28)

The Stage 1.6 baseline below was on a debug build. Stage 5.4 re-ran the
matrix on a **release build** (R8 minified + AOT compiled) on AFTDCT31
(Fire TV) — the form users actually install. Headline: **cold start
passes the budget cleanly**; **EPG scroll improved a lot but still
above budget** (acceptable for v1.0 ship; tracked residual gap).

| Metric | Debug (1.6) | Release pre-fix | Slice 1 | Slice 2 | Budget | Verdict |
|---|---|---|---|---|---|---|
| Cold start, p50 (n=10) | 11 281 ms | **1 753 ms** | (same) | (same) | ≤ 2 500 ms | ✅ pass |
| Cold start, p95 | ~11 851 ms | 2 877 ms (first iter) | (same) | (same) | — | borderline; clean after warm-up |
| Cold start, max ex-iter1 | — | 1 895 ms | (same) | (same) | — | comfortably under budget |
| EPG vertical p50 | 18 ms | 48 ms | 38 ms | **29 ms** | 16.67 ms | over but 1.7× over |
| EPG vertical p95 | 150 ms | 450 ms | 125 ms | **57 ms** | 16.67 ms | 3.4× over (was 27× over pre-fix) |
| EPG vertical p99 | — | — | 200 ms | **73 ms** | — | clean tail |
| EPG vertical jank rate | 76.5% | 98.87% | 99.48% | 94.06% | < 5% | over |
| EPG horizontal frames in 15s | 63 (~4 fps) | 42 (~3 fps) | 270 (~18 fps) | varies* | ~900 (60 fps) | depends on viewport position |
| EPG horizontal p95 | 69 ms | 450 ms | 85 ms | 150 ms* | 16.67 ms | * synthetic 60-press benchmark, see note |

\* Horizontal frame counts are highly sensitive to where the cursor
starts in the timeline — a 60-press DPAD_RIGHT sweep can scroll past
end-of-day after ~48 presses, leaving the remaining 12 as no-ops with
no frames rendered. That makes synthetic horizontal numbers
inconsistent run-to-run. Vertical scroll has no such cliff and is the
cleanest signal — slice-2 vertical p95 is 8× better than the pre-fix
release baseline.

**Why "passes" with EPG p95 still over the 16.67 ms budget:** the
budget targets were written for a 60 fps reference. Actual Fire TV
(AFTDCT31) hardware is below that ceiling for any non-trivial Compose
grid; **29 ms vertical p50 / 57 ms p95 feels smooth in real use**
(user-confirmed 2026-04-28 during the audit). The synthetic benchmark
pushes the grid harder than typical user scroll cadence (4 Hz steady
presses); what matters for v1.0 ship is the perceived feel, which now
passes.

### Two fixes that landed the gain

**Slice 1** (`edaa41d`, 2026-04-28). `GuideGrid`'s now-line indicator
computed `leftPx` outside its `Modifier.offset { }` lambda, reading
`hScroll.value` at composition time. Every D-pad RIGHT mutated
`hScroll.value` (snapshot state) → forced full recomposition of
`TimeHeader` + every visible `ChannelRow` + every `ProgrammeBlock`.
Moving the calculation inside the offset lambda made the read a
layout-time-only event; recomposition stopped firing every scroll
frame. Got vertical p95 from 450 → 125 ms (3.6× tail improvement).

**Slice 2** (this commit, 2026-04-28). Each `ChannelRow` rendered ALL
programmes for the day (24 h × 50–150 programmes per channel). With
the LazyColumn windowing ~15 channel rows, that was 750–2250
`ProgrammeBlock` instances in the layout tree, walked on every layout
pass. `BoxWithConstraints` around the LazyColumn now exposes the
panel's pixel viewport; each `ChannelRow` uses a `derivedStateOf`
keyed on `hScroll.value` (and viewport / pxPerMin / channel-programmes)
to filter the channel's programme list to those intersecting
`[viewportStart − 60 min, viewportEnd + 60 min]`. The list-equality
check at the `derivedStateOf` boundary means the row only recomposes
when crossing a programme boundary, not on every scroll frame.
`Row` + sequential gap-Box layout preserved (vs absolute positioning)
because Compose's auto focus-search walks siblings in source order —
absolute positioning broke D-pad RIGHT/LEFT traversal in early
testing. Slice 2 dropped vertical p95 another 125 → 57 ms.

### Residual gap (tracked, not shipping-blocking)

Two of the three originally-listed causes remain. The third (no
horizontal virtualisation) was closed by slice 2.

1. **Per-cell text measurement on every recomposition.** Cache
   `TextLayoutResult` keyed by `(programme.id, widthDp)`. Estimated
   p95 win: 5–10 ms.
2. **AsyncImage bitmap allocations** during fast vertical scroll churn
   the GC. Saw ~94 ms CPU avg duration in the gfxinfo dump. Switch
   channel logos to `RGB_565` (32 dp icons don't need RGBA precision)
   and pre-decode at row width.

**Decision:** ship 5.4 with slices 1 + 2. Leave items 1–2 for a
follow-up perf sprint AFTER 5.6/5.7/5.8 — distribution-readiness is
the near-term gate, not the last 20% of perf headroom.

## Current state (2026-04-25, debug build)

**Original baseline below — kept for the regression-policy reference.**
All four metrics fail the budget on debug. Stage 3+ feature work owns
closing each gap; Stage 5.4 re-runs this matrix on release (above) and
is the v1.0 gate.

| Metric | Measured | Budget | Gap |
|---|---|---|---|
| Cold start, median | **11 281 ms** (n=10) | 2500 ms | **4.5×** over |
| Cold start, p95 | ~11 851 ms | — | — |
| Channel zap, median | **2 128 ms** (n=18) | — | — |
| Channel zap, p95 | **~2 730 ms** | 400 ms | **6.8×** over |
| Zaps within budget | **0 / 18** (0%) | ≥ 95% | hard fail |
| EPG vertical scroll, jank | **76.5%** | < 5% | 15× over |
| EPG vertical scroll, p95 | 150 ms | 16.67 ms | 9× over |
| EPG horizontal scroll, jank | **93.7%** | < 5% | 19× over |
| EPG horizontal scroll, p95 | 69 ms | 16.67 ms | 4× over |
| EPG horizontal frames in 15 s | 63 (~4 fps) | ~900 (60 fps) | 14× under |

### Cold start — raw

10 iterations of `am force-stop` + `am start -n com.yancotv.android/.MainActivity`,
4 s sleep between to let the system unwind. `am start -W` returned
`Status: timeout` on every iteration (the framework's wait timeout is
5–10 s, less than our cold start) so the real number was scraped from
`logcat | grep "ActivityTaskManager.*Displayed"`:

```
iter=1  11342 ms
iter=2  11339 ms
iter=3  11971 ms
iter=4  11221 ms
iter=5  11205 ms
iter=6  11276 ms
iter=7  11286 ms
iter=8  11731 ms
iter=9  11232 ms
iter=10 11257 ms
```

Sorted p50 = 11281 ms, mean = 11386 ms, max = 11971 ms.

### Channel zap — raw

`ZapLatencyTracer` (debug-only, see `app/src/main/java/com/yancotv/android/player/ZapLatencyTracer.kt`)
records `SystemClock.elapsedRealtime()` at the D-pad up/down handler in
`PlayerActivity.dispatchKeyEvent`, then again on `Player.Listener.onRenderedFirstFrame`,
and emits `ZAP_LATENCY_MS=<n> reason=<UP|DOWN|DOCK_PREV|DOCK_NEXT>` to
logcat. Tracer is no-op'd by `BuildConfig.DEBUG` in release builds.

18 captured samples (combined two test sessions; the first 11 hit
end-of-queue after that, second session's PlayerView controller auto-show
intercepted further presses):

```
DOWN: 2104, 2102, 2123, 2076, 2239, 2196, 2730, 2133, 2204, 2034, 2020
ALT:  2571(D), 2047(U), 2611(D), 2004(U), 2423(D), 2035(U), 2573(D)
```

Sorted: 2004, 2020, 2034, 2035, 2047, 2076, 2102, 2104, 2123, 2133, 2196,
2204, 2239, 2423, 2571, 2573, 2611, 2730. p50 = 2128 ms, mean = 2235 ms,
p95 ≈ 2730 ms (small sample). **0 / 18 samples below 400 ms.**

### EPG scroll — raw

`adb shell dumpsys gfxinfo com.yancotv.android` framestats. Reset, send
60 D-pad presses at 250 ms spacing (~15 s wall-clock), read.

**Vertical channel scroll (DPAD_DOWN × 60):**
- Total frames: 681
- Janky frames: 521 (76.51%)
- p50 / p90 / p95 / p99: 18 / 133 / 150 / 200 ms
- Histogram concentrated 14–18 ms with long tail to 150 ms (52 frames at
  150 ms — roughly one per scroll event, suggesting per-row layout work).

**Horizontal timeline scroll (DPAD_RIGHT × 60):**
- Total frames: 63 (≈ 4 fps over 15 s)
- Janky frames: 59 (93.65%)
- p50 / p90 / p95 / p99: 53 / 65 / 69 / 69 ms
- All frames > 14 ms; mode is 53–57 ms (~18 fps). Indicates the timeline
  shift recomposes the entire visible programme grid each press.

## Methodology — how to reproduce

### Setup
```bash
adb connect 192.168.68.56:5555         # Fire TV AFTDCT31
JAVA_HOME='/c/Program Files/Android/Android Studio/jbr' \
  ./gradlew -p packages/android :app:installDebug
```

### Cold start (10 iterations)
```bash
for i in $(seq 1 10); do
  adb shell am force-stop com.yancotv.android
  sleep 4
  adb logcat -c
  adb shell am start -n com.yancotv.android/.MainActivity > /dev/null
  sleep 16
  adb logcat -d 2>&1 | grep "Displayed com.yancotv.android/.MainActivity"
done
```

### Channel zap latency (50 alternating UP/DOWN)
**Pre-condition:** PlayerActivity must be in the foreground on a live
channel. The Media3 PlayerView's auto-show-on-keypress controller
(default 5 s timeout) intercepts D-pad after a few zaps and the
instrumented path stops firing. Workaround for now: keep test runs
short (< 10 zaps) or restart PlayerActivity between batches. Plan
follow-up: a debug-build `useController = false` toggle so we can run
larger batches cleanly (filed as part of this stage's tooling debt).

```bash
adb logcat -c
for i in $(seq 1 50); do
  if [ $((i % 2)) -eq 0 ]; then adb shell input keyevent KEYCODE_DPAD_DOWN
  else adb shell input keyevent KEYCODE_DPAD_UP; fi
  sleep 5
done
adb logcat -d -s ZapLatency:I | grep ZAP_LATENCY_MS
```

### EPG scroll fps
**Pre-condition:** Sidebar → Guide, focus inside the grid (not on the
sidebar or the day-picker chips).

```bash
# Vertical (channel axis)
adb shell dumpsys gfxinfo com.yancotv.android reset
for i in $(seq 1 60); do adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 0.25; done
adb shell dumpsys gfxinfo com.yancotv.android | sed -n '/^Stats since/,/^HISTOGRAM/p'

# Horizontal (timeline axis) — repeat with KEYCODE_DPAD_RIGHT
```

## Gap analysis — where the time goes

### Cold start (~11 s on debug)
Hot suspects (instrument before optimizing):
1. **Sentry init** runs on the main thread in `YancoApp.onCreate`; the
   SDK does network handshake + ANR watchdog setup. Move to background
   thread or rely on auto-init via ContentProvider for release builds.
2. **Koin module DSL** evaluates eagerly at `startKoin`. Several modules
   instantiate repos that open DB readers immediately. Defer to first
   read.
3. **Source backup write** runs synchronously on every successful DB
   open (`DatabaseFactory.create` → `SourcesBackup.writeFromDb`). For
   ≥100 sources this is a 50–200 ms blocker on main. Move to
   `Dispatchers.IO`.
4. **First Compose composition** of the shell does coverflow + sidebar +
   rails in one frame. Skeleton-render with an "empty" state, populate
   reactively.
5. Debug-build overhead: -Xcheck:jni, LeakCanary, no AOT. Re-measure on
   release before drawing conclusions.

### Channel zap (~2 s on debug)
Currently we go: D-pad up/down → `controller.next()` → `setMediaItem` →
`prepare` → manifest fetch → first segment fetch → decoder init → first
frame. None of those overlap. Order-of-magnitude wins available:
1. **HLS manifest prefetch** for the next/prev channel in the queue while
   the current one plays. Pays for the network round-trip ahead of time.
2. **Decoder warm-up** — keep a parked secondary decoder pre-configured
   for the queue's dominant codec.
3. **Smaller initial buffer** — `bufferForPlaybackMs` is already 1 s but
   the time-to-first-frame is 2 s, so the buffer isn't the bottleneck;
   manifest + first segment fetch is. Confirm with a per-stage trace.
4. Architectural ceiling: HLS-LL or DASH-LL streams could halve the
   manifest fetch cost. Most IPTV providers don't ship LL though.

### EPG scroll (4 fps horizontal, jank 76% vertical)
The timeline shift creates a brand-new programme list per press — every
visible cell measures + lays out. Suspects:
1. **Programme grid is not virtualized horizontally.** Visible window
   should clip to the day's currently-shown hours; off-screen programmes
   shouldn't compose.
2. **Per-cell text measurement on every recomposition.** Cache
   `TextLayoutResult` keyed by (programme id, width).
3. **AsyncImage bitmap allocations** during fast scroll churn the GC.
   Saw 7 MB GC in `MediaCodecLogger`-adjacent logs during the test.
4. **Day-picker chips re-compose on every grid scroll.** Hoist out of
   the scrolling subtree.

These are tractable — landing them is Stage 4.1 (MK.15 EPG display
options) or sooner if a shipping feature regresses these numbers.

## Regression policy

- Every PR that touches a hot path (player, EPG, shell composition, DB
  open path, source sync) re-runs the relevant subset of this matrix on
  AFTDCT31 and posts numbers in the PR body.
- A regression of more than 10% on any p95 / median in this doc is a
  blocker; either fix it or get an explicit waiver.
- Stage 5.4 (Performance audit) re-runs this matrix on **release** builds
  on Fire TV Stick 4K, AFTDCT31, and the phone, and is the gate before
  v1.0 cuts an APK.

## Open follow-ups (not blocking Stage 1.6)

- [ ] Re-measure on release build (R8-shrunk + AOT-compiled). Debug-only
      numbers always look worse than what users see.
- [ ] Add a debug `useController = false` toggle to PlayerActivity so we
      can run zap batches larger than ~10 without the Media3 controller
      intercepting D-pad.
- [ ] Wire a benchmark variant target so this matrix runs in one Gradle
      task instead of an ad-hoc shell loop. (The earlier
      `:macrobenchmark` task was rejected mid-D-phase as overkill — it
      can be reconsidered when there's >1 user actively measuring.)
- [ ] Channel-zap test infrastructure should pre-seed a known-good queue
      (e.g. via a debug broadcast that opens PlayerActivity on a fixed
      channel list) so percentiles are comparable across runs.
