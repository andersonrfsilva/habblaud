import { randomUUID } from 'node:crypto';
import { appendFileSync, writeFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { isAbsolute, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

if (process.env.FAKE_AGY_ARGV_FILE) writeFileSync(process.env.FAKE_AGY_ARGV_FILE, JSON.stringify(process.argv.slice(2)));
const send = message => process.stdout.write(JSON.stringify({ jsonrpc: '2.0', ...message }) + '\n');
const sessions = new Map();
const permissions = new Map();
const configOptions = currentValue => [{ id: 'model', currentValue, options: [{ value: 'fake-a', name: 'Fake A' }, { value: 'fake-b', name: 'Fake B' }] }];
let sequence = 0;
let delayedNew;
const update = (sessionId, value) => send({ method: 'session/update', params: { sessionId, update: value } });
const finish = (sessionId, stopReason = 'end_turn') => {
  const session = sessions.get(sessionId);
  if (!session?.turn) return;
  send({ id: session.turn, result: { stopReason, usage: { inputTokens: 10, outputTokens: 1 } } });
  delete session.turn;
};
const completeTool = (sessionId, toolCall, allow) => {
  update(sessionId, { ...toolCall, sessionUpdate: 'tool_call_update', status: allow ? 'completed' : 'failed', rawOutput: { output: allow ? 'ok' : '' } });
  finish(sessionId);
};
const lines = createInterface({ input: process.stdin });
lines.on('line', line => {
  const message = JSON.parse(line);
  if (process.env.FAKE_AGY_TRACE_FILE) appendFileSync(process.env.FAKE_AGY_TRACE_FILE, line + '\n');
  const { id, method, params = {} } = message;
  if (!method) {
    const permission = permissions.get(id);
    if (!permission) return;
    permissions.delete(id);
    if (message.result?.outcome?.outcome === 'cancelled') finish(permission.sessionId, 'cancelled');
    else completeTool(permission.sessionId, permission.toolCall, message.result?.outcome?.optionId === 'yes');
    return;
  }
  switch (method) {
    case 'initialize':
      send({ id, result: { protocolVersion: 1, agentInfo: { name: 'Antigravity ACP Server', version: '1.5.9' }, agentCapabilities: { sessionCapabilities: { resume: {}, close: {}, load: {}, list: {}, additionalDirectories: {} } } } });
      break;
    case 'session/new':
    case 'session/resume': {
      if (process.env.FAKE_AGY_AUTH === '0') { send({ id, error: { code: -32000, message: 'Authentication required' } }); break; }
      const sessionId = params.sessionId ?? randomUUID();
      sessions.set(sessionId, { cwd: params.cwd, additionalDirectories: params.additionalDirectories ?? [], model: 'fake-a' });
      const response = { id, result: { sessionId, configOptions: configOptions('fake-a'), models: { currentModelId: 'fake-model', availableModels: [{ modelId: 'fake-model', name: 'Fake' }] }, _meta: { antigravity: { tier: { name: 'Test' } } } } };
      if (method === 'session/new' && process.env.FAKE_AGY_REVERSE_NEW_PAIR === '1') {
        if (!delayedNew) { delayedNew = response; break; }
        send(response);
        send(delayedNew);
        delayedNew = undefined;
      } else send(response);
      break;
    }
    case 'session/set_config_option': {
      if (!['fake-a', 'fake-b'].includes(params.value)) {
        send({ id, error: { code: -32602, message: `Unknown model ID: ${params.value}` } }); break;
      }
      sessions.get(params.sessionId).model = params.value;
      send({ id, result: { configOptions: configOptions(params.value) } });
      update(params.sessionId, { sessionUpdate: 'config_option_update', configOptions: configOptions(params.value) });
      break;
    }
    case 'session/list':
      send({ id, result: { sessions: [...sessions].map(([sessionId, session]) => ({ sessionId, cwd: session.cwd })) } });
      break;
    case 'session/close':
      sessions.delete(params.sessionId); send({ id, result: {} });
      break;
    case 'session/prompt': {
      const sessionId = params.sessionId;
      const session = sessions.get(sessionId);
      session.turn = id;
      const text = params.prompt.filter(block => block.type === 'text').map(block => block.text).join('\n');
      for (const content of params.prompt) update(sessionId, { sessionUpdate: 'user_message_chunk', content });
      if (text === 'sleep') break;
      const outside = params.prompt.find(block => {
        if (block.type !== 'resource_link') return false;
        const path = fileURLToPath(block.uri);
        return ![session.cwd, ...session.additionalDirectories].some(directory => {
          const child = relative(directory, path);
          return child !== '..' && !child.startsWith('../') && !isAbsolute(child);
        });
      });
      if (text.startsWith('tool:') || outside) {
        const toolCall = outside
          ? { sessionUpdate: 'tool_call', toolCallId: `call_${++sequence}`, name: 'read_file', kind: 'read', title: `File ${fileURLToPath(outside.uri)}`, rawInput: { Path: fileURLToPath(outside.uri) }, status: 'in_progress' }
          : { sessionUpdate: 'tool_call', toolCallId: `call_${++sequence}`, name: 'run_command', kind: 'execute', title: text.slice(5), rawInput: { CommandLine: text.slice(5) }, status: 'in_progress' };
        update(sessionId, toolCall);
        if (process.argv.includes('--dangerously-skip-permissions')) { completeTool(sessionId, toolCall, true); break; }
        update(sessionId, { ...toolCall, sessionUpdate: 'tool_call_update', status: 'pending' });
        const requestId = `permission-${sequence}`;
        permissions.set(requestId, { sessionId, toolCall });
        send({ id: requestId, method: 'session/request_permission', params: { sessionId, toolCall: { ...toolCall, status: 'pending' }, options: [{ optionId: 'yes', name: 'Allow', kind: 'allow_once' }, { optionId: 'no', name: 'Deny', kind: 'reject_once' }] } });
      } else {
        update(sessionId, { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'ok' } });
        finish(sessionId);
      }
      break;
    }
    case 'session/cancel':
      if (![...permissions.values()].some(permission => permission.sessionId === params.sessionId)) finish(params.sessionId, 'cancelled');
      break;
    default:
      send({ id, error: { code: -32601, message: 'Method not found' } });
  }
});
lines.on('close', () => process.exit(0));
