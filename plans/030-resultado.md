# Execução do plano 030 — 2026-10-27

## Veredito

APPROVE: implementação revisada e critérios do 030 exercitados. Estado canônico dos planos em `plans/README.md`.

- Worktree: `/home/anderson/Desenvolvimento/habblaud-030`.
- Branch: `advisor/030-upload-binario-em-disco`.
- Commit: `155e734722810ecb3bfd6b8144cd0642656d7cae` — `perf(uploads)!: stream attachments to disk`.
- Base: `c19afb0`, candidata do plano 029. A branch inclui essa implementação anterior e substitui seu transporte/depósito de anexos.
- 31 arquivos no commit; código de servidor/cliente/auxiliar, testes, fixture, documentação, traduções e receita reproduzível.
- Nenhum merge, push, publicação, alteração de produção ou de tenant ativo.

## Implementação entregue

- POST de mensagens e Novo agente recebe `application/octet-stream`, corpo File, nome/MIME em cabeçalhos limitados. JSON/base64 antigo nesses uploads é rejeitado; demais APIs continuam JSON.
- Depósito único de 120 MiB contabiliza reservas, pendentes e held, com IDs por consumidor. Cinco anexos por mensagem, oito por spawn e 15 MiB por arquivo preservados.
- Stream/backpressure grava staging no filesystem persistente; índice guarda metadados. Promoção direta usa rename. Não há lote inteiro em Buffer/base64 no caminho de upload.
- Docker entrega descritores ao auxiliar. GET local autoriza somente bytes de um job enviado vivo; o auxiliar real baixa sequencialmente e confirma caminhos finais após promover o lote.
- Host/Origin/login/permissões preservados. Sem novos pacotes, flags de GC, aumento de RAM, redução de limites, projeto RW ou parser JSON incremental.
- TTL de 30 minutos somente para pendentes. Arquivos finais antigos e novos continuam com retenção manual.

## Revisão

Revisões independentes Security030 e Lifecycle030 não encontraram bugs concretos no servidor/shared. Coordenador leu clientes, auxiliar, testes e receita.

Correções antes da aprovação:

1. Cleanup no início do auxiliar remove apenas staging próprio `bridge-<sufixo mkdtemp>` abandonado, antes dos loops concorrentes. Regressões preservam finais, pendentes UUID do servidor e destinos de symlinks; `.pending` symlink é rejeitado.
2. Erro 415 dos uploads passou a informar formato binário, não pedir JSON. Traduções sincronizadas.
3. A receita inicialmente criou o volume em `/tmp` do host, que era tmpfs; essa rodada não integra a evidência válida. Agora exige filesystem persistente e cria o diretório temporário na worktree.
4. Na repetição independente, bridge falhou uma vez porque o cliente começou antes da presença real do auxiliar. A receita agora observa o primeiro HTTP 200 de `/api/host/files/poll?wait=1` feito pelo auxiliar real. Preload de instrumentação não muda corpo, resposta ou resultado de fetch; não há poll sintético nem ACK falso. A repetição final passou.

Adaptação de escopo aprovada: fixture `server/test/attachment-fixture.ts` prepara streams e depósitos temporários compartilhados pelos testes, sem reintroduzir API base64.

## Gates observados pelo coordenador

Executados após as correções de produto. A última correção posterior foi somente da receita de readiness; não mudou fonte de produto.

| Comando | Resultado |
|---|---|
| `npm run typecheck` | passou |
| `npm test -- --maxWorkers=2` | 187 arquivos; 2299 testes passaram, dois skips preexistentes |
| `npm run i18n:check -- --strict` | 3196/3196 EN, ES e FR |
| `npm run build` | passou; aviso de chunks grandes preservado |

Evidência independente: `artifact://218`. O executor também passou regressões focadas: dez arquivos, 213 testes (`artifact://210`). Nenhum teste novo ignorado ou teste externo alterado para obter verde.

## Servidor completo sob 256 MiB

O coordenador repetiu a receita do commit em ambas as topologias:

