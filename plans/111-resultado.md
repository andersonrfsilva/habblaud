# Plano 111: diagnóstico de acesso por servidor no Zaplaud

Base: `7236378`. Evidência de leitura estática; as referências `K/` abaixo são relativas a `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/`.

## Respostas

### 1. Combinações e efeito atual no cliente Kotlin

O acesso é efetivo **para esta conexão**, não uma lista de privilégios configurados para cada conta de agente. Na porta remota autenticada, cada permissão exige tanto o item na lista do servidor quanto o recurso ligado. Na porta local, o servidor aplica a regra de loopback. O Kotlin lê `terminal`, `messages`, `messageSessionGuard` e `permissions`; não usa `remote`, `local`, `spawn`, `world` nem `files` nessa leitura. O login exige sucesso de `/api/access`, mas descarta seu corpo. Fontes: `server/http/app.ts:310–320,480–495`, `server/http/remote.ts:82–106,602–610,658–670`, `K/data/SessionRepository.kt:55–68`, `K/ZaplaudApp.kt:211–217`.

Para um servidor remoto com os recursos globais ativos, a tabela cobre todas as combinações das três travas que o Kotlin lê como permissões. `messages=true` na tabela pressupõe a proteção anunciada; as exceções estão abaixo. Sem as três, o login ainda dá acesso à lista de sessões abertas.

| `terminal` | `messages` | `permissions` | O que aparece ou funciona hoje |
| --- | --- | --- | --- |
| Não | Não | Não | Abertas; aviso de leitura bloqueada; compose sem envio; sem resposta a pedidos. |
| Não | Não | Sim | Como acima, mas permite responder quando há pedido elegível. É o padrão remoto se o recurso global estiver ativo. |
| Não | Sim | Não | Abertas e envio elegível em sessão viva; leitura/recentes bloqueados; sem resposta a pedidos. |
| Não | Sim | Sim | Abertas, envio elegível e resposta a pedidos; leitura/recentes bloqueados. |
| Sim | Não | Não | Leitura e recentes; ações do elenco se disponíveis; compose sem envio; sem resposta a pedidos. |
| Sim | Não | Sim | Leitura, recentes e elenco se disponível; resposta a pedidos; compose sem envio. |
| Sim | Sim | Não | Leitura, recentes, elenco se disponível e envio elegível; sem resposta a pedidos. |
| Sim | Sim | Sim | Leitura, recentes, elenco se disponível, envio elegível e resposta a pedidos. |

