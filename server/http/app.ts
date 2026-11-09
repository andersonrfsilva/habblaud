// Rotas da API (/api/*). Respostas JSON; rotas desconhecidas -> 404 JSON.
import type { IncomingMessage, ServerResponse } from 'node:http';
import { readdirSync, realpathSync, statSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { basename, dirname, isAbsolute, join, relative, sep } from 'node:path';
import { parseCastMember } from '../model/cast';
import type { CastLauncher } from '../cast/launcher';
import type { AccessInfo, AgentInfo, ModSummary, OfficeSnapshot, SourceInfo, UpdateStatus } from '../../shared/types';
import type { AccountsService } from '../accounts/service';
import { handleCodexEvent } from '../codex/http';
import { handleOmpEvent } from '../omp/http';
import type { DayStatsService } from '../history/daystats';
import type { Office } from '../model/office';
import type { CodexLive } from '../sources/codex/live';
import type { OmpLive } from '../sources/omp/live';
import type { SessionLookup } from '../sources/source';
import { isJsonContentType, isLoopbackHost } from './guard';
import type { RemoteFeature } from './remote';
import { handleSessionsRoute } from './sessions';
import type { Hub } from './sse';
import { handleStatsRoute } from './stats';
import type { TerminalStreams } from './terminal';
import { isNeutralBrand, tr } from '../../shared/i18n';
import type { SpawnService } from '../spawn/spawn';
import { AttachmentError, type AttachmentStore } from '../spawn/attachments';
import type { SpawnFolder } from '../spawn/folders';
import { AUDIO_BODY_MAX, type DictationService } from '../dictation/dictation';
import type { DeliveryIndex } from '../deliveries/deliveries';
import type { FileFetcher } from '../deliveries/fetcher';
import { handleFilesRoute, type BrowseDeps } from './files';
import { HOST_WAIT_MS } from '../host-wait';
import { SUMMARY_INPUT_MAX, SummaryUnavailable, type Summarizer } from '../summaries/summarizer';
import { avatarPng } from './avatar';

export interface ApiDeps {
  office: Office;
  hub: Hub;
  accounts: AccountsService;
  sources: () => SourceInfo[];
  version: string;
  inDocker: boolean;
  /** Terminal ligado (ServerConfig.terminal: só com bind local). */
  terminal?: boolean;
  /** Streams do terminal; sem eles o recurso fica desligado mesmo com `terminal`. */
  terminals?: TerminalStreams;
  /**
   * Histórico de sessões do terminal (GET /api/sessions/*, http/sessions.ts): o HistorySet de todas as ferramentas
   * (ou um provedor sozinho); mesma trava do terminal.
   */
  sessions?: SessionLookup;
  /** Rotas do timelapse (/api/timeline/*, ver http/timeline.ts); devolve false para o resto. */
  timeline?: (req: IncomingMessage, res: ServerResponse, url: URL) => boolean;
  /**
   * Rotas de /api/permissions (responder pelo escritório, server/permissions/http.ts). Só existem com bind
   * local (ServerConfig.terminal); a trava do Host local é conferida aqui antes de chamá-las.
   */
  permissions?: (req: IncomingMessage, res: ServerResponse, path: string) => void;
  /**
   * Rotas das mensagens pelo escritório (/api/messages, a caixa de entrada do plugin em /api/mod/inbox e a do auxiliar
   * do Codex em /api/codex/bridge/*, server/messages/http.ts). Só existem com ServerConfig.messages (a trava do
   * terminal e HABBLAUD_MENSAGENS); a trava do Host local é conferida aqui antes de chamá-las.
   */
  messages?: (req: IncomingMessage, res: ServerResponse, path: string) => void;
  /**
   * Bate-papo paralelo com o agente do omp (server/asides/http.ts): `page` serve /api/asides (a janela lateral do
   * terminal) e `progress` recebe a resposta da extensão em POST /api/omp/aside. Só existem com as mensagens ligadas
   * (mesmo `ServerConfig.messages`); as travas ficam aqui.
   */
  asides?: {
    page: (req: IncomingMessage, res: ServerResponse, path: string, url: URL) => void;
    progress: (req: IncomingMessage, res: ServerResponse) => Promise<void>;
  };
  /**
   * Terminal interativo e ações nos agentes (/api/pty/*, POST /api/agents/:id/stop|takeover; http/pty.ts).
   * Ausente = desligado (sem HABBLAUD_PTY=1, Docker, sem node-pty ou sem bind local).
   */
  pty?: (req: IncomingMessage, res: ServerResponse, path: string) => void;
  /**
   * Fonte do Codex ao vivo: recebe os eventos dos hooks do Codex (POST /api/codex/events, server/codex/http.ts). Sem
   * ela a rota responde {ok: false}. Só com Host local e, fora do Docker, conexão pelo loopback (os eventos só observam:
   * não dependem da trava do terminal).
   */
  codexLive?: CodexLive;
  /**
   * Fonte do omp ao vivo: recebe os eventos da extensão do Habblaud no omp (POST /api/omp/events, server/omp/http.ts).
   * Sem ela a rota responde {ok: false}. A mesma trava de /api/codex/events: Host local e, fora do Docker, conexão pelo
   * loopback.
   */
  ompLive?: OmpLive;
  /** Configurações › Sala do omp segue o projeto (OmpSettings): POST /api/omp/settings. Ausente = sem omp (404). */
  setOmpRoomByProject?: (on: boolean) => boolean;
  /** Novo agente pelo escritório (/api/spawn e, para o auxiliar do host, /api/host/spawn/*; server/spawn/spawn.ts). */
  spawn?: SpawnService;
  castLauncher?: CastLauncher;
  castRoots?: string[];
  /** Pastas sugeridas no "Novo agente" (GET /api/spawn/folders; spawn/folders.ts): projetos recentes. */
  spawnFolders?: () => Promise<SpawnFolder[]>;
  /** Anexos do "Novo agente" (POST /api/spawn/attachments; spawn/attachments.ts). */
  attachments?: AttachmentStore;
  /** Ditado (/api/dictation e, para o auxiliar do host, /api/host/dictation/*; dictation/dictation.ts). */
  dictation?: DictationService;
  /** Entregas (GET /api/deliveries e os anexos em /api/deliveries/file/:id; deliveries/deliveries.ts). */
  deliveries?: DeliveryIndex;
  /** Arquivos das pastas de trabalho (/api/files*, http/files.ts): só leitura; mesma trava do terminal. Ausente = desligado (sem terminal ou no Docker). */
  browse?: BrowseDeps;
  /** Quem lê os arquivos das entregas (e, para o auxiliar do host, /api/host/files/*; deliveries/fetcher.ts). */
  files?: FileFetcher;
  /** Renomeia a sala (POST /api/rooms/rename {id, name}; vazio volta ao padrão). Devolve o nome em uso, ou undefined se a sala não existe. */
  renameRoom?: (id: string, name: string) => string | undefined;
  /** Estatísticas do "Meu dia" (GET /api/stats, http/stats.ts). */
  stats?: DayStatsService;
  /** Verificação de versão nova no GitHub (GET /api/updates, POST /api/updates/check; updates/checker.ts). */
  updates?: {
    status(): UpdateStatus;
    check(opts: { manual?: boolean }): Promise<UpdateStatus>;
  };
  /**
   * Porta remota com login (http/remote.ts): `is` diz se a requisição chegou por ela (já autenticada); `allows`, o que
   * HABBLAUD_REMOTE_ALLOW libera. Requisição remota nunca vale pelo Host: só pelo que `allows` liberar.
   */
  remote?: { is(req: IncomingMessage): boolean; allows(feature: RemoteFeature): boolean };
  /**
   * Rotas do Habblaud World (/api/world/*, server/world/http.ts). Ausente = HABBLAUD_WORLD=0: 404 em todas. Só pela porta
   * local com Host local: pela porta do celular, nem com login (403 `local_only`).
   */
  world?: (req: IncomingMessage, res: ServerResponse, url: URL) => void;
  /** Resumo das respostas do agente (POST /api/summaries {text}; summaries/summarizer.ts). Ausente = desligado (404). */
  summaries?: Summarizer;
}

/** GET /api/agents/:id/terminal (ids nunca contêm '/'). */
const TERMINAL_ROUTE = /^\/api\/agents\/([^/]+)\/terminal$/;
/** POST /api/agents/:id/stop|takeover (terminal interativo). */
const AGENT_ACTION_ROUTE = /^\/api\/agents\/[^/]+\/(stop|takeover)$/;

/** Elenco e vínculo de um agente principal. */
const CAST_ROUTE = /^\/api\/cast\/([a-z0-9-]{1,40})$/;
const AGENT_CAST_ROUTE = /^\/api\/agents\/([^/]+)\/cast$/;
const NOT_EDITABLE = tr('agente não encontrado: só o agente principal de uma sessão aberta tem personagem editável');

/** Conexão vinda do próprio computador (127.x, ::1 ou ::ffff:127.x). */
function isLoopbackAddress(addr: string | undefined): boolean {
  if (!addr) return false;
  const a = addr.replace(/^::ffff:/i, '');
  return a === '::1' || /^127\./.test(a);
}

/** Rotas das mensagens pelo escritório (server/messages/http.ts); /api/mod/summary fica de fora (sem trava). */
/**
 * Rotas dos hooks, dos plugins e do auxiliar do host: registrar e esperar pedidos de permissão, buscar as mensagens de
 * uma sessão, as rodadas do auxiliar. Só valem pela porta local; pela remota, nem com login (o celular aprova e manda
 * mensagens pelas rotas da página).
 */
function isLocalOnlyPath(path: string): boolean {
  return (
    path === '/api/permissions' ||
    /^\/api\/permissions\/[^/]+\/wait$/.test(path) ||
    path === '/api/mod/inbox' ||
    path === '/api/mod/inbox/ack' ||
    path.startsWith('/api/codex/') ||
    path.startsWith('/api/omp/') ||
    path.startsWith('/api/host/')
  );
}

const MESSAGES_OFF = (): string =>
  tr('mensagens pelo escritório desligadas: só funcionam com o Habblaud acessível apenas pelo próprio computador (e sem HABBLAUD_MENSAGENS=0)');

function isMessagesPath(path: string): boolean {
  return (
    path === '/api/messages' ||
    path.startsWith('/api/messages/') ||
    path === '/api/mod/inbox' ||
    path === '/api/mod/inbox/ack' ||
    path === '/api/codex/bridge/poll' ||
    path === '/api/codex/bridge/ack'
  );
}

const MAX_BODY = 256 * 1024;

export class HttpError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

export function sendJson(res: ServerResponse, status: number, body: unknown): void {
  const json = JSON.stringify(body);
  // Listas grandes (/api/sessions/recent, /api/cast, /api/snapshot) vão comprimidas a quem aceita gzip; as pequenas não compensam.
  const gzip = json.length > 1024 && /\bgzip\b/i.test(String(res.req?.headers['accept-encoding'] ?? ''));
  const data = gzip ? gzipSync(json) : json;
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(data),
    ...(gzip ? { 'Content-Encoding': 'gzip', Vary: 'Accept-Encoding' } : {}),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    // A API só é lida pela própria página: nada de leitura "no-cors" por outras origens.
    'Cross-Origin-Resource-Policy': 'same-origin',
  });
  res.end(data);
}

