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

  // Wait for the main window to appear
  const page = await app.firstWindow();
  await page.waitForLoadState('domcontentloaded');

  return { app, page };
}

/**
 * Close the app and clean up.
 */
export async function closeApp(ctx: AppContext): Promise<void> {
  await ctx.app.close();
}
