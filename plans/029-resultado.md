# Resultado do plano 029 — 2026-10-27

## Entrega

- Habblaud: worktree `../habblaud-029`, branch `advisor/029-anexos-no-prompt`, commit `c19afb0`.
- Tenants: worktree `../omp-tenant-029`, branch `advisor/029-anexos-isolados`, commit `0ed95c9`.
- Nenhum merge, push, release, alteração de produção ou de tenant ativo.
- Estado canônico: linha 029 em `plans/README.md`.

Implementado seletor de arquivos, paste/drop, miniaturas, remoção e rascunho por agente na caixa compartilhada entre terminal e gaveta. Cinco anexos de até 15 MiB por mensagem; limite de oito preservado no Novo agente. Reenvio reutiliza IDs válidos. Texto colado tem precedência sobre imagem simultânea.

Anexos persistidos em raiz central por instalação, configurável por `HABBLAUD_ATTACHMENTS_DIR`, com UUID exclusivo por lote. Nenhuma escrita no projeto ou limpeza por idade. A mensagem só recebe os caminhos após gravação; preparação não vaza ao inbox/Codex nem atravessa mudança de sessão. Controles e asides não ficam bloqueados pela gravação.

Tenant usa backing físico `t/anexos`, fora do HOME controlado pelo usuário; servidor monta RW e omp RO no mesmo destino absoluto `h/.habblaud/attachments`. Origem preparada com privilégio restrito e descritores `O_NOFOLLOW`; destino preparado sem privilégio. O estado privado do Habblaud não entra no agente.

## Revisão

Revisão independente de servidor/host e segurança do tenant. Dois achados corrigidos antes da entrega:

1. Writer não muda permissões da raiz preexistente configurada. Reproduzido antes: raiz 0750 virava 0700; regressão permanente agora protege raiz 0750 e lote 0700/arquivo 0600.
2. STOP/control e perguntas paralelas passam enquanto um anexo aguarda gravação; textos comuns mantêm ordem. Regressão com writer pendente protege ambos.

Adaptação de escopo autorizada: `omp-tenant/tests/test_release.py` atualiza somente a fixture do `sync` para executar o preparo real sem sudo. Nenhuma outra funcionalidade upstream das PRs #121–123 foi incorporada.

## Verificações observadas pelo coordenador

| Gate | Resultado |
|---|---|
| `npm run typecheck` | passou no commit final |
| `npm test -- --maxWorkers=2` | 187 arquivos; 2288 testes passaram, 2 ignorados |
| `npm run i18n:check -- --strict` | 3199/3199 EN, ES e FR |
| `npm run build` | passou; aviso existente de chunks grandes |
| `python3 -m unittest discover -s tests` | 75 testes passaram |

Evidência dos gates finais: `artifact://119`. Na rodada do executor com concorrência padrão, `server/world/uplink.test.ts:361` falhou com `esperando: reconectou`. Esse arquivo não foi alterado. O teste usa relógio manual junto com socket real e timeout de parede de três segundos; a causa exata não foi demonstrada. A suíte inteira posterior, com dois workers, passou sem exclusões adicionais nem alteração do teste.

### Browser e sessão OMP reais

Instância isolada na porta 4757, HOME/estado/anexos/sessões descartáveis, sem PTY, World ou produção. OMP real 18.12.6 em modo RPC, extensão da worktree, modelo `openai-codex/gpt-5.6-sol`.

- Seletor nativo do browser anexou `nota ação.txt` e `azul.png`; envio sem texto.
- Prompt recebido continha dois caminhos absolutos na mesma pasta UUID.
- Agente executou `read` em ambos os arquivos e respondeu `UPLOAD029-a798e1e7-86d5-4b31-b7b3-59ca7d73e65c — azul.`
- Cancelar seletor preservou seleção; input foi resetado; remover e selecionar novamente o mesmo arquivo funcionou.
- Layout verificado em 1280×900 e 390×844; clipe visível e anexos dentro do terminal.
- Eventos DOM de paste/drop exercitaram os handlers reais: imagem interceptada, arquivo arrastado anexado, clipboard misto de texto/imagem não interceptado.
- Nenhum erro de página observado na consulta efetuada durante o envio.

Capturas: `/tmp/omp-sshots-15b6994c2c3526ad.webp` (desktop antes do envio), `/tmp/omp-sshots-15b69a3cdcf526b0.webp` (terminal móvel com resposta real).

### Podman real descartável

Coordenador repetiu a receita `local://tenant029-smoke.py`; pod único `plan029-smoke-776af311df`, removido no finally. Imagem local Node, `keep-id`, UID 1000, sem rede/capacidades, rootfs RO.

- Writer gravou 5 × 15 MiB: 78.643.200 bytes; diretório 0700 e arquivos 0600.
- Reader leu cinco arquivos com SHA-256 iguais; escrita retornou `EROFS`.
- Estado privado, outro tenant e symlinks para ambos retornaram `ENOENT`.
- Reinício dos dois contêineres preservou caminhos e hashes.
- Executor também provou preparo com ownership de produção: pai 1000:1001 0750; raiz/destino 1001:1001 0700.

### Bloqueador de publicação: app completo sob 256 MiB

Executor rodou `local://tenant029-app-memory.py`: servidor Habblaud completo, Node 24.22.1, rootfs RO, mesmo UID, HOME RO e estado/anexos RW; `--memory=256m --memory-swap=256m`. Cliente em outro contêiner compartilhou somente o namespace de rede, sem publicar porta no host.

- Primeira prova: uploads 0–2 de 15 MiB retornaram 201; upload 3 encerrou conexão. `OOMKilled=true`, exit 137, nenhum arquivo persistido.
- Reprodução do executor do app: cinco uploads retornaram 201, mas o processo morreu durante gravação. O instante do pico varia; não é um limite determinístico de três arquivos.
- Tentativa de correção eliminou Buffers duplicados em `readJson`: os cinco uploads passaram, mas o servidor ainda sofreu OOM durante o quinto arquivo. A tentativa não satisfez o aceite e não integra a candidata validada `c19afb0`.

Não reduzimos cinco anexos/15 MiB nem aumentamos os 256 MiB. A implementação está disponível para revisão, mas o aceite completo e a publicação em tenants estão bloqueados. É necessário replanejar o pipeline de upload para memória limitada e provar novamente o mesmo cenário, antes de marcar o plano DONE.

## Limites da prova e publicação

- Não houve uso do clipboard nativo do sistema nem gesto físico de arrastar: esses caminhos foram exercitados por eventos DOM no browser real. O seletor e o envio ao modelo foram reais.
- Não repetimos no browser a troca de agente durante upload nem envio durante turno ocupado; regressões automatizadas cobrem captura de lote por agente, preparação e sessão trocada. O primeiro envio real foi com agente ocioso.
- O teste do servidor completo sob 256 MiB falhou por OOM. A prova de mount com Node escritor/leitor não substitui esse aceite.
- Nenhum launcher adicional bwrap do dono foi localizado; não afirmar sua compatibilidade. O binário direto e os mounts Podman por tenant foram os cenários verificados.
- Persistência sem exclusão por idade implica crescimento de disco; limpeza é manual e não foi adicionada quota persistente.
- Serviços de smoke e abas foram encerrados. Publicar exige integrar ambos os commits e executar o fluxo de release/sync do operador; nada está no ar por esta execução.
