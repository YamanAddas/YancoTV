import { describe, it, expect, beforeEach, vi } from 'vitest';
import { createTestDb, insertTestSource, insertTestContent } from './helpers/test-db';
import type Database from 'better-sqlite3';

// Mock dependencies before importing the service under test.
let currentDb: Database.Database;

vi.mock('../../src/main/services/db', () => ({
  getDb: () => currentDb,
}));

vi.mock('electron', () => ({
  app: { getVersion: () => '0.1.0-test' },
}));

vi.mock('electron-log/main', () => ({
  default: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() },
}));

// Credential decryption — identity fallback so tests don't need safeStorage
vi.mock('../../src/main/services/credential-store', () => ({
  decryptCredential: (buf: Buffer) => buf.toString('utf-8'),
  encryptCredential: (s: string) => Buffer.from(s, 'utf-8'),
}));

// Import after mocks are set up.
import {
  buildBackup,
  importBackup,
  BACKUP_VERSION,
  defaultBackupFilename,
  type BackupFile,
} from '../../src/main/services/backup-service';

function emptyBackup(overrides: Partial<BackupFile> = {}): BackupFile {
  return {
    version: BACKUP_VERSION,
    appVersion: '0.1.0-test',
    exportedAt: Date.now(),
    sources: [],
    favorites: [],
    history: [],
    settings: {},
    parental: { lockedChannelIds: [], hiddenChannelIds: [], overrides: {} },
    groupPreferences: [],
    ...overrides,
  };
}

