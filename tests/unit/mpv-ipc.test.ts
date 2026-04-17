import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { EventEmitter } from 'events';

vi.mock('electron-log/main', () => ({
  default: { info: vi.fn(), error: vi.fn(), warn: vi.fn() },
}));

/**
 * FakeSocket implements just enough of net.Socket for MpvIpc:
 * - `write(msg, cb)` — record the outgoing JSON line and invoke the callback
 * - `destroy()` — mark destroyed, emit nothing (real sockets do emit 'close',
 *    but MpvIpc.destroy() removes all listeners before calling destroy so we
 *    match that flow here)
 * - EventEmitter provides on/once/off/removeListener/removeAllListeners/emit
 */
class FakeSocket extends EventEmitter {
  written: string[] = [];
  destroyed = false;

  write(msg: string | Buffer, cb?: (err?: Error | null) => void): boolean {
    this.written.push(typeof msg === 'string' ? msg : msg.toString('utf8'));
    if (cb) cb(null);
    return true;
  }

  destroy(): void {
    this.destroyed = true;
  }
}

let currentSocket: FakeSocket | null = null;

vi.mock('net', () => ({
  default: {
    connect: (..._args: unknown[]) => {
      currentSocket = new FakeSocket();
      return currentSocket;
    },
  },
  connect: (..._args: unknown[]) => {
    currentSocket = new FakeSocket();
    return currentSocket;
  },
}));

// Import AFTER mocks are registered
import { MpvIpc } from '../../src/main/player/mpv-ipc';

/** Emit a connect event asynchronously — mirrors how a real net.Socket fires it. */
function deliverConnect(socket: FakeSocket) {
  queueMicrotask(() => socket.emit('connect'));
}

/** Send a JSON line from "mpv" back to the client. */
function deliverLine(socket: FakeSocket, obj: unknown) {
  socket.emit('data', Buffer.from(JSON.stringify(obj) + '\n', 'utf8'));
}

