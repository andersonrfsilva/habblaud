# Plan 087: Reconciliar configuração, API e limites documentados com o código atual

> Alterar apenas os documentos listados em Scope. Não criar endpoints, opções, compatibilidade ou testes de redação.
> Atualizar a linha 087 do índice quando concluído, salvo integrador.
> **Drift check:** `git diff --stat 8e52027..HEAD -- README.md README.pt-BR.md server/README.md zaplaud-rs/README.md docs/zaplaud-paridade.md plans/README.md`.
> Mudanças do 006/086 são esperadas nos READMEs. Compare os contratos abaixo, preserve esses predecessores
> e pare diante de drift não explicado; linhas deslocadas não são, sozinhas, drift de contrato.
> A publicação deste lote no índice de planos também é drift esperado.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW — somente documentação; não se deve inventar garantias ausentes no código.
- **Depends on**: `plans/086-documentar-entrada-e-instalacao-do-fork.md`
- **Category**: docs
- **Planned at**: commit `8e52027`, 2026-11-14

## Why this matters

A referência de API diz que `model` é exclusivo do ZeroClaw, apesar de o elenco já abrir Antigravity por modelo.
As tabelas principais não permitem descobrir configuração ZeroClaw/resumo/cota, e o README Rust descreve uma
limitação Kotlin já corrigida. Contratos completos ficam no README do servidor; a entrada do projeto deve
resumir e apontar para ele, sem manter explicações extensas duplicadas.

## Current state

- `server/README.md:321`: `GET /api/cast/models` descreve catálogo ZeroClaw ou `{provider:'omp',models:[]}`.
- `server/README.md:327`: start aceita `{mode: 'new' | 'resume', prompt?, model?}`, mas diz
  “`model` (só ZeroClaw, só conversa nova)”. `:330` herda essa descrição no `/api/cast/new`.
- `server/cast/launcher.ts:54–56`:

  ```ts
  return { ...(this.opts.zeroclaw?.models() ?? { provider: 'omp' as const, models: [] as [] }),
    ...(this.opts.antigravity ? { agyModels: this.opts.antigravity.agyModels() } : {}) };
  ```

- `server/README.md:65–100` já descreve modelo `agy/…`, descoberta após primeira sessão, retomada pelo espelho,
  seleção de runtime, troca de ferramenta sem turno e preservação da identidade `novo-…`.
- `server/cast/launcher.ts:144–166`: conversa nova escolhe Antigravity por modelo; ZeroClaw tem seu catálogo;
  o caminho OMP de `startNewInternal` não passa `body.model` diretamente ao job como um override genérico.
  Não documentar que qualquer modelo arbitrário seleciona qualquer runtime em qualquer endpoint.
- `server/cast/launcher.ts:107,133,525–529`: em `start`, sem ZeroClaw, um personagem com modelo `agy/…` aceita
  um modelo OMP explícito válido (`ROLE_VALUE`) em conversa nova; o job recebe esse modelo. Em `/api/cast/new`,
  sem ZeroClaw, um `model` não-`agy` é recusado com 422. Não confundir os dois endpoints.
- `README.pt-BR.md:1091–1125` e `README.md` na seção Configuration: tabelas não incluem ZeroClaw,
  resumo HTTP ou cota. `HABBLAUD_CAST_FOLDER` está no texto de elenco, não na tabela.
- `server/config.ts:23–39`: resumo exige URL, modelo e tokenFile quando qualquer variável é preenchida;
  AUTH é opcional, padrão `x-api-key`. URL base sem `/v1/messages`, query, fragmento ou credenciais.
- `server/config.ts:282–326`: ZeroClaw só HTTP loopback; catálogo/default obrigatórios; NAMED opcional;
  cota exige URL HTTP(S) e tokenFile absoluto; CAST_FOLDER preenchida deve ser absoluta.
- `server/README.md:138–181`: catálogo, agentes fixos, persona e Telegram já possuem referência detalhada.
- `server/http/app.ts:482–497`: `GET /api/access` também devolve `files`; tabela em `server/README.md:341` omite.
  `:450–477`: health acrescenta `antigravity: true` quando a integração existe na instalação, sem filtro
  por autorização da conexão; tabela em `:338` não menciona. Já `files` em access é filtrado pela conexão.
- `zaplaud-rs/README.md:33`: “Kotlin atual, que ainda aceita prazos sem esse limite”. Está obsoleto:
  `android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/UiPlatform.android.kt:47–51` limita 24 h,
  inclusive data HTTP; a variante Apple em `iosMain/.../MainViewController.kt:95–100` aplica o mesmo teto.
- `docs/zaplaud-paridade.md:36` já diz limite de 24 h nos dois clientes: essa linha está correta.
  `:29` ainda diz “pasta da conversa aberta vem primeiro”; a chamada nova agora usa a pasta padrão do servidor.
- `server/README.md:327` é um bom exemplo de texto que separa conversa nova de retomada. Preserve a distinção,
  corrigindo somente as regras desatualizadas; mantenha campos de protocolo verbatim, inclusive `agyModels`.

