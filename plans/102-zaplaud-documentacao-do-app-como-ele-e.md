# Plano 102: Documentação do Zaplaud Kotlin descreve o app atual: imagens, bate-papo paralelo, alvos por plataforma e limites dos avisos

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 102 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- README.pt-BR.md docs/zaplaud-paridade.md CHANGELOG.md`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: S–M
- **Risco**: LOW (só documentação)
- **Depende de**: melhor depois de 091 e 096 (para descrever o comportamento final)
- **Categoria**: docs
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

A documentação promete coisas que o app não faz e omite o que ele faz. Exemplos conferidos: o README diz que
imagens remotas nunca são carregadas (hoje carregam depois de um toque); diz que uma puxada longa no fim da
conversa abre o bate-papo paralelo (o gesto foi removido, abre só pelo menu); a tabela de paridade diz que o
código Kotlin serve "Android e iPhone/iPad" (o projeto iOS só tem alvo iPad) e marca "Avisos" como "sim" para o
Kotlin sem dizer que no iPad não há aviso nenhum. Quem instala para usar acredita na documentação.

## Estado atual

`README.pt-BR.md`:

- `:264-265`: "As mensagens exibem Markdown selecionável, incluindo listas, tabelas e código; links abrem ao
  toque. Imagens remotas e HTML não são carregados ou interpretados."
- `:274-275`: "...o menu da conversa de um agente do omp abre o **Bate-papo paralelo** ... No fim da conversa,
  uma puxada longa para cima também o abre."
- `:267-268`: receita das capturas (`scripts/android-screens.sh`), que hoje não termina (plano 101).
- `:371`: "Chamar, dispensar ou retomar contato | `terminal` e auxiliar do host disponível".
- `:403-434`: seção "Desktop (Linux e macOS)" diz que o Compose está obsoleto e em seguida dá a receita completa
  dele; `:433-434` ainda diz "Um cliente Rust + Slint está planejado ... só começa por decisão do dono", quando o
  cliente Rust já é o desktop oficial (`zaplaud-rs/README.md`).
- `:436`: "Aplicativo iPadOS (Zaplaud; validação pendente)".

`docs/zaplaud-paridade.md`:

- `:5`: "**Kotlin** (`android/shared/src/commonMain`): Android e iPhone/iPad."
- linha da tabela "Avisos de aprovação, pergunta e fim de turno | sim | sim | Android: serviço em segundo plano;
  desktop: só com o app aberto".

Fatos do código para escrever certo:

- `ios/Zaplaud.xcodeproj/project.pbxproj:33-34`: `TARGETED_DEVICE_FAMILY = 2` (só iPad).
- `android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/MainViewController.kt:27`: no iPad,
  `NotificationPlatform(false, ...)`: avisos indisponíveis.
- Imagens: `docs/zaplaud-paridade.md`, seção "Imagens da conversa" (remota só após "Carregar imagem", sem
  cookies; `data:` e arquivo do host sozinhos; até 10 MB).
- Bate-papo paralelo: `ReadingScreen.kt:321`, item de menu `aside_menu`; `CHANGELOG.md` registra a remoção do
  gesto.
- Avisos no Android: serviço em primeiro plano iniciado pelo app; não há receptor de boot
  (`android/app/src/main/AndroidManifest.xml` não declara `RECEIVE_BOOT_COMPLETED`), então depois de reiniciar o
  celular os avisos só voltam quando o app é aberto.
- Downloads: Android 10+ grava em Downloads pelo MediaStore; Android 8/9 usa permissão de armazenamento
  (`WRITE_EXTERNAL_STORAGE` com `maxSdkVersion="28"` no manifesto).
- Elenco com ZeroClaw não usa o auxiliar do host (planos 069–071 no índice).

Os planos 087 e 088 do índice (TODO) foram escritos antes de a integração com o Antigravity ser removida
(commit `b9f3308`) e pedem documentar campos e comportamentos que não existem mais.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-102 -b advisor/102-zaplaud-documentacao-do-app-como-ele-e producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Traduções íntegras | `npm run i18n:check -- --strict` | sai com 0 |

## Escopo

**Pode modificar**:
- `README.pt-BR.md` (seções "Aplicativo Android (Zaplaud)", "Desktop (Linux e macOS)" e a tabela de permissões)
- `docs/zaplaud-paridade.md`
- `CHANGELOG.md` (só para remover de "Não lançado" itens que se contradizem, mantendo o comportamento final)
- `plans/README.md` (nota nas linhas 087 e 088)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- `README.md`, `README.es.md`, `README.fr.md`: tradução é o plano 088 (a ser replanejado); não traduza aqui.
- Qualquer código.
- Seções do README sobre o escritório, o servidor e o Habblaud World.
- Entradas do CHANGELOG de versões já publicadas.

## Passos

### Passo 1: README: recursos do app

Reescreva as frases citadas em "Estado atual":

- Imagens: troque a frase da linha 265 pela regra real (HTML não é interpretado; imagens embutidas e arquivos
  do servidor aparecem sozinhos; imagem de outro site só depois de tocar em "Carregar imagem", sem enviar o
  login do Habblaud; até 10 MB). Se o plano 091 já estiver mesclado, inclua o aviso de falha e o limite de
  tamanho da imagem; se não, não invente.
- Bate-papo paralelo: remova a frase do gesto; diga que abre pelo menu da conversa.
- Tabela de permissões (`:371`): separe "omp: `terminal` e auxiliar do host" de "ZeroClaw: `terminal`, sem
  auxiliar".
- Acrescente, na seção do app, um parágrafo "Avisos": o que avisa (aprovação, pergunta, fim de turno), que
  depende de uma notificação fixa do Android, que depois de reiniciar o celular é preciso abrir o app uma
  vez, e que economia de bateria agressiva pode atrasar avisos. Se o plano 096 estiver mesclado, cite o texto
  de bloqueio em Configurações.

**Verificação**: `grep -n 'puxada longa para cima' README.pt-BR.md` → nenhuma ocorrência;
`grep -n 'Imagens remotas e HTML não são carregados' README.pt-BR.md` → nenhuma ocorrência.

### Passo 2: README: desktop e iPad

Na seção "Desktop (Linux e macOS)", deixe só o apontamento para `zaplaud-rs/README.md` e uma frase dizendo que
o desktop Compose está obsoleto e fica no repositório sem suporte; remova a receita `./gradlew :desktop:run` e
tudo que a detalha, e a frase "Um cliente Rust + Slint está planejado...". Mantenha o título da seção (outros
documentos apontam para a âncora).

Na seção do iPadOS, deixe explícito no primeiro parágrafo: só iPad (não há alvo iPhone), sem avisos, instalação
física ainda não validada (plano 041).

**Verificação**: `grep -n 'desktop:run' README.pt-BR.md` → nenhuma ocorrência.

### Passo 3: tabela de paridade por plataforma

Em `docs/zaplaud-paridade.md`: linha 5 → "Android e iPad (o projeto iOS só tem alvo iPad)". Na linha "Avisos",
a observação passa a dizer "Android: serviço em segundo plano; **iPad: sem avisos**; desktop: só com o app
aberto". Na linha dos arquivos, "Android 10+: Downloads (MediaStore); Android 8/9: pasta Downloads com
permissão de armazenamento". Não mude nenhum "sim" para "não" sem evidência no código.

**Verificação**: `grep -n 'iPhone' docs/zaplaud-paridade.md` → nenhuma ocorrência.

### Passo 4: changelog e índice

Em `CHANGELOG.md`, só dentro de "Não lançado": onde duas linhas se contradizem (um item introduz o gesto do
bate-papo paralelo e outro o remove; um fala do botão ⋮ da lista e outro do toque longo), mantenha a que
descreve o comportamento final e apague a outra.

Em `plans/README.md`, nas linhas 087 e 088, acrescente ao Status: "replanejar antes de executar: o plano cita
a integração com o Antigravity, removida em `b9f3308`". Não reescreva esses planos.

**Verificação**: `npm run i18n:check -- --strict` → sai com 0.

## Plano de testes

Não há teste de aplicação. As verificações são os `grep` de cada passo.

## Critérios de pronto

Todos precisam valer:

- [ ] Os quatro `grep` dos passos 1 a 3 não encontram as frases antigas
- [ ] `git diff --stat` mostra só `README.pt-BR.md`, `docs/zaplaud-paridade.md`, `CHANGELOG.md` e `plans/README.md`
- [ ] Nenhuma afirmação nova sem arquivo de código que a sustente (o revisor confere por amostragem)
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 102 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- Uma frase a corrigir depender de comportamento que você não consegue confirmar no código: deixe-a como está e
  liste no relato.
- O README tiver sido reorganizado e as seções citadas não existirem.

## Notas de manutenção

- Os READMEs em inglês, espanhol e francês continuam com o texto antigo até o plano 088 ser replanejado.
- Regra para o futuro: mudança de comportamento do app atualiza o README na mesma entrega (já é regra para o
  CHANGELOG).
