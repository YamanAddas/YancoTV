# Incident: HTML5 Player Refactor — Silent VOD Failures

**Date:** 2026-04-16
**Status:** Archived incident report. This is NOT the live bug register — see [bugs.md](../../bugs.md) for the current register.
**Scope:** Movies and Series not playing (no visible error, silent failures) during the mpv → HTML5 player refactor.
**Context:** Captures bugs 1–29 discovered during the refactor. Both mpv and HTML5 backends still coexist in `src/`. Reference when touching `src/renderer/components/player/` or resuming the HTML5 work; do not treat it as a list of open issues against the current shipped app.

---

## Root Cause Summary

The player was recently refactored from **mpv** (external process, JSON-RPC IPC) to **HTML5 `<video>`** (in-renderer, with hls.js + mpegts.js). This transition is **incomplete** — the two systems are partially wired and there are critical codec/format gaps that cause most VOD content to silently fail.

---

## BUG 1 — CRITICAL: `.mkv` and `.avi` classified as "native" but HTML5 `<video>` cannot play them

**File:** `src/renderer/components/player/player-utils.ts:9-22`
**Impact:** Most Xtream VOD movies/series use `.mkv` containers. ALL of them fail.

The `detectStreamType()` function classifies `.mkv` and `.avi` as `'native'`, which means the code does `video.src = url` directly. But Chromium's HTML5 `<video>` element **does not support MKV or AVI containers**. Chromium only supports: MP4 (H.264/AAC), WebM (VP8/VP9/AV1 + Opus/Vorbis), Ogg, and WAV.

```typescript
// CURRENT (broken):
if (lower.endsWith('.mkv') || lower.endsWith('.avi') || ...) return 'native';
```

**What happens:** The native player sets `video.src = "...mkv"`, Chromium fires `MEDIA_ERR_SRC_NOT_SUPPORTED`, the fallback chain tries `.ts` then `.m3u8` — but most Xtream servers don't serve the same movie in all formats, so all three attempts fail. The user sees "Unable to play — format not supported" or in many cases the error display is swallowed (see Bug 5).