/** Cabeçalhos de um arquivo para ver na página (anexos das entregas, /api/files/raw). HTML e SVG em sandbox sem scripts. */
export function viewHeaders(type: string, name: string, length: number): Record<string, string | number> {
  const active = /^text\/html|^image\/svg/.test(type);
  return {
    'Content-Type': type,
    'Content-Length': length,
    'Cache-Control': 'private, max-age=300',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'SAMEORIGIN',
    'Content-Disposition': `inline; filename*=UTF-8''${encodeURIComponent(name)}`,
    ...(active ? { 'Content-Security-Policy': "sandbox; default-src 'none'; img-src data: blob: 'self'; style-src 'unsafe-inline'; media-src 'self'" } : {}),
  };
}

/**
 * Anexo de entrega. Páginas e SVG vêm numa sandbox sem scripts (CSP), para não rodarem com o login do escritório;
 * PDF e imagens podem abrir dentro do modal (mesma origem).
 */
function sendFile(res: ServerResponse, data: Buffer, type: string, name: string): void {
  res.writeHead(200, viewHeaders(type, name, data.length));
  res.end(data);
}

/** Lê o corpo JSON. Exige `Content-Type: application/json` (barreira contra CSRF; ver http/guard.ts). */
export function readJson(req: IncomingMessage, max = MAX_BODY): Promise<unknown> {
  if (!isJsonContentType(req.headers['content-type'])) {
    req.resume();
    return Promise.reject(new HttpError(415, tr('envie o corpo como JSON (Content-Type: application/json)')));
  }
  return new Promise((ok, fail) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on('data', (c: Buffer) => {
      size += c.length;
      // Grande demais: continua drenando (sem guardar) para conseguir responder 413.
      if (size > max) return void fail(new HttpError(413, tr('corpo grande demais')));
      chunks.push(c);
    });
    req.on('end', () => {
      if (size > max) return;
      const raw = Buffer.concat(chunks).toString('utf8');
      if (!raw.trim()) return ok({});
      try {
        ok(JSON.parse(raw));
      } catch {
        fail(new HttpError(400, tr('JSON inválido')));
      }
    });
    req.on('error', fail);
  });
}

