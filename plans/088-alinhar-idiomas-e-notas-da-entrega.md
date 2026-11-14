# Plan 088: Alinhar os idiomas e consolidar as notas da entrega documental

> Execute depois de 006, 086 e 087. O resultado desses planos é o texto-base a traduzir, não sua versão antiga.
> Atualize somente a linha 088 do índice ao concluir, salvo integrador.
> **Drift check:** `git diff --stat 8e52027..HEAD -- README.md README.pt-BR.md README.es.md README.fr.md CHANGELOG.md docs/zaplaud-paridade.md docs/zaplaud-desktop.md zaplaud-rs/README.md plans/README.md`.
> As alterações documentais dos predecessores são esperadas. Reancore os trechos; STOP para mudanças de contrato
> ou novos merges de produto não reconciliados. Não exigir igualdade literal com uma redação anterior ao 006/086/087.
> A publicação deste lote no índice de planos também é drift esperado.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW — documentação; preservar ressalvas evita transformar instalação em validação inexistente.
- **Depends on**: `plans/006-readme-chamadas-externas.md`, `plans/086-documentar-entrada-e-instalacao-do-fork.md`, `plans/087-reconciliar-configuracao-e-contratos.md`
- **Category**: docs
- **Planned at**: commit `8e52027`, 2026-11-14

## Why this matters

ES/FR não apresentam Antigravity nem pasta padrão do elenco. “Não lançado” reúne estados intermediários que se
contradizem, e as referências ainda dizem que a janela Rust não foi aberta, apesar de reteste posterior no Mac.
A consolidação deve descrever o estado entregue sem apagar o histórico publicado ou inventar cobertura de testes.

## Current state

- `README.es.md:623–640`: “Llamar abre una sesión nueva en la carpeta personal”; não há Antigravity, `agy/…`
  ou `HABBLAUD_CAST_FOLDER` em todo o arquivo na base auditada.
- `README.fr.md:623–643`: mesma lacuna e promessa de abrir no dossier personnel.
- ES/FR não têm nenhuma entrada Zaplaud na base; o 086 criará resumos com links aos guias PT.
  O 088 deve traduzir o comportamento e os limites desses resumos, não duplicar as receitas móveis completas.
- `README.pt-BR.md:882–892,1134–1180` e `README.md:632–641,881–924`: base mais recente para elenco/Antigravity,
  mas precisam receber primeiro as correções contratuais do 087 e de entrada/privacidade do 086/006.
- `CHANGELOG.md:15–16`: pasta padrão configurável; `:165–168`: “sempre na pasta pessoal”.
- `CHANGELOG.md:34–44`: “Paridade Rust pendente no plano 082” e “nem controles próprios nos clientes Zaplaud”,
  apesar de modelos/STOP/conversa nova já estarem documentados e integrados.
- `CHANGELOG.md:179–187`: já registra identidade Novo, espelho vazio, cartão de permissão, texto humano limpo,
  `additionalDirectories` e acompanhamento pela página. Preservar essas entregas, consolidando duplicatas.
- `docs/zaplaud-paridade.md:35,45,49–59` e `zaplaud-rs/README.md:101`: ainda usam avisos genéricos de validação
  manual/sem abrir janela, sem distinguir a revisão original do reteste posterior.
- `docs/zaplaud-desktop.md:11`: tabela atual diz faltar verificar “Wayland, macOS, chaveiro e uso real”.
  Não declarar tudo verificado: houve medições/gates diferentes para cada cenário, e a fase 6 do 074 permanece aberta.
- **Delta posterior à primeira auditoria:** `8e52027` altera apenas duas linhas do índice; não muda código.
  A linha 085, linha 109 de `git show 8e52027:plans/README.md`, registra o reteste real, não apenas build:

  > Reteste no Mac do dono, com o app real e o servidor de produção [...] mesmo `cast`; a tela seguiu sozinha
  > para a conversa nova [...] a primeira mensagem não pediu permissão, a fala apareceu limpa [...].

  O mesmo registro informa que página, Android e o novo cartão de permissão não foram conferidos na tela;
  o botão Novo não respondeu ao acionamento por acessibilidade, resultado inconclusivo, não bug provado.
  M35 atualizado não significa comportamento Android verificado. A ressalva de iOS recente não compilado vem
  de `docs/zaplaud-paridade.md:35,49`, não desse reteste. A linha 085 chama o cliente apenas de “app real”.
  [INFERÊNCIA] É o Zaplaud Rust, pois o desktop corrente é Rust e página/Android ficaram fora do reteste;
  não converter essa inferência em evidência nominal de build/runtime. Referir-se ao “app no Mac” no texto final.