describe('backup-service', () => {
  beforeEach(() => {
    currentDb = createTestDb();
  });

  it('produces a backup with the current version and app version', () => {
    const { backup: b } = buildBackup();
    expect(b.version).toBe(BACKUP_VERSION);
    expect(b.appVersion).toBe('0.1.0-test');
    expect(typeof b.exportedAt).toBe('number');
  });

  it('exports sources with decrypted credentials for xtream types', () => {
    const now = Date.now();
    currentDb
      .prepare(
        `INSERT INTO sources (id, name, type, url, username_encrypted, password_encrypted, is_active, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)`,
      )
      .run(
        'src-x',
        'Xtream Test',
        'xtream',
        'http://xtream.example.com',
        Buffer.from('myuser', 'utf-8'),
        Buffer.from('mypass', 'utf-8'),
        now,
        now,
      );

    const { backup: b } = buildBackup();
    expect(b.sources).toHaveLength(1);
    expect(b.sources[0].username).toBe('myuser');
    expect(b.sources[0].password).toBe('mypass');
    // Decrypted strings, not the raw Buffer fields
    expect((b.sources[0] as unknown as { username_encrypted?: unknown }).username_encrypted).toBeUndefined();
  });

  it('exports favorites with re-link refs (sourceId, streamUrl, title)', () => {
    insertTestSource(currentDb, { id: 'src-1' });
    insertTestContent(currentDb, { id: 'c-1', sourceId: 'src-1', title: 'Favourite Channel', streamUrl: 'http://s/fav' });
    currentDb
      .prepare('INSERT INTO favorites (id, content_id, added_at) VALUES (?, ?, ?)')
      .run('fav-1', 'c-1', 1700000000000);

    const { backup: b } = buildBackup();
    expect(b.favorites).toHaveLength(1);
    expect(b.favorites[0].contentId).toBe('c-1');
    expect(b.favorites[0].addedAt).toBe(1700000000000);
    expect(b.favorites[0].ref).toMatchObject({
      sourceId: 'src-1',
      streamUrl: 'http://s/fav',
      title: 'Favourite Channel',
    });
  });

  it('exports watch history rows, newest first, with re-link refs', () => {
    insertTestSource(currentDb, { id: 'src-1' });
    insertTestContent(currentDb, { id: 'c-1', sourceId: 'src-1', title: 'A', streamUrl: 'http://s/a' });
    insertTestContent(currentDb, { id: 'c-2', sourceId: 'src-1', title: 'B', streamUrl: 'http://s/b' });
    currentDb
      .prepare(
        'INSERT INTO watch_history (id, content_id, position_seconds, duration_seconds, watched_at) VALUES (?, ?, ?, ?, ?)',
      )
      .run('h-1', 'c-1', 120, 3600, 100);
    currentDb
      .prepare(
        'INSERT INTO watch_history (id, content_id, position_seconds, duration_seconds, watched_at) VALUES (?, ?, ?, ?, ?)',
      )
      .run('h-2', 'c-2', 60, 1800, 200);

    const { backup: b } = buildBackup();
    expect(b.history).toHaveLength(2);
    expect(b.history[0].id).toBe('h-2'); // newest first
    expect(b.history[0].ref).toMatchObject({ sourceId: 'src-1', streamUrl: 'http://s/b', title: 'B' });
    expect(b.history[1].positionSeconds).toBe(120);
  });

  it('exports settings as a flat key-value map', () => {
    currentDb.prepare("INSERT INTO settings (key, value) VALUES ('ui_theme', 'dark')").run();
    currentDb.prepare("INSERT INTO settings (key, value) VALUES ('playback_default_volume', '80')").run();

    const { backup: b } = buildBackup();
    expect(b.settings.ui_theme).toBe('dark');
    expect(b.settings.playback_default_volume).toBe('80');
  });

  it('exports parental locked/hidden lists and overrides', () => {
    currentDb.prepare("INSERT INTO locked_channels (content_id, locked_at) VALUES ('c-1', 1)").run();
    currentDb.prepare("INSERT INTO locked_channels (content_id, locked_at) VALUES ('c-2', 2)").run();
    currentDb.prepare("INSERT INTO hidden_channels (content_id, hidden_at) VALUES ('c-3', 3)").run();
    currentDb
      .prepare(
        "INSERT INTO channel_overrides (content_id, custom_name, custom_number, updated_at) VALUES ('c-1', 'Renamed', 7, 1)",
      )
      .run();

    const { backup: b } = buildBackup();
    expect(b.parental.lockedChannelIds.sort()).toEqual(['c-1', 'c-2']);
    expect(b.parental.hiddenChannelIds).toEqual(['c-3']);
    expect(b.parental.overrides['c-1']).toMatchObject({ customName: 'Renamed', customNumber: 7 });
  });

  it('exports group preferences across all content types', () => {
    currentDb
      .prepare(
        `INSERT INTO group_preferences (id, content_type, group_key, sort_order, is_hidden, is_pinned, custom_name, created_at)
         VALUES ('g-1', 'live', 'news', 5, 0, 1, 'News (Pinned)', 1), ('g-2', 'movie', 'action', 0, 1, 0, NULL, 2)`,
      )
      .run();

    const { backup: b } = buildBackup();
    expect(b.groupPreferences).toHaveLength(2);
    const byKey = Object.fromEntries(b.groupPreferences.map((g) => [g.groupKey, g]));
    expect(byKey.news).toMatchObject({ contentType: 'live', isPinned: true, customName: 'News (Pinned)' });
    expect(byKey.action).toMatchObject({ contentType: 'movie', isHidden: true, customName: null });
  });

  it('produces a dated filename like yancotv-backup-YYYY-MM-DD.json', () => {
    const name = defaultBackupFilename();
    expect(name).toMatch(/^yancotv-backup-\d{4}-\d{2}-\d{2}\.json$/);
  });

  describe('importBackup', () => {
    it('rejects a file with the wrong version', () => {
      const bad = emptyBackup({ version: 999 });
      const res = importBackup(bad, 'merge');
      expect(res.ok).toBe(false);
      if (!res.ok) expect(res.error).toMatch(/invalid|unsupported/i);
    });

    it('restores settings and sources in replace mode (wipes existing first)', () => {
      // Pre-existing data that should be cleared.
      currentDb.prepare("INSERT INTO settings (key, value) VALUES ('ui_theme', 'light')").run();
      insertTestSource(currentDb, { id: 'old-src', name: 'Old Source' });

      const now = Date.now();
      const backup = emptyBackup({
        settings: { ui_theme: 'oled', playback_default_volume: '50' },
        sources: [
          {
            id: 'src-1',
            name: 'Restored',
            type: 'm3u_url',
            url: 'http://restored.example.com',
            isActive: true,
            priority: 0,
            channelCount: 0,
            autoSyncInterval: 0,
            createdAt: now,
            updatedAt: now,
          },
        ],
      });

      const res = importBackup(backup, 'replace');
      expect(res.ok).toBe(true);
      if (!res.ok) return;
      expect(res.stats.sourcesImported).toBe(1);
      expect(res.stats.settingsImported).toBe(2);

      // Old source wiped
      const sources = currentDb.prepare('SELECT id, name FROM sources').all();
      expect(sources).toHaveLength(1);
      expect((sources[0] as { name: string }).name).toBe('Restored');

      // Settings replaced
      const theme = currentDb.prepare("SELECT value FROM settings WHERE key = 'ui_theme'").get() as {
        value: string;
      };
      expect(theme.value).toBe('oled');
    });

    it('re-encrypts xtream credentials during import', () => {
      const now = Date.now();
      const backup = emptyBackup({
        sources: [
          {
            id: 'x-1',
            name: 'X',
            type: 'xtream',
            url: 'http://x.example.com',
            username: 'user1',
            password: 'pass1',
            isActive: true,
            priority: 0,
            channelCount: 0,
            autoSyncInterval: 0,
            createdAt: now,
            updatedAt: now,
          },
        ],
      });

      const res = importBackup(backup, 'replace');
      expect(res.ok).toBe(true);

      const row = currentDb
        .prepare('SELECT username_encrypted, password_encrypted FROM sources WHERE id = ?')
        .get('x-1') as { username_encrypted: Buffer; password_encrypted: Buffer };
      // credential-store mock is identity: UTF-8 bytes
      expect(row.username_encrypted.toString('utf-8')).toBe('user1');
      expect(row.password_encrypted.toString('utf-8')).toBe('pass1');
    });

    it('re-links favorites by (sourceId, streamUrl), skipping rows with no matching content', () => {
      insertTestSource(currentDb, { id: 'src-1' });
      insertTestContent(currentDb, {
        id: 'c-resynced',
        sourceId: 'src-1',
        title: 'Channel A',
        streamUrl: 'http://s/a',
      });

      const backup = emptyBackup({
        sources: [],
        favorites: [
          {
            contentId: 'old-content-id-that-no-longer-exists',
            addedAt: 123,
            ref: { sourceId: 'src-1', streamUrl: 'http://s/a', title: 'Channel A' },
          },
          {
            contentId: 'orphan',
            addedAt: 456,
            ref: { sourceId: 'src-1', streamUrl: 'http://s/gone', title: 'Gone' },
          },
        ],
      });

      const res = importBackup(backup, 'merge');
      expect(res.ok).toBe(true);
      if (!res.ok) return;
      expect(res.stats.favoritesImported).toBe(1);
      expect(res.stats.favoritesSkipped).toBe(1);
      expect(res.warnings).toHaveLength(1);
      expect(res.warnings[0]).toMatch(/skipped/i);

      const favs = currentDb
        .prepare('SELECT content_id FROM favorites')
        .all() as { content_id: string }[];
      expect(favs).toHaveLength(1);
      expect(favs[0].content_id).toBe('c-resynced');
    });

    it('re-links watch history by ref, skipping unmatched', () => {
      insertTestSource(currentDb, { id: 'src-1' });
      insertTestContent(currentDb, {
        id: 'c-1',
        sourceId: 'src-1',
        title: 'A',
        streamUrl: 'http://s/a',
      });

      const backup = emptyBackup({
        history: [
          {
            id: 'h-1',
            contentId: 'old',
            episodeId: null,
            positionSeconds: 120,
            durationSeconds: 3600,
            watchedAt: 5000,
            ref: { sourceId: 'src-1', streamUrl: 'http://s/a', title: 'A' },
          },
          {
            id: 'h-2',
            contentId: 'old',
            episodeId: null,
            positionSeconds: 10,
            durationSeconds: null,
            watchedAt: 6000,
            ref: { sourceId: 'src-1', streamUrl: 'http://s/missing', title: 'Missing' },
          },
        ],
      });

      // Merge mode — leaves existing source/content intact so history can re-link
      const res = importBackup(backup, 'merge');
      expect(res.ok).toBe(true);
      if (!res.ok) return;
      expect(res.stats.historyImported).toBe(1);
      expect(res.stats.historySkipped).toBe(1);

      const rows = currentDb
        .prepare('SELECT content_id, position_seconds FROM watch_history')
        .all() as { content_id: string; position_seconds: number }[];
      expect(rows).toHaveLength(1);
      expect(rows[0].content_id).toBe('c-1');
      expect(rows[0].position_seconds).toBe(120);
    });

    it('merges group preferences by (contentType, groupKey)', () => {
      currentDb
        .prepare(
          `INSERT INTO group_preferences (id, content_type, group_key, sort_order, is_hidden, is_pinned, custom_name, created_at)
           VALUES ('g-old', 'live', 'news', 0, 0, 0, 'Old Name', 1)`,
        )
        .run();

      const backup = emptyBackup({
        groupPreferences: [
          {
            id: 'g-new',
            contentType: 'live',
            groupKey: 'news',
            sortOrder: 5,
            isHidden: false,
            isPinned: true,
            customName: 'New Name',
            createdAt: 2,
          },
        ],
      });

      const res = importBackup(backup, 'merge');
      expect(res.ok).toBe(true);

      const rows = currentDb
        .prepare('SELECT custom_name, is_pinned, sort_order FROM group_preferences')
        .all() as { custom_name: string; is_pinned: number; sort_order: number }[];
      expect(rows).toHaveLength(1);
      expect(rows[0].custom_name).toBe('New Name');
      expect(rows[0].is_pinned).toBe(1);
      expect(rows[0].sort_order).toBe(5);
    });

    it('restores parental locked/hidden lists and channel overrides', () => {
      const backup = emptyBackup({
        parental: {
          lockedChannelIds: ['c-1', 'c-2'],
          hiddenChannelIds: ['c-3'],
          overrides: {
            'c-1': { contentId: 'c-1', customName: 'Renamed', customNumber: 7 },
          },
        },
      });

      const res = importBackup(backup, 'replace');
      expect(res.ok).toBe(true);

      const locked = currentDb
        .prepare('SELECT content_id FROM locked_channels')
        .all() as { content_id: string }[];
      expect(locked.map((r) => r.content_id).sort()).toEqual(['c-1', 'c-2']);

      const hidden = currentDb
        .prepare('SELECT content_id FROM hidden_channels')
        .all() as { content_id: string }[];
      expect(hidden.map((r) => r.content_id)).toEqual(['c-3']);

      const override = currentDb
        .prepare('SELECT custom_name, custom_number FROM channel_overrides WHERE content_id = ?')
        .get('c-1') as { custom_name: string; custom_number: number };
      expect(override.custom_name).toBe('Renamed');
      expect(override.custom_number).toBe(7);
    });

    it('round-trips a full backup: export → import → identical shape', () => {
      insertTestSource(currentDb, { id: 'src-1' });
      insertTestContent(currentDb, { id: 'c-1', sourceId: 'src-1', streamUrl: 'http://s/a', title: 'A' });
      currentDb
        .prepare('INSERT INTO favorites (id, content_id, added_at) VALUES (?, ?, ?)')
        .run('fav-1', 'c-1', 100);
      currentDb
        .prepare("INSERT INTO settings (key, value) VALUES ('ui_theme', 'oled')")
        .run();

      const { backup: snapshot } = buildBackup();

      // Wipe favorites + settings only — leave source/content in place so the
      // re-link by (sourceId, streamUrl) can resolve during import.
      currentDb.prepare('DELETE FROM favorites').run();
      currentDb.prepare('DELETE FROM settings').run();

      const res = importBackup(snapshot, 'merge');
      expect(res.ok).toBe(true);
      if (!res.ok) return;

      expect(res.stats.favoritesImported).toBe(1);
      expect(res.stats.settingsImported).toBe(1);
      const theme = currentDb
        .prepare("SELECT value FROM settings WHERE key = 'ui_theme'")
        .get() as { value: string };
      expect(theme.value).toBe('oled');
    });
  });
});
