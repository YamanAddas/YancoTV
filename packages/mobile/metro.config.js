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

// Under pnpm, every peer-dep-hashed copy of a package (zustand,
// @react-navigation/*, etc.) gets its own node_modules/react symlink. Mobile
// pins react@19.2.3 to match the renderer bundled inside react-native-tvos
// @0.85, but @yancotv/core's zustand copy is linked against react@19.2.5 —
// bundling both gives us two React instances, two hook dispatchers, and a
// null-dispatcher crash the moment any hook flows through the "wrong" copy
// ("TypeError: Cannot read property 'useCallback' of null" on first render
// of any screen that subscribes to a core-factory Zustand store).
// The resolveRequest below pins `react` and `react-native` to the single
// copy under packages/mobile/node_modules regardless of who issued the
// import. Those are the only packages that (a) hold React state — the hook
// dispatcher — and (b) are actually hoisted to packages/mobile/node_modules.
// Subpath imports like 'react/jsx-runtime' resolve against the same copy.
// Intentionally NOT singleton-pinned: use-sync-external-store and scheduler.
// Both are stateless shims that call into React. They aren't hoisted under
// packages/mobile so forcing mobile origin would fail to resolve. Leaving
// them hierarchical is safe — each copy imports 'react' itself, and that
// import is caught by this override and routed to the single React copy.
const REACT_SINGLETON_PKGS = new Set(['react', 'react-native']);

const MOBILE_ORIGIN = path.join(projectRoot, 'index.js');

function rootPkgOf(moduleName) {
  const parts = moduleName.split('/');
  return parts[0].startsWith('@') ? `${parts[0]}/${parts[1]}` : parts[0];
}

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
    resolveRequest: (context, moduleName, platform) => {
      if (REACT_SINGLETON_PKGS.has(rootPkgOf(moduleName))) {
        // Re-enter the default resolver as if the request came from
        // packages/mobile/index.js, so Metro's hierarchical lookup starts
        // at the mobile node_modules copy rather than the requesting
        // package's (potentially pnpm-hashed) own tree.
        return context.resolveRequest(
          { ...context, originModulePath: MOBILE_ORIGIN },
          moduleName,
          platform,
        );
      }
      return context.resolveRequest(context, moduleName, platform);
    },
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
