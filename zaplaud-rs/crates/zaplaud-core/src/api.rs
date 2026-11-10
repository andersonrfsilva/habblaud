//! Cliente HTTP sem replay de POST, sem redirects e com sessão isolada por origem.
//!
//! `request` mantém a ponte JSON de baixo nível. Os métodos tipados em `routes` reutilizam os
//! decoders tolerantes do modelo Kotlin. Respostas JSON ficam limitadas a 8 MiB, PNG a 1 MiB
//! e frames SSE a 8 MiB. `resilient_stream` aplica reconexão sem tarefas ocultas; descartar
//! o fluxo cancela a conexão. Limites e elegibilidade do compositor pertencem às regras.
//! A persistência recebe apenas o cookie remoto normalizado, nunca a senha.

use crate::Error;
use crate::model::ServerEvent;
use reqwest::cookie::{CookieStore, Jar};
use reqwest::header::{ACCEPT, CONTENT_TYPE, COOKIE, RETRY_AFTER, SET_COOKIE};
use serde_json::{Value, json};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex, MutexGuard};
use url::Url;
mod reconnect;
mod routes;
pub use reconnect::*;
pub use routes::*;
const MAX_EVENT_BYTES: usize = 8 * 1024 * 1024;

pub struct UrlPolicy;

impl UrlPolicy {
    /// Aceita apenas origens HTTP(S), sem credenciais, caminho, consulta ou fragmento.
    pub fn origin(input: &str) -> Option<String> {
        let text = input.trim();
        let (scheme, authority) = text.split_once("://")?;
        if !scheme.eq_ignore_ascii_case("http") && !scheme.eq_ignore_ascii_case("https") {
            return None;
        }
        let authority = authority.strip_suffix('/').unwrap_or(authority);
        if authority.is_empty()
            || authority
                .chars()
                .any(|c| c.is_whitespace() || matches!(c, '@' | '%' | '/' | '?' | '#' | '\\'))
            || authority.ends_with(':')
        {
            return None;
        }
        let port = if authority.starts_with('[') {
            authority.split_once(']')?.1
        } else {
            authority.split_once(':').map_or("", |(_, port)| port)
        };
        let port = port.strip_prefix(':').unwrap_or(port);
        if !port.is_empty() && !port.parse::<u16>().is_ok_and(|port| port != 0) {
            return None;
        }
        let url = Url::parse(text).ok()?;
        if url.host_str().is_none_or(str::is_empty)
            || !url.username().is_empty()
            || url.password().is_some()
        {
            return None;
        }
        Some(url.origin().ascii_serialization())
    }
}

/// Persistência opcional; implementações nunca devem registrar valores dos cookies.
pub trait SessionPersistence: Send + Sync {
    fn load(&self, origin: &str) -> Option<String>;
    fn save(&self, origin: &str, cookie: &str);
    fn clear(&self, origin: &str);
}

#[derive(Default)]
struct Session {
    generation: u64,
    cookies: Jar,
}

#[derive(Clone)]
pub struct HabblaudApi {
    origin: String,
    client: reqwest::Client,
    session: Arc<Mutex<Session>>,
    persistence: Option<Arc<dyn SessionPersistence>>,
    cancellation: tokio::sync::watch::Sender<u64>,
}

struct AuthorizedResponse {
    response: reqwest::Response,
    cancellation: tokio::sync::watch::Receiver<u64>,
}
impl std::ops::Deref for AuthorizedResponse {
    type Target = reqwest::Response;
    fn deref(&self) -> &Self::Target {
        &self.response
    }
}
impl std::ops::DerefMut for AuthorizedResponse {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.response
    }
}

impl std::fmt::Debug for HabblaudApi {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("HabblaudApi")
            .field("origin", &self.origin)
            .field("session", &"[REDACTED]")
            .finish()
    }
}

