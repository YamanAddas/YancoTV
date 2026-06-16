# YancoTV Security Audit Notes

Durable record of audit findings that are **deliberately accepted** rather than fixed. Every entry here is paired with a fingerprint so a future audit run can be diffed against this register: anything the scanner reports that isn't in this file is a genuine new finding.

The audit tooling itself lives outside this repo. Findings come from:

- **yancoxplorer** (`yancoxplorer-audit-*.json` — the umbrella tool that orchestrates the rest)
- **Semgrep CE** (SAST)
- **Gitleaks** + **TruffleHog** (secret detection)
- **Grype**, **Trivy**, **OSV-Scanner** (dependency CVEs)

Closed-by-fix findings move to the per-phase commit messages on the relevant launch-audit branch and are not duplicated here. This file only records the **accepted-risk** entries.

---

## Action register (rotation required, not a code fix)

### Live Sentry auth token in `packages/android/local.properties:10`

The 2026-05-14 050016 rescan upgraded this finding to **Critical** because TruffleHog's verifier confirmed the token is currently live (accepted by `api.sentry.io`). The token has never been in git — `packages/android/.gitignore:3` excludes `local.properties` and `git ls-files` confirms it's never been tracked — but TruffleHog walks the working directory regardless and the file sits on disk where any process with read access can extract a working credential.

