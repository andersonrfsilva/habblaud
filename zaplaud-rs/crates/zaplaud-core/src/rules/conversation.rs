use super::{kotlin_blank, kotlin_hash, utf16_len};
use crate::model::{EntryRow, TerminalEntry, VIEWS};
use std::collections::HashSet;

pub const VIEW_MODES: [&str; 3] = VIEWS;
pub const SUMMARY_MIN_CHARS: usize = 500;
pub const SUMMARY_MAX_CHARS: usize = 32768;

pub fn visible_entries(entries: &[TerminalEntry], view: &str) -> Vec<TerminalEntry> {
    entries
        .iter()
        .filter(|entry| match view {
            "prompts" => matches!(entry.kind.as_str(), "user" | "assistant"),
            "no_tools" => !matches!(entry.kind.as_str(), "tool" | "result"),
            _ => true,
        })
        .cloned()
        .collect()
}

pub fn group_turn(entries: &[TerminalEntry]) -> Vec<EntryRow> {
    let tool_ids: HashSet<_> = entries
        .iter()
        .filter(|entry| entry.kind == "tool")
        .map(|entry| entry.id.as_str())
        .collect();
    let mut rows: Vec<EntryRow> = Vec::new();
    let mut thinking: Option<usize> = None;
    let mut advisor: Option<usize> = None;
    let mut tools: Option<usize> = None;
    for entry in entries {
        if entry.kind == "result"
            && entry
                .tool_use_id
                .as_deref()
                .is_some_and(|id| tool_ids.contains(id))
        {
            continue;
        }
        if entry.kind == "user" {
            thinking = None;
            advisor = None;
            tools = None;
        }
        let group = match entry.kind.as_str() {
            "thinking" => Some(&mut thinking),
            "advisor" => Some(&mut advisor),
            "tool" => Some(&mut tools),
            _ => None,
        };
        if let Some(group) = group {
            if let Some(index) = *group {
                let row = &mut rows[index];
                if matches!(entry.kind.as_str(), "thinking" | "advisor") {
                    if row.count == 1 && kotlin_blank(&row.entry.text) {
                        row.entry.text.clear();
                    }
                    if !kotlin_blank(&entry.text) {
                        if !row.entry.text.is_empty() {
                            row.entry.text.push_str("\n\n");
                        }
                        row.entry.text.push_str(&entry.text);
                    }
                    if entry.kind == "advisor" {
                        row.entry.truncated |= entry.truncated;
                    }
                } else {
                    row.tools.push(entry.clone());
                }
                row.count += 1;
                row.entry.at = entry.at.or(row.entry.at);
            } else {
                *group = Some(rows.len());
                let mut row = EntryRow::new(entry.clone());
                if entry.kind == "tool" {
                    row.tools.push(entry.clone());
                }
                rows.push(row);
            }
        } else {
            rows.push(EntryRow::new(entry.clone()));
        }
    }
    rows
}

pub fn tool_group_time(row: &EntryRow, entries: &[TerminalEntry]) -> Option<i64> {
    row.tools
        .iter()
        .filter_map(|tool| tool.at)
        .chain(
            entries
                .iter()
                .filter(|entry| {
                    entry.kind == "result"
                        && row
                            .tools
                            .iter()
                            .any(|tool| entry.tool_use_id.as_ref() == Some(&tool.id))
                })
                .filter_map(|entry| entry.at),
        )
        .max()
        .or(row.entry.at)
}

pub fn recovered_errors(entries: &[TerminalEntry]) -> HashSet<String> {
    let mut recovered = HashSet::new();
    let mut pending = Vec::new();
    for entry in entries {
        match entry.kind.as_str() {
            "user" => pending.clear(),
            "system" if entry.id.ends_with(":err") => pending.push(entry.id.clone()),
            "assistant" | "tool" | "thinking" => recovered.extend(pending.drain(..)),
            _ => {}
        }
    }
    recovered
}

