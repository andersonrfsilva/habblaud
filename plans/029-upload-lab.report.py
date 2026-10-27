#!/usr/bin/env python3
"""Render the raw upload measurements without rerunning experiments."""
import json
import pathlib
import statistics
ROOT=pathlib.Path(__file__).resolve().parent
FILES=['029-upload-lab-results.json','029-upload-lab-256-results.json','029-upload-lab-warm-a-results.json','029-upload-lab-isolated-a-results.json']
sets={name:json.loads((ROOT/name).read_text())['results'] for name in FILES}
primary=sets[FILES[3]]+[r for r in sets[FILES[0]] if r['mode']!='A']
bounded=sets[FILES[1]]
allrows=[('Principal',r) for r in primary]+[('256 MiB',r) for r in bounded]+[('Inicial A',r) for r in sets[FILES[0]] if r['mode']=='A']+[('Auxiliar A',r) for r in sets[FILES[2]]]
M=1024**2
def maxfield(r,k): return max(s[k] for s in r['server']['samples'])/M
def median(mode,field): return statistics.median(maxfield(r,field) for r in primary if r['mode']==mode)
lines=['''# Laboratório quantitativo de upload — plano 029

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
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|''']
for mode in 'ABCD':
    rs=[r for r in primary if r['mode']==mode]
    vals=[median(mode,k) for k in ['peak','rss','heapUsed','external','arrayBuffers','anon','file']]
    lines.append('|'+mode+'|'+'|'.join(f'{v:.1f}' for v in vals)+f'|{statistics.median(sum(u["ms"] for u in r["upload"]) for r in rs):.1f}|{statistics.median(r["commitMs"] for r in rs):.1f}|')
a=median('A','peak'); c=median('C','peak')
lines.append(f'\nDelta mediano de pico cgroup C versus A: {a-c:.1f} MiB ({100*(a-c)/a:.1f}% menor). Esse delta pertence ao laboratório, não ao app completo.\n')
lines.append('## Todas as execuções — máximos e tempos\n\n|Série|Modo|Rep.|Limite|Pico cgroup|RSS|heap|external|arrays|anon|file|Upload 0|Upload 1|Upload 2|Upload 3|Upload 4|Commit + hashes|Duração servidor|Drains cliente|Hashes|\n|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|')
for group,r in allrows:
    vals=[maxfield(r,k) for k in ['peak','rss','heapUsed','external','arrayBuffers','anon','file']]
    vals += [u['ms'] for u in r['upload']]+[r['commitMs'],r['server']['samples'][-1]['ms']]
    lines.append(f'|{group}|{r["mode"]}|{r["repeat"]}|{r["limit"]}|'+'|'.join(f'{v:.1f}' for v in vals)+f'|{sum(u["drains"] for u in r["upload"])}|{r["verified"]}|')
lines.append('''
Wire por upload: A/D enviam 20.971.585 bytes (20 MiB + 65 bytes JSON); B/C enviam 15.728.640 bytes. Cada lote binário evita aproximadamente 25 MiB de tráfego base64. Os tempos de upload são requisição/resposta; não incluem geração dos bytes nem serialização JSON no cliente. A duração do servidor exclui importação inicial, já concluída antes do início do relógio; inclui espera por inicialização do cliente e pausas de 1 s. Não é throughput puro.

## Todas as fases — antes, recebimento, retenção, commit e depois

`ready` é um checkpoint. `receive max` agrega receive-start, receive, etapas JSON/binárias e decoded. `retained 5` é o checkpoint imediatamente após o quinto upload (A/B ainda com depósito cheio). `precommit` ocorre depois de 1 s, sem GC forçado. `commit max` inclui held, escrita, rename e releitura de hashes. `final` ocorre depois de mais 1 s. `current` e demais colunas em linhas max são máximos independentes; o JSON conserva cada instante original.

|Série|Modo|Rep.|Fase|current|peak kernel acumulado|RSS|heapUsed|external|arrayBuffers|anon|file|shmem|file_dirty|file_writeback|
|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|''')
keys=['current','peak','rss','heapUsed','external','arrayBuffers','anon','file','shmem','file_dirty','file_writeback']
for group,r in allrows:
    samples=r['server']['samples']
    phases=[('ready',[next(s for s in samples if s['phase']=='ready')]),
            ('receive max',[s for s in samples if s['phase'] in ['receive-start','receive','json-chunks','json-string','json-parsed','decoded','binary-chunks','binary-concat']]),
            ('retained 5',[[s for s in samples if s['phase']=='upload-retained'][-1]]),
            ('precommit',[next(s for s in samples if s['phase']=='commit-before')]),
            ('commit max',[s for s in samples if s['phase'] in ['commit','commit-before','store-held','disk-written','commit-after','verify','verified']]),
            ('final',[samples[-1]])]
    for label,ss in phases:
        lines.append(f'|{group}|{r["mode"]}|{r["repeat"]}|{label}|'+'|'.join(f'{max(s[k] for s in ss)/M:.1f}' for k in keys)+'|')
lines.append('''
## Evidência suficiente e trabalho não feito

Critério de parada escolhido: três repetições comparáveis para cada arquitetura, bytes persistidos conferidos, snapshots das fases e delta mensurável; mais três repetições de B/C no limite real isolado. Os 24 ensaios satisfazem esse critério, com rodadas auxiliares preservadas. Os JSON retêm os 120 hashes esperados, cada resposta de tamanho/hash conferida no cliente e 120 hashes relidos em disco conferidos durante commit. Qualquer divergência encerraria a receita com erro.

Não foi implementado transporte de produto, parser incremental, multipart, quota persistente, política de expiração ou mudança de permissões. Não foi executado browser nem app completo nesta rodada. Nenhum teste de concorrência, desconexão, disco cheio, travessia de paths, abuso de quota ou compatibilidade de agentes integra estes números. Esses limites impedem promover o protótipo diretamente a release; não impedem concluir que binário com buffers resolve apenas parte do pico e que streaming para armazenamento persistente elimina a retenção do lote em RAM.
''')
(ROOT/'029-upload-lab-results.md').write_text('\n'.join(lines)+'\n')