/** Resumo da verificação de versão nova para o /api/health. */
function updatesSummary(s: UpdateStatus | undefined): { state: UpdateStatus['state']; latest?: string; available: boolean } {
  return s ? { state: s.state, latest: s.latest, available: s.available } : { state: 'off', available: false };
}

/** Subagentes aninhados mais fundo que isto não existem na prática; o limite só evita laço num parentId torto. */
const MAX_PARENT_HOPS = 16;

/**
 * Resumo para o mod do Claude Code (GET /api/mod/summary, ver ModSummary). `isReal` diz quem é agente de
 * verdade: os do demo vivem só no snapshot (não no Office), então `office.has(id)` os separa sem depender
 * do prefixo "demo:". A própria sessão sai quando vem `session` (e, se vier, `account` também precisa
 * bater): o principal tem o sessionId atual da sessão (troca no /clear) e os subagentes guardam o da
 * sessão que os disparou; por garantia, um subagente também sai quando algum ancestral (parentId) é dela.
 */
export function modSummary(
  snap: OfficeSnapshot,
  isReal: (id: string) => boolean,
  version: string,
  own: { account?: string; session?: string } = {},
): ModSummary {
  const byId = new Map(snap.agents.map((a) => [a.id, a]));
  const rooms = new Map(snap.rooms.map((r) => [r.id, r.name]));
  const isOwn = (a: AgentInfo): boolean => {
    if (!own.session || (own.account && a.account !== own.account)) return false;
    let cur: AgentInfo | undefined = a;
    for (let hops = 0; cur && hops <= MAX_PARENT_HOPS; hops++) {
      if (cur.sessionId === own.session) return true;
      cur = cur.parentId ? byId.get(cur.parentId) : undefined;
    }
    return false;
  };
  const present = snap.agents.filter((a) => isReal(a.id) && a.status !== 'offline' && a.status !== 'done' && !isOwn(a));
  const waiting = present
    .filter((a) => a.status === 'waiting')
    .sort((a, b) => a.statusSince - b.statusSince || a.id.localeCompare(b.id))
    .map((a) => ({
      id: a.id,
      name: a.name,
      room: rooms.get(a.roomId) ?? a.roomId,
      account: a.account,
      waitingFor: a.waitingFor ?? tr('responder no terminal'),
      since: a.statusSince,
      answerable: !!a.permission,
    }));
  return { version, agents: present.length, working: present.filter((a) => a.status === 'working').length, waiting };
}

