import AsyncStorage from '@react-native-async-storage/async-storage';
import type { KVStore } from './kv-store';

export const asyncStorageKV: KVStore = {
  async get(key) {
    return AsyncStorage.getItem(key);
  },
  async set(key, value) {
    await AsyncStorage.setItem(key, value);
  },
  async remove(key) {
    await AsyncStorage.removeItem(key);
  },
};
