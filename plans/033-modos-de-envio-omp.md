# Plano 033: Escolher quando uma mensagem entra no trabalho do agente OMP

> Executor: leia o plano inteiro. Implemente somente este recurso no clone de desenvolvimento ou em worktree isolada. Não publique, não reinicie serviços e não reabra sessões reais sem autorização. Atualize somente a linha 033 em `plans/README.md` ao concluir.
>
> Drift check: execute `git diff --stat a6b6664..HEAD -- shared/messages.ts shared/types.ts server/messages client/src/net/store.ts client/src/ui/composer.ts client/src/ui/composer-model.ts mod/habblaud-omp/habblaud.ts server/test/omp-extension.test.ts`. Compare os trechos abaixo com os arquivos atuais. Mudanças locais também precisam ser preservadas; o SHA não captura alterações sem commit.

## Status e motivação

- Prioridade: P2. Esforço: M. Risco: médio. Categoria: direção. Dependências: nenhuma pendente.
- Planejado em `a6b6664`, 2026-11-01.
- Fonte única do andamento: linha 033 de `plans/README.md`.

Hoje toda mensagem enviada ao OMP ocupado espera o turno terminar. O usuário quer poder acrescentar informação durante o trabalho ou mudar a direção sem precisar esperar sua conclusão. A decisão é oferecer três modos no compositor existente, com `aside` como padrão para novos envios do OMP. Não criar outro chat nem substituir o botão de parar.

## Contrato de produto decidido

| Rótulo | Valor no protocolo | Com agente ocupado | Com agente livre |
|---|---|---|---|
| Acrescentar agora | `aside` | Entrega no próximo limite de etapa, sem interromper o lote atual de ferramentas | Inicia um turno |
| Enfileirar | `followUp` | Aguarda o turno terminar | Inicia um turno |
| Mudar direção | `steer` | Direciona no próximo ponto possível; pode pular ferramentas ainda não executadas do lote | Inicia um turno |

- Mostrar seletor nas duas variantes de `MessageComposer`: gaveta e terminal de leitura ao vivo. Não alterar o terminal PTY nem capturar suas teclas.
- Seletor visível para destinatário `provider === 'omp'` e compositor disponível. Claude e Codex mantêm o envio existente, sem seletor.
- Padrão inicial por agente: `aside`. Guardar a escolha apenas em memória, por agente, como os rascunhos. Recarregar a página restaura o padrão. Não criar configuração, armazenamento persistente ou sincronização entre instâncias de compositor.
- Enter e botão Enviar usam o modo selecionado. Shift+Enter e Alt+Enter continuam quebrando linha. Não adicionar atalhos.
- Capturar agente, provedor e modo no início de `send()`, antes do primeiro `await` de upload. Trocar de agente ou mexer no seletor durante o upload não altera o pedido em andamento.
- Aviso de `steer`: pode pular ferramentas pendentes; não desfaz alterações e não garante cancelamento de uma operação já iniciada.
- `aside` aqui é mensagem no histórico principal. NÃO é `InboxMessage.aside`, `AsideRequest`, `/api/asides` nem o bate-papo efêmero `runEphemeralTurn`. Não misturar esses fluxos.
- `aside` não interrompe o lote atual, exceto esperas interruptíveis `wait`/`vibe_wait` conforme o runtime OMP. Não prometer entrega instantânea: há também polling da extensão, atualmente 5 s quando ocupada, e preparo de anexos.
- `delivered` continua significando aceitação pelo runtime, não leitura pelo modelo nem execução concluída. Os textos precisam refletir essa distinção para cada modo.

## Estado atual confirmado

Stack: TypeScript ESM, UI DOM sem framework, servidor Node, extensão executada pelo Bun/OMP. Usar os componentes DOM e `tr()` existentes; não adicionar dependências.

