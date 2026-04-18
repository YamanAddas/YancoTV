import { describe, it, expect } from 'vitest';
import { gzip, deflate } from 'pako';
import {
  parseXmltv,
  decodeXmltvBytes,
} from '@yancotv/core';

const SAMPLE_XML = `<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <channel id="bbc1"><display-name>BBC 1</display-name></channel>
  <programme channel="bbc1" start="20260415120000 +0000" stop="20260415130000 +0000">
    <title>News</title>
    <desc>Daily news</desc>
  </programme>
</tv>`;

function toBytes(s: string): Uint8Array {
  return new TextEncoder().encode(s);
}

describe('parseXmltv / decodeXmltvBytes', () => {
  it('decodes a plain UTF-8 byte array', async () => {
    const out = await decodeXmltvBytes(toBytes(SAMPLE_XML));
    expect(out).toContain('<programme');
  });

  it('decodes a gzipped byte array', async () => {
    const compressed = gzip(SAMPLE_XML);
    const out = await decodeXmltvBytes(compressed);
    expect(out).toBe(SAMPLE_XML);
  });

  it('decodes a zlib-wrapped deflate byte array', async () => {
    const compressed = deflate(SAMPLE_XML);
    const out = await decodeXmltvBytes(compressed);
    expect(out).toBe(SAMPLE_XML);
  });

  it('falls back to UTF-8 when a 0x78-prefixed buffer is actually plain text', async () => {
    // "xml-starts-with-x..." has byte[0]===0x78 but is not a valid zlib stream.
    const plain = toBytes('xylophone <tv></tv>');
    const out = await decodeXmltvBytes(plain);
    expect(out).toBe('xylophone <tv></tv>');
  });

  it('parseXmltv accepts a gzipped byte array', async () => {
    const compressed = gzip(SAMPLE_XML);
    const result = await parseXmltv(compressed);
    expect(result.channels).toHaveLength(1);
    expect(result.channels[0].id).toBe('bbc1');
    expect(result.programmes).toHaveLength(1);
    expect(result.programmes[0].title).toBe('News');
  });

  it('parseXmltv accepts a plain string', async () => {
    const result = await parseXmltv(SAMPLE_XML);
    expect(result.programmes[0].description).toBe('Daily news');
  });
});
