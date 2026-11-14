import { constants } from 'node:fs';
import { chmod, lstat, mkdir, open, rename, unlink } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import type { ZeroClawConnector } from '../sources/zeroclaw/connector';
import { log } from '../log';
import { tr } from '../../shared/i18n';

export const PERSONA_MAX_CHARS = 20_000;
const REQUEST_MAX_BYTES = 4096;
export type TelegramRequest = { token: string; peers: string[] };

export function parseTelegramRequest(raw: unknown): TelegramRequest | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const value = raw as Record<string, unknown>;
  if (Object.keys(value).length !== 2 || typeof value.token !== 'string' || !/^[0-9]{6,12}:[A-Za-z0-9_-]{30,64}$/.test(value.token)
    || !Array.isArray(value.peers) || value.peers.length < 1 || value.peers.length > 8
    || value.peers.some((peer) => typeof peer !== 'string' || !/^[0-9]{1,20}$/.test(peer))
    || new Set(value.peers).size !== value.peers.length || Buffer.byteLength(JSON.stringify(value)) > REQUEST_MAX_BYTES) return null;
  return { token: value.token, peers: [...value.peers] as string[] };
}

/** Arquivos de pedido pertencem ao Habblaud; somente o host aplica os vínculos. */
export class NamedAgentSettings {
  private readonly directory: string;
  constructor(dataDir: string, private readonly connector: Pick<ZeroClawConnector, 'request'>) {
    this.directory = join(dataDir, 'telegram');
  }
  private file(alias: string): string {
    if (!/^[a-z][a-z0-9]{1,23}$/.test(alias)) throw new Error(tr('Agente fixo inválido'));
    return join(this.directory, `${alias}.json`);
  }
  private async checkDirectory(create = false): Promise<boolean> {
    if (create) await mkdir(this.directory, { mode: 0o700, recursive: true });
    try {
      const stat = await lstat(this.directory);
      if (stat.isSymbolicLink() || !stat.isDirectory()) throw new Error(tr('Diretório Telegram inválido'));
      if (create) await chmod(this.directory, 0o700);
      return true;
    } catch (error) { if ((error as NodeJS.ErrnoException).code === 'ENOENT' && !create) return false; throw error; }
  }
  private async checkFile(file: string): Promise<void> {
    try {
      const stat = await lstat(file);
      if (!stat.isFile() || stat.isSymbolicLink()) throw new Error(tr('Arquivo Telegram inválido'));
    } catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error; }
  }
  async telegram(alias: string): Promise<{ linked: boolean; peers: string[]; active: boolean }> {
    let request: TelegramRequest | null = null;
    const file = this.file(alias);
    try {
      if (await this.checkDirectory()) {
        const handle = await open(file, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
        try {
          const stat = await handle.stat();
          if (stat.isFile() && stat.size <= REQUEST_MAX_BYTES) {
            const buffer = Buffer.alloc(REQUEST_MAX_BYTES + 1);
            const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
            if (bytesRead <= REQUEST_MAX_BYTES) request = parseTelegramRequest(JSON.parse(buffer.subarray(0, bytesRead).toString('utf8')));
          }
          if (!request) log.warn(tr('Pedido Telegram inválido ou ilegível; vínculo ignorado'));
        } finally { await handle.close(); }
      }
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') log.warn(tr('Pedido Telegram inválido ou ilegível; vínculo ignorado'));
    }
    let active = false;
    try {
      const result = await this.connector.request('/api/channels') as { channels?: unknown };
      active = Array.isArray(result?.channels) && result.channels.some((channel: unknown) => !!channel && typeof channel === 'object' && 'name' in channel && channel.name === `telegram.${alias}`);
    } catch { /* Reiniciar o daemon interrompe brevemente a consulta de estado. */ }
    return { linked: request !== null, peers: request?.peers ?? [], active };
  }
  async link(alias: string, request: TelegramRequest): Promise<void> {
    if (!parseTelegramRequest(request)) throw new Error(tr('Pedido Telegram inválido'));
    const file = this.file(alias);
    await this.checkDirectory(true);
    await this.checkFile(file);
    const temporary = join(this.directory, `.${alias}-${randomUUID()}.tmp`);
    const handle = await open(temporary, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
    try {
      await handle.writeFile(JSON.stringify(request));
      await handle.sync();
      await handle.close();
      await this.checkFile(file);
      await rename(temporary, file);
    } finally { await handle.close().catch(() => undefined); await unlink(temporary).catch(() => undefined); }
  }
  async unlink(alias: string): Promise<void> {
    const file = this.file(alias);
    if (!await this.checkDirectory()) return;
    await this.checkFile(file);
    try { await unlink(file); } catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error; }
  }
  async persona(alias: string, text?: string): Promise<string> {
    this.file(alias);
    if (text !== undefined && [...text].length > PERSONA_MAX_CHARS) throw new Error(tr('Persona excede 20000 caracteres'));
    const result = await this.connector.request(`/api/personality/SOUL.md?agent=${encodeURIComponent(alias)}`, text === undefined ? undefined : {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ content: text }),
    }) as { content?: unknown; exists?: boolean };
    if (text !== undefined) return text;
    if (result?.exists === false) return '';
    if (typeof result?.content !== 'string' || [...result.content].length > PERSONA_MAX_CHARS) throw new Error(tr('Resposta de persona inválida'));
    return result.content;
  }
}