**Required action** (cannot be done in code — must be performed by the user against Sentry's UI):

1. Open Sentry → Settings → Auth Tokens → revoke the current token.
2. Generate a new token with the minimum needed scopes (the build pipeline only needs `project:releases` for R8 mapping upload).
3. **Run [`scripts/setup-sentry-env.ps1`](../../scripts/setup-sentry-env.ps1) from the repo root.** It prompts for the new token + DSN (input hidden), writes both as user-scope environment variables (`SENTRY_AUTH_TOKEN`, `YANCOTV_SENTRY_DSN`) via the .NET API (so tokens with `%` / `&` / `"` chars don't need escaping), backs up `packages/android/local.properties` with a timestamped suffix, and removes only the `sentry.auth.token` + `sentry.dsn` lines from it. Keystore credentials and the update endpoint stay in the file.
4. Close + re-open the shell and Android Studio so the new env vars are picked up (Windows `setx` / .NET `SetEnvironmentVariable` only affect processes started AFTER the write).
5. `cd packages\android` then `.\gradlew --stop` so the Gradle daemon picks up the new environment on its next build.

After step 3 the new credential lives only in environment variables. The on-disk `local.properties` no longer contains Sentry secrets, so the TruffleHog / Gitleaks findings on `packages/android/local.properties` will not fire on the next rescan. The keystore credentials in the file are deliberately retained — `keytool` and the Sentry Gradle plugin need them at file paths, and they're not flagged by any of the scanner rule sets the audit runs.

If anything goes wrong, the timestamped backup the script creates (`local.properties.bak-YYYYMMDD-HHMMSS`) restores the original — `Move-Item` it back over `local.properties` to roll back.

After step 4 the new credential lives only in environment variables. After step 5 the on-disk file no longer contains Sentry secrets, so the TruffleHog / Gitleaks findings on `packages/android/local.properties` will not fire on the next rescan. The keystore credentials in the file are deliberately retained — `keytool` and the Sentry Gradle plugin need them at file paths, and they're not flagged by any of the scanner rule sets the audit runs.

**Old fingerprints to ignore on the next rescan** (broken with a `…` mid-hex so TruffleHog's 64-char Sentry-token regex stops matching the fingerprints themselves — concatenate to reconstruct for grep):

- `c799259083a63c0fbef5a620ea37a0c` `…` `fa67cb9a9eb3ba2413e6d27e28c938bb` (TruffleHog, "Unverified" — old rule run, the leading character has been dropped intentionally so reconstruction requires this comment)
- `f227a2e17cb719ca5528519862e3aa1` `…` `f44295ab35f8b9da3edcf177535cc070b` (TruffleHog, "Verified live" — the run that prompted the rotation)
- `12e52cabae584d41a181e826890728f` `…` `5300806c7f5a72e524398b0a885a9edbd` (Gitleaks sentry-user-token)
- `d5620dc0d660b51df1dfad52747bd30` `…` `dcdc179af5fd0f6e4851d932f65fe3524` (Gitleaks generic-api-key — same line as the sentry-user-token, matched by a different rule)

If a rescan produces a NEW fingerprint after rotation + env-var migration, the credential is still on disk somewhere — investigate before treating it as a known issue.

---

## Accepted findings

### `mobile.readiness.cleartext.47a0a49d` — Cleartext traffic enabled globally

| Field | Value |
|---|---|
| Severity | High |
| Source | yancoxplorer / Mobile Launch Readiness |
| File | `packages/android/app/src/main/AndroidManifest.xml` |
| Fingerprint | `e68336285b1222be32a2a233e2680d000402948926ca5e345c1669c51496a5fa` |
| Decision | **Accepted — won't fix** |
| Decided | 2026-05-14 |
| References | [AGENTS.md "Cleartext traffic (Android)"](../../AGENTS.md), [bugs.md MB-203](../../bugs.md), commits `bd37159` / `4066c1d` / `e2077de` (MK.SEC.A/B/C) |

**Why accepted:**

- IPTV providers commonly serve plain HTTP (M3U playlists, Xtream `player_api.php`, MPEG-TS streams, EPG XMLTV dumps).
- The provider host-set is user-configured at runtime; Android's `network_security_config.xml` is read once at `Application.onCreate` and offers no runtime mutation API. There is no OS-layer mechanism to allowlist a host the user just added in Settings.
- Flipping `usesCleartextTraffic="false"` would block every legitimate HTTP provider at the OS layer before our application code sees the request.

**What we did instead:**

- `MK.SEC.A` (commit `bd37159`) — `CleartextAllowlist` interface + `cleartextAllowlistFromSources` derivation in `packages/shared/commonMain/`. Pure logic, 22 tests.
- `MK.SEC.B` (commit `4066c1d`) — `CleartextAllowlistInterceptor` OkHttp interceptor; refuses HTTP to non-allowlisted hosts with a synthetic 469 response and credential-redacted body. Wired into the shared `OkHttpClient` (EPG importer, update download, subtitle search) and the Ktor engine's internal OkHttp (Stalker, Xtream, M3U downloads). Lazy provider breaks the SourceRepository circular DI; `runCatching { ... }.getOrElse { PermitAllCleartextAllowlist }` keeps very-early-startup unbricked. 9 interceptor tests.
  - **Image carve-out (2026-05-20):** Coil's image loader (`YancoApp.onCreate`) is built from the shared client with this interceptor stripped, so plain-http channel logos (`tvg-logo` on CDNs outside the user's source hosts) load instead of being blanked by the 469. Channel/poster images carry no credentials, so this is an accepted **cosmetic-only** cleartext exposure (an on-path attacker could swap a logo image, nothing more). Every credential-bearing path (provider API, streams, EPG, update download, subtitle search) keeps the gate. Code: `imageHttpClient` in `YancoApp.kt`.
- `MK.SEC.C` (commit `e2077de`) — same interceptor extended to `PlaybackController`'s per-controller OkHttp instance, so ExoPlayer's HTTP traffic (Media3's `OkHttpDataSource.Factory`) is covered. No additional Media3 wrapper needed.

**What this means for the scanner:**

yancoxplorer's `mobile.readiness.cleartext` check reads the manifest attribute directly; it has no visibility into the OkHttp interceptor layer. The finding will fire on every rescan. The fingerprint above is the durable identifier — a rescan that produces the same fingerprint is the same accepted decision, not a new issue.

**What would change the decision:**

- Android shipping a runtime allowlist API on `NetworkSecurityConfig` (no such API on the roadmap as of Android 16).
- A reasonable maintainer-burden way to know every IPTV provider host at build time (impractical given the user-supplied source model).
- An architectural pivot to proxy every stream through `localhost:N` (significant engineering; defers the issue rather than solving it).

### CodeQL findings introduced by the 2026-05-14 172439 audit run

CodeQL was not in the previous (050016) audit's scanner set. Its addition surfaced 80+ findings, most of which are noise (auto-generated `playwright-report/index.html` minified JS that lights up CodeQL with semicolon-insertion, superfluous-trailing-args, etc.). After deleting that directory and the script-backup file, the remaining real CodeQL findings on YancoTV source are documented below.

#### Polynomial regex — accepted

| Site | Pattern | Why accepted |
|---|---|---|
| `packages/core/src/content/title-cleaner.ts:42` | iterates `STRIP_PATTERNS` over playlist titles | Patterns strip "(2024)", "[1080p]", etc. from M3U entries. Input is bounded (M3U title field is typically <200 chars) and the patterns are well-formed enough that JavaScript's NFA backtracking is bounded in practice. Worst case is a parsing thread stall on a crafted playlist, not a security incident. |
| `packages/core/src/content/title-cleaner.ts:101` | `/\s*Season\s+\d+.*/i` | Same risk profile. Replacing with split-based parsing is real engineering effort — out of audit-cleanup scope. |
| `packages/core/src/content/title-cleaner.ts:104` | `/\s*S\d{1,2}\s*E\d{1,3}.*/i` | Same. |
| `packages/core/src/stalker/client.ts:88` | `portalUrl.replace(/\/+$/, '')` | **False positive.** `\/+$` is linear-time; there's no ambiguity to backtrack over. CodeQL's heuristic flagged it anyway. |
| `packages/core/src/xtream/client.ts:175` | `url.replace(/\/+$/, '').replace(/\/player_api\.php$/, '')` | **False positive.** Both regexes are linear. |

#### File-system race conditions — accepted

| Site | Pattern | Why accepted |
|---|---|---|
| `src/main/services/asset-fetcher.ts:358` | `if (!fs.existsSync(tvshowPath)) { fs.writeFileSync(tvshowPath, ...) }` | TOCTOU on Kodi's `tvshow.nfo`. Worst case: a concurrent write loses to ours (we overwrite). No security impact — both writes are our own application's. |
| `src/main/services/source-sync.ts:100` | `fs.stat(path)` then `fs.readFile(path)` | TOCTOU on the user's local M3U file. If the file is deleted between stat and read, the error surfaces normally through the sync's error path. |

#### `src/main/services/opensubtitles-client.ts:240` — `Network data written to file` (false positive)

CodeQL flags the legitimate subtitle download → cache write path. The bytes are HTTP-fetched from `api.opensubtitles.com` (HTTPS) and written to the app's `userData/subtitles-cache/` directory via `confinePath` (Phase 2.3). The flow is exactly what the SDK is supposed to do.

#### `src/main/services/update-service.ts:60` — `Useless conditional` (intentional gate)

`if (!UPDATE_MANIFEST_URL)` always returns true today because `UPDATE_MANIFEST_URL` is an empty string in `src/shared/constants.ts`. The constant ships empty until Stage 5.2 wires the real manifest URL — at which point the conditional flips to discriminating and the warning self-resolves. Documented in `constants.ts`'s comment block.

#### `tests/unit/source-sync.test.ts:188` — `Unused variable` (test cleanup)

Trivial — an unused fixture import or variable. Code-quality only.

#### `docs/design/design_handoff_yancotv/designs/tweaks-panel.jsx:187` — `Missing origin verification in postMessage handler`

Already accepted as part of the `docs/design/` mockup acceptance — this is the same family as the `wildcard-postmessage-configuration` Semgrep finding. `.semgrepignore` covers `docs/` for Semgrep but not for CodeQL.

### `aismell.universal.localhost-in-source` — `DEV_RENDERER_URL` constant

| Field | Value |
|---|---|
| Severity | Medium |
| Source | yancoxplorer / AI Smell |
| File | `src/shared/constants.ts` (post-Phase-2.4 consolidation) |
| Decision | **Accepted — residual after consolidation** |
| Decided | 2026-05-14 |
| References | commit `a067abd` (Phase 2.4 consolidation: three call-site literals → one named constant) |

**Why accepted:**

Phase 2.4 consolidated three "Hardcoded localhost URL in source" matches (in `src/main/index.ts` × 2 and `src/main/player/overlay-window.ts` × 1) into a single named constant `DEV_RENDERER_URL = 'http://localhost:5173'` in `src/shared/constants.ts`. The yancoxplorer rule is a substring match on the literal "localhost"; the named constant still contains the literal so one match remains. Replacing with `127.0.0.1` would match the rule's IP-style sibling pattern; using a build-time-substituted value would require a Vite/esbuild define plus a runtime fallback that's far more complex than the smell warrants.

The constant is intentionally a string literal — it's read by the Electron main process to load the Vite dev server, and the value is well-known per Vite's defaults. There's no production exposure: production builds use `mainWindow.loadFile(...)` and never touch this URL.

### MK.26 — LAN companion-handoff receiver (new network surface)

| Field | Value |
|---|---|
| Severity | Medium |
| Source | Self-documented (proactive — ahead of next yancoxplorer rescan) |
| File | `packages/android/app/src/main/java/com/yancotv/android/handoff/HandoffReceiverService.kt`, `HandoffServer.kt` |
| Decision | **Accepted — mitigated by pairing-code auth** |
| Decided | 2026-06-15 |
| References | [project_cast_mk26](../../PRODUCTION_PLAN_NATIVE.md) (MK.26 Track A), `AppPreferences.getOrGenerateHandoffPairingCode` |

**What it is:**

MK.26 Track A adds an embedded Ktor (CIO) HTTP server on a fixed LAN port inside `HandoffReceiverService`, advertised over NSD so a phone can discover the TV and POST a `HandoffPlayCommand` (stream URL + provider `User-Agent` / `Referer` + a pairing token). The TV plays the handed-off stream on its single shared player. This is a new inbound network surface that did not exist before MK.26.

**Why accepted:**

- **Auth gate.** Every command is validated against the TV's own pairing code — a 6-char code from a 32-symbol alphabet (`SecureRandom`-generated as of the 2026-06-15 polish; ~30 bits) persisted per device. A missing code rejects with `UNAUTHORIZED` rather than falling through to an unauthenticated path (`HandoffReceiverService.handlePlay`). The phone must echo the exact code, set out-of-band by the user.
- **Bounded blast radius.** The command flow is inbound-only: it tells the TV *what to play*. It carries no path that returns the TV's stored provider credentials to the caller, so a successful command cannot exfiltrate secrets — worst case an on-LAN attacker who has the pairing code makes the TV play a URL of their choosing.
- **Same trust boundary as the existing cleartext decision.** Traffic is plain HTTP on the local network, consistent with — and no worse than — the already-accepted [`mobile.readiness.cleartext`](#mobilereadinesscleartext47a0a49d--cleartext-traffic-enabled-globally) entry. The handoff client uses a dedicated Ktor client deliberately exempt from the cleartext allow-list (it talks to user-paired TVs, not provider hosts).

**Residual risk / what would change the decision:**

- A ~30-bit code is resistant to casual guessing but not to a determined on-LAN brute-force. If the threat model ever extends to hostile actors already on the user's LAN, raise the code entropy and/or add rate-limiting + lockout on repeated `UNAUTHORIZED` rejects on the receiver.
- The on-device `CastProxy` (Track B) serves remuxed HLS on the LAN for the duration of a cast; it injects provider headers server-side and is reachable by any LAN host while active. It carries the media bytes, not the provider credentials. Tracked as a Phase-2 hardening item if the cast feature graduates from secondary status.

---

## How to use this file in a rescan

1. Run the audit (`yancoxplorer audit .`).
2. Open the resulting `yancoxplorer-audit-*.json`.
3. For each finding, look up its `fingerprintSha256` here.
4. **If present in this file** — accepted, no action.
5. **If absent** — genuine new finding. Triage: fix, suppress with rationale, or add a new accepted-risk entry below with a written decision.

Do **not** add an entry here without a written decision and a reference to the commit(s) that produced the application-layer mitigation (if any). The point of this file is to make the chain of reasoning durable across rescans and reviewers.
