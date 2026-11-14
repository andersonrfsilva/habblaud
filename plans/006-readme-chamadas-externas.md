# Plan 006: Corrigir promessas de privacidade e explicitar os fluxos de dados do fork

> Execute somente a documentação em Scope. Leia o plano inteiro; não altere comportamento para fazê-lo coincidir
> com o texto. Atualize somente a linha 006 de `plans/README.md` ao concluir, salvo se houver integrador.
>
> **Drift check:** `git diff --stat 8e52027..HEAD -- README.md README.pt-BR.md README.es.md README.fr.md plans/README.md`.
> Compare os trechos abaixo com os arquivos atuais. Mudanças inexplicadas exigem STOP; movimentação de linhas não.
> A publicação deste lote de planos muda `plans/README.md` e é drift esperado; não exige parar a execução.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW — documentação; o risco editorial é minimizar uma transferência de dados.
- **Depends on**: none
- **Category**: docs
- **Planned at**: commit `8e52027`, 2026-11-14
- **Revision**: substitui integralmente a especificação de 2026-10-24, commit `4931abc`; estado no índice.

## Why this matters

A abertura ainda promete ausência de chamadas externas e de leitura de credenciais. O fork já possui resumo HTTP,
cota autenticada, configuração Telegram e transferência de conversa entre ferramentas. A versão anterior deste
plano também estava errada: preservava a promessa de que as sessões nunca saem do computador e proibia corrigir
a seção de privacidade. Corrigir apenas a introdução não resolve mais o problema.

## Current state

- `README.pt-BR.md:37–38`: “Nada sai do computador: o Habblaud não lê credenciais nem faz chamadas externas.”
- `README.md:37–39`: “Nothing leaves the computer: Habblaud doesn't read credentials or make external calls.”
- `README.es.md:37–39` e `README.fr.md:38–40`: a mesma promessa em espanhol e francês.
- `README.pt-BR.md:1413–1425`: “A única chamada externa é a verificação de versão nova” e
  “Sem credenciais: o Habblaud não lê senhas nem tokens de acesso.”
- `server/summaries/summarizer.ts:25–49`:

  ```ts
  token = (await readFile(config.tokenFile, 'utf8')).trim();
  const response = await fetch(`${config.url}/v1/messages`, {
  ```

  O corpo contém o texto enviado para resumo. A autenticação usa `x-api-key` ou Bearer.
- `server/accounts/quota.ts:72–76`: lê o arquivo de token e faz GET autenticado na URL configurada.
- `server/cast/named.ts:74–86`: recebe token e peers Telegram e grava o pedido de vínculo em arquivo privado.
  Não copie nenhum valor real; `server/README.md:168–181` documenta o contrato sem expor credenciais.
- `server/README.md:93–123`: contexto cumulativo, mascarado, sem raciocínio; até 150 mil caracteres;
  arquivos privados em `<dataDir>/handoff`, limpeza na inicialização após 7 dias. O arquivo é enviado na primeira
  mensagem humana após a troca, para o provedor da ferramenta de destino. `additionalDirectories` não aprova
  ferramentas automaticamente nem transfere a sessão nativa.
- `server/updates/checker.ts:189–200`: consulta o GitHub; `HABBLAUD_UPDATE_CHECK=0` desliga.
- `server/README.md:250–304`: resumo HTTP configurado não exige Tiny; sem HTTP, permanece RPC do OMP.
- `server/README.md:129–135,159–163,632–640`: ZeroClaw recebe mensagens e persona pelo gateway local;
  a configuração desse gateway determina o provedor final. A conexão loopback não torna a inferência offline.

Convenção: textos completos no idioma do arquivo, linhas de prosa próximas de 120 colunas, links Markdown relativos.
Preserve âncoras de privacidade: `#privacy-and-security`, `#privacidade-e-segurança`,
`#privacidad-y-seguridad`, `#confidentialité-et-sécurité`. Use tabelas `| Recurso | ... |`, como
`docs/zaplaud-paridade.md:20–39`, para uma única representação dos fluxos, sem repetir uma segunda lista.

## Scope

**In scope:** `README.md`, `README.pt-BR.md`, `README.es.md`, `README.fr.md`: introdução e seção de privacidade;
`plans/README.md`: somente linha 006 e eventual evidência de conclusão dessa linha.

