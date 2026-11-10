#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("Origem do servidor inválida")]
    InvalidOrigin,
    #[error("Caminho da API inválido")]
    InvalidPath,
    #[error("Redirecionamento recusado (HTTP {0})")]
    Redirect(u16),
    #[error("HTTP {status}: {detail}")]
    Api {
        status: u16,
        retry_after: Option<String>,
        code: Option<String>,
        attachments_expired: bool,
        detail: String,
    },
    #[error("Login recusado")]
    LoginRefused,
    #[error("Sessão encerrada")]
    SessionEnded,
    #[error("Evento SSE excede 8 MiB")]
    EventTooLarge,
    #[error("Evento SSE não contém UTF-8 válido")]
    InvalidUtf8(#[from] std::str::Utf8Error),
    #[error("Resposta não é um stream SSE")]
    InvalidEventStream,
    #[error("Falha de rede: {0}")]
    Network(#[from] reqwest::Error),
    #[error("JSON inválido: {0}")]
    Json(#[from] serde_json::Error),
}
