//! Executado explicitamente após gerar o fixture com LocalStateStore.jvm.kt real.
use std::{collections::BTreeMap, fs, path::PathBuf};
use zaplaud_core::model::{SessionKey, TerminalEntry};
use zaplaud_store::{DesktopDirs, LocalStateStore, StoreError};
#[test]
#[ignore = "requer fixture gravado pelo teste JVM real; configure ZAPLAUD_JVM_INTEROP_DIR"]
fn reads_jvm_and_writes_back_for_jvm() -> Result<(), StoreError> {
    let root = PathBuf::from(
        std::env::var_os("ZAPLAUD_JVM_INTEROP_DIR").expect("fixture JVM obrigatório"),
    );
    let store = LocalStateStore::with_dirs(DesktopDirs {
        data: root.join("data"),
        cache: root.join("cache"),
    })?;
    let key = SessionKey {
        origin: "https://interop.invalid".into(),
        provider: "omp".into(),
        account: "account/é😀".into(),
        session_id: "session/\"😀".into(),
    };
    assert_eq!(store.servers()?, vec![key.origin.clone()]);
    let message = store.message(&key)?;
    assert_eq!(message.draft.text, "JVM criou 😀");
    assert_eq!(message.draft.revision, 7);
    assert_eq!(message.draft.attachments[0].size, Some(42));
    assert_eq!(message.operation.unwrap().id.as_deref(), Some("job"));
    assert_eq!(message.control.unwrap().status, "unknown");
    assert_eq!(store.session_view(&key)?, "prompts");
    assert_eq!(store.session_deliver_as(&key)?, "followUp");
    assert_eq!(store.seen_replies(&key.origin)?[&store.seen_id(&key)?], 123);
    assert_eq!(store.last_session()?, Some(key.clone()));
    assert_eq!(
        store.conversation(&key)?.unwrap().entries[0].text,
        "JVM cache"
    );
    store.update_message(&key, |mut m| {
        m.draft.text = "Rust reescreveu 😀".into();
        m.draft.revision = 8;
        m
    })?;
    store.set_session_view(&key, "no_tools")?;
    let write = store.conversation_write(&key.origin, store.conversation_epoch(&key.origin));
    store.set_conversation(
        &key,
        &[TerminalEntry {
            id: "rust".into(),
            kind: "assistant".into(),
            text: "Rust cache".into(),
            ..Default::default()
        }],
        false,
        &write,
        &BTreeMap::new(),
    )?;
    fs::write(root.join("data/rust-written"), b"ok")?;
    Ok(())
}
