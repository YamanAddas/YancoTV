import { test, expect } from '@playwright/test';
import { _electron as electron, type ElectronApplication, type Page } from 'playwright';
import path from 'path';
import { mainWindow } from './helpers/electron-app';

/**
 * Language switching, verified in a real Electron window.
 *
 * The unit tests prove the translation RULES — plural categories, fallback,
 * interpolation. They cannot prove the app is wired to them: that the provider
 * wraps the tree, that the setting reaches it, or that `dir` lands on the
 * document where the browser's bidi algorithm and logical CSS can see it. Those
 * are exactly the seams a correct-but-unwired i18n layer fails at, so they are
 * checked here against the running app.
 */

let app: ElectronApplication;
let page: Page;

test.beforeAll(async () => {
  app = await electron.launch({
    args: [path.resolve(__dirname, '../../dist/main/main/index.js')],
    env: { ...process.env, NODE_ENV: 'test' },
  });
  page = await mainWindow(app);
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(1500);
});

test.afterAll(async () => {
  // Leave the profile in English so this spec cannot influence another run.
  await page.evaluate(() => window.api.settings.set('ui_language', 'en'));
  await app.close();
});

async function setLanguage(code: string) {
  await page.evaluate((c) => window.api.settings.set('ui_language', c), code);
  // The store reloads from the main process; give React a beat to re-render.
  await page.evaluate(() => window.location.reload());
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(1500);
}

test('defaults to English, left-to-right', async () => {
  await setLanguage('en');
  expect(await page.getAttribute('html', 'dir')).toBe('ltr');
  expect(await page.getAttribute('html', 'lang')).toBe('en');
  await expect(page.locator('nav a[href="#/live"]')).toContainText('Live TV');
});

test('switching to Arabic translates the shell and flips the document to RTL', async () => {
  await setLanguage('ar');

  // `dir` must be on <html>, not a wrapper: portals and overlay windows render
  // outside the React tree and inherit direction from the document root.
  expect(await page.getAttribute('html', 'dir')).toBe('rtl');
  expect(await page.getAttribute('html', 'lang')).toBe('ar');

  // The sidebar reads its labels through the translator.
  await expect(page.locator('nav a[href="#/live"]')).toContainText('البث المباشر');
  await expect(page.locator('nav a[href="#/settings"]')).toContainText('الإعدادات');
});

test('the browser actually applies the RTL layout, not just the attribute', async () => {
  await setLanguage('ar');
  // Computed direction is what decides logical properties, text alignment and
  // scrollbar side. Asserting the attribute alone would pass even if something
  // downstream forced `direction: ltr` back on.
  const computed = await page.evaluate(
    () => getComputedStyle(document.body).direction,
  );
  expect(computed).toBe('rtl');
});

test('switching back to English restores LTR', async () => {
  await setLanguage('ar');
  await setLanguage('en');
  expect(await page.getAttribute('html', 'dir')).toBe('ltr');
  await expect(page.locator('nav a[href="#/live"]')).toContainText('Live TV');
});
