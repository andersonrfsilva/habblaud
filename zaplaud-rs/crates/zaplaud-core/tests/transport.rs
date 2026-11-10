use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime};
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpListener;
use zaplaud_core::{
    Error,
    api::{HabblaudApi, SessionPersistence, retry_after, terminal_stream_status},
    model::StreamEvent,
};
type TestResult = Result<(), Box<dyn std::error::Error>>;

async fn server(
    responses: Vec<String>,
) -> Result<(String, tokio::task::JoinHandle<Vec<String>>), std::io::Error> {
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let origin = format!("http://{}", listener.local_addr()?);
    let task = tokio::spawn(async move {
        let mut requests = Vec::new();
        for response in responses {
            let (socket, _) = listener.accept().await.expect("conexão de teste");
            let mut socket = BufReader::new(socket);
            let mut request = String::new();
            loop {
                let mut line = String::new();
                socket.read_line(&mut line).await.expect("linha de teste");
                request.push_str(&line);
                if line == "\r\n" {
                    break;
                }
            }
            if let Some(length) = request.lines().find_map(|line| {
                line.to_ascii_lowercase()
                    .strip_prefix("content-length: ")
                    .and_then(|v| v.parse::<usize>().ok())
            }) {
                let mut body = vec![0; length];
                socket.read_exact(&mut body).await.expect("corpo de teste");
                request.push_str(&String::from_utf8_lossy(&body));
            }
            requests.push(request);
            socket
                .get_mut()
                .write_all(response.as_bytes())
                .await
                .expect("resposta de teste");
        }
        requests
    });
    Ok((origin, task))
}
fn response(status: u16, headers: &str, body: &str) -> String {
    format!(
        "HTTP/1.1 {status} Test\r\nConnection: close\r\nContent-Length: {}\r\n{headers}\r\n{body}",
        body.len()
    )
}

