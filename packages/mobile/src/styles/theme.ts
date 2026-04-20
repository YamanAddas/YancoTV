// Exact port of the desktop renderer palette (see src/renderer/styles/global.css
// + tailwind.config.js). All hex values match rgb() variables from the desktop
// dark theme so mobile reads identical.

export const colors = {
  // Root background — matches `bg-space` with surface-950
  bg: 'rgb(3, 6, 14)',

  // Surface scale (desktop dark theme)
  surface950: 'rgb(3, 6, 14)',
  surface900: 'rgb(6, 12, 22)',
  surface800: 'rgb(12, 20, 32)',
  surface700: 'rgb(20, 32, 45)',
  surface600: 'rgb(35, 50, 65)',
  surface500: 'rgb(60, 80, 100)',
  surface400: 'rgb(90, 110, 130)',
  surface300: 'rgb(130, 150, 165)',
  surface200: 'rgb(160, 180, 190)',
  surface100: 'rgb(200, 215, 220)',
  surface50: 'rgb(230, 240, 240)',

  // Accent — exact desktop values
  accent: '#00FFAA',
  accentHover: '#33FFBB',
  accentMuted: '#00CC88',
  accentDim: '#009966',
  teal: '#00CCFF',
  cyan: '#33FFD0',

  // Status
  brand: '#e11d48',
  focus: '#fbbf24',
  live: '#22c55e',
  amber: '#fbbf24',
  red400: '#f87171',
  red300: '#fca5a5',

  // Pure
  white: '#ffffff',
  muted: '#9ca3af',

  // Glass panel fills
  glass: 'rgba(6, 12, 22, 0.7)',
  glassStrong: 'rgba(6, 12, 22, 0.85)',
  glassSubtle: 'rgba(6, 12, 22, 0.5)',
  glassBorder: 'rgba(0, 255, 170, 0.12)',
  glassBorderSoft: 'rgba(0, 255, 170, 0.08)',
  // Accent border used for HexChannelRow container (M4R.D.3 spec: 18%).
  accentBorder18: 'rgba(0, 255, 170, 0.18)',

  accentGlow: 'rgba(0, 255, 170, 0.25)',
};

export const spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
  xxl: 48,
};

export const radii = {
  sm: 6,
  md: 10,
  lg: 16,
  xl: 24,
  pill: 999,
};

// Sidebar widths match desktop exactly (260 / 56)
export const sidebar = {
  width: 260,
  widthCollapsed: 56,
};

// Font family declarations. Fallbacks used on Android until custom TTFs are
// bundled; declaring the names anyway means switching to TTFs later is a
// one-line drop-in.
export const fonts = {
  sans: 'sans-serif',
  display: 'sans-serif-medium',
  mono: 'monospace',
};

// Accent-colored shadow helper that approximates the desktop `.shadow-glow`.
// React Native can only render one shadow at a time, so use the strongest
// layer of the desktop multi-shadow stack.
export const glow = {
  sm: {
    shadowColor: colors.accent,
    shadowOpacity: 0.35,
    shadowRadius: 10,
    shadowOffset: { width: 0, height: 0 },
    elevation: 6,
  },
  md: {
    shadowColor: colors.accent,
    shadowOpacity: 0.55,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 0 },
    elevation: 10,
  },
  lg: {
    shadowColor: colors.accent,
    shadowOpacity: 0.75,
    shadowRadius: 28,
    shadowOffset: { width: 0, height: 0 },
    elevation: 16,
  },
};
