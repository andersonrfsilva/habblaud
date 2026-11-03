import type { AppearanceParts } from '../../../../shared/appearance';
import { appearanceFromSeed } from '../../../../shared/world/appearance-flat';
import { PixelBuf } from '../core/pixbuf';
import { CHAR_W, renderCharacter } from './render';

/** Mesmo busto e escala por vizinho mais próximo no navegador e no servidor. */
export function avatarPixels(seed: number, opts: { look?: 'f' | 'm'; sub?: boolean; scale?: number; parts?: AppearanceParts } = {}): PixelBuf {
  const scale = Math.max(1, Math.round(opts.scale ?? 3));
  const sprite = renderCharacter({ appearance: appearanceFromSeed(seed, opts), dir: 'down', pose: 'stand', frame: 0 });
  const crop = sprite.buf.crop(2, 2, CHAR_W - 4, 20);
  const out = new PixelBuf(crop.w * scale, crop.h * scale);
  for (let y = 0; y < out.h; y++) for (let x = 0; x < out.w; x++) {
    const source = (Math.floor(y / scale) * crop.w + Math.floor(x / scale)) * 4;
    const target = (y * out.w + x) * 4;
    out.data[target] = crop.data[source];
    out.data[target + 1] = crop.data[source + 1];
    out.data[target + 2] = crop.data[source + 2];
    out.data[target + 3] = crop.data[source + 3];
  }
  return out;
}