impl HabblaudApi {
    pub fn new(origin: &str) -> Result<Self, Error> {
        Self::build(origin, None)
    }
    pub fn with_persistence(
        origin: &str,
        persistence: Arc<dyn SessionPersistence>,
    ) -> Result<Self, Error> {
        Self::build(origin, Some(persistence))
    }
    fn build(
        origin: &str,
        persistence: Option<Arc<dyn SessionPersistence>>,
    ) -> Result<Self, Error> {
        let origin = UrlPolicy::origin(origin).ok_or(Error::InvalidOrigin)?;
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .retry(reqwest::retry::never())
            .connect_timeout(std::time::Duration::from_secs(10))
            .read_timeout(std::time::Duration::from_secs(45))
            .build()?;
        let session = Session::default();
        if let Some(value) = persistence.as_ref().and_then(|p| p.load(&origin)) {
            let url = Url::parse(&origin).map_err(|_| Error::InvalidOrigin)?;
            session.cookies.add_cookie_str(&value, &url);
        }
        Ok(Self {
            origin,
            client,
            session: Arc::new(Mutex::new(session)),
            persistence,
            cancellation: tokio::sync::watch::channel(0).0,
        })
    }
    pub fn origin(&self) -> &str {
        &self.origin
    }
    fn session(&self) -> MutexGuard<'_, Session> {
        self.session
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }
    pub fn cancel_calls(&self) {
        self.cancellation.send_modify(|v| *v = v.wrapping_add(1));
    }
    pub fn clear_session(&self) {
        let mut session = self.session();
        session.generation = session.generation.wrapping_add(1);
        session.cookies = Jar::default();
        if let Some(p) = &self.persistence {
            p.clear(&self.origin);
        }
        self.cancel_calls();
    }
    fn url(&self, path: &str) -> Result<Url, Error> {
        if !path.starts_with("/api/") || path.contains('#') || path.contains('\\') {
            return Err(Error::InvalidPath);
        }
        let url = Url::parse(&format!("{}{path}", self.origin)).map_err(|_| Error::InvalidPath)?;
        if url.origin().ascii_serialization() != self.origin {
            return Err(Error::InvalidPath);
        }
        Ok(url)
    }
    async fn send(
        &self,
        mut request: reqwest::RequestBuilder,
        url: &Url,
        retry: bool,
    ) -> Result<AuthorizedResponse, Error> {
        let mut cancellation = self.cancellation.subscribe();
        let generation = {
            let session = self.session();
            if let Some(cookie) = session.cookies.cookies(url) {
                request = request.header(COOKIE, cookie);
            }
            session.generation
        };
        let fallback = if retry { request.try_clone() } else { None };
        let result = tokio::select! {
            biased;
            _ = cancellation.changed() => return Err(Error::SessionEnded),
            result = request.send() => result,
        };
        let response = match (result, fallback) {
            (Err(_), Some(request)) => tokio::select! {
                biased;
                _ = cancellation.changed() => return Err(Error::SessionEnded),
                result = request.send() => result?,
            },
            (result, _) => result?,
        };
        if cancellation.has_changed().unwrap_or(true) {
            return Err(Error::SessionEnded);
        }
        {
            let mut session = self.session();
            if generation != session.generation {
                return Err(Error::SessionEnded);
            }
            // Só o cookie remoto pertence à sessão; nunca aceite cookies auxiliares do servidor.
            if let Some(mut cookie) = response
                .headers()
                .get_all(SET_COOKIE)
                .iter()
                .filter_map(|header| header.to_str().ok())
                .filter_map(|header| cookie::Cookie::parse(header.to_owned()).ok())
                .rfind(|cookie| cookie.name() == "habblaud_remote")
            {
                let host = url.host_str().unwrap_or("");
                let domain = cookie.domain().unwrap_or(host).trim_start_matches('.');
                if host.eq_ignore_ascii_case(domain)
                    || host
                        .to_ascii_lowercase()
                        .ends_with(&format!(".{}", domain.to_ascii_lowercase()))
                {
                    let now = cookie::time::OffsetDateTime::now_utc();
                    if let Some(age) = cookie.max_age() {
                        cookie.set_expires(now.checked_add(age).unwrap_or(now));
                        cookie.set_max_age(None);
                    }
                    if cookie
                        .expires_datetime()
                        .is_some_and(|expiry| expiry <= now)
                    {
                        session.cookies = Jar::default();
                        session.generation = session.generation.wrapping_add(1);
                        if let Some(p) = &self.persistence {
                            p.clear(&self.origin);
                        }
                    } else {
                        if cookie.domain().is_none() {
                            cookie.set_domain(host.to_owned());
                        }
                        if cookie.path().is_none() {
                            cookie.set_path(
                                url.path()
                                    .rsplit_once('/')
                                    .map_or(
                                        "/",
                                        |(parent, _)| if parent.is_empty() { "/" } else { parent },
                                    )
                                    .to_owned(),
                            );
                        }
                        let value = cookie.to_string();
                        if let Some(p) = &self.persistence {
                            p.save(&self.origin, &value);
                        }
                        session.cookies.add_cookie_str(&value, url);
                    }
                }
            }
        }
        if response.status().as_u16() == 401 {
            let mut session = self.session();
            if generation == session.generation {
                session.generation = session.generation.wrapping_add(1);
                session.cookies = Jar::default();
                if let Some(p) = &self.persistence {
                    p.clear(&self.origin);
                }
                self.cancel_calls();
            }
            return Err(Error::Unauthorized);
        }
        if response.status().is_redirection() {
            return Err(Error::Redirect(response.status().as_u16()));
        }
        Ok(AuthorizedResponse {
            response,
            cancellation,
        })
    }
    async fn bytes(
        &self,
        mut response: AuthorizedResponse,
        limit: usize,
    ) -> Result<Vec<u8>, Error> {
        let mut bytes = Vec::new();
        let mut cancellation = response.cancellation.clone();
        loop {
            let chunk = tokio::select! {
                biased;
                _ = cancellation.changed() => return Err(Error::SessionEnded),
                chunk = response.chunk() => chunk?,
            };
            let Some(chunk) = chunk else {
                return Ok(bytes);
            };
            if chunk.len() > limit.saturating_sub(bytes.len()) {
                return Err(Error::ResponseTooLarge);
            }
            bytes.extend_from_slice(&chunk);
        }
    }
    async fn checked(&self, response: AuthorizedResponse) -> Result<AuthorizedResponse, Error> {
        if response.status().is_success() {
            return Ok(response);
        }
        let status = response.status().as_u16();
        let retry_after = response
            .headers()
            .get(RETRY_AFTER)
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned);
        let body = self.bytes(response, MAX_EVENT_BYTES).await?;
        let value: Value = serde_json::from_slice(&body).unwrap_or(Value::Null);
        Err(Error::Api {
            status,
            retry_after,
            code: crate::model::string(&value, "code").filter(|v| !v.is_empty()),
            attachments_expired: value.get("attachmentsExpired").and_then(Value::as_bool)
                == Some(true),
            detail: crate::model::string(&value, "error")
                .unwrap_or_default()
                .chars()
                .take(500)
                .collect(),
        })
    }
    async fn response(
        &self,
        path: &str,
        body: Option<&Value>,
    ) -> Result<AuthorizedResponse, Error> {
        let url = self.url(path)?;
        let request = match body {
            Some(body) => self
                .client
                .post(url.clone())
                .json(body)
                .header(CONTENT_TYPE, "application/json; charset=utf-8"),
            None => self.client.get(url.clone()),
        }
        .header(ACCEPT, "application/json, text/event-stream");
        self.checked(self.send(request, &url, body.is_none()).await?)
            .await
    }
    pub async fn request(&self, path: &str, body: Option<&Value>) -> Result<String, Error> {
        let bytes = self
            .bytes(self.response(path, body).await?, MAX_EVENT_BYTES)
            .await?;
        Ok(std::str::from_utf8(&bytes)?.to_owned())
    }
    pub async fn login(&self, user: &str, password: &str) -> Result<(), Error> {
        let value: Value = serde_json::from_str(
            &self
                .request(
                    "/api/remote/login",
                    Some(&json!({"user":user,"password":password})),
                )
                .await?,
        )?;
        if value.get("ok").and_then(Value::as_bool) != Some(true) {
            return Err(Error::LoginRefused);
        }
        Ok(())
    }
    /// Apaga a sessão local mesmo quando o servidor não confirma o logout.
    pub async fn logout(&self) -> Result<(), Error> {
        let result = self.request("/api/remote/logout", Some(&json!({}))).await;
        self.clear_session();
        result.map(|_| ())
    }
    pub async fn stream(&self, path: &str) -> Result<EventStream, Error> {
        let generation = self.session().generation;
        let cancellation = self.cancellation.subscribe();
        let url = self.url(path)?;
        let response = self
            .checked(
                self.send(
                    self.client
                        .get(url.clone())
                        .header(ACCEPT, "text/event-stream"),
                    &url,
                    false,
                )
                .await?,
            )
            .await?;
        if !response
            .headers()
            .get(CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .split(';')
            .next()
            .is_some_and(|v| v.trim().eq_ignore_ascii_case("text/event-stream"))
        {
            return Err(Error::InvalidEventStream);
        }
        Ok(EventStream {
            api: self.clone(),
            generation,
            response,
            decoder: SseDecoder::default(),
            pending: VecDeque::new(),
            cancellation,
        })
    }
}

