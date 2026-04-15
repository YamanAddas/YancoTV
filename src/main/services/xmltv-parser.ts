/**
 * XMLTV parser for EPG data.
 *
 * Uses an indexOf-based approach instead of regex to avoid catastrophic
 * backtracking and O(n²) behaviour on large EPG files (100-200 MB).
 * The main parse function is async and yields to the event loop every
 * 2 000 programmes so IPC handlers remain responsive throughout.
 */

import zlib from 'zlib';
import log from 'electron-log/main';

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const OPEN_PROG = '<programme ';
const CLOSE_PROG = '</programme>';
const OPEN_CHAN = '<channel ';
const CLOSE_CHAN = '</channel>';
const YIELD_EVERY = 2_000; // yield to event loop every N programmes

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Parse XMLTV content (plain XML or gzipped buffer) into channels + programmes.
 *
 * Async — safe to await on the Electron main process without blocking IPC.
 */
export async function parseXmltv(input: string | Buffer): Promise<XmltvResult> {
  let xml: string;

  if (Buffer.isBuffer(input)) {
    try {
      xml = await gunzipAsync(input);
    } catch {
      // Not gzipped — decode as plain UTF-8
      xml = input.toString('utf-8');
    }
  } else {
    xml = input;
  }

  const channels = parseChannels(xml);
  const programmes = await parseProgrammesAsync(xml);

  log.info(`XMLTV parsed: ${channels.length} channels, ${programmes.length} programmes`);
  return { channels, programmes };
}

// ---------------------------------------------------------------------------
// XMLTV timestamp parsing
// ---------------------------------------------------------------------------

/**
 * Parse XMLTV timestamp: "YYYYMMDDHHmmss +HHMM" or "YYYYMMDDHHmmss"
 * Returns Unix seconds, or 0 if unparseable.
 */
export function parseXmltvTimestamp(ts: string): number {
  if (!ts) return 0;

  const cleaned = ts.trim();
  const match = cleaned.match(
    /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})\s*([+-]\d{4})?$/,
  );
  if (!match) return 0;

  const [, year, month, day, hour, minute, second, offset] = match;
  let isoStr = `${year}-${month}-${day}T${hour}:${minute}:${second}`;

  if (offset) {
    isoStr += `${offset.slice(0, 3)}:${offset.slice(3)}`;
  } else {
    isoStr += 'Z';
  }

  const date = new Date(isoStr);
  return isNaN(date.getTime()) ? 0 : Math.floor(date.getTime() / 1000);
}

// ---------------------------------------------------------------------------
// Channel parsing
// ---------------------------------------------------------------------------

function parseChannels(xml: string): XmltvChannel[] {
  const channels: XmltvChannel[] = [];
  let pos = 0;

  while (true) {
    const start = xml.indexOf(OPEN_CHAN, pos);
    if (start === -1) break;

    const attrEnd = xml.indexOf('>', start + OPEN_CHAN.length);
    if (attrEnd === -1) break;

    const closeStart = xml.indexOf(CLOSE_CHAN, attrEnd + 1);
    if (closeStart === -1) break;

    const attrs = xml.slice(start + OPEN_CHAN.length, attrEnd);
    const body = xml.slice(attrEnd + 1, closeStart);
    pos = closeStart + CLOSE_CHAN.length;

    const id = extractAttrFast(attrs, 'id');
    if (!id) continue;

    channels.push({
      id,
      displayName: extractTagFast(body, 'display-name') ?? undefined,
      iconUrl: extractAttrFromTag(body, 'icon', 'src') ?? undefined,
    });
  }

  return channels;
}

// ---------------------------------------------------------------------------
// Programme parsing — async with periodic event-loop yields
// ---------------------------------------------------------------------------

