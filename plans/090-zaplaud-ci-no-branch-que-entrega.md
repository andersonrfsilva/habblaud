# Plano 090: Zaplaud: o CI móvel roda no branch `producao`, valida os testes que diz validar e compila o APK release com R8

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 090 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- .github/workflows/android.yml .github/workflows/ios.yml scripts/android-instrumented-ci.py CONTRIBUTING.md`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: S–M
- **Risco**: MED (gatilhos mais amplos gastam minutos de runner; o job instrumentado continua dependendo de KVM)
- **Depende de**: nenhum
- **Categoria**: tests / dx
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O desenvolvimento acontece no branch `producao` (é o branch atual do clone e a base de todos os planos recentes),
mas o workflow Android só dispara em push para `main` e o do iPadOS em push para um branch antigo de plano. Um
push em `producao` não roda nenhum teste móvel. Além disso, sem `/dev/kvm` o job instrumentado emite um aviso e
termina verde, o validador aceita uma suíte `FixtureUiInstrumentedTest` inteira pulada, e o APK release
(minificado com R8, o que o dono instala) nunca é compilado no CI. Hoje um plano pode quebrar o app distribuído
com o CI verde. Os demais planos da auditoria (091 em diante) dependem deste gate.

## Estado atual

`.github/workflows/android.yml`:

```yaml
# :3-6
on:
  workflow_dispatch:
  push:
    branches: [main]
# :68  (job verificar)
      - run: ./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# :75  (desktop Compose obsoleto; NÃO é o APK Android)
      - run: xvfb-run -a ./gradlew :shared:jvmTest :desktop:createReleaseDistributable --max-workers=4
# :114-117 (job instrumentado)
            echo "::warning::Sem /dev/kvm neste runner: os testes instrumentados do Android não rodaram."
            echo "ok=false" >> "$GITHUB_OUTPUT"
      - if: steps.kvm.outputs.ok == 'true'
        uses: reactivecircus/android-emulator-runner@...
```

Os filtros `paths` (linhas 7-17 e 19-29) não incluem `scripts/android-files-instrumented.py`, que o gate importa
(`scripts/android-instrumented-ci.py:88`).

`.github/workflows/ios.yml:6-7`: `push: branches: [advisor/041-zaplaud-ipados-kotlin]` (branch de um plano já
mesclado).

`scripts/android-instrumented-ci.py`:

```python
# :127-130  aceita o caso mesmo se <skipped/>
has_fixture = any('FixtureUiInstrumentedTest' in case.get('classname', '')
                  for suite in suites for case in suite.iter('testcase'))
if not suites or tests <= 0 or failures or errors or not has_fixture:
    raise RuntimeError('Android test XML does not meet acceptance criteria')
