// Inline SVG strings for the hex frame states. Direct ports of the desktop
// assets at src/renderer/assets/hex-frames/*.svg, rendered via
// react-native-svg's SvgXml.
//
// Mobile note: the desktop frames stack three Gaussian-blur passes for the
// outer glow. On Android that lands as a per-card render-to-bitmap with
// shadow blur every frame — drop the FPS through the floor when 50+ cards
// are visible. We keep the gradient strokes, bevel, inner fill, and
// corner accents (visually 95% of the look at phone-card sizes) and strip
// the blur filter. The frame paints flat with no extra GPU work, then
// HexCard wraps it in renderToHardwareTextureAndroid so the rasterized
// result is cached across scrolls.

import { colors } from '../../styles/theme';

const ACCENT_MID = '#00DDBB';
const ACCENT_FAR = '#00BBDD';
const ACCENT_FAR_H = '#00DDEE';
const ACCENT_HIGHLIGHT = '#33FFD0';
const ACCENT_LIGHT = '#66FFCC';
const ACCENT_LIGHT_H = '#88FFDD';
const ACCENT_LIGHT_2 = '#66FFDD';
const ACCENT_CORNER = '#44FFCC';
const BEVEL_BOTTOM = '#003322';
const INNER_FILL_TOP_N = '#0a1a18';
const INNER_FILL_TOP_H = '#0c2220';
const INNER_FILL_BOT = '#040a0e';

export const HEX_FRAME_NORMAL = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 230" fill="none">
  <defs>
    <linearGradient id="outerGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="${colors.accent}" stop-opacity="0.85"/>
      <stop offset="35%" stop-color="${ACCENT_MID}" stop-opacity="0.5"/>
      <stop offset="65%" stop-color="${ACCENT_FAR}" stop-opacity="0.5"/>
      <stop offset="100%" stop-color="${colors.teal}" stop-opacity="0.85"/>
    </linearGradient>
    <linearGradient id="bevelGrad" x1="50%" y1="0%" x2="50%" y2="100%">
      <stop offset="0%" stop-color="${ACCENT_LIGHT}" stop-opacity="0.4"/>
      <stop offset="30%" stop-color="${colors.accent}" stop-opacity="0.08"/>
      <stop offset="100%" stop-color="${BEVEL_BOTTOM}" stop-opacity="0"/>
    </linearGradient>
    <radialGradient id="innerFill" cx="50%" cy="40%" r="60%">
      <stop offset="0%" stop-color="${INNER_FILL_TOP_N}" stop-opacity="0.8"/>
      <stop offset="100%" stop-color="${INNER_FILL_BOT}" stop-opacity="0.95"/>
    </radialGradient>
    <linearGradient id="cornerGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="${ACCENT_CORNER}" stop-opacity="1"/>
      <stop offset="100%" stop-color="${colors.teal}" stop-opacity="0.3"/>
    </linearGradient>
  </defs>
  <polygon points="100,12 185,62 185,168 100,218 15,168 15,62" fill="url(#innerFill)"/>
  <polygon points="100,12 185,62 185,168 100,218 15,168 15,62" fill="url(#bevelGrad)"/>
  <polygon points="100,8 188,60 188,170 100,222 12,170 12,60" stroke="url(#outerGrad)" stroke-width="1.8" fill="none"/>
  <polygon points="100,14 183,64 183,166 100,216 17,166 17,64" stroke="url(#outerGrad)" stroke-width="0.5" fill="none" opacity="0.25"/>
  <line x1="100" y1="8" x2="148" y2="36" stroke="url(#cornerGrad)" stroke-width="2.5" stroke-linecap="round"/>
  <line x1="100" y1="8" x2="52" y2="36" stroke="url(#cornerGrad)" stroke-width="2.5" stroke-linecap="round"/>
  <line x1="100" y1="222" x2="145" y2="196" stroke="url(#cornerGrad)" stroke-width="2" stroke-linecap="round" opacity="0.4"/>
  <line x1="100" y1="222" x2="55" y2="196" stroke="url(#cornerGrad)" stroke-width="2" stroke-linecap="round" opacity="0.4"/>
</svg>`;

export const HEX_FRAME_HOVER = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 230" fill="none">
  <defs>
    <linearGradient id="outerGradH" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="${colors.accentHover}" stop-opacity="1"/>
      <stop offset="30%" stop-color="${colors.accent}" stop-opacity="0.8"/>
      <stop offset="70%" stop-color="${ACCENT_FAR_H}" stop-opacity="0.8"/>
      <stop offset="100%" stop-color="#33DDFF" stop-opacity="1"/>
    </linearGradient>
    <linearGradient id="bevelGradH" x1="50%" y1="0%" x2="50%" y2="100%">
      <stop offset="0%" stop-color="${ACCENT_LIGHT_H}" stop-opacity="0.5"/>
      <stop offset="25%" stop-color="${colors.accentHover}" stop-opacity="0.15"/>
      <stop offset="100%" stop-color="${BEVEL_BOTTOM}" stop-opacity="0"/>
    </linearGradient>
    <radialGradient id="innerFillH" cx="50%" cy="40%" r="60%">
      <stop offset="0%" stop-color="${INNER_FILL_TOP_H}" stop-opacity="0.75"/>
      <stop offset="100%" stop-color="${INNER_FILL_BOT}" stop-opacity="0.9"/>
    </radialGradient>
    <linearGradient id="pulseGrad" x1="0%" y1="0%" x2="100%" y2="0%">
      <stop offset="0%" stop-color="${colors.accent}" stop-opacity="0"/>
      <stop offset="50%" stop-color="${ACCENT_HIGHLIGHT}" stop-opacity="0.6"/>
      <stop offset="100%" stop-color="${colors.teal}" stop-opacity="0"/>
    </linearGradient>
    <linearGradient id="cornerGradH" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="${ACCENT_LIGHT_2}" stop-opacity="1"/>
      <stop offset="100%" stop-color="#33DDFF" stop-opacity="0.5"/>
    </linearGradient>
  </defs>
  <polygon points="100,12 185,62 185,168 100,218 15,168 15,62" fill="url(#innerFillH)"/>
  <polygon points="100,12 185,62 185,168 100,218 15,168 15,62" fill="url(#bevelGradH)"/>
  <polygon points="100,8 188,60 188,170 100,222 12,170 12,60" stroke="url(#outerGradH)" stroke-width="2.2" fill="none"/>
  <polygon points="100,14 183,64 183,166 100,216 17,166 17,64" stroke="url(#outerGradH)" stroke-width="0.6" fill="none" opacity="0.35"/>
  <line x1="12" y1="115" x2="188" y2="115" stroke="url(#pulseGrad)" stroke-width="0.8" opacity="0.3"/>
  <line x1="100" y1="8" x2="152" y2="38" stroke="url(#cornerGradH)" stroke-width="3" stroke-linecap="round"/>
  <line x1="100" y1="8" x2="48" y2="38" stroke="url(#cornerGradH)" stroke-width="3" stroke-linecap="round"/>
  <line x1="100" y1="222" x2="148" y2="195" stroke="url(#cornerGradH)" stroke-width="2.5" stroke-linecap="round" opacity="0.6"/>
  <line x1="100" y1="222" x2="52" y2="195" stroke="url(#cornerGradH)" stroke-width="2.5" stroke-linecap="round" opacity="0.6"/>
</svg>`;

// Clip-path polygon used over the inner image so it stays inside the hex body.
// Matches the desktop `.clip-hex-tall` utility in global.css.
export const HEX_CLIP_POINTS = '100,8 186,59 186,171 100,222 14,171 14,59';
