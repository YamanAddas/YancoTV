export interface KVStore {
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
  remove(key: string): Promise<void>;
}

export async function getJson<T>(store: KVStore, key: string): Promise<T | null> {
  const raw = await store.get(key);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

export async function setJson(
  store: KVStore,
  key: string,
  value: unknown,
): Promise<void> {
  await store.set(key, JSON.stringify(value));
}