# :131-140  só seis classes do módulo shared são exigidas; `executed` já exclui as puladas
required_shared = {
    'PersistenceInstrumentedTest', 'ConversationEpochInstrumentedTest',
    'LastSessionOrderInstrumentedTest', 'AvatarCacheInstrumentedTest',
    'SessionCookieStoreTest', 'LocalStateStoreTest',
}
executed = { ... if case.find('skipped') is None }
```

Classes instrumentadas do app (`android/app/src/androidTest/java/br/com/xlinx/habblaud/`):
`FixtureFilesInstrumentedTest`, `FixtureUiInstrumentedTest`, `HttpLoginInstrumentedTest`,
`NotificationPrivacyInstrumentedTest`, `StatusLineInstrumentedTest`. No shared existe também
`PersistenceFailureInstrumentedTest` (plano 049), fora da lista exigida.

`android/app/build.gradle.kts:21-30`: o `signingConfig` release lê `HABBLAUD_KEYSTORE`,
`HABBLAUD_KEYSTORE_PASSWORD`, `HABBLAUD_KEY_ALIAS` e `HABBLAUD_KEY_PASSWORD` de variável de ambiente ou
propriedade Gradle; sem as quatro, `packageRelease` falha (`validateReleaseSigning`).

`CONTRIBUTING.md` descreve a verificação Node antes de um PR, sem comando para o Zaplaud.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-090 -b advisor/090-zaplaud-ci-no-branch-que-entrega producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Testes de host, lint e APK debug | `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` |

## Escopo

**Pode modificar**:
- `.github/workflows/android.yml`
- `.github/workflows/ios.yml`
- `scripts/android-instrumented-ci.py`
- `CONTRIBUTING.md` (um parágrafo com o comando de verificação do Zaplaud)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- `android/app/build.gradle.kts` e a chave de release do dono: o CI usa uma chave descartável gerada no próprio
  job; nenhuma chave ou senha real entra no repositório nem em segredos do CI.
- Tornar o job instrumentado obrigatório ou trocar de runner: o runner hospedado deste repositório não tem KVM;
  usar um runner próprio é decisão do dono (ver "Notas de manutenção").
- Remover o passo do desktop Compose (linha 75): é o plano 107.
- Qualquer arquivo Kotlin.

## Passos

### Passo 1: gatilhos no branch que entrega

Em `android.yml`, troque `branches: [main]` por `branches: [main, producao]` e acrescente
`'scripts/android-files-instrumented.py'` às duas listas `paths` (push e pull_request).

Em `ios.yml`, troque `branches: [advisor/041-zaplaud-ipados-kotlin]` por `branches: [main, producao]` e dê ao
`push` a mesma lista `paths` que o `pull_request` já tem logo abaixo (runner macOS custa caro: sem o filtro ele
rodaria em todo push).

**Verificação** (nesta máquina não há PyYAML nem Ruby; o Bun instalado lê YAML):
`bun -e "for (const f of process.argv.slice(2)) Bun.YAML.parse(await Bun.file(f).text()); console.log('ok')" .github/workflows/android.yml .github/workflows/ios.yml` → `ok`

### Passo 2: APK release minificado compilado no CI com chave descartável

No job `verificar` de `android.yml`, depois do passo da linha 68, acrescente um passo que gera um keystore
temporário e compila o release:

```yaml
    - name: APK release (R8) com chave descartável do CI
      working-directory: android
      env:
        HABBLAUD_KEYSTORE: ${{ runner.temp }}/ci.jks
        HABBLAUD_KEYSTORE_PASSWORD: ci-descartavel
        HABBLAUD_KEY_ALIAS: ci
        HABBLAUD_KEY_PASSWORD: ci-descartavel
      run: |
        keytool -genkeypair -keystore "$HABBLAUD_KEYSTORE" -storepass "$HABBLAUD_KEYSTORE_PASSWORD" -keypass "$HABBLAUD_KEY_PASSWORD" -alias "$HABBLAUD_KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 1 -dname 'CN=CI descartavel'
        ./gradlew :app:assembleRelease
