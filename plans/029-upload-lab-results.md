# Laboratório quantitativo de upload — plano 029

Data: 2026-10-27. Somente experimentos; nenhum código de produto foi alterado.

## Resultado e limite da conclusão

Cinco arquivos de 15 MiB (75 MiB, 78.643.200 bytes) chegaram e foram persistidos com SHA-256 conferido em todas as execuções. Foram 24 execuções, 120 arquivos. O cliente gerou bytes aleatórios criptográficos independentes; não houve compressão de transporte. Cliente e servidor rodaram em contêineres/cgroups diferentes. A memória do cliente não integra os números do servidor.

C (stream binário para disco, metadados em memória) apresentou o menor pico e removeu a retenção proporcional ao lote no depósito. B (binário com depósito de buffers) removeu base64, mas ainda reteve 75 MiB. D (JSON/base64, persistência imediata) removeu a retenção do depósito, mas não o pico do parser/decoder. A persistência imediata sem mudar transporte não foi suficiente para uma margem segura.

**Não é aprovação do app completo em 256 MiB.** O laboratório importa módulos reais, mas não inicia World, WebSocket, sessões, UI nem outros consumidores do app. A falha do app completo já está documentada em `029-resultado.md:61-69`; ela não foi repetida. O teto de 1 GiB foi usado somente na comparação de medição, nunca como proposta de correção. B e C foram também medidos isoladamente em 256 MiB, sem aumentar esse teto. O sucesso de B isolado, perto do teto, não revoga o bloqueio conhecido.

## Implementação experimental e fidelidade

- A: reprodução instrumentada do caminho de sucesso de `habblaud-029/server/http/app.ts:217-242`: chunks, `Buffer.concat(...).toString('utf8')`, `JSON.parse`. Não há parser JSON novo. Importa `parseAttachment`, `AttachmentStore` e `writeAttachments` reais de `server/spawn/attachments.ts`.
- B: corpo binário acumulado em chunks, concatenado em Buffer; mesmo `AttachmentStore`, `take`, `held`, writer e `release` reais de A.
- C: `pipeline(req, Transform(hash/size), createWriteStream)`; staging no volume persistente bind-mounted `/data`, fora do `/tmp` do contêiner. Upload guarda somente caminho, hash e tamanho. Commit usa `rename` no mesmo filesystem. Buffers transitórios podem continuar alocados até GC natural.
- D: mesmo JSON/parser/base64 de A; writer real grava cada arquivo durante upload. Só metadados aguardam commit, que renomeia os arquivos no mesmo volume.
- Todo cenário verifica o hash no recebimento e depois relê todos os arquivos persistidos, em streams, para conferir hashes no commit. O custo dessa leitura está incluído na duração do commit e na fase de verificação.
- Ordem: cinco uploads sequenciais sem pausa artificial entre eles; 1 s de espera antes do commit e 1 s após a verificação. Não há `global.gc`, `--expose-gc`, limpeza manual de pagecache ou alteração de parâmetros de V8.
- O protótipo bruto não implementa guard Host/Origin, autenticação, contrato final de Content-Type, limite concorrente global, cancelamento/expiração ou segurança de paths de produção. Não é um servidor final nem uma proposta de remover essas proteções.
- `writeAttachments` usa escrita síncrona, sem fsync. O stream fecha o arquivo, também sem fsync. Os números não demonstram durabilidade contra queda de energia.

## Ambiente e reprodução

- Candidata fornecida: `habblaud-029` em `c19afb0`; original fornecido em `6fd1c5b`.
- Imagem local: `docker.io/library/node:24-bookworm-slim`, ID `dc1f22974dab4be31a8b56d58cb1f8fc218e83de83ccd831d885b3d7ac1223fb`; digest `sha256:2dd9a4f758e173b65fee15388f7706af6fb01ef50cf7a2c0538711d67a6981ae`.
- Node efetivo: `v24.22.1`, também registrado no JSON por execução. Loader tsx já instalado da candidata, montada somente leitura. Nenhum install/build/suíte foi executado.
- Staging é um diretório único criado sob `plans/029-upload-lab-data-*`, bind RW em `/data`, removido em `finally`. Filesystem do host: btrfs em `/home`, `/dev/sda2[/@home]`, `compress=zstd:3`. Payload aleatório evita ganho relevante por compressão de conteúdo.
- Contêiner servidor com rede externa desabilitada, rootfs RO, sem capabilities e com `no-new-privileges`, UID do usuário (`keep-id`). Cliente tem cgroup próprio de 1 GiB e compartilha somente namespace de rede do servidor. Não usa serviço de produção.
- `--memory` e `--memory-swap` têm o mesmo valor: não se concedeu swap adicional. Containers e diretórios de dados são removidos em `finally`, inclusive em falha. Receitas e dados brutos ficam em `plans/`.

Comandos executados a partir de `/home/anderson/Desenvolvimento`:

```sh
python3 habblaud/plans/029-upload-lab.py
python3 habblaud/plans/029-upload-lab.py --modes BC --limit 256m --output /home/anderson/Desenvolvimento/habblaud/plans/029-upload-lab-256-results.json
python3 habblaud/plans/029-upload-lab.py --modes A --output /home/anderson/Desenvolvimento/habblaud/plans/029-upload-lab-warm-a-results.json
python3 habblaud/plans/029-upload-lab.py --modes A --output /home/anderson/Desenvolvimento/habblaud/plans/029-upload-lab-isolated-a-results.json
python3 habblaud/plans/029-upload-lab.report.py
```

