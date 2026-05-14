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
3. Export the new value as an environment variable rather than writing it back to `local.properties`:
   - Windows (PowerShell): `setx SENTRY_AUTH_TOKEN "..."` then restart the shell + Android Studio.
   - macOS / Linux: add `export SENTRY_AUTH_TOKEN="..."` to `~/.zshrc` or `~/.bashrc`, then `source` the file.
4. Optionally do the same for the DSN — `export YANCOTV_SENTRY_DSN="..."`. Phase 5.1 of the launch-audit cleanup (commit `6075b10`) wires both env vars in `packages/android/app/build.gradle.kts` ahead of the `local.properties` fallback, so the file no longer needs to hold either Sentry value.
5. Delete the `sentry.auth.token` and `sentry.dsn` lines from `packages/android/local.properties`. The file may retain the release-keystore credentials and the `update.endpoint` line — those aren't currently flagged by any scanner.

After step 4 the new credential lives only in environment variables. After step 5 the on-disk file no longer contains Sentry secrets, so the TruffleHog / Gitleaks findings on `packages/android/local.properties` will not fire on the next rescan. The keystore credentials in the file are deliberately retained — `keytool` and the Sentry Gradle plugin need them at file paths, and they're not flagged by any of the scanner rule sets the audit runs.

**Old fingerprints to ignore on the next rescan:**

- `c799259083a63c0fb1ef5a620ea37a0cfa67cb9a9eb3ba2413e6d27e28c938bb` (TruffleHog, "Unverified" — old rule run)
- `f227a2e17cb719ca5528519862e3aa1f44295ab35f8b9da3edcf177535cc070b` (TruffleHog, "Verified live" — current rule run, this is the one that needs rotation)
- `12e52cabae584d41a181e826890728f5300806c7f5a72e524398b0a885a9edbd` (Gitleaks sentry-user-token)
- `d5620dc0d660b51df1dfad52747bd30dcdc179af5fd0f6e4851d932f65fe3524` (Gitleaks generic-api-key — same line, matched by a different rule)

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
- `MK.SEC.B` (commit `4066c1d`) — `CleartextAllowlistInterceptor` OkHttp interceptor; refuses HTTP to non-allowlisted hosts with a synthetic 469 response and credential-redacted body. Wired into the shared `OkHttpClient` (Coil, EPG importer) and the Ktor engine's internal OkHttp (Stalker, Xtream, M3U downloads). Lazy provider breaks the SourceRepository circular DI; `runCatching { ... }.getOrElse { PermitAllCleartextAllowlist }` keeps very-early-startup unbricked. 9 interceptor tests.
- `MK.SEC.C` (commit `e2077de`) — same interceptor extended to `PlaybackController`'s per-controller OkHttp instance, so ExoPlayer's HTTP traffic (Media3's `OkHttpDataSource.Factory`) is covered. No additional Media3 wrapper needed.

**What this means for the scanner:**

yancoxplorer's `mobile.readiness.cleartext` check reads the manifest attribute directly; it has no visibility into the OkHttp interceptor layer. The finding will fire on every rescan. The fingerprint above is the durable identifier — a rescan that produces the same fingerprint is the same accepted decision, not a new issue.

**What would change the decision:**

- Android shipping a runtime allowlist API on `NetworkSecurityConfig` (no such API on the roadmap as of Android 16).
- A reasonable maintainer-burden way to know every IPTV provider host at build time (impractical given the user-supplied source model).
- An architectural pivot to proxy every stream through `localhost:N` (significant engineering; defers the issue rather than solving it).

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

---

## How to use this file in a rescan

1. Run the audit (`yancoxplorer audit .`).
2. Open the resulting `yancoxplorer-audit-*.json`.
3. For each finding, look up its `fingerprintSha256` here.
4. **If present in this file** — accepted, no action.
5. **If absent** — genuine new finding. Triage: fix, suppress with rationale, or add a new accepted-risk entry below with a written decision.

Do **not** add an entry here without a written decision and a reference to the commit(s) that produced the application-layer mitigation (if any). The point of this file is to make the chain of reasoning durable across rescans and reviewers.
