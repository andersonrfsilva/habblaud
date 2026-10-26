import { afterEach, describe, expect, it, vi } from 'vitest';
import type { OfficeSnapshot } from '../../shared/types';
import { setQuiet } from '../log';
import { NameStore } from '../model/names';
import { Office } from '../model/office';
import { Hub } from './sse';

setQuiet(true);

let hub: Hub | null = null;
afterEach(() => {
  hub?.stop();
  hub = null;
});

function setup() {
  const flag = { broken: true };
  const now = Date.now();
  const office = new Office({
    names: new NameStore(null),
    version: 't',
    startedAt: now,
    accounts: (s) => [{ id: 'acc', short: 'C', name: 'Conta C', color: '#f08a3c', configDir: '/x', sessions: s.get('acc') ?? 0, usageStatus: 'disabled' }],
    sources: () => [],
    accountName: () => 'Conta C',
    permissions: () => {
      if (flag.broken) throw new Error('falhou');
      return new Map();
    },
  });
  hub = new Hub(office, { throttleMs: 5, retryMs: 20 });
  return { office, hub, flag, now };
}

describe('Hub com snapshot que falha', () => {
  it('erro no snapshot não escapa do timer e a próxima tentativa transmite sozinha', async () => {
    const { office, hub, flag } = setup();
    const snapshots: OfficeSnapshot[] = [];
    hub.onSnapshot((s) => snapshots.push(s));
    hub.start();
    office.markDirty();
    await new Promise((r) => setTimeout(r, 60));
    expect(snapshots).toHaveLength(0);
    flag.broken = false;
    await vi.waitFor(() => expect(snapshots).toHaveLength(1));
  });

  it('falha não descarta feed nem avisos pendentes', async () => {
    const { office, hub, flag, now } = setup();
    const chunks: string[] = [];
    const fake = {
      destroyed: false,
      writableEnded: false,
      writableLength: 0,
      write: (c: string) => {
        chunks.push(c);
        return true;
      },
      on: () => {},
      end: () => {},
    };
    (hub as unknown as { clients: Set<unknown> }).clients.add(fake);
    hub.start();
    office.addMain({ id: 'acc:1', account: 'acc', sessionId: 's1', cwd: '/p/loja/', role: 'Agente principal', startedAt: now, status: 'working' });
    office.addActivity('acc:1', { id: 'a1', at: now, kind: 'read', icon: '📖', text: 'Lendo x' }, true);
    await new Promise((r) => setTimeout(r, 60));
    expect(chunks.join('')).not.toContain('event: feed');
    flag.broken = false;
    await vi.waitFor(() => expect(chunks.some((c) => c.includes('event: feed') && c.includes('"a1"'))).toBe(true));
  });

  it('current() continua propagando o erro', () => {
    const { office, hub, flag } = setup();
    office.markDirty();
    expect(() => hub.current()).toThrow('falhou');
    flag.broken = false;
    expect(typeof hub.current().rev).toBe('number');
    expect(() => hub.current()).not.toThrow();
  });
});
