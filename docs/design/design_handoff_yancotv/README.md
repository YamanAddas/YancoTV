# Handoff: YancoTV — "Frosted Glass Emerald" Concept A

## Overview

YancoTV is an Android TV IPTV / live-streaming client. This handoff covers **Concept A — "Frosted Glass Emerald"**, a complete hi-fi UI redesign spanning eight surfaces: Home, Live TV, VOD Player, Live Player, Player Options Sheet, Settings (14 tabs), Sources Manager, and a Components reference.

The visual language is built around:
- A **dark emerald** base palette (deep near-black greens `#050A08 → #14251F`) with a single bright emerald accent `#00E28A`.
- A **beveled hex shape family** (`YancoShapes.kt`) — every chip, button, card, and capsule shares the same 45° cut-corner DNA, giving the UI a distinctive "cut circuit board" silhouette without feeling sci-fi.
- **Frosted-glass overlays** (`backdrop-filter: blur(…)`) for hero cards, player chrome, and sheets — content blurs *through* the UI rather than sitting on opaque panes.
- A **TV-first focus model**: every interactive surface has a 1.5px solid accent border + 3px outer `rgba(102,240,181,.35)` ring + 40px halo, with 180ms scale-to-1.08× on focus. No hover states, no ripples.
- **Inter** for all sans copy + **JetBrains Mono** with `tabular-nums` for every numeric readout (clock, bitrate, channel numbers, IDs).

## About the Design Files

The files in this bundle are **design references created in HTML** — high-fidelity prototypes showing intended look, layout, typography, spacing, and behaviour. **They are not production code to copy directly.** React + Babel-in-browser was used to prototype quickly; ignore that runtime entirely when implementing.

Your task is to **recreate these designs in the target codebase's existing environment** — from the existing folder structure (`yanco-ds.css`, token naming, and one-off `YancoPalette` references in the mocks) this appears to be a **Kotlin / Jetpack Compose** Android TV app. Use Compose + Material 3 for TV, the existing `YancoPalette` and `YancoShapes` theme objects, and the existing Coil/Media3 integrations where you see them referenced.

If the target environment is something else (React, SwiftUI, etc.), use the same tokens and layouts but bind them to your existing component library rather than porting the HTML verbatim.

## Fidelity

**High-fidelity.** Pixel-perfect mockups with final colors, typography, spacing, shape geometry, and motion specs. All values are either stated inline in this README, declared as CSS custom properties in `designs/yanco-ds.css`, or visible as exact `style={{…}}` objects in the JSX files. The Components page (`designs/components.html`) is the canonical source of truth for any token — if this README and the components page disagree, trust the components page.

## Canvas & Scale

Every surface is designed on a **1920×1080 stage** (TV-native 16:9). The HTML mocks scale-to-fit using `transform: scale()` on a fixed 1920×1080 root; production should use the system's native dp/sp units. Rule of thumb:

| HTML px @ 1080 | Compose dp (TV) |
|---|---|
| 4px  | 4dp  |
| 8px  | 8dp  |
| 12px | 12dp |
| 16px | 16dp |
| 24px | 24dp |

**Minimum text size on TV:** 13px (≈ 13sp). Body copy is 14–17px. Numeric readouts are 14px+ mono. Display titles reach 76–88px.

---

## Design Tokens

### Colors — `YancoPalette`

All tokens are in `designs/yanco-ds.css` as CSS custom properties. They map 1:1 to the Compose `YancoPalette` object.

#### Canvas
| Token | Hex | Use |
|---|---|---|
| `BackgroundDeep` / `--bg-deep` | `#050A08` | Root background, behind everything |
| `BackgroundRaised` / `--bg-raised` | `#0A1410` | Cards, nav rails, sheets |
| `BackgroundHover` / `--bg-hover` | `#0F1C17` | Subtle hover / resting raised |
| `BackgroundElevated` / `--bg-elevated` | `#14251F` | Top-layer panels, modals |

