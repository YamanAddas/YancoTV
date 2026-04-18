export interface HttpRequestOptions {
  /** Per-request timeout in milliseconds. */
  timeoutMs?: number;
  /** Extra request headers. */
  headers?: Record<string, string>;
  /** Hard cap on response body size in bytes. Implementations should reject when exceeded. */
  maxResponseBytes?: number;
  /** Whether to follow HTTP 3xx redirects. Defaults to true. */
  followRedirects?: boolean;
}

/** Thrown for non-2xx HTTP responses. Preserves status so retry logic can match on it. */
export class HttpResponseError extends Error {
  constructor(
    public readonly status: number,
    public readonly statusText: string,
    message?: string,
  ) {
    super(message ?? `HTTP ${status}: ${statusText}`);
    this.name = 'HttpResponseError';
  }
}

/**
 * Minimal HTTP client interface. Platform implementations handle transport
 * (Node http/https, fetch, React Native fetch, etc.) while core code stays agnostic.
 */
export interface HttpClient {
  /** Fetch JSON from a URL. Throws on network error, non-2xx status, or invalid JSON. */
  getJson(url: string, options?: HttpRequestOptions): Promise<unknown>;
}
