# Plano 113: Spike: avisos que chegam com o app fechado, depois de reiniciar o celular e no iPad (push ou alternativa)

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 113 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/app/src/main/AndroidManifest.xml android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/AlertWatcher.kt`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P3 (direção: decisão do dono)
- **Esforço**: M (spike); L para implementar depois
- **Risco**: LOW (investigação; nenhum código de produção)
- **Depende de**: 096 (estado real dos avisos)
- **Categoria**: direction
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Os avisos do Android dependem de um serviço em primeiro plano com uma conexão aberta por servidor. Funciona
enquanto o processo vive: depois de reiniciar o celular não há aviso até abrir o app; a economia de bateria pode
pausar a conexão; e a notificação fixa incomoda. No iPad não há aviso nenhum. O próprio manifesto registra que
"não há push (FCM)". Para o app ser confiável como alerta, alguém tem de acordar o aparelho; isso muda a
privacidade e a operação de um produto que hoje fala só com o servidor do próprio usuário.

## Estado atual

- `android/app/src/main/AndroidManifest.xml:18-20`: serviço `specialUse`, com a justificativa "não há push (FCM)"; nenhum receptor de boot.
- `android/app/src/main/java/br/com/xlinx/habblaud/NotificationService.kt`: serviço e `START_STICKY`.
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/AlertWatcher.kt`: um stream por servidor.
- `android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/MainViewController.kt:27`: `NotificationPlatform(false, ...)` no iPad; `ios/Zaplaud/Info.plist:22`: "No background modes are enabled".
- `README.pt-BR.md` (privacidade): o fork documenta quais chamadas externas existem; um relé de push seria uma nova.
- Medição anterior: `plans/auditoria-zaplaud-deep-2026-11-07.md` (CPU com tela bloqueada depois do plano 044: 0,3% de um núcleo).

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-113 -b advisor/113-spike-avisos-sem-conexao-permanente producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Conferir o documento | `test -s plans/113-resultado.md && grep -c '^## ' plans/113-resultado.md` | pelo menos 4 seções |

## Escopo

**Pode modificar**:
- `plans/113-resultado.md` (criar): o único arquivo que este plano produz
 - protótipos descartáveis em `/tmp/habblaud-113/`, fora do repositório

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Qualquer arquivo de código, configuração ou documentação do repositório: este plano termina num documento de decisão. A implementação, se o dono aprovar, vira outro plano.
 - Servidor de produção (`~/apps/habblaud`), contas de loja, chaves e segredos.

## Passos

### Passo 1: responder às perguntas com evidência

Para cada pergunta abaixo, responda no documento de resultado citando arquivo e linha do repositório, documentação oficial (com link) ou medição feita. "Não sei" é resposta válida quando dita com o que faltou para saber.

1. Sem serviço externo, o que dá para melhorar no Android: receptor de boot que reinicia o serviço; `WorkManager` periódico como reforço; pedido de isenção de otimização de bateria. O que o Android 14–16 permite para um serviço `specialUse` iniciado no boot? (documentação oficial, com link.)
2. Com serviço externo: FCM/APNs direto exige conta e chave por app; um relé (ntfy, UnifiedPush, servidor próprio) carrega o quê? Desenhe a versão em que o relé só leva "acorde e consulte seu servidor", sem título, projeto nem texto.
3. O servidor Habblaud (Node, sem dependências de runtime) consegue enviar esse sinal? O que precisaria de configuração nova?
4. No iPad, o que é possível sem conta paga de desenvolvedor (instalação por SideStore)? APNs funciona nesse cenário?
5. Consumo: quanto o serviço atual gasta por dia no M35 (se o dono autorizar medir com `dumpsys batterystats`) contra a estimativa com push.
6. O que muda na promessa de privacidade do README e nos termos do fork.

**Verificação**: cada pergunta tem uma resposta com fonte no documento.

### Passo 2: escrever a recomendação

Apresente pelo menos: (A) só reforço local no Android (boot + isenção de bateria), sem serviço externo;
(B) relé opcional com sinal genérico; (C) FCM/APNs. Para cada uma: o que o usuário configura, o que sai da
máquina dele, esforço e o que continua sem solução (por exemplo, iPad na opção A). Recomende uma.

**Verificação**: `test -s plans/113-resultado.md` e o documento tem as seções "Respostas", "Opções", "Recomendação" e "O que não foi verificado".

## Plano de testes

Não há teste. O produto é o documento de resultado.

## Critérios de pronto

Todos precisam valer:

- [ ] `plans/113-resultado.md` existe com as quatro seções
 - [ ] Cada opção tem esforço (S/M/L), risco e o que muda para o usuário
 - [ ] `git status` mostra só o documento de resultado e o índice
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 113 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- Qualquer experimento exigir conta de terceiros, chave de push ou enviar dados do escritório para fora: não faça; descreva.
 - Medição no aparelho sem autorização do dono: não meça.

## Notas de manutenção

- O documento de resultado é entrada para o dono decidir; não comece a implementação sem a decisão registrada no índice.
 - Estimativas de esforço aqui são grosseiras; o plano de implementação as refaz.
