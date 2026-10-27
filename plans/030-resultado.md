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
