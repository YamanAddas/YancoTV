# YancoTV — Claude Code Guide

@AGENTS.md

Everything above is the cross-tool guide. The rest of this file is Claude Code-specific.

## Working with this repo in Claude Code

- **Before touching `packages/android/` or `packages/shared/`, load the `native-android-mk` skill.** It encodes the MK.8 lesson set (threading, schema units, resume-point discipline, two-tap TV activation, semantics). 29 bugs shipped in one commit the last time this wasn't on screen.
- **IPTV domain questions** (M3U, Xtream, Stalker, XMLTV, title cleaning, classification) load the `iptv-domain` skill for protocol references and patterns.
- **Nested `CLAUDE.md` auto-loads** when working in `packages/android/` or `packages/shared/` — platform-specific rules live there, not here.

## Tool discipline

- Desktop bug fixes go in [bugs.md](bugs.md) with a `BUG-*` id. Native bugs use `MB-*` in [PRODUCTION_PLAN_NATIVE.md](PRODUCTION_PLAN_NATIVE.md).
- Commits: one `MK.*` sub-task per commit on native. MK.8 shipped three sub-tasks in one commit and the bug count scaled with commit size.
- Before committing a shell screen, self-audit against the `native-android-mk` skill checklist.
- **Don't push** without an explicit ask. Build + install + commit is fine; `git push` is not.
- **Fire TV is the canonical test target for native.** `adb connect 192.168.68.56:5555`, `./gradlew :app:installDebug` from `packages/android/`. Test on the phone (HT74J0206349) too for phone-specific code.

## Editing conventions

- TypeScript strict, no `any` unless unavoidable. Named exports (except framework-required defaults).
- Functional React components with hooks. kebab-case files, PascalCase components.
- Error handling: `Result<T>` in service layer, try/catch at module/IPC boundaries.
- No emoji in code or committed UI text — text-rendering is unreliable cross-device.
- One component per file.

## What NOT to do

- Don't couple desktop code to mpv directly (use `IPlayer`).
- Don't call `android.*` from `packages/shared/commonMain/`.
- Don't instantiate a second `ExoPlayer` — share via `switchTargetView()`.
- Don't add features outside the active plan. If it's not in a `MK.*` or Sprint task, amend the plan first.
- Don't touch `packages/mobile/` except for P0 RN bugs.

## Reference map

| Want | Go to |
|---|---|
| Monorepo layout + tech stacks | [AGENTS.md](AGENTS.md) (inlined above) |
| System architecture | [ARCHITECTURE.md](ARCHITECTURE.md) |
| Desktop roadmap | [PRODUCTION_PLAN.md](PRODUCTION_PLAN.md) |
| Native roadmap (ACTIVE) | [PRODUCTION_PLAN_NATIVE.md](PRODUCTION_PLAN_NATIVE.md) |
| Desktop bugs | [bugs.md](bugs.md) |
| Desktop releases | [CHANGELOG.md](CHANGELOG.md) |
| Decision log | [docs/adr/](docs/adr/) |
| MK.* lessons + checklist | `.claude/skills/native-android-mk/` |
| IPTV protocols + patterns | `.claude/skills/iptv-domain/` |
| Frozen RN reference | [packages/mobile/CLAUDE.md](packages/mobile/CLAUDE.md) |

Personal overrides (not committed): create `CLAUDE.local.md` at the repo root — auto-loaded, gitignored.
