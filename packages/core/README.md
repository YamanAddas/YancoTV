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