- `client/src/ui/composer.ts:343-378`: `send()` captura agente e texto, faz upload e chama `this.ctx.store.sendMessage(agentId, messageText(raw), ids)`. Há rascunhos e estados por agente; erros preservam o texto.
- `client/src/net/store.ts:366-377`: `sendMessage(agentId, text, attachments?)` envia JSON a `POST /api/messages`.
- `server/messages/http.ts:45`: rota delega a `registry.send(await readJson(req))`; `InvalidRequest` vira HTTP 400.
- `server/messages/registry.ts:183-193`: `parseSend` valida texto/anexos. `send():320-348` resolve o provedor, prepara anexos e cria `Entry`. `inbox():446-478` serializa explicitamente os campos; acrescentar tipo sem atualizar essa serialização perderia o modo.
- `shared/types.ts:838-847,887-894`: `OutboxMessage` e `InboxMessage` não possuem modo.
- `mod/habblaud-omp/habblaud.ts:185-187`: interface local `OmpApi.sendUserMessage` só declara `deliverAs?: 'followUp'`.
- A extensão possui OUTRA interface `InboxMessage` em `:250-257`, independente de `shared/types.ts`; `parseInbox():269` constrói os campos explicitamente. Ambos precisam ser atualizados.
- Entrega atual da extensão (`:717-720`):

```ts
if (idle) {
  pi.sendUserMessage(m.text);
  idle = false;
} else pi.sendUserMessage(m.text, { deliverAs: 'followUp' });
```

- `server/test/omp-extension.test.ts:485-492` cobre o comportamento antigo com agente ocupado. Os helpers `setup`, `makeCtx`, `fakeHabblaud` e o mock `sent` são o padrão da suíte.
- `client/src/ui/composer-model.ts:49-53,94-111`: dicas e confirmação de entrega assumem sempre continuação no OMP.
- `server/messages/codex.ts:1-10,62-65`: Codex usa `codex queue`; fora do novo recurso.
- Referência do runtime consultada: `omp://sdk.md`, seção Prompt lifecycle. `sendUserMessage` sem `deliverAs` assume steer durante streaming; `aside` entra no limite de etapa; `followUp` espera terminar. A extensão existente registra uma particularidade de OMP livre: `followUp` explícito pode ficar esperando outro pedido. Preservar chamada sem opções para o primeiro envio livre, independentemente do modo escolhido.

## Arquivos em escopo

Alterar somente conforme necessário:

- `shared/messages.ts`, `shared/types.ts`: valores e tipos de modo.
- `server/messages/registry.ts`, `server/messages/http.ts`: validação, transporte e comentário do contrato.
- `client/src/net/store.ts`, `client/src/ui/composer.ts`, `client/src/ui/composer-model.ts`: seletor, captura do modo, JSON e status.
- `mod/habblaud-omp/habblaud.ts`: contrato local, parser e entrega.
- `server/messages/registry.test.ts`, `server/messages/http.test.ts`, `server/test/omp-extension.test.ts`, `client/src/ui/composer-model.test.ts`: testes de comportamento relevante.
- `shared/locales/en.ts`, `shared/locales/es.ts`, `shared/locales/fr.ts`: traduções das novas mensagens, seguindo os catálogos existentes.
- Estilo existente responsável por `.ui-msg` somente se necessário para encaixe responsivo; localizar por busca antes de editar. Não criar tema ou folha nova.
- `README.md` e `server/README.md`: atualizar somente a documentação existente de mensagens/API; localizar a seção antes de editar.
- `plans/README.md`: status. Artefatos temporários ficam fora do repositório e são removidos após prova.

Fora de escopo: lógica de Claude/Codex, PTY, polling/latência, novas filas, retries, cancelamento de mensagens já aceitas, pausa/retomada, capabilities/versionamento automático, batimento/presença, `/api/asides`, instalações, tenants, deploy, configuração global e alterações anteriores de abas do terminal.

## Passos de implementação

### 1. Definir e transportar o modo

Usar campo `deliverAs?: 'aside' | 'followUp' | 'steer'` no JSON, sem inventar outra nomenclatura para o mesmo conceito. Centralizar a lista/tipo puro em `shared/messages.ts` e reutilizar onde a extensão puder importar apenas tipos, mantendo sua carga independente do DOM/Node do servidor.

`parseSend` valida qualquer valor fornecido; null, arrays, objetos e strings desconhecidas devem gerar HTTP 400. Resolver o provedor no registro: somente OMP aceita modo explícito; para Claude/Codex, retornar 400 antes de consumir anexos ou criar Entry. Omissão conserva a semântica antiga: OMP ocupado usa `followUp`. O novo compositor OMP sempre manda modo explícito, inclusive o padrão `aside`. Isso preserva clientes anteriores sem criar aliases ou endpoints novos.

