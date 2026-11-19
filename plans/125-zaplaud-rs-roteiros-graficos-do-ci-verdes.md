# Plano 125: Zaplaud Rust: os três roteiros gráficos do CI (conversa, teclado, acessibilidade) passam nos dois temas

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 125 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 3decd97..HEAD -- zaplaud-rs/scripts .github/workflows/zaplaud-rs.yml`
> Se algum arquivo do escopo mudou desde `3decd97`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: M
- **Risco**: MED (roteiros guiados por coordenadas de pixel; podem revelar defeito real do cliente)
- **Depende de**: 124 (mesclado)
- **Categoria**: tests
- **Planejado em**: commit `3decd97`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O workflow `Zaplaud Rust` é o único ainda vermelho no GitHub. O passo "Exercitar conversa, teclado e acessibilidade
nos dois temas" falha no primeiro roteiro; os outros dois nunca chegaram a rodar no CI. Enquanto esse passo falha,
o job `empacotar-linux` não roda. O plano 124 tratou a falha como prazo curto e errou: a mensagem nova mostra que
nenhum cache de conversa é criado.

## Estado atual

Run do commit `3decd97` (`gh run view 44446309082 --repo andersonrfsilva/habblaud --log-failed`):

```
AssertionError: Cache de conversa em /tmp/zaplaud-conversation-regression-XXXX/state/data não chegou a 220 entradas
em 20 s; arquivos: ['.state.lock', 'local_state.json', 'settings.json']; entradas por cache: {}
```

O caminho está certo (`ZAPLAUD_DATA_DIR=<dir>/state` → dados em `<dir>/state/data`, `zaplaud-store/src/lib.rs:79-84`;
nome `conversation-<...>.json`, `state.rs:681`). Não existe nenhum arquivo `conversation-*`: ou a conversa não foi
aberta, ou o cliente não gravou. O roteiro `zaplaud-rs/scripts/conversation-smoke.py` (linhas ~150-175) faz login e
abre a conversa com coordenadas fixas (`desktop.fill(167, ...)`, `desktop.click(550, 383)`, `desktop.click(760, 432)`,
`desktop.click(150, 250)`) numa janela redimensionada para largura 1100. Hipótese a confirmar: a tela mudou desde
que as coordenadas foram medidas (por exemplo, a lista ganhou itens/cabeçalhos, ou o login mudou de altura) e o
clique não abre a conversa.

O passo do CI (`.github/workflows/zaplaud-rs.yml`), com o binário debug:

```sh
export ZAPLAUD_CI_BINARY="$PWD/zaplaud-rs/target/debug/zaplaud"
for theme in light dark; do
  for script in conversation-smoke keyboard-smoke accessibility-smoke; do
    xvfb-run -a -s '-screen 0 1280x1024x24' sh -c 'python3 "zaplaud-rs/scripts/$script.py" --binary "$ZAPLAUD_CI_BINARY" --display "$DISPLAY" --port 14789 --theme "$theme"'
  done
