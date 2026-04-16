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

test('search page has input field', async () => {
  await page.click('text=/Search/i');
  await page.waitForTimeout(300);
  const input = page.locator('input[type="search"], input[placeholder*="Search"]').first();
  await expect(input).toBeVisible({ timeout: 5000 });
});

test('search input accepts text', async () => {
  const input = page.locator('input[type="search"], input[placeholder*="Search"]').first();
  await input.fill('test query');
  const value = await input.inputValue();
  expect(value).toBe('test query');
});

test('empty search shows prompt', async () => {
  const input = page.locator('input[type="search"], input[placeholder*="Search"]').first();
  await input.clear();
  await page.waitForTimeout(500);
  // Should show "Type to search" or similar
  const prompt = page.locator('text=/Type to search|Search your content/i').first();
  await expect(prompt).toBeVisible({ timeout: 5000 });
});

test('search for nonexistent content shows no results', async () => {
  const input = page.locator('input[type="search"], input[placeholder*="Search"]').first();
  await input.fill('xyznonexistent12345');
  await page.waitForTimeout(1000);
  const noResults = page.locator('text=/No results/i').first();
  await expect(noResults).toBeVisible({ timeout: 5000 });
});
