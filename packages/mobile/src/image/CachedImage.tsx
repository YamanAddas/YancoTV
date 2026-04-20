import React, { memo, useMemo } from 'react';
import {
  Image,
  type ImageProps,
  type ImageSourcePropType,
} from 'react-native';

// Standardized image wrapper (M4R rule 7). Every `<Image>` in the app routes
// through here so future swaps (expo-image, react-native-fast-image) are a
// one-file change. Today the internals are plain React Native `<Image>` with
// two scroll-jank mitigations for URL sources:
//
//   1. The source object is memoized on `uri` so FlashList recycling doesn't
//      churn a fresh identity each render — Fresco treats that as a new
//      request and re-decodes.
//   2. `fadeDuration={0}` disables Android's 300ms default crossfade. On a
//      scrolling list the fade manifests as flashing/blinking tiles during
//      rapid recycle; disabling it is the standard fix.
//
// Two call styles:
//   <CachedImage uri={item.logoUrl} ... />          // dynamic URL
//   <CachedImage source={require('./logo.png')} />  // bundled asset

export interface CachedImageProps extends Omit<ImageProps, 'source'> {
  /** Remote URI. Mutually exclusive with `source`. */
  uri?: string | null;
  /** Static/bundled source (e.g. require('./logo.png')). Mutually exclusive with `uri`. */
  source?: ImageSourcePropType;
  /** Rendered when `uri` is empty. Ignored if `source` is set. */
  fallbackSource?: ImageSourcePropType;
}

function CachedImageImpl({
  uri,
  source,
  fallbackSource,
  fadeDuration,
  ...rest
}: CachedImageProps) {
  const resolved = useMemo<ImageSourcePropType | undefined>(() => {
    if (source) return source;
    if (uri && uri.length > 0) return { uri };
    return fallbackSource;
  }, [uri, source, fallbackSource]);

  if (!resolved) return null;

  return <Image {...rest} source={resolved} fadeDuration={fadeDuration ?? 0} />;
}

export const CachedImage = memo(CachedImageImpl);

/** Warm the native image cache for a given URI. No-op if uri is empty. */
export function prefetchImage(uri: string | undefined | null): Promise<boolean> {
  if (!uri) return Promise.resolve(false);
  return Image.prefetch(uri);
}
