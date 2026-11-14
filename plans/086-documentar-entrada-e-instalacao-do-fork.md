# Plan 086: Fazer a entrada do projeto instalar e explicar o fork correto

> Execute apenas Markdown. Leia o plano inteiro e atualize sua linha no índice ao concluir, salvo integrador.
> **Drift check:** `git diff --stat 8e52027..HEAD -- README.md README.pt-BR.md README.es.md README.fr.md CONTRIBUTING.md docs/zaplaud-desktop.md plans/README.md`.
> Alterações do plano 006 na introdução/privacidade são esperadas. Preserve-as; STOP para mudanças não explicadas.
> A publicação deste lote em `plans/README.md` também é drift esperado, não um motivo para parar.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW — documentação; não muda repositórios remotos ou mecanismo de instalação.
- **Depends on**: `plans/006-readme-chamadas-externas.md` (sequência editorial, arquivos compartilhados)
- **Category**: docs
- **Planned at**: commit `8e52027`, 2026-11-14

## Why this matters

O leitor clona o upstream e depois tenta usar recursos que só existem neste fork. O guia desktop mistura a
instalação Compose descontinuada com o cliente Rust já entregue. A mudança precisa corrigir a orientação sem
renomear o produto, apagar créditos ou alterar o destino do verificador de atualizações por acidente.

## Current state

- `README.pt-BR.md:138`: `git clone https://github.com/marmottajr/habblaud.git`.
- Mesma instrução em `README.md:139`, `README.es.md:141`, `README.fr.md:143`.
- `README.md:8`: “The virtual office for your Claude Code (and Codex) agents.” Não apresenta OMP/ZeroClaw/Antigravity.
- `README.pt-BR.md:368–373` já explica que mensagens protegidas e outros recursos do Zaplaud precisam deste fork.
  Preserve a distinção, sem prometer compatibilidade total com upstream.
- `README.md:394–396` manda executar `git remote set-url origin https://github.com/marmottajr/habblaud.git`
  na migração CodeTown. Não basta corrigir o clone e deixar essa instrução ativa para usuários do fork.
- `CONTRIBUTING.md:7` envia bugs para `https://github.com/marmottajr/habblaud/issues`.
- `README.pt-BR.md:392–423`: “O desktop do Zaplaud é o cliente nativo em Rust”; em seguida fornece
  `./gradlew :desktop:run`, `bash scripts/desktop-install-linux.sh` e “só começa por decisão do dono” para Rust.
- `README.md:246–277` contém a mesma receita Compose e “it starts only on the owner's call”.
- ES/FR não possuem seção nem ocorrência de Zaplaud na base. Inserir a entrada curta de clientes após a
  instalação OMP (`README.es.md:246`, `README.fr.md:249`), antes de abrir o escritório no navegador do celular.
- `docs/zaplaud-desktop.md:14–16`: já define o restante do documento como registro do Compose obsoleto.
- `zaplaud-rs/packaging/README.md:5–15`: pacote por usuário, instalador sem sudo, CI Ubuntu 24.04/glibc 2.39;
  tarball local Arch exige glibc 2.43. `:72–86`: bundle macOS com assinatura ad hoc, sem notarização.
- `package.json:7–11` ainda identifica o upstream. `server/updates/checker.ts:43–56,189–200` usa o repositório
  configurado para a consulta GitHub; não trocar isso em um plano documental.
- `scripts/release.ts:99–110`: release exige branch `main`, árvore limpa e igualdade com `origin/main`;
  `--dry-run` ainda passa pelo fetch/validações. Não execute esse comando como verificação desta tarefa.
- Remotos observados: `origin=https://github.com/andersonrfsilva/habblaud.git`,
  `upstream=https://github.com/marmottajr/habblaud`; branch local auditada `producao`.

Convenção: preservar nome Habblaud e crédito MIT, prosa no idioma do arquivo, links relativos e tabelas existentes.
Exemplo reutilizável: `README.pt-BR.md:368–373` distingue upstream e fork sem desmerecer a origem.
Não crie um segundo manual de build Rust: a referência já é `zaplaud-rs/README.md` e seu guia de distribuição.

## Scope

