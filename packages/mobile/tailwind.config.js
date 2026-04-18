/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./App.tsx', './src/**/*.{js,jsx,ts,tsx}'],
  presets: [require('nativewind/preset')],
  theme: {
    extend: {
      colors: {
        // YancoTV brand palette (mirrors desktop theme)
        brand: {
          DEFAULT: '#e11d48', // rose-600
          dark: '#881337',
          light: '#fb7185',
        },
        surface: {
          900: '#0a0a0f',
          800: '#14141b',
          700: '#1e1e2a',
          600: '#2a2a3a',
        },
        focus: '#fbbf24', // amber-400 — high-contrast D-pad focus ring
      },
      fontFamily: {
        sans: ['Inter', 'System'],
      },
    },
  },
  plugins: [],
};