**Out of scope:** código, manifests, `.env`, arquivos de tokens, estado real de usuários, `docs/world/privacidade.md`,
outras seções dos READMEs, traduções de UI, CHANGELOG, deploy e qualquer comando com provedor real.
Detalhes do World continuam no documento existente; não reauditar seu servidor.

## Commands you will need

Execute da raiz do clone/worktree.

| Finalidade | Comando | Resultado |
|---|---|---|
| Base | `git show -s --format='%h %s' 8e52027` | `docs(planos): 084 e 085 publicados e retestados no Mac` |
| Fontes | `rg -n -e 'tokenFile' -e '/v1/messages' server/summaries/summarizer.ts server/accounts/quota.ts` | Locais de leitura e envio, sem conteúdo de segredo |
| Contratos | `rg -n -e 'handoff' -e 'additionalDirectories' -e 'telegram' -e 'SUMMARY_' server/README.md` | Referências de fluxo, persistência e configuração |
| Âncoras | `rg -n -e '^## Privacy and security$' -e '^## Privacidade e segurança$' -e '^## Privacidad y seguridad$' -e '^## Confidentialité et sécurité$' README*.md` | Quatro títulos, um por arquivo |

Não rode `npm install`, builds ou testes de runtime: o corte é exclusivamente Markdown e a prova é documental.
Não crie testes permanentes que fixem redação.

## Git workflow

- Branch sugerido: `advisor/006-privacidade-fork`, a partir da `producao` atual.
- Worktree temporária somente em `/tmp/habblaud-006-docs`, nunca em `~/Desenvolvimento`.
- Commit sugerido, se autorizado: `docs: esclarece privacidade e fluxos de dados do fork`.
- Este plano não autoriza merge, push, deploy, reinícios ou acesso a aparelhos.

## Steps

### Step 1: Remover as promessas absolutas nos quatro idiomas

Reescreva o parágrafo de abertura: processamento e armazenamento locais não significam ausência de rede.
Informe que integrações habilitadas podem enviar conteúdo e utilizar tokens configurados pelo operador.
Inclua link para a seção de privacidade do próprio arquivo, sem prometer “suas sessões ficam no computador”.
Não afirme que todas as chamadas são opt-in: a verificação de versão é ligada por padrão.

**Verify:** execute o bloco abaixo; saída esperada `introducoes: OK`.

```sh
python3 - <<'PY'
from pathlib import Path
for name, anchor, forbidden in [
    ("README.md", "#privacy-and-security", "Nothing leaves the computer"),
    ("README.pt-BR.md", "#privacidade-e-segurança", "Nada sai do computador"),
    ("README.es.md", "#privacidad-y-seguridad", "Nada sale de tu computadora"),
    ("README.fr.md", "#confidentialité-et-sécurité", "Rien ne quitte l'ordinateur"),
]:
    intro = Path(name).read_text().split("\n## ", 1)[0]
    assert forbidden not in intro, name
    assert anchor in intro, name
print("introducoes: OK")
PY
```

### Step 2: Descrever os fluxos reais e os limites de cada credencial

Em cada seção de privacidade, substitua “única chamada externa” e “sem credenciais” por afirmações delimitadas.
Não diga que o Habblaud lê credenciais privadas do OMP ou Antigravity: a autenticação dessas ferramentas
continua nelas. Diferencie isso dos tokens explicitamente fornecidos ao Habblaud.

Inclua uma tabela por idioma com recurso, dado/destino, ativação e persistência:

1. GitHub: IP e versão no User-Agent, sem conteúdo da conversa; `HABBLAUD_UPDATE_CHECK=0` desliga.
2. World após login: apenas projeção documentada, link para `docs/world/privacidade.md`; `HABBLAUD_WORLD=0`.
3. OMP usage: o subprocesso consulta provedores com suas próprias credenciais; `HABBLAUD_OMP_USAGE=0`.
4. Resumo: texto da resposta ao endpoint/modelo configurado, por HTTP ou RPC Tiny; no HTTP lê
   `HABBLAUD_SUMMARY_TOKEN_FILE`. Recomendar HTTPS para endpoints externos; o parser aceita HTTP e não impõe
   essa regra ao operador. Não confundir gateway local com destino final. A mesma distinção vale para cota.