Cada JSON contém comandos Podman completos com nomes únicos, hashes esperados, amostras temporais, métricas do processo, métricas cgroup e estado final observado. `State` foi capturado antes da remoção; pode indicar servidor ainda encerrando, mas `OOMKilled` não ocorreu. A verificação de bytes independe desse estado.

## Instrumentação e comparabilidade

Amostras a cada 10 ms e checkpoints explícitos em etapas síncronas: chunks, string, JSON parsed, decoded, retenção, held, escrita e commit. `memory.peak` vem do kernel: ele captura picos perdidos pelo timer. RSS/heap/external/arrayBuffers são máximos amostrados, não picos contínuos garantidos; `memory.stat` também pode ter defasagem de atualização. Os máximos de colunas diferentes não precisam ocorrer no mesmo instante. `external` inclui `arrayBuffers`; não somar ambos. RSS, anon e current também não são componentes somáveis entre si.

A primeira execução A teve 120,5 MiB de `file` já no checkpoint ready; as seguintes tiveram aproximadamente zero. Isso é compatível com cache frio de imports/loader cobrado ao primeiro cgroup, mas não isolamos qual arquivo o causou. Não atribuímos essa diferença ao upload. Conservamos as três A iniciais no apêndice e fizemos três A aquecidas auxiliares, iniciadas sem barreira de exclusão em relação à rodada B/C de 256 MiB. Por isso, fizemos depois mais três A aquecidas isoladas para a tabela principal. A tabela principal usa essas últimas três A e as B/C/D da rodada original; todas em 1 GiB, sem concorrência entre experimentos dessa comparação. Tempos da rodada auxiliar A e da rodada limitada B/C podem conter interferência de CPU; seus cgroups de memória continuaram separados. Cache de imports não foi artificialmente apagado.

O volume de dados é único por execução. Pagecache dos arquivos persistidos continua cobrado ao cgroup: cerca de 75–76 MiB em `file`, mesmo com poucos buffers JS em C. **Streaming não significa memória zero.** Parte de `file` é recuperável pelo kernel sob pressão, ao contrário dos buffers ainda referenciados em A/B. `/tmp` tmpfs de 64 MiB do tenant não comportaria este lote; esta receita não o usa. Não houve ensaio de pressão concorrente nem prova de latência de reclaim.

Backpressure: cliente escreve chunks de 64 KiB e aguarda `drain` quando `request.write` retorna false. C usa `pipeline` e writer com `highWaterMark=64 KiB`; o fluxo para de drenar o socket quando o disco deixa de aceitar chunks. Os contadores abaixo medem drains do cliente, não stalls exclusivos de disco. A/B mantêm o corpo completo; a presença de backpressure de socket não limita sua retenção total. Não foi imposto disco lento.

## Resumo principal — máximos por execução, medianas de três repetições

Todos os valores de memória estão em MiB (2²⁰ bytes). Tempos em ms.

|Modo|Pico cgroup|RSS|heapUsed|external|arrayBuffers|anon|file|Uploads (soma)|Commit + hashes|
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
|A|356.2|318.5|52.4|165.3|121.2|273.4|76.0|779.7|213.2|
|B|258.8|217.1|19.9|103.8|99.8|173.5|76.0|219.1|195.6|
|C|172.8|129.2|19.9|20.4|16.3|85.3|76.0|276.8|193.6|
|D|323.5|294.9|52.4|120.3|76.2|252.5|76.0|795.7|178.0|

Delta mediano de pico cgroup C versus A: 183.4 MiB (51.5% menor). Esse delta pertence ao laboratório, não ao app completo.

## Todas as execuções — máximos e tempos

