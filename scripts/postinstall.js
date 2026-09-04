#!/usr/bin/env node
/**
 * Post-install hook. Leaves the tree in a state where the app actually runs:
 * native modules built for Electron's ABI, and the bundled mpv binary present.
 *
 * ffmpeg is handled by `ffmpeg-static`'s own install script, so nothing to do
 * there. The mpv step is Windows-only and silent when the binary already exists.
 */
'use strict';

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

/**
 * Rebuild better-sqlite3 against Electron's ABI.
 *
 * `pnpm install` builds it for the local **Node** ABI, because that is what
 * prebuild-install targets. Electron ships a different V8, so the freshly
 * installed binary throws NODE_MODULE_VERSION 127-vs-145 the moment the app
 * touches the database — before any window is created. A clean clone therefore
 * produced an app that could never open, with a stack trace in the console and
 * nothing on screen; the five Playwright specs all timed out waiting for a
 * window that was never going to appear.
 *
 * `pnpm test` (scripts/run-tests.js) flips the module to the Node ABI for Vitest
 * and flips it back afterwards, so it looked as if something already handled
 * this. It only does for people who have run the tests at least once.
 */
async function rebuildForElectron() {
  let rebuild;
  try {
    // Resolved, not path-joined. The previous hardcoded
    // `node_modules/.pnpm/@electron+rebuild@4.0.3/...` path in run-tests.js
    // silently breaks on any patch bump of the dependency.
    ({ rebuild } = require(require.resolve('@electron/rebuild')));
  } catch {
    console.warn('[postinstall] @electron/rebuild not installed; skipping native rebuild.');
    return;
  }

  const root = path.join(__dirname, '..');
  let electronVersion;
  try {
    electronVersion = require(path.join(root, 'node_modules/electron/package.json')).version;
  } catch {
    console.warn('[postinstall] electron not installed; skipping native rebuild.');
    return;
  }

  console.log('[postinstall] Rebuilding native modules for Electron ' + electronVersion + '...');
  try {
    await rebuild({
      buildPath: path.dirname(require.resolve('better-sqlite3/package.json')),
      electronVersion,
      force: true,
      onlyModules: ['better-sqlite3'],
      buildFromSource: true,
    });
    console.log('[postinstall] Native rebuild complete.');
  } catch (e) {
    // Not fatal to the install, but the app will not start until this succeeds,
    // so say exactly what to run rather than failing quietly.
    console.warn('[postinstall] Native rebuild failed: ' + (e && e.message));
    console.warn('[postinstall] The app will NOT start until this succeeds.');
    console.warn('[postinstall] Retry with: node scripts/postinstall.js');
  }
}

async function main() {
  await rebuildForElectron();

  if (process.platform !== 'win32') {
    return;
  }

  const mpvExe = path.join(__dirname, '..', 'mpv', 'mpv.exe');
  if (fs.existsSync(mpvExe)) {
    return;
  }

  const script = path.join(__dirname, 'download-mpv.ps1');
  console.log('[postinstall] Fetching mpv binary...');
  const result = spawnSync(
    'powershell',
    ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', script],
    { stdio: 'inherit' },
  );

  if (result.status !== 0) {
    console.warn(
      '[postinstall] mpv download failed. You can retry manually: ' +
        'powershell -ExecutionPolicy Bypass -File scripts/download-mpv.ps1',
    );
    // Don't fail install — the app can still start, playback will just warn.
  }
}

main().catch((e) => {
  console.warn('[postinstall] ' + (e && e.message));
});
