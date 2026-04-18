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

  if (!catchupSource && !catchupType) return null;

  let template = catchupSource || originalUrl;

  // If catchup type is "append", just append the start/duration to the URL
  if (catchupType === 'append' && !catchupSource) {
    return `${originalUrl}?utc=${programmeStart}&lutc=${programmeStart}&duration=${programmeDuration}`;
  }

  // If catchup type is "shift", use standard shift format
  if (catchupType === 'shift' && !catchupSource) {
    const nowSecs = Math.floor(Date.now() / 1000);
    const shift = nowSecs - programmeStart;
    return `${originalUrl}?utc=${programmeStart}&lutc=${programmeStart}&shift=${shift}`;
  }

  // Replace placeholders in the catchup-source template
  const startDate = new Date(programmeStart * 1000);

  template = template
    .replace(/\{start\}/g, String(programmeStart))
    .replace(/\{end\}/g, String(programmeStart + programmeDuration))
    .replace(/\{duration\}/g, String(programmeDuration))
    .replace(/\{timestamp\}/g, String(programmeStart))
    .replace(/\{utc\}/g, String(programmeStart))
    .replace(/\{lutc\}/g, String(programmeStart))
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
