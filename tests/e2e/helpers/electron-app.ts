/**
 * Helper to launch and manage the Electron app in E2E tests.
 *
 * Uses Playwright's _electron module to start the app from the built output.
 * Tests should call `pnpm build` before running E2E tests.
 */
import { _electron as electron, type ElectronApplication, type Page } from 'playwright';
import path from 'path';

export interface AppContext {
  app: ElectronApplication;
  page: Page;
}

/**
 * Launch the YancoTV Electron app for testing.
 * Returns the app instance and the main window page.
 */
export async function launchApp(): Promise<AppContext> {
  const appPath = path.resolve(__dirname, '../../../dist/main/main/index.js');

  const app = await electron.launch({
    args: [appPath],
    env: {
      ...process.env,
      NODE_ENV: 'test',
      // Use a separate user data directory for tests to avoid polluting real data
      ELECTRON_USER_DATA_DIR: path.resolve(__dirname, '../../../.test-user-data'),
    },
  });

  const page = await mainWindow(app);
  await page.waitForLoadState('domcontentloaded');

  return { app, page };
}

/**
 * The window running the YancoTV renderer.
 *
 * NOT `app.firstWindow()`. The app opens three: a transparent `data:text/html`
 * child window that mpv is embedded into, the real renderer, and a controls
 * overlay. Creation order puts the video stage first, so `firstWindow()` returns
 * a blank page — `title()` is `""`, no sidebar, no body text — and every
 * assertion about the UI fails against a window that was never going to contain
 * any. Seventeen specs were failing on exactly that.
 *
 * Identified by URL rather than by index or title: the renderer is the only
 * window loaded from `dist/renderer/index.html` (or, in dev, the Vite server),
 * whereas title and order are both incidental. Waiting matters too — the
 * renderer is not necessarily present the instant the app is up.
 */
export async function mainWindow(app: ElectronApplication): Promise<Page> {
  const isRenderer = (p: Page) => {
    const url = p.url();
    return url.includes('renderer/index.html') || url.includes('localhost:5173/index.html');
  };

  const existing = app.windows().find(isRenderer);
  if (existing) return existing;

  // Not open yet — take windows as they appear until the renderer shows up.
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    const found = app.windows().find(isRenderer);
    if (found) return found;
    await new Promise((r) => setTimeout(r, 200));
  }

  throw new Error(
    'renderer window never appeared. Windows seen: ' +
      JSON.stringify(app.windows().map((w) => w.url())),
  );
}

/**
 * Close the app and clean up.
 */
export async function closeApp(ctx: AppContext): Promise<void> {
  await ctx.app.close();
}
