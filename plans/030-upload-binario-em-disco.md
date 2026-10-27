# Plan 030: Receber anexos por stream em disco sem reter o lote em RAM

> Executor: implemente em worktree isolada baseada em `c19afb0`, não no clone original nem em produção. Leia o plano inteiro antes de editar. Não publique, faça merge, reinicie serviços reais ou altere tenants ativos. O coordenador mantém o índice quando houver execução supervisionada.
>
> Drift inicial: `git diff --stat c19afb0..HEAD -- server/spawn server/messages server/deliveries/fetcher.ts server/http/app.ts server/http/guard.ts server/index.ts shared/types.ts client/src/net/store.ts client/src/ui/spawn.ts scripts/codex-bridge.ts`. Compare também alterações locais e todos os caminhos do Escopo antes de editar. Se diferirem dos trechos abaixo, reconcilie o plano antes de continuar. Consulte referências de símbolos exportados antes de mudar assinaturas.

## Status e dependências

- **Priority**: P1.
- **Effort**: L.
- **Risk**: MED: mudança conjunta de transporte, armazenamento temporário e auxiliar do host.
- **Category**: perf.
- **Planned at**: candidata Habblaud `c19afb0`, 2026-10-27; clone original `6fd1c5b`.
- **Depends on**: código candidato do 029 em `../habblaud-029`, branch `advisor/029-anexos-no-prompt`, commit `c19afb0`. Não depende do status DONE do 029: este plano é justamente o desbloqueio do aceite de memória.
- **Integração de isolamento**: candidata `../omp-tenant-029`, branch `advisor/029-anexos-isolados`, commit `0ed95c9`. Não alterar esse repositório neste plano; reutilizar seus mounts na prova final do 029.
- **Substitui**: a arquitetura JSON/base64 e o depósito em RAM do 029. Preserva UI, controles, limites, retenção dos arquivos finais e mounts por tenant; não executar novamente a implementação antiga de upload.

Ordem: candidata 029 já existente → executar 030 → repetir aceite completo do 029 com as duas candidatas → revisão e decisão do operador sobre publicação. Concluir 030 não autoriza marcar 029 DONE sem essa revisão.

## Por que mudar

A candidata recebe JSON/base64, cria representações completas do conteúdo e guarda Buffers até o envio. Cinco arquivos de 15 MiB causaram OOM no servidor completo sob o teto existente de 256 MiB dos tenants. Otimizar apenas cópias de `readJson` não resolveu; a tentativa foi descartada.

O laboratório comparou as arquiteturas com cliente separado e hashes conferidos. Binário para disco com índice de metadados teve mediana de pico cgroup de 172,8 MiB, contra 356,2 MiB do pipeline original. A repetição independente sob 256 MiB completou cinco arquivos com hashes corretos e pico de 164,1 MiB. São protótipos isolados, não prova do app completo. Evidências e receitas: `plans/029-upload-lab-results.md`, `029-upload-lab.py`, `.server.mjs`, `.client.mjs` e JSON de resultados.

Decisão: remover base64 de todo o caminho de anexos, gravar por stream no volume persistente e guardar somente metadados. Não aumentar RAM, reduzir limites, forçar GC, adicionar multipart, criar parser JSON incremental ou introduzir dependência.

## Estado atual confirmado

Todos os caminhos abaixo são relativos à raiz da candidata Habblaud, não ao clone original:

- `server/spawn/attachments.ts:22-25`:
  ```ts
  export interface Attachment {
    name: string;
    type: string;
    data: Buffer;
  }
  ```
  `parseAttachment()` faz `Buffer.from(r.data, 'base64')`; `AttachmentStore.items` e `held` mantêm o lote em RAM. `safeFileName()` normaliza NFC, remove separadores/controles e limita o nome a 120 caracteres. Reutilizar esse saneamento.
- `server/spawn/attachments.ts:12-19`: 15 MiB por arquivo, oito no Novo agente, 30 minutos de TTL para pendentes, orçamento de 120 MiB. `shared/messages.ts:9-10`: cinco arquivos por mensagem. Preservar esses limites, não confundir TTL de pendentes com anexos finais.
- `server/http/guard.ts:138-140`:
  ```ts
  const path = (req.url ?? '/').split('?')[0];
  if (path.startsWith('/api/') && !isJsonContentType(req.headers['content-type'])) {
    reject(res, 415, tr('Envie o corpo como JSON (Content-Type: application/json).'));
  ```
  Antes disso, o guard valida Host e Origin. Não afrouxar essas validações.
