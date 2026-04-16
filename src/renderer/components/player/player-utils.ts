/**
 * Pure utility functions for the video player — no browser-only dependencies.
 * Extracted so they can be unit tested without mocking hls.js/mpegts.js.
 */

// --- Stream type detection ---

/** Chromium natively supports these container formats in <video> */
const NATIVE_EXTENSIONS = new Set(['.mp4', '.webm']);

/** Extensions that Chromium cannot play natively — need HLS or mpegts.js */
const UNSUPPORTED_NATIVE = new Set(['.mkv', '.avi', '.mov']);

export function detectStreamType(url: string): 'hls' | 'mpegts' | 'native' {
  const lower = url.toLowerCase().split('?')[0];

  // HLS manifests — check extension (not substring) to avoid false matches
  if (lower.endsWith('.m3u8')) return 'hls';

  // Extract the file extension
  const lastSlash = lower.lastIndexOf('/');
  const afterSlash = lastSlash >= 0 ? lower.substring(lastSlash) : lower;
  const dotIdx = afterSlash.lastIndexOf('.');
  const ext = dotIdx >= 0 ? afterSlash.substring(dotIdx) : '';

  // Formats Chromium can play natively
  if (NATIVE_EXTENSIONS.has(ext)) return 'native';

  // Formats Chromium CANNOT play — route through mpegts.js which can often
  // demux these containers when served over HTTP from IPTV providers
  if (UNSUPPORTED_NATIVE.has(ext)) return 'mpegts';

  // .ts, .flv, and everything else (including extensionless IPTV URLs) → mpegts
  return 'mpegts';
}

// --- Check whether a URL has a file extension that is NOT natively supported ---

export function hasUnsupportedExtension(url: string): boolean {
  const lower = url.toLowerCase().split('?')[0];
  const lastSlash = lower.lastIndexOf('/');
  const afterSlash = lastSlash >= 0 ? lower.substring(lastSlash) : lower;
  const dotIdx = afterSlash.lastIndexOf('.');
  const ext = dotIdx >= 0 ? afterSlash.substring(dotIdx) : '';
  return UNSUPPORTED_NATIVE.has(ext);
}

// --- VOD URL detection ---

export function isVodUrl(url: string): boolean {
  const lower = url.toLowerCase();
  return lower.includes('/movie/') || lower.includes('/series/');
}

// --- URL extension replacement for format fallback ---

/**
 * Replace the file extension in a stream URL.
 * For Xtream m3u8 requests, also tries the /streamId/streamId.m3u8 pattern
 * that some servers require (Bug 6 fix).
 */
export function replaceStreamExtension(url: string, newExt: string): string {
  const qIdx = url.indexOf('?');
  const base = qIdx >= 0 ? url.substring(0, qIdx) : url;
  const query = qIdx >= 0 ? url.substring(qIdx) : '';
  const dotIdx = base.lastIndexOf('.');
  if (dotIdx < 0) return `${base}.${newExt}${query}`;
  return `${base.substring(0, dotIdx)}.${newExt}${query}`;
}

/**
 * Build an alternative HLS URL for Xtream servers that use
 * /type/user/pass/streamId/streamId.m3u8 instead of /type/user/pass/streamId.m3u8
 */
export function buildXtreamHlsUrl(url: string): string | null {
  // Match: /movie|series/user/pass/12345.ext or /movie|series/user/pass/12345
  const match = url.match(/^(https?:\/\/.+\/(?:movie|series)\/[^/]+\/[^/]+\/)(\d+)(?:\.[^/?]+)?(\?.*)?$/i);
  if (!match) return null;
  const [, basePath, streamId, query] = match;
  return `${basePath}${streamId}/${streamId}.m3u8${query || ''}`;
}

// --- Video error diagnostics ---

const ERR_ABORTED = 1;
const ERR_NETWORK = 2;
const ERR_DECODE = 3;
const ERR_SRC_NOT_SUPPORTED = 4;

export function getVideoErrorMessage(video: HTMLVideoElement): string {
  const err = video.error;
  if (!err) return 'Unknown playback error';

  switch (err.code) {
    case ERR_ABORTED:
      return 'Playback was aborted';
    case ERR_NETWORK:
      return 'Network error — could not load the stream';
    case ERR_DECODE:
      return 'Codec/decode error — this format may not be supported';
    case ERR_SRC_NOT_SUPPORTED:
      return 'Stream unavailable or format not supported';
    default:
      return err.message || `Playback error (code ${err.code})`;
  }
}
