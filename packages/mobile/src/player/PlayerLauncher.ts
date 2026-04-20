import { NativeModules, Platform } from 'react-native';

// Typed wrapper around the native PlayerActivity bridge
// (android/app/.../player/PlayerLauncherModule.kt). The native Activity
// owns the ExoPlayer + PlayerView — JS never mounts <Video>.

interface PlayerLauncherOptions {
  url: string;
  title?: string;
  userAgent?: string;
}

interface PlayerLauncherNative {
  launch(options: PlayerLauncherOptions): Promise<void>;
}

const native = NativeModules.PlayerLauncher as PlayerLauncherNative | undefined;

export async function launchNativePlayer(
  options: PlayerLauncherOptions,
): Promise<void> {
  if (Platform.OS !== 'android') {
    throw new Error('Native player is Android-only');
  }
  if (!native) {
    throw new Error(
      'PlayerLauncher native module unavailable — rebuild the Android app',
    );
  }
  await native.launch(options);
}
