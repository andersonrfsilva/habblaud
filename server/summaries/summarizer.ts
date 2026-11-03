// Resumo das respostas do agente (Android › Configurações › Resumir respostas): `omp -p` com o papel `tiny` da conta
// padrão do omp. O texto vai pelo stdin (sem limite de argumento e sem virar opção quando começa com `-`); sem sessão,
// ferramentas, extensões, skills nem regras, para não aparecer no escritório nem no histórico.
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { tr } from '../../shared/i18n';

// Em caracteres; escapado em JSON ainda cabe no corpo de 256 KiB do readJson.
export const SUMMARY_INPUT_MAX = 32 * 1024;
const TIMEOUT_MS = 90_000;
const OUTPUT_MAX = 64 * 1024;
const PROMPT =
  'Resuma a resposta do agente que o usuário enviar em no máximo 5 frases, no mesmo idioma dela. Mantenha exatos os ' +
  'nomes, números, arquivos, comandos e erros que importam. Responda só com o resumo, sem introdução nem markdown de títulos.';

export class SummaryUnavailable extends Error {}

export type RunSummary = (bin: string, agentDir: string, text: string) => Promise<string>;

/** Roda o omp com PI_CODING_AGENT_DIR=agentDir e devolve o stdout; rejeita com a 1ª linha útil do stderr. */
export const runSummary: RunSummary = (bin, agentDir, text) => {
  const { promise, resolve, reject } = Promise.withResolvers<string>();
  const args = ['-p', '--no-session', '--no-tools', '--no-lsp', '--no-extensions', '--no-skills', '--no-rules', '--model', '@tiny', '--system-prompt', PROMPT];
  const child = spawn(bin, args, { env: { ...process.env, PI_CODING_AGENT_DIR: agentDir }, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
  let out = '';
  let err = '';
  const timer = setTimeout(() => child.kill('SIGKILL'), TIMEOUT_MS);
  child.stdout.setEncoding('utf8').on('data', (c: string) => { if (out.length < OUTPUT_MAX) out += c; });
  child.stderr.setEncoding('utf8').on('data', (c: string) => { if (err.length < OUTPUT_MAX) err += c; });
  child.on('error', (e) => { clearTimeout(timer); reject(e); });
  child.on('close', (code, signal) => {
    clearTimeout(timer);
    const summary = out.trim();
    if (code === 0 && summary) return resolve(summary);
    const line = err.split(/\r?\n/).map((s) => s.trim()).find((s) => s && s !== 'Working...');
    reject(new Error((line ?? (signal ? `timeout (${TIMEOUT_MS / 1000} s)` : `exit ${code}`)).slice(0, 200)));
  });
  child.stdin.on('error', () => {});
  child.stdin.end(text);
  return promise;
};

interface Options {
  bin(): string | undefined;
  agentDir(): string | undefined;
  run?: RunSummary;
  /** Resumos rodando ao mesmo tempo; os outros esperam na fila. */
  concurrency?: number;
  /** Resumos guardados (por hash do texto), os mais antigos saem primeiro. */
  cacheMax?: number;
}

export class Summarizer {
  private readonly cache = new Map<string, Promise<string>>();
  private readonly waiting: (() => void)[] = [];
  private running = 0;
  constructor(private readonly opts: Options) {}

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
    const job = this.slot(() => (this.opts.run ?? runSummary)(bin, agentDir, text));
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
