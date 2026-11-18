# Plano 114: Spike: Zaplaud em inglês, espanhol e francês (o que traduzir primeiro e como manter)

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 114 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/shared/src/commonMain/composeResources/values/strings.xml`
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

O servidor e a página do Habblaud têm quatro idiomas (pt-BR, en, es, fr), com verificação automática de
traduções. O Zaplaud só tem português: cerca de 290 textos num único `strings.xml`, datas formatadas com a
localidade `pt_BR` fixa no iOS, e os textos das notificações do Android num segundo arquivo. Para qualquer uso
fora do Brasil o app é ilegível justamente nos pontos em que errar custa caro: aprovar um comando, confirmar um
reenvio, aceitar login por HTTP. Só vale o esforço se houver usuário de fora; a decisão é do dono.

## Estado atual

- `android/shared/src/commonMain/composeResources/`: só a pasta `values/` (sem `values-en`, `values-es`, `values-fr`); ~290 entradas.
- `android/app/src/main/res/values/strings.xml`: textos nativos (notificações), só em português.
- `android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/MainViewController.kt:83,89`: `NSLocale.localeWithLocaleIdentifier("pt_BR")` fixo.
- `shared/locales/` e `npm run i18n:check`: o mecanismo de tradução da página, com verificação no CI.
- `AGENTS.md`: "Textos do Zaplaud ficam no `strings.xml` do Kotlin; o Rust gera os dele a partir desse arquivo" (`zaplaud-rs/crates/zaplaud-ui/resource_gen.rs`).
- `ios/Zaplaud/Info.plist:4,19`: região de desenvolvimento `pt_BR` e texto de permissão de rede local em português.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-114 -b advisor/114-spike-zaplaud-em-outros-idiomas producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Conferir o documento | `test -s plans/114-resultado.md && grep -c '^## ' plans/114-resultado.md` | pelo menos 4 seções |

## Escopo

**Pode modificar**:
- `plans/114-resultado.md` (criar): o único arquivo que este plano produz
 - protótipos descartáveis em `/tmp/habblaud-114/`, fora do repositório

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Qualquer arquivo de código, configuração ou documentação do repositório: este plano termina num documento de decisão. A implementação, se o dono aprovar, vira outro plano.
 - Servidor de produção (`~/apps/habblaud`), contas de loja, chaves e segredos.

## Passos

### Passo 1: responder às perguntas com evidência

Para cada pergunta abaixo, responda no documento de resultado citando arquivo e linha do repositório, documentação oficial (com link) ou medição feita. "Não sei" é resposta válida quando dita com o que faltou para saber.

1. Há usuário de fora previsto? (pergunta ao dono; sem isso, a recomendação é não fazer.)
2. Como o Compose Multiplatform Resources escolhe o idioma (pastas `values-<idioma>`), e o que acontece com chave sem tradução?
3. O gerador do Rust lê só `values/strings.xml`. O que ele precisaria para gerar os outros idiomas? (leia `resource_gen.rs`.)
4. Quais textos são críticos (login, HTTP, permissão/aprovação, envio incerto, avisos)? Liste as chaves por grupo e conte.
5. Dá para reaproveitar traduções já existentes em `shared/locales/` (mesmas frases da página)? Quantas chaves coincidem?
6. Como verificar no CI que nenhum idioma ficou sem chave (um teste de host comparando os arquivos, no espírito do `i18n:check`)?
7. Formatos: plurais (`<plurals>`), datas e números em cada plataforma; o que está fixo em `pt_BR` hoje.

**Verificação**: cada pergunta tem uma resposta com fonte no documento.

### Passo 2: escrever a recomendação

Proponha: (A) não fazer agora; (B) traduzir só os textos críticos (com a contagem); (C) traduzir tudo. Para B e
C: quem traduz, como se verifica, o que muda no Rust e esforço. Recomende conforme a resposta à pergunta 1.

**Verificação**: `test -s plans/114-resultado.md` e o documento tem as seções "Respostas", "Opções", "Recomendação" e "O que não foi verificado".

## Plano de testes

Não há teste. O produto é o documento de resultado.

## Critérios de pronto

Todos precisam valer:

- [ ] `plans/114-resultado.md` existe com as quatro seções
 - [ ] Cada opção tem esforço (S/M/L), risco e o que muda para o usuário
 - [ ] `git status` mostra só o documento de resultado e o índice
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 114 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- O dono responder que não há usuário de fora: registre a opção A como decisão e encerre o spike sem investigar o resto.

## Notas de manutenção

- O documento de resultado é entrada para o dono decidir; não comece a implementação sem a decisão registrada no índice.
 - Estimativas de esforço aqui são grosseiras; o plano de implementação as refaz.
