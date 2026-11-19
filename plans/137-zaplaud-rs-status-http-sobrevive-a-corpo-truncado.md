# Plano 137: Zaplaud Rust: 403, 404 e 500 não viram falha de rede quando o corpo da resposta de erro é cortado

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 137 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat a4c5b2c..HEAD -- zaplaud-rs/crates/zaplaud-core/src/api.rs zaplaud-rs/crates/zaplaud-core/tests/transport.rs`
> Se algum arquivo do escopo mudou desde `a4c5b2c`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: S
- **Risco**: LOW-MED
- **Depende de**: 136 (mesmo defeito no Kotlin)
- **Categoria**: bug
- **Planejado em**: commit `a4c5b2c`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O cliente Rust lê o corpo de uma resposta de erro antes de devolver o erro com o status. Se a conexão cai nesse
meio, o erro de leitura substitui o status: um 403 (acesso bloqueado), 404 (sessão não existe mais) ou 500 passa a
ser tratado como falha de rede, e o desktop fica tentando reconectar em vez de mostrar o aviso certo. O 401 não
sofre disso (é checado antes do corpo). Achado em 2026-11-19 ao conferir a paridade do plano 136.

## Estado atual

`zaplaud-rs/crates/zaplaud-core/src/api.rs` (base `producao`): `checked()` (`:353-365`) faz a leitura do corpo com
`?` antes de construir `Error::Api { status, ... }`; o aperto de mão do SSE (`:422-437`) chama `checked()`. O 401 é
tratado antes de ler o corpo (`:305-318`). Confirme as linhas e levante todos os chamadores de `checked()` e
qualquer outro ponto que leia corpo de erro com `?` (`grep -n 'checked(\|\.text()\|\.bytes()' api.rs` e nos
módulos de `src/api/`).

Testes de transporte com servidor falso: `crates/zaplaud-core/tests/transport.rs` (veja como ele sobe o servidor e
se consegue responder cabeçalhos e cortar o corpo; se usa um servidor TCP próprio, cortar é escrever os cabeçalhos
com `Content-Length` maior que o corpo enviado e fechar o soquete).

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-137 -b advisor/137-zaplaud-rs-status-http-sobrevive-a-corpo-truncado producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Testes | `ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-core -p zaplaud-store -j 4` | todos passam |
| Lint | `cargo clippy --locked --workspace --all-targets -j 4 -- -D warnings` | sai com 0 |

## Escopo

**Pode modificar**:
- `api.rs` e módulos de `src/api/` com o mesmo padrão
 - `tests/transport.rs`
 - `docs/zaplaud-paridade.md` (a linha do 136), `CHANGELOG.md` (uma linha)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Política de reconexão, mensagens de erro, `zaplaud-ui`. Trocar `reqwest`. `plans/`.

## Passos

### Passo 1: teste que falha hoje

Em `transport.rs`: para 403, 404 e 500 no aperto de mão do stream (e em uma chamada comum que passe por
`checked()`), o servidor falso envia os cabeçalhos e corta o corpo. Esperado: o erro devolvido carrega o status.
Controle: conexão cortada antes dos cabeçalhos continua sendo erro de rede; 401 com corpo cortado continua 401.

**Verificação**: `ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-core --test transport -j 4` — os casos
novos de 403/404/500 falham na base (mostre a mensagem); os controles passam.

### Passo 2: preservar o status

Em `checked()` (e nos pontos iguais): se a leitura do corpo de uma resposta de erro falhar, devolva
`Error::Api` com o status já conhecido e corpo/mensagem vazios, em vez de propagar o erro de leitura. Respostas
de sucesso com corpo cortado continuam sendo erro de leitura. Nada de `unwrap`.

**Verificação**: os testes do passo 1 passam; `ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-core -p zaplaud-store -j 4` inteiro passa.

### Passo 3: fechar

`cargo fmt --check`; `cargo clippy --locked --workspace --all-targets -j 4 -- -D warnings`. Paridade e CHANGELOG
("Zaplaud desktop: um erro do servidor (acesso bloqueado, sessão inexistente, erro interno) continua sendo
mostrado como tal quando a conexão cai logo depois da resposta, em vez de virar falha de rede.").

**Verificação**: os dois comandos saem com 0.

## Plano de testes

Casos de corpo cortado em `transport.rs`.

## Critérios de pronto

Todos precisam valer:

- [ ] 403/404/500 com corpo cortado mantêm o status; controles intactos
 - [ ] fmt, clippy e testes ok
 - [ ] Paridade e CHANGELOG atualizados
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 137 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `a4c5b2c`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- O servidor falso dos testes não conseguir cortar o corpo depois dos cabeçalhos sem reescrevê-lo: relate e proponha.
 - Preservar o status mudar o que a tela mostra de forma não prevista (ex.: passar a exibir diálogo novo): relate.

## Notas de manutenção

- A tela não é exercitada: o efeito visível (aviso certo em vez de reconectar) fica para conferência manual.
