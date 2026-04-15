#!/usr/bin/env node
/**
 * Test runner that temporarily rebuilds better-sqlite3 for the system Node ABI
 * (required by Vitest), then always restores it for Electron — even on failure
 * or Ctrl+C.
 *
 * Uses @electron/rebuild's programmatic API directly to avoid Windows shell
 * path-escaping issues with node_modules/.bin wrappers.
 */

const { execSync, spawnSync } = require('child_process');
const path = require('path');

const ROOT = path.join(__dirname, '..');

// Resolve better-sqlite3's location in the pnpm virtual store
const sqlite3Dir = path.dirname(require.resolve('better-sqlite3/package.json'));

// Electron version for the rebuild target
const electronVersion = require(path.join(ROOT, 'node_modules/electron/package.json')).version;

// @electron/rebuild programmatic API
const rebuildLib = path.join(
  ROOT,
  'node_modules/.pnpm/@electron+rebuild@4.0.3/node_modules/@electron/rebuild/lib/rebuild.js',
);
const { rebuild } = require(rebuildLib);

async function rebuildForElectron() {
  console.log('\nRebuilding better-sqlite3 for Electron...');
  try {
    await rebuild({
      buildPath: sqlite3Dir,
      electronVersion,
      force: true,
      onlyModules: ['better-sqlite3'],
      buildFromSource: true,
    });
    console.log('Rebuild for Electron complete.');
  } catch (e) {
    console.error('Rebuild for Electron FAILED:', e.message);
    console.error('Run manually: pnpm exec electron-rebuild');
  }
}

function rebuildForNode() {
  console.log('\nRebuilding better-sqlite3 for Node.js...');
  try {
    execSync('npm rebuild better-sqlite3 --build-from-source', {
      stdio: 'inherit',
      cwd: ROOT,
    });
  } catch (e) {
    console.error('Rebuild for Node.js failed:', e.message);
    process.exit(1);
  }
}

async function main() {
  // Always restore Electron binary on Ctrl+C
  process.on('SIGINT', async () => {
    console.log('\nInterrupted — restoring Electron native modules...');
    await rebuildForElectron();
    process.exit(130);
  });

  // Step 1: rebuild for system Node so Vitest can load better-sqlite3
  rebuildForNode();

  // Step 2: run the tests
  const args = process.argv.slice(2);
  const result = spawnSync('npx', ['vitest', 'run', ...args], {
    stdio: 'inherit',
    cwd: ROOT,
    shell: true,
  });
  const testExitCode = result.status ?? 1;

  // Step 3: always restore for Electron, regardless of test outcome
  await rebuildForElectron();

  process.exit(testExitCode);
}

main().catch((e) => {
  console.error('run-tests.js fatal error:', e);
  process.exit(1);
});
