import { useEffect, useRef, useCallback } from 'react';
import Hls from 'hls.js';
import mpegts from 'mpegts.js';
import { usePlayerStore } from '../../stores/player-store';
import { setVideoElement } from './video-ref';
import { detectStreamType, getVideoErrorMessage, isVodUrl, hasUnsupportedExtension, replaceStreamExtension, buildXtreamHlsUrl } from './player-utils';

// --- Throttled store updater (prevents React re-render storm) ---

let lastTimeUpdate = 0;
const TIME_UPDATE_INTERVAL = 500; // Only push position to store every 500ms

function throttledTimeUpdate(video: HTMLVideoElement): void {
  const now = performance.now();
  if (now - lastTimeUpdate < TIME_UPDATE_INTERVAL) return;
  lastTimeUpdate = now;
  const dur = video.duration;
  usePlayerStore.setState({
    position: video.currentTime,
    duration: dur && isFinite(dur) ? dur : 0,
  });
}

// Debounce buffering status to avoid spinner flicker on brief stalls
let bufferingTimer: ReturnType<typeof setTimeout> | null = null;
const BUFFERING_DEBOUNCE = 400; // Only show spinner if stalled for 400ms+

function debouncedBuffering(): void {
  if (bufferingTimer) return; // already scheduled
  bufferingTimer = setTimeout(() => {
    bufferingTimer = null;
    const video = document.querySelector('video');
    // Only set buffering if the video is still waiting (not already playing)
    if (video && video.readyState < 3 && usePlayerStore.getState().status !== 'idle') {
      usePlayerStore.setState({ status: 'buffering' });
    }
  }, BUFFERING_DEBOUNCE);
}

function cancelBufferingDebounce(): void {
  if (bufferingTimer) {
    clearTimeout(bufferingTimer);
    bufferingTimer = null;
  }
}

/**
 * HTML5 video player with HLS and MPEG-TS support.
 * Optimized for smooth, uninterrupted IPTV playback.
 */