- `server/messages/http.ts:47-49`: `registry.uploadAttachment(await readJson(req, ATTACHMENT_BODY_MAX))`. `server/http/app.ts:567-579` faz o mesmo para `/api/spawn/attachments`.
- `client/src/net/store.ts:341-354` e `client/src/ui/spawn.ts:358-367` usam `FileReader`/`fileToBase64` e `JSON.stringify` do conteúdo. Ambos precisam migrar.
- `server/index.ts:287-308` cria dois `AttachmentStore` independentes, um para spawn e outro para mensagens. Isso permite até 240 MiB de arquivos no mesmo processo. Substituir por um depósito compartilhado, mantendo permissões e limites por consumidor.
- `server/messages/registry.ts:315-375`: valida disponibilidade, captura sessão, toma IDs e prepara anexos antes de entregar. Flag interna `preparing` impede entrega parcial; controles/asides passam enquanto textos comuns mantêm ordem. Preservar isso.
- `server/deliveries/fetcher.ts:123-133`, `server/spawn/spawn.ts:244-254` e `scripts/codex-bridge.ts:308-326` serializam/decodificam anexos base64 no salto Docker–host. Corrigir só o navegador não basta.
- `docker-compose.yml:27-28` oferece volume persistente em `/data`; `/tmp` é tmpfs de 64 MiB. O gerador do tenant também usa `/tmp` de 64 MiB. Nunca guardar o lote em `/tmp`.
- Raiz final existente: `attachmentsRoot()` usa `HABBLAUD_ATTACHMENTS_DIR` ou `HOME/.habblaud/attachments`. O tenant monta raiz própria RW no Habblaud e RO no omp. Arquivos finais antigos devem continuar legíveis pelo mesmo caminho.
- Convenções: Node streams/fs, `HttpError`, `sendJson`, `tr()` e Vitest. Modelar testes de isolamento em `server/spawn/attachments.test.ts` com `tempDir()` de `server/test/fixtures`; HTTP real em `server/messages/http.test.ts`. Não testar texto-fonte ou eco de mocks.

## Escopo

Arquivos de produto que podem mudar, com respectivos testes existentes:

- `server/spawn/attachments.ts`, `attachments.test.ts`; `server/spawn/spawn.ts`, `spawn.test.ts`.
- `server/messages/registry.ts`, `registry.test.ts`; `server/messages/http.ts`, `http.test.ts`.
- `server/deliveries/fetcher.ts`, `fetcher.test.ts`.
- `server/http/app.ts`, `guard.ts`; `server/http/http.test.ts`, `remote.test.ts` e `terminal.test.ts` somente para regressões das travas e da nova rota local do auxiliar.
- `server/index.ts`.
- `shared/types.ts`: somente DTOs de anexos/jobs de gravação e spawn; não alterar protocolo World, SSE, contas ou agentes.
- `client/src/net/store.ts`, `client/src/ui/spawn.ts`, `spawn.test.ts`; `client/src/ui/composer-model.ts` e `.test.ts` somente se necessário preservar reuso/expiração de IDs.
- `scripts/codex-bridge.ts`, `server/test/codex-bridge.test.ts`.
- `server/test/attachment-fixture.ts` (criar): fixture compartilhada de requisições stream e depósito em diretório temporário. Adaptação aprovada na execução para migrar os testes de consumidores sem restaurar a API base64 nem duplicar preparo.
- `shared/locales/{en,es,fr}.ts`, `CHANGELOG.md`, `README.md`, `README.pt-BR.md`, `README.es.md`, `README.fr.md`, `server/README.md`.
- `plans/030-upload-fullapp-smoke.py` (criar): receita reproduzível de validação do app completo, não biblioteca de produto. `plans/README.md` e relatório de execução do 030 apenas para o responsável pelo índice.

