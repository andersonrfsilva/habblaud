# Plan 029: Enviar arquivos pelo prompt com armazenamento centralizado

> Retomada após o 030: implementação e provas de memória concluídas em `../habblaud-030`, commit `155e734`, branch `advisor/030-upload-binario-em-disco`. A candidata dos mounts está em `../omp-tenant-029`, commit `0ed95c9`. Não reimplementar os passos históricos abaixo nem restaurar JSON/base64 ou depósito em RAM: essa arquitetura foi substituída pelo plano 030.
>
> Resta o aceite integrado da candidata atual com os mounts por tenant, sem publicar ou alterar tenants ativos. Consulte `030-resultado.md` para provas já exercitadas e `plans/README.md` para estado canônico. Drift da retomada: `git diff --stat 155e734..HEAD` na candidata Habblaud e `git diff --stat 0ed95c9..HEAD` na candidata de tenants; compare também alterações locais antes de agir.

## Status

- Priority: P2
- Effort: L
- Risk: MED
- Depends on: 030 concluído; integração de isolamento `0ed95c9` faz parte do aceite restante.
- Category: direction
- Planned at: `6fd1c5b`, 2026-10-27.

## Decisão e resultado esperado

Adicionar botão de anexo ao prompt do terminal e à gaveta, além de colar e arrastar arquivos. Reaproveitar o mecanismo da PR upstream https://github.com/marmottajr/habblaud/pull/124, sem importar suas funcionalidades de comandos ou menções.

Guardar em `~/.habblaud/attachments/<UUID>/` por envio, com nomes saneados e caminhos absolutos na mensagem. Manter `HABBLAUD_ATTACHMENTS_DIR`, já existente, para instalações que precisam de outro caminho. Nunca escrever no projeto. Nunca apagar automaticamente anexos persistidos: referências de conversas antigas devem continuar válidas. Limpeza continua manual, sem criar tela ou agendamento.

Cada instalação/tenant usa sua própria raiz. Não compartilhar anexos entre usuários. Agentes isolados devem receber somente a raiz de anexos em montagem somente leitura, nunca todo o estado do Habblaud. O servidor continua com escrita. Preservar permissões 0700/0600 quando os processos usam o mesmo UID; não tornar arquivos públicos para contornar falhas de montagem.

## Estado atual confirmado

- `server/spawn/attachments.ts:109-110`:
  ```ts
  return env.HABBLAUD_ATTACHMENTS_DIR?.trim() || join(env.HOME || homedir(), '.habblaud', 'attachments');
  ```
- `writeAttachments(root, spawnId, files)` já recebe raiz, saneia nomes, deduplica nomes no lote e cria arquivos com `flag: 'wx'`. Hoje a pasta usa data em segundos e `spawnId.slice(0, 8)` (linha 121): inadequado para IDs de mensagem com prefixo temporal. Usar UUID próprio para novos lotes; manter arquivos antigos legíveis e não migrar nem apagar pastas existentes.
- `AttachmentStore` limita memória em 120 MiB, expira pendentes após 30 minutos e contabiliza `held` até `release()`. `take()` limita atualmente a oito arquivos do Novo agente. Reutilizar com limite explícito de cinco para mensagens sem mudar o limite oito do Novo agente. Rejeitar IDs repetidos sem consumir nada.
- `server/index.ts:287-292` cria o depósito atual somente com `spawnOn`; anexos de mensagem precisam funcionar com mensagens habilitadas, sem exigir `HABBLAUD_PTY=1`.
- `server/messages/http.ts:44` chama `registry.send(await readJson(req))`; adicionar rota específica antes do matcher `/api/messages/:id`.
- `server/messages/registry.ts:306-322` valida disponibilidade e limite antes de criar entrada. `entry()` fixa `target = agent.sessionId`; preservar essa proteção ao preparar anexos de forma assíncrona.
- `registry.ts:417` entrega entradas `queued` pelo inbox. Enquanto grava, entrada não pode ser retirada pelo inbox nem pelo Codex. Preservar `control`, `aside`, cancelamento e confirmações existentes.
- `client/src/ui/composer.ts:248-275` guarda agente e texto antes de aguardar rede; limpa rascunho somente se não mudou. Aplicar o mesmo princípio aos arquivos e não deslocar anexos quando trocar agente.
- `server/deliveries/fetcher.ts` tem fila de arquivos para o auxiliar, presença e timeout; reaproveitar para escrita como na PR, sem trazer listagem de projetos.
- `deploy/podman/habblaud.container:32` monta `~/.habblaud` RW; linha 38 mantém `~/Desenvolvimento` RO. Não mudar essa proteção.
- `../omp-tenant/bin/omp-tenant:148` monta HOME RW no omp; linhas 195-198 montam HOME RO no Habblaud e estado separado RW. Portanto o servidor do tenant NÃO consegue gravar no destino padrão sem uma montagem específica.
- O gerador `pod_units()` usa o mesmo caminho absoluto `h` nos dois contêineres. `linhas_montagens()` preserva sufixo `:ro`. Não alterar montagens dos projetos nem documentos compartilhados.
- Padrões: `server/spawn/attachments.test.ts` usa Vitest e `tempDir()` de `server/test/fixtures`; interface usa `h`, `setHidden`, `setText`, `tr()`. Textos novos exigem traduções EN/ES/FR.

