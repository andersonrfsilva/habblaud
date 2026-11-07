# Plano 051: remover o gate vazio de VPN no CI e corrigir o README do app

> **Instruções ao executor**: siga os passos e verificações. Em condição de PARADA, pare e relate. Ao terminar, atualize a linha 051 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat 778466839..HEAD -- .github/workflows/android.yml README.pt-BR.md README.md README.es.md README.fr.md`.

## Status

- **Prioridade**: P3
- **Esforço**: S
- **Risco**: LOW
- **Depende de**: nenhum (base `778466839`)
- **Categoria**: dx / docs
- **Planejado em**: commit `778466839`, 2026-11-07

## Por que importa

- O CI roda `:app:testReleaseUnitTest --tests '*VpnGuardTest*'`, mas `VpnGuardTest` não existe mais. A task fica `NO-SOURCE` e passa verde sem testar nada (observado no gate da 043). A política atual (HTTP com consentimento, plano 037) está coberta por `UrlPolicyTest` em `commonTest`, que roda em `:shared:testAndroidHostTest`.
- O README diz que o app aceita "uma instalação por vez", mas o plano 042 entregou vários servidores ao mesmo tempo. Também pede plataforma 36 na seção de testes instrumentados e 37 na de build, enquanto o CI instala `android-37.0`.

## Estado atual

- `.github/workflows/android.yml:68-71`:

  ```yaml
  - run: ./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
    working-directory: android
  - run: ./gradlew :app:testReleaseUnitTest --tests '*VpnGuardTest*'
    working-directory: android
  ```

- `.github/workflows/android.yml:67` e `:96`: `sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0'`. O emulador do CI usa `api-level: 35` (`:115`).
- `README.pt-BR.md:249`: "O aplicativo aceita uma instalação por vez; informe endereço, usuário e senha do acesso remoto."
- `README.pt-BR.md:264`: "...Android SDK com `platforms;android-37.0` e ferramentas de build."
- `README.pt-BR.md:299-301`: "...Android SDK com plataforma 36 e build-tools 36.0.0; a CI usa um emulador API 35 com KVM."
- `plans/README.md:120` já registra que esse filtro não comprova cobertura VPN.

## Escopo

**Pode modificar**: `.github/workflows/android.yml` (só a etapa do filtro) e os READMEs da raiz (só os trechos acima e seus equivalentes em `README.md`, `README.es.md` e `README.fr.md`, se existirem). **Fora**: planos antigos (são registro histórico) e o resto dos READMEs.

## Passos

### Passo 1: CI

Remova as duas linhas da etapa `:app:testReleaseUnitTest --tests '*VpnGuardTest*'`. Não crie teste de VPN: a política atual não exige VPN (plano 037).

**Verificar**: `rg -n 'VpnGuard' .github README*.md android` → nenhuma ocorrência.

### Passo 2: README pt-BR

- `:249`: diga que o app aceita vários servidores ao mesmo tempo, com login por servidor, e que se informa endereço, usuário e senha de cada um. Confira a interface antes de escrever (plano 042: lista única com filtro).
- `:299-301`: alinhe a `platforms;android-37.0` e `build-tools;36.0.0`, mantendo a menção ao emulador API 35 do CI.

### Passo 3: outras línguas

Para cada `README.md`, `README.es.md` e `README.fr.md`, localize os trechos equivalentes (`rg -n 'Zaplaud' README*.md`) e aplique a mesma correção no idioma do arquivo. Se um idioma não tiver o trecho, não acrescente.

### Passo 4: validação

- `rg -n 'uma instalação por vez|plataforma 36' README.pt-BR.md` → sem ocorrência.
- Se o projeto tiver verificação de i18n ou docs no CI (`rg -n 'i18n|markdownlint' .github/workflows`), rode o comando local equivalente.

## Critérios de pronto

- [ ] Validações do Passo 4 ok.
- [ ] `git diff --stat` só nos arquivos do escopo.
- [ ] Linha 051 atualizada.

## Condições de PARADA

- O app na branch atual ainda limitar a um servidor (o plano 042 não estaria na base).

## Notas de manutenção

- Filtros `--tests` em tasks sem fonte passam verdes sem testar nada. Prefira apontar para testes que existem no módulo.
