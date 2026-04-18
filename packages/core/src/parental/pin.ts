/**
 * Parental PIN hashing — platform-agnostic.
 *
 * Hash format: `scrypt:{saltHex}:{hashHex}` (new) or 64-char hex SHA-256 (legacy).
 * Legacy hashes are accepted on verify so an existing desktop DB keeps working;
 * callers upgrade them to salted scrypt on the next successful check.
 *
 * Both sync and async variants are exported. Desktop uses sync (keeps the
 * existing IPC signatures); mobile should prefer async to avoid blocking
 * the JS thread for the ~20–50ms scrypt takes.
 */

import { sha256 } from '@noble/hashes/sha2.js';
import { scrypt, scryptAsync } from '@noble/hashes/scrypt.js';
import { bytesToHex, hexToBytes, utf8ToBytes } from '@noble/hashes/utils.js';

const SCRYPT_KEY_LEN = 64;
const SCRYPT_SALT_BYTES = 16;
// Cost tuned for ~20ms on a modern desktop — painful to brute-force 4-digit
// PINs while still snappy for interactive entry.
const SCRYPT_N = 1 << 14;
const SCRYPT_R = 8;
const SCRYPT_P = 1;

const SCRYPT_OPTS = {
  N: SCRYPT_N,
  r: SCRYPT_R,
  p: SCRYPT_P,
  dkLen: SCRYPT_KEY_LEN,
} as const;

export interface PinVerifyResult {
  ok: boolean;
  /** True if the match was against a legacy unsalted SHA-256 hash. */
  legacy: boolean;
}

function cryptoRandomBytes(length: number): Uint8Array {
  const out = new Uint8Array(length);
  const g = (globalThis as { crypto?: Crypto }).crypto;
  if (g && typeof g.getRandomValues === 'function') {
    g.getRandomValues(out);
    return out;
  }
  throw new Error(
    'No secure random source available. Expected globalThis.crypto.getRandomValues.',
  );
}

/** Timing-safe bytewise equality. */
export function timingSafeEqualBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

/** SHA-256 of a UTF-8 PIN, as lowercase hex. Legacy format. */
export function legacyPinSha256Hex(pin: string): string {
  return bytesToHex(sha256(utf8ToBytes(pin)));
}

function encodeScrypt(salt: Uint8Array, hash: Uint8Array): string {
  return `scrypt:${bytesToHex(salt)}:${bytesToHex(hash)}`;
}

function parseScrypt(stored: string): { salt: Uint8Array; expected: Uint8Array } | null {
  const [, saltHex, hashHex] = stored.split(':');
  if (!saltHex || !hashHex) return null;
  try {
    return { salt: hexToBytes(saltHex), expected: hexToBytes(hashHex) };
  } catch {
    return null;
  }
}

function isLegacySha256(stored: string): boolean {
  return /^[a-f0-9]{64}$/i.test(stored);
}

// ---------------------------------------------------------------------------
// Sync variants — block the caller. Use on desktop (main process) where IPC
// latency already dominates and we want to keep the existing IPC signatures.
// ---------------------------------------------------------------------------

export function encodePinScryptSync(pin: string): string {
  const salt = cryptoRandomBytes(SCRYPT_SALT_BYTES);
  const hash = scrypt(utf8ToBytes(pin), salt, SCRYPT_OPTS);
  return encodeScrypt(salt, hash);
}

export function verifyPinAgainstHashSync(pin: string, stored: string): PinVerifyResult {
  const normalized = stored.trim();
  if (normalized.startsWith('scrypt:')) {
    const parsed = parseScrypt(normalized);
    if (!parsed) return { ok: false, legacy: false };
    const actual = scrypt(utf8ToBytes(pin), parsed.salt, SCRYPT_OPTS);
    return { ok: timingSafeEqualBytes(actual, parsed.expected), legacy: false };
  }
  if (isLegacySha256(normalized)) {
    const actual = sha256(utf8ToBytes(pin));
    const expected = hexToBytes(normalized);
    return { ok: timingSafeEqualBytes(actual, expected), legacy: true };
  }
  return { ok: false, legacy: false };
}

// ---------------------------------------------------------------------------
// Async variants — non-blocking via scryptAsync. Preferred on mobile so a
// PIN check doesn't freeze the UI.
// ---------------------------------------------------------------------------

export async function encodePinScryptAsync(pin: string): Promise<string> {
  const salt = cryptoRandomBytes(SCRYPT_SALT_BYTES);
  const hash = await scryptAsync(utf8ToBytes(pin), salt, SCRYPT_OPTS);
  return encodeScrypt(salt, hash);
}

export async function verifyPinAgainstHashAsync(
  pin: string,
  stored: string,
): Promise<PinVerifyResult> {
  const normalized = stored.trim();
  if (normalized.startsWith('scrypt:')) {
    const parsed = parseScrypt(normalized);
    if (!parsed) return { ok: false, legacy: false };
    const actual = await scryptAsync(utf8ToBytes(pin), parsed.salt, SCRYPT_OPTS);
    return { ok: timingSafeEqualBytes(actual, parsed.expected), legacy: false };
  }
  if (isLegacySha256(normalized)) {
    const actual = sha256(utf8ToBytes(pin));
    const expected = hexToBytes(normalized);
    return { ok: timingSafeEqualBytes(actual, expected), legacy: true };
  }
  return { ok: false, legacy: false };
}
