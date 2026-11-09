import { describe, expect, it } from 'vitest';
import { loadConfig, parseZeroClawUrl } from '../config';

describe('gateway ZeroClaw opt-in', () => {
  it('fica desligado sem URL e normaliza origem local', () => {
    const env = { HOME: '/tmp/habblaud-config-isolated', HABBLAUD_IN_DOCKER: '0', HABBLAUD_OMP: '0' };
    expect(loadConfig(env, []).zeroclawUrl).toBeUndefined();
    expect(loadConfig({ ...env, HABBLAUD_ZEROCLAW_URL: '  ' }, []).zeroclawUrl).toBeUndefined();
    for (const url of ['http://127.0.0.1:42617/', 'http://localhost:42617/', 'http://[::1]:42617/']) {
      expect(parseZeroClawUrl(url)).toBe(url.slice(0, -1));
    }
  });
  it('recusa rede remota, credenciais e componentes extras sem expor segredos', () => {
    for (const url of ['https://127.0.0.1', 'ws://localhost', 'http://192.168.1.2', 'http://127.0.0.1/path', 'http://localhost/?x=1', 'http://localhost/#x', 'http://user:secret@localhost/', 'invalid']) {
      expect(() => parseZeroClawUrl(url)).toThrow(/HABBLAUD_ZEROCLAW_URL/);
      try { parseZeroClawUrl(url); } catch (error) { expect(String(error)).not.toContain('secret'); }
    }
  });
});
