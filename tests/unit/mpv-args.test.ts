import { describe, it, expect } from 'vitest';
import {
  getPlaybackArgs,
  getLivePlaybackArgs,
  getVodPlaybackArgs,
  getSubtitleAppearanceArgs,
  getNetworkArgs,
} from '../../src/main/player/mpv-args';

describe('mpv-args — playback', () => {
  it('chooses live args when isLive', () => {
    const args = getPlaybackArgs({ isLive: true });
    expect(args).toEqual(getLivePlaybackArgs({ isLive: true }));
  });

  it('chooses VOD args when not live', () => {
    const args = getPlaybackArgs({ isLive: false });
    expect(args).toEqual(getVodPlaybackArgs({ isLive: false }));
  });

  // Moved here from timeshift-service.test.ts, which exercised these through
  // `getTimeshiftMpvArgs` — a deprecated wrapper that only delegated to this
  // function. The assertions are about live playback args, so they belong with
  // the function that builds them.
  it('uses a 30-minute buffer by default', () => {
    const args = getLivePlaybackArgs({ isLive: true });
    const expectedBytes = 1800 * 2 * 1024 * 1024;
    expect(args).toContain(`--demuxer-max-bytes=${expectedBytes}`);
    expect(args).toContain(`--demuxer-max-back-bytes=${expectedBytes}`);
  });

  it('enables caching without the VOD-stuttering pause flag', () => {
    const args = getLivePlaybackArgs({ isLive: true });
    expect(args).toContain('--cache=yes');
    // Replaced the old `--cache-pause=no` (which caused VOD stutter) with
    // `--cache-pause-wait=1` — mpv briefly holds instead of stuttering when the
    // buffer drains, then drops frames to return to the live edge.
    expect(args).toContain('--cache-pause-wait=1');
    expect(args).toContain('--cache-pause-initial=yes');
  });

  it('hardens the transport with auto-reconnect for flaky IPTV servers', () => {
    const args = getLivePlaybackArgs({ isLive: true });
    const reconnect = args.find((a) => a.startsWith('--stream-lavf-o='));
    expect(reconnect).toBeDefined();
    expect(reconnect).toContain('reconnect=1');
  });

  it('honors a custom live buffer size', () => {
    const args = getLivePlaybackArgs({ isLive: true, liveBufferSeconds: 60 });
    const bytes = 60 * 2 * 1024 * 1024;
    expect(args).toContain(`--demuxer-max-bytes=${bytes}`);
    expect(args).toContain(`--demuxer-max-back-bytes=${bytes}`);
  });

  it('applies buffer preset readahead for live', () => {
    const args = getLivePlaybackArgs({ isLive: true, bufferPreset: 'low' });
    expect(args).toContain('--demuxer-readahead-secs=3');
  });

  it('applies buffer preset readahead for VOD', () => {
    const args = getVodPlaybackArgs({ isLive: false, bufferPreset: 'high' });
    expect(args).toContain('--demuxer-readahead-secs=60');
  });

  it('uses VOD default readahead when preset is auto or unset', () => {
    const args = getVodPlaybackArgs({ isLive: false, bufferPreset: 'auto' });
    expect(args).toContain('--demuxer-readahead-secs=30');
  });

  it('honors a custom network timeout', () => {
    const args = getPlaybackArgs({ isLive: false, networkTimeoutSecs: 45 });
    expect(args).toContain('--network-timeout=45');
  });

  it('falls back to default network timeout', () => {
    const args = getPlaybackArgs({ isLive: false });
    expect(args).toContain('--network-timeout=30');
  });
});

