// Ritmo do laço do escritório: 60 qps só quando a câmera se mexe ou há entrada; parado, 30 (15 sem foco); com a
// economia de energia, parado a 10 (5 sem foco).
// Puro (sem DOM): testado em pacing.test.ts.

/** Escritório parado, janela com foco. */
export const IDLE_FPS = 30;
/** Escritório parado, janela sem foco (ex.: num segundo monitor). */
export const UNFOCUSED_FPS = 15;
/** Escritório parado com a economia de energia, janela com foco (digitar troca de pose a cada 120 ms). */
export const STILL_FPS = 10;
/** Escritório parado com a economia de energia, janela sem foco. */
export const STILL_UNFOCUSED_FPS = 5;
/** Por quanto tempo depois de uma entrada (mouse, roda, toque, teclado) o laço segue no ritmo do monitor. */
export const ACTIVE_GRACE_MS = 1000;

/**
 * Limite de quadros por segundo (0 = o do monitor). `uiCap` é o limite pedido pela interface (`setFrameCap`, ex.: 30
 * com o terminal aberto); `active` = câmera em movimento, entrada recente ou timelapse; `powerSave` = preferência
 * "Economia de energia".
 */
export function frameCapFor(s: { uiCap: number; active: boolean; focused: boolean; powerSave: boolean }): number {
  if (s.active) return s.uiCap;
  const idle = s.powerSave ? (s.focused ? STILL_FPS : STILL_UNFOCUSED_FPS) : s.focused ? IDLE_FPS : UNFOCUSED_FPS;
  return s.uiCap > 0 ? Math.min(s.uiCap, idle) : idle;
}

/**
 * Abaixo de 30 qps o laço dorme num setTimeout em vez de pular quadros do requestAnimationFrame: o rAF acorda a
 * página 60 vezes por segundo mesmo sem desenhar (medido: ~8% de CPU a 1 qps).
 */
export function sleepsBetweenFrames(cap: number): boolean {
  return cap > 0 && cap < IDLE_FPS;
}
