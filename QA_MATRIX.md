# YancoTV — Manual QA Matrix

> Stage 5.8. Lightweight checklist for hands-on testing across the
> device matrix. This is a living document — fill it in as you test,
> mark issues as MB-* in [bugs.md](bugs.md). The friend-group beta
> is the QA pool; this matrix just gives them (and you) a structured
> way to surface what's working + what isn't.

## Device matrix

Addresses are DHCP and have moved more than once. **Identify a device by `model:` in
`adb devices -l`, not by the number in this table** — a stale IP has already caused a session to
install onto the wrong box. Values below were re-verified 2026-09-04.

| Tier | Device | Status | Notes |
|---|---|---|---|
| Primary | Fire TV AFTDCT31 (`model:AFTDCT31`, .66) | Daily-driver during dev | Android 9 / API 28. All Stage 1–5 features verified hands-on. Was `.56`, then `.74` |
| Primary | Chromecast with Google TV (`model:Chromecast`, .70) | Regular second TV target | Android 14 / API 34. Pairs over **wireless debugging** — shows up as an `adb-…_tcp` serial, not an IP. **Must be given a release build**; a debug APK is a signature mismatch, and uninstalling to force it is data loss |
| Primary | Phone HT74J0206349 | Lighter testing | Phone-specific MK rules apply. Carries a **debug** build, so it seeds a free sample playlist full of dead links — Live TV failing there is usually the data, not the app |
| Tier 2 | Unknown host at 192.168.68.52 | Answers adb on 5555, never authorised | Listed here since 2026-04 as "Google TV (living room)". It is **not** the Chromecast above, which reports its own address as .70. What it actually is has never been confirmed |
| Tier 3 | Fire TV Stick 4K | Not on hand | Friend with one to test |
| Tier 3 | Mid-range Android TV box (any) | Not on hand | Friend with one to test |
| Tier 3 | Google TV emulator | Optional | Run when something specific to AOSP-TV needs verification |

## Core feature smoke tests

Run these on every supported device once before declaring v1 ready
for that device. Mark with date + status. Re-run after any major
release.

### 1. First install + launch

- [ ] Install APK via sideload (file manager / `adb install` / Drive).
- [ ] Allow "install unknown apps" if prompted.
- [ ] App launches; lands on Home / Live TV (per startup pref).
- [ ] No crash, no "App not installed" signature mismatch.

### 2. Add an IPTV source

- [ ] Settings → Sources → Add source.
- [ ] M3U URL path: paste an M3U URL, save. Channels populate within seconds.
- [ ] Xtream path: enter host, username, password, save. Channel + EPG sync runs.
- [ ] Source row shows last-synced time + channel count.
- [ ] Sources persist across app restart.

### 3. Live TV playback

- [ ] Sidebar → Live TV. Coverflow shows channel logos.
- [ ] Press CENTER on a channel. Mini-player starts within 2 s.
- [ ] Press CENTER again to fullscreen. Player keeps playing — no
      black frame, no audio gap.
- [ ] Back exits fullscreen → mini-player keeps playing.
- [ ] D-pad UP / DOWN inside player zaps to next / prev channel
      within ~2 s.

### 4. Guide / EPG

- [ ] Sidebar → Guide. Channel list appears; programmes populate.
- [ ] Vertical scroll feels smooth — no obvious jank.
- [ ] Horizontal scroll (timeline) advances 30 min per press.
- [ ] Long-press a programme → action sheet (Watch / Schedule /
      Reminder / Catch-up).
- [ ] Tap "Schedule recording" — first time shows the disclaimer
      dialog. Tap "I understand" → schedule confirmed. Re-doing
      this in another session should NOT show the dialog again.

### 5. Recording

- [ ] Player options → Record this channel. Record starts; toast
      confirms.
- [ ] Settings → Recordings shows the in-flight recording with
      live byte count.
- [ ] Stop recording → row flips to Completed with file size +
      duration.
- [ ] Tap a completed row → playback starts from disk.
- [ ] Schedule a recording 2 min in the future → it fires + records
      the window.
- [ ] Cancel a scheduled recording before it fires → state flips
      to CANCELLED.

### 6. Movies / Series

