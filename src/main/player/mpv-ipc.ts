import net from 'net';
import { EventEmitter } from 'events';
import log from 'electron-log/main';

/**
 * Low-level JSON IPC communication with mpv over Windows named pipes.
 *
 * mpv protocol: send `{"command": [...]}\n`, receive JSON lines back.
 * Property observations arrive as `{"event": "property-change", "name": ..., "data": ...}`.
 */
export class MpvIpc extends EventEmitter {
  private socket: net.Socket | null = null;
  private buffer = '';
  private requestId = 0;
  private pending = new Map<number, { resolve: (v: unknown) => void; reject: (e: Error) => void }>();
  private connected = false;

  constructor(private pipeName: string) {
    super();
  }

  connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      const pipePath = `\\\\.\\pipe\\${this.pipeName}`;
      this.socket = net.connect(pipePath);

      const onConnect = () => {
        this.connected = true;
        this.socket!.removeListener('error', onError);
        log.info(`mpv IPC connected: ${pipePath}`);
        resolve();
      };

      const onError = (err: Error) => {
        this.socket!.removeListener('connect', onConnect);
        reject(err);
      };

      this.socket.once('connect', onConnect);
      this.socket.once('error', onError);

      this.socket.on('data', (chunk) => this.onData(chunk));

      this.socket.on('close', () => {
        this.connected = false;
        this.rejectAllPending('mpv IPC connection closed');
        this.emit('close');
      });

      this.socket.on('error', (err) => {
        log.error('mpv IPC error:', err.message);
        this.emit('error', err);
      });
    });
  }

  isConnected(): boolean {
    return this.connected;
  }

  /**
   * Send a command to mpv and wait for the response.
   * Example: command(['loadfile', url]) or command(['set_property', 'pause', true])
   */
  command(args: unknown[]): Promise<unknown> {
    return new Promise((resolve, reject) => {
      if (!this.socket || !this.connected) {
        reject(new Error('mpv IPC not connected'));
        return;
      }

      const id = ++this.requestId;
      this.pending.set(id, { resolve, reject });

      const msg = JSON.stringify({ command: args, request_id: id }) + '\n';
      this.socket.write(msg, (err) => {
        if (err) {
          this.pending.delete(id);
          reject(err);
        }
      });
    });
  }

  /**
   * Observe a property. mpv will send property-change events for it.
   * The event is emitted as 'property-change' with { name, data }.
   */
  async observeProperty(name: string): Promise<void> {
    const id = ++this.requestId;
    await this.command(['observe_property', id, name]);
  }

  destroy(): void {
    this.rejectAllPending('mpv IPC destroyed');
    if (this.socket) {
      this.socket.destroy();
      this.socket = null;
    }
    this.connected = false;
    this.removeAllListeners();
  }

  private onData(chunk: Buffer): void {
    this.buffer += chunk.toString('utf8');

    let newlineIdx: number;
    while ((newlineIdx = this.buffer.indexOf('\n')) !== -1) {
      const line = this.buffer.slice(0, newlineIdx).trim();
      this.buffer = this.buffer.slice(newlineIdx + 1);

      if (!line) continue;

      try {
        const msg = JSON.parse(line) as Record<string, unknown>;
        this.handleMessage(msg);
      } catch {
        log.warn('mpv IPC: failed to parse line:', line);
      }
    }
  }

  private handleMessage(msg: Record<string, unknown>): void {
    // Response to a command we sent
    if ('request_id' in msg && typeof msg.request_id === 'number') {
      const pending = this.pending.get(msg.request_id);
      if (pending) {
        this.pending.delete(msg.request_id);
        if (msg.error === 'success') {
          pending.resolve(msg.data);
        } else {
          pending.reject(new Error(String(msg.error ?? 'mpv command failed')));
        }
      }
      return;
    }

    // Asynchronous event from mpv
    if ('event' in msg && typeof msg.event === 'string') {
      this.emit('mpv-event', msg);

      if (msg.event === 'property-change') {
        this.emit('property-change', {
          name: msg.name as string,
          data: msg.data,
        });
      }
    }
  }

  private rejectAllPending(reason: string): void {
    for (const [id, p] of this.pending) {
      p.reject(new Error(reason));
      this.pending.delete(id);
    }
  }
}