**Fix:** Route `.mkv` and `.avi` through mpegts.js first (it can handle MPEG-TS wrapped MKV-style streams from Xtream). Alternatively, these formats genuinely need mpv — they cannot be played in-browser. The real fix is either:
1. Route unsupported containers through mpv (keep the mpv backend for formats HTML5 can't handle), OR
2. Ask the Xtream server for an HLS (.m3u8) version of the stream first (many servers support this)

---

## BUG 2 — CRITICAL: Dual player architecture — mpv IPC still fully wired but renderer ignores it

**Files:**
- `src/main/ipc/index.ts:81-113` — Creates MpvPlayer, forwards events to renderer
- `src/renderer/stores/player-store.ts` — Uses HTML5 `<video>` via `getVideoElement()`, ignores IPC
- `src/main/preload.ts:134-186` — Exposes full mpv player API + event listeners

**Impact:** Two completely separate player systems exist. The renderer's `play()` action does NOT call `window.api.player.play()`. The mpv-based IPC handlers in main process are never invoked by the new player code. Meanwhile, the preload script still exposes `player.onStateChange`, `player.onTimeUpdate`, `player.onError` listeners for mpv events that the renderer never subscribes to.

**What breaks:**
- No IPC `player.play()` call → mpv never starts → no playback via mpv
- The HTML5 player works for MP4/HLS/TS but fails for MKV/AVI (Bug 1)
- Dead code in main process: `getPlayer()`, all mpv IPC handlers, mpv event forwarding
- If someone calls `window.api.player.pause()` it would send an IPC to mpv (which isn't running), not the HTML5 video

---

## BUG 3 — HIGH: `.mov` classified as "native" — unreliable in Chromium

**File:** `src/renderer/components/player/player-utils.ts:17`

`.mov` (Apple QuickTime) is listed as native, but Chromium's support for `.mov` is inconsistent — it depends on the codec inside (ProRes = no, H.264 = maybe). Most IPTV `.mov` files will fail silently.

---

## BUG 4 — HIGH: Format fallback only works for VOD URLs with `/movie/` or `/series/` in path

**File:** `src/renderer/components/player/VideoPlayer.tsx:97-117`

The fallback chain (native → mpegts → HLS) only activates when `isVodUrl(url)` is true, which checks for `/movie/` or `/series/` in the URL path. But:
- M3U-sourced movies don't have Xtream-style paths — they have arbitrary URLs like `http://provider.com/films/xyz.mkv`
- Stalker portal VOD URLs don't follow Xtream patterns
- Any non-Xtream VOD with unsupported format gets ONE attempt only, then fails

---

## BUG 5 — HIGH: Silent failure — errors swallowed without user feedback

**File:** `src/renderer/components/player/VideoPlayer.tsx:135-143`

When all format attempts fail, the code sets `status: 'error'` and an error message. But `PlayerContainer.tsx:96-107` only shows the error overlay when `status === 'error'` — and the `isActive` check on line 23 doesn't include `'error'`:

```typescript
const isActive = status === 'playing' || status === 'paused' || status === 'buffering';
```

The error overlay IS rendered outside the `isActive` guard (line 96), so it should show. However, there's a race condition: if the mpegts/native player fires multiple error events rapidly during the fallback chain, the status can briefly flip to 'idle' (via the `onEnded` handler or other state transitions) before the final error state is set. Also, the `stop()` action resets status to `'idle'` and mode to `'idle'` — so if the user hits Escape during the fallback attempts, the error state is lost.

**Additionally:** The `video.play().catch(() => {})` calls at lines 312, 434 in VideoPlayer.tsx silently swallow play promise rejections. If autoplay fails (e.g., browser policy, broken stream), no error is surfaced.

---

## BUG 6 — MEDIUM: `replaceStreamExtension` fallback generates wrong URLs for some Xtream servers

**File:** `src/renderer/components/player/player-utils.ts:33-39`

The function replaces the file extension to try alternative formats (e.g., `.mkv` → `.ts`). But many Xtream servers use different endpoint paths for different formats — not just different extensions. For example:
- Movie: `/movie/user/pass/123.mkv`
- HLS of same: `/movie/user/pass/123/123.m3u8` or completely different endpoint

Simply replacing `.mkv` with `.m3u8` produces a URL the server may 404 on, wasting the fallback attempt.

---

## BUG 7 — MEDIUM: mpegts.js recovery loop can stall the player

**File:** `src/renderer/components/player/VideoPlayer.tsx:384-409`

When mpegts.js hits errors, the recovery logic does `player.unload()` + `player.load()` + `video.play()` up to `maxErrors` times. But:
- Each recovery attempt takes time, during which the UI shows nothing (or a frozen frame)
- The `debouncedBuffering()` debounce (400ms) means the spinner may not even show during these retries
- For VOD, `maxErrors` is 2, so it gives up quickly — but for live streams it's 5, which can mean 10+ seconds of apparent hang

---

## BUG 8 — MEDIUM: HLS.js config too aggressive for fallback attempts

**File:** `src/renderer/components/player/VideoPlayer.tsx:292-305`

When HLS is used as a fallback (not the primary format), `fragLoadingMaxRetry` is set to 2 and `manifestLoadingMaxRetry` to 2. But the primary attempt uses 6/4 retries. If the server is slow to respond to the `.m3u8` fallback URL, 2 retries may not be enough — especially since some Xtream servers need to transcode on-the-fly when serving HLS for a file that's stored as MKV.

---

## BUG 9 — MEDIUM: `XtreamClient.buildStreamUrl` uses provider-reported `containerExtension` which can be wrong

**File:** `src/main/services/xtream-client.ts:364-370`
**File:** `src/main/services/content-store.ts:364-368`

During sync, VOD stream URLs are built using `stream.containerExtension` from the Xtream API response. But providers frequently report wrong extensions:
- Extension is empty string or whitespace → handled by `?.trim() || defaultExt` guard
- Extension is `"mkv"` but file is actually remuxed MP4 on the server
- Extension is `"mp4"` but the actual container is MKV (wrong metadata from provider)

This means the initial URL may already point to the wrong format, and the fallback chain tries alternatives for an extension that was wrong to begin with.

---

## BUG 10 — MEDIUM: Stale mpv pipe name — cannot reconnect after process crash

**File:** `src/main/player/mpv-player.ts:48`

The pipe name is set once in the constructor: `mpv-yancotv-${randomUUID().slice(0, 8)}`. If the mpv process crashes and `spawnMpv` is called again, it tries to use the SAME pipe name. On Windows, if the old pipe wasn't fully cleaned up, the new mpv process can't bind to it, causing connection failures. This is a latent bug in the mpv backend (currently unused by renderer, but still present).

---

## BUG 11 — LOW: `getVideoErrorMessage` native error handler can fire during library-managed playback

**File:** `src/renderer/components/player/VideoPlayer.tsx:177-187`

The `onNativeError` handler checks `if (hlsRef.current || mpegtsRef.current) return;` to skip errors when a library is active. But there's a timing gap: when `advanceToNext()` calls `destroyPlayers()` (which sets refs to null), then loads the next format — if the video element fires a lingering error from the PREVIOUS source after refs are cleared but before the new player is set up, `onNativeError` fires and calls `advanceToNext()` again, potentially skipping a valid fallback attempt.

---

## BUG 12 — LOW: History position recording broken in HTML5 player

**File:** `src/renderer/stores/player-store.ts:277-301`

The `initPlayerEventListeners` function sets up a 5-second interval to update watch position. But it only fires when `status === 'playing'`. The throttle check `if (now - lastHistoryUpdateAt < 30_000) return;` means updates only go out every 30 seconds. Combined with the fact that position comes from the store (not directly from `video.currentTime`), and the store's position is only updated every 500ms (throttled in VideoPlayer.tsx:13-22), there can be up to 30.5 seconds of unwatched progress lost if the app crashes or stream stops unexpectedly.

---

## BUG 13 — LOW: Keyboard shortcuts use store state for seek, not video element directly

**File:** `src/renderer/hooks/use-player-shortcuts.ts:64-76`

Seek uses `state.position` (from the store, throttled to 500ms updates) instead of `video.currentTime` directly. This means rapid key presses can seek to stale positions, causing jumpy/inaccurate seeking. The `seek()` action itself does use the video element directly, but the position it seeks TO is stale.

---

## BUG 14 — INFO: Dead code / orphaned mpv infrastructure

These files/systems are fully wired but never used by the current HTML5 player:

| File | Status |
|------|--------|
| `src/main/player/mpv-player.ts` | Dead — renderer never calls player IPC |
| `src/main/player/mpv-ipc.ts` | Dead — only used by mpv-player |
| `src/main/player/mpv-path.ts` | Dead — only used by mpv-player |
| `src/main/ipc/index.ts` lines 687-865 | Dead — all PLAYER_* IPC handlers unused |
| `src/main/preload.ts` lines 134-186 | Partially dead — only `setFullscreen` and `loadSubtitleFile` are used |
| `src/main/services/timeshift-service.ts` `getTimeshiftMpvArgs()` | Dead — only used by mpv-player |

---

## Recommended Fix Priority

1. **Bug 1 + Bug 2** (Critical): Decide on player strategy — either complete the HTML5 migration by routing MKV/AVI through mpegts.js or HLS conversion, or re-enable mpv as a fallback for unsupported formats. This is THE reason most movies/series don't play.

2. **Bug 4** (High): Extend format fallback to ALL VOD URLs, not just Xtream-patterned ones.

3. **Bug 5** (High): Ensure error states are always visible and never silently swallowed. Stop eating `play()` promise rejections.

4. **Bug 9** (Medium): Before playing, probe the actual format (e.g., try HLS first since Xtream servers often support it regardless of stored extension).

5. **Bugs 6-8** (Medium): Improve fallback chain robustness.

6. **Bugs 10-14** (Low/Info): Cleanup and minor fixes — address after core playback works.

---

## SWEEP 2 — Additional Bugs Found

---

## BUG 15 — CRITICAL: Series played from Favorites/Search/Home use empty `streamUrl`

**Files:**
- `src/renderer/pages/FavoritesPage.tsx:47-51`
- `src/renderer/pages/SearchPage.tsx:57-61`
- `src/renderer/pages/HomePage.tsx:53-60` (favorites card)

These pages call `play(item.streamUrl, ...)` directly on any content item, including **series parents**. But Xtream series parents are stored with `streamUrl: ''` (empty string) in the database:

```typescript
// content-store.ts:422 — Xtream series insertion:
insertContent.run(
  ...
  '', // No direct stream URL for series parent  ← EMPTY
  ...
);
```

When a user favorites a series and clicks it from Favorites/Search/Home, the player receives an empty URL, enters theater mode with `status: 'buffering'`, and then `VideoPlayer.tsx` runs `detectStreamType('')` which returns `'mpegts'` (the default). mpegts.js then tries to fetch an empty URL, silently fails, and the user sees an infinite spinner or a black screen.

**Fix:** These pages should navigate to the detail page (`/series/${item.id}`) for series items instead of calling `play()` directly. Only live channels and movies should play directly.

---

## BUG 16 — HIGH: `XtreamClient.getVodInfo` rating has operator precedence bug

**File:** `src/main/services/xtream-client.ts:351`

```typescript
rating: String(info.rating ?? info.rating_5based ? `${info.rating_5based}/5` : info.rating ?? ''),
```

Due to JavaScript operator precedence, `??` binds tighter than `?:`, so this evaluates as:

```typescript
String((info.rating ?? info.rating_5based) ? `${info.rating_5based}/5` : (info.rating ?? ''))
```

This means:
- If `info.rating` is truthy → it enters the `?` branch and returns `"${info.rating_5based}/5"` which could be `"undefined/5"` if `rating_5based` is missing
- If `info.rating` is `null`/`undefined` but `info.rating_5based` is truthy → correct behavior (accident)
- If both are falsy → returns `''` (correct)

**Fix:** Add parentheses: `info.rating ?? (info.rating_5based ? \`${info.rating_5based}/5\` : '')`

---

## BUG 17 — HIGH: `loadSubtitleFile` IPC goes to mpv (not HTML5 video)

**File:** `src/renderer/stores/player-store.ts:260-264`
**File:** `src/main/ipc/index.ts:842-865`

The store's `loadSubtitleFile` action calls `window.api.player.loadSubtitleFile()` which opens a file dialog in main process and then calls `getPlayer().addSubtitleFile(path)` — which sends the path to **mpv** via IPC. Since mpv is never started by the HTML5 player, this throws "mpv is not running" (caught and returned as `{ ok: false }`). The user picks a subtitle file and nothing happens.

**Fix:** Load the subtitle file into the HTML5 `<track>` element or use a subtitle renderer library (e.g., subtitle.js, ass.js). The file dialog part can stay in main process, but the subtitle must be applied to the `<video>` element, not mpv.

---

## BUG 18 — HIGH: Audio/subtitle track selection doesn't work in HTML5 player

**Files:**
- `src/renderer/components/player/SettingsPanel.tsx:98-128` (Subtitles tab)
- `src/renderer/components/player/SettingsPanel.tsx:134-142` (Audio tab)
- `src/renderer/stores/player-store.ts:250-258` (toggleSubtitles)

The subtitles toggle in the store manipulates `video.textTracks`, which only works for HTML5 `<track>` elements loaded via the DOM. IPTV streams typically embed subtitles in the transport stream — these are handled by hls.js/mpegts.js internally, not exposed as `video.textTracks`. So the toggle does nothing for embedded subtitles.

The Audio tab just shows a static message "Audio track selection for HTML5 streams is automatic" — there's no actual track switching capability.

The `TheaterControls` component shows subtitle/audio track buttons based on `subtitleTracks.length` and `audioTracks.length` from the store — but these arrays are never populated by the HTML5 player. The mpv property observer used to fill them (`parseTrackList` in mpv-player.ts), but the HTML5 `VideoPlayer.tsx` never writes to `subtitleTracks` or `audioTracks`. So these buttons never appear, even when the stream has multiple audio/subtitle tracks.

---

## BUG 19 — MEDIUM: MediaInfo mostly empty in HTML5 player

**File:** `src/renderer/components/player/VideoPlayer.tsx:239-244`

The `onLoadedMetadata` handler only sets `width` and `height`:

```typescript
usePlayerStore.setState({
  duration: dur && isFinite(dur) ? dur : 0,
  mediaInfo: { width: video.videoWidth, height: video.videoHeight },
});
```

But `MediaInfo` in the store also has `videoCodec`, `audioCodec`, `fps`, `bitrate` fields. These are never populated by the HTML5 player (they were populated by mpv's property observers). The Info tab in SettingsPanel shows `--` for everything except resolution.

**Impact:** The "Info" tab is mostly empty. Users can't see what codec/bitrate they're getting, which is important for debugging playback issues.

---

## BUG 20 — MEDIUM: `getContentByTypeMerged` has SQL string interpolation

**File:** `src/main/services/content-store.ts:908-915`

```typescript
const rows = db
  .prepare(
    `SELECT c.* FROM content c
     JOIN sources s ON c.source_id = s.id
     WHERE c.type = ?
     ORDER BY s.priority ASC, c.${order.replace('ORDER BY ', '')}`,
  )
  .all(type) as ContentRow[];
```

The `order` variable comes from `sortClause(sort)` which is a switch statement on a known enum, so there's no user-injectable input reaching the SQL. However, the pattern of string-interpolating into SQL is fragile — if `sortClause` ever returns unexpected content, it becomes injectable. The `.replace('ORDER BY ', '')` is also brittle since `sortClause` returns e.g. `"ORDER BY sort_order ASC"` and the replace strips the prefix.

**Impact:** Not exploitable today, but a maintenance hazard. The `c.` prefix is also wrong for clauses that reference `COALESCE(clean_title, title)` since it becomes `c.COALESCE(clean_title, title)` which is invalid SQL. This means multi-source browsing with `name-asc`, `name-desc`, or `group` sort will **throw a SQL error**.

---

## BUG 21 — MEDIUM: Renderer imports types from main process files

**Files:**
- `src/renderer/pages/HomePage.tsx:5-6` — `import type { FavoriteEntry } from '../../main/services/favorites-store'`
- `src/renderer/pages/FavoritesPage.tsx:6` — `import type { FavoriteEntry } from '../../main/services/favorites-store'`
- `src/renderer/pages/HomePage.tsx:6` — `import type { HistoryEntry } from '../../main/services/history-store'`

These imports cross the Electron process boundary at the **type** level. Since they use `import type`, they're erased at compile time and don't actually load main-process code into the renderer. However:
- They create a dependency from renderer tsconfig to main-process files, which can break project references
- If someone accidentally removes the `type` keyword, it would try to load `better-sqlite3` in the sandboxed renderer (crash)
- The types should be in `src/shared/types/` for clean architecture

---

## BUG 22 — MEDIUM: EpisodesTab fetches positions sequentially (N+1 query)

**File:** `src/renderer/components/EpisodesTab.tsx:41-56`

```typescript
const fetchPositions = async () => {
  const positions = {};
  for (const ep of episodes) {
    const pos = await window.api.history.getPosition(contentId, ep.id);
    if (pos && pos.positionSeconds > 0) positions[ep.id] = pos;
  }
  setEpisodePositions(positions);
};
```

For a series with 100+ episodes, this makes 100+ sequential IPC round-trips. Each IPC call has overhead (serialize → send → main process handles → serialize → return). This can cause noticeable delay (1-3 seconds) when opening a series detail page with many episodes.

**Fix:** Add a batch IPC handler like `history.getPositionBatch(contentId, episodeIds)` that does a single SQL query.

---

## BUG 23 — MEDIUM: `initPlayerEventListeners` called before React renders

**File:** `src/renderer/main.tsx:9`

```typescript
initPlayerEventListeners();  // Called at module level, before React mounts
```

This sets up the history update interval immediately, but the interval accesses `usePlayerStore.getState()` which is fine (Zustand works outside React). However, the function returns a cleanup function that is **never called** — it's invoked at module level, not inside a `useEffect`. If hot-module replacement (HMR) triggers during dev, this creates duplicate intervals that keep running, causing double position updates and potential race conditions.

---

## BUG 24 — MEDIUM: Video element can remain in DOM after stop in edge cases

**File:** `src/renderer/stores/player-store.ts:153-181`

The `stop()` action calls `video.removeAttribute('src')` and `video.load()` to reset the video element, then sets `mode: 'idle'`. When mode becomes `'idle'`, `PlayerContainer` returns `null`, which unmounts `VideoPlayer`. But `VideoPlayer`'s cleanup effect (line 194-201) tries to access `videoRef.current` — which is already null after unmount. The `destroyPlayers()` call in cleanup may not fire if the component unmounts before the effect runs, leaving hls.js or mpegts.js instances alive in memory.

---

## BUG 25 — LOW: `detectStreamType` HLS detection matches `.m3u8` anywhere in URL path, not just extension

**File:** `src/renderer/components/player/player-utils.ts:10`

```typescript
if (lower.includes('.m3u8')) return 'hls';
```

This checks if `.m3u8` appears **anywhere** in the URL, not just as the file extension. A URL like `http://server.com/stream.m3u8-backup/video.ts` would be incorrectly classified as HLS. Unlikely in practice but incorrect.

---

## BUG 26 — LOW: Native playback timeout (20s) is too long for an obviously wrong format

**File:** `src/renderer/components/player/VideoPlayer.tsx:163-169`

When native playback is attempted for a `.mkv` file, Chromium typically rejects it within 1-2 seconds with `MEDIA_ERR_SRC_NOT_SUPPORTED`. But the timeout is set at 20 seconds. If the server is slow to respond (not a format error, but a network timeout), the user waits 20 seconds before the fallback tries. Combined with the fallback chain (3 attempts), worst case is 60+ seconds of apparent hang.

---

## BUG 27 — LOW: `advanceToNext` re-entrancy guard can miss valid calls

**File:** `src/renderer/components/player/VideoPlayer.tsx:123-146`

The `advancing` flag prevents re-entrant calls to `advanceToNext()`. But it's set synchronously and cleared at the end of the function. If `startAttempt()` throws synchronously (caught by try/catch in line 171-174), the catch calls `advanceToNext()` — but `advancing` is still `true` from the parent call, so the recovery call is silently dropped. The next format attempt is never tried.

Actually looking more carefully: the `advancing` flag is set before `startAttempt()` and cleared after — but `advanceToNext` itself is what increments `currentIdx` and calls `startAttempt`. The re-entrancy comes from error handlers firing synchronously during `startAttempt`. The flag correctly prevents double-advance in MOST cases, but the synchronous throw path in `startAttempt`'s try/catch calls `advanceToNext()` while `advancing` is true, dropping the attempt.

---

## BUG 28 — LOW: `destroyPlayers` cleanup doesn't reset mpegts.js monkey-patched `destroy`

**File:** `src/renderer/components/player/VideoPlayer.tsx:420-429`

For live streams, `initMpegts` monkey-patches `player.destroy` to clear the catch-up interval:

```typescript
const originalDestroy = player.destroy.bind(player);
player.destroy = () => {
  clearInterval(catchupInterval);
  originalDestroy();
};
```

But in `destroyPlayers` (line 64-81), the code calls methods on `mpegtsRef.current` including `destroy()`. If the player was for a live stream, this correctly clears the interval. However, if the monkey-patched `destroy` throws (e.g., player already destroyed), the interval leaks. The catch block on line 77 swallows the error, preventing the interval from being cleared.

---

## BUG 29 — INFO: `SettingsPanel` Escape handler captures before player shortcuts

**File:** `src/renderer/components/player/SettingsPanel.tsx:49-58`

The settings panel registers its Escape handler with `capture: true` (`addEventListener('keydown', handler, true)`), which intercepts Escape before it reaches `usePlayerShortcuts`. This means Escape in settings panel correctly closes the panel. But there's a subtle issue: `e.stopPropagation()` is called, which stops the event from reaching the player shortcuts handler. This is CORRECT behavior, but if the settings panel is open during an error state, pressing Escape closes settings instead of exiting theater mode. The user needs to press Escape twice (once for settings, once for theater). Minor UX friction.

---

## Updated Recommended Fix Priority

### Must Fix (playback broken)
1. **Bug 1** — `.mkv`/`.avi` can't play in HTML5 — main cause of movie failures
2. **Bug 15** — Series from Favorites/Search/Home play with empty URL
3. **Bug 20** — Multi-source sort crashes with SQL error for name/group sorts

### Should Fix (features broken)
4. **Bug 16** — Rating display shows wrong values
5. **Bug 17** — Subtitle file loading goes to dead mpv
6. **Bug 18** — Audio/subtitle track selection non-functional
7. **Bug 4** — Format fallback limited to Xtream URL patterns
8. **Bug 5** — Silent play failures

### Should Fix (performance/reliability)
9. **Bug 22** — N+1 IPC for episode positions
10. **Bug 24** — Potential hls.js/mpegts.js memory leak on stop
11. **Bug 23** — HMR duplicate intervals

### Nice to Fix
12. **Bugs 2, 14** — Dead mpv code cleanup
13. **Bugs 3, 6-13, 19, 21, 25-29** — Edge cases and polish

---

## Quick Test: How to Verify

1. Load an Xtream source with movies
2. Open DevTools console (`Ctrl+Shift+I`)
3. Click play on any movie — watch for:
   - `Player: trying native (1/3)` followed by errors
   - `Native playback timed out` or `Native playback error`
   - Fallback attempts to `.ts` and `.m3u8`
   - Final "Unable to play" error (or silent black screen)
4. Check the stream URL in the console — if it ends in `.mkv`, that's Bug 1
5. Favorite a series → go to Favorites → click it → infinite spinner (Bug 15)
6. Add 2+ sources → Movies page → sort by Name → SQL error in main process log (Bug 20)
7. Play a movie → Settings gear → Info tab → all `--` except resolution (Bug 19)
8. Play a movie → Settings gear → load subtitle file → nothing happens (Bug 17)
