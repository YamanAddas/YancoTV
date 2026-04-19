module.exports = {
  presets: ['@react-native/babel-preset'],
  plugins: [
    // Required by react-native-reanimated / react-native-worklets. MUST be the
    // last plugin in the list per the upstream docs — any transform that
    // processes function bodies after this will corrupt the worklet
    // transforms.
    'react-native-worklets/plugin',
  ],
};
