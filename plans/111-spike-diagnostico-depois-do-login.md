# Plano 111: Spike: tela de diagnóstico depois do login (o que esta conta pode fazer neste servidor)

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 111 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/SessionRepository.kt server/http/app.ts`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P3 (direção: decisão do dono)
- **Esforço**: S (spike); M para implementar depois
- **Risco**: LOW (investigação; nenhum código de produção)
- **Depende de**: nenhum
- **Categoria**: direction
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O servidor decide, por conexão, o que o acesso remoto pode fazer: ler conversas (`terminal`), mandar mensagens
(`messages`), responder aprovações (`permissions`), ver arquivos. O app descobre isso aos poucos e reage
escondendo botões ou mostrando um aviso genérico. Quem configura um servidor novo e esquece uma permissão vê um
app "que não deixa mandar mensagem" sem saber por quê, nem o que mudar no servidor.

## Estado atual

- `server/http/app.ts:480-492`: `GET /api/access` devolve `remote`, `local`, `permissions`, `messages`, `messageSessionGuard`, `terminal`, `spawn`, `world` (e `files`, mais abaixo).
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/SessionRepository.kt:55-60`: o app lê `terminal`, `messages`, `messageSessionGuard`, `permissions`; ignora o resto.
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ZaplaudApp.kt`: no login, `api.access()` é chamado e o resultado descartado.
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt:100` (`terminal_blocked`) e `:106-107` (`actionsAvailable`): como a falta de permissão aparece hoje.
- `README.pt-BR.md:366-373`: a tabela "recurso → permissão" que o usuário teria de consultar.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-111 -b advisor/111-spike-diagnostico-depois-do-login producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Conferir o documento | `test -s plans/111-resultado.md && grep -c '^## ' plans/111-resultado.md` | pelo menos 4 seções |

## Escopo

**Pode modificar**:
- `plans/111-resultado.md` (criar): o único arquivo que este plano produz
 - protótipos descartáveis em `/tmp/habblaud-111/`, fora do repositório

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Qualquer arquivo de código, configuração ou documentação do repositório: este plano termina num documento de decisão. A implementação, se o dono aprovar, vira outro plano.
 - Servidor de produção (`~/apps/habblaud`), contas de loja, chaves e segredos.

## Passos

### Passo 1: responder às perguntas com evidência

Para cada pergunta abaixo, responda no documento de resultado citando arquivo e linha do repositório, documentação oficial (com link) ou medição feita. "Não sei" é resposta válida quando dita com o que faltou para saber.

1. Quais combinações de permissões existem na prática e o que o app mostra em cada uma hoje? Monte a tabela lendo `ReadingScreen.kt`, `CastActions.kt`, `FilesSheet.kt` e `SettingsScreen.kt`.
2. Para cada permissão ausente, qual é a instrução exata para o operador do servidor ligá-la (variável de ambiente, comando)? Leia `server/config.ts` e a seção de acesso remoto do README.
3. Onde a informação caberia sem tela nova: na linha do servidor em Configurações, num aviso único depois do primeiro login, ou nos pontos em que o botão falta?
4. O que distinguir no erro de login: endereço da porta local sem autenticação, certificado não confiável, servidor inalcançável, senha errada. O que o app consegue saber em cada caso? (leia o tratamento de erro do login em `ZaplaudApp.kt` e `UrlPolicy.kt`.)
5. O mesmo diagnóstico serviria ao cliente Rust (regra de paridade)?

**Verificação**: cada pergunta tem uma resposta com fonte no documento.

### Passo 2: escrever a recomendação

Proponha a menor versão útil (provavelmente: uma seção por servidor em Configurações listando o que está
liberado e o que não está, com uma frase de como liberar) e uma versão maior, com esboço em texto das telas.
Diga quais textos novos seriam necessários em `strings.xml`.

**Verificação**: `test -s plans/111-resultado.md` e o documento tem as seções "Respostas", "Opções", "Recomendação" e "O que não foi verificado".

## Plano de testes

Não há teste. O produto é o documento de resultado.

## Critérios de pronto

Todos precisam valer:

- [ ] `plans/111-resultado.md` existe com as quatro seções
 - [ ] Cada opção tem esforço (S/M/L), risco e o que muda para o usuário
 - [ ] `git status` mostra só o documento de resultado e o índice
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 111 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- A resposta à pergunta 2 exigir adivinhar configuração do servidor que não está documentada nem no código: registre como lacuna.

## Notas de manutenção

- O documento de resultado é entrada para o dono decidir; não comece a implementação sem a decisão registrada no índice.
 - Estimativas de esforço aqui são grosseiras; o plano de implementação as refaz.
