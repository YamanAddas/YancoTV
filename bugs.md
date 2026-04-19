# YancoTV — Bug Register

Living register of **open** bugs across desktop and mobile. Closed bugs move to the changelog; incident reports (deep post-mortems or one-off refactor bug dumps) go to [docs/incidents/](docs/incidents/).

**Last updated:** 2026-04-19
**Format:** ID · Platform · Severity · One-line · Status · First seen · Fix target

| ID | Platform | Sev | Summary | Status | First seen | Fix target |
|---|---|---|---|---|---|---|
| MB-13 | Mobile | High | Player takes two back presses to close; surface state desyncs between routes | In flight | 2026-04-12 | M4R.7 (persistent MiniPlayer) — partially landed in `27a0f34` |
| MB-14 | Mobile | High | HEVC-main10 / AC3 / EAC3 / DTS / TrueHD decode audio-only on ~30% of streams | Open | 2026-04-10 | M8R (FFmpeg ExoPlayer extension, vendored jniLibs) |
| MB-15 | Mobile | Medium | First-frame blocked by hydration gate on cold boot | Open | 2026-04-14 | M4R.6 (cached-first boot) |
| MB-16 | Mobile | High | SearchScreen crashes during fast typing | **Fixed** | 2026-04-14 | FlatList virtualization (2026-04-19) — full rebuild as SearchOverlay in M4R |
| MB-17 | Mobile | Medium | Navigation sluggish across the whole app | In flight | 2026-04-12 | M4R (paged SQL + collapsed navigator + CachedImage) |
| MB-18 | Desktop | Critical | Electron boot `ERR_UNSUPPORTED_DIR_IMPORT` on `@yancotv/core` internal imports | **Fixed** | 2026-04-19 | Explicit `.js` extensions on all core internal re-exports (2026-04-19) |
| MB-19 | Mobile | Critical | PhoneLayout crams 260-wide LeftRail into 56px horizontal strip — categories + Sources button unreachable | Open | 2026-04-19 | New task — PhoneShell component |
| MB-20 | Mobile | High | `Platform.isTV` misdetects on some Fire TV / GTV boxes → those devices fall into broken PhoneLayout | Open | 2026-04-19 | New task — robust `isTelevision()` helper (UiMode + feature flag) |
| MB-21 | Mobile | High | No SafeAreaView / status bar inset on HomeShell — top content covered on notched phones | Open | 2026-04-19 | New task — wrap in SafeAreaView with proper insets |
| MB-22 | Mobile | Medium | `ContentPanel.tsx:56` dead conditional (`category.kind === 'type' ? category.type : category.type` — both branches identical) | Open | 2026-04-19 | Fix inline |
| MB-23 | Mobile | Medium | No `hasTVPreferredFocus` on first mount — D-pad does nothing until click | Open | 2026-04-19 | M4R.10 (focus primitive) |

## How to use this register

- **Adding a bug:** append a row with the next MB-NN (mobile) or DB-NN (desktop) ID. Keep summary to one line; details go in the fix commit.
- **Fixing a bug:** flip Status to **Fixed**, add commit ref to the Fix target column, and remove the row on the next doc pass once the fix has stabilized.
- **Dumping a refactor-wide set of bugs:** don't list them here. Create `docs/incidents/YYYY-MM-DD-<topic>.md` and link it from the Status column (e.g. `Open — see [incident](docs/incidents/...)`).

## Cross-references

- [docs/incidents/2026-04-16-html5-player-refactor.md](docs/incidents/2026-04-16-html5-player-refactor.md) — desktop HTML5 refactor bug dump (bugs 1–29, archived)
- [PRODUCTION_PLAN_ANDROID.md](PRODUCTION_PLAN_ANDROID.md) § Reboot Notice — M4R rebuilds the mobile shell; MB-13/15/17/19–23 are all scoped there
