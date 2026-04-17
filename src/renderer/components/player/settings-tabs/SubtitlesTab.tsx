import { useMemo, useState } from 'react';
import { usePlayerStore } from '../../../stores/player-store';
import { DelayControl } from '../SettingsPanel';
import { guessTitle } from '../../../utils/guess-title';

/**
 * Three subtitle sources, in the order a user usually wants to try them:
 *   1. Tracks already embedded in the stream (zero-click).
 *   2. A local .srt/.ass file on disk.
 *   3. OpenSubtitles search + direct download.
 */
type SubSection = 'embedded' | 'local' | 'opensubtitles';

export function SubtitlesTab() {
  const subtitleTracks = usePlayerStore((s) => s.subtitleTracks);
  const subtitleDelay = usePlayerStore((s) => s.subtitleDelay);
  const backend = usePlayerStore((s) => s.backend);
  const toggleSubtitles = usePlayerStore((s) => s.toggleSubtitles);
  const loadSubtitleFile = usePlayerStore((s) => s.loadSubtitleFile);
  const setSubtitleTrack = usePlayerStore((s) => s.setSubtitleTrack);
  const setSubtitleDelay = usePlayerStore((s) => s.setSubtitleDelay);
  const adjustSubtitleDelay = usePlayerStore((s) => s.adjustSubtitleDelay);

  const [section, setSection] = useState<SubSection>(
    subtitleTracks.length > 0 ? 'embedded' : 'opensubtitles',
  );

  return (
    <div className="space-y-4">
      {/* Section tabs */}
      <div className="flex rounded-lg bg-surface-800 p-1">
        <SectionTab
          active={section === 'embedded'}
          onClick={() => setSection('embedded')}
          badge={subtitleTracks.length || undefined}
        >
          In stream
        </SectionTab>
        <SectionTab
          active={section === 'local'}
          onClick={() => setSection('local')}
        >
          File
        </SectionTab>
        <SectionTab
          active={section === 'opensubtitles'}
          onClick={() => setSection('opensubtitles')}
        >
          OpenSubtitles
        </SectionTab>
      </div>

      {section === 'embedded' && (
        <EmbeddedSection
          tracks={subtitleTracks}
          onSelect={setSubtitleTrack}
          onToggle={toggleSubtitles}
        />
      )}

      {section === 'local' && <LocalSection onPick={loadSubtitleFile} />}

      {section === 'opensubtitles' && <OpenSubtitlesSection />}

      {/* Subtitle delay — applies to whichever track is active */}
      <DelayControl
        label="Subtitle Delay"
        hint="Negative = earlier, Positive = later"
        value={subtitleDelay}
        onReset={() => setSubtitleDelay(0)}
        onStep={(d) => adjustSubtitleDelay(d)}
        step={0.1}
        disabled={backend !== 'mpv'}
      />
    </div>
  );
}

function SectionTab({
  active,
  onClick,
  badge,
  children,
}: {
  active: boolean;
  onClick: () => void;
  badge?: number;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      className={`flex-1 rounded-md px-2 py-1.5 text-xs font-medium transition-colors ${
        active
          ? 'bg-surface-700 text-accent'
          : 'text-surface-400 hover:text-surface-200'
      }`}
    >
      {children}
      {typeof badge === 'number' && (
        <span className="ml-1 rounded-full bg-accent/20 px-1.5 py-0.5 text-[10px] text-accent">
          {badge}
        </span>
      )}
    </button>
  );
}

// ---------------------------------------------------------------------------
// Embedded
// ---------------------------------------------------------------------------

