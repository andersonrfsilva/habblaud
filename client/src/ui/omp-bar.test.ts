import { describe, expect, it } from 'vitest';
import { advisorNext, barActions, contextView, newSessionAgent } from './omp-bar';
import type { AgentInfo } from '../../../shared/types';
import { spawn } from 'node:child_process';
import { access, constants, realpath } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { chromium, type Browser, type Page } from 'playwright-core';
import { createServer, type ViteDevServer } from 'vite';

describe('barra do contexto do omp no terminal', () => {
  it('percentual com uma casa, nível de cor pelos limites do uso (50% e 80%) e o texto tokens / janela', () => {
    expect(contextView({ tokens: 16_881, window: 1_000_000 })).toMatchObject({ pct: 1.7, level: 'ok' });
    expect(contextView({ tokens: 120_000, window: 200_000 })?.level).toBe('warn');
    expect(contextView({ tokens: 190_000, window: 200_000 })?.level).toBe('crit');
    expect(contextView({ tokens: 250_000, window: 200_000 })?.pct).toBe(100);
    expect(contextView({ tokens: 16_881, window: 1_000_000 })?.text).toMatch(/16,9 k \/ 1 M · 1\.7%|16\.9 k \/ 1 M · 1\.7%/);
  });

  it('sem leitura ou janela zero: sem barra', () => {
    expect(contextView(undefined)).toBeUndefined();
    expect(contextView({ tokens: 1, window: 0 })).toBeUndefined();
  });
});

describe('olho do advisor', () => {
  it('estado desconhecido ou desligado liga; ligado desliga', () => {
    expect(advisorNext(undefined)).toBe('advisorOn');
    expect(advisorNext(false)).toBe('advisorOn');
    expect(advisorNext(true)).toBe('advisorOff');
  });
});

describe('controles por recurso', () => {
  const agent = (over: Partial<AgentInfo> = {}) => ({
    id: 'zc:old', provider: 'zeroclaw', kind: 'main', account: 'zc', roomId: 'r', cast: 'maria',
    sessionId: 'old', status: 'working', canMessage: true, ...over,
  }) as AgentInfo;

  it('ZeroClaw recebe STOP/new, nunca controles exclusivos do omp', () => {
    expect(barActions(agent(), true, false)).toEqual({
      new: true, stop: true, canStop: true, shake: false, advisor: false, resume: false, context: undefined,
    });
    expect(barActions(agent({ status: 'waiting' }), true, false).canStop).toBe(true);
    expect(barActions(agent({ status: 'idle' }), true, false).canStop).toBe(false);
  });

  it('contexto ZeroClaw não é confiável, mesmo com zero ou tokens positivos', () => {
    for (const context of [undefined, { tokens: 0, window: 200_000 }, { tokens: 12_000, window: 200_000 }]) {
      const actions = barActions(agent({ context }), true, false);
      expect(actions.context).toBeUndefined();
      expect(actions.new).toBe(true);
    }
    expect(barActions(agent({ provider: 'omp', context: { tokens: 0, window: 200_000 } }), true, false).context?.pct).toBe(0);
  });

  it('preserva todos os comandos OMP; não concede comandos a outros providers', () => {
    expect(barActions(agent({ provider: 'omp' }), true, false)).toMatchObject({ new: true, stop: true, shake: true, advisor: true, resume: true });
    for (const provider of ['claude', 'codex', undefined, 'unknown'] as const) {
      expect(barActions(agent({ provider: provider as AgentInfo['provider'] }), true, false)).toMatchObject({ new: false, stop: false, shake: false, advisor: false, resume: false });
    }
  });

  it('subagente, sessão encerrada, demo e caixa sem acesso não ganham controles', () => {
    for (const changed of [agent({ kind: 'sub' }), agent({ status: 'offline' }), agent({ status: 'done' }), undefined]) {
      expect(barActions(changed, true, false).new).toBe(false);
    }
    expect(barActions(agent(), false, false).stop).toBe(false);
    expect(barActions(agent(), true, true).new).toBe(false);
  });

  it('new encontra o novo UUID pelo snapshot mesmo sem a sessão anterior', () => {
    const previous = agent();
    const next = agent({ id: 'zc:new', sessionId: 'new' });
    expect(newSessionAgent([next], previous)).toBe(next);
    const stable = agent({ sessionId: 'new' });
    expect(newSessionAgent([stable], previous)).toBe(stable);
    for (const changed of [{ provider: 'omp' }, { account: 'other' }, { cast: 'other' }, { roomId: 'other' }, { sessionId: 'old' }, { status: 'offline' }]) {
      expect(newSessionAgent([agent(changed as Partial<AgentInfo>)], previous)).toBeUndefined();
    }
  });
});

