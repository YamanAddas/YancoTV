import { useState } from 'react';
import type { ContentMetadata } from '../../shared/types';
import { useT } from '../i18n';

interface InfoTabProps {
  metadata: ContentMetadata;
}

export function InfoTab({ metadata }: InfoTabProps) {
  const t = useT();
  const [expanded, setExpanded] = useState(false);

  const description = metadata.plot || metadata.description;
  const cast = metadata.cast;
  const director = metadata.director;
  const genre = metadata.genre;
  const releaseDate = metadata.releaseDate;

  // Parse genres into array
  const genres = genre
    ? genre.split(/[,/]/).map((g) => g.trim()).filter(Boolean)
    : [];

  return (
    <div className="space-y-4">
      {/* Description */}
      {description && (
        <div className="rounded-xl border border-accent/5 bg-surface-900/30 p-5">
          <p
            className={`text-sm leading-relaxed text-surface-300 ${
              !expanded ? 'line-clamp-4' : ''
            }`}
          >
            {description}
          </p>
          {description.length > 200 && (
            <button
              onClick={() => setExpanded(!expanded)}
              className="mt-2 text-xs font-medium text-accent transition-colors hover:text-accent-hover"
            >
              {expanded ? 'Show less' : 'Show more'}
            </button>
          )}
        </div>
      )}

      {/* Info card */}
      {(cast || director || genres.length > 0 || releaseDate) && (
        <div className="rounded-xl border border-accent/5 bg-surface-900/30 p-5 space-y-3">
          {cast && (
            <InfoRow label="Cast" value={cast} />
          )}

          {director && (
            <InfoRow label={t('detail.director')} value={director} />
          )}

          {genres.length > 0 && (
            <div>
              <span className="flex items-center gap-1.5 text-xs font-medium uppercase tracking-wider text-surface-500">
                <span className="text-[10px] text-accent/40">&#x2B21;</span>
                Genre
              </span>
              <div className="mt-1.5 flex flex-wrap gap-1.5">
                {genres.map((g) => (
                  <span
                    key={g}
                    className="rounded-md bg-surface-800/60 px-2.5 py-1 text-xs text-surface-300"
                    style={{
                      clipPath: 'polygon(8% 0%, 92% 0%, 100% 50%, 92% 100%, 8% 100%, 0% 50%)',
                      padding: '3px 12px',
                    }}
                  >
                    {g}
                  </span>
                ))}
              </div>
            </div>
          )}

          {releaseDate && (
            <InfoRow label="Release" value={releaseDate} />
          )}
        </div>
      )}
    </div>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <span className="flex items-center gap-1.5 text-xs font-medium uppercase tracking-wider text-surface-500">
        <span className="text-[10px] text-accent/40">&#x2B21;</span>
        {label}
      </span>
      <p className="mt-0.5 text-sm text-surface-300">{value}</p>
    </div>
  );
}
