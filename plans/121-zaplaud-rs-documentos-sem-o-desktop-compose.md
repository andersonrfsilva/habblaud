# Plano 121: Zaplaud Rust: documentos e comentários deixam de citar arquivos do desktop Compose removido

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 121 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat f50b599..HEAD -- zaplaud-rs/docs/contrato zaplaud-rs/scripts/dump-m3-baseline.md zaplaud-rs/crates/zaplaud-store/src/lib.rs`
> Se algum arquivo do escopo mudou desde `f50b599`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P3
- **Esforço**: S
- **Risco**: LOW
- **Depende de**: 120 (mesclado)
- **Categoria**: docs
- **Planejado em**: commit `f50b599`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O plano 120 removeu o desktop Compose (`android/desktop`, `android/shared/src/jvmMain`, `jvmTest`). Três lugares do
cliente Rust ainda apontam o leitor para arquivos que não existem mais; quem seguir essas referências não acha nada.

## Estado atual

- `zaplaud-rs/docs/contrato/01-rotas-e-streams.md`, `02-regras-e-estado.md`, `03-telas-e-fixture.md`: citam fontes e
  testes de `jvmMain`/`jvmTest`/`android/desktop` como referência de comportamento.
- `zaplaud-rs/scripts/dump-m3-baseline.md` (linhas ~5 e ~16): instrui gerar a base de cores com `:shared:jvmTest`.
- `zaplaud-rs/crates/zaplaud-store/src/lib.rs:1`: comentário cita `DesktopDirs.kt`.

Ache todas com: `grep -rn 'jvmMain\|jvmTest\|android/desktop\|DesktopDirs\|DesktopAlerts\|:desktop:' zaplaud-rs --include=*.md --include=*.rs --include=*.py --include=*.toml | grep -v '/target/'`.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-121 -b advisor/121-zaplaud-rs-documentos-sem-o-desktop-compose producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Formatação | `cd zaplaud-rs && cargo fmt --check` | sai com 0 |

## Escopo

**Pode modificar**:
- os arquivos acima e qualquer outro `.md`/comentário em `zaplaud-rs/` que o grep achar

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Qualquer mudança de código (só comentários e documentação). `plans/`. `CHANGELOG.md` (nada visível ao usuário).

## Passos

### Passo 1: corrigir as referências

Para cada ocorrência do grep: se a frase cita o arquivo como **origem histórica** de uma regra, mantenha a regra e
troque a referência por "(desktop Compose, removido em 2026-11-18; ver histórico do git)" ou pela referência
equivalente que ainda existe em `android/shared/src/commonMain`/`androidMain` quando houver uma óbvia (confirme
com `ls`/`grep` que o arquivo existe). Se é uma **instrução de execução** que não funciona mais
(`dump-m3-baseline.md`), diga que o procedimento dependia do desktop Compose removido e que `theme/m3-baseline.json`
passa a ser mantido à mão a partir de `ZaplaudApp.kt`; não invente um procedimento novo. Comentário de `lib.rs`:
descreva o layout de diretórios sem citar o arquivo Kotlin removido.

**Verificação**: o grep acima não devolve nada; `cd zaplaud-rs && cargo fmt --check` sai com 0;
`git diff --stat` mostra só `.md` e a linha de comentário.

## Plano de testes

Nenhum.

## Critérios de pronto

Todos precisam valer:

- [ ] grep sem ocorrências
 - [ ] `cargo fmt --check` sai com 0
 - [ ] nenhuma linha de código alterada
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 121 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `f50b599`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- Uma referência for usada por código ou script vivo (não só texto).

## Notas de manutenção

- Nenhuma.
