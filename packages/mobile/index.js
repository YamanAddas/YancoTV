// Polyfills MUST load before any app code that consumes them. @yancotv/core's
// parental PIN hashing (scrypt via @noble/hashes) needs crypto.getRandomValues,
// and the xmltv-parser's TextDecoder path needs the TextEncoder/TextDecoder
// globals that RN's JS runtime doesn't ship by default.
import 'react-native-get-random-values';
import 'fastestsmallesttextencoderdecoder';

import { initSentry } from './src/sentry';
initSentry();

import { AppRegistry } from 'react-native';
import App from './App';
import { name as appName } from './app.json';

AppRegistry.registerComponent(appName, () => App);
