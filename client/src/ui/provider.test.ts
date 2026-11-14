import { describe, expect, it } from 'vitest';
import type { AgentInfo, OfficeSnapshot } from '../../../shared/types';
import { setNeutralBrand } from '../../../shared/i18n';
import {
  accountChipLabel,
  accountProvider,
  codexApprovalLabel,
  emptyOfficeHint,
  fallbackShort,
  hasShortPermission,
  isCodex,
  isOmp,
  looksLikeCodexId,
  looksLikeOmpId,
  providerOf,
  PROVIDER_NAME,
  showsProviderTag,
  runtimeAllowsMessages,
  runtimeAllowsControl,
  runtimeAllowsDeliveryModes,
  runtimeAllowsOmpTools,
  runtimeAllowsContext,
  normalizeDeliverAs,
  parseCastModelCatalog,
  castModelOptions,
  castDefaultModel,
  validCastModel,
} from './provider';

describe('ferramenta (Claude Code ou Codex)', () => {
  it('nome ZeroClaw e selo no modo sem marca', () => {
    expect(PROVIDER_NAME.zeroclaw).toBe('ZeroClaw');
    expect(providerOf({ provider: 'zeroclaw' })).toBe('zeroclaw');
    setNeutralBrand(true);
    try {
      expect(showsProviderTag('zeroclaw')).toBe(false);
    } finally {
      setNeutralBrand(false);
    }
  });
  it('provider ausente = Claude Code', () => {
    expect(providerOf(undefined)).toBe('claude');
    expect(providerOf({})).toBe('claude');
    expect(providerOf({ provider: 'codex' })).toBe('codex');
    expect(isCodex({ provider: 'codex' })).toBe(true);
    expect(isCodex({ provider: 'claude' })).toBe(false);
    expect(isCodex(null)).toBe(false);
  });

  it('id com cara de pasta do Codex (só quando a conta já não está no snapshot)', () => {
    for (const id of ['.codex', 'demo:.codex', '.codex-trabalho', '.codex~2', '/Users/ana/.codex', 'codex']) expect(looksLikeCodexId(id), id).toBe(true);
    for (const id of ['.claude', '.claude-conta2', 'demo:.claude', 'meucodex', '.codexy', '']) expect(looksLikeCodexId(id), id).toBe(false);
    // Com a conta à mão, vale o provider dela; sem ela, a dica de quem chamou e, por fim, o id.
    expect(accountProvider({ provider: 'codex' }, '.claude')).toBe('codex');
    expect(accountProvider({}, '.codex')).toBe('claude');
    expect(accountProvider(undefined, '.claude', 'codex')).toBe('codex');
    expect(accountProvider(undefined, '.codex')).toBe('codex');
    expect(accountProvider(undefined, 'x')).toBe('claude');
  });

  it('letra do chip sem a conta no snapshot: nunca "." nem vazio', () => {
    expect(fallbackShort('.claude-conta2')).toBe('C');
    expect(fallbackShort('.claude')).toBe('?');
    expect(fallbackShort('.codex')).toBe('X');
    expect(fallbackShort('.codex-trabalho')).toBe('T');
    expect(fallbackShort('.codex~2')).toBe('X');
    expect(fallbackShort('demo:.codex')).toBe('X');
    expect(fallbackShort('minha-conta')).toBe('M');
    expect(fallbackShort('')).toBe('?');
    expect(fallbackShort('...', 'codex')).toBe('X');
  });

  it('selo "Codex": só no Codex, e sem repetir o nome da conta', () => {
    expect(showsProviderTag('codex', 'Conta X')).toBe(true);
    expect(showsProviderTag('codex', 'Codex')).toBe(false);
    expect(showsProviderTag('codex', 'Demo Codex')).toBe(false);
    expect(showsProviderTag('claude', 'Conta C')).toBe(false);
  });

  it('rótulo do chip: e-mail no Claude Code; ferramenta e plano no Codex', () => {
    expect(accountChipLabel({ name: 'Conta C', email: 'a@b.c' }, '.claude', 'claude')).toBe('Conta C (a@b.c)');
    expect(accountChipLabel({ name: 'Conta X', plan: 'Team', provider: 'codex' }, '.codex', 'codex')).toBe('Conta X · Codex · plano Team');
    expect(accountChipLabel({ name: 'Codex', plan: 'Team', provider: 'codex' }, '.codex', 'codex')).toBe('Codex · plano Team');
    expect(accountChipLabel(undefined, '.codex', 'codex')).toBe('.codex · Codex');
    expect(accountChipLabel(undefined, '', 'claude')).toBe('Conta desconhecida');
  });

  it('escritório vazio: atalhos só das contas do Claude Code; o Codex entra quando há conta dele', () => {
    const c = { short: 'C' };
    const d = { short: 'D' };
    const x = { short: 'X', provider: 'codex' as const };
    expect(emptyOfficeHint([c, d])).toBe('Abra o Claude Code em qualquer projeto (atalhos c ou d) e veja seu agente chegar.');
    expect(emptyOfficeHint([c, d, x])).toBe('Abra o Claude Code (atalhos c ou d) ou o Codex em qualquer projeto e veja seu agente chegar.');
    expect(emptyOfficeHint([x])).toBe('Abra o Claude Code ou o Codex em qualquer projeto e veja seu agente chegar.');
    expect(emptyOfficeHint([])).toBe('Abra o Claude Code em qualquer projeto e veja seu agente chegar.');
  });

  it('relógio de 1 s enquanto há pedido do Codex esperando', () => {
    const agent = (permission?: AgentInfo['permission'], status: AgentInfo['status'] = 'waiting') => ({ status, permission }) as AgentInfo;
    const p = { id: 'p', tool: 'Bash', title: 'Bash(ls)', text: 'Listando', icon: '💻', createdAt: 0, expiresAt: 25_000 };
    const snap = (agents: AgentInfo[]) => ({ agents }) as Pick<OfficeSnapshot, 'agents'>;
    expect(hasShortPermission(snap([agent({ ...p, provider: 'codex' })]))).toBe(true);
    expect(hasShortPermission(snap([agent(p)]))).toBe(false);
    expect(hasShortPermission(snap([agent({ ...p, provider: 'codex' }, 'offline')]))).toBe(false);
    expect(hasShortPermission(null)).toBe(false);
  });

  it('aprovações do Codex em português (valor desconhecido passa como veio)', () => {
    expect(codexApprovalLabel('on-request')).toBe('Pergunta quando precisa');
    expect(codexApprovalLabel('never')).toBe('Nunca pergunta');
    expect(codexApprovalLabel('outra')).toBe('outra');
    expect(codexApprovalLabel(undefined)).toBe('—');
  });
});

