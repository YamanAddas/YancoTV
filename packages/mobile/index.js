// Polyfills MUST load before any app code that consumes them. @yancotv/core's
// parental PIN hashing (scrypt via @noble/hashes) needs crypto.getRandomValues,
// and the xmltv-parser's TextDecoder path needs the TextEncoder/TextDecoder
// globals that RN's JS runtime doesn't ship by default.
import 'react-native-get-random-values';
import 'fastestsmallesttextencoderdecoder';

// react-native-gesture-handler must load first thing so its native module is
// initialized before any navigator or drawer tries to attach gesture handlers.
// Per the upstream docs, this import at the top of the entry file is what
// installs the JS <-> native wiring.
import 'react-native-gesture-handler';

import { initSentry } from './src/sentry';
initSentry();

import { AppRegistry } from 'react-native';
import App from './App';
import { name as appName } from './app.json';

AppRegistry.registerComponent(appName, () => App);
