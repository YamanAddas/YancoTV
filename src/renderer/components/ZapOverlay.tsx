import { AnimatePresence, motion } from 'motion/react';
import { usePlayerStore } from '../stores/player-store';

/**
 * Floating preview that appears while the user is scrolling through channels
 * with PageUp/PageDown (Sprint 19.4). Auto-dismisses when the zap commits
 * (player-store clears zapTarget on play).
 */
export function ZapOverlay() {
  const target = usePlayerStore((s) => s.zapTarget);

  return (
    <AnimatePresence>
      {target && (
        <motion.div
          key="zap-overlay"
          initial={{ opacity: 0, y: -12 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -12 }}
          transition={{ duration: 0.15 }}
          className="pointer-events-none fixed left-1/2 top-10 z-[900] -translate-x-1/2"
          aria-live="polite"
        >
          <div className="flex items-center gap-3 rounded-xl border border-accent/40 bg-surface-900/90 px-4 py-3 shadow-xl backdrop-blur">
            {target.logoUrl && (
              <img
                src={target.logoUrl}
                alt=""
                className="h-8 w-8 flex-shrink-0 rounded object-contain"
                onError={(e) => {
                  (e.target as HTMLImageElement).style.display = 'none';
                }}
              />
            )}
            <div className="min-w-0">
              <p className="truncate text-sm font-semibold text-surface-100">
                {target.title}
              </p>
              <p className="text-xs text-surface-400">
                {target.index + 1} / {target.total} &middot; tuning in 2s…
              </p>
            </div>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
