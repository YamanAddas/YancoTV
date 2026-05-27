export * from './types/index.js';
export * from './parsers/index.js';
export * from './content/index.js';
export * from './catchup/index.js';
export * from './parental/index.js';
// NOTE: stores are deliberately NOT re-exported from the main entry. They
// pull `zustand`, which transitively requires `react` — so re-exporting them
// here makes any consumer of `@yancotv/core` (including the Electron main
// process) eagerly load react at module-load time. Missing react in the
// packaged asar then crashes the app with ERR_MODULE_NOT_FOUND on launch
// (this bit us in 0.3.0). Import store factories from `@yancotv/core/stores`
// instead — the subpath export is declared in `package.json`.
export * from './http/index.js';
export * from './xtream/index.js';
export * from './stalker/index.js';

export { NOOP_LOGGER } from './logger.js';
export type { Logger } from './logger.js';

export {
  sourceTypeSchema,
  addSourceInputSchema,
  updateSourceInputSchema,
  reorderIdsSchema,
  groupPrefSetSchema,
  groupPrefReorderSchema,
  channelOverrideSchema,
} from './schemas/index.js';
export type { GroupPrefSetInput, ChannelOverrideInput } from './schemas/index.js';

export const CORE_VERSION = '0.1.0';
