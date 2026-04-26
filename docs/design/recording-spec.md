# Recording — interaction & technical design (Stage 3.1, MK.14)

> Plan rule: *"Spec the 'record while playing? while another channel
> plays?' interaction questions BEFORE writing code."* This doc is
> that gate — every interaction question gets a decision before any
> recording code lands.

Locked 2026-04-26 as MK.14.1 entry-criterion. Revisions go in this
file with a date stamp; deviations made during implementation are
either reflected here or backed out.

---

## 1. Architectural shape

**The recorder is independent of the player.** Two parallel HTTP
clients fetch from the same source URL (or different sources). They
share nothing at runtime. Reasons:

- The player's ExoPlayer/MediaCodec stack is hardware-coupled and
  optimised for low-latency presentation. Sharing its byte stream
  with a recorder via tee would block presentation on disk I/O.
- Multiple concurrent recordings need their own fetchers anyway
  (different start times, different segments). Building one general
  case is simpler than the player+recorder shared-tee special case.
- Catch-up + future-scheduled recordings have no live player.

Cost: extra bandwidth when watch + record on the same channel. For
IPTV streams that's typically 5–20 Mbps × 2 = 10–40 Mbps; well within
home WiFi budgets and the user's existing IPTV plan. Acceptable.

**Single `RecordingService` instance, multiple in-flight recordings.**
The `FOREGROUND_SERVICE_TYPE_DATA_SYNC` notification updates as
recordings start/stop; we don't spin a service per recording.

**Recording engine lives in `:shared`** (Kotlin Multiplatform). The
HTTP fetcher, segment-tee logic, and per-format writers
(HLS / MPEG-TS) are pure Kotlin + Ktor + OkIO. Android wires the
`RecordingService` + `MediaStore` writes + `WorkManager` scheduling
on top. iOS gets the same engine wrapped in a `BGTaskScheduler` +
`URLSession` pair when MK.iOS lands.

---

## 2. Interaction questions — answers

### Q1. Can you record the channel you're currently watching?

**Yes.** Both the player and the recorder fetch independently. The
player's UX is unaffected. Notification + sheet badge surface the
"recording in progress" state.

### Q2. Can you record one channel while watching another?

**Yes.** Independent fetchers; no constraint.

### Q3. Can multiple recordings happen simultaneously?

**Yes, up to a hard cap.** Default cap = **3** concurrent recordings.
Reasons:

- Bandwidth: 3 × 10 Mbps = 30 Mbps, fine for home WiFi but the floor
  for "this is starting to thrash."
- Disk I/O: 3 concurrent writers on a Fire TV's eMMC start contending
  with the OS write-back cache; user sees random ANRs.
- Coordination cost in the UI: the user can keep ≤3 recordings in
  their head; more just generates "what's recording right now"
  confusion.

Cap is configurable in Settings → Recordings (range 1–5). The 4th
concurrent record-now or scheduled-fire either prompts the user
("Already recording 3 channels — replace which?") or, for scheduled,
marks the schedule `missed` with reason `concurrent_cap`.

### Q4. What happens if you change channels mid-recording?

**Recording continues unaffected.** The player loads the new channel;
the recorder keeps fetching the old channel. The user sees the
"Recording…" indicator on a small badge in the shell.

If the user explicitly stops the recording (notification action,
RecordingsScreen, sheet), the recorder finalises the file and writes
the final `recordings.status = 'completed'`.

### Q5. Scheduled recording fires while you're watching a different channel?

**Recording starts silently.** No interruption to playback. A
non-intrusive toast surfaces ("Recording started: <programme title>")
and the persistent notification shows the count.

### Q6. Scheduled recording fires while an in-flight recording already exists for the same channel?

**No-op + warning.** Schedule's state goes to `completed` (the
desired outcome — "this channel is being recorded right now" —
is already true), `error` field set to
`"already_recording_inflight: <recording_id>"`. The user can find
the actual recording row in the browser and play / trim / share it.

