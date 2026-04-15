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
  SOURCES_SYNC_PROGRESS: 'sources:syncProgress',

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
  PLAYER_RESUME: 'player:resume',
  PLAYER_STOP: 'player:stop',
  PLAYER_SEEK: 'player:seek',
  PLAYER_SET_VOLUME: 'player:setVolume',
  PLAYER_STATE: 'player:state',

  // Player events (main → renderer)
  PLAYER_STATE_CHANGED: 'player:stateChanged',
  PLAYER_TIME_UPDATE: 'player:timeUpdate',
  PLAYER_ERROR: 'player:error',

  // Favorites
  FAVORITES_GET_ALL: 'favorites:getAll',
  FAVORITES_ADD: 'favorites:add',
  FAVORITES_REMOVE: 'favorites:remove',
  FAVORITES_GET_IDS: 'favorites:getIds',

  // Watch history
  HISTORY_GET_RECENT: 'history:getRecent',
  HISTORY_RECORD: 'history:record',
  HISTORY_UPDATE_POSITION: 'history:updatePosition',
  HISTORY_GET_POSITION: 'history:getPosition',
  HISTORY_REMOVE: 'history:remove',
  HISTORY_CLEAR: 'history:clear',

  // Database
  DB_STATUS: 'db:status',

  // Dialog
  DIALOG_OPEN_M3U_FILE: 'dialog:openM3uFile',

  // App
  APP_GET_VERSION: 'app:getVersion',
} as const;

export type IpcChannel = (typeof IpcChannels)[keyof typeof IpcChannels];
