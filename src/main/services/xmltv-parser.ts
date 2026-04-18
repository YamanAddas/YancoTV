import zlib from 'zlib';
import log from 'electron-log/main';
import { parseXmltvString, parseXmltvTimestamp as parseXmltvTimestampCore } from '@yancotv/core';
import type { XmltvResult } from '@yancotv/core';

export type { XmltvProgramme, XmltvChannel, XmltvResult } from '@yancotv/core';

export const parseXmltvTimestamp = parseXmltvTimestampCore;

/**
 * Electron-flavoured wrapper. Accepts plain XML strings or gzipped Buffers;
 * delegates pure parsing to `@yancotv/core`.
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

  return parseXmltvString(xml, log);
}

function gunzipAsync(buffer: Buffer): Promise<string> {
  return new Promise((resolve, reject) => {
    zlib.gunzip(buffer, (err, result) => {
      if (err) reject(err);
      else resolve(result.toString('utf-8'));
    });
  });
}