### Q7. What if you try to play a stream that's currently being recorded?

**Stream from the network, not from the recording file.** Cleaner —
the recording's container may be mid-segment-write and the player
has no way to know what's safe to read. Two TCP connections to the
same source URL is the easy answer.

Once the recording finishes, the file is playable normally via
`PlaybackController.play(filePath)` — the existing local-file path.

### Q8. What about Xtream catch-up streams during recording?

**Treated as just another stream URL.** The catch-up URL builder
already produces a fully-qualified HTTP URL (`UrlBuilder` lives in
shared). The recorder fetches it like any other stream. Catch-up
streams are bounded (the programme has a fixed end time) so the
recorder finishes naturally when the source HTTP server stops
serving segments.

### Q9. What if the recording fetcher can't keep up (network blip, server hiccup)?

**Best-effort with explicit failure modes.** The recorder has a
`Strategy`:

- **Retry with backoff** for 5xx + connection-reset (max 3 retries
  per segment). Lost segment after retries → log gap, continue.
- **Fail fast** for 4xx (the source has revoked / the URL is wrong);
  state `failed`, `error` carries the HTTP code.
- **Heartbeat watchdog**: if no segment lands for 60 s, fail with
  `error = "heartbeat_timeout"`. Avoids a stuck recorder hanging the
  service forever.

User-visible: status flips to `failed` with the error reason; the
notification shows the failure briefly; the row stays in the
RecordingsScreen with an "i" affordance to inspect details and a
"retry from now" action that creates a new recording at the current
time.

### Q10. App is killed mid-recording — what happens?

**Recovery on relaunch.** On `RecordingService.onCreate` after a
crash recovery launch (Android process death), we scan
`recordings WHERE status = 'recording'` and:

- For each, decide based on `started_at` and the configured "max
  age before treating as orphan":
  - < 10 min old: try to resume (rebind the fetcher to the same URL
    and continue appending — this works for HLS because each segment
    is a separate request, not a long-poll).
  - ≥ 10 min old: mark `failed` with `error = "orphaned_by_app_kill"`.
- Either way, partial file is preserved; user can play the partial
  recording in the browser.

**iOS won't get this niceness in v1.0** — `BGTaskScheduler` doesn't
let us run for hours. iOS recording is bounded by the OS background
budget; `MK.iOS` will shape the contract differently and this doc
gets a forked section then.

### Q11. Storage cap reached — what happens to a new recording?

**Auto-evict oldest completed recordings until enough space.** Logic:

```
needed = expected_size_for_recording (estimated from bitrate × duration)
                                     OR a 1 GB default if we don't know

while storage_for_recordings + in_flight_size + needed > cap:
    oldest = oldest completed recording with no "keep" flag
    if oldest == null:
        return Outcome.NO_SPACE
    delete oldest's file + row
    log "auto_evict: <id> for <new_id>"
```

If the user has all recordings flagged `keep` (a future Stage 5+
feature — for v1.0, no `keep` flag exists, so all completed are
evictable), we have eviction headroom by construction.

The "expected size" estimate for a scheduled recording uses
`scheduled_end - scheduled_start` in seconds × peak observed bitrate
on this channel × 1.2 safety. If we don't know the bitrate, fall
back to 1 GB and hope.

### Q12. Storage cap reached mid-recording?

