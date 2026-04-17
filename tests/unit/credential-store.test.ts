import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('electron', () => ({
  safeStorage: {
    isEncryptionAvailable: vi.fn(),
    encryptString: vi.fn(),
    decryptString: vi.fn(),
  },
}));

vi.mock('electron-log/main', () => ({
  default: { warn: vi.fn(), info: vi.fn(), error: vi.fn() },
}));

import { safeStorage } from 'electron';
import {
  encryptCredential,
  decryptCredential,
} from '../../src/main/services/credential-store';

const mockSafeStorage = vi.mocked(safeStorage);

describe('Credential Store', () => {
  const ORIGINAL_OPT_IN = process.env.YANCOTV_ALLOW_PLAINTEXT_CREDENTIALS;

  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    if (ORIGINAL_OPT_IN === undefined) {
      delete process.env.YANCOTV_ALLOW_PLAINTEXT_CREDENTIALS;
    } else {
      process.env.YANCOTV_ALLOW_PLAINTEXT_CREDENTIALS = ORIGINAL_OPT_IN;
    }
  });

  describe('encryptCredential', () => {
    it('uses safeStorage when encryption is available', () => {
      mockSafeStorage.isEncryptionAvailable.mockReturnValue(true);
      const encrypted = Buffer.from('encrypted-data');
      mockSafeStorage.encryptString.mockReturnValue(encrypted);

      const result = encryptCredential('my-secret');

      expect(mockSafeStorage.isEncryptionAvailable).toHaveBeenCalled();
      expect(mockSafeStorage.encryptString).toHaveBeenCalledWith('my-secret');
      expect(result).toBe(encrypted);
    });

    it('throws when encryption is unavailable and plaintext opt-in is not set', () => {
      mockSafeStorage.isEncryptionAvailable.mockReturnValue(false);
      delete process.env.YANCOTV_ALLOW_PLAINTEXT_CREDENTIALS;

      expect(() => encryptCredential('my-secret')).toThrow(
        /Credential encryption unavailable/,
      );
      expect(mockSafeStorage.encryptString).not.toHaveBeenCalled();
    });

    it('falls back to plain Buffer when opt-in env var is set', () => {
      mockSafeStorage.isEncryptionAvailable.mockReturnValue(false);
      process.env.YANCOTV_ALLOW_PLAINTEXT_CREDENTIALS = '1';

      const result = encryptCredential('my-secret');

      expect(mockSafeStorage.encryptString).not.toHaveBeenCalled();
      expect(result).toEqual(Buffer.from('my-secret', 'utf-8'));
    });
  });

  describe('decryptCredential', () => {
    it('uses safeStorage when encryption is available', () => {
      mockSafeStorage.isEncryptionAvailable.mockReturnValue(true);
      mockSafeStorage.decryptString.mockReturnValue('my-secret');
      const encrypted = Buffer.from('encrypted-data');

      const result = decryptCredential(encrypted);

      expect(mockSafeStorage.isEncryptionAvailable).toHaveBeenCalled();
      expect(mockSafeStorage.decryptString).toHaveBeenCalledWith(encrypted);
      expect(result).toBe('my-secret');
    });

    it('falls back to plaintext decode when safeStorage decrypt throws (legacy data)', () => {
      mockSafeStorage.isEncryptionAvailable.mockReturnValue(true);
      mockSafeStorage.decryptString.mockImplementation(() => {
        throw new Error('not encrypted');
      });
      const plainBuffer = Buffer.from('legacy-secret', 'utf-8');

      const result = decryptCredential(plainBuffer);

      expect(result).toBe('legacy-secret');
    });

    it('falls back to Buffer.toString when encryption is not available', () => {
      mockSafeStorage.isEncryptionAvailable.mockReturnValue(false);
      const plainBuffer = Buffer.from('my-secret', 'utf-8');

      const result = decryptCredential(plainBuffer);

      expect(mockSafeStorage.decryptString).not.toHaveBeenCalled();
      expect(result).toBe('my-secret');
    });
  });

  describe('round-trip', () => {
    it('encrypt then decrypt returns original string with opt-in plaintext', () => {
      mockSafeStorage.isEncryptionAvailable.mockReturnValue(false);
      process.env.YANCOTV_ALLOW_PLAINTEXT_CREDENTIALS = '1';

      const original = 'super-secret-password-123!@#';
      const encrypted = encryptCredential(original);
      const decrypted = decryptCredential(encrypted);

      expect(decrypted).toBe(original);
    });

    it('encrypt then decrypt returns original string when encryption is available', () => {
      const fakeEncrypted = Buffer.from('fake-encrypted');
      mockSafeStorage.isEncryptionAvailable.mockReturnValue(true);
      mockSafeStorage.encryptString.mockReturnValue(fakeEncrypted);
      mockSafeStorage.decryptString.mockReturnValue('my-password');

      const encrypted = encryptCredential('my-password');
      const decrypted = decryptCredential(encrypted);

      expect(mockSafeStorage.encryptString).toHaveBeenCalledWith('my-password');
      expect(mockSafeStorage.decryptString).toHaveBeenCalledWith(fakeEncrypted);
      expect(decrypted).toBe('my-password');
    });
  });
});