pub fn summary_key(entry: &TerminalEntry) -> String {
    format!("{}:{}", entry.id, kotlin_hash(&entry.text))
}
pub fn wants_summary(entry: &TerminalEntry, enabled: bool) -> bool {
    enabled && entry.kind == "assistant" && utf16_len(&entry.text) > SUMMARY_MIN_CHARS
}
/// Vec<u16> preserva inclusive um surrogate final cortado pelo take do Kotlin.
pub fn summary_text(entry: &TerminalEntry) -> Vec<u16> {
    entry.text.encode_utf16().take(SUMMARY_MAX_CHARS).collect()
}
/// O começo sem modelo junta parágrafos até passar de BRIEF_MIN e corta em BRIEF_MAX caracteres.
const BRIEF_MIN: usize = 160;
const BRIEF_MAX: usize = 400;

/// Com "Resumir respostas" desligado, a resposta longa mostra o começo dela (`reply_brief`), sem modelo.
pub fn wants_brief(entry: &TerminalEntry, enabled: bool) -> bool {
    !enabled && entry.kind == "assistant" && utf16_len(&entry.text) > SUMMARY_MIN_CHARS
}

fn list_mark(line: &str) -> Option<&str> {
    let rest = line.strip_prefix(['-', '*', '+']).or_else(|| {
        let digits = line.trim_start_matches(|c: char| c.is_ascii_digit());
        (digits.len() < line.len())
            .then_some(digits)?
            .strip_prefix(['.', ')'])
    })?;
    rest.starts_with(char::is_whitespace)
        .then(|| rest.trim_start())
}

