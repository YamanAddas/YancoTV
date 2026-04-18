import http from 'http';
import https from 'https';
import type { IncomingMessage } from 'http';
import type { HttpClient, HttpRequestOptions } from '@yancotv/core';
import { HttpResponseError } from '@yancotv/core';

const DEFAULT_TIMEOUT_MS = 60_000;
const DEFAULT_MAX_BYTES = 150 * 1024 * 1024;
const MAX_REDIRECTS = 5;

function fetchText(
  url: string,
  options: HttpRequestOptions | undefined,
  redirectsRemaining: number,
): Promise<string> {
  const timeoutMs = options?.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const maxBytes = options?.maxResponseBytes ?? DEFAULT_MAX_BYTES;
  const headers = options?.headers;
  const followRedirects = options?.followRedirects !== false;

  return new Promise<string>((resolve, reject) => {
    const callback = (res: IncomingMessage) => {
      if (
        followRedirects &&
        res.statusCode &&
        [301, 302, 307, 308].includes(res.statusCode) &&
        res.headers.location
      ) {
        if (redirectsRemaining <= 0) {
          reject(new Error('Too many redirects'));
          return;
        }
        fetchText(res.headers.location, options, redirectsRemaining - 1).then(resolve, reject);
        return;
      }

      if (res.statusCode && (res.statusCode < 200 || res.statusCode >= 300)) {
        reject(new HttpResponseError(res.statusCode, res.statusMessage ?? ''));
        return;
      }

      let receivedBytes = 0;
      const chunks: Buffer[] = [];

      res.on('data', (chunk: Buffer) => {
        receivedBytes += chunk.length;
        if (receivedBytes > maxBytes) {
          res.destroy();
          reject(new Error(`Response exceeded ${Math.round(maxBytes / 1024 / 1024)}MB limit`));
          return;
        }
        chunks.push(chunk);
      });

      res.on('end', () => {
        resolve(Buffer.concat(chunks).toString('utf-8'));
      });

      res.on('error', reject);
    };

    const requestOptions = { timeout: timeoutMs, headers };

    const request = url.startsWith('https')
      ? https.get(url, requestOptions, callback)
      : http.get(url, requestOptions, callback);

    request.on('error', reject);
    request.on('timeout', () => {
      request.destroy();
      reject(new Error('HTTP request timed out'));
    });
  });
}

export function createNodeHttpClient(): HttpClient {
  return {
    async getJson(url: string, options?: HttpRequestOptions): Promise<unknown> {
      const text = await fetchText(url, options, MAX_REDIRECTS);
      try {
        return JSON.parse(text);
      } catch {
        const preview = text.slice(0, 200);
        throw new Error(`Invalid JSON response: ${preview}`);
      }
    },
  };
}

export const nodeHttpClient: HttpClient = createNodeHttpClient();
