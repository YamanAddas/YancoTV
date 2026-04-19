# @yancotv/core

Platform-agnostic business logic shared between the YancoTV Electron desktop app and the React Native mobile app.

## What belongs here

- TypeScript types (source, content, EPG, etc.)
- Zod schemas
- M3U / XMLTV parsers (fetch + gunzip injected by platform)
- Xtream / Stalker API clients
- Content classifier, title cleaner
- Catchup URL builders
- Zustand store factories (state shape + actions, no DOM/RN deps)

## What does NOT belong here

- Anything touching `window`, `document`, `electron`, `react-native`
- Anything touching `node:fs`, `child_process`, `better-sqlite3` directly
- React components

If a module needs a platform primitive (fetch, crypto, storage), inject it as a dependency.

## ESM gotcha — explicit `.js` extensions required

`@yancotv/core` is published as pure ESM (`"type": "module"`). Every internal relative import **must use an explicit `.js` extension**, even though the source is `.ts`:

```ts
// ✅ Correct — works at runtime and type-checks under moduleResolution: "bundler"
export * from './types/index.js';
import { XtreamClient } from './xtream/client.js';

// ❌ Wrong — compiles clean, crashes at Electron boot
export * from './types';            // ERR_UNSUPPORTED_DIR_IMPORT
import { XtreamClient } from './xtream/client';  // ERR_MODULE_NOT_FOUND
```

Node 22's loader rejects directory imports and extensionless specifiers. Metro strips `.js` on the mobile side (see `packages/mobile/metro.config.js`), so the same source works for both apps. Adding a new file or re-export without `.js` will crash desktop boot — this incident caused MB-18 on 2026-04-19.
