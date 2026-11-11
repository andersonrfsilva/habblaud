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
async fn cast_new_uses_host_workspace_and_resume_keeps_history_folder() -> TestResult {
    use serde_json::{Value, json};
    use zaplaud_core::api::ResumeSession;

    let (origin, task) = server(vec![response(202, "", "{}"); 6]).await?;
    let api = HabblaudApi::new(&origin)?;
    api.new_cast(None, None).await?;
    api.start_cast("free contact", None, None).await?;
    let resume = || {
        Some(ResumeSession {
            room: "/history/project",
            session: "abcd1234-5678",
            account: ".omp",
        })
    };
    api.new_cast(resume(), Some("must-not-send")).await?;
    api.start_cast("free contact", resume(), Some("must-not-send"))
        .await?;
    api.new_cast(None, Some("m_123456abcdef")).await?;
    api.start_cast("free contact", None, Some("m_abcdef123456"))
        .await?;
    let requests = task.await?;
    let expected = [
        ("/api/cast/new", json!({})),
        ("/api/cast/free%20contact/start", json!({"mode":"new"})),
        (
            "/api/cast/new",
            json!({"room":"/history/project","session":"abcd1234-5678","account":".omp"}),
        ),
        (
            "/api/cast/free%20contact/start",
            json!({"mode":"resume","room":"/history/project","session":"abcd1234-5678","account":".omp"}),
        ),
        ("/api/cast/new", json!({"model":"m_123456abcdef"})),
        (
            "/api/cast/free%20contact/start",
            json!({"mode":"new","model":"m_abcdef123456"}),
        ),
    ];
    for (request, (path, body)) in requests.iter().zip(expected) {
        assert!(request.starts_with(&format!("POST {path} ")));
        assert_eq!(
            serde_json::from_str::<Value>(
                request.split_once("\r\n\r\n").ok_or("corpo ausente")?.1
            )?,
            body
        );
    }
    Ok(())
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
    let mut stream = api.resilient_stream("/api/stream")?;
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
    let api = HabblaudApi::with_persistence(&origin, persistence.clone()).await?;
    api.login("android-test", "android-test").await?;
    let cookie = persistence.load(&origin).ok_or("cookie não salvo")?;
    assert!(cookie.contains("Expires="));
    assert!(!cookie.contains("Max-Age="));
    assert!(!format!("{api:?}").contains("fictional"));
    let restored = HabblaudApi::with_persistence(&origin, persistence.clone()).await?;
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
    let mut stream = api.resilient_stream("/api/stream")?;
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
        {
            let mut reader = BufReader::new(&mut first);
            loop {
                let mut line = String::new();
                if reader.read_line(&mut line).await? == 0 || line == "\r\n" {
                    break;
                }
            }
        }
        first
            .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n")
            .await?;
        let _ = headers_sent.send(());
        let (mut second, _) = listener.accept().await?;
        {
            let mut reader = BufReader::new(&mut second);
            loop {
                let mut line = String::new();
                if reader.read_line(&mut line).await? == 0 || line == "\r\n" {
                    break;
                }
            }
        }
        second
            .write_all(b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n")
            .await?;
        second.shutdown().await?;
        let mut bytes = [0; 1024];
        while first.read(&mut bytes).await? != 0 {}
        Ok::<_, std::io::Error>(())
    });
    let caller = api.clone();
    let body = tokio::spawn(async move { caller.request("/api/snapshot", None).await });
    headers_received.await?;
    let unauthorized = api.access().await;
    assert!(
        matches!(unauthorized, Err(Error::Unauthorized)),
        "{unauthorized:?}"
    );
    assert!(matches!(body.await?, Err(Error::SessionEnded)));
    server.await??;
    Ok(())
}

#[tokio::test]
async fn summaries_accept_all_kotlin_primitives_without_http_fallback() -> TestResult {
    let responses = [
        r#"{"summary":"texto"}"#,
        r#"{"summary":12}"#,
        r#"{"summary":true}"#,
        r#"{"summary":null}"#,
        "{}",
        r#"{"summary":[]}"#,
    ]
    .into_iter()
    .map(|body| response(200, "", body))
    .collect();
    let (origin, server) = server(responses).await?;
    let api = HabblaudApi::new(&origin)?;
    for expected in ["texto", "12", "true", "null"] {
        assert_eq!(api.summary("texto fictício").await?, expected);
    }
    for _ in 0..2 {
        assert!(matches!(
            api.summary("texto fictício").await,
            Err(Error::InvalidResponse("summary"))
        ));
    }
    server.await?;
    Ok(())
}

