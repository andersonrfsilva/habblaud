// Limites do cartão de perguntas (AskUserQuestion do Claude Code e `ask` do omp): quanto o escritório mostra e aceita
// de volta. Sem dependências: a extensão do omp (mod/habblaud-omp/habblaud.ts) importa este arquivo.

/** Perguntas num pedido. */
export const ASK_MAX_QUESTIONS = 12;
/** Opções numa pergunta. */
export const ASK_MAX_OPTIONS = 12;
/** Rótulo de uma opção (os agentes do omp escrevem a explicação dentro dele). */
export const ASK_LABEL_MAX = 300;
/** Texto de uma pergunta, com o contexto do formulário. */
export const ASK_QUESTION_MAX = 300;
/** Texto livre ("Outro") mais longo aceito numa resposta. */
export const ANSWER_OTHER_MAX = 2_000;
