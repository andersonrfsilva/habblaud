import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { connect } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { AntigravityClient } from '../antigravity/acp';

describe('relé Antigravity', () => {
  it('liga ACP pelo soquete privado, recusa segunda conexão e encerra o filho', async () => {
    const directory = await mkdtemp(join(tmpdir(), 'antigravity-relay-'));
    const socket = join(directory, 'acp.sock');
    const executable = join(directory, 'fake-agy');
    const argv = join(directory, 'argv.json');
    const exitedFile = join(directory, 'exited');
    const fake = new URL('../antigravity/fake-agy.mjs', import.meta.url).href;
    await writeFile(executable, `#!/usr/bin/env node\nimport {writeFileSync} from 'node:fs';\nprocess.once('exit', () => writeFileSync(${JSON.stringify(exitedFile)}, 'exited'));\nimport(${JSON.stringify(fake)});\n`, { mode: 0o700 });
    const relay = spawn(process.execPath, ['--import', 'tsx', fileURLToPath(new URL('../../scripts/antigravity-relay.ts', import.meta.url)), '--socket', socket], {
      env: { ...process.env, HABBLAUD_ANTIGRAVITY_BIN: executable, HABBLAUD_ANTIGRAVITY_SKIP_PERMISSIONS: '0', FAKE_AGY_ARGV_FILE: argv }, stdio: ['pipe', 'pipe', 'pipe'],
    });
    const exited = Promise.withResolvers<void>(); relay.once('exit', () => exited.resolve());
    const ready = Promise.withResolvers<void>();
    let output = '';
    relay.stdout.on('data', (chunk: Buffer) => { output += chunk.toString(); if (output.includes(socket)) ready.resolve(); });
    relay.once('error', error => ready.reject(error));
    relay.once('exit', code => ready.reject(new Error(`Relay exited before ready: ${code}`)));
    const client = new AntigravityClient({ bin: 'unused', socket, skipPermissions: false, version: 'test' });
    try {
      await ready.promise;
      expect((await stat(socket)).mode & 0o777).toBe(0o600);
      const { sessionId } = await client.open({ cwd: directory });
      expect(JSON.parse(await readFile(argv, 'utf8'))).toEqual(['acp']);
      const second = connect(socket);
      const rejected = Promise.withResolvers<void>();
      second.on('error', () => {}); second.once('close', () => rejected.resolve());
      await rejected.promise;
      const response = Promise.withResolvers<void>();
      const unsubscribe = client.onEvent(event => {
        if (event.type === 'update' && event.update.sessionUpdate === 'agent_message_chunk') response.resolve();
      });
      await client.prompt(sessionId, 'ok'); await response.promise; unsubscribe();
      await client.close(sessionId);
      await expect.poll(() => existsSync(exitedFile)).toBe(true);
    } finally {
      client.stop(); relay.kill('SIGTERM'); await exited.promise;
      expect(existsSync(socket)).toBe(false);
      await rm(directory, { recursive: true, force: true });
    }
  });
});
