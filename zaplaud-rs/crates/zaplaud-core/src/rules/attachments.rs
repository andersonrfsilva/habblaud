use crate::model::Attachment;
pub use crate::model::{MAX_ATTACHMENT_SIZE, MAX_ATTACHMENTS};
use std::collections::{HashMap, HashSet};

pub fn check_attachment_count(current: i32, added: i32) -> Result<(), &'static str> {
    // Int do Kotlin soma com overflow; os tamanhos reais de seleção são validados separadamente.
    if current < 0 || added < 0 || current.wrapping_add(added) > MAX_ATTACHMENTS as i32 {
        Err("too many attachments")
    } else {
        Ok(())
    }
}
pub fn validate_attachment_size(size: Option<i64>) -> Result<(), &'static str> {
    if size.is_some_and(|size| !(1..=MAX_ATTACHMENT_SIZE).contains(&size)) {
        Err("invalid attachment size")
    } else {
        Ok(())
    }
}
pub fn encode_attachment_name(name: &str) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut encoded = String::with_capacity(name.len());
    for byte in name.bytes() {
        if byte.is_ascii_alphanumeric() || b"-_.!~*'()".contains(&byte) {
            encoded.push(char::from(byte));
        } else {
            encoded.push('%');
            encoded.push(char::from(HEX[(byte >> 4) as usize]));
            encoded.push(char::from(HEX[(byte & 15) as usize]));
        }
    }
    encoded
}
/// O JVM consulta a base MIME do sistema; a camada nativa fornece essa base sem I/O nesta regra.
pub fn guess_mime(name: &str, mime_types: &HashMap<String, String>) -> String {
    let basename = name.rsplit(['/', '\\']).next().unwrap_or(name);
    basename
        .rsplit_once('.')
        .filter(|(_, ext)| !ext.is_empty())
        .and_then(|(_, ext)| mime_types.get(&ext.to_lowercase()))
        .cloned()
        .unwrap_or_else(|| "application/octet-stream".into())
}
pub fn new_attachment_uris(
    uris: &[String],
    existing: &[Attachment],
) -> Result<Vec<String>, &'static str> {
    let mut seen: HashSet<&str> = existing.iter().map(|item| item.uri.as_str()).collect();
    let added: Vec<_> = uris
        .iter()
        .filter(|uri| seen.insert(uri.as_str()))
        .cloned()
        .collect();
    if existing.len() > MAX_ATTACHMENTS || added.len() > MAX_ATTACHMENTS - existing.len() {
        return Err("too many attachments");
    }
    Ok(added)
}
/// Valide antes de abrir, antes de gravar cada bloco, e no EOF.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AttachmentSize {
    pub expected: Option<i64>,
    pub count: i64,
}
impl AttachmentSize {
    pub fn new(expected: Option<i64>) -> Result<Self, &'static str> {
        validate_attachment_size(expected)?;
        Ok(Self { expected, count: 0 })
    }
    pub fn chunk(&mut self, bytes: usize) -> Result<i64, &'static str> {
        let count = i64::try_from(bytes)
            .ok()
            .and_then(|bytes| self.count.checked_add(bytes))
            .filter(|count| {
                *count <= MAX_ATTACHMENT_SIZE && self.expected.is_none_or(|size| *count <= size)
            })
            .ok_or("attachment too large")?;
        self.count = count;
        Ok(count)
    }
    pub fn finish(&self) -> Result<(), &'static str> {
        if self.count > 0 && self.expected.is_none_or(|size| self.count == size) {
            Ok(())
        } else {
            Err("invalid attachment size")
        }
    }
}
