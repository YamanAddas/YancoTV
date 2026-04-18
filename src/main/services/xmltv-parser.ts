import log from 'electron-log/main';
import {
  parseXmltv as parseXmltvCore,
  parseXmltvTimestamp as parseXmltvTimestampCore,
} from '@yancotv/core';
import type { XmltvResult } from '@yancotv/core';

export type { XmltvProgramme, XmltvChannel, XmltvResult } from '@yancotv/core';

export const parseXmltvTimestamp = parseXmltvTimestampCore;

/**
 * Electron-flavoured wrapper. Accepts plain XML strings or gzipped
 * Buffers; delegates gzip + parsing to `@yancotv/core` (pako-based, so
 * mobile shares the same code path).
 */
export async function parseXmltv(input: string | Buffer): Promise<XmltvResult> {
  if (typeof input === 'string') {
    return parseXmltvCore(input, log);
  }
  const bytes = new Uint8Array(
    input.buffer,
    input.byteOffset,
    input.byteLength,
  );
  return parseXmltvCore(bytes, log);
}
