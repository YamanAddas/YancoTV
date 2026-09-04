/**
 * Single source of truth for all IPC channel names.
 * Both main and renderer processes import from here.
 */
export const IpcChannels = {
  // Source management
  SOURCES_GET_ALL: 'sources:getAll',
  SOURCES_ADD: 'sources:add',
  SOURCES_UPDATE: 'sources:update',
  SOURCES_REMOVE: 'sources:remove',
  SOURCES_SYNC: 'sources:sync',
  SOURCES_SYNC_PROGRESS: 'sources:syncProgress',
  SOURCES_REORDER: 'sources:reorder',

  // Content browsing
  CONTENT_GET_LIVE: 'content:getLive',
  CONTENT_GET_MOVIES: 'content:getMovies',
  CONTENT_GET_SERIES: 'content:getSeries',
  CONTENT_GET_CATEGORIES: 'content:getCategories',
  CONTENT_SEARCH: 'content:search',
  CONTENT_GET_EPISODES: 'content:getEpisodes',
  CONTENT_GET_DETAIL: 'content:getDetail',
  CONTENT_GET_RELATED: 'content:getRelated',

  // Player control
  PLAYER_PLAY: 'player:play',
  PLAYER_PAUSE: 'player:pause',
  PLAYER_RESUME: 'player:resume',
  PLAYER_STOP: 'player:stop',
  PLAYER_SEEK: 'player:seek',
  PLAYER_SET_VOLUME: 'player:setVolume',
  PLAYER_TOGGLE_MUTE: 'player:toggleMute',
  PLAYER_SET_SPEED: 'player:setSpeed',
  PLAYER_SET_ASPECT_RATIO: 'player:setAspectRatio',
  PLAYER_TOGGLE_FULLSCREEN: 'player:toggleFullscreen',
  PLAYER_GET_TRACKS: 'player:getTracks',
  PLAYER_SET_SUBTITLE_TRACK: 'player:setSubtitleTrack',
  PLAYER_TOGGLE_SUBTITLES: 'player:toggleSubtitles',
  PLAYER_SET_AUDIO_TRACK: 'player:setAudioTrack',
  PLAYER_STATE: 'player:state',
  PLAYER_GET_MEDIA_INFO: 'player:getMediaInfo',
  PLAYER_LOAD_SUBTITLE_FILE: 'player:loadSubtitleFile',
  PLAYER_ADD_SUBTITLE_PATH: 'player:addSubtitlePath',
  PLAYER_SET_SUBTITLE_DELAY: 'player:setSubtitleDelay',
  PLAYER_SET_AUDIO_DELAY: 'player:setAudioDelay',
  PLAYER_SET_VIDEO_ZOOM: 'player:setVideoZoom',
  PLAYER_TAKE_SCREENSHOT: 'player:takeScreenshot',
  PLAYER_SET_FULLSCREEN: 'player:setFullscreen',

  // Subtitle provider — OpenSubtitles
  SUBTITLES_SEARCH: 'subtitles:search',
  SUBTITLES_DOWNLOAD_AND_LOAD: 'subtitles:downloadAndLoad',
  SUBTITLES_SET_CREDENTIALS: 'subtitles:setCredentials',
  SUBTITLES_CLEAR_CREDENTIALS: 'subtitles:clearCredentials',
  SUBTITLES_GET_CACHE_STATS: 'subtitles:getCacheStats',
  SUBTITLES_CLEAR_CACHE: 'subtitles:clearCache',

  // Player events (main → renderer)
  PLAYER_STATE_CHANGED: 'player:stateChanged',
  PLAYER_TIME_UPDATE: 'player:timeUpdate',
  PLAYER_ERROR: 'player:error',
  PLAYER_SUBTITLE_TEXT: 'player:subtitleText',

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
  HISTORY_GET_POSITIONS_BATCH: 'history:getPositionsBatch',
  HISTORY_REMOVE: 'history:remove',
  HISTORY_CLEAR: 'history:clear',

  // EPG
  EPG_REFRESH: 'epg:refresh',
  EPG_GET_NOW_NEXT: 'epg:getNowNext',
  EPG_GET_NOW_NEXT_BATCH: 'epg:getNowNextBatch',
  EPG_GET_GUIDE: 'epg:getGuide',
  EPG_GET_FOR_CHANNEL: 'epg:getForChannel',
  EPG_GET_STATS: 'epg:getStats',
  EPG_SET_GLOBAL_URL: 'epg:setGlobalUrl',
  EPG_GET_SETTINGS: 'epg:getSettings',
  /** Push event: main → renderer when a refresh starts/completes/errors */
  EPG_REFRESH_PROGRESS: 'epg:refreshProgress',

  // Catch-up
  CATCHUP_GET_URL: 'catchup:getUrl',
  CATCHUP_CHECK_SUPPORT: 'catchup:checkSupport',

  // (Removed 2026-09-04.) The four `timeshift:*` channels drove a service that
  // only tracked "how far behind live the viewer is" and broadcast it. No
  // renderer file ever consumed it — git history shows it was never wired, not
  // that it regressed. Live rewind itself is unaffected: it comes from mpv's
  // own back-buffer (`--demuxer-max-back-bytes`, applied whenever
  // `player:play` resolves the item as `type === 'live'`), never from this
  // service. Restore from git history if a "3:42 behind live" indicator is
  // ever built.

  // General app settings (key-value store)
  SETTINGS_GET_ALL: 'settings:getAll',
  SETTINGS_SET: 'settings:set',
  SETTINGS_SET_MANY: 'settings:setMany',

  // Parental Controls
  PARENTAL_GET_SETTINGS: 'parental:getSettings',
  PARENTAL_SET_PIN: 'parental:setPin',
  PARENTAL_VERIFY_PIN: 'parental:verifyPin',
  PARENTAL_REMOVE_PIN: 'parental:removePin',
  PARENTAL_UPDATE_SETTING: 'parental:updateSetting',
  PARENTAL_LOCK_CHANNEL: 'parental:lockChannel',
  PARENTAL_UNLOCK_CHANNEL: 'parental:unlockChannel',
  PARENTAL_GET_LOCKED_IDS: 'parental:getLockedIds',
  PARENTAL_IS_LOCKED: 'parental:isLocked',
  PARENTAL_HIDE_CHANNEL: 'parental:hideChannel',
  PARENTAL_UNHIDE_CHANNEL: 'parental:unhideChannel',
  PARENTAL_GET_HIDDEN_IDS: 'parental:getHiddenIds',
  PARENTAL_SET_OVERRIDE: 'parental:setOverride',
  PARENTAL_REMOVE_OVERRIDE: 'parental:removeOverride',
  PARENTAL_GET_OVERRIDES: 'parental:getOverrides',
  /** MB-405 — may this item be played, or must the PIN be entered first? */
  PARENTAL_REQUIRES_PIN: 'parental:requiresPin',

  // Group Preferences
  GROUP_PREFS_GET: 'groups:getPrefs',
  GROUP_PREFS_SET: 'groups:setPrefs',
  GROUP_PREFS_REMOVE: 'groups:removePrefs',
  GROUP_PREFS_REORDER: 'groups:reorder',

  // Downloads (HTTP-streaming VOD download manager)
  DOWNLOAD_ENQUEUE: 'download:enqueue',
  DOWNLOAD_PAUSE: 'download:pause',
  DOWNLOAD_RESUME: 'download:resume',
  DOWNLOAD_CANCEL: 'download:cancel',
  DOWNLOAD_REMOVE: 'download:remove',
  DOWNLOAD_LIST: 'download:list',
  DOWNLOAD_OPEN_FOLDER: 'download:openFolder',
  /** main → renderer push: live progress updates for a download */
  DOWNLOAD_PROGRESS: 'download:progress',
  /** main → renderer push: status change (queued → downloading → completed/failed) */
  DOWNLOAD_STATUS: 'download:status',

  // TMDb metadata enrichment
  TMDB_GET_STATUS: 'tmdb:getStatus',
  TMDB_SET_API_KEY: 'tmdb:setApiKey',
  TMDB_CLEAR_API_KEY: 'tmdb:clearApiKey',
  TMDB_TEST_API_KEY: 'tmdb:testApiKey',
  TMDB_CLEAR_CACHE: 'tmdb:clearCache',

  // Recordings (ffmpeg-based live recording)
  RECORDING_START: 'recording:start',
  RECORDING_STOP: 'recording:stop',
  RECORDING_LIST: 'recording:list',
  RECORDING_DELETE: 'recording:delete',
  RECORDING_OPEN_FOLDER: 'recording:openFolder',
  RECORDING_CHECK_FFMPEG: 'recording:checkFfmpeg',
  /** main → renderer push: progress updates for a recording */
  RECORDING_PROGRESS: 'recording:progress',
  /** main → renderer push: status change (completed, failed, cancelled) */
  RECORDING_STATUS: 'recording:status',

  // Programme reminders — schedule a toast (and optional auto-tune) for a future EPG programme
  REMINDERS_LIST: 'reminders:list',
  REMINDERS_LIST_ACTIVE: 'reminders:listActive',
  REMINDERS_SET: 'reminders:set',
  REMINDERS_REMOVE: 'reminders:remove',
  REMINDERS_REMOVE_FOR_PROGRAMME: 'reminders:removeForProgramme',
  /** main → renderer push: a reminder just fired */
  REMINDERS_FIRED: 'reminders:fired',

  // Database
  DB_STATUS: 'db:status',

  // Dialog
  DIALOG_OPEN_M3U_FILE: 'dialog:openM3uFile',
  DIALOG_PICK_DIRECTORY: 'dialog:pickDirectory',
  DIALOG_PICK_FILE: 'dialog:pickFile',

  // App paths / admin
  APP_GET_VERSION: 'app:getVersion',
  APP_OPEN_DATA_DIR: 'app:openDataDir',
  APP_GET_PATHS: 'app:getPaths',
  APP_GET_LAUNCH_ON_STARTUP: 'app:getLaunchOnStartup',
  APP_SET_LAUNCH_ON_STARTUP: 'app:setLaunchOnStartup',

  // Backup / restore (user data → single JSON file)
  BACKUP_EXPORT: 'backup:export',
  BACKUP_IMPORT: 'backup:import',

  // Logs
  APP_EXPORT_LOGS: 'app:exportLogs',

  // Crash reporting — renderer → main, piped into the main log file
  CRASH_REPORT: 'crash:report',

  // Manual update check (the About tab button). Full auto-update lives on 18.3.
  APP_CHECK_FOR_UPDATES: 'app:checkForUpdates',

  // (Removed 2026-09-04.) The four `window:*` channels drove a custom
  // titlebar that was never mounted: `Titlebar.tsx` was imported by nothing,
  // and the main window is created WITHOUT `frame: false`, so Windows draws
  // its own chrome and a custom bar could not have appeared. Deleting them
  // narrows the IPC surface the renderer can reach. Restore from git history
  // if a frameless window is ever wanted.

  // Player — mpv availability check
  PLAYER_CHECK_MPV: 'player:checkMpv',

  // Embedded player — main window hosts mpv, overlay window hosts controls
  /** Renderer (main window) → main: begin theater/embedded-mpv mode (show overlay) */
  PLAYER_OVERLAY_SHOW: 'player:overlayShow',
  /** Renderer (main window) → main: exit theater mode (hide overlay, restore main UI) */
  PLAYER_OVERLAY_HIDE: 'player:overlayHide',
  /** main → overlay renderer: broadcast that theater mode just started */
  PLAYER_OVERLAY_SHOWN: 'player:overlayShown',
  /** main → overlay renderer: broadcast that theater mode just ended */
  PLAYER_OVERLAY_HIDDEN: 'player:overlayHidden',
  /**
   * Renderer → main: select the mpv presentation surface for the current
   * stream — 'theater' shows both the embedded video child window (full area)
   * and the transparent controls overlay; 'mini' shows the video at the
   * renderer-supplied custom bounds (see PLAYER_SET_VIDEO_BOUNDS) and hides
   * the controls overlay; 'idle' tears both down. html5 backend ignores this
   * channel since its surface lives inside the main React tree via
   * VideoStage.
   */
  PLAYER_SET_PRESENTATION: 'player:setPresentation',
  /**
   * Renderer → main: position the embedded mpv video child window over a
   * specific rect (in renderer-relative DIPs) for the docked mini-player.
   * Pass null/undefined to clear the custom bounds and restore full-content
   * tracking. Bounds are clamped to the parent content area on the main
   * side; HiDPI scaling is a no-op (Electron + CSS px both work in DIPs).
   */
  PLAYER_SET_VIDEO_BOUNDS: 'player:setVideoBounds',
  /**
   * main → all renderers: broadcast the player mode whenever it transitions
   * via setPresentation. Both the main window and the controls-overlay
   * BrowserWindow keep their own Zustand stores; without a sync broadcast,
   * an action in one (e.g. Back/Esc in the overlay's TheaterControls) wouldn't
   * propagate to the other (the main window's MiniPlayer wouldn't know to
   * mount). Receivers ignore the broadcast when the value already matches
   * their local mode.
   */
  PLAYER_MODE_BROADCAST: 'player:modeBroadcast',
} as const;

export type IpcChannel = (typeof IpcChannels)[keyof typeof IpcChannels];
