# Plano 126: Zaplaud Rust: conversa longa recém-carregada chega de fato ao fim (falha no CI com a fonte DejaVu)

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 126 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 419208c..HEAD -- zaplaud-rs/crates/zaplaud-ui/ui/conversation_view.slint zaplaud-rs/scripts/conversation-smoke.py`
> Se algum arquivo do escopo mudou desde `419208c`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: M
- **Risco**: MED (pode ser defeito real de rolagem do cliente, dependente da métrica da fonte)
- **Depende de**: 125 (mesclado)
- **Categoria**: bug
- **Planejado em**: commit `419208c`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Último vermelho do GitHub: workflow `Zaplaud Rust`, passo dos roteiros gráficos. Depois do plano 125 o roteiro de
conversa passa do cache e falha adiante: a lista não chega à última mensagem. Se for o que parece, um usuário com
outra fonte de sistema abre uma conversa longa e fica uma mensagem antes do fim, sem saber que há mais.

## Estado atual

Run `44486844888` (commit `419208c`), tema claro:

```
File "zaplaud-rs/scripts/conversation-smoke.py", line 225, in main
    assert copied == latest, f'A primeira carga não acompanhou o fim: {copied!r}'
AssertionError: A primeira carga não acompanhou o fim: ''
```

O roteiro acrescenta 220 mensagens ("Carga fictícia 1." … "Carga fictícia 220."), espera o cache e então, por até
20 s, passa o ponteiro em `(700, 644)`, clica no botão de copiar em `(950, 639)` e compara a área de transferência
com "Carga fictícia 220." (linhas ~210-225).

Captura do CI (artefato `zaplaud-rs-fin-conversation-regression-initial-light.png`; baixe com
`gh run download 44486844888 --repo andersonrfsilva/habblaud -D /tmp/ci-art-126`): a última mensagem visível é
**219**, inteira; a 220 está abaixo da área visível. A fonte do runner é DejaVu Sans (balões ~67 px). Captura local
do plano 125 (`~/dados/habblaud/plano-125/conversation-dark-initial.png`, fonte Noto Sans, balões ~70 px): a 220
aparece **cortada** pela linha de status no primeiro instante e o roteiro passa dentro dos 20 s. Ou seja: logo
após a carga a lista não está exatamente no fim em nenhum dos dois; aqui ela converge, no CI não convergiu em 20 s
(ou convergiu e o botão de copiar não está em `(950, 639)` com aquela fonte — distinguir é o passo 2).

Lógica de seguir o fim em `zaplaud-rs/crates/zaplaud-ui/ui/conversation_view.slint`: propriedade `following`
(`:59`), `jump-to-end()` (`:263-270`), `changed content-height => { if following { content-y = min(0px, height - content-height) } }`
(`:336-338`), e o recálculo de `following` no rolar (`:344`, tolerância de 2 px). A lista é virtualizada e a altura
total é estimada até as linhas serem medidas.

Para reproduzir a fonte do CI nesta máquina (DejaVu está instalada: `fc-list | grep -i dejavu`): um
`fonts.conf` temporário que faça `sans-serif`/`sans` resolver para DejaVu Sans e
`FONTCONFIG_FILE=<esse arquivo>` no ambiente do `xvfb-run` (confira com `FONTCONFIG_FILE=... fc-match sans`). Veja
em `zaplaud-rs/README.md`/código como o cliente escolhe a fonte (há fontes embutidas em `crates/zaplaud-ui/ui/fonts`
e variáveis de ambiente de fonte nos roteiros de medição) para garantir que a fonte usada é a mesma do CI.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-126 -b advisor/126-zaplaud-rs-conversa-longa-chega-ao-fim-com-a-fonte-do-ci producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Roteiros | matriz do passo do CI com `xvfb-run` | sai com 0 |
| Lint | `cargo clippy --locked --workspace --all-targets -j 6 -- -D warnings` | sai com 0 |

## Escopo

**Pode modificar**:
- `zaplaud-rs/crates/zaplaud-ui/ui/conversation_view.slint` e o Rust que o alimenta (`src/conversation_view.rs`), se a causa for o cliente
- `zaplaud-rs/scripts/conversation-smoke.py`, se a causa for o roteiro (coordenada do botão de copiar dependente da fonte)
- `CHANGELOG.md` (uma linha, se o cliente mudar)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Trocar a fonte do CI ou embutir fonte só para o teste passar. Afrouxar a verificação (aceitar 219, aumentar prazo sem causa).
- Outras telas. `android/`. `plans/`.

## Passos

### Passo 1: reproduzir com a fonte do CI

Build debug (`CARGO_TARGET_DIR=/tmp/zaplaud-rs-target-126`), `npm ci`, e rode o roteiro de conversa no tema claro
com `FONTCONFIG_FILE` apontando DejaVu Sans, como descrito acima.

**Verificação**: falha em `A primeira carga não acompanhou o fim`, e a captura mostra 219 como última mensagem
visível, como no CI. Se não reproduzir, compare a captura com a do artefato (altura dos balões, largura do
texto) até a métrica bater, ou relate o que difere.

### Passo 2: o que está errado: posição da lista ou coordenada do clique

Com a falha reproduzida, capture a tela a cada segundo durante os 20 s e registre (log temporário, não commitar)
`content-y`, `content-height`, `height` e `following` da lista. Perguntas: a lista chega ao fim
(`content-y == height - content-height`) em algum momento? `following` continua verdadeiro? A altura estimada
muda depois da última correção de `content-y` (uma medição tardia de linha que não dispara `changed content-height`
ou dispara com `following` já falso por causa da tolerância de 2 px em `:344`)? Se a lista está no fim e a 220
está visível, onde está o botão de copiar dela nessa fonte?

**Verificação**: você diz qual das duas é, com números do log e capturas em `~/dados/habblaud/plano-126/`.

### Passo 3: corrigir

**Cliente** (lista não chega ao fim / perde `following` sem gesto do usuário): corrija a causa na lógica de
seguir o fim, de modo que, enquanto `following` for verdadeiro e o usuário não rolar, a lista termine no fim real
depois que as alturas estimadas forem substituídas pelas medidas, em qualquer fonte. Não resolva com temporizador
de "tentar de novo depois de N ms" se houver um evento que diga que a altura mudou. `following` só pode virar
falso por gesto do usuário (rolagem, Home, PageUp), nunca por reestimativa.
**Roteiro** (lista certa, coordenada errada): localize o botão de copiar sem depender da altura do balão (por
exemplo ancorando no fim da lista) mantendo a verificação de que o texto copiado é "Carga fictícia 220.".

**Verificação**: com DejaVu **e** com a fonte padrão desta máquina, o roteiro de conversa passa 5 vezes seguidas
em cada tema.

### Passo 4: não quebrar o resto

`cargo fmt --check`; `cargo clippy --locked --workspace --all-targets -j 6 -- -D warnings`;
`ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-core -p zaplaud-store -j 6`; matriz do CI
(2 temas × 3 roteiros, como no passo do workflow) duas vezes com DejaVu e uma com a fonte padrão.

**Verificação**: tudo sai com 0. Os trechos do roteiro de conversa que testam leitura no meio da conversa
(o leitor não é arrastado para o fim quando chega mensagem nova enquanto lê o histórico) continuam passando:
são eles que provam que a correção não passou a forçar o fim.

## Plano de testes

O roteiro de conversa nas duas fontes. Se a correção for no Rust e houver função pura envolvida, um teste de unidade dela.

## Critérios de pronto

Todos precisam valer:

- [ ] Falha do CI reproduzida localmente com DejaVu e causa identificada com números
- [ ] Roteiro de conversa 5/5 por tema nas duas fontes
- [ ] Matriz do CI passa (2× DejaVu, 1× fonte padrão); fmt, clippy e testes ok
- [ ] Nenhuma verificação afrouxada
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 126 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `419208c`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- Não reproduzir a falha do CI depois de igualar a fonte.
- A correção exigir trocar o componente de lista ou reescrever a virtualização.
- A correção fizer o leitor ser puxado para o fim enquanto lê o histórico.

## Notas de manutenção

- Prova final: workflow `Zaplaud Rust` verde no GitHub, e o job `empacotar-linux` (nunca executado) rodando.
- Depois de mesclar, o desktop precisa ser reinstalado no Mac e no miranote.
