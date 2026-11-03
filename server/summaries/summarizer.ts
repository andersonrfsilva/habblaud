// Resumo das respostas do agente (Android › Configurações › Resumir respostas): o papel `tiny` da conta padrão do omp.
// Cada resumo abrindo um `omp -p` gastava uns 5 s só para o omp subir; aqui um `omp --mode rpc` fica aberto e cada
// resumo é uma conversa nova nele. Sem sessão gravada, ferramentas, extensões, skills nem regras: não aparece no
// escritório nem no histórico.
import { execFile, spawn, type ChildProcess } from 'node:child_process';
import { createHash } from 'node:crypto';
import { statSync } from 'node:fs';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { tr } from '../../shared/i18n';

// Em caracteres; escapado em JSON ainda cabe no corpo de 256 KiB do readJson.
export const SUMMARY_INPUT_MAX = 32 * 1024;
const TIMEOUT_MS = 90_000;
/** Processo parado há tanto tempo fecha (cada omp aberto ocupa memória). */
const IDLE_MS = 10 * 60_000;
const PROMPT =
  'O usuário envia a resposta que um agente de programação deu. Não responda a ela nem converse: resuma-a em no ' +
  'máximo 5 frases, no mesmo idioma dela. Mantenha exatos os nomes, números, arquivos, comandos e erros que importam. ' +
  'Responda só com o resumo, sem introdução nem markdown de títulos.';

export class SummaryUnavailable extends Error {}

export type RunSummary = (bin: string, agentDir: string, text: string) => Promise<string>;

type Frame = Record<string, unknown>;

/** Um `omp --mode rpc` aberto, um resumo por vez. `config` = quando o config.yml da conta mudou por último ao abrir. */
class RpcWorker {
  private readonly child: ChildProcess;
  private readonly waiters = new Map<string, (frame: Frame) => void>();
  private seq = 0;
  dead = false;
  readonly ready: Promise<void>;
  idle?: NodeJS.Timeout;
  constructor(readonly bin: string, readonly agentDir: string, readonly config: number, onExit?: () => void) {
    const args = ['--mode', 'rpc', '--no-session', '--no-tools', '--no-lsp', '--no-extensions', '--no-skills', '--no-rules', '--model', '@tiny', '--system-prompt', PROMPT];
    this.child = spawn(bin, args, { env: { ...process.env, PI_CODING_AGENT_DIR: agentDir }, stdio: ['pipe', 'pipe', 'ignore'], windowsHide: true });
    const { promise, resolve, reject } = Promise.withResolvers<void>();
    this.ready = promise;
    promise.catch(() => {});
    this.waiters.set('ready', (frame) => frame.type === 'dead' ? reject(new Error('omp saiu')) : resolve());
    this.child.on('error', () => this.kill());
    // Saiu sozinho (não foi fechado por aqui): quem mantém o processo aberto abre outro.
    this.child.on('exit', () => { const unexpected = !this.dead; this.kill(); if (unexpected) onExit?.(); });
    this.child.stdin!.on('error', () => {});
    createInterface({ input: this.child.stdout! }).on('line', (line) => {
      let frame: Frame;
      try { frame = JSON.parse(line) as Frame; } catch { return; }
      // `ready` abre; respostas pelo id; o fim do prompt vem num `prompt_result` com o id dele.
      const key = frame.type === 'ready' ? 'ready' : typeof frame.id === 'string' && (frame.type === 'response' || frame.type === 'prompt_result') ? `${frame.id}:${frame.type}` : undefined;
      const waiter = key && this.waiters.get(key);
      if (waiter) { this.waiters.delete(key); waiter(frame); }
    });
  }
  kill(): void {
    if (this.dead) return;
    this.dead = true;
    clearTimeout(this.idle);
    this.child.kill('SIGKILL');
    for (const waiter of this.waiters.values()) waiter({ type: 'dead' });
    this.waiters.clear();
  }
  private send(command: Frame, until: 'response' | 'prompt_result'): Promise<Frame> {
    if (this.dead) return Promise.resolve({ type: 'dead' });
    const id = `s${++this.seq}`;
    const { promise, resolve } = Promise.withResolvers<Frame>();
    this.waiters.set(`${id}:${until}`, resolve);
    // O prompt também manda uma resposta (a admissão); sem quem espere por ela, a linha é só ignorada.
    this.child.stdin!.write(JSON.stringify({ ...command, id }) + '\n');
    return promise;
  }
  async summarize(text: string): Promise<string> {
    await this.ready;
    const fail = (frame: Frame, what: string) => {
      if (frame.type === 'dead') throw new Error('omp saiu');
      const error = frame.error && typeof frame.error === 'object' ? (frame.error as Frame).message : frame.error;
      throw new Error(typeof error === 'string' && error ? error.slice(0, 200) : what);
    };
    const fresh = await this.send({ type: 'new_session' }, 'response');
    if (fresh.success !== true) fail(fresh, 'new_session');
    // Prefixo fixo: uma resposta que começa com `/` viraria comando do omp.
    const done = await this.send({ type: 'prompt', message: `Resposta do agente:\n\n${text}` }, 'prompt_result');
    if (done.status !== 'completed') fail(done, String(done.status ?? 'prompt'));
    const last = await this.send({ type: 'get_last_assistant_text' }, 'response');
    const summary = last.success === true && last.data && typeof last.data === 'object' ? (last.data as Frame).text : undefined;
    if (typeof summary !== 'string' || !summary.trim()) fail(last, tr('resposta inesperada'));
    return (summary as string).trim();
  }
}

