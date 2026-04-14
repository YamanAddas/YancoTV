import { describe, it, expect } from 'vitest';
import {
  APP_NAME,
  APP_VERSION,
  DB_FILE_NAME,
  DEFAULT_WINDOW_WIDTH,
  DEFAULT_WINDOW_HEIGHT,
  MIN_WINDOW_WIDTH,
  MIN_WINDOW_HEIGHT,
} from '../../src/shared/constants';

describe('Shared Constants', () => {
  it('APP_NAME is defined', () => {
    expect(APP_NAME).toBe('YancoTV');
  });

  it('APP_VERSION follows semver', () => {
    expect(APP_VERSION).toMatch(/^\d+\.\d+\.\d+$/);
  });

  it('DB_FILE_NAME has .db extension', () => {
    expect(DB_FILE_NAME).toMatch(/\.db$/);
  });

  it('window dimensions are reasonable', () => {
    expect(DEFAULT_WINDOW_WIDTH).toBeGreaterThanOrEqual(800);
    expect(DEFAULT_WINDOW_HEIGHT).toBeGreaterThanOrEqual(600);
    expect(MIN_WINDOW_WIDTH).toBeLessThanOrEqual(DEFAULT_WINDOW_WIDTH);
    expect(MIN_WINDOW_HEIGHT).toBeLessThanOrEqual(DEFAULT_WINDOW_HEIGHT);
  });

  it('min dimensions are reasonable', () => {
    expect(MIN_WINDOW_WIDTH).toBeGreaterThanOrEqual(640);
    expect(MIN_WINDOW_HEIGHT).toBeGreaterThanOrEqual(480);
  });
});