- **Leitura e elenco:** `terminal=false` impede buscar recentes e abrir o stream da conversa, não o snapshot de abertas. A lista mantém a aba Recentes; o aviso geral usa `any { terminalAllowed }` entre servidores e pode sumir mesmo se a origem escolhida estiver bloqueada. A conversa usa a origem selecionada. A consulta do elenco exige origem definida, conexão, login válido e `terminalAllowed || selectedPermission`. Sem isso, não consulta e as ações somem; com um pedido pendente, ainda pode consultar sem `terminal` e receber `403`. A consulta de jobs separa `403` de `available=false`; só `available=true` oferece chamar/retomar/dispensar, cada ação com seus pré-requisitos. Fontes: `README.pt-BR.md:365–373`, `K/ReadingScreen.kt:106–107,162–181,262,404–419,528–531`, `K/data/ReadingController.kt:280`, `K/CastActions.kt:107–130,181–214`, `server/http/app.ts:556–565`.
- **Mensagens:** nesta revisão o servidor anuncia `messages` e `messageSessionGuard` com a mesma condição. Servidor antigo pode ter `messages=true` sem `messageSessionGuard`; o compose mostra `message_guard_required`, não envia mensagens/anexos nem executa os controles protegidos. `messages=false` tem seu próprio aviso (`messages_blocked`) e prevalece mesmo se uma resposta incoerente trouxer `messageSessionGuard=true`. Com os dois ligados, sessão precisa estar viva, conectada e anunciar `canMessage`; histórico e subagentes não recebem compose. Fontes: `server/http/app.ts:488–489`, `README.pt-BR.md:372–384`, `K/ReadingScreen.kt:118–122,658–675`, `K/data/ReadingController.kt:80–89`.
- **Aprovações:** a presença do pedido não prova que o usuário pode responder. O SSE envia o snapshot antes de classificar o cliente como apto a responder. Um pedido anunciado pode mostrar o botão; a gaveta exibe `permissions_blocked` e não consulta nem responde se `permissions=false`. Sem pedido não há controle. Fontes: `server/http/sse.ts:82–94`, `server/index.ts:227–230`, `K/ReadingScreen.kt:183–190,502`, `K/CastActions.kt:183–185`, `K/PermissionSheet.kt:84–86,134,187`.
- **Arquivos:** o menu da lista/conversa não usa a flag `files`. Abre a gaveta; se a API responde `403`, aparece “arquivos desligados”; se responde `401`, pede novo login para aquela origem. Em Node com terminal global ativo, `files` acompanha a trava remota `terminal`; em Docker fica desligado mesmo quando `terminal=true`. Fontes: `K/ReadingScreen.kt:309,333,344–349`, `K/FilesSheet.kt:47–57,115–122`, `server/http/app.ts:493,748–761`, `server/index.ts:458–469`.
- **Outros e limites:** `spawn` é uma quarta permissão remota independente na lista. Pode estar ligada ou desligada em cada linha quando o serviço existe; não é o **Chamar** do Zaplaud, que usa `/api/cast/*` e `terminal`. `world` é sempre falso no remoto. Se a trava global do terminal está fechada, cai o acesso efetivo de leitura, respostas, mensagens, arquivos e spawn; `HABBLAUD_MENSAGENS` também pode desligar apenas mensagens e o serviço de spawn. Configurações lista servidores e saída; algumas seções consultam endpoints de cotas, papéis/modelos e saúde, mas não oferecem um mapa de acesso. Fontes: `server/http/remote.ts:34–36,114–121`, `server/http/app.ts:487–493,556–567`, `server/config.ts:198–218`, `server/index.ts:329–354`, `K/CastActions.kt:165–166`, `K/SettingsScreen.kt:84–103,197–215,248–264`.

### 2. O que o operador pode mudar

No ambiente do servidor, acrescente apenas a permissão desejada a `HABBLAUD_REMOTE_ALLOW`, preservando as liberações já aprovadas. O valor é uma lista completa, não um incremento. Exemplo: para acrescentar mensagens ao padrão de aprovações, use `HABBLAUD_REMOTE_ALLOW=permissions,messages`. Se hoje o valor for `none`, substitua por apenas o que deseja liberar. Vazio/ausente volta ao padrão `permissions`; entradas desconhecidas são ignoradas. Em Docker, altere o `.env` e reaplique com `npm run docker:up -- --no-build`. Em Node, ajuste o ambiente do processo e reinicie a instância pelo mecanismo que a iniciou (para instalação já compilada e iniciada à mão, `npm start` com o ambiente ajustado). Se a porta remota ainda não existe, o fluxo documentado começa com `npm run remote:setup`; esse comando não é necessário para apenas mudar a lista. Fontes: `server/config.ts:290–297`, `server/http/remote.ts:76–121`, `package.json:25`, `README.pt-BR.md:557–564,582–584,1115,1132–1137`.

