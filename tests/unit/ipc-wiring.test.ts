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
  });

  it('has all expected content channels', () => {
    expect(IpcChannels.CONTENT_GET_LIVE).toBe('content:getLive');
    expect(IpcChannels.CONTENT_GET_MOVIES).toBe('content:getMovies');
    expect(IpcChannels.CONTENT_GET_SERIES).toBe('content:getSeries');
    expect(IpcChannels.CONTENT_GET_CATEGORIES).toBe('content:getCategories');
    expect(IpcChannels.CONTENT_SEARCH).toBe('content:search');
    expect(IpcChannels.CONTENT_GET_EPISODES).toBe('content:getEpisodes');
  });

  it('has all expected player channels', () => {
    expect(IpcChannels.PLAYER_PLAY).toBe('player:play');
    expect(IpcChannels.PLAYER_PAUSE).toBe('player:pause');
    expect(IpcChannels.PLAYER_RESUME).toBe('player:resume');
    expect(IpcChannels.PLAYER_STOP).toBe('player:stop');
    expect(IpcChannels.PLAYER_SEEK).toBe('player:seek');
    expect(IpcChannels.PLAYER_SET_VOLUME).toBe('player:setVolume');
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

  it('has all expected system channels', () => {
    expect(IpcChannels.DB_STATUS).toBe('db:status');
    expect(IpcChannels.DIALOG_OPEN_M3U_FILE).toBe('dialog:openM3uFile');
    expect(IpcChannels.APP_GET_VERSION).toBe('app:getVersion');
  });

  it('exports exactly 33 channels', () => {
    expect(Object.keys(IpcChannels)).toHaveLength(33);
  });
});
