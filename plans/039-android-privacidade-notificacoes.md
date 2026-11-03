# Plano 039: Ocultar detalhes das notificações na tela bloqueada

> Executor: siga etapas e gates; em divergência, pare e relate. Não altere o índice sem coordenação com seu responsável. Na raiz do repositório, execute `git diff --stat 9469c0b..HEAD -- android/app/src/main/java/br/com/xlinx/habblaud/NotificationService.kt android/app/src/main/res/values/strings.xml android/app/src/androidTest/java/br/com/xlinx/habblaud/NotificationPrivacyInstrumentedTest.kt CHANGELOG.md`; compare também `git diff` e `git status --short` desses caminhos com os trechos abaixo. Preserve alterações locais preexistentes.

## Estado e motivação

- Prioridade P1; esforço M; risco médio; categoria privacidade; dependências: nenhuma. Planejado em `9469c0b`, 2026-11-03. O manifesto tinha alteração local alheia ao plano na leitura; não o modifique.
- `android/app/src/main/java/br/com/xlinx/habblaud/NotificationService.kt:76-97` publica alertas com título `session.characterName ?: session.title`, texto que distingue aprovação, pergunta ou turno concluído, e projeto no `setSubText`. Esses dados podem revelar atividade pessoal na tela bloqueada. Ainda não há `setVisibility` ou versão pública.
- Trecho atual: `.setContentTitle(session.characterName ?: session.title).setContentText(text).setSubText(session.project?.substringAfterLast('/'))`, seguido de `setContentIntent(PendingIntent.getActivity(this, id, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))`, `setAutoCancel(true)`, prioridade alta e categoria mensagem. O identificador é `session.key.serialize().hashCode()`; a intent carrega `MainActivity.EXTRA_SESSION` com a chave serializada. `MainActivity.kt:193-195` recebe e remove esse extra.
- `NotificationService.kt:111-115` cria canal `alerts` de alta importância e canal `connection` de importância mínima. `onCreate():35-41` monta notificação permanente sem detalhes de sessão. `strings.xml:18,137-144` fornece `app_name`, nomes dos canais e textos dos três eventos. Só existe `values/strings.xml`; mantenha português e convenção dos recursos.
- Android permite `NotificationCompat.VISIBILITY_PRIVATE` e `setPublicVersion`: a versão pública genérica é usada quando o sistema oculta conteúdo privado na tela bloqueada. Configuração do aparelho, usuário, canal e fabricante controla exibição: não prometa ocultação universal nem que a notificação apareça. Não há garantia de obrigar desbloqueio usando apenas visibilidade.

## Escopo

Modificar somente `android/app/src/main/java/br/com/xlinx/habblaud/NotificationService.kt`, `android/app/src/main/res/values/strings.xml`, `CHANGELOG.md` e criar `android/app/src/androidTest/java/br/com/xlinx/habblaud/NotificationPrivacyInstrumentedTest.kt`. Não mudar canais existentes, notificações permanentes, conteúdo privado, regras de seleção, permissões, política de tela bloqueada, nem comportamento do servidor. Não exigir senha de aparelho nem mudar configurações do usuário.

## Etapas

1. No builder de alerta, adicione `setVisibility(NotificationCompat.VISIBILITY_PRIVATE)` e `setPublicVersion(...)`. Construa versão pública com mesmo ícone pequeno, título `R.string.app_name` e texto genérico novo em `strings.xml`, por exemplo `Nova atividade no Zaplaud`. Não inclua nome, título, projeto, conteúdo, tipo de evento, extras ou metadados sensíveis na versão pública. Não adicione content intent à versão pública; mantenha intent original na notificação privada. Não mude ID, canal, título, texto, subtítulo, prioridade ou clique da notificação original. Não recrie nem force visibilidade de canal já existente.
   - Gate: `git diff -- android/app/src/main/java/br/com/xlinx/habblaud/NotificationService.kt android/app/src/main/res/values/strings.xml` mostra versão pública genérica e mantém linhas de identificação e intent originais.
