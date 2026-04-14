/**
 * Single source of truth for all IPC channel names.
 * Both main and renderer processes import from here.
 */
export const IpcChannels = {
  // Source management
  SOURCES_GET_ALL: 'sources:getAll',
  SOURCES_ADD: 'sources:add',
  SOURCES_REMOVE: 'sources:remove',
  SOURCES_SYNC: 'sources:sync',

  // Content browsing
  CONTENT_GET_LIVE: 'content:getLive',
  CONTENT_GET_MOVIES: 'content:getMovies',
  CONTENT_GET_SERIES: 'content:getSeries',
  CONTENT_GET_CATEGORIES: 'content:getCategories',
  CONTENT_SEARCH: 'content:search',
  CONTENT_GET_EPISODES: 'content:getEpisodes',

  // Player control
  PLAYER_PLAY: 'player:play',
  PLAYER_PAUSE: 'player:pause',
  PLAYER_STOP: 'player:stop',
  PLAYER_SEEK: 'player:seek',
  PLAYER_SET_VOLUME: 'player:setVolume',
  PLAYER_STATE: 'player:state',

  // Database
  DB_STATUS: 'db:status',

  // Dialog
  DIALOG_OPEN_M3U_FILE: 'dialog:openM3uFile',

  // App
  APP_GET_VERSION: 'app:getVersion',
} as const;

export type IpcChannel = (typeof IpcChannels)[keyof typeof IpcChannels];