describe('mpv-args — network', () => {
  it('emits nothing when everything is empty', () => {
    expect(getNetworkArgs({})).toEqual([]);
  });

  it('emits --user-agent when provided', () => {
    expect(getNetworkArgs({ userAgent: 'MyAgent/1.0' })).toEqual([
      '--user-agent=MyAgent/1.0',
    ]);
  });

  it('trims and skips whitespace-only user agents', () => {
    expect(getNetworkArgs({ userAgent: '   ' })).toEqual([]);
  });

  it('builds http proxy url', () => {
    const args = getNetworkArgs({
      proxyEnabled: true,
      proxyType: 'http',
      proxyHost: 'proxy.example.com',
      proxyPort: '8080',
    });
    expect(args).toContain('--http-proxy=http://proxy.example.com:8080');
  });

  it('builds socks5 proxy url', () => {
    const args = getNetworkArgs({
      proxyEnabled: true,
      proxyType: 'socks5',
      proxyHost: '10.0.0.1',
      proxyPort: '1080',
    });
    expect(args).toContain('--http-proxy=socks5://10.0.0.1:1080');
  });

  it('rejects proxy config when disabled', () => {
    expect(
      getNetworkArgs({
        proxyEnabled: false,
        proxyType: 'http',
        proxyHost: 'host',
        proxyPort: '8080',
      }),
    ).toEqual([]);
  });

  it('rejects proxy with invalid type or missing port', () => {
    expect(
      getNetworkArgs({ proxyEnabled: true, proxyType: 'bogus', proxyHost: 'h', proxyPort: '80' }),
    ).toEqual([]);
    expect(
      getNetworkArgs({ proxyEnabled: true, proxyType: 'http', proxyHost: 'h', proxyPort: 'abc' }),
    ).toEqual([]);
    expect(
      getNetworkArgs({ proxyEnabled: true, proxyType: 'http', proxyHost: '', proxyPort: '80' }),
    ).toEqual([]);
  });

  it('emits IPv4 preference', () => {
    expect(getNetworkArgs({ preferIpv4: true })).toContain(
      '--stream-lavf-o-append=force_ipv4=1',
    );
  });
});

describe('mpv-args — subtitle appearance', () => {
  it('emits no args when every input is empty', () => {
    expect(getSubtitleAppearanceArgs({})).toEqual([]);
    expect(
      getSubtitleAppearanceArgs({ scale: '', color: '', backOpacity: '' }),
    ).toEqual([]);
    expect(
      getSubtitleAppearanceArgs({ scale: null, color: null, backOpacity: null }),
    ).toEqual([]);
  });

  it('emits --sub-scale for valid numeric scale', () => {
    expect(getSubtitleAppearanceArgs({ scale: '1.25' })).toContain('--sub-scale=1.25');
    expect(getSubtitleAppearanceArgs({ scale: '0.5' })).toContain('--sub-scale=0.5');
    expect(getSubtitleAppearanceArgs({ scale: '3' })).toContain('--sub-scale=3');
  });

  it('rejects scale outside [0.5, 3.0]', () => {
    expect(getSubtitleAppearanceArgs({ scale: '0.1' })).toEqual([]);
    expect(getSubtitleAppearanceArgs({ scale: '5.0' })).toEqual([]);
    expect(getSubtitleAppearanceArgs({ scale: 'garbage' })).toEqual([]);
  });

  it('emits --sub-color for well-formed hex', () => {
    expect(getSubtitleAppearanceArgs({ color: '#FFFFFF' })).toEqual([
      '--sub-color=#FFFFFF',
    ]);
    expect(getSubtitleAppearanceArgs({ color: '#abcdef' })).toEqual([
      '--sub-color=#abcdef',
    ]);
  });

  it('rejects malformed color values', () => {
    expect(getSubtitleAppearanceArgs({ color: 'red' })).toEqual([]);
    expect(getSubtitleAppearanceArgs({ color: '#FFF' })).toEqual([]);
    expect(getSubtitleAppearanceArgs({ color: '#GGGGGG' })).toEqual([]);
  });

  it('converts backOpacity percentage to inverted alpha hex', () => {
    // 100% opaque → mpv alpha 00 (fully opaque)
    expect(getSubtitleAppearanceArgs({ backOpacity: '100' })).toEqual([
      '--sub-back-color=#00000000',
    ]);
    // 0% → mpv alpha FF (fully transparent)
    expect(getSubtitleAppearanceArgs({ backOpacity: '0' })).toEqual([
      '--sub-back-color=#FF000000',
    ]);
    // 50% → alpha ~80 (mid)
    const args50 = getSubtitleAppearanceArgs({ backOpacity: '50' });
    expect(args50).toHaveLength(1);
    expect(args50[0]).toMatch(/^--sub-back-color=#80000000$/);
  });

  it('rejects backOpacity outside [0, 100]', () => {
    expect(getSubtitleAppearanceArgs({ backOpacity: '150' })).toEqual([]);
    expect(getSubtitleAppearanceArgs({ backOpacity: '-10' })).toEqual([]);
    expect(getSubtitleAppearanceArgs({ backOpacity: 'nope' })).toEqual([]);
  });

  it('combines all three settings when valid', () => {
    const args = getSubtitleAppearanceArgs({
      scale: '1.5',
      color: '#FFFF00',
      backOpacity: '75',
    });
    expect(args).toContain('--sub-scale=1.5');
    expect(args).toContain('--sub-color=#FFFF00');
    // 75% → alpha 0x40 (25% of 255 ≈ 64 = 0x40)
    expect(args).toContain('--sub-back-color=#40000000');
  });
});