**In scope:** os quatro `README*.md` da raiz (identidade, instalação, requisitos, links, migração CodeTown,
entrada desktop e sumário), `CONTRIBUTING.md` (destino das contribuições e roteamento para gates por plataforma),
`docs/zaplaud-desktop.md` (clareza da entrada e distinção atual/histórico), `plans/README.md` (linha 086).

**Out of scope:** `package.json`, código, scripts de release/instalação, Docker/Quadlet, LICENSE, dados reais,
execução de comandos de instalação, changelog, tradução de UI, remoção do Compose e reestruturação ampla dos READMEs.
Privacidade pertence ao 006; tabelas de variáveis/API e divergências técnicas pertencem ao 087.

## Commands you will need

Da raiz do clone/worktree:

| Finalidade | Comando | Resultado |
|---|---|---|
| Remotos existentes | `git remote -v` | Distinguir origin do fork e upstream; não modificar |
| Instruções arriscadas | `rg -n -e 'git clone' -e 'git remote set-url' -e 'desktop:run' -e 'desktop-install-linux' -e 'só começa' -e 'only starts' README*.md` | Inventário dos passos que precisam ser corrigidos ou rotulados como históricos |
| Referência atual | `rg -n -e 'install-linux' -e 'glibc' -e 'macOS' -e 'notariza' zaplaud-rs/packaging/README.md` | Contratos atuais sem construir pacotes |
| Links de contribuição | `rg -n -e 'github.com/.*/habblaud/issues' -e 'github.com/.*/habblaud/pulls' CONTRIBUTING.md` | Destino do fork para assuntos do fork |

## Git workflow

- Branch sugerido `advisor/086-entrada-do-fork`; aplicar depois do 006, sem editores paralelos nos READMEs.
- Worktree temporária, se usada, em `/tmp/habblaud-086-docs`.
- Commit sugerido, se autorizado: `docs: apresenta o fork e a instalação atual do Zaplaud`.
- Não fazer merge, push, release, deploy, instalação ou reinício sem nova autorização.

## Steps

### Step 1: Identificar o fork e corrigir a aquisição do código

Nos quatro idiomas, mantenha a marca Habblaud e apresente o fork de `andersonrfsilva/habblaud`, com link/crédito
para `marmottajr/habblaud`. Explique em poucas linhas: OMP, ZeroClaw opt-in, Antigravity pessoal opt-in e clientes
Zaplaud Kotlin/Rust. Não afirmar que todos os runtimes rodam em qualquer combinação de configuração.

Corrija o comando de clone para `git clone https://github.com/andersonrfsilva/habblaud.git`. Corrija também a
orientação de `origin` na migração CodeTown: o origin do fork não deve voltar ao upstream. Preserve links upstream
que são créditos, referências de origem ou explicações da versão upstream. Não faça substituição global de URLs.

**Verify:** execute o bloco abaixo; saída esperada `origem: OK`.

```sh
python3 - <<'PY'
from pathlib import Path
for p in [Path(n) for n in ("README.md", "README.pt-BR.md", "README.es.md", "README.fr.md")]:
    t = p.read_text()
    assert "git clone https://github.com/andersonrfsilva/habblaud.git" in t, p
    assert "git clone https://github.com/marmottajr/habblaud.git" not in t, p
    assert "git remote set-url origin https://github.com/marmottajr/habblaud.git" not in t, p
    assert "https://github.com/marmottajr/habblaud" in t.split("\n## ", 1)[0], (p, "credito na apresentacao")
print("origem: OK")
PY
```

### Step 2: Separar instalação corrente, histórico e requisitos opcionais

No requisito Claude Code, delimite que ele é necessário para sua integração, não para toda instalação do fork.
Não remover versões mínimas nem dizer que nenhum CLI é necessário para funcionalidades que o exigem.

A entrada desktop corrente dos quatro READMEs deve apontar diretamente para `zaplaud-rs/README.md` e
`zaplaud-rs/packaging/README.md`. Retire dali receitas Compose e a frase de Rust ainda planejado; mantenha um link
para o registro histórico, que já vive em `docs/zaplaud-desktop.md`. Não apagar o registro nem o código Compose.
Não copie valores de memória fora do contexto de medição, nem prometa pacote universal/assinado no macOS.
Preserve instruções Android/iPadOS e suas pendências específicas.