```

O APK gerado não é publicado como artefato (é assinado com chave descartável; ninguém deve instalá-lo).

**Verificação local** (prova que o release compila hoje, na worktree em `/tmp`):
`keytool -genkeypair -keystore /tmp/habblaud-090/ci.jks -storepass ci-descartavel -keypass ci-descartavel -alias ci -keyalg RSA -keysize 2048 -validity 1 -dname 'CN=CI descartavel' && HABBLAUD_KEYSTORE=/tmp/habblaud-090/ci.jks HABBLAUD_KEYSTORE_PASSWORD=ci-descartavel HABBLAUD_KEY_ALIAS=ci HABBLAUD_KEY_PASSWORD=ci-descartavel ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :app:assembleRelease`
→ `BUILD SUCCESSFUL`. Apague `/tmp/habblaud-090/ci.jks` depois.

### Passo 3: validador exige execução real das suítes críticas

Em `scripts/android-instrumented-ci.py`, leia o trecho de `:116` até o fim da função. Mova a construção de
`executed` para antes do `if` da linha 129 e troque `has_fixture` por uma lista exigida do app, conferida
contra `executed` (que já ignora casos com `<skipped/>`):

```python
required_app = {
    'FixtureUiInstrumentedTest', 'FixtureFilesInstrumentedTest', 'HttpLoginInstrumentedTest',
    'NotificationPrivacyInstrumentedTest', 'StatusLineInstrumentedTest',
}
```

Acrescente `'PersistenceFailureInstrumentedTest'` a `required_shared`. A mensagem de erro deve listar as
classes que faltaram (`sorted((required_app | required_shared) - executed)`), para o log dizer o que não rodou.
Mantenha as checagens de `tests`, `failures` e `errors`.

**Verificação**: `python3 -m py_compile scripts/android-instrumented-ci.py` → sai com 0.

### Passo 4: aviso de KVM vira resumo visível

No passo `Enable KVM` de `android.yml`, além do `::warning::`, escreva a mesma frase em `$GITHUB_STEP_SUMMARY`
(`echo '...' >> "$GITHUB_STEP_SUMMARY"`), para a ausência do teste aparecer na página da execução e não só no
log. Não transforme em erro: o runner hospedado deste repositório não tem KVM, e falhar aqui bloquearia todo
merge.

**Verificação**: a mesma do passo 1 (YAML válido).

### Passo 5: comando único documentado

Em `CONTRIBUTING.md`, na seção que lista o que rodar antes de um PR, acrescente um parágrafo curto: quem mexe em
`android/` roda `android/gradlew -p android :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
(JDK 17, Android SDK com `platforms;android-37.0`); os testes instrumentados rodam com
`python3 scripts/android-instrumented-ci.py` num emulador isolado; o iPadOS só compila num Mac
(`scripts/ios-build.sh simulator`).

**Verificação**: `npm run i18n:check -- --strict` → sai com 0 (o CONTRIBUTING não tem tradução, mas o comando
confirma que nada mais quebrou).

## Plano de testes

Não há teste novo de aplicação. A prova é: YAML válido, `py_compile` ok e `:app:assembleRelease` compilando com a
chave descartável. A lógica nova do validador não pode ser exercitada sem emulador; se o dono autorizar o
emulador, rode `python3 scripts/android-instrumented-ci.py` e confirme saída 0.

## Critérios de pronto

Todos precisam valer:

- [ ] `grep -n 'branches:' .github/workflows/android.yml .github/workflows/ios.yml` mostra `[main, producao]` nos dois `push`
- [ ] `grep -c 'android-files-instrumented.py' .github/workflows/android.yml` → `2`
- [ ] `grep -n 'assembleRelease' .github/workflows/android.yml` → 1 ocorrência
- [ ] `grep -n 'has_fixture' scripts/android-instrumented-ci.py` → nenhuma ocorrência
- [ ] `python3 -m py_compile scripts/android-instrumented-ci.py` sai com 0
- [ ] `:app:assembleRelease` local com a chave descartável termina em `BUILD SUCCESSFUL`
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 090 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- `:app:assembleRelease` falhar por regra do R8 (classe ausente, `Missing class`): isso é um defeito real do
  release. Relate a saída; não acrescente regras `-keep`/`-dontwarn` por conta própria.
- O arquivo `ios.yml` tiver sido reescrito e o `push` não apontar mais para o branch antigo.

## Notas de manutenção

- **Decisão pendente do dono**: o job instrumentado só roda com KVM, que o runner hospedado deste repositório
  não oferece. Para o gate valer de verdade é preciso um runner próprio com KVM (a máquina de desenvolvimento
  tem) ou tornar o repositório elegível a runners com virtualização. Até lá, os instrumentados continuam manuais.
- Quem acrescentar uma classe instrumentada nova deve incluí-la em `required_app`/`required_shared`.
- O revisor deve conferir que nenhuma senha além da descartável aparece no YAML.
