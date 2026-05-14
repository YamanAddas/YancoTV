import { z } from 'zod';
import type {
  StalkerAuthInfo,
  StalkerCategory,
  StalkerChannel,
  StalkerVodItem,
  StalkerSeriesItem,
} from './client.js';

/**
 * Zod schemas for the Stalker / Ministra Portal JSON responses.
 *
 * Real-world portal vendors (MAG, Infomir, third-party emulators) return
 * loose, vendor-specific JSON: fields that should be numbers come back
 * as strings, optional fields are omitted or `null`, and unknown keys
 * abound. The schemas mirror that reality:
 *
 *   - `flexNumber` / `flexString` use Zod's `.preprocess` to replicate
 *     the `Number(x) || 0` and `String(x ?? '')` coercion the client
 *     was doing inline, so the schema-validated value is identical to
 *     what `as any` was producing.
 *   - `.passthrough()` everywhere so vendor extensions don't cause a
 *     reject.
 *   - Item schemas `.transform()` to the camelCased `Stalker*` types
 *     in one pass, so the client just spreads `parsed.data` instead
 *     of doing a second mapping step.
 *   - List response schemas only validate the *wrapping* shape; the
 *     item array is `z.array(z.unknown())` so per-item parse errors
 *     don't poison the whole list. Clients then run the item schema
 *     in a loop, skipping malformed entries.
 *
 * The goal is to drop the 9 `as any` casts the launch audit flagged
 * without changing observable behaviour for legitimate portals.
 */

const flexNumber = z.preprocess((v) => Number(v) || 0, z.number());
const flexString = z.preprocess((v) => String(v ?? ''), z.string());

// ─── Handshake / auth ───────────────────────────────────────────────────

/** `{ js: { token: "..." } }` (modern) or `{ token: "..." }` (legacy). */
export const stalkerHandshakeResponseSchema = z
  .object({
    js: z.object({ token: z.string().optional() }).passthrough().optional(),
    token: z.string().optional(),
  })
  .passthrough();

/** Pulls the token out of either of the two shapes; null if neither. */
export function extractStalkerHandshakeToken(raw: unknown): string | null {
  const parsed = stalkerHandshakeResponseSchema.safeParse(raw);
  if (!parsed.success) return null;
  return parsed.data.js?.token ?? parsed.data.token ?? null;
}

// ─── Category (genres, vod categories, series categories) ────────────────

/** Stalker category: `{ id, title|name, ... }`. */
export const stalkerCategoryItemSchema = z
  .object({
    id: flexString,
    title: flexString.optional(),
    name: flexString.optional(),
  })
  .passthrough()
  .transform(
    (raw): StalkerCategory => ({
      id: raw.id,
      title: raw.title || raw.name || '',
    }),
  );

/** Response wrapper for *all* category-listing endpoints. */
export const stalkerCategoryListResponseSchema = z
  .object({
    js: z.array(z.unknown()).optional(),
  })
  .passthrough();

// ─── Live channels ──────────────────────────────────────────────────────

export const stalkerChannelItemSchema = z
  .object({
    id: flexNumber,
    name: flexString,
    cmd: flexString,
    tv_genre_id: flexString.optional(),
    logo: flexString.optional(),
    epg_channel_id: flexString.optional(),
    xmltv_id: flexString.optional(),
    number: flexNumber.optional(),
    tv_archive: flexNumber.optional(),
    tv_archive_duration: flexNumber.optional(),
  })
  .passthrough()
  .transform(
    (raw): StalkerChannel => ({
      id: raw.id,
      name: raw.name,
      cmd: raw.cmd,
      tvGenreId: raw.tv_genre_id ?? '',
      logo: raw.logo ?? '',
      epgId: raw.epg_channel_id || raw.xmltv_id || '',
      number: raw.number ?? 0,
      tvArchive: raw.tv_archive ?? 0,
      tvArchiveDuration: raw.tv_archive_duration ?? 0,
    }),
  );

export const stalkerChannelPageResponseSchema = z
  .object({
    js: z
      .object({
        data: z.array(z.unknown()).optional(),
        total_items: flexNumber.optional(),
      })
      .passthrough()
      .optional(),
  })
  .passthrough();

// ─── VOD items ──────────────────────────────────────────────────────────

export const stalkerVodItemSchema = z
  .object({
    id: flexNumber,
    name: flexString,
    cmd: flexString,
    category_id: flexString.optional(),
    screenshot_uri: flexString.optional(),
    logo: flexString.optional(),
    description: flexString.optional(),
  })
  .passthrough()
  .transform(
    (raw): StalkerVodItem => ({
      id: raw.id,
      name: raw.name,
      cmd: raw.cmd,
      categoryId: raw.category_id ?? '',
      logo: raw.screenshot_uri || raw.logo || '',
      description: raw.description ?? '',
    }),
  );

export const stalkerVodPageResponseSchema = stalkerChannelPageResponseSchema;

// ─── Series ─────────────────────────────────────────────────────────────

export const stalkerSeriesItemSchema = z
  .object({
    id: flexNumber,
    name: flexString,
    category_id: flexString.optional(),
    screenshot_uri: flexString.optional(),
    cover: flexString.optional(),
    description: flexString.optional(),
    genre: flexString.optional(),
  })
  .passthrough()
  .transform(
    (raw): StalkerSeriesItem => ({
      id: raw.id,
      name: raw.name,
      categoryId: raw.category_id ?? '',
      cover: raw.screenshot_uri || raw.cover || '',
      plot: raw.description ?? '',
      genre: raw.genre ?? '',
    }),
  );

export const stalkerSeriesPageResponseSchema = stalkerChannelPageResponseSchema;

// ─── Auth info hand-back (typed re-export for client.ts) ────────────────

export type { StalkerAuthInfo };
