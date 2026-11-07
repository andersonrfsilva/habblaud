# Plano 050: medir e, se valer, tirar o parse do snapshot da thread da UI

> **Instruções ao executor**: este plano começa por medição. Se a medição ficar abaixo do limite, marque o plano como REJECTED com os números e pare. Atualize a linha 050 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat 778466839..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/SessionRepository.kt`.

## Status

- **Prioridade**: P3
- **Esforço**: S (medição); M (mudança)
- **Risco**: MED (estado do `SessionRepository` acessado por duas threads)
- **Depende de**: `plans/044-zaplaud-indicadores-sem-consumo-ocioso.md` (sem ele, o custo dos indicadores mascara este)
- **Categoria**: perf
- **Planejado em**: commit `778466839`, 2026-11-07

## Por que importa

No perfil do M35 (debug, lista parada), o tratamento de cada evento `snapshot` do `/api/stream` na thread da UI apareceu com 8,6% inclusivo (`ReadingController$start$1$2.emit`). Esse número inclui a atualização do estado. A parte do parse não foi isolada.

## Estado atual

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt:96-115`: o `collect` roda no `scope` da tela (dispatcher da UI, vindo de `rememberCoroutineScope` em `ReadingScreen.kt:71`). Ele chama `repository.snapshot(event.data)`, que faz parse do JSON inteiro e monta a lista, e depois atualiza `mutable.value`.

`SessionRepository.kt:31-45`: guarda estado mutável (`recent`, `open`, `cast`, `terminalAllowed`, `serverTime`) lido também da thread da UI (`ReadingController.kt:93`, `:137`).

## Passos

### Passo 1: medir

No aparelho, com o app debug ou profileable:

1. Instrumente temporariamente (sem commit) `repository.snapshot(event.data)` com `System.nanoTime()` e `Log.d`, ou use `simpleperf record --app br.com.xlinx.habblaud -g` e filtre `SessionRepository.snapshot`.
2. Registre o tamanho de `event.data` (bytes), o tempo p50/p95 por snapshot e quantos snapshots por minuto chegam com agentes trabalhando.

**Decisão**:

- p95 < 2 ms e menos de 30 snapshots/min: REJECTED, com os números.
- Caso contrário, siga para o Passo 2.

### Passo 2: parse fora da UI, aplicação na UI

- Separe em `SessionRepository` uma função pura que transforma `data` num resultado imutável (`SnapshotResult` com sessões e flags), sem tocar nos campos mutáveis.
- No `collect`, rode essa função em `withContext(Dispatchers.Default)` e aplique o resultado nos campos e em `mutable.value` de volta na UI.
- A ordem dos eventos fica preservada, porque o `collect` é sequencial.

### Passo 3: testes

Os testes existentes de snapshot (`SessionIdentityTest`, `UnreadTest`, `ReadingConnectionTest` em `commonTest`) devem passar sem mudança. Adicione um teste da função pura com um snapshot de fixture: mesma saída que `snapshot()` antes da mudança.

Comando: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL`.

## Critérios de pronto

- [ ] Medição registrada.
- [ ] REJECTED com números, ou mudança com testes verdes e nova medição mostrando a thread da UI sem o parse.
- [ ] Linha 050 atualizada.

## Condições de PARADA

- A separação exige mudar o contrato de `SessionRepository` usado pelo `NotificationService` (que já roda em `Dispatchers.IO`).

## Notas de manutenção

- `NotificationService.kt:73` chama `sessions.snapshot(...)` em IO. A função pura serve aos dois.
