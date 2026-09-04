/**
 * E2E: App Launch & Window Basics
 *
 * Verifies the app starts, shows the main window, and has expected UI elements.
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
});

test.afterAll(async () => {
  await app.close();
});

test('app window is visible', async () => {
  // beforeAll resolves once the main window's DOM is parsed, but the
  // BrowserWindow itself isn't visible until ready-to-show fires (it's
  // created with show:false then explicitly shown). Poll briefly so the
  // assertion isn't racing the show() call. Also: getAllWindows()[0] is no
  // longer guaranteed to be main once the controls-overlay and video-stage
  // child windows exist — those load fast and hidden, so a naive [0] would
  // pick one of them up.
  const isVisible = await app.evaluate(async ({ BrowserWindow }) => {
    const deadline = Date.now() + 5000;
    while (Date.now() < deadline) {
      const wins = BrowserWindow.getAllWindows();
      const main = wins.find((w) => w.webContents.getURL().includes('/renderer/index.html'));
      if (main?.isVisible()) return true;
      await new Promise((r) => setTimeout(r, 100));
    }
    return false;
  });
  expect(isVisible).toBe(true);
});

test('window title contains YancoTV', async () => {
  // Use page.title() (the main window's document.title) instead of
  // BrowserWindow.getAllWindows()[0].getTitle() — once the overlay and
  // video child windows exist, [0] is no longer guaranteed to be main.
  const title = await page.title();
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