| Bloqueio | Instrução exata, se a causa for confirmada |
| --- | --- |
| Leitura e recentes (`terminal`) | Inclua `terminal` em `HABBLAUD_REMOTE_ALLOW`. Se a trava global estiver fechada, use `HABBLAUD_TERMINAL=1` (ou remova o valor que desliga) e mantenha a porta **principal** só em loopback: `HABBLAUD_HOST=127.0.0.1` no Node ou `HABBLAUD_BIND=127.0.0.1` no Docker, repassado ao contêiner. Não exponha a porta principal para resolver acesso remoto. `server/config.ts:188–207`; `README.pt-BR.md:557–560,1102–1112`. |
| Responder pedidos (`permissions`) | Inclua `permissions` e mantenha a trava global anterior aberta. Se a permissão já aparece liberada, confirme que a integração da sessão publica pedidos; para Claude Code com hooks legados, `npm run hooks:install` é o comando documentado. `server/index.ts:328–342`; `README.pt-BR.md:215,843–847`. |
| Enviar (`messages`) | Inclua `messages` e mantenha a trava global do terminal aberta. Se `HABBLAUD_MENSAGENS` estiver desligada, use `HABBLAUD_MENSAGENS=1` ou remova o valor. Não é necessário adicionar `terminal` à lista **remota** só para enviar. Se a sessão não anuncia `canMessage`, a lista de acesso não resolve; verifique a integração do agente. `server/config.ts:198–218`; `server/index.ts:343–347,371–378`; `K/data/ReadingController.kt:88–89`; `README.pt-BR.md:882–885,1117`. |
| Proteção do envio (`messageSessionGuard`) | Não há variável separada. Se `messages=true` e a proteção não for anunciada, use uma versão compatível do servidor deste repositório; não ignore a proteção. Com `messages=false`, investigue primeiro a liberação de mensagens. `server/http/app.ts:488–489`; `README.pt-BR.md:375–384`. |
| Navegar arquivos (`files`) | Inclua `terminal` e mantenha a trava global ligada. Não existe item `files` nem chave que ligue essa navegação no Docker; para usar, execute o servidor fora do Docker. `server/http/remote.ts:34–36`; `server/index.ts:458–469`; `server/http/app.ts:748–761`. |
| Gerenciar contatos (elenco) | Inclua `terminal` e mantenha a trava global ligada. Se `/api/cast/jobs` responder `available=false`, no modo omp verifique o auxiliar no host (`npm run codex:bridge`); no modo ZeroClaw a disponibilidade vem da integração configurada, não desse auxiliar. `server/index.ts:163–185`; `server/http/app.ts:556–565`; `server/cast/launcher.ts:37,82`; `server/README.md:212`. |
| Novo agente do escritório (`spawn`), não o elenco | Inclua `spawn`, use `HABBLAUD_PTY=1` e mantenha as travas **globais** de mensagens e terminal abertas; no Docker, rode `npm run codex:bridge` no host. É instrução para o recurso do servidor, não para fazer aparecer **Chamar** no Zaplaud. `server/index.ts:348–359`; `README.pt-BR.md:587–592`. |
| Habblaud World (`world`) | Não há liberação remota. Use no próprio computador. Se desligado localmente, verifique `HABBLAUD_WORLD`, a URL configurada e a trava do terminal para o anúncio em `/api/access`. `server/config.ts:231–262`; `server/index.ts:117–133`; `server/http/app.ts:427–436,492`. |

**Lacuna:** um `false` não identifica o motivo. A API não diz se faltou item na lista, se o recurso está desligado ou se o ambiente impede o recurso. Um `false` em `files` não revela Docker. O app pode indicar o que conferir, mas não afirmar qual ajuste fazer naquela instalação. Também não sabe qual gerenciador reinicia um serviço Node instalado pelo operador. Não há comando de serviço universal sustentado pelas fontes.

### 3. Onde cabe sem tela nova

- **Configurações › Servidores:** expandir a linha já existente por origem, sem mudar a navegação. É o ponto permanente e evita atribuir o acesso de um servidor a outro. A linha já separa origens, mas hoje só oferece saída; coletar o acesso da origem expandida. `K/SettingsScreen.kt:66–71,257–264`.
- **Aviso único no primeiro login:** ajuda quem acabou de configurar um servidor. O resultado da consulta já está disponível nesse ponto. Usar um aviso não modal com atalho para Configurações apenas se houver limitação relevante; exige gravar que o aviso foi visto por origem, sem repeti-lo em todo relogin. Não é o único lugar para explicar mudanças posteriores. `K/ZaplaudApp.kt:196–219`.
- **No ponto do bloqueio:** trocar o texto genérico por dica curta e atalho, por exemplo no aviso de leitura, no compose ou na gaveta de arquivos. Resolve a dúvida na hora, mas não explica botões que simplesmente não aparecem (ex.: chamar contato). Não tornar possível uma ação negada. `K/ReadingScreen.kt:417,529–531,673–675`, `K/FilesSheet.kt:117–120`, `K/CastActions.kt:203–214`.

### 4. O que o login permite distinguir

`UrlPolicy` valida formato, não testa o servidor: aceita origem `http`/`https`, host e porta válidos, sem caminho além de `/`, usuário, query ou fragmento. Por isso, não infere porta local ou remota. HTTP exige confirmação explícita; não é teste de reachability. `K/data/UrlPolicy.kt:5–23`, `K/ZaplaudApp.kt:196–200,245–260`.

