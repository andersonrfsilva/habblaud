import { renderMermaidSVG } from 'beautiful-mermaid';

export const MERMAID_SOURCE_MAX = 20_000;

/** Resolve as cores CSS: os leitores SVG nativos não entendem var() nem color-mix(). */
export function mermaidSvg(source: string): string {
  let svg = renderMermaidSVG(source, { bg: '#ffffff', fg: '#27272a', font: 'sans-serif' });
  const variables = new Map<string, string>();
  for (const match of svg.matchAll(/(--[\w-]+)\s*:\s*([^;}"\n]+)/g)) variables.set(match[1], match[2].trim());
  function resolve(value: string): string {
    for (let pass = 0; pass < 20 && /var\(|color-mix\(/.test(value); pass++) {
      value = value.replace(/var\((--[\w-]+)(?:,\s*([^()]*))?\)/g, (whole, name: string, fallback: string) => variables.get(name) ?? fallback ?? whole);
      value = value.replace(/color-mix\(in srgb,\s*#([\da-f]{6})\s+(\d+)%,\s*#([\da-f]{6})(?:\s+(\d+)%)?\)/gi, (_, a: string, weight: string, b: string) => {
        const ratio = Number(weight) / 100;
        return '#' + [0, 2, 4].map(offset => Math.round(parseInt(a.slice(offset, offset + 2), 16) * ratio + parseInt(b.slice(offset, offset + 2), 16) * (1 - ratio)).toString(16).padStart(2, '0')).join('');
      });
    }
    return value;
  }
  svg = svg.replace(/var\((--[\w-]+)\)/g, (whole, name: string) => resolve(variables.get(name) ?? whole));
  // Sem fontes remotas; remove também as declarações CSS já substituídas.
  // A URL do @import tem ';' (wght@400;500): cortar só até o primeiro deixaria lixo no CSS.
  svg = svg.replace(/@import\s+url\([^)]*\)\s*;?/g, '').replace(/--[\w-]+\s*:[^;}"\n]+;?/g, '').replace(/\/\*[\s\S]*?\*\//g, '');
  // AndroidSVG recusa orient="auto-start-reverse" (SVG 2) e descarta o desenho inteiro: o equivalente é "auto"
  // com o conteúdo girado 180° em volta do ponto de referência (o marcador só é usado como marker-start).
  return svg.replace(/<marker([^>]*?)\sorient="auto-start-reverse"([^>]*)>([\s\S]*?)<\/marker>/g, (_, before: string, after: string, body: string) => {
    const attrs = before + after;
    const ref = (name: string) => attrs.match(new RegExp(`\\b${name}="([^"]*)"`))?.[1] ?? '0';
    return `<marker${before} orient="auto"${after}><g transform="rotate(180 ${ref('refX')} ${ref('refY')})">${body}</g></marker>`;
  });
}
