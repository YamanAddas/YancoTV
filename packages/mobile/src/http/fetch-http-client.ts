import {
  HttpResponseError,
  type HttpClient,
  type HttpRequestOptions,
} from '@yancotv/core';

const DEFAULT_TIMEOUT_MS = 20_000;
const DEFAULT_MAX_BYTES = 50 * 1024 * 1024;

async function fetchText(url: string, options: HttpRequestOptions | undefined): Promise<string> {
  const controller = new AbortController();
  const timeoutMs = options?.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const res = await fetch(url, {
      method: 'GET',
      headers: options?.headers,
      signal: controller.signal,
      redirect: options?.followRedirects === false ? 'manual' : 'follow',
    });

    if (!res.ok) {
      throw new HttpResponseError(res.status, res.statusText || '');
    }

    const maxBytes = options?.maxResponseBytes ?? DEFAULT_MAX_BYTES;
    const lenHeader = res.headers.get('content-length');
    if (lenHeader) {
      const len = Number.parseInt(lenHeader, 10);
      if (Number.isFinite(len) && len > maxBytes) {
        throw new Error(`Response too large: ${len} bytes (max ${maxBytes})`);
      }
    }

    const text = await res.text();
    if (text.length > maxBytes) {
      throw new Error(`Response too large: ${text.length} bytes (max ${maxBytes})`);
    }
    return text;
  } finally {
    clearTimeout(timer);
  }
}

export function createFetchHttpClient(): HttpClient {
  return {
    async getJson(url: string, options?: HttpRequestOptions) {
      const text = await fetchText(url, options);
      try {
        return JSON.parse(text);
      } catch (err) {
        throw new Error(
          `Failed to parse JSON from ${url}: ${err instanceof Error ? err.message : String(err)}`,
        );
      }
    },
  };
}

export const fetchHttpClient: HttpClient = createFetchHttpClient();

export async function fetchTextRaw(
  url: string,
  options?: HttpRequestOptions,
): Promise<string> {
  return fetchText(url, options);
}
