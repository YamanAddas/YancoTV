import { describe, it, expect } from 'vitest';
import { __testing } from '../../src/main/services/asset-fetcher';

const { extractProviderSubtitles, posterExt, subtitleExtFromUrl } = __testing;

describe('asset-fetcher — extractProviderSubtitles', () => {
  it('returns [] when metadata has no subtitles field', () => {
    expect(extractProviderSubtitles({})).toEqual([]);
  });

  it('parses an array of plain URLs (Xtream shorthand)', () => {
    const md = {
      subtitles: ['http://p/en.srt', 'http://p/fr.srt'],
    } as unknown as Parameters<typeof extractProviderSubtitles>[0];
    expect(extractProviderSubtitles(md)).toEqual([
      { url: 'http://p/en.srt' },
      { url: 'http://p/fr.srt' },
    ]);
  });

  it('parses an array of objects with url + language', () => {
    const md = {
      subtitles: [
        { url: 'http://p/en.srt', language: 'en' },
        { url: 'http://p/fr.srt', lang: 'fr' },
      ],
    } as unknown as Parameters<typeof extractProviderSubtitles>[0];
    const r = extractProviderSubtitles(md);
    expect(r).toEqual([
      { url: 'http://p/en.srt', lang: 'en' },
      { url: 'http://p/fr.srt', lang: 'fr' },
    ]);
  });

  it('accepts href/src as alternative URL keys', () => {
    const md = {
      subtitles: [{ href: 'http://p/a.srt' }, { src: 'http://p/b.srt' }],
    } as unknown as Parameters<typeof extractProviderSubtitles>[0];
    expect(extractProviderSubtitles(md).map((s) => s.url)).toEqual([
      'http://p/a.srt',
      'http://p/b.srt',
    ]);
  });

  it('skips entries with no URL', () => {
    const md = {
      subtitles: [{ language: 'en' }, null, { url: 'http://p/x.srt' }],
    } as unknown as Parameters<typeof extractProviderSubtitles>[0];
    expect(extractProviderSubtitles(md)).toEqual([{ url: 'http://p/x.srt' }]);
  });
});

describe('asset-fetcher — posterExt', () => {
  it('returns the URL extension when valid', () => {
    expect(posterExt('http://x/foo.png')).toBe('.png');
    expect(posterExt('http://x/foo.jpg')).toBe('.jpg');
    expect(posterExt('http://x/foo.WEBP')).toBe('.webp');
  });

  it('falls back to .jpg for unknown or missing extensions', () => {
    expect(posterExt('http://x/foo')).toBe('.jpg');
    expect(posterExt('http://x/foo.exe')).toBe('.jpg');
    expect(posterExt('not-a-url')).toBe('.jpg');
  });
});

describe('asset-fetcher — subtitleExtFromUrl', () => {
  it('keeps known subtitle extensions', () => {
    expect(subtitleExtFromUrl(new URL('http://x/a.srt'))).toBe('.srt');
    expect(subtitleExtFromUrl(new URL('http://x/a.vtt'))).toBe('.vtt');
    expect(subtitleExtFromUrl(new URL('http://x/a.ass'))).toBe('.ass');
  });

  it('falls back to .srt for unknown extensions', () => {
    expect(subtitleExtFromUrl(new URL('http://x/a.txt'))).toBe('.srt');
    expect(subtitleExtFromUrl(new URL('http://x/a'))).toBe('.srt');
  });
});
