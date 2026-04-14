import { safeStorage } from 'electron';
import log from 'electron-log/main';

export function encryptCredential(plaintext: string): Buffer {
  if (!safeStorage.isEncryptionAvailable()) {
    log.warn('safeStorage encryption not available, using basic encoding');
    return Buffer.from(plaintext, 'utf-8');
  }
  return safeStorage.encryptString(plaintext);
}

export function decryptCredential(encrypted: Buffer): string {
  if (!safeStorage.isEncryptionAvailable()) {
    log.warn('safeStorage encryption not available, using basic decoding');
    return encrypted.toString('utf-8');
  }
  return safeStorage.decryptString(encrypted);
}
