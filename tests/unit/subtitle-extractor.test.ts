import { describe, it, expect } from 'vitest';
import {
  parseSubtitleStreams,
  subtitleFilename,
} from '../../src/main/services/subtitle-extractor';

describe('parseSubtitleStreams', () => {
  it('returns an empty list when stderr has no subtitle streams', () => {
    const stderr = `
      Input #0, mov, from 'x.mp4':
        Duration: 01:30:00
        Stream #0:0: Video: h264
        Stream #0:1: Audio: aac
    `;
    expect(parseSubtitleStreams(stderr)).toEqual([]);
  });

  it('parses a single text subtitle stream with language', () => {
    const stderr = `    Stream #0:2(eng): Subtitle: subrip (default)`;
    const streams = parseSubtitleStreams(stderr);
    expect(streams).toHaveLength(1);
    expect(streams[0]).toMatchObject({
      subtitleIndex: 0,
      codec: 'subrip',
      language: 'eng',
      textBased: true,
    });
  });

  it('parses multiple streams and increments subtitle-only index', () => {
    const stderr = `
      Stream #0:0: Video: h264
      Stream #0:1: Audio: aac
      Stream #0:2(eng): Subtitle: subrip
      Stream #0:3(fre): Subtitle: mov_text
      Stream #0:4(spa): Subtitle: ass
    `;
    const streams = parseSubtitleStreams(stderr);
    expect(streams.map((s) => s.subtitleIndex)).toEqual([0, 1, 2]);
    expect(streams.map((s) => s.language)).toEqual(['eng', 'fre', 'spa']);
    expect(streams.every((s) => s.textBased)).toBe(true);
  });

  it('flags image-based subtitle codecs as non-text', () => {
    const stderr = `
      Stream #0:2(eng): Subtitle: hdmv_pgs_subtitle
      Stream #0:3(fre): Subtitle: dvdsub
    `;
    const streams = parseSubtitleStreams(stderr);
    expect(streams.every((s) => !s.textBased)).toBe(true);
  });

  it('uses "und" when no language tag is present', () => {
    const stderr = `    Stream #0:3: Subtitle: webvtt`;
    const streams = parseSubtitleStreams(stderr);
    expect(streams[0].language).toBe('und');
  });

  it('handles the stream-id-with-brackets variant', () => {
    const stderr = `    Stream #0:2[0x10](eng): Subtitle: subrip (default)`;
    const streams = parseSubtitleStreams(stderr);
    expect(streams).toHaveLength(1);
    expect(streams[0].language).toBe('eng');
  });
});

describe('subtitleFilename', () => {
  it('generates a simple {base}.{lang}.srt', () => {
    const used = new Set<string>();
    expect(subtitleFilename('movie', 'en', used)).toBe('movie.en.srt');
  });

  it('disambiguates when the same language appears twice', () => {
    const used = new Set<string>();
    expect(subtitleFilename('movie', 'en', used)).toBe('movie.en.srt');
    expect(subtitleFilename('movie', 'en', used)).toBe('movie.en.2.srt');
    expect(subtitleFilename('movie', 'en', used)).toBe('movie.en.3.srt');
  });

  it('falls back to "und" for non-iso-like tags', () => {
    const used = new Set<string>();
    expect(subtitleFilename('x', 'english (forced)', used)).toBe('x.und.srt');
  });

  it('lowercases the language tag', () => {
    const used = new Set<string>();
    expect(subtitleFilename('x', 'ENG', used)).toBe('x.eng.srt');
  });
});
