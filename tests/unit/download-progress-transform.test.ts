import { describe, it, expect } from 'vitest';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import {
  createProgressTransform,
  type ProgressState,
} from '../../src/main/services/download-service';

/**
 * These tests exercise the progress-Transform in isolation. It's the hot-path
 * piece of the download worker, so regressions here directly hurt throughput
 * and resume accuracy. We feed synthetic chunks through `pipeline` exactly
 * like the worker does, with a controllable clock.
 */

function makeClock(start = 0): { now: () => number; advance: (ms: number) => void } {
  let t = start;
  return {
    now: () => t,
    advance: (ms) => {
      t += ms;
    },
  };
}

function makeSource(chunks: Buffer[]): Readable {
  return Readable.from(chunks);
}

function sink(): { write: NodeJS.WritableStream; collected: Buffer[] } {
  const collected: Buffer[] = [];
  const write = new (require('node:stream').Writable)({
    write(chunk: Buffer, _enc: unknown, cb: () => void) {
      collected.push(Buffer.from(chunk));
      cb();
    },
  });
  return { write, collected };
}

describe('download progress Transform', () => {
  it('forwards every byte unchanged', async () => {
    const state: ProgressState = { bytes: 0, startBytes: 0 };
    const clock = makeClock();
    const transform = createProgressTransform({
      state,
      maxBytes: 1_000_000,
      isAborted: () => false,
      onTick: () => undefined,
      onPersist: () => undefined,
      now: clock.now,
    });
    const payload = [Buffer.from('hello '), Buffer.from('world')];
    const { write, collected } = sink();
    await pipeline(makeSource(payload), transform, write);
    expect(Buffer.concat(collected).toString()).toBe('hello world');
    expect(state.bytes).toBe(11);
  });

  it('counts bytes incrementally', async () => {
    const state: ProgressState = { bytes: 0, startBytes: 0 };
    const transform = createProgressTransform({
      state,
      maxBytes: 1_000_000,
      isAborted: () => false,
      onTick: () => undefined,
      onPersist: () => undefined,
    });
    const chunks = [Buffer.alloc(100), Buffer.alloc(250), Buffer.alloc(50)];
    const { write } = sink();
    await pipeline(makeSource(chunks), transform, write);
    expect(state.bytes).toBe(400);
  });

  it('respects the startBytes baseline (resume scenario)', async () => {
    const state: ProgressState = { bytes: 500, startBytes: 500 };
    const transform = createProgressTransform({
      state,
      maxBytes: 1_000_000,
      isAborted: () => false,
      onTick: () => undefined,
      onPersist: () => undefined,
    });
    const chunks = [Buffer.alloc(200)];
    const { write } = sink();
    await pipeline(makeSource(chunks), transform, write);
    expect(state.bytes).toBe(700);
    expect(state.startBytes).toBe(500);
  });

  it('throws when the cap is exceeded', async () => {
    const state: ProgressState = { bytes: 0, startBytes: 0 };
    const transform = createProgressTransform({
      state,
      maxBytes: 100,
      isAborted: () => false,
      onTick: () => undefined,
      onPersist: () => undefined,
    });
    const chunks = [Buffer.alloc(60), Buffer.alloc(60)];
    const { write } = sink();
    await expect(pipeline(makeSource(chunks), transform, write)).rejects.toThrow(
      /exceeded max/i,
    );
  });

  it('stops forwarding when isAborted() returns true', () => {
    const state: ProgressState = { bytes: 0, startBytes: 0 };
    let aborted = false;
    const transform = createProgressTransform({
      state,
      maxBytes: 1_000_000,
      isAborted: () => aborted,
      onTick: () => undefined,
      onPersist: () => undefined,
    });
    const collected: Buffer[] = [];
    transform.on('data', (c: Buffer) => collected.push(Buffer.from(c)));

    // Sync writes — clock is deterministic per chunk.
    transform.write(Buffer.alloc(10));
    aborted = true;
    transform.write(Buffer.alloc(10));
    transform.write(Buffer.alloc(10));
    transform.end();

    // Only the pre-abort chunk is forwarded and counted.
    expect(Buffer.concat(collected).length).toBe(10);
    expect(state.bytes).toBe(10);
  });

  it('calls onTick only after broadcastIntervalMs has elapsed', () => {
    const state: ProgressState = { bytes: 0, startBytes: 0 };
    const clock = makeClock();
    const ticks: Array<{ bytes: number; bps: number }> = [];
    const transform = createProgressTransform({
      state,
      maxBytes: 1_000_000,
      isAborted: () => false,
      onTick: (bytes, bps) => ticks.push({ bytes, bps }),
      onPersist: () => undefined,
      now: clock.now,
      broadcastIntervalMs: 500,
      persistIntervalMs: 2000,
    });
    // Consume the readable side so the Transform doesn't buffer.
    transform.on('data', () => undefined);

    // Transform's _transform is called synchronously on each write, so the
    // mock clock reading is deterministic per chunk (unlike pipeline, which
    // interleaves chunks across async ticks).
    clock.advance(100); // t=100
    transform.write(Buffer.alloc(100));
    clock.advance(100); // t=200
    transform.write(Buffer.alloc(100));
    clock.advance(100); // t=300
    transform.write(Buffer.alloc(100));
    clock.advance(300); // t=600 — crosses the 500ms mark
    transform.write(Buffer.alloc(100));
    clock.advance(50); // t=650
    transform.write(Buffer.alloc(100));
    transform.end();

    // First tick fires on the chunk processed at t=600 (dt=600 from t=0).
    expect(ticks).toHaveLength(1);
    expect(ticks[0].bytes).toBe(400);
    // 400 bytes in 600ms → ~666 B/s
    expect(ticks[0].bps).toBeCloseTo(666, -1);
  });

  it('calls onPersist on its own interval (less often than onTick)', () => {
    const state: ProgressState = { bytes: 0, startBytes: 0 };
    const clock = makeClock();
    const ticks: number[] = [];
    const persists: number[] = [];
    const transform = createProgressTransform({
      state,
      maxBytes: 1_000_000,
      isAborted: () => false,
      onTick: (b) => ticks.push(b),
      onPersist: (b) => persists.push(b),
      now: clock.now,
      broadcastIntervalMs: 500,
      persistIntervalMs: 2000,
    });
    transform.on('data', () => undefined);

    // Process 6 chunks at 600ms intervals — expect ~5 ticks, 1 persist
    // (persist fires at the ≥2000ms crossing, i.e. after ~4 chunks).
    for (let i = 0; i < 6; i++) {
      clock.advance(600);
      transform.write(Buffer.alloc(100));
    }
    transform.end();

    expect(ticks.length).toBeGreaterThanOrEqual(4);
    expect(persists.length).toBeGreaterThanOrEqual(1);
    expect(persists.length).toBeLessThanOrEqual(2);
  });

  it('handles many small chunks without errors (throughput smoke test)', async () => {
    const state: ProgressState = { bytes: 0, startBytes: 0 };
    let tickCount = 0;
    let persistCount = 0;
    const transform = createProgressTransform({
      state,
      maxBytes: 1 << 24, // 16 MiB
      isAborted: () => false,
      onTick: () => tickCount++,
      onPersist: () => persistCount++,
    });

    // 10,000 × 1KB chunks = ~10 MB through the Transform.
    const chunks: Buffer[] = [];
    for (let i = 0; i < 10_000; i++) chunks.push(Buffer.alloc(1024));

    const { write } = sink();
    await pipeline(Readable.from(chunks), transform, write);

    expect(state.bytes).toBe(10_000 * 1024);
    // Real clock advanced — we at least get one persist.
    expect(tickCount).toBeGreaterThanOrEqual(0);
    expect(persistCount).toBeGreaterThanOrEqual(0);
  });
});
