import type { ReactNode } from 'react';

// Shared primitives for settings panels. Every tab uses the same row/toggle/select
// look, so pinning them here keeps the visual language consistent.

export function SettingRow({
  label,
  description,
  children,
}: {
  label: string;
  description?: string;
  children: ReactNode;
}) {
  return (
    <div className="flex items-center justify-between gap-4 rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3">
      <div className="min-w-0">
        <p className="text-sm font-medium text-surface-200">{label}</p>
        {description && <p className="mt-0.5 text-xs text-surface-500">{description}</p>}
      </div>
      <div className="flex-shrink-0">{children}</div>
    </div>
  );
}

export function SettingBlock({
  label,
  description,
  children,
}: {
  label: string;
  description?: string;
  children: ReactNode;
}) {
  return (
    <div className="space-y-3 rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3">
      <div>
        <p className="text-sm font-medium text-surface-200">{label}</p>
        {description && <p className="mt-0.5 text-xs text-surface-500">{description}</p>}
      </div>
      {children}
    </div>
  );
}

export function SectionHeading({ children }: { children: ReactNode }) {
  return (
    <h3 className="px-1 text-xs font-semibold uppercase tracking-wider text-surface-500">
      {children}
    </h3>
  );
}

export function PageHeading({ title, subtitle }: { title: string; subtitle?: string }) {
  return (
    <div>
      <h2 className="text-lg font-semibold text-surface-100">{title}</h2>
      {subtitle && <p className="mt-1 text-sm text-surface-500">{subtitle}</p>}
    </div>
  );
}

export function Toggle({
  checked,
  onChange,
  disabled,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${
        checked ? 'bg-accent shadow-glow-sm' : 'bg-surface-600'
      } ${disabled ? 'opacity-50 cursor-not-allowed' : ''}`}
    >
      <span
        className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white transition-transform ${
          checked ? 'translate-x-[18px]' : 'translate-x-[3px]'
        }`}
      />
    </button>
  );
}

export function Select({
  value,
  onChange,
  options,
  disabled,
}: {
  value: string;
  onChange: (v: string) => void;
  options: { value: string; label: string }[];
  disabled?: boolean;
}) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      disabled={disabled}
      className="rounded-lg border border-surface-700/50 bg-surface-800/40 px-3 py-1.5 text-sm text-surface-200 focus:border-accent/50 focus:outline-none focus:ring-1 focus:ring-accent/30 disabled:opacity-50"
    >
      {options.map((opt) => (
        <option key={opt.value} value={opt.value}>
          {opt.label}
        </option>
      ))}
    </select>
  );
}

export function TextInput({
  value,
  onChange,
  placeholder,
  type = 'text',
  disabled,
  className,
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  type?: string;
  disabled?: boolean;
  className?: string;
}) {
  return (
    <input
      type={type}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
      disabled={disabled}
      className={`rounded-lg border border-surface-700 bg-surface-800 px-3 py-2 text-sm text-surface-100 placeholder:text-surface-500 focus:border-accent focus:outline-none disabled:opacity-50 ${
        className ?? ''
      }`}
    />
  );
}

export function PrimaryButton({
  children,
  onClick,
  disabled,
  type = 'button',
}: {
  children: ReactNode;
  onClick?: () => void;
  disabled?: boolean;
  type?: 'button' | 'submit';
}) {
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      className="rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-surface-950 transition-colors hover:bg-accent-hover disabled:opacity-50"
    >
      {children}
    </button>
  );
}

export function GhostButton({
  children,
  onClick,
  disabled,
}: {
  children: ReactNode;
  onClick?: () => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="rounded-lg border border-surface-700 px-3 py-1.5 text-xs text-surface-300 transition-colors hover:border-surface-500 hover:text-surface-100 disabled:opacity-50"
    >
      {children}
    </button>
  );
}

export function LoadingSpinner() {
  return (
    <div className="flex items-center justify-center py-12">
      <div className="h-6 w-6 animate-spin rounded-full border-2 border-accent border-t-transparent" />
    </div>
  );
}

/**
 * Path picker — read-only text box showing the current value, with a button
 * that opens the system folder/file picker and a "Reset to default" chip.
 */
export function PathPicker({
  value,
  placeholder,
  onPick,
  onReset,
  onReveal,
  canReset,
  revealLabel = 'Open',
}: {
  value: string;
  placeholder: string;
  onPick: () => void;
  onReset?: () => void;
  onReveal?: () => void;
  canReset?: boolean;
  revealLabel?: string;
}) {
  return (
    <div className="flex items-center gap-2">
      <div className="flex-1 truncate rounded-lg border border-surface-700/50 bg-surface-800/40 px-3 py-1.5 text-xs font-mono text-surface-300" title={value || placeholder}>
        {value || <span className="text-surface-500">{placeholder}</span>}
      </div>
      {onReveal && value && (
        <GhostButton onClick={onReveal}>{revealLabel}</GhostButton>
      )}
      <PrimaryButton onClick={onPick}>Change…</PrimaryButton>
      {onReset && canReset && (
        <GhostButton onClick={onReset}>Reset</GhostButton>
      )}
    </div>
  );
}
