/**
 * XMLTV parser for EPG data.
 *
 * Parses XMLTV format (plain XML or gzipped) into structured programme data.
 * Uses a simple regex-based approach rather than a full DOM parser to keep
 * memory usage low on large EPG files (some are 100MB+).
 */

import zlib from 'zlib';
import log from 'electron-log/main';

export interface XmltvProgramme {
  channelId: string;
  title: string;
  description?: string;
  startTime: number; // Unix seconds
  endTime: number; // Unix seconds
  category?: string;
  iconUrl?: string;
}

export interface XmltvChannel {
  id: string;
  displayName?: string;
  iconUrl?: string;
}

export interface XmltvResult {
  channels: XmltvChannel[];
  programmes: XmltvProgramme[];
}

/**
 * Parse XMLTV content (string or gzipped buffer) into channels and programmes.
 */
export function parseXmltv(input: string | Buffer): XmltvResult {
  let xml: string;

  if (Buffer.isBuffer(input)) {
    // Try to decompress as gzip; fall back to treating as plain text
    try {
      xml = zlib.gunzipSync(input).toString('utf-8');
    } catch {
      xml = input.toString('utf-8');
    }
  } else {
    xml = input;
  }

  const channels = parseChannels(xml);
  const programmes = parseProgrammes(xml);

  log.info(`XMLTV parsed: ${channels.length} channels, ${programmes.length} programmes`);

  return { channels, programmes };
}

// ---------------------------------------------------------------------------
// XMLTV timestamp parsing
// ---------------------------------------------------------------------------

/**
 * Parse XMLTV timestamp format: "YYYYMMDDHHmmss +HHMM" or "YYYYMMDDHHmmss"
 * Returns Unix seconds, or 0 if unparseable.
 */
export function parseXmltvTimestamp(ts: string): number {
  if (!ts) return 0;

  // Strip whitespace
  const cleaned = ts.trim();

  // Match: 20260414120000 +0200  or  20260414120000
  const match = cleaned.match(
    /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})\s*([+-]\d{4})?$/,
  );
  if (!match) return 0;

  const [, year, month, day, hour, minute, second, offset] = match;

  // Build an ISO-like string the Date constructor can handle
  let isoStr = `${year}-${month}-${day}T${hour}:${minute}:${second}`;

  if (offset) {
    // Convert "+0200" -> "+02:00"
    isoStr += `${offset.slice(0, 3)}:${offset.slice(3)}`;
  } else {
    // No offset specified — assume UTC
    isoStr += 'Z';
  }

  const date = new Date(isoStr);
  if (isNaN(date.getTime())) return 0;

  return Math.floor(date.getTime() / 1000);
}

// ---------------------------------------------------------------------------
// Channel parsing
// ---------------------------------------------------------------------------

function parseChannels(xml: string): XmltvChannel[] {
  const channels: XmltvChannel[] = [];
  const channelRegex = /<channel\s+id="([^"]*)"[^>]*>([\s\S]*?)<\/channel>/gi;

  let match;
  while ((match = channelRegex.exec(xml)) !== null) {
    const id = match[1];
    const body = match[2];

    const displayName = extractTagContent(body, 'display-name');
    const iconUrl = extractAttr(body, 'icon', 'src');

    channels.push({
      id,
      displayName: displayName || undefined,
      iconUrl: iconUrl || undefined,
    });
  }

  return channels;
}

// ---------------------------------------------------------------------------
// Programme parsing
// ---------------------------------------------------------------------------

function parseProgrammes(xml: string): XmltvProgramme[] {
  const programmes: XmltvProgramme[] = [];

  // Match <programme ...>...</programme>
  const progRegex =
    /<programme\s+([^>]*)>([\s\S]*?)<\/programme>/gi;

  let match;
  while ((match = progRegex.exec(xml)) !== null) {
    const attrs = match[1];
    const body = match[2];

    const startStr = extractAttrFromString(attrs, 'start');
    const stopStr = extractAttrFromString(attrs, 'stop');
    const channelId = extractAttrFromString(attrs, 'channel');

    if (!startStr || !stopStr || !channelId) continue;

    const startTime = parseXmltvTimestamp(startStr);
    const endTime = parseXmltvTimestamp(stopStr);
    if (!startTime || !endTime) continue;

    const title = extractTagContent(body, 'title');
    if (!title) continue;

    const description = extractTagContent(body, 'desc') || undefined;
    const category = extractTagContent(body, 'category') || undefined;
    const iconUrl = extractAttr(body, 'icon', 'src') || undefined;

    programmes.push({
      channelId,
      title,
      description,
      startTime,
      endTime,
      category,
      iconUrl,
    });
  }

  return programmes;
}

// ---------------------------------------------------------------------------
// XML helpers — lightweight extraction without a full parser
// ---------------------------------------------------------------------------

/** Extract text content of the first occurrence of a tag */
function extractTagContent(xml: string, tagName: string): string | null {
  const regex = new RegExp(`<${tagName}[^>]*>([^<]*)</${tagName}>`, 'i');
  const match = regex.exec(xml);
  return match ? decodeXmlEntities(match[1].trim()) : null;
}

/** Extract an attribute value from within a tag's body (looks for <tagName attr="value" />) */
function extractAttr(
  xml: string,
  tagName: string,
  attrName: string,
): string | null {
  const regex = new RegExp(`<${tagName}\\s+[^>]*${attrName}="([^"]*)"`, 'i');
  const match = regex.exec(xml);
  return match ? decodeXmlEntities(match[1]) : null;
}

/** Extract an attribute value from an attributes string */
function extractAttrFromString(attrs: string, name: string): string | null {
  const regex = new RegExp(`${name}="([^"]*)"`, 'i');
  const match = regex.exec(attrs);
  return match ? decodeXmlEntities(match[1]) : null;
}

/** Decode common XML entities */
function decodeXmlEntities(str: string): string {
  return str
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, code) => String.fromCharCode(parseInt(code, 10)))
    .replace(/&#x([0-9a-fA-F]+);/g, (_, hex) =>
      String.fromCharCode(parseInt(hex, 16)),
    );
}
