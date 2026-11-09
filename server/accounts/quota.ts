import { readFile } from 'node:fs/promises';
import type { AccountUsage } from '../../shared/types';
import { log } from '../log';
import { FIVE_HOURS_MS, SEVEN_DAYS_MS, parseOmpUsage } from '../sources/omp/usage';

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/** Valida o contrato do proxy antes de reutilizar o parser histórico, sem metadados de provedor. */
export function parseTenantQuota(raw: string, now: number): AccountUsage | undefined {
  let data: unknown;
  try { data = JSON.parse(raw); } catch { return undefined; }
  if (!object(data) || data.quota !== true || !Array.isArray(data.reports)) return undefined;
  for (const report of data.reports) {
    if (!object(report) || typeof report.provider !== 'string' || !report.provider.trim() ||
        typeof report.fetchedAt !== 'number' || !Number.isFinite(report.fetchedAt) || !Array.isArray(report.limits)) continue;
    const limits = report.limits.filter((limit: unknown) => {
      if (!object(limit) || !object(limit.scope) || limit.scope.shared !== true || limit.scope.tier !== undefined ||
          !object(limit.window) || !object(limit.amount)) return false;
      return [FIVE_HOURS_MS, SEVEN_DAYS_MS].includes(limit.window.durationMs as number) &&
        typeof limit.amount.usedFraction === 'number' && Number.isFinite(limit.amount.usedFraction) &&
        (limit.window.resetsAt === undefined || (typeof limit.window.resetsAt === 'number' && Number.isFinite(limit.window.resetsAt)));
    });
    if (![FIVE_HOURS_MS, SEVEN_DAYS_MS].every((duration) => limits.some((limit) => limit.window.durationMs === duration))) continue;
    const usage = parseOmpUsage(JSON.stringify({ quota: true, reports: [{ ...report, limits }] }), report.provider, now)!;
    return { source: 'omp', quota: true, fetchedAt: usage.fetchedAt, fiveHour: usage.fiveHour, sevenDay: usage.sevenDay };
  }
  return undefined;
}

export interface QuotaCollectorOptions {
  url: string;
  tokenFile: string;
  onResult: (usage: AccountUsage) => void;
  fetch?: typeof fetch;
  now?: () => number;
}

/** Uma coleta em voo; falhas preservam a última publicação no AccountsService. */
export class QuotaCollector {
  private running = false;
  private timer?: NodeJS.Timeout;
  private controller?: AbortController;

  constructor(private readonly opts: QuotaCollectorOptions) {}

  start(): void {
    if (this.running) return;
    this.running = true;
    void this.collect();
  }

  stop(): void {
    this.running = false;
    clearTimeout(this.timer);
    this.timer = undefined;
    this.controller?.abort();
  }

  private async collect(): Promise<void> {
    const controller = new AbortController();
    this.controller = controller;
    let category = 'token';
    let success = false;
    const timeout = setTimeout(() => controller.abort(), 10_000);
    timeout.unref?.();
    const { promise: aborted, reject } = Promise.withResolvers<never>();
    controller.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true });
    try {
      const usage = await Promise.race([aborted, (async () => {
        const token = (await readFile(this.opts.tokenFile, 'utf8')).trim();
        if (!token || controller.signal.aborted) throw new Error('token');
        category = 'rede';
        const response = await (this.opts.fetch ?? fetch)(this.opts.url, {
          method: 'GET', headers: { Authorization: `Bearer ${token}` }, redirect: 'error', signal: controller.signal,
        });
        if (!response.ok) {
          category = `HTTP ${response.status}`;
          throw new Error('http');
        }
        category = 'resposta inválida';
        const parsed = parseTenantQuota(await response.text(), (this.opts.now ?? Date.now)());
        if (!parsed) throw new Error('response');
        return parsed;
      })()]);
      if (this.running && this.controller === controller) {
        this.opts.onResult(usage);
        success = true;
      }
    } catch {
      if (this.running && this.controller === controller) {
        log.warnOnce('tenant-quota', `Falha na consulta de cota (${controller.signal.aborted ? 'timeout' : category}).`);
      }
    } finally {
      clearTimeout(timeout);
      if (this.controller === controller) {
        this.controller = undefined;
        if (this.running) {
          this.timer = setTimeout(() => void this.collect(), success ? 300_000 : 60_000);
          this.timer.unref?.();
        }
      }
    }
  }
}
