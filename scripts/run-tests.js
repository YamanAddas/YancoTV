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
// Resolved rather than path-joined. The previous form hardcoded
// `@electron+rebuild@4.0.3` inside pnpm's virtual store, so any patch bump of
// the dependency silently broke `pnpm test` with a module-not-found.
const { rebuild } = require(require.resolve('@electron/rebuild'));

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
  console.log('');
  console.log('Rebuilding better-sqlite3 for Node.js...');

  // Driven directly, without npm.
  //
  // `npm rebuild better-sqlite3 --build-from-source` used to do this. Two
  // separate npm changes broke it: npm 11+ rejects unknown CLI flags outright
  // (EUNKNOWNCONFIG), and npm 12 blocks dependency build scripts by default,
  // reporting "N packages had install scripts blocked" as a WARNING and then
  // exiting 0 — so the rebuild reported success while leaving the Electron-ABI
  // binary in place, and every test then failed to load it. `--allow-scripts`
  // is refused in a project-scoped install, and the `allowScripts` manifest
  // field does not reach a dependency inside pnpm's store.
  //
  // `prebuild-install` is what better-sqlite3's own install script runs first.
  // Invoking it directly fetches the prebuilt binary for whatever Node is
  // executing this file, needs no compiler, and is not subject to any of the
  // above. This mirrors rebuildForElectron, which already avoids npm.
  const moduleDir = path.dirname(require.resolve('better-sqlite3/package.json'));
  const prebuildInstall = require.resolve('prebuild-install/bin.js', { paths: [moduleDir] });

  const result = spawnSync(
    process.execPath,
    [prebuildInstall, '--runtime=node', `--target=${process.versions.node}`, '--tag-prefix=v'],
    { stdio: 'inherit', cwd: moduleDir, shell: false },
  );

  if (result.error || result.status !== 0) {
    console.error(
      'Rebuild for Node.js failed:',
      result.error ? result.error.message : `prebuild-install exited ${result.status}`,
    );
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
  //
  // Resolve npx via the platform-specific binary (`npx.cmd` on win32,
  // `npx` elsewhere) instead of relying on the shell to find it. This
  // closes the `spawn-shell-true` Semgrep finding — shell expansion
  // would propagate the user's shell variables into the spawned
  // process, which `child_process.spawn` does not need when the
  // command and args are fully specified.
  const args = process.argv.slice(2);
  // Run vitest's JS entry on THIS Node, rather than shelling out to npx.
  //
  // The previous form spawned `npx.cmd` with `shell: false`. Node 18.20+/20+/22+
  // refuse to spawn a `.cmd` or `.bat` without a shell — it throws EINVAL, the
  // fix for CVE-2024-27980 — so `pnpm test` died before running a single test,
  // and with `stdio: 'inherit'` it printed nothing at all to say why. Turning
  // `shell: true` back on would fix it and reintroduce the shell-expansion
  // finding the `shell: false` was added for. Resolving the bin and handing it
  // to `process.execPath` needs no shell on any platform.
  const vitestBin = require.resolve('vitest/vitest.mjs');
  const result = spawnSync(process.execPath, [vitestBin, 'run', ...args], {
    stdio: 'inherit',
    cwd: ROOT,
    shell: false,
  });
  if (result.error) {
    console.error('Failed to start vitest:', result.error.message);
  }
  const testExitCode = result.status ?? 1;

  // Step 3: always restore for Electron, regardless of test outcome
  await rebuildForElectron();

  process.exit(testExitCode);
}

main().catch((e) => {
  console.error('run-tests.js fatal error:', e);
  process.exit(1);
});