Em ES/FR, criar respectivamente `### Clientes Zaplaud` e `### Clients Zaplaud` no ponto definido acima, com dois
parágrafos curtos: desktop Rust e os dois links de instalação; Android/iPadOS Kotlin e os links
`README.pt-BR.md#aplicativo-android-zaplaud` e `README.pt-BR.md#aplicativo-ipados-zaplaud-validação-pendente`,
rotulados como guia detalhado em português. No EN, incluir o mesmo roteamento Android/iPadOS após o desktop.
No PT, as seções completas já existem. Não duplicar receitas extensas de build móvel em três idiomas;
o 088 traduz os resumos funcionais e limites, mantendo esses guias detalhados como referência.

O guia histórico já registra `desktop:run`, pacotes e instalador Compose em `docs/zaplaud-desktop.md:30–45`.
Não copiar essas receitas novamente. As receitas tar/icns e notas JVM/ProGuard retiradas da raiz não precisam
ganhar outra cópia; o histórico Git preserva a instalação descontinuada. Não apagar conteúdo histórico já no guia.

**Verify:** `rg -n 'zaplaud-rs/(README.md|packaging/README.md)' README*.md` deve localizar ambos os destinos
em cada um dos quatro READMEs. O comando `rg -n 'desktop:run|desktop-install-linux|só começa por decisão|starts only on the owner' README*.md`
não deve encontrar receitas Compose nem a frase obsoleta inglesa. Nos READMEs EN/ES/FR, conferir também os dois
links de plataforma móvel definidos acima; links externos ao arquivo preservam as âncoras do documento PT.

### Step 3: Corrigir o caminho de contribuição sem alterar o atualizador

Direcione problemas e PRs específicos do fork para `andersonrfsilva/habblaud`. Contribuições para upstream
continuam possíveis, mas precisam de rótulo explícito. Em `CONTRIBUTING.md`, mantenha gates Node existentes;
para clientes nativos, use links às referências existentes, sem fingir que `npm test` cobre Kotlin ou Rust.

No desenvolvimento/publicação dos READMEs, rotule o procedimento `npm run release` como fluxo de release
baseado em `main`, diferente da implantação pessoal/tenants. Explique que o aviso de versão usa atualmente o
upstream definido no manifesto; não anuncie que passou a acompanhar releases do fork. Não executar release nem
alterar o manifesto neste corte. Para implantação, apontar às referências existentes, sem inventar um comando único.

**Verify:** `rg -n 'andersonrfsilva/habblaud/issues' CONTRIBUTING.md` encontra o destino do fork;
`rg -n 'marmottajr/habblaud|"repository"' package.json` continua exibindo os metadados originais.
Reexecute `origem: OK` e confira que os quatro READMEs delimitam release/atualizações sem anunciar mudança de runtime.

## Test plan

Não instalar dependências, compilar clientes nem chamar GitHub/produção. Usar a verificação Python e os greps
acima, depois conferir destinos relativos dos links novos. Não adicionar testes de redação ao repositório.
A renderização pode ser revisada no preview Markdown local, sem iniciar o servidor de aplicação.

## Done criteria

- [ ] `origem: OK`; os quatro READMEs usam o fork e preservam os créditos do upstream.
- [ ] Nenhuma receita corrente da raiz instala Compose ou apresenta Rust como trabalho não iniciado.
- [ ] Os quatro idiomas apontam às duas referências Rust existentes.
- [ ] Contribuição e release estão separados de deploy; atualizador não é descrito como corrigido sem mudança de código.
- [ ] Privacidade do 006 preservada; somente Scope alterado; linha 086 atualizada.

## STOP conditions

- Resolver um link exigiria mudar `package.json`, atualizador, release ou script de deploy.
- A distribuição Rust mudou, o instalador exige outra forma ou uma instrução exigiria dados privados do operador.
- Há conflito com a privacidade já corrigida no 006 ou alterações de outra execução ainda não integradas.
- Gate documental continua falhando após duas correções; não trocar nomes de recursos para calar o gate.

## Maintenance notes

Separar crédito de origem de comando operacional evita redirecionar usuários ao projeto errado. Preservar o guia
histórico não significa recomendar o cliente descontinuado. Mudança futura de política de releases é outro corte,
com manifesto, atualizador e testes próprios; esta tarefa apenas descreve o comportamento observado.
