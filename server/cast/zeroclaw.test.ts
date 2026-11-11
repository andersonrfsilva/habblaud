import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { parseZeroClawCatalog, resolveZeroClawModel } from './zeroclaw';

const first = { alias: 'm_ba7816bf8f01', model: 'abc' };
const second = { alias: 'm_2cf24dba5fb0', model: 'hello' };
const raw = JSON.stringify([first, second]);

describe('catálogo ZeroClaw', () => {
  it('valida SHA-256 conhecido, mantém ordem e exige padrão explícito', () => {
    expect(parseZeroClawCatalog(raw, second.alias)).toEqual({ models: [first, second], defaultModel: second.alias });
  });

  it('calcula o alias sobre o identificador completo em UTF-8, sem normalizar', () => {
    const model = 'provedor/modelo-ação:thinking';
    const alias = `m_${createHash('sha256').update(model, 'utf8').digest('hex').slice(0, 12)}`;
    expect(parseZeroClawCatalog(JSON.stringify([{ alias, model }]), alias).models).toEqual([{ alias, model }]);
  });

  it.each([undefined, '', ' ', '{', 'null', '{}', '[]', '[null]', '[[]]', '["abc"]', '[{}]',
    JSON.stringify([{ alias: first.alias, model: 1 }]),
    JSON.stringify([{ alias: 1, model: first.model }]),
  ])('recusa JSON ausente, inválido, vazio ou estrutura inválida: %s', (value) => {
    expect(() => parseZeroClawCatalog(value, first.alias)).toThrow(/HABBLAUD_ZEROCLAW_AGENTS/);
  });

  it.each([undefined, '', ' ', 'm_000000000000', first.model, `${first.alias} `])('recusa padrão ausente ou fora do catálogo: %s', (value) => {
    expect(() => parseZeroClawCatalog(raw, value)).toThrow(/HABBLAUD_ZEROCLAW_DEFAULT_AGENT/);
  });

  it.each(['ba7816bf8f01', 'm_BA7816BF8F01', 'm_ba7816bf8f0', 'm_ba7816bf8f010', 'm_000000000000'])('recusa formato ou hash incorreto: %s', (alias) => {
    expect(() => parseZeroClawCatalog(JSON.stringify([{ ...first, alias }]), alias)).toThrow(/HABBLAUD_ZEROCLAW_AGENTS/);
  });

  it('recusa entradas, modelos e aliases repetidos ou em colisão', () => {
    for (const entries of [
      [first, first],
      [first, { alias: second.alias, model: first.model }],
      [first, { alias: first.alias, model: second.model }],
    ]) {
      expect(() => parseZeroClawCatalog(JSON.stringify(entries), first.alias)).toThrow(/HABBLAUD_ZEROCLAW_AGENTS/);
    }
  });

  it.each(['', 'abc def', ' abc', 'abc ', 'abc\tdef', 'abc\ndef', 'abc\rdef', 'abc\u0000def', 'abc\u001fdef',
    'abc\u007fdef', 'abc\u0085def', 'abc\u00a0def', 'abc*', 'abc?', 'abc[12]',
  ])('recusa modelo vazio, espaço, controle ou wildcard: %s', (model) => {
    const alias = `m_${createHash('sha256').update(model, 'utf8').digest('hex').slice(0, 12)}`;
    expect(() => parseZeroClawCatalog(JSON.stringify([{ alias, model }]), alias)).toThrow(/HABBLAUD_ZEROCLAW_AGENTS/);
  });

  it('não inclui conteúdo bruto do ambiente nos erros', () => {
    const secret = 'secret-catalog-content';
    for (const value of [secret, JSON.stringify([{ alias: secret, model: secret }])]) {
      expect(() => parseZeroClawCatalog(value, secret)).toThrow(/^HABBLAUD_ZEROCLAW_AGENTS inválida$/);
    }
  });
});

describe('seleção do modelo ZeroClaw', () => {
  const catalog = parseZeroClawCatalog(raw, second.alias);

  it('usa padrão somente quando o modelo está ausente', () => {
    expect(resolveZeroClawModel(catalog)).toBe(second.alias);
    expect(resolveZeroClawModel(catalog, first.alias)).toBe(first.alias);
    expect(resolveZeroClawModel(catalog, first.model)).toBe(first.alias);
    expect(resolveZeroClawModel(catalog, second.model)).toBe(second.alias);
  });

  it.each(['', 'unknown', 'ABC', 'abc:thinking', 'abc(high)', 'abc ', ' abc', `${first.alias}-other`])('recusa desconhecido e legado não exato sem fallback: %s', (model) => {
    expect(() => resolveZeroClawModel(catalog, model)).toThrow('Modelo ZeroClaw não configurado');
  });
});

import { mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { vi } from 'vitest';
import type { AgentInfo, CastJobInfo, CastMember } from '../../shared/types';
import type { MessageRegistry, SendResult } from '../messages/registry';
import type { ZeroClawDelivery } from '../messages/zeroclaw';
import type { ConnectorEvent, OpenSession, SessionDescriptor, ZeroClawConnector } from '../sources/zeroclaw/connector';
import { CastLauncher } from './launcher';
import { ZeroClawCast } from './zeroclaw';

const historicalId = '10000000-0000-4000-8000-000000000001';
const replacementId = '10000000-0000-4000-8000-000000000002';
const runtimeMember: CastMember = { id: 'ana', name: 'Ana', kind: 'main', look: 'f', seed: 1 };

function deferred() {
  return Promise.withResolvers<void>();
}

async function settleRuntime() {
  // Drain the fixed connector/open/cleanup promise chain without timers or a real daemon.
  for (let i = 0; i < 20; i++) await Promise.resolve();
}

function accepted(result: { job: CastJobInfo } | { status: number; error: string }): CastJobInfo {
  if (!('job' in result)) throw new Error(`Start rejected: ${result.status}: ${result.error}`);
  return result.job;
}

function runtimeSetup(max?: number, dataDir = '/unused-zeroclaw-cast-test') {
  const agents: AgentInfo[] = [];
  const members = new Map<string, CastMember>([
    ['ana', { ...runtimeMember }], ['bia', { ...runtimeMember, id: 'bia', name: 'Bia' }],
  ]);
  const sessions = new Map<string, SessionDescriptor>();
  const history = new Map<string, OpenSession>();
  const listeners = new Set<(event: ConnectorEvent) => void | Promise<void>>();
  const order: string[] = [];
  const controls = {
    now: 0, ready: true, presence: true, busyOnOpen: false,
    openGate: undefined as Promise<void> | undefined,
    stopGate: undefined as Promise<void> | undefined,
    closeGate: undefined as Promise<void> | undefined,
    sendResult: { message: { id: 'message', agentId: 'real-office-agent', status: 'queued', createdAt: 0, updatedAt: 0 } } as SendResult,
  };
  function emit(event: ConnectorEvent) {
    for (const listener of listeners) void listener(event);
  }
  function present(session: SessionDescriptor) {
    const agent: AgentInfo = {
      id: 'real-office-agent', provider: 'zeroclaw', account: 'zeroclaw', kind: 'main', cast: session.cast,
      sessionId: session.sessionId, roomId: session.cwd, name: session.name, look: 'f', role: 'Agente principal',
      status: 'idle', statusSince: 0, recent: [], tasks: [], startedAt: 0, lastEventAt: 0, seed: 1,
      stats: { toolCalls: 0, tokensIn: 0, tokensOut: 0, subagents: 0 },
    };
    const previous = agents.findIndex((entry) => entry.cast === session.cast);
    if (previous >= 0) agents.splice(previous, 1, agent);
    else agents.push(agent);
    return agent;
  }
  function restore(input: Partial<SessionDescriptor> = {}) {
    const session: SessionDescriptor = {
      sessionId: historicalId, agent: first.alias, name: 'Ana', cwd: '/a', cast: 'ana',
      connected: true, busy: false, continuityLost: false, ...input,
    };
    sessions.set(session.sessionId, session);
    history.set(session.sessionId, session);
    return session;
  }
  const connector = {
    openSession: vi.fn(async (input: OpenSession) => {
      order.push('open');
      const descriptor: SessionDescriptor = { ...input, connected: false, busy: false, continuityLost: false };
      sessions.set(input.sessionId, descriptor);
      history.set(input.sessionId, { ...input });
      await controls.openGate;
      descriptor.connected = true;
      descriptor.busy = controls.busyOnOpen;
      sessions.set(input.sessionId, descriptor);
      if (controls.presence) present(descriptor);
      emit({ type: 'session-open', sessionId: input.sessionId, descriptor, resumed: false, continuityLost: false });
    }),
    closeSession: vi.fn(async (id: string) => {
      order.push('close');
      await controls.closeGate;
      sessions.delete(id);
      const index = agents.findIndex((entry) => entry.sessionId === id);
      if (index >= 0) agents.splice(index, 1);
      emit({ type: 'session-close', sessionId: id, intentional: true, replacement: false, code: 1000, reason: '' });
    }),
    metadataOf: (id: string) => history.get(id),
    session: (id: string) => sessions.get(id),
    openSessions: () => [...sessions.values()],
    onEvent: (listener: (event: ConnectorEvent) => void | Promise<void>) => {
      listeners.add(listener);
      return () => { listeners.delete(listener); };
    },
  } satisfies Pick<ZeroClawConnector, 'openSession' | 'closeSession' | 'metadataOf' | 'session' | 'openSessions' | 'onEvent'>;
  const delivery = {
    cancelSession: vi.fn((_id: string, _reason: string) => { order.push('cancel'); }),
    stopSession: vi.fn(async (_id: string) => { order.push('stop'); await controls.stopGate; }),
  } satisfies Pick<ZeroClawDelivery, 'cancelSession' | 'stopSession'>;
  const messages = {
    send: vi.fn((_input: unknown): SendResult => { order.push('send'); return controls.sendResult; }),
  } satisfies Pick<MessageRegistry, 'send'>;
  const account = vi.fn(() => '/must-not-resolve-omp');
  const idle = vi.fn();
  const changed = vi.fn();
  const runtime = new ZeroClawCast({
    catalog: parseZeroClawCatalog(raw, second.alias), home: '/a', connector: () => connector, delivery: () => delivery,
    messages: () => messages, account: () => 'zeroclaw', ready: () => controls.ready,
  });
  const launcher = new CastLauncher({
    dataDir, member: (id) => members.get(id), agents: () => agents, account, changed, idle,
    now: () => controls.now, max, zeroclaw: runtime,
  });
  return { launcher, runtime, controls, agents, members, sessions, history, connector, delivery, messages,
    account, changed, idle, order, emit, restore, present };
}

describe('elenco com runtime ZeroClaw', () => {
  it('reserva antes de abrir, gera UUID novo e usa presença real para entregar uma única vez', async () => {
    const s = runtimeSetup();
    const gate = deferred();
    s.controls.openGate = gate.promise;
    const job = accepted(s.launcher.start('ana', { mode: 'new', room: '/a/', prompt: 'Olá' }));
    expect(job.status).toBe('queued');
    expect(s.launcher.state('ana')).toBe('calling');
    expect(s.connector.openSession).not.toHaveBeenCalled();
    await settleRuntime();
    const session = s.connector.openSession.mock.calls[0][0];
    expect(session).toMatchObject({ agent: second.alias, cwd: '/a', cast: 'ana', name: 'Ana' });
    expect(session.sessionId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(s.launcher.jobs()[0].status).toBe('sent');
    expect(s.messages.send).not.toHaveBeenCalled();
    gate.resolve();
    await settleRuntime();
    expect(s.launcher.jobs()[0].status).toBe('done');
    expect(s.messages.send).toHaveBeenCalledTimes(1);
    expect(s.messages.send).toHaveBeenCalledWith({
      agentId: 'real-office-agent', text: 'Olá', expectedSessionId: session.sessionId,
    });
    s.emit({ type: 'session-open', sessionId: session.sessionId, descriptor: s.sessions.get(session.sessionId)!, resumed: true, continuityLost: false });
    s.launcher.tick();
    expect(s.messages.send).toHaveBeenCalledTimes(1);
    expect(s.members.get('ana')?.last).toBeUndefined();
  });

  it.each([first.alias, first.model])('abre com alias ou identificador legado exato: %s', async (model) => {
    const s = runtimeSetup();
    s.members.set('ana', { ...runtimeMember, model });
    accepted(s.launcher.start('ana', { mode: 'new', room: '/a' }));
    await settleRuntime();
    expect(s.connector.openSession.mock.calls[0][0].agent).toBe(first.alias);
    expect(s.members.get('ana')?.model).toBe(model);
  });

  it.each([
    [undefined, undefined, undefined],
    ['Perfil', undefined, 'Instruções do personagem:\nPerfil'],
    [undefined, 'Pedido', 'Pedido'],
    ['Perfil', 'Pedido', 'Instruções do personagem:\nPerfil\n\nPedido inicial:\nPedido'],
  ])('compõe contexto textual sem inferência quando vazio: %s / %s', async (instructions, prompt, expected) => {
    const s = runtimeSetup();
    s.members.set('ana', { ...runtimeMember, instructions });
    accepted(s.launcher.start('ana', { mode: 'new', room: '/a', prompt }));
    await settleRuntime();
    if (expected === undefined) expect(s.messages.send).not.toHaveBeenCalled();
    else expect(s.messages.send).toHaveBeenCalledWith(expect.objectContaining({ text: expected }));
    expect(s.launcher.jobs()[0].status).toBe('done');
  });

  it('preserva os dois campos de 8.000 caracteres abaixo do limite do registry', async () => {
    const s = runtimeSetup();
    s.members.set('ana', { ...runtimeMember, instructions: 'i'.repeat(8_000) });
    accepted(s.launcher.start('ana', { mode: 'new', room: '/a', prompt: 'p'.repeat(8_000) }));
    await settleRuntime();
    const input = s.messages.send.mock.calls[0][0] as { text: string };
    expect(input.text).toBe(`Instruções do personagem:\n${'i'.repeat(8_000)}\n\nPedido inicial:\n${'p'.repeat(8_000)}`);
    expect(input.text.length).toBeLessThan(20_000);
  });

  it.each([false, true])('retoma UUID e alias original sem repetir perfil; session explícita=%s', async (explicit) => {
    const s = runtimeSetup();
    s.history.set(historicalId, { sessionId: historicalId, agent: first.alias, name: 'Anterior', cwd: '/a/' });
    s.members.set('ana', { ...runtimeMember, instructions: 'Não repetir', last: { '/a': historicalId } });
    accepted(s.launcher.start('ana', { mode: 'resume', room: '/a', prompt: 'Continue', ...(explicit ? { session: historicalId } : {}) }));
    await settleRuntime();
    expect(s.connector.openSession).toHaveBeenCalledWith(expect.objectContaining({ sessionId: historicalId, agent: first.alias }));
    expect(s.messages.send).toHaveBeenCalledWith(expect.objectContaining({ text: 'Continue', expectedSessionId: historicalId }));
    expect(s.members.get('ana')?.last).toEqual({ '/a': historicalId });
  });

  it('retoma no modelo original mesmo se o personagem trocou de modelo depois', async () => {
    const s = runtimeSetup();
    s.history.set(historicalId, { sessionId: historicalId, agent: first.alias, name: 'Ana', cwd: '/a' });
    s.members.set('ana', { ...runtimeMember, model: second.alias, instructions: 'Perfil' });
    accepted(s.launcher.start('ana', { mode: 'resume', room: '/a', session: historicalId }));
    await settleRuntime();
    expect(s.connector.openSession).toHaveBeenCalledWith(expect.objectContaining({ sessionId: historicalId, agent: first.alias }));
    expect(s.messages.send).not.toHaveBeenCalled();
    expect(s.launcher.jobs()[0].status).toBe('done');
  });

  it.each(['omp', 'room', 'alias', 'already-open'])('recusa histórico incompatível antes da conexão: %s', (problem) => {
    const s = runtimeSetup();
    if (problem !== 'omp') s.history.set(historicalId, {
      sessionId: historicalId, agent: problem === 'alias' ? 'm_000000000000' : first.alias,
      name: 'Ana', cwd: problem === 'room' ? '/other' : '/a',
    });
    if (problem === 'already-open') s.restore({ cast: undefined });
    expect(s.launcher.start('ana', { mode: 'resume', room: '/a', session: historicalId })).toMatchObject({ status: 422 });
    expect(s.connector.openSession).not.toHaveBeenCalled();
    expect(s.launcher.jobs()).toEqual([]);
  });

  it('conversa nova usa o modelo escolhido no pedido, acima do modelo do personagem e do padrão', async () => {
    const s = runtimeSetup();
    s.members.set('ana', { ...runtimeMember, model: first.alias });
    accepted(s.launcher.start('ana', { mode: 'new', model: second.alias }));
    accepted(s.launcher.startNew({ model: second.model }));
    await settleRuntime();
    expect(s.connector.openSession).toHaveBeenNthCalledWith(1, expect.objectContaining({ cast: 'ana', agent: second.alias }));
    expect(s.connector.openSession).toHaveBeenNthCalledWith(2, expect.objectContaining({ agent: second.alias }));
  });

  it('recusa modelo fora do catálogo, não textual ou junto de uma retomada', () => {
    const s = runtimeSetup();
    s.history.set(historicalId, { sessionId: historicalId, agent: first.alias, name: 'Ana', cwd: '/a' });
    for (const result of [
      s.launcher.start('ana', { mode: 'new', model: 'm_000000000000' }),
      s.launcher.start('ana', { mode: 'new', model: 7 }),
      s.launcher.start('ana', { mode: 'resume', room: '/a', session: historicalId, model: first.alias }),
      s.launcher.startNew({ model: 'outro' }),
      s.launcher.startNew({ room: '/a', session: historicalId, model: first.alias }),
    ]) expect(result).toMatchObject({ status: 422 });
    expect(s.connector.openSession).not.toHaveBeenCalled();
  });

  it('recusa membro, prompt, instruções e modelo inválidos antes de conectar', () => {
    const s = runtimeSetup();
    for (const [id, body] of [
      ['missing', { mode: 'new', room: '/a' }],
      ['ana', { mode: 'new', room: '/a', prompt: 'p'.repeat(8_001) }],
      ['ana', { mode: 'new', room: '/a', prompt: '--flag' }],
      ['ana', { mode: 'resume', room: '/a' }],
    ] as const) expect(s.launcher.start(id, body)).toMatchObject({ status: 422 });
    s.members.set('ana', { ...runtimeMember, instructions: 'i'.repeat(8_001) });
    expect(s.launcher.start('ana', { mode: 'new', room: '/a' })).toMatchObject({ status: 422 });
    s.members.set('ana', { ...runtimeMember, model: `${first.model}:thinking` });
    expect(s.launcher.start('ana', { mode: 'new', room: '/a' })).toMatchObject({ status: 422 });
    expect(s.connector.openSession).not.toHaveBeenCalled();
  });

  it('aceita somente conta ZeroClaw explícita, ignora conta OMP legada e não grava arquivo de instruções', async () => {
    const directory = mkdtempSync(join(tmpdir(), 'cast-zeroclaw-'));
    try {
      const s = runtimeSetup(undefined, directory);
      s.members.set('ana', { ...runtimeMember, account: '.omp-old', instructions: 'Perfil' });
      for (const account of ['.omp', '', 5]) {
        expect(s.launcher.start('ana', { mode: 'new', room: '/a', account })).toMatchObject({ status: 422 });
      }
      expect(s.launcher.startNew({ room: '/a', account: '.omp' })).toMatchObject({ status: 422 });
      accepted(s.launcher.start('ana', { mode: 'new', room: '/a', account: 'zeroclaw' }));
      await settleRuntime();
      expect(s.account).not.toHaveBeenCalled();
      expect(s.launcher.rolesDir()).toBeUndefined();
      expect(s.launcher.setRoles({ roles: { task: 'a/b' } })).toMatchObject({ status: 422 });
      expect(s.account).not.toHaveBeenCalled();
      expect(readdirSync(directory)).toEqual([]);
    } finally { rmSync(directory, { recursive: true, force: true }); }
  });

  it('indisponibilidade ZeroClaw não depende de bridge nem cai para OMP', () => {
    const s = runtimeSetup();
    s.controls.ready = false;
    s.launcher.poll();
    expect(s.launcher.available()).toBe(false);
    expect(s.launcher.start('ana', { mode: 'new', room: '/a' })).toMatchObject({ status: 503, error: expect.stringContaining('ZeroClaw') });
    expect(s.launcher.startNew({ room: '/a' })).toMatchObject({ status: 503 });
    expect(s.account).not.toHaveBeenCalled();
    expect(s.connector.openSession).not.toHaveBeenCalled();
  });

  it('registry rejeitado falha job, cancela e fecha sem apagar histórico', async () => {
    const s = runtimeSetup();
    s.controls.sendResult = { error: 'unavailable', reason: 'Fila recusada' };
    accepted(s.launcher.start('ana', { mode: 'new', room: '/a', prompt: 'Pedido' }));
    await settleRuntime();
    const id = s.connector.openSession.mock.calls[0][0].sessionId;
    expect(s.launcher.jobs()[0]).toMatchObject({ status: 'failed', error: 'Fila recusada' });
    expect(s.order).toEqual(['open', 'send', 'cancel', 'close']);
    expect(s.launcher.isOpened('ana')).toBe(false);
    expect(s.history.has(id)).toBe(true);
  });

  it('falha de abertura preserva last e limpa a intenção sem enviar mensagem', async () => {
    const s = runtimeSetup();
    const gate = deferred();
    s.controls.openGate = gate.promise;
    s.history.set(historicalId, { sessionId: historicalId, agent: first.alias, name: 'Ana', cwd: '/a' });
    s.members.set('ana', { ...runtimeMember, last: { '/a': historicalId } });
    accepted(s.launcher.start('ana', { mode: 'resume', room: '/a', prompt: 'Continue' }));
    await settleRuntime();
    gate.reject(new Error('Gateway recusou abertura'));
    await settleRuntime();
    expect(s.launcher.jobs()[0]).toMatchObject({ status: 'failed', error: 'Gateway recusou abertura' });
    expect(s.members.get('ana')?.last).toEqual({ '/a': historicalId });
    expect(s.messages.send).not.toHaveBeenCalled();
    expect(s.sessions.size).toBe(0);
  });

  it('timeout sem presença fecha conexão e nunca entrega prompt', async () => {
    const s = runtimeSetup(1);
    s.controls.presence = false;
    accepted(s.launcher.start('ana', { mode: 'new', room: '/a', prompt: 'Não enviar' }));
    await settleRuntime();
    expect(s.launcher.jobs()[0].status).toBe('waiting');
    expect(s.launcher.startNew({ room: '/a' })).toMatchObject({ status: 409 });
    s.controls.now = 60_000;
    s.launcher.tick();
    await settleRuntime();
    expect(s.launcher.jobs()[0]).toMatchObject({ status: 'failed', error: expect.stringContaining('prazo') });
    expect(s.messages.send).not.toHaveBeenCalled();
    expect(s.sessions.size).toBe(0);
  });

  it.each([false, true])('abertura tardia após 60s limpa presença sem prompt; tick antecipado=%s', async (tickFirst) => {
    const s = runtimeSetup(1);
    const gate = deferred();
    s.controls.openGate = gate.promise;
    accepted(s.launcher.start('ana', { mode: 'new', room: '/a', prompt: 'Não enviar' }));
    await settleRuntime();
    s.controls.now = 60_001;
    if (tickFirst) { s.launcher.tick(); await settleRuntime(); }
    gate.resolve();
    await settleRuntime();
    expect(s.launcher.jobs()[0].status).toBe('failed');
    expect(s.messages.send).not.toHaveBeenCalled();
    expect(s.sessions.size).toBe(0);
    expect(s.agents).toEqual([]);
    expect(s.launcher.isOpened('ana')).toBe(false);
  });

  it('não deixa host poll/ack concluir jobs nem reconstruir abertos ZeroClaw', async () => {
    const s = runtimeSetup();
    const gate = deferred();
    s.controls.openGate = gate.promise;
    const job = accepted(s.launcher.start('ana', { mode: 'new', room: '/a' }));
    expect(s.launcher.poll({ open: ['bia'], roles: { task: 'a/b' } })).toEqual([]);
    s.launcher.ack({ results: [{ id: job.id, ok: true }] });
    expect(s.launcher.jobs()[0].status).toBe('queued');
    expect(s.launcher.isOpened('bia')).toBe(false);
    expect(s.launcher.roles()).toEqual({ pending: false });
    await settleRuntime();
    s.launcher.ack({ results: [{ id: job.id, ok: false, error: 'host error' }] });
    expect(s.launcher.jobs()[0].status).toBe('sent');
    gate.resolve();
    await settleRuntime();
    expect(s.launcher.jobs()[0].status).toBe('done');
  });

  it('startNew usa novo-…, alias padrão, nenhuma conta OMP e a mesma cota', async () => {
    const s = runtimeSetup(1);
    const job = accepted(s.launcher.startNew({ room: '/a' }));
    expect(job.cast).toMatch(/^novo-[0-9a-f]{8}$/);
    expect(s.launcher.start('ana', { mode: 'new', room: '/a' })).toMatchObject({ status: 409 });
    await settleRuntime();
    expect(s.connector.openSession).toHaveBeenCalledWith(expect.objectContaining({ cast: job.cast, agent: second.alias, name: 'Novo' }));
    expect(s.members.size).toBe(2);
    expect(s.account).not.toHaveBeenCalled();
    expect(s.messages.send).not.toHaveBeenCalled();
    expect(s.launcher.dismiss(s.agents[0])).toHaveProperty('job');
    await settleRuntime();
    expect(s.launcher.isOpened(job.cast)).toBe(false);
  });

  it('startNew retoma UUID ZeroClaw válido com alias original', async () => {
    const s = runtimeSetup();
    s.history.set(historicalId, { sessionId: historicalId, agent: first.alias, name: 'Anterior', cwd: '/a' });
    accepted(s.launcher.startNew({ room: '/a', session: historicalId, prompt: 'Continue', account: 'zeroclaw' }));
    await settleRuntime();
    expect(s.connector.openSession).toHaveBeenCalledWith(expect.objectContaining({ sessionId: historicalId, agent: first.alias, cast: expect.stringMatching(/^novo-/) }));
  });

  it('duas retomadas concorrentes não reservam o mesmo UUID para personagens diferentes', async () => {
    const s = runtimeSetup();
    s.history.set(historicalId, { sessionId: historicalId, agent: first.alias, name: 'Ana', cwd: '/a' });
    accepted(s.launcher.start('ana', { mode: 'resume', room: '/a', session: historicalId }));
    expect(s.launcher.start('bia', { mode: 'resume', room: '/a', session: historicalId })).toMatchObject({ status: 409 });
    await settleRuntime();
    expect(s.connector.openSession).toHaveBeenCalledTimes(1);
  });

  it('cota inclui queued, abertura e desconexão; duplicidade não abre outra sessão', async () => {
    const s = runtimeSetup(1);
    const gate = deferred();
    s.controls.openGate = gate.promise;
    accepted(s.launcher.start('ana', { mode: 'new', room: '/a' }));
    expect(s.launcher.start('bia', { mode: 'new', room: '/a' })).toMatchObject({ status: 409 });
    expect(s.launcher.start('ana', { mode: 'new', room: '/a' })).toMatchObject({ status: 409 });
    await settleRuntime();
    expect(s.launcher.startNew({ room: '/a' })).toMatchObject({ status: 409 });
    gate.resolve();
    await settleRuntime();
    const session = [...s.sessions.values()][0];
    session.connected = false;
    s.agents[0].status = 'offline';
    s.emit({ type: 'session-close', sessionId: session.sessionId, intentional: false, replacement: false, code: 1001, reason: 'reconnect' });
    expect(s.launcher.isOpened('ana')).toBe(true);
    expect(s.launcher.startNew({ room: '/a' })).toMatchObject({ status: 409 });
    expect(s.connector.openSession).toHaveBeenCalledTimes(1);
  });

  it('sem limite aceita personagens concorrentes', async () => {
    const s = runtimeSetup();
    accepted(s.launcher.start('ana', { mode: 'new', room: '/a' }));
    accepted(s.launcher.start('bia', { mode: 'new', room: '/a' }));
    accepted(s.launcher.startNew({ room: '/a' }));
    await settleRuntime();
    expect(s.connector.openSession).toHaveBeenCalledTimes(3);
  });
});

describe('dispensa e continuidade ZeroClaw', () => {
  it.each([false, true])('cancela, para somente busy e mantém vaga até close confirmado: busy=%s', async (busy) => {
    const s = runtimeSetup(1);
    const session = s.restore({ busy });
    const agent = s.present(session);
    const stop = deferred();
    const close = deferred();
    if (busy) s.controls.stopGate = stop.promise;
    s.controls.closeGate = close.promise;
    const job = accepted(s.launcher.dismiss(agent));
    expect(s.launcher.isClosing(session.sessionId)).toBe(true);
    expect(s.launcher.dismiss(agent)).toMatchObject({ status: 409 });
    expect(s.launcher.startNew({ room: '/a' })).toMatchObject({ status: 409 });
    await settleRuntime();
    if (busy) {
      expect(s.order).toEqual(['cancel', 'stop']);
      expect(s.connector.closeSession).not.toHaveBeenCalled();
      stop.resolve();
      await settleRuntime();
    } else expect(s.delivery.stopSession).not.toHaveBeenCalled();
    expect(s.order).toEqual(busy ? ['cancel', 'stop', 'close'] : ['cancel', 'close']);
    expect(s.launcher.jobs().find((entry) => entry.id === job.id)?.status).toBe('sent');
    expect(s.launcher.isOpened('ana')).toBe(true);
    expect(s.launcher.startNew({ room: '/a' })).toMatchObject({ status: 409 });
    close.resolve();
    await settleRuntime();
    expect(s.launcher.jobs().find((entry) => entry.id === job.id)?.status).toBe('done');
    expect(s.launcher.isClosing(session.sessionId)).toBe(false);
    expect(s.launcher.isOpened('ana')).toBe(false);
    expect(s.history.has(session.sessionId)).toBe(true);
    accepted(s.launcher.startNew({ room: '/a' }));
    await settleRuntime();
  });

  it('stop já confirmado sem frame (no_active_response) autoriza close imediato', async () => {
    const s = runtimeSetup();
    const session = s.restore({ busy: true, continuityLost: true });
    const agent = s.present(session);
    accepted(s.launcher.dismiss(agent));
    await settleRuntime();
    expect(s.order).toEqual(['cancel', 'stop', 'close']);
    expect(s.launcher.jobs()[0].status).toBe('done');
    expect(s.launcher.isOpened('ana')).toBe(false);
  });

  it('continuidade perdida exige stop mesmo quando busy=false', async () => {
    const s = runtimeSetup();
    const session = s.restore({ busy: false, continuityLost: true });
    accepted(s.launcher.dismiss(s.present(session)));
    await settleRuntime();
    expect(s.order).toEqual(['cancel', 'stop', 'close']);
  });

  it('falha de stop preserva intenção e vaga, remove trava e permite nova dispensa', async () => {
    const s = runtimeSetup(1);
    const session = s.restore({ busy: true });
    const agent = s.present(session);
    const stop = deferred();
    s.controls.stopGate = stop.promise;
    accepted(s.launcher.dismiss(agent));
    await settleRuntime();
    stop.reject(new Error('Abort não confirmado'));
    await settleRuntime();
    expect(s.launcher.jobs()[0]).toMatchObject({ status: 'failed', error: 'Abort não confirmado' });
    expect(s.connector.closeSession).not.toHaveBeenCalled();
    expect(s.launcher.isOpened('ana')).toBe(true);
    expect(s.launcher.isClosing(session.sessionId)).toBe(false);
    expect(s.launcher.startNew({ room: '/a' })).toMatchObject({ status: 409 });
    s.controls.stopGate = undefined;
    accepted(s.launcher.dismiss(agent));
    await settleRuntime();
    expect(s.launcher.jobs()[1].status).toBe('done');
  });

  it('recusa dispensa de sessão externa, outro UUID e provider OMP', () => {
    const s = runtimeSetup();
    const session = s.restore();
    const agent = s.present(session);
    expect(s.launcher.dismiss({ ...agent, cast: 'external' })).toMatchObject({ status: 404 });
    expect(s.launcher.dismiss({ ...agent, sessionId: replacementId })).toMatchObject({ status: 404 });
    expect(s.launcher.dismiss({ ...agent, provider: 'omp' })).toMatchObject({ status: 404 });
    expect(s.launcher.dismiss(undefined)).toMatchObject({ status: 404 });
    expect(s.delivery.cancelSession).not.toHaveBeenCalled();
    expect(s.connector.closeSession).not.toHaveBeenCalled();
  });

  it('rehidrata intenção desconectada sem presença e mantém limite após poll vazio', () => {
    const s = runtimeSetup(1);
    s.restore({ connected: false, busy: true, continuityLost: true });
    s.restore({ sessionId: replacementId, cast: undefined });
    expect(s.launcher.isOpened('ana')).toBe(true);
    expect(s.launcher.poll({ open: [] })).toEqual([]);
    s.launcher.tick();
    expect(s.launcher.isOpened('ana')).toBe(true);
    expect(s.launcher.startNew({ room: '/a' })).toMatchObject({ status: 409 });
    expect(s.delivery.stopSession).not.toHaveBeenCalled();
    expect(s.idle).not.toHaveBeenCalled();
  });

  it('/new troca UUID preservando uma vaga, aceita dispensa somente do UUID novo', async () => {
    const s = runtimeSetup(2);
    const old = s.restore();
    const oldAgent = s.present(old);
    expect(s.launcher.isOpened('ana')).toBe(true);
    s.sessions.delete(old.sessionId);
    s.emit({ type: 'session-close', sessionId: old.sessionId, intentional: true, replacement: true, code: 1000, reason: 'new' });
    expect(s.launcher.isOpened('ana')).toBe(true);
    const replacement = s.restore({ sessionId: replacementId });
    s.present(replacement);
    s.emit({ type: 'session-open', sessionId: replacementId, descriptor: replacement, resumed: false, continuityLost: false });
    expect(s.launcher.dismiss(oldAgent)).toMatchObject({ status: 404 });
    accepted(s.launcher.start('bia', { mode: 'new', room: '/a' }));
    expect(s.launcher.startNew({ room: '/a' })).toMatchObject({ status: 409 });
    await settleRuntime();
    accepted(s.launcher.dismiss(s.agents.find((agent) => agent.cast === 'ana')));
    await settleRuntime();
    expect(s.connector.closeSession).toHaveBeenCalledWith(replacementId);
    expect(s.launcher.isOpened('ana')).toBe(false);
  });

  it.each([undefined, 5])('ociosidade padrão/customizada dispara uma única notificação: %s minutos', async (idleMinutes) => {
    const s = runtimeSetup();
    s.members.set('ana', { ...runtimeMember, idleMinutes });
    const session = s.restore();
    s.present(session);
    const threshold = (idleMinutes ?? 120) * 60_000;
    s.controls.now = threshold - 1;
    s.launcher.tick();
    expect(s.idle).not.toHaveBeenCalled();
    s.controls.now = threshold;
    s.launcher.tick();
    s.launcher.tick();
    expect(s.idle).toHaveBeenCalledTimes(1);
    expect(s.launcher.state('ana')).toBe('dismissing');
    await settleRuntime();
    s.launcher.tick();
    expect(s.idle).toHaveBeenCalledTimes(1);
    expect(s.order).toEqual(['cancel', 'close']);
  });

  it.each(['disabled', 'working', 'waiting', 'shell', 'busy', 'continuityLost', 'disconnected'])('não dispensa por ociosidade em %s', async (condition) => {
    const s = runtimeSetup();
    if (condition === 'disabled') s.members.set('ana', { ...runtimeMember, idleMinutes: 0 });
    const session = s.restore({
      busy: condition === 'busy', continuityLost: condition === 'continuityLost', connected: condition !== 'disconnected',
    });
    const agent = s.present(session);
    if (condition === 'working' || condition === 'waiting' || condition === 'shell') agent.status = condition;
    s.controls.now = 24 * 60 * 60_000;
    s.launcher.tick();
    await settleRuntime();
    expect(s.launcher.jobs()).toEqual([]);
    expect(s.idle).not.toHaveBeenCalled();
    expect(s.connector.closeSession).not.toHaveBeenCalled();
  });
});

describe('corridas de cleanup ZeroClaw', () => {
  it('abertura tardia com turno ativo cancela, aguarda stop e só então fecha', async () => {
    const s = runtimeSetup(1);
    const open = deferred();
    const stop = deferred();
    s.controls.openGate = open.promise;
    s.controls.stopGate = stop.promise;
    s.controls.busyOnOpen = true;
    accepted(s.launcher.start('ana', { mode: 'new', room: '/a', prompt: 'Não enviar' }));
    await settleRuntime();
    s.controls.now = 60_001;
    open.resolve();
    await settleRuntime();
    expect(s.launcher.jobs()[0].status).toBe('failed');
    expect(s.messages.send).not.toHaveBeenCalled();
    expect(s.order).toEqual(['open', 'cancel', 'stop']);
    expect(s.launcher.isClosing(s.connector.openSession.mock.calls[0][0].sessionId)).toBe(true);
    expect(s.connector.closeSession).not.toHaveBeenCalled();
    expect(s.launcher.startNew({ room: '/a' })).toMatchObject({ status: 409 });
    stop.resolve();
    await settleRuntime();
    expect(s.order).toEqual(['open', 'cancel', 'stop', 'close']);
    expect(s.launcher.isOpened('ana')).toBe(false);
  });

  it('timeout mantém tentativa pendente além de KEEP_MS e limpa resolução tardia sem prompt', async () => {
    const s = runtimeSetup(1);
    const open = deferred();
    s.controls.openGate = open.promise;
    accepted(s.launcher.start('ana', { mode: 'new', room: '/a', prompt: 'Não enviar' }));
    await settleRuntime();
    s.controls.now = 60_000;
    s.launcher.tick();
    await settleRuntime();
    s.controls.now = 20 * 60_000;
    s.launcher.tick();
    expect(s.launcher.jobs()[0]?.status).toBe('failed');
    open.resolve();
    await settleRuntime();
    expect(s.messages.send).not.toHaveBeenCalled();
    expect(s.sessions.size).toBe(0);
    expect(s.agents).toEqual([]);
    expect(s.launcher.isOpened('ana')).toBe(false);
  });

  it('falha de cleanup após registry rejeitado preserva vaga e remove trava de envio', async () => {
    const s = runtimeSetup(1);
    const stop = deferred();
    s.controls.busyOnOpen = true;
    s.controls.stopGate = stop.promise;
    s.controls.sendResult = { error: 'unavailable', reason: 'Registry recusou' };
    accepted(s.launcher.start('ana', { mode: 'new', room: '/a', prompt: 'Pedido' }));
    await settleRuntime();
    const id = s.connector.openSession.mock.calls[0][0].sessionId;
    expect(s.launcher.isClosing(id)).toBe(true);
    stop.reject(new Error('Stop recusou'));
    await settleRuntime();
    expect(s.launcher.jobs()[0]).toMatchObject({ status: 'failed', error: expect.stringContaining('Stop recusou') });
    expect(s.connector.closeSession).not.toHaveBeenCalled();
    expect(s.launcher.isOpened('ana')).toBe(true);
    expect(s.launcher.isClosing(id)).toBe(false);
    expect(s.launcher.startNew({ room: '/a' })).toMatchObject({ status: 409 });
  });
});
