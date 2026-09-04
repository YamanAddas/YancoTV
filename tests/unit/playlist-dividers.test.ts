import { describe, it, expect } from 'vitest';
import { isPlaylistDivider } from '@yancotv/core';

/**
 * The rule is deliberately strict, and the tests exist to keep it that way.
 *
 * A looser rule starts eating real channels, which is a far worse failure than
 * leaving a banner in the list: a missing channel is invisible, while a banner
 * is merely annoying. Every "does NOT match" case below is a title a person
 * would actually want to watch.
 *
 * Kept in step with `PlaylistDividers.kt` — the same rule ships on Android and
 * iOS, and a change to what counts as a banner must not come to mean two
 * different things on two platforms.
 */
describe('isPlaylistDivider', () => {
  describe('matches real banners', () => {
    // Every one of these is from the owner's own catalogue.
    it.each([
      '##### 4K ᵁᴴᴰ ³⁸⁴⁰ᴾ #####',
      '###### RELAX ᵁᴴᴰ 3840P ######',
      '### ARABIC 24/7 4K UHD 3840P ###',
      '### beIN SP⚽RTS AFC 8K ###',
      '=== SPORTS ===',
      '--- MOVIES ---',
      '***  KIDS  ***',
    ])('%s', (title) => {
      expect(isPlaylistDivider(title)).toBe(true);
    });

    it('matches a row that is nothing but the marker', () => {
      expect(isPlaylistDivider('######')).toBe(true);
      expect(isPlaylistDivider('=========')).toBe(true);
    });

    it('ignores surrounding whitespace', () => {
      expect(isPlaylistDivider('   ### NEWS ###   ')).toBe(true);
    });
  });

  describe('does NOT match real channel names', () => {
    /**
     * The load-bearing half. A false positive here deletes a channel from the
     * user's library with no message and no way to find out why.
     */
    it.each([
      'beIN SPORTS 1 HD',
      'Ping-Pong -- Live',      // has a run, but not at both ends
      '24/7 SINGER',            // digits and a slash, no marker run
      '- MBC 1',                // one leading dash is not a heading
      'MBC 1 -',                // one trailing dash either
      'AR | BEIN SPORTS',       // pipe separator, not a run
      'C++ Programming TV',     // internal run, not at the ends
      '2001: A Space Odyssey',
      '*',                      // too short to be a run
      '**',
      '##',                     // two is below the three-character minimum
      '',
    ])('%s', (title) => {
      expect(isPlaylistDivider(title)).toBe(false);
    });

    /**
     * Both ends must use the SAME character. A title that happens to start with
     * one marker and end with another is not a banner — and this is the case a
     * naive "starts or ends with punctuation" rule gets wrong.
     */
    it('requires the same marker at both ends', () => {
      expect(isPlaylistDivider('### NEWS ===')).toBe(false);
      expect(isPlaylistDivider('--- NEWS ***')).toBe(false);
    });

    it('requires three or more at BOTH ends, not just one', () => {
      expect(isPlaylistDivider('### NEWS #')).toBe(false);
      expect(isPlaylistDivider('# NEWS ###')).toBe(false);
    });
  });

  it('survives null and undefined rather than throwing', () => {
    // Provider data is not validated by anyone before it reaches this.
    expect(isPlaylistDivider(undefined as unknown as string)).toBe(false);
    expect(isPlaylistDivider(null as unknown as string)).toBe(false);
  });
});
