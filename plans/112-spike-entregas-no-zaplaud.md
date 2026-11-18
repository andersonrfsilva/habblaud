# Plano 112: Spike: entregas concluídas (resultado e arquivos de um turno) no Zaplaud

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 112 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- client/src/ui/deliveries.ts server/http/app.ts`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P3 (direção: decisão do dono)
- **Esforço**: S (spike); M–L para implementar depois
- **Risco**: LOW (investigação; nenhum código de produção)
- **Depende de**: nenhum
- **Categoria**: direction
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

A página do Habblaud tem uma tela de **entregas**: o que cada agente concluiu, com os arquivos gerados, pensada
para abrir a partir do aviso no celular. O Zaplaud não chama essas rotas. No app, tocar em "o agente terminou"
leva à conversa, e o usuário rola a saída de ferramentas para achar o resultado; para ver um arquivo, navega
pela pasta e baixa. O caso de uso central do app no celular ("o que ficou pronto?") tem o caminho mais longo.

## Estado atual

- `client/src/ui/deliveries.ts`: a tela de entregas da página (lista, abertura por link, anexos).
- `server/http/app.ts:103-107` e a rota `/api/deliveries` (e `/api/deliveries/file/:id`), sob a permissão `terminal`.
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/HabblaudApi.kt` e `data/Files.kt`: nenhuma chamada a `/api/deliveries`.
- `android/app/src/main/java/br/com/xlinx/habblaud/NotificationService.kt:88-90`: o toque no aviso carrega só a chave da sessão.
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/FilesSheet.kt`: arquivos são navegados e baixados; não há prévia.
- `docs/zaplaud-paridade.md`: a tabela não tem linha de entregas (nem no Rust).

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-112 -b advisor/112-spike-entregas-no-zaplaud producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Conferir o documento | `test -s plans/112-resultado.md && grep -c '^## ' plans/112-resultado.md` | pelo menos 4 seções |

## Escopo

**Pode modificar**:
- `plans/112-resultado.md` (criar): o único arquivo que este plano produz
 - protótipos descartáveis em `/tmp/habblaud-112/`, fora do repositório

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Qualquer arquivo de código, configuração ou documentação do repositório: este plano termina num documento de decisão. A implementação, se o dono aprovar, vira outro plano.
 - Servidor de produção (`~/apps/habblaud`), contas de loja, chaves e segredos.

## Passos

### Passo 1: responder às perguntas com evidência

Para cada pergunta abaixo, responda no documento de resultado citando arquivo e linha do repositório, documentação oficial (com link) ou medição feita. "Não sei" é resposta válida quando dita com o que faltou para saber.

1. O que exatamente `/api/deliveries` devolve (campos, tamanho, paginação) e o que `/api/deliveries/file/:id` aceita? Leia `server/deliveries/`.
2. O que a página mostra e o que disso faz sentido numa tela de celular?
3. Como o aviso de fim de turno poderia levar à entrega em vez da conversa: o evento do stream traz o identificador da entrega? (leia `shared/` e `data/NotificationRules.kt`.)
4. Que tipos de arquivo aparecem nas entregas reais do dono e quais o app já sabe mostrar (imagem, markdown, texto) depois dos planos 091/092?
5. Qual o risco de mostrar conteúdo de arquivo dentro do app (HTML, SVG, tamanho)? Reaproveite as regras de `docs/zaplaud-paridade.md`, "Imagens da conversa".
6. Custo de paridade: o Rust teria de ganhar a mesma tela na mesma entrega?

**Verificação**: cada pergunta tem uma resposta com fonte no documento.

### Passo 2: escrever a recomendação

Descreva a menor versão (lista de entregas de uma sessão, somente leitura, com download pelos meios que já
existem) e uma versão com prévia. Para cada uma: rotas usadas, telas, textos novos, esforço no Kotlin e no Rust.
Diga com franqueza se uma busca dentro da conversa resolveria a maior parte da dor com menos código.

**Verificação**: `test -s plans/112-resultado.md` e o documento tem as seções "Respostas", "Opções", "Recomendação" e "O que não foi verificado".

## Plano de testes

Não há teste. O produto é o documento de resultado.

## Critérios de pronto

Todos precisam valer:

- [ ] `plans/112-resultado.md` existe com as quatro seções
 - [ ] Cada opção tem esforço (S/M/L), risco e o que muda para o usuário
 - [ ] `git status` mostra só o documento de resultado e o índice
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 112 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- A rota de entregas não estiver disponível pela porta remota (só local): registre e pare; o spike vira pergunta ao dono sobre abrir a rota.

## Notas de manutenção

- O documento de resultado é entrada para o dono decidir; não comece a implementação sem a decisão registrada no índice.
 - Estimativas de esforço aqui são grosseiras; o plano de implementação as refaz.
