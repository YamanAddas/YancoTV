import { createFavoritesStore } from '@yancotv/core';
import { sqliteFavoritesAdapter } from '../db/favorites-adapter';

/**
 * Mobile favorites store — core factory + op-sqlite adapter.
 *
 * Call `useFavoritesStore.getState().load()` once after `initDatabase()`
 * to hydrate `favoriteIds` from the `favorites` table.
 */
export const useFavoritesStore = createFavoritesStore(sqliteFavoritesAdapter);