Não mudar: limites de RAM/CPU/mounts, `docker-compose.yml`, `deploy/podman/*`, `../omp-tenant*`, modelos de agente, controles, listagem de projetos/arquivos, API de entregas, ditado e áudio. Base64 de áudio/imagens de outras funcionalidades não faz parte deste cutover. Não refatorar o leitor JSON genérico.

## Contrato de destino

### HTTP navegador–servidor

Manter endpoints e resposta pública útil, mudando somente o formato do upload:

| Item | Contrato |
|---|---|
| Rotas | `POST /api/messages/attachments` e `POST /api/spawn/attachments` |
| Content-Type | `application/octet-stream`, corpo = bytes do File |
| Nome | cabeçalho `X-Habblaud-File-Name: encodeURIComponent(file.name)`; máximo 2048 caracteres codificados; decodificar uma vez, exigir nome não vazio e aplicar `safeFileName` |
| MIME original | `X-Habblaud-File-Type`, máximo 256 caracteres; validar com a regra existente, fallback `application/octet-stream` |
| Sucesso | 201 `{id, name, size}`; ID opaco gerado no servidor, sem caminho local na resposta |
| Erros | 400 metadados/vazio, 413 arquivo acima de 15 MiB, 429 orçamento de pendentes ocupado, 415 formato de corpo não suportado; preservar 403 das travas existentes e 5xx de falha interna |

Não aceitar base64/JSON como fallback nesses dois uploads. POST de mensagem e pedido de spawn continuam JSON, referenciando IDs como hoje. Cliente não escolhe raiz, path, ID ou job do host.

A exceção de tipo no guard deve ser uma correspondência exata de método, pathname e media type para essas duas rotas. Octet-stream não é CORS-safelisted; manter rejeição de Origin estranho, Host inválido, acesso remoto sem permissão e não liberar CORS/preflight cross-origin. Outras rotas seguem exigindo JSON. OPTIONS sozinho nunca grava nem reserva espaço. Não adicionar token ou mecanismo de autenticação redundante.

### Depósito em disco e orçamento

- Um único depósito para os dois consumidores; índice contém ID, nome, tipo, tamanho, horário, estado e caminho interno. Não conter conteúdo em Buffer/base64.
- Direto/tenant: staging em `<attachmentsRoot>/.pending/<UUID>/`, no mesmo filesystem do destino final. Docker padrão: staging do servidor em `<config.dataDir>/attachments-pending/`, no volume `/data`; destino do host permanece sob `attachmentsRoot()` calculado no host.
- Diretórios novos 0700, arquivo exclusivo 0600. Não alterar permissões de raiz preexistente. IDs e diretórios não podem atravessar symlinks controlados por entrada externa; não seguir symlink no arquivo de staging e nunca usar filename do navegador como diretório.
- Reserva antes de receber o corpo: usar Content-Length válido até 15 MiB quando presente; para corpo chunked sem tamanho reservar 15 MiB. Somatório de reservas em voo, pendentes e held nunca ultrapassa 120 MiB. Validar bytes reais e tamanho informado; não confiar só no cabeçalho. Ajustar reserva ao tamanho real ao concluir. Suportar oito uploads de 15 MiB dentro do orçamento; o próximo recebe 429, sem uma fila ilimitada de uploads.
- Stream com backpressure escreve blocos limitados. Proibidos `readFile`, `arrayBuffer`, `Buffer.concat` ou string completa do conteúdo nesse caminho. Pode haver Buffers pequenos de stream; a exigência não é RAM zero.
- `take(ids, limit)` valida todos os IDs antes de mudar qualquer estado. Repetidos, desconhecidos, expirados ou de consumidor errado não devem consumir lote válido. Marcar held atomicamente e impedir dupla utilização. O depósito compartilhado deve guardar o consumidor do ID, para spawn não usar ID de mensagem ou vice-versa.
- Promoção direta usa `rename` no mesmo filesystem para pasta final UUID por envio. Resolver nomes repetidos como hoje. Fechar gravações antes de promover. Não reler os arquivos para construir prompt.
- Ciclo: recebendo → pendente → held → finalizado ou descartado. Ao abortar/falhar, fechar handles, remover apenas parciais próprios e devolver reserva exatamente uma vez. Retenção de arquivo final nunca depende do TTL do depósito.
- Expirar pendentes após 30 minutos e limpar parciais órfãos do próprio depósito após restart, sem tocar diretórios finais ou arquivos externos. IDs em memória anteriores ao restart deixam de ser válidos, como já acontece; cliente conserva File e pode reenviar. A raiz de staging pressupõe uma única instância escritora por instalação; não criar locking distribuído ou suporte multi-instância neste plano.
- Só aceitar/enfileirar mensagem após validação e captura da sessão; só entregar depois da promoção/ack do host. Preservar as correções de permissões e STOP/aside da candidata `c19afb0`.

