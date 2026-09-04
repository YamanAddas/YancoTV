/**
 * Rows a playlist ships as *headings* rather than as channels.
 *
 * ### What these are
 *
 * A flat M3U has no notion of a section, so providers fake one by putting a
 * row in the list whose name is a banner:
 *
 * ```
 * ##### beIN SP⚽RTS ᴴᴰ #####
 * ###### RELAX ᵁᴴᴰ 3840P ######
 * ### ARABIC 24/7 4K UHD 3840P ###
 * ```
 *
 * They carry a stream URL like any other row — usually one that answers
 * nothing — because the format has nowhere else to put them. In a player that
 * groups by category, which this one does, they are pure noise: the grouping
 * already does their job.
 *
 * ### Why this is not cosmetic
 *
 * Measured on the owner's own Windows catalogue, 2026-09-04: **1,005 of
 * 252,517 rows** are these. They are indistinguishable from channels in the
 * list, they sort to the front of a group, and clicking one opens a player
 * that never resolves. The sibling Android app hit the same rows and lost
 * forty-eight seconds of a recording test to a wrong conclusion about the
 * recorder before anyone read the channel's name.
 *
 * ### Ported deliberately, not for symmetry
 *
 * `packages/core` and `packages/shared` are independent and need not match
 * (see AGENTS.md). This one crosses because the user sees the difference: the
 * same provider, the same rows, junk on Windows and clean on the TV. The rule
 * is kept byte-identical to `PlaylistDividers.kt` so a future change to what
 * counts as a banner does not silently mean two different things.
 *
 * ### The rule, and why it is this strict
 *
 * The title must **begin and end with a run of three or more of the same
 * separator character**. Both ends, three or more, and the same character:
 * anything looser starts eating real titles — "Ping-Pong -- Live" has a run,
 * "24/7 SINGER" has none, and a channel legitimately named with one leading
 * dash is not a heading.
 */

const DIVIDER_CHARS = '#=*_~+-•▬═◆★';

const MIN_RUN = 3;

export function isPlaylistDivider(rawTitle: string): boolean {
  const title = (rawTitle ?? '').trim();
  if (title.length < MIN_RUN * 2) return false;

  const marker = title[0];
  if (!DIVIDER_CHARS.includes(marker)) return false;

  let lead = 0;
  while (lead < title.length && title[lead] === marker) lead++;
  if (lead < MIN_RUN) return false;

  let trail = 0;
  while (trail < title.length && title[title.length - 1 - trail] === marker) trail++;
  if (trail < MIN_RUN) return false;

  // A row of nothing but the marker is a divider too — and the two runs must
  // not be the same run counted twice, which is what this catches.
  return lead + trail >= MIN_RUN * 2 || lead === title.length;
}
