import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { IpcChannels } from '../../src/shared/ipc-channels';

/**
 * MB-404 — every content channel that reaches the renderer must filter.
 *
 * The unit tests around `applyParentalVisibility` prove the RULE. They cannot
 * prove the WIRING, and the wiring is where this bug actually lived: the filter
 * existed, in `LiveTvPage`, and six other pages simply never called it. A
 * correct helper nobody applies is the exact shape of the original defect, so
 * asserting the helper works would have passed against the broken app.
 *
 * The failure this guards is a future one: someone adds `content:getDocs`,
 * returns rows straight from the store, and hidden content is reachable again
 * on one page. Listing a channel here is not optional — a new CONTENT_* channel
 * either filters or gets an explicit, reasoned exemption below.
 *
 * Static analysis rather than invoking the handlers: exercising them needs an
 * Electron `ipcMain` plus a database, and the property under test — "this call
 * site passes through a filter" — is visible in the source. The cost of the
 * heavier harness buys nothing here.
 */
describe('parental filtering is wired into every content channel', () => {
  const src = readFileSync(join(process.cwd(), 'src/main/ipc/index.ts'), 'utf-8');

  /**
   * Channels that legitimately return unfiltered rows, each with the reason.
   * Adding to this list should feel deliberate.
   */
  const EXEMPT: Record<string, string> = {
    CONTENT_GET_EPISODES:
      'Episodes are not independently hideable — they belong to a series, and the ' +
      'series itself is gated by CONTENT_GET_DETAIL. A user cannot navigate to ' +
      'episodes without first resolving the parent, which is filtered.',
  };

  const FILTERS = [
    'applyParentalVisibility',
    'applyParentalCategoryVisibility',
    'filterHiddenItem',
  ];

  /** Body of `ipcMain.handle(IpcChannels.X, ...)` up to the next handler. */
  function handlerBody(channelKey: string): string | null {
    const start = src.indexOf(`ipcMain.handle(IpcChannels.${channelKey},`);
    if (start === -1) return null;
    const next = src.indexOf('ipcMain.handle(', start + 1);
    return src.slice(start, next === -1 ? src.length : next);
  }

  const contentChannels = Object.keys(IpcChannels).filter((k) => k.startsWith('CONTENT_'));

  it('finds the content channels and the handler file', () => {
    // Guard the guard. If the channel prefix changes or the handlers move to
    // another file, every assertion below would iterate an empty list and this
    // suite would pass while testing nothing.
    expect(contentChannels.length).toBeGreaterThan(4);
    expect(src).toContain('ipcMain.handle(IpcChannels.CONTENT_GET_LIVE,');
  });

  it.each(contentChannels)('%s passes its result through a parental filter', (key) => {
    const body = handlerBody(key);
    expect(body, `no ipcMain.handle found for ${key}`).toBeTruthy();

    if (EXEMPT[key]) {
      expect(
        FILTERS.some((f) => body!.includes(f)),
        `${key} is listed as exempt but now filters — remove it from EXEMPT`,
      ).toBe(false);
      return;
    }

    expect(
      FILTERS.some((f) => body!.includes(f)),
      `${key} returns content to the renderer without a parental filter. ` +
        `Wrap it in one of ${FILTERS.join(' / ')}, or add it to EXEMPT with a reason.`,
    ).toBe(true);
  });

  it('the renderer does not re-implement the filter', () => {
    // The old bug was a client-side copy that only one page called. A second
    // implementation is how the two drift apart again (AGENTS.md rule 8).
    const live = readFileSync(
      join(process.cwd(), 'src/renderer/pages/LiveTvPage.tsx'),
      'utf-8',
    );
    expect(live).not.toMatch(/includes\(\s*'xxx'\s*\)/i);
    expect(live).not.toMatch(/includes\(\s*'18\+'\s*\)/i);
  });
});

/**
 * MB-405 — playback must be gated at the funnel, not per page.
 *
 * Two independent gates, because there are two backends and they do not share a
 * code path: mpv goes through `player:play` in the main process, while the html5
 * backend sets `currentUrl` and lets the <video> element load the stream inside
 * the renderer — never touching main at all. A single check in either place
 * leaves the other backend open, which is why both are asserted here.
 */
describe('locked playback is gated at the funnel', () => {
  const ipc = readFileSync(join(process.cwd(), 'src/main/ipc/index.ts'), 'utf-8');
  const playerStore = readFileSync(
    join(process.cwd(), 'src/renderer/stores/player-store.ts'),
    'utf-8',
  );

  it('the main-process play handler refuses locked content', () => {
    const start = ipc.indexOf('IpcChannels.PLAYER_PLAY,');
    expect(start, 'PLAYER_PLAY handler not found').toBeGreaterThan(-1);
    const body = ipc.slice(start, start + 3000);
    expect(
      body.includes('requiresPinToPlay'),
      'player:play must refuse locked content — it is the authority even when ' +
        'the renderer forgets to prompt',
    ).toBe(true);
  });

  it('the play handler recovers the id when the caller omits contentId', () => {
    const start = ipc.indexOf('IpcChannels.PLAYER_PLAY,');
    const body = ipc.slice(start, start + 3000);
    // `contentId` is an optional argument. Without this fallback, a call site
    // that omits it walks straight past the lock.
    expect(body).toContain('getContentIdByStreamUrl');
  });

  it('the renderer play funnel gates before starting either backend', () => {
    expect(
      playerStore.includes('parental.requiresPin'),
      'player-store.play must ask before playing — the html5 backend never ' +
        'calls player:play, so the main-process gate does not cover it',
    ).toBe(true);
  });

  it('no page re-implements the lock check before playing', () => {
    // The original defect was a per-page gate. A page that grows its own is a
    // page the other seven playback entry points still bypass.
    const pages = [
      'src/renderer/pages/LiveTvPage.tsx',
      'src/renderer/pages/ContentDetailPage.tsx',
      'src/renderer/pages/FavoritesPage.tsx',
      'src/renderer/pages/HomePage.tsx',
    ];
    for (const rel of pages) {
      const body = readFileSync(join(process.cwd(), rel), 'utf-8');
      expect(body, `${rel} re-implements a lock gate`).not.toMatch(
        /lockedIds\s*\.\s*has\([^)]*\)\s*&&/,
      );
    }
  });
});
