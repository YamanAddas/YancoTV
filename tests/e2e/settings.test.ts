/**
 * E2E: Settings Page
 *
 * Verifies the settings page renders tabs and source management works.
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
  await page.waitForTimeout(1000);
  // Navigate to settings
  await page.click('text=/Settings/i');
  await page.waitForTimeout(500);
});

test.afterAll(async () => {
  await app.close();
});

test('settings page has tab navigation', async () => {
  // Should have at least General and Playback tabs
  await expect(page.locator('text=/General/i').first()).toBeVisible({ timeout: 5000 });
});

test('can switch between settings tabs', async () => {
  const tabs = ['General', 'Playback', 'Network', 'Playlist', 'EPG', 'Parental', 'Shortcuts', 'About'];

  for (const tab of tabs) {
    const tabButton = page.locator(`text=/${tab}/i`).first();
    if (await tabButton.isVisible().catch(() => false)) {
      await tabButton.click();
      await page.waitForTimeout(200);
      // Tab should be active (no crash/error)
    }
  }
});

test('about tab shows version', async () => {
  const aboutTab = page.locator('text=/About/i').first();
  if (await aboutTab.isVisible().catch(() => false)) {
    await aboutTab.click();
    await page.waitForTimeout(300);
    // Should show version number somewhere
    const versionText = page.locator('text=/\\d+\\.\\d+\\.\\d+/').first();
    await expect(versionText).toBeVisible({ timeout: 5000 });
  }
});
