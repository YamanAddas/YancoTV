# ADR 0002 — Cast-to-TV transport strategy: LAN handoff primary, Google Cast secondary

**Status:** Accepted (2026-06-15)
**Reverses:** the 2026-04-25 "dropped permanently" decision on Chromecast (MK.11.3 / MK.18.3) and the deferral of cross-device handoff (MK.18.5). See [PRODUCTION_PLAN_NATIVE.md](../../PRODUCTION_PLAN_NATIVE.md) → **MK.26** and its decision-log rows.

## Context

The user asked for "stream to TV" parity — cast live TV, movies, and series — "the best, no lag, no mistakes," to **Chromecast/Google TV, Fire TV, and generic smart TVs**. Casting had been dropped on 2026-04-25 over IPTV-feasibility doubts. Two research + adversarial-red-team workflows (2026-06-14/15, 20 agents) re-examined it against current (2026) sources and the actual codebase. The walls:

- **Google Cast cannot carry YancoTV's live catalog without a server we operate.** The Cast Web Receiver is an HTTPS browser page: it silently drops `User-Agent`, can't set `Referer`, blocks cleartext `http://`, requires CORS that IPTV providers don't send, treats AC-3/E-AC-3 as HDMI passthrough-only (silent video), and rejects HEVC-in-TS. Xtream live + timeshift are always raw MPEG-TS. So every nontrivial stream would have to traverse a server-side transcode/remux proxy — and standard HLS is structurally **~10–20 s behind live** regardless. "No lag" over Cast is physically impossible.
- **Fire TV — the canonical device — is not a Google Cast receiver at all.** Amazon Fling reached end-of-support 2026-03-05; Matter Casting (its successor) is app-launch-only. No Cast path reaches Fire TV.
- **The user already installs YancoTV on every TV they control.** That fleet (Fire TV AFTDCT31, Android TVs, phones) already runs the full native player, which plays raw TS / AC-3 / HEVC / `.mkv` / header-gated / cleartext streams natively.

## Decision

Ship cast as **two independent transports** under MK.26:

- **Track A — LAN companion handoff (PRIMARY).** The phone discovers a YancoTV instance on the LAN and commands it to "play content X at position Y" (stream URL + provider headers + resume seconds). The TV's own `ExoPlayer` fetches the original stream and plays it exactly as if opened locally — **zero added lag**, all codecs/containers, because nothing is transcoded and nothing relays through the phone. In-app HTTP/WebSocket receiver service + `NsdManager` mDNS discovery **with first-class manual IP/code pairing** (router multicast suppression is the #1 field failure). LAN-only — no cloud, no GDPR surface. This revives MK.18.5.
- **Track B — Google Cast (SECONDARY, droppable).** Only adds reach to app-less Chromecast/Google-TV devices the user doesn't control. `CastPlayer.Builder().setLocalPlayer(exoPlayer)` (Media3 ≥ 1.9.0; needs the 1.5.1→1.10.x bump + `play-services-cast-framework`), gated behind `GoogleApiAvailability` so it's dark on Fire TV. Movies/series first; live TV needs a registered Custom Web Receiver ($5) + a server-side transcode/remux proxy and is never zero-lag. Build only on proven demand. This partially revives MK.11.3 / MK.18.3, in degraded form.

**DLNA stays dropped** (reconfirmed: raw TS fails DLNA compliance, renderers reject live HLS, Fire TV exposes no renderer). **Generic non-Cast smart TVs (Roku, old Samsung/LG, dumb TVs) are an accepted coverage gap** — no reliable Android-app path for live IPTV; the answer is a ~$35 Google TV/Fire stick running YancoTV, or the TV's native IPTV app.

## Consequences

**One-player rule preserved.** Both tracks honor "one `ExoPlayer`": Track A drives a *remote* device's own single player; Track B wraps the *same* local `ExoPlayer` in a `CastPlayer`. (Track B must widen `PlaybackController.player` from `ExoPlayer` to the `Player` interface — a regression risk against the mini↔theater + FFmpeg-rebuild paths stabilized in desktop-parallel commits 0.3.6–0.3.8.)

**Fire TV gap closed the only way it can be** — by handoff to the installed app, not by a cast protocol Fire TV doesn't speak.

**New threat surface (Track A).** Credentialed Xtream URLs now cross the LAN to a paired peer, outside the on-device cleartext allow-list. Mitigated by a pairing token, LAN-only binding, and `redactCredentials`; to be recorded in [docs/security/AUDIT_NOTES.md](../security/AUDIT_NOTES.md) when the code lands (MK.26.A.5), parallel to the MB-203 cleartext entry.

**Honest scope.** Track A delivers the user's full ask (live + movies + series, zero lag) for every TV they own. Track B is bonus reach for TVs they don't, and its live-TV path carries real, ongoing proxy cost. "Cast everything to any TV with no lag" is not deliverable and was not promised.

## Alternatives considered

- **Google Cast as the primary transport.** Rejected: cannot reach Fire TV at all; never zero-lag for live; requires a server-side proxy for ~100% of nontrivial streams.
- **A transcode-everything proxy + Custom Web Receiver as primary.** Rejected: solves codec/header/CORS/cleartext walls but not the ~10–20 s HLS live-latency floor, costs scale with concurrent viewers, and it *still* can't reach Fire TV.
- **DLNA / UPnP.** Rejected (again): raw TS fails DLNA compliance, most renderers reject live HLS, and Fire TV has no DLNA renderer — it doesn't even close the Fire TV gap.
- **AirPlay / Miracast / screen-mirroring.** Rejected: no production-viable Android sender; mirroring is a laggy last resort, not a feature.
- **Leave casting dropped; rely on "install on every TV."** This remains the honest answer for Fire TV — and Track A is precisely the productized form of it (the install becomes a zero-lag cast target). Casting to *app-less* devices is the only thing that needs Track B.

## References

- [PRODUCTION_PLAN_NATIVE.md](../../PRODUCTION_PLAN_NATIVE.md) — **MK.26** (the milestone) + decision-log rows dated 2026-06-15
- Research/red-team workflows: `yancotv-cast-research` (2026-06-14) and `yancotv-cast-redteam` (2026-06-15)
- [reference: Cast supported media](https://developers.google.com/cast/docs/media), [Media3 CastPlayer](https://developer.android.com/media/media3/cast), Amazon Fling EOL (2026-03-05)
- Seam: `PlaybackController` single `ExoPlayer` — `play(list, startIndex, fromStart)` / `play(episode)`; resume offsets are SECONDS (× 1000 for ExoPlayer)
