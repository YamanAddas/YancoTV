import type { ApiType } from '../../main/preload';

declare global {
  interface Window {
    api: ApiType;
  }
}
