export * from './types';
export * from './parsers';
export * from './content';
export * from './catchup';
export * from './parental';
export * from './http';
export * from './xtream';
export * from './stalker';

export { NOOP_LOGGER } from './logger';
export type { Logger } from './logger';

export {
  sourceTypeSchema,
  addSourceInputSchema,
  updateSourceInputSchema,
  reorderIdsSchema,
  groupPrefSetSchema,
  groupPrefReorderSchema,
  channelOverrideSchema,
} from './schemas';
export type { GroupPrefSetInput, ChannelOverrideInput } from './schemas';

export const CORE_VERSION = '0.1.0';