```sh
python3 plans/030-upload-fullapp-smoke.py --memory 256m --repeats 3 --topology direct
python3 plans/030-upload-fullapp-smoke.py --memory 256m --repeats 3 --topology bridge
```

Node `v24.22.1`, `memory.max=268435456`, `--memory-swap=256m`, rootfs RO, `/tmp` tmpfs 64 MiB sem payload, binds de dados persistentes, rede isolada. Cliente e auxiliar em processos/contêineres separados do servidor. World/timeline desligados conforme o plano. O pico abaixo é do cgroup do servidor, incluindo pagecache; não é a soma da RAM do cliente/auxiliar.

| Topologia | Rodadas fresh | Rodada mista | Maior pico do servidor, incluindo mista | Menor margem até 256 MiB |
|---|---:|---:|---:|---:|
| direct | 3 | 1 | 122,2 MiB | 133,8 MiB |
| bridge real | 3 | 1 | 126,8 MiB | 129,2 MiB |

- Todas as oito rodadas: cinco arquivos de `15728640` bytes, total `78643200`, cinco SHA-256 corretos e mensagem `delivered`.
- Todos os cgroups: `oom=0`, `oom_kill=0`, `OOMKilled=false`, `shmem=0`.
- Mista: oito reservas concorrentes de 15 MiB, repartidas cinco mensagens/três spawn; reserva adicional recebe 429, oito abortos liberam capacidade e novo lote é entregue.
- Staging do bridge é descartado após ACK; `memory.stat.file=0` ao final não representa o pico. `memory.peak` é a métrica acumulada usada na tabela.
- Evidências finais do coordenador: `artifact://214` (direct) e `artifact://224` (bridge com readiness real). A falha de readiness anterior permanece registrada em `artifact://215`, não omitida.

## Browser e OMP reais

Instância própria em 127.0.0.1:4758, estado/sessões/anexos temporários; duas sessões OMP reais, modelo `openai-codex/gpt-5.6-sol`, sem alterar autenticação/configuração global.

- Seletor nativo anexou TXT com nome Unicode e PNG, sem texto. Servidor gravou ambos no mesmo diretório UUID; OMP executou read em ambos e respondeu `UPLOAD030-3a365dde-8c3a-4be3-950d-53ab586b64f2 — azul`.
- Cancelar o seletor preservou `azul.png`; remover e selecionar novamente o mesmo arquivo funcionou no Chromium descartável por CDP.
- Drop/paste foram exercitados por eventos DOM com File/DataTransfer reais, seguindo o handler da UI e o transporte real. Não houve gesto físico de arrastar nem clipboard nativo do sistema nessa prova.
- Upload lento: começou em `1793114227612`; UI mudou de Fábio para Lorenzo em `1793114227624`; envio terminou em `1793114229821`. Lorenzo estava sem anexos. POST manteve o ID original de Fábio; mensagem `m-mvqtwylr-3-DTwUtk41FfVD` foi entregue.
- A sessão original leu o arquivo e respondeu `SWAP030-41edf6ce-f3f0-41d0-8a91-87693003de94`.
- Envio durante turno: snapshot de Fábio indicava `working` antes e depois do POST 201 do arquivo `continuação.txt`. Mensagem `m-mvqty1ms-4-n0IF7pwGcpJ7` foi entregue como continuação; sessão respondeu `FOLLOW030-ece471d4-ab48-4ec2-8a70-99bd790c0bea`.
- Consulta às transcrições confirmou ambos os marcadores nas respostas da sessão original. Sessão B/Lorenzo não continha esses marcadores nem caminhos de anexos.
- Captura final do terminal: `/tmp/omp-sshots-15b6ca1543eb3b6c.webp`; seletor inicial: `/tmp/omp-sshots-15b6c6933ec8640e.webp`.

A primeira instância gerenciada do navegador apresentou falhas de worker/retângulos/espera e foi encerrada pelo harness. Reportado por `xd://report_issue`. A prova restante usou Chromium próprio em CDP loopback; não houve mudança de produto para contornar o navegador.

## Limites e integração

