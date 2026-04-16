/**
 * E2E: App Launch & Window Basics
 *
 * Verifies the app starts, shows the main window, and has expected UI elements.
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
});

test.afterAll(async () => {
  await app.close();
});

test('app window is visible', async () => {
  const isVisible = await app.evaluate(({ BrowserWindow }) => {
    const win = BrowserWindow.getAllWindows()[0];
    return win?.isVisible() ?? false;
  });
  expect(isVisible).toBe(true);
});

test('window title contains YancoTV', async () => {
  const title = await app.evaluate(({ BrowserWindow }) => {
    const win = BrowserWindow.getAllWindows()[0];
    return win?.getTitle() ?? '';
  });
  expect(title).toContain('YancoTV');
});

test('main window has correct security settings', async () => {
  const settings = await app.evaluate(({ BrowserWindow }) => {
    const win = BrowserWindow.getAllWindows()[0];
    const prefs = win?.webContents.getLastWebPreferences();
    return {
      contextIsolation: prefs?.contextIsolation,
      nodeIntegration: prefs?.nodeIntegration,
      sandbox: prefs?.sandbox,
    };
  });
  expect(settings.contextIsolation).toBe(true);
  expect(settings.nodeIntegration).toBe(false);
  expect(settings.sandbox).toBe(true);
});

test('sidebar navigation is present', async () => {
  // The sidebar should have navigation links
  const sidebar = page.locator('nav, [class*="sidebar"], [class*="Sidebar"]').first();
  await expect(sidebar).toBeVisible({ timeout: 10_000 });
});

test('page renders content or empty state', async () => {
  // After launch, the page should show either content counts or an empty-state prompt
  const body = page.locator('body');
  await expect(body).not.toBeEmpty();

  // Wait for React to render — either content grid or empty state
  const hasContent = await page
    .locator('text=/Live TV|Movies|Series|No content|Add an IPTV source/')
    .first()
    .isVisible()
    .catch(() => false);
  expect(hasContent).toBe(true);
});
