use futures_util::StreamExt;
use serde_json::Value;
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
    process::Stdio,
    time::Duration,
};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader, Lines},
    process::{Child, ChildStdin, ChildStdout, Command},
    time::timeout,
};
use zaplaud_core::{
    Error,
    api::*,
    model::{SendMode, ServerEvent, Snapshot},
};
type TestResult<T = ()> = Result<T, Box<dyn std::error::Error>>;
struct Fixture {
    child: Child,
    input: ChildStdin,
    lines: Lines<BufReader<ChildStdout>>,
    ready: Value,
}
impl Fixture {
    async fn start() -> TestResult<Option<Self>> {
        match Command::new("node").arg("--version").output().await {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                eprintln!(
                    "SKIP contract: Node.js ausente; instale Node.js e execute npm ci na raiz."
                );
                return Ok(None);
            }
            Err(e) => return Err(e.into()),
            Ok(result) if !result.status.success() => return Err("Node.js não iniciou".into()),
            Ok(_) => {}
        }
        let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .ancestors()
            .nth(3)
            .ok_or("raiz ausente")?
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
        let input = child.stdin.take().ok_or("stdin ausente")?;
        let mut lines = BufReader::new(child.stdout.take().ok_or("stdout ausente")?).lines();
        let ready = timeout(Duration::from_secs(30), async {
            while let Some(line) = lines.next_line().await? {
                let value: Value = serde_json::from_str(&line)?;
                if value["ready"] == true {
                    return Ok::<_, Box<dyn std::error::Error>>(value);
                }
            }
            Err("fixture encerrou antes de ready".into())
        })
        .await??;
        Ok(Some(Self {
            child,
            input,
            lines,
            ready,
        }))
    }
    async fn command(&mut self, command: &str) -> TestResult<Value> {
        self.input
            .write_all(format!("{command}\n").as_bytes())
            .await?;
        self.input.flush().await?;
        let line = timeout(Duration::from_secs(10), self.lines.next_line())
            .await
            .map_err(|_| format!("timeout do comando {command}"))??
            .ok_or("fixture encerrou")?;
        let value: Value = serde_json::from_str(&line)?;
        if value["ok"] == false {
            return Err(format!("comando {command}: {value}").into());
        }
        Ok(value)
    }
    async fn finish(mut self) -> TestResult {
        drop(self.input);
        timeout(Duration::from_secs(10), self.child.wait())
            .await
            .map_err(|_| "timeout ao fechar fixture")??;
        Ok(())
    }
}
fn required<'a>(value: &'a Value, field: &str) -> TestResult<&'a str> {
    value[field]
        .as_str()
        .ok_or_else(|| format!("campo {field} ausente").into())
}
fn find_identical(dir: &Path, bytes: &[u8]) -> TestResult<bool> {
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        if entry.file_type()?.is_dir() {
            if find_identical(&entry.path(), bytes)? {
                return Ok(true);
            }
        } else if std::fs::read(entry.path())? == bytes {
            return Ok(true);
        }
    }
    Ok(false)
}
async fn delivered(fixture: &mut Fixture, api: &HabblaudApi, agent: &str, id: &str) -> TestResult {
    timeout(Duration::from_secs(10), async {
        loop {
            fixture.command(&format!("deliver {agent}")).await?;
            if api.message_status(id).await?.status.as_deref() == Some("delivered") {
                return Ok::<_, Box<dyn std::error::Error>>(());
            }
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
    })
    .await
    .map_err(|_| format!("timeout de entrega {id}"))??;
    Ok(())
}

#[tokio::test]
async fn full_kotlin_http_contract() -> TestResult {
    let Some(mut fixture) = Fixture::start().await? else {
        return Ok(());
    };
    let api = HabblaudApi::new(required(&fixture.ready, "url")?)?;
    assert!(matches!(api.snapshot().await, Err(Error::Unauthorized)));
    api.login("android-test", "android-test").await?;
    let access = api.access().await?;
    assert!(
        access.terminal && access.messages && access.permissions && access.message_session_guard
    );
    let snapshot = api.snapshot().await?;
    let markdown = snapshot
        .sessions
        .iter()
        .find(|s| s.agent_id.as_deref() == Some("fixture:claude"))
        .ok_or("sessão markdown ausente")?;
    let omp = snapshot
        .sessions
        .iter()
        .find(|s| s.agent_id.as_deref() == Some("fixture:omp"))
        .ok_or("sessão omp ausente")?;
    let recent = api.recent_sessions().await?;
    assert!(!recent.is_empty());
    assert!(!api.cast().await?.is_empty());
    assert!(api.cast_jobs().await?.available);
    let mut global = api.stream("/api/stream").await?;
    assert!(matches!(
        timeout(Duration::from_secs(10), global.next_event())
            .await
            .map_err(|_| "timeout snapshot SSE")??,
        Some(ServerEvent::Snapshot(_))
    ));
    let mut conversation = api.stream(&conversation_path(markdown, &[])).await?;
    let init = timeout(Duration::from_secs(10), conversation.next_event())
        .await
        .map_err(|_| "timeout init SSE")??;
    let entries = match init {
        Some(ServerEvent::ConversationInit(init)) => init.entries,
        _ => return Err("init ausente".into()),
    };
    assert!(
        entries
            .iter()
            .any(|entry| entry.text.contains("# Relatório fictício"))
    );
    drop(conversation);
    let mut incremental = api.stream(&conversation_path(markdown, &entries)).await?;
    assert!(
        matches!(timeout(Duration::from_secs(10),incremental.next_event()).await.map_err(|_| "timeout init incremental")??,Some(ServerEvent::ConversationInit(init)) if init.after_present)
    );
    drop(incremental);
    let bytes = b"anexo ficticio de trinta e um!!";
    assert_eq!(bytes.len(), 31);
    let progress = std::sync::Arc::new(std::sync::atomic::AtomicU64::new(0));
    let recorded = progress.clone();
    let attachment = api
        .upload(
            "fictício +.txt",
            "text/plain",
            std::io::Cursor::new(bytes.to_vec()),
            Some(31),
            move |count| {
                recorded.store(count, std::sync::atomic::Ordering::Relaxed);
            },
        )
        .await?;
    assert_eq!(progress.load(std::sync::atomic::Ordering::Relaxed), 31);
    let receipt = api
        .send_message(&SendMessage {
            agent_id: "fixture:claude",
            text: "Mensagem fictícia",
            expected_session_id: &markdown.key.session_id,
            deliver_as: None,
            attachments: vec![attachment],
        })
        .await?;
    assert_eq!(receipt.status.as_deref(), Some("queued"));
    delivered(&mut fixture, &api, "fixture:claude", &receipt.id).await?;
    assert!(find_identical(
        Path::new(required(&fixture.ready, "deliveredDir")?),
        bytes
    )?);
    for mode in [SendMode::Aside, SendMode::FollowUp, SendMode::Steer] {
        let receipt = api
            .send_message(&SendMessage {
                agent_id: "fixture:omp",
                text: "Modo fictício",
                expected_session_id: &omp.key.session_id,
                deliver_as: Some(mode),
                attachments: vec![],
            })
            .await?;
        delivered(&mut fixture, &api, "fixture:omp", &receipt.id).await?;
    }
    for control in [
        Control::Stop { stop: true },
        Control::Shake {
            command: "shake".into(),
        },
        Control::Model {
            model: "anthropic/claude-opus-4-6".into(),
        },
        Control::Thinking {
            thinking: "high".into(),
        },
    ] {
        let receipt = api
            .control_message(&ControlMessage {
                agent_id: "fixture:omp",
                expected_session_id: &omp.key.session_id,
                control,
            })
            .await?;
        delivered(&mut fixture, &api, "fixture:omp", &receipt.id).await?;
    }
    for decision in [
        PermissionDecision::Allow { suggestion: None },
        PermissionDecision::Deny {
            message: Some("Negação fictícia".into()),
            interrupt: false,
        },
    ] {
        let created = fixture.command("permission fixture:claude bash").await?;
        let id = required(&created, "id")?;
        let detail = api.permission(id).await?;
        assert_eq!(detail.provider, "claude");
        assert!(
            api.permissions()
                .await?
                .iter()
                .any(|(_, permission)| permission == id)
        );
        api.decide_permission(id, &decision).await?;
        assert!(matches!(
            api.permission(id).await,
            Err(Error::Api {
                status: 404 | 409,
                ..
            })
        ));
    }
    let question = fixture
        .command("permission fixture:claude question-single")
        .await?;
    let id = required(&question, "id")?;
    let detail = api.permission(id).await?;
    let question = detail.questions.first().ok_or("pergunta ausente")?;
    let choice = question
        .options
        .first()
        .and_then(|o| o.index)
        .ok_or("opção ausente")?;
    api.decide_permission(
        id,
        &PermissionDecision::Answer {
            answers: vec![QuestionAnswer {
                question: question.index.ok_or("índice ausente")?,
                options: vec![choice],
                other: None,
            }],
        },
    )
    .await?;
    let topic = api
        .ask_aside("fixture:omp", "  Pergunta fictícia  ", None)
        .await?
        .ok_or("tópico ausente")?;
    assert_eq!(topic.turns[0].question, "Pergunta fictícia");
    assert!(
        api.asides("fixture:omp")
            .await?
            .iter()
            .any(|item| item.id == topic.id)
    );
    let mut aside_stream = Box::pin(api.aside_stream("fixture:omp"));
    assert!(
        aside_stream
            .next()
            .await
            .ok_or("aside stream vazio")??
            .iter()
            .any(|topic| topic.turns.iter().any(|turn| turn.running()))
    );
    api.cancel_aside("fixture:omp", &topic.id).await?;
    assert!(
        !api.asides("fixture:omp")
            .await?
            .iter()
            .flat_map(|topic| &topic.turns)
            .any(|turn| turn.running())
    );
    assert!(
        !aside_stream
            .next()
            .await
            .ok_or("aside final ausente")??
            .iter()
            .any(|topic| topic.turns.iter().any(|turn| turn.running()))
    );
    assert!(aside_stream.next().await.is_none());
    assert!(matches!(
        api.summary("Texto fictício para resumo.").await,
        Err(Error::Api { status: 404, .. })
    ));
    let _roles = api.roles().await?;
    api.set_roles(&BTreeMap::from([(
        "task".into(),
        "anthropic/claude-opus-4-6:high".into(),
    )]))
    .await?;
    assert!(api.roles().await?.pending);
    fixture.command("cast-consume").await?;
    let called = api.start_cast("caio", None).await?;
    assert_eq!(called.cast.as_deref(), Some("caio"));
    fixture.command("cast-consume").await?;
    assert!(
        api.snapshot()
            .await?
            .sessions
            .iter()
            .any(|s| s.agent_id.as_deref() == Some("fixture:cast:caio"))
    );
    api.dismiss_agent("fixture:cast:caio").await?;
    fixture.command("cast-consume").await?;
    let historical = recent
        .iter()
        .find(|s| s.key.session_id.ends_with("0003"))
        .ok_or("histórico ausente")?;
    api.start_cast(
        "bia",
        Some(ResumeSession {
            room: historical
                .project
                .as_deref()
                .ok_or("pasta histórica ausente")?,
            session: &historical.key.session_id,
            account: &historical.key.account,
        }),
    )
    .await?;
    fixture.command("cast-consume").await?;
    let resumed = api.snapshot().await?;
    assert!(
        resumed
            .sessions
            .iter()
            .any(|s| s.agent_id.as_deref() == Some("fixture:cast:bia") && s.key == historical.key)
    );
    let new = api.new_cast(None).await?;
    assert!(new.cast.is_some());
    fixture.command("cast-consume").await?;
    // Novo também deve aceitar retomada sem personagem fixo.
    let resumed_new = api
        .new_cast(Some(ResumeSession {
            room: markdown
                .project
                .as_deref()
                .ok_or("pasta histórica ausente")?,
            session: &markdown.key.session_id,
            account: &markdown.key.account,
        }))
        .await?;
    let resumed_cast = resumed_new.cast.ok_or("cast Novo ausente")?;
    fixture.command("cast-consume").await?;
    assert!(
        api.snapshot()
            .await?
            .sessions
            .iter()
            .any(|s| s.cast.as_deref() == Some(&resumed_cast)
                && s.key.session_id == markdown.key.session_id)
    );
    if let Some(avatar) = &markdown.avatar {
        assert!(api.avatar_bytes(avatar).await?.is_some());
    }
    let _quotas = api.quotas().await?;
    drop(global);
    api.logout().await?;
    assert!(matches!(api.snapshot().await, Err(Error::Unauthorized)));
    fixture.finish().await?;
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
    assert!(!initial.sessions.iter().any(|s| s.permission));
    assert!(permission.sessions.iter().any(|s| s.permission));
    assert!(
        matches!(ServerEvent::parse("http://127.0.0.1","init",include_str!("fixtures/conversation-init.json"))?,ServerEvent::ConversationInit(init) if !init.entries.is_empty())
    );
    Ok(())
}

#[tokio::test]
async fn closed_omp_resume_subagents_and_usage_contract() -> TestResult {
    let Some(mut fixture) = Fixture::start().await? else {
        return Ok(());
    };
    let api = HabblaudApi::new(required(&fixture.ready, "url")?)?;
    api.login("android-test", "android-test").await?;
    let session = "00000000-0000-4000-8000-000000000099";
    fixture
        .command(&format!("seed-closed-omp {session}"))
        .await?;
    let recent = api.recent_sessions().await?;
    let closed = recent
        .iter()
        .find(|s| s.key.session_id == session)
        .ok_or("histórico OMP ausente")?;
    assert_eq!(closed.key.provider, "omp");
    assert_eq!(closed.key.account, ".omp");
    assert!(closed.agent_id.is_none());
    for contact in [Some("caio"), None] {
        let resume = Some(ResumeSession {
            room: closed.project.as_deref().ok_or("pasta histórica ausente")?,
            session,
            account: ".omp",
        });
        let job = match contact {
            Some(id) => api.start_cast(id, resume).await?,
            None => api.new_cast(resume).await?,
        };
        let cast = job.cast.ok_or("cast ausente")?;
        let consumed = fixture.command("cast-consume").await?;
        assert_eq!(consumed["consumed"][0]["resume"], session);
        assert!(required(&consumed["consumed"][0], "agentDir")?.ends_with("/.omp"));
        let snapshot = api.snapshot().await?;
        let opened = snapshot
            .sessions
            .iter()
            .find(|s| s.cast.as_deref() == Some(&cast))
            .ok_or("retomada ausente")?;
        assert_eq!(opened.key, closed.key);
        assert!(opened.can_message);
        api.dismiss_agent(opened.agent_id.as_deref().ok_or("agente ausente")?)
            .await?;
        fixture.command("cast-consume").await?;
    }
    fixture.command("seed-subagents fixture:omp").await?;
    let snapshot = api.snapshot().await?;
    let parent = snapshot
        .sessions
        .iter()
        .find(|s| s.agent_id.as_deref() == Some("fixture:omp"))
        .ok_or("principal ausente")?;
    assert_eq!(parent.subagents.len(), 2);
    assert_eq!(parent.subagents.iter().filter(|s| s.advisor).count(), 1);
    assert!(snapshot.sessions.iter().all(|s| !s.sub));
    let seeded = fixture.command("seed-usage").await?;
    let quotas = api.quotas().await?;
    let omp = quotas
        .iter()
        .find(|q| q.id == ".omp")
        .ok_or("cotas ausentes")?;
    assert_eq!(omp.fetched_at, seeded["fetchedAt"].as_i64());
    assert!(!omp.no_quota);
    assert_eq!(omp.groups[0].limits[0].utilization, Some(60.0));
    assert_eq!(omp.groups[0].limits[1].utilization, Some(100.0));
    assert!(
        omp.groups[0]
            .limits
            .iter()
            .all(|limit| limit.resets_at.is_some())
    );
    fixture.finish().await
}

#[tokio::test]
async fn zeroclaw_runtime_messages_controls_cast_and_summary_contract() -> TestResult {
    let mut fixture = Fixture::start()
        .await?
        .ok_or("Node.js obrigatório para contrato ZeroClaw")?;
    let api = HabblaudApi::new(required(&fixture.ready, "url")?)?;
    api.login("android-test", "android-test").await?;
    fixture.command("runtime zeroclaw").await?;
    fixture.command("zc-reset").await?;
    assert_eq!(api.cast_runtime().await?.as_deref(), Some("zeroclaw"));
    assert!(api.summary_http().await?);
    assert!(
        !api.summary("Resposta fictícia para resumir.")
            .await?
            .is_empty()
    );
    fixture.command("catalog empty").await?;
    assert_eq!(api.cast_runtime().await?.as_deref(), Some("zeroclaw"));
    fixture.command("catalog error").await?;
    assert!(api.cast_runtime().await.is_err());
    fixture.command("catalog normal").await?;
    fixture.command("brand neutral").await?;
    assert_eq!(api.cast_runtime().await?.as_deref(), Some("zeroclaw"));
    fixture.command("brand normal").await?;
    let snapshot = api.snapshot().await?;
    let session = snapshot
        .sessions
        .iter()
        .find(|s| s.key.provider == "zeroclaw" && s.agent_id.as_deref() == Some("fixture:zeroclaw"))
        .ok_or("sessão ZeroClaw ausente")?;
    for mode in [SendMode::Steer, SendMode::FollowUp] {
        let receipt = api
            .send_message(&SendMessage {
                agent_id: "fixture:zeroclaw",
                text: "Mensagem fictícia",
                expected_session_id: &session.key.session_id,
                deliver_as: Some(mode),
                attachments: vec![],
            })
            .await?;
        if mode == SendMode::FollowUp {
            fixture.command("zc-done").await?;
        }
        timeout(Duration::from_secs(10), async {
            loop {
                let status = api.message_status(&receipt.id).await?;
                if status.status.as_deref() == Some("delivered") {
                    return Ok::<_, Box<dyn std::error::Error>>(());
                }
                if status.status.as_deref() == Some("failed") {
                    return Err("entrega ZeroClaw falhou".into());
                }
                tokio::time::sleep(Duration::from_millis(30)).await;
            }
        })
        .await??;
    }
    for command in ["zc-working", "zc-no-active"] {
        fixture.command(command).await?;
        let receipt = api
            .control_message(&ControlMessage {
                agent_id: "fixture:zeroclaw",
                expected_session_id: &session.key.session_id,
                control: Control::Stop { stop: true },
            })
            .await?;
        timeout(Duration::from_secs(10), async {
            loop {
                if api.message_status(&receipt.id).await?.status.as_deref() == Some("delivered") {
                    return Ok::<_, Box<dyn std::error::Error>>(());
                }
                tokio::time::sleep(Duration::from_millis(30)).await;
            }
        })
        .await??;
    }
    let control: Value = serde_json::from_str(&api.request("/api/messages/control", Some(&serde_json::json!({"agentId":"fixture:zeroclaw","command":"new","expectedSessionId":session.key.session_id}))).await?)?;
    let id = required(&control, "id")?;
    timeout(Duration::from_secs(10), async {
        loop {
            if api.message_status(id).await?.status.as_deref() == Some("delivered") {
                return Ok::<_, Box<dyn std::error::Error>>(());
            }
            tokio::time::sleep(Duration::from_millis(30)).await;
        }
    })
    .await??;
    let next = api.snapshot().await?;
    assert!(
        next.sessions
            .iter()
            .any(|s| s.agent_id.as_deref() == Some("fixture:zeroclaw")
                && s.key.session_id != session.key.session_id)
    );
    let job = api.new_cast(None).await?;
    assert!(job.cast.is_some());
    let stats = fixture.command("stats").await?;
    assert_eq!(stats["runtime"], "zeroclaw");
    assert!(
        !stats["castRequests"]
            .as_array()
            .ok_or("requisições de elenco ausentes")?
            .is_empty()
    );
    assert!(
        stats["messageRequests"]
            .as_array()
            .ok_or("mensagens ausentes")?
            .iter()
            .all(|request| request["expectedSessionId"] == session.key.session_id)
    );
    fixture.command("runtime omp").await?;
    assert_eq!(api.cast_runtime().await?.as_deref(), Some("omp"));
    assert!(!api.summary_http().await?);
    fixture.finish().await
}