Guardar o modo OMP normalizado na mensagem/Entry e expô-lo em `OutboxMessage` para o status retornar o modo realmente aceito. Propagar para `InboxMessage` apenas em mensagens textuais OMP. Não anexar o campo a controles, perguntas paralelas ou mensagens dos outros provedores. Preparação de anexos não pode apagar o campo nem deixar a mensagem ultrapassar os bloqueios existentes.

**Verificação:** `npm test -- server/messages/registry.test.ts server/messages/http.test.ts` deve passar com testes de valores válidos/inválidos, omissão, provedor incompatível e isolamento por sessão. Não alterar limites nem estados da fila.

### 2. Entregar pela extensão sem interromper o caso livre

Expandir `OmpApi`, `InboxMessage` local e `parseInbox`. Valor ausente mantém `followUp`; valor desconhecido deve falhar com ack por mensagem, sem ser interpretado como `steer`, sem lançar para o processo OMP e sem bloquear mensagens válidas seguintes. Manter a validação de sessão entre mensagens e as prioridades atuais de `control` e da pergunta paralela.

Em `deliver()`, manter `idleNow()` e a decisão local de que o primeiro envio iniciou um turno. Livre: `pi.sendUserMessage(m.text)` sem opções. Ocupado ou mensagens seguintes do mesmo lote: `pi.sendUserMessage(m.text, { deliverAs: modoValidado })`. Nunca chamar `abort()` para implementar `steer` ou `aside`. Não alterar `stopTurn()`.

Atualizar os comentários que afirmam que tudo vira continuação. Não remover comentários de outras funcionalidades.

**Verificação:** `npm test -- server/test/omp-extension.test.ts` deve passar. Cobrir escolhas explícitas, omissão, modo inválido, lote misto, exceção na entrega e troca de sessão. Testes do contrato com mock provam decisão da extensão; não provam a semântica do runtime, que exige a etapa 5.

### 3. Integrar seletor e estados nas duas caixas

Reutilizar `h`, `setHidden`, `setAttr`, `tr()` e padrão de selects do projeto, localizando um exemplo antes de criar o controle. Usar select nativo acessível com rótulo visível ou nome acessível inequívoco. Não implementar menu customizado.

Adicionar mapa em memória por agente para a escolha. Capturar modo/provedor junto com `agentId` no início de `send()`, antes do upload; passar o modo ao `OfficeStore.sendMessage`. Para outros provedores, omitir o campo. Atualizar fluxo mock só o necessário para manter contrato honesto; demonstração não prova interrupção real.

Dicas exibem efeito e limite do modo selecionado. `SendState` conserva o modo de cada envio, sem ler o seletor atual para descrever uma mensagem anterior. Usar `OutboxMessage.deliverAs` aceito pelo servidor quando disponível. Durante envio e polling, trocar agente ou modo não muda a confirmação anterior. Se houver erro, preservar rascunho/anexos e escolha conforme o comportamento atual. Não desabilitar envio só porque o agente está trabalhando.

**Verificação:** `npm run typecheck` e `npm test -- client/src/ui/composer-model.test.ts` devem passar. Testar decisões e limites observáveis, não textos literais: remover testes existentes que somente fixem wording em vez de repiná-los. Usar smoke de navegador para DOM e envio, não adicionar teste permanente de simples encaminhamento.

### 4. Atualizar documentação e traduções

Documentar os três modos na seção existente de mensagens, o novo campo na API, o comportamento quando omitido e a limitação a OMP. Explicar que `aside` de mensagem principal difere do chat paralelo. Traduzir cada novo `tr()` nos três catálogos, sem traduzir código/protocolo. Não criar documentação paralela.

**Verificação:** `npm run i18n:check -- --strict` deve sair 0. `npm run typecheck` deve sair 0.

### 5. Provar no navegador e em OMP descartável

Rodar a aplicação em porta e diretório de dados isolados; descobrir opções reais pelo bootstrap/configuração do projeto. Não reutilizar produção. Usar somente arquivos temporários e sessão OMP descartável, sem segredos nem alteração de trabalho do usuário.

Prova de navegador com backend real: nas caixas da gaveta e do terminal, verificar seletor, teclado, envio, estados e layout estreito. Verificar que Claude/Codex não mostram seletor. Exercitar troca de agente e de modo durante upload controladamente atrasado: pedido conserva destinatário e modo originais. Registrar evidência visual e corpo/resposta HTTP sem conteúdo privado.

