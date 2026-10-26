// Fila de teclas de UM pty: junta o que é digitado enquanto um POST está em andamento e envia em ordem. Cada ligação
// da tela (PtyPane.attach) cria a sua; assim, o que foi digitado num pty nunca é esvaziado por um envio de outro.
// Puro (sem DOM nem xterm): testado em ptyinput.test.ts.

export type PostInput = (id: string, data: string) => Promise<unknown>;

export class PtyInput {
  private pending = '';
  private sending: Promise<void> = Promise.resolve();

  constructor(
    readonly id: string,
    private post: PostInput,
  ) {}

  /** Enfileira teclas; os envios saem um por vez, na ordem, e só com o texto deste pty. */
  push(data: string): void {
    if (!data) return;
    this.pending += data;
    this.sending = this.sending.then(async () => {
      if (!this.pending) return;
      const chunk = this.pending;
      this.pending = '';
      // Falha de rede não pode travar a fila: a próxima tecla tenta de novo.
      await this.post(this.id, chunk).catch(() => undefined);
    });
  }
}
