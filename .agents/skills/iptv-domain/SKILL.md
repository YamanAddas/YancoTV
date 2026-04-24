---
name: iptv-domain
description: Use when working with IPTV protocols, playlist parsing, source ingest, content classification, title cleaning, catch-up URL building, EPG parsing, subtitles, or provider-specific bugs. Covers M3U/M3U8, Xtream Codes, Stalker/Ministra, XMLTV, stream URL patterns, and the mirrored packages/core and packages/shared domain logic.
---

# IPTV Domain

Use this skill for protocol and content-model work. YancoTV keeps IPTV logic mirrored in `packages/core` for desktop TypeScript and `packages/shared` for native Kotlin Multiplatform; neither side is allowed to drift.

## Working Rules

- Preserve original provider data. Cleaned titles go to `clean_title`; do not overwrite `title`.
- Keep parser, classifier, title-cleaner, catch-up, and URL-builder tests mirrored between TypeScript and Kotlin.
- Stream large playlists and EPG files. Do not buffer 50MB+ XMLTV or M3U payloads unless the existing parser already does so safely.
- Normalize provider quirks at the edge, then pass typed domain models through the app.
- Redact usernames, passwords, MAC addresses, tokens, and generated stream URLs in logs and errors.
- Treat provider data as hostile: missing fields, wrong numeric types, broken encodings, duplicate rows, and malformed URLs are normal.

## M3U And M3U8

- Extended M3U uses `#EXTINF:` metadata followed by the stream URL line.
- Important attributes: `group-title`, `tvg-logo`, `tvg-id`, `tvg-name`, `catchup`, `catchup-source`, `catchup-days`.
- Header `#EXTM3U url-tvg="..."` can advertise an EPG URL.
- Parser expectations: one pass, BOM tolerant, CRLF/LF tolerant, continuation-line aware.
- Drop duplicate `#EXTINF` entries that never receive a URL.
- Expect URL-less group headers and provider-injected ad channels near the top.

## Xtream Codes

- Base endpoint is `/player_api.php` with `username`, `password`, and optional `action`.
- Main actions: `get_live_categories`, `get_live_streams`, `get_vod_categories`, `get_vod_streams`, `get_series_categories`, `get_series`, `get_series_info`.
- Normalize `stream_id` whether it arrives as a string or number.
- Watch `is_adult` for VOD feeds when present.
- Series episodes are nested inside `get_series_info`; flatten into the app episode model.
- Timeshift URL pattern: `/timeshift/<user>/<pass>/<duration>/<start>/<stream_id>.ts`.

## Stalker And Ministra

- Auth is MAC-address based. Common endpoints are `/portal.php` and `/server/load.php`.
- Session starts with `type=stb&action=handshake`.
- Some portals require `get_profile` before `get_all_channels`.
- Some portals sniff User-Agent; use the project STB-style default when implementing Stalker requests.
- Channel fetch can be paginated; do not assume one response contains all rows.

## XMLTV EPG

- XML contains `<channel>` and `<programme>` entries.
- Programme start/stop values look like `20260418120000 +0000`, but providers may use `UTC` suffixes or offsets.
- Match M3U `tvg-id` to XMLTV `<channel id="...">`.
- Support gzipped `.xml.gz` feeds.
- Decode HTML entities in programme titles and descriptions.

## Catch-Up Patterns

YancoTV supports three families:

1. Xtream timeshift: `/timeshift/<user>/<pass>/<duration>/<start>/<stream_id>.ts`
2. M3U append: append query params such as `?utc=<start>&lutc=<now>`
3. M3U template: replace `catchup-source` tokens such as `${start}`, `${utc}`, and `${duration}`

Keep builders mirrored in `packages/core/src/catchup/` and `packages/shared/src/commonMain/kotlin/.../catchup/`.

## Classification

The classifier is heuristic and two-pass:

- Group-level hint first.
- Per-item override second.
- Per-item evidence wins.

Signals:

- Live TV: duration missing or `-1`, live-like groups, country/language groups, `.ts`, `.m3u8`.
- Movies: finite duration, Xtream VOD endpoints, VOD/movie/film groups, `.mp4`, `.mkv`, `.avi`.
- Series: Xtream series endpoints or title patterns like `S01E05`, `1x05`, `Season 1`, `Episode 5`.

## Title Cleaning

Strip in a stable order:

1. Country/language prefixes: `US:`, `UK |`, `AR -`, `EN |`, `DE:`, `FR -`
2. Leading numbering: `001.`, `123 |`, `[01]`
3. Quality tags: `[HD]`, `(4K)`, `FHD`, `SD`, `H.265`, `HEVC`, `DOLBY`, `ATMOS`, `10BIT`, `IMAX`
4. Provider tags: `[MULTI]`, `[BACKUP]`, `(NEW)`, `*NEW*`, `VIP`, `PREMIUM`
5. Empty brackets, trailing dots, duplicate spaces, and stray punctuation

Then extract structured data such as year, season, and episode.

## Streams And Subtitles

- HLS `.m3u8`: desktop mpv and Android Media3 HLS.
- DASH `.mpd`: desktop mpv and Android Media3 DASH.
- MPEG-TS `.ts`: common for live; Android progressive source.
- MP4/MKV: VOD progressive source.
- SRT and WebVTT are first-class subtitle formats. ASS/SSA are fully supported by mpv and partially by Media3.
