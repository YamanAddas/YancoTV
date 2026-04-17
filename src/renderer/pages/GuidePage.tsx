import { useState, useMemo, useCallback, useRef, useEffect } from 'react';
import { useGuideData, useEpgStats, triggerEpgRefresh, useEpgAutoInvalidate } from '../hooks/use-epg';
import { useQueryClient } from '@tanstack/react-query';
import { usePlayerStore } from '../stores/player-store';
import type { EpgProgramme, EpgGuideChannel } from '../../shared/types/epg';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const HOUR_WIDTH = 240; // pixels per hour
const CHANNEL_ROW_HEIGHT = 60; // pixels per channel row
const CHANNEL_LABEL_WIDTH = 200; // left sidebar width
const TIME_HEADER_HEIGHT = 40; // top time header height
const HOURS_TO_SHOW = 6; // default time window
const OVERSCAN = 5; // extra rows to render above/below viewport

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function startOfHour(ts: number): number {
  return Math.floor(ts / 3600) * 3600;
}

function formatTime(ts: number): string {
  const d = new Date(ts * 1000);
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function formatDate(ts: number): string {
  const d = new Date(ts * 1000);
  return d.toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' });
}

// ---------------------------------------------------------------------------
// GuidePage
// ---------------------------------------------------------------------------

export function GuidePage() {
  const queryClient = useQueryClient();
  const play = usePlayerStore((s) => s.play);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [selectedProgramme, setSelectedProgramme] = useState<{
    programme: EpgProgramme;
    channel: EpgGuideChannel;
  } | null>(null);

  // Auto-invalidate EPG queries when a push refresh completes
  useEpgAutoInvalidate();

  // Time window: start at the current hour, show HOURS_TO_SHOW hours
  const now = useMemo(() => Math.floor(Date.now() / 1000), []);
  const [windowStart, setWindowStart] = useState(() => startOfHour(now));
  const windowEnd = windowStart + HOURS_TO_SHOW * 3600;

  const { data: channels, isLoading } = useGuideData(windowStart, windowEnd);
  const { data: stats } = useEpgStats();

  const handleRefresh = useCallback(async () => {
    setIsRefreshing(true);
    try {
      await triggerEpgRefresh();
      queryClient.invalidateQueries({ queryKey: ['epg'] });
    } finally {
      setIsRefreshing(false);
    }
  }, [queryClient]);

  const handlePrevious = useCallback(() => {
    setWindowStart((prev) => prev - HOURS_TO_SHOW * 3600);
  }, []);

  const handleNext = useCallback(() => {
    setWindowStart((prev) => prev + HOURS_TO_SHOW * 3600);
  }, []);

  const handleNow = useCallback(() => {
    setWindowStart(startOfHour(Math.floor(Date.now() / 1000)));
  }, []);

  const handleProgrammeClick = useCallback(
    (programme: EpgProgramme, channel: EpgGuideChannel) => {
      setSelectedProgramme({ programme, channel });
    },
    [],
  );

  const hasEpgData = stats && stats.programmeCount > 0;

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold text-surface-100 text-glow-sm">TV Guide</h2>
          {stats && (
            <p className="text-sm text-surface-500">
              {stats.programmeCount.toLocaleString()} programmes across{' '}
              {stats.channelCount} channels
              {stats.lastRefreshedAt && (
                <> &middot; Updated {new Date(stats.lastRefreshedAt).toLocaleString()}</>
              )}
            </p>
          )}
        </div>
        <button
          onClick={handleRefresh}
          disabled={isRefreshing}
          className="rounded-lg bg-accent shadow-glow-sm px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-accent-hover hover:shadow-glow disabled:opacity-50"
        >
          {isRefreshing ? 'Refreshing...' : 'Refresh EPG'}
        </button>
      </div>

      {!hasEpgData && !isLoading ? (
        <EmptyEpgState onRefresh={handleRefresh} isRefreshing={isRefreshing} />
      ) : (
        <>
          {/* Time navigation */}
          <div className="mb-3 flex items-center gap-2">
            <button
              onClick={handlePrevious}
              className="rounded-lg bg-surface-800 px-3 py-1.5 text-sm text-surface-300 hover:bg-surface-700"
            >
              &larr; Earlier
            </button>
            <button
              onClick={handleNow}
              className="rounded-lg bg-surface-800 px-3 py-1.5 text-sm text-surface-300 hover:bg-surface-700"
            >
              Now
            </button>
            <button
              onClick={handleNext}
              className="rounded-lg bg-surface-800 px-3 py-1.5 text-sm text-surface-300 hover:bg-surface-700"
            >
              Later &rarr;
            </button>
            <span className="ml-2 text-sm text-surface-400">
              {formatDate(windowStart)} &middot; {formatTime(windowStart)} &ndash;{' '}
              {formatTime(windowEnd)}
            </span>
          </div>

          {/* EPG Grid */}
          {isLoading ? (
            <GridSkeleton />
          ) : channels && channels.length > 0 ? (
            <EpgGrid
              channels={channels}
              windowStart={windowStart}
              windowEnd={windowEnd}
              now={Math.floor(Date.now() / 1000)}
              onProgrammeClick={handleProgrammeClick}
            />
          ) : (
            <p className="text-center text-surface-500 py-12">
              No guide data available for this time window.
            </p>
          )}
        </>
      )}

      {/* Programme detail popup */}
      {selectedProgramme && (
        <ProgrammeDetail
          programme={selectedProgramme.programme}
          channel={selectedProgramme.channel}
          onClose={() => setSelectedProgramme(null)}
          onPlay={(streamUrl, title) => {
            play(streamUrl, title);
            setSelectedProgramme(null);
          }}
        />
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// EpgGrid — single-container virtualized grid
//
// Layout strategy: one overflow:auto div handles BOTH scroll axes.
//   - Time header:     position:sticky top:0        → stays visible on vertical scroll
//   - Corner cell:     position:sticky top:0 left:0  → sticks to top-left corner
//   - Channel labels:  position:sticky left:0        → stay visible on horizontal scroll
//
// Row virtualization uses paddingTop/paddingBottom so only visible rows are
// in the DOM (+ OVERSCAN rows). Eliminates ~4 000 DOM nodes for large EPG feeds.
// No separate scroll sync hack needed — single container keeps everything in sync.
// ---------------------------------------------------------------------------

function EpgGrid({
  channels,
  windowStart,
  windowEnd,
  now,
  onProgrammeClick,
}: {
  channels: EpgGuideChannel[];
  windowStart: number;
  windowEnd: number;
  now: number;
  onProgrammeClick: (p: EpgProgramme, ch: EpgGuideChannel) => void;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [containerHeight, setContainerHeight] = useState(600);

  const totalWidth = ((windowEnd - windowStart) / 3600) * HOUR_WIDTH;

  // Track container height for virtual row computation
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setContainerHeight(el.clientHeight));
    ro.observe(el);
    setContainerHeight(el.clientHeight);
    return () => ro.disconnect();
  }, []);

  // Generate hour marks
  const hours = useMemo(() => {
    const result: number[] = [];
    let t = windowStart;
    while (t < windowEnd) {
      result.push(t);
      t += 3600;
    }
    return result;
  }, [windowStart, windowEnd]);

  // Now indicator horizontal position
  const nowOffset =
    now >= windowStart && now <= windowEnd
      ? ((now - windowStart) / 3600) * HOUR_WIDTH
      : null;

  // Scroll to "now" on initial mount
  useEffect(() => {
    if (containerRef.current && nowOffset !== null) {
      containerRef.current.scrollLeft = Math.max(0, nowOffset - 100);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Virtual row window
  const visibleAreaHeight = Math.max(containerHeight - TIME_HEADER_HEIGHT, 1);
  const firstVisible = Math.max(0, Math.floor(scrollTop / CHANNEL_ROW_HEIGHT) - OVERSCAN);
  const lastVisible = Math.min(
    channels.length - 1,
    Math.ceil((scrollTop + visibleAreaHeight) / CHANNEL_ROW_HEIGHT) + OVERSCAN,
  );
  const visibleChannels = channels.slice(firstVisible, lastVisible + 1);
  const paddingTop = firstVisible * CHANNEL_ROW_HEIGHT;
  const paddingBottom = Math.max(0, (channels.length - lastVisible - 1) * CHANNEL_ROW_HEIGHT);

  const handleScroll = useCallback((e: React.UIEvent<HTMLDivElement>) => {
    setScrollTop(e.currentTarget.scrollTop);
  }, []);

  return (
    <div
      ref={containerRef}
      className="min-h-0 flex-1 overflow-auto rounded-xl border border-accent/5"
      onScroll={handleScroll}
    >
      {/* Inner div drives the total scrollable area */}
      <div style={{ minWidth: CHANNEL_LABEL_WIDTH + totalWidth }}>

        {/* ── Time header row — sticky top ─────────────────────────────── */}
        <div
          className="sticky top-0 z-20 flex border-b border-accent/5 bg-surface-950"
          style={{ height: TIME_HEADER_HEIGHT }}
        >
          {/* Corner cell — also sticky left so it sticks on both axes */}
          <div
            className="sticky left-0 z-30 flex flex-shrink-0 items-center border-r border-accent/5 bg-surface-950 px-3"
            style={{ width: CHANNEL_LABEL_WIDTH }}
          >
            <span className="text-xs font-medium text-surface-500">Channel</span>
          </div>

          {hours.map((h) => (
            <div
              key={h}
              className="flex flex-shrink-0 items-center border-r border-accent/5 px-2"
              style={{ width: HOUR_WIDTH }}
            >
              <span className="text-xs font-medium text-surface-400">{formatTime(h)}</span>
            </div>
          ))}
        </div>

        {/* ── Channel rows — virtualized ────────────────────────────────── */}
        <div
          className="relative"
          style={{ paddingTop, paddingBottom }}
        >
          {/* Now indicator — vertical red line */}
          {nowOffset !== null && (
            <div
              className="pointer-events-none absolute inset-y-0 z-10 w-0.5 bg-red-500/80"
              style={{ left: CHANNEL_LABEL_WIDTH + nowOffset }}
            />
          )}

          {visibleChannels.map((ch) => (
            <div
              key={ch.tvgId}
              className="flex border-b border-accent/5"
              style={{ height: CHANNEL_ROW_HEIGHT }}
            >
              {/* Channel label — sticky left */}
              <div
                className="sticky left-0 z-10 flex flex-shrink-0 items-center gap-2 border-r border-accent/5 bg-surface-950 px-3"
                style={{ width: CHANNEL_LABEL_WIDTH }}
              >
                {ch.logoUrl && (
                  <img
                    src={ch.logoUrl}
                    alt=""
                    className="h-6 w-6 flex-shrink-0 rounded object-contain"
                    onError={(e) => {
                      (e.target as HTMLImageElement).style.display = 'none';
                    }}
                  />
                )}
                <span className="truncate text-sm text-surface-200" title={ch.name}>
                  {ch.name}
                </span>
              </div>

              {/* Programme cells */}
              <ProgrammeRow
                channel={ch}
                windowStart={windowStart}
                windowEnd={windowEnd}
                totalWidth={totalWidth}
                now={now}
                onProgrammeClick={onProgrammeClick}
              />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// ProgrammeRow — horizontally-positioned programme buttons for one channel
// ---------------------------------------------------------------------------

function ProgrammeRow({
  channel,
  windowStart,
  windowEnd,
  totalWidth,
  now,
  onProgrammeClick,
}: {
  channel: EpgGuideChannel;
  windowStart: number;
  windowEnd: number;
  totalWidth: number;
  now: number;
  onProgrammeClick: (p: EpgProgramme, ch: EpgGuideChannel) => void;
}) {
  return (
    <div
      className="relative flex-shrink-0"
      style={{ width: totalWidth, height: CHANNEL_ROW_HEIGHT }}
    >
      {channel.programmes.map((prog) => {
        const clampedStart = Math.max(prog.startTime, windowStart);
        const clampedEnd = Math.min(prog.endTime, windowEnd);
        const left = ((clampedStart - windowStart) / 3600) * HOUR_WIDTH;
        const width = ((clampedEnd - clampedStart) / 3600) * HOUR_WIDTH;

        if (width < 2) return null;

        const isNow = prog.startTime <= now && prog.endTime > now;
        const isPast = prog.endTime <= now;

        return (
          <button
            key={prog.id}
            onClick={() => onProgrammeClick(prog, channel)}
            className={`absolute top-1 bottom-1 overflow-hidden rounded border px-2 py-1 text-left transition-colors ${
              isNow
                ? 'border-accent/50 bg-accent/15 hover:bg-accent/25'
                : isPast
                  ? 'border-surface-800/50 bg-surface-800/30 text-surface-500 hover:bg-surface-800/50'
                  : 'border-surface-700/50 bg-surface-800/60 hover:bg-surface-700/60'
            }`}
            style={{ left, width: Math.max(width - 2, 1) }}
            title={`${prog.title}\n${formatTime(prog.startTime)} - ${formatTime(prog.endTime)}`}
          >
            <p className="truncate text-xs font-medium text-surface-200">{prog.title}</p>
            <p className="truncate text-[10px] text-surface-500">
              {formatTime(prog.startTime)} - {formatTime(prog.endTime)}
            </p>
          </button>
        );
      })}
    </div>
  );
}

// ---------------------------------------------------------------------------
// ProgrammeDetail — popup overlay for a selected programme
// ---------------------------------------------------------------------------

function ProgrammeDetail({
  programme,
  channel,
  onClose,
  onPlay,
}: {
  programme: EpgProgramme;
  channel: EpgGuideChannel;
  onClose: () => void;
  onPlay: (streamUrl: string, title: string) => void;
}) {
  const duration = Math.round((programme.endTime - programme.startTime) / 60);
  const nowSecs = Math.floor(Date.now() / 1000);
  const isNow = programme.startTime <= nowSecs && programme.endTime > nowSecs;
  const isPast = programme.endTime <= nowSecs;
  const isFuture = programme.startTime > nowSecs;

  const [catchupStatus, setCatchupStatus] = useState<{
    loading: boolean;
    available: boolean;
    archiveHours: number;
    streamUrl?: string;
    error?: string;
  }>({ loading: false, available: false, archiveHours: 0 });

  const [reminderId, setReminderId] = useState<string | null>(null);
  const [reminderBusy, setReminderBusy] = useState(false);

  // Check whether this programme already has a reminder set
  useEffect(() => {
    if (!isFuture || !window.api?.reminders) {
      setReminderId(null);
      return;
    }
    let cancelled = false;
    window.api.reminders.listActive().then((res) => {
      if (cancelled) return;
      if (res?.ok) {
        const match = res.reminders.find((r) => r.programmeId === programme.id);
        setReminderId(match?.id ?? null);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [isFuture, programme.id]);

  const handleSetReminder = useCallback(async () => {
    if (!window.api?.reminders || reminderBusy) return;
    setReminderBusy(true);
    try {
      const res = await window.api.reminders.set({
        programmeId: programme.id,
        channelTvgId: programme.channelTvgId,
        title: `${channel.name} — ${programme.title}`,
        startTime: programme.startTime,
        endTime: programme.endTime,
      });
      if (res?.ok) setReminderId(res.reminder.id);
    } finally {
      setReminderBusy(false);
    }
  }, [programme.id, programme.channelTvgId, programme.title, programme.startTime, programme.endTime, channel.name, reminderBusy]);

  const handleRemoveReminder = useCallback(async () => {
    if (!window.api?.reminders || !reminderId || reminderBusy) return;
    setReminderBusy(true);
    try {
      const res = await window.api.reminders.remove(reminderId);
      if (res?.ok) setReminderId(null);
    } finally {
      setReminderBusy(false);
    }
  }, [reminderId, reminderBusy]);

  // Check catch-up availability for past programmes
  useEffect(() => {
    if (!isPast || !window.api?.catchup) return;

    setCatchupStatus({ loading: true, available: false, archiveHours: 0 });

    const progDuration = programme.endTime - programme.startTime;
    window.api.catchup
      .getUrl(programme.channelTvgId, programme.startTime, progDuration)
      .then(
        (result: { ok: boolean; available?: boolean; archiveHours?: number; streamUrl?: string; error?: string }) => {
          if (result.ok) {
            setCatchupStatus({
              loading: false,
              available: result.available ?? false,
              archiveHours: result.archiveHours ?? 0,
              streamUrl: result.streamUrl,
            });
          } else {
            setCatchupStatus({
              loading: false,
              available: false,
              archiveHours: 0,
              error: result.error,
            });
          }
        },
      )
      .catch(() => {
        setCatchupStatus({ loading: false, available: false, archiveHours: 0 });
      });
  }, [isPast, programme.channelTvgId, programme.startTime, programme.endTime]);

  // Use channel.streamUrl directly — avoids fetching all channels just for one URL
  const handlePlayLive = useCallback(async () => {
    // Fast path: use the streamUrl already embedded in the guide data
    if (channel.streamUrl) {
      onPlay(channel.streamUrl, `${channel.name} - ${programme.title}`);
      return;
    }
    // Fallback: look up from content list (only if streamUrl was missing)
    if (!window.api) return;
    try {
      const allChannels = await window.api.content.getLive();
      const match = (
        allChannels as Array<{ tvgId?: string; streamUrl: string; title: string; id: string }>
      ).find((c) => c.tvgId === programme.channelTvgId);
      if (match) {
        onPlay(match.streamUrl, `${channel.name} - ${programme.title}`);
      }
    } catch {
      // unable to resolve stream URL
    }
  }, [channel.streamUrl, channel.name, programme.channelTvgId, programme.title, onPlay]);

  const handlePlayCatchup = useCallback(() => {
    if (catchupStatus.streamUrl) {
      onPlay(catchupStatus.streamUrl, `[Catch-up] ${channel.name} - ${programme.title}`);
    }
  }, [catchupStatus.streamUrl, channel.name, programme.title, onPlay]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
      onClick={onClose}
    >
      <div
        className="w-full max-w-md rounded-xl border border-accent/5 bg-surface-900/30 p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-start justify-between">
          <div>
            <p className="mb-1 text-xs font-medium text-surface-500">{channel.name}</p>
            <h3 className="text-lg font-semibold text-surface-100">{programme.title}</h3>
            <p className="text-sm text-surface-400">
              {formatTime(programme.startTime)} &ndash; {formatTime(programme.endTime)} ({duration}{' '}
              min)
            </p>
            {programme.category && (
              <span className="mt-1 inline-block rounded bg-surface-800 px-2 py-0.5 text-xs text-surface-400">
                {programme.category}
              </span>
            )}
          </div>
          <button onClick={onClose} className="text-surface-400 hover:text-surface-200">
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {programme.description && (
          <p className="mb-4 text-sm text-surface-300">{programme.description}</p>
        )}

        {/* Status badges + action buttons */}
        <div className="space-y-3">
          {isNow && (
            <>
              <div className="flex items-center gap-2">
                <span className="h-2 w-2 rounded-full bg-green-500 animate-pulse" />
                <span className="text-sm font-medium text-green-400">Currently airing</span>
              </div>
              <button
                onClick={handlePlayLive}
                className="flex w-full items-center justify-center gap-2 rounded-lg bg-accent shadow-glow-sm px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-accent-hover hover:shadow-glow"
              >
                <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 24 24">
                  <path d="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.348a1.125 1.125 0 010 1.971l-11.54 6.347a1.125 1.125 0 01-1.667-.985V5.653z" />
                </svg>
                Watch Live
              </button>
            </>
          )}

          {isPast && (
            <>
              <div className="flex items-center gap-2">
                <span className="text-sm text-surface-500">
                  Ended {formatTimeSince(nowSecs - programme.endTime)} ago
                </span>
              </div>

              {catchupStatus.loading ? (
                <div className="flex items-center gap-2 rounded-lg bg-surface-800 px-4 py-2.5 text-sm text-surface-400">
                  <svg className="h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                  </svg>
                  Checking catch-up availability...
                </div>
              ) : catchupStatus.available && catchupStatus.streamUrl ? (
                <button
                  onClick={handlePlayCatchup}
                  className="flex w-full items-center justify-center gap-2 rounded-lg bg-accent shadow-glow-sm px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-accent-hover hover:shadow-glow"
                >
                  <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M12.75 15l3-3m0 0l-3-3m3 3h-7.5M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                  </svg>
                  Watch Catch-up
                </button>
              ) : (
                <p className="rounded-lg bg-surface-800 px-4 py-2.5 text-center text-sm text-surface-500">
                  Catch-up not available for this channel
                </p>
              )}
            </>
          )}

          {isFuture && (
            <>
              <p className="rounded-lg bg-surface-800 px-4 py-2.5 text-center text-sm text-surface-500">
                Starts in {formatTimeSince(programme.startTime - nowSecs)}
              </p>
              {reminderId ? (
                <button
                  onClick={handleRemoveReminder}
                  disabled={reminderBusy}
                  className="flex w-full items-center justify-center gap-2 rounded-lg border border-accent/40 bg-accent/10 px-4 py-2.5 text-sm font-medium text-accent transition-colors hover:bg-accent/20 disabled:opacity-50"
                >
                  <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 24 24">
                    <path d="M12 22a2.5 2.5 0 002.5-2.5h-5A2.5 2.5 0 0012 22zm7-6v-5a7 7 0 10-14 0v5l-2 2v1h18v-1l-2-2z" />
                  </svg>
                  Reminder set — click to cancel
                </button>
              ) : (
                <button
                  onClick={handleSetReminder}
                  disabled={reminderBusy}
                  className="flex w-full items-center justify-center gap-2 rounded-lg bg-accent shadow-glow-sm px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-accent-hover hover:shadow-glow disabled:opacity-50"
                >
                  <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
                  </svg>
                  Remind me when it starts
                </button>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function formatTimeSince(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  return m > 0 ? `${h}h ${m}m` : `${h}h`;
}

// ---------------------------------------------------------------------------
// Empty state / Skeleton
// ---------------------------------------------------------------------------

function EmptyEpgState({
  onRefresh,
  isRefreshing,
}: {
  onRefresh: () => void;
  isRefreshing: boolean;
}) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center">
      <svg
        className="mb-4 h-16 w-16 text-surface-600"
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
        strokeWidth={1}
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M6 2h12a2 2 0 012 2v16a2 2 0 01-2 2H6a2 2 0 01-2-2V4a2 2 0 012-2zM8 6h8M8 10h8M8 14h4"
        />
      </svg>
      <h3 className="mb-2 text-lg font-semibold text-surface-300">No EPG Data</h3>
      <p className="mb-4 max-w-sm text-center text-sm text-surface-500">
        Add an EPG URL to your source in Settings, or set a global EPG URL, then refresh.
      </p>
      <button
        onClick={onRefresh}
        disabled={isRefreshing}
        className="rounded-lg bg-accent shadow-glow-sm px-4 py-2 text-sm font-medium text-white hover:bg-accent-hover hover:shadow-glow disabled:opacity-50"
      >
        {isRefreshing ? 'Refreshing...' : 'Refresh EPG'}
      </button>
    </div>
  );
}

function GridSkeleton() {
  return (
    <div className="flex-1 space-y-1 rounded-xl border border-accent/5 p-4">
      {Array.from({ length: 8 }).map((_, i) => (
        <div key={i} className="flex gap-2">
          <div className="h-12 w-48 animate-pulse rounded bg-surface-800" />
          <div className="h-12 flex-1 animate-pulse rounded bg-surface-800/50" />
        </div>
      ))}
    </div>
  );
}