### Salto servidor Docker–auxiliar

Manter a fila existente e seu timeout; substituir conteúdo base64 por descritores `{id, name, type, size}` dentro do job. Servidor mantém o conjunto autorizado de arquivos held daquele job.

Adicionar `GET /api/codex/bridge/files/:jobId/attachments/:fileId` para o auxiliar buscar bytes. A rota deve resolver somente IDs de um job de gravação ainda vivo, nunca caminho fornecido pelo cliente. Aplicar a mesma proteção local/autorização do poll/ack do auxiliar; não incluir essa leitura na allowlist remota de arquivos do projeto. Job expirado/desconhecido ou arquivo fora do job não é servido. Resposta com Content-Length verificado e stream do arquivo, sem buffer completo.

Auxiliar baixa sequencialmente por stream para staging privado na própria raiz final, valida tamanho real/limites/nomes e promove por rename local. ACK retorna apenas caminhos absolutos finais calculados pelo host. Não aceitar raiz enviada pelo servidor como autoridade. Mensagens e spawn usam o mesmo mecanismo de descritores/stream; não deixar um segundo caminho base64 no spawn.

Na falha/timeout, cancelar streams ativos e aguardar fechamento antes de devolver reservas/apagar temporários. ACK tardio não entrega mensagem nem altera sessão. Nenhum retry automático novo é necessário. Não apagar arquivos finais publicados para compensar um ACK perdido; preservar a política manual de anexos finais e reportar falha de entrega.

## Comandos e gates

Executar na worktree de implementação. Usar Node suportado pelo `package.json`, npm com lockfile e imagem local `docker.io/library/node:24-bookworm-slim` para a prova isolada. Se faltar imagem ou dependência, registrar o pré-requisito; não buscar credenciais ou usar produção.

| Finalidade | Comando | Resultado esperado |
|---|---|---|
| Dependências na worktree nova, se faltarem | `npm ci` | exit 0, lockfile sem alteração |
| Tipos | `npm run typecheck` | exit 0 |
| Regressões focadas | `npx vitest run server/spawn/attachments.test.ts server/spawn/spawn.test.ts server/messages/registry.test.ts server/messages/http.test.ts server/deliveries/fetcher.test.ts server/http/http.test.ts server/http/remote.test.ts client/src/ui/spawn.test.ts client/src/ui/composer-model.test.ts server/test/codex-bridge.test.ts` | todos passam |
| Suíte completa | `npm test -- --maxWorkers=2` | todos passam; nenhuma exclusão adicional |
| Traduções | `npm run i18n:check -- --strict` | nenhuma chave faltante/excedente |
| Build | `npm run build` | exit 0 |
| Servidor completo sob limite | `python3 plans/030-upload-fullapp-smoke.py --memory 256m --repeats 3 --topology direct` | 3 rodadas com bytes corretos, entrega e sem OOM |
| Salto host real | `python3 plans/030-upload-fullapp-smoke.py --memory 256m --repeats 3 --topology bridge` | idem; tráfego de bytes por stream e respostas do host reais |

Os dois últimos comandos definem a interface obrigatória da receita a criar no passo 5; não são comandos já existentes. Receitas do laboratório são referência de medição, não substituem esses gates. O limite de dois workers vem da execução anterior: houve timeout de socket em `world/uplink.test.ts` sob concorrência padrão, sem alteração nesse código. Não suprimir ou reescrever esse teste.

## Passos de implementação

Não executar gates/build/formatters entre edições parciais. Completar um contrato coerente, depois fazer uma rodada focada e os gates finais; repetir somente após corrigir achados relevantes.

### 1. Trocar o depósito por staging em disco

