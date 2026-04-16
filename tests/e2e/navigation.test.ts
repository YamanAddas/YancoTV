/**
 * E2E: Navigation
 *
 * Verifies sidebar navigation works and all pages render without errors.
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
  await page.click('text=/Search/i');
  await expect(page.locator('input[type="search"], input[placeholder*="Search"]').first()).toBeVisible({
    timeout: 5000,
  });
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

  // Navigate through all pages quickly
  for (const label of ['Live', 'Movies', 'Series', 'Search', 'Favorites', 'Settings']) {
    await page.click(`text=/${label}/i`);
    await page.waitForTimeout(300);
  }

  // Filter out known non-critical errors (e.g., favicon 404)
  const critical = errors.filter(
    (e) => !e.includes('favicon') && !e.includes('net::ERR_FILE_NOT_FOUND'),
  );
  expect(critical).toEqual([]);
});
