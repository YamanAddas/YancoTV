import { usePlayerStore } from '../../stores/player-store';
import { VideoPlayer } from './VideoPlayer';

/**
 * Positioning wrapper around the html5 <video> element. Mounts the underlying
 * VideoPlayer once and reshapes its surrounding box as the player mode changes,
 * so switching between mini and theater never unmounts the video element —
 * playback continues uninterrupted across the transition.
 *
 * mpv backend renders into a dedicated child window driven by the main process
 * (see video-window.ts); this component is a no-op when backend !== 'html5'.
 */
export function VideoStage() {
  const mode = usePlayerStore((s) => s.mode);
  const backend = usePlayerStore((s) => s.backend);

  if (backend !== 'html5' || mode === 'idle') return null;

  const isMini = mode === 'mini';

  return (
    <div
      className={
        isMini
          ? 'pointer-events-none fixed bottom-4 right-4 z-40 aspect-video w-96 overflow-hidden rounded-lg bg-black shadow-2xl ring-1 ring-accent/30'
          : 'absolute inset-0 z-40 bg-black'
      }
    >
      <VideoPlayer />
    </div>
  );
}