- O 030 resolve o bloqueio de memória do 029. A integração final do 029 com os mounts de `omp-tenant-029` (`0ed95c9`) continua uma revisão separada; este plano não alterou nem publicou aquele repositório.
- Não testamos limites de outras cargas simultâneas, como World/timeline ativos ou cache de entregas cheio. Os números provam os cenários descritos, não toda combinação de carga possível.
- Anexos finais continuam acumulando disco até limpeza manual. O orçamento de 120 MiB é de pendentes/reservas, não quota persistente de finais.
- A mudança HTTP é um cutover conjunto: atualizar servidor, página e auxiliar juntos e recarregar páginas antigas; sem fallback base64. Caminhos finais existentes são preservados.
- Serviços/abas/contêineres e dados próprios de smoke foram encerrados/removidos. Branch e worktree de implementação permanecem para revisão do operador.

## Integração posterior solicitada pelo usuário

- Planos e evidências commitados em `producao`: `4568dd3`, `docs(plans): record upload migration evidence`.
- Candidata `advisor/030-upload-binario-em-disco` mesclada localmente por merge commit `0697614`, `merge: 030 upload binário em disco`. Inclui `c19afb0` e `155e734`.
- `git diff --exit-code 155e734 HEAD -- . ':(exclude)plans/**'` confirmou produto idêntico à candidata aprovada; por isso, os resultados da suíte completa permanecem aplicáveis.
- Após o merge, `npm run typecheck`, `npm run build` e `python3 plans/030-upload-fullapp-smoke.py --memory 256m --repeats 1 --topology direct` passaram. Rodada normal e mista entregaram os cinco arquivos com hashes corretos, sem OOM; maior pico de 128360448 bytes.
- Evidência pós-merge: `artifact://251`. Nenhum push, deploy, restart de produção ou merge do repositório de tenants foi realizado.

## Deploy posterior no ambiente pessoal

- Publicado a pedido do usuário em 2026-10-27, somente na instância pessoal `habblaud.service` do systemd de usuário. Não houve atualização dos tenants.
- Revisão da imagem: `7e164a5bf16b45aa0ba3501c931bae77eeb89292`, `dirty=false`, confirmada em `/app/dist/build.json` dentro do contêiner ativo.
- Imagem construída por `deploy/podman/Containerfile`, com `HABBLAUD_BUILD_COMMIT`/`HABBLAUD_BUILD_DIRTY` e label OCI de revisão. Tag construída `localhost/habblaud:deploy-7e164a5`, promovida para `localhost/habblaud:producao`.
- Imagem ativa nova: `54993768e7c8fc6be5e39cf846c22093c3bcaf28c866f886f3457fb5799dbb15`.
- Imagem anterior preservada em `localhost/habblaud:rollback-c987dd8fdaf6`, digest local `c987dd8fdaf67388220cc8b8f54f8928aeaf8e2196c5e2d65a9141e4e8056b43`.
- Unit, volumes, configuração de acesso remoto e arquivos de autenticação não foram alterados. Configuração ativa é `HABBLAUD_IN_DOCKER=0`: servidor grava diretamente em `~/.habblaud/attachments`; não precisa do auxiliar do host.
- `systemctl --user restart habblaud.service` concluiu; estado `active/running`. `podman healthcheck run habblaud` passou; contêiner `healthy`; `/api/health` retornou `ok=true`.
- Prova na interface publicada em `http://127.0.0.1:4747`: seletor nativo enviou `prova de upload.txt`, sem texto, para uma sessão OMP descartável exclusiva. POST retornou 201, mensagem `m-mvqwe6rd-1-B-5nFTysvUv3` ficou `delivered`.
- Arquivo foi persistido em `~/.habblaud/attachments/4e8912fa-53c7-4208-b2c3-7cb71b23dd70/`; agente executou read e respondeu exatamente `DEPLOY-7e164a5-yd5OCO`. Leitura independente no host confirmou os mesmos bytes. Nenhuma mensagem de teste foi enviada às sessões de trabalho existentes.
- Captura: `/tmp/omp-sshots-15b6d932a985721e.webp`. Nenhum erro de página foi observado na consulta efetuada.
- Sessão, bucket de transcrição, arquivo final de teste, diretório temporário e Chromium próprios foram encerrados/removidos após a prova. Os agentes de trabalho do usuário não foram reiniciados.
- Atualizar abas antigas com reload: o novo servidor rejeita o formato JSON/base64 antigo de anexos. Nenhum push foi feito.

