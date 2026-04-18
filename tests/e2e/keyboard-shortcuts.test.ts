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

test('F11 is a no-op when nothing is playing', async () => {
  // In YancoTV, F11 only toggles fullscreen when playback is active
  // (see use-player-shortcuts.ts — the handler guards on `isActive`).
  // Without a stream, F11 is deliberately inert. Verify that the main
  // window state doesn't change — catches regressions that would
  // accidentally fullscreen the chrome/UI outside of playback.
  const readMainFull = () =>
    app.evaluate(({ BrowserWindow }) => {
      const main =
        BrowserWindow.getAllWindows().find((w) => !w.getParentWindow()) ??
        BrowserWindow.getAllWindows()[0];
      return main?.isFullScreen() ?? false;
    });

  const before = await readMainFull();
  await page.keyboard.press('F11');
  await page.waitForTimeout(500);
  const after = await readMainFull();

  expect(after).toBe(before);
});

test('keyboard does not interfere with search input', async () => {
  // The sidebar has a search input (role=combobox) — there is no separate
  // "Search" nav link. Focus the input directly instead of clicking a link.
  const input = page.locator('input[type="search"], input[placeholder*="Search"]').first();
  await input.focus();
  await input.fill('');
  await page.keyboard.type('space test', { delay: 50 });
  const value = await input.inputValue();
  // Space should type into the input, not trigger play/pause
  expect(value).toContain('space test');
});
