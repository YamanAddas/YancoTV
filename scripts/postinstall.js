#!/usr/bin/env node
/**
 * Post-install hook. Ensures bundled binaries (mpv) are in place.
 * ffmpeg is handled by `ffmpeg-static`'s own install script, so nothing to do there.
 *
 * Runs silently on non-Windows platforms (mpv bundling is Windows-only today).
 * Runs silently if the binary is already present.
 */
'use strict';

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

if (process.platform !== 'win32') {
  process.exit(0);
}

const mpvExe = path.join(__dirname, '..', 'mpv', 'mpv.exe');
if (fs.existsSync(mpvExe)) {
  process.exit(0);
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
  process.exit(0);
}