|Série|Modo|Rep.|Limite|Pico cgroup|RSS|heap|external|arrays|anon|file|Upload 0|Upload 1|Upload 2|Upload 3|Upload 4|Commit + hashes|Duração servidor|Drains cliente|Hashes|
|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
|Principal|A|1|1g|331.7|288.0|35.7|140.3|116.2|245.4|76.0|167.6|157.5|155.9|145.7|152.9|191.6|3782.5|1600|True|
|Principal|A|2|1g|356.2|318.5|52.4|165.3|121.2|273.4|76.0|171.8|164.2|152.6|147.3|149.3|213.2|3837.4|1600|True|
|Principal|A|3|1g|375.5|339.0|52.4|185.3|141.2|294.5|76.0|168.2|158.3|155.4|149.4|147.9|216.9|3803.6|1600|True|
|Principal|B|1|1g|248.9|205.6|19.9|95.3|91.3|162.9|76.0|59.2|46.7|44.5|40.1|38.3|195.6|3096.7|1200|True|
|Principal|B|2|1g|262.1|218.6|19.9|103.8|99.8|175.6|76.0|56.9|40.8|43.1|39.7|38.5|207.3|3088.9|1200|True|
|Principal|B|3|1g|258.8|217.1|20.0|106.8|102.8|173.5|76.0|54.2|42.2|45.8|37.1|37.3|189.0|3077.4|1200|True|
|Principal|C|1|1g|171.6|128.2|19.1|20.4|16.4|85.3|76.0|69.5|56.5|54.5|50.1|46.1|193.7|3108.3|1200|True|
|Principal|C|2|1g|174.5|129.2|20.1|21.3|15.1|86.3|76.0|71.1|53.0|53.4|53.5|48.5|193.6|3156.2|1200|True|
|Principal|C|3|1g|172.8|130.1|19.9|20.3|16.3|84.4|76.0|67.9|54.9|52.8|48.6|49.3|183.4|3082.8|1200|True|
|Principal|D|1|1g|323.5|296.5|53.9|140.3|96.2|252.9|76.0|174.5|161.6|157.9|149.5|152.2|178.0|3804.6|1600|True|
|Principal|D|2|1g|325.1|294.9|52.4|120.3|76.2|252.5|76.0|177.4|164.8|156.6|148.3|153.3|177.1|3785.8|1600|True|
|Principal|D|3|1g|266.9|224.1|35.8|80.3|56.2|179.0|76.0|170.8|157.7|158.5|148.8|154.0|180.3|3812.1|1600|True|
|256 MiB|B|1|256m|252.5|212.8|16.4|110.3|106.2|169.6|76.0|58.6|44.2|42.4|40.3|38.7|204.1|3098.5|1200|True|
|256 MiB|B|2|256m|243.4|204.1|17.9|103.9|99.9|156.1|76.0|58.5|44.5|45.3|36.6|37.3|187.3|3064.7|1200|True|
|256 MiB|B|3|256m|241.3|205.7|17.8|103.6|99.6|158.3|76.0|57.4|43.6|45.4|38.5|40.6|188.5|3088.0|1200|True|
|256 MiB|C|1|256m|167.5|125.3|17.7|21.1|17.0|81.6|76.0|68.0|60.5|51.9|47.6|48.3|172.0|3056.9|1200|True|
|256 MiB|C|2|256m|167.8|121.9|17.8|20.8|16.8|80.9|76.0|68.9|58.5|50.5|45.7|50.9|175.4|3103.5|1200|True|
|256 MiB|C|3|256m|166.8|122.8|17.3|19.6|15.5|78.8|76.0|69.9|59.0|52.2|48.9|44.5|173.0|3087.7|1200|True|
|Inicial A|A|1|1g|475.4|318.7|53.8|165.3|121.2|271.5|196.5|171.3|157.6|154.8|147.1|148.2|220.6|3994.1|1600|True|
|Inicial A|A|2|1g|354.6|320.5|53.8|165.3|121.2|273.4|76.0|169.9|158.7|155.6|148.1|146.6|194.3|3809.7|1600|True|
|Inicial A|A|3|1g|355.4|319.8|52.4|165.3|121.2|272.8|76.0|172.4|164.3|157.3|148.9|150.1|207.9|3895.9|1600|True|
|Auxiliar A|A|1|1g|383.4|345.1|52.4|185.3|141.2|301.7|76.0|165.2|161.5|154.3|149.6|150.6|191.0|3817.1|1600|True|
|Auxiliar A|A|2|1g|356.9|320.0|53.8|165.3|121.2|277.2|76.0|167.8|171.1|157.4|152.2|148.9|224.9|3842.2|1600|True|
|Auxiliar A|A|3|1g|364.9|325.6|52.4|165.3|121.2|283.1|76.0|168.9|162.1|152.8|146.0|146.5|229.6|3892.7|1600|True|

Wire por upload: A/D enviam 20.971.585 bytes (20 MiB + 65 bytes JSON); B/C enviam 15.728.640 bytes. Cada lote binário evita aproximadamente 25 MiB de tráfego base64. Os tempos de upload são requisição/resposta; não incluem geração dos bytes nem serialização JSON no cliente. A duração do servidor exclui importação inicial, já concluída antes do início do relógio; inclui espera por inicialização do cliente e pausas de 1 s. Não é throughput puro.

## Todas as fases — antes, recebimento, retenção, commit e depois

`ready` é um checkpoint. `receive max` agrega receive-start, receive, etapas JSON/binárias e decoded. `retained 5` é o checkpoint imediatamente após o quinto upload (A/B ainda com depósito cheio). `precommit` ocorre depois de 1 s, sem GC forçado. `commit max` inclui held, escrita, rename e releitura de hashes. `final` ocorre depois de mais 1 s. `current` e demais colunas em linhas max são máximos independentes; o JSON conserva cada instante original.

