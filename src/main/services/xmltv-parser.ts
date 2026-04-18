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
 * mobile shares the same code path). Node Buffers are Uint8Array
 * subclasses, so they pass through to core without copying.
 */
export async function parseXmltv(input: string | Buffer): Promise<XmltvResult> {
  return parseXmltvCore(input, log);
}
