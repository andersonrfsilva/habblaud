# Plano 107: Zaplaud: o CI móvel não empacota o desktop obsoleto e os textos nativos do Android não divergem dos compartilhados

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 107 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- .github/workflows/android.yml android/app/src/main/res/values/strings.xml`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P3
- **Esforço**: S
- **Risco**: LOW
- **Depende de**: 090 (mexe no mesmo workflow; faça depois)
- **Categoria**: tech-debt
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Duas sobras com custo recorrente:

1. O desktop Compose está obsoleto desde 2026-11-10 (não recebe mudanças, segundo o `AGENTS.md`), mas todo push
   que toca `android/` ainda **empacota** a distribuição dele no CI (`:desktop:createReleaseDistributable`, com
   Xvfb e Node instalados só para isso). São minutos de runner por execução e um ponto de falha que não protege
   nada que se entrega.
2. `android/app/src/main/res/values/strings.xml` é uma cópia manual, de 237 linhas, dos textos que hoje moram em
   `android/shared/src/commonMain/composeResources/values/strings.xml`. O código do app usa 8 dessas chaves; os
   testes instrumentados usam 65 para localizar elementos na tela. As cópias já divergiram (o texto do resumo é
   diferente nos dois arquivos), então um teste pode procurar um texto que a tela não mostra mais, e o lint
   acusa dezenas de recursos não usados.

## Estado atual

`.github/workflows/android.yml:68-76` (job `verificar`):

```yaml
      - run: ./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
        working-directory: android
      - uses: actions/setup-node@...
        with:
          node-version: '24'
          cache: npm
      - run: npm ci
      - run: xvfb-run -a ./gradlew :shared:jvmTest :desktop:createReleaseDistributable --max-workers=4
        working-directory: android
```

`:shared:jvmTest` roda testes de código compartilhado no JVM (`android/shared/src/jvmTest/...`:
`MermaidCodeFenceTest`, `TwoPaneWideUiTest`, `TwoPaneNarrowUiTest`, `AsideScrollUiTest`,
`FilesDesktopAcceptanceTest`, `FileDownloadsJvmTest`, `data/LocalStateStoreJvmTest`, `data/NativeStorageRulesJvmTest`,
`data/AttachmentsJvmTest`, `data/AvatarCacheJvmTest`). Alguns cobrem comportamento comum (duas colunas, Mermaid)
que não tem outro teste de host; por isso ele **fica**.

Textos nativos realmente usados pelo código do app (`android/app/src/main`): `app_name` (manifesto e Kotlin),
`channel_alerts`, `channel_connection`, `notifications_running`, `notify_done`, `notify_permission`,
`notify_public`, `notify_question`. Os testes em `android/app/src/androidTest` usam `R.string.<chave>` do app
(65 chaves distintas) para achar textos na tela.

Divergência conferida: `settings_summarize_hint` é "O papel tiny do omp resume cada resposta..." no arquivo do
app e "O servidor resume cada resposta..." no compartilhado.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-107 -b advisor/107-zaplaud-sem-pacote-desktop-no-ci-e-sem-textos-orfaos producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Testes de host, lint e APK debug | `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` |
| Compilação dos instrumentados | `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :app:compileDebugAndroidTestKotlin` | `BUILD SUCCESSFUL` |

## Escopo

**Pode modificar**:
- `.github/workflows/android.yml` (uma linha)
- `android/app/src/main/res/values/strings.xml`

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Apagar o módulo `:desktop`, `jvmMain` ou `jvmTest`: decisão do dono, com perda de testes; não é este plano.
- `android/shared/src/commonMain/composeResources/values/strings.xml`: é a fonte; não mude textos nela.
- Reescrever os testes instrumentados para usarem os recursos compartilhados.

## Passos

### Passo 1: CI roda os testes JVM sem empacotar o desktop

Na linha 75 de `android.yml`, troque por
`- run: xvfb-run -a ./gradlew :shared:jvmTest --max-workers=4`. Os passos `setup-node` e `npm ci` continuam
(alguns testes JVM sobem a fixture Node; confirme com `grep -rn 'npx\|node ' android/shared/src/jvmTest | head`;
se nenhum teste JVM usar Node, remova também esses dois passos do job).

**Verificação**: `grep -n 'createReleaseDistributable' .github/workflows/android.yml` → nenhuma ocorrência;
YAML válido (`bun -e "Bun.YAML.parse(await Bun.file('.github/workflows/android.yml').text()); console.log('ok')"` → `ok`).

### Passo 2: textos nativos: só os usados, com o mesmo texto da fonte

Liste as chaves usadas:

```bash
{ grep -rhoE 'R\.string\.[a-z_0-9]+' android/app/src/main android/app/src/androidTest | sed 's/.*\.//';      grep -rhoE '@string/[a-z_0-9]+' android/app/src/main | sed 's/.*\///'; } | sort -u > /tmp/habblaud-107/usadas.txt
```

Em `android/app/src/main/res/values/strings.xml`, apague todo `<string>`/`<plurals>` cuja chave não está na
lista. Para cada chave que **fica** e que também existe no arquivo compartilhado, copie o texto do
compartilhado (a fonte). Chaves que só existem no arquivo do app (as de notificação) ficam como estão.

**Verificação**:
- toda chave restante está na lista:
  `grep -oE 'name="[a-z_0-9]+"' android/app/src/main/res/values/strings.xml | sed 's/name="//;s/"//' | sort -u | comm -23 - /tmp/habblaud-107/usadas.txt`
  → saída vazia;
- nenhuma chave usada ficou sem texto: o comando de host completo → `BUILD SUCCESSFUL` e
  `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :app:compileDebugAndroidTestKotlin` → `BUILD SUCCESSFUL`.

## Plano de testes

Sem teste novo. Os instrumentados existentes são quem prova que os textos batem com a tela; rodam só com
autorização do dono (`python3 scripts/android-instrumented-ci.py`). Sem ela, registre "instrumentados não
executados" no índice: há risco real de um teste passar a procurar o texto correto e revelar outra divergência.

## Critérios de pronto

Todos precisam valer:

- [ ] `grep -n 'createReleaseDistributable' .github/workflows/android.yml` → nenhuma ocorrência
- [ ] O arquivo de textos do app só tem chaves usadas por `android/app/src/main` ou `android/app/src/androidTest`
- [ ] Para as chaves presentes nos dois arquivos, o texto é idêntico
- [ ] Comando de host completo e `:app:compileDebugAndroidTestKotlin` terminam em `BUILD SUCCESSFUL`
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 107 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- Um teste instrumentado usar uma chave que não existe mais no arquivo compartilhado (texto removido da
  interface): não invente o texto; liste a chave no relato.
- Com autorização para emulador, um instrumentado falhar depois de sincronizar os textos: o teste estava
  passando com texto velho; relate qual, não reverta o texto.

## Notas de manutenção

- Enquanto a cópia existir, quem mudar um texto compartilhado usado em teste precisa mudar aqui também. A saída
  definitiva é os testes lerem os recursos compartilhados; fica para quando alguém mexer nesses testes.
- Decisão pendente do dono: apagar `android/desktop` e `jvmMain` de vez (perde os testes de duas colunas e do
  Mermaid no JVM, a menos que sejam portados para `androidHostTest`).
