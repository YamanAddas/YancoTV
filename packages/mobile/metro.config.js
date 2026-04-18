const path = require('path');
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

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
    disableHierarchicalLookup: false,
  },
};

module.exports = mergeConfig(getDefaultConfig(projectRoot), config);
