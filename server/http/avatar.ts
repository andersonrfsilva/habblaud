import { deflateSync } from 'node:zlib';
import { avatarPixels } from '../../client/src/art/character/avatar';
import { PART_KEYS, parseAppearanceParts, type AppearanceParts } from '../../shared/appearance';

function crc32(data: Uint8Array): number {
  let crc = 0xffffffff;
  for (const byte of data) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type: string, data: Buffer): Buffer {
  const name = Buffer.from(type, 'ascii');
  const length = Buffer.alloc(4); length.writeUInt32BE(data.length);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([name, data])));
  return Buffer.concat([length, name, data, crc]);
}

/** PNG determinístico sem dependências nativas; null indica parâmetros inválidos. */
export function avatarPng(query: URLSearchParams): Buffer | null {
  const allowed = ['seed', 'look', 'kind', 'parts', 'scale'];
  if ([...query.keys()].some((key) => !allowed.includes(key) || query.getAll(key).length !== 1)) return null;
  const seed = query.get('seed');
  const look = query.get('look');
  const kind = query.get('kind');
  const scale = query.get('scale');
  if (!seed || !/^(0|[1-9]\d{0,9})$/.test(seed) || Number(seed) > 0xffffffff ||
      (look !== 'f' && look !== 'm') || (kind !== 'main' && kind !== 'sub') ||
      !scale || !/^(?:[1-9]|1[0-6])$/.test(scale)) return null;
  let parts: AppearanceParts | undefined;
  const raw = query.get('parts');
  if (raw) {
    if (raw.length > 1024) return null;
    try {
      const values: unknown = raw.startsWith('{') ? JSON.parse(raw) : (() => {
        const fields = raw.split(',');
        if (fields.length !== PART_KEYS.length) return null;
        return Object.fromEntries(fields.flatMap((value, i) => value ? [[PART_KEYS[i], value]] : []));
      })();
      const parsed = parseAppearanceParts(values);
      if (!parsed) return null;
      parts = parsed;
    } catch { return null; }
  }
  const image = avatarPixels(Number(seed), { look, sub: kind === 'sub', scale: Number(scale), parts });
  const header = Buffer.alloc(13);
  header.writeUInt32BE(image.w, 0); header.writeUInt32BE(image.h, 4);
  header[8] = 8; header[9] = 6;
  const stride = image.w * 4;
  const rows = Buffer.alloc((stride + 1) * image.h);
  for (let y = 0; y < image.h; y++) rows.set(image.data.subarray(y * stride, (y + 1) * stride), y * (stride + 1) + 1);
  return Buffer.concat([Buffer.from('89504e470d0a1a0a', 'hex'), chunk('IHDR', header), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]);
}
