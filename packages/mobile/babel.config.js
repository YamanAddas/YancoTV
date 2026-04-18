module.exports = {
  presets: [
    '@react-native/babel-preset',
    ['nativewind/babel', { jsxImportSource: 'nativewind' }],
  ],
  plugins: [
    // Reanimated plugin MUST be last.
    'react-native-reanimated/plugin',
  ],
};
