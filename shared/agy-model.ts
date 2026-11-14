export const AGY_PREFIX = 'agy/';
export const AGY_DEFAULT = 'agy/default';
/** Modelo que roda no Antigravity CLI. `google-antigravity/...` (do omp) não é. */
export function isAgyModel(model: string | undefined): boolean {
  return !!model?.startsWith(AGY_PREFIX) && model.length > AGY_PREFIX.length;
}
/** Valor a mandar ao agy; `undefined` para `agy/default`. Só chame com isAgyModel verdadeiro. */
export function agyModelValue(model: string): string | undefined {
  return model === AGY_DEFAULT ? undefined : model.slice(AGY_PREFIX.length);
}
export function toAgyModel(value: string): string { return `${AGY_PREFIX}${value}`; }