Modificar `server/spawn/attachments.ts` e injeção em `server/index.ts`; migrar consumidores `SpawnService` e `MessageRegistry` para metadados e referências de arquivo. Preservar limites por consumidor, `attachmentsExpired`, reuso de ID na recusa antes da aceitação e orçamento único. Remover APIs mortas de base64/Buffer somente depois de migrar todos os chamadores; não criar alias/shim.

Implementar rollback da promoção parcialmente concluída sem apagar anexos antigos: antes de liberar quota, remover os arquivos novos pertencentes à tentativa falha, inclusive os movidos para sua pasta final ainda não publicada. Não apagar arquivos depois de publicar seus caminhos.

Verificação após integração: comando de regressões focadas, cobrindo limites, consumo atômico, duplicatas, abort, falha de disco, held e TTL. Esperado: bytes e arquivos finais corretos; reservas retornam a zero nos términos; nenhuma entrega antes da promoção.

### 2. Migrar HTTP e clientes juntos

Adaptar guard e handlers exatos, sem alterar `readJson` genérico. Extrair um helper pequeno de upload em `client/src/net/store.ts`, reaproveitado pelo Novo agente e pelo método do Store usado no compositor. `fetch` recebe `body: file`; não chamar FileReader para anexos. Remover `fileToBase64` desses dois fluxos e imports mortos, sem mexer no ditado.

Preservar miniaturas/Object URLs, selecionar novamente o mesmo arquivo, rascunho por agente e captura do lote durante await. Novos arquivos adicionados durante envio não são consumidos pelo lote anterior. Não modificar o desenho da UI.

Verificação após integração: regressões HTTP reais e do cliente, typecheck; upload binário retorna 201, conteúdo acima do limite retorna 413 observável e zero parcial. Rejeitar JSON legado, Host/Origin estranhos e acesso remoto proibido sem criar arquivo.

### 3. Migrar fila e auxiliar sem base64

Alterar `FileFetcher`, jobs de spawn, DTOs em `shared/types.ts`, roteamento local em `server/http/app.ts` e `scripts/codex-bridge.ts`. Proteger a nova leitura por job antes de abrir arquivo. Reaproveitar o fluxo existente de polling/ack e resolução da raiz do host. Mudanças nos DTOs são cutover privado conjunto desses participantes; não remover campos de outros protocolos do arquivo compartilhado.

Verificação após integração: testes do fetcher, registry, spawn e bridge; leitura de outro job/arquivo expirado retorna falha e não vaza bytes; helper grava arquivos de tamanho/hash correto e devolve caminhos finais. Regressões de sessão capturada e STOP/aside continuam passando.

### 4. Provar integridade e segurança por comportamento

Estender testes nos arquivos em escopo. Para streams, usar HTTP real ou Readables pequenos/fragmentados determinísticos; nenhum assert em texto-fonte ou estrutura incidental.

Cobrir: arquivo de 1 byte, 15 MiB exatos e 15 MiB + 1; vazio; nome Unicode e separadores; Content-Length ausente/inválido/incompatível; metadados malformados; cliente interrompido; falha/ENOSPC; slots concorrentes de mensagens/spawn; rejeição antes de aceitação preserva IDs; duplicata de IDs não consome; expiração só pendentes; restart preserva arquivos finais; batch parcial não publica caminho; origem existente 0750 preservada; novo lote 0700/arquivos 0600.

Teste integrado de acesso: octet-stream só nas duas rotas, formulário/text/plain rejeitados, demais APIs continuam JSON, Origin/Host/porta remota continuam bloqueando, helper não lê path arbitrário nem arquivo de outro job. Para oversize, assert status 413 recebido pelo cliente, não apenas que o stream lançou erro: `pipeline(req, ...)` pode destruir o socket prematuramente.

Verificação: regressões focadas, depois suíte completa sem casos novos ignorados. Os testes devem falhar contra o comportamento antigo para a regressão que pretendem proteger, não apenas verificar encaminhamento.

### 5. Criar e executar prova do servidor completo

