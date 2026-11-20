// Terminal do omp com as fixtures reais (texto trocado por `…`): prompts, texto, ferramentas, resultados e erros.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { TerminalEntry } from '../../../shared/types';
import { createOmpTerminalParser, ompToolView } from './terminal';

const FIX = join(import.meta.dirname, 'fixtures');

function entries(name: string): TerminalEntry[] {
  const p = createOmpTerminalParser();
  return readFileSync(join(FIX, name), 'utf8')
    .split('\n')
    .filter(Boolean)
    .flatMap((l) => p.push(l));
}

describe('terminal do omp', () => {
  it('sessão curta: prompt, ferramenta, resultado e resposta, em ordem', () => {
    const e = entries('main-clean-exit.jsonl');
    const kinds = e.map((x) => x.kind);
    expect(kinds[0]).toBe('user');
    expect(kinds).toContain('tool');
    expect(kinds).toContain('result');
    expect(kinds).toContain('assistant');
    const tool = e.find((x): x is Extract<TerminalEntry, { kind: 'tool' }> => x.kind === 'tool')!;
    expect(tool).toMatchObject({ tool: 'Bash', title: 'Bash(git status --short)', inputKind: 'command' });
    const result = e.find((x): x is Extract<TerminalEntry, { kind: 'result' }> => x.kind === 'result')!;
    expect(result.toolUseId).toBe(tool.id);
    expect(result.error).toBeUndefined();
    expect(new Set(e.map((x) => x.id)).size).toBe(e.length);
  });

  it('cada ferramenta ganha o título do Claude Code', () => {
    const titles = Object.fromEntries(entries('main-tools.jsonl').filter((x): x is Extract<TerminalEntry, { kind: 'tool' }> => x.kind === 'tool').map((x) => [x.tool, x.title]));
    expect(titles.Read).toBe('Read(src/app.ts)');
    expect(titles.Grep).toBe('Grep(TODO)');
    expect(titles.Write).toBe('Write(src/novo.ts)');
    expect(titles.Edit).toBe('Edit(src/app.ts)');
    expect(titles.Agent).toMatch(/^Agent\(/);
    expect(titles.AskUserQuestion).toMatch(/^AskUserQuestion\(/);
    expect(titles.WebSearch).toMatch(/^WebSearch\(/);
  });

  it('todo(init) e todo(done) mostram os estados por fase sem JSON cru', () => {
    const p = createOmpTerminalParser();
    const msg = (id: string, message: object) => p.push(JSON.stringify({ type: 'message', id, message }));
    const call = (op: string, args: object) => msg(`c-${op}`, {
      role: 'assistant', content: [{ type: 'toolCall', id: op, name: 'todo', arguments: { op, ...args } }],
    });
    const result = (op: string, details: unknown, isError = false) => msg(`r-${op}`, {
      role: 'toolResult', toolCallId: op, toolName: 'todo', details,
      content: [{ type: 'text', text: 'Saída original' }], isError,
    });
    expect(call('init', { list: [{ phase: 'Triagem', items: ['Contar caixas'] }, { phase: 'Envio', items: ['Enviar caixas'] }] })[0])
      .toMatchObject({ kind: 'tool', title: 'todo(init: 2)' });
    const phases = [
      { name: 'Triagem', tasks: [{ content: 'Contar caixas', status: 'pending' }] },
      { name: 'Envio', tasks: [{ content: 'Enviar caixas', status: 'pending' }, { content: 'Usar palete', status: 'abandoned' }] },
    ];
    expect(result('init', { phases })[0]).toMatchObject({
      kind: 'result', toolUseId: 'init', text: 'Triagem:\n☐ Contar caixas\nEnvio:\n☐ Enviar caixas\n⊘ Usar palete',
    });
    expect(call('done', { task: 'Contar caixas' })[0]).toMatchObject({ kind: 'tool', title: 'todo(done)' });
    phases[0].tasks[0].status = 'completed';
    phases[1].tasks[0].status = 'in_progress';
    expect(result('done', { phases })[0]).toMatchObject({
      kind: 'result', toolUseId: 'done', text: 'Triagem:\n☒ Contar caixas\nEnvio:\n◐ Enviar caixas\n⊘ Usar palete',
    });
    expect(result('rm', { phases: [] })[0]).toMatchObject({ kind: 'result', text: 'Nenhuma tarefa.' });
    expect(result('view', { phases: [{ name: 'Triagem' }] })[0]).toMatchObject({ kind: 'result', text: 'Saída original' });
    expect(result('drop', { phases }, true)[0]).toMatchObject({ kind: 'result', text: 'Saída original', error: true });
  });

  it('erro da API vira entrada de sistema com nível de erro; resultado com isError é marcado', () => {
    const e = entries('main-error.jsonl');
    expect(e.at(-1)).toMatchObject({ kind: 'system', level: 'error' });
    const p = createOmpTerminalParser();
    const out = p.push(JSON.stringify({ type: 'message', id: 'r1', message: { role: 'toolResult', toolCallId: 'tc1', toolName: 'bash', content: [{ type: 'text', text: 'falhou' }], isError: true } }));
    expect(out).toEqual([expect.objectContaining({ kind: 'result', id: 'tc1:r', toolUseId: 'tc1', error: true, text: 'falhou' })]);
  });

  it('aborted vira aviso; pensamento aparece (sem texto quando vazio); injetado pelo omp e bastidores ficam de fora', () => {
    const p = createOmpTerminalParser();
    const msg = (m: object) => JSON.stringify({ type: 'message', id: `m${Math.random()}`, timestamp: '2026-10-19T10:00:00.000Z', message: m });
    expect(p.push(msg({ role: 'assistant', content: [{ type: 'thinking', thinking: 'hmm' }, { type: 'thinking', thinking: '' }], stopReason: 'aborted' })).map((x) => x.kind)).toEqual(['thinking', 'thinking', 'system']);
    expect(p.push(msg({ role: 'user', attribution: 'agent', content: [{ type: 'text', text: 'lembrete' }] }))).toEqual([]);
    for (const type of ['credential_pin', 'mode_change', 'custom', 'title_change', 'model_change', 'tipo_futuro']) expect(p.push(JSON.stringify({ type, id: 'x' }))).toEqual([]);
    expect(p.push('{quebrado')).toEqual([]);
  });

  it('subagente: a chamada task vira Agent com o nome dos subagentes e o que cada um faz; segredos mascarados', () => {
    const v = ompToolView('task', { i: 'Mapeando', tasks: [{ name: 'ArchScout', task: 'ler a arquitetura' }, { name: 'ApiScout', task: 'ler a API' }] });
    expect(v).toMatchObject({ tool: 'Agent', title: 'Agent(ArchScout, ApiScout: Mapeando)' });
    expect(v.input).toContain('ArchScout: ler a arquitetura');
    const secret = ompToolView('bash', { command: 'curl -H "Authorization: Bearer sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789" x' });
    expect(secret.title).not.toContain('abcdefghijklmnopqrstuvwxyz0123456789');
  });

  it('a mesma linha lida duas vezes não duplica entradas', () => {
    const p = createOmpTerminalParser();
    const line = JSON.stringify({ type: 'message', id: 'u1', message: { role: 'user', content: [{ type: 'text', text: 'oi' }] } });
    expect(p.push(line)).toHaveLength(1);
    expect(p.push(line)).toEqual([]);
  });
});
