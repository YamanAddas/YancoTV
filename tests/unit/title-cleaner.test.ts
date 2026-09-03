import { describe, it, expect } from 'vitest';
import {
  cleanTitle,
  extractYear,
  extractSeasonEpisode,
  extractShowName,
} from '../../src/main/services/title-cleaner';

describe('Title Cleaner', () => {
  describe('cleanTitle', () => {
    it('strips quality tags', () => {
      expect(cleanTitle('CNN HD')).toBe('CNN');
      expect(cleanTitle('ESPN FHD')).toBe('ESPN');
      expect(cleanTitle('BBC UHD')).toBe('BBC');
      expect(cleanTitle('Channel 4K')).toBe('Channel');
      expect(cleanTitle('Movie 1080p')).toBe('Movie');
      expect(cleanTitle('Show 720p')).toBe('Show');
    });

    it('strips bracketed tags', () => {
      expect(cleanTitle('CNN [HD]')).toBe('CNN');
      expect(cleanTitle('Movie (MULTI)')).toBe('Movie');
      expect(cleanTitle('Channel {VIP}')).toBe('Channel');
      expect(cleanTitle('Show [NEW]')).toBe('Show');
    });

    it('strips country prefixes', () => {
      expect(cleanTitle('US: CNN')).toBe('CNN');
      expect(cleanTitle('UK | BBC One')).toBe('BBC One');
      expect(cleanTitle('FR - TF1')).toBe('TF1');
    });

    it('strips trailing country suffix', () => {
      expect(cleanTitle('Sky Sports | UK')).toBe('Sky Sports');
    });

    it('strips channel numbering', () => {
      expect(cleanTitle('001. CNN')).toBe('CNN');
      expect(cleanTitle('CH 042: Fox News')).toBe('Fox News');
      expect(cleanTitle('15. Discovery')).toBe('Discovery');
    });

    it('strips backup/server suffixes', () => {
      expect(cleanTitle('CNN - Backup')).toBe('CNN');
      expect(cleanTitle('ESPN | S2')).toBe('ESPN');
      expect(cleanTitle('Fox | Server 3')).toBe('Fox');
    });

    it('handles combined noise', () => {
      expect(cleanTitle('US: CNN HD [MULTI]')).toBe('CNN');
    });

    it('returns original if cleaning empties the title', () => {
      expect(cleanTitle('HD')).toBe('HD');
    });

    it('keeps the raw title when cleaning leaves only punctuation (MB-377)', () => {
      const raw = '(MX) (VIX 01) | (2098-12-31 08:00:01)';
      const cleaned = cleanTitle(raw);
      expect(/[\p{L}\p{N}]/u.test(cleaned)).toBe(true);
      expect(cleaned).toBe(raw);
    });

    it('trims whitespace', () => {
      expect(cleanTitle('  CNN  ')).toBe('CNN');
    });
  });

  describe('extractYear', () => {
    it('extracts year from parentheses', () => {
      expect(extractYear('The Matrix (1999)')).toBe(1999);
      expect(extractYear('Dune (2021)')).toBe(2021);
    });

    it('extracts trailing year', () => {
      expect(extractYear('Movie Title 2023')).toBe(2023);
    });

    it('returns null for no year', () => {
      expect(extractYear('CNN Live')).toBeNull();
    });

    it('rejects invalid years', () => {
      expect(extractYear('Channel (1800)')).toBeNull();
    });
  });

  describe('extractSeasonEpisode', () => {
    it('extracts S01E02 format', () => {
      expect(extractSeasonEpisode('Show S01E02')).toEqual({ season: 1, episode: 2 });
      expect(extractSeasonEpisode('Show S12E99')).toEqual({ season: 12, episode: 99 });
    });

    it('extracts case-insensitive', () => {
      expect(extractSeasonEpisode('show s03e04')).toEqual({ season: 3, episode: 4 });
    });

    it('extracts Season/Episode long format', () => {
      expect(extractSeasonEpisode('Show Season 2 Episode 5')).toEqual({
        season: 2,
        episode: 5,
      });
    });

    it('extracts episode-only', () => {
      expect(extractSeasonEpisode('Show E05')).toEqual({ season: 1, episode: 5 });
      expect(extractSeasonEpisode('Show Ep12')).toEqual({ season: 1, episode: 12 });
    });

    it('returns null for non-series', () => {
      expect(extractSeasonEpisode('CNN Live')).toBeNull();
      expect(extractSeasonEpisode('The Matrix (1999)')).toBeNull();
    });
  });

  describe('extractShowName', () => {
    it('strips S01E02 and everything after', () => {
      expect(extractShowName('Breaking Bad S01E02 Pilot')).toBe('Breaking Bad');
    });

    it('strips Season X and everything after', () => {
      expect(extractShowName('The Office Season 3 Episode 1')).toBe('The Office');
    });

    it('returns cleaned title for non-series', () => {
      expect(extractShowName('CNN Live')).toBe('CNN Live');
    });
  });

  // MB-391 — a separator that only existed to divide a stripped tag from
  // the name has nothing left to divide. 6,903 live channels on a real
  // account rendered as ": SKY SPORT..." before this.
  describe('orphaned separators', () => {
    it('drops a separator left behind by a strip', () => {
      expect(cleanTitle('4K: V SPORT UHD')).toBe('V SPORT');
      expect(cleanTitle('4K: ELEVEN SPORTS 1 UHD')).toBe('ELEVEN SPORTS 1');
      expect(cleanTitle('HD | BBC One')).toBe('BBC One');
      expect(cleanTitle('Arte - HD')).toBe('Arte');
      expect(cleanTitle('1080p / Rai Uno')).toBe('Rai Uno');
    });

    it('keeps punctuation that is part of the name', () => {
      expect(cleanTitle('Sky Sports: Main Event')).toBe('Sky Sports: Main Event');
      expect(cleanTitle('Canal+ HD')).toBe('Canal+');
      // A trailing dot is not a separator — '.' is excluded on purpose.
      expect(cleanTitle('M.A.S.H. HD')).toBe('M.A.S.H.');
    });

    it('still falls back when only separators remain', () => {
      // MB-377 holds: trimming must not turn a punctuation-only title into
      // an empty one.
      expect(cleanTitle('|')).toBe('|');
      expect(cleanTitle('- HD -')).toBe('- HD -');
    });
  });
});
