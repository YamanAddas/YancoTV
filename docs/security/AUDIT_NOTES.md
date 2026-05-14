# YancoTV Security Audit Notes

Durable record of audit findings that are **deliberately accepted** rather than fixed. Every entry here is paired with a fingerprint so a future audit run can be diffed against this register: anything the scanner reports that isn't in this file is a genuine new finding.

The audit tooling itself lives outside this repo. Findings come from:

- **yancoxplorer** (`yancoxplorer-audit-*.json` — the umbrella tool that orchestrates the rest)
- **Semgrep CE** (SAST)
- **Gitleaks** + **TruffleHog** (secret detection)
- **Grype**, **Trivy**, **OSV-Scanner** (dependency CVEs)

Closed-by-fix findings move to the per-phase commit messages on the relevant launch-audit branch and are not duplicated here. This file only records the **accepted-risk** entries.

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

---

## How to use this file in a rescan

1. Run the audit (`yancoxplorer audit .`).
2. Open the resulting `yancoxplorer-audit-*.json`.
3. For each finding, look up its `fingerprintSha256` here.
4. **If present in this file** — accepted, no action.
5. **If absent** — genuine new finding. Triage: fix, suppress with rationale, or add a new accepted-risk entry below with a written decision.

Do **not** add an entry here without a written decision and a reference to the commit(s) that produced the application-layer mitigation (if any). The point of this file is to make the chain of reasoning durable across rescans and reviewers.