## Escopo

Habblaud:
- `server/spawn/attachments.ts` e `.test.ts`.
- `server/messages/{http,registry}.ts` e respectivos testes.
- `server/deliveries/fetcher.ts` e `.test.ts`.
- `server/index.ts`, `server/http/app.ts` e testes HTTP existentes quando necessário para registrar/proteger a rota.
- `scripts/codex-bridge.ts`, `server/test/codex-bridge.test.ts`.
- `shared/messages.ts`, `shared/types.ts`, testes correspondentes.
- `client/src/net/store.ts`; `client/src/ui/composer.ts`, `composer-model.ts` e teste do modelo; `styles.css`, `icons.ts` se necessário.
- `shared/locales/{en,es,fr}.ts`, `CHANGELOG.md`, READMEs raiz nos quatro idiomas, `server/README.md`, `plans/README.md`.
- `deploy/podman/habblaud.container` apenas se necessário documentar caminho/mount; não ampliar acesso ao projeto.

Integração obrigatória no clone `../omp-tenant`:
- `bin/omp-tenant`, `tests/test_quadlets.py`, `tests/test_home.py`, `README.md`; `tests/test_release.py` somente para adaptar a fixture de `sync` ao preparo seguro da raiz de anexos, sem executar sudo real no teste.
- Permitir ajustes mínimos no criador/sincronizador de homes dentro do mesmo executável para preparar raiz de anexos antes de gerar os pods.

Fora do escopo: comandos de barra, menu `@`, chat paralelo com anexos, novo gerenciador de arquivos, limpeza automática, antivírus, novo mecanismo de autenticação, dependências novas, acesso global ao host, alteração de configurações ou dados de tenants reais.

## Referência upstream

A PR #124 está aberta e com conflitos. Os únicos commits de anexos são `be5b68d492316989c03fdd92b95df2c5b89ed823` (servidor/auxiliar) e `49ce1c16bffbd801aef962f8262cd1c9356bccc3` (cliente). Os dez anteriores são das PRs #121–123. Inspecionar esses patches como referência, não fazer merge bruto: nosso registro possui OMP, controles e perguntas paralelas ausentes no código upstream.

## Comandos

Executar a partir de `habblaud`, salvo indicação. Comandos extraídos de `package.json` e `CONTRIBUTING.md`; não rodados durante a redação deste plano.

| Finalidade | Comando | Sucesso |
|---|---|---|
| Tipos | `npm run typecheck` | exit 0 |
| Regressões de anexos | `npx vitest run server/spawn/attachments.test.ts server/messages/registry.test.ts server/messages/http.test.ts server/deliveries/fetcher.test.ts client/src/ui/composer-model.test.ts server/test/codex-bridge.test.ts` | todos passam |
| Suíte | `npm test` | todos passam |
| Traduções | `npm run i18n:check -- --strict` | nenhuma chave faltante/excedente |
| Build | `npm run build` | exit 0 |
| Tenants | `python3 -m unittest discover -s tests` em `../omp-tenant` | exit 0 |
| Smoke web | `HABBLAUD_PORT=4757 npm run dev` com HOME, estado e raiz de anexos temporários preparados pelo executor | servidor isolado, sem usar porta/estado de produção |

## Passos

### 1. Preparar armazenamento e depósito

Reutilizar `parseAttachment`, `safeFileName`, `writeAttachments` e `promptWithAttachments`. Diretório exclusivo por UUID completo; criação deve falhar em colisão sem reutilizar diretório existente. Sem rotina de poda de disco. Preservar oito anexos no Novo agente; cinco por mensagem. Limite individual de 15 MiB, incluindo validação server-side. Rejeitar lote com IDs duplicados, desconhecidos ou vencidos de modo atômico. Não consumir uploads numa recusa anterior à aceitação da mensagem.

Liberar memória `held` em todos os términos: sucesso, erro, timeout e descarte. Remover apenas arquivos parciais criados pela própria tentativa falha, nunca anexos antigos.

