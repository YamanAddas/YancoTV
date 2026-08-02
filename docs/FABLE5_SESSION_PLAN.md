# Fable 5 + ultracode — next session plan

**Written 2026-07-31.** Produced by a 6-auditor survey of the repo followed by a
3-design / 3-judge panel (12 agents, ~1.3M subagent tokens). Every load-bearing
claim below was re-verified against source before being written down — six of the
survey's own findings turned out to be stale and are corrected in §1.

Read §1 before planning anything. It removes roughly 4 hours of work that two of
the three designs had budgeted for.

---

## 1. Corrections — do NOT budget for these

The designers verified rather than trusted, and the survey lost six claims:

| Survey claim | Reality (verified in source) |
|---|---|
| "MK.25 player UX entirely unbuilt, two user-reported pains open" | **MK.25.A shipped** — `f33e4ef` + `a130a03` + `0241cb3`. The tiered VOD/live buffering debounce is live at `PlayerActivity.kt:436-468`, `lastSeekAtMs` has 4 call sites, `SeekFlashOverlay.kt` exists. Both user-reported pains are already fixed. `25.B.3`'s buffered band also already renders (`VodPlayerDock.kt:329`) |
| "lintRelease: 24 errors including MB-294, a real minSdk-24 crash" | **18 errors, and zero are bugs.** MB-294 was fixed in `f007ccc`; the cached report predates it. 10 × `RestrictedApi` (androidx.tvprovider), 4 × media3 `UnsafeOptInUsage`, 4 × `ProduceStateDoesNotAssignValue` that **do** assign inside a nested `collect` lambda. It's annotation work, not bug-fixing |
| "Arabic is missing 57 keys" | **Not a gap.** All 57 are `translatable="false"` in `values/`, and the same 57 are absent from `es` and `fr`. I verified this independently earlier (1024 en vs 967 × 3) |
| "MB-230 heap fix awaiting device soak" | Already **device-verified end-to-end** on AFTMM: peak 133 MB PSS vs 313–318 MB before, two consecutive full syncs, zero fatals. What remains is a *regression check on a different Fire TV model*, not an open investigation |
| "CI/R8 gate open, path-segment redaction open" | Both **closed**. `.github/workflows/android-tests.yml` runs ktlint, migration verify, shared+app tests, `assembleDebug`, R8 `assembleRelease`. Redaction shipped as MB-292 |
| "`.sqm` migrations blocked ⇒ EPG search blocked" | **Disproven.** MK.30.3 shipped `11.sqm`, CI verifies it, the TV renders a real expiry date. EPG programme search is unblocked |

**Genuinely new, and worse than anything the survey found — see §4.**

---

## 2. The decision

Three designs were scored by three judges on independent lenses.

| Design | User value | Feasibility | Ultracode fit | Total |
|---|---|---|---|---|
| **Remote-in-Hand** (player felt-quality + Arabic counts) | **90** | **88** | 56 | **234** |
| MK.34 — DVR trust pass (recording tests + MB-315) | 55 | 79 | 74 | 208 |
| Gates, Harnesses, Detectors (lint gate, harnesses, detectors) | 38 | 66 | **88** | 192 |

**Run Remote-in-Hand.** It wins the two lenses that matter for a daily-driver app
and was the only design whose premises survived source verification.

The ultracode-fit judge preferred the Gates block, and it does contain the most
sophisticated pattern anywhere in the set (two agents build competing MB-315
routes against one harness contract, a judge picks on measured numbers). But the
user-value judge's objection is decisive and the design concedes it in its own
risk section: *"this session ends with five green gates and zero Fire TV
minutes."* Optimising for orchestration elegance over what the user feels is the
wrong trade for a personal app used nightly.

---

## 3. The session — 4 workstreams, ~9h, W4 is cut-first

### Session-wide rule (unanimous graft — all three judges named it)

**Negative-control every fix.** A green test proves nothing unless you've seen it
red. For each fix: revert it in a scratch worktree, confirm a **specifically
named** test goes red, restore, and report that test's name. A `SeekAcceleratorTest`
that stays green with the `repeatCount` guard removed has pinned nothing.

