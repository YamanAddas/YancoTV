import { HexCard } from './HexCard';
import type { ContentItem } from '../../shared/types';

interface RelatedTabProps {
  sameGroup: ContentItem[];
  sameSource: ContentItem[];
  groupName?: string;
  onItemClick: (item: ContentItem) => void;
}

export function RelatedTab({ sameGroup, sameSource, groupName, onItemClick }: RelatedTabProps) {
  // Combine and dedupe — prioritize same group
  const shownIds = new Set<string>();

  return (
    <div className="space-y-6">
      {sameGroup.length > 0 && (
        <div>
          <h3 className="mb-3 text-lg font-semibold text-surface-100">
            More {groupName ? `in ${groupName}` : 'Like This'}
          </h3>
          <div className="grid grid-cols-[repeat(auto-fill,minmax(120px,1fr))] gap-3">
            {sameGroup.slice(0, 12).map((item) => {
              shownIds.add(item.id);
              return (
                <HexCard
                  key={item.id}
                  title={item.cleanTitle || item.title}
                  imageUrl={item.logoUrl}
                  fallbackLetter={(item.cleanTitle || item.title).charAt(0)}
                  onClick={() => onItemClick(item)}
                  size="normal"
                />
              );
            })}
          </div>
        </div>
      )}

      {sameSource.length > 0 && (
        <div>
          <h3 className="mb-3 text-lg font-semibold text-surface-100">
            You Might Also Like
          </h3>
          <div className="grid grid-cols-[repeat(auto-fill,minmax(120px,1fr))] gap-3">
            {sameSource
              .filter((item) => !shownIds.has(item.id))
              .slice(0, 12)
              .map((item) => (
                <HexCard
                  key={item.id}
                  title={item.cleanTitle || item.title}
                  imageUrl={item.logoUrl}
                  fallbackLetter={(item.cleanTitle || item.title).charAt(0)}
                  onClick={() => onItemClick(item)}
                  size="normal"
                />
              ))}
          </div>
        </div>
      )}
    </div>
  );
}