#### Text
| Token | Hex | Use |
|---|---|---|
| `TextPrimary` / `--text-primary` | `#F0FFF6` | Headings, primary copy (tinted white) |
| `TextSecondary` / `--text-secondary` | `#A7B8AF` | Body supporting copy |
| `TextMuted` / `--text-muted` | `#5F7068` | Kickers, captions, metadata |
| `TextFaint` / `--text-faint` | `#3A4A43` | Disabled text, placeholder strokes |

#### Accent
| Token | Hex | Use |
|---|---|---|
| `Accent` / `--accent` | `#00E28A` | Primary action color, focus borders, progress fills |
| `AccentSoft` / `--accent-soft` | `#66F0B5` | Focus rings, hover glow, gradient top-stop |
| `AccentDeep` / `--accent-deep` | `#00B872` | Gradient bottom-stop, pressed state |
| `AccentMuted` / `--accent-muted` | `#1C7A55` | Low-emphasis accent fills |

#### Feedback
| Token | Hex | Use |
|---|---|---|
| `Live` / `--live` | `#FF3B3B` | On-air badge, live indicator dot |
| `Premium` / `--premium` | `#D7B36A` | 4K / HDR / catch-up marker |
| `Error` / `--error` | `#FF6B6B` | Error states, destructive actions |
| `FocusRing` / `--focus-ring` | `#66F0B5` | Outer glow around focused elements |

#### Borders & scrims
- `--border-subtle: rgba(255,255,255,0.08)` — default card border
- `--panel-border: rgba(255,255,255,0.12)` — raised panel border
- `--scrim: rgba(5,10,8,0.8)` — dim behind sheets
- `--veil: rgba(0,0,0,0.4)` — soft image darken

### Typography

```
--font-sans: 'Inter', system-ui, -apple-system, sans-serif
--font-mono: 'JetBrains Mono', ui-monospace, monospace
```

Font-feature-settings on `body`: `'ss01','ss03','cv11'` — unlocks Inter's slashed zero and single-storey `a/g`. Mono always uses `font-variant-numeric: tabular-nums` (class `.mono` or `.tab-nums`).

**Type scale** (size / weight / letter-spacing):

