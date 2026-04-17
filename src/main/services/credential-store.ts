import { safeStorage } from 'electron';
import log from 'electron-log/main';

/**
 * Wraps Electron's safeStorage for storing IPTV provider credentials.
 *
 * Security model: when the OS keyring is unavailable (e.g. headless Linux
 * without libsecret, or Windows without DPAPI), encryption is impossible.
 * We refuse to store plaintext credentials by default — the caller must
 * explicitly opt in via the YANCOTV_ALLOW_PLAINTEXT_CREDENTIALS=1 env var
 * (used by tests). Otherwise we throw a clear error so the user can fix
 * their environment instead of silently leaking credentials.
 *
 * Decryption keeps a legacy plaintext fallback so previously-stored data
 * (from before this hardening) remains readable.
 */

let warnedUnavailable = false;

function plaintextOptIn(): boolean {
  return process.env.YANCOTV_ALLOW_PLAINTEXT_CREDENTIALS === '1';
}

export function encryptCredential(plaintext: string): Buffer {
  if (!safeStorage.isEncryptionAvailable()) {
    if (!plaintextOptIn()) {
      log.error(
        'Refusing to store credential: OS keyring unavailable. ' +
          'On Linux install libsecret-1-0; on Windows ensure DPAPI is functional. ' +
          'Set YANCOTV_ALLOW_PLAINTEXT_CREDENTIALS=1 to override (UNSAFE).',
      );
      throw new Error(
        'Credential encryption unavailable on this system. ' +
          'Install your OS keyring (libsecret on Linux) or set ' +
          'YANCOTV_ALLOW_PLAINTEXT_CREDENTIALS=1 to store unencrypted (not recommended).',
      );
    }
    if (!warnedUnavailable) {
      log.warn(
        'Credential encryption unavailable — storing in plaintext (opt-in via env var).',
      );
      warnedUnavailable = true;
    }
    return Buffer.from(plaintext, 'utf-8');
  }
  return safeStorage.encryptString(plaintext);
}

export function decryptCredential(encrypted: Buffer): string {
  // Try real decryption first.
  if (safeStorage.isEncryptionAvailable()) {
    try {
      return safeStorage.decryptString(encrypted);
    } catch (err) {
      // Buffer was likely stored as legacy plaintext (e.g. from a prior
      // install without keyring). Fall through to UTF-8 decode.
      log.warn(
        'safeStorage decryption failed; falling back to plaintext decode (legacy data).',
        err instanceof Error ? err.message : err,
      );
      return encrypted.toString('utf-8');
    }
  }

  if (!warnedUnavailable) {
    log.warn(
      'Credential decryption: safeStorage unavailable, decoding as plaintext.',
    );
    warnedUnavailable = true;
  }
  return encrypted.toString('utf-8');
}
