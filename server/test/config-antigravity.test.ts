import { describe, expect, it } from 'vitest';
import { loadConfig } from '../config';

const env = { HOME: '/tmp/habblaud-config-isolated', HABBLAUD_IN_DOCKER: '0', HABBLAUD_OMP: '0' };

describe('Antigravity opt-in', () => {
  it('fica desligado por padrão e usa o binário oficial', () => {
    const config = loadConfig(env, []);
    expect(config.antigravity).toBe(false);
    expect(config.antigravityBin).toBe('agy');
    expect(config.antigravitySocket).toBeUndefined();
    expect(config.antigravitySkipPermissions).toBe(false);
  });
  it('liga explicitamente e normaliza as opções', () => {
    const config = loadConfig({ ...env, HABBLAUD_ANTIGRAVITY: '1', HABBLAUD_ANTIGRAVITY_BIN: ' /usr/bin/agy ', HABBLAUD_ANTIGRAVITY_SOCKET: ' /tmp/agy.sock ' }, []);
    expect(config.antigravity).toBe(true);
    expect(config.antigravityBin).toBe('/usr/bin/agy');
    expect(config.antigravitySocket).toBe('/tmp/agy.sock');
  });
  it('recusa soquete relativo', () => {
    expect(() => loadConfig({ ...env, HABBLAUD_ANTIGRAVITY_SOCKET: 'agy.sock' }, [])).toThrow(/caminho absoluto/);
  });
  it('não pula permissões sem opção explícita', () => {
    for (const value of [undefined, '', '0', 'false']) {
      expect(loadConfig({ ...env, HABBLAUD_ANTIGRAVITY_SKIP_PERMISSIONS: value }, []).antigravitySkipPermissions).toBe(false);
    }
    expect(loadConfig({ ...env, HABBLAUD_ANTIGRAVITY_SKIP_PERMISSIONS: '1' }, []).antigravitySkipPermissions).toBe(true);
  });
});