- [ ] Sidebar → Movies. Coverflow loads.
- [ ] Tap a movie → Detail screen with poster + synopsis + Play
      action.
- [ ] Play → fullscreen player starts within ~2 s.
- [ ] Back exits to detail; coverflow position preserved.
- [ ] Same flow for Series → Episode list → Play episode.

### 7. Favourites

- [ ] Long-press a channel → Favourite. Star appears on the tile.
- [ ] Sidebar → Favourites — channel appears immediately (reactive
      flow, no manual reload).
- [ ] Long-press → Unfavourite — disappears from Favourites,
      star removed everywhere.

### 8. Search

- [ ] Sidebar → Search OR remote SEARCH key (Fire TV) OR
      Ctrl-K (phone keyboard).
- [ ] Type a query → results render in <100 ms.
- [ ] Tap a result → behaves like the source surface (channel
      plays, movie opens detail, etc.).

### 9. Settings tabs

For each tab (General, Appearance, Playback, Subtitles, Network,
Recordings, EPG, Backup, Parental, Groups, Sources, Shortcuts,
About):

- [ ] Tab opens without delay.
- [ ] D-pad UP / DOWN walks every focusable row.
- [ ] LEFT from a row exits to the inner sidebar (active tab keeps
      focus).
- [ ] Each toggle / chip / select responds correctly.

### 10. Theme + Appearance

- [ ] Settings → Appearance → switch theme. Whole app re-paints
      with new palette.
- [ ] Switch accent → focus rings / buttons take new colour.
- [ ] Restart the app → theme + accent persist.

### 11. Backup / restore

- [ ] Settings → Backup → Export backup. SAF picker → save to
      Downloads.
- [ ] Uninstall the app.
- [ ] Reinstall + open → Settings → Backup → Restore. Pick the
      file. Sources, favourites, history, recording schedules
      should be back.

### 12. Auto-update flow

- [ ] (Tester install) Open Settings → About → Updates section.
- [ ] If update endpoint configured + a newer version published:
      banner appears within 24 h (or immediately on "Check now").
- [ ] Tap Download → progress bar fills → APK downloaded.
- [ ] Tap Install → Android install dialog → confirm → app
      relaunches at new versionCode.
- [ ] User data (sources, favourites, history) preserved across
      the update.

### 13. Privacy + crash reports toggle

- [ ] Settings → About → Privacy → Send crash reports = ON →
      observed crashes reach Sentry dashboard.
- [ ] Toggle OFF → trigger a test crash (debug build only) →
      no event lands in Sentry.

### 14. Stability soak

- [ ] Leave a channel playing for 30 + min (Fire TV). No
      buffer-stall, no decoder crash, no audio drift.
- [ ] Background the app for 5 min, return — picks up where
      you left off (resume-point preserved).
- [ ] Reboot the device. Open the app — channel list / sources
      intact, scheduled recordings re-armed.

## Known limitations / acceptances

- **EPG p95 frame time still ~57 ms vs 16.67 ms budget.** Feels
  smooth in real use but the synthetic benchmark says otherwise.
  Documented in [PERFORMANCE.md](packages/android/PERFORMANCE.md);
  not a regression, two follow-up perf items still tracked.
- **M3U parser materialises the full playlist** before parsing.
  ~20 MB transient memory for a 50 K-channel playlist. Acceptable
  for v1. Streaming parser is MB-200, deferred.
- **Cleartext traffic** allowed for IPTV provider hosts. Documented
  in [AGENTS.md](AGENTS.md). MB-203, accepted.
- **Schedule stays FIRING until next boot's reconcileAfterBoot** if
  the recording service dies mid-session (rare). MB-212 fix accepts
  this trade-off.

## How to file issues

When a tester finds something:

1. Note device model + Android version (Settings → About →
   Diagnostics → Device).
2. Note app version (Settings → About → Diagnostics → Build).
3. Reproduction steps.
4. Expected vs actual.
5. If a crash: was the in-app crash-reports toggle ON? (If yes,
   check the Sentry dashboard with the timestamp; the report is
   already there.)
6. Add the bug to [bugs.md](bugs.md) with the next available `MB-*`
   id.

## Last-updated

2026-04-29.
