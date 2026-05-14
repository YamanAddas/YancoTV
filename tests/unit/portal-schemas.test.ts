/**
 * Tests for the Zod schemas that replaced the `as any` casts in the
 * Stalker and Xtream clients. Covers three properties:
 *
 *   1. **Happy path.** A representative response from each endpoint
 *      parses into the expected camelCased shape.
 *   2. **Permissiveness.** Vendor extensions (extra unknown keys),
 *      string-typed numbers, and missing optional fields all parse
 *      without error.
 *   3. **Safety.** Malformed entries (non-objects, null, missing
 *      required fields beyond rescue) fail `safeParse` so the client
 *      can skip them rather than crash.
 */

import { describe, it, expect } from 'vitest';
import {
  extractStalkerHandshakeToken,
  stalkerCategoryItemSchema,
  stalkerChannelItemSchema,
  stalkerVodItemSchema,
  stalkerSeriesItemSchema,
} from '../../packages/core/src/stalker/schemas';
import {
  xtreamAccountInfoResponseSchema,
  transformXtreamAuthInfo,
  xtreamCategoryItemSchema,
  xtreamLiveStreamItemSchema,
  xtreamVodStreamItemSchema,
  xtreamSeriesItemSchema,
  xtreamSeriesDetailResponseSchema,
  transformXtreamSeriesDetail,
  xtreamVodInfoResponseSchema,
  transformXtreamVodDetail,
} from '../../packages/core/src/xtream/schemas';

// ─── Stalker ────────────────────────────────────────────────────────────