Rollback disponível, não executado:

```sh
podman tag localhost/habblaud:rollback-c987dd8fdaf6 localhost/habblaud:producao
systemctl --user restart habblaud.service
```

## Atualização posterior dos cinco tenants

- Aplicada a pedido do usuário em 2026-10-27: anderson, camilez, mira, tadeuz e teuz.
- Mounts da candidata `0ed95c9` mesclados em `omp-tenant/main` por `20dac54`; 75 testes Python passaram. O plano não relacionado `omp-tenant/plans/033-redesenhar-painel-multitenant.md` permaneceu fora do merge/commit.
- Revisão `7e164a5bf16b45aa0ba3501c931bae77eeb89292` importada por fetch local no repo usado pelo gerenciador (`~/apps/habblaud`), sem mudar sua working tree. `omp-tenant release` preparou imagem e extensão pinadas.
- A tag `localhost/habblaud:tenant-7e164a5bf16b` recebeu a mesma imagem já validada no ambiente pessoal, digest `54993768e7c8fc6be5e39cf846c22093c3bcaf28c866f886f3457fb5799dbb15`, preservando o build stamp correto. `sync` a carregou no Podman do usuário `omp-tenants`.
- Antes do restart, as seis salas estavam idle. `sync` reiniciou os cinco contêineres OMP e os cinco Habblaud; pods infra, binário do OMP, gateway e proxy de cota não foram atualizados.
- Todos os Habblaud ficaram healthy; `/api/health` retornou ok e `/app/dist/build.json` confirmou a revisão correta em cada um. O limite de 268435456 bytes por Habblaud foi mantido.
- Cada tenant usa `/habblaud/<nome>/anexos` como backing fora do HOME; o mesmo destino absoluto `home/.habblaud/attachments` é RW no Habblaud e RO no OMP. Inspeção dos mounts confirmou Source idêntico e RW verdadeiro/falso nos cinco.
- Prova real em todos: cliente HTTP dentro do Habblaud iniciou upload binário parcial com marcador único; o contêiner OMP leu os bytes exatos da staging. Tentativa de escrever retornou erro de filesystem somente leitura (EROFS). Estado privado do Habblaud e HOME de outro tenant ficaram invisíveis.
- Após a prova, o cliente abortou cada upload e confirmou a remoção da staging. Nenhum prompt foi enviado às sessões dos usuários e nenhum anexo final de teste foi criado.
- Os conteúdos de 15 arquivos (`tenant.json`, `remote.env`, `token` por tenant) permaneceram idênticos ao baseline privado de hashes: senhas, tokens, modelos, cotas, número de salas e montagens individuais preservados.
- Anderson e Teuz retomaram os três IDs de sessão anteriores. Camilez, Mira e Tadeuz ganharam IDs novos: seus IDs ativos anteriores não tinham transcript materializado, nem por filename nem por ID do header. As novas sessões também ainda não tinham arquivo materializado. Mira mantém cinco transcrições históricas de outras sessões; não foi forçada retomada de uma história diferente.
- Rota pública de Anderson verificada por HTTPS: redirecionou para `/login`, com proteção de acesso presente. Não fiz login nas contas nem alterei senhas para testar a UI privada; a prova completa de UI/OMP usa o mesmo artefato, validado no ambiente pessoal.
- Backup anterior: `~/.config/omp-tenants/rollback-upload.TXollj/`, contendo release anterior `6fd1c5be49cd`, quadlets, clients.json e rotas. Imagem/release antigas preservadas no host e no Podman dos tenants. Rollback não foi executado.
- Rollback operacional exige restaurar release e quadlets desse backup, daemon-reload do usuário `omp-tenants` e reinício das units afetadas. Não apagar `/habblaud/<nome>/anexos`: conterá uploads reais após esta publicação.
- Nenhum push foi feito; ambiente pessoal não foi reiniciado nesta etapa.
