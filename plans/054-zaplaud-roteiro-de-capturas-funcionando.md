# Plano 054: `scripts/android-screens.sh` volta a gerar todas as capturas do Zaplaud

> **Instruções ao executor**: siga os passos em ordem e confirme cada verificação. Em condição de PARADA, pare e
> relate; não improvise. Ao terminar, atualize a linha 054 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat dab677d..HEAD -- scripts/android-screens.sh android/shared/src/commonMain/composeResources/values/strings.xml`.
> Se algum arquivo mudou, compare os trechos de "Estado atual" com o código vivo; divergência é condição de PARADA.

## Status

- **Prioridade**: P2 (pré-requisito de verificação visual dos planos 055–062)
- **Esforço**: S
- **Risco**: LOW (só script de teste; o app não muda)
- **Depende de**: nenhum
- **Categoria**: dx / tests
- **Planejado em**: commit `dab677d`, 2026-11-08

## Por que importa

Os planos 055–062 modernizam a interface do Zaplaud e dependem de capturas antes/depois para revisão. O roteiro que
gera essas capturas com dados fictícios está quebrado: em 2026-11-08 ele parou logo no segundo login, porque o app
agora pede confirmação a cada login HTTP (plano 037) e o roteiro não confirma. Rótulos do elenco também mudaram de
"personagem" para "contato". Com o roteiro funcionando, cada plano seguinte prova sua mudança visual com um comando.

## Estado atual

- `scripts/android-screens.sh` — inicia o AVD `habblaud034`, instala o APK debug, sobe duas fixtures
  (`scripts/android-smoke.ts`, portas 14748 e 14749) e navega por toque com `uiautomator`, salvando PNGs em
  `/tmp/android-screens/<nome>-<claro|escuro>.png`.
- `scripts/android-screens.sh:157-162` (login sem confirmar o aviso HTTP):

  ```bash
  fill_login() {
      enter_text 'Endereço do servidor' "$1"
      enter_text 'Usuário' android-test
      enter_text 'Senha' "$2"
      tap 'Entrar'
  }
  ```

- Depois de `tap 'Entrar'` em `http://`, o app mostra o diálogo "Autenticação sem criptografia?" com os botões
  **Cancelar** e **Confirmar** (`ZaplaudApp.kt:239-250`, string `confirm` = "Confirmar"). Sem tocar em Confirmar, a
  autenticação não acontece. Saída real da execução de 2026-11-08: `Elemento não encontrado: Login inválido ou sessão expirada.`
- Rótulos desatualizados no roteiro (`scripts/android-screens.sh:435-437`):

  ```bash
  wait_text 'Dispensar personagem'
  tap 'Dispensar personagem'
  wait_text 'Dispensar este personagem e fechar sua sessão no servidor?'
  ```

  Os textos atuais, em `android/shared/src/commonMain/composeResources/values/strings.xml`, são
  `cast_dismiss` = "Dispensar contato" e `cast_dismiss_confirm` = "Dispensar este contato e fechar a sessão dele no servidor?".
- Pode haver outros rótulos desatualizados mais adiante; a primeira execução não passou do login.

## Comandos

| Objetivo | Comando (na raiz `habblaud/`) | Esperado |
|---|---|---|
| Capturas | `bash scripts/android-screens.sh` | exit 0; caminhos dos PNGs impressos |
| Contagem | `ls /tmp/android-screens/*-claro.png \| wc -l` e `ls /tmp/android-screens/*-escuro.png \| wc -l` | mesmo número, maior que 25 |
| Rótulo existe | `grep -n '>TEXTO<' android/shared/src/commonMain/composeResources/values/strings.xml` | 1 linha |

Requisitos: `$HOME/Android/Sdk` com o AVD `habblaud034`, KVM, Node 22.12+ e `npm ci` já executado. O roteiro inicia e
encerra o emulador sozinho se ele não estiver aberto.

## Escopo

**Pode modificar**: `scripts/android-screens.sh`.

**Fora**: qualquer arquivo do app (`android/**`), a fixture (`scripts/android-smoke.ts`), o servidor. Se o roteiro
falhar porque o app tem um defeito real, isso é condição de PARADA, não motivo para mudar o app.

## Git

- Branch: `advisor/054-zaplaud-roteiro-capturas`.
- Mensagem no estilo do repositório, por exemplo: `fix(scripts): roteiro de capturas confirma login HTTP e usa rótulos atuais`.
- Não faça push. Nunca instale nada no celular M35 nem rode `pm clear` nele; o roteiro só usa o emulador.

## Passos

### Passo 1: confirmar o aviso HTTP no login

Em `fill_login`, depois de `tap 'Entrar'`, acrescente `tap Confirmar`. A função `tap` já espera o elemento aparecer.

**Verificar**: `bash scripts/android-screens.sh` passa da captura `conexao-erro` (o caminho
`/tmp/android-screens/conexao-erro-claro.png` aparece na saída).

### Passo 2: atualizar os rótulos do elenco

Troque, em `scripts/android-screens.sh`, `'Dispensar personagem'` por `'Dispensar contato'` e
`'Dispensar este personagem e fechar sua sessão no servidor?'` por
`'Dispensar este contato e fechar a sessão dele no servidor?'`.

**Verificar**: `grep -n 'personagem' scripts/android-screens.sh` não retorna linhas.

### Passo 3: rodar até o fim e corrigir rótulos restantes

Rode `bash scripts/android-screens.sh`. Para cada falha `Elemento não encontrado: X`:

1. Procure `X` em `android/shared/src/commonMain/composeResources/values/strings.xml`.
2. Se o texto mudou de nome (existe uma string equivalente com outro texto), atualize o roteiro para o texto atual.
3. Se o elemento deveria existir e não existe (por exemplo, botão ausente), PARE e relate.

**Verificar**: o roteiro termina com exit 0 e as duas contagens da tabela são iguais e maiores que 25.

## Plano de testes

Sem testes novos: o próprio roteiro é a verificação. Guarde a lista de PNGs gerados na descrição do commit.

## Critérios de pronto

- [ ] `bash scripts/android-screens.sh` termina com exit 0.
- [ ] As contagens de `*-claro.png` e `*-escuro.png` são iguais e maiores que 25.
- [ ] `git status --short` mostra só `scripts/android-screens.sh` (e `plans/README.md`).
- [ ] Linha 054 de `plans/README.md` atualizada.

## Condições de PARADA

- O diálogo de HTTP não tem o botão "Confirmar".
- Algum passo exige mudar o app ou a fixture.
- O AVD `habblaud034` não existe ou não inicia (`$HOME/Android/Sdk/emulator/emulator -list-avds`).

## Notas de manutenção

- Os planos 059 (busca) e 061 (campo de mensagem) mudam a interação da busca e do campo de mensagem; eles próprios
  atualizam este roteiro.
- Rótulos do roteiro são textos visíveis: ao renomear uma string, procure-a também em `scripts/android-screens.sh`.