describe('MpvIpc', () => {
  let ipc: MpvIpc;

  beforeEach(() => {
    currentSocket = null;
    ipc = new MpvIpc('test-pipe');
  });

  afterEach(() => {
    ipc.destroy();
  });

  describe('connect', () => {
    it('resolves on successful connect', async () => {
      const connectPromise = ipc.connect();
      expect(currentSocket).not.toBeNull();
      deliverConnect(currentSocket!);
      await expect(connectPromise).resolves.toBeUndefined();
      expect(ipc.isConnected()).toBe(true);
    });

    it('rejects and cleans up on connect error', async () => {
      const connectPromise = ipc.connect();
      const err = new Error('ENOENT');
      queueMicrotask(() => currentSocket!.emit('error', err));
      await expect(connectPromise).rejects.toThrow('ENOENT');
      expect(ipc.isConnected()).toBe(false);
    });

    it('discards any prior socket before retrying', async () => {
      // First attempt fails
      const firstAttempt = ipc.connect();
      const firstSock = currentSocket!;
      queueMicrotask(() => firstSock.emit('error', new Error('boom')));
      await expect(firstAttempt).rejects.toThrow();
      expect(firstSock.destroyed).toBe(true);

      // Second attempt uses a fresh socket
      const secondAttempt = ipc.connect();
      expect(currentSocket).not.toBe(firstSock);
      deliverConnect(currentSocket!);
      await expect(secondAttempt).resolves.toBeUndefined();
    });
  });

  describe('command', () => {
    beforeEach(async () => {
      const p = ipc.connect();
      deliverConnect(currentSocket!);
      await p;
    });

    it('rejects immediately when not connected', async () => {
      ipc.destroy();
      await expect(ipc.command(['get_version'])).rejects.toThrow('not connected');
    });

    it('serializes the command as a JSON line with a request_id', async () => {
      const promise = ipc.command(['get_property', 'volume']);
      // Command should have been written immediately
      expect(currentSocket!.written).toHaveLength(1);
      const line = currentSocket!.written[0];
      expect(line.endsWith('\n')).toBe(true);
      const parsed = JSON.parse(line) as { command: unknown[]; request_id: number };
      expect(parsed.command).toEqual(['get_property', 'volume']);
      expect(parsed.request_id).toBeGreaterThan(0);

      // Deliver a success response — promise should resolve with data
      deliverLine(currentSocket!, { request_id: parsed.request_id, error: 'success', data: 85 });
      await expect(promise).resolves.toBe(85);
    });

    it('rejects when mpv returns a non-success error', async () => {
      const promise = ipc.command(['set_property', 'pause', 'nope']);
      const line = currentSocket!.written[0];
      const id = (JSON.parse(line) as { request_id: number }).request_id;
      deliverLine(currentSocket!, { request_id: id, error: 'property unavailable' });
      await expect(promise).rejects.toThrow('property unavailable');
    });

    it('pairs multiple pending commands to their responses out of order', async () => {
      const p1 = ipc.command(['get_property', 'volume']);
      const p2 = ipc.command(['get_property', 'duration']);
      expect(currentSocket!.written).toHaveLength(2);
      const id1 = (JSON.parse(currentSocket!.written[0]) as { request_id: number }).request_id;
      const id2 = (JSON.parse(currentSocket!.written[1]) as { request_id: number }).request_id;

      // Respond in reverse order
      deliverLine(currentSocket!, { request_id: id2, error: 'success', data: 3600 });
      deliverLine(currentSocket!, { request_id: id1, error: 'success', data: 50 });

      await expect(p2).resolves.toBe(3600);
      await expect(p1).resolves.toBe(50);
    });

    it('times out when mpv never responds', async () => {
      vi.useFakeTimers();
      try {
        const promise = ipc.command(['get_property', 'stuck'], 100);
        // Swallow the eventual rejection so Node doesn't flag it as unhandled
        // while we advance timers.
        const expectation = expect(promise).rejects.toThrow(/timed out after 100ms/);
        await vi.advanceTimersByTimeAsync(150);
        await expectation;
      } finally {
        vi.useRealTimers();
      }
    });

    it('late response after timeout is silently dropped', async () => {
      vi.useFakeTimers();
      try {
        const promise = ipc.command(['get_property', 'stuck'], 100);
        const id = (JSON.parse(currentSocket!.written[0]) as { request_id: number }).request_id;
        const expectation = expect(promise).rejects.toThrow(/timed out/);
        await vi.advanceTimersByTimeAsync(150);
        await expectation;
        // Late response — must not throw or crash
        expect(() =>
          deliverLine(currentSocket!, { request_id: id, error: 'success', data: 1 }),
        ).not.toThrow();
      } finally {
        vi.useRealTimers();
      }
    });
  });

  describe('data parsing', () => {
    beforeEach(async () => {
      const p = ipc.connect();
      deliverConnect(currentSocket!);
      await p;
    });

    it('emits property-change events for mpv property observations', async () => {
      const received: Array<{ name: string; data: unknown }> = [];
      ipc.on('property-change', (change) => received.push(change as { name: string; data: unknown }));

      deliverLine(currentSocket!, { event: 'property-change', name: 'time-pos', data: 42.5 });
      deliverLine(currentSocket!, { event: 'property-change', name: 'pause', data: true });

      expect(received).toEqual([
        { name: 'time-pos', data: 42.5 },
        { name: 'pause', data: true },
      ]);
    });

    it('emits mpv-event for generic mpv events', async () => {
      const events: unknown[] = [];
      ipc.on('mpv-event', (msg) => events.push(msg));

      deliverLine(currentSocket!, { event: 'file-loaded' });
      deliverLine(currentSocket!, { event: 'end-file', reason: 'error' });

      expect(events).toEqual([{ event: 'file-loaded' }, { event: 'end-file', reason: 'error' }]);
    });

    it('reassembles JSON split across multiple chunks', async () => {
      const received: unknown[] = [];
      ipc.on('mpv-event', (msg) => received.push(msg));

      const payload = JSON.stringify({ event: 'file-loaded' }) + '\n';
      const half = Math.floor(payload.length / 2);
      currentSocket!.emit('data', Buffer.from(payload.slice(0, half), 'utf8'));
      expect(received).toHaveLength(0); // not enough yet
      currentSocket!.emit('data', Buffer.from(payload.slice(half), 'utf8'));

      expect(received).toEqual([{ event: 'file-loaded' }]);
    });

    it('handles two JSON messages arriving in a single chunk', async () => {
      const received: unknown[] = [];
      ipc.on('mpv-event', (msg) => received.push(msg));

      const combined =
        JSON.stringify({ event: 'file-loaded' }) + '\n' +
        JSON.stringify({ event: 'end-file', reason: 'eof' }) + '\n';
      currentSocket!.emit('data', Buffer.from(combined, 'utf8'));

      expect(received).toEqual([
        { event: 'file-loaded' },
        { event: 'end-file', reason: 'eof' },
      ]);
    });

    it('does not crash on malformed JSON lines', async () => {
      const received: unknown[] = [];
      ipc.on('mpv-event', (msg) => received.push(msg));

      // Bad line, good line — we should parse the good one and skip the bad one
      currentSocket!.emit(
        'data',
        Buffer.from('not-json\n' + JSON.stringify({ event: 'file-loaded' }) + '\n', 'utf8'),
      );

      expect(received).toEqual([{ event: 'file-loaded' }]);
    });
  });

  describe('close and destroy', () => {
    beforeEach(async () => {
      const p = ipc.connect();
      deliverConnect(currentSocket!);
      await p;
    });

    it('rejects pending commands when the socket closes', async () => {
      const promise = ipc.command(['get_property', 'volume']);
      currentSocket!.emit('close');
      await expect(promise).rejects.toThrow(/connection closed/);
      expect(ipc.isConnected()).toBe(false);
    });

    it('emits a close event when the socket closes', () => {
      const onClose = vi.fn();
      ipc.on('close', onClose);
      currentSocket!.emit('close');
      expect(onClose).toHaveBeenCalled();
    });

    it('rejects pending commands when destroyed', async () => {
      const promise = ipc.command(['get_property', 'volume']);
      ipc.destroy();
      await expect(promise).rejects.toThrow(/destroyed/);
      expect(currentSocket!.destroyed).toBe(true);
      expect(ipc.isConnected()).toBe(false);
    });

    it('destroy clears pending-command timers (no leaked timeouts)', async () => {
      vi.useFakeTimers();
      try {
        const promise = ipc.command(['get_property', 'volume'], 100);
        const expectation = expect(promise).rejects.toThrow(/destroyed/);
        ipc.destroy();
        // If destroy didn't clear the timer, advancing would fire the
        // timeout and trigger a second rejection on the same promise,
        // which we'd see as an unhandled rejection.
        await vi.advanceTimersByTimeAsync(500);
        await expectation;
      } finally {
        vi.useRealTimers();
      }
    });
  });

  describe('observeProperty', () => {
    beforeEach(async () => {
      const p = ipc.connect();
      deliverConnect(currentSocket!);
      await p;
    });

    it('sends an observe_property command with a unique id', async () => {
      const promise = ipc.observeProperty('time-pos');
      const line = currentSocket!.written[0];
      const parsed = JSON.parse(line) as { command: unknown[]; request_id: number };
      expect(parsed.command[0]).toBe('observe_property');
      expect(parsed.command[1]).toBeTypeOf('number'); // observer id
      expect(parsed.command[2]).toBe('time-pos');

      deliverLine(currentSocket!, { request_id: parsed.request_id, error: 'success' });
      await expect(promise).resolves.toBeUndefined();
    });
  });
});