**Stop the recording cleanly with `error = "storage_cap_hit"`.**
We don't delete other recordings to make room mid-stream — the user
will be confused if files vanish without warning. Instead, the
in-flight recording finalises whatever it has, marks `failed`, and
notifies the user with a Settings shortcut ("Storage full: tap to
manage recordings").

The Settings → Recordings screen shows the cap, current usage, and a
"Free space" action that triggers manual eviction.

### Q13. Low-storage warning before scheduled record fires?

**Yes, 1 hour before scheduled_start the WorkManager arm-loop
checks free space.** If `available < 2 × estimated_size`, queue a
notification (`channel = "yanco_recordings_warning"`) telling the
user to free space or the recording may fail. Notification has an
action that opens Recordings settings.

We don't auto-cancel on low space — the recording might still
succeed (network hiccup might keep the actual size below the
estimate, or the user might free up space in time).

---

## 3. Recording engine — shapes

### `Recorder` interface (commonMain)

```kotlin
interface Recorder {
    val state: StateFlow<RecorderState>
    /** Start a new recording. Returns the record id (also the row PK). */
    suspend fun start(input: RecordInput): String
    /** Finalise an in-flight recording and flush. Idempotent on a stopped recorder. */
    suspend fun stop(recordId: String): Result<RecordResult, Throwable>
}

data class RecordInput(
    val sourceUrl: String,
    val title: String,
    val output: Sink,            // OkIO sink — Android wraps MediaStore output
    val format: RecordingFormat, // HLS, MPEG_TS
    val maxDurationMs: Long?,    // null = unbounded; scheduled recs set this
    val userAgent: String?,
    val referer: String?,
)

sealed interface RecorderState {
    data object Idle : RecorderState
    data class Recording(val recordId: String, val bytesWritten: Long, val secondsElapsed: Long) : RecorderState
    data class Failed(val recordId: String, val reason: String) : RecorderState
}
```

### Format implementations

- **HlsRecorder** — fetches `.m3u8` manifest, follows updates, fetches
  each `.ts` segment in order, writes raw TS bytes to the sink. The
  resulting file is a valid concatenated MPEG-TS that ExoPlayer
  can play back via the existing `PlaybackController.play(file)`
  path. No remuxing.

- **MpegTsRecorder** — direct HTTP GET of an HTTP-served MPEG-TS
  stream (e.g. Xtream catch-up). Reads the body in fixed-size chunks
  (188-byte aligned, 16 KiB chunk = 87 packets) and writes them to
  the sink. No segment concept; just one long byte stream until the
  server closes the connection or `maxDurationMs` elapses.

Both recorders honour:
- `Strategy.maxRetriesPerRequest = 3` with exponential backoff (1s, 2s, 4s).
- `Strategy.heartbeatTimeoutMs = 60_000`.
- Cooperative cancellation via the host coroutine.

### Persistence + observability

- `RecordingsRepository` (commonMain) wraps `recordingsQueries`. The
  recorder calls `repo.markStarted(...)`, `repo.markCompleted(id, sizeBytes, durationSec)`,
  `repo.markFailed(id, reason)`. The repo handles transitions; the
  recorder doesn't write SQL directly.
- All errors flow through Kermit's `Logger` (already wired to
  Sentry via `SentryKermitWriter` from Stage 1.3) — every recording
  failure becomes a Sentry event automatically.

### Android wiring

- `RecordingService : Service` with `FOREGROUND_SERVICE_TYPE_DATA_SYNC`.
  Holds a `Map<String, Job>` of in-flight recordings. `onStartCommand`
  receives an intent with the input bundle; spawns a coroutine on
  `Dispatchers.IO`; the coroutine drives the recorder and updates
  the notification on each segment.
- Notification channel `"yanco_recordings"` (LOW importance — no
  sound). Notification body: `"<n> recording(s) in progress · <total
  size>"`. Per-recording progress not shown (would be too busy at
  3 concurrent); detail lives in RecordingsScreen.
- Output: `MediaStore.Video` insert with `RELATIVE_PATH = Movies/YancoTV/Recordings`
  and a sanitised filename `<sourceName>-<ymd>-<hms>.ts`. The
  resulting `content://` URI goes into `recordings.file_path`.

### WorkManager scheduling

- Each `recording_schedules` row armed via `OneTimeWorkRequest` with
  `setInitialDelay(scheduled_start - now - padding_pre)`.
- The work tag is the schedule id, so we can cancel via
  `WorkManager.cancelUniqueWork(scheduleId)` when the user cancels.
- The arm loop runs every 15 minutes via `PeriodicWorkRequest` to
  catch schedules that landed within the last window (covers the
  case where the device was off when the schedule was created and
  came online later).