2. Crie teste instrumentado, padrão `AndroidJUnit4`, `ApplicationProvider` e `ActivityScenario` conforme `FixtureUiInstrumentedTest.kt:40-76`. Extraia somente se necessário um construtor interno de notificação de alerta para produzir `Notification` real sem iniciar stream; mantenha produção e teste usando exatamente o mesmo construtor, sem API pública extra. Teste os três tipos de evento com nomes/projetos fictícios distintos.
   - Inspecione `Notification.visibility == Notification.VISIBILITY_PRIVATE`, `publicVersion != null` e `Notification.EXTRA_TITLE`, `EXTRA_TEXT` e `EXTRA_SUB_TEXT` da versão pública; exija título e texto genéricos e ausência dos valores sensíveis e do tipo de evento. Verifique, na versão privada, título original, texto específico, subtítulo, canal `alerts`, ID calculado pela chave e `contentIntent` não nula. Compare duas chaves distintas para confirmar identificadores distintos dos dados de teste.
   - Exercite clique privado: envie o `PendingIntent` com app desbloqueado e verifique que `MainActivity.pendingSession` recebe a chave esperada; restaure estado estático e feche a atividade no `finally`. Construa evento a partir dos tipos reais de `AgentEvent` e `SessionKey` do repositório, sem criar duplicata de lógica de produção no teste. Não exija notificação exibida para inspecionar objeto; isso evita depender de permissão e configuração do usuário.
   - Gate: `test -f android/app/src/androidTest/java/br/com/xlinx/habblaud/NotificationPrivacyInstrumentedTest.kt && git status --short -- android/app/src/androidTest/java/br/com/xlinx/habblaud/NotificationPrivacyInstrumentedTest.kt` confirma presença e situação do arquivo, inclusive se não rastreado.
3. Em `CHANGELOG.md`, na seção `Não lançado`, registre que alertas oferecem versão genérica quando o sistema oculta conteúdo privado. Explique que a exibição depende das configurações do sistema e usuário; não prometa proteção universal.
   - Gate: `git diff -- CHANGELOG.md` mostra uma entrada pertinente sem alteração de itens não relacionados.

## Testes e critérios de conclusão

- Com SDK Android e emulador/dispositivo conectado: `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=br.com.xlinx.habblaud.NotificationPrivacyInstrumentedTest` deve terminar com código 0 e testes da classe aprovados. Não foi executado durante o planejamento.
- Verificação manual em aparelho/emulador com bloqueio seguro, permissão concedida, notificações na tela bloqueada habilitadas e conteúdo sensível configurado como oculto: provoque alerta de teste; se mostrado, a tela bloqueada deve exibir apenas versão genérica, sem nome, projeto ou tipo. Desbloqueie: título, texto e projeto continuam originais; toque abre sessão correta. Registre modelo, API, configuração e resultado. Se política ou fabricante impedir a visualização, registre como inconclusivo, não como sucesso. Não altere configurações permanentes sem restaurá-las.
- `git status --short` mostra apenas os quatro caminhos de escopo alterados pelo executor, além de alterações anteriores preservadas. Os testes automatizados verificam os três tipos e a navegação.

## STOP e riscos

Pare se o estado divergir dos trechos, se não for possível testar a intent real sem mudar comportamento, ou se a correção exigir mudar o canal existente ou qualquer arquivo fora do escopo. Após duas falhas do mesmo gate, pare e relate. O sistema e usuário podem optar por exibir ou ocultar conteúdo, desativar canal ou notificações; a API não oferece garantia universal contra divulgação. Um alerta já publicado antes da atualização não é reescrito por este plano.

## Execução bloqueada em 2026-11-03

- Trabalho não commitado: `/tmp/habblaud-plan039`. Não foi mesclado. O teste instrumentado de versão pública e clique passou também na revisão independente.
- No emulador API 36, com bloqueio seguro e conteúdo privado oculto, um alerta fictício construído pelo código de produção mostrou apenas o ícone na tela bloqueada. Após desbloqueio, o painel também não mostrou texto; portanto, apresentação privada e clique manual não foram confirmados. Configurações temporárias e disparador foram removidos.
- Próximo executor: repetir o ensaio completo em ambiente capaz de exibir o conteúdo e registrar resultado. Não inferir sucesso visual do teste de objetos.