describe('ferramenta omp', () => {
  it('provider omp é reconhecido; ausente continua Claude Code', () => {
    expect(providerOf({ provider: 'omp' })).toBe('omp');
    expect(isOmp({ provider: 'omp' })).toBe(true);
    expect(isOmp({ provider: 'codex' })).toBe(false);
    expect(isOmp(undefined)).toBe(false);
  });

  it('id com cara de pasta do omp (só quando a conta não está no snapshot)', () => {
    for (const id of ['.omp', '.omp~2', 'demo:.omp', '/Users/ana/.omp', 'C:\\Users\\ana\\.omp']) expect(looksLikeOmpId(id), id).toBe(true);
    for (const id of ['.claude', '.codex', 'compomp', '.ompx', '']) expect(looksLikeOmpId(id), id).toBe(false);
    expect(accountProvider(undefined, '.omp')).toBe('omp');
    expect(accountProvider({ provider: 'omp' }, '.claude')).toBe('omp');
    expect(accountProvider(undefined, '.claude', 'omp')).toBe('omp');
    expect(fallbackShort('.omp')).toBe('O');
    expect(fallbackShort('.omp-trabalho')).toBe('T');
    expect(fallbackShort('...', 'omp')).toBe('O');
  });

  it('selo "omp" e rótulo do chip', () => {
    expect(showsProviderTag('omp', '.omp')).toBe(false);
    expect(showsProviderTag('omp', 'Conta pessoal')).toBe(true);
    expect(accountChipLabel({ name: 'Conta pessoal', provider: 'omp' }, '.omp', 'omp')).toBe('Conta pessoal · omp');
    expect(accountChipLabel({ name: 'omp', provider: 'omp' }, '.omp', 'omp')).toBe('omp');
    expect(accountChipLabel(undefined, '.omp', 'omp')).toBe('.omp · omp');
  });

  it('escritório vazio cita o omp só quando há conta dele (sem atalho para ela)', () => {
    const c = { short: 'C' };
    const o = { short: 'O', provider: 'omp' as const };
    const x = { short: 'X', provider: 'codex' as const };
    expect(emptyOfficeHint([c, o])).toBe('Abra o Claude Code (atalho c) ou o omp em qualquer projeto e veja seu agente chegar.');
    expect(emptyOfficeHint([o])).toBe('Abra o Claude Code ou o omp em qualquer projeto e veja seu agente chegar.');
    expect(emptyOfficeHint([c, x, o])).toBe('Abra o Claude Code (atalho c), o Codex ou o omp em qualquer projeto e veja seu agente chegar.');
  });

  it('relógio de 1 s também com pedido do omp esperando', () => {
    const p = { id: 'p', tool: 'bash', title: 'Bash(ls)', text: 'Listando', icon: '💻', createdAt: 0, expiresAt: 25_000, provider: 'omp' as const };
    const agent = (status: AgentInfo['status'] = 'waiting') => ({ status, permission: p }) as AgentInfo;
    expect(hasShortPermission({ agents: [agent()] })).toBe(true);
    expect(hasShortPermission({ agents: [agent('offline')] })).toBe(false);
  });
});

