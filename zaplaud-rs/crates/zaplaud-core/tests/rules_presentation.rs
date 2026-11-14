use chrono::{FixedOffset, TimeZone};
use serde_json::{Value, json};
use std::collections::HashMap;
use zaplaud_core::rules::time::local_time;
use zaplaud_core::{
    model::*,
    rules::{alerts::*, attachments::*, composer::*, conversation::*, time::*},
};

fn e(id: &str, kind: &str, text: &str, at: Option<i64>) -> TerminalEntry {
    TerminalEntry {
        id: id.into(),
        kind: kind.into(),
        text: text.into(),
        at,
        ..TerminalEntry::default()
    }
}
fn result(id: &str, text: &str, tool: &str) -> TerminalEntry {
    TerminalEntry {
        tool_use_id: Some(tool.into()),
        ..e(id, "result", text, None)
    }
}
#[test]
fn kotlin_conversation_filters() {
    let entries: Vec<_> = ["user", "thinking", "tool", "result", "assistant", "system"]
        .map(|kind| e(kind, kind, kind, None))
        .into();
    for (view, expected) in [
        ("prompts", vec!["user", "assistant"]),
        ("no_tools", vec!["user", "thinking", "assistant", "system"]),
        (
            "all",
            vec!["user", "thinking", "tool", "result", "assistant", "system"],
        ),
        (
            "invalid",
            vec!["user", "thinking", "tool", "result", "assistant", "system"],
        ),
    ] {
        assert_eq!(
            visible_entries(&entries, view)
                .iter()
                .map(|e| e.kind.as_str())
                .collect::<Vec<_>>(),
            expected
        );
    }
}
#[test]
fn kotlin_thinking_and_tools_of_turn_become_counted_rows() {
    let rows = group_turn(&[
        e("t1", "thinking", "um", None),
        e("x", "tool", "ls", None),
        result("rx", "a.txt", "x"),
        e("t2", "thinking", "", None),
        e("y", "tool", "cat", Some(9)),
        result("ry", "conteúdo", "y"),
        e("t3", "thinking", "três", None),
        e("a", "assistant", "ok", None),
        result("ro", "sem ferramenta", "fora"),
        e("u", "user", "próximo", None),
        e("t4", "thinking", "quatro", None),
        e("z", "tool", "pwd", None),
    ]);
    assert_eq!(
        rows.iter()
            .map(|r| (r.entry.id.as_str(), r.count))
            .collect::<Vec<_>>(),
        [
            ("t1", 3),
            ("x", 2),
            ("a", 1),
            ("ro", 1),
            ("u", 1),
            ("t4", 1),
            ("z", 1)
        ]
    );
    assert_eq!(rows[0].entry.text, "um\n\ntrês");
    assert_eq!(rows[1].entry.at, Some(9));
    assert_eq!(
        rows[1]
            .tools
            .iter()
            .map(|e| e.id.as_str())
            .collect::<Vec<_>>(),
        ["x", "y"]
    );
    assert_eq!(rows[6].tools[0].id, "z");
}
#[test]
fn kotlin_three_thoughts_discard_blank_middle() {
    let first = e("t1", "thinking", "um", Some(1));
    assert_eq!(
        group_turn(&[
            first.clone(),
            e("t2", "thinking", " \n\t", Some(2)),
            e("t3", "thinking", "três", Some(3))
        ]),
        vec![EntryRow {
            entry: TerminalEntry {
                text: "um\n\ntrês".into(),
                at: Some(3),
                ..first
            },
            count: 3,
            tools: vec![]
        }]
    );
}
#[test]
fn kotlin_interleaved_thoughts_preserve_positions_and_metadata() {
    let first = e("t1", "thinking", "um", Some(1));
    let tool = e("x", "tool", "ls", Some(2));
    let next = e("y", "tool", "cat", Some(4));
    let answer = e("a", "assistant", "ok", None);
    assert_eq!(
        group_turn(&[
            first.clone(),
            tool.clone(),
            e("t2", "thinking", "dois", Some(3)),
            answer.clone(),
            next.clone(),
            result("rx", "arquivo", "x")
        ]),
        vec![
            EntryRow {
                entry: TerminalEntry {
                    text: "um\n\ndois".into(),
                    at: Some(3),
                    ..first
                },
                count: 2,
                tools: vec![]
            },
            EntryRow {
                entry: TerminalEntry {
                    at: Some(4),
                    ..tool.clone()
                },
                count: 2,
                tools: vec![tool, next]
            },
            EntryRow::new(answer)
        ]
    );
}
#[test]
fn kotlin_blank_thought_cases() {
    for blank in ["", " \n\t"] {
        let first = e("t1", "thinking", blank, None);
        assert_eq!(
            group_turn(&[
                first.clone(),
                e("t2", "thinking", "dois", None),
                e("t3", "thinking", "três", None)
            ]),
            vec![EntryRow {
                entry: TerminalEntry {
                    text: "dois\n\ntrês".into(),
                    ..first
                },
                count: 3,
                tools: vec![]
            }]
        );
    }
    let first = e("t1", "thinking", " \n\t", None);
    assert_eq!(
        group_turn(std::slice::from_ref(&first)),
        vec![EntryRow::new(first.clone())]
    );
    assert_eq!(
        group_turn(&[first.clone(), e("t2", "thinking", " ", None)]),
        vec![EntryRow {
            entry: TerminalEntry {
                text: "".into(),
                ..first
            },
            count: 2,
            tools: vec![]
        }]
    );
}
#[test]
fn kotlin_null_times_keep_last_nonnull_and_user_resets_groups() {
    let first = e("t1", "thinking", "um", None);
    let tool = e("x", "tool", "ls", None);
    let next = e("y", "tool", "cat", Some(8));
    let last = e("z", "tool", "pwd", None);
    assert_eq!(
        group_turn(&[
            first.clone(),
            tool.clone(),
            e("t2", "thinking", "dois", Some(7)),
            next.clone(),
            e("t3", "thinking", "três", None),
            last.clone()
        ]),
        vec![
            EntryRow {
                entry: TerminalEntry {
                    text: "um\n\ndois\n\ntrês".into(),
                    at: Some(7),
                    ..first
                },
                count: 3,
                tools: vec![]
            },
            EntryRow {
                entry: TerminalEntry {
                    at: Some(8),
                    ..tool.clone()
                },
                count: 3,
                tools: vec![tool, next, last]
            }
        ]
    );
    let first = e("t1", "thinking", "um", Some(1));
    let tool = e("x", "tool", "ls", Some(2));
    let user = e("u", "user", "próximo", None);
    let next = e("t3", "thinking", "", Some(5));
    let next_tool = e("z", "tool", "pwd", Some(6));
    assert_eq!(
        group_turn(&[
            first.clone(),
            tool.clone(),
            e("t2", "thinking", "dois", Some(3)),
            user.clone(),
            next.clone(),
            next_tool.clone(),
            e("t4", "thinking", "quatro", None),
            e("w", "tool", "date", None)
        ]),
        vec![
            EntryRow {
                entry: TerminalEntry {
                    text: "um\n\ndois".into(),
                    at: Some(3),
                    ..first
                },
                count: 2,
                tools: vec![]
            },
            EntryRow {
                entry: tool.clone(),
                count: 1,
                tools: vec![tool]
            },
            EntryRow::new(user),
            EntryRow {
                entry: TerminalEntry {
                    text: "quatro".into(),
                    ..next
                },
                count: 2,
                tools: vec![]
            },
            EntryRow {
                entry: next_tool.clone(),
                count: 2,
                tools: vec![next_tool, e("w", "tool", "date", None)]
            }
        ]
    );
}
#[test]
fn kotlin_recovered_errors_require_same_turn_work() {
    let entries = [
        e("u1", "user", "eae", None),
        e("a1:t0", "assistant", "Ainda há três", None),
        e("a1:err", "system", "Erro: socket", None),
        e("a2:t0", "assistant", "mais campos", None),
        e("a3:err", "system", "Erro: socket", None),
        e("a4:k0", "thinking", "", None),
        e("a5:err", "system", "Erro: limite", None),
        e("u2", "user", "sim", None),
        e("x:int", "system", "Interrompido", None),
    ];
    assert_eq!(
        recovered_errors(&entries),
        ["a1:err".into(), "a3:err".into()].into()
    );
    assert!(
        recovered_errors(&[
            e("x:err", "system", "", None),
            result("r", "", "none"),
            e("s", "system", "", None)
        ])
        .is_empty()
    );
}
#[test]
fn grouping_boundary_global_tool_ids_and_times() {
    let entries = [
        result("r", "before", "x"),
        e("u", "user", "", None),
        e("x", "tool", "", Some(10)),
        TerminalEntry {
            at: Some(20),
            ..result("r2", "after", "x")
        },
        e("y", "tool", "", Some(5)),
    ];
    let rows = group_turn(&entries);
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[1].entry.at, Some(5));
    assert_eq!(tool_group_time(&rows[1], &entries), Some(20));
    assert!(group_turn(&[]).is_empty());
}
fn session(status: &str, permission: Option<&str>) -> SessionItem {
    SessionItem {
        key: SessionKey {
            origin: "http://servidor:4747".into(),
            provider: "omp".into(),
            account: ".omp".into(),
            session_id: "s1".into(),
        },
        agent_id: Some("agente".into()),
        title: "Título".into(),
        project: Some("/projetos/Projeto".into()),
        status: Some(status.into()),
        permission: permission.is_some(),
        permission_id: permission.map(str::to_owned),
        ..SessionItem::default()
    }
}
#[test]
fn kotlin_notification_cases_and_priority() {
    let working = session("working", None);
    let asked = session("waiting", Some("p1"));
    assert!(matches!(
        agent_events(
            &watch_of(std::slice::from_ref(&working)),
            std::slice::from_ref(&asked)
        )
        .as_slice(),
        [AgentEvent::Permission(_)]
    ));
    assert!(
        agent_events(
            &watch_of(std::slice::from_ref(&asked)),
            std::slice::from_ref(&asked)
        )
        .is_empty()
    );
    assert!(matches!(
        agent_events(
            &watch_of(std::slice::from_ref(&asked)),
            &[session("waiting", Some("p2"))]
        )
        .as_slice(),
        [AgentEvent::Permission(_)]
    ));
    assert!(matches!(
        agent_events(
            &watch_of(std::slice::from_ref(&working)),
            &[session("idle", None)]
        )
        .as_slice(),
        [AgentEvent::TurnDone(_)]
    ));
    for status in ["shell", "busy", "running", "waiting", "closed"] {
        assert!(
            agent_events(
                &watch_of(std::slice::from_ref(&working)),
                &[session(status, None)]
            )
            .is_empty()
        );
    }
    assert!(
        agent_events(
            &watch_of(&[session("idle", None)]),
            &[session("idle", None)]
        )
        .is_empty()
    );
    assert!(
        agent_events(
            &watch_of(std::slice::from_ref(&asked)),
            std::slice::from_ref(&working)
        )
        .is_empty()
    );
    assert!(matches!(
        agent_events(&HashMap::new(), std::slice::from_ref(&asked)).as_slice(),
        [AgentEvent::Permission(_)]
    ));
    assert!(agent_events(&HashMap::new(), &[session("idle", None)]).is_empty());
    let mut other = asked.clone();
    other.key.session_id = "s2".into();
    let events = agent_events(
        &watch_of(&[working.clone(), other.clone()]),
        &[session("idle", None), other],
    );
    assert_eq!(events, vec![AgentEvent::TurnDone(session("idle", None))]);
    assert!(matches!(
        agent_events(&watch_of(&[working]), &[session("idle", Some("new"))]).as_slice(),
        [AgentEvent::Permission(_)]
    ));
    let mut closed = asked;
    closed.agent_id = None;
    assert!(watch_of(&[closed.clone()]).is_empty());
    assert!(agent_events(&HashMap::new(), &[closed]).is_empty());
    assert!(agent_events(&watch_of(&[session("working", None)]), &[]).is_empty());
    assert_eq!(
        watch_of(&[session("working", None), session("idle", None)])
            .values()
            .next()
            .unwrap()
            .status
            .as_deref(),
        Some("idle")
    );
}
#[test]
fn kotlin_notification_text_cases() {
    let s = session("idle", None);
    let event = AgentEvent::Permission(s.clone());
    assert_eq!(alert_text(&event), "notify_permission");
    assert_eq!(
        alert_text(&AgentEvent::Permission(SessionItem {
            permission_question: true,
            ..s.clone()
        })),
        "notify_question"
    );
    assert_eq!(alert_text(&AgentEvent::TurnDone(s.clone())), "notify_done");
    assert_eq!(alert_subtitle(&event, false).as_deref(), Some("Projeto"));
    assert_eq!(
        alert_subtitle(&event, true).as_deref(),
        Some("Projeto · servidor:4747")
    );
    let no = AgentEvent::TurnDone(SessionItem { project: None, ..s });
    assert_eq!(alert_subtitle(&no, false), None);
    assert_eq!(alert_subtitle(&no, true).as_deref(), Some("servidor:4747"));
}
#[test]
fn kotlin_model_brand_cases() {
    for (input, brand) in [
        ("claude-opus-5-5", "claude"),
        ("anthropic/claude-sonnet-5-5", "claude"),
        ("gpt-5.5", "openai"),
        ("o3-mini", "openai"),
        ("codex-mini-latest", "openai"),
        ("gemini-3-pro", "gemini"),
    ] {
        assert_eq!(model_brand(Some(input)), Some(brand));
    }
    for input in [Some("ollama-o1x"), Some("llama3"), None] {
        assert_eq!(model_brand(input), None);
    }
    assert_eq!(
        split_role("openai-codex/gpt-6-luna:high"),
        ("openai-codex/gpt-6-luna", Some("high"))
    );
    assert_eq!(split_role("ollama/llama3:8b"), ("ollama/llama3:8b", None));
    assert_eq!(
        split_role("ollama/llama3:8b:low"),
        ("ollama/llama3:8b", Some("low"))
    );
    assert_eq!(
        split_role("typesafe/jev-latest"),
        ("typesafe/jev-latest", None)
    );
    assert_eq!(join_role("a/b", Some("max")), "a/b:max");
    assert_eq!(join_role("a/b", None), "a/b");
    assert_eq!(
        snapshot_models(r#"{"agents":[{"models":["b/y","a/x"]},{"models":["a/x",3]},{}]}"#)
            .unwrap(),
        ["a/x", "b/y"]
    );
}
#[test]
fn kotlin_model_picker_cases() {
    let models = [
        "openrouter/google/gemini-2.5-flash:batch",
        "anthropic/claude-opus-4-8",
        "openrouter/google/gemini-2.5-flash",
        "anthropic/claude-haiku-4-5-20251001",
        "anthropic/claude-opus-5",
        "anthropic/claude-haiku-4-5",
        "anthropic/claude-opus-5-5",
        "anthropic/claude-opus-4-20250514",
        "google-antigravity/gemini-3.8-flash",
        "google-antigravity/gemini-3.5-flash-lite",
        "google-antigravity/gemini-3.5-flash",
    ]
    .map(str::to_owned);
    let expected = vec![
        (
            "anthropic",
            vec![
                "anthropic/claude-haiku-4-5",
                "anthropic/claude-opus-5-5",
                "anthropic/claude-opus-5",
                "anthropic/claude-opus-4-8",
                "anthropic/claude-opus-4-20250514",
            ],
        ),
        (
            "google-antigravity",
            vec![
                "google-antigravity/gemini-3.8-flash",
                "google-antigravity/gemini-3.5-flash",
                "google-antigravity/gemini-3.5-flash-lite",
            ],
        ),
        ("openrouter/google", vec!["openrouter/google/gemini-2.5-flash"]),
    ]
    .into_iter()
    .map(|(p, ms)| (p.to_owned(), ms.into_iter().map(str::to_owned).collect()))
    .collect::<Vec<_>>();
    assert_eq!(role_model_groups(&models, None), expected);
    assert_eq!(
        role_model_groups(
            &["anthropic/claude-haiku-4-5".into()],
            Some("anthropic/claude-haiku-4-5-20251001")
        )[0]
        .1,
        [
            "anthropic/claude-haiku-4-5",
            "anthropic/claude-haiku-4-5-20251001"
        ]
    );
}
#[test]
fn openrouter_model_picker_filters_and_keeps_exact_selection() {
    let models = [
        "openrouter/anthropic/claude-opus-5",
        "openrouter/anthropic/claude-opus-5",
        "openrouter/anthropic/claude-opus-5-20261001",
        "openrouter/anthropic/claude-sonnet-5-20261001",
        "openrouter/anthropic/claude-opus-5:batch",
        "openrouter/anthropic/claude-opus-5:free",
        "openrouter/anthropic/claude-opus-5:extended",
        "openrouter/anthropic/claude-opus-5:thinking",
        "openrouter/anthropic/claude-opus-5:exact",
        "openrouter/~anthropic/claude-opus-5",
        "openrouter/google/gemini-3-pro",
        "openrouter/auto",
        "anthropic/claude-opus-5",
        "local/model:free",
        "local/model:extended",
        "local/model:batch",
        "local/model:thinking",
        "local/model:exact",
        "invalid",
    ]
    .map(str::to_owned);
    let groups = role_model_groups(&models, None);
    assert_eq!(
        groups.iter().map(|(group, _)| group.as_str()).collect::<Vec<_>>(),
        ["anthropic", "local", "openrouter", "openrouter/anthropic", "openrouter/google"]
    );
    let shown: Vec<_> = groups.iter().flat_map(|(_, models)| models.iter().map(String::as_str)).collect();
    for hidden in [
        "openrouter/anthropic/claude-opus-5-20261001",
        "openrouter/anthropic/claude-opus-5:batch",
        "openrouter/anthropic/claude-opus-5:free",
        "openrouter/anthropic/claude-opus-5:extended",
        "openrouter/~anthropic/claude-opus-5",
        "local/model:batch",
        "invalid",
    ] {
        assert!(!shown.contains(&hidden), "{hidden}");
        if hidden.contains('/') {
            let kept = role_model_groups_filtered(&models, Some(hidden), "no match");
            assert_eq!(kept.len(), 1);
            assert_eq!(kept[0].1, [hidden]);
        }
    }
    for visible in [
        "openrouter/anthropic/claude-sonnet-5-20261001",
        "openrouter/anthropic/claude-opus-5:thinking",
        "openrouter/anthropic/claude-opus-5:exact",
        "local/model:free",
        "local/model:extended",
        "local/model:thinking",
        "local/model:exact",
    ] {
        assert!(shown.contains(&visible), "{visible}");
    }
    assert_eq!(shown.iter().filter(|model| **model == "openrouter/anthropic/claude-opus-5").count(), 1);
    let filtered = role_model_groups_filtered(&models, None, "  OPENROUTER \t Google\n gemini ");
    assert_eq!(filtered, vec![("openrouter/google".into(), vec!["openrouter/google/gemini-3-pro".into()])]);
    assert!(role_model_groups_filtered(&models, None, "unknown model").is_empty());
    let selected = "openrouter/absent/model:free";
    assert_eq!(
        role_model_groups_filtered(&models, Some(selected), "unknown model"),
        vec![("openrouter/absent".into(), vec![selected.into()])]
    );
    for (model, label) in [
        ("openrouter/anthropic/claude-opus-5", "Claude Opus 5"),
        ("openrouter/~anthropic/claude-opus-5", "Claude Opus 5"),
        ("openrouter/auto", "Auto"),
        ("custom/vendor/model", "Model"),
        ("local/model:exact", "Model · Exact"),
        ("model", "Model"),
    ] {
        assert_eq!(model_option_label(model), label);
    }
    assert_eq!(provider_label("openrouter/anthropic"), "OpenRouter · Anthropic");
    assert_eq!(model_label("openrouter/anthropic/claude-opus-5"), "OpenRouter · Anthropic · Claude Opus 5");
}
#[test]
fn model_display_names_preserve_versions_variants_and_raw_ids() {
    let cases = [
        ("openrouter/anthropic/claude-opus-5.5", "Claude Opus 5.5"),
        ("anthropic/claude-sonnet-4-5-20250929", "Claude Sonnet 4.5 · 20250929"),
        ("claude-3-5-sonnet-20241022", "Claude Sonnet 3.5 · 20241022"),
        ("openai-codex/gpt-6.1-sol", "GPT-6.1 Sol"),
        ("openrouter/google/gemini-3.8-flash", "Gemini 3.8 Flash"),
        ("openrouter/deepseek/deepseek-v4-flash:free", "DeepSeek V4 Flash · Free"),
        ("openrouter/meta-llama/llama-3.3-70b-instruct", "Llama 3.3 70B Instruct"),
        ("openrouter/z-ai/glm-4.6", "GLM 4.6"),
        ("openrouter/minimax/minimax-m2.5", "MiniMax M2.5"),
        ("openrouter/qwen/qwen3-vl-235b-a22b-instruct", "Qwen3 VL 235B A22b Instruct"),
        ("vendor/custom_model-v2:exact", "Custom Model V2 · Exact"),
        ("anthropic/claude-3-5-sonnet-20241022:thinking", "Claude Sonnet 3.5 · 20241022 · Thinking"),
        ("claude-sonnet-4-5:thinking", "Claude Sonnet 4.5 · Thinking"),
        ("deepseek/deepseek-v4-flash:free", "DeepSeek V4 Flash · Free"),
    ];
    for (model, expected) in cases {
        assert_eq!(model_option_label(model), expected, "{model}");
        let selected = model_label(model);
        assert!(selected.ends_with(expected), "{model}: {selected}");
        if !model.contains('/') {
            assert_eq!(selected, expected);
            continue;
        }
        let models = [model.to_owned()];
        let expected_group = vec![(model_group_key(model), vec![model.to_owned()])];
        assert_eq!(role_model_groups(&models, Some(model)), expected_group);
        assert_eq!(
            role_model_groups_filtered(&models, Some(model), expected),
            expected_group
        );
        if !(model.starts_with("openrouter/") && model.ends_with(":free")) {
            assert_eq!(
                role_model_groups_filtered(&models, None, expected),
                expected_group
            );
            assert_eq!(
                role_model_groups_filtered(&models, None, model),
                expected_group
            );
        }
    }
}
#[test]
fn kotlin_status_and_clock_cases() {
    for (ms, text) in [
        (-500, "0:00"),
        (7999, "0:07"),
        (751000, "12:31"),
        (3730000, "1:02:10"),
    ] {
        assert_eq!(stopwatch(ms), text);
    }
    assert_eq!(
        status_line_text(Some("  Lendo arquivo... ")).as_deref(),
        Some("Lendo arquivo")
    );
    assert_eq!(
        status_line_text(Some("Pensando…")).as_deref(),
        Some("Pensando")
    );
    for text in [Some("  "), Some("…"), None] {
        assert_eq!(status_line_text(text), None);
    }
    for (server_now, now, expected) in [
        (2000000, 2005080, 1005000),
        (2000000, 2000500, 1001000),
        (2000000, 1999500, 1000000),
        (0, 9999999, 1000000),
    ] {
        assert_eq!(local_time(1000000, server_now, now), expected);
    }
    let zone = FixedOffset::west_opt(3 * 3600).unwrap();
    let now = 1_793_052_000_000;
    assert_eq!(
        entry_time_in(now - 5 * 60000, now, &zone).as_deref(),
        Some("18:55")
    );
    assert_eq!(
        entry_time_in(now - 24 * 3600000, now, &zone).as_deref(),
        Some("25/10 19:00")
    );
    let midnight = zone
        .with_ymd_and_hms(2026, 11, 9, 0, 0, 0)
        .single()
        .unwrap()
        .timestamp_millis();
    assert_eq!(
        entry_time_in(midnight - 1, midnight, &zone).as_deref(),
        Some("08/11 23:59")
    );
}
#[test]
fn deadline_backoff_relative_context_boundaries() {
    assert_eq!(retry_after_deadline(Some("-1"), 100), 100);
    assert_eq!(retry_after_deadline(Some("35"), 100), 35100);
    assert_eq!(retry_after_deadline(None, 100), 60100);
    assert_eq!(
        retry_after_deadline(Some("9223372036854775807"), 100),
        (i64::MAX / 1000) * 1000 + 100
    );
    assert_eq!(
        retry_after_deadline(Some("invalid"), i64::MAX),
        i64::MAX.wrapping_add(60000)
    );
    let at = chrono::DateTime::parse_from_rfc2822("Wed, 21 Oct 2015 07:28:00 GMT")
        .unwrap()
        .timestamp_millis();
    assert_eq!(
        retry_after_deadline(Some("Wed, 21 Oct 2015 07:28:00 GMT"), at - 100),
        at
    );
    assert_eq!(
        retry_after_deadline(Some("Wed, 21 Oct 2015 07:28:00 GMT"), at + 100),
        at + 100
    );
    assert_eq!(retry_after_millis(Some("9999999"), 0), 86400000);
    assert_eq!(retry_after_millis(Some("-1"), 0), 0);
    assert_eq!(retry_after_millis(None, 0), 0);
    for (minutes, key, amount) in [
        (0, "time_now", None),
        (35, "time_minutes", Some(35)),
        (60, "time_hours", Some(1)),
        (1439, "time_hours", Some(23)),
        (1440, "time_days", Some(1)),
    ] {
        let time = relative_time(0, minutes * 60000);
        assert_eq!((time.key, time.amount), (key, amount));
    }
    for (tokens, tone, pct) in [
        (0, "primary", 0),
        (499, "primary", 49),
        (500, "warning", 50),
        (799, "warning", 79),
        (800, "error", 80),
        (2000, "error", 100),
    ] {
        let ring = context_ring(tokens, 1000).unwrap();
        assert_eq!((ring.tone, ring.percent), (tone, pct));
    }
    assert!(context_ring(-1, 100).is_none());
    assert!(context_ring(1, 0).is_none());
    assert!(stop_visible(Some("working")));
    assert!(stop_visible(Some("busy")));
    assert!(stop_visible(Some("shell")));
    assert!(!stop_visible(Some("running")));
    assert!(shake_requires_confirmation(true));
    assert!(!shake_requires_confirmation(false));
}
#[test]
fn summary_utf16_and_failure_boundaries() {
    assert_eq!(zaplaud_core::rules::kotlin_hash("😀"), 1772899);
    let mut entry = e("a", "assistant", &"x".repeat(500), None);
    assert!(!wants_summary(&entry, true));
    entry.text.push('x');
    assert!(wants_summary(&entry, true));
    assert!(!wants_summary(&entry, false));
    let key = summary_key(&entry);
    entry.text.push('y');
    assert_ne!(summary_key(&entry), key);
    entry.text = format!("{}😀", "x".repeat(32767));
    let text = summary_text(&entry);
    assert_eq!(text.len(), 32768);
    assert_eq!(text[32767], 0xd83d);
    let (state, effects) = reduce_summary(
        SummaryState::default(),
        SummaryEvent::Observe {
            entry: entry.clone(),
            enabled: true,
            cached: None,
        },
    );
    assert!(state.pending);
    assert_eq!(effects.len(), 1);
    let (state, _) = reduce_summary(
        state,
        SummaryEvent::Failed {
            key: summary_key(&entry),
            cancelled: false,
        },
    );
    let (_, effects) = reduce_summary(
        state,
        SummaryEvent::Observe {
            entry,
            enabled: true,
            cached: None,
        },
    );
    assert!(effects.is_empty());
}
#[test]
fn attachment_count_size_encoding_and_mime() {
    assert_eq!(MAX_ATTACHMENT_SIZE, 15728640);
    assert_eq!(MAX_ATTACHMENTS, 5);
    for (current, added) in [(0, 5), (5, 0), (2, 3), (i32::MAX, i32::MAX)] {
        assert!(check_attachment_count(current, added).is_ok());
    }
    for (current, added) in [(-1, 0), (0, -1), (5, 1)] {
        assert_eq!(
            check_attachment_count(current, added),
            Err("too many attachments")
        );
    }
    for size in [Some(0), Some(-1), Some(MAX_ATTACHMENT_SIZE + 1)] {
        assert!(validate_attachment_size(size).is_err());
    }
    for size in [None, Some(1), Some(MAX_ATTACHMENT_SIZE)] {
        assert!(validate_attachment_size(size).is_ok());
    }
    assert_eq!(
        encode_attachment_name("ação +#?.txt"),
        "a%C3%A7%C3%A3o%20%2B%23%3F.txt"
    );
    assert_eq!(encode_attachment_name("AZaz09-_.!~*'()"), "AZaz09-_.!~*'()");
    assert_eq!(
        new_attachment_uris(&["uri".into(), "uri".into()], &[]).unwrap(),
        ["uri"]
    );
    assert!(new_attachment_uris(&(1..=6).map(|i| i.to_string()).collect::<Vec<_>>(), &[]).is_err());
    let mime = HashMap::from([
        ("txt".into(), "text/plain".into()),
        ("png".into(), "image/png".into()),
    ]);
    assert_eq!(guess_mime("small.txt", &mime), "text/plain");
    assert_eq!(guess_mime("PHOTO.PNG", &mime), "image/png");
    assert_eq!(guess_mime("no-type", &mime), "application/octet-stream");
    let mut body = AttachmentSize::new(Some(3)).unwrap();
    assert_eq!(body.chunk(3), Ok(3));
    assert_eq!(body.finish(), Ok(()));
    assert!(body.chunk(1).is_err());
    assert_eq!(body.count, 3);
    assert!(AttachmentSize::new(None).unwrap().finish().is_err());
    let mut unknown = AttachmentSize::new(None).unwrap();
    assert!(unknown.chunk(MAX_ATTACHMENT_SIZE as usize).is_ok());
    assert!(unknown.chunk(1).is_err());
}
#[test]
fn avatar_name_hash_badge_boundary() {
    let mut s = session("idle", None);
    s.title = "Ana".into();
    s.model = Some("claude-opus".into());
    assert_eq!(avatar_selection(&s, 31.0).brand, None);
    let a = avatar_selection(&s, 32.0);
    assert_eq!(a.initial, "A");
    assert_eq!(a.brand, Some("claude"));
    assert_eq!(a.badge_size, 32.0 * 0.42);
    s.character_name = Some("Outra".into());
    assert_eq!(avatar_selection(&s, 48.0).name, "Outra");
}
fn entry_json(e: &TerminalEntry) -> Value {
    json!({"id":e.id,"kind":e.kind,"text":e.text,"title":e.title,"toolUseId":e.tool_use_id,"truncated":e.truncated,"at":e.at})
}
#[test]
fn kotlin_fixture_conversation_golden() {
    let fixture: Value =
        serde_json::from_str(include_str!("fixtures/conversation-init.json")).unwrap();
    let entries: Vec<_> = fixture["entries"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(TerminalEntry::parse)
        .collect();
    let mut actual = json!({});
    for view in ["all", "prompts", "no_tools", "invalid"] {
        let visible = visible_entries(&entries, view);
        let rows:Vec<_>=group_turn(&visible).into_iter().map(|r|json!({"entry":entry_json(&r.entry),"count":r.count,"tools":r.tools.iter().map(entry_json).collect::<Vec<_>>() })).collect();
        actual[view] =
            json!({"visible":visible.iter().map(entry_json).collect::<Vec<_>>(),"rows":rows});
    }
    let expected: Value =
        serde_json::from_str(include_str!("golden/conversation-kotlin.json")).unwrap();
    assert_eq!(actual, expected);
}
#[test]
fn kotlin_fixture_notification_golden() {
    let origin = "http://fixture.test";
    let previous = Snapshot::parse_at(origin, include_str!("fixtures/snapshot.json"), 0).unwrap();
    let current =
        Snapshot::parse_at(origin, include_str!("fixtures/snapshot-permission.json"), 0).unwrap();
    let actual: Vec<_> = agent_events(&watch_of(&previous.sessions), &current.sessions)
        .into_iter()
        .map(|event| {
            let (kind, s) = match event {
                AgentEvent::Permission(s) => ("permission", s),
                AgentEvent::TurnDone(s) => ("done", s),
            };
            json!({"kind":kind,"key":s.key.serialize().unwrap(),"permissionId":s.permission_id})
        })
        .collect();
    let expected: Value = serde_json::from_str(include_str!("golden/alerts-kotlin.json")).unwrap();
    assert_eq!(json!(actual), expected);
}

#[test]
fn kotlin_platform_golden() {
    let golden: Value = serde_json::from_str(include_str!("golden/platform-kotlin.json")).unwrap();
    let zone = FixedOffset::west_opt(3 * 3600).unwrap();
    assert_eq!(
        deadline_in(1793052000000, &zone).unwrap(),
        golden["deadline"]
    );
    let mime = HashMap::from([
        ("txt".into(), "text/plain".into()),
        ("png".into(), "image/png".into()),
        ("js".into(), "text/javascript".into()),
        ("json".into(), "application/json".into()),
    ]);
    for (name, expected) in golden["mime"].as_object().unwrap() {
        assert_eq!(guess_mime(name, &mime), expected.as_str().unwrap());
    }
}

#[test]
fn model_numeric_versions_and_alphanumeric_suffixes_have_consistent_order() {
    let expected: Vec<String> = ["openrouter/vendor/model-2", "openrouter/vendor/model-1", "openrouter/vendor/model-1b"]
        .into_iter().map(str::to_owned).collect();
    for offset in 0..expected.len() {
        let mut input = expected.clone();
        input.rotate_left(offset);
        for _ in 0..2 {
            assert_eq!(zaplaud_core::rules::composer::role_model_groups(&input, None)[0].1, expected);
            input.reverse();
        }
    }
}
