import { describe, it, expect } from 'vitest';
import { detectStreamType, getVideoErrorMessage, isVodUrl, hasUnsupportedExtension, replaceStreamExtension, buildXtreamHlsUrl } from '../../src/renderer/components/player/player-utils';

describe('detectStreamType', () => {
  describe('HLS detection', () => {
    it('detects .m3u8 as HLS', () => {
      expect(detectStreamType('http://server.com/stream.m3u8')).toBe('hls');
    });

    it('detects .m3u8 with query params as HLS', () => {
      expect(detectStreamType('http://server.com/stream.m3u8?token=abc')).toBe('hls');
    });

    it('does not match .m3u8 mid-path (only as final extension)', () => {
      // URL like /output.m3u8/segment.ts — the actual resource is .ts, not .m3u8
      expect(detectStreamType('http://server.com/hls/output.m3u8/segment.ts')).toBe('mpegts');
    });

    it('detects .M3U8 case-insensitive as HLS', () => {
      expect(detectStreamType('http://server.com/stream.M3U8')).toBe('hls');
    });
  });

  describe('native video detection', () => {
    it('detects .mp4 as native', () => {
      expect(detectStreamType('http://server.com/movie/user/pass/123.mp4')).toBe('native');
    });

    it('detects .webm as native', () => {
      expect(detectStreamType('http://server.com/video.webm')).toBe('native');
    });

    it('detects .MP4 case-insensitive as native', () => {
      expect(detectStreamType('http://server.com/movie/user/pass/123.MP4')).toBe('native');
    });

    it('ignores query params when detecting native', () => {
      expect(detectStreamType('http://server.com/movie.mp4?output=ts')).toBe('native');
    });
  });

  describe('unsupported-native detection (routed to mpegts)', () => {
    it('routes .mkv through mpegts (not natively supported in Chromium)', () => {
      expect(detectStreamType('http://server.com/movie/user/pass/456.mkv')).toBe('mpegts');
    });

    it('routes .avi through mpegts', () => {
      expect(detectStreamType('http://server.com/movie/user/pass/789.avi')).toBe('mpegts');
    });

    it('routes .mov through mpegts', () => {
      expect(detectStreamType('http://server.com/video.mov')).toBe('mpegts');
    });
  });

  describe('MPEG-TS detection (default)', () => {
    it('detects .ts as mpegts', () => {
      expect(detectStreamType('http://server.com/live/user/pass/123.ts')).toBe('mpegts');
    });

    it('detects .flv as mpegts', () => {
      expect(detectStreamType('http://server.com/live/stream.flv')).toBe('mpegts');
    });

    it('defaults to mpegts for extensionless URLs', () => {
      expect(detectStreamType('http://server.com/live/user/pass/123')).toBe('mpegts');
    });

    it('defaults to mpegts for unknown extensions', () => {
      expect(detectStreamType('http://server.com/stream.xyz')).toBe('mpegts');
    });

    it('defaults to mpegts for URL with trailing dot (empty extension)', () => {
      expect(detectStreamType('http://server.com/movie/user/pass/123.')).toBe('mpegts');
    });
  });

  describe('Xtream URL patterns', () => {
    it('live Xtream URL (.ts) → mpegts', () => {
      expect(detectStreamType('http://provider.com/live/user1/pass1/12345.ts')).toBe('mpegts');
    });

    it('movie Xtream URL (.mp4) → native', () => {
      expect(detectStreamType('http://provider.com/movie/user1/pass1/67890.mp4')).toBe('native');
    });

    it('movie Xtream URL (.mkv) → mpegts (unsupported native)', () => {
      expect(detectStreamType('http://provider.com/movie/user1/pass1/67890.mkv')).toBe('mpegts');
    });

    it('series Xtream URL (.mp4) → native', () => {
      expect(detectStreamType('http://provider.com/series/user1/pass1/11111.mp4')).toBe('native');
    });

    it('series Xtream URL (.mkv) → mpegts (unsupported native)', () => {
      expect(detectStreamType('http://provider.com/series/user1/pass1/11111.mkv')).toBe('mpegts');
    });
  });

  describe('M3U playlist URL patterns', () => {
    it('handles direct channel URL without extension', () => {
      expect(detectStreamType('http://iptv.example.com:8080/live/user/pass/12345')).toBe('mpegts');
    });

    it('handles channel URL with .ts extension', () => {
      expect(detectStreamType('http://iptv.example.com:8080/stream/channelid/12345.ts')).toBe('mpegts');
    });

    it('handles HLS playlist URL', () => {
      expect(detectStreamType('http://iptv.example.com/playlist/channel.m3u8')).toBe('hls');
    });
  });
});

describe('hasUnsupportedExtension', () => {
  it('returns true for .mkv', () => {
    expect(hasUnsupportedExtension('http://server.com/movie/user/pass/123.mkv')).toBe(true);
  });

  it('returns true for .avi', () => {
    expect(hasUnsupportedExtension('http://server.com/movie/user/pass/123.avi')).toBe(true);
  });

  it('returns true for .mov', () => {
    expect(hasUnsupportedExtension('http://server.com/video.mov')).toBe(true);
  });

  it('returns false for .mp4', () => {
    expect(hasUnsupportedExtension('http://server.com/movie/user/pass/123.mp4')).toBe(false);
  });

  it('returns false for .ts', () => {
    expect(hasUnsupportedExtension('http://server.com/live/user/pass/123.ts')).toBe(false);
  });

  it('returns false for extensionless URLs', () => {
    expect(hasUnsupportedExtension('http://server.com/live/user/pass/123')).toBe(false);
  });

  it('ignores query params', () => {
    expect(hasUnsupportedExtension('http://server.com/movie.mkv?token=abc')).toBe(true);
  });
});

