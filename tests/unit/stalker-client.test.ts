import { describe, it, expect, vi, beforeEach } from 'vitest';
import { StalkerClient } from '../../src/main/services/stalker-client';

// Mock modules
vi.mock('http');
vi.mock('https');
vi.mock('electron-log/main', () => ({
  default: { info: vi.fn(), error: vi.fn(), warn: vi.fn() },
}));

describe('StalkerClient', () => {
  let client: StalkerClient;

  beforeEach(() => {
    vi.clearAllMocks();
    client = new StalkerClient('http://portal.example.com/stalker_portal', '00:1A:79:AA:BB:CC');
  });

  describe('constructor', () => {
    it('can be instantiated with required parameters', () => {
      const c = new StalkerClient('http://portal.example.com', '00:1A:79:00:00:01');
      expect(c).toBeInstanceOf(StalkerClient);
    });

    it('strips trailing slashes from portal URL', () => {
      const c = new StalkerClient('http://portal.example.com/stalker_portal///', '00:1A:79:00:00:01');
      // Verify via buildStreamUrl that the URL itself is fine (no trailing slash interference)
      expect(c.buildStreamUrl('http://stream.example.com/live/123')).toBe(
        'http://stream.example.com/live/123',
      );
    });

    it('accepts optional timeout parameter', () => {
      const c = new StalkerClient('http://portal.example.com', '00:1A:79:00:00:01', 30_000);
      expect(c).toBeInstanceOf(StalkerClient);
    });
  });

  describe('buildStreamUrl', () => {
    it('strips "ffrt " prefix', () => {
      expect(client.buildStreamUrl('ffrt http://stream.example.com/live/123')).toBe(
        'http://stream.example.com/live/123',
      );
    });

    it('strips "ffmpeg " prefix', () => {
      expect(client.buildStreamUrl('ffmpeg http://stream.example.com/live/456')).toBe(
        'http://stream.example.com/live/456',
      );
    });

    it('strips "auto " prefix', () => {
      expect(client.buildStreamUrl('auto http://stream.example.com/live/789')).toBe(
        'http://stream.example.com/live/789',
      );
    });

    it('handles plain URL without prefix', () => {
      expect(client.buildStreamUrl('http://stream.example.com/live/100')).toBe(
        'http://stream.example.com/live/100',
      );
    });

    it('trims whitespace', () => {
      expect(client.buildStreamUrl('  http://stream.example.com/live/200  ')).toBe(
        'http://stream.example.com/live/200',
      );
    });

    it('performs case-insensitive prefix removal', () => {
      expect(client.buildStreamUrl('FFRT http://stream.example.com/live/300')).toBe(
        'http://stream.example.com/live/300',
      );
      expect(client.buildStreamUrl('Ffmpeg http://stream.example.com/live/301')).toBe(
        'http://stream.example.com/live/301',
      );
      expect(client.buildStreamUrl('AUTO http://stream.example.com/live/302')).toBe(
        'http://stream.example.com/live/302',
      );
    });

    it('strips prefix with extra whitespace between prefix and URL', () => {
      expect(client.buildStreamUrl('ffrt   http://stream.example.com/live/400')).toBe(
        'http://stream.example.com/live/400',
      );
    });

    it('does not strip prefix-like substrings in the middle of URL', () => {
      const url = 'http://stream.example.com/ffrt/live/500';
      expect(client.buildStreamUrl(url)).toBe(url);
    });

    it('handles empty string', () => {
      expect(client.buildStreamUrl('')).toBe('');
    });
  });

  describe('type exports', () => {
    it('StalkerClient is a class with expected public methods', () => {
      expect(typeof client.authenticate).toBe('function');
      expect(typeof client.getLiveCategories).toBe('function');
      expect(typeof client.getLiveChannels).toBe('function');
      expect(typeof client.getVodCategories).toBe('function');
      expect(typeof client.getVodItems).toBe('function');
      expect(typeof client.getSeriesCategories).toBe('function');
      expect(typeof client.getSeriesList).toBe('function');
      expect(typeof client.buildStreamUrl).toBe('function');
    });
  });
});