describe.skipIf(process.env.HABBLAUD_WEB_UI_TEST !== '1')('ZeroClaw navegador real', () => {
  const root = fileURLToPath(new URL('../../../', import.meta.url));
  const liveSession = '00000000-0000-4000-8000-000000000008';
  const closedSession = '00000000-0000-4000-8000-000000000009';
  type Lab = {
    page: Page;
    origin: string;
    command(line: string): Promise<unknown>;
    stats(): Promise<{ controlRequests: Record<string, unknown>[]; zeroclaw: { sessionId: string } }>;
  };

  // Client TypeScript targets pre-ES2024, including these Node-run tests.
  function deferred<T>() {
    let resolve!: (value: T | PromiseLike<T>) => void;
    let reject!: (reason?: unknown) => void;
    const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
  }

  async function laboratory(run: (lab: Lab) => Promise<void>): Promise<void> {
    const executablePath = process.env.CHROMIUM_BIN;
    if (!executablePath || !isAbsolute(executablePath)) throw new Error('CHROMIUM_BIN deve apontar para um executável absoluto.');
    await access(executablePath, constants.X_OK);
    const child = spawn('npx', ['tsx', 'scripts/android-smoke.ts', '--port', '0', '--allow', 'terminal,permissions,messages'], {
      cwd: root, stdio: ['pipe', 'pipe', 'pipe'], detached: true,
    });
    // Readiness and stdin replies stay in memory; never include fixture credentials in failures.
    const lines = createInterface({ input: child.stdout });
    const ready = deferred<{ localUrl: string }>();
    const replies: Array<{ resolve(value: unknown): void; reject(error: Error): void }> = [];
    const exit = deferred<void>();
    child.once('close', () => exit.resolve());
    child.stderr.resume();
    child.once('error', () => ready.reject(new Error('Não foi possível iniciar a fixture.')));
    child.once('exit', () => {
      const error = new Error('Fixture encerrada antes de concluir o laboratório.');
      ready.reject(error);
      for (const reply of replies.splice(0)) reply.reject(error);
    });
    lines.on('line', line => {
      try {
        const value = JSON.parse(line);
        if (value.ready === true) ready.resolve({ localUrl: value.localUrl });
        else replies.shift()?.resolve(value);
      } catch { /* The child may emit unrelated tool diagnostics before readiness. */ }
    });
    async function bounded<T>(promise: Promise<T>, message: string): Promise<T> {
      // Real child/browser I/O cannot use fake timers; these deadlines bound failure, not successful readiness.
      const timeout = deferred<never>();
      const timer = setTimeout(() => timeout.reject(new Error(message)), 20_000);
      try { return await Promise.race([promise, timeout.promise]); }
      finally { clearTimeout(timer); }
    }
    let browser: Browser | undefined;
    let vite: ViteDevServer | undefined;
    try {
      const { localUrl } = await bounded(ready.promise, 'Fixture não informou prontidão.');
      const backend = new URL(localUrl);
      if (backend.protocol !== 'http:' || backend.hostname !== '127.0.0.1' || !backend.port || backend.username || backend.password) throw new Error('Fixture fora do loopback.');
      const command = async (line: string) => {
        const reply = deferred<unknown>();
        replies.push(reply);
        child.stdin.write(`${line}\n`);
        const value = await bounded(reply.promise, 'Comando da fixture não respondeu.');
        expect(value).not.toMatchObject({ ok: false });
        return value;
      };
      await command('runtime zeroclaw');
      vite = await createServer({
        configFile: fileURLToPath(new URL('../../../vite.config.ts', import.meta.url)),
        server: { host: '127.0.0.1', port: 0, strictPort: false, fs: { allow: [root, await realpath(`${root}/node_modules`)] }, proxy: { '/api': { target: localUrl, changeOrigin: true } } },
      });
      await vite.listen();
      const address = vite.httpServer?.address();
      if (!address || typeof address === 'string') throw new Error('Vite sem porta de laboratório.');
      const origin = `http://127.0.0.1:${address.port}`;
      browser = await chromium.launch({ executablePath, headless: true, chromiumSandbox: true });
      const page = await browser.newPage({ locale: 'pt-BR', viewport: { width: 1440, height: 1000 } });
      page.setDefaultTimeout(15_000);
      page.on('dialog', dialog => void dialog.accept());
      await page.addInitScript(() => localStorage.setItem('habblaud:prefs', JSON.stringify({ ompDeliverAs: 'aside', powerSave: true })));
      await run({ page, origin, command, stats: async () => (await page.request.get(`${origin}/api/fixture/stats`)).json() });
    } finally {
      try { await browser?.close(); }
      finally {
        try { await vite?.close(); }
        finally {
          child.stdin.end();
          const timer = setTimeout(() => { if (child.pid) { try { process.kill(-child.pid, 'SIGKILL'); } catch { /* Already exited. */ } } }, 5_000);
          try { await exit.promise; } finally { clearTimeout(timer); lines.close(); }
        }
      }
    }
  }

  async function terminal(page: Page, origin: string, agent = 'fixture:zeroclaw'): Promise<void> {
    await page.goto(`${origin}/?agent=${encodeURIComponent(agent)}`);
    await page.locator('.ui-term-cta').click();
    await page.locator('.ui-term:not([hidden]) .ui-term__input').waitFor({ state: 'visible' });
  }

  async function setMode(page: Page, mode: 'Enfileirar' | 'Mudar direção'): Promise<void> {
    await page.getByRole('button', { name: 'Configurações', exact: true }).click();
    const settings = page.getByRole('dialog', { name: 'Configurações', exact: true });
    await settings.getByRole('radio', { name: mode, exact: true }).click();
    expect(await settings.getByRole('radio', { name: 'Acrescentar agora', exact: true }).count()).toBe(0);
    await settings.getByRole('button', { name: 'Fechar configurações', exact: true }).click();
  }

  it('envia steer/followUp com expectedSessionId; normaliza aside sem apagar preferência OMP', async () => {
    await laboratory(async ({ page, origin, command }) => {
      await terminal(page, origin);
      const term = page.getByRole('dialog', { name: /^Terminal (?:de |da sessão )/ });
      const send = async (text: string, deliverAs: string) => {
        await term.locator('textarea.ui-term__input').fill(text);
        const [request] = await Promise.all([
          page.waitForRequest(request => new URL(request.url()).pathname === '/api/messages' && request.method() === 'POST'),
          term.getByRole('button', { name: 'Mandar a mensagem', exact: true }).click(),
        ]);
        expect(request.postDataJSON()).toMatchObject({ agentId: 'fixture:zeroclaw', text, deliverAs, expectedSessionId: liveSession });
      };
      await send('Preferência antiga', 'followUp');
      expect(await page.evaluate(() => JSON.parse(localStorage.getItem('habblaud:prefs')!).ompDeliverAs)).toBe('aside');
      await command('zc-done');
      await setMode(page, 'Mudar direção');
      await send('Direção nova', 'steer');
      await setMode(page, 'Enfileirar');
      await send('Pedido enfileirado', 'followUp');
      await command('zc-done');
      await terminal(page, origin, 'fixture:omp');
      await command('runtime omp');
      await page.reload();
      await page.getByRole('button', { name: 'Configurações', exact: true }).click();
      const settings = page.getByRole('dialog', { name: 'Configurações', exact: true });
      await settings.getByRole('radio', { name: 'Acrescentar agora', exact: true }).click();
      await settings.getByRole('button', { name: 'Fechar configurações', exact: true }).click();
      await page.goto(`${origin}/?agent=fixture%3Aomp`);
      await page.locator('.ui-term-cta').click();
      await page.locator('.ui-term:not([hidden]) textarea.ui-term__input').fill('OMP mantém aside');
      const [ompRequest] = await Promise.all([
        page.waitForRequest(request => new URL(request.url()).pathname === '/api/messages' && request.method() === 'POST'),
        page.locator('.ui-term:not([hidden])').getByRole('button', { name: 'Mandar a mensagem', exact: true }).click(),
      ]);
      expect(ompRequest.postDataJSON()).toMatchObject({ agentId: 'fixture:omp', deliverAs: 'aside' });
    });
  }, 120_000);

  it('STOP/new usam controle; STOP já parado conclui sem frame e new troca UUID preservando personagem', async () => {
    await laboratory(async ({ page, origin, command, stats }) => {
      await terminal(page, origin);
      const term = page.getByRole('dialog', { name: /^Terminal (?:de |da sessão )/ });
      const [control] = await Promise.all([
        page.waitForRequest(request => new URL(request.url()).pathname === '/api/messages/control' && request.method() === 'POST'),
        term.getByRole('button', { name: 'STOP', exact: true }).click(),
      ]);
      expect(control.postDataJSON()).toEqual({ agentId: 'fixture:zeroclaw', stop: true, expectedSessionId: liveSession });
      await expect.poll(async () => {
        const requests = (await stats()).controlRequests;
        if (!requests.length) return false;
        const snapshot = await (await page.request.get(`${origin}/api/snapshot`)).json();
        return snapshot.agents.find((agent: AgentInfo) => agent.id === 'fixture:zeroclaw')?.status === 'idle';
      }, { timeout: 15_000 }).toBe(true);
      await command('zc-working');
      await command('zc-no-active');
      const [stopped] = await Promise.all([
        page.waitForResponse(response => new URL(response.url()).pathname === '/api/messages/control' && response.request().method() === 'POST'),
        term.getByRole('button', { name: 'STOP', exact: true }).click(),
      ]);
      const stoppedBody = await stopped.json();
      const statusId = stoppedBody.id;
      await expect.poll(async () => (await (await page.request.get(`${origin}/api/messages/${statusId}`)).json()).status, { timeout: 15_000 }).toBe('delivered');
      const [next] = await Promise.all([
        page.waitForRequest(request => new URL(request.url()).pathname === '/api/messages/control' && request.method() === 'POST'),
        term.getByRole('button', { name: '/new', exact: true }).click(),
      ]);
      expect(next.postDataJSON()).toEqual({ agentId: 'fixture:zeroclaw', command: 'new', expectedSessionId: liveSession });
      await expect.poll(async () => (await stats()).zeroclaw.sessionId, { timeout: 15_000 }).not.toBe(liveSession);
      await term.getByText('Conversa nova aberta.', { exact: true }).waitFor();
      const snapshot = await (await page.request.get(`${origin}/api/snapshot`)).json();
      expect(snapshot.agents.find((agent: AgentInfo) => agent.id === 'fixture:zeroclaw')).toMatchObject({ provider: 'zeroclaw', account: 'zeroclaw', cast: 'zelia', name: 'Zélia Fictícia' });
      expect((await stats()).controlRequests).toHaveLength(3);
    });
  }, 120_000);

  it('oculta comandos OMP-only e contexto na árvore acessível; mantém gates OMP', async () => {
    await laboratory(async ({ page, origin, command }) => {
      await terminal(page, origin, 'fixture:omp');
      const term = page.getByRole('dialog', { name: /^Terminal (?:de |da sessão )/ });
      for (const name of ['STOP', '/new', '/shake', '/resume']) expect(await term.getByRole('button', { name, exact: true }).count()).toBe(1);
      expect(await term.getByRole('button', { name: 'Advisor do omp', exact: true }).count()).toBe(1);
      expect(await term.getByRole('meter').count()).toBe(1);
      await terminal(page, origin);
      for (const context of ['none', '0', '120000']) {
        await command(`zc-context ${context}`);
        // The positive gate waits for rendering, so absence assertions do not pass before the terminal exists.
        await term.getByRole('button', { name: '/new', exact: true }).waitFor();
        expect(await term.getByRole('meter').count()).toBe(0);
        expect(await term.getByRole('button', { name: 'STOP', exact: true }).count()).toBe(1);
        for (const name of ['/shake', '/resume', 'Advisor do omp']) expect(await term.getByRole('button', { name, exact: true }).count()).toBe(0);
        expect(await term.getByRole('button', { name: /Bate-papo paralelo/ }).count()).toBe(0);
        for (const name of ['Modelo', 'Raciocínio']) expect(await page.getByRole('combobox', { name, exact: true }).count()).toBe(0);
      }
    });
  }, 120_000);

  it('histórico lê espelho e retoma pelo elenco, nunca pelo controle OMP', async () => {
    await laboratory(async ({ page, origin, stats }) => {
      await page.goto(origin);
      await page.locator('.ui-hist-btn').click();
      let row = page.locator('.ui-hist__row').filter({ has: page.locator(`.ui-hist__item[data-session*="${closedSession}"]`) });
      await row.locator('.ui-hist__item').click();
      const term = page.getByRole('dialog', { name: /^Terminal (?:de |da sessão )/ });
      await term.getByText('Resposta ZeroClaw fictícia', { exact: true }).waitFor();
      expect(await term.getByRole('button', { name: '/new', exact: true }).count()).toBe(0);
      await term.getByRole('button', { name: 'Fechar terminal (Esc)', exact: true }).click();
      await page.locator('.ui-hist-btn').click();
      row = page.locator('.ui-hist__row').filter({ has: page.locator(`.ui-hist__item[data-session*="${closedSession}"]`) });
      await row.getByRole('button', { name: 'Retomar em…', exact: true }).click();
      expect(await row.getByRole('button', { name: 'Davi Fictício', exact: true }).count()).toBe(0);
      const [resume] = await Promise.all([
        page.waitForRequest(request => new URL(request.url()).pathname === '/api/cast/zora/start' && request.method() === 'POST'),
        row.getByRole('button', { name: 'Zora Fictícia', exact: true }).click(),
      ]);
      expect(resume.postDataJSON()).toEqual({ room: '/fixture/projeto', mode: 'resume', session: closedSession, account: 'zeroclaw' });
      await expect.poll(async () => {
        const snapshot = await (await page.request.get(`${origin}/api/snapshot`)).json();
        return snapshot.agents.some((agent: AgentInfo) => agent.provider === 'zeroclaw' && agent.sessionId === closedSession && agent.cast === 'zora' && agent.status !== 'offline');
      }, { timeout: 15_000 }).toBe(true);
      expect((await stats()).controlRequests).toHaveLength(0);
      // Light-dismiss guards ignore an immediate reopen; wait for the actual popover, not stale hidden rows.
      const historyButton = page.locator('.ui-hist-btn');
      await expect.poll(async () => {
        if (await historyButton.getAttribute('aria-expanded') !== 'true') await historyButton.click();
        return historyButton.getAttribute('aria-expanded');
      }, { timeout: 15_000 }).toBe('true');
      const open = page.locator('.ui-hist__row').filter({ has: page.locator(`.ui-hist__item[data-session*="${closedSession}"]`) });
      await open.locator('.ui-hist__live').waitFor();
      expect(await open.getByRole('button', { name: 'Retomar em…', exact: true }).count()).toBe(0);
    });
  }, 120_000);

  it('catálogo sem sessões mostra modelo legível e salva alias; selo normal desaparece sem marca', async () => {
    await laboratory(async ({ page, origin, command }) => {
      await terminal(page, origin);
      const term = page.getByRole('dialog', { name: /^Terminal (?:de |da sessão )/ });
      await term.locator('.ui-prov', { hasText: 'ZeroClaw' }).waitFor();
      await command('brand neutral');
      await terminal(page, origin);
      expect(await term.locator('.ui-prov:visible').count()).toBe(0);
      expect(await term.getByRole('group', { name: /ZeroClaw/ }).count()).toBe(0);
      await command('brand normal');
      await command('zc-close');
      await page.goto(origin);
      await page.getByRole('button', { name: 'Elenco: personagens, chamar e dispensar', exact: true }).click();
      const cast = page.getByRole('dialog', { name: 'Elenco', exact: true });
      await cast.locator('.ui-cast__row').filter({ hasText: 'Zora Fictícia' }).getByRole('button', { name: 'Editar', exact: true }).click();
      const catalog = await (await page.request.get(`${origin}/api/cast/models`)).json();
      const model = cast.getByRole('combobox', { name: 'Modelo', exact: true });
      await model.waitFor();
      expect(await model.locator('option').allTextContents()).toEqual(expect.arrayContaining(catalog.models.map((item: { model: string }) => item.model)));
      expect(await model.locator('option').allTextContents()).not.toEqual(expect.arrayContaining([expect.stringMatching(/^m_[0-9a-f]{12}$/)]));
      await model.selectOption({ label: 'fixture/model-alternativo' });
      const alias = catalog.models.find((item: { model: string }) => item.model === 'fixture/model-alternativo').alias;
      expect(await model.inputValue()).toBe(alias);
      expect(await cast.getByRole('combobox', { name: 'Conta', exact: true }).count()).toBe(0);
      const [saved] = await Promise.all([
        page.waitForRequest(request => new URL(request.url()).pathname === '/api/cast/zora' && request.method() === 'PUT'),
        cast.getByRole('button', { name: 'Salvar', exact: true }).click(),
      ]);
      expect(saved.postDataJSON()).toMatchObject({ id: 'zora', model: alias });
    });
  }, 120_000);
});
