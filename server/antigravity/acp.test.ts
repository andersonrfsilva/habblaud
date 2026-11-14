import { spawn, type ChildProcess } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Duplex } from 'node:stream';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import { agyArgs, AntigravityClient, AuthRequired, type AcpEvent } from './acp';

const fake = fileURLToPath(new URL('./fake-agy.mjs', import.meta.url));
const clients: AntigravityClient[] = [];
const children: ChildProcess[] = [];
const folders: string[] = [];
afterEach(async () => {
  for (const client of clients.splice(0)) client.stop();
  for (const child of children.splice(0)) {
    if (child.exitCode !== null || child.signalCode !== null) continue;
    const exited = Promise.withResolvers<void>();
    child.once('exit', () => exited.resolve());
    child.stdin?.end();
    const timer = setTimeout(() => child.kill('SIGKILL'), 2000);
    await exited.promise; clearTimeout(timer);
  }
  for (const folder of folders.splice(0)) await rm(folder, { recursive: true, force: true });
});
function fixture(env: NodeJS.ProcessEnv = {}) {
  const events: AcpEvent[] = [];
  const client = new AntigravityClient({ bin: 'unused', skipPermissions: false, version: '1.0.3', connect: () => {
    const child = spawn(process.execPath, [fake], { env: { ...process.env, ...env }, stdio: ['pipe', 'pipe', 'pipe'] });
    children.push(child);
    return Duplex.from({ readable: child.stdout, writable: child.stdin });
  } });
  clients.push(client); client.onEvent(event => events.push(event));
  return { client, events };
}
async function folder() {
  const directory = await mkdtemp(join(tmpdir(), 'habblaud-acp-'));
  folders.push(directory); return directory;
}
async function permission(events: AcpEvent[]) {
  await expect.poll(() => events.some(event => event.type === 'permission')).toBe(true);
  const event = events.find(event => event.type === 'permission');
  if (!event || event.type !== 'permission') throw new Error('Missing permission');
  return event;
}

describe('Antigravity ACP', () => {
  it('fixa todos os argumentos do binário', () => {
    expect(agyArgs({ skipPermissions: false })).toEqual(['acp']);
    expect(agyArgs({ skipPermissions: true })).toEqual(['acp', '--dangerously-skip-permissions']);
  });
  it('abre, envia texto e conclui o turno', async () => {
    const { client, events } = fixture();
    const { sessionId } = await client.open({ cwd: '/tmp' });
    await client.prompt(sessionId, 'ok');
    await expect.poll(() => client.session(sessionId)?.busy).toBe(false);
    expect(events).toContainEqual({ type: 'update', sessionId, update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'ok' } } });
    expect(events.filter(event => event.type === 'turn-state').map(event => event.busy)).toEqual([true, false]);
  });
  it('multiplexa duas sessões na mesma conexão', async () => {
    const { client, events } = fixture();
    const first = await client.open({ cwd: '/tmp/first' });
    const second = await client.open({ cwd: '/tmp/second' });
    expect(children).toHaveLength(1);
    await Promise.all([client.prompt(first.sessionId, 'ok'), client.prompt(second.sessionId, 'ok')]);
    await expect.poll(() => events.filter(event => event.type === 'update' && event.update.sessionUpdate === 'agent_message_chunk').length).toBe(2);
    expect(events.filter(event => event.type === 'update').map(event => event.sessionId)).toContain(first.sessionId);
    expect(events.filter(event => event.type === 'update').map(event => event.sessionId)).toContain(second.sessionId);
  });
  it('escolhe permitir e negar pelo kind, não pelo optionId', async () => {
    const { client, events } = fixture();
    const { sessionId } = await client.open({ cwd: '/tmp' });
    for (const [kind, status] of [['allow_once', 'completed'], ['reject_once', 'failed']] as const) {
      events.length = 0;
      await client.prompt(sessionId, 'tool:echo ok');
      const request = await permission(events);
      expect(client.answerPermission(sessionId, request.requestId, kind)).toBe(true);
      await expect.poll(() => client.session(sessionId)?.busy).toBe(false);
      expect(events.some(event => event.type === 'update' && event.update.status === status)).toBe(true);
      expect(client.answerPermission(sessionId, request.requestId, kind)).toBe(false);
    }
  });
  it('cancela o turno e responde cancelled à permissão pendente', async () => {
    const trace = join(await folder(), 'trace.ndjson');
    const { client, events } = fixture({ FAKE_AGY_TRACE_FILE: trace });
    const { sessionId } = await client.open({ cwd: '/tmp' });
    await client.prompt(sessionId, 'tool:sleep 60');
    const request = await permission(events);
    await client.cancel(sessionId);
    expect(events).toContainEqual({ type: 'permission-gone', sessionId, requestId: request.requestId });
    const messages = (await readFile(trace, 'utf8')).trim().split('\n').map(line => JSON.parse(line));
    expect(messages.some(message => message.method === 'session/cancel')).toBe(true);
    expect(messages.some(message => message.id === request.requestId && message.result?.outcome?.outcome === 'cancelled')).toBe(true);
    expect(client.session(sessionId)?.busy).toBe(false);
  });
  it('não tenta autenticar quando falta login', async () => {
    const { client } = fixture({ FAKE_AGY_AUTH: '0' });
    await expect(client.open({ cwd: '/tmp' })).rejects.toBeInstanceOf(AuthRequired);
  });
  it('encerra o filho quando fecha a última sessão', async () => {
    const { client } = fixture();
    const { sessionId } = await client.open({ cwd: '/tmp' });
    const child = children[0];
    await client.close(sessionId);
    await expect.poll(() => child.exitCode).toBe(0);
    expect(client.sessions()).toEqual([]);
  });
  it('fecha sessões e limpa busy se o processo cai', async () => {
    const { client, events } = fixture();
    const { sessionId } = await client.open({ cwd: '/tmp' });
    await client.prompt(sessionId, 'sleep');
    children[0].kill('SIGKILL');
    await expect.poll(() => client.sessions()).toEqual([]);
    expect(events).toContainEqual({ type: 'session-close', sessionId, reason: 'process-exit' });
    expect(events).toContainEqual({ type: 'turn-state', sessionId, busy: false });
  });
  it('o caminho de processo recebe apenas os argumentos permitidos', async () => {
    const directory = await folder();
    const executable = join(directory, 'fake-agy');
    const argv = join(directory, 'argv.json');
    await writeFile(executable, `#!/usr/bin/env node\nimport(${JSON.stringify(new URL('./fake-agy.mjs', import.meta.url).href)});\n`, { mode: 0o700 });
    for (const skipPermissions of [false, true]) {
      const client = new AntigravityClient({ bin: executable, skipPermissions, version: '1.0.3', env: { ...process.env, FAKE_AGY_ARGV_FILE: argv } });
      clients.push(client);
      const { sessionId } = await client.open({ cwd: directory });
      expect(JSON.parse(await readFile(argv, 'utf8'))).toEqual(agyArgs({ skipPermissions }));
      await client.close(sessionId);
    }
  });
});
