import { describe, it, expect } from 'vitest';
import { __testing } from '../../src/main/services/update-service';

const { compareVersions } = __testing;

describe('update-service compareVersions', () => {
  it('returns 0 for identical versions', () => {
    expect(compareVersions('0.1.0', '0.1.0')).toBe(0);
  });

  it('treats higher patch as greater', () => {
    expect(compareVersions('0.1.1', '0.1.0')).toBeGreaterThan(0);
    expect(compareVersions('0.1.0', '0.1.1')).toBeLessThan(0);
  });

  it('treats higher minor as greater than any patch', () => {
    expect(compareVersions('0.2.0', '0.1.99')).toBeGreaterThan(0);
  });

  it('treats higher major as greater than any minor', () => {
    expect(compareVersions('1.0.0', '0.99.99')).toBeGreaterThan(0);
  });

  it('pads missing components with 0', () => {
    expect(compareVersions('1', '1.0.0')).toBe(0);
    expect(compareVersions('1.2', '1.2.0')).toBe(0);
    expect(compareVersions('1.2', '1.2.1')).toBeLessThan(0);
  });

  it('ignores prerelease suffixes', () => {
    // The comparison strips anything after the first non-digit/dot.
    expect(compareVersions('1.2.3-beta.1', '1.2.3')).toBe(0);
    expect(compareVersions('1.3.0-rc.1', '1.2.9')).toBeGreaterThan(0);
  });

  it('handles malformed input gracefully', () => {
    expect(compareVersions('abc', '1.0.0')).toBeLessThan(0);
    expect(compareVersions('', '')).toBe(0);
  });
});
