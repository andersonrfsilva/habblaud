use std::collections::VecDeque;
use std::sync::{Arc, Mutex, MutexGuard};

use reqwest::cookie::{CookieStore, Jar};
use reqwest::header::{ACCEPT, CONTENT_TYPE, COOKIE, RETRY_AFTER, SET_COOKIE};
use serde_json::{Value, json};
use url::Url;

use crate::Error;
use crate::model::ServerEvent;

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
}

impl HabblaudApi {
    pub fn new(origin: &str) -> Result<Self, Error> {
        let origin = UrlPolicy::origin(origin).ok_or(Error::InvalidOrigin)?;
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .build()?;
        Ok(Self {
            origin,
            client,
            session: Arc::default(),
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

    pub fn clear_session(&self) {
        let mut session = self.session();
        session.generation = session.generation.wrapping_add(1);
        session.cookies = Jar::default();
    }

    async fn response(&self, path: &str, body: Option<&Value>) -> Result<reqwest::Response, Error> {
        if !path.starts_with("/api/") || path.contains('#') || path.contains('\\') {
            return Err(Error::InvalidPath);
        }
        let url = Url::parse(&format!("{}{path}", self.origin)).map_err(|_| Error::InvalidPath)?;
        if url.origin().ascii_serialization() != self.origin {
            return Err(Error::InvalidPath);
        }
        let mut request = match body {
            Some(body) => self.client.post(url.clone()).json(body),
            None => self.client.get(url.clone()),
        };
        let generation = {
            let session = self.session();
            if let Some(cookie) = session.cookies.cookies(&url) {
                request = request.header(COOKIE, cookie);
            }
            session.generation
        };
        let response = request
            .header(ACCEPT, "application/json, text/event-stream")
            .send()
            .await?;
        {
            let mut session = self.session();
            if generation != session.generation {
                return Err(Error::SessionEnded);
            }
            // Só o cookie remoto pertence à sessão; nunca aceite cookies auxiliares do servidor.
            for header in response.headers().get_all(SET_COOKIE) {
                if let Ok(value) = header.to_str()
                    && value
                        .split_once('=')
                        .is_some_and(|(name, _)| name.trim() == "habblaud_remote")
                {
                    session.cookies.add_cookie_str(value, &url);
                }
            }
            if response.status().as_u16() == 401 {
                session.generation = session.generation.wrapping_add(1);
                session.cookies = Jar::default();
            }
        }
        if response.status().is_redirection() {
            return Err(Error::Redirect(response.status().as_u16()));
        }
        if !response.status().is_success() {
            let status = response.status().as_u16();
            let retry_after = response
                .headers()
                .get(RETRY_AFTER)
                .and_then(|v| v.to_str().ok())
                .map(str::to_owned);
            let body = response.text().await?;
            let value: Value = serde_json::from_str(&body).unwrap_or(Value::Null);
            return Err(Error::Api {
                status,
                retry_after,
                code: value
                    .get("code")
                    .and_then(Value::as_str)
                    .filter(|v| !v.is_empty())
                    .map(str::to_owned),
                attachments_expired: value.get("attachmentsExpired").and_then(Value::as_bool)
                    == Some(true),
                detail: value
                    .get("error")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .chars()
                    .take(500)
                    .collect(),
            });
        }
        Ok(response)
    }

    pub async fn request(&self, path: &str, body: Option<&Value>) -> Result<String, Error> {
        Ok(self.response(path, body).await?.text().await?)
    }

    pub async fn login(&self, user: &str, password: &str) -> Result<(), Error> {
        let value: Value = serde_json::from_str(
            &self
                .request(
                    "/api/remote/login",
                    Some(&json!({ "user": user, "password": password })),
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
        let response = self.response(path, None).await?;
        let content_type = response
            .headers()
            .get(CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        if !content_type
            .split(';')
            .next()
            .is_some_and(|value| value.trim().eq_ignore_ascii_case("text/event-stream"))
        {
            return Err(Error::InvalidEventStream);
        }
        Ok(EventStream {
            api: self.clone(),
            generation,
            response,
            decoder: SseDecoder::default(),
            pending: VecDeque::new(),
        })
    }
}

pub struct EventStream {
    api: HabblaudApi,
    generation: u64,
    response: reqwest::Response,
    decoder: SseDecoder,
    pending: VecDeque<(String, String)>,
}

impl EventStream {
    pub async fn next_event(&mut self) -> Result<Option<ServerEvent>, Error> {
        loop {
            if self.api.session().generation != self.generation {
                return Err(Error::SessionEnded);
            }
            if let Some((event, data)) = self.pending.pop_front() {
                return Ok(Some(ServerEvent::parse(self.api.origin(), &event, &data)?));
            }
            match self.response.chunk().await? {
                Some(chunk) => self.decoder.push(&chunk, &mut self.pending)?,
                // Sem linha em branco, o último evento está incompleto e não pode ser entregue.
                None => return Ok(None),
            }
        }
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
