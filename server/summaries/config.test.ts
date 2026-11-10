import { afterEach, describe, expect, it } from 'vitest';
import { loadConfig, parseSummaryHttpConfig } from '../config';
import { tempDir } from '../test/fixtures';

const complete = {
  HABBLAUD_SUMMARY_URL: ' https://example.test/base/// ',
  HABBLAUD_SUMMARY_MODEL: ' model-id ',
  HABBLAUD_SUMMARY_TOKEN_FILE: ' /not-read/token ',
};
let cleanup: (() => void) | undefined;
afterEach(() => cleanup?.());

describe('summary HTTP configuration', () => {
  it('keeps RPC when all variables are absent or blank', () => {
    expect(parseSummaryHttpConfig({})).toBeUndefined();
    expect(parseSummaryHttpConfig({ HABBLAUD_SUMMARY_URL: ' ', HABBLAUD_SUMMARY_MODEL: '', HABBLAUD_SUMMARY_TOKEN_FILE: '\n', HABBLAUD_SUMMARY_AUTH: ' ' })).toBeUndefined();
  });
  it('normalizes values, defaults authentication and accepts bearer without OMP', () => {
    expect(parseSummaryHttpConfig(complete)).toEqual({ url: 'https://example.test/base', model: 'model-id', tokenFile: '/not-read/token', auth: 'x-api-key' });
    const tmp = tempDir();
    cleanup = tmp.cleanup;
    const config = loadConfig({ HOME: tmp.dir, HABBLAUD_OMP: '0', ...complete, HABBLAUD_SUMMARY_AUTH: ' bearer ' }, []);
    expect(config.omp).toBe(false);
    expect(config.summaryHttp?.auth).toBe('bearer');
    expect(parseSummaryHttpConfig({ ...complete, HABBLAUD_SUMMARY_URL: 'http://127.0.0.1:4000/' })?.url).toBe('http://127.0.0.1:4000');
  });
  it.each(['HABBLAUD_SUMMARY_URL', 'HABBLAUD_SUMMARY_MODEL', 'HABBLAUD_SUMMARY_TOKEN_FILE'] as const)('rejects missing %s', (key) => {
    expect(() => parseSummaryHttpConfig({ ...complete, [key]: '' })).toThrow(key);
  });
  it('treats auth alone as HTTP intent', () => {
    expect(() => parseSummaryHttpConfig({ HABBLAUD_SUMMARY_AUTH: 'bearer' })).toThrow('HABBLAUD_SUMMARY_URL');
  });
  it.each(['not-a-url', 'ftp://example.test', 'https://user:secret@example.test', 'https://example.test?secret', 'https://example.test#secret', 'https://example.test?', 'https://example.test#', 'https://example.test/base/v1/messages///'])('rejects unsafe URL without echoing it: %s', (url) => {
    expect(() => parseSummaryHttpConfig({ ...complete, HABBLAUD_SUMMARY_URL: url })).toThrow(/^HABBLAUD_SUMMARY_URL inválida$/);
  });
  it('rejects invalid auth without echoing its value', () => {
    expect(() => parseSummaryHttpConfig({ ...complete, HABBLAUD_SUMMARY_AUTH: 'secret' })).toThrow(/^HABBLAUD_SUMMARY_AUTH inválida$/);
  });
});
