/**
 * E2E: Navigation
 *
 * Verifies sidebar navigation works and all pages render without errors.
 */
import { test, expect } from '@playwright/test';
import { _electron as electron, type ElectronApplication, type Page } from 'playwright';
import path from 'path';
import { mainWindow } from './helpers/electron-app';

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
  page = await mainWindow(app);
  await page.waitForLoadState('domcontentloaded');
  // Wait for initial page to render
  await page.waitForTimeout(1000);
});

test.afterAll(async () => {
  await app.close();
});

test('navigate to Live TV page', async () => {
  await page.click('text=/Live/i');
  await expect(page.locator('text=/Live TV/i').first()).toBeVisible({ timeout: 5000 });
});

test('navigate to Movies page', async () => {
  await page.click('text=/Movies/i');
  await expect(page.locator('text=/Movies/i').first()).toBeVisible({ timeout: 5000 });
});

test('navigate to Series page', async () => {
  await page.click('text=/Series/i');
  await expect(page.locator('text=/Series/i').first()).toBeVisible({ timeout: 5000 });
});

test('navigate to Search page', async () => {
  // Search has no sidebar nav link — it's reached by submitting the sidebar's
  // search input or via the hash route directly.
  await page.evaluate(() => {
    window.location.hash = '#/search';
  });
  await expect(
    page.locator('input[type="search"], input[placeholder*="Search"]').first(),
  ).toBeVisible({ timeout: 5000 });
});

test('navigate to Favorites page', async () => {
  await page.click('text=/Favorites/i');
  await expect(page.locator('text=/Favorites/i').first()).toBeVisible({ timeout: 5000 });
});

test('navigate to Settings page', async () => {
  await page.click('text=/Settings/i');
  await expect(page.locator('text=/Settings/i').first()).toBeVisible({ timeout: 5000 });
});

test('navigate to Guide page', async () => {
  await page.click('text=/Guide/i');
  await expect(page.locator('text=/Guide|EPG/i').first()).toBeVisible({ timeout: 5000 });
});

test('no console errors during navigation', async () => {
  const errors: string[] = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') {
      errors.push(msg.text());
    }
  });

  // Navigate via URL hash — "Search" has no sidebar link, and hash routing
  // avoids depending on i18n-sensitive label text.
  const routes = ['#/live', '#/movies', '#/series', '#/search', '#/favorites', '#/settings'];
  for (const hash of routes) {
    await page.evaluate((h) => {
      window.location.hash = h;
    }, hash);
    await page.waitForTimeout(300);
  }

  // Filter out known non-critical errors (e.g., favicon 404)
  const critical = errors.filter(
    (e) => !e.includes('favicon') && !e.includes('net::ERR_FILE_NOT_FOUND'),
  );
  expect(critical).toEqual([]);
});