/** Devolve um handler que trata /api/* e responde false para o resto. */
export function createApiHandler(deps: ApiDeps): (req: IncomingMessage, res: ServerResponse, url: URL) => boolean {
  const { office, hub, accounts } = deps;
  const terminals = deps.terminal ? deps.terminals : undefined;

  const isRemote = (req: IncomingMessage): boolean => !!deps.remote?.is(req);
  /**
   * A trava "pelo próprio computador": na porta local, o Host precisa ser loopback (como antes); na porta remota, só o
   * que HABBLAUD_REMOTE_ALLOW liberar (`feature` ausente = nunca pela porta remota).
   */
  const trusted = (req: IncomingMessage, feature?: RemoteFeature): boolean =>
    isRemote(req) ? !!feature && !!deps.remote?.allows(feature) : isLoopbackHost(req.headers.host);
  const denied = (req: IncomingMessage, localMessage: string): string =>
    isRemote(req) ? tr('não liberado no acesso remoto (veja HABBLAUD_REMOTE_ALLOW)') : localMessage;

  const methodNotAllowed = (res: ServerResponse, allow: string) => {
    res.setHeader('Allow', allow);
    sendJson(res, 405, { error: tr('método não permitido') });
  };

  const handleDemo = async (req: IncomingMessage, res: ServerResponse) => {
    const body = (await readJson(req)) as { enabled?: unknown };
    if (typeof body?.enabled !== 'boolean') throw new HttpError(400, tr('esperado {enabled: boolean}'));
    office.setDemo(body.enabled);
    sendJson(res, 200, { ok: true, demo: office.isDemo() });
  };

  const handleOmpSettings = async (req: IncomingMessage, res: ServerResponse, set: (on: boolean) => boolean) => {
    const body = (await readJson(req)) as { roomByProject?: unknown };
    if (typeof body?.roomByProject !== 'boolean') throw new HttpError(400, tr('esperado {roomByProject: boolean}'));
    sendJson(res, 200, { ok: true, roomByProject: set(body.roomByProject) });
  };

  const handleCast = async (req: IncomingMessage, res: ServerResponse, method: string, id?: string, agentId?: string) => {
    if (agentId) {
      if (method === 'DELETE') {
        req.resume();
        if (office.undress(agentId) === 'not-found') return sendJson(res, 404, { error: NOT_EDITABLE });
        return sendJson(res, 200, { ok: true });
      }
      const body = await readJson(req) as { cast?: unknown } | null;
      if (typeof body?.cast !== 'string') throw new HttpError(400, tr('id inválido'));
      const result = office.dress(agentId, body.cast);
      if (result === 'not-found') return sendJson(res, 404, { error: NOT_EDITABLE });
      if (result === 'conflict') return sendJson(res, 409, { error: tr('personagem ocupado') });
      if (result === 'room') return sendJson(res, 422, { error: tr('personagem não trabalha nesta sala') });
      return sendJson(res, 200, { ok: true });
    }
    if (method === 'GET') return sendJson(res, 200, { members: office.cast.list() });
    if (method === 'DELETE') {
      req.resume();
      return sendJson(res, office.deleteCast(id!) ? 200 : 404, { ok: true });
    }
    const body = await readJson(req);
    if (!body || typeof body !== 'object' || Array.isArray(body)) throw new HttpError(400, tr('membro do elenco inválido'));
    const old = id ? office.cast.get(id) : undefined;
    if (id && !old) return sendJson(res, 404, { error: tr('membro do elenco não encontrado') });
    const data = body as Record<string, unknown>;
    if (data.home === null) delete old?.home;
    if (data.match === null) delete old?.match;
    if (data.home === null) delete data.home;
    if (data.match === null) delete data.match;
    for (const key of ['model', 'instructions', 'idleMinutes', 'account']) if (data[key] === null) {
      if (old) delete (old as unknown as Record<string, unknown>)[key];
      delete data[key];
    }
    if (data.kind !== undefined && data.kind !== 'main' && old) delete old.last;
    // "Criar a partir deste agente": o nome dele não conta como conflito e ele já veste o personagem criado.
    const from = method === 'POST' && typeof data.from === 'string' ? data.from : undefined;
    const member = parseCastMember({ ...old, ...data, id: id ?? office.cast.nextId(typeof data.name === 'string' ? data.name : '') });
    if (!member) throw new HttpError(400, tr('membro do elenco inválido'));
    if (office.putCast(member, from) === 'conflict') return sendJson(res, 409, { error: tr('nome já está em uso') });
    return sendJson(res, method === 'POST' ? 201 : 200, { member });
  };

  /**
   * Rodada do auxiliar do host: com `?wait=1`, a resposta espera trabalho (até HOST_WAIT_MS). Devolve undefined se a
   * conexão caiu enquanto esperava (nada sai da fila).
   */
  const hostWait = async <T>(res: ServerResponse, url: URL, poll: (ms: number, signal: AbortSignal) => Promise<T[]>): Promise<T[] | undefined> => {
    const ac = new AbortController();
    const abort = () => ac.abort();
    res.once('close', abort);
    try {
      const list = await poll(url.searchParams.get('wait') === '1' ? HOST_WAIT_MS : 0, ac.signal);
      return ac.signal.aborted ? undefined : list;
    } finally {
      res.off('close', abort);
    }
  };

  const fail = (res: ServerResponse, err: unknown) => {
    if (res.headersSent) return void res.destroy();
    if (err instanceof HttpError || err instanceof AttachmentError) sendJson(res, err.status, { error: err.message });
    else sendJson(res, 500, { error: tr('erro interno') });
  };

  return (req, res, url) => {
    const path = url.pathname;
    if (path !== '/api' && !path.startsWith('/api/')) return false;
    const method = req.method ?? 'GET';
    const isRead = method === 'GET' || method === 'HEAD';

    if (isRemote(req) && isLocalOnlyPath(path)) {
      req.resume();
      sendJson(res, 403, { error: tr('rota só do próprio computador (hooks, plugins e o auxiliar do host)') });
      return true;
    }
    if (path === '/api/avatar.png') {
      if (!isRead) methodNotAllowed(res, 'GET');
      else {
        const png = avatarPng(url.searchParams);
        if (!png) sendJson(res, 400, { error: tr('pedido inválido') });
        else {
          res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': png.length, 'Cache-Control': 'private, max-age=86400' });
          res.end(method === 'HEAD' ? undefined : png);
        }
      }
      return true;
    }
    if (path === '/api/world' || path.startsWith('/api/world/')) {
      // A conta do Habblaud World age em seu nome: só o próprio computador, como o terminal. O Host local sozinho não
      // basta (o Tailscale chega pelo loopback e o Host se imita): a porta remota nunca passa, nem com login.
      if (!deps.world) {
        req.resume();
        sendJson(res, 404, { error: tr('rota desconhecida') });
      } else if (isRemote(req) || !isLoopbackHost(req.headers.host)) {
        req.resume();
        sendJson(res, 403, { error: { code: 'local_only', message: tr('o Habblaud World só é usado pelo próprio computador (http://localhost)') } });
      } else deps.world(req, res, url);
      return true;
    }
    if (path === '/api/stream') {
      if (method !== 'GET') methodNotAllowed(res, 'GET');
      else hub.attach(req, res);
      return true;
    }
    if (path === '/api/snapshot') {
      if (!isRead) methodNotAllowed(res, 'GET');
      else sendJson(res, 200, hub.current());
      return true;
    }
    if (path === '/api/health') {
      if (!isRead) methodNotAllowed(res, 'GET');
      else {
        const remote = isRemote(req);
        sendJson(res, 200, {
          ok: true,
          version: deps.version,
          demo: office.isDemo(),
          docker: deps.inDocker,
          remote,
          terminal: !!terminals && (!remote || trusted(req, 'terminal')),
          permissions: !!deps.permissions && (!remote || trusted(req, 'permissions')),
          // O terminal interativo (pty) nunca vale pela porta remota.
          pty: !!deps.pty && !remote,
          messages: !!deps.messages && (!remote || trusted(req, 'messages')),
          summaryHttp: !!deps.summaries?.httpConfigured && trusted(req, 'messages'),
          codexEvents: !!deps.codexLive,
          ompEvents: !!deps.ompLive,
          // Modo sem marca (HABBLAUD_SEM_MARCA=1): o main.ts do navegador lê antes de montar a interface.
          semMarca: isNeutralBrand(),
          updates: updatesSummary(deps.updates?.status()),
          sources: deps.sources(),
          accounts: accounts.allEntries().map((a) =>
            a.provider === 'claude'
              ? { id: a.id, usageStatus: accounts.usageView(a.id).status }
              : { id: a.id, provider: a.provider, usageStatus: accounts.usageView(a.id).status },
          ),
        });
      }
      return true;
    }
    if (path === '/api/access') {
      if (!isRead) methodNotAllowed(res, 'GET');
      else {
        const remote = isRemote(req);
        const access: AccessInfo = {
          remote,
          local: !remote && isLoopbackHost(req.headers.host),
          permissions: !!deps.permissions && trusted(req, 'permissions'),
          messages: !!deps.messages && trusted(req, 'messages'),
          messageSessionGuard: !!deps.messages && trusted(req, 'messages'),
          terminal: !!terminals && trusted(req, 'terminal'),
          spawn: !!deps.spawn && trusted(req, 'spawn'),
          world: !!deps.world && !!deps.terminal && !remote && isLoopbackHost(req.headers.host),
          files: !!deps.browse && trusted(req, 'terminal'),
        };
        sendJson(res, 200, access);
      }
      return true;
    }
    if (path === '/api/mod/summary') {
      // Lido a cada 5 s por sessão com o mod: só contagens e quem espera (nada que o snapshot já não mostre).
      if (!isRead) methodNotAllowed(res, 'GET');
      else {
        const own = { account: url.searchParams.get('account') || undefined, session: url.searchParams.get('session') || undefined };
        sendJson(res, 200, modSummary(hub.current(), (id) => office.has(id), deps.version, own));
      }
      return true;
    }
    const terminalMatch = TERMINAL_ROUTE.exec(path);
    if (terminalMatch) {
      if (method !== 'GET') methodNotAllowed(res, 'GET');
      else if (!terminals) {
        sendJson(res, 403, { error: tr('terminal desligado: ele só funciona com o Habblaud acessível apenas pelo próprio computador') });
      } else if (!trusted(req, 'terminal')) {
        sendJson(res, 403, { error: denied(req, tr('o terminal só abre pelo próprio computador (http://localhost ou http://127.0.0.1)')) });
      } else {
        let id: string;
        try {
          id = decodeURIComponent(terminalMatch[1]);
        } catch {
          sendJson(res, 400, { error: tr('id inválido') });
          return true;
        }
        terminals.attach(req, res, id);
      }
      return true;
    }
    if (path.startsWith('/api/sessions/')) {
      handleSessionsRoute(req, res, path, {
        history: deps.terminal ? deps.sessions : undefined,
        terminals,
        trusted: isRemote(req) ? trusted(req, 'terminal') : undefined,
      });
      return true;
    }
    if (path === '/api/permissions' || path.startsWith('/api/permissions/')) {
      // Responder pelo escritório age sobre as sessões: a mesma trava do terminal (bind local + Host local).
      if (!deps.permissions) {
        sendJson(res, 403, { error: tr('responder pelo escritório desligado: só funciona com o Habblaud acessível apenas pelo próprio computador') });
      } else if (!trusted(req, 'permissions')) {
        sendJson(res, 403, { error: denied(req, tr('pedidos de permissão só são respondidos pelo próprio computador (http://localhost ou http://127.0.0.1)')) });
      } else {
        deps.permissions(req, res, path);
      }
      return true;
    }
    if (path === '/api/pty' || path.startsWith('/api/pty/') || AGENT_ACTION_ROUTE.test(path)) {
      // Executa comandos na máquina: nunca pela porta remota, nem com login (o Host e o endereço de quem conecta não
      // bastam: o Tailscale chega pelo loopback e o Host se imita).
      if (isRemote(req)) sendJson(res, 403, { error: tr('o terminal interativo só pode ser usado no próprio computador') });
      else if (!deps.pty) sendJson(res, 403, { error: tr('terminal interativo desligado (veja o log do servidor)') });
      else deps.pty(req, res, path);
      return true;
    }
    const castStart = /^\/api\/cast\/([a-z0-9-]{1,40})\/start$/.exec(path);
    const castDismiss = /^\/api\/agents\/([^/]+)\/dismiss$/.exec(path);
    if (path === '/api/cast/jobs' || path === '/api/cast/folders' || path === '/api/cast/dirs' || path === '/api/cast/new' || path === '/api/cast/roles' || castStart || castDismiss) {
      const launcher = deps.castLauncher;
      // Mesma trava do terminal: local, ou a porta remota com login e `terminal` em HABBLAUD_REMOTE_ALLOW.
      if (!deps.terminal || !trusted(req, 'terminal') || !launcher) sendJson(res, 403, { error: denied(req, tr('o elenco só pode ser editado pelo próprio computador')) });
      else if (path === '/api/cast/jobs') {
        if (!isRead) methodNotAllowed(res, 'GET');
        else sendJson(res, 200, { jobs: launcher.jobs(), available: launcher.available() });
      } else if (path === '/api/cast/roles' && isRead) {
        sendJson(res, 200, { available: launcher.available(), ...launcher.roles() });
      } else if (path === '/api/cast/folders') {
        // Pastas recentes (histórico de sessões + salas) para chamar o personagem, mesmo com "Novo agente" desligado.
        if (!isRead) methodNotAllowed(res, 'GET');
        else (deps.spawnFolders ?? (async () => []))().then((folders) => sendJson(res, 200, { folders }), (err) => fail(res, err));
      } else if (path === '/api/cast/dirs') {
        if (!isRead) methodNotAllowed(res, 'GET');
        else {
          const roots = (deps.castRoots ?? []).flatMap((root) => {
            try { return isAbsolute(root) && statSync(root).isDirectory() ? [realpathSync(root)] : []; } catch { return []; }
          });
          const requested = url.searchParams.get('path');
          if (!requested) sendJson(res, 200, { path: '', dirs: roots.map((root) => ({ name: basename(root) || root, path: root })) });
          else {
            try {
              if (!isAbsolute(requested) || requested.includes('\0') || /(^|[\\/])\.\.([\\/]|$)/.test(requested)) throw new Error();
              const real = realpathSync(requested);
              const root = roots.find((r) => real === r || !relative(r, real).startsWith('..' + sep) && relative(r, real) !== '..' && !isAbsolute(relative(r, real)));
              if (!root || !statSync(real).isDirectory() || relative(root, real).split(sep).some((part) => part.startsWith('.'))) throw new Error();
              const dirs = readdirSync(real).filter((name) => !name.startsWith('.')).sort((a, b) => a.localeCompare(b)).flatMap((name) => {
                try {
                  const target = realpathSync(join(real, name));
                  const rel = relative(root, target);
                  return rel !== '..' && !rel.startsWith('..' + sep) && !isAbsolute(rel) && !rel.split(sep).some((part) => part.startsWith('.')) && statSync(target).isDirectory() ? [{ name, path: join(real, name) }] : [];
                } catch { return []; }
              }).slice(0, 500);
              sendJson(res, 200, { path: real, ...(real !== root ? { parent: dirname(real) } : {}), dirs });
            } catch { sendJson(res, 404, { error: tr('pasta ou arquivo fora da pasta de trabalho') }); }
          }
        }
      } else if (method !== 'POST') methodNotAllowed(res, 'POST');
      else if (castDismiss) {
        let id: string;
        try { id = decodeURIComponent(castDismiss[1]); } catch { sendJson(res, 400, { error: tr('id inválido') }); return true; }
        const result = launcher.dismiss(office.get(id));
        if ('error' in result) sendJson(res, result.status, { error: result.error });
        else sendJson(res, 202, result);
      } else {
        readJson(req).then((body) => {
          // "Novo": mesma trava de chamar um personagem sem salas (qualquer pasta que o auxiliar ache no host).
          const result = path === '/api/cast/new' ? launcher.startNew(body) : path === '/api/cast/roles' ? launcher.setRoles(body) : launcher.start(castStart![1], body);
          if ('error' in result) sendJson(res, result.status, { error: result.error });
          else sendJson(res, 202, result);
        }).catch((err) => fail(res, err));
      }
      return true;
    }
    const castMatch = CAST_ROUTE.exec(path);
    const agentCastMatch = AGENT_CAST_ROUTE.exec(path);
    if (path === '/api/cast' || castMatch || agentCastMatch) {
      const allow = agentCastMatch ? 'PUT, DELETE' : castMatch ? 'PUT, DELETE' : 'GET, POST';
      if (!allow.split(', ').includes(method)) methodNotAllowed(res, allow);
      else if (!deps.terminal || !trusted(req, 'terminal')) sendJson(res, 403, { error: denied(req, tr('o elenco só pode ser editado pelo próprio computador')) });
      else {
        let agentId: string | undefined;
        try { agentId = agentCastMatch ? decodeURIComponent(agentCastMatch[1]) : undefined; }
        catch { sendJson(res, 400, { error: tr('id inválido') }); return true; }
        handleCast(req, res, method, castMatch?.[1], agentId).catch((err) => fail(res, err));
      }
      return true;
    }
    if (path === '/api/codex/events') {
      // Eventos dos hooks do Codex: só observam, mas só valem vindos do próprio computador. Fora do Docker o hook
      // sempre conecta pelo loopback (o Host sozinho um cliente da rede consegue imitar); no Docker ele chega pela porta
      // publicada, com o endereço do gateway.
      if (method !== 'POST') methodNotAllowed(res, 'POST');
      else if (!trusted(req) || (!deps.inDocker && !isLoopbackAddress(req.socket.remoteAddress))) {
        sendJson(res, 403, { error: denied(req, tr('eventos do Codex só são aceitos pelo próprio computador (http://localhost ou http://127.0.0.1)')) });
      } else handleCodexEvent(req, res, { live: deps.codexLive, entries: () => accounts.entriesOf('codex') }).catch((err) => fail(res, err));
      return true;
    }
    if (path === '/api/omp/events') {
      // Eventos da extensão do omp: só observam, mas só valem vindos do próprio computador (a mesma trava do Codex).
      if (method !== 'POST') methodNotAllowed(res, 'POST');
      else if (!trusted(req) || (!deps.inDocker && !isLoopbackAddress(req.socket.remoteAddress))) {
        sendJson(res, 403, { error: denied(req, tr('eventos do omp só são aceitos pelo próprio computador (http://localhost ou http://127.0.0.1)')) });
      } else handleOmpEvent(req, res, { live: deps.ompLive }).catch((err) => fail(res, err));
      return true;
    }
    if (path === '/api/omp/aside') {
      // A resposta do bate-papo paralelo vem da extensão do omp: a mesma trava dos eventos dela (só o próprio computador).
      if (method !== 'POST') methodNotAllowed(res, 'POST');
      else if (!deps.asides) sendJson(res, 403, { error: MESSAGES_OFF() });
      else if (!trusted(req) || (!deps.inDocker && !isLoopbackAddress(req.socket.remoteAddress))) {
        sendJson(res, 403, { error: denied(req, tr('perguntas paralelas só pelo próprio computador (http://localhost ou http://127.0.0.1)')) });
      } else deps.asides.progress(req, res).catch((err) => fail(res, err));
      return true;
    }
    if (path === '/api/asides' || path.startsWith('/api/asides/')) {
      // Perguntar manda texto ao modelo (a trava das mensagens); ler mostra a conversa (a trava do terminal).
      if (!deps.asides) sendJson(res, 403, { error: MESSAGES_OFF() });
      else if (!trusted(req, isRead ? 'terminal' : 'messages')) {
        sendJson(res, 403, { error: denied(req, tr('perguntas paralelas só pelo próprio computador (http://localhost ou http://127.0.0.1)')) });
      } else deps.asides.page(req, res, path, url);
      return true;
    }
    if (path === '/api/summaries') {
      // Manda texto ao modelo (HTTP configurado ou papel tiny do omp): a mesma trava das mensagens.
      if (method !== 'POST') methodNotAllowed(res, 'POST');
      else if (!deps.summaries) sendJson(res, 404, { error: tr('resumo indisponível: omp ou conta do omp não encontrados') });
      else if (!trusted(req, 'messages')) sendJson(res, 403, { error: denied(req, tr('o resumo só pode ser pedido pelo próprio computador')) });
      else {
        const summaries = deps.summaries;
        readJson(req).then(async (body) => {
          const text = body && typeof body === 'object' ? (body as Record<string, unknown>).text : undefined;
          if (typeof text !== 'string' || !text.trim() || text.length > SUMMARY_INPUT_MAX) return sendJson(res, 422, { error: tr('pedido inválido') });
          try { sendJson(res, 200, { summary: await summaries.summarize(text) }); }
          catch (err) { sendJson(res, err instanceof SummaryUnavailable ? 503 : 502, { error: err instanceof SummaryUnavailable ? err.message : tr('falha ao resumir: {0}', [err instanceof Error ? err.message : String(err)]) }); }
        }).catch((err) => fail(res, err));
      }
      return true;
    }
    if (path === '/api/spawn' || path.startsWith('/api/spawn/')) {
      // Abre uma sessão nova no app do Claude: roda código no computador, então a mesma trava das mensagens.
      const spawn = deps.spawn;
      if (!spawn) sendJson(res, 403, { error: tr('novo agente pelo escritório desligado') });
      else if (!trusted(req, 'spawn')) sendJson(res, 403, { error: denied(req, tr('agentes novos só são abertos pelo próprio computador (http://localhost)')) });
      else if (path === '/api/spawn' && isRead) sendJson(res, 200, { available: spawn.available(), mode: spawn.mode() });
      else if (path === '/api/spawn/folders') {
        if (!isRead) methodNotAllowed(res, 'GET');
        else (deps.spawnFolders ?? (async () => []))().then((folders) => sendJson(res, 200, { folders }), (err) => fail(res, err));
      } else if (path === '/api/spawn/attachments') {
        const store = deps.attachments;
        if (method !== 'POST') methodNotAllowed(res, 'POST');
        else if (!store) sendJson(res, 403, { error: tr('anexos desligados') });
        else store.receive(req, 'spawn').then(result => sendJson(res, 201, result)).catch(err => fail(res, err));
      }
      else if (path === '/api/spawn' && method === 'POST') {
        readJson(req)
          .then((body) => {
            const r = spawn.submit(body);
            if ('spawn' in r) return sendJson(res, 201, r.spawn);
            sendJson(res, r.error === 'invalid' ? 400 : r.error === 'too-many' ? 429 : 409, { error: r.reason });
          })
          .catch((err) => fail(res, err));
      } else if (path !== '/api/spawn' && isRead) {
        const info = spawn.get(decodeURIComponent(path.slice('/api/spawn/'.length)));
        if (info) sendJson(res, 200, info);
        else sendJson(res, 404, { error: tr('pedido desconhecido') });
      } else methodNotAllowed(res, path === '/api/spawn' ? 'GET, POST' : 'GET');
      return true;
    }
    if (path === '/api/dictation' || path.startsWith('/api/dictation/')) {
      // Ditado do "Novo agente" e da caixa de mensagem: vale com qualquer uma das duas travas (o texto vira um pedido
      // ou uma mensagem, que continuam com a trava delas ao serem enviados).
      const dictation = deps.dictation;
      if (!dictation) sendJson(res, 403, { error: tr('ditado desligado') });
      else if (!trusted(req, 'spawn') && !trusted(req, 'messages')) {
        sendJson(res, 403, { error: denied(req, tr('o ditado só funciona pelo próprio computador (http://localhost)')) });
      }
      else if (path === '/api/dictation' && isRead) sendJson(res, 200, { available: dictation.available() });
      else if (path === '/api/dictation' && method === 'POST') {
        readJson(req, AUDIO_BODY_MAX)
          .then((body) => {
            const r = dictation.submit(body);
            if ('dictation' in r) return sendJson(res, 201, r.dictation);
            sendJson(res, r.error === 'invalid' ? 400 : r.error === 'too-many' ? 429 : 409, { error: r.reason });
          })
          .catch((err) => fail(res, err));
      } else if (path !== '/api/dictation' && isRead) {
        const info = dictation.get(decodeURIComponent(path.slice('/api/dictation/'.length)));
        if (info) sendJson(res, 200, info);
        else sendJson(res, 404, { error: tr('ditado desconhecido') });
      } else methodNotAllowed(res, path === '/api/dictation' ? 'GET, POST' : 'GET');
      return true;
    }
    if (path === '/api/files' || path.startsWith('/api/files/')) {
      // Arquivos da pasta de trabalho: a mesma trava do terminal.
      const browse = deps.browse;
      if (!browse) sendJson(res, 403, { error: tr('arquivos desligados (precisam do terminal: acesso local, fora do Docker)') });
      else if (!isRead) methodNotAllowed(res, 'GET');
      else if (!trusted(req, 'terminal')) sendJson(res, 403, { error: denied(req, tr('os arquivos só abrem pelo próprio computador (http://localhost)')) });
      else {
        try {
          handleFilesRoute(req, res, url, browse);
        } catch (err) {
          fail(res, err);
        }
      }
      return true;
    }
    if (path === '/api/deliveries' || path.startsWith('/api/deliveries/')) {
      // Entregas mostram a resposta inteira e arquivos do agente: a mesma trava do terminal.
      const deliveries = deps.deliveries;
      if (!deliveries) sendJson(res, 403, { error: tr('entregas desligadas (precisam do terminal: acesso local)') });
      else if (!isRead) methodNotAllowed(res, 'GET');
      else if (!trusted(req, 'terminal')) sendJson(res, 403, { error: denied(req, tr('as entregas só abrem pelo próprio computador (http://localhost)')) });
      else if (path === '/api/deliveries') deliveries.list().then((list) => sendJson(res, 200, { deliveries: list }), (err) => fail(res, err));
      else if (path.startsWith('/api/deliveries/file/')) {
        const att = deliveries.attachment(decodeURIComponent(path.slice('/api/deliveries/file/'.length)));
        if (!att) sendJson(res, 404, { error: tr('anexo desconhecido: atualize a lista de entregas') });
        else if (att.kind === 'capture') sendFile(res, att.data, att.type, att.name);
        else if (!deps.files) sendJson(res, 404, { error: tr('arquivos desligados') });
        else
          deps.files.get(att.path).then(
            (r) => ('data' in r ? sendFile(res, r.data, att.type, att.name) : sendJson(res, r.status, { error: r.error })),
            (err) => fail(res, err),
          );
      } else sendJson(res, 404, { error: tr('rota desconhecida') });
      return true;
    }
    if (path === '/api/host/files/poll' || path === '/api/host/files/ack') {
      // Só o auxiliar do host, pela porta local (nunca pela remota); fora do Docker, só pelo loopback.
      const files = deps.files;
      if (method !== 'POST') methodNotAllowed(res, 'POST');
      else if (!files) sendJson(res, 403, { error: tr('arquivos desligados') });
      else if (!trusted(req) || (!deps.inDocker && !isLoopbackAddress(req.socket.remoteAddress))) {
        sendJson(res, 403, { error: denied(req, tr('só o auxiliar do próprio computador')) });
      } else if (path === '/api/host/files/poll') {
        req.resume();
        hostWait(res, url, (ms, signal) => files.pollWait(ms, signal)).then((list) => list && sendJson(res, 200, { files: list }), (err) => fail(res, err));
      } else {
        readJson(req, 36 * 1024 * 1024)
          .then((body) => {
            files.ack(body);
            sendJson(res, 200, { ok: true });
          })
          .catch((err) => fail(res, err));
      }
      return true;
    }
    if (path === '/api/host/dictation/poll' || path === '/api/host/dictation/ack') {
      // Só o auxiliar do host, pela porta local (nunca pela remota); fora do Docker, só pelo loopback.
      const dictation = deps.dictation;
      if (method !== 'POST') methodNotAllowed(res, 'POST');
      else if (!dictation) sendJson(res, 403, { error: tr('ditado desligado') });
      else if (!trusted(req) || (!deps.inDocker && !isLoopbackAddress(req.socket.remoteAddress))) {
        sendJson(res, 403, { error: denied(req, tr('só o auxiliar do próprio computador')) });
      } else if (path === '/api/host/dictation/poll') {
        req.resume();
        hostWait(res, url, (ms, signal) => dictation.pollWait(ms, signal)).then((list) => list && sendJson(res, 200, { dictations: list }), (err) => fail(res, err));
      } else {
        readJson(req, 512 * 1024)
          .then((body) => {
            dictation.ack(body);
            sendJson(res, 200, { ok: true });
          })
          .catch((err) => fail(res, err));
      }
      return true;
    }
    if (path === '/api/host/cast/poll' || path === '/api/host/cast/ack') {
      const launcher = deps.castLauncher;
      if (method !== 'POST') methodNotAllowed(res, 'POST');
      else if (!launcher) sendJson(res, 403, { error: tr('desligado') });
      else if (!trusted(req) || (!deps.inDocker && !isLoopbackAddress(req.socket.remoteAddress))) sendJson(res, 403, { error: denied(req, tr('só o auxiliar do próprio computador')) });
      else readJson(req).then((body) => {
        if (path === '/api/host/cast/poll') sendJson(res, 200, { jobs: launcher.poll(body), rolesDir: launcher.rolesDir() });
        else { launcher.ack(body); sendJson(res, 200, { ok: true }); }
      }).catch((err) => fail(res, err));
      return true;
    }
    if (path === '/api/host/spawn/poll' || path === '/api/host/spawn/ack') {
      // Só o auxiliar do host, pela porta local (nunca pela remota); fora do Docker, só pelo loopback.
      const spawn = deps.spawn;
      if (method !== 'POST') methodNotAllowed(res, 'POST');
      else if (!spawn) sendJson(res, 403, { error: tr('novo agente pelo escritório desligado') });
      else if (!trusted(req) || (!deps.inDocker && !isLoopbackAddress(req.socket.remoteAddress))) {
        sendJson(res, 403, { error: denied(req, tr('só o auxiliar do próprio computador')) });
      } else if (path === '/api/host/spawn/poll') {
        req.resume();
        sendJson(res, 200, { spawns: spawn.poll() });
      } else {
        readJson(req)
          .then((body) => {
            spawn.ack(body);
            sendJson(res, 200, { ok: true });
          })
          .catch((err) => fail(res, err));
      }
      return true;
    }
    const attachmentMatch = /^\/api\/codex\/bridge\/files\/([^/]+)\/attachments\/([^/]+)$/.exec(path);
    if (attachmentMatch) {
      if (method !== 'GET') methodNotAllowed(res, 'GET');
      else if (!trusted(req) || (!deps.inDocker && !isLoopbackAddress(req.socket.remoteAddress))) {
        sendJson(res, 403, { error: denied(req, tr('só o auxiliar do próprio computador')) });
      } else {
        const [, job, file] = attachmentMatch;
        const transfer = deps.files?.attachment(job, file, res) ?? deps.spawn?.attachment(job, file, res);
        if (!transfer) sendJson(res, 404, { error: tr('arquivo não encontrado') });
        else transfer.catch(err => fail(res, err));
      }
      return true;
    }
    if (isMessagesPath(path)) {
      // As mensagens entram na sessão como se você as tivesse digitado: a mesma trava (recurso ligado + Host local).
      if (!deps.messages) {
        sendJson(res, 403, {
          error: tr('mensagens pelo escritório desligadas: só funcionam com o Habblaud acessível apenas pelo próprio computador (e sem HABBLAUD_MENSAGENS=0)'),
        });
      } else if (!trusted(req, 'messages')) {
        sendJson(res, 403, { error: denied(req, tr('mensagens só são mandadas pelo próprio computador (http://localhost ou http://127.0.0.1)')) });
      } else {
        deps.messages(req, res, path);
      }
      return true;
    }
    if (path.startsWith('/api/agents/')) {
      if (!isRead) {
        methodNotAllowed(res, 'GET');
        return true;
      }
      let id: string;
      try {
        id = decodeURIComponent(path.slice('/api/agents/'.length));
      } catch {
        sendJson(res, 400, { error: tr('id inválido') });
        return true;
      }
      const detail = office.detail(id);
      if (detail) sendJson(res, 200, detail);
      else sendJson(res, 404, { error: tr('agente não encontrado') });
      return true;
    }
    if (path === '/api/rooms/rename') {
      // Como o personagem: a mesma trava do terminal (bind local + Host local).
      if (method !== 'POST') methodNotAllowed(res, 'POST');
      else if (!deps.terminal) sendJson(res, 403, { error: tr('renomear salas desligado: só funciona com o Habblaud acessível apenas pelo próprio computador') });
      else if (!trusted(req)) sendJson(res, 403, { error: denied(req, tr('renomear salas só pelo próprio computador (http://localhost)')) });
      else
        readJson(req)
          .then((body) => {
            const b = body as { id?: unknown; name?: unknown };
            if (typeof b?.id !== 'string' || typeof b.name !== 'string') throw new HttpError(400, tr('esperado {id, name}'));
            const name = deps.renameRoom?.(b.id, b.name);
            if (name === undefined) throw new HttpError(404, tr('sala não encontrada'));
            sendJson(res, 200, { name });
          })
          .catch((err) => fail(res, err));
      return true;
    }
    if (path === '/api/stats' || path.startsWith('/api/stats/')) {
      handleStatsRoute(req, res, url, deps.stats, sendJson);
      return true;
    }
    if (path === '/api/demo') {
      // Liga o modo demonstração para todo mundo: só pelo computador (o celular com login só vê o que foi liberado).
      if (method !== 'POST') methodNotAllowed(res, 'POST');
      else if (isRemote(req)) sendJson(res, 403, { error: tr('não liberado no acesso remoto (veja HABBLAUD_REMOTE_ALLOW)') });
      else handleDemo(req, res).catch((err) => fail(res, err));
      return true;
    }
    if (path === '/api/omp/settings') {
      // Muda a sala de todos os agentes do omp: só pelo computador (isLocalOnlyPath já barra a porta remota).
      if (!deps.setOmpRoomByProject) sendJson(res, 404, { error: tr('desligado') });
      else if (method !== 'POST') methodNotAllowed(res, 'POST');
      else handleOmpSettings(req, res, deps.setOmpRoomByProject).catch((err) => fail(res, err));
      return true;
    }
    if (path === '/api/updates') {
      if (!isRead) methodNotAllowed(res, 'GET');
      else sendJson(res, 200, { version: deps.version, ...(deps.updates?.status() ?? { state: 'off', available: false }) });
      return true;
    }
    if (path === '/api/updates/check') {
      // "Verificar agora": consulta o GitHub (no máximo uma vez a cada 30 s) e devolve o status novo.
      if (method !== 'POST') methodNotAllowed(res, 'POST');
      else {
        req.resume();
        const updates = deps.updates;
        if (!updates) sendJson(res, 200, { version: deps.version, state: 'off', available: false });
        else
          updates
            .check({ manual: true })
            .then((status) => sendJson(res, 200, { version: deps.version, ...status }))
            .catch((err) => fail(res, err));
      }
      return true;
    }
    if (deps.timeline?.(req, res, url)) return true;
    sendJson(res, 404, { error: tr('rota desconhecida') });
    return true;
  };
}
