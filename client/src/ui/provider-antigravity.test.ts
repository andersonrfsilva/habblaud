import { describe, expect, it } from 'vitest';
import { accountProvider, fallbackShort, isAntigravity, looksLikeAntigravityId, PROVIDER_NAME, runtimeAllowsControl, runtimeAllowsDeliveryModes, runtimeAllowsMessages, runtimeAllowsOmpTools } from './provider';
import { barActions } from './omp-bar';
import type { AgentInfo } from '../../../shared/types';
import { castBody, crossesTool, modelGroups, parseCastModelCatalog, runtimeAllowsModelChoice, runtimeAllowsThinking } from './provider';
import { usageMessage } from './usage';

it('explica a ausência de cota Antigravity', () => {
  expect(usageMessage({ provider: 'antigravity', usageStatus: 'empty' })).toEqual(['o Antigravity não informa cota', 'o Antigravity não informa cota']);
});

describe('castBody', () => {
  const base = { name: 'Nina', look: 'f' as const, seed: 1, kind: 'main' as const, account: '.omp', agent: 'fixed' };
  it.each(['agy/gemini', 'agy/default'])('salva %s sem conta nem agente fixo', (model) => {
    const body = castBody({ ...base, model });
    expect(body.model).toBe(model);
    expect(body.kind).toBe('main');
    expect(body).not.toHaveProperty('account');
    expect(body).not.toHaveProperty('agent');
  });
  it('preserva conta do omp', () => {
    expect(castBody({ ...base, model: 'openai/gpt' }).account).toBe('.omp');
  });
});

describe('modelos agy', () => {
  it('lê apenas modelos agy válidos sem rejeitar o catálogo', () => {
    expect(parseCastModelCatalog({ provider: 'omp', models: [] })?.agyModels ?? []).toEqual([]);
    expect(parseCastModelCatalog({ provider: 'omp', models: [], agyModels: ['agy/default', 'agy/gemini', 'google-antigravity/x', 'agy/', 42] })?.agyModels)
      .toEqual(['agy/default', 'agy/gemini']);
  });
  it.each([
    ['omp', 'agy/default', true], ['omp', 'openai/x', false],
    ['antigravity', 'agy/gemini', false], ['antigravity', 'openai/x', true],
    ['claude', 'agy/default', false], ['codex', 'agy/default', false], ['zeroclaw', 'agy/default', false],
  ])('decide troca de ferramenta de %s para %s', (provider, model, expected) => {
    expect(crossesTool(provider, model as string)).toBe(expected);
  });
  it('libera modelo, mas não raciocínio para Antigravity', () => {
    expect(runtimeAllowsModelChoice('antigravity')).toBe(true);
    expect(runtimeAllowsThinking('antigravity')).toBe(false);
    expect(runtimeAllowsThinking('omp')).toBe(true);
    expect(modelGroups(['agy/default'])).toEqual([{ label: 'Antigravity', options: [{ value: 'agy/default', label: 'Padrão do Antigravity' }] }]);
  });
});

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