### W1 — Hold-to-seek (~3h) · reframed from feature to bug fix

**Verified defect.** `PlayerActivity.kt:2412-2461` — the VOD and timeshift
LEFT/RIGHT branches call `seekTo(±10s)` per key-down with **no `repeatCount`
filter**. `repeatCount` appears only at lines 2239 and 2266, both for the CENTER
long-press. So holding the D-pad fires one 10-second seek per OS auto-repeat
tick: a 2-second hold scrubs a minute or more in unpredictable jumps, and every
VOD and timeshift session goes through this path.

Replace with an accelerating FF/RW around a pure `SeekAccelerator` kernel.

**Shape:** 3 parallel finders → 1 serial integrator → 1 adversarial verifier in a
fresh context. Finders partition by *dimension*, not file: (a) key-dispatch
collision map — every LEFT/RIGHT consumer (`dispatchKeyEvent` 2217+, `onKeyDown`
2412+, `VodDockProgressRow.onPreviewKeyEvent` ~349, ChannelSurf); (b) the
live/timeshift boundary; (c) key-state teardown on activity pause and surface
handoff. **Single writer on PlayerActivity** — it's 2580 lines, the largest file
in the repo.

**Proof:** `SeekAcceleratorTest` green *and* shown red against the reverted
guard; on device, `adb logcat -s YancoSeek` shows exactly one seek for a tap and
an accelerating series for a hold.

### W2 — Make the dock tell the truth (~1.5h) · highest felt-quality per hour

The dock prints only position and duration (`VodPlayerDock.kt:325-448`). The two
facts you actually want at 3 metres — **how much is left** and **when it ends** —
aren't shown, and series episodes show no `SxxExx` context.

**Shape:** 2 parallel authors + 1 verifier. Author A writes a pure
`DockTimeLabels(playedMs, durationMs, isLive, nowMs, zoneId)` with a table-driven
test (duration ≤ 0, position > duration, live ⇒ no ends-at, DST boundary, 24h
rollover). Author B supplies episode context.

**Proof:** `uiautomator dump` (from PowerShell, per the no-screenshots rule) shows
three distinct label nodes on a movie — `12:34`, `-32:26`, `Ends at 21:47` — and
an `S01E02 · <title>` kicker on an episode.

### W3 — Arabic counts: real `<plurals>` + the 8 mistranslations (~2.5h) · file as MK.31.4 — **SHIPPED** `d7adb85` + `9364267`

> **Outcome (2026-08-01).** 23 plurals × 4 locales, 24 flat strings retired, 13
> call sites converted, two hand-rolled `if (count == 1)` rules collapsed into
> the resource. 5 of the listed mistranslations were confirmed and fixed; the
> `par_restored_count` entry below turned out to be the plurals defect rather
> than a separate wording error, and 9 breadcrumb arrows were normalised to the
> RTL-correct `←`.
>
> **The plan's proof requirement caught three of my own bugs**, which is the
> whole reason it was written that way:
> 1. Folding `sl_no_items` into the plural killed "no items yet" in en/fr/es —
>    CLDR has no `zero` for those languages, so `n == 0` selects `other`.
> 2. French `one` covers **0 and 1**, so eight French `one` items with a
>    hardcoded "1" would render "1 ligne restaurée" for zero. Lint's
>    `ImpliedQuantity` found it; the parity test now asserts it too.
> 3. `PluralResourceParityTest` was **green against a deliberately broken
>    Arabic plural** — it reads `res/` off the filesystem and Gradle had no
>    reason to re-run the task, so it was skipped as UP-TO-DATE. Fixed by
>    declaring `src/main/res` as a test input. Without that the whole class was
>    theatre. **Generalises: any test that reads files Gradle does not know
>    about must declare them, or its negative control is meaningless.**
>
> Correction to the plan text below: two-count strings did **not** need
> splitting. `getQuantityString` takes the selector separately from the format
> args, so `Showing %1$d of %2$d channels` is one plural keyed on the total.
> Only `gs_counts` and `bk_export_ok` needed it — there the counts govern two
> different nouns.