/// O começo da resposta em texto corrido, como o `replyBrief` do Kotlin: os agentes respondem com a conclusão
/// primeiro. Pula blocos de código, títulos e tabelas; começa no primeiro parágrafo em prosa. Vazio quando não
/// sobra texto (resposta só de código): fica a resposta inteira.
pub fn reply_brief(text: &str) -> String {
    let mut paragraphs: Vec<Vec<&str>> = Vec::new();
    let mut lines = Vec::new();
    let mut fence = false;
    for raw in text.lines() {
        let line = raw.trim();
        if line.starts_with("```") {
            fence = !fence;
        } else if !fence && !line.is_empty() {
            lines.push(line);
            continue;
        }
        if !lines.is_empty() {
            paragraphs.push(std::mem::take(&mut lines));
        }
    }
    if !lines.is_empty() {
        paragraphs.push(lines);
    }
    let start = paragraphs
        .iter()
        .position(|p| !p[0].starts_with(['|', '#', '>']) && list_mark(p[0]).is_none())
        .unwrap_or(0);
    let mut out = String::new();
    for paragraph in &paragraphs[start.min(paragraphs.len())..] {
        if out.chars().count() >= BRIEF_MIN {
            break;
        }
        for line in paragraph {
            if line.starts_with(['|', '#']) {
                continue;
            }
            let line = line.strip_prefix('>').unwrap_or(line).trim();
            let plain = list_mark(line)
                .unwrap_or(line)
                .replace("**", "")
                .replace('`', "");
            if !plain.trim().is_empty() {
                out.push(' ');
                out.push_str(&plain);
            }
        }
    }
    let brief = out.trim();
    if brief.chars().count() <= BRIEF_MAX {
        return brief.to_owned();
    }
    let head: String = brief.chars().take(BRIEF_MAX - 1).collect();
    let cut = match head.rfind(' ') {
        Some(space) if head[..space].chars().count() * 10 > BRIEF_MAX * 6 => &head[..space],
        _ => &head,
    };
    format!("{}…", cut.trim_end_matches([' ', ',', ';', ':', '.', '-']))
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct SummaryState {
    pub entry_id: String,
    pub key: String,
    pub text: Option<String>,
    pub failed: bool,
    pub pending: bool,
    pub expanded: bool,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SummaryEvent {
    Observe {
        entry: TerminalEntry,
        enabled: bool,
        cached: Option<String>,
    },
    Completed {
        key: String,
        text: String,
    },
    Failed {
        key: String,
        cancelled: bool,
    },
    Toggle,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SummaryEffect {
    Summarize { key: String, text: Vec<u16> },
    PropagateCancellation,
}
pub fn reduce_summary(
    mut state: SummaryState,
    event: SummaryEvent,
) -> (SummaryState, Vec<SummaryEffect>) {
    let mut effects = Vec::new();
    match event {
        SummaryEvent::Observe {
            entry,
            enabled,
            cached,
        } => {
            let key = summary_key(&entry);
            if state.key != key {
                let expanded = state.entry_id == entry.id && state.expanded;
                state = SummaryState {
                    entry_id: entry.id.clone(),
                    key,
                    text: cached,
                    expanded,
                    ..SummaryState::default()
                };
            }
            if wants_summary(&entry, enabled)
                && state.text.is_none()
                && !state.failed
                && !state.pending
            {
                state.pending = true;
                effects.push(SummaryEffect::Summarize {
                    key: state.key.clone(),
                    text: summary_text(&entry),
                });
            }
        }
        SummaryEvent::Completed { key, text } if key == state.key => {
            state.pending = false;
            state.text = Some(text);
        }
        SummaryEvent::Failed { key, cancelled } if key == state.key => {
            state.pending = false;
            if cancelled {
                effects.push(SummaryEffect::PropagateCancellation);
            } else {
                state.failed = true;
            }
        }
        SummaryEvent::Toggle => state.expanded = !state.expanded,
        _ => {}
    }
    (state, effects)
}

/// Minúsculas sem marcas combinantes nem acentos decomponíveis do alfabeto latino.
pub fn normalize_find(text: &str) -> String {
    text.chars()
        .flat_map(char::to_lowercase)
        .filter_map(find_char)
        .collect()
}

fn find_char(ch: char) -> Option<char> {
    Some(match ch {
            '\u{0300}'..='\u{036f}' | '\u{1ab0}'..='\u{1aff}' | '\u{1dc0}'..='\u{1dff}'
            | '\u{20d0}'..='\u{20ff}' | '\u{fe20}'..='\u{fe2f}' => return None,
            'à' | 'á' | 'â' | 'ã' | 'ä' | 'å' | 'ā' | 'ă' | 'ą' | 'ǎ' | 'ǟ' | 'ǡ' | 'ǻ'
            | 'ȁ' | 'ȃ' | 'ȧ' | 'ḁ' | 'ạ' | 'ả' | 'ấ' | 'ầ' | 'ẩ' | 'ẫ' | 'ậ' | 'ắ'
            | 'ằ' | 'ẳ' | 'ẵ' | 'ặ' => 'a',
            'ḃ' | 'ḅ' | 'ḇ' => 'b',
            'ç' | 'ć' | 'ĉ' | 'ċ' | 'č' | 'ḉ' => 'c',
            'ď' | 'ḋ' | 'ḍ' | 'ḏ' | 'ḑ' | 'ḓ' => 'd',
            'è' | 'é' | 'ê' | 'ë' | 'ē' | 'ĕ' | 'ė' | 'ę' | 'ě' | 'ȅ' | 'ȇ' | 'ȩ' | 'ḕ'
            | 'ḗ' | 'ḙ' | 'ḛ' | 'ḝ' | 'ẹ' | 'ẻ' | 'ẽ' | 'ế' | 'ề' | 'ể' | 'ễ' | 'ệ' => 'e',
            'ḟ' => 'f',
            'ĝ' | 'ğ' | 'ġ' | 'ģ' | 'ǧ' | 'ǵ' | 'ḡ' => 'g',
            'ĥ' | 'ȟ' | 'ḣ' | 'ḥ' | 'ḧ' | 'ḩ' | 'ḫ' | 'ẖ' => 'h',
            'ì' | 'í' | 'î' | 'ï' | 'ĩ' | 'ī' | 'ĭ' | 'į' | 'ǐ' | 'ȉ' | 'ȋ' | 'ḭ' | 'ḯ'
            | 'ỉ' | 'ị' => 'i',
            'ĵ' | 'ǰ' => 'j',
            'ķ' | 'ǩ' | 'ḱ' | 'ḳ' | 'ḵ' => 'k',
            'ĺ' | 'ļ' | 'ľ' | 'ḷ' | 'ḹ' | 'ḻ' | 'ḽ' => 'l',
            'ḿ' | 'ṁ' | 'ṃ' => 'm',
            'ñ' | 'ń' | 'ņ' | 'ň' | 'ǹ' | 'ṅ' | 'ṇ' | 'ṉ' | 'ṋ' => 'n',
            'ò' | 'ó' | 'ô' | 'õ' | 'ö' | 'ō' | 'ŏ' | 'ő' | 'ơ' | 'ǒ' | 'ǫ' | 'ǭ' | 'ȍ'
            | 'ȏ' | 'ȫ' | 'ȭ' | 'ȯ' | 'ȱ' | 'ṍ' | 'ṏ' | 'ṑ' | 'ṓ' | 'ọ' | 'ỏ' | 'ố' | 'ồ'
            | 'ổ' | 'ỗ' | 'ộ' | 'ớ' | 'ờ' | 'ở' | 'ỡ' | 'ợ' => 'o',
            'ṕ' | 'ṗ' => 'p',
            'ŕ' | 'ŗ' | 'ř' | 'ȑ' | 'ȓ' | 'ṙ' | 'ṛ' | 'ṝ' | 'ṟ' => 'r',
            'ś' | 'ŝ' | 'ş' | 'š' | 'ș' | 'ṡ' | 'ṣ' | 'ṥ' | 'ṧ' | 'ṩ' => 's',
            'ţ' | 'ť' | 'ț' | 'ṫ' | 'ṭ' | 'ṯ' | 'ṱ' | 'ẗ' => 't',
            'ù' | 'ú' | 'û' | 'ü' | 'ũ' | 'ū' | 'ŭ' | 'ů' | 'ű' | 'ų' | 'ư' | 'ǔ' | 'ǖ'
            | 'ǘ' | 'ǚ' | 'ǜ' | 'ȕ' | 'ȗ' | 'ṳ' | 'ṵ' | 'ṷ' | 'ṹ' | 'ṻ' | 'ụ' | 'ủ' | 'ứ'
            | 'ừ' | 'ử' | 'ữ' | 'ự' => 'u',
            'ṽ' | 'ṿ' => 'v',
            'ŵ' | 'ẁ' | 'ẃ' | 'ẅ' | 'ẇ' | 'ẉ' | 'ẘ' => 'w',
            'ẋ' | 'ẍ' => 'x',
            'ý' | 'ÿ' | 'ŷ' | 'ȳ' | 'ẏ' | 'ẙ' | 'ỳ' | 'ỵ' | 'ỷ' | 'ỹ' => 'y',
            'ź' | 'ż' | 'ž' | 'ẑ' | 'ẓ' | 'ẕ' => 'z',
            'ǣ' | 'ǽ' => 'æ',
            'ǿ' => 'ø',
            'ẛ' => 'ſ',
            'ǯ' => 'ʒ',
            _ => ch,
    })
}

/// Texto normalizado com o mapa de volta aos bytes exibidos.
pub struct FindText {
    normalized: String,
    offsets: Vec<(usize, usize)>,
}

impl FindText {
    pub fn new(text: &str) -> Self {
        let mut normalized = String::with_capacity(text.len());
        let mut offsets: Vec<(usize, usize)> = Vec::new();
        if text.is_ascii() {
            normalized.push_str(text);
            normalized.make_ascii_lowercase();
        } else {
            for (start, ch) in text.char_indices() {
                let len = normalized.len();
                normalized.extend(ch.to_lowercase().filter_map(find_char));
                if normalized.len() - len != ch.len_utf8() {
                    let boundary = (normalized.len(), start + ch.len_utf8());
                    if let Some(last) = offsets.last_mut().filter(|last| last.0 == boundary.0) {
                        *last = boundary;
                    } else {
                        offsets.push(boundary);
                    }
                }
            }
        }
        Self { normalized, offsets }
    }

    /// Ocorrências sem sobreposição, em ordem de leitura.
    pub fn occurrences<'a>(
        &'a self,
        term: &'a str,
    ) -> impl Iterator<Item = std::ops::Range<usize>> + 'a {
        (!term.is_empty())
            .then_some(self.normalized.match_indices(term))
            .into_iter()
            .flatten()
            .map(|(start, matched)| self.original(start)..self.original(start + matched.len()))
    }

    fn original(&self, offset: usize) -> usize {
        let next = self.offsets.partition_point(|&(normalized, _)| normalized <= offset);
        if next == 0 {
            offset
        } else {
            let (normalized, original) = self.offsets[next - 1];
            original + (offset - normalized)
        }
    }
}

/// Parte do topo visível ou escolhe a última ocorrência acima.
pub fn find_initial<T>(matches: &[T], top: usize, row: impl Fn(&T) -> usize) -> Option<usize> {
    let last = matches.len().checked_sub(1)?;
    Some(matches.partition_point(|hit| row(hit) < top).min(last))
}

pub fn find_step(current: usize, len: usize, forward: bool) -> Option<usize> {
    let last = len.checked_sub(1)?;
    Some(if forward {
        if current >= last { 0 } else { current + 1 }
    } else {
        if current == 0 { last } else { current - 1 }
    })
}

pub fn find_term(term: &str) -> String {
    normalize_find(term.trim())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn find_occurrences_return_original_byte_ranges_without_overlap() {
        let text = "AÇÃO na aça\u{303}o; REAÇÃO e café\u{301}";
        let indexed = FindText::new(text);
        let hits = |term| {
            indexed
                .occurrences(&find_term(term))
                .map(|range| &text[range])
                .collect::<Vec<_>>()
        };
        assert_eq!(hits(" AçÃO "), ["AÇÃO", "aça\u{303}o", "AÇÃO"]);
        assert_eq!(hits("CAFE"), ["café\u{301}"]);
        assert!(hits("   \n").is_empty());
        assert!(hits("\u{301}").is_empty());
        assert_eq!(FindText::new("AnaNaNa").occurrences("ana").collect::<Vec<_>>(), [0..3, 4..7]);
        assert_eq!(normalize_find(" Ǻ Ć "), " a c ");
    }

    #[test]
    fn find_starts_at_visible_message_and_wraps() {
        assert_eq!(find_initial::<usize>(&[], 3, |&end| end), None);
        assert_eq!(find_initial(&[2, 7, 12], 4, |&end| end), Some(1));
        assert_eq!(find_initial(&[2, 7, 12], 7, |&end| end), Some(1));
        assert_eq!(find_initial(&[2, 7, 12], 13, |&end| end), Some(2));
        assert_eq!(find_step(2, 3, true), Some(0));
        assert_eq!(find_step(0, 3, false), Some(2));
        assert_eq!(find_step(1, 3, false), Some(0));
        assert_eq!(find_step(0, 0, true), None);
    }

    #[test]
    fn brief_starts_at_first_prose_skips_code_titles_tables_and_cuts_at_word() {
        let reply = format!(
            "## Causa\n\n```rust\nlet x = 1;\n```\n\nO **resumo** demora: `slot` atende 2 por vez.\n\n| a | b |\n|---|---|\n\n- Cada pedido leva 2,3 s.\n- A fila cresce.\n\n{}",
            "fim ".repeat(200)
        );
        let brief = reply_brief(&reply);
        assert_eq!(
            brief.split(" fim").next().unwrap(),
            "O resumo demora: slot atende 2 por vez. Cada pedido leva 2,3 s. A fila cresce."
        );
        assert!(
            brief.chars().count() <= BRIEF_MAX && brief.ends_with("fim…"),
            "{brief}"
        );
        assert_eq!(
            reply_brief(&format!("```\n{}```", "código\n".repeat(100))),
            ""
        );
    }
}
