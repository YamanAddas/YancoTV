import { describe, it, expect } from 'vitest';
import {
  buildMovieNfo,
  buildEpisodeNfo,
  buildTvShowNfo,
  splitList,
  __testing,
} from '../../src/main/services/nfo-writer';

describe('nfo-writer — buildMovieNfo', () => {
  it('emits a minimal valid document with only a title', () => {
    const xml = buildMovieNfo({ title: 'The Thing' });
    expect(xml).toMatch(/^<\?xml version="1.0" encoding="UTF-8" standalone="yes"\?>/);
    expect(xml).toContain('<movie>');
    expect(xml).toContain('<title>The Thing</title>');
    expect(xml).toContain('</movie>');
  });

  it('escapes XML-special characters in values', () => {
    const xml = buildMovieNfo({
      title: 'A & B <fun>',
      metadata: { plot: 'Quotes "here" and \'there\'', director: 'R&D' },
    });
    expect(xml).toContain('<title>A &amp; B &lt;fun&gt;</title>');
    expect(xml).toContain('Quotes &quot;here&quot; and &apos;there&apos;');
    expect(xml).toContain('<director>R&amp;D</director>');
  });

  it('omits fields that are missing (no empty tags)', () => {
    const xml = buildMovieNfo({ title: 'Bare' });
    expect(xml).not.toContain('<plot>');
    expect(xml).not.toContain('<year>');
    expect(xml).not.toContain('<director>');
  });

  it('extracts year from releaseDate', () => {
    const xml = buildMovieNfo({
      title: 'Old Film',
      metadata: { releaseDate: '1982-06-25' },
    });
    expect(xml).toContain('<year>1982</year>');
    expect(xml).toContain('<premiered>1982-06-25</premiered>');
  });

  it('splits comma-separated genres into individual tags', () => {
    const xml = buildMovieNfo({
      title: 'X',
      metadata: { genre: 'Sci-Fi, Horror / Thriller' },
    });
    expect(xml).toContain('<genre>Sci-Fi</genre>');
    expect(xml).toContain('<genre>Horror</genre>');
    expect(xml).toContain('<genre>Thriller</genre>');
  });

  it('emits an <actor> block for each cast member', () => {
    const xml = buildMovieNfo({
      title: 'X',
      metadata: { cast: 'Alice, Bob, Carol' },
    });
    const actors = xml.match(/<actor>/g);
    expect(actors?.length).toBe(3);
    expect(xml).toContain('<name>Alice</name>');
    expect(xml).toContain('<name>Bob</name>');
    expect(xml).toContain('<name>Carol</name>');
  });

  it('includes uniqueid when tmdbId is present', () => {
    const xml = buildMovieNfo({
      title: 'X',
      metadata: { tmdbId: 12345 },
    });
    expect(xml).toContain('<uniqueid type="tmdb">12345</uniqueid>');
  });

  it('falls back to description when plot is missing', () => {
    const xml = buildMovieNfo({
      title: 'X',
      metadata: { description: 'From Xtream VOD' },
    });
    expect(xml).toContain('<plot>From Xtream VOD</plot>');
  });
});

describe('nfo-writer — buildEpisodeNfo', () => {
  it('includes season/episode numbers', () => {
    const xml = buildEpisodeNfo({
      showTitle: 'Succession',
      episode: {
        id: 'ep1',
        contentId: 'c1',
        seasonNumber: 3,
        episodeNumber: 5,
        title: 'Retired Janitors',
        streamUrl: 'http://x',
      },
    });
    expect(xml).toContain('<episodedetails>');
    expect(xml).toContain('<showtitle>Succession</showtitle>');
    expect(xml).toContain('<season>3</season>');
    expect(xml).toContain('<episode>5</episode>');
    expect(xml).toContain('<title>Retired Janitors</title>');
  });

  it('synthesises a title from episode number when missing', () => {
    const xml = buildEpisodeNfo({
      showTitle: 'Show',
      episode: {
        id: 'ep',
        contentId: 'c',
        episodeNumber: 7,
        streamUrl: 'http://x',
      },
    });
    expect(xml).toContain('<title>Episode 7</title>');
  });

  it('converts duration seconds to minutes', () => {
    const xml = buildEpisodeNfo({
      showTitle: 'S',
      episode: {
        id: 'e',
        contentId: 'c',
        seasonNumber: 1,
        episodeNumber: 1,
        duration: 3600, // 60 min
        streamUrl: 'http://x',
      },
    });
    expect(xml).toContain('<runtime>60</runtime>');
  });
});

describe('nfo-writer — buildTvShowNfo', () => {
  it('uses the <tvshow> root', () => {
    const xml = buildTvShowNfo({ title: 'Show', metadata: { genre: 'Drama' } });
    expect(xml).toContain('<tvshow>');
    expect(xml).toContain('</tvshow>');
    expect(xml).toContain('<genre>Drama</genre>');
  });
});

describe('nfo-writer — helpers', () => {
  it('splitList splits on commas, slashes, and semicolons', () => {
    expect(splitList('a, b; c / d')).toEqual(['a', 'b', 'c', 'd']);
    expect(splitList('')).toEqual([]);
    expect(splitList(undefined)).toEqual([]);
  });

  it('parseYear finds the first 19xx/20xx in a date string', () => {
    expect(__testing.parseYear('1999-10-10')).toBe(1999);
    expect(__testing.parseYear('October 2021')).toBe(2021);
    expect(__testing.parseYear('')).toBeUndefined();
    expect(__testing.parseYear(undefined)).toBeUndefined();
  });
});
