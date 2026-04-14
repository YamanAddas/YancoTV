interface CategorySidebarProps {
  categories: string[];
  selected: string | null;
  onSelect: (category: string | null) => void;
  isLoading?: boolean;
}

export function CategorySidebar({
  categories,
  selected,
  onSelect,
  isLoading,
}: CategorySidebarProps) {
  if (isLoading) {
    return (
      <div className="w-48 flex-shrink-0 space-y-1">
        {Array.from({ length: 8 }).map((_, i) => (
          <div key={i} className="h-8 animate-pulse rounded-md bg-surface-800" />
        ))}
      </div>
    );
  }

  if (categories.length === 0) return null;

  return (
    <div className="w-48 flex-shrink-0 overflow-y-auto">
      <button
        onClick={() => onSelect(null)}
        className={`w-full rounded-md px-3 py-2 text-left text-sm font-medium transition-colors ${
          selected === null
            ? 'bg-accent/10 text-accent'
            : 'text-surface-400 hover:bg-surface-800 hover:text-surface-200'
        }`}
      >
        All
      </button>
      {categories.map((cat) => (
        <button
          key={cat}
          onClick={() => onSelect(cat)}
          className={`w-full rounded-md px-3 py-2 text-left text-sm transition-colors ${
            selected === cat
              ? 'bg-accent/10 text-accent'
              : 'text-surface-400 hover:bg-surface-800 hover:text-surface-200'
          }`}
        >
          <span className="line-clamp-1">{cat}</span>
        </button>
      ))}
    </div>
  );
}
