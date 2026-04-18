import log from 'electron-log/main';
import { parseM3u as parseM3uCore } from '@yancotv/core';

export type { M3uEntry, M3uParseResult } from '@yancotv/core';

/** Electron-flavoured wrapper that attaches electron-log. Core is pure. */
export function parseM3u(content: string) {
  return parseM3uCore(content, log);
}
