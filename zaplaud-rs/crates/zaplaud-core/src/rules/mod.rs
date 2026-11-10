//! Regras puras: relógio, rede e armazenamento são entradas ou efeitos explícitos.
pub mod alerts;
pub mod aside;
pub mod attachments;
pub mod composer;
pub mod conversation;
pub mod list;
pub mod permission;
pub mod time;
pub mod url_policy;

pub use alerts::{agent_events, watch_of};
pub use list::{by_last_message, unread_count, updated_seen};

/// Hash de String Kotlin/JVM: unidades UTF-16 e overflow de i32.
pub fn kotlin_hash(value: &str) -> i32 {
    value.encode_utf16().fold(0_i32, |hash, unit| {
        hash.wrapping_mul(31).wrapping_add(i32::from(unit))
    })
}

/// Kotlin Char.isWhitespace inclui os separadores Unicode não quebráveis.
pub fn kotlin_blank(value: &str) -> bool {
    value.chars().all(kotlin_whitespace)
}

pub fn kotlin_whitespace(value: char) -> bool {
    matches!(value, '\u{0009}'..='\u{000d}' | '\u{001c}'..='\u{0020}' | '\u{00a0}' | '\u{1680}' | '\u{2000}'..='\u{200a}' | '\u{2028}' | '\u{2029}' | '\u{202f}' | '\u{205f}' | '\u{3000}')
}

pub fn kotlin_trim(value: &str) -> &str {
    value.trim_matches(kotlin_whitespace)
}

pub fn utf16_len(value: &str) -> usize {
    value.encode_utf16().count()
}
