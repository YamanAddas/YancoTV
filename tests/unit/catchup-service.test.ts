import { describe, it, expect, vi } from 'vitest';

// Mock electron dependencies
vi.mock('electron-log/main', () => ({
  default: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}));
vi.mock('../../src/main/services/db', () => ({ getDb: vi.fn() }));
vi.mock('../../src/main/services/source-manager', () => ({
  getSourceCredentials: vi.fn(),
}));

import {
  buildXtreamTimeshiftUrl,
  buildM3uCatchupUrl,
} from '../../src/main/services/catchup-service';

describe('Catch-up Service — URL Builders', () => {
  describe('buildXtreamTimeshiftUrl', () => {
    it('builds a standard Xtream timeshift URL', () => {
      // 2026-04-15 14:30:00 UTC as Unix seconds
      const start = Math.floor(new Date('2026-04-15T14:30:00Z').getTime() / 1000);
      const duration = 3600; // 1 hour

      const url = buildXtreamTimeshiftUrl(
        'http://provider.com',
        'user1',
        'pass1',
        'http://provider.com/live/user1/pass1/12345.ts',
        start,
        duration,
      );

      expect(url).toBe(
        'http://provider.com/timeshift/user1/pass1/60/2026-04-15:14-30/12345.ts',
      );
    });

    it('extracts stream ID from URL', () => {
      const start = Math.floor(new Date('2026-01-01T00:00:00Z').getTime() / 1000);
      const url = buildXtreamTimeshiftUrl(
        'http://host.com',
        'u',
        'p',
        'http://host.com/live/u/p/99999.ts',
        start,
        1800, // 30 min
      );

      expect(url).toContain('/99999.ts');
      expect(url).toContain('/30/'); // 1800s → 30 minutes
    });

    it('defaults stream ID to 0 when URL has no numeric ID', () => {
      const start = Math.floor(new Date('2026-01-01T00:00:00Z').getTime() / 1000);
      const url = buildXtreamTimeshiftUrl(
        'http://host.com',
        'u',
        'p',
        'http://host.com/stream',
        start,
        600,
      );

      expect(url).toContain('/0.ts');
    });

    it('does not strip trailing slashes itself (caller responsibility)', () => {
      const start = Math.floor(new Date('2026-06-01T10:00:00Z').getTime() / 1000);
      const url = buildXtreamTimeshiftUrl(
        'http://host.com///',
        'u',
        'p',
        'http://host.com/live/u/p/1.ts',
        start,
        120,
      );

      // buildXtreamTimeshiftUrl just appends — getCatchupUrl strips slashes before calling
      expect(url).toMatch(/^http:\/\/host\.com\/\/\/\/timeshift\//);
    });

    it('rounds duration up to next minute', () => {
      const start = Math.floor(new Date('2026-01-01T00:00:00Z').getTime() / 1000);
      const url = buildXtreamTimeshiftUrl(
        'http://host.com',
        'u',
        'p',
        'http://host.com/live/u/p/1.ts',
        start,
        61, // 1 min 1 sec → should ceil to 2 min
      );

      expect(url).toContain('/2/'); // Math.ceil(61/60) = 2
    });
  });

  describe('buildM3uCatchupUrl', () => {
    const start = 1713189000; // some Unix timestamp
    const duration = 3600;

    it('returns null when no catchup metadata', () => {
      const url = buildM3uCatchupUrl('http://stream.com/ch1', {}, start, duration);
      expect(url).toBeNull();
    });

    it('builds append-style catchup URL', () => {
      const url = buildM3uCatchupUrl(
        'http://stream.com/ch1',
        { catchupType: 'append' },
        start,
        duration,
      );

      expect(url).toBe(
        `http://stream.com/ch1?utc=${start}&lutc=${start}&duration=${duration}`,
      );
    });

    it('builds shift-style catchup URL', () => {
      const now = Math.floor(Date.now() / 1000);
      const recentStart = now - 600; // 10 minutes ago

      const url = buildM3uCatchupUrl(
        'http://stream.com/ch1',
        { catchupType: 'shift' },
        recentStart,
        duration,
      );

      expect(url).toContain(`utc=${recentStart}`);
      expect(url).toContain(`lutc=${recentStart}`);
      expect(url).toContain('shift=');
    });

    it('replaces placeholders in catchup-source template', () => {
      const url = buildM3uCatchupUrl(
        'http://stream.com/live/1234.ts',
        {
          catchupSource:
            'http://archive.com/timeshift/{stream_id}/{start}/{duration}',
        },
        start,
        duration,
      );

      expect(url).toBe(
        `http://archive.com/timeshift/1234/${start}/${duration}`,
      );
    });

    it('replaces date component placeholders', () => {
      // 2026-04-15T14:30:45Z
      const ts = Math.floor(new Date('2026-04-15T14:30:45Z').getTime() / 1000);

      const url = buildM3uCatchupUrl(
        'http://stream.com/live/1.ts',
        {
          catchupSource:
            'http://archive.com/{Y}-{m}-{d}/{H}:{M}:{S}',
        },
        ts,
        3600,
      );

      expect(url).toBe('http://archive.com/2026-04-15/14:30:45');
    });

    it('replaces {end} placeholder', () => {
      const url = buildM3uCatchupUrl(
        'http://stream.com/live/1.ts',
        {
          catchupSource: 'http://archive.com/?start={start}&end={end}',
        },
        start,
        duration,
      );

      expect(url).toBe(
        `http://archive.com/?start=${start}&end=${start + duration}`,
      );
    });

    it('replaces {timestamp} and {utc} as aliases for {start}', () => {
      const url = buildM3uCatchupUrl(
        'http://stream.com/live/1.ts',
        {
          catchupSource: 'http://archive.com/?ts={timestamp}&u={utc}',
        },
        start,
        duration,
      );

      expect(url).toBe(
        `http://archive.com/?ts=${start}&u=${start}`,
      );
    });

    it('uses catchupSource template over original URL when both types are present', () => {
      const url = buildM3uCatchupUrl(
        'http://stream.com/ch1',
        {
          catchupType: 'append',
          catchupSource: 'http://archive.com/{start}',
        },
        start,
        duration,
      );

      // catchupSource is used as the template
      expect(url).toBe(`http://archive.com/${start}`);
    });
  });
});
