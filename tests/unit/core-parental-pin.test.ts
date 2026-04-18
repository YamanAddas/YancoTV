import { describe, it, expect } from 'vitest';
import {
  encodePinScryptSync,
  encodePinScryptAsync,
  verifyPinAgainstHashSync,
  verifyPinAgainstHashAsync,
  legacyPinSha256Hex,
  timingSafeEqualBytes,
} from '@yancotv/core';

describe('parental/pin', () => {
  describe('encode/verify round-trip (sync)', () => {
    it('accepts the correct PIN and rejects a wrong one', () => {
      const hash = encodePinScryptSync('1234');
      expect(hash.startsWith('scrypt:')).toBe(true);
      expect(verifyPinAgainstHashSync('1234', hash)).toEqual({ ok: true, legacy: false });
      expect(verifyPinAgainstHashSync('9999', hash)).toEqual({ ok: false, legacy: false });
    });

    it('produces a different salt on every encode', () => {
      const a = encodePinScryptSync('1234');
      const b = encodePinScryptSync('1234');
      expect(a).not.toBe(b);
    });
  });

  describe('encode/verify round-trip (async)', () => {
    it('accepts the correct PIN via scryptAsync', async () => {
      const hash = await encodePinScryptAsync('0000');
      const result = await verifyPinAgainstHashAsync('0000', hash);
      expect(result).toEqual({ ok: true, legacy: false });
    });

    it('async verify accepts a sync-encoded hash', async () => {
      const hash = encodePinScryptSync('4242');
      const result = await verifyPinAgainstHashAsync('4242', hash);
      expect(result.ok).toBe(true);
    });
  });

  describe('legacy SHA-256 fallback', () => {
    it('matches an unsalted SHA-256 hex hash and marks it legacy', () => {
      const stored = legacyPinSha256Hex('7777');
      expect(verifyPinAgainstHashSync('7777', stored)).toEqual({ ok: true, legacy: true });
      expect(verifyPinAgainstHashSync('0000', stored).ok).toBe(false);
    });

    it('async verify also honours legacy hashes', async () => {
      const stored = legacyPinSha256Hex('5555');
      const result = await verifyPinAgainstHashAsync('5555', stored);
      expect(result).toEqual({ ok: true, legacy: true });
    });
  });

  describe('stored-hash hygiene', () => {
    it('tolerates whitespace around a stored scrypt hash (DB round-trip artifacts)', () => {
      const hash = encodePinScryptSync('1111');
      const padded = `  ${hash}\n`;
      expect(verifyPinAgainstHashSync('1111', padded).ok).toBe(true);
    });

    it('tolerates whitespace around a stored legacy hash', () => {
      const stored = `  ${legacyPinSha256Hex('2222')}\n`;
      const result = verifyPinAgainstHashSync('2222', stored);
      expect(result).toEqual({ ok: true, legacy: true });
    });

    it('rejects garbled stored strings without throwing', () => {
      expect(verifyPinAgainstHashSync('1234', '').ok).toBe(false);
      expect(verifyPinAgainstHashSync('1234', 'scrypt:not-hex:not-hex').ok).toBe(false);
      expect(verifyPinAgainstHashSync('1234', 'scrypt:').ok).toBe(false);
      expect(verifyPinAgainstHashSync('1234', 'totally bogus').ok).toBe(false);
    });
  });

  describe('timingSafeEqualBytes', () => {
    it('returns true for identical buffers', () => {
      expect(timingSafeEqualBytes(new Uint8Array([1, 2, 3]), new Uint8Array([1, 2, 3]))).toBe(true);
    });

    it('returns false for different buffers of equal length', () => {
      expect(timingSafeEqualBytes(new Uint8Array([1, 2, 3]), new Uint8Array([1, 2, 4]))).toBe(false);
    });

    it('returns false for different-length buffers', () => {
      expect(timingSafeEqualBytes(new Uint8Array([1, 2]), new Uint8Array([1, 2, 3]))).toBe(false);
    });
  });
});
