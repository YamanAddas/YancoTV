# YancoTV — Claude Code Project Guide

## Project Overview

YancoTV is a custom IPTV media application. Windows-first desktop app built with Electron + React + TypeScript. Not a fork — built from scratch with selective use of open-source components.

## Tech Stack

- **Runtime:** Electron (hardened)
- **Frontend:** React 18+ / TypeScript / Tailwind CSS
- **State:** Zustand
- **Database:** SQLite via better-sqlite3
- **Playback:** mpv (via child process, behind IPlayer abstraction)
- **Media tools:** ffmpeg (recording, downloading, subtitle extraction)
- **Package manager:** pnpm
- **Bundler:** Vite (for renderer process)
- **Packaging:** electron-builder
- **Testing:** Vitest (unit), Playwright (e2e)
- **Linting:** ESLint + Prettier

## Project Structure

```
YancoTV/
├── CLAUDE.md
├── package.json
├── electron-builder.yml
├── vite.config.ts
├── tsconfig.json
├── src/
│   ├── main/                    # Electron main process
│   │   ├── index.ts             # App entry, window creation
│   │   ├── preload.ts           # Preload script (IPC bridge)
│   │   ├── ipc/                 # IPC handlers (strict channel definitions)
│   │   ├── services/            # Backend logic
│   │   │   ├── source-manager.ts
│   │   │   ├── m3u-parser.ts
│   │   │   ├── xtream-client.ts
│   │   │   ├── content-classifier.ts
│   │   │   ├── epg-service.ts
│   │   │   ├── title-cleaner.ts
│   │   │   ├── db.ts
│   │   │   └── subtitle-service.ts
│   │   └── player/
│   │       ├── player.interface.ts  # IPlayer abstraction
│   │       └── mpv-player.ts        # mpv implementation
│   ├── renderer/                # React frontend (renderer process)
│   │   ├── index.html
│   │   ├── main.tsx
│   │   ├── App.tsx
│   │   ├── pages/
│   │   ├── components/
│   │   ├── hooks/
│   │   ├── stores/              # Zustand stores
│   │   └── styles/
│   ├── shared/                  # Shared types, constants, utils
│   │   ├── types/
│   │   ├── constants.ts
│   │   └── ipc-channels.ts      # Single source of truth for IPC channel names
│   └── assets/                  # Icons, images
├── scripts/                     # Build/dev helper scripts
├── tests/
│   ├── unit/
│   └── e2e/
└── docs/
```

## Commands

```bash
pnpm install          # Install dependencies
pnpm dev              # Run in development mode (Electron + Vite HMR)
pnpm build            # Build for production
pnpm package          # Package as Windows installer
pnpm test             # Run unit tests
pnpm test:e2e         # Run e2e tests
pnpm lint             # Lint all files
pnpm lint:fix         # Auto-fix lint issues
```

## Architecture Rules

### Electron Security (NON-NEGOTIABLE)

- `contextIsolation: true` — always
- `nodeIntegration: false` — always
- `sandbox: true` — for renderer
- Never load remote/untrusted URLs in BrowserWindow
- All main↔renderer communication goes through typed IPC via preload
- Preload script exposes ONLY specific API methods, never raw `ipcRenderer`
- Define all IPC channels in `src/shared/ipc-channels.ts` — single source of truth
- Validate all data crossing the IPC boundary

### Player Abstraction

- All playback goes through the `IPlayer` interface in `src/main/player/player.interface.ts`
- Never call mpv directly from renderer or services — always through the interface
- This allows swapping mpv for another backend later without touching the rest of the app

### Database

- SQLite via better-sqlite3 in the main process only
- Renderer accesses data exclusively through IPC calls
- Migrations managed via versioned SQL files in `src/main/services/migrations/`
- Never store credentials in plaintext — use Electron safeStorage API

### Code Conventions

- TypeScript strict mode — no `any` unless absolutely unavoidable
- Functional React components with hooks only — no class components
- Tailwind for styling — no CSS modules, no styled-components
- Named exports only — no default exports (except page-level components if needed by router)
- Error handling: use Result types for service layer, try/catch at IPC boundary
- File naming: kebab-case for files, PascalCase for components, camelCase for functions/variables
- One component per file

### What NOT To Do

- Do not use `webSecurity: false`
- Do not disable `contextIsolation`
- Do not use `shell.openExternal` with unvalidated URLs
- Do not render provider HTML/content in BrowserWindow
- Do not store M3U/Xtream credentials in plaintext files
- Do not couple any service directly to mpv — use the player interface
- Do not add features not in the current phase without explicit approval
- Do not add unnecessary abstractions — keep it simple until complexity is earned
