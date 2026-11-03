import { inflateSync } from 'node:zlib';
import { describe, expect, it, vi } from 'vitest';
import { avatarCanvas } from '../../client/src/art/index';
import { avatarPng } from './avatar';

function pixels(png: Buffer): Buffer {
  const width = png.readUInt32BE(16), height = png.readUInt32BE(20);
  let offset = 8;
  const idat: Buffer[] = [];
  while (offset < png.length) {
    const length = png.readUInt32BE(offset), type = png.toString('ascii', offset + 4, offset + 8);
    if (type === 'IDAT') idat.push(png.subarray(offset + 8, offset + 8 + length));
    offset += length + 12;
  }
  const rows = inflateSync(Buffer.concat(idat));
  const out = Buffer.alloc(width * height * 4);
  for (let y = 0; y < height; y++) {
    expect(rows[y * (width * 4 + 1)]).toBe(0);
    rows.copy(out, y * width * 4, y * (width * 4 + 1) + 1, (y + 1) * (width * 4 + 1));
  }
  return out;
}

describe('avatar PNG', () => {
  it('é determinístico e tem os mesmos pixels do canvas', () => {
    let canvasData: Uint8ClampedArray | undefined;
    vi.stubGlobal('ImageData', class { constructor(public data: Uint8ClampedArray) {} });
    vi.stubGlobal('document', { createElement: () => ({ getContext: () => ({ putImageData: (image: { data: Uint8ClampedArray }) => { canvasData = image.data; } }) }) });
    try {
      avatarCanvas(42, { look: 'f', scale: 3, parts: { hairStyle: 'bun' } });
      const query = new URLSearchParams({ seed: '42', look: 'f', kind: 'main', scale: '3', parts: JSON.stringify({ hairStyle: 'bun' }) });
      const png = avatarPng(query)!;
      expect(png.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a');
      expect(png.readUInt32BE(16)).toBe(60);
      expect(png.readUInt32BE(20)).toBe(60);
      expect(pixels(png)).toEqual(Buffer.from(canvasData!));
      expect(avatarPng(query)).toEqual(png);
    } finally { vi.unstubAllGlobals(); }
  });
  it.each(['seed=-1&look=f&kind=main&scale=1', 'seed=4294967296&look=f&kind=main&scale=1', 'seed=1&look=x&kind=main&scale=1', 'seed=1&look=f&kind=sub&scale=17', 'seed=1&look=f&kind=main&scale=1&parts=%7B%22skin%22%3A%22oops%22%7D', 'seed=1&seed=2&look=f&kind=main&scale=1'])('rejeita %s', (query) => {
    expect(avatarPng(new URLSearchParams(query))).toBeNull();
  });
});
