import { spawn, type ChildProcess } from 'node:child_process';
import { chmod, lstat, mkdir, unlink } from 'node:fs/promises';
import { createServer, type Socket } from 'node:net';
import { homedir } from 'node:os';
import { dirname, isAbsolute, join } from 'node:path';
import { agyArgs } from '../server/antigravity/acp';
import { tr } from '../shared/i18n';

const args = process.argv.slice(2);
if (args.length && (args.length !== 2 || args[0] !== '--socket')) throw new Error(tr('uso: npm run antigravity:relay -- --socket /caminho/absoluto'));
const socketPath = args[1] ?? process.env.HABBLAUD_ANTIGRAVITY_SOCKET?.trim() ?? join(homedir(), '.habblaud', 'antigravity-acp.sock');
if (!isAbsolute(socketPath)) throw new Error(tr('HABBLAUD_ANTIGRAVITY_SOCKET precisa ser um caminho absoluto'));
const bin = process.env.HABBLAUD_ANTIGRAVITY_BIN?.trim() || 'agy';
const skipPermissions = /^(1|true|yes|on)$/i.test(process.env.HABBLAUD_ANTIGRAVITY_SKIP_PERMISSIONS?.trim() ?? '');
const directory = dirname(socketPath);
await mkdir(directory, { recursive: true, mode: 0o700 });
const parent = await lstat(directory);
if (!parent.isDirectory() || parent.uid !== process.getuid?.() || (parent.mode & 0o022)) throw new Error(tr('Antigravity: a pasta do soquete precisa pertencer ao usuário e não permitir escrita de outros'));
try {
  const existing = await lstat(socketPath);
  if (!existing.isSocket()) throw new Error(tr('Antigravity: o caminho do soquete já existe e não é um soquete'));
  await unlink(socketPath);
} catch (error) {
  if (!(error && typeof error === 'object' && 'code' in error && error.code === 'ENOENT')) throw error;
}
let active: { socket: Socket; child: ChildProcess; exited: Promise<void> } | undefined;
let stopping = false;
const server = createServer(socket => {
  if (active || stopping) { socket.destroy(); return; }
  const child = spawn(bin, agyArgs({ skipPermissions }), { stdio: ['pipe', 'pipe', 'ignore'], env: process.env });
  const exited = Promise.withResolvers<void>();
  const connection = { socket, child, exited: exited.promise };
  active = connection;
  child.on('error', () => {
    console.error(tr('Antigravity: não foi possível abrir o agy; confira HABBLAUD_ANTIGRAVITY_BIN'));
    socket.destroy();
  });
  child.once('close', () => {
    socket.destroy();
    if (active === connection) active = undefined;
    exited.resolve();
  });
  socket.on('error', () => child.stdin.end());
  socket.once('close', () => child.stdin.end());
  child.stdin.on('error', () => socket.destroy());
  child.stdout.on('error', () => socket.destroy());
  socket.pipe(child.stdin);
  child.stdout.pipe(socket);
});
server.on('error', error => { console.error(tr('Antigravity: falha no relé: {0}', [error.message])); void shutdown(1); });
async function shutdown(code: number): Promise<void> {
  if (stopping) return;
  stopping = true;
  const closed = Promise.withResolvers<void>();
  server.close(() => closed.resolve());
  const connection = active;
  if (connection) {
    connection.socket.destroy();
    connection.child.stdin?.end();
    const timeout = setTimeout(() => connection.child.kill('SIGKILL'), 5000);
    await connection.exited;
    clearTimeout(timeout);
  }
  await closed.promise;
  try { if ((await lstat(socketPath)).isSocket()) await unlink(socketPath); }
  catch (error) { if (!(error && typeof error === 'object' && 'code' in error && error.code === 'ENOENT')) throw error; }
  process.exitCode = code;
}
process.once('SIGTERM', () => { void shutdown(0); });
process.once('SIGINT', () => { void shutdown(0); });
server.listen(socketPath, async () => {
  await chmod(socketPath, 0o600);
  console.log(tr('Relé Antigravity pronto em {0}', [socketPath]));
});
