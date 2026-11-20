# Plano 145: omp: formulários com mais de quatro perguntas também chegam ao Zaplaud

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. O revisor cuida de
> `plans/README.md`.
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat cb38b5d..HEAD -- shared/activity.ts shared/answers.ts mod/habblaud-omp/habblaud.ts server/permissions`
> Se algum arquivo do escopo mudou desde `cb38b5d`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

> **Correção do revisor, medida depois de escrever o plano (vale mais que o texto abaixo onde conflitar):** passei
> o formulário real por `formToQuestions` e por variações. **Cada `<fieldset>` sozinho já não converte**, e passa a
> converter quando os rótulos das opções são encurtados: os rótulos reais têm 150, 83 e 68 caracteres, e a regra
> `label.length > 80` (`mod/habblaud-omp/habblaud.ts:581`) barra o grupo. A caixa de texto com `placeholder`
> converte. Portanto há **duas** causas: rótulo de opção acima de 80 caracteres (a principal: agentes escrevem a
> explicação dentro do rótulo) e mais de 4 perguntas. Este plano trata as duas:
> - **Rótulos longos**: aceite rótulo de até **300** caracteres. A resposta do cartão volta por posição da opção e a
>   extensão a traduz para o `value` do controle, então o texto exibido não precisa ser idêntico ao nativo. Leve o
>   texto inteiro ao cartão: aumente o corte de exibição do rótulo em `shared/activity.ts` (`clean(o.label, 80)`)
>   para 300, ou divida em rótulo curto + `description` — escolha o que os três clientes desenham bem (o texto tem de
>   quebrar linha, não cortar com reticências, na página, em `PermissionSheet.kt` e em `permission.slint`); confira
>   cada um e diga em NOTES. Acima de 300 → não conversível.
> - Estrutura do caso real, para o teste sintético (textos fictícios): `<form><p>…</p><p>…</p>` + 5 `<fieldset>`
>   cada um com `<legend>` curta e `<label><input type=radio|checkbox name value [checked]>texto longo</label>` +
>   `<label>texto<textarea name placeholder></textarea></label>`. O teste do passo 1 deve usar rótulos de 150, 83 e
>   68 caracteres e falhar na base.
> - Critério de pronto adicional: esse formulário sintético converte em 6 perguntas e a ida e volta devolve o
>   resultado nativo.

## Status

- **Prioridade**: P1
- **Esforço**: M
- **Risco**: MED (contrato de perguntas lido por três clientes; folha de perguntas do desktop pode não rolar)
- **Depende de**: 139 (mesclado)
- **Categoria**: bug
- **Planejado em**: commit `cb38b5d`, 2026-11-20 (relato do dono: pergunta do omp continua sem aparecer)

## Por que importa

O plano 139 levou as perguntas em formulário do omp ao cartão de perguntas do Habblaud, mas herdou os limites do
`AskUserQuestion` do Claude Code: no máximo 4 perguntas, 6 opções por pergunta. Os agentes do omp fazem formulários
maiores. Caso real em 2026-11-20 (sessão "Tadeuz", omp 18.22.2, extensão já atualizada): um formulário com 4 grupos
de escolha única, 1 grupo de múltipla escolha e 1 caixa de texto — 6 perguntas, todas de tipos que o 139 converte.
Como passa de 4, a extensão desiste e a pergunta fica só no terminal: o Zaplaud mostra "Fazendo uma pergunta a
você" e nenhum cartão. O dono não consegue responder pelo app.

## Estado atual

- `mod/habblaud-omp/habblaud.ts`, `formToQuestions` (~`:560-625`): devolve "não conversível" quando
  `map.length >= 4` ao acrescentar uma pergunta (`:592`) e quando um grupo passa de 6 opções (`:586`); rótulo de
  opção até 80 caracteres (`:581`), texto da pergunta até 300 (`:607,622`), HTML até 32.768 (`:421`).
- `shared/activity.ts:14-15,22,27`: `MAX_QUESTIONS = 4`, `MAX_OPTIONS = 6`; `askQuestions()` corta a lista nesses
  limites para exibição. O comentário diz que as posições contam no original, porque a resposta volta por elas.
- `shared/answers.ts` e `server/permissions/registry.ts` (~`:261-290,523-563`): validam e montam a resposta por
  posição de pergunta e de opção; "Outro" até 2.000 caracteres.
- Clientes (leem `permission.questions` e desenham uma pergunta por item, sem limite próprio conhecido):
  - Página: `client/src/ui/permission.ts`.
  - Kotlin: `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/PermissionSheet.kt` — a folha tem
    `verticalScroll` (`:128`).
  - Rust: `zaplaud-rs/crates/zaplaud-ui/ui/permission.slint` e `zaplaud-core/src/rules/permission.rs` — **não achei
    rolagem na folha** (`grep -n 'ScrollView\|Flickable\|ListView' permission.slint` não devolve nada): com muitas
    perguntas o conteúdo pode passar da altura da janela e os botões ficarem inalcançáveis. Confirme.
- O `AskUserQuestion` do Claude Code tem no máximo 4 perguntas pelo próprio esquema da ferramenta; aumentar o
  limite do Habblaud não muda nada para ele.

## Convenções do repositório (valem para este plano)

- TypeScript com Vitest; `npm ci`; **`npm run typecheck`** e `npm test` em todo passo que toque TypeScript (o CI
  roda os dois; `npm test` sozinho não pega erro de tipo).
- Rust: `cargo fmt --check`, clippy do workspace com `-D warnings`, testes com `ZAPLAUD_KEYRING=memory`; a janela só
  em tela virtual (`xvfb-run -a -s '-screen 0 1280x1024x24'`), uma instância por vez.
- Paridade Kotlin/Rust; `CHANGELOG.md` ("Não lançado"); commits em português, um por passo.
- O cartão nunca pode produzir uma resposta que o formulário nativo do omp não produziria (regra do plano 139).

## Escopo

**Dentro:** `shared/activity.ts`, `shared/answers.ts`, `server/permissions/**` (limites e testes),
`mod/habblaud-omp/habblaud.ts` e seus testes (`server/test/omp-extension.test.ts`), `client/src/ui/permission.ts` e
estilos (se a folha da página não rolar), `zaplaud-rs/crates/zaplaud-ui/ui/permission.slint` e
`zaplaud-ui/src/permission.rs` (rolagem da folha), roteiros `zaplaud-rs/scripts/*.py` só se a folha mudar de
geometria, `server/test/android-fixture.ts` (uma pergunta longa de teste, se não houver), `CHANGELOG.md`,
`docs/zaplaud-paridade.md`.

**Fora:** tipos de controle novos (`select`, número, data): continuam só no terminal. Mudar a folha do Kotlin
além de um teste. A regra de fidelidade do 139.

## Passos

### Passo 1: limites novos, num lugar só

Defina os limites do cartão uma vez (em `shared/`, exportados) e use-os na extensão, no servidor e na exibição:
**12 perguntas** e **12 opções por pergunta**. Mantenha: rótulo ≤ 80, pergunta ≤ 300, "Outro" ≤ 2.000, HTML ≤ 32.768.
A extensão importa de `shared/` como já importa outros contratos (confira como `mod/habblaud-omp/habblaud.ts`
referencia `shared/`; se a extensão precisa ser autocontida, duplique as duas constantes com um teste que as compara).

Testes (antes da implementação, vendo falhar): formulário com 6 perguntas (4 rádio, 1 checkbox, 1 textarea — a
forma do caso real, com textos fictícios) é conversível e a resposta do cartão vira o resultado nativo certo para
as 6; 12 perguntas conversível, 13 não; 12 opções conversível, 13 não; `askQuestions()` exibe 12; resposta por
posição acima de 4 é aceita pelo servidor e recusada acima do limite.

**Verificação**: `npm run typecheck` e `npx vitest run server/test/omp-extension.test.ts server/permissions shared` saem com 0.

### Passo 2: as folhas aguentam 12 perguntas

- **Página** (`client/src/ui/permission.ts`): confirme que o cartão rola quando não cabe e que os botões continuam
  alcançáveis; se não, corrija com o padrão de rolagem dos outros painéis.
- **Kotlin**: a folha já rola. Acrescente um teste de host da regra `permissionDecision` com 6 perguntas (escolhas
  e "Outro"), no arquivo de teste vizinho.
- **Rust**: se a folha não rola, envolva o conteúdo das perguntas numa área rolável (`ScrollView`, como
  `SessionInfoSheet` em `conversation_view.slint`), com os botões Responder/Terminal **fixos fora da rolagem**;
  altura máxima da folha limitada à janela. Teste de unidade em `rules/permission.rs` com 6 perguntas. Sob
  `xvfb-run`, os três roteiros gráficos passam nos dois temas (a folha de permissão é exercitada pelo
  `keyboard-smoke`); se a geometria da folha mudar e deslocar alvos, corrija medindo, sem tirar verificação.

**Verificação**: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest --max-workers=3`
passa; `cargo fmt --check`, clippy, `cargo test --locked -p zaplaud-core -p zaplaud-store -p zaplaud-ui -j 6` e a
matriz dos roteiros (2 temas × 3) passam; `npm run build` passa.

### Passo 3: documentação

`CHANGELOG.md`: "omp: formulários de pergunta com até 12 perguntas e 12 opções por pergunta aparecem no Zaplaud e
na página (antes, acima de 4 perguntas ficavam só no terminal)." Atualize a frase do plano 139 no changelog/README
se citar o limite antigo. Paridade: uma linha se a folha do Rust ganhou rolagem.

**Verificação**: `npm run i18n:check -- --strict`, `npm run typecheck` e `npm test` saem com 0.

## Plano de testes

Passo 1 (limites e ida e volta com 6 perguntas) e passo 2 (regras dos dois clientes com 6 perguntas).

## Critérios de pronto

- [ ] Formulário de 6 perguntas (forma do caso real) vira cartão e a resposta volta no formato nativo
- [ ] Limites 12/12 valem na extensão, no servidor e na exibição; acima disso, só no terminal
- [ ] As três folhas deixam responder 12 perguntas (rolagem; botões alcançáveis)
- [ ] `npm run typecheck`, `npm test`, testes de host do Kotlin e verificações do Rust passam

## Condições de PARADA

- A resposta por posição depender de um limite fixo em algum cliente (Kotlin/Rust/página) que exija mudança de
  contrato.
- A folha do Rust não puder rolar sem reestruturar o componente além do escopo.
- O formulário real não converter por outro motivo além da contagem (confira com um formulário sintético de mesma
  forma: 2 `<p>` de contexto, 5 `<fieldset>` com `<legend>`, opções pré-marcadas com `checked`, `<textarea>` com
  `placeholder`): relate qual regra o barra antes de afrouxar qualquer coisa.

## Notas de manutenção

- Depois do merge: atualizar `~/apps/habblaud` (a extensão vale para sessões novas do omp) e publicar o servidor
  (o limite de exibição é código do servidor); reinstalar os clientes se as folhas mudaram.
- Atenção ao terceiro item das condições de parada: `textarea` com `placeholder` e opções com `checked` aparecem no
  caso real; a linha `habblaud.ts:581` recusa `placeholder` em **opções** de escolha — verifique se também recusa o
  `placeholder` da caixa de texto.

## Comandos de verificação

| O quê | Comando | Esperado |
|---|---|---|
| Tipos | `npm run typecheck` | sai com 0 |
| Testes | `npm test` | 0 falhas |
