# Plano 040: Acompanhar dependências Gradle Android com Dependabot

> Execute na raiz do repositório. Confira cada resultado antes de avançar. Se uma condição STOP ocorrer, interrompa e relate. Não faça commit, push ou abra PR sem autorização. Atualize a linha deste plano em `plans/README.md` ao concluir, salvo orientação diferente do responsável pelo índice.
>
> **Deriva:** `git diff --stat 9469c0b..HEAD -- .github/dependabot.yml android/settings.gradle.kts android/gradle/libs.versions.toml android/app/build.gradle.kts plans/README.md` e `git status --short -- .github/dependabot.yml android/settings.gradle.kts android/gradle/libs.versions.toml android/app/build.gradle.kts plans/README.md`. Compare mudanças com os trechos abaixo; preserve alterações preexistentes e pare se afetarem premissas.

## Status

- **Prioridade:** P2; **esforço:** S; **risco:** baixo; **categoria:** dependências.
- **Dependências:** nenhuma; este plano independe do plano 036. Planejado em `9469c0b`, 2026-11-03.

## Por que importa

O Dependabot acompanha npm, Docker e GitHub Actions, mas a configuração atual não inclui Gradle. Isso deixa dependências e plugins Android sem esse acompanhamento configurado. Uma entrada semanal aponta o serviço para o projeto Gradle, sem atualizar versões automaticamente nem alterar código Android.

## Estado atual

- `.github/dependabot.yml:3-9,16-20,26-30` tem `version: 2`, entradas `npm`, `docker` e `github-actions`, todas com `directory: "/"` e `schedule: interval: "weekly"`. npm e Docker têm regras de ignore específicas que devem permanecer intactas.
- `android/settings.gradle.kts:1-4` define repositórios Google, Maven Central e Gradle Plugin Portal, nome `Habblaud` e módulo `:app`.
- `android/gradle/libs.versions.toml:1-18` define versões e plugins, incluindo AGP, Kotlin e Compose; linhas 20-39 definem bibliotecas. `android/app/build.gradle.kts:1-4,50-72` consome aliases desse catálogo.
- `.github/workflows/android.yml:20-33` usa Java 17 e executa `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` e testes unitários release filtrados. Essas verificações avaliam PRs de dependências; elas não provam que o serviço Dependabot analisou o catálogo.

## Escopo

Modificar somente `.github/dependabot.yml` e, quando autorizado, a linha deste plano em `plans/README.md`. Não atualizar versões, wrapper, código, workflow, repositórios Gradle ou outros ecossistemas. O comentário no próprio arquivo documenta a nova configuração; nenhum changelog de produto é necessário para esta alteração interna.

## Passos

1. Ao final de `updates` em `.github/dependabot.yml`, acrescente uma entrada irmã das existentes:

   ```yaml
   # Dependências e plugins Gradle do aplicativo Android
   - package-ecosystem: "gradle"
     directory: "/android"
     schedule:
       interval: "weekly"
   ```

   Não acrescente credenciais, registry privado, política de ignore, agrupamento ou permissões. Preserve as entradas existentes.

   **Verificação:** `git diff -- .github/dependabot.yml` deve mostrar apenas comentário e entrada acima; o nível de indentação deve ser igual ao das entradas existentes. Execute `ruby -e 'require "yaml"; x = YAML.load_file(".github/dependabot.yml"); abort "version" unless x["version"] == 2; u = x.fetch("updates"); g = u.select { |e| e["package-ecosystem"] == "gradle" }; abort "gradle" unless g.size == 1 && g[0]["directory"] == "/android" && g[0].dig("schedule", "interval") == "weekly"; abort "ecossistemas" unless %w[npm docker github-actions].all? { |n| u.count { |e| e["package-ecosystem"] == n } == 1 }; puts "configuração estrutural OK"'` e espere essa mensagem e saída 0. Se Ruby ou parser YAML não estiver disponível, use parser já instalado que preserve a mesma verificação; não instale dependências apenas para isso.
2. Confira a referência oficial `https://docs.github.com/code-security/dependabot/dependabot-version-updates/configuration-options-for-the-dependabot.yml-file` para suporte a `gradle`, `directory` e opções atuais. Não assuma que a checagem YAML comprova análise de todos os aliases do catálogo ou do wrapper.

   **Verificação:** confirme que a documentação aceita `package-ecosystem: gradle`, diretório relativo à raiz `/android` e frequência `weekly`; se não aceitar, STOP e relate.

## Testes e critérios de conclusão

- O parser estrutural acima sai 0; `git diff --check -- .github/dependabot.yml` sai 0; diff preserva regras anteriores e não altera versões nem credenciais.
- `git status --short` mostra somente arquivos permitidos, além de alterações preexistentes preservadas. Atualize o índice quando autorizado.
- Após merge autorizado, um mantenedor deve verificar no GitHub os logs da execução de atualizações Dependabot para Gradle e confirmar que o diretório e manifesto/catálogo foram analisados sem erro. A ausência de PR não indica falha: pode não haver atualização disponível. O serviço pode não suportar todos os formatos, aliases ou plugins; reporte cobertura efetivamente observada, sem prometê-la. Se o serviço ou logs forem inacessíveis, marque essa validação externa como pendente; não declare execução real comprovada.
- Não é necessário executar build/testes Android para mudar apenas YAML. Quando um PR real de versão existir, o workflow Android existente deve passar antes do merge desse PR; não force uma atualização como teste deste plano.

## STOP e manutenção

Pare se já houver entrada Gradle para `/android`, se o projeto Gradle mudar de localização, se houver divergência relevante, se o GitHub rejeitar a configuração, ou se a solução exigir credenciais/registry privado. Não copie segredos em logs, plano ou PR. Ao mudar a localização do projeto ou formato do catálogo, revise o diretório e os logs do Dependabot; YAML válido sozinho não comprova cobertura real.
