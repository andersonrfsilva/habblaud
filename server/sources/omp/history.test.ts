// Histórico do terminal do omp: listagem das sessões recentes (sem subagentes) e a sessão resolvida com segurança.
import { mkdirSync, utimesSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { setQuiet } from '../../log';
import { symlinkOrSkip, tempDir } from '../../test/fixtures';
import { OmpHistory } from './history';

setQuiet(true);

let cleanups: Array<() => void> = [];
afterEach(() => {
  for (const c of cleanups) c();
  cleanups = [];
});

const A = '01a12d9e-b033-7000-a6b5-5c32fc303cf1';
const B = '01a12d9e-b033-7000-a6b5-5c32fc303cf2';
const OLD = '01a12d9e-b033-7000-a6b5-5c32fc303cf3';

function setup() {
  const tmp = tempDir();
  cleanups.push(tmp.cleanup);
  const dir = join(tmp.dir, 'agent');
  const now = Date.now();
  const open = new Set<string>();
  const history = new OmpHistory({ accounts: () => [{ id: '.omp', dir }], openAgentOf: (acc, sid) => (open.has(sid) ? `omp:${sid}` : undefined), now: () => now });
  /** Sessão principal em sessions/<projeto>/; `parent` grava um cabeçalho de subagente. */
  const session = (id: string, o: { title?: string; cwd?: string; at?: number; mtime?: number; parent?: string; prompt?: string } = {}) => {
    const proj = join(dir, 'sessions', '-proj');
    mkdirSync(proj, { recursive: true });
    const at = o.at ?? now - 60_000;
    const path = join(proj, `${new Date(at).toISOString().replace(/[:.]/g, '-')}_${id}.jsonl`);
    const header = { type: 'session', version: 3, id, timestamp: new Date(at).toISOString(), cwd: o.cwd ?? '/projetos/loja', ...(o.parent ? { parentSession: o.parent } : {}) };
    const msg = o.prompt ? `${JSON.stringify({ type: 'message', message: { role: 'user', content: [{ type: 'text', text: o.prompt }] } })}\n` : '';
    writeFileSync(path, `${JSON.stringify({ type: 'title', v: 1, title: o.title ?? '' })}\n${JSON.stringify(header)}\n${msg}`);
    if (o.mtime) utimesSync(path, o.mtime / 1000, o.mtime / 1000);
    return path;
  };
  return { tmp, dir, history, now, open, session };
}

describe('histórico do omp', () => {
  it('lista as sessões dos últimos 7 dias com título, projeto e início; sem subagentes nem antigas', async () => {
    const { history, now, open, session } = setup();
    const a = session(A, { title: '🏨 HABB: auditoria', cwd: '/home/u/habblaud', at: now - 3_600_000 });
    session(B, { mtime: now - 120_000, prompt: 'arrume o checkout' });
    session(OLD, { mtime: now - 10 * 86_400_000 });
    // Subagente: pasta irmã da sessão principal (e cabeçalho com parentSession).
    mkdirSync(a.replace(/\.jsonl$/, ''), { recursive: true });
    writeFileSync(join(a.replace(/\.jsonl$/, ''), 'Scout.jsonl'), '{}\n');
    open.add(A);
    const list = await history.list();
    expect(list.map((s) => s.sessionId)).toEqual([A, B]);
    expect(list[0]).toMatchObject({ account: '.omp', provider: 'omp', title: '🏨 HABB: auditoria', project: '/home/u/habblaud', projectDir: '-home-u-habblaud', firstAt: now - 3_600_000, open: true, agentId: `omp:${A}` });
    expect(list[1]).toMatchObject({ open: false });
    expect(list[1]).toMatchObject({ title: 'arrume o checkout' });
  });

  it('fora da lista: sessão fechada em pasta temporária ou sem instrução sua; aberta continua', async () => {
    const { history, open, session } = setup();
    session(A, { cwd: '/tmp/probe1', prompt: 'teste' });
    session(B);
    session(OLD, { cwd: '/tmp/probe2', prompt: 'teste' });
    open.add(OLD);
    expect((await history.list()).map((s) => s.sessionId)).toEqual([OLD]);
  });

  it('fora da lista: arquivo sem cabeçalho ainda e cabeçalho de subagente', async () => {
    const { dir, history, session } = setup();
    session(A, { parent: '/x/pai.jsonl' });
    writeFileSync(join(dir, 'sessions', '-proj', `2026-10-24T10-00-00-000Z_${B}.jsonl`), '{"type":"title","v":1,"title":""}\n');
    expect(await history.list()).toEqual([]);
  });

  it('resolve: id inválido 400, conta ou sessão desconhecida 404, e o arquivo da sessão (id em qualquer caixa)', async () => {
    const { history, session } = setup();
    const path = session(A);
    expect(history.resolve('.omp', '../../etc/passwd')).toMatchObject({ status: 400 });
    expect(history.resolve('.outra', A)).toMatchObject({ status: 404 });
    expect(history.resolve('.omp', B)).toMatchObject({ status: 404 });
    // Sessão criada depois da última listagem: o resolve varre de novo.
    const r = history.resolve('.omp', A.toUpperCase());
    expect(r).toMatchObject({ path });
  });

  it('resolve: link para fora da agent dir não serve', ({ skip }) => {
    const { tmp, dir, history } = setup();
    const outside = join(tmp.dir, 'fora.jsonl');
    writeFileSync(outside, '{}\n');
    mkdirSync(join(dir, 'sessions', '-proj'), { recursive: true });
    symlinkOrSkip(skip, outside, join(dir, 'sessions', '-proj', `2026-10-24T10-00-00-000Z_${A}.jsonl`));
    expect(history.resolve('.omp', A)).toMatchObject({ status: 404 });
  });
});