| Caso | Sinal disponível e limite |
| --- | --- |
| Porta local sem autenticação | No Habblaud desta revisão, o `POST /api/remote/login` chega a uma rota desconhecida (`404`) na porta local se passar pela guarda. Hoje aparece erro HTTP; `404` também pode significar proxy mal roteado, outro serviço ou servidor incompatível. Se houver resposta confiável do Habblaud com `local=true, remote=false`, ela identifica acesso por loopback local; `remote=false, local=false` sozinho não identifica serviço/versão. Não inferir só por `:4747`, não enviar novamente a senha, não sugerir exposição da porta local. `server/index.ts:549–560,570–591`; `server/http/app.ts:483–486,953`; `README.pt-BR.md:258–262,557–560,598–601`. |
| Certificado não confiável | O transporte valida certificado e nome; a UI atual não separa falha TLS dos demais erros de rede. Uma versão futura só deve dizer “certificado não confiável” quando o transporte confirmar falha de confiança; nome incorreto, certificado vencido e outros erros TLS não são sinônimos. Oriente a conferir nome/validade e CA por canal confiável, sem desativar validação ou recomendar HTTP. `README.pt-BR.md:258–262,504–506,567–572`; `K/ZaplaudApp.kt:220–236`; `android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/data/HttpTransport.ios.kt:27–32`. |
| Servidor inalcançável | Sem resposta HTTP, a classe de transporte pode indicar DNS, conexão recusada ou timeout, mas não se a máquina está desligada, se é firewall, VPN ou endereço errado. Hoje cai no mesmo `network_error` que TLS. `K/ZaplaudApp.kt:220–236`; `android/shared/src/commonMain/composeResources/values/strings.xml:34`. |
| Usuário ou senha incorretos | `401` **no POST de login** vindo do Habblaud significa credenciais recusadas, sem dizer qual campo errou. `401` **no GET de acesso após POST bem-sucedido** significa que o servidor não aceitou a sessão, não prova senha errada. Hoje o mesmo `catch` mostra `login_error` nos dois. `403` pode vir da guarda Host/Origin, `429` tem `Retry-After`, e redirecionamento tem tratamento separado. `server/http/remote.ts:577–610,668–674`; `server/http/guard.ts:118–136`; `K/ZaplaudApp.kt:213–236`; `K/data/HabblaudApi.kt:22–27,58,109–113`. |

### 5. Paridade com o Zaplaud Rust

Sim para o diagnóstico de acesso: mesma origem, mesma rota e mesmas regras de interpretação. Rust já lê os mesmos quatro campos; também descarta o resultado no login e trata `401` genericamente na UI. Para listar arquivos e distinguir campo ausente de `false`, ampliar os dois modelos de diagnóstico sem alterar as regras de autorização existentes. Compartilhar rótulos e casos de contrato; classificação de TLS é por transporte, não uma promessa de que OkHttp, Darwin e reqwest expõem a mesma causa. Fontes: `zaplaud-rs/crates/zaplaud-core/src/model.rs:665–679`, `zaplaud-rs/crates/zaplaud-core/src/api/routes.rs:123–125`, `zaplaud-rs/crates/zaplaud-ui/src/app.rs:277–287`, `zaplaud-rs/crates/zaplaud-ui/src/main.rs:62–81`, `docs/zaplaud-paridade.md:3–16`, `README.pt-BR.md:464`. A próxima implementação deve entregar os dois clientes juntos, ou registrar a pendência e o motivo na tabela de paridade. Este spike não cria divergência de comportamento.

## Opções

| Opção | Esforço | Risco | O que muda para o usuário |
| --- | --- | --- | --- |
| A. Seção de acesso na linha do servidor | S | Baixo; principal risco é apresentar capacidade antiga como atual. | Consulta o que está liberado e o que deve pedir ao operador, sem sair de Configurações. |
| B. Seção + aviso único + ajuda contextual e no login | M | Médio; depende de classificar erros de transporte sem falso diagnóstico e de não repetir avisos. | Vê a limitação ao entrar, consegue voltar à explicação pelo recurso afetado e recebe ajuda mais precisa antes de entrar. |

Os dois esboços mantêm a navegação existente. Exemplo para um servidor que permite apenas aprovações:

