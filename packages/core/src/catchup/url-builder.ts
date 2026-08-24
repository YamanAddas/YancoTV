/**
 * Catch-up URL builders. Pure — no DB, no credential storage, no network.
 *
 * Higher-level services (desktop or mobile) look up credentials and metadata,
 * then call these functions to produce the final playback URL.
 */

/**
 * Build Xtream Codes timeshift/catch-up URL.
 *
 * Xtream standard format:
 *   {baseUrl}/timeshift/{username}/{password}/{duration}/{start}/{streamId}.ts
 *
 * We use the path-based format as it's more widely supported.
 */
export function buildXtreamTimeshiftUrl(
  baseUrl: string,
  username: string,
  password: string,
  originalStreamUrl: string,
  programmeStart: number,
  programmeDuration: number,
): string {
  // Extract stream ID from the original URL (e.g., /live/user/pass/12345.ts → 12345)
  const streamIdMatch = originalStreamUrl.match(/\/(\d+)\.\w+$/);
  const streamId = streamIdMatch ? streamIdMatch[1] : '0';

  // Format start time as YYYY-MM-DD:HH-MM
  const startDate = new Date(programmeStart * 1000);
  const year = startDate.getUTCFullYear();
  const month = String(startDate.getUTCMonth() + 1).padStart(2, '0');
  const day = String(startDate.getUTCDate()).padStart(2, '0');
  const hours = String(startDate.getUTCHours()).padStart(2, '0');
  const minutes = String(startDate.getUTCMinutes()).padStart(2, '0');
  const startStr = `${year}-${month}-${day}:${hours}-${minutes}`;

  // Duration in minutes
  const durationMins = Math.ceil(programmeDuration / 60);

  return `${baseUrl}/timeshift/${username}/${password}/${durationMins}/${startStr}/${streamId}.ts`;
}

/**
 * Build catch-up URL for M3U sources using catchup-source patterns.
 *
 * Common M3U catchup patterns:
 *   catchup-source="http://example.com/timeshift/{stream_id}/{start}/{duration}"
 *   catchup-type="flussonic" / "xc" / "shift" / "append"
 *
 * Placeholder variables:
 *   {start}        — Unix timestamp or formatted date
 *   {end}          — End timestamp
 *   {duration}     — Duration in seconds
 *   {timestamp}    — Alias for {start}
 *   {utc}          — UTC timestamp
 *   {lutc}         — Local time UTC
 *   {stream_id}    — Extracted from original URL
 *   {Y}, {m}, {d}, {H}, {M}, {S} — Date components
 */
export function buildM3uCatchupUrl(
  originalUrl: string,
  metadata: Record<string, unknown>,
  programmeStart: number,
  programmeDuration: number,
): string | null {
  const catchupSource = String(metadata.catchupSource ?? '');
  const catchupType = String(metadata.catchupType ?? '');
  const nowSecs = Math.floor(Date.now() / 1000);

  if (!catchupSource && !catchupType) return null;

  // MB-385 — no catchup-source template: build from the TYPE. "default" is the
  // common Kodi keyword and means append; an unknown type we can't build
  // returns null (caller shows unavailable) instead of the old fall-through
  // that returned the LIVE url and silently played live.
  if (!catchupSource) {
    const t = catchupType.toLowerCase();
    if (t === 'shift' || t === 'timeshift') {
      return appendCatchupParams(originalUrl, programmeStart, programmeDuration, nowSecs, true);
    }
    if (t === 'append' || t === 'default') {
      return appendCatchupParams(originalUrl, programmeStart, programmeDuration, nowSecs, false);
    }
    return null;
  }

  // Replace placeholders in the catchup-source template
  const startDate = new Date(programmeStart * 1000);

  let template = catchupSource
    .replace(/\{start\}/g, String(programmeStart))
    .replace(/\{end\}/g, String(programmeStart + programmeDuration))
    .replace(/\{duration\}/g, String(programmeDuration))
    .replace(/\{timestamp\}/g, String(programmeStart))
    .replace(/\{utc\}/g, String(programmeStart))
    // MB-388 — {lutc} is "now" (offset = lutc-utc); {offset} = seconds back.
    .replace(/\{lutc\}/g, String(nowSecs))
    .replace(/\{offset\}/g, String(Math.max(0, nowSecs - programmeStart)))
    .replace(/\{Y\}/g, String(startDate.getUTCFullYear()))
    .replace(/\{m\}/g, String(startDate.getUTCMonth() + 1).padStart(2, '0'))
    .replace(/\{d\}/g, String(startDate.getUTCDate()).padStart(2, '0'))
    .replace(/\{H\}/g, String(startDate.getUTCHours()).padStart(2, '0'))
    .replace(/\{M\}/g, String(startDate.getUTCMinutes()).padStart(2, '0'))
    .replace(/\{S\}/g, String(startDate.getUTCSeconds()).padStart(2, '0'));

  // Extract and replace stream_id placeholder
  const streamIdMatch = originalUrl.match(/\/(\d+)\.\w+$/);
  if (streamIdMatch) {
    template = template.replace(/\{stream_id\}/g, streamIdMatch[1]);
  }

  return template;
}

/**
 * MB-388 — build an append/shift-style catch-up URL from the live URL. Uses `&`
 * when the URL already has a query (no double `?`); `lutc` is the CURRENT time
 * (providers derive the archive offset as `lutc - utc`, so `lutc == utc` served
 * live).
 */
function appendCatchupParams(
  originalUrl: string,
  utc: number,
  duration: number,
  nowSecs: number,
  shift: boolean,
): string {
  const sep = originalUrl.includes('?') ? '&' : '?';
  return shift
    ? `${originalUrl}${sep}utc=${utc}&lutc=${nowSecs}&shift=${Math.max(0, nowSecs - utc)}`
    : `${originalUrl}${sep}utc=${utc}&lutc=${nowSecs}&duration=${duration}`;
}
