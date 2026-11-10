use std::path::PathBuf;
use std::process::Stdio;
use std::time::Duration;

use serde_json::Value;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::Command;
use tokio::time::timeout;
use zaplaud_core::api::{EventStream, HabblaudApi};
use zaplaud_core::model::{ServerEvent, Snapshot};

type TestResult<T = ()> = Result<T, Box<dyn std::error::Error>>;

async fn next_snapshot(stream: &mut EventStream) -> TestResult<Snapshot> {
    loop {
        match stream.next_event().await? {
            Some(ServerEvent::Snapshot(snapshot)) => return Ok(snapshot),
            Some(_) => {}
            None => return Err("Stream encerrado antes do snapshot".into()),
        }
    }
}

#[tokio::test]
async fn login_snapshot_permission_and_logout() -> TestResult {
    match Command::new("node").arg("--version").output().await {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            eprintln!("SKIP contract: Node.js ausente; instale Node.js e execute npm ci na raiz.");
            return Ok(());
        }
        Err(error) => return Err(error.into()),
        Ok(output) if !output.status.success() => return Err("Node.js não iniciou".into()),
        Ok(_) => {}
    }
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .ancestors()
        .nth(3)
        .ok_or("Raiz da worktree ausente")?
        .to_path_buf();
    let mut child = Command::new("node")
        .args([
            "--import",
            "tsx",
            "scripts/android-smoke.ts",
            "--port",
            "0",
            "--allow",
            "terminal,messages,permissions",
        ])
        .current_dir(root)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .kill_on_drop(true)
        .spawn()?;
    let stdout = child.stdout.take().ok_or("stdout da fixture ausente")?;
    let mut lines = BufReader::new(stdout).lines();
    let ready = timeout(Duration::from_secs(30), async {
        while let Some(line) = lines.next_line().await? {
            let value: Value = serde_json::from_str(&line)?;
            if value.get("ready").and_then(Value::as_bool) == Some(true) {
                return Ok::<_, Box<dyn std::error::Error>>(value);
            }
        }
        Err("Fixture encerrou antes de anunciar a porta".into())
    })
    .await??;
    let origin = ready
        .get("url")
        .and_then(Value::as_str)
        .ok_or("URL da fixture ausente")?;
    let agent_id = ready
        .get("agents")
        .and_then(Value::as_array)
        .and_then(|agents| agents.first())
        .and_then(Value::as_str)
        .ok_or("Agente da fixture ausente")?;
    let api = HabblaudApi::new(origin)?;
    assert!(matches!(
        api.request("/api/snapshot", None).await,
        Err(zaplaud_core::Error::Api { status: 401, .. })
    ));
    api.login("android-test", "android-test").await?;
    let isolated = HabblaudApi::new(origin)?;
    assert!(matches!(
        isolated.request("/api/snapshot", None).await,
        Err(zaplaud_core::Error::Api { status: 401, .. })
    ));
    let mut stream = api.stream("/api/stream").await?;
    let first = timeout(Duration::from_secs(10), next_snapshot(&mut stream)).await??;
    assert!(
        first
            .sessions
            .iter()
            .any(|session| session.agent_id.as_deref() == Some(agent_id))
    );
    let mut input = child.stdin.take().ok_or("stdin da fixture ausente")?;
    input
        .write_all(format!("permission {agent_id} bash\n").as_bytes())
        .await?;
    input.flush().await?;
    timeout(Duration::from_secs(10), async {
        loop {
            let snapshot = next_snapshot(&mut stream).await?;
            if snapshot
                .sessions
                .iter()
                .any(|session| session.agent_id.as_deref() == Some(agent_id) && session.permission)
            {
                return Ok::<_, Box<dyn std::error::Error>>(());
            }
        }
    })
    .await??;
    drop(stream);
    api.logout().await?;
    assert!(matches!(
        api.request("/api/snapshot", None).await,
        Err(zaplaud_core::Error::Api { status: 401, .. })
    ));
    drop(input);
    timeout(Duration::from_secs(10), child.wait()).await??;
    Ok(())
}

#[test]
fn real_server_payloads_decode() -> TestResult {
    let initial = Snapshot::parse("http://127.0.0.1", include_str!("fixtures/snapshot.json"))?;
    let permission = Snapshot::parse(
        "http://127.0.0.1",
        include_str!("fixtures/snapshot-permission.json"),
    )?;
    assert!(!initial.sessions.is_empty());
    assert!(!initial.sessions.iter().any(|session| session.permission));
    assert!(permission.sessions.iter().any(|session| session.permission));
    match ServerEvent::parse(
        "http://127.0.0.1",
        "init",
        include_str!("fixtures/conversation-init.json"),
    )? {
        ServerEvent::ConversationInit(init) => assert!(!init.entries.is_empty()),
        _ => return Err("Evento de conversa incorreto".into()),
    }
    Ok(())
}