export function VideoPlayer() {
  const videoRef = useRef<HTMLVideoElement>(null);
  const hlsRef = useRef<Hls | null>(null);
  const mpegtsRef = useRef<mpegts.Player | null>(null);
  const url = usePlayerStore((s) => s.currentUrl);
  const startPosition = usePlayerStore((s) => s._startPosition);

  // Register the video element for external control
  useEffect(() => {
    setVideoElement(videoRef.current);
    return () => setVideoElement(null);
  }, []);

  const destroyPlayers = useCallback(() => {
    cancelBufferingDebounce();
    if (hlsRef.current) {
      hlsRef.current.destroy();
      hlsRef.current = null;
    }
    if (mpegtsRef.current) {
      try {
        mpegtsRef.current.pause();
        mpegtsRef.current.unload();
        mpegtsRef.current.detachMediaElement();
        mpegtsRef.current.destroy();
      } catch {
        // Ignore cleanup errors
      }
      mpegtsRef.current = null;
    }
  }, []);

  // --- Attach stream when URL changes (with format fallback chain) ---
  useEffect(() => {
    const videoEl = videoRef.current;
    if (!videoEl || !url) return;
    // Rebind so TypeScript carries the non-null type into closures
    const video: HTMLVideoElement = videoEl;

    destroyPlayers();
    lastTimeUpdate = 0;

    const type = detectStreamType(url);
    const vodUrl = isVodUrl(url);
    const unsupported = hasUnsupportedExtension(url);

    // Build format attempt queue.
    // Primary format is determined by detectStreamType.  For URLs with formats
    // that Chromium cannot play natively (.mkv, .avi, .mov), detectStreamType
    // already routes to 'mpegts'.  We add HLS fallback for VOD and also for
    // any URL with an unsupported extension (not just Xtream /movie/ /series/).
    interface FormatAttempt { url: string; playerType: 'native' | 'mpegts' | 'hls' }
    const attempts: FormatAttempt[] = [];

    if (type === 'hls') {
      attempts.push({ url, playerType: 'hls' });
    } else if (type === 'mpegts') {
      // For Xtream VOD with unsupported extension (.mkv, .avi, .mov), try HLS
      // FIRST since Xtream servers reliably serve .m3u8 for any content (Bug 9).
      if (vodUrl && unsupported && Hls.isSupported()) {
        attempts.push({ url: replaceStreamExtension(url, 'm3u8'), playerType: 'hls' });
        // Some Xtream servers use /streamId/streamId.m3u8 pattern (Bug 6)
        const altHls = buildXtreamHlsUrl(url);
        if (altHls) {
          attempts.push({ url: altHls, playerType: 'hls' });
        }
      }
      attempts.push({ url, playerType: 'mpegts' });
      // Generic unsupported extension — HLS fallback if not already queued
      if (!vodUrl && unsupported && Hls.isSupported()) {
        attempts.push({ url: replaceStreamExtension(url, 'm3u8'), playerType: 'hls' });
      }
    } else {
      // Native (.mp4, .webm)
      attempts.push({ url, playerType: 'native' });
      if (vodUrl || unsupported) {
        // VOD format fallbacks — try HLS first (most reliable for Xtream),
        // then mpegts as last resort
        if (Hls.isSupported()) {
          attempts.push({ url: replaceStreamExtension(url, 'm3u8'), playerType: 'hls' });
        }
        if (mpegts.isSupported()) {
          attempts.push({ url: replaceStreamExtension(url, 'ts'), playerType: 'mpegts' });
        }
      }
    }

    let currentIdx = 0;
    let loadTimer: ReturnType<typeof setTimeout> | null = null;
    let advancing = false;
    // Track which attempt index the native error handler should act on.
    // This prevents stale error events from a previous attempt from
    // triggering advanceToNext after we've already moved on.
    let activeAttemptIdx = 0;

    function advanceToNext() {
      if (advancing) return; // prevent re-entrant calls
      advancing = true;

      if (loadTimer) { clearTimeout(loadTimer); loadTimer = null; }
      destroyPlayers();
      video.removeAttribute('src');
      video.load();

      currentIdx++;
      activeAttemptIdx = currentIdx;

      if (currentIdx < attempts.length) {
        startAttempt();
      } else {
        // All formats exhausted — show the most helpful error
        const errorMsg = getVideoErrorMessage(video);
        usePlayerStore.setState({
          status: 'error',
          error: errorMsg !== 'Unknown playback error' ? errorMsg : 'Unable to play — format not supported',
        });
      }

      advancing = false;
    }

    function startAttempt() {
      const attempt = attempts[currentIdx];
      console.info(`Player: trying ${attempt.playerType} (${currentIdx + 1}/${attempts.length})`, attempt.url);

      try {
        if (attempt.playerType === 'hls' && Hls.isSupported()) {
          initHls(video, attempt.url, startPosition, hlsRef, advanceToNext);
        } else if (attempt.playerType === 'mpegts' && mpegts.isSupported()) {
          initMpegts(video, attempt.url, startPosition, mpegtsRef, {
            maxErrors: vodUrl ? 2 : 5,
            onFatalError: advanceToNext,
          });
        } else {
          initNative(video, attempt.url, startPosition);
          // Native loading timeout — 10s (reduced from 20s for faster fallback)
          loadTimer = setTimeout(() => {
            loadTimer = null;
            if (!hlsRef.current && !mpegtsRef.current && video.readyState < 2) {
              console.warn('Native playback timed out (10s)');
              advanceToNext();
            }
          }, 10_000);
        }
      } catch (err) {
        console.warn('Player init error:', err);
        // Use setTimeout to break out of the re-entrancy guard
        setTimeout(() => advanceToNext(), 0);
      }
    }

    // Handle native video element errors (fires for <video src="..."> failures)
    const onNativeError = () => {
      // If a library player (HLS/mpegts) is active, its own handler manages errors
      if (hlsRef.current || mpegtsRef.current) return;
      // Ignore stale errors from a previous attempt (Bug 11 fix)
      if (currentIdx !== activeAttemptIdx) return;
      if (loadTimer) { clearTimeout(loadTimer); loadTimer = null; }

      const errorMsg = getVideoErrorMessage(video);
      console.warn('Native playback error:', errorMsg, '| URL:', attempts[currentIdx]?.url ?? url);

      advanceToNext();
    };

    video.addEventListener('error', onNativeError);

    // Start first attempt
    startAttempt();

    return () => {
      if (loadTimer) clearTimeout(loadTimer);
      video.removeEventListener('error', onNativeError);
      destroyPlayers();
      if (video) {
        video.removeAttribute('src');
        video.load();
      }
    };
  }, [url, startPosition, destroyPlayers]);

  // --- Sync video events → store (with throttling) ---
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    const onPlay = () => {
      cancelBufferingDebounce();
      usePlayerStore.setState({ status: 'playing' });
    };
    const onPause = () => {
      const { status } = usePlayerStore.getState();
      if (status !== 'idle' && status !== 'stopped') {
        usePlayerStore.setState({ status: 'paused' });
      }
    };
    const onWaiting = () => {
      // Debounce — don't flash spinner on brief network hiccups
      debouncedBuffering();
    };
    const onPlaying = () => {
      cancelBufferingDebounce();
      usePlayerStore.setState({ status: 'playing' });
    };
    const onEnded = () => usePlayerStore.getState().stop();
    // Note: error handling is in the URL-change effect above (with fallback logic).
    // This effect only syncs playback state — no onError here to avoid conflicts.
    const onTimeUpdate = () => throttledTimeUpdate(video);
    const onVolumeChange = () => {
      usePlayerStore.setState({
        volume: Math.round(video.volume * 100),
        muted: video.muted,
      });
    };
    const onLoadedMetadata = () => {
      const dur = video.duration;
      usePlayerStore.setState({
        duration: dur && isFinite(dur) ? dur : 0,
        mediaInfo: { width: video.videoWidth, height: video.videoHeight },
      });
    };

    video.addEventListener('play', onPlay);
    video.addEventListener('pause', onPause);
    video.addEventListener('waiting', onWaiting);
    video.addEventListener('playing', onPlaying);
    video.addEventListener('ended', onEnded);
    video.addEventListener('timeupdate', onTimeUpdate);
    video.addEventListener('volumechange', onVolumeChange);
    video.addEventListener('loadedmetadata', onLoadedMetadata);

    return () => {
      cancelBufferingDebounce();
      video.removeEventListener('play', onPlay);
      video.removeEventListener('pause', onPause);
      video.removeEventListener('waiting', onWaiting);
      video.removeEventListener('playing', onPlaying);
      video.removeEventListener('ended', onEnded);
      video.removeEventListener('timeupdate', onTimeUpdate);
      video.removeEventListener('volumechange', onVolumeChange);
      video.removeEventListener('loadedmetadata', onLoadedMetadata);
    };
  }, []);

  return (
    <video
      ref={videoRef}
      className="absolute inset-0 h-full w-full bg-black"
      playsInline
      // Preload enough data for smooth start
      preload="auto"
    />
  );
}

