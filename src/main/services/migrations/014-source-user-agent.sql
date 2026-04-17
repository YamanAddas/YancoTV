-- Sprint 20.4: Per-source User-Agent override.
-- Some IPTV providers reject requests from generic clients (e.g. libmpv/default
-- ffmpeg UA) and require a specific UA string like "IPTVSmartersPro" or
-- "TiviMate/5.0". Storing it on the source lets mpv's --user-agent be set
-- per-stream at spawn time.

ALTER TABLE sources ADD COLUMN user_agent TEXT;
