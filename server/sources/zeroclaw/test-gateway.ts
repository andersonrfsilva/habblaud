import { createHash } from 'node:crypto';
import http from 'node:http';
import type { AddressInfo, Socket } from 'node:net';

export interface GatewayConnection {
  url: URL; sessionId: string; messages: Record<string, unknown>[]; open: boolean;
  send(frame: unknown): void;
  raw(text: string): void;
  close(code?: number): void;
  drop(): void;
}
export interface GatewayResponse { status?: number; body: unknown; headers?: Record<string, string>; delayMs?: number; wait?: Promise<void> }
export interface TestGateway {
  url: string;
  connections: GatewayConnection[];
  requests: { method: string; path: string; at: number }[];
  sessions: Record<string, unknown>[];
  messages: Map<string, unknown[]>;
  states: Map<string, 'running' | 'idle' | 'error'>;
  aborts: Map<string, 'aborted' | 'no_active_response'>;
  responses: Map<string, GatewayResponse>;
  options: { autoStart: boolean; resumed: boolean; sessionId?: string; status: number; retryAfter: string; abortFrame: boolean; sessionsShape: 'object' | 'array' };
  onConnection?: (connection: GatewayConnection) => void;
  close(): Promise<void>;
}
function encode(opcode: number, payload: Buffer): Buffer {
  const length = payload.length;
  const header = Buffer.alloc(length < 126 ? 2 : length < 65536 ? 4 : 10);
  header[0] = 0x80 | opcode;
  if (length < 126) header[1] = length;
  else if (length < 65536) { header[1] = 126; header.writeUInt16BE(length, 2); }
  else { header[1] = 127; header.writeBigUInt64BE(BigInt(length), 2); }
  return Buffer.concat([header, payload]);
}
/** RFC6455 loopback fixture, with no model, tenant, or websocket package. */
export async function startTestGateway(): Promise<TestGateway> {
  const sockets = new Set<Socket>();
  const delayed = new Set<NodeJS.Timeout>();
  const server = http.createServer((request, response) => {
    const path = new URL(request.url!, 'http://localhost').pathname;
    const method = request.method ?? 'GET';
    gateway.requests.push({ method, path, at: Date.now() });
    let reply = gateway.responses.get(`${method} ${path}`) ?? gateway.responses.get(path);
    if (!reply && gateway.options.status !== 200) reply = { status: gateway.options.status, body: { error: 'unavailable' }, headers: { 'Retry-After': gateway.options.retryAfter } };
    if (!reply) {
      const match = /^\/api\/sessions\/([^/]+)\/(messages|state|abort)$/.exec(path);
      if (path === '/api/sessions') reply = { body: gateway.options.sessionsShape === 'array' ? gateway.sessions : { sessions: gateway.sessions } };
      else if (path === '/api/sessions/running') reply = { body: { sessions: [...gateway.states].filter(([, state]) => state === 'running').map(([session_id]) => ({ session_id, state: 'running' })) } };
      else if (match?.[2] === 'messages') reply = { body: { messages: gateway.messages.get(match[1]) ?? [] } };
      else if (match?.[2] === 'state') reply = { body: { session_id: match[1], state: gateway.states.get(match[1]) ?? 'idle' } };
      else if (match?.[2] === 'abort' && method === 'POST') {
        const status = gateway.aborts.get(match[1]) ?? (gateway.states.get(match[1]) === 'running' ? 'aborted' : 'no_active_response');
        reply = { body: { status } };
        if (status === 'aborted' && gateway.options.abortFrame) {
          gateway.states.set(match[1], 'idle');
          for (const connection of gateway.connections) if (connection.sessionId === match[1]) connection.send({ type: 'aborted' });
        }
      } else reply = { status: 404, body: { error: 'not_found' } };
    }
    const selected = reply;
    const send = () => { if (!response.destroyed) { response.writeHead(selected.status ?? 200, { 'Content-Type': 'application/json', ...selected.headers }); response.end(JSON.stringify(selected.body)); } };
    if (selected.delayMs) {
      const timer = setTimeout(() => { delayed.delete(timer); send(); }, selected.delayMs); delayed.add(timer);
    } else if (selected.wait) void selected.wait.then(send);
    else send();
  });
  server.on('connection', (socket) => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
  const gateway: TestGateway = {
    url: '', connections: [], requests: [], sessions: [], messages: new Map(), states: new Map(), aborts: new Map(), responses: new Map(),
    options: { autoStart: true, resumed: false, status: 200, retryAfter: '1', abortFrame: false, sessionsShape: 'object' },
    async close() {
      for (const timer of delayed) clearTimeout(timer);
      for (const socket of sockets) socket.destroy();
      const closed = Promise.withResolvers<void>(); server.close(() => closed.resolve()); await closed.promise;
    },
  };
  server.on('upgrade', (request, socket: Socket, head) => {
    const key = request.headers['sec-websocket-key'];
    const url = new URL(request.url!, gateway.url);
    if (url.pathname !== '/ws/chat' || typeof key !== 'string') { socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n'); return; }
    const accept = createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
    socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
    socket.setNoDelay(true);
    const connection: GatewayConnection = {
      url, sessionId: url.searchParams.get('session_id')!, messages: [], open: true,
      send(value) { connection.raw(JSON.stringify(value)); },
      raw(text) { if (connection.open) socket.write(encode(1, Buffer.from(text))); },
      close(code = 1001) {
        if (!connection.open) return;
        const payload = Buffer.alloc(2); payload.writeUInt16BE(code);
        socket.end(encode(8, payload)); connection.open = false;
      },
      drop() { connection.open = false; socket.destroy(); },
    };
    gateway.connections.push(connection);
    let buffered = Buffer.alloc(0); let fragments: Buffer[] = [];
    const receive = (chunk: Buffer) => {
      buffered = Buffer.concat([buffered, chunk]);
      while (buffered.length >= 2) {
        const final = !!(buffered[0] & 0x80); const opcode = buffered[0] & 15; const masked = !!(buffered[1] & 0x80);
        let length = buffered[1] & 127; let offset = 2;
        if (length === 126) { if (buffered.length < 4) return; length = buffered.readUInt16BE(2); offset = 4; }
        else if (length === 127) { if (buffered.length < 10) return; length = Number(buffered.readBigUInt64BE(2)); offset = 10; }
        if (length > 16 * 1024 * 1024) { connection.close(1009); return; }
        const maskOffset = offset; if (masked) offset += 4;
        if (buffered.length < offset + length) return;
        const payload = Buffer.from(buffered.subarray(offset, offset + length));
        if (masked) for (let index = 0; index < length; index++) payload[index] ^= buffered[maskOffset + index % 4];
        buffered = buffered.subarray(offset + length);
        if (opcode === 8) { connection.close(1000); return; }
        if (opcode === 9) { socket.write(encode(10, payload)); continue; }
        if (opcode === 1 || opcode === 0) {
          fragments.push(payload);
          if (!final) continue;
          const text = Buffer.concat(fragments).toString('utf8'); fragments = [];
          try { connection.messages.push(JSON.parse(text) as Record<string, unknown>); } catch { connection.close(1007); }
        }
      }
    };
    socket.on('data', receive);
    socket.on('error', () => { connection.open = false; });
    socket.on('close', () => { connection.open = false; });
    if (head.length) receive(head);
    if (gateway.options.autoStart) connection.send({ type: 'session_start', session_id: gateway.options.sessionId ?? connection.sessionId, resumed: gateway.options.resumed, message_count: 0, name: url.searchParams.get('name') });
    gateway.onConnection?.(connection);
  });
  const listening = Promise.withResolvers<void>();
  server.listen(0, '127.0.0.1', () => listening.resolve()); await listening.promise;
  gateway.url = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return gateway;
}
export const startFakeGateway = startTestGateway;