// ---------------------------------------------------------------------------
// Player initializers — separated for clarity
// ---------------------------------------------------------------------------

function initHls(
  video: HTMLVideoElement,
  url: string,
  startPosition: number | undefined,
  hlsRef: React.MutableRefObject<Hls | null>,
  onFatalError?: () => void,
): void {
  let networkRetries = 0;

  const hls = new Hls({
    enableWorker: true,
    lowLatencyMode: false,
    maxBufferLength: 60,
    maxMaxBufferLength: 120,
    maxBufferSize: 60 * 1000 * 1000,
    maxBufferHole: 0.5,
    abrEwmaDefaultEstimate: 5_000_000,
    abrBandWidthUpFactor: 0.7,
    // Fallback attempts (onFatalError set) get 3 retries — enough for Xtream
    // servers that need a moment to transcode (Bug 8 fix). Primary gets full retries.
    fragLoadingMaxRetry: onFatalError ? 3 : 6,
    manifestLoadingMaxRetry: onFatalError ? 3 : 4,
    levelLoadingMaxRetry: onFatalError ? 3 : 4,
    fragLoadingRetryDelay: 1000,
  });
  hls.loadSource(url);
  hls.attachMedia(video);
  hls.on(Hls.Events.MANIFEST_PARSED, (_event, data) => {
    if (startPosition && startPosition > 0) {
      video.currentTime = startPosition;
    }
    video.play().catch((err) => {
      console.warn('HLS autoplay failed:', err.message);
    });

    // Populate audio tracks from HLS manifest
    if (data.audioTracks && data.audioTracks.length > 0) {
      usePlayerStore.setState({
        audioTracks: data.audioTracks.map((t: { id: number; name?: string; lang?: string }, i: number) => ({
          id: t.id ?? i,
          title: t.name || t.lang || `Audio ${i + 1}`,
          language: t.lang,
          selected: i === 0,
        })),
      });
    }

    // Populate subtitle tracks from HLS manifest
    if (data.subtitleTracks && data.subtitleTracks.length > 0) {
      usePlayerStore.setState({
        subtitleTracks: data.subtitleTracks.map((t: { id: number; name?: string; lang?: string }, i: number) => ({
          id: t.id ?? i,
          title: t.name || t.lang || `Subtitle ${i + 1}`,
          language: t.lang,
          selected: false,
        })),
      });
    }

    // Extract codec info from the first level (Bug 19 fix)
    if (hls.levels && hls.levels.length > 0) {
      const level = hls.levels[hls.currentLevel >= 0 ? hls.currentLevel : 0];
      if (level) {
        const info: Record<string, unknown> = {
          width: video.videoWidth || level.width,
          height: video.videoHeight || level.height,
        };
        if (level.videoCodec) info.videoCodec = level.videoCodec;
        if (level.audioCodec) info.audioCodec = level.audioCodec;
        if (level.bitrate) info.bitrate = Math.round(level.bitrate / 1000);
        usePlayerStore.setState({ mediaInfo: info });
      }
    }
  });
  hls.on(Hls.Events.ERROR, (_event, data) => {
    if (data.fatal) {
      const handleFatal = () => {
        if (onFatalError) {
          onFatalError();
        } else {
          usePlayerStore.setState({ status: 'error', error: `HLS error: ${data.details}` });
        }
      };

      switch (data.type) {
        case Hls.ErrorTypes.NETWORK_ERROR:
          networkRetries++;
          if (networkRetries <= (onFatalError ? 2 : 6)) {
            hls.startLoad();
          } else {
            handleFatal();
          }
          break;
        case Hls.ErrorTypes.MEDIA_ERROR:
          hls.recoverMediaError();
          break;
        default:
          handleFatal();
          break;
      }
    }
  });
  hlsRef.current = hls;
}

