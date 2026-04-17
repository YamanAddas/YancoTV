import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { useToastStore, __testing } from '../../src/renderer/stores/toast-store';

describe('Toast store', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    __testing.reset();
  });

  afterEach(() => {
    __testing.reset();
    vi.useRealTimers();
  });

  it('starts with no toasts', () => {
    expect(useToastStore.getState().toasts).toEqual([]);
    expect(__testing.timerCount()).toBe(0);
  });

  it('push returns an id and appends the toast', () => {
    const id = useToastStore.getState().push({ kind: 'info', message: 'hi' });
    const toasts = useToastStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0].id).toBe(id);
    expect(toasts[0].kind).toBe('info');
    expect(toasts[0].message).toBe('hi');
  });

  it('preserves the action payload', () => {
    useToastStore.getState().push({
      kind: 'success',
      message: 'Added',
      action: { label: 'View', href: '/downloads' },
    });
    expect(useToastStore.getState().toasts[0].action).toEqual({
      label: 'View',
      href: '/downloads',
    });
  });

  it('auto-dismisses after the default duration', () => {
    useToastStore.getState().push({ kind: 'info', message: 'autodismiss' });
    expect(useToastStore.getState().toasts).toHaveLength(1);
    expect(__testing.timerCount()).toBe(1);

    // Default duration is 3500ms — advance past it.
    vi.advanceTimersByTime(3600);
    expect(useToastStore.getState().toasts).toHaveLength(0);
    expect(__testing.timerCount()).toBe(0);
  });

  it('honours an explicit durationMs', () => {
    useToastStore.getState().push({ kind: 'info', message: 'short', durationMs: 1000 });
    vi.advanceTimersByTime(500);
    expect(useToastStore.getState().toasts).toHaveLength(1);
    vi.advanceTimersByTime(600);
    expect(useToastStore.getState().toasts).toHaveLength(0);
  });

  it('dismiss removes the toast and clears its timer', () => {
    const id = useToastStore.getState().push({ kind: 'info', message: 'x' });
    expect(__testing.timerCount()).toBe(1);
    useToastStore.getState().dismiss(id);
    expect(useToastStore.getState().toasts).toHaveLength(0);
    expect(__testing.timerCount()).toBe(0);
  });

  it('dismiss is a no-op for an unknown id', () => {
    useToastStore.getState().push({ kind: 'info', message: 'keep' });
    useToastStore.getState().dismiss('does-not-exist');
    expect(useToastStore.getState().toasts).toHaveLength(1);
  });

  it('clear removes every toast and every timer', () => {
    const s = useToastStore.getState();
    s.push({ kind: 'info', message: 'a' });
    s.push({ kind: 'success', message: 'b' });
    s.push({ kind: 'error', message: 'c' });
    expect(useToastStore.getState().toasts).toHaveLength(3);
    expect(__testing.timerCount()).toBe(3);
    useToastStore.getState().clear();
    expect(useToastStore.getState().toasts).toHaveLength(0);
    expect(__testing.timerCount()).toBe(0);
  });

  it('stacks multiple toasts — they co-exist until each expires', () => {
    const s = useToastStore.getState();
    s.push({ kind: 'info', message: 'first', durationMs: 1000 });
    s.push({ kind: 'info', message: 'second', durationMs: 2000 });
    expect(useToastStore.getState().toasts).toHaveLength(2);

    vi.advanceTimersByTime(1100);
    let toasts = useToastStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0].message).toBe('second');

    vi.advanceTimersByTime(1000);
    toasts = useToastStore.getState().toasts;
    expect(toasts).toHaveLength(0);
  });

  it('each push gets a unique id even when called back-to-back', () => {
    const s = useToastStore.getState();
    const ids = new Set<string>();
    for (let i = 0; i < 10; i++) {
      ids.add(s.push({ kind: 'info', message: `m${i}` }));
    }
    expect(ids.size).toBe(10);
  });
});