Criar `plans/030-upload-fullapp-smoke.py` com stdlib Python e Podman. Referência: a prova anterior executou `node /app/dist/server/index.js --demo` em contêiner, imagem Node 24, sem rede externa, rootfs RO, UID/GID de keep-id, `/tmp` tmpfs 64 MiB, HOME RO e estado/anexos em binds persistentes RW. Cliente separado compartilha somente namespace de rede; não incluir payload do cliente na RAM do servidor. Não publicar porta no host.

A receita deve encontrar a worktree atual via seu próprio caminho, não hardcode `habblaud-029` nem depender de `local://`/histórico. Esperar readiness por endpoint, gerar nomes únicos de pods/containers/diretórios, remover tudo no finally. Usar dados e SHA-256 determinísticos, sem ler dados pessoais.

Topologia direct: `HABBLAUD_IN_DOCKER=0`, `HABBLAUD_DEMO=1`, World/timeline desligados, anexos em bind RW igual ao tenant. Buscar um agente demo `canMessage` no snapshot, enviar cinco arquivos de 15 MiB por HTTP binário, enviar mensagem somente com IDs, aguardar estado de entrega, conferir os cinco arquivos e hashes no destino. O processo deve ser o servidor completo construído por `npm run build`, nunca o servidor mínimo do laboratório.

Topologia bridge: servidor completo com `HABBLAUD_IN_DOCKER=1`, staging no volume `/data`, sem acesso RW ao destino final do host. Executar o auxiliar real da worktree em ambiente descartável e raiz própria; cliente envia a mesma mensagem. Provar poll, GET de cada conteúdo, gravação host e ACK antes de entregar. Não usar mock/echo de ACK como prova. Nenhum Claude/Codex real precisa ser aberto para a mensagem demo; o job de gravação é o caminho real sob teste.

Registrar versão Node, `memory.max`, `memory.peak`, `memory.events`, `memory.stat` anon/file/shmem e estado OOMKilled/ExitCode, mais duração e hashes. Os 256 MiB incluem pagecache; não confundir RSS baixo com uso total baixo. `--memory=256m --memory-swap=256m`, sem `global.gc`, sem cache drop e sem mexer no host. A imagem do auxiliar deve usar o caminho real necessário para executar o código compilado ou `tsx` já instalado, por bind RO; não instalar dependências no contêiner de produção.

Depois das três rodadas fresh por topologia, executar carga mista no mesmo servidor: oito reservas de 15 MiB repartidas entre mensagens/spawn (cinco/três), requisição adicional recebe 429, cancelamento/expiração devolve capacidade, e novo lote de cinco é aceito/entregue. Verificar que nenhuma espera mantém Buffers do lote em RAM. Não iniciar agente real só para saturar o depósito; o cenário misto valida admissão/limpeza, não o lançamento de um agente.

Smoke browser separado: instância local descartável com sessão OMP real, selecionar TXT/imagem e enviar sem texto. Agente lê ambos e confirma marcador/cor. Exercitar seletor cancelado, paste/drop, troca de agente durante upload e envio durante turno ocupado. Capturar evidência de que os arquivos ficam na sessão original. Encerrar aba, processos e dados descartáveis próprios.

Verificação: os dois comandos `030-upload-fullapp-smoke.py` saem 0 com três rodadas cada, cinco hashes corretos, estado entregue e sem evento OOM/OOMKill; carga mista retorna códigos esperados; browser/OMP confirmam leitura. Reportar pico e margem reais, sem prometer reproduzir os 172,8 MiB do laboratório.

### 6. Documentar cutover e revisar

Atualizar docs/CHANGELOG e traduções: corpo binário, cabeçalhos, retorno, limites, localização do staging e exceção CSRF estrita. Remover exemplos de base64 exclusivamente dessas rotas e DTOs do auxiliar, não de áudio ou entregas.

Registrar comandos/resultados, commits, worktree, arquivos alterados e desvios no resultado do 030. Não tocar configuração de produção. Coordenador revisa diff, testes e evidência completa antes de alterar os estados 030/029.

Verificação: typecheck, suíte, traduções strict, build e provas completas. Referências de contratos antigos no upload devem estar removidas; nenhum caminho antigo deve permanecer como fallback.

## Cutover, dados e rollback

