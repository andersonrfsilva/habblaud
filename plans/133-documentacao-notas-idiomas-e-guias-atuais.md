# Plano 133: Documentação: notas de "Não lançado" consolidadas, guias do Zaplaud atualizados e ES/FR sem afirmações falsas (substitui o 088)

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 133 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 3152f8c..HEAD -- CHANGELOG.md README.pt-BR.md README.md README.es.md README.fr.md docs/zaplaud-paridade.md zaplaud-rs/README.md CONTRIBUTING.md`
> Se algum arquivo do escopo mudou desde `3152f8c`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: M
- **Risco**: LOW
- **Depende de**: 132
- **Categoria**: docs
- **Planejado em**: commit `3152f8c`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O plano 088 (2026-11-14) mandava traduzir para os quatro idiomas um contrato que incluía o Antigravity. O dono
decidiu em 2026-11-19 que não há usuários de outro idioma: tradução completa de ES/FR deixou de valer o custo. O
que continua valendo: as notas de "Não lançado" se contradizem (anunciam coisas que a mesma seção registra como
removidas), os guias não explicam recursos novos do Zaplaud, e os READMEs ES/FR afirmam coisas hoje falsas — pior
do que não dizer nada.

## Estado atual

Leia `plans/087-088-levantamento-2026-11-19.md` (seções "088" e "Fatos posteriores"). Resumo:

- **`CHANGELOG.md`, "Não lançado"**: `:173-176` diz que novas chamadas vão "sempre" para a home; `:236-238` dá
  prioridade à pasta aberta; `:311-322` anuncia seleção de pasta que a seção registra como removida; `:72-93`
  recomenda `:desktop:run`, script antigo, JVM e pacotes Compose (removidos em 2026-11-18); `:41-42` diz que
  imagens do markdown viram link, contra `:27-30`.
- **Guia PT** (`README.pt-BR.md`): seção Android (`:256-413`) e iPadOS (`:419-526`). Faltam: Entregas da sessão
  (menu da conversa), "Acesso neste servidor" em Configurações (diagnóstico, não muda permissão), aviso de
  bloqueio de notificações pelo sistema; `:312-331` diz que o CI roda só em `main` (roda em `main` e `producao`,
  com emulador API 35 e artefatos de diagnóstico: `.github/workflows/android.yml`). O título do iPadOS diz
  "validação pendente": o workflow `iPadOS` compila e roda o smoke no simulador; aparelho físico continua não validado.
- **Guia Rust** (`zaplaud-rs/README.md`): faltam Entregas, "Acesso neste servidor" e o que o CI roda (três roteiros
  gráficos nos dois temas com Xvfb e chaveiro em memória, e empacotamento Linux): `.github/workflows/zaplaud-rs.yml`.
- **EN** (`README.md`): não tem as seções Android/iPadOS do PT; só links. O link "Android e iPadOS" abre só a seção
  Android nos três idiomas não-PT (`README.md:32-34`, `README.es.md:32-34`, `README.fr.md:32-34`).
- **ES/FR, afirmações falsas**: novas chamadas "para a home" (`README.es.md:633-651`, `README.fr.md:633-653`);
  "elenco só local" (ES; FR na seção de privacidade, contradizendo a própria seção de elenco).
- **`CONTRIBUTING.md:7-9`** manda issues ao upstream (se houver dúvida sobre o desejado para o fork, não mude e relate).
- **Matriz** (`docs/zaplaud-paridade.md:80-85`): lista comandos sem distinguir o que é provado por teste
  automatizado, por CI, ou só por conferência manual.
- `npm run i18n:check -- --strict` **não** valida Markdown (só chaves `tr(...)` em TypeScript).

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-133 -b advisor/133-documentacao-notas-idiomas-e-guias-atuais producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Gate | `npm run i18n:check -- --strict` | sai com 0 |

## Escopo

**Pode modificar**:
- os arquivos listados em "Arquivos afetados"

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Tradução completa das seções móveis para EN/ES/FR (decisão do dono).
 - Reescrever notas de versões já lançadas. Código. `plans/`.

## Passos

### Passo 1: consolidar "Não lançado"

A seção passa a descrever o **estado final**: para cada recurso, uma nota verdadeira hoje. Notas que se anulam
(anunciado e depois removido na mesma seção) somem ou viram uma linha em "Removido". Corrija as cinco passagens
do "Estado atual". Não apague recurso vivo: antes de remover uma nota, confirme no código que o recurso não existe.

**Verificação**: `sed -n '/Não lançado/,/^## \[/p' CHANGELOG.md | grep -n -i 'desktop:run\|jvm\|sempre.*home'` → nada;
liste em NOTES cada nota removida ou reescrita e o motivo.

### Passo 2: guia PT do Zaplaud

Android/iPadOS: uma subseção curta para Entregas da sessão (onde abre, o que mostra, download; exige permissão de
terminal no servidor) e uma para "Acesso neste servidor" (o que cada linha significa e o que pedir ao operador);
aviso de bloqueio de notificações; CI correto (ramos, emulador, artefatos); título do iPadOS sem "validação
pendente", dizendo o que o CI prova e o que só um iPad físico prova. Se o plano 131 já estiver mesclado, o
parágrafo de reinício do aparelho já foi corrigido por ele: não duplique.

**Verificação**: `grep -n 'Entregas\|Acesso neste servidor' README.pt-BR.md` mostra as subseções; âncoras do sumário conferidas.

### Passo 3: guia Rust

`zaplaud-rs/README.md`: Entregas, "Acesso neste servidor", e uma seção curta "O que o CI verifica" (e o que não
verifica: Wayland, chaveiro real, bandeja, macOS).

**Verificação**: os nomes de jobs e passos citados existem em `.github/workflows/zaplaud-rs.yml`.

### Passo 4: EN, ES e FR: verdade mínima

EN: resumo de um parágrafo do Zaplaud Android e outro do iPadOS com link para a seção PT correspondente, e links
separados (Android, iPadOS, desktop Rust) no lugar do link combinado. ES/FR: os mesmos links separados e a
correção das afirmações falsas (pasta das novas chamadas; elenco não é só local), com o texto mínimo necessário,
no idioma do arquivo. Não traduza o resto.

**Verificação**: cada link relativo novo abre arquivo e âncora existentes; `grep -n -i 'antigravity' README.es.md README.fr.md` → nada novo.

### Passo 5: matriz: o que prova o quê

Em `docs/zaplaud-paridade.md`, a seção de verificação passa a ter três grupos: testes automatizados locais,
workflows do GitHub (Android com instrumentados, iPadOS, Zaplaud Rust, CI) e conferência manual pendente (aparelho
físico, desktop real). Use `plans/README.md` como fonte, sem copiar contagens.

**Verificação**: `npm ci && npm run i18n:check -- --strict` sai com 0; `git diff --stat` só com os arquivos do escopo.

## Plano de testes

Nenhum.

## Critérios de pronto

Todos precisam valer:

- [ ] "Não lançado" sem contradições internas
 - [ ] Guias PT e Rust cobrem Entregas, Acesso e CI
 - [ ] EN/ES/FR sem afirmações falsas e com links separados
 - [ ] Matriz separa automatizado, CI e manual
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 133 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `3152f8c`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- Uma nota do changelog não puder ser classificada (recurso existe ou não) pela leitura do código.
 - Corrigir ES/FR exigir reescrever seções inteiras: faça o mínimo e relate o que ficou desatualizado.

## Notas de manutenção

- ES/FR continuam atrás de EN/PT por decisão; se aparecerem usuários desses idiomas, tradução completa vira plano novo.
