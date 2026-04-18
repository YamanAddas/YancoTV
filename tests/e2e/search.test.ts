/**
 * E2E: Search Functionality
 *
 * Verifies the search page renders, accepts input, and shows results/empty state.
 */
import { test, expect } from '@playwright/test';
import { _electron as electron, type ElectronApplication, type Page } from 'playwright';
import path from 'path';

let app: ElectronApplication;
let page: Page;

test.beforeAll(async () => {
  app = await electron.launch({
    args: [path.resolve(__dirname, '../../dist/main/main/index.js')],
    env: {
      ...process.env,
      NODE_ENV: 'test',
    },
  });
  page = await app.firstWindow();
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(1000);
});

test.afterAll(async () => {
  await app.close();
});

// There are two search inputs once on the /search page: the sidebar's
// autocomplete and the page's own input. Scope to <main> so tests don't
// accidentally target the sidebar and get the wrong placeholder/state.
const PAGE_SEARCH_INPUT = 'main input[type="search"]';

test('search page has input field', async () => {
  // Search has no sidebar nav link — navigate via hash route.
  await page.evaluate(() => {
    window.location.hash = '#/search';
  });
  await page.waitForTimeout(300);
  const input = page.locator(PAGE_SEARCH_INPUT).first();
  await expect(input).toBeVisible({ timeout: 5000 });
});

test('search input accepts text', async () => {
  const input = page.locator(PAGE_SEARCH_INPUT).first();
  await input.fill('test query');
  const value = await input.inputValue();
  expect(value).toBe('test query');
});

test('empty search shows prompt', async () => {
  const input = page.locator(PAGE_SEARCH_INPUT).first();
  await input.clear();
  await page.waitForTimeout(500);
  // SearchPage renders "Type to search your content library" for empty query.
  const prompt = page.locator('text=/Type to search/i').first();
  await expect(prompt).toBeVisible({ timeout: 5000 });
});

test('search for nonexistent content shows no results', async () => {
  const input = page.locator(PAGE_SEARCH_INPUT).first();
  await input.fill('xyznonexistent12345');
  await page.waitForTimeout(1000);
  // SearchPage renders: No results for "…"
  const noResults = page.locator('text=/No\\s*.*results for/i').first();
  await expect(noResults).toBeVisible({ timeout: 5000 });
});
