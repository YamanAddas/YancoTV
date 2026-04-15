import { useState, useEffect, useRef, useCallback } from 'react';

// ---------------------------------------------------------------------------
// PIN Verification Modal
//
// Full-screen overlay that blocks interaction until a correct PIN is entered.
// Used for locked channels and protected settings access.
// ---------------------------------------------------------------------------

interface PinModalProps {
  /** What the user is trying to access */
  title?: string;
  /** Called with true if PIN verified, false if cancelled */
  onResult: (verified: boolean) => void;
}

export function PinModal({ title = 'Enter PIN', onResult }: PinModalProps) {
  const [pin, setPin] = useState('');
  const [error, setError] = useState('');
  const [checking, setChecking] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    // Auto-focus the input on mount
    inputRef.current?.focus();
  }, []);

  const handleVerify = useCallback(async () => {
    if (pin.length < 4) {
      setError('PIN must be at least 4 digits');
      return;
    }

    setChecking(true);
    setError('');

    try {
      const result = await window.api.parental.verifyPin(pin);
      if (result.verified) {
        onResult(true);
      } else {
        setError('Incorrect PIN');
        setPin('');
        inputRef.current?.focus();
      }
    } catch {
      setError('Verification failed');
    } finally {
      setChecking(false);
    }
  }, [pin, onResult]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter') {
        handleVerify();
      } else if (e.key === 'Escape') {
        onResult(false);
      }
    },
    [handleVerify, onResult],
  );

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm">
      <div className="w-full max-w-xs rounded-2xl border border-surface-700 bg-surface-900 p-6 shadow-2xl">
        {/* Lock icon */}
        <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-surface-800">
          <svg className="h-7 w-7 text-accent" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
            <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
            <path d="M7 11V7a5 5 0 0 1 10 0v4" />
          </svg>
        </div>

        <h2 className="mb-1 text-center text-lg font-semibold text-surface-100">
          {title}
        </h2>
        <p className="mb-5 text-center text-sm text-surface-500">
          Enter your PIN to continue
        </p>

        <input
          ref={inputRef}
          type="password"
          inputMode="numeric"
          maxLength={6}
          value={pin}
          onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))}
          onKeyDown={handleKeyDown}
          placeholder="----"
          disabled={checking}
          className="mb-3 w-full rounded-lg border border-surface-700 bg-surface-800 px-4 py-3 text-center text-2xl tracking-[0.5em] text-surface-100 placeholder-surface-600 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent disabled:opacity-50"
          autoComplete="off"
        />

        {error && (
          <p className="mb-3 text-center text-sm text-red-400">{error}</p>
        )}

        <div className="flex gap-3">
          <button
            onClick={() => onResult(false)}
            disabled={checking}
            className="flex-1 rounded-lg border border-surface-700 bg-surface-800 px-4 py-2.5 text-sm font-medium text-surface-300 transition-colors hover:bg-surface-700 disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            onClick={handleVerify}
            disabled={checking || pin.length < 4}
            className="flex-1 rounded-lg bg-accent px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-accent-hover disabled:opacity-50"
          >
            {checking ? 'Checking...' : 'Verify'}
          </button>
        </div>
      </div>
    </div>
  );
}