|Série|Modo|Rep.|Fase|current|peak kernel acumulado|RSS|heapUsed|external|arrayBuffers|anon|file|shmem|file_dirty|file_writeback|
|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
|Principal|A|1|ready|84.5|84.5|118.5|20.0|6.9|3.0|76.7|0.0|0.0|0.0|0.0|
|Principal|A|1|receive max|255.7|255.7|287.7|33.5|140.3|116.2|245.3|1.0|1.0|0.0|0.0|
|Principal|A|1|retained 5|255.7|255.7|287.7|31.9|140.3|116.2|245.3|1.0|1.0|0.0|0.0|
|Principal|A|1|precommit|255.9|256.2|287.9|34.9|120.3|96.2|245.3|1.0|1.0|0.0|0.0|
|Principal|A|1|commit max|331.4|331.7|288.0|35.7|120.3|105.3|245.4|76.0|1.0|75.0|0.0|
|Principal|A|1|final|312.2|331.7|268.3|15.2|83.1|79.0|225.8|76.0|1.0|75.0|0.0|
|Principal|A|2|ready|85.2|85.2|118.5|20.0|6.8|2.9|74.6|0.0|0.0|0.0|0.0|
|Principal|A|2|receive max|287.5|287.7|318.5|52.4|165.3|121.2|273.4|1.0|1.0|0.0|0.0|
|Principal|A|2|retained 5|281.5|287.7|312.8|31.7|160.3|96.2|267.8|1.0|1.0|0.0|0.0|
|Principal|A|2|precommit|280.9|287.7|312.8|35.1|160.3|96.2|267.8|1.0|1.0|0.0|0.0|
|Principal|A|2|commit max|356.0|356.2|313.0|35.8|160.3|104.1|267.8|76.0|1.0|75.0|0.0|
|Principal|A|2|final|336.3|356.2|293.6|15.2|83.1|79.0|248.4|76.0|1.0|75.0|0.0|
|Principal|A|3|ready|84.2|84.2|119.1|20.0|6.8|2.9|75.0|0.0|0.0|0.0|0.0|
|Principal|A|3|receive max|305.7|306.0|339.0|52.4|185.3|141.2|294.5|1.0|1.0|0.0|0.0|
|Principal|A|3|retained 5|300.7|306.0|334.0|31.7|180.3|96.2|289.6|1.0|1.0|0.0|0.0|
|Principal|A|3|precommit|299.8|306.0|334.0|35.0|180.3|96.2|289.6|1.0|1.0|0.0|0.0|
|Principal|A|3|commit max|375.5|375.5|334.4|35.9|180.3|107.1|289.6|76.0|1.0|75.0|0.0|
|Principal|A|3|final|357.1|375.5|314.5|15.2|83.1|79.0|270.1|76.0|1.0|75.0|0.0|
|Principal|B|1|ready|88.6|88.6|119.2|19.9|6.9|3.0|78.0|0.0|0.0|0.0|0.0|
|Principal|B|1|receive max|174.9|174.9|204.6|15.0|95.3|91.2|162.9|1.0|1.0|0.0|0.0|
|Principal|B|1|retained 5|174.9|174.9|204.1|12.7|95.3|91.2|162.5|1.0|1.0|0.0|0.0|
|Principal|B|1|precommit|159.1|174.9|191.7|15.0|95.3|76.2|146.7|1.0|1.0|0.0|0.0|
|Principal|B|1|commit max|248.9|248.9|205.6|15.7|95.3|91.3|160.8|76.0|1.0|75.0|0.0|
|Principal|B|1|final|171.9|248.9|128.5|15.1|95.3|1.2|83.6|76.0|1.0|75.0|0.0|
|Principal|B|2|ready|88.5|88.5|121.9|19.9|6.8|2.9|79.0|0.0|0.0|0.0|0.0|
|Principal|B|2|receive max|174.6|174.6|206.9|15.3|95.3|91.2|163.7|1.0|1.0|0.0|0.0|
|Principal|B|2|retained 5|174.5|174.6|206.8|12.7|95.3|91.2|163.5|1.0|1.0|0.0|0.0|
|Principal|B|2|precommit|174.1|174.6|207.0|15.1|95.3|91.2|163.6|1.0|1.0|0.0|0.0|
|Principal|B|2|commit max|261.8|262.1|218.6|15.8|103.8|99.8|175.6|76.0|1.0|75.0|0.0|
|Principal|B|2|final|167.7|262.1|125.0|15.2|95.3|1.2|82.0|76.0|1.0|75.0|0.0|
|Principal|B|3|ready|85.3|85.3|119.3|20.0|6.8|2.9|76.2|0.0|0.0|0.0|0.0|
|Principal|B|3|receive max|171.8|171.8|204.7|15.7|95.3|91.2|161.2|1.0|1.0|0.0|0.0|
|Principal|B|3|retained 5|171.8|171.8|204.7|12.7|95.3|91.2|161.2|1.0|1.0|0.0|0.0|
|Principal|B|3|precommit|170.8|171.8|204.8|15.1|95.3|91.2|161.3|1.0|1.0|0.0|0.0|
|Principal|B|3|commit max|258.8|258.8|217.1|16.0|106.8|102.8|173.5|76.0|1.0|75.0|0.0|
|Principal|B|3|final|168.8|258.8|126.8|15.1|95.3|1.2|83.4|76.0|1.0|75.0|0.0|
|Principal|C|1|ready|84.5|84.5|117.5|18.9|6.8|2.9|75.8|0.0|0.0|0.0|0.0|
|Principal|C|1|receive max|171.0|171.3|127.2|15.7|20.3|15.5|85.2|75.0|1.0|73.9|0.0|
|Principal|C|1|retained 5|171.0|171.3|127.3|13.7|20.4|16.4|85.0|75.0|1.0|73.9|0.0|
|Principal|C|1|precommit|171.3|171.3|127.8|12.0|5.4|1.3|85.3|76.0|1.0|75.0|0.0|
|Principal|C|1|commit max|171.6|171.6|128.2|12.7|20.4|16.4|85.3|76.0|1.0|75.0|0.0|
|Principal|C|1|final|168.1|171.6|124.7|12.3|8.0|1.9|82.8|76.0|1.0|75.0|0.0|
|Principal|C|2|ready|86.7|86.7|118.3|20.1|6.9|3.0|75.9|0.0|0.0|0.0|0.0|
|Principal|C|2|receive max|170.1|170.1|126.9|15.9|18.8|14.8|83.7|74.4|1.0|73.4|0.0|
|Principal|C|2|retained 5|171.7|171.7|127.3|12.0|7.3|3.2|84.0|76.0|1.0|75.0|0.0|
|Principal|C|2|precommit|171.5|171.8|127.3|15.4|7.3|3.2|84.0|76.0|1.0|75.0|0.0|
|Principal|C|2|commit max|174.5|174.5|129.2|15.9|21.3|15.1|86.3|76.0|1.0|75.0|0.0|
|Principal|C|2|final|171.1|174.5|125.7|15.4|7.9|3.9|82.8|76.0|1.0|75.0|0.0|
|Principal|C|3|ready|87.9|87.9|119.2|19.9|6.8|2.9|76.4|0.0|0.0|0.0|0.0|
|Principal|C|3|receive max|172.3|172.4|127.7|15.6|18.3|14.0|84.4|75.2|1.0|74.2|0.0|
|Principal|C|3|retained 5|172.5|172.8|127.7|12.1|7.3|3.2|84.4|75.2|1.0|74.2|0.0|
|Principal|C|3|precommit|167.6|172.8|127.7|15.3|7.3|3.2|80.5|76.0|1.0|75.0|0.0|
|Principal|C|3|commit max|169.9|172.8|130.1|15.9|20.3|16.3|81.7|76.0|1.0|75.0|0.0|
|Principal|C|3|final|163.9|172.8|123.4|15.5|8.1|4.0|76.2|76.0|1.0|75.0|0.0|
|Principal|D|1|ready|85.0|85.0|119.0|20.0|6.8|2.9|76.8|0.0|0.0|0.0|0.0|
|Principal|D|1|receive max|323.4|323.5|296.5|53.9|140.3|96.2|252.9|61.0|1.0|60.0|0.0|
|Principal|D|1|retained 5|272.6|323.5|229.6|31.7|115.3|36.2|185.9|76.0|1.0|75.0|0.0|
|Principal|D|1|precommit|272.3|323.5|229.6|35.1|115.3|36.2|185.9|76.0|1.0|75.0|0.0|
|Principal|D|1|commit max|273.2|323.5|229.9|35.8|115.3|45.7|185.9|76.0|1.0|75.0|0.0|
|Principal|D|1|final|188.9|323.5|146.8|15.2|8.0|4.0|103.3|76.0|1.0|75.0|0.0|
|Principal|D|2|ready|86.1|86.1|118.1|18.9|6.8|2.9|76.1|0.0|0.0|0.0|0.0|
|Principal|D|2|receive max|325.1|325.1|294.9|52.4|120.3|76.2|252.5|61.0|1.0|60.0|0.0|
|Principal|D|2|retained 5|273.3|325.1|228.2|31.7|115.3|36.2|185.8|76.0|1.0|75.0|0.0|
|Principal|D|2|precommit|273.4|325.1|228.2|35.0|115.3|36.2|185.9|76.0|1.0|75.0|0.0|
|Principal|D|2|commit max|274.1|325.1|228.5|35.8|115.3|47.0|185.9|76.0|1.0|75.0|0.0|
|Principal|D|2|final|206.3|325.1|160.3|15.1|8.1|4.0|117.9|76.0|1.0|75.0|0.0|
|Principal|D|3|ready|87.4|87.4|118.7|20.0|6.8|2.9|74.4|0.0|0.0|0.0|0.0|
|Principal|D|3|receive max|250.8|250.8|224.1|33.2|80.3|56.2|179.0|61.0|1.0|60.0|0.0|
|Principal|D|3|retained 5|265.8|265.8|220.3|31.7|80.3|36.2|175.5|76.0|1.0|75.0|0.0|
|Principal|D|3|precommit|265.9|266.3|220.3|34.9|80.3|36.2|175.5|76.0|1.0|75.0|0.0|
|Principal|D|3|commit max|266.9|266.9|220.5|35.8|80.3|47.1|175.5|76.0|1.0|75.0|0.0|
|Principal|D|3|final|191.3|266.9|146.2|15.2|8.1|4.0|101.0|76.0|1.0|75.0|0.0|
|256 MiB|B|1|ready|77.6|77.6|110.9|15.6|6.8|2.8|68.1|0.0|0.0|0.0|0.0|
|256 MiB|B|1|receive max|180.5|180.5|212.8|16.4|110.3|106.2|169.6|1.0|1.0|0.0|0.0|
|256 MiB|B|1|retained 5|180.4|180.5|212.8|13.0|110.3|106.2|169.6|1.0|1.0|0.0|0.0|
|256 MiB|B|1|precommit|179.5|180.6|212.8|12.0|95.3|91.2|169.6|1.0|1.0|0.0|0.0|
|256 MiB|B|1|commit max|252.5|252.5|212.8|12.7|104.4|100.3|169.6|76.0|1.0|75.0|0.0|
|256 MiB|B|1|final|238.2|252.5|196.0|12.3|81.1|77.0|152.4|76.0|1.0|75.0|0.0|
|256 MiB|B|2|ready|77.8|80.2|109.7|16.7|6.8|2.8|67.1|0.0|0.0|0.0|0.0|
|256 MiB|B|2|receive max|166.1|166.1|196.4|17.9|95.3|91.2|153.3|1.0|1.0|0.0|0.0|
|256 MiB|B|2|retained 5|166.1|166.1|196.4|12.6|95.3|91.2|153.3|1.0|1.0|0.0|0.0|
|256 MiB|B|2|precommit|160.4|166.1|196.4|12.0|95.3|91.2|148.7|1.0|1.0|0.0|0.0|
|256 MiB|B|2|commit max|243.4|243.4|204.1|12.7|103.9|99.9|156.1|76.0|1.0|75.0|0.0|
|256 MiB|B|2|final|235.5|243.4|196.5|12.3|81.4|77.3|148.7|76.0|1.0|75.0|0.0|
|256 MiB|B|3|ready|76.6|79.1|110.1|16.9|6.8|2.8|66.5|0.0|0.0|0.0|0.0|
|256 MiB|B|3|receive max|165.1|165.1|197.2|17.4|95.3|91.2|153.4|1.0|1.0|0.0|0.0|
|256 MiB|B|3|retained 5|158.4|165.1|197.2|12.7|95.3|91.2|150.4|1.0|1.0|0.0|0.0|
|256 MiB|B|3|precommit|157.1|165.1|196.8|11.9|95.3|91.2|149.9|1.0|1.0|0.0|0.0|
|256 MiB|B|3|commit max|241.3|241.3|205.7|12.7|103.6|99.6|158.3|76.0|1.0|75.0|0.0|
|256 MiB|B|3|final|227.7|241.3|192.2|12.3|81.6|77.5|145.4|76.0|1.0|75.0|0.0|
|256 MiB|C|1|ready|78.8|81.1|112.0|16.8|6.8|2.8|68.8|0.0|0.0|0.0|0.0|
|256 MiB|C|1|receive max|159.1|159.4|117.3|17.7|13.4|9.4|73.6|75.1|1.0|28.1|11.4|
|256 MiB|C|1|retained 5|159.3|159.6|117.3|13.5|6.7|2.6|73.6|75.1|1.0|14.1|0.0|
|256 MiB|C|1|precommit|159.0|159.6|117.3|13.8|6.4|2.3|73.6|76.0|1.0|15.0|0.0|
|256 MiB|C|1|commit max|167.2|167.5|125.3|14.2|21.1|17.0|81.6|76.0|1.0|15.0|0.0|
|256 MiB|C|1|final|165.2|167.5|123.1|12.3|6.5|2.5|79.4|76.0|1.0|15.0|0.0|
|256 MiB|C|2|ready|81.6|81.6|111.7|16.9|6.8|2.8|71.0|0.0|0.0|0.0|0.0|
|256 MiB|C|2|receive max|162.3|162.6|116.9|17.4|13.2|9.1|75.6|74.5|1.0|27.4|14.2|
|256 MiB|C|2|retained 5|163.2|163.2|117.3|14.4|13.9|9.8|76.2|76.0|1.0|15.0|0.0|
|256 MiB|C|2|precommit|159.9|163.2|114.9|13.6|5.3|1.3|73.7|76.0|1.0|15.0|0.0|
|256 MiB|C|2|commit max|167.8|167.8|121.9|14.1|20.8|16.8|79.6|76.0|1.0|15.0|0.0|
|256 MiB|C|2|final|167.6|167.8|121.9|12.3|6.3|2.2|80.9|76.0|1.0|15.0|0.0|
|256 MiB|C|3|ready|81.3|81.3|112.6|16.3|6.8|2.8|69.1|0.0|0.0|0.0|0.0|
|256 MiB|C|3|receive max|161.0|161.0|117.6|17.3|13.8|9.7|73.7|74.2|1.0|27.1|11.2|
|256 MiB|C|3|retained 5|162.4|162.7|118.0|14.0|11.4|7.4|74.0|76.0|1.0|15.0|0.0|
|256 MiB|C|3|precommit|159.5|162.7|115.6|14.2|6.5|2.5|71.5|76.0|1.0|15.0|0.0|
|256 MiB|C|3|commit max|166.8|166.8|122.8|14.4|19.6|15.5|78.8|76.0|1.0|15.0|0.0|
|256 MiB|C|3|final|160.4|166.8|116.1|12.3|6.5|2.5|72.2|76.0|1.0|15.0|0.0|
|Inicial A|A|1|ready|206.2|206.2|117.0|20.0|6.9|3.0|75.0|120.5|0.0|0.0|0.0|
|Inicial A|A|1|receive max|405.7|405.7|318.7|53.8|165.3|121.2|271.5|121.5|1.0|0.0|0.0|
|Inicial A|A|1|retained 5|399.9|405.7|313.0|31.7|160.3|96.2|265.9|121.5|1.0|0.0|0.0|
|Inicial A|A|1|precommit|399.9|405.7|313.0|35.0|160.3|96.2|265.9|121.5|1.0|0.0|0.0|
|Inicial A|A|1|commit max|475.1|475.4|313.1|35.8|160.3|104.6|265.9|196.5|1.0|75.0|0.0|
|Inicial A|A|1|final|456.0|475.4|293.6|15.2|83.1|79.0|246.2|196.5|1.0|75.0|0.0|
|Inicial A|A|2|ready|88.8|88.8|119.7|20.0|6.8|2.9|77.2|0.0|0.0|0.0|0.0|
|Inicial A|A|2|receive max|284.9|285.2|320.5|53.8|165.3|121.2|273.4|1.0|1.0|0.0|0.0|
|Inicial A|A|2|retained 5|279.2|285.2|314.8|31.7|160.3|96.2|267.8|1.0|1.0|0.0|0.0|
|Inicial A|A|2|precommit|278.9|285.2|314.8|35.0|160.3|96.2|267.8|1.0|1.0|0.0|0.0|
|Inicial A|A|2|commit max|354.1|354.6|314.9|35.7|160.3|104.6|267.8|76.0|1.0|75.0|0.0|
|Inicial A|A|2|final|334.8|354.6|295.1|15.2|83.1|79.1|248.0|76.0|1.0|75.0|0.0|
|Inicial A|A|3|ready|84.8|84.8|118.5|20.0|6.8|2.9|74.5|0.0|0.0|0.0|0.0|
|Inicial A|A|3|receive max|285.2|285.4|319.8|52.4|165.3|121.2|272.8|1.0|1.0|0.0|0.0|
|Inicial A|A|3|retained 5|279.6|285.4|314.1|31.7|160.3|96.2|267.2|1.0|1.0|0.0|0.0|
|Inicial A|A|3|precommit|279.7|285.4|314.1|35.0|160.3|96.2|267.2|1.0|1.0|0.0|0.0|
|Inicial A|A|3|commit max|354.9|355.4|314.4|35.8|160.3|105.3|267.2|76.0|1.0|75.0|0.0|
|Inicial A|A|3|final|336.4|355.4|294.8|15.2|83.0|79.0|247.9|76.0|1.0|75.0|0.0|
|Auxiliar A|A|1|ready|83.7|83.7|118.2|20.0|6.8|2.9|75.3|0.0|0.0|0.0|0.0|
|Auxiliar A|A|1|receive max|312.5|312.5|345.1|52.4|185.3|141.2|301.7|1.0|1.0|0.0|0.0|
|Auxiliar A|A|1|retained 5|307.3|312.5|340.1|31.7|180.3|96.2|296.7|1.0|1.0|0.0|0.0|
|Auxiliar A|A|1|precommit|307.4|312.5|340.1|35.1|180.3|96.2|296.7|1.0|1.0|0.0|0.0|
|Auxiliar A|A|1|commit max|383.1|383.4|340.4|35.8|180.3|104.7|296.7|76.0|1.0|75.0|0.0|
|Auxiliar A|A|1|final|363.0|383.4|320.6|15.3|83.0|79.0|277.3|76.0|1.0|75.0|0.0|
|Auxiliar A|A|2|ready|84.3|84.3|119.3|20.0|6.9|3.0|76.9|0.0|0.0|0.0|0.0|
|Auxiliar A|A|2|receive max|287.0|287.5|320.0|53.8|165.3|121.2|277.2|1.0|1.0|0.0|0.0|
|Auxiliar A|A|2|retained 5|281.6|287.5|314.5|31.7|160.3|96.2|271.7|1.0|1.0|0.0|0.0|
|Auxiliar A|A|2|precommit|281.4|287.5|314.5|35.1|160.3|96.2|271.7|1.0|1.0|0.0|0.0|
|Auxiliar A|A|2|commit max|356.6|356.9|314.8|35.9|160.3|104.7|271.7|76.0|1.0|75.0|0.0|
|Auxiliar A|A|2|final|336.5|356.9|295.1|15.2|83.0|79.0|252.2|76.0|1.0|75.0|0.0|
|Auxiliar A|A|3|ready|86.5|86.5|119.2|20.0|6.9|3.0|77.0|0.0|0.0|0.0|0.0|
|Auxiliar A|A|3|receive max|294.7|294.7|325.6|52.4|165.3|121.2|283.1|1.0|1.0|0.0|0.0|
|Auxiliar A|A|3|retained 5|289.7|294.7|320.6|31.7|160.3|96.2|278.1|1.0|1.0|0.0|0.0|
|Auxiliar A|A|3|precommit|289.3|294.7|320.6|35.0|160.3|96.2|278.1|1.0|1.0|0.0|0.0|
|Auxiliar A|A|3|commit max|364.6|364.9|321.0|35.8|160.3|105.1|278.1|76.0|1.0|75.0|0.0|
|Auxiliar A|A|3|final|345.5|364.9|301.2|15.3|83.1|79.1|258.7|76.0|1.0|75.0|0.0|

