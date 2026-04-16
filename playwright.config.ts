import { defineConfig } from '@playwright/test';

/**
 * Playwright configuration for YancoTV E2E tests.
 *
 * These tests launch the Electron app and interact with it via the Playwright API.
 * Run with: pnpm test:e2e
 */
export default defineConfig({
  testDir: './tests/e2e',
  timeout: 30_000,
  expect: {
    timeout: 10_000,
  },
  fullyParallel: false, // Electron tests must run sequentially
  retries: 0,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
});
