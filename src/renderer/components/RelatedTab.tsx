import { HexCard } from './HexCard';
import type { ContentItem } from '../../shared/types';

interface RelatedTabProps {
  sameGroup: ContentItem[];
  sameSource: ContentItem[];
  groupName?: string;
  onItemClick: (item: ContentItem) => void;
}

export function RelatedTab({ sameGroup, sameSource, groupName, onItemClick }: RelatedTabProps) {
  return (
    <div className="space-y-6">
      {sameGroup.length > 0 && (
        <div>
          <h3 className="mb-3 text-sm font-medium text-surface-400">
            {groupName ? `From ${groupName}` : 'Same category'}
          </h3>
          <div className="grid grid-cols-[repeat(auto-fill,minmax(120px,1fr))] gap-3">
            {sameGroup.slice(0, 12).map((item) => (
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

      {sameSource.length > 0 && (
        <div>
          <h3 className="mb-3 text-sm font-medium text-surface-400">
            More from this source
          </h3>
          <div className="grid grid-cols-[repeat(auto-fill,minmax(120px,1fr))] gap-3">
            {sameSource.slice(0, 12).map((item) => (
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
