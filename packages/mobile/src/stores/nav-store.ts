import { create } from 'zustand';

export type Screen =
  | 'home'
  | 'live'
  | 'movies'
  | 'series'
  | 'sources'
  | 'settings'
  | 'detail'
  | 'player';

interface NavState {
  screen: Screen;
  /** The previous screen — used so detail view can go back to the right list. */
  previousScreen: Screen;
  /** ID of the channel the user selected. Read by ChannelDetailScreen and PlayerScreen. */
  selectedChannelId?: string;

  navigate: (screen: Screen) => void;
  openDetail: (channelId: string) => void;
  openPlayer: (channelId: string) => void;
  back: () => void;
}

export const useNavStore = create<NavState>((set, get) => ({
  screen: 'home',
  previousScreen: 'home',
  selectedChannelId: undefined,

  navigate: (screen) =>
    set((s) => ({ screen, previousScreen: s.screen })),

  openDetail: (channelId) =>
    set((s) => ({
      screen: 'detail',
      previousScreen: s.screen,
      selectedChannelId: channelId,
    })),

  openPlayer: (channelId) =>
    set((s) => ({
      screen: 'player',
      previousScreen: s.screen,
      selectedChannelId: channelId,
    })),

  back: () => {
    const { previousScreen } = get();
    set({ screen: previousScreen, previousScreen: 'home' });
  },
}));