async function parseProgrammesAsync(xml: string): Promise<XmltvProgramme[]> {
  const programmes: XmltvProgramme[] = [];
  let pos = 0;
  let count = 0;

  while (true) {
    const start = xml.indexOf(OPEN_PROG, pos);
    if (start === -1) break;

    const attrEnd = xml.indexOf('>', start + OPEN_PROG.length);
    if (attrEnd === -1) break;

    // Skip self-closing <programme .../> (rare but valid XML)
    if (xml[attrEnd - 1] === '/') {
      pos = attrEnd + 1;
      continue;
    }

    const closeStart = xml.indexOf(CLOSE_PROG, attrEnd + 1);
    if (closeStart === -1) break;

    const attrs = xml.slice(start + OPEN_PROG.length, attrEnd);
    const body = xml.slice(attrEnd + 1, closeStart);
    pos = closeStart + CLOSE_PROG.length;

    const channelId = extractAttrFast(attrs, 'channel');
    const startStr = extractAttrFast(attrs, 'start');
    const stopStr = extractAttrFast(attrs, 'stop');

    if (!channelId || !startStr || !stopStr) continue;

    const startTime = parseXmltvTimestamp(startStr);
    const endTime = parseXmltvTimestamp(stopStr);
    if (!startTime || !endTime) continue;

    const title = extractTagFast(body, 'title');
    if (!title) continue;

    programmes.push({
      channelId,
      title,
      description: extractTagFast(body, 'desc') ?? undefined,
      startTime,
      endTime,
      category: extractTagFast(body, 'category') ?? undefined,
      iconUrl: extractAttrFromTag(body, 'icon', 'src') ?? undefined,
    });

    // Yield to event loop so IPC handlers stay responsive during parse
    if (++count % YIELD_EVERY === 0) {
      await yieldToEventLoop();
    }
  }

  return programmes;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function gunzipAsync(buffer: Buffer): Promise<string> {
  return new Promise((resolve, reject) => {
    zlib.gunzip(buffer, (err, result) => {
      if (err) reject(err);
      else resolve(result.toString('utf-8'));
    });
  });
}

function yieldToEventLoop(): Promise<void> {
  return new Promise<void>((resolve) => setImmediate(resolve));
}

/**
 * Extract an attribute value from an attribute-string slice.
 * e.g. extractAttrFast('start="20260415" channel="BBC1"', 'channel') → "BBC1"
 */
function extractAttrFast(attrs: string, name: string): string | null {
  const search = `${name}="`;
  const idx = attrs.indexOf(search);
  if (idx === -1) return null;
  const valStart = idx + search.length;
  const valEnd = attrs.indexOf('"', valStart);
  if (valEnd === -1) return null;
  return decodeXmlEntities(attrs.slice(valStart, valEnd));
}

/**
 * Extract the text content of the first matching element.
 * e.g. extractTagFast('<title lang="en">News</title>', 'title') → "News"
 */
function extractTagFast(body: string, tagName: string): string | null {
  const open = `<${tagName}`;
  const close = `</${tagName}>`;

  const openIdx = body.indexOf(open);
  if (openIdx === -1) return null;

  const gtIdx = body.indexOf('>', openIdx + open.length);
  if (gtIdx === -1) return null;

  if (body[gtIdx - 1] === '/') return null; // self-closing

  const closeIdx = body.indexOf(close, gtIdx + 1);
  if (closeIdx === -1) return null;

  return decodeXmlEntities(body.slice(gtIdx + 1, closeIdx).trim());
}

/**
 * Extract an attribute from a child tag within a body string.
 * e.g. extractAttrFromTag('<icon src="http://..." />', 'icon', 'src') → "http://..."
 */
function extractAttrFromTag(
  body: string,
  tagName: string,
  attrName: string,
): string | null {
  const open = `<${tagName}`;
  const idx = body.indexOf(open);
  if (idx === -1) return null;
  const tagEnd = body.indexOf('>', idx + open.length);
  if (tagEnd === -1) return null;
  return extractAttrFast(body.slice(idx + open.length, tagEnd), attrName);
}

/** Decode common XML character entities. Fast-path when no '&' present. */
function decodeXmlEntities(str: string): string {
  if (!str.includes('&')) return str;
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
