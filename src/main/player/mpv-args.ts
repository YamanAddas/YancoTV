/**
 * mpv command-line argument builders.
 *
 * The split between live and VOD matters a lot for playback smoothness:
 *
 *   • LIVE streams need a large back-buffer (timeshift rewind), tolerate
 *     network jitter without stuttering, and should NOT pause-to-rebuffer on
 *     the brief gaps that IPTV multicast/CDN edges produce — they drop frames
 *     instead to stay at the live edge. But `--cache-pause=no` alone causes
 *     visible stutter; `--cache-pause-wait=1` lets mpv briefly hold a frame
 *     when the buffer empties before dropping.
 *
 *   • VOD streams benefit from a big forward-read target, a smaller overall
 *     cache budget (no reason to pin GBs of RAM), and `--cache-pause=yes` —
 *     a clean rebuffer beats any form of visible stutter when the stream is
 *     not real-time.
 *
 * Universal tuning adds auto-reconnect on transient network failures — IPTV
 * servers frequently close a socket mid-stream and expect clients to reopen.
 */

const LIVE_BUFFER_SECONDS_DEFAULT = 30 * 60; // 30 min rewind
const LIVE_BUFFER_BITRATE_BPS = 2 * 1024 * 1024; // 2 MB/s average bitrate estimate
const VOD_FORWARD_CACHE_BYTES = 256 * 1024 * 1024; // 256 MiB forward buffer
const VOD_READAHEAD_SECS = 30; // try to keep 30 s demuxed ahead

// Universal transport hardening for lavf-based streams (http/https/hls).
// Encoding matters: commas inside the option value have to be escaped with
// backslashes because mpv uses commas as the option-list separator.
// Reference: https://mpv.io/manual/stable/#options-stream-lavf-o
const LAVF_RECONNECT_OPTS = [
  'reconnect=1',
  'reconnect_streamed=1',
  'reconnect_on_network_error=1',
  'reconnect_on_http_error=4xx\\,5xx',
  'reconnect_delay_max=5',
].join(',');

function universalStreamingArgs(): string[] {
  return [
    '--cache=yes',
    // 30s before we give up on a stalled request (default: 60s is too long
    // and makes startup on flaky servers feel broken).
    '--network-timeout=30',
    // Auto-reconnect on mid-stream drops — the fix for IPTV "stream dies
    // after 2 minutes" problems that aren't actually client-side bugs.
    `--stream-lavf-o=${LAVF_RECONNECT_OPTS}`,
  ];
}

/**
 * Args for live TV playback, with timeshift rewind buffer.
 */
export function getLivePlaybackArgs(bufferSeconds?: number): string[] {
  const secs = bufferSeconds ?? LIVE_BUFFER_SECONDS_DEFAULT;
  const bufferBytes = secs * LIVE_BUFFER_BITRATE_BPS;
  return [
    ...universalStreamingArgs(),
    `--demuxer-max-bytes=${bufferBytes}`,
    `--demuxer-max-back-bytes=${bufferBytes}`,
    // Don't pause-for-rebuffer on every micro-hiccup — mpv drops frames to
    // stay at the live edge. But do hold briefly (1s) when cache truly
    // empties, which lets IPTV edge hand-offs recover without a visible
    // stutter burst.
    '--cache-pause-wait=1',
    '--cache-pause-initial=yes',
  ];
}

/**
 * Args for VOD (movies/series) playback. Smaller cache, pause-on-underrun.
 */
export function getVodPlaybackArgs(): string[] {
  return [
    ...universalStreamingArgs(),
    `--demuxer-max-bytes=${VOD_FORWARD_CACHE_BYTES}`,
    `--demuxer-readahead-secs=${VOD_READAHEAD_SECS}`,
    // Clean rebuffer on underrun — much smoother-looking than stutter.
    '--cache-pause=yes',
    '--cache-pause-wait=0.5',
  ];
}

/**
 * Pick the right arg set for the stream type. Exported for testing.
 */
export function getPlaybackArgs(opts: { isLive: boolean; liveBufferSeconds?: number }): string[] {
  return opts.isLive ? getLivePlaybackArgs(opts.liveBufferSeconds) : getVodPlaybackArgs();
}

/**
 * Subtitle appearance — applied to both external sub files and embedded tracks.
 * All inputs are raw setting strings (or null/undefined if never set); this
 * function is responsible for normalization and validation so a bad value
 * from the DB doesn't wedge mpv startup.
 *
 * Supported settings:
 *   • subtitle_scale       — number, 0.5..3.0 (mpv `--sub-scale`)
 *   • subtitle_color       — hex like "#FFFFFF" (mpv `--sub-color`)
 *   • subtitle_back_opacity — 0..100 % (mpv `--sub-back-color`, 0 = transparent)
 */
export function getSubtitleAppearanceArgs(opts: {
  scale?: string | null;
  color?: string | null;
  backOpacity?: string | null;
}): string[] {
  const args: string[] = [];

  if (opts.scale != null && opts.scale !== '') {
    const n = Number(opts.scale);
    if (Number.isFinite(n) && n >= 0.5 && n <= 3.0) {
      args.push(`--sub-scale=${n}`);
    }
  }

  if (opts.color && /^#[0-9a-fA-F]{6}$/.test(opts.color)) {
    args.push(`--sub-color=${opts.color}`);
  }

  if (opts.backOpacity != null && opts.backOpacity !== '') {
    const pct = Number(opts.backOpacity);
    if (Number.isFinite(pct) && pct >= 0 && pct <= 100) {
      // mpv's sub-back-color alpha: 0 = opaque, 255 = transparent.
      // We invert so "100% opacity" in the UI → alpha 0 in mpv.
      const alpha = Math.round(255 * (1 - pct / 100));
      const hex = alpha.toString(16).padStart(2, '0').toUpperCase();
      args.push(`--sub-back-color=#${hex}000000`);
    }
  }

  return args;
}