5. Cota: GET na URL configurada, lendo `HABBLAUD_QUOTA_TOKEN_FILE`; não envia conversa nesse pedido.
6. Telegram: pedido em `<HABBLAUD_DATA_DIR>/telegram/<alias>.json`, arquivo 0600 e pasta 0700; aplicação pelo host
   pode reiniciar personagens. Não recebe de volta o token pelo GET. Não prometer criptografia em repouso.
7. Troca omp/Antigravity: `<dataDir>/handoff`, envio cumulativo ao provedor de destino na próxima mensagem humana;
   mascaramento não garante anonimização. Raciocínio e eventos de sistema ficam de fora.
8. Inferência normal: Claude Code, Codex, omp e `agy` enviam pedidos aos provedores configurados nas próprias
   ferramentas; Habblaud não torna essas sessões offline. ZeroClaw recebe texto e persona pelo gateway HTTP
   loopback habilitado com `HABBLAUD_ZEROCLAW_URL`, sem token nesse transporte isolado, e o gateway pode usar
   provedores externos com suas próprias credenciais. O Habblaud não lê as credenciais privadas desses runtimes.

Preserve avisos HTTP, poder do usuário local e isolamento de acesso remoto já existentes.

**Verify:** execute o bloco abaixo; saída esperada `fluxos: OK`. Os identificadores técnicos devem aparecer
dentro da seção de privacidade, não apenas nas tabelas de configuração de outra seção.

```sh
python3 - <<'PY'
from pathlib import Path
headings = {
    "README.md": "## Privacy and security",
    "README.pt-BR.md": "## Privacidade e segurança",
    "README.es.md": "## Privacidad y seguridad",
    "README.fr.md": "## Confidentialité et sécurité",
}
for name, heading in headings.items():
    body = Path(name).read_text().split(heading + "\n", 1)[1].split("\n## ", 1)[0]
    for term in ("HABBLAUD_UPDATE_CHECK", "HABBLAUD_WORLD", "HABBLAUD_OMP_USAGE",
                 "HABBLAUD_ZEROCLAW_URL", "HABBLAUD_SUMMARY_TOKEN_FILE", "HABBLAUD_QUOTA_TOKEN_FILE",
                 "telegram/", "handoff", "0600", "0700"):
        assert term in body, (name, term)
print("fluxos: OK")
PY
```

### Step 3: Revisar fronteiras e concluir a linha 006

Leia os quatro textos como operador: cada saída de dados precisa informar destino e condição; nenhuma credencial
real pode estar em exemplos. Registre na linha 006 os comandos documentais executados e o commit, quando existir.
Não marque outros planos como concluídos nem repita o estado em um novo relatório.

**Verify:** repita os dois blocos e o comando de âncoras; todos passam. O diff da execução deve conter somente
os quatro READMEs e a atualização permitida em `plans/README.md`. `git status --short` deve mostrar somente
esses caminhos da execução; arquivos de planejamento já presentes no início não são alterações da execução.

## Test plan

Sem testes novos ou chamadas de rede. Os dois blocos Python são verificações descartáveis de documentação.
Eles cobrem localização e presença; a revisão humana verifica o significado e os limites, não apenas palavras.

## Done criteria

- [ ] `introducoes: OK`, `fluxos: OK` e quatro âncoras válidas.
- [ ] Não há promessa geral de ausência de rede ou de leitura de qualquer token.
- [ ] Credenciais dos CLIs e tokens configurados para Habblaud estão claramente separados.
- [ ] Texto transferido, destino e retenção de handoff estão explicados nos quatro idiomas.
- [ ] Somente arquivos de Scope alterados; linha 006 atualizada.

## STOP conditions

- Surgiu outra integração que lê/envia dados e contradiz a tabela: reportar antes de prometer completude.
- Seria necessário ler tokens, `.env` ou chamar provedores reais para escrever o texto.
- O contrato de retenção, ativação ou autenticação diverge das fontes acima.
- Uma verificação continua falhando após duas tentativas de correção documental.

## Maintenance notes

Qualquer integração nova deve atualizar esta fronteira de privacidade em todos os idiomas. A abertura aponta para
ela; não replique um inventário completo na introdução. A implementação continua sendo a evidência do contrato.
