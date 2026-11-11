use std::{collections::BTreeMap, fs};
use zaplaud_core::model::*;
use zaplaud_store::{DesktopDirs, LocalStateStore, StoreError, storage_hash};
fn store(root: &std::path::Path) -> LocalStateStore {
    LocalStateStore::with_dirs(DesktopDirs {
        data: root.join("data"),
        cache: root.join("cache"),
    })
    .unwrap()
}
fn key(origin: &str) -> SessionKey {
    SessionKey {
        origin: origin.into(),
        provider: "omp".into(),
        account: "test".into(),
        session_id: "same/é😀\"".into(),
    }
}
#[test]
fn all_operations_persist_and_removal_is_isolated() -> Result<(), StoreError> {
    let root = tempfile::tempdir()?;
    let a = key("https://one.invalid");
    let b = key("https://two.invalid");
    let s = store(root.path());
    assert_eq!(s.theme()?, "system");
    assert!(!s.summarize()?);
    assert!(!s.notifications_enabled()?);
    assert_eq!(s.notify_events()?, (true, true));
    assert_eq!(s.deliver_as()?, "aside");
    assert_eq!(s.default_view()?, "all");
    s.set_theme("dark")?;
    s.set_summarize(true)?;
    s.set_notifications_enabled(true)?;
    s.set_notify_events(false, true)?;
    s.set_deliver_as("steer")?;
    s.set_default_view("no_tools")?;
    s.set_retry_until(&a.origin, 456)?;
    let attachment = Attachment {
        uri: "file:///user/file.txt".into(),
        name: "file.txt".into(),
        mime: "text/plain".into(),
        size: Some(12),
        persistent: true,
        identity: Some(AttachmentIdentity {
            modified_nanos: 123_456,
            device: 7,
            inode: 42,
        }),
    };
    for k in [&a, &b] {
        s.add_server(&k.origin)?;
        s.add_server(&k.origin)?;
        s.update_message(k, |_| MessageLocalState {
            draft: MessageDraft {
                text: k.origin.clone(),
                revision: 3,
                attachments: vec![attachment.clone()],
            },
            operation: Some(MessageOperation {
                id: None,
                ..Default::default()
            }),
            control: Some(MessageOperation {
                id: Some("stop".into()),
                status: "queued".into(),
                revision: 2,
                error: Some("error".into()),
            }),
        })?;
        s.set_session_view(k, "prompts")?;
        s.set_session_deliver_as(k, "followUp")?;
        s.set_seen_replies(&k.origin, &BTreeMap::from([(s.seen_id(k)?, 123)]))?;
        let write = s.conversation_write(&k.origin, s.conversation_epoch(&k.origin));
        s.set_conversation(
            k,
            &[TerminalEntry {
                id: "cached".into(),
                kind: "assistant".into(),
                text: k.origin.clone(),
                at: Some(99),
                ..Default::default()
            }],
            false,
            &write,
            &BTreeMap::new(),
        )?;
    }
    s.set_last_session(Some(&b))?;
    let reopened = store(root.path());
    assert_eq!(
        reopened.servers()?,
        vec![a.origin.clone(), b.origin.clone()]
    );
    assert_eq!(reopened.message(&a)?.draft.revision, 3);
    assert_eq!(reopened.message(&a)?.draft.attachments, vec![attachment]);
    assert_eq!(reopened.message(&a)?.operation.unwrap().status, "unknown");
    assert_eq!(
        reopened.message(&a)?.control.unwrap().error.as_deref(),
        Some("error")
    );
    assert!(reopened.uses_attachment("file:///user/file.txt")?);
    assert_eq!(reopened.last_session()?, Some(b.clone()));
    assert_eq!(reopened.theme()?, "dark");
    assert!(reopened.summarize()?);
    assert!(reopened.notifications_enabled()?);
    assert_eq!(reopened.notify_events()?, (false, true));
    assert_eq!(reopened.deliver_as()?, "steer");
    assert_eq!(reopened.default_view()?, "no_tools");
    assert_eq!(reopened.retry_until(&a.origin)?, 456);
    assert_eq!(reopened.session_view(&a)?, "prompts");
    assert_eq!(reopened.session_deliver_as(&a)?, "followUp");
    assert_eq!(
        reopened.seen_replies(&a.origin)?.values().next(),
        Some(&123)
    );
    s.remove_server(&a.origin)?;
    let r = store(root.path());
    assert_eq!(r.servers()?, vec![b.origin.clone()]);
    assert_eq!(r.message(&a)?, MessageLocalState::default());
    assert!(r.conversation(&a)?.is_none());
    assert_eq!(r.conversation(&b)?.unwrap().entries[0].text, b.origin);
    assert_eq!(r.session_view(&b)?, "prompts");
    assert!(r.seen_replies(&a.origin)?.is_empty());
    assert_eq!(r.last_session()?, Some(b));
    r.clear()?;
    let r = store(root.path());
    assert!(r.servers()?.is_empty());
    assert!(r.last_session()?.is_none());
    assert_eq!(r.theme()?, "dark");
    assert!(r.summarize()?);
    assert_eq!(r.retry_until(&a.origin)?, 456);
    assert!(
        !fs::read_dir(root.path().join("data"))?.any(|f| f
            .unwrap()
            .path()
            .extension()
            .is_some_and(|e| e == "tmp"))
    );
    Ok(())
}
#[test]
fn corrupt_state_errors_without_overwrite_but_corrupt_cache_is_ignored() -> Result<(), StoreError> {
    let root = tempfile::tempdir()?;
    let s = store(root.path());
    let path = root.path().join("data/local_state.json");
    fs::write(&path, b"{\"servers\":")?;
    assert!(s.add_server("new").is_err());
    assert_eq!(fs::read(&path)?, b"{\"servers\":");
    fs::remove_file(path)?;
    let k = key("https://one.invalid");
    s.add_server(&k.origin)?;
    let write = s.conversation_write(&k.origin, 0);
    s.set_conversation(&k, &[], false, &write, &BTreeMap::new())?;
    for item in fs::read_dir(root.path().join("data"))? {
        let item = item?;
        if item
            .file_name()
            .to_string_lossy()
            .starts_with("conversation-")
        {
            fs::write(item.path(), b"{")?;
        }
    }
    assert!(s.conversation(&k)?.is_none());
    Ok(())
}
#[test]
fn legacy_migration_preserves_unknown_fields() -> Result<(), StoreError> {
    let root = tempfile::tempdir()?;
    let s = store(root.path());
    let k = key("https://one.invalid");
    let hash = s.seen_id(&k)?;
    fs::write(
        root.path().join("data/local_state.json"),
        format!(
            r#"{{"origin":"{}","seen_replies":{{"{hash}":123}},"unknown":{{"future":true}},"view_{hash}":"prompts"}}"#,
            k.origin
        ),
    )?;
    assert_eq!(s.servers()?, vec![k.origin.clone()]);
    assert_eq!(s.seen_replies(&k.origin)?[&hash], 123);
    assert_eq!(s.session_view(&k)?, "prompts");
    s.set_last_session(Some(&k))?;
    let value: serde_json::Value =
        serde_json::from_slice(&fs::read(root.path().join("data/local_state.json"))?)?;
    assert_eq!(value["unknown"]["future"], true);
    assert_eq!(
        storage_hash("abc"),
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    );
    Ok(())
}
#[test]
fn old_writes_cannot_restore_removed_origin_and_cache_retains_500() -> Result<(), StoreError> {
    let root = tempfile::tempdir()?;
    let s = store(root.path());
    let k = key("one");
    s.add_server(&k.origin)?;
    let epoch = s.conversation_epoch(&k.origin);
    let old = s.conversation_write(&k.origin, epoch);
    s.remove_server(&k.origin)?;
    assert!(matches!(
        s.set_seen_replies(&k.origin, &BTreeMap::from([("x".into(), 12)])),
        Err(StoreError::ConnectionChanged)
    ));
    s.add_server(&k.origin)?;
    s.set_conversation(&k, &[], false, &old, &BTreeMap::new())?;
    s.set_last_session_with_write(Some(&k), &old)?;
    assert!(s.conversation(&k)?.is_none());
    assert!(s.last_session()?.is_none());
    assert!(s.seen_replies(&k.origin)?.is_empty());
    let entries: Vec<_> = (0..503)
        .map(|i| TerminalEntry {
            id: i.to_string(),
            kind: "assistant".into(),
            text: "😀".into(),
            ..Default::default()
        })
        .collect();
    let write = s.conversation_write(&k.origin, s.conversation_epoch(&k.origin));
    s.set_conversation(
        &k,
        &entries,
        false,
        &write,
        &BTreeMap::from([
            ("502:1772899".into(), "summary".into()),
            ("0:1772899".into(), "gone".into()),
        ]),
    )?;
    let cache = s.conversation(&k)?.unwrap();
    assert_eq!(cache.entries.len(), 500);
    assert!(cache.truncated);
    assert_eq!(cache.entries[0].id, "3");
    assert_eq!(cache.summaries.len(), 1);
    assert_eq!(cache.summaries["502:1772899"], "summary");
    Ok(())
}
#[cfg(unix)]
#[test]
fn permissions_are_private() -> Result<(), StoreError> {
    use std::os::unix::fs::PermissionsExt;
    let root = tempfile::tempdir()?;
    let s = store(root.path());
    s.add_server("one")?;
    for item in fs::read_dir(root.path().join("data"))? {
        assert_eq!(item?.metadata()?.permissions().mode() & 0o777, 0o600);
    }
    for name in ["data", "cache"] {
        assert_eq!(
            fs::metadata(root.path().join(name))?.permissions().mode() & 0o777,
            0o700
        );
    }
    Ok(())
}
#[test]
fn concurrent_read_modify_write_does_not_lose_servers() -> Result<(), StoreError> {
    let root = tempfile::tempdir()?;
    let s = store(root.path());
    std::thread::scope(|scope| {
        for n in 0..12 {
            let s = s.clone();
            scope.spawn(move || s.add_server(&format!("origin-{n}")).unwrap());
        }
    });
    assert_eq!(s.servers()?.len(), 12);
    Ok(())
}

