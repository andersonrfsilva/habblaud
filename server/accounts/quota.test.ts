import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import * as childProcess from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { loadConfig } from '../config';
import { log } from '../log';
import { AccountsService } from './service';
import { parseTenantQuota, QuotaCollector } from './quota';

vi.mock('node:child_process', { spy: true });

const NOW = 1800000000000;
const raw = readFileSync(join(__dirname, 'fixtures/quota.json'), 'utf8');
const env = { HOME: '/tmp/no-quota-home', HABBLAUD_IN_DOCKER: '0' };
const configured = { ...env, HABBLAUD_QUOTA_URL: 'http://127.0.0.1:4000/v1/cota', HABBLAUD_QUOTA_TOKEN_FILE: '/tmp/tenant-token' };

it('envs ausentes/vazias preservam OMP; configuração válida desliga somente omp usage', () => {
  expect(loadConfig(env, []).quota).toBeUndefined();
  expect(loadConfig({ ...env, HABBLAUD_QUOTA_URL: ' ', HABBLAUD_QUOTA_TOKEN_FILE: '' }, []).quota).toBeUndefined();
  expect(loadConfig(env, []).ompUsage).toBe(true);
  expect(loadConfig(configured, [])).toMatchObject({ omp: true, ompUsage: false, quota: { url: configured.HABBLAUD_QUOTA_URL } });
  expect(loadConfig({ ...configured, HABBLAUD_IN_DOCKER: '1', HABBLAUD_OMP: '0' }, [])).toMatchObject({ inDocker: true, ompUsage: false, ompDirs: [], quota: { tokenFile: '/tmp/tenant-token' } });
});

