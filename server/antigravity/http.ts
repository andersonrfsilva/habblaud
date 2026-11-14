import type { IncomingMessage, ServerResponse } from 'node:http';
import { isAbsolute } from 'node:path';
import { tr } from '../../shared/i18n';
import { HttpError, readJson, sendJson } from '../http/app';
import type { MessageRegistry } from '../messages/registry';
import type { Office } from '../model/office';
import { normalizeCwd } from '../model/rooms';
import { PROMPT_MAX } from '../spawn/spawn';
import { AntigravityClient, AuthRequired } from './acp';

export interface AntigravityHttpDeps {
  client: AntigravityClient;
  office: Office;
  messages?: MessageRegistry;
  socket?: string;
  skipPermissions: boolean;
}
export async function handleAntigravityRoute(req: IncomingMessage, res: ServerResponse, path: string, deps: AntigravityHttpDeps): Promise<void> {
  try {
    if (path === '/api/antigravity' && req.method === 'GET') {
      sendJson(res, 200, { available: true, skipPermissions: deps.socket ? null : deps.skipPermissions });
    } else if (path === '/api/antigravity/sessions' && req.method === 'GET') {
      sendJson(res, 200, { sessions: await deps.client.list() });
    } else if (path === '/api/antigravity/sessions' && req.method === 'POST') {
      const raw = await readJson(req);
      if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new HttpError(422, tr('pedido inválido'));
      const body = raw as Record<string, unknown>;
      const folder = typeof body.folder === 'string' ? normalizeCwd(body.folder) : '';
      if (!isAbsolute(folder) || folder.length > 1024 || folder.includes('\0') || (body.prompt !== undefined && (typeof body.prompt !== 'string' || body.prompt.length > PROMPT_MAX)) || (body.session !== undefined && (typeof body.session !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(body.session)))) throw new HttpError(422, tr('pedido inválido'));
      if (typeof body.prompt === 'string' && body.prompt.trim() && !deps.messages) throw new HttpError(503, tr('Antigravity: mensagens pelo escritório desligadas'));
      const { sessionId } = await deps.client.open({ cwd: folder, resume: typeof body.session === 'string' ? body.session : undefined });
      const agent = deps.office.list().find(agent => agent.provider === 'antigravity' && agent.sessionId === sessionId);
      if (!agent) throw new HttpError(503, tr('Antigravity: agente não entrou no escritório'));
      if (typeof body.prompt === 'string' && body.prompt.trim()) {
        const result = deps.messages!.send({ agentId: agent.id, text: body.prompt });
        if ('error' in result) throw new HttpError(503, 'reason' in result ? result.reason : tr('Antigravity não confirmou a entrega da mensagem'));
      }
      sendJson(res, 201, { agentId: agent.id, sessionId });
    } else if (path === '/api/antigravity' || path === '/api/antigravity/sessions') {
      res.setHeader('Allow', path === '/api/antigravity' ? 'GET' : 'GET, POST');
      sendJson(res, 405, { error: tr('método não permitido') });
    } else sendJson(res, 404, { error: tr('rota desconhecida') });
  } catch (error) {
    if (error instanceof AuthRequired) sendJson(res, 409, { error: tr('entre na conta do Antigravity no computador: rode agy e faça o login') });
    else if (error instanceof HttpError) sendJson(res, error.status, { error: error.message });
    else sendJson(res, 503, { error: deps.socket
      ? tr('Antigravity: soquete indisponível; inicie o relé com npm run antigravity:relay')
      : tr('Antigravity: binário indisponível; instale o agy oficial ou configure HABBLAUD_ANTIGRAVITY_BIN') });
  }
}
