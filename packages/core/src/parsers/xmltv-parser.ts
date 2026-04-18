/**
 * XMLTV parser for EPG data.
 *
 * Uses an indexOf-based approach instead of regex to avoid catastrophic
 * backtracking and O(n²) behaviour on large EPG files (100-200 MB).
 * The main parse function is async and yields to the event loop every
 * 2 000 programmes so IPC handlers remain responsive throughout.
 *
 * Platform-agnostic: takes a plain XML string. The desktop side owns
 * gunzip/Buffer handling since core must not depend on Node `zlib`.
 */

import { NOOP_LOGGER, type Logger } from '../logger';

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
 * Parse an XMLTV string into channels + programmes.
 * Async — yields to the event loop every {@link YIELD_EVERY} programmes
 * so host runtime stays responsive.
 */
export async function parseXmltvString(
  xml: string,
  logger: Logger = NOOP_LOGGER,
): Promise<XmltvResult> {
  const channels = parseChannels(xml);
  const programmes = await parseProgrammesAsync(xml);

  logger.info(`XMLTV parsed: ${channels.length} channels, ${programmes.length} programmes`);
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

function yieldToEventLoop(): Promise<void> {
  // Portable across Node, browsers and React Native. The microsecond-level
  // difference vs. setImmediate is negligible for 2000-item batches.
  return new Promise<void>((resolve) => setTimeout(resolve, 0));
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

/**
 * Common HTML named entities that appear in real-world EPG feeds beyond the
 * five XML-defined ones. Not exhaustive — this covers the practical set
 * (whitespace, dashes, quotes, accents, common symbols). Unknown entities
 * are left intact so the raw text doesn't get corrupted.
 */
const NAMED_ENTITIES: Record<string, string> = {
  // XML-defined
  amp: '&',
  lt: '<',
  gt: '>',
  quot: '"',
  apos: "'",
  // Whitespace & punctuation
  nbsp: '\u00A0',
  ensp: '\u2002',
  emsp: '\u2003',
  thinsp: '\u2009',
  ndash: '\u2013',
  mdash: '\u2014',
  hellip: '\u2026',
  lsquo: '\u2018',
  rsquo: '\u2019',
  ldquo: '\u201C',
  rdquo: '\u201D',
  laquo: '\u00AB',
  raquo: '\u00BB',
  middot: '\u00B7',
  bull: '\u2022',
  // Symbols
  copy: '\u00A9',
  reg: '\u00AE',
  trade: '\u2122',
  deg: '\u00B0',
  plusmn: '\u00B1',
  times: '\u00D7',
  divide: '\u00F7',
  pound: '\u00A3',
  euro: '\u20AC',
  yen: '\u00A5',
  cent: '\u00A2',
  sect: '\u00A7',
  para: '\u00B6',
  // Common Latin-1 accents (most others fall through unchanged)
  agrave: '\u00E0',
  aacute: '\u00E1',
  acirc: '\u00E2',
  atilde: '\u00E3',
  auml: '\u00E4',
  aring: '\u00E5',
  aelig: '\u00E6',
  ccedil: '\u00E7',
  egrave: '\u00E8',
  eacute: '\u00E9',
  ecirc: '\u00EA',
  euml: '\u00EB',
  igrave: '\u00EC',
  iacute: '\u00ED',
  icirc: '\u00EE',
  iuml: '\u00EF',
  ntilde: '\u00F1',
  ograve: '\u00F2',
  oacute: '\u00F3',
  ocirc: '\u00F4',
  otilde: '\u00F5',
  ouml: '\u00F6',
  oslash: '\u00F8',
  ugrave: '\u00F9',
  uacute: '\u00FA',
  ucirc: '\u00FB',
  uuml: '\u00FC',
  yacute: '\u00FD',
  szlig: '\u00DF',
  Agrave: '\u00C0',
  Aacute: '\u00C1',
  Acirc: '\u00C2',
  Atilde: '\u00C3',
  Auml: '\u00C4',
  Aring: '\u00C5',
  AElig: '\u00C6',
  Ccedil: '\u00C7',
  Egrave: '\u00C8',
  Eacute: '\u00C9',
  Ecirc: '\u00CA',
  Euml: '\u00CB',
  Ntilde: '\u00D1',
  Oacute: '\u00D3',
  Ouml: '\u00D6',
  Uuml: '\u00DC',
};

/** Decode XML/HTML character entities. Fast-path when no '&' present.
 *  Handles named entities (&amp; &nbsp; …), decimal (&#160;), and hex (&#xA0;).
 *  Unknown named entities pass through unchanged. */
function decodeXmlEntities(str: string): string {
  if (!str.includes('&')) return str;
  return str.replace(/&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z][a-zA-Z0-9]*);/g, (match, ref: string) => {
    if (ref[0] === '#') {
      const code = ref[1] === 'x' || ref[1] === 'X'
        ? parseInt(ref.slice(2), 16)
        : parseInt(ref.slice(1), 10);
      if (!Number.isFinite(code) || code < 0 || code > 0x10FFFF) return match;
      try {
        return String.fromCodePoint(code);
      } catch {
        return match;
      }
    }
    const named = NAMED_ENTITIES[ref];
    return named !== undefined ? named : match;
  });
}
