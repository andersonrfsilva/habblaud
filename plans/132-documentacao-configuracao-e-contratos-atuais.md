# Plano 132: Documentação: configuração e contratos do servidor como estão hoje (substitui o 087)

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 132 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 3152f8c..HEAD -- server/README.md README.md README.pt-BR.md docs/zaplaud-paridade.md`
> Se algum arquivo do escopo mudou desde `3152f8c`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: M
- **Risco**: LOW (só documentação; risco é afirmar o que o código não faz)
- **Depende de**: nenhum (substitui o 087)
- **Categoria**: docs
- **Planejado em**: commit `3152f8c`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O plano 087 (2026-11-14) ficou obsoleto: exigia documentar a integração com o Antigravity, removida em `b9f3308`.
As lacunas reais continuam: quem instala o servidor pelos READMEs não descobre doze variáveis de ambiente que o
código lê (ZeroClaw, resumo por HTTP, cota, elenco), nem três rotas do elenco, nem a capacidade `files` de
`/api/access`; e a matriz de paridade afirma uma regra de pasta que o servidor não aplica.

## Estado atual

Leia primeiro `plans/087-088-levantamento-2026-11-19.md` (seção "087"): cada lacuna tem ali as referências
`arquivo:linha` no código e nos documentos. Resumo:

- **Rotas do elenco**: `server/README.md:222-231` já descreve `GET /api/cast/models`, `POST /api/cast/start` (com
  `model?`) e `POST /api/cast/new`; os resumos de API em `README.md:999-1009` e `README.pt-BR.md:1267-1277` não.
  A linha do catálogo não menciona `named` nem liga à seção de agentes fixos (`server/README.md:52-81`).
- **`/api/access`**: o campo `files` (navegador de arquivos disponível **e** permissão `terminal` da conexão,
  `server/http/app.ts:480-497`) falta em `server/README.md:240`, `README.md:1024`, `README.pt-BR.md:1292`.
- **Doze variáveis** ausentes das tabelas de `README.md:814-854` e `README.pt-BR.md:1082-1123`, todas já descritas
  em `server/README.md`: `HABBLAUD_ZEROCLAW_URL`, `_AGENTS`, `_DEFAULT_AGENT`, `_NAMED`; `HABBLAUD_SUMMARY_URL`,
  `_MODEL`, `_TOKEN_FILE`, `_AUTH`; `HABBLAUD_QUOTA_URL`, `_TOKEN_FILE`; `HABBLAUD_CAST_MAX`, `HABBLAUD_CAST_FOLDER`.
  Regras em `server/config.ts:23-39,284-325,345`.
- **Contêiner**: `docker-compose.yml:30-59` não repassa essas doze; `deploy/podman/habblaud.container:32-35` tem
  `CAST_FOLDER` comentada e `EnvironmentFile`. Os READMEs não avisam nem dizem como injetar e montar os arquivos de token.
- **Elenco**: o texto diz que dispensas não contam para o limite; no ZeroClaw contam até fechar
  (`server/README.md:88-93`, `server/cast/launcher.ts:155-161`).
- **Matriz**: `docs/zaplaud-paridade.md:32` diz "pasta da conversa aberta vem primeiro"; o servidor ignora a sala na
  criação e usa a pasta da conversa só na retomada (`server/cast/launcher.ts:59-114`). `:64-68` ainda cita "JVM".
- **Obsoleto, não escrever**: Antigravity (`agy/...`, `agyModels`, troca de ferramenta, espelho) e instruções do
  desktop Compose. Docker Compose (o orquestrador) **não** foi removido.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-132 -b advisor/132-documentacao-configuracao-e-contratos-atuais producao`. Remova ao terminar.
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
- `server/README.md`, `README.md`, `README.pt-BR.md`, `docs/zaplaud-paridade.md`
 - `CHANGELOG.md` só se uma nota de "Não lançado" afirmar o contrário do que for corrigido (uma linha)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Código, `docker-compose.yml`, Quadlet (documentar o limite, não mudar). `README.es.md`/`README.fr.md` (plano 133). `plans/`.

## Passos

### Passo 1: conferir cada afirmação no código

Para cada item do "Estado atual", abra o código citado e confirme a regra **hoje** (as linhas podem ter andado).
Monte uma tabela em NOTES: afirmação → `arquivo:linha` que a sustenta. O que não se confirmar não entra no texto.

**Verificação**: tabela completa; nenhuma afirmação sem referência.

### Passo 2: `server/README.md` como referência única

Complete `files` em `/api/access`; ligue a linha do catálogo a `named`/agentes fixos; deixe explícito no `start`
o que vale para OMP (modelo salvo do personagem; `model` no corpo é recusado) e para ZeroClaw (alias ou modelo
do catálogo; retomar mantém o original; agente fixo usa `agent`).

**Verificação**: `grep -n 'files' server/README.md` mostra o campo na tabela de `/api/access`.

### Passo 3: READMEs EN e PT

Tabelas de configuração: as doze variáveis, cada uma com valor padrão, regra de validação em uma frase e link
para a seção do `server/README.md` (não duplique o contrato). Resumo de API: as três rotas do elenco e `files`.
Elenco: corrija a frase das dispensas. Junto das integrações, um aviso curto: o `docker-compose.yml` e o Quadlet
do repositório não repassam essas variáveis; para usá-las é preciso acrescentá-las e montar os arquivos de token
no caminho que o processo enxerga. Mesmo conteúdo nos dois idiomas.

**Verificação**: `for v in ZEROCLAW_URL ZEROCLAW_AGENTS ZEROCLAW_DEFAULT_AGENT ZEROCLAW_NAMED SUMMARY_URL SUMMARY_MODEL SUMMARY_TOKEN_FILE SUMMARY_AUTH QUOTA_URL QUOTA_TOKEN_FILE CAST_MAX CAST_FOLDER; do for f in README.md README.pt-BR.md; do grep -q "HABBLAUD_$v" $f || echo "FALTA $v em $f"; done; done` → nada;
`grep -n -i 'antigravity\|agy/' README.md README.pt-BR.md server/README.md` → só menções de remoção, se houver.

### Passo 4: matriz de paridade

Corrija a linha da pasta (`:32`) para a regra real e troque "JVM" pelo que existe hoje nas linhas `:64-68`.

**Verificação**: `npm ci && npm run i18n:check -- --strict` sai com 0 (não valida Markdown; é só para não quebrar
o gate); todo link relativo acrescentado aponta para arquivo e âncora existentes (confira cada um com `grep -n '^#'`).

## Plano de testes

Nenhum. A prova é a tabela afirmação → código.

## Critérios de pronto

Todos precisam valer:

- [ ] Doze variáveis, três rotas e `files` descobríveis em EN e PT
 - [ ] Nenhuma afirmação sem referência no código
 - [ ] Matriz corrigida; nada de Antigravity ou desktop Compose reintroduzido
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 132 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `3152f8c`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- O código contradisser o `server/README.md` em algum contrato: relate (pode ser defeito), não escolha um lado.

## Notas de manutenção

- O plano 133 usa este texto como base para ES/FR e para as notas.