Verificação: testes de armazenamento e registro do comando de regressões, depois do passo 3. Incluir dois lotes no mesmo instante com nomes idênticos: diretórios distintos, bytes intactos.

### 2. Integrar rota, preparação e auxiliar

Criar `POST /api/messages/attachments` com JSON base64 e limite de corpo existente para anexos. Mesma trava `messages`, Origin/CSRF e métodos das mensagens; não expor escrita pela porta pública sem autorização. A rota não aceita raiz nem caminho de destino do cliente.

`POST /api/messages` aceita IDs opcionais, permite só anexos sem texto e continua recusando mensagem totalmente vazia. Depósito de mensagens separado do Novo agente, sem exigir spawn/pty. Validar e capturar agente/sessão antes de aceitar; preparar arquivos antes da entrega. Manter estado público compatível com polling atual; flag interna de preparação basta se não for necessário um novo status.

No Node/Podman direto, gravar em `attachmentsRoot()`. No Docker padrão, portar operação de escrita do auxiliar com validação de quantidade, bytes, base64 e nomes no host. Host determina sua própria raiz configurada; nunca obedecer caminho arbitrário vindo do contêiner. Devolver caminhos absolutos visíveis à sessão. Timeout/erro deve virar falha legível e nunca entregar texto que alegue anexos inexistentes.

Não liberar mensagem em sessão nova após `/new`, troca/saída do agente ou confirmação tardia. Não aplicar regras Claude a OMP. Preservar controles e asides.

Verificação: regressões do comando acima. HTTP real em instância temporária: upload, envio somente anexo, polling e inbox; bytes no disco iguais aos enviados. Porta sem permissão retorna 403; auxiliar indisponível falha com motivo e não entrega mensagem parcial.

### 3. Adicionar UI compartilhada

Adicionar botão compacto de clipe coerente com microfone/enviar, com título e `aria-label` traduzidos. Usar `<input type="file" multiple>` sem restringir tipo. Resetar valor após seleção para permitir selecionar novamente o mesmo arquivo. Usar um só caminho de inclusão para seletor, paste e drop.

Mostrar miniatura ou extensão, tamanho e remover. Até cinco arquivos, 15 MiB cada, com aviso específico para rejeitados. Texto colado continua normal, inclusive clipboard contendo texto e imagem: dar precedência ao texto nesse caso. Não interceptar drop de texto como se fosse arquivo.

Guardar anexos por agente. Capturar lote ao enviar; impedir duplo envio e não limpar novos anexos adicionados durante a rede. Botão enviar habilitado para anexos sem texto. Cancelar seletor não altera rascunho. Revogar Object URLs ao remover, enviar ou destruir o componente.

Reutilizar IDs já carregados após recusa da mensagem e após falha intermediária; não reenviar bytes válidos a cada tentativa. Quando o servidor informar expiração/perda após reinício, invalidar IDs afetados e permitir novo envio a partir dos File ainda guardados, sem loop automático. Limpeza de pendentes não deve apagar anexos persistidos.

Verificação: regressões do modelo e typecheck. Browser real, desktop e largura móvel: selecionar imagem e TXT, remover um, mandar sem texto, colar imagem, arrastar arquivo, cancelar seletor, repetir mesmo arquivo e trocar agente durante upload. Evidência visual do botão/lista e prova de que os bytes chegam à sessão correta.

### 4. Integrar isolamento por tenant

Em `../omp-tenant/bin/omp-tenant`, preparar uma raiz física por tenant em `t/anexos`, fora do HOME gravável pelo usuário. Montá-la em `h/.habblaud/attachments` nos dois contêineres. Não usar `h/.habblaud/attachments` como origem de bind no host: o usuário pode trocar componentes por symlinks. Preservar a proteção comprovada por `tests/test_quadlets.py:32-41`. Garantir UID/modos compatíveis com `keep-id` e preparar o destino sem seguir symlinks do usuário no host.

Gerar bind mount específico dessa pasta RW no Habblaud, sobre HOME RO. Gerar mount dessa mesma pasta RO no omp, sobre HOME RW. Usar exatamente o mesmo caminho absoluto nos dois. Não montar `habblaud-dados` no omp, nem compartilhar a raiz do dono ou de outro tenant. Não mudar mounts de livros/docs/guias/projetos.

A instalação do dono já permite escrita pelo mount `~/.habblaud:rw`. Se o agente do dono usar isolamento adicional, verificar como esse launcher monta caminhos: precisa enxergar somente anexos no mesmo caminho e RO. Não inventar configuração bwrap sem localizar o launcher real; registrar como bloqueador de publicação se esse launcher não estiver acessível.

