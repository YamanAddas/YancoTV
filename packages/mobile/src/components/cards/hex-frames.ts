// Inline SVG strings for the three hex frame states. These are direct copies of
// the desktop assets at src/renderer/assets/hex-frames/*.svg and are rendered
// via react-native-svg's SvgXml so we don't need a metro SVG transformer.

export const HEX_FRAME_NORMAL = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 230" fill="none">
  <defs>
    <filter id="glow" x="-60%" y="-60%" width="220%" height="220%">
      <feGaussianBlur in="SourceGraphic" stdDeviation="4" result="b1"/>
      <feGaussianBlur in="SourceGraphic" stdDeviation="10" result="b2"/>
      <feGaussianBlur in="SourceGraphic" stdDeviation="18" result="b3"/>
      <feMerge>
        <feMergeNode in="b3"/>
        <feMergeNode in="b2"/>
        <feMergeNode in="b1"/>
        <feMergeNode in="SourceGraphic"/>
      </feMerge>
    </filter>
    <linearGradient id="outerGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#00FFAA" stop-opacity="0.85"/>
      <stop offset="35%" stop-color="#00DDBB" stop-opacity="0.5"/>
      <stop offset="65%" stop-color="#00BBDD" stop-opacity="0.5"/>
      <stop offset="100%" stop-color="#00CCFF" stop-opacity="0.85"/>
    </linearGradient>
    <linearGradient id="bevelGrad" x1="50%" y1="0%" x2="50%" y2="100%">
      <stop offset="0%" stop-color="#66FFCC" stop-opacity="0.4"/>
      <stop offset="30%" stop-color="#00FFAA" stop-opacity="0.08"/>
      <stop offset="100%" stop-color="#003322" stop-opacity="0"/>
    </linearGradient>
    <radialGradient id="innerFill" cx="50%" cy="40%" r="60%">
      <stop offset="0%" stop-color="#0a1a18" stop-opacity="0.8"/>
      <stop offset="100%" stop-color="#040a0e" stop-opacity="0.95"/>
    </radialGradient>
    <linearGradient id="cornerGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#44FFCC" stop-opacity="1"/>
      <stop offset="100%" stop-color="#00CCFF" stop-opacity="0.3"/>
    </linearGradient>
  </defs>
  <polygon points="100,8 188,60 188,170 100,222 12,170 12,60" stroke="url(#outerGrad)" stroke-width="1.5" fill="none" filter="url(#glow)" opacity="0.45"/>
  <polygon points="100,12 185,62 185,168 100,218 15,168 15,62" fill="url(#innerFill)"/>
  <polygon points="100,12 185,62 185,168 100,218 15,168 15,62" fill="url(#bevelGrad)"/>
  <polygon points="100,8 188,60 188,170 100,222 12,170 12,60" stroke="url(#outerGrad)" stroke-width="1.8" fill="none"/>
  <polygon points="100,14 183,64 183,166 100,216 17,166 17,64" stroke="url(#outerGrad)" stroke-width="0.5" fill="none" opacity="0.25"/>
  <line x1="100" y1="8" x2="148" y2="36" stroke="url(#cornerGrad)" stroke-width="2.5" stroke-linecap="round"/>
  <line x1="100" y1="8" x2="52" y2="36" stroke="url(#cornerGrad)" stroke-width="2.5" stroke-linecap="round"/>
  <line x1="100" y1="222" x2="145" y2="196" stroke="url(#cornerGrad)" stroke-width="2" stroke-linecap="round" opacity="0.4"/>
  <line x1="100" y1="222" x2="55" y2="196" stroke="url(#cornerGrad)" stroke-width="2" stroke-linecap="round" opacity="0.4"/>
  <line x1="188" y1="90" x2="194" y2="90" stroke="#00FFAA" stroke-width="1.2" opacity="0.3" stroke-linecap="round"/>
  <line x1="188" y1="115" x2="196" y2="115" stroke="#00FFAA" stroke-width="1.8" opacity="0.45" stroke-linecap="round"/>
  <line x1="188" y1="140" x2="194" y2="140" stroke="#00FFAA" stroke-width="1.2" opacity="0.3" stroke-linecap="round"/>
  <line x1="12" y1="90" x2="6" y2="90" stroke="#00CCFF" stroke-width="1.2" opacity="0.3" stroke-linecap="round"/>
  <line x1="12" y1="115" x2="4" y2="115" stroke="#00CCFF" stroke-width="1.8" opacity="0.45" stroke-linecap="round"/>
  <line x1="12" y1="140" x2="6" y2="140" stroke="#00CCFF" stroke-width="1.2" opacity="0.3" stroke-linecap="round"/>
  <polygon points="192,115 188,112 184,115 188,118" fill="#00FFAA" opacity="0.3"/>
  <polygon points="8,115 12,112 16,115 12,118" fill="#00CCFF" opacity="0.3"/>
