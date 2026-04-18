module.exports = {
  presets: [
    '@react-native/babel-preset',
    ['nativewind/babel', { jsxImportSource: 'nativewind' }],
  ],
  // react-native-reanimated re-added in Phase 4 after tvos compat verified
};