#[tokio::test]
async fn summaries_preserve_utf16_boundary_including_unpaired_surrogate() -> TestResult {
    let (origin, task) = server(vec![response(
        200,
        "Content-Type: application/json\r\n",
        r#"{"summary":"resumo"}"#,
    )])
    .await?;
    let api = HabblaudApi::new(&origin)?;
    assert_eq!(api.summary_utf16(&[0x61, 0xd83d]).await?, "resumo");
    let requests = task.await?;
    assert!(requests[0].starts_with("POST /api/summaries "));
    assert!(requests[0].ends_with(r#"{"text":"\u0061\ud83d"}"#));
    Ok(())
}

#[tokio::test]
async fn files_parse_roots_listing_and_encode_paths() -> TestResult {
    let (origin, task) = server(vec![
        response(
            200,
            "",
            r#"{"roots":[{"path":"/work/sala","label":"Sala","roomId":"room-1"},{"path":"/home/user/projetos","label":"projetos"}]}"#,
        ),
        response(
            200,
            "",
            r#"{"path":"/work/sala","root":"/work/sala","entries":[{"name":"pasta","type":"dir","size":0,"mtime":1700000000000.25},{"name":"dados.bin","type":"file","size":9000000000,"mtime":1700000000001},{"name":"leia.txt","type":"file","size":4,"mtime":1700000000002,"kind":"text"}],"truncated":true}"#,
        ),
    ])
    .await?;
    let api = HabblaudApi::new(&origin)?;
    let roots = api.files_roots().await?;
    assert_eq!(roots.len(), 2);
    assert_eq!(roots[0].room_id.as_deref(), Some("room-1"));
    assert_eq!(roots[1].room_id, None);
    let listing = api.files_list("/work/sala/a &ç?#").await?;
    assert_eq!(listing.path, "/work/sala");
    assert_eq!(listing.root, "/work/sala");
    assert!(listing.truncated);
    assert_eq!(listing.entries[0].entry_type, "dir");
    assert_eq!(listing.entries[0].mtime, 1_700_000_000_000.25);
    assert_eq!(listing.entries[1].size, 9_000_000_000);
    assert_eq!(listing.entries[1].kind, None);
    assert_eq!(listing.entries[2].kind.as_deref(), Some("text"));
    let requests = task.await?;
    assert!(requests[0].starts_with("GET /api/files "));
    assert!(
        requests[1].starts_with("GET /api/files/list?path=%2Fwork%2Fsala%2Fa%20%26%C3%A7%3F%23 ")
    );
    Ok(())
}

#[tokio::test]
async fn files_forbidden_preserves_server_error_without_creating_files() -> TestResult {
    let (origin, task) = server(vec![
        response(403, "", r#"{"error":"sem permissão"}"#),
        response(403, "", r#"{"error":"sem permissão"}"#),
    ])
    .await?;
    let api = HabblaudApi::new(&origin)?;
    let directory = tempfile::tempdir()?;
    let errors = [
        api.files_list("/work/sala").await.map(|_| ()),
        api.files_download("/work/sala/a", &directory.path().join("a"), |_, _| {})
            .await,
    ];
    for error in errors {
        assert!(matches!(
            error,
            Err(Error::Api { status: 403, detail, .. }) if detail == "sem permissão"
        ));
    }
    assert_eq!(std::fs::read_dir(directory.path())?.count(), 0);
    task.await?;
    Ok(())
}

#[tokio::test]
async fn files_download_streams_beyond_json_cap_and_reuses_cookie() -> TestResult {
    let body = "x".repeat(8 * 1024 * 1024 + 31);
    let (origin, task) = server(vec![
        response(
            200,
            "Set-Cookie: habblaud_remote=download-test; Path=/\r\n",
            r#"{"roots":[]}"#,
        ),
        response(200, "Content-Type: application/octet-stream\r\n", &body),
    ])
    .await?;
    let api = HabblaudApi::new(&origin)?;
    api.files_roots().await?;
    let directory = tempfile::tempdir()?;
    let destination = directory.path().join("download.bin");
    let mut updates = Vec::new();
    api.files_download("/work/sala/a &ç", &destination, |received, total| {
        assert!(!destination.exists());
        updates.push((received, total));
    })
    .await?;
    assert_eq!(tokio::fs::read(&destination).await?, body.as_bytes());
    assert_eq!(updates.first(), Some(&(0, Some(body.len() as u64))));
    assert_eq!(
        updates.last(),
        Some(&(body.len() as u64, Some(body.len() as u64)))
    );
    assert!(updates.windows(2).all(|pair| pair[0].0 < pair[1].0));
    assert_eq!(std::fs::read_dir(directory.path())?.count(), 1);
    let requests = task.await?;
    assert!(
        requests[1]
            .starts_with("GET /api/files/raw?path=%2Fwork%2Fsala%2Fa%20%26%C3%A7&download=1 ")
    );
    assert!(
        requests[1]
            .to_ascii_lowercase()
            .contains("cookie: habblaud_remote=download-test\r\n")
    );
    Ok(())
}

#[tokio::test]
async fn files_download_interruption_cleans_temporary_and_preserves_destination() -> TestResult {
    let interrupted =
        "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 100000\r\n\r\npartial";
    let (origin, task) =
        server(vec![interrupted.to_owned(), response(200, "", "complete")]).await?;
    let api = HabblaudApi::new(&origin)?;
    let directory = tempfile::tempdir()?;
    let destination = directory.path().join("download.bin");
    assert!(
        api.files_download("/work/sala/a", &destination, |_, _| {})
            .await
            .is_err()
    );
    assert_eq!(std::fs::read_dir(directory.path())?.count(), 0);
    tokio::fs::write(&destination, b"original").await?;
    assert!(matches!(
        api.files_download("/work/sala/a", &destination, |_, _| {})
            .await,
        Err(Error::Io(error)) if error.kind() == std::io::ErrorKind::AlreadyExists
    ));
    assert_eq!(tokio::fs::read(&destination).await?, b"original");
    assert_eq!(std::fs::read_dir(directory.path())?.count(), 1);
    task.await?;
    Ok(())
}

#[tokio::test]
async fn files_download_drop_removes_partial_sibling() -> TestResult {
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let api = HabblaudApi::new(&format!("http://{}", listener.local_addr()?))?;
    let (release, released) = tokio::sync::oneshot::channel::<()>();
    let server = tokio::spawn(async move {
        let (socket, _) = listener.accept().await?;
        let mut socket = BufReader::new(socket);
        loop {
            let mut line = String::new();
            socket.read_line(&mut line).await?;
            if line == "\r\n" {
                break;
            }
        }
        socket
            .get_mut()
            .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\npartial")
            .await?;
        let _ = released.await;
        Ok::<_, std::io::Error>(())
    });
    let directory = tempfile::tempdir()?;
    let destination = directory.path().join("download.bin");
    let (started, received) = tokio::sync::oneshot::channel();
    let task = tokio::spawn(async move {
        let mut started = Some(started);
        api.files_download("/work/sala/a", &destination, move |count, _| {
            if count > 0
                && let Some(started) = started.take()
            {
                let _ = started.send(());
            }
        })
        .await
    });
    tokio::time::timeout(Duration::from_secs(5), received).await??;
    assert_eq!(std::fs::read_dir(directory.path())?.count(), 1);
    task.abort();
    assert!(task.await.is_err_and(|error| error.is_cancelled()));
    assert_eq!(std::fs::read_dir(directory.path())?.count(), 0);
    let _ = release.send(());
    server.await??;
    Ok(())
}

#[tokio::test]
async fn files_download_unauthorized_clears_shared_session() -> TestResult {
    let (origin, task) = server(vec![
        response(
            200,
            "Set-Cookie: habblaud_remote=download-test; Path=/\r\n",
            r#"{"roots":[]}"#,
        ),
        response(401, "", r#"{"error":"login necessário"}"#),
        response(200, "", r#"{"roots":[]}"#),
    ])
    .await?;
    let api = HabblaudApi::new(&origin)?;
    api.files_roots().await?;
    let directory = tempfile::tempdir()?;
    assert!(matches!(
        api.files_download("/work/sala/a", &directory.path().join("a"), |_, _| {})
            .await,
        Err(Error::Unauthorized)
    ));
    api.files_roots().await?;
    assert_eq!(std::fs::read_dir(directory.path())?.count(), 0);
    let requests = task.await?;
    assert!(requests[1].to_ascii_lowercase().contains("cookie:"));
    assert!(!requests[2].to_ascii_lowercase().contains("cookie:"));
    Ok(())
}

#[tokio::test]
async fn cast_controller_sends_selected_alias_only_for_new_zeroclaw() -> TestResult {
    use serde_json::{Value, json};
    use zaplaud_core::{controller::cast::State, model::CastCatalog};
    let (origin, task) = server(vec![response(202, "", "{}"); 4]).await?;
    let api = HabblaudApi::new(&origin)?;
    let mut state = State {
        runtime: Some("zeroclaw".into()),
        catalog: CastCatalog::parse(&json!({
            "provider": "zeroclaw",
            "models": [
                {"alias": "m_one", "model": "fixture/one"},
                {"alias": "m_two", "model": "fixture/two"}
            ],
            "defaultModel": "m_one"
        })),
        ..State::default()
    };
    state.submit(&api, "", None, None, Some("m_two")).await?;
    state
        .submit(&api, "free contact", None, None, Some("m_two"))
        .await?;
    state.runtime = Some("omp".into());
    state.submit(&api, "", None, None, Some("m_two")).await?;
    state
        .submit(&api, "free contact", None, None, Some("m_two"))
        .await?;
    let requests = task.await?;
    for (request, expected) in requests.iter().zip([
        json!({"model":"m_two"}),
        json!({"mode":"new","model":"m_two"}),
        json!({}),
        json!({"mode":"new"}),
    ]) {
        let body: Value =
            serde_json::from_str(request.split_once("\r\n\r\n").ok_or("corpo ausente")?.1)?;
        assert_eq!(body, expected);
    }
    Ok(())
}
