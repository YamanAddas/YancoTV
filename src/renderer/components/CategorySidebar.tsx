interface CategorySidebarProps {
  categories: string[];
  selected: string | null;
  onSelect: (category: string | null) => void;
  isLoading?: boolean;
  categoryCounts?: Record<string, number>;
  totalCount?: number;
}

export function CategorySidebar({
  categories,
  selected,
  onSelect,
  isLoading,
  categoryCounts,
  totalCount,
}: CategorySidebarProps) {
  if (isLoading) {
    return (
      <div className="glass w-48 flex-shrink-0 space-y-1 rounded-xl p-2">
        {Array.from({ length: 10 }).map((_, i) => (
          <div key={i} className="h-8 animate-pulse rounded-md bg-surface-800/50" />
        ))}
      </div>
    );
  }

  if (categories.length === 0) return null;

  return (
    <div className="glass w-48 flex-shrink-0 overflow-y-auto rounded-xl p-2">
      <button
        onClick={() => onSelect(null)}
        className={`flex w-full items-center justify-between rounded-lg px-3 py-2 text-left text-sm font-medium transition-all duration-200 ${
          selected === null
            ? 'bg-accent/10 text-accent shadow-glow-sm'
            : 'text-surface-400 hover:bg-surface-700/30 hover:text-surface-200'
        }`}
      >
        <span>All</span>
        {totalCount != null && (
          <span
            className={`ml-2 rounded px-1.5 py-px text-[10px] font-medium tabular-nums ${
              selected === null
                ? 'bg-accent/15 text-accent'
                : 'bg-surface-700/40 text-surface-500'
            }`}
          >
            {totalCount.toLocaleString()}
          </span>
        )}
      </button>

      {/* Separator */}
      <div className="mx-2 my-1.5 border-t border-accent/8" />

      {categories.map((cat) => {
        const count = categoryCounts?.[cat];
        return (
          <button
            key={cat}
            onClick={() => onSelect(cat)}
            className={`flex w-full items-center justify-between rounded-lg px-3 py-2 text-left text-sm transition-all duration-200 ${
              selected === cat
                ? 'bg-accent/10 text-accent shadow-glow-sm'
                : 'text-surface-400 hover:bg-surface-700/30 hover:text-surface-200'
            }`}
          >
            <span className="min-w-0 flex-1 truncate">{cat}</span>
            {count != null && (
              <span
                className={`ml-2 flex-shrink-0 rounded px-1.5 py-px text-[10px] font-medium tabular-nums ${
                  selected === cat
                    ? 'bg-accent/15 text-accent'
                    : 'bg-surface-700/40 text-surface-500'
                }`}
              >
                {count.toLocaleString()}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