</svg>`;

export const HEX_FRAME_HOVER = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 230" fill="none">
  <defs>
    <filter id="glowH" x="-70%" y="-70%" width="240%" height="240%">
      <feGaussianBlur in="SourceGraphic" stdDeviation="6" result="b1"/>
      <feGaussianBlur in="SourceGraphic" stdDeviation="14" result="b2"/>
      <feGaussianBlur in="SourceGraphic" stdDeviation="24" result="b3"/>
      <feMerge>
        <feMergeNode in="b3"/>
        <feMergeNode in="b2"/>
        <feMergeNode in="b1"/>
        <feMergeNode in="SourceGraphic"/>
      </feMerge>
    </filter>
    <linearGradient id="outerGradH" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#33FFBB" stop-opacity="1"/>
      <stop offset="30%" stop-color="#00FFAA" stop-opacity="0.8"/>
      <stop offset="70%" stop-color="#00DDEE" stop-opacity="0.8"/>
      <stop offset="100%" stop-color="#33DDFF" stop-opacity="1"/>
    </linearGradient>
    <linearGradient id="bevelGradH" x1="50%" y1="0%" x2="50%" y2="100%">
      <stop offset="0%" stop-color="#88FFDD" stop-opacity="0.5"/>
      <stop offset="25%" stop-color="#33FFBB" stop-opacity="0.15"/>
      <stop offset="100%" stop-color="#003322" stop-opacity="0"/>
    </linearGradient>
    <radialGradient id="innerFillH" cx="50%" cy="40%" r="60%">
      <stop offset="0%" stop-color="#0c2220" stop-opacity="0.75"/>
      <stop offset="100%" stop-color="#040a0e" stop-opacity="0.9"/>
    </radialGradient>
    <linearGradient id="pulseGrad" x1="0%" y1="0%" x2="100%" y2="0%">
      <stop offset="0%" stop-color="#00FFAA" stop-opacity="0"/>
      <stop offset="50%" stop-color="#33FFCC" stop-opacity="0.6"/>
      <stop offset="100%" stop-color="#00CCFF" stop-opacity="0"/>
    </linearGradient>
    <linearGradient id="cornerGradH" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#66FFDD" stop-opacity="1"/>
      <stop offset="100%" stop-color="#33DDFF" stop-opacity="0.5"/>
    </linearGradient>
  </defs>
  <polygon points="100,8 188,60 188,170 100,222 12,170 12,60" stroke="url(#outerGradH)" stroke-width="2" fill="none" filter="url(#glowH)" opacity="0.65"/>
  <polygon points="100,12 185,62 185,168 100,218 15,168 15,62" fill="url(#innerFillH)"/>
  <polygon points="100,12 185,62 185,168 100,218 15,168 15,62" fill="url(#bevelGradH)"/>
  <polygon points="100,8 188,60 188,170 100,222 12,170 12,60" stroke="url(#outerGradH)" stroke-width="2.2" fill="none"/>
  <polygon points="100,14 183,64 183,166 100,216 17,166 17,64" stroke="url(#outerGradH)" stroke-width="0.6" fill="none" opacity="0.35"/>
  <line x1="12" y1="115" x2="188" y2="115" stroke="url(#pulseGrad)" stroke-width="0.8" opacity="0.3"/>
  <line x1="100" y1="8" x2="152" y2="38" stroke="url(#cornerGradH)" stroke-width="3" stroke-linecap="round"/>
  <line x1="100" y1="8" x2="48" y2="38" stroke="url(#cornerGradH)" stroke-width="3" stroke-linecap="round"/>
  <line x1="100" y1="222" x2="148" y2="195" stroke="url(#cornerGradH)" stroke-width="2.5" stroke-linecap="round" opacity="0.6"/>
  <line x1="100" y1="222" x2="52" y2="195" stroke="url(#cornerGradH)" stroke-width="2.5" stroke-linecap="round" opacity="0.6"/>
  <line x1="188" y1="90" x2="195" y2="90" stroke="#33FFBB" stroke-width="1.5" opacity="0.5" stroke-linecap="round"/>
  <line x1="188" y1="115" x2="197" y2="115" stroke="#33FFBB" stroke-width="2" opacity="0.6" stroke-linecap="round"/>
  <line x1="188" y1="140" x2="195" y2="140" stroke="#33FFBB" stroke-width="1.5" opacity="0.5" stroke-linecap="round"/>
  <line x1="12" y1="90" x2="5" y2="90" stroke="#33DDFF" stroke-width="1.5" opacity="0.5" stroke-linecap="round"/>
  <line x1="12" y1="115" x2="3" y2="115" stroke="#33DDFF" stroke-width="2" opacity="0.6" stroke-linecap="round"/>
  <line x1="12" y1="140" x2="5" y2="140" stroke="#33DDFF" stroke-width="1.5" opacity="0.5" stroke-linecap="round"/>
  <polygon points="194,115 188,111 182,115 188,119" fill="#33FFBB" opacity="0.5"/>
  <polygon points="6,115 12,111 18,115 12,119" fill="#33DDFF" opacity="0.5"/>
  <polygon points="100,4 104,8 100,12 96,8" fill="#66FFDD" opacity="0.4"/>
</svg>`;

// Clip-path polygon used over the inner image so it stays inside the hex body.
// Matches the desktop `.clip-hex-tall` utility in global.css.
export const HEX_CLIP_POINTS = '100,8 186,59 186,171 100,222 14,171 14,59';