#[tokio::test]
async fn status_mapping_and_redirect_refusal() -> TestResult {
    for status in [302, 401, 403, 404, 409, 413, 429, 500] {
        let (origin, task) = server(vec![response(
            status,
            "Retry-After: 7\r\nLocation: /api/redirected\r\n",
            r#"{"code":9,"error":true,"attachmentsExpired":true}"#,
        )])
        .await?;
        let api = HabblaudApi::new(&origin)?;
        let error = api
            .request("/api/snapshot", None)
            .await
            .err()
            .ok_or("status aceito")?;
        match status {
            302 => assert!(matches!(error, Error::Redirect(302))),
            401 => assert!(matches!(error, Error::Unauthorized)),
            _ => match error {
                Error::Api {
                    status: actual,
                    retry_after,
                    code,
                    detail,
                    attachments_expired,
                } => {
                    assert_eq!(actual, status);
                    assert_eq!(retry_after.as_deref(), Some("7"));
                    assert_eq!(code.as_deref(), Some("9"));
                    assert_eq!(detail, "true");
                    assert!(attachments_expired);
                }
                other => panic!("{other}"),
            },
        }
        assert_eq!(task.await?.len(), 1);
    }
    Ok(())
}
#[tokio::test]
async fn sse_crlf_multiline_and_terminal_eof() -> TestResult {
    let body = ": ping\r\nevent: append\r\ndata: one\r\ndata: two\r\n\r\n";
    let (origin, task) = server(vec![response(
        200,
        "Content-Type: text/event-stream\r\n",
        body,
    )])
    .await?;
    let api = HabblaudApi::new(&origin)?;
    let mut stream = api.resilient_stream("/api/stream")?;
    assert_eq!(stream.next_event().await, Some(StreamEvent::Connected));
    assert_eq!(
        stream.next_event().await,
        Some(StreamEvent::Data {
            event_type: "append".into(),
            data: "one\ntwo".into(),
            first_connection: true
        })
    );
    assert_eq!(
        stream.next_event().await,
        Some(StreamEvent::Reconnecting {
            delay: Duration::from_secs(2)
        })
    );
    drop(stream);
    task.await?;
    Ok(())
}
#[tokio::test]
async fn backoff_warn_retry_after_and_terminal_status() -> TestResult {
    let responses = (0..6)
        .map(|_| response(429, "Retry-After: 3\r\n", "{}"))
        .chain(std::iter::once(response(403, "", "{}")))
        .collect();
    let (origin, task) = server(responses).await?;
    let api = HabblaudApi::new(&origin)?;
    let mut stream = api.resilient_stream("/api/stream")?;
    for (index, delay) in [3, 4, 8, 16, 30, 30].into_iter().enumerate() {
        if index >= 2 {
            assert!(matches!(
                stream.next_event().await,
                Some(StreamEvent::Failure {
                    status: Some(429),
                    ..
                })
            ));
            assert_eq!(
                stream.next_event().await,
                Some(StreamEvent::Warn { status: Some(429) })
            );
        }
        assert_eq!(
            stream.next_event().await,
            Some(StreamEvent::Reconnecting {
                delay: Duration::from_secs(delay)
            })
        );
        tokio::time::pause();
        tokio::time::advance(Duration::from_secs(delay)).await;
        tokio::time::resume();
    }
    assert!(matches!(
        stream.next_event().await,
        Some(StreamEvent::Failure {
            status: Some(403),
            ..
        })
    ));
    assert_eq!(stream.next_event().await, None);
    task.await?;
    Ok(())
}
#[tokio::test]
async fn connected_resets_failures_and_first_connection() -> TestResult {
    let sse = response(200, "Content-Type: text/event-stream\r\n", "data: yes\n\n");
    let (origin, task) = server(vec![
        response(500, "", "{}"),
        response(500, "", "{}"),
        sse.clone(),
        sse,
        response(404, "", "{}"),
    ])
    .await?;
    let api = HabblaudApi::new(&origin)?;
    let mut stream = api.snapshot_stream()?;
    for seconds in [2, 4] {
        assert_eq!(
            stream.next_event().await,
            Some(StreamEvent::Reconnecting {
                delay: Duration::from_secs(seconds)
            })
        );
        tokio::time::pause();
        tokio::time::advance(Duration::from_secs(seconds)).await;
        tokio::time::resume();
    }
    for first_connection in [true, false] {
        assert_eq!(stream.next_event().await, Some(StreamEvent::Connected));
        assert!(
            matches!(stream.next_event().await,Some(StreamEvent::Data{first_connection: actual,..}) if actual == first_connection)
        );
        assert_eq!(
            stream.next_event().await,
            Some(StreamEvent::Reconnecting {
                delay: Duration::from_secs(2)
            })
        );
        tokio::time::pause();
        tokio::time::advance(Duration::from_secs(2)).await;
        tokio::time::resume();
    }
    assert!(matches!(
        stream.next_event().await,
        Some(StreamEvent::Failure {
            status: Some(404),
            ..
        })
    ));
    task.await?;
    Ok(())
}
#[test]
fn retry_after_and_terminal_policy() {
    let now = SystemTime::UNIX_EPOCH + Duration::from_secs(1_000);
    for (input, seconds) in [
        (None, 0),
        (Some("bad"), 0),
        (Some("-2"), 0),
        (Some("12"), 12),
        (Some("999999"), 86_400),
        (Some("Thu, 01 Jan 1970 00:20:00 GMT"), 200),
        (Some("Thu, 01 Jan 1970 00:00:00 GMT"), 0),
    ] {
        assert_eq!(retry_after(input, now), Duration::from_secs(seconds));
    }
    for status in [-1, 301, 399, 401, 403, 404] {
        assert!(terminal_stream_status(Some(status)));
    }
    for status in [408, 409, 413, 429, 500] {
        assert!(!terminal_stream_status(Some(status)));
    }
    assert!(!terminal_stream_status(None));
}
#[derive(Default)]
struct Persistence(Mutex<Option<String>>);
impl SessionPersistence for Persistence {
    fn load(&self, _: &str) -> Option<String> {
        self.0.lock().expect("mutex teste").clone()
    }
    fn save(&self, _: &str, value: &str) {
        *self.0.lock().expect("mutex teste") = Some(value.into());
    }
    fn clear(&self, _: &str) {
        *self.0.lock().expect("mutex teste") = None;
    }
}
#[tokio::test]
async fn persistence_cookie_scope_and_redaction() -> TestResult {
    let (origin, task) = server(vec![
        response(
            200,
            "Set-Cookie: habblaud_remote=fictional; Path=/; Max-Age=60\r\n",
            r#"{"ok":true}"#,
        ),
        response(200, "", "{}"),
        response(
            200,
            "Set-Cookie: habblaud_remote=; Path=/; Max-Age=0\r\n",
            "{}",
        ),
    ])
    .await?;
    let persistence = Arc::new(Persistence::default());
    let api = HabblaudApi::with_persistence(&origin, persistence.clone())?;
    api.login("android-test", "android-test").await?;
    let cookie = persistence.load(&origin).ok_or("cookie não salvo")?;
    assert!(cookie.contains("Expires="));
    assert!(!cookie.contains("Max-Age="));
    assert!(!format!("{api:?}").contains("fictional"));
    let restored = HabblaudApi::with_persistence(&origin, persistence.clone())?;
    restored.access().await?;
    restored.logout().await?;
    assert!(persistence.load(&origin).is_none());
    let requests = task.await?;
    assert!(requests[1].contains("cookie: habblaud_remote=fictional"));
    Ok(())
}
#[tokio::test]
async fn invalid_paths_never_make_network_requests() -> TestResult {
    let api = HabblaudApi::new("http://127.0.0.1:1")?;
    for path in [
        "https://example.com/api/snapshot",
        "//example.com/api/snapshot",
        "/other",
        "/api/\\\\example.com",
        "/api/snapshot#fragment",
    ] {
        assert!(matches!(
            api.request(path, None).await,
            Err(Error::InvalidPath)
        ));
    }
    Ok(())
}
#[tokio::test]
async fn cancellation_interrupts_waiting_handshake() -> TestResult {
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let api = HabblaudApi::new(&format!("http://{}", listener.local_addr()?))?;
    let caller = api.clone();
    let pending = tokio::spawn(async move { caller.stream("/api/stream").await });
    let (_socket, _) = listener.accept().await?;
    api.cancel_calls();
    assert!(matches!(pending.await?, Err(Error::SessionEnded)));
    Ok(())
}

