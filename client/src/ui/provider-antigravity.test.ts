import { describe, expect, it } from 'vitest';
import { accountProvider, fallbackShort, isAntigravity, looksLikeAntigravityId, PROVIDER_NAME, runtimeAllowsControl, runtimeAllowsDeliveryModes, runtimeAllowsMessages, runtimeAllowsOmpTools } from './provider';
import { barActions } from './omp-bar';
import type { AgentInfo } from '../../../shared/types';

describe('ferramenta Antigravity', () => {
  it('mostra nome e identifica conta fora do snapshot', () => {
    expect(PROVIDER_NAME.antigravity).toBe('Antigravity');
    expect(accountProvider(undefined, 'antigravity')).toBe('antigravity');
    expect(looksLikeAntigravityId('demo:.antigravity~2')).toBe(true);
    expect(fallbackShort('antigravity')).toBe('G');
    expect(isAntigravity({ provider: 'antigravity' })).toBe(true);
  });
  it('permite mensagens, STOP e conversa nova sem modos de entrega ou ferramentas omp', () => {
    expect(runtimeAllowsMessages('antigravity')).toBe(true);
    expect(runtimeAllowsControl('antigravity')).toBe(true);
    expect(runtimeAllowsDeliveryModes('antigravity')).toBe(false);
    expect(runtimeAllowsOmpTools('antigravity')).toBe(false);
    const agent = { provider: 'antigravity', kind: 'main', status: 'working' } as AgentInfo;
    expect(barActions(agent, true, false)).toMatchObject({ new: true, stop: true, canStop: true, shake: false, advisor: false, resume: false });
  });
});