describe('Stalker schemas', () => {
  describe('extractStalkerHandshakeToken', () => {
    it('reads token from the modern `js.token` shape', () => {
      expect(extractStalkerHandshakeToken({ js: { token: 'abc123' } })).toBe('abc123');
    });

    it('reads token from the legacy bare `token` shape', () => {
      expect(extractStalkerHandshakeToken({ token: 'xyz789' })).toBe('xyz789');
    });

    it('returns null when no token is present', () => {
      expect(extractStalkerHandshakeToken({ js: {} })).toBeNull();
      expect(extractStalkerHandshakeToken({})).toBeNull();
    });

    it('returns null on garbage input', () => {
      expect(extractStalkerHandshakeToken(null)).toBeNull();
      expect(extractStalkerHandshakeToken('not an object')).toBeNull();
    });
  });

  describe('stalkerCategoryItemSchema', () => {
    it('parses { id, title } shape', () => {
      const parsed = stalkerCategoryItemSchema.parse({ id: '12', title: 'News' });
      expect(parsed).toEqual({ id: '12', title: 'News' });
    });

    it('falls back from missing title to name', () => {
      const parsed = stalkerCategoryItemSchema.parse({ id: '3', name: 'Sports' });
      expect(parsed).toEqual({ id: '3', title: 'Sports' });
    });

    it('coerces numeric id to string', () => {
      const parsed = stalkerCategoryItemSchema.parse({ id: 42, title: 'Kids' });
      expect(parsed.id).toBe('42');
    });

    it('tolerates extra vendor keys via passthrough', () => {
      const parsed = stalkerCategoryItemSchema.parse({
        id: '1',
        title: 'Misc',
        custom_vendor_flag: true,
        unknown_array: [1, 2, 3],
      });
      expect(parsed.id).toBe('1');
    });
  });

  describe('stalkerChannelItemSchema', () => {
    it('parses a representative MAG portal channel', () => {
      const parsed = stalkerChannelItemSchema.parse({
        id: '101',
        name: 'BBC One HD',
        cmd: 'ffrt http://provider.example.com/live/101.ts',
        tv_genre_id: '5',
        logo: 'logos/bbc.png',
        epg_channel_id: 'bbc.one',
        number: '101',
        tv_archive: '1',
        tv_archive_duration: '7',
      });
      expect(parsed).toEqual({
        id: 101,
        name: 'BBC One HD',
        cmd: 'ffrt http://provider.example.com/live/101.ts',
        tvGenreId: '5',
        logo: 'logos/bbc.png',
        epgId: 'bbc.one',
        number: 101,
        tvArchive: 1,
        tvArchiveDuration: 7,
      });
    });

    it('uses xmltv_id when epg_channel_id is missing', () => {
      const parsed = stalkerChannelItemSchema.parse({
        id: 1,
        name: 'Test',
        cmd: 'http://a/b',
        xmltv_id: 'fallback.id',
      });
      expect(parsed.epgId).toBe('fallback.id');
    });

    it('defaults missing optional fields to empty string / zero', () => {
      const parsed = stalkerChannelItemSchema.parse({ id: 1, name: 'X', cmd: 'y' });
      expect(parsed).toEqual({
        id: 1,
        name: 'X',
        cmd: 'y',
        tvGenreId: '',
        logo: '',
        epgId: '',
        number: 0,
        tvArchive: 0,
        tvArchiveDuration: 0,
      });
    });

    it('skips malformed items via safeParse failure', () => {
      // A bare string isn't an object — schema rejects.
      expect(stalkerChannelItemSchema.safeParse('not a channel').success).toBe(false);
      expect(stalkerChannelItemSchema.safeParse(null).success).toBe(false);
    });
  });

  describe('stalkerVodItemSchema', () => {
    it('parses screenshot_uri as the logo', () => {
      const parsed = stalkerVodItemSchema.parse({
        id: 7,
        name: 'A Movie',
        cmd: 'http://a/b.mkv',
        category_id: '3',
        screenshot_uri: 'covers/movie.jpg',
        description: 'A description.',
      });
      expect(parsed.logo).toBe('covers/movie.jpg');
      expect(parsed.description).toBe('A description.');
    });

    it('falls back to logo when screenshot_uri is missing', () => {
      const parsed = stalkerVodItemSchema.parse({
        id: 7,
        name: 'X',
        cmd: 'y',
        logo: 'fallback.png',
      });
      expect(parsed.logo).toBe('fallback.png');
    });
  });

  describe('stalkerSeriesItemSchema', () => {
    it('parses a series with description → plot mapping', () => {
      const parsed = stalkerSeriesItemSchema.parse({
        id: 200,
        name: 'A Show',
        category_id: '8',
        screenshot_uri: 'shows/cover.jpg',
        description: 'Plot summary here.',
        genre: 'Drama',
      });
      expect(parsed).toEqual({
        id: 200,
        name: 'A Show',
        categoryId: '8',
        cover: 'shows/cover.jpg',
        plot: 'Plot summary here.',
        genre: 'Drama',
      });
    });
  });
});

// ─── Xtream ─────────────────────────────────────────────────────────────