**This closes the Arabic review that was requested and interrupted.** The auditor
compared *every* one of ~985 keys and rates the translation professional-grade
MSA. There is exactly one systematic defect and a short list of specific errors.

**Verified myself:** zero `<plurals>` elements and zero `getQuantityString` /
`pluralStringResource` call sites exist anywhere. 74 translatable strings carry
`%d`; ~25–30 are true counts. Arabic needs **zero / one / two / few / many /
other** — six categories — and singular-for-everything is outright wrong for
2–10, the commonest range in practice.

The worst offenders are strings **I wrote** in MK.31:

| Key | English | Arabic today | Problem |
|---|---|---|---|
| `se_soon_full_many` | `%1$s · %2$d days left` | `بقي %2$d يوم` | singular **يوم** in the branch that exists *specifically* for 2–10 days |
| `pa_buffering` | `BUFFERING` | `جارٍ التخزين` | reads "**saving** in progress" — alarming mid-playback. Needs المؤقت |
| `rec_release_folder` | `Release this folder` | `تحرير هذا المجلد` | تحرير reads "**edit**", not "relinquish" |
| `pb_buffer_profile` | `Buffer profile` | `ملف التخزين المؤقت` | ملف reads "**file**" — users parse it as a file setting |
| `voice_input` | `VOICE` | `الصوت` | identical to the Audio label (`vd_audio`) |
| `par_restored_count` | `Restored %1$d channel(s)` | `%1$d قناة` | singular; needs plurals |

Plus `ca_rename_body` (M3U URL collision) and inconsistent breadcrumb arrow
direction between keys.

**Shape — the best fan-out in the session, and no shared hot file.** 1 classifier
partitions the 74 `%d` strings into TRUE-COUNT vs ORDINAL/IDENTIFIER
(`Season %d`, `Track %d`, `build %d`, `schema v%d` must **not** become plurals),
over-reporting by design and **gating on user sign-off before any edit**. Then 4
locale agents in parallel from one shared English quantity spec, each paired with
an adversarial reviewer that re-derives the required CLDR categories
independently — omitting Arabic's `two` is the classic error.

**Proof:** a `PluralResourceParityTest` that is green *and* proven to fail on a
seeded defect (drop one `ar` category → red), which also emits a rendered table of
every plural × {0,1,2,3,11,100} per locale as a test artifact. That printout is
the actual check; the assertion is just the tripwire.

### W4 — Up-next bumper for series (~2h) · **SHIPPED** `f984bcc` (was CUT-FIRST)

> **Outcome (2026-08-01).** Shipped as MB-343, and the survey changed the shape of
> it. The plan assumed W4 was purely a UI addition; it isn't. The dock's `›` NEXT
> transport button — shipped in MK.16.player.vod.dock precisely to go to the next
> thing — has been **dead for every episode** since, because `play(episode)`
> synthesises a one-item queue and the button gated on `queue.size > 1`. So the
> "no way to jump early" half was a **bug fix**, not a feature, and wiring that
> existing button is strictly better than the new activation surface all three
> candidate designs proposed: already focusable, already RTL-correct, one CENTER
> press away, and it dissolves the "which control owns CENTER" problem every
> design struggled with.
>
> The card itself is deliberately inert — no focus target, no key binding, absent
> from `noOverlay`, advances nothing — and adds **zero coroutines**, riding the
> existing 1 Hz live-offset ticker.
>
> **Process note worth keeping.** A 31-agent adversarial review found 13 defects;
> 9 survived independent refutation and were all fixed before commit. The one
> that mattered most was invisible to me and to all three designers:
> `autoPlayNext` **defaults to off**, so the countdown was promising an advance
> that would never happen. Two of the review's own suggested fixes were wrong for
> this codebase and were rejected on inspection — comparing
> `currentMediaItem?.mediaId` to `currentId` would have silently disabled the
> MB-VOD-LOOP protection, since nothing calls `setMediaId`. Four findings were
> killed by refutation and correctly left alone.
>
> Filed rather than fixed inside the diff: **MB-344** (`autoplayInFlight` latches
> true on the error path — widening a guard inside the commit that adds a second
> claimant to it is how you ship two bugs) and **MB-345** (dock auto-hide not
> reset by focus traversal; both refuters judged it a non-defect).
>
> **Not device-verified** — no test hardware reachable. See §6.

