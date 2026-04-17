import { Link } from 'react-router-dom';
import { AnimatePresence, motion } from 'motion/react';
import { useToastStore } from '../stores/toast-store';

const KIND_STYLES: Record<'info' | 'success' | 'error', string> = {
  info: 'border-accent/40 bg-surface-900/95 text-surface-100',
  success: 'border-emerald-500/50 bg-emerald-950/90 text-emerald-50',
  error: 'border-red-500/50 bg-red-950/90 text-red-50',
};

export function Toaster() {
  const toasts = useToastStore((s) => s.toasts);
  const dismiss = useToastStore((s) => s.dismiss);

  return (
    <div className="pointer-events-none fixed right-4 top-4 z-[1000] flex w-80 flex-col gap-2">
      <AnimatePresence initial={false}>
        {toasts.map((t) => (
          <motion.div
            key={t.id}
            layout
            initial={{ opacity: 0, x: 24, scale: 0.96 }}
            animate={{ opacity: 1, x: 0, scale: 1 }}
            exit={{ opacity: 0, x: 24, scale: 0.96 }}
            transition={{ duration: 0.2 }}
            className={`pointer-events-auto flex items-center gap-3 rounded-lg border px-4 py-3 text-sm shadow-xl backdrop-blur ${KIND_STYLES[t.kind]}`}
          >
            <span className="flex-1 leading-snug">{t.message}</span>
            {t.action && (
              <Link
                to={t.action.href}
                onClick={() => dismiss(t.id)}
                className="shrink-0 rounded-md border border-current/30 px-2 py-1 text-xs font-medium opacity-90 transition-opacity hover:opacity-100"
              >
                {t.action.label}
              </Link>
            )}
            <button
              onClick={() => dismiss(t.id)}
              aria-label="Dismiss"
              className="shrink-0 rounded p-1 opacity-50 transition-opacity hover:opacity-100"
            >
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M3 3 L13 13 M13 3 L3 13" strokeLinecap="round" />
              </svg>
            </button>
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  );
}
