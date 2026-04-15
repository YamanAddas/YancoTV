import { useState, useEffect } from 'react';
import { useParentalStore } from '../../stores/parental-store';

// ---------------------------------------------------------------------------
// Parental Controls Settings — PIN management, content restrictions
// ---------------------------------------------------------------------------

export function ParentalSettings() {
  const {
    settings,
    loaded,
    load,
    setPin,
    removePin,
    updateSetting,
  } = useParentalStore();

  const [pin, setLocalPin] = useState('');
  const [confirmPin, setConfirmPin] = useState('');
  const [pinError, setPinError] = useState('');
  const [pinSuccess, setPinSuccess] = useState('');
  const [removingPin, setRemovingPin] = useState(false);
  const [currentPin, setCurrentPin] = useState('');

  useEffect(() => {
    load();
  }, [load]);

  const handleSavePin = async () => {
    setPinError('');
    setPinSuccess('');

    if (pin.length < 4) {
      setPinError('PIN must be at least 4 digits');
      return;
    }
    if (!/^\d+$/.test(pin)) {
      setPinError('PIN must contain only numbers');
      return;
    }
    if (pin !== confirmPin) {
      setPinError('PINs do not match');
      return;
    }

    const result = await setPin(pin);
    if (result.ok) {
      setPinSuccess(settings.pinSet ? 'PIN updated!' : 'PIN set!');
      setLocalPin('');
      setConfirmPin('');
    } else {
      setPinError(result.error || 'Failed to set PIN');
    }
  };

  const handleRemovePin = async () => {
    if (!currentPin) {
      setPinError('Enter current PIN to remove');
      return;
    }
    const verified = await useParentalStore.getState().verifyPin(currentPin);
    if (!verified) {
      setPinError('Incorrect PIN');
      return;
    }
    await removePin();
    setRemovingPin(false);
    setCurrentPin('');
    setPinError('');
    setPinSuccess('PIN removed');
  };

  const handleToggleHideAdult = () => {
    updateSetting('hide_adult', !settings.hideAdultContent);
  };

  const handleToggleRequirePin = () => {
    updateSetting('require_pin_settings', !settings.requirePinForSettings);
  };

  if (!loaded) {
    return (
      <div className="flex items-center justify-center py-12">
        <div className="h-6 w-6 animate-spin rounded-full border-2 border-accent border-t-transparent" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-surface-100">
          Parental Controls
        </h2>
        <p className="mt-1 text-sm text-surface-500">
          Restrict access to content and app settings
        </p>
      </div>

      {/* PIN Management */}
      <section className="rounded-xl border border-accent/5 bg-surface-900/30 p-5">
        <div className="mb-4">
          <h3 className="text-sm font-semibold uppercase tracking-wider text-surface-500">
            PIN Protection
          </h3>
          <p className="mt-1 text-xs text-surface-500">
            {settings.pinSet
              ? 'PIN is set. You can change or remove it below.'
              : 'Set a PIN to restrict access to locked channels and settings'}
          </p>
        </div>

        {/* Status indicator */}
        <div className="mb-4 flex items-center gap-2">
          <span
            className={`inline-block h-2.5 w-2.5 rounded-full ${
              settings.pinEnabled && settings.pinSet ? 'bg-green-500' : 'bg-surface-600'
            }`}
          />
          <span className="text-sm text-surface-300">
            {settings.pinEnabled && settings.pinSet
              ? 'PIN protection active'
              : settings.pinSet
                ? 'PIN set but disabled'
                : 'No PIN set'}
          </span>
        </div>

        {/* Set / Change PIN form */}
        {!removingPin && (
          <div className="space-y-3 border-t border-accent/5 pt-4">
            <p className="text-sm font-medium text-surface-300">
              {settings.pinSet ? 'Change PIN' : 'Set a new PIN'}
            </p>
            <div className="flex gap-3">
              <div>
                <label className="mb-1 block text-xs text-surface-500">
                  {settings.pinSet ? 'New PIN' : 'PIN Code'}
                </label>
                <input
                  type="password"
                  inputMode="numeric"
                  maxLength={6}
                  value={pin}
                  onChange={(e) => setLocalPin(e.target.value.replace(/\D/g, ''))}
                  placeholder="4-6 digits"
                  className="w-36 rounded-lg border border-surface-700/50 bg-surface-800/40 px-3 py-2 text-sm text-surface-200 placeholder-surface-500 focus:border-accent/50 focus:outline-none focus:ring-1 focus:ring-accent/30"
                />
              </div>
              <div>
                <label className="mb-1 block text-xs text-surface-500">
                  Confirm
                </label>
                <input
                  type="password"
                  inputMode="numeric"
                  maxLength={6}
                  value={confirmPin}
                  onChange={(e) =>
                    setConfirmPin(e.target.value.replace(/\D/g, ''))
                  }
                  placeholder="Re-enter"
                  className="w-36 rounded-lg border border-surface-700/50 bg-surface-800/40 px-3 py-2 text-sm text-surface-200 placeholder-surface-500 focus:border-accent/50 focus:outline-none focus:ring-1 focus:ring-accent/30"
                />
              </div>
            </div>

            <div className="flex items-center gap-3">
              <button
                onClick={handleSavePin}
                className="rounded-lg bg-accent shadow-glow-sm px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-accent-hover hover:shadow-glow"
              >
                {settings.pinSet ? 'Update PIN' : 'Save PIN'}
              </button>
              {settings.pinSet && (
                <button
                  onClick={() => {
                    setRemovingPin(true);
                    setPinError('');
                    setPinSuccess('');
                  }}
                  className="rounded-lg border border-red-500/30 px-4 py-2 text-sm font-medium text-red-400 transition-colors hover:bg-red-500/10"
                >
                  Remove PIN
                </button>
              )}
            </div>
          </div>
        )}

        {/* Remove PIN confirmation */}
        {removingPin && (
          <div className="space-y-3 border-t border-accent/5 pt-4">
            <p className="text-sm font-medium text-red-400">
              Enter current PIN to remove it
            </p>
            <input
              type="password"
              inputMode="numeric"
              maxLength={6}
              value={currentPin}
              onChange={(e) => setCurrentPin(e.target.value.replace(/\D/g, ''))}
              placeholder="Current PIN"
              className="w-36 rounded-lg border border-surface-700/50 bg-surface-800/40 px-3 py-2 text-sm text-surface-200 placeholder-surface-500 focus:border-accent/50 focus:outline-none focus:ring-1 focus:ring-accent/30"
              autoFocus
            />
            <div className="flex items-center gap-3">
              <button
                onClick={handleRemovePin}
                className="rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-red-700"
              >
                Confirm Remove
              </button>
              <button
                onClick={() => {
                  setRemovingPin(false);
                  setCurrentPin('');
                  setPinError('');
                }}
                className="rounded-lg border border-surface-700 px-4 py-2 text-sm font-medium text-surface-400 transition-colors hover:bg-surface-800"
              >
                Cancel
              </button>
            </div>
          </div>
        )}

        {pinError && <p className="mt-3 text-sm text-red-400">{pinError}</p>}
        {pinSuccess && (
          <p className="mt-3 text-sm text-green-400">{pinSuccess}</p>
        )}
      </section>

      {/* Content Restrictions */}
      <section className="rounded-xl border border-accent/5 bg-surface-900/30 p-5">
        <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-surface-500">
          Content Restrictions
        </h3>
        <div className="space-y-2">
          <ToggleRow
            label="Hide adult content"
            description="Filter out channels and VOD tagged as adult/XXX"
            checked={settings.hideAdultContent}
            onChange={handleToggleHideAdult}
          />
          <ToggleRow
            label="Require PIN for settings"
            description="Ask for PIN before opening the Settings page"
            checked={settings.requirePinForSettings}
            onChange={handleToggleRequirePin}
            disabled={!settings.pinSet}
          />
        </div>
        {!settings.pinSet && settings.requirePinForSettings === false && (
          <p className="mt-3 text-xs text-surface-500">
            Set a PIN above to enable the &quot;Require PIN for settings&quot; option.
          </p>
        )}
      </section>

      {/* Channel management info */}
      <section className="rounded-xl border border-accent/5 bg-surface-900/30 p-5">
        <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-surface-500">
          Channel Management
        </h3>
        <p className="text-sm text-surface-400">
          To lock or hide individual channels, right-click on any channel card
          in the Live TV, Movies, or Series views. Locked channels require a PIN
          to play. Hidden channels are removed from all views.
        </p>
      </section>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Shared toggle row component
// ---------------------------------------------------------------------------

function ToggleRow({
  label,
  description,
  checked,
  onChange,
  disabled,
}: {
  label: string;
  description: string;
  checked: boolean;
  onChange: () => void;
  disabled?: boolean;
}) {
  return (
    <div className="flex items-center justify-between rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3">
      <div>
        <p className={`text-sm font-medium ${disabled ? 'text-surface-500' : 'text-surface-200'}`}>
          {label}
        </p>
        <p className="mt-0.5 text-xs text-surface-500">{description}</p>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        onClick={onChange}
        disabled={disabled}
        className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${
          checked ? 'bg-accent shadow-glow-sm' : 'bg-surface-600'
        } ${disabled ? 'cursor-not-allowed opacity-40' : ''}`}
      >
        <span
          className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white transition-transform ${
            checked ? 'translate-x-[18px]' : 'translate-x-[3px]'
          }`}
        />
      </button>
    </div>
  );
}
