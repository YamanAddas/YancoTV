const path = require('path');
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');
const { withNativeWind } = require('nativewind/metro');

const projectRoot = __dirname;
const workspaceRoot = path.resolve(projectRoot, '../..');

/**
 * Metro configuration for pnpm monorepo.
 * Watches the workspace root so changes in @yancotv/core trigger HMR.
 */
const config = {
  projectRoot,
  watchFolders: [workspaceRoot],
  resolver: {
    // pnpm symlinks packages into node_modules — tell Metro to follow them.
    nodeModulesPaths: [
      path.resolve(projectRoot, 'node_modules'),
      path.resolve(workspaceRoot, 'node_modules'),
    ],
    // Prefer the mobile package's own copy of React to avoid duplicate-instance errors.
    disableHierarchicalLookup: false,
  },
};

module.exports = withNativeWind(
  mergeConfig(getDefaultConfig(projectRoot), config),
  { input: './src/styles/global.css' },
);
