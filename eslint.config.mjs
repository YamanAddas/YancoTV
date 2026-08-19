// ESLint v9+ flat config.
//
// Sits alongside .eslintrc.json (legacy v8 config). ESLint v9+ prefers
// flat config and ignores the legacy file when this one is present;
// ESLint v8 (currently pinned in package.json) does the inverse. Both
// surfaces stay in sync until we drop v8 support.
//
// The audit runner (yancoxplorer Code Quality sub-tool) ships its own
// ESLint v10 binary and needs this file to lint at all — without it
// the runner crashes with "ESLint couldn't find an eslint.config.(js|
// mjs|cjs) file" and the Code Quality category produces no findings.

import tsPlugin from '@typescript-eslint/eslint-plugin';
import reactPlugin from 'eslint-plugin-react';
import reactHooksPlugin from 'eslint-plugin-react-hooks';
import prettier from 'eslint-config-prettier';

export default [
  {
    ignores: [
      'dist/**',
      'dist-electron/**',
      'dist-apk/**',
      'build/**',
      'out/**',
      'release/**',
      '**/node_modules/**',
      'playwright-report/**',
      'test-results/**',
      // Frozen RN port — see AGENTS.md.
      'packages/mobile/**',
      // Native modules (Kotlin / SwiftUI) — not JS/TS.
      'packages/android/**',
      'packages/ios/**',
      // Figma handoff artifacts — not part of any shipping bundle.
      'docs/design/**',
      // Generated SQLDelight / build outputs.
      '**/.gradle/**',
      '**/coverage/**',
      // Per-session worktree scratch.
      '.claude/worktrees/**',
      '.claude-scratch/**',
    ],
  },

  // @typescript-eslint v8 ships a flat-config preset as a 3-entry array
  // covering parser + plugin + rules. Spread it whole.
  ...tsPlugin.configs['flat/recommended'],

  // eslint-plugin-react v7.37 ships a single flat-config object (includes
  // settings.react + parser options + rules).
  reactPlugin.configs.flat.recommended,

  // eslint-plugin-react-hooks v5 exposes `recommended-latest` for flat
  // config (the legacy `recommended` is the old extends shape).
  reactHooksPlugin.configs['recommended-latest'],

  // Disable formatting rules that conflict with Prettier.
  prettier,

  {
    files: ['**/*.{ts,tsx,js,jsx,mjs,cjs}'],
    settings: {
      react: { version: 'detect' },
    },
    rules: {
      // TypeScript already catches undefined identifiers at compile time;
      // ESLint's no-undef double-checks against a globals list that's
      // tedious to maintain (browser + node + DOM + worker). TS-ESLint's
      // own recommended config disables it for the same reason.
      'no-undef': 'off',

      // Project overrides (kept in sync with .eslintrc.json).
      'react/react-in-jsx-scope': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      '@typescript-eslint/no-explicit-any': 'error',
      'no-console': ['warn', { allow: ['warn', 'error'] }],
    },
  },
];
