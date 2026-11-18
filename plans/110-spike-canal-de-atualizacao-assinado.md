# Plano 110: Spike: como o Zaplaud Android chega atualizado ao aparelho sem `adb` (canal assinado e versionado)

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 110 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/app/build.gradle.kts README.pt-BR.md`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P3 (direção: decisão do dono)
- **Esforço**: S (spike); M para implementar depois
- **Risco**: LOW (investigação; nenhum código de produção)
- **Depende de**: 100 (versão por release)
- **Categoria**: direction
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Hoje atualizar o app é compilar e rodar `adb install -r`. Serve para o dono; não serve para uma segunda pessoa
nem para um segundo aparelho longe do computador. O README já descreve a chave de release guardada fora do
repositório e o risco de trocá-la (perde login e rascunhos). Falta decidir por onde um APK assinado e numerado
chega ao aparelho e como o app avisa que há versão nova.

## Estado atual

- `android/app/build.gradle.kts:15-16`: versão fixa em 1 / "1.0" (o plano 100 passa a numerar).
- `android/app/build.gradle.kts:21-30`: assinatura por quatro variáveis `HABBLAUD_*`, chave do operador.
- `README.pt-BR.md`, "Compilar e instalar": instalação manual; "trocar de chave exige desinstalar o app".
- `.github/workflows/android.yml`: o CI só gera APK debug; `.github/workflows/ios.yml` publica um IPA **sem assinatura** como artefato.
- O servidor já tem verificação de versão para si mesmo (`GET /api/updates`, `POST /api/updates/check`), usada pela página; o app não a consulta.
- Plano 046 (índice): release assinado com a chave atual no M35, R8 ligado.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-110 -b advisor/110-spike-canal-de-atualizacao-assinado producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Conferir o documento | `test -s plans/110-resultado.md && grep -c '^## ' plans/110-resultado.md` | pelo menos 4 seções |

## Escopo

**Pode modificar**:
- `plans/110-resultado.md` (criar): o único arquivo que este plano produz
 - protótipos descartáveis em `/tmp/habblaud-110/`, fora do repositório

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Qualquer arquivo de código, configuração ou documentação do repositório: este plano termina num documento de decisão. A implementação, se o dono aprovar, vira outro plano.
 - Servidor de produção (`~/apps/habblaud`), contas de loja, chaves e segredos.

## Passos

### Passo 1: responder às perguntas com evidência

Para cada pergunta abaixo, responda no documento de resultado citando arquivo e linha do repositório, documentação oficial (com link) ou medição feita. "Não sei" é resposta válida quando dita com o que faltou para saber.

1. Quem são os usuários-alvo nos próximos meses: só o dono, pessoas próximas, ou público? (pergunta ao dono; registre a resposta literal.)
2. Opções de canal e o que cada uma exige: (a) APK anexado a um Release do GitHub do fork, baixado pelo navegador do celular; (b) o próprio servidor Habblaud servindo o APK na porta remota, com o app mostrando "há versão nova"; (c) repositório F-Droid próprio; (d) Google Play (teste interno). Para cada uma: quem assina, onde a chave fica, se preserva os dados instalados, custo e tempo de publicação.
3. A chave de assinatura pode ir para o CI? Quais os riscos, e qual a alternativa (assinar na máquina do dono e publicar o arquivo)?
4. Como o app descobre a versão nova sem depender de loja: há rota no servidor que possa informar a versão mínima/atual do cliente? (leia `server/http/app.ts`, rotas `/api/health` e `/api/updates`.)
5. O Android permite ao app pedir a instalação de um APK baixado (`REQUEST_INSTALL_PACKAGES`)? Quais avisos o sistema mostra e o que a política do Google Play diz, caso um dia vá para a loja?
6. Para o iPad, qual canal é viável depois do plano 041 (SideStore, TestFlight)? Só liste; não aprofunde.

**Verificação**: cada pergunta tem uma resposta com fonte no documento.

### Passo 2: escrever a recomendação

No documento, liste de duas a quatro opções com esforço, risco e o que o usuário faz para atualizar em cada uma,
e recomende uma para o cenário respondido na pergunta 1. Inclua o procedimento de verificação que o plano de
implementação deverá ter (por exemplo: "instalar a versão N, publicar N+1, atualizar pelo canal, conferir que o
login e um rascunho continuam").

**Verificação**: `test -s plans/110-resultado.md` e o documento tem as seções "Respostas", "Opções", "Recomendação" e "O que não foi verificado".

## Plano de testes

Não há teste. O produto é o documento de resultado.

## Critérios de pronto

Todos precisam valer:

- [ ] `plans/110-resultado.md` existe com as quatro seções
 - [ ] Cada opção tem esforço (S/M/L), risco e o que muda para o usuário
 - [ ] `git status` mostra só o documento de resultado e o índice
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 110 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- O dono não responder à pergunta 1: escreva as opções para os três cenários e não recomende.
 - Qualquer passo exigir a chave de release real ou uma conta de loja: não use; descreva.

## Notas de manutenção

- O documento de resultado é entrada para o dono decidir; não comece a implementação sem a decisão registrada no índice.
 - Estimativas de esforço aqui são grosseiras; o plano de implementação as refaz.