- Leitores/escritores envolvidos: navegador das duas telas, dois handlers HTTP, depósito comum, fila de mensagens, fila spawn, poll/GET/ACK e auxiliar do host. Integrar todos na mesma candidata; não misturar versões na publicação.
- Arquivos finais antigos continuam intactos e no mesmo caminho. Não há migração em massa nem varredura/poda dos anexos finais.
- Publicação futura exige parar aceitação de uploads durante reinício, atualizar servidor/cliente/auxiliar juntos e pedir reload das páginas antigas. JSON de cliente antigo recebe 415; não manter compatibilidade silenciosa. Pending IDs podem expirar no restart, como no contrato atual, e os arquivos locais selecionados permitem novo envio.
- Rollback futuro: parar os participantes, restaurar o conjunto anterior de artefatos, reiniciar junto e recarregar clientes. Limpar apenas staging próprio não referenciado pelo processo parado; jamais excluir anexos finais. Para rollback anterior ao 029, manter upload do prompt fora de publicação, já que a candidata com OOM não é uma alternativa segura para tenants.
- Execução deste plano não faz publicação nem rollback real. Se existir integração externa documentada que dependa do JSON antigo e não permita cutover conjunto, STOP: registrar o consumidor e revisar explicitamente a migração antes de quebrá-lo.

## Critérios de aceite

- [ ] Ambos os uploads recebem binário; nenhum FileReader/base64/Buffer de arquivo completo persiste nesses fluxos, incluindo auxiliar e spawn.
- [ ] Depósito único respeita 120 MiB considerando reservas, pendentes e held; cinco/oito e 15 MiB preservados.
- [ ] Staging fica em disco persistente, não tmpfs; promoção direta é rename; nenhum caminho inexistente chega ao agente.
- [ ] Falha, aborto, quota, TTL, restart e duplicatas não perdem reserva nem apagam arquivos finais/externos.
- [ ] Host/Origin/login/feature guards permanecem efetivos; rota de bytes do auxiliar só serve arquivo autorizado daquele job.
- [ ] Sessão capturada, STOP/aside, reuso de IDs e rascunhos mantidos.
- [ ] Todos os gates da tabela passam, sem nova dependência ou exclusão de testes.
- [ ] App completo passa três rodadas direct e três bridge sob 256 MiB, sem swap adicional/OOM, com entrega e hashes corretos; carga mista também passa.
- [ ] Browser e OMP reais confirmam leitura dos arquivos e preservação de sessão durante await/ocupação.
- [ ] Docs e resultado registrados; diff permanece no escopo; nenhuma publicação ou alteração de tenants reais.

## STOP conditions

- Candidata `c19afb0` ou contratos citados não estão disponíveis/foram alterados de modo incompatível.
- Só é possível passar aumentando 256 MiB, reduzindo limites, usando tmpfs para staging, expondo projetos RW, enfraquecendo Origin/Host ou forçando GC.
- A solução exige parser JSON/base64 próprio, serviço externo, dependência nova ou refactor fora do escopo.
- Gate relevante continua falhando após duas rodadas de correções: registrar causa e bloqueio; não entregar otimização parcial como DONE.
- Não é possível executar servidor/auxiliar descartáveis sem acessar produção, credenciais ou tenants reais. Concluir trabalho alcançável e registrar exatamente o pré-requisito; não substituir por prova do protótipo.
- É necessário alterar protocolos de terceiros/consumidores externos não cobertos pelo cutover conjunto.

## Manutenção

A mudança limita retenção de memória, não o crescimento de disco dos anexos finais. Limpeza final permanece manual; não criar quota persistente de arquivos finais. TTL/budget valem somente pendentes e reservas. Pagecache ainda aparece no cgroup e precisa permanecer no relatório. Qualquer adição futura de consumidor deve usar o mesmo depósito, não criar um novo orçamento de 120 MiB por serviço.

Referência operacional do auxiliar real para o smoke: `npm run codex:bridge -- --port <porta-descartavel>`, com HOME e `HABBLAUD_ATTACHMENTS_DIR` descartáveis no processo do auxiliar. `scripts/codex-bridge.ts:70-83` também aceita `HABBLAUD_PORT`. Não usar `--once` na prova: o processo precisa continuar buscando jobs até o ACK do lote completo. O contêiner do auxiliar compartilha somente a rede do servidor e seus próprios mounts de teste; não a raiz final do usuário real.
