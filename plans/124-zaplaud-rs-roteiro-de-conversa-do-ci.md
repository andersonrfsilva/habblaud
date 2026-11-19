# Plano 124: Zaplaud Rust: o roteiro `conversation-smoke.py` do CI volta a achar o cache de conversas

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 124 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat f50b599..HEAD -- zaplaud-rs/scripts/conversation-smoke.py zaplaud-rs/crates/zaplaud-store .github/workflows/zaplaud-rs.yml`
> Se algum arquivo do escopo mudou desde `f50b599`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: S
- **Risco**: MED (o roteiro precisa de tela virtual, que não roda nesta máquina; prova final só no CI)
- **Depende de**: 118 (mesclado)
- **Categoria**: tests
- **Planejado em**: commit `f50b599`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O workflow `Zaplaud Rust` falhava em `cargo fmt --check` até o plano 118. Com a formatação corrigida, o job agora
chega ao passo "Exercitar conversa, teclado e acessibilidade nos dois temas" e falha nele. Enquanto isso, o
empacotamento Linux do CI (`empacotar-linux`) nunca roda.

## Estado atual

Falha no run do commit `f50b599`:

```
File "zaplaud-rs/scripts/conversation-smoke.py", line 187, in main
    result['retained_entries'] = max(len(cache['entries']) for cache in caches)
ValueError: max() iterable argument is empty
```

Trecho (linhas ~185-188): depois de acrescentar 220 mensagens ao transcript da fixture e esperar 4 s, o roteiro lê
`caches = [... for path in (data / 'state/data').glob('conversation-*.json')]` e exige pelo menos 220 entradas.
A lista veio vazia: ou o cliente grava o cache de conversa em outro caminho/nome/formato hoje, ou não gravou
dentro dos 4 s no runner, ou deixou de gravar.

Onde o cliente grava: `zaplaud-rs/crates/zaplaud-store/` (procure `conversation-` e o diretório de dados; veja
também `README.md` do crate). O passo do CI roda, para cada tema, `conversation-smoke`, `keyboard-smoke` e
`accessibility-smoke` com `xvfb-run`; só o primeiro chegou a rodar, então os outros dois podem ter falhas ainda
não vistas.

Nesta máquina não há autorização para subir tela virtual: **não rode os roteiros**. A investigação é por leitura
do código e por testes do `zaplaud-store`.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-124 -b advisor/124-zaplaud-rs-roteiro-de-conversa-do-ci producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Sintaxe | `python3 -m py_compile zaplaud-rs/scripts/*.py` | sai com 0 |

## Escopo

**Pode modificar**:
- `zaplaud-rs/scripts/conversation-smoke.py` (e os outros dois roteiros, só se tiverem o mesmo erro de caminho)
 - testes em `zaplaud-rs/crates/zaplaud-store/tests/` se precisar provar onde o cache é gravado

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Código do cliente (salvo PARADA por defeito real). Rodar Xvfb ou a janela. `plans/`.

## Passos

### Passo 1: achar onde e quando o cache de conversa é gravado

No `zaplaud-store` e em quem o chama (`zaplaud-core`/`zaplaud-ui`): nome e diretório do arquivo de cache de
conversa, formato (tem `entries`?), e em que momento é gravado (a cada evento? com atraso/debounce? só ao trocar
de conversa ou fechar?). Compare com o que o roteiro espera (`<data>/state/data/conversation-*.json`, campo
`entries`, até 4 s depois). Use `git log -S'conversation-' -- zaplaud-rs/crates zaplaud-rs/scripts` para ver se
um dos lados mudou e o outro não.

**Verificação**: você aponta a linha do cliente que define caminho/nome/formato/momento e diz qual das três
causas é (caminho/formato diferente; tempo; não grava mais).

### Passo 2: corrigir o roteiro

Caminho/nome/formato diferente → atualize o roteiro para o que o cliente grava hoje. Tempo → troque o
`time.sleep(4)` por espera de condição com prazo (ex.: até 20 s, verificando a cada 0,5 s que existe cache com
≥ 220 entradas) e mensagem de erro que diga o que foi achado. Em qualquer caso, quando a lista vier vazia o
roteiro deve falhar com mensagem clara (quais arquivos existem no diretório), não com `ValueError` do `max()`.
Se o cliente **não grava mais** o cache que deveria: PARE e relate (defeito do cliente).

Leia os outros dois roteiros procurando o mesmo padrão de caminho/tempo e corrija se for idêntico.

**Verificação**: `python3 -m py_compile zaplaud-rs/scripts/*.py` sai com 0; se criou teste no `zaplaud-store`,
`ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-store -j 4` passa
(`CARGO_TARGET_DIR=/tmp/zaplaud-rs-target-124`; apague ao terminar).

## Plano de testes

Opcional: um teste no `zaplaud-store` que fixe o nome e o formato do arquivo de cache que o roteiro lê.

## Critérios de pronto

Todos precisam valer:

- [ ] Causa identificada com referência de linha
 - [ ] Roteiro corrigido, falha com mensagem clara quando não há cache
 - [ ] `py_compile` ok
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 124 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `f50b599`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- O cliente deixou de gravar o cache de conversa.
 - A correção exigir rodar a janela para ser decidida.

## Notas de manutenção

- A prova é o workflow `Zaplaud Rust` no GitHub depois do merge; o revisor confere e, se o passo seguinte (teclado, acessibilidade) falhar, abre novo plano com o log.
