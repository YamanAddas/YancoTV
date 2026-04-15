import { describe, it, expect, vi } from 'vitest';

// Mock electron-log before importing the module
vi.mock('electron-log/main', () => ({
  default: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}));

import { parseXmltv, parseXmltvTimestamp } from '../../src/main/services/xmltv-parser';

describe('XMLTV Parser', () => {
  describe('parseXmltvTimestamp', () => {
    it('parses timestamp without offset (UTC)', () => {
      const ts = parseXmltvTimestamp('20260415120000');
      // 2026-04-15T12:00:00Z
      expect(ts).toBe(Math.floor(new Date('2026-04-15T12:00:00Z').getTime() / 1000));
    });

    it('parses timestamp with positive offset', () => {
      const ts = parseXmltvTimestamp('20260415120000 +0200');
      // 2026-04-15T12:00:00+02:00 = 2026-04-15T10:00:00Z
      expect(ts).toBe(Math.floor(new Date('2026-04-15T10:00:00Z').getTime() / 1000));
    });

    it('parses timestamp with negative offset', () => {
      const ts = parseXmltvTimestamp('20260415120000 -0500');
      // 2026-04-15T12:00:00-05:00 = 2026-04-15T17:00:00Z
      expect(ts).toBe(Math.floor(new Date('2026-04-15T17:00:00Z').getTime() / 1000));
    });

    it('handles timestamp with no spaces before offset', () => {
      const ts = parseXmltvTimestamp('20260101000000+0000');
      expect(ts).toBe(Math.floor(new Date('2026-01-01T00:00:00Z').getTime() / 1000));
    });

    it('returns 0 for empty string', () => {
      expect(parseXmltvTimestamp('')).toBe(0);
    });

    it('returns 0 for invalid format', () => {
      expect(parseXmltvTimestamp('not-a-timestamp')).toBe(0);
      expect(parseXmltvTimestamp('2026')).toBe(0);
      expect(parseXmltvTimestamp('20261301000000')).toBe(0); // month 13 → invalid date
    });

    it('trims whitespace', () => {
      const ts = parseXmltvTimestamp('  20260415120000 +0000  ');
      expect(ts).toBe(Math.floor(new Date('2026-04-15T12:00:00Z').getTime() / 1000));
    });
  });

  describe('parseXmltv', () => {
    it('parses channels from XMLTV', async () => {
      const xml = `<?xml version="1.0"?>
<tv>
  <channel id="bbc1">
    <display-name>BBC One</display-name>
    <icon src="http://logos.com/bbc1.png" />
  </channel>
  <channel id="itv1">
    <display-name>ITV</display-name>
  </channel>
</tv>`;

      const result = await parseXmltv(xml);
      expect(result.channels).toHaveLength(2);
      expect(result.channels[0]).toEqual({
        id: 'bbc1',
        displayName: 'BBC One',
        iconUrl: 'http://logos.com/bbc1.png',
      });
      expect(result.channels[1]).toEqual({
        id: 'itv1',
        displayName: 'ITV',
        iconUrl: undefined,
      });
    });

    it('parses programmes from XMLTV', async () => {
      const xml = `<?xml version="1.0"?>
<tv>
  <programme start="20260415180000 +0000" stop="20260415190000 +0000" channel="bbc1">
    <title>Evening News</title>
    <desc>The latest headlines.</desc>
    <category>News</category>
    <icon src="http://img.com/news.png" />
  </programme>
  <programme start="20260415190000 +0000" stop="20260415200000 +0000" channel="bbc1">
    <title>Drama Show</title>
  </programme>
</tv>`;

      const result = await parseXmltv(xml);
      expect(result.programmes).toHaveLength(2);

      const p1 = result.programmes[0];
      expect(p1.channelId).toBe('bbc1');
      expect(p1.title).toBe('Evening News');
      expect(p1.description).toBe('The latest headlines.');
      expect(p1.category).toBe('News');
      expect(p1.iconUrl).toBe('http://img.com/news.png');
      expect(p1.startTime).toBe(parseXmltvTimestamp('20260415180000 +0000'));
      expect(p1.endTime).toBe(parseXmltvTimestamp('20260415190000 +0000'));

      const p2 = result.programmes[1];
      expect(p2.title).toBe('Drama Show');
      expect(p2.description).toBeUndefined();
      expect(p2.category).toBeUndefined();
    });

    it('skips programmes missing required attributes', async () => {
      const xml = `<tv>
  <programme start="20260415180000 +0000" channel="bbc1">
    <title>Missing stop attr</title>
  </programme>
  <programme start="20260415180000 +0000" stop="20260415190000 +0000" channel="bbc1">
    <title>Valid</title>
  </programme>
</tv>`;

      const result = await parseXmltv(xml);
      expect(result.programmes).toHaveLength(1);
      expect(result.programmes[0].title).toBe('Valid');
    });

    it('skips programmes with no title', async () => {
      const xml = `<tv>
  <programme start="20260415180000 +0000" stop="20260415190000 +0000" channel="bbc1">
    <desc>No title element</desc>
  </programme>
</tv>`;

      const result = await parseXmltv(xml);
      expect(result.programmes).toHaveLength(0);
    });

    it('decodes XML entities in text', async () => {
      const xml = `<tv>
  <programme start="20260415180000 +0000" stop="20260415190000 +0000" channel="ch1">
    <title>Tom &amp; Jerry</title>
    <desc>It&apos;s a &quot;classic&quot;</desc>
  </programme>
</tv>`;

      const result = await parseXmltv(xml);
      expect(result.programmes[0].title).toBe('Tom & Jerry');
      expect(result.programmes[0].description).toBe('It\'s a "classic"');
    });

    it('handles empty XML gracefully', async () => {
      const result = await parseXmltv('<tv></tv>');
      expect(result.channels).toEqual([]);
      expect(result.programmes).toEqual([]);
    });

    it('handles plain UTF-8 buffer input', async () => {
      const xml = `<tv>
  <channel id="ch1"><display-name>Test</display-name></channel>
</tv>`;
      const buf = Buffer.from(xml, 'utf-8');
      const result = await parseXmltv(buf);
      expect(result.channels).toHaveLength(1);
      expect(result.channels[0].id).toBe('ch1');
    });

    it('handles localized title elements with lang attribute', async () => {
      const xml = `<tv>
  <programme start="20260415180000 +0000" stop="20260415190000 +0000" channel="ch1">
    <title lang="en">Hello World</title>
  </programme>
</tv>`;

      const result = await parseXmltv(xml);
      expect(result.programmes[0].title).toBe('Hello World');
    });

    it('handles numeric character references', async () => {
      const xml = `<tv>
  <programme start="20260415180000 +0000" stop="20260415190000 +0000" channel="ch1">
    <title>&#72;ello</title>
  </programme>
</tv>`;

      const result = await parseXmltv(xml);
      expect(result.programmes[0].title).toBe('Hello');
    });
  });
});
