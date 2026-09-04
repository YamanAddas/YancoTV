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
 * Decryption keeps a legacy plaintext fallback so previously-stored data (from
 * before this hardening) remains readable — but only when the bytes actually
 * look like text. A blob that fails to decrypt and is NOT text is an error, not
 * a credential; returning the raw bytes used to turn a storage fault into a
 * garbage username sent to the provider.
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

/**
 * Could this buffer plausibly BE a legacy plaintext credential?
 *
 * The legacy fallback exists for rows written before encryption was enforced,
 * and those hold ordinary text: a username, a password, a MAC address. An
 * encrypted blob that fails to decrypt — because it is corrupt, or was written
 * by a different OS keyring, or was substituted — is binary, and returning it
 * as a "credential" hands the caller garbage that then travels into a provider
 * URL. The failure surfaces as a baffling auth error rather than the storage
 * problem it actually is.
 *
 * So the fallback is gated on the bytes looking like text: decodable as UTF-8
 * without loss, non-empty, and free of control characters. That keeps every
 * genuine legacy row readable while refusing to invent a credential out of
 * binary.
 */
function looksLikeLegacyPlaintext(buf: Buffer): boolean {
  if (buf.length === 0) return false;
  const text = buf.toString('utf-8');

  // Re-encoding a genuine UTF-8 string is byte-identical. Invalid bytes decode
  // to U+FFFD, which re-encodes to three bytes and changes the length, so this
  // single check covers 'not valid UTF-8' without having to look for the
  // replacement character itself.
  if (Buffer.byteLength(text, 'utf-8') !== buf.length) return false;

  // Credentials are printable. Control characters (tab, CR and LF included) do
  // not appear in a username, password or MAC address, but appear all over a
  // binary ciphertext. Compared by code point rather than a regex: a
  // control-character class is easy to mangle and impossible to read back.
  for (const ch of text) {
    const code = ch.codePointAt(0) ?? 0;
    if (code < 0x20 || code === 0x7f) return false;
  }
  return true;
}

export function decryptCredential(encrypted: Buffer): string {
  // Try real decryption first.
  if (safeStorage.isEncryptionAvailable()) {
    try {
      return safeStorage.decryptString(encrypted);
    } catch (err) {
      if (looksLikeLegacyPlaintext(encrypted)) {
        // Pre-hardening row. Readable, and expected on an upgraded install.
        log.warn(
          'safeStorage decryption failed; the value is plaintext, so this is a ' +
            'legacy row from before credential encryption was enforced.',
          err instanceof Error ? err.message : err,
        );
        return encrypted.toString('utf-8');
      }
      // Not text. Previously this returned the raw bytes as a string, so a
      // corrupted or foreign blob became a credential and was sent to the
      // provider. Fail loudly instead — the caller cannot do anything useful
      // with garbage, and the user needs to re-enter the credential.
      log.error(
        'Credential could not be decrypted and is not legacy plaintext. The ' +
          'stored value is unreadable on this system — re-enter it in Settings.',
        err instanceof Error ? err.message : err,
      );
      throw new Error('Stored credential could not be decrypted.');
    }
  }

  // No keyring at all. Everything on disk must be plaintext for the app to
  // have written it, so the same text check applies: a binary blob here means
  // the row was encrypted on a machine that HAD a keyring and this one cannot
  // read it.
  if (!looksLikeLegacyPlaintext(encrypted)) {
    log.error(
      'Credential is encrypted but this system has no keyring to decrypt it. ' +
        'Install your OS keyring (libsecret on Linux) or re-enter the credential.',
    );
    throw new Error('Stored credential could not be decrypted.');
  }

  if (!warnedUnavailable) {
    log.warn(
      'Credential decryption: safeStorage unavailable, decoding as plaintext.',
    );
    warnedUnavailable = true;
  }
  return encrypted.toString('utf-8');
}
