import { randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { AGY_DEFAULT, toAgyModel } from '../../../shared/agy-model';

export class AntigravityCatalog {
  private values: string[] = [];
  private readonly root: string;
  constructor(dataDir: string) {
    this.root = join(dataDir, 'antigravity');
    try {
      const parsed: unknown = JSON.parse(readFileSync(join(this.root, 'models.json'), 'utf8'));
      if (parsed && typeof parsed === 'object' && 'values' in parsed && Array.isArray(parsed.values)
        && parsed.values.every(value => typeof value === 'string' && !!value && !/[\s\x00-\x1f\x7f]/.test(value))) {
        this.values = [...new Set(parsed.values as string[])];
      }
    } catch { /* An absent or unreadable cache must not prevent startup. */ }
  }
  learn(options: { value: string; name: string }[]): void {
    const values = [...new Set(options.map(option => option.value))];
    if (JSON.stringify(values) === JSON.stringify(this.values)) return;
    mkdirSync(this.root, { recursive: true, mode: 0o700 });
    const temporary = join(this.root, `models.${randomUUID()}.tmp`);
    try {
      writeFileSync(temporary, JSON.stringify({ values }), { mode: 0o600 });
      renameSync(temporary, join(this.root, 'models.json'));
      this.values = values;
    } finally { rmSync(temporary, { force: true }); }
  }
  agyModels(): string[] { return [AGY_DEFAULT, ...this.values.filter(value => toAgyModel(value) !== AGY_DEFAULT).map(toAgyModel)]; }
}