```text
A — Configurações › Servidores › casa.exemplo › Acesso neste servidor
  Conversas e recentes — Bloqueado
  Mensagens e anexos — Bloqueado
  Responder aprovações e perguntas — Liberado
  Arquivos — Bloqueado
  Peça ao operador para conferir as permissões indicadas no Habblaud.

B — Após o primeiro login: “Alguns recursos estão limitados neste servidor.”
    [Ver acesso do servidor] [Dispensar]
    Conversa: “Envio bloqueado pelo servidor.” [Ver acesso do servidor]
    Login, quando confirmado: “O servidor não aceitou a sessão.”
    Login, rota ausente: “Não encontrei o login remoto neste endereço.
    Confira a porta remota do Habblaud.”
```

### Textos para `strings.xml`

Em `android/shared/src/commonMain/composeResources/values/strings.xml`, reutilizar `terminal_blocked`, `messages_blocked`, `message_guard_required`, `permissions_blocked`, `files_title`, `network_error` e `rate_limited` onde a mensagem já cabe. Proposta de chaves novas:

| Opção | Chaves e texto |
| --- | --- |
| A — seção e linhas | `access_title`: “Acesso neste servidor”; `access_reading`: “Conversas e recentes”; `access_messaging`: “Mensagens e anexos”; `access_permissions`: “Responder aprovações e perguntas”. Reutilizar `files_title`. |
| A — estados | `access_allowed`: “Liberado”; `access_blocked`: “Bloqueado”; `access_not_reported`: “Não informado pelo servidor”; `access_loading`: “Consultando acesso…”; `access_unavailable`: “Não foi possível consultar o acesso.”; `access_login_required`: “Entre novamente para consultar o acesso.” |
| A — ajuda acionável | `access_allow_hint`: “Peça ao operador para conferir `%1$s` em `HABBLAUD_REMOTE_ALLOW` e se o recurso está ligado.”; `access_files_hint`: “Arquivos exigem `terminal` e um servidor fora do Docker.” Usar `message_guard_required` para a incompatibilidade de envio. |
| B — aviso e atalho | `access_limited`: “Alguns recursos estão limitados neste servidor.”; `access_open`: “Ver acesso do servidor”; usar o botão de dispensar existente se o texto servir. |
| B — login | `login_remote_route_missing`: “Não encontrei o login remoto neste endereço. Confira a porta remota do Habblaud.”; `login_credentials_rejected`: “Usuário ou senha incorretos.”; `login_session_rejected`: “O servidor não aceitou a sessão. Confira a configuração do acesso remoto.”; `login_tls_untrusted`: “O certificado do servidor não é confiável. Confira o endereço e o certificado com o operador.” Só mostrar o último com evidência de falha de confiança. |

## Recomendação

Escolher **A**. É o menor local permanente que explica as limitações sem mudar o login. Mostrar quatro capacidades que o Zaplaud já usa ou oferece (leitura, envio protegido, aprovações e arquivos). Não listar `spawn` e `world` como ações disponíveis do Zaplaud. Também não usar `terminal=true` como prova de que o auxiliar do elenco está disponível.

Consultar ou atualizar o diagnóstico ao expandir a linha, usando a API daquela origem. Separar ausência de campo, `false`, falha de rede e autenticação expirada. Para envio, só mostrar “Liberado” quando ambas as flags forem verdadeiras; com mensagens anunciadas sem proteção, explicar a incompatibilidade. Não persistir o resultado nem usar esse diagnóstico para substituir os bloqueios das ações reais. Se o servidor não informa a causa, usar “confira”, não “falta configurar”.

Se o dono escolher B, manter essa seção como destino comum e separar as etapas do login antes de classificar os erros. Confirmar os sinais dos transportes antes de prometer mensagens específicas. Não fazer sondagem adicional ou alteração no servidor como parte da opção A.

## O que não foi verificado

- Nenhum servidor foi iniciado ou consultado. Não foram examinados ambiente, configuração efetiva, contas, chaves ou segredos de produção. As combinações são derivadas dos gates e dos estados do código, não de medições.
- Não foram reproduzidos DNS, TLS inválido, proxy, porta errada nem cookie recusado nos transportes. O tipo ou código preservado por cada engine e a confiança em CA particular por plataforma ainda precisam de verificação para B.
- Não foram feitos protótipos, testes de interface ou validação com operadores. Não houve execução de Gradle, testes ou aparelhos.