#[tokio::test]
async fn bounded_http_sse_and_avatar_bodies() -> TestResult {
    let huge = "x".repeat(8 * 1024 * 1024 + 1);
    let (origin, task) = server(vec![
        response(200, "", &huge),
        response(
            200,
            "Content-Type: text/event-stream\r\n",
            &format!(": {huge}\n\n"),
        ),
        response(
            200,
            "Content-Type: image/png\r\n",
            &"x".repeat(1024 * 1024 + 1),
        ),
        response(200, "Content-Type: text/plain\r\n", "not png"),
    ])
    .await?;
    let api = HabblaudApi::new(&origin)?;
    assert!(matches!(
        api.request("/api/snapshot", None).await,
        Err(Error::ResponseTooLarge)
    ));
    let mut stream = api.snapshot_stream()?;
    assert_eq!(stream.next_event().await, Some(StreamEvent::Connected));
    assert!(matches!(
        stream.next_event().await,
        Some(StreamEvent::Failure {
            status: Some(-1),
            ..
        })
    ));
    assert_eq!(stream.next_event().await, None);
    assert!(api.avatar_path_bytes("/api/avatar.png").await?.is_none());
    assert!(api.avatar_path_bytes("/api/avatar.png").await?.is_none());
    task.await?;
    Ok(())
}

#[tokio::test]
async fn uploads_are_binary_incremental_and_validate_known_size() -> TestResult {
    let bytes = vec![b'z'; 24_000];
    let progress = Arc::new(Mutex::new(Vec::new()));
    let observed = progress.clone();
    let (origin, task) = server(vec![response(201, "", r#"{"id":"upload-test"}"#)]).await?;
    let api = HabblaudApi::new(&origin)?;
    assert_eq!(
        api.upload(
            "ação +.txt",
            "text/plain",
            std::io::Cursor::new(bytes.clone()),
            Some(bytes.len() as u64),
            move |count| observed.lock().expect("progresso de teste").push(count)
        )
        .await?,
        "upload-test"
    );
    assert_eq!(
        *progress.lock().expect("progresso de teste"),
        vec![8192, 16384, 24000]
    );
    let requests = task.await?;
    assert!(requests[0].contains("content-type: application/octet-stream"));
    assert!(requests[0].contains("x-habblaud-file-name: a%C3%A7%C3%A3o%20%2B.txt"));
    assert!(requests[0].ends_with(&String::from_utf8(bytes)?));
    for size in [0, 15 * 1024 * 1024 + 1] {
        assert!(matches!(
            api.upload(
                "a",
                "text/plain",
                std::io::Cursor::new(vec![0]),
                Some(size),
                |_| {}
            )
            .await,
            Err(Error::InvalidResponse(_))
        ));
    }
    Ok(())
}

#[tokio::test]
async fn growing_or_shortened_upload_is_not_accepted() -> TestResult {
    for (actual, expected) in [(8193, 8192), (10, 11), (0, 1)] {
        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let api = HabblaudApi::new(&format!("http://{}", listener.local_addr()?))?;
        let drain = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await?;
            let mut buffer = [0; 8192];
            while socket.read(&mut buffer).await? != 0 {}
            Ok::<_, std::io::Error>(())
        });
        assert!(
            api.upload(
                "a",
                "text/plain",
                std::io::Cursor::new(vec![0; actual]),
                Some(expected),
                |_| {}
            )
            .await
            .is_err()
        );
        drain.await??;
    }
    Ok(())
}

#[tokio::test]
async fn unauthorized_cancels_other_bodies_without_resurrecting_session() -> TestResult {
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let api = HabblaudApi::new(&format!("http://{}", listener.local_addr()?))?;
    let (headers_sent, headers_received) = tokio::sync::oneshot::channel();
    let server = tokio::spawn(async move {
        let (mut first, _) = listener.accept().await?;
        first
            .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n")
            .await?;
        let _ = headers_sent.send(());
        let (mut second, _) = listener.accept().await?;
        second
            .write_all(b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n")
            .await?;
        let mut bytes = [0; 1024];
        while first.read(&mut bytes).await? != 0 {}
        Ok::<_, std::io::Error>(())
    });
    let caller = api.clone();
    let body = tokio::spawn(async move { caller.request("/api/snapshot", None).await });
    headers_received.await?;
    assert!(matches!(api.access().await, Err(Error::Unauthorized)));
    assert!(matches!(body.await?, Err(Error::SessionEnded)));
    server.await??;
    Ok(())
}
