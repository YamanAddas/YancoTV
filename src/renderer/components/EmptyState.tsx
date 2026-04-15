import { useNavigate } from 'react-router-dom';

interface EmptyStateProps {
  icon: 'tv' | 'film' | 'layers' | 'search' | 'heart';
  title: string;
  message: string;
  showSettingsLink?: boolean;
}

const iconPaths: Record<string, string> = {
  tv: 'M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z',
  film: 'M7 4v16M17 4v16M3 8h4m10 0h4M3 12h18M3 16h4m10 0h4M4 20h16a1 1 0 001-1V5a1 1 0 00-1-1H4a1 1 0 00-1 1v14a1 1 0 001 1z',
  layers:
    'M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10',
  search: 'M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z',
  heart:
    'M21 8.25c0-2.485-2.099-4.5-4.688-4.5-1.935 0-3.597 1.126-4.312 2.733-.715-1.607-2.377-2.733-4.313-2.733C5.1 3.75 3 5.765 3 8.25c0 7.22 9 12 9 12s9-4.78 9-12z',
};

export function EmptyState({ icon, title, message, showSettingsLink = true }: EmptyStateProps) {
  const navigate = useNavigate();

  return (
    <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-surface-700 bg-surface-900/50 py-16">
      <svg
        className="h-12 w-12 text-surface-600"
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
        strokeWidth={1}
      >
        <path strokeLinecap="round" strokeLinejoin="round" d={iconPaths[icon]} />
      </svg>
      <h3 className="mt-4 text-sm font-medium text-surface-300">{title}</h3>
      <p className="mt-1 text-sm text-surface-500">{message}</p>
      {showSettingsLink && (
        <button
          onClick={() => navigate('/settings')}
          className="mt-4 rounded-lg bg-accent/10 px-4 py-2 text-sm font-medium text-accent shadow-glow-sm transition-colors hover:bg-accent/20"
        >
          Go to Settings
        </button>
      )}
    </div>
  );
}