/** Mtime do config.yml da conta: o papel tiny trocado nas Configurações vale a partir do próximo resumo. */
function configStamp(agentDir: string): number {
  try { return statSync(join(agentDir, 'config.yml')).mtimeMs; } catch { return 0; }
}

export type ReadTinyRole = (bin: string, agentDir: string) => Promise<string | undefined>;

/** `modelRoles.tiny` da conta (`omp config get`), relido só quando o config.yml muda. */
export function tinyRoleReader(): ReadTinyRole {
  let last: { agentDir: string; stamp: number; value: Promise<string | undefined> } | undefined;
  return (bin, agentDir) => {
    const stamp = configStamp(agentDir);
    if (last?.agentDir === agentDir && last.stamp === stamp) return last.value;
    const { promise, resolve } = Promise.withResolvers<string | undefined>();
    execFile(bin, ['config', 'get', 'modelRoles', '--json'], { env: { ...process.env, PI_CODING_AGENT_DIR: agentDir }, timeout: 10_000, windowsHide: true, encoding: 'utf8' }, (err, out) => {
      let parsed: unknown;
      try { parsed = err ? undefined : JSON.parse(out); } catch { parsed = undefined; }
      const roles = parsed && typeof parsed === 'object' && 'value' in parsed ? parsed.value : undefined;
      const tiny = roles && typeof roles === 'object' && 'tiny' in roles ? roles.tiny : undefined;
      resolve(typeof tiny === 'string' && tiny.trim() ? tiny : undefined);
    });
    last = { agentDir, stamp, value: promise };
    // Falha ao ler não fica guardada: o próximo pedido tenta de novo.
    void promise.then((v) => { if (v === undefined && last?.value === promise) last = undefined; });
    return promise;
  };
}

/**
 * Guarda os omp parados para o próximo resumo. Um fica sempre aberto (até o primeiro resumo depois de o servidor subir
 * não espera o omp abrir); os outros fecham após IDLE_MS parados. Quem morre ou leu outra configuração é trocado.
 */
