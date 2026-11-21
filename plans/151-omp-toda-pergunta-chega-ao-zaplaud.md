# Plano 151: omp: toda pergunta em formulário chega ao Zaplaud (listas, e texto livre para o que não couber)

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. O revisor cuida de
> `plans/README.md`.
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 640068e..HEAD -- mod/habblaud-omp shared/activity.ts shared/answers.ts shared/types.ts shared/ask-limits.ts server/permissions client/src/ui/permission.ts android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/PermissionSheet.kt zaplaud-rs/crates/zaplaud-core/src/rules/permission.rs zaplaud-rs/crates/zaplaud-ui/ui/permission.slint`
> Se algum mudou desde `640068e`, compare os trechos de "Estado atual" com o código vivo; se não baterem, é PARADA.

## Status

- **Prioridade**: P1 · **Esforço**: M · **Risco**: MED (contrato de perguntas lido por três clientes; resposta devolvida ao agente)
- **Depende de**: 139, 145 (mesclados) · **Categoria**: bug
- **Planejado em**: commit `640068e`, 2026-11-21 (relato do dono: "numa sessão com perguntas, ela nunca chega no Zaplaud")

## Por que importa

O dono responde aos agentes pelo Zaplaud. Quando um agente do omp pergunta com um formulário que a extensão não
consegue converter, o Zaplaud mostra só "Fazendo uma pergunta a você"; o formulário fica no terminal, que o dono não
usa, e o agente fica parado até alguém mandar uma mensagem (que cancela a pergunta: "Ask input was cancelled").

Levantamento dos últimos 4 dias (35 transcripts do omp, só formas, sem conteúdo): 8 perguntas em formulário HTML;
nenhuma chegou ao Zaplaud (as anteriores ao plano 145 por limites já corrigidos; todas terminaram "cancelled"). Com o
código de hoje, 4 de 8 converteriam. As outras 4 têm: uma `<select>` (caso vivo de 2026-11-21, sessão "Bozo":
5 grupos de rádio + 1 lista de 2 opções; **sem a lista, converte**); campos `password`/`number` com validação; e uma
sem causa identificada. Regra atual (`mod/habblaud-omp/habblaud.ts`, `formToQuestions`): qualquer controle fora de
rádio/checkbox/texto, ou grupo sem o "Outro" nativo do omp, torna o formulário inteiro não conversível.

## Estado atual

- Extensão: `formToQuestions(html)` / `answersToFormResult(map, answers)` em `mod/habblaud-omp/habblaud.ts`; limites em
  `shared/ask-limits.ts`; testes em `server/test/omp-extension.test.ts`. A extensão corre o `ask` nativo e o cartão em
  paralelo; o primeiro a responder vence.
- Contrato do cartão: `AskQuestion` (`shared/types.ts`) com `question`, `header?`, `multiSelect?`, `options[]`
  (`label`, `description?`); exibição em `shared/activity.ts:16-37`; resposta validada em `shared/answers.ts`
  (`options` por posição e `other` texto até 2.000). **Todo cartão mostra o campo "Outro"** nos três clientes
  (página `client/src/ui/permission.ts`; Kotlin `PermissionSheet.kt`; Rust `rules/permission.rs` + `ui/permission.slint`).
- Resultado nativo do `ask` de formulário (omp 18.22.2): texto `User answers:` + JSON `{name: valor}` e
  `details.answers` — confira em `answersToFormResult` e nos testes do plano 139.

## Decisões

1. **Listas (`<select>`, com ou sem `multiple`) e grupos sem "Outro" nativo viram perguntas sem "Outro"**: campo novo
   opcional `noOther: true` em `AskQuestion`. O servidor **recusa** resposta com `other` numa pergunta `noOther`
   (fidelidade garantida mesmo com cliente antigo); os três clientes **escondem** o campo "Outro" quando `noOther`.
   Ausente = comportamento atual.
2. **O que ainda não couber vira cartão de texto livre, em vez de ficar só no terminal**: cada controle com `name`
   vira uma pergunta (rótulo/legenda como texto; opções quando houver; senão só texto livre); a resposta volta ao
   agente no formato nativo `{name: valor}` com valores em texto, e o resultado ganha uma linha final
   `(Respondido pelo Habblaud em texto livre; o formulário não validou os valores.)`. Se nem isso der (HTML
   irreconhecível, nenhum controle com `name`), um cartão de **uma** pergunta, texto = o texto visível do formulário
   (cortado no limite, com "… veja o formulário completo no terminal"), e o resultado é
   `User answered in free text (Habblaud): <texto>`.
3. **Nunca pelo Zaplaud**: formulários com `input type="password"` ou `type="file"` continuam só no terminal (o acesso
   remoto do servidor pessoal é HTTP sem criptografia; senha não trafega por ele). O Zaplaud continua mostrando
   "Fazendo uma pergunta a você".

## Escopo

**Dentro:** `mod/habblaud-omp/habblaud.ts` e `server/test/omp-extension.test.ts`; `shared/types.ts`,
`shared/activity.ts`, `shared/answers.ts` e testes; `server/permissions/**` só se a validação passar por lá; página
`client/src/ui/permission.ts`; Kotlin `PermissionSheet.kt` e o modelo/parse da pergunta + testes de host; Rust
`zaplaud-core/src/rules/permission.rs`, o parse da pergunta, `zaplaud-ui/ui/permission.slint`/`src/permission.rs` +
testes; `CHANGELOG.md`, `docs/zaplaud-paridade.md`.

**Fora:** `AskUserQuestion` do Claude Code (não muda); o diálogo nativo do omp; senha/arquivo; iPad além do que
compartilha em `commonMain` (sem `expect`/`actual` novo).

## Passos

### Passo 1: levantamento com motivo

Rode a conversão atual sobre as perguntas em formulário dos transcripts do omp dos últimos 7 dias
(`~/.omp/agent/sessions/**.jsonl`, chamadas `toolCall` `ask` com `arguments.html`) e classifique **por que** cada
uma não converte (instrumente `formToQuestions` temporariamente para devolver o motivo; não commite). Só formas e
contagens; nada de conteúdo das sessões no repositório nem no relatório.

**Verificação**: tabela motivo → quantidade em `~/dados/habblaud/plano-151/levantamento.md`, incluindo o caso "sem
causa identificada" do levantamento acima.

### Passo 2: `noOther` no contrato e nos clientes (testes primeiro)

`AskQuestion.noOther?: true`; `askQuestions()` repassa; `shared/answers.ts` recusa `other` em pergunta `noOther`
(teste); página, Kotlin e Rust escondem o "Outro" quando `noOther` e exigem uma opção (testes de regra em cada
cliente; captura da folha do desktop sob `xvfb-run` com uma pergunta `noOther`).

**Verificação**: `npm run typecheck`, `npm test`; `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:assembleDebug --max-workers=3`;
`cargo fmt --check`, clippy `-D warnings`, `cargo test --locked -p zaplaud-core -p zaplaud-store -p zaplaud-ui -j 6`.

### Passo 3: listas e grupos sem "Outro"

`formToQuestions` passa a aceitar `<select>` (opções de `<option>`; `multiple` → `multiSelect`; `selected` como hoje
o `checked`) e grupos de rádio/checkbox em que o omp não oferece "Outro", marcando `noOther`; `answersToFormResult`
devolve o `value` da opção. Teste com a **forma** do caso vivo (5 grupos de rádio com um `checked` cada + 1 `select` de
2 opções, textos fictícios): converte em 6 perguntas e a ida e volta devolve o resultado nativo.

**Verificação**: `npx vitest run server/test/omp-extension.test.ts` (novos falham antes); typecheck.

### Passo 4: texto livre para o resto

Implemente a decisão 2 numa função separada (`formToFreeText`), chamada só quando `formToQuestions` recusa e o
formulário não tem senha/arquivo. O texto das perguntas passa pelo mesmo mascaramento de segredos do resto. Testes:
`number` com `min`/`max`, `email`, `date`, `pattern` → perguntas de texto livre, resultado `{name: "texto"}` + a
linha de aviso; HTML sem controles com `name` → cartão único e resultado `User answered in free text (Habblaud): …`;
`password` e `file` → nenhum cartão; o formato antigo (`questions`) não muda.

**Verificação**: testes novos passam; `npm run typecheck` e `npm test` saem com 0.

### Passo 5: documentação

`CHANGELOG.md`: "omp: toda pergunta em formulário aparece no Zaplaud: listas viram escolha sem 'Outro', e o que o
cartão não representa vira resposta em texto livre. Formulários com senha ou arquivo continuam só no terminal."
Paridade: linha "Perguntas sem 'Outro'" (página, Android, desktop: sim; iPad: compartilha o código, não compilado).

**Verificação**: `npm run i18n:check -- --strict`, typecheck e `npm test` saem com 0.

## Critérios de pronto

- [ ] A forma do caso vivo (rádios + lista) vira cartão de 6 perguntas e a resposta volta no formato nativo
- [ ] Pergunta `noOther` sem campo "Outro" nos três clientes; servidor recusa `other` nela
- [ ] Qualquer outro formulário sem senha/arquivo vira cartão de texto livre; senha/arquivo nunca
- [ ] typecheck, `npm test`, host Kotlin, Rust; levantamento com motivos salvo

## Condições de PARADA

- O resultado nativo do omp para `<select>` não puder ser reproduzido (confira na implementação do omp).
- Esconder o "Outro" exigir mudar a estrutura da folha em algum cliente além de uma condição.
- Precisar de `expect`/`actual` novo no Kotlin.

## Notas de manutenção

- Depois do merge: atualizar `~/apps/habblaud` (a extensão vale para sessões **novas** do omp), publicar o servidor
  (validação de `noOther`), instalar o APK no M35 e o desktop no Mac.
- A pergunta pendente de uma sessão já aberta continua só no terminal; responder por mensagem cancela a pergunta e
  entrega a mensagem ao agente.
