import { describe, expect, it } from 'vitest';
import type { AgentInfo } from '../../../shared/types';
import { pickerAgents } from './agent-picker';

const a = (id: string, kind: 'main' | 'sub', status: AgentInfo['status'], statusSince = 0) => ({ id, name: id, kind, status, statusSince }) as AgentInfo;

describe('pickerAgents', () => {
  const all = [a('zeca', 'sub', 'working'), a('bia', 'main', 'idle'), a('ana', 'main', 'working'), a('saiu', 'main', 'offline'), a('eva', 'main', 'waiting', 5), a('rui', 'main', 'waiting', 2)];

  it('agentes: todos os presentes, principais antes dos subagentes, por nome', () => {
    expect(pickerAgents('agents', all).map((x) => x.id)).toEqual(['ana', 'bia', 'eva', 'rui', 'zeca']);
  });

  it('trabalhando: só quem está trabalhando; precisam de você: do que espera há mais tempo', () => {
    expect(pickerAgents('working', all).map((x) => x.id)).toEqual(['ana', 'zeca']);
    expect(pickerAgents('waiting', all).map((x) => x.id)).toEqual(['rui', 'eva']);
  });
});
