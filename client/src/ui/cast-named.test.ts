import { describe, expect, it } from 'vitest';
import { PERSONA_MAX_CHARS, telegramPeers, validPersona, validTelegramToken } from './cast-named';

describe('configurações do agente fixo', () => {
  it('acompanha o limite de caracteres da persona do gateway', () => {
    expect(PERSONA_MAX_CHARS).toBe(20_000);
  });

  it('limita persona por caracteres Unicode, não unidades UTF-16', () => {
    expect(validPersona('')).toBe(true);
    expect(validPersona('a'.repeat(20_000))).toBe(true);
    expect(validPersona('a'.repeat(20_001))).toBe(false);
    expect(validPersona('😀'.repeat(20_000))).toBe(true);
    expect(validPersona('😀'.repeat(20_001))).toBe(false);
  });

  it('aceita IDs numéricos únicos sem converter nem perder precisão', () => {
    expect(telegramPeers(' 001, 12345678901234567890 ')).toEqual(['001', '12345678901234567890']);
    expect(telegramPeers('0')).toEqual(['0']);
    expect(telegramPeers('1,2,3,4,5,6,7,8')).toHaveLength(8);
  });

  it.each(['', '1,', '1,,2', '1, 1', '-123', '+123', 'abc', '1.0', '123456789012345678901', '1,2,3,4,5,6,7,8,9'])(
    'rejeita IDs fora do contrato: %s', (value) => expect(telegramPeers(value)).toBeUndefined(),
  );

  it('valida limites do token sem aceitar espaços ou caracteres extras', () => {
    expect(validTelegramToken(`123456:${'A'.repeat(30)}`)).toBe(true);
    expect(validTelegramToken(`123456789012:${'_-a0'.repeat(16)}`)).toBe(true);
    for (const token of [
      `12345:${'a'.repeat(30)}`, `1234567890123:${'a'.repeat(30)}`,
      `123456:${'a'.repeat(29)}`, `123456:${'a'.repeat(65)}`,
      ` 123456:${'a'.repeat(30)}`, `123456:${'a'.repeat(30)} `,
      `123456:${'a'.repeat(29)}!`, '',
    ]) expect(validTelegramToken(token)).toBe(false);
  });
});
