import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AcpEvent } from './acp';
import { AntigravityPermissions } from './permissions';
import { NameStore } from '../model/names';
import { Office } from '../model/office';
import { PermissionRegistry } from '../permissions/registry';

const SID = 'permission-session';
const cleanups: (() => void)[] = [];
afterEach(() => { for (const cleanup of cleanups.splice(0)) cleanup(); vi.useRealTimers(); });
function setup(initialViewers = 1) {
  let viewers = initialViewers;
  const office = new Office({ names: new NameStore(null), version: 'test', startedAt: 0, accounts: () => [], sources: () => [], accountName: () => undefined });
  const id = `antigravity:${SID}`;
  office.addMain({ id, provider: 'antigravity', account: 'antigravity', sessionId: SID, cwd: '/tmp', role: 'Agente principal', startedAt: 0, status: 'working' });
  const registry = new PermissionRegistry({ office, viewers: () => viewers });
  const listeners = new Set<(event: AcpEvent) => void>();
  const answerPermission = vi.fn(() => true);
  const client = { onEvent(listener: (event: AcpEvent) => void) { listeners.add(listener); return () => { listeners.delete(listener); }; }, session: () => ({ sessionId: SID, cwd: '/tmp', busy: true }), answerPermission };
  const bridge = new AntigravityPermissions({ client, registry });
  cleanups.push(() => { bridge.stop(); registry.stop(); });
  const emit = (event: AcpEvent) => { for (const listener of listeners) listener(event); };
  const request = () => emit({ type: 'permission', sessionId: SID, requestId: 7, toolCall: { name: 'run_command', title: 'echo ok', rawInput: { CommandLine: 'echo ok' } }, options: [{ optionId: 'different', kind: 'allow_once', name: 'Allow' }] });
  return { id, registry, request, emit, answerPermission, setViewers: (value: number) => { viewers = value; } };
}
describe('permissões Antigravity', () => {
  it.each([['allow', 'allow_once'], ['deny', 'reject_once']] as const)('encaminha somente a decisão humana %s', async (behavior, kind) => {
    const s = setup(); s.request();
    const request = s.registry.snapshot().get(s.id)!;
    expect(request).toMatchObject({ provider: 'antigravity', title: expect.stringContaining('echo ok') });
    expect(s.answerPermission).not.toHaveBeenCalled();
    expect(s.registry.decide(request.id, { behavior, interrupt: true })).toBe('unsupported');
    expect(s.registry.decide(request.id, { behavior, suggestion: 0 })).toBe('unsupported');
    expect(s.registry.decide(request.id, { behavior })).toBe('ok');
    await Promise.resolve();
    expect(s.answerPermission).toHaveBeenCalledExactlyOnceWith(SID, 7, kind);
  });
  it('sem página espera e tenta registrar novamente, sem aprovar sozinho', async () => {
    vi.useFakeTimers();
    const s = setup(0); s.request();
    expect(s.registry.snapshot().size).toBe(0);
    await vi.advanceTimersByTimeAsync(6000);
    expect(s.answerPermission).not.toHaveBeenCalled();
    s.setViewers(1);
    await vi.advanceTimersByTimeAsync(3000);
    expect(s.registry.snapshot().has(s.id)).toBe(true);
    await vi.advanceTimersByTimeAsync(50_000);
    expect(s.answerPermission).not.toHaveBeenCalled();
  });
  it('pedido que desaparece retira a espera sem responder ao agente', async () => {
    const s = setup(); s.request();
    s.emit({ type: 'permission-gone', sessionId: SID, requestId: 7 });
    await Promise.resolve();
    expect(s.registry.snapshot().size).toBe(0);
    expect(s.answerPermission).not.toHaveBeenCalled();
  });
  it('pedido liberado volta a registrar sem conceder permissão', async () => {
    vi.useFakeTimers();
    const s = setup(); s.request();
    const previous = s.registry.snapshot().get(s.id)!;
    s.registry.decide(previous.id, { behavior: 'terminal' });
    await vi.advanceTimersByTimeAsync(3000);
    expect(s.registry.snapshot().get(s.id)?.id).not.toBe(previous.id);
    expect(s.registry.snapshot().has(s.id)).toBe(true);
    expect(s.answerPermission).not.toHaveBeenCalled();
  });
});