Prova do runtime: confirmar versão e disponibilidade de `aside`/`steer` na instalação que executará a extensão. Em sessão descartável, manter uma tarefa com várias etapas em andamento e enviar um marcador distinto por modo. Observar transcript/eventos, não apenas ack:

- `aside`: marcador entra antes de acabar o trabalho, no limite de etapa, sem cortar o lote comum em andamento.
- `followUp`: marcador só é tratado após conclusão do turno atual.
- `steer`: marcador muda o próximo passo possível; demonstrar o tratamento de ferramentas pendentes sem prometer interromper comandos iniciados.
- Livre: cada modo inicia resposta sem exigir outro Enter.
- Um lote com mais de uma mensagem preserva texto e modo de cada item; não duplicar mensagens nem iniciar chamadas concorrentes indevidas.

Se modelo não produzir lote determinístico, usar harness descartável com o runtime real e ferramenta controlada para registrar os marcos. Mock de `sendUserMessage` não substitui essa prova. Registrar comandos, versão e sequência observada na entrega do executor.

**Gates finais:** `npm run typecheck`, `npm test`, `npm run i18n:check -- --strict` e `npm run build`, todos com saída 0. Executar gates finais uma vez após integração; falhas precisam ser atribuídas sem apagar comportamento ou reduzir aceite.

## Critérios de aceite

- [ ] Seletor com os três modos nas duas caixas OMP; padrão `aside`, escolha em memória por agente.
- [ ] Agente livre inicia turno em qualquer modo; ocupado respeita os três modos no runtime real.
- [ ] Modo imutável por envio, inclusive upload, polling e troca de agente.
- [ ] API valida modo antes de consumir anexos; omissão preserva `followUp`; provedores incompatíveis recusam campo explícito.
- [ ] Sem mudança no chat paralelo, controles, PTY, Claude/Codex, isolamento de sessão, limites e anexos.
- [ ] Status informa aceitação, não promete leitura ou execução; dicas explicam riscos de steer e limites de latência.
- [ ] Gates finais passam e há evidência visual e de runtime dos cenários acima.
- [ ] Documentação/traduções atualizadas, artefatos temporários removidos e índice atualizado.

## Condições para parar e reportar

- Runtime instalado não suporta `aside`/`steer`, ou a semântica real contradiz o contrato. Não substituir por abort nem degradar silenciosamente para fila.
- Exigir mudança em produção, reiniciar auxiliar ou sessões existentes para obter prova. Primeiro concluir gates locais; reportar pré-requisito de publicação separadamente.
- Exigir mudanças de transporte Claude/Codex, capabilities ou nova arquitetura de filas. Não ampliar o plano.
- Mudanças concorrentes contradizem os trechos ou decisões deste plano. Reconciliar com o operador antes de sobrescrever.

## Publicação e manutenção

Publicação fica com o operador após revisão. Servidor/cliente e extensão OMP precisam ser distribuídos juntos; sessões com extensão antiga podem ignorar o campo e continuar enfileirando. Informar que reinstalação/atualização da extensão e reabertura controlada das sessões são necessárias; não encerrar personagens automaticamente. Testar no alvo antes de anunciar disponibilidade.

O novo padrão é intencional apenas no compositor OMP atualizado. Mudanças futuras na API do runtime devem preservar a distinção entre entrega no histórico principal, direcionamento, continuação e chat efêmero. Não esconder comportamento incompatível com uma tradução ou ack positivo.

## Resultado da execução em 2026-11-01

Candidata preservada no commit `484ac82`, branch `advisor/033-modos-de-envio-omp`, worktree `../habblaud-033`. Não houve merge, push, publicação ou reinício de produção. O status canônico continua no índice.

O executor implementou o contrato, seletor, propagação, testes, traduções e documentação. O revisor leu o diff e observou o seletor `aside` padrão na gaveta usando navegador e backend isolado. O smoke do executor cobriu também terminal, tela estreita, teclado e captura imutável durante upload; essa última evidência registra envio `aside` ao agente original após troca de destinatário e seleção para `steer`.

### Bloqueio de aceite do runtime

Com OMP 18.12.6 real, extensão Habblaud candidata e provedor HTTP local determinístico, o executor observou:

- Livre: os três modos iniciaram turno sem outro Enter.
- Ocupado: `aside` entrou após o lote de ferramentas e antes da resposta; `followUp` entrou após a resposta; `steer` entrou antes da resposta.
- Em lote de duas ferramentas exclusivas, o ack de `steer` chegou durante `call_1`. `call_2` ainda estava pendente e executou após `call_1`; só depois o runtime consumiu o marcador de direcionamento. O revisor conferiu os eventos e o inbox.

