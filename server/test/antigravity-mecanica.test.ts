import { spawn } from 'node:child_process';
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Duplex } from 'node:stream';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { AntigravityClient, agyArgs } from '../antigravity/acp';
import { loadConfig } from '../config';

const root = fileURLToPath(new URL('../../', import.meta.url));
async function implementations(): Promise<{ path: string; text: string }[]> {
  const paths = ['server/messages/antigravity.ts', 'scripts/antigravity-relay.ts'];
  for (const directory of ['server/antigravity', 'server/sources/antigravity']) {
    for (const name of await readdir(join(root, directory))) if (!name.endsWith('.test.ts') && /\.(ts|mjs)$/.test(name)) paths.push(`${directory}/${name}`);
  }
  return Promise.all(paths.map(async path => ({ path, text: await readFile(join(root, path), 'utf8') })));
}
describe('mecânica Antigravity', () => {
  it('não acessa credenciais, endpoints Google ou autenticação', async () => {
    for (const file of await implementations()) for (const word of ['.gemini', 'oauth', 'googleapis', 'google.com', 'keyring', 'authenticate']) {
      expect(file.text.toLowerCase(), `${file.path}: ${word}`).not.toContain(word);
    }
  });
  it('não passa flags proibidas ao binário', async () => {
    const flags = ['--override_user_agent_name', '--cloud_code_endpoint', '--disable-telemetry', '--disable_telemetry', '--is_google3_workspace', '--is_google_internal', '--gemini-dir', '--gemini_dir', '--app-data-dir', '--app_data_dir'];
    for (const file of await implementations()) for (const flag of flags) expect(file.text, file.path).not.toContain(flag);
  });
  it('abre subprocesso somente pelos dois pontos e argumentos fixos', async () => {
    const allowed = ['server/antigravity/acp.ts', 'scripts/antigravity-relay.ts'];
    for (const file of await implementations()) {
      expect(file.text, file.path).not.toMatch(/shell\s*:\s*true|\bexec\s*\(|\bexecSync\s*\(/);
      const calls = [...file.text.matchAll(/\bspawn\s*\(/g)];
      if (calls.length) {
        expect(allowed).toContain(file.path);
        expect(calls).toHaveLength(1);
        expect(file.text.slice(calls[0].index), file.path).toMatch(/^spawn\([^,]+,\s*agyArgs\(/);
      } else expect(allowed).not.toContain(file.path);
    }
  });
  it('só aceita acp e a flag explícita do dono', () => {
    expect(agyArgs({ skipPermissions: false })).toEqual(['acp']);
    expect(agyArgs({ skipPermissions: true })).toEqual(['acp', '--dangerously-skip-permissions']);
  });
  it('se identifica como habblaud no initialize capturado pelo falso', async () => {
    const directory = await mkdtemp(join(tmpdir(), 'antigravity-mechanics-'));
    const trace = join(directory, 'trace.ndjson');
    const child = spawn(process.execPath, [fileURLToPath(new URL('../antigravity/fake-agy.mjs', import.meta.url))], { env: { ...process.env, FAKE_AGY_TRACE_FILE: trace }, stdio: ['pipe', 'pipe', 'pipe'] });
    const exited = Promise.withResolvers<void>(); child.once('exit', () => exited.resolve());
    const client = new AntigravityClient({ bin: 'unused', version: '1.0.3', skipPermissions: false, connect: () => Duplex.from({ readable: child.stdout, writable: child.stdin }) });
    try {
      await client.open({ cwd: directory });
      const initialize = JSON.parse((await readFile(trace, 'utf8')).split('\n')[0]);
      expect(initialize.method).toBe('initialize');
      expect(initialize.params.clientInfo).toEqual({ name: 'habblaud', version: '1.0.3' });
    } finally {
      client.stop(); await exited.promise; await rm(directory, { recursive: true, force: true });
    }
  });
  it('não monta credenciais no contêiner', async () => {
    const container = await readFile(join(root, 'deploy/podman/habblaud.container'), 'utf8');
    expect(container.split('\n').filter(line => /^Volume=/.test(line)).some(line => line.includes('.gemini'))).toBe(false);
  });
  it('fica desligado por padrão', () => {
    expect(loadConfig({ HOME: '/home/x' }, []).antigravity).toBe(false);
  });
});