it.each([
  { HABBLAUD_QUOTA_URL: configured.HABBLAUD_QUOTA_URL },
  { HABBLAUD_QUOTA_TOKEN_FILE: '/tmp/token' },
  ...['ftp://host/path', '/relative', 'https://secret:password@host', 'https://host/#fragment'].map((url) => ({ ...configured, HABBLAUD_QUOTA_URL: url })),
  { ...configured, HABBLAUD_QUOTA_TOKEN_FILE: 'relative' },
])('configuração inválida falha sem expor valores: %j', (bad) => {
  expect(() => loadConfig({ ...env, ...bad }, [])).toThrow('Configuração de cota inválida');
  try { loadConfig({ ...env, ...bad }, []); } catch (error) {
    expect(String(error)).not.toMatch(/secret|password|#fragment/);
  }
});

it('fixture de dois provedores escolhe primeiro válido, sem somar nem copiar metadados', () => {
  expect(parseTenantQuota(raw, NOW)).toEqual({ source: 'omp', quota: true, fetchedAt: NOW,
    fiveHour: { utilization: 25, resetsAt: NOW + 18000000 }, sevenDay: { utilization: 50, resetsAt: NOW + 604800000 } });
  const data = JSON.parse(raw);
  data.reports.unshift(null, { provider: 'invalid' });
  data.reports[2].metadata = { planType: 'secret' };
  expect(parseTenantQuota(JSON.stringify(data), NOW)).toEqual(parseTenantQuota(raw, NOW));
});

it('exaustão mantém 100%; limita frações; aceita resetsAt ausente', () => {
  const data = JSON.parse(raw);
  data.reports = [data.reports[0]];
  data.reports[0].limits[0].status = 'exhausted';
  data.reports[0].limits[0].amount.usedFraction = 2;
  data.reports[0].limits[1].amount.usedFraction = -1;
  delete data.reports[0].limits[1].window.resetsAt;
  expect(parseTenantQuota(JSON.stringify(data), NOW)).toEqual({ source: 'omp', quota: true, fetchedAt: NOW,
    fiveHour: { utilization: 100, resetsAt: NOW + 18000000 }, sevenDay: { utilization: 0 } });
});

it.each(['truncated', 'quota', 'reports', 'null', 'shared', 'tier', 'fraction', 'fetchedAt', 'resetsAt', 'missing'])('recusa resposta inválida: %s', (kind) => {
  const data = JSON.parse(raw);
  data.reports = [data.reports[0]];
  const report = data.reports[0];
  if (kind === 'quota') delete data.quota;
  if (kind === 'reports') data.reports = [];
  if (kind === 'null') data.reports = [null];
  if (kind === 'shared') delete report.limits[0].scope.shared;
  if (kind === 'tier') report.limits[0].scope.tier = 'paid';
  if (kind === 'fraction') report.limits[0].amount.usedFraction = '0.5';
  if (kind === 'fetchedAt') report.fetchedAt = 'invalid';
  if (kind === 'resetsAt') report.limits[0].window.resetsAt = 'invalid';
  if (kind === 'missing') report.limits.pop();
  expect(parseTenantQuota(kind === 'truncated' ? '{' : JSON.stringify(data), NOW)).toBeUndefined();
});

// Wait for filesystem promises without advancing the collector's fake clock.
async function eventually(check: () => void) {
  for (let attempt = 0; ; attempt++) {
    try { check(); return; } catch (error) {
      if (attempt === 100) throw error;
      await delay(2);
    }
  }
}

async function settle() {
  await delay(10);
  expect(vi.getTimerCount()).toBe(1);
}

describe('transporte HTTP', () => {
  let dir: string;
  let tokenFile: string;
  let collector: QuotaCollector | undefined;
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    dir = mkdtempSync(join(tmpdir(), 'quota-test-'));
    tokenFile = join(dir, 'token');
    writeFileSync(tokenFile, 'synthetic-bearer\n');
  });
  afterEach(() => {
    collector?.stop();
    collector = undefined;
    vi.useRealTimers();
    vi.restoreAllMocks();
    rmSync(dir, { recursive: true, force: true });
  });

  it('início imediato, headers, rotação, 5 min sucesso e 1 min falha preservando fetchedAt', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(raw));
    const result = vi.fn();
    collector = new QuotaCollector({ url: configured.HABBLAUD_QUOTA_URL, tokenFile, fetch: fetcher, onResult: result });
    collector.start();
    await eventually(() => expect(result).toHaveBeenCalledTimes(1));
    expect(fetcher.mock.calls[0]).toMatchObject([configured.HABBLAUD_QUOTA_URL, { method: 'GET', redirect: 'error', headers: { Authorization: 'Bearer synthetic-bearer' } }]);
    await vi.advanceTimersByTimeAsync(299999);
    expect(fetcher).toHaveBeenCalledTimes(1);
    writeFileSync(tokenFile, 'rotated-synthetic\n');
    fetcher.mockResolvedValueOnce(new Response('', { status: 401 }));
    await vi.advanceTimersByTimeAsync(1);
    await eventually(() => expect(fetcher).toHaveBeenCalledTimes(2));
    await settle();
    expect(fetcher.mock.calls[1][1]?.headers).toEqual({ Authorization: 'Bearer rotated-synthetic' });
    expect(result).toHaveBeenCalledTimes(1);
    expect(result.mock.calls[0][0].fetchedAt).toBe(NOW);
    fetcher.mockResolvedValueOnce(new Response(raw));
    await vi.advanceTimersByTimeAsync(60000);
    await eventually(() => expect(result).toHaveBeenCalledTimes(2));
  });

  it.each(['empty', 'missing'])('token %s não consulta rede', async (kind) => {
    if (kind === 'empty') writeFileSync(tokenFile, ' \n');
    else rmSync(tokenFile);
    const fetcher = vi.fn<typeof fetch>();
    const result = vi.fn();
    collector = new QuotaCollector({ url: configured.HABBLAUD_QUOTA_URL, tokenFile, fetch: fetcher, onResult: result });
    collector.start();
    await settle();
    expect(fetcher).not.toHaveBeenCalled();
    expect(result).not.toHaveBeenCalled();
  });

  it.each([401, 403, 429, 500, 'network', 'redirect', 'json'])('falha %s sanitizada, sem números falsos', async (failure) => {
    const warn = vi.spyOn(log, 'warnOnce').mockImplementation(() => {});
    const fetcher = vi.fn<typeof fetch>();
    if (typeof failure === 'number') fetcher.mockResolvedValue(new Response('secret response', { status: failure }));
    else if (failure === 'json') fetcher.mockResolvedValue(new Response('{'));
    else fetcher.mockRejectedValue(new Error('synthetic-bearer secret URL'));
    const result = vi.fn();
    collector = new QuotaCollector({ url: configured.HABBLAUD_QUOTA_URL, tokenFile, fetch: fetcher, onResult: result });
    collector.start();
    await eventually(() => expect(warn).toHaveBeenCalled());
    expect(result).not.toHaveBeenCalled();
    expect(JSON.stringify(warn.mock.calls)).not.toMatch(/synthetic-bearer|secret/);
    await vi.advanceTimersByTimeAsync(59999);
    expect(fetcher).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    await eventually(() => expect(fetcher).toHaveBeenCalledTimes(2));
  });

  it.each(['fetch', 'body'])('timeout total no %s, sem sobreposição; stop aborta e descarta resultado tardio', async (phase) => {
    const response = Promise.withResolvers<Response>();
    const body = Promise.withResolvers<string>();
    const fetcher = vi.fn<typeof fetch>().mockImplementation(() => phase === 'fetch'
      ? response.promise
      : Promise.resolve({ ok: true, text: () => body.promise } as Response));
    const result = vi.fn();
    collector = new QuotaCollector({ url: configured.HABBLAUD_QUOTA_URL, tokenFile, fetch: fetcher, onResult: result });
    collector.start();
    collector.start();
    await eventually(() => expect(fetcher).toHaveBeenCalledTimes(1));
    await vi.advanceTimersByTimeAsync(9999);
    expect(fetcher).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetcher.mock.calls[0][1]?.signal?.aborted).toBe(true);
    expect(result).not.toHaveBeenCalled();
    collector.stop();
    if (phase === 'fetch') response.resolve(new Response(raw));
    else body.resolve(raw);
    await vi.advanceTimersByTimeAsync(600000);
    expect(result).not.toHaveBeenCalled();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('stop durante requisição aborta imediatamente, sem publicar nem reagendar', async () => {
    const pending = Promise.withResolvers<Response>();
    const fetcher = vi.fn<typeof fetch>().mockReturnValue(pending.promise);
    const result = vi.fn();
    collector = new QuotaCollector({ url: configured.HABBLAUD_QUOTA_URL, tokenFile, fetch: fetcher, onResult: result });
    collector.start();
    await eventually(() => expect(fetcher).toHaveBeenCalledTimes(1));
    collector.stop();
    expect(fetcher.mock.calls[0][1]?.signal?.aborted).toBe(true);
    pending.resolve(new Response(raw));
    await vi.advanceTimersByTimeAsync(600000);
    expect(result).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it.each([401, 429, 'timeout'])('falha %s após sucesso preserva quota, fetchedAt, stale e rollover', async (failure) => {
    const accounts = new AccountsService({ dirs: [], home: dir, env: {}, tenantQuota: true, onChange: () => {} });
    accounts.setProviderAccounts('codex', [{ dir, detected: { id: 'codex', configDir: dir, name: 'Codex', short: 'C', color: '#fff' } }]);
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => new Response(raw));
    collector = new QuotaCollector({ url: configured.HABBLAUD_QUOTA_URL, tokenFile, fetch: fetcher, onResult: (usage) => accounts.setTenantQuota(usage) });
    collector.start();
    await eventually(() => expect(accounts.usageView('codex').usage?.fetchedAt).toBe(NOW));
    if (failure === 'timeout') fetcher.mockImplementation(() => Promise.withResolvers<Response>().promise);
    else if (typeof failure === 'number') fetcher.mockImplementation(async () => new Response('', { status: failure }));
    await vi.advanceTimersByTimeAsync(300000);
    await eventually(() => expect(fetcher).toHaveBeenCalledTimes(2));
    await vi.advanceTimersByTimeAsync(10000);
    expect(accounts.usageView('codex').usage?.fetchedAt).toBe(NOW);
    collector.stop();
    vi.setSystemTime(NOW + 1800001);
    expect(accounts.usageView('codex').status).toBe('stale');
    vi.setSystemTime(NOW + 18000000);
    expect(accounts.usageView('codex').usage?.fiveHour).toBeUndefined();
    expect(accounts.usageView('codex').usage?.sevenDay?.utilization).toBe(50);
  });

  it('bootstrap sem OMP, Docker, binário inexistente e zero sessões não executa processos', async () => {
    const spawn = vi.mocked(childProcess.spawn).mockImplementation(() => { throw new Error('unexpected process'); });
    const exec = vi.mocked(childProcess.execFile).mockImplementation(() => { throw new Error('unexpected process'); });
    const config = loadConfig({ ...configured, HABBLAUD_IN_DOCKER: '1', HABBLAUD_OMP: '0', HABBLAUD_OMP_BIN: '/nonexistent' }, []);
    const accounts = new AccountsService({ dirs: [], home: dir, env: {}, tenantQuota: !!config.quota, onChange: () => {} });
    accounts.setProviderAccounts('codex', [{ dir, detected: { id: 'codex', configDir: dir, name: 'Codex', short: 'C', color: '#fff' } }]);
    collector = new QuotaCollector({ ...config.quota!, tokenFile, fetch: vi.fn<typeof fetch>().mockResolvedValue(new Response(raw)), onResult: (usage) => accounts.setTenantQuota(usage) });
    collector.start();
    await eventually(() => expect(accounts.list(new Map())[0].usage?.quota).toBe(true));
    expect(accounts.list(new Map())[0]).toMatchObject({ sessions: 0, usage: { fiveHour: { utilization: 25 }, sevenDay: { utilization: 50 } } });
    expect(spawn).not.toHaveBeenCalled();
    expect(exec).not.toHaveBeenCalled();
  });
});
