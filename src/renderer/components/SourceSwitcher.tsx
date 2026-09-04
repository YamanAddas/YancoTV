import { useEffect, useState } from 'react';
import { useT } from '../i18n';

interface Source {
  id: string;
  name: string;
  type: string;
}

interface SourceSwitcherProps {
  selected: string | null;
  onSelect: (sourceId: string | null) => void;
}

export function SourceSwitcher({ selected, onSelect }: SourceSwitcherProps) {
  const t = useT();
  const [sources, setSources] = useState<Source[]>([]);

  useEffect(() => {
    if (!window.api) return;
    window.api.sources.getAll().then(setSources);
  }, []);

  if (sources.length <= 1) return null;

  return (
    <div className="flex items-center gap-2">
      <span className="text-xs text-surface-500">Source:</span>
      <select
        value={selected ?? ''}
        onChange={(e) => onSelect(e.target.value || null)}
        className="rounded-md border border-accent/5 bg-surface-800 px-2 py-1 text-sm text-surface-200 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
      >
        <option value="">{t('source.allSources')}</option>
        {sources.map((s) => (
          <option key={s.id} value={s.id}>
            {s.name}
          </option>
        ))}
      </select>
    </div>
  );
}