done
```

Os roteiros importam utilitários de `compare-compose.py` (captura, `Fixture`, `palette`, `wait_for`). A fixture é o
servidor Node de teste do repositório (`npm ci` na raiz). Nesta máquina: `xvfb-run` instalado (2026-11-19), Pillow,
`libX11`, `libXtst` e `at-spi2-core` presentes.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-125 -b advisor/125-zaplaud-rs-roteiros-graficos-do-ci-verdes producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Roteiros | laço do passo do CI com `xvfb-run` | sai com 0 |
| Sintaxe | `python3 -m py_compile zaplaud-rs/scripts/*.py` | sai com 0 |

## Escopo

**Pode modificar**:
- `zaplaud-rs/scripts/conversation-smoke.py`, `keyboard-smoke.py`, `accessibility-smoke.py` e os utilitários que eles
  importam de `compare-compose.py`
- `.github/workflows/zaplaud-rs.yml` (só para enviar capturas e logs como artefato quando o passo falhar)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Código do cliente (`zaplaud-rs/crates/**`). Defeito real do cliente é condição de PARADA.
- Afrouxar o que o roteiro verifica (ex.: tirar a exigência de 220 entradas no cache) ou pular roteiro/tema.
- Reescrever os roteiros para outra técnica de automação.

## Passos

### Passo 1: compilar e reproduzir

`npm ci` na raiz. `cd zaplaud-rs && CARGO_TARGET_DIR=/tmp/zaplaud-rs-target-125 cargo build --locked -p zaplaud-ui --bin zaplaud -j 6`.
Rode o roteiro de conversa, tema claro, exatamente como o CI (porta 14789, `xvfb-run -a -s '-screen 0 1280x1024x24'`).

**Verificação**: falha com a mesma mensagem do CI (nenhum `conversation-*.json`). Se passar aqui, compare
ambiente (fontes instaladas mudam alturas: `fc-match sans`, DPI, versão do Xvfb) e relate antes de seguir.

### Passo 2: ver o que a janela mostra em cada clique

Instrumente temporariamente o roteiro (não commitar) para salvar uma captura antes e depois de cada
`fill`/`click` do login e da abertura da conversa (o módulo já tem `desktop.image()`). Olhe as capturas: o login
termina? Em que tela o clique `(150, 250)` cai? Existe conversa na lista nesse ponto?

**Verificação**: você aponta o primeiro passo cujo clique não cai no alvo, com a captura, e a coordenada correta
medida na captura.

### Passo 3: corrigir o roteiro de conversa

Corrija as coordenadas/esperas que derivaram. Onde o roteiro já tem como localizar o alvo sem coordenada fixa
(cor da paleta, espera por pixel, árvore de acessibilidade usada no `accessibility-smoke`), prefira isso; onde
não tem, atualize a coordenada e acrescente uma espera por estado antes do clique (o padrão do arquivo é
`smoke.wait_for(lambda: ...)`). Se a conversa abre e mesmo assim o cliente não grava `conversation-*.json` em 20 s
depois de receber as 220 mensagens: PARE (defeito do cliente), com as capturas e o log
`/tmp/zaplaud-rs-fin-conversation-regression-<tema>.log`.

**Verificação**: o roteiro de conversa passa 3 vezes seguidas em cada tema.

### Passo 4: teclado e acessibilidade

Rode `keyboard-smoke` e `accessibility-smoke` nos dois temas. Para cada falha, o mesmo método: captura, alvo,
correção do roteiro; defeito real do cliente → PARE.

**Verificação**: os dois passam 3 vezes seguidas em cada tema.

### Passo 5: matriz do CI e artefatos de falha

Rode o laço completo do passo do CI (2 temas × 3 roteiros) duas vezes seguidas. No workflow, acrescente depois
desse passo um `actions/upload-artifact` com `if: failure()` enviando as capturas e logs que os roteiros gravam em
`/tmp/zaplaud-rs-*` (use a mesma ação e o mesmo SHA fixado que `.github/workflows/android.yml` usa; confirme os
nomes reais dos arquivos que os roteiros escrevem), para a próxima falha no CI vir com imagem.

**Verificação**: as duas execuções do laço saem com 0; o YAML é válido
(`bun -e "Bun.YAML.parse(await Bun.file('.github/workflows/zaplaud-rs.yml').text()); console.log('ok')"`);
`python3 -m py_compile zaplaud-rs/scripts/*.py` sai com 0; `git status` sem instrumentação temporária nem
arquivos novos fora do escopo.

## Plano de testes

Os três roteiros são o teste.

## Critérios de pronto

Todos precisam valer:

- [ ] Laço completo do CI (2 temas × 3 roteiros) passa 2 vezes seguidas localmente
- [ ] Nenhuma verificação dos roteiros foi removida ou afrouxada
- [ ] Workflow envia capturas/logs quando o passo falha; YAML válido
- [ ] Nenhum arquivo em `zaplaud-rs/crates/` alterado
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 125 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `3decd97`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- O cliente não grava o cache com a conversa aberta, ou qualquer roteiro falha por comportamento errado do cliente.
- Os roteiros passam aqui mas a diferença para o CI não é explicável (fontes/DPI): relate o que mediu.
- Um roteiro depender de recurso ausente nesta máquina (ex.: barramento de acessibilidade) que não dê para subir
  dentro do `xvfb-run` sem instalar pacote: relate qual.

## Notas de manutenção

- A prova final é o workflow `Zaplaud Rust` verde no GitHub depois do merge, inclusive o job `empacotar-linux`,
  que nunca rodou.
- Coordenadas fixas voltam a quebrar a cada mudança de layout; os artefatos de falha encurtam o diagnóstico.