## Scope

**In scope:** `README.md`, `README.pt-BR.md` (tabelas de configuração/API e links às integrações),
`server/README.md` (referência contratual), `zaplaud-rs/README.md` (Retry-After e links contratuais),
`docs/zaplaud-paridade.md` (observação de pasta, sem recontar verificação), `plans/README.md` (linha 087).

**Out of scope:** READMEs ES/FR (088 traduz depois), CHANGELOG (088), código, testes, manifests, scripts,
credenciais reais, mudanças de autenticação, Docker/Quadlet, novos formatos de protocolo e política de paridade.
A seção Verificação da paridade será reconciliada pelo 088, não sobrescrita aqui.

## Commands you will need

Da raiz do clone/worktree:

| Finalidade | Comando | Resultado |
|---|---|---|
| API real | `rg -n -e 'agyModels' -e 'useAntigravity' -e 'startNewInternal' server/cast/launcher.ts` | Locais de catálogo e seleção de runtime |
| Configuração real | `rg -n -e 'HABBLAUD_ZEROCLAW' -e 'HABBLAUD_SUMMARY' -e 'HABBLAUD_QUOTA' -e 'HABBLAUD_CAST_' server/config.ts server/cast/zeroclaw.ts` | Nomes e validação sem valores privados |
| Campos de capacidade | `rg -n -e 'files:' -e 'summaryHttp:' -e 'antigravity:' server/http/app.ts` | Distinguir instalação e autorização por conexão |
| Limite Kotlin | `rg -n -e '86_400' -e 'maximum' -e 'retryAfterDeadline' android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/UiPlatform.android.kt android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/MainViewController.kt` | Teto de 24 horas nos dois adaptadores |

Não executar builds, Gradle, cargo, instalação de dependências ou pedidos aos runtimes para corrigir Markdown.

## Git workflow

- Branch sugerido `advisor/087-contratos-documentados`, após 086, worktree temporária em `/tmp`.
- Commit sugerido, se autorizado: `docs: alinha configuração e API aos runtimes atuais`.
- Não alterar fonte para satisfazer um exemplo. Sem merge, push, deploy, reinício ou uso de conta real.

## Steps

### Step 1: Reconciliar as tabelas de API com os contratos existentes

No README do servidor e nas tabelas EN/PT, documentar `agyModels` opcional no catálogo da instalação,
`agy/default`, descoberta posterior e ausência do campo quando desativado. Nas tabelas EN/PT, criar as linhas
ausentes `GET /api/cast/models` e `POST /api/cast/new`; acrescentar `model?` à linha de start.
Descrever `model` explicitamente: só em conversa nova; `agy/…` seleciona Antigravity; no ZeroClaw precisa do
catálogo; sem ZeroClaw, `start` aceita modelo OMP explícito válido apenas quando o modelo salvo do personagem
é `agy/…`. No `/api/cast/new`, sem ZeroClaw, um modelo não-`agy` retorna 422. Preservar defaults e regras de
retomada; não anunciar override OMP genérico. Conferir essas regras em `validModel` e nos dois caminhos do launcher.

Acrescentar `named` condicional no catálogo ZeroClaw onde a forma do retorno é descrita, com link a persona/Telegram.
Completar `/api/access` com `files` conforme autorização desta conexão. Em `/api/health`, documentar
`antigravity` opcional como integração presente na instalação: o campo não é filtrado por conexão.
Não remover `expectedSessionId`, `messageSessionGuard`, estados assíncronos nem causas de 403/409/422/503.
Manter a explicação extensa apenas em `server/README.md`; as tabelas EN/PT resumem e apontam para as seções corretas.

**Verify:** `rg -n 'agyModels|named|antigravity|files' server/README.md README.md README.pt-BR.md` encontra os
campos nas referências pertinentes; execute também o bloco abaixo, esperado `api: OK`.

```sh
python3 - <<'PY'
from pathlib import Path
for name in ("server/README.md", "README.md", "README.pt-BR.md"):
    rows = [line for line in Path(name).read_text().splitlines() if line.startswith("|")]
    catalog = "\n".join(line for line in rows if "GET /api/cast/models" in line)
    assert "agyModels" in catalog, (name, "catalogo")
    access = "\n".join(line for line in rows if "GET /api/access" in line)
    assert "files" in access, (name, "access")
assert "`model` (só ZeroClaw, só conversa nova)" not in Path("server/README.md").read_text()
print("api: OK")
PY
```

### Step 2: Tornar a configuração descoberta nos READMEs EN/PT

Adicionar às duas tabelas as 12 variáveis: quatro ZEROCLAW, quatro SUMMARY, duas QUOTA, CAST_MAX e CAST_FOLDER.
Use nomes técnicos exatos e descreva defaults/dependências:

