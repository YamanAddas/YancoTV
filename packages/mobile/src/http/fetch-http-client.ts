import {
  HttpResponseError,
  type HttpClient,
  type HttpRequestOptions,
} from '@yancotv/core';

const DEFAULT_TIMEOUT_MS = 20_000;
const DEFAULT_MAX_BYTES = 50 * 1024 * 1024;
const DEFAULT_UA = 'VLC/3.0.20 LibVLC/3.0.20';

function cleanUrl(raw: string): string {
  return raw
    .replace(/^\uFEFF/, '')
    .replace(/[\u200B-\u200D\uFEFF]/g, '')
    .replace(/\s+/g, '')
    .trim();
}

function redactUrl(url: string): string {
  return url
    .replace(/([?&]password=)[^&]*/gi, '$1***')
    .replace(/([?&]pw=)[^&]*/gi, '$1***');
}

function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

function xhrGet(url: string, headers: Record<string, string>, timeoutMs: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    let timer: ReturnType<typeof setTimeout> | null = setTimeout(() => {
      xhr.abort();
      reject(new Error(`Timed out after ${timeoutMs}ms`));
    }, timeoutMs);

    const clearTimer = () => {
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
    };

    xhr.open('GET', url, true);
    for (const [k, v] of Object.entries(headers)) {
      try {
        xhr.setRequestHeader(k, v);
      } catch {
        // Some headers are protected — ignore silently.
      }
    }

    xhr.onload = () => {
      clearTimer();
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(xhr.responseText);
      } else {
        reject(new HttpResponseError(xhr.status, xhr.statusText || `HTTP ${xhr.status}`));
      }
    };

    xhr.onerror = () => {
      clearTimer();
      const status = xhr.status;
      const st = xhr.statusText;
      if (status && status > 0) {
        reject(new HttpResponseError(status, st || `HTTP ${status}`));
      } else {
        const hint =
          url.startsWith('https://')
            ? 'TLS/SSL failure, DNS lookup failed, or connection refused'
            : 'DNS lookup failed, connection refused, or cleartext blocked';
        reject(new Error(`Connect failed (${hint})`));
      }
    };

    xhr.ontimeout = () => {
      clearTimer();
      reject(new Error(`Timed out after ${timeoutMs}ms`));
    };

    xhr.timeout = timeoutMs;
    xhr.send();
  });
}

async function fetchText(rawUrl: string, options: HttpRequestOptions | undefined): Promise<string> {
  const url = cleanUrl(rawUrl);
  if (!/^https?:\/\//i.test(url)) {
    throw new Error(`Bad URL (must start with http:// or https://): ${redactUrl(url)}`);
  }

  const timeoutMs = options?.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const headers: Record<string, string> = {
    'User-Agent': DEFAULT_UA,
    Accept: '*/*',
    ...(options?.headers ?? {}),
  };

  try {
    const text = await xhrGet(url, headers, timeoutMs);
    const maxBytes = options?.maxResponseBytes ?? DEFAULT_MAX_BYTES;
    if (text.length > maxBytes) {
      throw new Error(`Response too large: ${text.length} bytes (max ${maxBytes})`);
    }
    return text;
  } catch (err) {
    if (err instanceof HttpResponseError) {
      throw new Error(`${redactUrl(url)} → HTTP ${err.status} ${err.message}`);
    }
    const msg = err instanceof Error ? err.message : String(err);
    throw new Error(`${redactUrl(url)} → ${msg}`);
  }
}

export function createFetchHttpClient(): HttpClient {
  return {
    async getJson(url: string, options?: HttpRequestOptions) {
      const text = await fetchText(url, options);
      try {
        return JSON.parse(text);
      } catch (err) {
        const snippet = text.slice(0, 80).replace(/\s+/g, ' ');
        throw new Error(
          `Invalid JSON from ${hostOf(cleanUrl(url))} (got "${snippet}..."): ${err instanceof Error ? err.message : String(err)}`,
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

export async function pingHost(url: string): Promise<{ ok: boolean; detail: string }> {
  try {
    const cleaned = cleanUrl(url);
    const parsed = new URL(cleaned);
    const origin = `${parsed.protocol}//${parsed.host}`;
    await xhrGet(origin + '/', { 'User-Agent': DEFAULT_UA, Accept: '*/*' }, 8000);
    return { ok: true, detail: `Reached ${parsed.host}` };
  } catch (err) {
    if (err instanceof HttpResponseError) {
      return { ok: true, detail: `Reached host (server returned HTTP ${err.status})` };
    }
    const msg = err instanceof Error ? err.message : String(err);
    return { ok: false, detail: msg };
  }
}
