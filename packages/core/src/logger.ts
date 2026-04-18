/** Minimal logger interface. Platforms inject their own implementation. */
export interface Logger {
  info(msg: string): void;
  warn(msg: string): void;
  error(msg: string): void;
}

export const NOOP_LOGGER: Logger = {
  info: () => {},
  warn: () => {},
  error: () => {},
};