## Evidência suficiente e trabalho não feito

Critério de parada escolhido: três repetições comparáveis para cada arquitetura, bytes persistidos conferidos, snapshots das fases e delta mensurável; mais três repetições de B/C no limite real isolado. Os 24 ensaios satisfazem esse critério, com rodadas auxiliares preservadas. Os JSON retêm os 120 hashes esperados, cada resposta de tamanho/hash conferida no cliente e 120 hashes relidos em disco conferidos durante commit. Qualquer divergência encerraria a receita com erro.

Não foi implementado transporte de produto, parser incremental, multipart, quota persistente, política de expiração ou mudança de permissões. Não foi executado browser nem app completo nesta rodada. Nenhum teste de concorrência, desconexão, disco cheio, travessia de paths, abuso de quota ou compatibilidade de agentes integra estes números. Esses limites impedem promover o protótipo diretamente a release; não impedem concluir que binário com buffers resolve apenas parte do pico e que streaming para armazenamento persistente elimina a retenção do lote em RAM.


## Recomendação após revisão do desenho

Escolher upload `application/octet-stream` com gravação progressiva em disco e índice de metadados. Não escrever um parser JSON/base64 incremental nem adicionar multipart para este caso. O resultado favorece eliminar tanto a codificação quanto a retenção do lote, não apenas otimizar `readJson`.