### EPG long-press → schedule

- `GuidePanel`'s programme cell already has a long-press handler; in
  Stage 3.1 it grows a "Record this programme" row. Tapping creates
  a `recording_schedules` row with `padding_pre = prefs.recordPaddingPreSec`,
  `padding_post = prefs.recordPaddingPostSec` (defaults 0, 60).

### RecordingsScreen

- Sidebar destination ("Recordings") between Favorites and Search
  (the existing `AppSection` enum has space).
- Three tabs (TV-style segmented chips): **Scheduled** /
  **In progress** / **Past**.
- Each row: thumbnail (logo from content row if available, else
  source logo), title, recorded date, file size, status badge,
  action menu (Play / Delete / Cancel).
- Long-press a Past row → "Add to favorites", "Show in files".

---

## 4. Tests we'll write

- **HlsRecorder** unit test against a fake Ktor server serving a
  static manifest + 5 TS segments → assert sink contains the
  concatenated bytes, status flips to completed.
- **HlsRecorder** retry test: server 5xx on segment 3 first time,
  succeeds on retry → recording completes.
- **HlsRecorder** heartbeat test: server hangs after segment 1 →
  recorder fails with `heartbeat_timeout` after 60 s (test uses a
  shortened timeout via Strategy override).
- **MpegTsRecorder** unit test with a 1 MB body → bytes round-trip,
  status completes.
- **RecordingsRepository** transitions: started → completed with
  size + duration; started → failed with reason; orphan recovery.
- **Storage cap eviction logic** — pure Kotlin function in
  `StorageCapPlanner` test, no Android deps.
- **Concurrent-cap rejection** — 4th `start()` call when 3 are
  in flight returns the right error.

Android instrumentation tests can wait — the engine logic is
testable in isolation. The wiring layer (Service, MediaStore,
WorkManager) gets a smoke test on real Fire TV.

---

## 5. Out of scope for v1.0 (re-confirmed)

- **DASH recording** — segment + init.mp4 munging is meaningfully
  more complex; phase 2.
- **Encrypted segments (CENC, AES-128 SAMPLE-AES)** — phase 2.
- **Cloud archive** — TiviMate Premium feature; we explicitly don't
  ship it.
- **NAS / SMB push** — flagged as MK.14.8 follow-up if time allows
  after the rest of v1.0; doesn't block v1.0 ship.
- **Auto series recording via XMLTV `episode-num`** — replaced with
  manual series binding (MK.14.6).

---

## 6. Sequencing within Stage 3.1

Order of commits (planned; deviations get a note in the commit body):

1. **MK.14.1a** — `Recorder` interface + `HlsRecorder` impl + tests
   (`:shared`).
2. **MK.14.1b** — `MpegTsRecorder` impl + tests (`:shared`). Ships
   together because Xtream catch-up is mostly TS.
3. **MK.14.1c** — `RecordingsRepository` (`:shared`) wraps SQLDelight
   queries, owns state-transition validation.
4. **MK.14.1d** — `StorageCapPlanner` (`:shared`) — pure eviction
   logic, no Android deps. Tests against a fake fs view.
5. **MK.14.1e** — Android `RecordingService` + `MediaStore` writer
   + notification.
6. **MK.14.2** — Player options sheet "Record now" row + ongoing
   notification action wiring.
7. **MK.14.3** — `WorkManager` scheduling + arm loop.
8. **MK.14.4** — EPG long-press → schedule row.
9. **MK.14.5** — `RecordingsScreen` + sidebar destination.
10. **MK.14.6** — Series-binding bulk-schedule action.
11. **MK.14.7** — MPEG-TS UI surfaces (record button enabled for
    `.ts` URLs; disabled-with-tooltip for DASH/encrypted).
12. **Storage management** — Settings → Recordings tab, cap slider,
    eviction trigger.

Each is its own commit per the MK.8 rule — one sub-task per commit,
not three.