function EmbeddedSection({
  tracks,
  onSelect,
  onToggle,
}: {
  tracks: Array<{ id: number; title: string; language?: string; selected: boolean }>;
  onSelect: (id: number) => void;
  onToggle: () => void;
}) {
  if (tracks.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-surface-700 bg-surface-800/40 px-3 py-4 text-center">
        <p className="text-sm text-surface-400">No embedded subtitles in this stream.</p>
        <p className="mt-1 text-xs text-surface-500">Try the File or OpenSubtitles tab.</p>
      </div>
    );
  }

  return (
    <div className="space-y-1">
      <button
        onClick={onToggle}
        className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm text-surface-300 transition-colors hover:bg-surface-800"
      >
        <EyeIcon />
        <span>Toggle on/off (S)</span>
      </button>
      {tracks.map((track) => (
        <button
          key={track.id}
          onClick={() => onSelect(track.id)}
          className={`flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm transition-colors ${
            track.selected
              ? 'bg-accent/20 text-accent'
              : 'text-surface-300 hover:bg-surface-800'
          }`}
        >
          <span className="flex-1">{track.title}</span>
          {track.language && (
            <span className="text-xs text-surface-500">{track.language}</span>
          )}
          {track.selected && <CheckIcon />}
        </button>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Local file
// ---------------------------------------------------------------------------

function LocalSection({ onPick }: { onPick: () => Promise<void> }) {
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const handlePick = async () => {
    setLoading(true);
    setMessage(null);
    try {
      await onPick();
      setMessage('Subtitle loaded.');
    } catch (err) {
      setMessage(`Failed: ${(err as Error).message}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="space-y-2">
      <button
        onClick={handlePick}
        disabled={loading}
        className="flex w-full items-center gap-2 rounded-lg border border-dashed border-surface-600 px-3 py-3 text-sm text-surface-300 transition-colors hover:border-accent/50 hover:text-accent disabled:opacity-50"
      >
        <svg
          className="h-4 w-4"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
          strokeWidth={1.5}
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5m-13.5-9L12 3m0 0l4.5 4.5M12 3v13.5"
          />
        </svg>
        <span>{loading ? 'Opening...' : 'Choose .srt / .ass file...'}</span>
      </button>
      {message && <p className="text-xs text-surface-400">{message}</p>}
      <p className="text-xs text-surface-500">
        Supported: SRT, ASS, SSA, VTT, SUB, IDX
      </p>
    </div>
  );
}

// ---------------------------------------------------------------------------
// OpenSubtitles
// ---------------------------------------------------------------------------

interface OsResult {
  id: string;
  attributes: {
    subtitle_id: string;
    language: string;
    release: string;
    download_count: number;
    hearing_impaired: boolean;
    feature_details?: { title: string; year: number };
    files: Array<{ file_id: number; file_name: string }>;
  };
}

const LANGUAGE_OPTIONS: Array<{ code: string; label: string }> = [
  { code: 'en', label: 'English' },
  { code: 'tr', label: 'Türkçe' },
  { code: 'ar', label: 'العربية' },
  { code: 'es', label: 'Español' },
  { code: 'fr', label: 'Français' },
  { code: 'de', label: 'Deutsch' },
  { code: 'it', label: 'Italiano' },
  { code: 'pt', label: 'Português' },
  { code: 'ru', label: 'Русский' },
  { code: 'nl', label: 'Nederlands' },
  { code: 'pl', label: 'Polski' },
  { code: 'ja', label: '日本語' },
  { code: 'ko', label: '한국어' },
  { code: 'zh', label: '中文' },
];

function OpenSubtitlesSection() {
  const currentTitle = usePlayerStore((s) => s.currentTitle);

  // Parse the current title once — same cleaner used for UI chips and
  // the initial query prefill. Year/season/episode get passed to the API
  // as separate fields for more accurate matching.
  const guess = useMemo(() => guessTitle(currentTitle), [currentTitle]);

  const [query, setQuery] = useState(guess.title);
  const [year, setYear] = useState<string>(guess.year ? String(guess.year) : '');
  const [season, setSeason] = useState<string>(guess.season ? String(guess.season) : '');
  const [episode, setEpisode] = useState<string>(guess.episode ? String(guess.episode) : '');
  const [language, setLanguage] = useState('en');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [results, setResults] = useState<OsResult[]>([]);
  const [downloadingId, setDownloadingId] = useState<number | null>(null);
  const [quotaMessage, setQuotaMessage] = useState<string | null>(null);

  const applyGuess = () => {
    setQuery(guess.title);
    setYear(guess.year ? String(guess.year) : '');
    setSeason(guess.season ? String(guess.season) : '');
    setEpisode(guess.episode ? String(guess.episode) : '');
  };
  const applyRaw = () => {
    setQuery(guess.raw);
    setYear('');
    setSeason('');
    setEpisode('');
  };

  const runSearch = async () => {
    const trimmed = query.trim();
    if (!trimmed) {
      setError('Enter a movie or show title to search.');
      return;
    }
    setLoading(true);
    setError(null);
    setResults([]);
    try {
      // Append year to query — OpenSubtitles ranks by relevance and a year in
      // the query string disambiguates titles like "Dune" (1984 vs 2021).
      const y = year.trim();
      const queryWithYear = y && !/\b\d{4}\b/.test(trimmed) ? `${trimmed} ${y}` : trimmed;
      const s = Number(season);
      const e = Number(episode);
      const res = await window.api?.subtitles.search({
        query: queryWithYear,
        languages: language,
        season: Number.isFinite(s) && s > 0 ? s : undefined,
        episode: Number.isFinite(e) && e > 0 ? e : undefined,
        type: Number.isFinite(e) && e > 0 ? 'episode' : 'movie',
      });
      if (!res?.ok) {
        setError(res?.error || 'Search failed');
      } else {
        setResults((res.results as OsResult[]) ?? []);
      }
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setLoading(false);
    }
  };

  const download = async (fileId: number) => {
    setDownloadingId(fileId);
    setError(null);
    setQuotaMessage(null);
    try {
      const res = await window.api?.subtitles.downloadAndLoad(fileId);
      if (!res?.ok) {
        setError(res?.error || 'Download failed');
      } else {
        setQuotaMessage(`Subtitle loaded. ${res.remaining ?? '?'} downloads remaining today.`);
      }
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setDownloadingId(null);
    }
  };

  return (
    <div className="space-y-3">
      {/* Smart-detect chips — shown only when we actually pulled something
          out of the raw title. User can pick either version or edit freely. */}
      {guess.raw && guess.changed && (
        <div className="space-y-1.5">
          <p className="text-[10px] font-semibold uppercase tracking-wider text-surface-500">
            Detected
          </p>
          <div className="flex flex-wrap gap-2">
            <button
              onClick={applyGuess}
              className="group flex min-w-0 max-w-full items-center gap-1.5 rounded-lg border border-accent/40 bg-accent/10 px-2.5 py-1.5 text-left text-xs text-accent transition-colors hover:bg-accent/20"
              title="Use the cleaned title"
            >
              <SparkleIcon />
              <span className="truncate">
                {guess.title}
                {guess.year && <span className="opacity-80"> ({guess.year})</span>}
                {guess.season && guess.episode && (
                  <span className="opacity-80">
                    {' '}
                    S{String(guess.season).padStart(2, '0')}E
                    {String(guess.episode).padStart(2, '0')}
                  </span>
                )}
              </span>
            </button>
            <button
              onClick={applyRaw}
              className="flex min-w-0 max-w-full items-center gap-1.5 rounded-lg border border-surface-700 px-2.5 py-1.5 text-left text-xs text-surface-300 transition-colors hover:bg-surface-800"
              title="Use the original title as-is"
            >
              <span className="truncate opacity-70">Original:</span>
              <span className="truncate">{guess.raw}</span>
            </button>
          </div>
        </div>
      )}

      <div className="space-y-2">
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') runSearch();
          }}
          placeholder="Movie / series title..."
          className="w-full rounded-lg border border-surface-700 bg-surface-800 px-3 py-2 text-sm text-surface-100 placeholder:text-surface-500 focus:border-accent focus:outline-none"
        />
        <div className="grid grid-cols-3 gap-2">
          <input
            type="text"
            inputMode="numeric"
            value={year}
            onChange={(e) => setYear(e.target.value.replace(/\D/g, '').slice(0, 4))}
            placeholder="Year"
            className="rounded-lg border border-surface-700 bg-surface-800 px-2 py-2 text-xs text-surface-100 placeholder:text-surface-500 focus:border-accent focus:outline-none"
          />
          <input
            type="text"
            inputMode="numeric"
            value={season}
            onChange={(e) => setSeason(e.target.value.replace(/\D/g, '').slice(0, 2))}
            placeholder="Season"
            className="rounded-lg border border-surface-700 bg-surface-800 px-2 py-2 text-xs text-surface-100 placeholder:text-surface-500 focus:border-accent focus:outline-none"
          />
          <input
            type="text"
            inputMode="numeric"
            value={episode}
            onChange={(e) => setEpisode(e.target.value.replace(/\D/g, '').slice(0, 3))}
            placeholder="Episode"
            className="rounded-lg border border-surface-700 bg-surface-800 px-2 py-2 text-xs text-surface-100 placeholder:text-surface-500 focus:border-accent focus:outline-none"
          />
        </div>
        <div className="flex items-center gap-2">
          <select
            value={language}
            onChange={(e) => setLanguage(e.target.value)}
            className="flex-1 rounded-lg border border-surface-700 bg-surface-800 px-2 py-2 text-xs text-surface-200 focus:border-accent focus:outline-none"
          >
            {LANGUAGE_OPTIONS.map((opt) => (
              <option key={opt.code} value={opt.code}>
                {opt.label}
              </option>
            ))}
          </select>
          <button
            onClick={runSearch}
            disabled={loading}
            className="rounded-lg bg-accent px-3 py-2 text-xs font-semibold text-surface-950 transition-colors hover:bg-accent-hover disabled:opacity-50"
          >
            {loading ? 'Searching...' : 'Search'}
          </button>
        </div>
      </div>

      {error && (
        <p className="rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-xs text-red-300">
          {error}
        </p>
      )}
      {quotaMessage && !error && (
        <p className="rounded-lg border border-accent/30 bg-accent/10 px-3 py-2 text-xs text-accent">
          {quotaMessage}
        </p>
      )}

      <div className="space-y-1">
        {results.length === 0 && !loading && !error && (
          <p className="text-center text-xs text-surface-500">
            Search for subtitles by title, then pick a release below.
          </p>
        )}
        {results.map((r) => {
          const file = r.attributes.files[0];
          if (!file) return null;
          const downloading = downloadingId === file.file_id;
          return (
            <button
              key={r.id}
              onClick={() => download(file.file_id)}
              disabled={downloading}
              className="flex w-full items-start gap-2 rounded-lg border border-surface-700 bg-surface-800/50 px-3 py-2 text-left text-xs transition-colors hover:border-accent/50 disabled:opacity-60"
            >
              <div className="min-w-0 flex-1">
                <div className="truncate font-medium text-surface-200">
                  {r.attributes.release || file.file_name}
                </div>
                <div className="mt-0.5 flex items-center gap-2 text-[11px] text-surface-500">
                  <span className="rounded bg-surface-700 px-1.5 py-0.5 uppercase">
                    {r.attributes.language}
                  </span>
                  {r.attributes.feature_details?.year && (
                    <span>{r.attributes.feature_details.year}</span>
                  )}
                  <span>↓ {r.attributes.download_count}</span>
                  {r.attributes.hearing_impaired && <span>CC</span>}
                </div>
              </div>
              <span className="shrink-0 self-center text-accent">
                {downloading ? (
                  <svg className="h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                  </svg>
                ) : (
                  <DownloadIcon />
                )}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Icons
// ---------------------------------------------------------------------------

function EyeIcon() {
  return (
    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M2.036 12.322a1.012 1.012 0 010-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178z" />
      <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
    </svg>
  );
}

function CheckIcon() {
  return (
    <svg className="h-4 w-4 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
      <path
        fillRule="evenodd"
        d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
        clipRule="evenodd"
      />
    </svg>
  );
}

function SparkleIcon() {
  return (
    <svg className="h-3.5 w-3.5 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.091zM18.259 8.715L18 9.75l-.259-1.035a3.375 3.375 0 00-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 002.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 002.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 00-2.456 2.456z" />
    </svg>
  );
}

function DownloadIcon() {
  return (
    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5M16.5 12L12 16.5m0 0L7.5 12m4.5 4.5V3" />
    </svg>
  );
}
