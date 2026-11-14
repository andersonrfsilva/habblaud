import { describe, expect, it } from 'vitest';
import { loadConfig } from '../config';

const env = { HOME: '/tmp/habblaud-config-isolated', HABBLAUD_IN_DOCKER: '0', HABBLAUD_OMP: '0' };

describe('HABBLAUD_CAST_FOLDER', () => {
  it('ausente deixa o elenco na pasta pessoal', () => {
    expect(loadConfig(env, []).castFolder).toBeUndefined();
  });
  it('aceita caminho absoluto e recusa relativo', () => {
    expect(loadConfig({ ...env, HABBLAUD_CAST_FOLDER: ' /home/x/Desenvolvimento ' }, []).castFolder).toBe('/home/x/Desenvolvimento');
    expect(() => loadConfig({ ...env, HABBLAUD_CAST_FOLDER: 'Desenvolvimento' }, [])).toThrow(/caminho absoluto/);
  });
});
