import { open } from '@op-engineering/op-sqlite';

export interface DbSmokeResult {
  version: string;
  path: string;
}

export async function runDbSmoke(): Promise<DbSmokeResult> {
  const db = open({ name: 'yancotv-smoke.db' });
  try {
    const res = await db.execute('SELECT sqlite_version() AS v');
    const row = res.rows?.[0] as { v?: string } | undefined;
    const version = row?.v ?? 'unknown';
    return { version, path: 'yancotv-smoke.db' };
  } finally {
    db.close();
  }
}
