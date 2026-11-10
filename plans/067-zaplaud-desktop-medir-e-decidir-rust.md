# Plano 067: medir o Zaplaud desktop real e decidir se vale um cliente Rust + Slint

> **Instruções ao executor**: este é um plano de **medição e decisão**, não de implementação. Siga os passos,
> registre os números e pare no portão de decisão. Ao terminar, atualize a linha 067 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git log --oneline -1 -- android/desktop` deve mostrar o commit do 066 integrado.
> Se `android/desktop/build.gradle.kts` não tiver `nativeDistributions` com `jvmArgs`, PARE (066 não integrado).

## Status

- **Prioridade**: P3
- **Esforço**: S (medição) + L (só se o portão abrir o spike)
- **Risco**: LOW
- **Depende de**: `066` (e, de preferência, uma a duas semanas de uso real pelo dono)
- **Categoria**: perf / direction
- **Planejado em**: commit `694938f`, 2026-11-09

## Por que importa

O dono pediu um cliente "leve, tipo Rust ou Go". Em 2026-11-09 decidiu: **Compose agora, Rust depois**, se o consumo
real incomodar. Esta decisão saiu de um protótipo, não do app real. Este plano mede o app real com o mesmo método e
aplica um critério fixado agora, para a decisão não depender de impressão.

## Linha de base (protótipo, não o app)

`/tmp/zaplaud-desktop-spike/REPORT.md` (os arquivos de `/tmp` somem em 10 dias; os números estão copiados aqui). São
três protótipos equivalentes: janela 1100×720, 300 conversas, 200 bolhas e listas virtualizadas. Mediana de 3 execuções,
Xvfb com renderização por software e i7-10700.

| Pilha | Abrir a janela (ms) | RSS parado (MiB) | PSS parado (MiB) | RSS depois de rolar (MiB) | CPU parada (%) | Artefato (MiB) |
|---|---:|---:|---:|---:|---:|---:|
| Compose 1.12.2, JVM padrão | 994 | 240 | 222 | 251 | 0,1 | 140 |
| Compose, `-Xmx128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1` | 876 | 209 | 191 | 231 | 0,1 | 140 |
| Rust + Slint 1.18.1 (renderizador por software) | 119 | 25 | 20 | 25 | 0,0 | 18 |
| Go + Fyne 2.8.2 | 115 | 166 | 152 | 174 | 1,2 | 23 |

Método: processo novo por execução; o tempo vai até a janela ficar `IsViewable` no X11 (não é o primeiro quadro).
RSS/PSS vêm de `/proc/<pid>/smaps_rollup` 10 s depois de abrir. A CPU é medida por 30 s via `/proc/<pid>/stat`. Para
rolar, foram 80 cliques de roda pelo XTest. Go + Fyne foi descartado (pesado e com CPU parada acima de zero).

Limites conhecidos do Slint para este app (documentação do `StyledText`,
<https://docs.slint.dev/latest/docs/slint/reference/elements/styledtext/>): não suporta títulos, tabelas,
citações nem imagens, e lista só código *inline*. As respostas dos agentes usam títulos, tabelas e blocos de código o
tempo todo, então um cliente Slint precisaria de renderizador próprio para esses blocos. A seleção de texto nesse
componente não está documentada. [INFERÊNCIA]

## Passos

### Passo 1: medir o app real

Use o app empacotado do 066 (`android/desktop/build/compose/binaries/main-release/app/Zaplaud/bin/Zaplaud`) contra o
servidor de teste (`node --import tsx scripts/android-smoke.ts --port 14748 --allow terminal,messages,permissions`),
logado, com uma conversa longa aberta. Reaproveite `measure.py` de `/tmp/zaplaud-desktop-spike/` se ainda existir;
senão, reproduza o método acima. Meça também no uso real do dono: com a autorização dele, rode `smem -P Zaplaud` ou
leia o `smaps_rollup` do processo depois de 1 h de uso com dois servidores.

**Verificar**: tabela com mediana de 3 execuções (abrir, RSS/PSS parado, RSS/PSS depois de rolar, CPU parada) e uma
linha de uso real. Os números ficam em `plans/067-resultado.md`.

### Passo 2: ajuste barato antes de qualquer reescrita

Repita o passo 1 com `-Xmx192m` e com `-XX:+UseSerialGC -XX:MaxHeapFreeRatio=20 -XX:MinHeapFreeRatio=10`. Teste também
o AppCDS (`-XX:SharedArchiveFile`, gerado por `-XX:ArchiveClassesAtExit` numa primeira execução), que reduz o tempo de
abertura. Mantenha no `build.gradle.kts` só o que reduzir o PSS em ≥ 10% ou a abertura em ≥ 20% sem
`OutOfMemoryError` na conversa mais longa.

### Passo 3: portão de decisão

Abra o spike Rust (passo 4) **somente se** valer ao menos uma condição depois do passo 2:
- PSS parado do app real > **300 MiB**, ou
- abertura > **2 s** na máquina do dono, ou
- CPU parada > **1%** com a janela minimizada, ou
- o dono disser que o consumo incomoda.

Se nenhuma valer, marque 067 como DONE com os números e "Rust não justificado". Pare.

### Passo 4 (condicional): spike Rust + Slint em `/tmp`, com orçamento fechado

Objetivo: provar ou derrubar as três dúvidas que decidem a reescrita. **Não** é um cliente.
1. Bloco de código e tabela markdown renderizados com seleção e cópia de texto (via `pulldown-cmark` + componentes
   Slint próprios).
2. Digitação em pt-BR com acentos mortos (`´` + `a` → `á`) no Linux (Wayland e X11) e no macOS.
3. SSE do `/api/stream` com cookie do servidor de teste (crate `reqwest` com `eventsource-stream` ou equivalente),
   mostrando a lista de sessões viva.
Orçamento: 2 dias de trabalho de agente. Meça com o mesmo método. Escreva em `plans/067-resultado.md` o custo estimado
de paridade (contagem dos itens do inventário abaixo) e recomende sim/não.

Inventário que um cliente Rust precisaria refazer (tudo hoje em `android/shared/src/commonMain`, 4.118 linhas):
login com aviso HTTP e vários servidores (`ZaplaudApp.kt`), lista com abertas/recentes, busca, filtro por servidor,
não lidas e subagentes (`ReadingScreen.kt`), conversa com markdown, pensamentos e ferramentas compactos, resumo,
"ir para o fim" (`ReadingScreen.kt`, `EntryView`), compositor com rascunho persistente, anexos, modos de envio, anel de
contexto, shake e interrupção (`data/MessageController.kt`), aprovação (`PermissionSheet.kt`), elenco
(`CastActions.kt`), bate-papo paralelo (`AsideSheet.kt`, `data/AsideController.kt`), configurações e papéis
(`SettingsScreen.kt`), avatares (`CharacterAvatar.kt`, `data/AvatarCache.kt`), regras de aviso
(`data/NotificationRules.kt`) e o contrato HTTP/SSE de cerca de 25 rotas (`data/HabblaudApi.kt`, `data/*Repository.kt`).

## Critérios de pronto

- [ ] `plans/067-resultado.md` com a tabela do app real, os ajustes testados e a decisão do portão.
- [ ] Se o spike rodou: números Slint do app mínimo, resposta às 3 dúvidas e recomendação.
- [ ] Nenhuma mudança fora de `android/desktop/build.gradle.kts` (flags), `plans/067-resultado.md` e `plans/README.md`.

## Condições de PARADA

- O app empacotado não abre ou não conecta: volte ao 066.
- O spike passa de 2 dias sem fechar as 3 dúvidas: pare e relate o que falta; não estenda.

## Notas de manutenção

- Uma reescrita Rust duplica cada mudança de interface. Os planos 054–062 mudaram a tela 9 vezes num dia. Pese isso
  contra o ganho de memória.
- Se o desktop ficar em Compose, reveja os números a cada atualização maior do Compose Multiplatform.
