import { describe, it, expect } from 'vitest';
import {
  isRetriableStreamError,
  shouldRetry,
  RETRIABLE_ERROR_CODES,
} from '../../src/main/services/download-service';

/**
 * Pure-logic tests for the download retry decision. These cover the exact
 * error shapes undici throws at us in production (nested `cause`, `TypeError:
 * fetch failed` with a SocketError inside, etc.) plus the progress-aware
 * shouldRetry counters.
 */

describe('isRetriableStreamError', () => {
  it('returns false for null/undefined/primitives', () => {
    expect(isRetriableStreamError(null)).toBe(false);
    expect(isRetriableStreamError(undefined)).toBe(false);
    expect(isRetriableStreamError('oops')).toBe(false);
    expect(isRetriableStreamError(42)).toBe(false);
  });

  it('never retries a user-initiated AbortError', () => {
    const err = new Error('aborted');
    err.name = 'AbortError';
    expect(isRetriableStreamError(err)).toBe(false);
  });

  it('detects `terminated` in the top-level message', () => {
    const err = new TypeError('terminated');
    expect(isRetriableStreamError(err)).toBe(true);
  });

  it('detects `other side closed` one level deep in cause', () => {
    const inner = Object.assign(new Error('other side closed'), { code: 'UND_ERR_SOCKET' });
    const outer = Object.assign(new TypeError('terminated'), { cause: inner });
    expect(isRetriableStreamError(outer)).toBe(true);
  });

  it('detects UND_ERR_SOCKET code on a nested cause', () => {
    const socket = Object.assign(new Error('socket'), { code: 'UND_ERR_SOCKET' });
    const outer = Object.assign(new Error('wrapped'), { cause: socket });
    expect(isRetriableStreamError(outer)).toBe(true);
  });

  it('walks several cause levels (undici nests deep)', () => {
    const deepest = Object.assign(new Error('ECONNRESET'), { code: 'ECONNRESET' });
    const mid = Object.assign(new Error('wrap1'), { cause: deepest });
    const outer = Object.assign(new TypeError('fetch failed'), { cause: mid });
    expect(isRetriableStreamError(outer)).toBe(true);
  });

  it('detects body/headers timeout codes', () => {
    const err = Object.assign(new Error('timed out'), { code: 'UND_ERR_BODY_TIMEOUT' });
    expect(isRetriableStreamError(err)).toBe(true);
    const err2 = Object.assign(new Error('timed out'), { code: 'UND_ERR_HEADERS_TIMEOUT' });
    expect(isRetriableStreamError(err2)).toBe(true);
  });

  it('detects socket-hang-up phrase', () => {
    const err = new Error('socket hang up');
    expect(isRetriableStreamError(err)).toBe(true);
  });

  it('detects premature-close phrase', () => {
    const err = new Error('Premature close');
    expect(isRetriableStreamError(err)).toBe(true);
  });

  it('does NOT retry a permanent HTTP error shape', () => {
    const err = new Error('Server returned HTTP 403');
    expect(isRetriableStreamError(err)).toBe(false);
  });

  it('does NOT loop forever on a cyclic cause', () => {
    type Cyclic = Error & { cause?: unknown };
    const a: Cyclic = new Error('a');
    const b: Cyclic = new Error('b');
    a.cause = b;
    b.cause = a;
    // Neither message nor code matches — should return false without hanging.
    expect(isRetriableStreamError(a)).toBe(false);
  });

  it('RETRIABLE_ERROR_CODES covers the core undici/socket codes', () => {
    for (const code of ['UND_ERR_SOCKET', 'UND_ERR_BODY_TIMEOUT', 'ECONNRESET', 'ETIMEDOUT']) {
      expect(RETRIABLE_ERROR_CODES.has(code)).toBe(true);
    }
  });
});

describe('shouldRetry', () => {
  it('allows the first few retries', () => {
    expect(shouldRetry({ totalRetries: 0, noProgressRetries: 0 })).toBe(true);
    expect(shouldRetry({ totalRetries: 3, noProgressRetries: 1 })).toBe(true);
  });

  it('stops once noProgressRetries reaches the cap (4)', () => {
    expect(shouldRetry({ totalRetries: 5, noProgressRetries: 4 })).toBe(false);
  });

  it('stops once totalRetries reaches the cap (15)', () => {
    expect(shouldRetry({ totalRetries: 15, noProgressRetries: 0 })).toBe(false);
  });

  it('keeps allowing retries while progress is being made', () => {
    // A flaky server that drops every few MB: noProgressRetries stays at 0
    // because forward progress resets it each attempt. We should still be
    // allowed to retry up to the TOTAL cap.
    for (let i = 0; i < 15; i++) {
      expect(shouldRetry({ totalRetries: i, noProgressRetries: 0 })).toBe(true);
    }
    expect(shouldRetry({ totalRetries: 15, noProgressRetries: 0 })).toBe(false);
  });
});
