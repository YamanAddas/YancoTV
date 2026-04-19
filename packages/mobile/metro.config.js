const path = require('path');
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

const projectRoot = __dirname;
const workspaceRoot = path.resolve(projectRoot, '../..');

// We must watch workspaceRoot so Metro can follow pnpm symlinks from
// packages/mobile/node_modules/* into <root>/node_modules/.pnpm/* (and
// reach @yancotv/core sources). But the workspace root also contains the
// desktop Electron dev server's build output (dist/, dist-electron/) which,
// when that dev server is running, fires file-change events during every
// TypeScript/Vite rebuild — enough to make Metro restart the mobile bundle
// mid-compile in an infinite 0% → partial → 0% loop.
//
// The fix is to watch the whole workspace (so symlinks resolve) but
// blockList the paths that churn or are never consumed by the RN bundle.
const config = {
  projectRoot,
  watchFolders: [workspaceRoot],
  resolver: {
    nodeModulesPaths: [
      path.resolve(projectRoot, 'node_modules'),
      path.resolve(workspaceRoot, 'node_modules'),
    ],
    disableHierarchicalLookup: false,
    unstable_enableSymlinks: true,
    unstable_enablePackageExports: true,
    blockList: [
      // Desktop Electron build outputs — rewritten continuously by `pnpm dev`
      // at the workspace root. This is the file-event firehose that caused
      // the bundle-restart loop.
      /.*[\\/]YancoTV[\\/]dist[\\/].*/,
      /.*[\\/]YancoTV[\\/]dist-electron[\\/].*/,
      /.*[\\/]YancoTV[\\/]release[\\/].*/,
      /.*[\\/]YancoTV[\\/]dist-apk[\\/].*/,
      // Android native build artifacts
      /.*[\\/]packages[\\/]mobile[\\/]android[\\/]build[\\/].*/,
      /.*[\\/]packages[\\/]mobile[\\/]android[\\/]\.gradle[\\/].*/,
      /.*[\\/]packages[\\/]mobile[\\/]android[\\/]app[\\/]build[\\/].*/,
      // VCS + iOS (not in play) + test outputs
      /.*[\\/]\.git[\\/].*/,
      /.*[\\/]coverage[\\/].*/,
    ],
  },
};

module.exports = mergeConfig(getDefaultConfig(projectRoot), config);
