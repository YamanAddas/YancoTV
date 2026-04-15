/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/renderer/**/*.{html,tsx,ts}'],
  theme: {
    extend: {
      colors: {
        // Surface palette via CSS variables — allows runtime theme switching.
        // The <alpha-value> placeholder lets opacity modifiers work (e.g. bg-surface-900/50).
        surface: {
          50:  'rgb(var(--surface-50)  / <alpha-value>)',
          100: 'rgb(var(--surface-100) / <alpha-value>)',
          200: 'rgb(var(--surface-200) / <alpha-value>)',
          300: 'rgb(var(--surface-300) / <alpha-value>)',
          400: 'rgb(var(--surface-400) / <alpha-value>)',
          500: 'rgb(var(--surface-500) / <alpha-value>)',
          600: 'rgb(var(--surface-600) / <alpha-value>)',
          700: 'rgb(var(--surface-700) / <alpha-value>)',
          800: 'rgb(var(--surface-800) / <alpha-value>)',
          900: 'rgb(var(--surface-900) / <alpha-value>)',
          950: 'rgb(var(--surface-950) / <alpha-value>)',
        },
        accent: {
          DEFAULT: '#00FFAA',
          hover:   '#33FFBB',
          muted:   '#00CC88',
          dim:     '#009966',
        },
        glow: {
          green: '#00FFAA',
          teal:  '#00CCFF',
          cyan:  '#33FFD0',
        },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
      backgroundImage: {
        'atmosphere': `
          radial-gradient(ellipse at 15% 50%, rgba(0, 255, 170, 0.07) 0%, transparent 50%),
          radial-gradient(ellipse at 85% 20%, rgba(0, 204, 255, 0.05) 0%, transparent 50%),
          radial-gradient(ellipse at 50% 85%, rgba(0, 255, 200, 0.04) 0%, transparent 50%)
        `,
      },
      boxShadow: {
        'glow-sm':  '0 0 8px rgba(0, 255, 170, 0.15), 0 0 16px rgba(0, 255, 170, 0.08)',
        'glow':     '0 0 12px rgba(0, 255, 170, 0.2), 0 0 24px rgba(0, 255, 170, 0.1), 0 0 48px rgba(0, 255, 170, 0.05)',
        'glow-lg':  '0 0 16px rgba(0, 255, 170, 0.3), 0 0 32px rgba(0, 255, 170, 0.15), 0 0 64px rgba(0, 255, 170, 0.08)',
        'glow-teal':'0 0 12px rgba(0, 204, 255, 0.2), 0 0 24px rgba(0, 204, 255, 0.1)',
        'glass':    '0 8px 32px rgba(0, 0, 0, 0.4)',
      },
      animation: {
        'pulse-glow': 'pulseGlow 3s ease-in-out infinite',
      },
      keyframes: {
        pulseGlow: {
          '0%, 100%': { opacity: '0.5' },
          '50%':      { opacity: '0.8' },
        },
      },
    },
  },
  plugins: [],
};