- `docs/zaplaud-paridade.md:41–59`: acompanhamento por mesmo `cast`/origem, até 90 segundos; termina com sucessor,
  erro de comando ou escolha de outra conversa. Kotlin também cancela ao fechar. Sem `cast`, não há acompanhamento.

Convenções: prosa natural no idioma do arquivo, identificadores técnicos inalterados, tabelas com links relativos.
Exemplo de limite corretamente delimitado: `zaplaud-rs/packaging/README.md:15` separa artefato CI e local;
`:86` separa assinatura ad hoc de distribuição notarizada. Não transformar limites em slogans de compatibilidade.
O índice de planos é a fonte de estado; documentos de produto devem explicar comportamento e apontar para a evidência.

## Scope

**In scope:** os quatro READMEs da raiz (paridade editorial e seções afetadas), `CHANGELOG.md` (somente “Não lançado”),
`docs/zaplaud-paridade.md` (comportamento e referências de verificação), `docs/zaplaud-desktop.md` (entrada atual e
limites; não reescrever o registro histórico Compose), `zaplaud-rs/README.md` (notas recentes de validação),
`plans/README.md` (somente a linha 088).

**Out of scope:** versões já publicadas do CHANGELOG, código, strings de UI, manifests, screenshots, scripts,
planos históricos e seus resultados, mudança de status de 033/041/054–062/074, executar testes ou publicar qualquer coisa.
Não remover limitação apenas porque a funcionalidade está integrada ou o app foi instalado.

## Commands you will need

Da raiz do clone/worktree:

| Finalidade | Comando | Resultado |
|---|---|---|
| Delta de revisão | `git show --format=fuller 8e52027 -- plans/README.md` | Registro de publicação/reteste de 084/085 |
| Traduções faltantes | `rg -n -e 'Antigravity' -e 'agy/' -e 'HABBLAUD_CAST_FOLDER' README.es.md README.fr.md` | Após correção, cobre ambos os arquivos |
| Contradições | `rg -n -e 'Paridade Rust pendente' -e 'nem controles próprios' -e 'sempre na pasta pessoal' CHANGELOG.md` | Após correção, nenhum estado corrente contraditório em Não lançado |
| Evidência recente | `rg -n '^. 08[45] ' plans/README.md` | Status/evidência atuais sem alterar outras linhas |

Não usar builds ou testes de UI como requisito para corrigir uma afirmação documental. Esta tarefa não autoriza
aparelhos, emuladores, VMs, navegador automatizado, acessibilidade do Mac ou inferência paga.

## Git workflow

- Branch sugerido `advisor/088-consolidacao-documental`, depois dos três predecessores, worktree temporária em `/tmp`.
- Commit sugerido, se autorizado: `docs: alinha idiomas e consolida as notas do fork`.
- Nada de merge, push, release, deploy, reinício ou atualização de dispositivos.

## Steps

### Step 1: Traduzir o contrato final, não os rascunhos anteriores

Alinhar ES/FR com o EN/PT resultante de 006/086/087: identidade do fork, instalação, desktop Rust, configuração,
API, catálogo ZeroClaw, resumo/cota HTTP, agentes fixos/persona/Telegram, `agy/…`, modelos OMP distintos de `agy/`,
retomada, pasta padrão e avisos de envio de dados. Preservar a privacidade já traduzida pelo 006 e os links de
instalação corrigidos pelo 086; não duplicar em seções novas o que esses predecessores resolveram.

