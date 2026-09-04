import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Every start-page setting must name a route that exists.
 *
 * `/` renders `<Navigate to={startRoute}>` and so does the `*` catch-all. If
 * `startRoute` resolves to a path with no `<Route>`, the two bounce off each
 * other forever: `/` -> `/history` -> no match -> `*` -> `/history` -> ...
 * That shipped. "History" was an option in the start-page dropdown, `/history`
 * was never a route, and picking it wedged the app on every launch.
 *
 * This is a static check on the source rather than a React render, because the
 * renderer has no component-test harness and adding one to catch a table/route
 * mismatch would be a heavy answer to a cheap question. Reading the file is
 * enough to make the class of bug impossible.
 */
describe('start-page routing', () => {
  const app = readFileSync(
    join(process.cwd(), 'src/renderer/App.tsx'),
    'utf-8',
  );

  const mapBody = app.match(
    /const START_PAGE_ROUTES: Record<string, string> = \{([^}]*)\}/,
  )?.[1];

  const declaredRoutes = new Set(
    [...app.matchAll(/<Route\s+path="([^"]+)"/g)].map((m) => m[1]),
  );

  it('finds the route table and the route declarations', () => {
    // Guard the guard: if either regex stops matching — the map is renamed, the
    // routes move to another file — every assertion below would pass over an
    // empty set and this test would silently stop testing anything.
    expect(mapBody, 'START_PAGE_ROUTES not found in App.tsx').toBeTruthy();
    expect(declaredRoutes.size).toBeGreaterThan(5);
  });

  it('every start-page value points at a declared route', () => {
    const entries = [...(mapBody ?? '').matchAll(/(\w+):\s*'([^']+)'/g)].map(
      (m) => [m[1], m[2]] as const,
    );
    expect(entries.length).toBeGreaterThan(0);

    const unrouted = entries.filter(([, path]) => !declaredRoutes.has(path));
    expect(
      unrouted,
      `start pages with no matching <Route>: ${unrouted
        .map(([k, v]) => `${k} -> ${v}`)
        .join(', ')}`,
    ).toEqual([]);
  });

  it('no start page redirects to the redirecting route itself', () => {
    // `home: '/'` was exactly this. '/' is the <Navigate> source, so pointing a
    // start page at it is a one-line infinite loop that no route table audit
    // would flag — '/' *is* declared.
    const selfReferential = [
      ...(mapBody ?? '').matchAll(/(\w+):\s*'\/'/g),
    ].map((m) => m[1]);
    expect(selfReferential, 'these resolve to / which redirects to them').toEqual(
      [],
    );
  });

  it('the settings dropdown offers only routable start pages', () => {
    // The dropdown is the only way a user sets this, so a value offered there
    // and missing from the map is the reachable half of the bug.
    const general = readFileSync(
      join(process.cwd(), 'src/renderer/components/settings/GeneralSettings.tsx'),
      'utf-8',
    );
    const block = general.match(
      /ui_start_page[\s\S]{0,400}?options=\{\[([\s\S]*?)\]\}/,
    )?.[1];
    expect(block, 'start-page <Select> options not found').toBeTruthy();

    const offered = [...(block ?? '').matchAll(/value:\s*'([^']+)'/g)].map(
      (m) => m[1],
    );
    expect(offered.length).toBeGreaterThan(0);

    const known = new Set(
      [...(mapBody ?? '').matchAll(/(\w+):\s*'[^']+'/g)].map((m) => m[1]),
    );
    const orphans = offered.filter((v) => !known.has(v));
    expect(
      orphans,
      `offered in Settings but absent from START_PAGE_ROUTES: ${orphans.join(', ')}`,
    ).toEqual([]);
  });
});
