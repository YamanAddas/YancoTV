import { describe, it, expect } from 'vitest';
import {
  getPlaybackArgs,
  getLivePlaybackArgs,
  getVodPlaybackArgs,
  getSubtitleAppearanceArgs,
} from '../../src/main/player/mpv-args';

describe('mpv-args — playback', () => {
  it('chooses live args when isLive', () => {
    const args = getPlaybackArgs({ isLive: true });
    expect(args).toEqual(getLivePlaybackArgs());
  });

  it('chooses VOD args when not live', () => {
    const args = getPlaybackArgs({ isLive: false });
    expect(args).toEqual(getVodPlaybackArgs());
  });

  it('honors a custom live buffer size', () => {
    const args = getLivePlaybackArgs(60);
    const bytes = 60 * 2 * 1024 * 1024;
    expect(args).toContain(`--demuxer-max-bytes=${bytes}`);
    expect(args).toContain(`--demuxer-max-back-bytes=${bytes}`);
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
