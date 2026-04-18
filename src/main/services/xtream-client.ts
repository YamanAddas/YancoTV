import log from 'electron-log/main';
import { XtreamClient as CoreXtreamClient } from '@yancotv/core';
import { nodeHttpClient } from './node-http-client';

export type {
  XtreamAuthInfo,
  XtreamCategory,
  XtreamLiveStream,
  XtreamVodStream,
  XtreamSeriesInfo,
  XtreamSeriesEpisode,
  XtreamSeriesDetail,
  XtreamVodDetail,
} from '@yancotv/core';

export class XtreamClient extends CoreXtreamClient {
  constructor(url: string, username: string, password: string, timeoutMs?: number) {
    super(url, username, password, { http: nodeHttpClient, logger: log, timeoutMs });
  }
}