pub struct EventStream {
    api: HabblaudApi,
    generation: u64,
    response: AuthorizedResponse,
    decoder: SseDecoder,
    pending: VecDeque<(String, String)>,
    cancellation: tokio::sync::watch::Receiver<u64>,
}
impl EventStream {
    async fn next_raw(&mut self) -> Result<Option<(String, String)>, Error> {
        loop {
            if self.api.session().generation != self.generation {
                return Err(Error::SessionEnded);
            }
            if let Some(event) = self.pending.pop_front() {
                return Ok(Some(event));
            }
            let chunk = tokio::select! {
                _ = self.cancellation.changed() => return Err(Error::SessionEnded),
                chunk = self.response.chunk() => chunk?,
            };
            match chunk {
                Some(chunk) => self.decoder.push(&chunk, &mut self.pending)?,
                // Sem linha em branco, o último evento está incompleto e não pode ser entregue.
                None => return Ok(None),
            }
        }
    }
    pub async fn next_event(&mut self) -> Result<Option<ServerEvent>, Error> {
        self.next_raw()
            .await?
            .map(|(event, data)| {
                ServerEvent::parse(self.api.origin(), &event, &data).map_err(Error::from)
            })
            .transpose()
    }
}
#[derive(Default)]
struct SseDecoder {
    line: Vec<u8>,
    event: String,
    data: String,
    has_data: bool,
    frame_bytes: usize,
    after_cr: bool,
    first_line: bool,
}

