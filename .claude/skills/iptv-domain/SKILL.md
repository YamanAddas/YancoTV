---
name: iptv-domain
description: ALWAYS invoke when working with IPTV protocols, playlist parsing, content classification, or title cleaning. Covers M3U/M3U8, Xtream Codes API, Stalker/Ministra Portal, XMLTV EPG format, catch-up URL patterns, subtitle formats, and the title-cleaning / content-classification heuristics that power packages/core/ and packages/shared/. Use when editing parsers, clients, classifier, title-cleaner, catchup URL builder, or debugging misclassified or mislabeled content.
---

# IPTV Domain Reference

Protocol specs, format quirks, and content-handling patterns for YancoTV. Both `packages/core/` (TypeScript, desktop) and `packages/shared/` (Kotlin, native) implement these — keep them in sync.

## M3U / M3U8

- Extended M3U playlist format. `#EXTINF:` lines carry metadata; the following line is the stream URL.
- Key attributes: `group-title`, `tvg-logo`, `tvg-id`, `tvg-name`, `catchup`, `catchup-source`, `catchup-days`.
- Streaming parser: one pass, BOM-tolerant, handles continuation lines. EPG URL advertised via `#EXTM3U url-tvg="..."` on the header.
- **Gotchas:** duplicate `#EXTINF` without URL → drop the entry; URL-less group headers; providers inject ad channels at the top; CRLF vs LF on Windows-written playlists.

## Xtream Codes API

- REST over `/player_api.php` with `username`, `password`, `action` params.
- Actions: `get_live_categories`, `get_live_streams`, `get_vod_categories`, `get_vod_streams`, `get_series_categories`, `get_series`, `get_series_info`.
- Returns JSON. Stream URLs built from the base URL + stream id + container extension.
- **Gotchas:** some servers return `stream_id` as string vs int — normalize. `is_adult` flag present on some VOD feeds. Series episodes embedded in `get_series_info` response; flatten into our episode model.
- Timeshift URL pattern: `/timeshift/<user>/<pass>/<duration>/<start>/<stream_id>.ts`.

## Stalker / Ministra Portal

- MAC-address-based auth. Endpoints under `/portal.php` or `/server/load.php`.
- Returns JSON. Paginated channel fetch. Distinct session token flow (`type=stb&action=handshake`).
- **Gotchas:** User-Agent sniffing — must spoof STB UA (e.g. `Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3`). Some portals require a prior `get_profile` before `get_all_channels`.

## XMLTV EPG

- XML with `<channel>` and `<programme>` elements.
- Start/stop times in local format: `20260418120000 +0000`.
- Often served gzipped (`.xml.gz`) — parser must support both.
- Channel match: `<tvg-id>` from M3U ↔ `<channel id="...">` in XMLTV.
- **Gotchas:** some feeds use `UTC` suffix, some use offset. Escape sequences in `<title>` (HTML entities). 50MB+ files — stream, don't buffer.

## Catch-up URL patterns

Three flavors we support:

1. **Xtream timeshift** — `/timeshift/<user>/<pass>/<duration>/<start>/<stream_id>.ts`
2. **M3U `catchup="append"`** — append query params to stream URL: `?utc=<start>&lutc=<now>`
3. **M3U `catchup-source="..."`** — template with `${start}` / `${utc}` / `${duration}` tokens

Builder lives in `packages/core/src/catchup/` and `packages/shared/src/commonMain/kotlin/.../catchup/`. Tests mirror.

## Content classification

Heuristic pipeline in `packages/core/src/content/classifier.ts` (and Kotlin mirror):

- **Live TV:** duration undefined or `-1`. Group names contain country codes, `News`, `Sports`, `24/7`, `HD`. URL extension `.ts` or `.m3u8`.
- **Movies (VOD):** finite duration. Xtream `get_vod_streams`. M3U heuristic: `group-title` contains `VOD`, `Movie`, `Film`. URL extension `.mp4` / `.mkv` / `.avi`.
- **Series:** Xtream has a dedicated endpoint. M3U heuristic: title matches `S\d+E\d+`, `Season \d+`, `Episode \d+`, `\d+x\d+`.

Classifier runs in two passes: group-level hint → per-item override. Per-item wins.

## Title cleaning

Patterns the cleaner strips (order matters — strip prefixes before quality tags):

- **Country/language prefixes:** `US:`, `UK |`, `AR -`, `EN |`, `DE:`, `FR -`
- **Leading numbering:** `001.`, `123 |`, `[01]`
- **Quality tags:** `[HD]`, `(4K)`, `FHD`, `SD`, `H.265`, `HEVC`, `DOLBY`, `ATMOS`, `10BIT`, `IMAX`
- **Provider tags:** `[MULTI]`, `[BACKUP]`, `(NEW)`, `*NEW*`, `★`, `VIP`, `PREMIUM`
- **Bracket noise:** empty `[]`, `()`, `{}`
- **Trailing dots, duplicate spaces, stray punctuation**

After stripping, extract structured data:
- Year `(2024)`, `[2024]`, `- 2024`
- Season/episode `S01E05`, `1x05`, `Season 1 Episode 5`

**Preserve original title** — cleaner writes to `clean_title`, never overwrites `title`. UI reads `clean_title ?? title`.

## Stream protocols

- **HLS (`.m3u8`):** master playlist → variant → segments. Desktop mpv handles. Android Media3 handles via `HlsMediaSource.Factory`.
- **DASH (`.mpd`):** desktop mpv, Android via `DashMediaSource.Factory`.
- **MPEG-TS (`.ts`):** raw transport stream. Common for live. Desktop mpv native. Android via progressive `DefaultMediaSourceFactory`.
- **MP4 / MKV:** VOD. Both platforms via progressive source.

## Subtitle formats

- **SRT** — most common. mpv + Media3 both handle.
- **WebVTT** — web standard. mpv + Media3.
- **ASS / SSA** — styled. mpv full support; Media3 partial (text only, styles ignored).
- **Auto-download:** OpenSubtitles REST API — search by file hash or title, language filter (EN / AR). Rate-limited; cache in SQLite.

## External APIs used

| API | Purpose | Where |
|---|---|---|
| Xtream Codes | Source ingest | `packages/core/src/xtream/`, `packages/shared/src/.../xtream/` |
| Stalker Portal | Source ingest | `packages/core/src/stalker/`, `packages/shared/src/.../stalker/` |
| XMLTV feeds | EPG data | Parser in core/shared |
| TMDb | Movie/show metadata | desktop Sprint 14 DONE; native MK.7+ |
| OpenSubtitles | Subtitle search/download | desktop Sprint 15 DONE; native MK.7+ |

## Reference

- [packages/core/](../../../packages/core/) — TypeScript reference implementation (desktop)
- [packages/shared/](../../../packages/shared/) — Kotlin mirror (native)
- Test fixtures: `tests/` (desktop) + `packages/shared/src/commonTest/` (native)
