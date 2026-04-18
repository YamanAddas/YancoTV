import log from 'electron-log/main';
import { StalkerClient as CoreStalkerClient } from '@yancotv/core';
import { nodeHttpClient } from './node-http-client';

export type {
  StalkerAuthInfo,
  StalkerCategory,
  StalkerChannel,
  StalkerVodItem,
  StalkerSeriesItem,
} from '@yancotv/core';

export class StalkerClient extends CoreStalkerClient {
  constructor(portalUrl: string, macAddress: string, timeoutMs?: number) {
    super(portalUrl, macAddress, { http: nodeHttpClient, logger: log, timeoutMs });
  }
}