Verificação: suíte Python. Em pods descartáveis, gravar arquivo pelo Habblaud, ler no omp e comparar hash; escrita pelo omp na raiz de anexos falha com filesystem RO. Leitura de `habblaud-dados` e anexos de outro tenant falha. Reiniciar pods descartáveis preserva anexo e caminho. Não tocar tenants ativos para esse teste.

### 5. Provar ponta a ponta e documentar

Usar instância isolada com estado temporário e sessão OMP real descartável. Pelo seletor no browser, enviar uma imagem e TXT com marcador aleatório. Agente deve ler TXT e imagem do caminho recebido. Repetir envio durante turno ocupado e alternar agente: cada mensagem fica na sessão original. Observar gravação, polling e conteúdo lido, não apenas testes com mocks.

Rodar uma vez os gates completos depois da integração. Atualizar CHANGELOG e documentação de API/comportamento: limites, raiz por instalação, mounts, sem expiração em disco e limpeza manual. Apagar scripts/arquivos de smoke criados pela execução. Não apagar anexos reais ou arquivos do usuário.

Verificação: quatro gates npm, suíte Python, relatório de smoke Node e pods descartáveis, captura visual sem dados pessoais.

## Testes permanentes necessários

- Writer: nomes com espaços/acentos/traversal saneados; duplicatas no lote; lotes concorrentes sem colisão/sobrescrita; permissões; falha de disco não entrega mensagem.
- Store/registry: cinco/seis arquivos, bytes no limite e acima; texto vazio com/sem anexo; IDs repetidos/desconhecidos/vencidos; recusa não consome IDs; falha libera memória; preparação não aparece em inbox/Codex; sessão trocada não recebe lote anterior.
- HTTP: limites e autorização da rota; endpoint auxiliar inacessível remotamente; cliente não escolhe raiz.
- Modelo UI: limites e remoção, seleção por agente, envio de anexos sem texto, preservação de rascunho em falha. Não criar testes de texto-fonte, wiring ou cópia de mocks.
- Tenants: testar propriedades de isolamento/comportamento com fixtures existentes; geração de texto de mount sozinha não substitui prova RO/RW nos pods descartáveis.

## Critérios de aceite

- [ ] Seletor, paste e drop funcionam no terminal e gaveta com acessibilidade e layout móvel.
- [ ] Cada envio usa diretório exclusivo, fora do projeto; agente lê bytes corretos por caminho absoluto.
- [ ] Falha/reenvio não duplica uploads válidos nem consome permanentemente memória do depósito.
- [ ] Nenhuma entrega antes de gravar; nenhuma entrega na sessão errada.
- [ ] Cinco arquivos por mensagem e 15 MiB por arquivo validados no servidor/host.
- [ ] Anexos permanecem após restart e não são excluídos por idade.
- [ ] Tenant Habblaud escreve; omp lê mas não escreve; outros tenants e segredos ficam inacessíveis.
- [ ] Gates completos e smoke real passam; evidências anexadas ao resultado.
- [ ] Docs e linha do índice atualizadas, sem publicação/alteração de produção.

## STOP conditions

- Drift altera identidade de sessão, fila, autenticação ou mounts de forma incompatível: atualizar este plano antes de improvisar.
- UID/caminhos diferem entre servidor e agente: resolver mapeamento explicitamente; não abrir permissões globais.
- Isolamento necessário depende de launcher externo inacessível: concluir partes alcançáveis e registrar bloqueio de publicação, sem afirmar aceite completo.
- Para concluir é necessário montar projeto RW, expor estado/segredo ao agente, apagar anexos antigos ou importar PRs #121–123: rejeitar essa abordagem.
- Não é possível executar prova em pods descartáveis sem atingir produção: não usar tenants reais; reportar pré-requisito exato.
- Antes de publicar, executar o servidor completo com `--memory=256m --memory-swap=256m`, cinco uploads sequenciais de 15 MiB e o envio da mensagem. Exigir cinco arquivos íntegros e `OOMKilled=false`; Node escritor/leitor sozinho não satisfaz esse gate. A execução de 2026-10-27 encontrou OOM variável entre upload e persistência: consultar `029-resultado.md` e replanejar consumo do pipeline antes de retomar. Não resolver aumentando memória ou reduzindo quantidade/tamanho.

## Manutenção

Revisar consumo de disco quando houver uso real: esta decisão preserva referências, não fornece quota persistente. O teto de 120 MiB protege apenas uploads pendentes em memória. O volume do tenant Habblaud tem limite de 256 MiB; smoke deve incluir um lote grande para observar pressão, sem aumentar teto ou introduzir streaming sem evidência. Compartilhar somente anexos dentro de uma mesma instalação/tenant é intencional; não representa isolamento entre agentes do mesmo usuário.