### Achados priorizados

|Achado|Evidência na candidata `habblaud-029`|Impacto|Esforço|Risco|Confiança|
|---|---|---|---|---|---|
|Transporte e depósito mantêm cópias completas|`client/src/net/store.ts:341-354`, `server/http/app.ts:217-242`, `server/spawn/attachments.ts:47-100`|Pico de parser/base64 e retenção de todos os arquivos até enviar|M|MED: mudar contrato HTTP e ciclo de vida dos arquivos|HIGH: código e laboratório|
|Auxiliar reencoda lote inteiro|`server/deliveries/fetcher.ts:123-133`, `server/spawn/spawn.ts:244-254`, `scripts/codex-bridge.ts:308-326`|A correção só no navegador deixa um pico de base64 no Docker|M|MED: contrato servidor–auxiliar precisa de cutover conjunto|HIGH: código; este salto não foi medido no laboratório|
|Dois depósitos têm orçamento independente|`server/index.ts:287-308`|Spawn e mensagens podem reter até 240 MiB de arquivos no mesmo processo|S dentro da mudança de depósito|MED: reservas e expiração não podem consumir o mesmo anexo duas vezes|HIGH: código; não executamos carga combinada|

### Desenho mínimo a especificar na revisão do plano 029

- Navegador envia o `File` cru, sem `FileReader`, base64 ou `JSON.stringify` do conteúdo. Nome/tipo são metadados pequenos, limitados e saneados; cliente nunca escolhe caminho.
- Exceção exata no guard: somente POST das rotas de anexos aceita `application/octet-stream`. Esse tipo já exige preflight em requisições cross-origin; manter Host, Origin, login, permissão `messages`/`spawn` e ausência de CORS permissivo. Não liberar binário globalmente nem aceitar tipos de formulário.
- Stream grava em staging privado no volume persistente, com backpressure e contagem real de bytes. Não usar `/tmp`: é tmpfs de 64 MiB em `docker-compose.yml:68-69` e `omp-tenant/bin/omp-tenant:202`, conta como RAM e não comporta o lote.
- Guardar somente ID, nome, tipo, tamanho, instante e caminho interno no depósito. Usar um orçamento compartilhado de 120 MiB para pendentes e reservas em voo, preservando cinco arquivos por mensagem, oito no Novo agente e 15 MiB por arquivo.
- Ao aceitar o envio, consumir IDs atomicamente e promover arquivos por `rename` no mesmo filesystem, sem reler o lote em Buffers. Preservar captura da sessão e reutilização de IDs no cliente. Expirar somente uploads pendentes/abortados, nunca anexos finais já referenciados.
- No Docker padrão, staging cabe no volume persistente `/data` (`docker-compose.yml:27-28`). O auxiliar deve receber metadados no poll e buscar cada arquivo por stream em rota local autenticada/autorizada para a operação. Não reconstruir JSON base64 do lote e não montar projetos RW.
- Tratamento de excesso precisa provar 413 observável: `pipeline(req,...)` pode destruir o socket antes da resposta em caso de erro. Aborto, disco cheio e falha de rename devem limpar somente arquivos parciais próprios e devolver a reserva; nenhum prompt pode receber caminho inexistente.
- Cutover inclui clientes de mensagens/Novo agente e auxiliar, além do servidor; preservar as travas atuais. A necessidade de mudança não autoriza novas opções, retries ou um protocolo genérico de upload.

