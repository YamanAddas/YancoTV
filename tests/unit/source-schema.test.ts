import { describe, it, expect } from 'vitest';
import { addSourceInputSchema } from '../../src/shared/schemas/source';

describe('Source Schema Validation', () => {
  describe('M3U URL sources', () => {
    it('accepts valid M3U URL input', () => {
      const result = addSourceInputSchema.safeParse({
        name: 'My Playlist',
        type: 'm3u_url',
        url: 'https://example.com/playlist.m3u',
      });
      expect(result.success).toBe(true);
    });

    it('rejects M3U URL without url field', () => {
      const result = addSourceInputSchema.safeParse({
        name: 'My Playlist',
        type: 'm3u_url',
      });
      expect(result.success).toBe(false);
    });

    it('rejects M3U URL with invalid url', () => {
      const result = addSourceInputSchema.safeParse({
        name: 'My Playlist',
        type: 'm3u_url',
        url: 'not-a-url',
      });
      expect(result.success).toBe(false);
    });
  });

  describe('M3U file sources', () => {
    it('accepts valid M3U file input', () => {
      const result = addSourceInputSchema.safeParse({
        name: 'Local Playlist',
        type: 'm3u_file',
        filePath: 'C:\\Users\\test\\playlist.m3u',
      });
      expect(result.success).toBe(true);
    });

    it('rejects M3U file without filePath', () => {
      const result = addSourceInputSchema.safeParse({
        name: 'Local Playlist',
        type: 'm3u_file',
      });
      expect(result.success).toBe(false);
    });
  });

  describe('Xtream sources', () => {
    it('accepts valid Xtream input', () => {
      const result = addSourceInputSchema.safeParse({
        name: 'My Xtream',
        type: 'xtream',
        url: 'http://provider.com:8080',
        username: 'user1',
        password: 'pass1',
      });
      expect(result.success).toBe(true);
    });

    it('rejects Xtream without url', () => {
      const result = addSourceInputSchema.safeParse({
        name: 'My Xtream',
        type: 'xtream',
        username: 'user1',
        password: 'pass1',
      });
      expect(result.success).toBe(false);
    });

    it('rejects Xtream without username', () => {
      const result = addSourceInputSchema.safeParse({
        name: 'My Xtream',
        type: 'xtream',
        url: 'http://provider.com:8080',
        password: 'pass1',
      });
      expect(result.success).toBe(false);
    });

    it('rejects Xtream without password', () => {
      const result = addSourceInputSchema.safeParse({
        name: 'My Xtream',
        type: 'xtream',
        url: 'http://provider.com:8080',
        username: 'user1',
      });
      expect(result.success).toBe(false);
    });
  });

  describe('common validation', () => {
    it('rejects empty name', () => {
      const result = addSourceInputSchema.safeParse({
        name: '',
        type: 'm3u_url',
        url: 'https://example.com/playlist.m3u',
      });
      expect(result.success).toBe(false);
    });

    it('rejects name over 100 characters', () => {
      const result = addSourceInputSchema.safeParse({
        name: 'a'.repeat(101),
        type: 'm3u_url',
        url: 'https://example.com/playlist.m3u',
      });
      expect(result.success).toBe(false);
    });

    it('rejects invalid source type', () => {
      const result = addSourceInputSchema.safeParse({
        name: 'Test',
        type: 'invalid_type',
        url: 'https://example.com',
      });
      expect(result.success).toBe(false);
    });

    it('rejects missing type', () => {
      const result = addSourceInputSchema.safeParse({
        name: 'Test',
      });
      expect(result.success).toBe(false);
    });

    it('rejects completely empty input', () => {
      const result = addSourceInputSchema.safeParse({});
      expect(result.success).toBe(false);
    });

    it('rejects non-object input', () => {
      const result = addSourceInputSchema.safeParse('not an object');
      expect(result.success).toBe(false);
    });

    it('ignores extra fields', () => {
      const result = addSourceInputSchema.safeParse({
        name: 'Test',
        type: 'm3u_url',
        url: 'https://example.com/playlist.m3u',
        extraField: 'ignored',
      });
      expect(result.success).toBe(true);
    });
  });
});