Não foi demonstrado o pulo de ferramentas pendentes exigido no passo 5. Isso impede aprovação do plano como escrito. Uma execução não prova que o runtime nunca pula ferramentas; prova que não podemos anunciar essa garantia com o aceite atual. Não substituir por `abort()` nem publicar a candidata como concluída.

Reprodução temporária: `/tmp/omp033-runtime/RESULTADOS.txt`, `reproduce.py`, `server.cjs`, `inbox.cjs`, `hold.ts`; comando `python3 /tmp/omp033-runtime/reproduce.py steer busy`. Usa HOME/diretório de agente isolados, `--no-session`, endpoint local 18765 e inbox local 18766, sem credenciais. Logs revisados: `extension-busy-steer-exclusive.jsonl` e `inbox-steer.jsonl`. O provedor devolve respostas determinísticas; não é prova de inferência de um modelo externo.

Evidências temporárias de UI: `/tmp/habblaud-033-upload-evidence.json`, `/tmp/habblaud-033-drawer.png`, `/tmp/habblaud-033-terminal.png`, `/tmp/habblaud-033-narrow.png`. Arquivos em `/tmp` não são duráveis; podem desaparecer após reinício. O servidor temporário `habblaud-033-isolated` foi encerrado na revisão.

O executor registrou typecheck, build e i18n estrito aprovados. A suíte completa não passou: 191 arquivos passaram, `server/world/uplink.test.ts` falhou por timeouts `esperando: reconectou` e `esperando: tema`, fora dos arquivos alterados. Não atribuir causa à infraestrutura ou chamar a suíte de verde sem investigação.

Na candidata final `484ac82`, o revisor repetiu `npm run typecheck`, os quatro arquivos de teste focados (137 testes aprovados), `npm run i18n:check -- --strict` (3278/3278 por idioma) e `npm run build`: todos saíram 0. Não repetiu a suíte completa para apenas confirmar os timeouts já registrados.

### Como desbloquear

Escolher explicitamente um destes caminhos antes de retomar:

1. Investigar o runtime OMP separadamente e demonstrar o comportamento de `steer` exigido pelo plano; então repetir a prova e os gates.
2. Revisar o contrato de produto com autorização do usuário, explicando que `steer` não demonstrou diferença de corte do lote neste runtime. Não reduzir o aceite silenciosamente.

### Publicação autorizada para teste

Depois do relatório do bloqueio, o usuário pediu explicitamente: “faça merge e deploy no standalone assim mesmo e vamos testar”. Isso autoriza publicar a candidata com o limite conhecido; não equivale a comprovar o aceite de `steer`.

O commit `484ac82` foi integrado por fast-forward em `producao` no clone de desenvolvimento e em `~/apps/habblaud`. A imagem standalone foi reconstruída pelo `deploy/podman/Containerfile` e somente `habblaud.service` foi reiniciado. Imagem publicada: `18d5257e53fcb7bf017013b84d175726773acfa5cd3300f29f132f1b64a21ab1`. `/api/health` retornou `ok: true` e o healthcheck do Podman passou. O navegador confirmou o seletor com padrão `aside` no terminal publicado.

O auxiliar permaneceu com PID `187334` e início em 2026-10-31 19:52:44 -03. As sessões tmux `5`, `hb-didiz`, `hb-ina`, `hb-mira` e `hb-tadeuz` conservaram IDs e horários de criação. Não houve deploy em tenants nem push remoto. A mudança local anterior de `termtabs.ts` não entrou nesta publicação.

O loader instalado já aponta para `~/apps/habblaud/mod/habblaud-omp/habblaud.ts`, agora atualizado. Sessões OMP já abertas não foram recarregadas/reiniciadas; podem continuar usando a extensão antiga e enfileirando todos os modos até reabrir/carregar a extensão nova. O seletor visível, sozinho, não prova atualização da extensão em memória.

Rollback da imagem anterior preservado na tag local `localhost/habblaud:rollback-033`. Para reverter o serviço web, retaggear essa imagem como `localhost/habblaud:producao` e reiniciar somente `habblaud.service`; não reiniciar o auxiliar. Se sessões novas já carregaram a extensão atualizada, o rollback web sozinho não reverte a extensão no host.