function initMpegts(
  video: HTMLVideoElement,
  url: string,
  startPosition: number | undefined,
  mpegtsRef: React.MutableRefObject<mpegts.Player | null>,
  options?: { maxErrors?: number; onFatalError?: () => void },
): void {
  const lower = url.toLowerCase();
  const isLive = lower.includes('/live/');
  const isFLV = lower.split('?')[0].endsWith('.flv');
  let errorCount = 0;
  let fatalFired = false;
  const maxErrors = options?.maxErrors ?? 5;

  const player = mpegts.createPlayer(
    {
      type: isFLV ? 'flv' : 'mpegts',
      isLive,
      url,
    },
    {
      enableWorker: true,
      enableStashBuffer: true,
      stashInitialSize: 1024 * 1024,
      liveBufferLatencyChasing: false,
      lazyLoad: !isLive,
      lazyLoadMaxDuration: 5 * 60,
      lazyLoadRecoverDuration: 30,
      autoCleanupSourceBuffer: true,
      autoCleanupMaxBackwardDuration: 30,
      autoCleanupMinBackwardDuration: 15,
      seekType: 'range',
    },
  );

  player.attachMediaElement(video);
  player.load();

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  player.on(mpegts.Events.ERROR, (errorType: any, errorDetail: any, errorInfo: any) => {
    errorCount++;
    console.warn('mpegts error #' + errorCount + ':', errorType, errorDetail, errorInfo);

    if (errorCount <= maxErrors) {
      // Show buffering state during recovery so the user sees a spinner (Bug 7 fix)
      usePlayerStore.setState({ status: 'buffering' });
      try {
        player.unload();
        player.load();
        video.play().catch((e) => console.warn('mpegts recovery play failed:', e.message));
      } catch {
        // If recovery fails, let it go — next error will count
      }
      return;
    }

    // Max errors exceeded — fire fatal once
    if (fatalFired) return;
    fatalFired = true;

    if (options?.onFatalError) {
      options.onFatalError();
    } else {
      const msg = errorInfo?.msg || errorDetail || 'Unknown stream error';
      usePlayerStore.setState({ status: 'error', error: `Stream error: ${msg}` });
    }
  });

  // Gentle live catch-up (not aggressive frame dropping)
  if (isLive) {
    const catchupInterval = setInterval(() => {
      if (!video || video.paused) return;
      const buffered = video.buffered;
      if (buffered.length === 0) return;
      const bufferEnd = buffered.end(buffered.length - 1);
      const lag = bufferEnd - video.currentTime;
      if (lag > 15) {
        video.currentTime = bufferEnd - 3;
      }
    }, 5000);

    const originalDestroy = player.destroy.bind(player);
    player.destroy = () => {
      // Clear interval FIRST to prevent leak even if originalDestroy throws (Bug 28 fix)
      clearInterval(catchupInterval);
      try {
        originalDestroy();
      } catch {
        // Player may already be destroyed
      }
    };
  }

  if (startPosition && startPosition > 0) {
    video.currentTime = startPosition;
  }
  video.play().catch((err) => {
    console.warn('mpegts autoplay failed:', err.message);
  });
  mpegtsRef.current = player;
}

function initNative(
  video: HTMLVideoElement,
  url: string,
  startPosition: number | undefined,
): void {
  video.src = url;
  video.addEventListener(
    'loadedmetadata',
    () => {
      if (startPosition && startPosition > 0) {
        video.currentTime = startPosition;
      }
      video.play().catch((err) => {
        console.warn('Native autoplay failed:', err.message);
      });
    },
    { once: true },
  );
  video.load();
}