impl SseDecoder {
    fn push(&mut self, bytes: &[u8], output: &mut VecDeque<(String, String)>) -> Result<(), Error> {
        for &byte in bytes {
            if self.after_cr && byte == b'\n' {
                self.after_cr = false;
                continue;
            }
            self.after_cr = byte == b'\r';
            if byte == b'\n' || byte == b'\r' {
                self.finish_line(output)?;
            } else {
                self.frame_bytes += 1;
                if self.frame_bytes > MAX_EVENT_BYTES {
                    return Err(Error::EventTooLarge);
                }
                self.line.push(byte);
            }
        }
        Ok(())
    }

    fn finish_line(&mut self, output: &mut VecDeque<(String, String)>) -> Result<(), Error> {
        let mut line = std::str::from_utf8(&self.line)?;
        if !self.first_line {
            line = line.strip_prefix('\u{feff}').unwrap_or(line);
            self.first_line = true;
        }
        if line.is_empty() {
            if self.has_data {
                self.data.pop();
                output.push_back((
                    if self.event.is_empty() {
                        "message".into()
                    } else {
                        std::mem::take(&mut self.event)
                    },
                    std::mem::take(&mut self.data),
                ));
            }
            self.event.clear();
            self.data.clear();
            self.has_data = false;
            self.frame_bytes = 0;
        } else {
            let (field, value) = line.split_once(':').unwrap_or((line, ""));
            let value = value.strip_prefix(' ').unwrap_or(value);
            match field {
                "event" => {
                    self.event.clear();
                    self.event.push_str(value);
                }
                "data" => {
                    self.has_data = true;
                    self.data.push_str(value);
                    self.data.push('\n');
                }
                _ => {}
            }
        }
        self.line.clear();
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn origins_match_kotlin_policy() {
        assert_eq!(
            UrlPolicy::origin(" HTTPS://EXAMPLE.COM:443/ ").as_deref(),
            Some("https://example.com")
        );
        assert_eq!(
            UrlPolicy::origin("http://[::1]:8080/").as_deref(),
            Some("http://[::1]:8080")
        );
        for origin in [
            "ftp://host",
            "http://user@host",
            "http://host/path",
            "http://host?x",
            "http://host#x",
            "http://host:",
            "http://host:0",
            "http://host:65536",
            "http://ho%73t",
            "http://ho st",
            "http://host//",
        ] {
            assert!(UrlPolicy::origin(origin).is_none(), "{origin}");
        }
    }

    #[test]
    fn multiline_sse_waits_for_blank_line_across_chunks() -> Result<(), Error> {
        let mut decoder = SseDecoder::default();
        let mut output = VecDeque::new();
        decoder.push(
            b"\xef\xbb\xbf: comment\r\nevent: example\r\ndata: first\r",
            &mut output,
        )?;
        decoder.push(b"\ndata: second\r\n", &mut output)?;
        assert!(output.is_empty());
        decoder.push(b"\r\ndata:\n\n", &mut output)?;
        assert_eq!(
            output.pop_front(),
            Some(("example".into(), "first\nsecond".into()))
        );
        assert_eq!(output.pop_front(), Some(("message".into(), "".into())));
        Ok(())
    }

    #[test]
    fn sse_limits_whole_frame_not_only_lines() {
        let mut decoder = SseDecoder::default();
        let mut output = VecDeque::new();
        let line = [b"data: ".as_slice(), &vec![b'x'; 1024], b"\n"].concat();
        let result =
            (0..MAX_EVENT_BYTES / 1024 + 1).try_for_each(|_| decoder.push(&line, &mut output));
        assert!(matches!(result, Err(Error::EventTooLarge)));
        assert!(output.is_empty());
    }
}