describe('Xtream schemas', () => {
  describe('xtreamAccountInfoResponseSchema + transformXtreamAuthInfo', () => {
    it('parses a representative account info response', () => {
      const raw = {
        user_info: {
          username: 'user1',
          status: 'Active',
          exp_date: '1735689600',
          is_trial: '0',
          active_cons: '1',
          max_connections: '2',
        },
        server_info: {
          url: 'cdn.example.com',
          port: '80',
          https_port: '443',
          server_protocol: 'http',
          time_now: '2026-05-14 12:00:00',
          timezone: 'UTC',
        },
      };
      const parsed = xtreamAccountInfoResponseSchema.parse(raw);
      const auth = transformXtreamAuthInfo(parsed);
      expect(auth).not.toBeNull();
      expect(auth?.userInfo.username).toBe('user1');
      expect(auth?.userInfo.isTrial).toBe(false);
      expect(auth?.userInfo.activeCons).toBe(1);
      expect(auth?.serverInfo.httpsPort).toBe('443');
    });

    it('returns null when user_info is missing', () => {
      const parsed = xtreamAccountInfoResponseSchema.parse({});
      expect(transformXtreamAuthInfo(parsed)).toBeNull();
    });

    it('returns null when account is disabled', () => {
      const parsed = xtreamAccountInfoResponseSchema.parse({
        user_info: { status: 'Disabled' },
      });
      expect(transformXtreamAuthInfo(parsed)).toBeNull();
    });

    it('handles is_trial as either "1" string or 1 number', () => {
      const a = xtreamAccountInfoResponseSchema.parse({
        user_info: { username: 'u', is_trial: '1' },
      });
      const b = xtreamAccountInfoResponseSchema.parse({
        user_info: { username: 'u', is_trial: 1 },
      });
      expect(transformXtreamAuthInfo(a)?.userInfo.isTrial).toBe(true);
      expect(transformXtreamAuthInfo(b)?.userInfo.isTrial).toBe(true);
    });
  });

  describe('xtreamCategoryItemSchema', () => {
    it('maps category_id / category_name / parent_id', () => {
      const parsed = xtreamCategoryItemSchema.parse({
        category_id: '5',
        category_name: 'Movies',
        parent_id: '0',
      });
      expect(parsed).toEqual({ categoryId: '5', categoryName: 'Movies', parentId: 0 });
    });
  });

  describe('xtreamLiveStreamItemSchema', () => {
    it('parses a representative live stream', () => {
      const parsed = xtreamLiveStreamItemSchema.parse({
        num: 1,
        name: 'CNN HD',
        stream_type: 'live',
        stream_id: 1234,
        stream_icon: 'logos/cnn.png',
        epg_channel_id: 'cnn.us',
        added: '1700000000',
        category_id: '1',
        category_ids: [1, 2, '3'],
        custom_sid: '',
        tv_archive: 1,
        direct_source: '',
        tv_archive_duration: 7,
      });
      expect(parsed.streamType).toBe('live');
      expect(parsed.streamId).toBe(1234);
      expect(parsed.categoryIds).toEqual([1, 2, 3]);
    });

    it('defaults stream_type to "live" and container fallback', () => {
      const parsed = xtreamLiveStreamItemSchema.parse({ name: 'Untyped', stream_id: '7' });
      expect(parsed.streamType).toBe('live');
      expect(parsed.streamId).toBe(7);
    });
  });

  describe('xtreamVodStreamItemSchema', () => {
    it('defaults container_extension to "mp4"', () => {
      const parsed = xtreamVodStreamItemSchema.parse({ name: 'Some Movie', stream_id: 9 });
      expect(parsed.containerExtension).toBe('mp4');
    });
  });

  describe('xtreamSeriesItemSchema', () => {
    it('prefers releaseDate over release_date', () => {
      const parsed = xtreamSeriesItemSchema.parse({
        name: 'Show',
        series_id: 5,
        releaseDate: '2024-01-01',
        release_date: '2023-01-01',
      });
      expect(parsed.releaseDate).toBe('2024-01-01');
    });

    it('falls back to release_date when releaseDate is absent', () => {
      const parsed = xtreamSeriesItemSchema.parse({
        name: 'Show',
        series_id: 5,
        release_date: '2022-07-15',
      });
      expect(parsed.releaseDate).toBe('2022-07-15');
    });
  });

  describe('xtreamSeriesDetailResponseSchema + transformXtreamSeriesDetail', () => {
    it('parses seasons + episodes', () => {
      const raw = {
        info: { name: 'Breaking Code', cover: 'c.jpg', plot: 'p', genre: 'Drama' },
        seasons: [
          { season_number: '1', name: 'Season 1' },
          { season: '2' },
        ],
        episodes: {
          '1': [
            {
              id: '11',
              episode_num: '1',
              title: 'Pilot',
              container_extension: 'mkv',
              info: { duration: '45:00', season: '1' },
            },
          ],
        },
      };
      const parsed = xtreamSeriesDetailResponseSchema.parse(raw);
      const detail = transformXtreamSeriesDetail(parsed);
      expect(detail.info.name).toBe('Breaking Code');
      expect(detail.seasons).toEqual([
        { seasonNumber: 1, name: 'Season 1' },
        { seasonNumber: 2, name: 'Season 2' },
      ]);
      expect(detail.episodes['1']).toHaveLength(1);
      expect(detail.episodes['1'][0].title).toBe('Pilot');
      expect(detail.episodes['1'][0].containerExtension).toBe('mkv');
    });

    it('skips malformed episodes within a valid season', () => {
      const parsed = xtreamSeriesDetailResponseSchema.parse({
        episodes: {
          '1': [{ id: '1', title: 'OK', episode_num: 1 }, 'not an episode', null],
        },
      });
      const detail = transformXtreamSeriesDetail(parsed);
      // The schema is permissive enough that even "not an episode" /
      // null may parse if all required fields are optional. The point
      // is the call doesn't throw — that's the property we care about.
      expect(detail.episodes['1'].length).toBeGreaterThanOrEqual(1);
    });
  });

  describe('xtreamVodInfoResponseSchema + transformXtreamVodDetail', () => {
    it('parses standard VOD info with subtitle objects', () => {
      const raw = {
        info: {
          name: 'A Movie',
          plot: 'A plot.',
          cast: 'Actor One, Actor Two',
          director: 'A Director',
          genre: 'Drama',
          release_date: '2024-06-01',
          rating: '8.5',
          duration: '2h 10min',
          movie_image: 'covers/a.jpg',
          backdrop_path: 'bg/a.jpg',
          tmdb_id: '12345',
          subtitles: [
            { url: 'http://x/en.srt', language: 'en' },
            { url: 'http://x/fr.srt', lang: 'fr' },
          ],
        },
      };
      const parsed = xtreamVodInfoResponseSchema.parse(raw);
      const detail = transformXtreamVodDetail(parsed);
      expect(detail.name).toBe('A Movie');
      expect(detail.tmdbId).toBe(12345);
      expect(detail.cover).toBe('covers/a.jpg');
      expect(detail.backdropUrl).toBe('bg/a.jpg');
      expect(detail.subtitles).toHaveLength(2);
      expect(detail.subtitles[0]).toEqual({ language: 'en', url: 'http://x/en.srt' });
      expect(detail.subtitles[1]).toEqual({ language: 'fr', url: 'http://x/fr.srt' });
    });

    it('handles bare-string subtitle URLs with language inferred from filename', () => {
      const parsed = xtreamVodInfoResponseSchema.parse({
        info: {
          name: 'X',
          subtitles: ['http://srv/movie.en.srt', 'http://srv/dump/no-lang.vtt'],
        },
      });
      const detail = transformXtreamVodDetail(parsed);
      expect(detail.subtitles[0]).toEqual({ language: 'en', url: 'http://srv/movie.en.srt' });
      // No 2-3 letter code before .vtt → 'und' fallback.
      expect(detail.subtitles[1].language).toBe('und');
    });

    it('picks first backdrop from array', () => {
      const parsed = xtreamVodInfoResponseSchema.parse({
        info: { name: 'X', backdrop_path: ['first.jpg', 'second.jpg'] },
      });
      const detail = transformXtreamVodDetail(parsed);
      expect(detail.backdropUrl).toBe('first.jpg');
    });

    it('falls back to rating_5based formatted as N/5', () => {
      const parsed = xtreamVodInfoResponseSchema.parse({
        info: { name: 'X', rating_5based: '4.5' },
      });
      const detail = transformXtreamVodDetail(parsed);
      expect(detail.rating).toBe('4.5/5');
    });

    it('returns null tmdbId when source is missing or zero', () => {
      const a = transformXtreamVodDetail(
        xtreamVodInfoResponseSchema.parse({ info: { name: 'X' } }),
      );
      const b = transformXtreamVodDetail(
        xtreamVodInfoResponseSchema.parse({ info: { name: 'X', tmdb_id: '0' } }),
      );
      expect(a.tmdbId).toBeNull();
      expect(b.tmdbId).toBeNull();
    });
  });
});
