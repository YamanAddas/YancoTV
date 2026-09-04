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

describe('Credential decryption refuses to invent a credential from binary', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  /**
   * The legacy fallback is why this function is forgiving at all: rows written
   * before encryption was enforced are readable plaintext and must keep working
   * across an upgrade.
   */
  it('still reads a legacy plaintext row when decryption fails', () => {
    mockSafeStorage.isEncryptionAvailable.mockReturnValue(true);
    mockSafeStorage.decryptString.mockImplementation(() => {
      throw new Error('not encrypted by this keyring');
    });
    expect(decryptCredential(Buffer.from('my-username', 'utf-8'))).toBe('my-username');
  });

  it('reads legacy plaintext containing non-ASCII text', () => {
    mockSafeStorage.isEncryptionAvailable.mockReturnValue(true);
    mockSafeStorage.decryptString.mockImplementation(() => {
      throw new Error('boom');
    });
    // Arabic is a first-class locale on the sibling app; a password can hold it.
    expect(decryptCredential(Buffer.from('كلمة-سر', 'utf-8'))).toBe('كلمة-سر');
  });

  /**
   * The behaviour this change exists for. Previously a blob that failed to
   * decrypt was returned as `buf.toString('utf-8')`, so corrupt or foreign
   * bytes became a "credential" and travelled into a provider URL — surfacing
   * as a baffling auth failure rather than the storage fault it is.
   */
  it('throws rather than returning binary that failed to decrypt', () => {
    mockSafeStorage.isEncryptionAvailable.mockReturnValue(true);
    mockSafeStorage.decryptString.mockImplementation(() => {
      throw new Error('bad ciphertext');
    });
    const ciphertext = Buffer.from([0x01, 0x02, 0x00, 0xff, 0xfe, 0x7f, 0x10]);
    expect(() => decryptCredential(ciphertext)).toThrow(/could not be decrypted/i);
  });

  it('throws on bytes that are not valid UTF-8', () => {
    mockSafeStorage.isEncryptionAvailable.mockReturnValue(true);
    mockSafeStorage.decryptString.mockImplementation(() => {
      throw new Error('bad ciphertext');
    });
    // A lone continuation byte — decodes to U+FFFD, so re-encoding changes length.
    expect(() => decryptCredential(Buffer.from([0x80, 0x81, 0x82]))).toThrow();
  });

  it('throws on an empty buffer rather than yielding an empty credential', () => {
    mockSafeStorage.isEncryptionAvailable.mockReturnValue(true);
    mockSafeStorage.decryptString.mockImplementation(() => {
      throw new Error('bad ciphertext');
    });
    expect(() => decryptCredential(Buffer.alloc(0))).toThrow();
  });

  it('a successful decryption is unaffected', () => {
    mockSafeStorage.isEncryptionAvailable.mockReturnValue(true);
    mockSafeStorage.decryptString.mockReturnValue('secret');
    expect(decryptCredential(Buffer.from('anything'))).toBe('secret');
  });

  describe('with no keyring at all', () => {
    beforeEach(() => {
      mockSafeStorage.isEncryptionAvailable.mockReturnValue(false);
    });

    it('reads plaintext, which is all the app could have written', () => {
      expect(decryptCredential(Buffer.from('plain-user', 'utf-8'))).toBe('plain-user');
    });

    /**
     * The realistic case: a profile encrypted on a machine that HAD a keyring,
     * opened on one that does not. Returning the ciphertext as text would send
     * binary to the provider; the user needs to be told to re-enter it.
     */
    it('throws on a blob that was encrypted elsewhere', () => {
      const ciphertext = Buffer.from([0xde, 0xad, 0x00, 0xbe, 0xef]);
      expect(() => decryptCredential(ciphertext)).toThrow(/could not be decrypted/i);
    });
  });
});