describe('isVodUrl', () => {
  it('detects Xtream movie URLs', () => {
    expect(isVodUrl('http://provider.com/movie/user/pass/12345.mp4')).toBe(true);
  });

  it('detects Xtream series URLs', () => {
    expect(isVodUrl('http://provider.com/series/user/pass/67890.mp4')).toBe(true);
  });

  it('does not flag live URLs', () => {
    expect(isVodUrl('http://provider.com/live/user/pass/12345.ts')).toBe(false);
  });

  it('does not flag generic M3U URLs', () => {
    expect(isVodUrl('http://iptv.example.com/stream/channel.ts')).toBe(false);
  });

  it('is case-insensitive', () => {
    expect(isVodUrl('http://provider.com/Movie/user/pass/123.mp4')).toBe(true);
    expect(isVodUrl('http://provider.com/SERIES/user/pass/456.mkv')).toBe(true);
  });
});

describe('replaceStreamExtension', () => {
  it('replaces .mp4 with .ts', () => {
    expect(replaceStreamExtension('http://server/movie/user/pass/123.mp4', 'ts')).toBe(
      'http://server/movie/user/pass/123.ts',
    );
  });

  it('replaces .mkv with .m3u8', () => {
    expect(replaceStreamExtension('http://server/movie/user/pass/456.mkv', 'm3u8')).toBe(
      'http://server/movie/user/pass/456.m3u8',
    );
  });

  it('preserves query parameters', () => {
    expect(replaceStreamExtension('http://server/movie/user/pass/789.mp4?token=abc', 'ts')).toBe(
      'http://server/movie/user/pass/789.ts?token=abc',
    );
  });

  it('adds extension when URL has none', () => {
    expect(replaceStreamExtension('http://server/movie/user/pass/123', 'ts')).toBe(
      'http://server/movie/user/pass/123.ts',
    );
  });

  it('replaces only the last extension', () => {
    expect(replaceStreamExtension('http://server.com/movie/user/pass/123.mp4', 'ts')).toBe(
      'http://server.com/movie/user/pass/123.ts',
    );
  });

  it('handles series URLs', () => {
    expect(replaceStreamExtension('http://server/series/user/pass/999.mp4', 'm3u8')).toBe(
      'http://server/series/user/pass/999.m3u8',
    );
  });
});

describe('buildXtreamHlsUrl', () => {
  it('returns alternative HLS URL for Xtream movie pattern', () => {
    expect(buildXtreamHlsUrl('http://provider.com/movie/user1/pass1/67890.mp4')).toBe(
      'http://provider.com/movie/user1/pass1/67890/67890.m3u8',
    );
  });

  it('returns alternative HLS URL for Xtream series pattern', () => {
    expect(buildXtreamHlsUrl('http://provider.com/series/user1/pass1/11111.mkv')).toBe(
      'http://provider.com/series/user1/pass1/11111/11111.m3u8',
    );
  });

  it('returns null for non-Xtream URLs', () => {
    expect(buildXtreamHlsUrl('http://example.com/stream/channel.ts')).toBeNull();
  });

  it('returns null for live URLs', () => {
    expect(buildXtreamHlsUrl('http://provider.com/live/user1/pass1/12345.ts')).toBeNull();
  });

  it('preserves query parameters', () => {
    expect(buildXtreamHlsUrl('http://provider.com/movie/user1/pass1/67890.mp4?token=abc&quality=hd')).toBe(
      'http://provider.com/movie/user1/pass1/67890/67890.m3u8?token=abc&quality=hd',
    );
  });

  it('handles URL with no extension', () => {
    expect(buildXtreamHlsUrl('http://provider.com/movie/user1/pass1/67890')).toBe(
      'http://provider.com/movie/user1/pass1/67890/67890.m3u8',
    );
  });
});

describe('getVideoErrorMessage', () => {
  function mockVideo(code: number | null, message?: string): HTMLVideoElement {
    return {
      error: code !== null ? { code, message: message || '' } : null,
    } as unknown as HTMLVideoElement;
  }

  it('returns "Unknown playback error" when video.error is null', () => {
    expect(getVideoErrorMessage(mockVideo(null))).toBe('Unknown playback error');
  });

  it('returns aborted message for MEDIA_ERR_ABORTED (1)', () => {
    const msg = getVideoErrorMessage(mockVideo(1));
    expect(msg).toBe('Playback was aborted');
  });

  it('returns network message for MEDIA_ERR_NETWORK (2)', () => {
    const msg = getVideoErrorMessage(mockVideo(2));
    expect(msg).toContain('Network error');
  });

  it('returns decode message for MEDIA_ERR_DECODE (3)', () => {
    const msg = getVideoErrorMessage(mockVideo(3));
    expect(msg).toContain('Codec');
  });

  it('returns not-supported message for MEDIA_ERR_SRC_NOT_SUPPORTED (4)', () => {
    const msg = getVideoErrorMessage(mockVideo(4));
    expect(msg).toContain('unavailable');
  });

  it('returns error.message for unknown error codes', () => {
    const msg = getVideoErrorMessage(mockVideo(99, 'Custom error'));
    expect(msg).toBe('Custom error');
  });

  it('returns fallback with code for unknown code and no message', () => {
    const msg = getVideoErrorMessage(mockVideo(99));
    expect(msg).toContain('code 99');
  });
});
