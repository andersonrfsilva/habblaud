import { afterEach, describe, expect, it } from 'vitest';
import { getLocale, intlLocale, normalizeLocale, serverLocale, setLocale, setNeutralBrand, tr, unbrand } from './i18n';
import { EN } from './locales/en';
import { ES } from './locales/es';
import { FR } from './locales/fr';

const placeholders = (s: string) => new Set(s.match(/\{\w+\}/g) ?? []);

describe('i18n', () => {
  afterEach(() => setLocale('pt-BR'));

  it('os testes rodam em PT, qualquer que seja o idioma da máquina', () => {
    expect(getLocale()).toBe('pt-BR');
  });

  it('reconhece os formatos de idioma do navegador e do sistema', () => {
    expect(normalizeLocale('fr')).toBe('fr');
    expect(normalizeLocale('fr-CA')).toBe('fr');
    expect(normalizeLocale('fr_FR.UTF-8')).toBe('fr');
    expect(normalizeLocale('pt')).toBe('pt-BR');
    expect(normalizeLocale('pt_PT')).toBe('pt-BR');
    expect(normalizeLocale('en-US')).toBe('en');
    expect(normalizeLocale('es_AR.UTF-8')).toBe('es');
    expect(normalizeLocale('de-DE')).toBeUndefined();
    expect(normalizeLocale('')).toBeUndefined();
    expect(normalizeLocale(null)).toBeUndefined();
  });

  it('em PT devolve o próprio texto, com as lacunas preenchidas', () => {
    expect(tr('Lendo {0}', ['app.ts'])).toBe('Lendo app.ts');
    expect(tr('Texto que ninguém traduziu')).toBe('Texto que ninguém traduziu');
  });

  it('em francês traduz e pode reordenar as lacunas', () => {
    setLocale('fr');
    expect(tr('Lendo {0}', ['app.ts'])).toBe('Lit app.ts');
    expect(tr('Texto que ninguém traduziu')).toBe('Texto que ninguém traduziu');
    expect(intlLocale()).toBe('fr-FR');
  });

  it('lacunas sem argumento ficam como estão (as nomeadas são preenchidas por quem chama)', () => {
    expect(tr('Ganhei de {a} a {b}! 🏆')).toBe('Ganhei de {a} a {b}! 🏆');
    expect(tr('{0} e {1}', ['um'])).toBe('um e {1}');
  });

  it('servidor: HABBLAUD_LANG vale para qualquer idioma; do sistema, o inglês não conta', () => {
    expect(serverLocale({ HABBLAUD_LANG: 'en' }, 'pt-BR')).toBe('en');
    expect(serverLocale({ HABBLAUD_LANG: 'es-MX', LANG: 'fr_FR.UTF-8' })).toBe('es');
    // Mac com LANG em inglês e o container sem LANG (Intl en-US): o escritório fica em português.
    expect(serverLocale({ LANG: 'en_US.UTF-8' }, 'en-US')).toBe('pt-BR');
    expect(serverLocale({}, 'en-US')).toBe('pt-BR');
    expect(serverLocale({ LANG: 'en_US.UTF-8' }, 'pt-BR')).toBe('pt-BR');
    expect(serverLocale({ LANG: 'fr_FR.UTF-8' })).toBe('fr');
    expect(serverLocale({ LC_ALL: 'es_ES.UTF-8', LANG: 'en_US.UTF-8' })).toBe('es');
    expect(serverLocale({}, 'es-AR')).toBe('es');
  });

  it('inglês e espanhol traduzem e usam o formato de data do idioma', () => {
    setLocale('en');
    expect(intlLocale()).toBe('en-US');
    setLocale('es');
    expect(intlLocale()).toBe('es');
  });

  it('nenhuma tradução fica vazia nem perde ou inventa lacunas', () => {
    for (const [lang, dict] of Object.entries({ en: EN, es: ES, fr: FR })) {
      for (const [pt, t] of Object.entries(dict)) {
        expect(t.trim(), `${lang}: ${pt}`).not.toBe('');
        const original = placeholders(pt);
        for (const p of placeholders(t)) expect(original.has(p), `${lang}: ${pt} -> ${t}`).toBe(true);
        if (lang !== 'fr') for (const p of original) expect(placeholders(t).has(p), `${lang}: ${pt} -> ${t}`).toBe(true);
      }
    }
  });
});

describe('modo sem marca', () => {
  afterEach(() => {
    setNeutralBrand(false);
    setLocale('pt-BR');
  });

  it('desligado, nada muda', () => {
    expect(tr('Comandos do omp')).toBe('Comandos do omp');
  });

  it('troca a ferramenta por "agente" sem estragar comando, caminho, id nem lacuna', () => {
    setNeutralBrand(true);
    expect(tr('Comandos do omp')).toBe('Comandos do agente');
    expect(unbrand('Agente principal (omp)')).toBe('Agente principal');
    expect(unbrand('Agente do omp (oh-my-pi)')).toBe('Agente');
    expect(unbrand('omp: falha no ciclo de leitura: {0}')).toBe('Agente: falha no ciclo de leitura: {0}');
    expect(unbrand('Só do Claude Code: o Codex não grava custo')).toBe('Só do agente: o agente não grava custo');
    expect(unbrand('Os agentes do Codex têm o chip vazado')).toBe('Os agentes têm o chip vazado');
    expect(unbrand('npm run omp:install, ~/.omp/agent, omp.json, claude-opus-5')).toBe('npm run omp:install, ~/.omp/agent, omp.json, claude-opus-5');
    expect(unbrand('o omp usage')).toBe('o medidor de uso');
    // A lacuna recebe o texto do usuário como veio: só o modelo do texto passa pelo filtro.
    expect(tr('{0} enviado ao omp.', ['/model claude'])).toBe('/model claude enviado ao agente.');
  });

  it('em inglês, "agent"', () => {
    setNeutralBrand(true);
    setLocale('en');
    expect(unbrand('Agent providers in omp')).toBe('Agent providers in agent');
    expect(unbrand('your Claude Code agents')).toBe('your agents');
  });
});