Autoplay already fires from `STATE_ENDED` (`51f1e20`) with no warning and no way
to jump early, so a binge either lurches into the next episode or you sit through
credits. This closes the UI half of plumbing that already shipped.

Listed last and explicitly droppable: it edits **PlayerActivity, the same file as
W1**. Strictly after W1's verifier signs off — never two writers on that file. Cut
it if W1's verifier finds a key-state regression; do not squeeze it.

---

## 4. File before the session starts — a fail-open DVR guard (needs your decision)

The DVR designer found this while reading, and **I verified it**:

`RecordingScheduleReceiver.kt:186-189`
```kotlin
val activeRecordings = runCatching {
    recordings.getByStatus(RecordingStatus.RECORDING)
}.getOrDefault(emptyList())
```

A read failure is **indistinguishable from "no recording in flight."** The
receiver then takes the else branch, calls `playbackController.play()` to switch
the player off the channel currently being recorded, and starts a second
recording. `grep -c getByStatus RecordingService.kt` returns **0** — the service
never re-checks the cap, so **this is the only 1-stream guard in the system.**
Result: a corrupted recording file plus a provider connection rejection, silently.

**Your call, because both options have a real cost:**
- **Fail-closed** (an unreadable recordings table ⇒ assume a recording is active ⇒
  mark MISSED) is safer, but converts a transient read error into a *skipped*
  recording.
- **Fail-open** (today) risks a corrupted recording and a provider rejection.

My recommendation: fail-closed, plus a user-visible notification saying why it was
skipped — a missed recording you're told about beats a corrupt one you aren't.
Filing as **MB-337**; not fixing it blind.

---

## 5. Deliberately excluded, with reasons

- **MB-315 (EPG write-lock)** — the strongest item in the losing DVR design and
  genuinely user-facing (MB-254 converted the ANR into a *late* recording start).
  Excluded only because it needs a two-connection file-backed WAL harness that
  **cannot be written with the current test infrastructure** — `testDb()` uses
  `JdbcSqliteDriver.IN_MEMORY`, which is per-connection, so two connections can't
  share it. Budget ~40 min for that harness in its own session, with a hard
  STOP-and-report rule if it can't be made to work. **This is the top candidate
  for the session after this one.**
- **Fire TV (API 28) device pass** — inherently serial, wall-clock-bound, needs
  your eyes; zero agent parallelism available. Also: every `installDebug` voids an
  in-progress MB-230 soak, so if a soak is wanted it must be the session's
  *closing* act, not an interleaved one.
- **`lintRelease` gate** — deferred now that it's known to be 18 non-bugs. Worth
  doing, but it is annotation hygiene, not defect-finding.
- **`mapping.txt` custody** (~45 min, from the Gates design) — `releasePackage`
  copies only APK/AAB/update.json/SHA256SUMS, so R8 deobfuscation is lost unless
  `SENTRY_AUTH_TOKEN` happened to be set at build time. Uniquely *irreversible*:
  crashes from already-shipped releases can never be symbolicated. Small enough to
  graft in as a warm-up if you want it.
- **Your two items, which no agent can do:** rotate the **live** Sentry auth token
  (TruffleHog-verified against api.sentry.io, logged Critical in
  `docs/security/AUDIT_NOTES.md`), and **back up the release keystore** — lose it
  and no installed user can ever update again.

---

## 6. Scheduling constraints

1. **PlayerActivity is a single-writer resource.** W1 and W4 both touch it; W4
   strictly follows W1's verifier. Never parallel.
2. **Gradle is the real serial bottleneck** (the Gates design was alone in noticing
   this). One daemon, and `assembleRelease` + `lintRelease` + device install
   queue behind each other. Interleave agent *reading* with build waits.
3. **The device is single-tenant.** Fire TV `192.168.68.56` is both the canonical
   target and the only valid MB-230 soak host. Any install voids a running soak.
4. **Per the device-driving protocol:** verify the focused node before every press.
   A blind CENTER destroyed a real source on 2026-07-31.
