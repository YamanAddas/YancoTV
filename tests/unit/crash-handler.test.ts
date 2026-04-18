import { describe, it, expect, vi, beforeEach } from 'vitest';

const { errorMock } = vi.hoisted(() => ({ errorMock: vi.fn() }));

vi.mock('electron-log/main', () => ({
  default: { info: vi.fn(), warn: vi.fn(), error: errorMock },
}));

vi.mock('electron', () => ({
  app: { on: vi.fn() },
}));

import { logRendererCrash, type CrashReport } from '../../src/main/services/crash-handler';

beforeEach(() => {
  errorMock.mockClear();
});

describe('crash-handler — logRendererCrash', () => {
  it('logs plain error with default kind and no location', () => {
    const report: CrashReport = { message: 'boom' };
    logRendererCrash(report);
    expect(errorMock).toHaveBeenCalledTimes(1);
    expect(errorMock.mock.calls[0][0]).toBe('[renderer/error] boom');
  });

  it('uses the reported kind tag when provided', () => {
    logRendererCrash({ message: 'rejected', kind: 'unhandledrejection' });
    expect(errorMock.mock.calls[0][0]).toBe('[renderer/unhandledrejection] rejected');
  });

  it('appends source with line and col when present', () => {
    logRendererCrash({
      message: 'null is not a function',
      kind: 'error',
      source: 'bundle.js',
      line: 42,
      col: 7,
    });
    expect(errorMock.mock.calls[0][0]).toBe(
      '[renderer/error] null is not a function at bundle.js:42:7',
    );
  });

  it('omits line/col when only source is known', () => {
    logRendererCrash({ message: 'oops', source: 'bundle.js' });
    expect(errorMock.mock.calls[0][0]).toBe('[renderer/error] oops at bundle.js');
  });

  it('treats missing col as 0', () => {
    logRendererCrash({ message: 'oops', source: 'bundle.js', line: 5 });
    expect(errorMock.mock.calls[0][0]).toBe('[renderer/error] oops at bundle.js:5:0');
  });

  it('logs the stack trace as a second error line when provided', () => {
    logRendererCrash({ message: 'bad', stack: 'Error: bad\n    at foo' });
    expect(errorMock).toHaveBeenCalledTimes(2);
    expect(errorMock.mock.calls[1][0]).toBe('Error: bad\n    at foo');
  });

  it('does not log a stack line when stack is missing', () => {
    logRendererCrash({ message: 'bad' });
    expect(errorMock).toHaveBeenCalledTimes(1);
  });

  it('accepts the react kind for ErrorBoundary reports', () => {
    logRendererCrash({ message: 'render failed', kind: 'react', stack: 'stack-trace' });
    expect(errorMock.mock.calls[0][0]).toBe('[renderer/react] render failed');
    expect(errorMock.mock.calls[1][0]).toBe('stack-trace');
  });
});