Nos quatro idiomas, explicar as mudanças 084/085 em uma passagem curta de uso do elenco/Antigravity: identidade
Novo persistente, nova conversa vazia, primeira mensagem com contexto sem poluir a fala humana e acompanhamento
automático do mesmo contato por até 90 segundos. Não afirmar que trocar ferramenta transfere sessão nativa nem
que toda ferramenta recebe permissão automática. Reaproveitar links à referência do servidor para detalhes.

Conferir sumários e âncoras de seções novas; nomes de variável, provider, campo JSON e endpoint não são traduzidos.
Se o README principal já aponta a uma referência técnica, traduzir o resumo e preservar o link em vez de copiar
o manual completo. Não atribuir quatro idiomas à UI nativa apenas porque os READMEs têm quatro idiomas.

**Verify:** bloco abaixo retorna `idiomas: OK`. Os nomes técnicos são verificáveis, não substituem revisão semântica.

```sh
python3 - <<'PY'
from pathlib import Path
required = ("Antigravity", "agy/", "HABBLAUD_CAST_FOLDER", "HABBLAUD_ZEROCLAW_URL",
            "HABBLAUD_SUMMARY_URL", "HABBLAUD_QUOTA_URL", "agyModels")
for name in ("README.md", "README.pt-BR.md", "README.es.md", "README.fr.md"):
    text = Path(name).read_text()
    for term in required:
        assert term in text, (name, term)
    assert "zaplaud-rs/README.md" in text, name
    assert "git clone https://github.com/andersonrfsilva/habblaud.git" in text, name
print("idiomas: OK")
PY
```

### Step 2: Consolidar somente “Não lançado”

Reescrever os bullets intermediários como estado final: modelos/STOP/conversa nova/retomada nos clientes Kotlin
com suas ressalvas e Rust, pasta padrão configurável com fallback home, recursos ZeroClaw e migração desktop
para Rust. Manter as correções 084/085; descrever acompanhamento Kotlin/Rust além da página, sem inventar testes.

Retirar “Paridade Rust pendente no plano 082”, a negação geral de controles nos clientes e o “sempre” em home.
As entregas Compose são histórico de desenvolvimento anterior à substituição; consolidar em nota de substituição,
sem recomendar seu instalador como caminho corrente. Não apagar uma mudança visível ao usuário apenas por ser antiga.
Preservar a distinção entre anexos arbitrários ainda não suportados no Antigravity e o arquivo de contexto do handoff.

Não mudar versão, datas de releases, links de versões publicadas nem executar `npm run release`.

**Verify:** bloco abaixo retorna `notas: OK` e confirma que o conteúdo de versões publicadas não foi alterado.

```sh
python3 - <<'PY'
import re, subprocess
from pathlib import Path
old = subprocess.check_output(["git", "show", "8e52027:CHANGELOG.md"], text=True)
new = Path("CHANGELOG.md").read_text()
def parts(text):
    first_release = re.search(r"^## \[\d", text, re.M)
    assert first_release, "Nenhuma versao publicada encontrada"
    return text[:first_release.start()], text[first_release.start():]
unreleased, published = parts(new)
assert published == parts(old)[1], "Versoes publicadas foram alteradas"
for obsolete in ("Paridade Rust pendente no plano 082", "nem controles próprios nos clientes Zaplaud",
                 "sempre na pasta pessoal"):
    assert obsolete not in unreleased, obsolete
for feature in ("HABBLAUD_CAST_FOLDER", "Antigravity", "ZeroClaw", "Rust", "90 segundos"):
    assert feature in unreleased, feature
print("notas: OK")
PY
```

### Step 3: Reconciliar verificação por cenário sem criar um segundo índice

Nos guias de paridade/Rust/entrada desktop, separar contrato atual e evidência de validação. Qualificar as frases
de revisão sem janela do Rust/desktop (`docs/zaplaud-paridade.md:59`, `zaplaud-rs/README.md:101`) com link datado ao
registro 085 em `plans/README.md`. Preservar os avisos Kotlin/Android de ausência de teste em aparelho/emulador,
que não foram invalidados pelo reteste Mac. O reteste cobre aquele fluxo, não todos os recursos.
Não atualizar as linhas históricas 080–082/074 no índice por dedução.

