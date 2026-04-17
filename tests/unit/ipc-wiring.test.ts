import { describe, it, expect } from 'vitest';
import { IpcChannels } from '../../src/shared/ipc-channels';

describe('IPC Wiring Consistency', () => {
  it('all IPC channel values are unique', () => {
    const values = Object.values(IpcChannels);
    const unique = new Set(values);
    expect(unique.size).toBe(values.length);
  });

  it('all IPC channel values follow naming convention (namespace:action)', () => {
    for (const [key, value] of Object.entries(IpcChannels)) {
      // Value: namespace:camelCaseAction (may include digits like M3u)
      expect(value).toMatch(/^[a-z]+:[a-zA-Z0-9]+$/);
      // Key should be SCREAMING_SNAKE_CASE
      expect(key).toMatch(/^[A-Z][A-Z0-9_]+$/);
    }
  });

  it('has all expected source channels', () => {
    expect(IpcChannels.SOURCES_GET_ALL).toBe('sources:getAll');
    expect(IpcChannels.SOURCES_ADD).toBe('sources:add');
    expect(IpcChannels.SOURCES_REMOVE).toBe('sources:remove');
    expect(IpcChannels.SOURCES_SYNC).toBe('sources:sync');
    expect(IpcChannels.SOURCES_SYNC_PROGRESS).toBe('sources:syncProgress');
  });

  it('has all expected content channels', () => {
    expect(IpcChannels.CONTENT_GET_LIVE).toBe('content:getLive');
    expect(IpcChannels.CONTENT_GET_MOVIES).toBe('content:getMovies');
    expect(IpcChannels.CONTENT_GET_SERIES).toBe('content:getSeries');
    expect(IpcChannels.CONTENT_GET_CATEGORIES).toBe('content:getCategories');
    expect(IpcChannels.CONTENT_SEARCH).toBe('content:search');
    expect(IpcChannels.CONTENT_GET_EPISODES).toBe('content:getEpisodes');
    expect(IpcChannels.CONTENT_GET_DETAIL).toBe('content:getDetail');
    expect(IpcChannels.CONTENT_GET_RELATED).toBe('content:getRelated');
  });

  it('has all expected player channels', () => {
    expect(IpcChannels.PLAYER_PLAY).toBe('player:play');
    expect(IpcChannels.PLAYER_PAUSE).toBe('player:pause');
    expect(IpcChannels.PLAYER_RESUME).toBe('player:resume');
    expect(IpcChannels.PLAYER_STOP).toBe('player:stop');
    expect(IpcChannels.PLAYER_SEEK).toBe('player:seek');
    expect(IpcChannels.PLAYER_SET_VOLUME).toBe('player:setVolume');
    expect(IpcChannels.PLAYER_TOGGLE_MUTE).toBe('player:toggleMute');
    expect(IpcChannels.PLAYER_SET_SPEED).toBe('player:setSpeed');
    expect(IpcChannels.PLAYER_SET_ASPECT_RATIO).toBe('player:setAspectRatio');
    expect(IpcChannels.PLAYER_TOGGLE_FULLSCREEN).toBe('player:toggleFullscreen');
    expect(IpcChannels.PLAYER_GET_TRACKS).toBe('player:getTracks');
    expect(IpcChannels.PLAYER_SET_SUBTITLE_TRACK).toBe('player:setSubtitleTrack');
    expect(IpcChannels.PLAYER_SET_AUDIO_TRACK).toBe('player:setAudioTrack');
    expect(IpcChannels.PLAYER_STATE).toBe('player:state');
    expect(IpcChannels.PLAYER_STATE_CHANGED).toBe('player:stateChanged');
    expect(IpcChannels.PLAYER_TIME_UPDATE).toBe('player:timeUpdate');
    expect(IpcChannels.PLAYER_ERROR).toBe('player:error');
  });

  it('has all expected favorites channels', () => {
    expect(IpcChannels.FAVORITES_GET_ALL).toBe('favorites:getAll');
    expect(IpcChannels.FAVORITES_ADD).toBe('favorites:add');
    expect(IpcChannels.FAVORITES_REMOVE).toBe('favorites:remove');
    expect(IpcChannels.FAVORITES_GET_IDS).toBe('favorites:getIds');
  });

  it('has all expected history channels', () => {
    expect(IpcChannels.HISTORY_GET_RECENT).toBe('history:getRecent');
    expect(IpcChannels.HISTORY_RECORD).toBe('history:record');
    expect(IpcChannels.HISTORY_UPDATE_POSITION).toBe('history:updatePosition');
    expect(IpcChannels.HISTORY_GET_POSITION).toBe('history:getPosition');
    expect(IpcChannels.HISTORY_REMOVE).toBe('history:remove');
    expect(IpcChannels.HISTORY_CLEAR).toBe('history:clear');
  });

  it('has all expected settings channels', () => {
    expect(IpcChannels.SETTINGS_GET_ALL).toBe('settings:getAll');
    expect(IpcChannels.SETTINGS_SET).toBe('settings:set');
    expect(IpcChannels.SETTINGS_SET_MANY).toBe('settings:setMany');
  });

  it('has all expected system channels', () => {
    expect(IpcChannels.DB_STATUS).toBe('db:status');
    expect(IpcChannels.DIALOG_OPEN_M3U_FILE).toBe('dialog:openM3uFile');
    expect(IpcChannels.APP_GET_VERSION).toBe('app:getVersion');
  });

  it('has all expected parental control channels', () => {
    expect(IpcChannels.PARENTAL_GET_SETTINGS).toBe('parental:getSettings');
    expect(IpcChannels.PARENTAL_SET_PIN).toBe('parental:setPin');
    expect(IpcChannels.PARENTAL_VERIFY_PIN).toBe('parental:verifyPin');
    expect(IpcChannels.PARENTAL_REMOVE_PIN).toBe('parental:removePin');
    expect(IpcChannels.PARENTAL_UPDATE_SETTING).toBe('parental:updateSetting');
    expect(IpcChannels.PARENTAL_LOCK_CHANNEL).toBe('parental:lockChannel');
    expect(IpcChannels.PARENTAL_UNLOCK_CHANNEL).toBe('parental:unlockChannel');
    expect(IpcChannels.PARENTAL_GET_LOCKED_IDS).toBe('parental:getLockedIds');
    expect(IpcChannels.PARENTAL_IS_LOCKED).toBe('parental:isLocked');
    expect(IpcChannels.PARENTAL_HIDE_CHANNEL).toBe('parental:hideChannel');
    expect(IpcChannels.PARENTAL_UNHIDE_CHANNEL).toBe('parental:unhideChannel');
    expect(IpcChannels.PARENTAL_GET_HIDDEN_IDS).toBe('parental:getHiddenIds');
    expect(IpcChannels.PARENTAL_SET_OVERRIDE).toBe('parental:setOverride');
    expect(IpcChannels.PARENTAL_REMOVE_OVERRIDE).toBe('parental:removeOverride');
    expect(IpcChannels.PARENTAL_GET_OVERRIDES).toBe('parental:getOverrides');
  });

  it('exports exactly 104 channels', () => {
    expect(Object.keys(IpcChannels)).toHaveLength(104);
  });
});