| Role | Size | Weight | LS | Font |
|---|---|---|---|---|
| Display (hero, section headings) | 76–88px | 900 | -0.035em | Inter |
| Page title | 48px | 800 | -0.02em | Inter |
| H2 | 32px | 900 | -0.02em | Inter |
| H3 / card title | 20–22px | 800-900 | -0.01em | Inter |
| Body strong | 16px | 600 | 0 | Inter |
| Body | 15px | 400 | 0 (line-height 1.55) | Inter |
| Caption | 12–13px | 400–500 | 0 | Inter |
| Kicker | 10px | 700 | 0.22em (uppercase) | JetBrains Mono |
| Kicker muted | 10px | 700 | 0.22em (uppercase, `--text-muted`) | JetBrains Mono |
| Numeric readout (clock, ch#) | 14–44px | 700–900 | 0.08–0 | JetBrains Mono, tabular-nums |

**Display copy gradient** (used on hero titles and page titles):
```css
background: linear-gradient(180deg, #F0FFF6 0%, #A7D9BE 100%);
-webkit-background-clip: text;
-webkit-text-fill-color: transparent;
```

### Shapes — `YancoShapes`

All shapes use CSS `clip-path` in the mocks — in Compose, use equivalent `CutCornerShape` / `GenericShape`. The 7-member family:

| Name | Class | Cut | Use |
|---|---|---|---|
| `ChipBevel` | `.y-chip` | 10dp · top-left + bottom-right | Small chips, icon tiles, row cells |
| `ButtonBevel` | `.y-btn` | 14dp · top-left + bottom-right | Primary and secondary buttons |
| `CutCornerCard` | `.y-card` | 22dp · top-left + bottom-right | Standard cards, panels |
| `CutCornerCardLarge` | `.y-card-l` | 32dp · top-left + bottom-right | Hero cards, sheets |
| `HexCapsule` | `.y-hex` | 16dp point · left + right | Status badges, tab hexes |
| `HexCapsuleSmall` | `.y-hex-sm` | 10dp point · left + right | Tiny inline tags |
| `HexPill` | `.y-pill` | 20dp point · left + right | Large CTAs ("PLAY NOW"), filter pills |

**Compose equivalents** — for the beveled TL/BR cuts:
```kotlin
val ChipBevel = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
val ButtonBevel = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)
val CutCornerCard = CutCornerShape(topStart = 22.dp, bottomEnd = 22.dp)
val CutCornerCardLarge = CutCornerShape(topStart = 32.dp, bottomEnd = 32.dp)
```

For the **horizontal hex** (points on left + right) — use `GenericShape` drawing a 6-point polygon as defined by the `clip-path` in `yanco-ds.css` lines 55–60.

### Spacing

8px grid. Common values: `6, 8, 10, 12, 14, 18, 22, 24, 32, 40, 56, 80, 96, 120`.

Screen padding: **80–96px horizontal**, **40px vertical** on 1920×1080. Rails gap: **18–24px**. Card inner padding: **22–32px**.

### Elevation & Shadows

Four levels (see Components page, §11):

| Level | Shadow | Backdrop blur |
|---|---|---|
| 0 · flat | none | — |
| 1 · card | `0 6px 14px rgba(0,0,0,0.4), inset 0 1px 0 rgba(255,255,255,0.06)` | — |
| 2 · raised | `0 20px 40px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.04)` | — |
| 3 · sheet (frosted) | `0 30px 80px rgba(0,0,0,0.7), 0 0 0 1px rgba(0,226,138,0.3), inset 0 1px 0 rgba(255,255,255,0.06)` | `blur(24px)` |

### Motion

TV-first — no ripples, no hover tracking. Four primitives:

| Event | Duration | Easing | Change |
|---|---|---|---|
| Focus in/out | 180ms | ease-out | scale 1→1.08, focus ring opacity 0→1, border fade |
| Rail cross-fade (Ambient Echo) | 240ms | ease-in-out | Hero backdrop swaps image on rail focus change |
| Panel in/out (sheet, overlay) | 320ms | cubic-bezier(0.2, 0, 0, 1) | Slide from right + 0→1 opacity |
| Live dot pulse | 1600ms ∞ | ease-in-out | Opacity 1→0.3→1 (55% hold, 30% fade) |

Focus ring rule (used everywhere):
```
border: 1.5px solid var(--accent);
box-shadow: 0 0 0 3px rgba(102,240,181,0.35), 0 20px 40px rgba(0,226,138,0.3);
```

---

## Screens / Views

The TOC (`designs/index.html`) links to every surface. Build order below is recommended.

### 1. Home — `designs/YancoTV Concept A.html`

**Purpose:** Landing / hub. Presents a curated hero + 3 content rails (Continue, Live Now, Discover).

**Layout (1920×1080):**
- **Left rail · 88px** — collapsed navigation with 7 hex icon tiles stacked vertically: Home, Live TV, Movies, Series, Sports, Search, Profile. Each is a 56×56 `.y-hex` tile; active tile has accent fill.
- **Main region · fluid** starting at x=88, padding 80px top / 96px horizontal.
  - **Top bar (h=64):** crumb-bar left ("HOME · FEATURED"), clock + network status right (mono, tabular-nums).
  - **Hero (h≈520):** Full-width `.y-card-l` (32dp cuts). Left 50% is frosted-glass card (`backdrop-filter: blur(32px)`) over a blurred poster, right 50% shows the crisp poster. Frosted card contains: kicker "FEATURED · TONIGHT", display title (76px gradient), two-line tagline (17px secondary), meta chips row ("MOVIE · 2024 · 2h 14m · 4K HDR"), two buttons: primary `HexPill` "▶ PLAY NOW" + secondary `HexBtn` "＋ MY LIST".
  - **Rail section (starts y≈640):**
    - Category chip row: 8 `HexChip`s in a horizontal strip, first one active ("ALL"), rest muted.
    - Three rails stacked with 40px gap. Each rail = 24px kicker + rail-title row + 6 `.y-card` thumbnails (320×180 with 22dp cut-corner).
  - **Ambient Echo:** when D-pad focus enters a card on a rail, the Hero backdrop cross-fades (240ms) to that card's poster. The frosted glass card stays in place — only the underlying image changes.

**Key components used:** `YancoAppBar` (left rail), `HexPill`, `HexChip`, `HexBtn`, `YCard` (both sizes), gradient text, frosted-glass overlay.

### 2. Live TV — section within Home, also standalone preview

**Purpose:** Browse live channels with EPG preview.

**Layout:** Replaces the main region content when "Live TV" is active in the nav. A 6-column grid of channel preview cards. Each card shows: 16:9 live thumbnail, channel logo tile (top-left overlay, white backdrop, 10px type), channel number (mono, bottom-left), live dot (top-right), program title + EPG progress bar on hover/focus.

### 3. VOD Player — `designs/vod.html`

**Purpose:** Full-screen playback for movies and series episodes. **5 canonical states**, each laid out as a separate 1920×1080 artboard inside a `design-canvas`:

1. **Default controls** — bottom chrome visible on user input: title block (left), progress bar (center, 4px height, accent fill + scrubber handle + 16px glow), time readouts (mono tabular on each side), primary controls (◀◀ 10s · ▶/⏸ · 10s ▶▶) centered above bar, right cluster (CC, audio, quality, options, fullscreen). All on a bottom gradient scrim `linear-gradient(180deg, transparent, rgba(5,10,8,0.9))`.
2. **Next up** — 5 seconds before end, slide-in card at bottom-right (400×220): next episode poster + title + "PLAYS IN 04s" + CANCEL button.
3. **Buffering** — center overlay: spinning hex icon (rotates 360° / 1.4s linear), "BUFFERING…" kicker below, bitrate readout line.
4. **Error** — center overlay card (`.y-card-l`): red hex icon with warn glyph, "PLAYBACK ERROR" title, `E_MEDIA_STALLED · 408` mono readout, RETRY + BACK buttons.
5. **End card** — dimmed backdrop, centered `.y-card-l` with "COMPLETED" + stats ("103m watched · 4K HDR · 142 Mbps avg") + two CTAs: "PLAY NEXT" primary, "MORE LIKE THIS" secondary.

Tabs/tweaks at the bottom of the canvas switch between the five states; see `vod.html` for the exact chrome.

### 4. Live Player — `designs/live-player.html`

**Purpose:** Full-screen channel playback with EPG / timeshift / catch-up affordances. **6 canonical states:**

1. **Channel zap** — slim channel badge slides from top-left on channel change: logo tile + channel number + program title + EPG progress. Lives for 2s then slides out.
2. **Info panel** — long-press Info on remote: bottom sheet (h≈260) with 3-column grid: now playing (title, synopsis, EPG bar), next up, in 1 hour.
3. **Timeshift** — back-button during live: special timeshift bar replaces the EPG line, showing START → "HERE" marker (premium gold) → LIVE marker (red). Transport controls add a "JUMP TO LIVE" button.
4. **Channel surf** — up/down on D-pad: overlay strip at top (h=110) showing 5 adjacent channels in a horizontal rail, current one centered and highlighted.
5. **Catch-up** — from EPG, rewind into past programs: bar shows past-program segments (gold gradient), title block adds a "CATCH-UP · 2h 14m AGO" chip.
6. **Signal error** — source-level failure: center card with `SIGNAL LOST` title, mono error ID, "TRY ANOTHER SOURCE" + "RETRY" buttons. Preserves channel badge to show user which channel errored.

### 5. Player Options Sheet — `designs/options-sheet.html`

**Purpose:** Right-side sheet that slides in from the right edge during playback (both VOD + Live). Width 560px, full-height, frosted-glass (blur 24px), `clip-path` uses 22dp cut on top-left + bottom-left only. **10 sub-panels** accessible via a left mini-tab-rail inside the sheet:

1. **Audio tracks** — list of tracks with language + codec + bitrate chips
2. **Subtitles** — track list + style controls (size slider, bg opacity, font family)
3. **Video quality** — Auto + manual ladder (2160p, 1080p, 720p, 480p) with current bandwidth
4. **Aspect / fit** — Fit, Fill, Stretch, 16:9, 4:3, Original
5. **Playback speed** — 0.5×–2.0× slider with preset chips
6. **Sleep timer** — Off, 15, 30, 45, 60, 90 min
7. **Cast / AirPlay** — device picker
8. **Stats (nerd mode)** — realtime bitrate, dropped frames, resolution, codec, buffer health — all in JetBrains Mono tabular
9. **Report an issue** — short form with category chips + note field
10. **Keyboard shortcuts** — reference list (also in Settings › Shortcuts)

Each sub-panel has: 24px top kicker, 28px title, content rows using the shared `Section` / `Row` primitives defined in `pages/settings-tabs.jsx`.

### 6. Settings — `designs/settings.html` + `designs/pages/settings-tabs.jsx`

**Purpose:** Full app preferences. 14 tabs, hex-nav left rail, detail pane right.

**Layout:**
- **Hex nav rail (left, 300px):** scroll column of hex-cornered tab items (`.y-card` with 14dp cuts). Each row: hex icon tile + label + 2-line subtitle (e.g. `"01 · lang · startup"`). Active row has accent fill + left accent bar; focused row has standard focus ring.
- **Detail pane (right, remaining):** scroll view, 48px outer padding, content width capped at 1000px.

**The 14 tabs, in order:**

| # | ID | Label | Key controls |
|---|---|---|---|
| 1 | `general` | General | Open on (HOME/LAST/LIVE), sidebar mode, startup channel, region, language |
| 2 | `appearance` | Appearance | Theme picker (Frosted Emerald / Onyx / Light), accent color chips, font size slider, reduce motion, show kicker labels |
| 3 | `playback` | Playback | Resize mode, HW decoding strategy (HW / HW+FALLBACK / SW), buffer size, seek step, auto-play next |
| 4 | `subtitles` | Subtitles | Size, BG opacity, stroke weight, font, default language, forced tracks |
| 5 | `network` | Network | HTTP User-Agent preset + custom, connect timeout slider, read timeout slider, proxy block (host/port/auth) |
| 6 | `sources` | **Sources** | Summary strip (5 metrics), installed sources list (deep-links to `sources.html#src-N`), auto-sync schedule, Wi-Fi only, parallel downloads, EPG match strictness, duplicate priority rule |
| 7 | `groups` | Groups | Live / VOD / Series tabs, pinned groups, hidden groups, reorder |
| 8 | `epg` | EPG | Forward/back window sliders, EPG offset, fetch schedule, fallback source |
| 9 | `parental` | Parental | Hide adult categories, require PIN for settings, PIN set/reset |
| 10 | `recordings` | Recordings | Storage quota, pre-roll / post-roll padding, retention, storage target |
| 11 | `notifications` | Notifications | Reminders, recording events, source sync events, errors |
| 12 | `storage` | Storage | Per-bucket usage bars (images, VOD cache, EPG cache, logs), total, clear buttons |
| 13 | `shortcuts` | Shortcuts | Remote shortcut reference table grouped by context (LIVE / VOD / NAV) |
| 14 | `about` | About | Version, build, device info, licenses, legal, reset |

**Settings primitives** — defined at the top of `pages/settings-tabs.jsx` and reused in every tab:
- `<Section title sub right>` — 22px bold title, accent gradient rule, optional subtitle
- `<Row label hint right kicker>` — card with label + hint + trailing control
- `<ChipRow options value onChange>` — group of toggle chips
- `<Slider value min max unit onChange presets>` — full custom slider with preset chips
- `<Select value options onChange>` — hex pill (non-functional in mocks, render as Compose DropdownMenu)
- `<TextField value placeholder mono>` — beveled input

### 7. Sources Manager — `designs/sources.html`

**Purpose:** Dedicated full-screen surface for managing playlists / portals / tuners. Deep-linked from Settings › Sources.

**Surfaces within:**
- **List view** (default) — header with summary metrics, filter chip row (ALL / XTREAM / M3U / STALKER / HDHOMERUN / ERROR), source table.
- **Detail view** (row click) — slide-in overlay with source stats, channel count per group, sync history log, "SYNC NOW" + "EDIT" + "DELETE" actions.
- **Edit dialog** — form matching whichever source type (URL, credentials, EPG URL, priority).
- **Add Source wizard** — 4-card chooser: XTREAM-CODES, M3U + EPG, STALKER, HDHOMERUN. Each type reveals its own field set.

### 8. Components Reference — `designs/components.html`

**Purpose:** Design-system reference. Canonical source of truth for every token and primitive. **This is where you verify any questionable value.** 12 sections:

01 Color tokens · 02 Typography · 03 Shapes · 04 Buttons · 05 Chips · 06 Cards · 07 Progress & sliders · 08 List rows · 09 Icon set (48 custom line icons, 1.8 stroke, 24×24 grid) · 10 Focus ring & motion · 11 Elevation · 12 Motion principles.

---

## Navigation Map

```
Home (index route)
├── Hero content → VOD Player
├── Rail card focus → Ambient Echo updates hero backdrop
├── Nav · Live TV → Live TV screen → channel → Live Player
├── Nav · Movies / Series / Sports → scoped Home
├── Nav · Search → search overlay (not designed — placeholder)
├── Nav · Profile → profile sheet (not designed — placeholder)
└── Settings
    └── 14 tabs, one of which (Sources) deep-links into the full Sources Manager
Sources Manager (standalone surface) ↔ Settings › Sources
Player Options Sheet (overlay from both VOD + Live Players)
```

## Interactions & Behavior

- **D-pad navigation:** all focusable elements must have a visible focus state (the accent ring + 1.08× scale). Focus order is strictly visual: top-to-bottom, left-to-right within each region.
- **Back button:** exits sheets/overlays first, then pops navigation stack.
- **Info / Menu button** during playback: opens the **Player Options Sheet** (slide from right).
- **Long-press Info** during Live playback: opens the **Info panel** (bottom sheet).
- **Up/Down** during Live fullscreen: channel surf overlay (held 2s then auto-zap).
- **Back** during Live playback: enters **Timeshift** mode.
- **Ambient Echo:** on Home, rail-card focus change triggers a 240ms cross-fade on the hero backdrop image behind the frosted glass card. The glass card content remains static (it's the featured item for the currently focused rail).
- **Live dot:** the red `.live-dot` element is used everywhere (channel badges, cards, player chrome). Always the same 6×6px red circle with 10px glow + 1.6s breathing pulse.

## State Management (what to expose in the store)

- `focusedRailId` / `focusedCardId` — drives Ambient Echo
- `activeNavTab` — drives left rail highlight and main region content
- `player.state` — `idle | buffering | playing | paused | error | ended`
- `player.source` — current source id + playback position (persist to disk on change; the mocks do this via `localStorage`)
- `player.ui` — `chrome | options-sheet | info-panel | channel-surf | timeshift`
- `settings.*` — one slice per tab, serialised to disk
- `sources[]` — per-source sync state machine: `idle | syncing | synced | stale | error` + timestamps

## Assets

- **Fonts:** Inter (400/500/600/700/800/900) + JetBrains Mono (400/500/700). Bundle with the app; do not fetch from Google Fonts on TV.
- **Icons:** all 48 icons are defined as inline SVG path data in `designs/yanco-icons.jsx` (exported as object `I`). Port to Compose by pasting the path strings into `Icons.Rounded` equivalents or custom `ImageVector`s. Stroke 1.8, line caps round, 24×24 viewBox.
- **Placeholder imagery:** the mocks use CSS gradient + noise placeholders where posters should go. Replace with real Coil-loaded art from your catalog pipeline.
- **No other binary assets are required** — everything visual is either a token, a shape, or an icon path.

## Files

| Path | Purpose |
|---|---|
| `designs/index.html` | TOC linking every surface |
| `designs/YancoTV Concept A.html` | Home + Live TV |
| `designs/vod.html` | VOD Player (5 states) |
| `designs/live-player.html` | Live Player (6 states) |
| `designs/options-sheet.html` | Player Options Sheet (10 sub-panels) |
| `designs/settings.html` | Settings shell (14-tab hex nav) |
| `designs/pages/settings-tabs.jsx` | All 14 tab contents |
| `designs/sources.html` | Sources Manager |
| `designs/components.html` | **Canonical component reference** |
| `designs/yanco-ds.css` | All design tokens + shape clip-paths + primitives |
| `designs/ds/ds.jsx` | Shared JSX primitives (HexBtn, HexChip, HexPill, YCard, Toggle, Icon, I) |
| `designs/yanco-icons.jsx` | Home/Live TV icon set (superseded by `I` in ds.jsx for the full app) |
| `designs/yanco-app.jsx` | Home composition |
| `designs/yanco-parts.jsx` | Home primitives (AppBar, Hero, Rail) |
| `designs/yanco-livetv.jsx` | Live TV grid |
| `designs/yanco-data.jsx` | Mock catalog data |
| `designs/design-canvas.jsx`, `tweaks-panel.jsx` | Presentation scaffolding — **ignore, not part of app** |

## Implementation Notes

- **Start from `components.html`** — build the full `YancoPalette`, `YancoShapes`, and primitive components (`HexBtn`, `HexChip`, `HexPill`, `YCard`, `YToggle`, `YSlider`, `YSelect`) before touching any screen. Every screen is a composition of these.
- **The Settings `Section` + `Row` + `Slider` + `Select` primitives** (in `pages/settings-tabs.jsx`) are the most-reused pattern in the app — lift them into your shared `ui/` module. Every Settings tab plus multiple Player Options sub-panels depend on them.
- **Shapes in Compose:** `CutCornerShape(topStart, bottomEnd)` covers the 4 bevel variants. The 3 hex-capsule variants need `GenericShape { Path().apply { … } }` — use the exact point coordinates from `yanco-ds.css` lines 55–60.
- **Focus ring:** wrap focusable composables in a `Modifier.onFocusChanged { }` + `Modifier.scale(animateFloatAsState(if (focused) 1.08f else 1f, spring))` + a conditional `Modifier.border(1.5.dp, Accent, shape) + Modifier.shadow(...)`.
- **Frosted-glass panels:** Compose doesn't have `backdrop-filter`. Use a `RenderEffect.createBlurEffect(32f, 32f, Shader.TileMode.CLAMP)` applied to a snapshot of the content behind, or use `HazeMaterial` / `@dev.chrisbanes.haze` as a production-ready path. Fall back to a semi-opaque solid if the device can't afford a blur pass.
- **Live dot animation:** use `rememberInfiniteTransition` with a keyframe spec matching the 55% hold, 30% fade pattern.
- **Tabular numbers:** set `TextStyle(fontFeatureSettings = "tnum")` on every numeric readout. Don't skip this — channel numbers, clocks, and bitrates all shift jarringly without it.