Página e Android continuam sem validação visual desse fluxo registrada; novo cartão de permissão não foi
observado; botão Novo via acessibilidade é inconclusivo; atualização do M35 não prova teste de interface.
Não declarar novos gates iOS passados. Não transformar ausência de pedido de permissão de handoff em teste do cartão.
Nos documentos, usar link à fonte única de evidência em vez de copiar contagens de testes e histórico de deploy.
Não eliminar observações de Wayland/chaveiro sem evidência específica do cenário.

**Verify:** `rg -n 'plans/README.md|085|2026-11-14|Mac|Android' docs/zaplaud-paridade.md docs/zaplaud-desktop.md zaplaud-rs/README.md`
mostra referência ao registro 085 e limites por cenário nos três guias. Resultado esperado: a frase absoluta
“Essa mudança foi verificada com clippy, testes do núcleo e compilação release, sem abrir janela.” não aparece
mais como estado atual na paridade; a ausência de validação visual Android/página e o resultado inconclusivo
por acessibilidade continuam explicitados ou apontados no registro canônico 085. Revisar contra a base fixada;
nenhuma plataforma ganha gate por inferência. Depois execute o gate de links abaixo.

### Step 4: Conferir os links locais do conjunto documental e encerrar

Execute da raiz. O gate considera Markdown fora dos planos/arquivos de instrução; verifica destinos de arquivos,
não URLs externas nem todos os slugs de headings. Para títulos criados ou renomeados, conferir também as âncoras
locais e os sumários manualmente no preview Markdown. Não iniciar a aplicação.

```sh
python3 - <<'PY'
import re, subprocess
from pathlib import Path
from urllib.parse import unquote
names = subprocess.check_output(["git", "ls-files", "*.md"], text=True).splitlines()
checked = 0
for name in names:
    if name.startswith("plans/") or Path(name).name in {"AGENTS.md", "CLAUDE.md"}:
        continue
    p = Path(name)
    for number, line in enumerate(p.read_text().splitlines(), 1):
        for target in re.findall(r'!?\[[^\]]*\]\(([^\s)]+)(?:\s+[^)]*)?\)', line):
            if target.startswith(("#", "http:", "https:", "mailto:", "data:")):
                continue
            local = unquote(target.split("#", 1)[0].split("?", 1)[0])
            if local:
                checked += 1
                assert (p.parent / local).exists(), (name, number, target)
print(f"links locais: OK ({checked} destinos)")
PY
```

**Verify:** os três blocos retornam `idiomas: OK`, `notas: OK`, `links locais: OK (...)`; repetir os gates dos
planos 006/086/087 sem reimplementar seus critérios. Atualizar somente a linha 088 do índice.

## Test plan

Sem teste permanente, rebuild ou execução de app. Verificação descartável de conteúdo/links, comparação imutável
do histórico publicado e revisão semântica por idioma. Não alegar testes de UI com base na execução desses blocos.

## Done criteria

- [ ] Três blocos do plano e gates dos predecessores passam.
- [ ] Os quatro idiomas descrevem os mesmos recursos, condições de habilitação e riscos.
- [ ] “Não lançado” é coerente com o código integrado e conserva as entregas visíveis; versões publicadas idênticas.
- [ ] Reteste Mac 085 reconhecido sem generalizar para página/Android/iOS, cartão novo, bandeja ou Wayland.
- [ ] Sem status duplicado, segredo, screenshots novos ou mudanças fora de Scope; linha 088 atualizada.

## STOP conditions

- O contrato muda durante a tradução ou os predecessores ainda não foram integrados.
- Um idioma promete mais do que a evidência do servidor ou de verificação permite.
- Seria necessário atualizar runtime/testes, executar release ou acessar um dispositivo para concluir esta tarefa.
- As notas publicadas diferem da base ou um gate continua falhando após duas correções.

## Maintenance notes

Mudanças de produto devem atualizar primeiro a referência contratual e o texto-base, depois os resumos traduzidos.
O changelog da versão ainda não lançada descreve o resultado final; o log Git conserva os estados intermediários.
Uma instalação bem-sucedida, um build e um reteste manual são evidências diferentes e nunca devem virar sinônimos.
