import { describe, it, expect } from 'vitest';
import { parseM3u } from '../../src/main/services/m3u-parser';

describe('M3U Parser', () => {
  it('parses a basic M3U playlist', () => {
    const content = `#EXTM3U
#EXTINF:-1 tvg-id="ch1" tvg-name="Channel 1" tvg-logo="http://logo.com/1.png" group-title="News",Channel One HD
http://stream.example.com/ch1
#EXTINF:-1 tvg-id="ch2" tvg-name="Channel 2" tvg-logo="http://logo.com/2.png" group-title="Sports",ESPN Live
http://stream.example.com/ch2`;

    const entries = parseM3u(content);
    expect(entries).toHaveLength(2);

    expect(entries[0].title).toBe('Channel One HD');
    expect(entries[0].tvgId).toBe('ch1');
    expect(entries[0].tvgName).toBe('Channel 1');
    expect(entries[0].tvgLogo).toBe('http://logo.com/1.png');
    expect(entries[0].groupTitle).toBe('News');
    expect(entries[0].streamUrl).toBe('http://stream.example.com/ch1');
    expect(entries[0].duration).toBe(-1);

    expect(entries[1].title).toBe('ESPN Live');
    expect(entries[1].groupTitle).toBe('Sports');
  });

  it('handles BOM marker', () => {
    const content = '\uFEFF#EXTM3U\n#EXTINF:-1,Test Channel\nhttp://example.com/stream';
    const entries = parseM3u(content);
    expect(entries).toHaveLength(1);
    expect(entries[0].title).toBe('Test Channel');
  });

  it('handles Windows line endings (CRLF)', () => {
    const content = '#EXTM3U\r\n#EXTINF:-1,Channel A\r\nhttp://a.com/1\r\n#EXTINF:-1,Channel B\r\nhttp://b.com/2\r\n';
    const entries = parseM3u(content);
    expect(entries).toHaveLength(2);
    expect(entries[0].title).toBe('Channel A');
    expect(entries[1].title).toBe('Channel B');
  });

  it('handles old Mac line endings (CR only)', () => {
    const content = '#EXTM3U\r#EXTINF:-1,Channel X\rhttp://x.com/stream';
    const entries = parseM3u(content);
    expect(entries).toHaveLength(1);
    expect(entries[0].title).toBe('Channel X');
  });

  it('skips empty lines', () => {
    const content = `#EXTM3U

#EXTINF:-1,Channel 1

http://example.com/1

#EXTINF:-1,Channel 2

http://example.com/2
`;
    const entries = parseM3u(content);
    expect(entries).toHaveLength(2);
  });

  it('handles malformed entries — URL without EXTINF', () => {
    const content = `#EXTM3U
http://example.com/stream1
#EXTINF:-1,Proper Channel
http://example.com/stream2`;

    const entries = parseM3u(content);
    expect(entries).toHaveLength(2);
    // First entry has a generated title from URL
    expect(entries[0].streamUrl).toBe('http://example.com/stream1');
    // Second entry is properly parsed
    expect(entries[1].title).toBe('Proper Channel');
  });

  it('extracts duration correctly', () => {
    const content = `#EXTM3U
#EXTINF:3600,Movie Title
http://example.com/movie
#EXTINF:-1,Live Channel
http://example.com/live
#EXTINF:0,Unknown Duration
http://example.com/unknown`;

    const entries = parseM3u(content);
    expect(entries[0].duration).toBe(3600);
    expect(entries[1].duration).toBe(-1);
    expect(entries[2].duration).toBe(0);
  });

  it('handles EXTINF with no attributes — just duration and title', () => {
    const content = `#EXTM3U
#EXTINF:-1,Simple Channel
http://example.com/simple`;

    const entries = parseM3u(content);
    expect(entries).toHaveLength(1);
    expect(entries[0].title).toBe('Simple Channel');
    expect(entries[0].groupTitle).toBe('');
    expect(entries[0].tvgId).toBe('');
  });

  it('handles attributes with single quotes', () => {
    const content = `#EXTM3U
#EXTINF:-1 tvg-id='abc' group-title='Entertainment',Show Name
http://example.com/show`;

    const entries = parseM3u(content);
    expect(entries[0].tvgId).toBe('abc');
    expect(entries[0].groupTitle).toBe('Entertainment');
  });

  it('handles real-world provider titles with noise', () => {
    const content = `#EXTM3U
#EXTINF:-1 tvg-id="us.cnn" tvg-logo="http://cdn.logo/cnn.png" group-title="US | News",US: CNN HD [MULTI]
http://provider.com/live/us/cnn
#EXTINF:-1 tvg-id="" tvg-logo="" group-title="VOD | Movies",The Matrix (1999) [4K]
http://provider.com/movie/12345.mp4
#EXTINF:-1 tvg-id="" group-title="Series | Drama",Breaking Bad S01E01
http://provider.com/series/bb/s01e01.mp4`;

    const entries = parseM3u(content);
    expect(entries).toHaveLength(3);
    expect(entries[0].title).toBe('US: CNN HD [MULTI]');
    expect(entries[0].groupTitle).toBe('US | News');
    expect(entries[1].title).toBe('The Matrix (1999) [4K]');
    expect(entries[2].title).toBe('Breaking Bad S01E01');
  });

  it('handles empty playlist', () => {
    const content = '#EXTM3U\n';
    const entries = parseM3u(content);
    expect(entries).toHaveLength(0);
  });

  it('handles playlist with only #EXTM3U header', () => {
    const content = '#EXTM3U';
    const entries = parseM3u(content);
    expect(entries).toHaveLength(0);
  });

  it('skips other directives like #EXTVLCOPT', () => {
    const content = `#EXTM3U
#EXTINF:-1,Channel 1
#EXTVLCOPT:http-user-agent=Mozilla
#EXTVLCOPT:http-referrer=http://ref.com
http://example.com/ch1`;

    const entries = parseM3u(content);
    expect(entries).toHaveLength(1);
    expect(entries[0].title).toBe('Channel 1');
    expect(entries[0].streamUrl).toBe('http://example.com/ch1');
  });

  it('handles group-title with special characters', () => {
    const content = `#EXTM3U
#EXTINF:-1 group-title="US | News & Politics (HD)",Channel
http://example.com/ch`;

    const entries = parseM3u(content);
    expect(entries[0].groupTitle).toBe('US | News & Politics (HD)');
  });

  it('handles large number of entries efficiently', () => {
    const lines = ['#EXTM3U'];
    for (let i = 0; i < 10000; i++) {
      lines.push(`#EXTINF:-1 group-title="Group ${i % 10}",Channel ${i}`);
      lines.push(`http://example.com/ch${i}`);
    }
    const content = lines.join('\n');

    const start = Date.now();
    const entries = parseM3u(content);
    const elapsed = Date.now() - start;

    expect(entries).toHaveLength(10000);
    expect(elapsed).toBeLessThan(5000); // Should parse 10K entries in under 5 seconds
  });
});