- ZEROCLAW_URL ausente sem catálogo desliga a fonte; AGENTS, DEFAULT_AGENT ou NAMED sem URL impedem inicialização.
  Com URL, HTTP loopback isolado, sem token neste desenho; AGENTS e DEFAULT_AGENT obrigatórios, NAMED opcional.
  Não inventar aliases nem configurar gateway público; remeter ao catálogo validado do servidor.
- SUMMARY_URL/MODEL/TOKEN_FILE obrigatórios quando configurado; AUTH opcional (`x-api-key` padrão, `bearer` alternativo).
  URL é base, não `/v1/messages`. Ausência total mantém RPC Tiny; falha HTTP não cai silenciosamente para RPC.
- QUOTA_URL e QUOTA_TOKEN_FILE em conjunto; tokenFile absoluto; consulta autenticada, sem exigir OMP.
  Esse modo desliga `omp usage` e não faz fallback, mesmo se token/endpoint falhar (`server/README.md:752–755`).
- CAST_MAX 1–50 ou sem limite quando ausente/inválido conforme configuração existente; CAST_FOLDER absoluta,
  ausente usa home. Nova conversa respeita o padrão; retomar e trocar ferramenta mantêm a pasta da conversa.

Inclua links para `server/README.md` nas entradas de resumo/cota/ZeroClaw e explique que configurar uma variável
no host não a injeta automaticamente no container. `docker-compose.yml:30–59` não repassa essas 12 variáveis;
o exemplo Quadlet tem apenas CAST_FOLDER comentada em `deploy/podman/habblaud.container:32`. Explicitar que o
operador precisa de configuração/mount apropriados; não dizer que `.env` sozinho já habilita esses recursos.
Preserve avisos de tokens/fluxo de dados produzidos no 006.

**Verify:** bloco abaixo retorna `configuracao: OK`.

```sh
python3 - <<'PY'
from pathlib import Path
names = ["HABBLAUD_ZEROCLAW_" + s for s in ("URL", "AGENTS", "DEFAULT_AGENT", "NAMED")]
names += ["HABBLAUD_SUMMARY_" + s for s in ("URL", "MODEL", "TOKEN_FILE", "AUTH")]
names += ["HABBLAUD_QUOTA_URL", "HABBLAUD_QUOTA_TOKEN_FILE", "HABBLAUD_CAST_MAX", "HABBLAUD_CAST_FOLDER"]
for filename in ("README.md", "README.pt-BR.md"):
    text = Path(filename).read_text()
    for name in names:
        assert any(line.startswith("| `" + name + "`") for line in text.splitlines()), (filename, name)
    assert "server/README.md" in text, filename
print("configuracao: OK")
PY
```

### Step 3: Corrigir divergências que não existem mais

No README Rust, substituir a comparação obsoleta por descrição factual de `Retry-After` com teto de 24 horas
compartilhado pelo Kotlin. Não mexer no código nem prometer outros tipos de paridade sem conferência.
Na linha de chamar personagem da matriz, remover a precedência da pasta da conversa aberta: criação segue
CAST_FOLDER/home; retomada segue a conversa. A tabela pode apontar para `server/README.md` sem duplicar o protocolo.

**Verify:** `rg -n 'Kotlin atual, que ainda aceita|cliente Kotlin não foi alterado|pasta da conversa aberta vem primeiro' zaplaud-rs/README.md docs/zaplaud-paridade.md`
retorna zero ocorrências (exit 1); `rg -n '24 h|24 horas|24h' zaplaud-rs/README.md docs/zaplaud-paridade.md`
continua encontrando o limite correto. Reexecutar os dois blocos Python e atualizar a linha 087 do índice.

## Test plan

Somente verificações documentais descartáveis, sem testes permanentes de palavras. Comparar formas JSON e
semântica com código e referência do servidor; os greps não substituem essa conferência. Não tocar nos testes de
contrato existentes para fazê-los coincidir com a documentação.

## Done criteria

- [ ] `api: OK` e `configuracao: OK`.
- [ ] Catálogo descreve campos condicionais e start/new não promete override inexistente.
- [ ] ZeroClaw, resumo, cota e pasta padrão descobertos nas tabelas EN/PT, com defaults e limites corretos.
- [ ] A frase antiga de Retry-After e a precedência antiga de pasta não aparecem nos dois arquivos de Step 3.
- [ ] Autorização e garantias de entrega preservadas; nenhum código/segredo alterado; linha 087 atualizada.

## STOP conditions

- Não for possível reconciliar runtime/modelo/retomada sem alterar implementação: registrar a divergência, não inventar contrato.
- O gateway exigir token ou endpoint diferente do desenho loopback documentado.
- Uma variável exigir valor privado ou configuração de produção para ser descrita.
- Drift não explicado por 006/086 ou falha repetida de gate documental.

## Maintenance notes

`server/README.md` é a referência operacional detalhada; os READMEs apontam a ela. ES/FR serão alinhados pelo 088.
Não converter uma limitação documental em tarefa de código sem pedido. Distinguir campos de instalação, como
`health.antigravity`, dos campos autorizados por conexão, como `access.files`, mensagens e terminal.
