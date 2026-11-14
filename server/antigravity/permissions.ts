import type { AntigravityClient, AcpEvent } from './acp';
import type { PermissionRegistry } from '../permissions/registry';
import { antigravityTool } from '../sources/antigravity/mirror';

type Permission = Extract<AcpEvent, { type: 'permission' }>;
type Active = { event: Permission; id?: string; cancel?: () => void; timer?: NodeJS.Timeout };
export class AntigravityPermissions {
  private readonly active = new Map<number | string, Active>();
  private readonly unsubscribe: () => void;
  constructor(private readonly opts: { client: Pick<AntigravityClient, 'onEvent' | 'session' | 'answerPermission'>; registry: PermissionRegistry }) {
    this.unsubscribe = opts.client.onEvent(event => {
      if (event.type === 'permission') {
        const active: Active = { event };
        this.active.set(event.requestId, active);
        void this.register(active);
      } else if (event.type === 'permission-gone') this.remove(event.requestId);
    });
  }
  stop(): void {
    this.unsubscribe();
    for (const id of this.active.keys()) this.remove(id);
  }
  private remove(requestId: number | string): void {
    const active = this.active.get(requestId);
    if (!active) return;
    this.active.delete(requestId);
    clearTimeout(active.timer);
    active.cancel?.();
    if (active.id) this.opts.registry.decide(active.id, { behavior: 'terminal' });
  }
  private exists(active: Active): boolean { return this.active.get(active.event.requestId) === active; }
  private retry(active: Active): void {
    if (!this.exists(active)) return;
    active.timer = setTimeout(() => { void this.register(active); }, 3000);
    active.timer.unref();
  }
  private async register(active: Active): Promise<void> {
    if (!this.exists(active)) return;
    const { event } = active;
    const tool = antigravityTool(event.toolCall);
    const result = this.opts.registry.register({ provider: 'antigravity', session_id: event.sessionId, tool_name: tool.name,
      tool_input: tool.input, cwd: this.opts.client.session(event.sessionId)?.cwd, timeout_ms: 24 * 60 * 60_000 });
    if ('skip' in result) { this.retry(active); return; }
    active.id = result.id;
    while (this.exists(active)) {
      const wait = this.opts.registry.wait(result.id, 25_000);
      if (!wait) break;
      active.cancel = wait.cancel;
      const decision = await wait.result;
      active.cancel = undefined;
      if (!this.exists(active)) return;
      if (decision.status === 'pending') continue;
      if (decision.status === 'decided' && (decision.behavior === 'allow' || decision.behavior === 'deny')) {
        this.opts.client.answerPermission(event.sessionId, event.requestId, decision.behavior === 'allow' ? 'allow_once' : 'reject_once');
        this.active.delete(event.requestId);
        return;
      }
      break;
    }
    active.id = undefined;
    this.retry(active);
  }
}