export function rpcSummaryRunner(): RunSummary & { warm(bin: string, agentDir: string): void } {
  const idle: RpcWorker[] = [];
  const park = (worker: RpcWorker) => {
    worker.idle = setTimeout(() => {
      // Só fecha os que sobram: o último parado continua aberto.
      if (idle.length <= 1) return;
      const i = idle.indexOf(worker);
      if (i >= 0) idle.splice(i, 1);
      worker.kill();
    }, IDLE_MS);
    worker.idle.unref();
    idle.push(worker);
  };
  const spawnWorker = (bin: string, agentDir: string): RpcWorker => {
    const worker: RpcWorker = new RpcWorker(bin, agentDir, configStamp(agentDir), () => {
      const i = idle.indexOf(worker);
      if (i >= 0) idle.splice(i, 1);
      // Espera antes de abrir outro: um omp que não sobe não vira um laço de processos.
      setTimeout(() => warm(bin, agentDir), 30_000).unref();
    });
    return worker;
  };
  const warm = (bin: string, agentDir: string) => {
    if (idle.some((w) => !w.dead && w.bin === bin && w.agentDir === agentDir && w.config === configStamp(agentDir))) return;
    park(spawnWorker(bin, agentDir));
  };
  const run: RunSummary = async (bin, agentDir, text) => {
    const config = configStamp(agentDir);
    let worker: RpcWorker | undefined;
    while ((worker = idle.pop())) {
      clearTimeout(worker.idle);
      if (!worker.dead && worker.bin === bin && worker.agentDir === agentDir && worker.config === config) break;
      worker.kill();
    }
    worker ??= spawnWorker(bin, agentDir);
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; worker.kill(); }, TIMEOUT_MS);
    try {
      return await worker.summarize(text);
    } catch (err) {
      worker.kill();
      throw timedOut ? new Error(`timeout (${TIMEOUT_MS / 1000} s)`) : err;
    } finally {
      clearTimeout(timer);
      if (!worker.dead) park(worker);
      else if (!idle.length) warm(bin, agentDir);
    }
  };
  return Object.assign(run, { warm });
}

interface Options {
  bin(): string | undefined;
  agentDir(): string | undefined;
  run?: RunSummary & { warm?(bin: string, agentDir: string): void };
  /** O papel tiny da conta; sem ele configurado não há resumo (o usuário escolhe o modelo nas Configurações). */
  tiny?: ReadTinyRole;
  /** Resumos rodando ao mesmo tempo; os outros esperam na fila. */
  concurrency?: number;
  /** Resumos guardados (por hash do texto), os mais antigos saem primeiro. */
  cacheMax?: number;
}

export class Summarizer {
  private readonly cache = new Map<string, Promise<string>>();
  private readonly waiting: (() => void)[] = [];
  private running = 0;
  private readonly run: RunSummary & { warm?(bin: string, agentDir: string): void };
  private readonly tiny: ReadTinyRole;
  constructor(private readonly opts: Options) {
    this.run = opts.run ?? rpcSummaryRunner();
    this.tiny = opts.tiny ?? tinyRoleReader();
  }

  /** Abre o omp que fica esperando o primeiro resumo; sem omp, conta ou papel tiny, não faz nada. */
  warm(): void {
    const bin = this.opts.bin();
    const agentDir = this.opts.agentDir();
    if (bin && agentDir) void this.tiny(bin, agentDir).then((tiny) => { if (tiny) this.run.warm?.(bin, agentDir); });
  }

  summarize(text: string): Promise<string> {
    const key = createHash('sha256').update(text).digest('hex');
    const cached = this.cache.get(key);
    if (cached) {
      this.cache.delete(key);
      this.cache.set(key, cached);
      return cached;
    }
    const bin = this.opts.bin();
    const agentDir = this.opts.agentDir();
    if (!bin || !agentDir) return Promise.reject(new SummaryUnavailable(tr('resumo indisponível: omp ou conta do omp não encontrados')));
    const job = this.tiny(bin, agentDir).then((tiny) => {
      if (!tiny) throw new SummaryUnavailable(tr('resumo desligado: escolha um modelo para o papel Tiny nas Configurações'));
      return this.slot(() => this.run(bin, agentDir, text));
    });
    // Falha não fica guardada: a próxima vez tenta de novo.
    job.catch(() => { if (this.cache.get(key) === job) this.cache.delete(key); });
    this.cache.set(key, job);
    const max = this.opts.cacheMax ?? 500;
    while (this.cache.size > max) this.cache.delete(this.cache.keys().next().value!);
    return job;
  }

  private async slot<T>(work: () => Promise<T>): Promise<T> {
    // A vaga passa direto para quem espera, sem abrir brecha para um pedido novo furar a fila.
    if (this.running >= (this.opts.concurrency ?? 2)) {
      const { promise, resolve } = Promise.withResolvers<void>();
      this.waiting.push(resolve);
      await promise;
    } else this.running++;
    try { return await work(); } finally {
      const next = this.waiting.shift();
      if (next) next(); else this.running--;
    }
  }
}