describe('recursos por runtime e catálogo do elenco', () => {
  it.each([
    [undefined, true, false, false],
    ['claude', true, false, false],
    ['codex', true, false, false],
    ['omp', true, true, true],
    ['zeroclaw', true, true, false],
    ['unknown', false, false, false],
    [null, false, false, false],
  ])('%s mantém permissões separadas', (provider, messages, controls, ompOnly) => {
    expect(runtimeAllowsMessages(provider)).toBe(messages);
    expect(runtimeAllowsControl(provider)).toBe(controls);
    expect(runtimeAllowsDeliveryModes(provider)).toBe(controls);
    expect(runtimeAllowsOmpTools(provider)).toBe(ompOnly);
    expect(runtimeAllowsContext(provider)).toBe(ompOnly);
  });

  it('normaliza somente no envio, preservando aside do OMP', () => {
    for (const value of ['aside', 'unknown', undefined]) expect(normalizeDeliverAs('zeroclaw', value)).toBe('followUp');
    for (const value of ['steer', 'followUp'] as const) expect(normalizeDeliverAs('zeroclaw', value)).toBe(value);
    expect(normalizeDeliverAs('omp', 'aside')).toBe('aside');
    expect(normalizeDeliverAs('claude', 'aside')).toBeUndefined();
    expect(normalizeDeliverAs(undefined, 'steer')).toBeUndefined();
  });

  it('catálogo funciona sem sessões: nome legível, valor alias exato e padrão validado', () => {
    const catalog = parseCastModelCatalog({ provider: 'zeroclaw', models: [{ alias: 'm_123', model: 'openai/gpt-5' }], defaultModel: 'm_123' });
    expect(castModelOptions(catalog)).toEqual([{ value: 'm_123', label: 'openai/gpt-5' }]);
    expect(castDefaultModel(catalog)).toBe('m_123');
    expect(validCastModel(catalog, 'm_123')).toBe(true);
    expect(validCastModel(catalog, 'openai/gpt-5')).toBe(false);
    expect(validCastModel(catalog, 'gpt-5')).toBe(false);
    expect(castDefaultModel({ ...catalog!, defaultModel: 'unknown' })).toBeUndefined();
  });

  it('aceita catálogo OMP sem modelo padrão, sem relaxar o contrato ZeroClaw', () => {
    expect(parseCastModelCatalog({ provider: 'omp', models: [] })).toEqual({ provider: 'omp', models: [], defaultModel: '' });
    expect(parseCastModelCatalog({ provider: 'zeroclaw', models: [] })).toBeUndefined();
    expect(parseCastModelCatalog({ provider: 'omp', models: [], defaultModel: 42 })).toBeUndefined();
  });

  it('expõe agentes fixos somente no ZeroClaw, mantendo a resposta antiga sem named', () => {
    const base = { provider: 'zeroclaw', models: [], defaultModel: '' };
    const named = [{ alias: 'editor01', model: 'openai/gpt-5' }];
    expect(parseCastModelCatalog({ ...base, named })?.named).toEqual(named);
    expect(parseCastModelCatalog(base)).not.toHaveProperty('named');
    expect(parseCastModelCatalog({ ...base, named: [] })).not.toHaveProperty('named');
    expect(parseCastModelCatalog({ ...base, provider: 'omp', named })).not.toHaveProperty('named');
  });

  it('rejeita catálogo com aliases fixos inválidos, repetidos ou modelos ausentes', () => {
    const base = { provider: 'zeroclaw', models: [], defaultModel: '' };
    for (const named of [
      null, {}, [{ alias: 'a', model: 'x' }], [{ alias: 'Uppercase', model: 'x' }],
      [{ alias: 'a_b', model: 'x' }], [{ alias: 'a'.repeat(25), model: 'x' }],
      [{ alias: 'ok', model: '' }], [{ alias: 'ok' }],
      [{ alias: 'ok', model: 'x' }, { alias: 'ok', model: 'y' }],
    ]) expect(parseCastModelCatalog({ ...base, named })).toBeUndefined();
    expect(parseCastModelCatalog({ ...base, named: [{ alias: 'a'.repeat(24), model: 'x' }] })?.named).toHaveLength(1);
  });

  it('catálogo ausente ou inválido nunca inventa runtime ou opções OMP', () => {
    for (const raw of [null, {}, { provider: 'unknown', models: [], defaultModel: '' }, { provider: 'zeroclaw', models: [{ alias: 'm_1' }], defaultModel: 'm_1' }]) {
      expect(parseCastModelCatalog(raw)).toBeUndefined();
    }
    expect(castModelOptions(undefined)).toEqual([]);
    expect(castDefaultModel(undefined)).toBeUndefined();
    expect(validCastModel(undefined, 'm_1')).toBe(false);
  });

  it('ZeroClaw tem selo próprio, não duplica a conta e desaparece sem marca', () => {
    expect(showsProviderTag('zeroclaw', 'Pessoal')).toBe(true);
    expect(showsProviderTag('zeroclaw', 'Minha ZeroClaw')).toBe(false);
    expect(accountChipLabel({ name: 'Pessoal', provider: 'zeroclaw' }, 'zc', 'zeroclaw')).toBe('Pessoal · ZeroClaw');
    setNeutralBrand(true);
    try {
      expect(showsProviderTag('zeroclaw')).toBe(false);
      expect(accountChipLabel({ name: 'Pessoal', provider: 'zeroclaw' }, 'zc', 'zeroclaw')).not.toMatch(/ZeroClaw/);
    } finally {
      setNeutralBrand(false);
    }
  });
});