### Alternativas consideradas e rejeitadas

- Apenas reduzir cópias em `readJson`: tentativa anterior não resolveu o app completo e mantém o transporte/base64; não é a solução principal.
- Apenas binário em RAM: reduziu bastante o pico, mas a rodada sob 256 MiB chegou perto do teto sem incluir o app completo. Não oferece a mesma margem que staging em disco.
- Apenas persistir JSON imediatamente: o laboratório separou esse efeito; o parser/decoder ainda produz pico alto.
- Parser JSON incremental próprio: mantém tráfego maior e adiciona complexidade de escapes, limites e fronteiras de base64.
- Multipart: desnecessário para enviar um arquivo por requisição; acrescenta parser e exige cuidado extra com a barreira CSRF por ser tipo safelisted.
- Aumentar RAM, reduzir cinco/15 MiB ou forçar GC: não resolve a retenção e não atende a decisão atual.

### Verificação independente do coordenador

O coordenador leu as receitas e repetiu C sob 256 MiB: `python3 plans/029-upload-lab.py --modes C --limit 256m --repeats 1 --output plans/029-upload-lab-review-results.json`. Os cinco arquivos tiveram hashes corretos; `OOMKilled=false`. O pico cgroup foi 164,1 MiB, com RSS máximo amostrado de 124,2 MiB e `file` de 76,0 MiB. Essa execução adicional não integra as 24 rodadas nem suas medianas.

O plano 029 continua BLOCKED até uma implementação completa desse desenho passar no servidor real sob 256 MiB, incluindo envio da mensagem, Docker/auxiliar, limites, erro/aborto, concorrência e segurança. Nenhum código de produto mudou nesta investigação.