#[test]
fn environment_child() -> Result<(), StoreError> {
    let Ok(mode) = std::env::var("ZAPLAUD_STORE_TEST_CHILD") else {
        return Ok(());
    };
    let store = LocalStateStore::new()?;
    if std::env::var("ZAPLAUD_KEYRING").as_deref() == Ok("memory") {
        let cookies = zaplaud_store::SecureSessionStorage::new();
        cookies.save(
            "https://memory.invalid",
            "habblaud_remote=fictitious; Path=/",
        );
        assert!(
            zaplaud_store::SecureSessionStorage::new()
                .load("https://memory.invalid")
                .is_some()
        );
        cookies.clear("https://memory.invalid");
        assert!(cookies.load("https://memory.invalid").is_none());
    }
    if mode == "prefs" {
        assert_eq!(store.theme()?, "dark");
        assert!(store.summarize()?);
        assert_eq!(store.default_view()?, "prompts");
        assert_eq!(store.notify_events()?, (false, true));
        assert_eq!(store.retry_until("https://prefs.invalid")?, 0);
        store.set_theme("light")?;
    } else {
        for n in 0..25 {
            store.add_server(&format!("{mode}-{n}"))?;
        }
    }
    Ok(())
}

#[test]
fn prefs_import_once_and_cross_process_lock() -> Result<(), StoreError> {
    let root = tempfile::tempdir()?;
    let home = tempfile::tempdir()?;
    let prefs = home
        .path()
        .join(".java/.userPrefs/br/com/xlinx/zaplaud/prefs.xml");
    fs::create_dir_all(prefs.parent().unwrap())?;
    let xml = format!(
        r#"<?xml version="1.0"?><!DOCTYPE map SYSTEM "http://java.sun.com/dtd/preferences.dtd"><map MAP_XML_VERSION="1.0"><entry key="theme" value="dark"/><entry key="summarize_replies" value="true"/><entry key="view" value="prompts"/><entry key="notify_permission" value="false"/><entry key="retry_{}" value="456"/></map>"#,
        storage_hash("https://prefs.invalid")
    );
    fs::write(&prefs, &xml)?;
    let command = |mode: &str| {
        let mut cmd = std::process::Command::new(std::env::current_exe().unwrap());
        cmd.args(["--exact", "environment_child"])
            .env("HOME", home.path())
            .env("ZAPLAUD_DATA_DIR", root.path())
            .env("ZAPLAUD_STORE_TEST_CHILD", mode);
        cmd
    };
    assert!(command("prefs").status()?.success());
    assert_eq!(fs::read_to_string(&prefs)?, xml);
    let s = store(root.path());
    assert_eq!(s.theme()?, "light");
    fs::write(&prefs, "<map><entry key=\"theme\" value=\"dark\"/></map>")?;
    assert_eq!(store(root.path()).theme()?, "light");
    let mut children = Vec::new();
    for mode in ["a", "b", "c", "d"] {
        children.push(command(mode).spawn()?);
    }
    for mut child in children {
        assert!(child.wait()?.success());
    }
    assert_eq!(s.servers()?.len(), 100);
    Ok(())
}
