/**
 * E2E: Keyboard Shortcuts
 *
 * Verifies global keyboard shortcuts work in the app.
 * These tests don't require active playback — they test the shortcut
 * registration and UI response.
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

test('F11 toggles fullscreen', async () => {
  const wasFull = await app.evaluate(({ BrowserWindow }) => {
    return BrowserWindow.getAllWindows()[0]?.isFullScreen() ?? false;
  });

  await page.keyboard.press('F11');
  await page.waitForTimeout(500);

  const isFullNow = await app.evaluate(({ BrowserWindow }) => {
    return BrowserWindow.getAllWindows()[0]?.isFullScreen() ?? false;
  });

  // Should have toggled
  expect(isFullNow).not.toBe(wasFull);

  // Restore
  if (isFullNow) {
    await page.keyboard.press('F11');
    await page.waitForTimeout(500);
  }
});

test('keyboard does not interfere with search input', async () => {
  await page.click('text=/Search/i');
  await page.waitForTimeout(300);
  const input = page.locator('input[type="search"], input[placeholder*="Search"]').first();
  await input.focus();
  await input.fill('');
  await page.keyboard.type('space test', { delay: 50 });
  const value = await input.inputValue();
  // Space should type into the input, not trigger play/pause
  expect(value).toContain('space test');
});
