import { describe, expect, it } from 'vitest';
import { loadConfig, parseZeroClawUrl } from '../config';

const isolatedEnv = { HOME: '/tmp/habblaud-config-isolated', HABBLAUD_IN_DOCKER: '0', HABBLAUD_OMP: '0' };
const models = [{ alias: 'm_ba7816bf8f01', model: 'abc' }];
const catalogEnv = {
  HABBLAUD_ZEROCLAW_AGENTS: JSON.stringify(models),
  HABBLAUD_ZEROCLAW_DEFAULT_AGENT: models[0].alias,
};

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

describe('configuração do catálogo ZeroClaw', () => {
  it('mantém OMP sem catálogo ou runtime ZeroClaw', () => {
    const config = loadConfig({ ...isolatedEnv, HABBLAUD_OMP: '1', HABBLAUD_OMP_DIRS: '/tmp/habblaud-config-isolated/omp' }, []);
    expect(config.omp).toBe(true);
    expect(config.zeroclawUrl).toBeUndefined();
    expect(config.zeroclawCatalog).toBeUndefined();
  });

  it('integra catálogo validado somente com URL do gateway', () => {
    const config = loadConfig({ ...isolatedEnv, ...catalogEnv, HABBLAUD_ZEROCLAW_URL: ' http://127.0.0.1:42617/ ' }, []);
    expect(config.zeroclawUrl).toBe('http://127.0.0.1:42617');
    expect(config.zeroclawCatalog).toEqual({ models, defaultModel: models[0].alias });
    expect(config.omp).toBe(false);
    expect(config.ompDirs).toEqual([]);
  });

  it.each([
    {},
    { HABBLAUD_ZEROCLAW_AGENTS: catalogEnv.HABBLAUD_ZEROCLAW_AGENTS },
    { HABBLAUD_ZEROCLAW_DEFAULT_AGENT: catalogEnv.HABBLAUD_ZEROCLAW_DEFAULT_AGENT },
    { ...catalogEnv, HABBLAUD_ZEROCLAW_AGENTS: '' },
    { ...catalogEnv, HABBLAUD_ZEROCLAW_DEFAULT_AGENT: '' },
    { ...catalogEnv, HABBLAUD_ZEROCLAW_AGENTS: '[]' },
    { ...catalogEnv, HABBLAUD_ZEROCLAW_AGENTS: 'not-json' },
    { ...catalogEnv, HABBLAUD_ZEROCLAW_DEFAULT_AGENT: 'm_000000000000' },
  ])('recusa catálogo parcial ou inválido com URL, sem fallback: %j', (env) => {
    expect(() => loadConfig({ ...isolatedEnv, ...env, HABBLAUD_ZEROCLAW_URL: 'http://127.0.0.1:42617' }, [])).toThrow(/HABBLAUD_ZEROCLAW_/);
  });

  it.each([
    catalogEnv,
    { HABBLAUD_ZEROCLAW_AGENTS: catalogEnv.HABBLAUD_ZEROCLAW_AGENTS },
    { HABBLAUD_ZEROCLAW_DEFAULT_AGENT: catalogEnv.HABBLAUD_ZEROCLAW_DEFAULT_AGENT },
    { HABBLAUD_ZEROCLAW_AGENTS: '' },
    { HABBLAUD_ZEROCLAW_DEFAULT_AGENT: '' },
  ])('recusa catálogo mesmo parcial sem URL, não seleciona OMP: %j', (env) => {
    for (const url of [undefined, '', ' ']) {
      expect(() => loadConfig({ ...isolatedEnv, ...env, HABBLAUD_ZEROCLAW_URL: url }, [])).toThrow(/HABBLAUD_ZEROCLAW_URL/);
    }
  });

  it('não permite catálogo válido mascarar URL inválida', () => {
    expect(() => loadConfig({ ...isolatedEnv, ...catalogEnv, HABBLAUD_ZEROCLAW_URL: 'http://remote.example' }, [])).toThrow(/HABBLAUD_ZEROCLAW_URL/);
  });
});
