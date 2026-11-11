//! Fragmentos limitados para coordenadas físicas i16 do renderizador por software.
use pulldown_cmark::{CodeBlockKind, Event, Options, Parser, Tag, TagEnd};
use std::ops::Range;

// Quatro caracteres por linha deixam margem inclusive nas células de 148 px e em
// listas estreitas. 160 linhas a 40 px + bordas ficam abaixo de 10.000 px lógicos.
// A matriz gráfica mede também seleção, títulos e escalas 1, 2 e 3.
pub const HEIGHT_BUDGET: usize = 160;

#[derive(Debug)]
pub struct TranscriptPiece {
    pub source: String,
    pub display: String,
    pub range: Range<usize>,
}

pub fn estimate(source: &str) -> usize {
    source
        .split_inclusive('\n')
        .map(|line| line.chars().count().div_ceil(4) + 1)
        .sum()
}

/// Conserva cada byte original em `source`; somente `display` recebe cercas sintéticas.
pub fn transcript_pieces(source: &str) -> Vec<TranscriptPiece> {
    if source.is_empty() {
        return Vec::new();
    }
    let mut boundaries = vec![0];
    let mut codes = Vec::new();
    let mut tables = Vec::new();
    for (event, range) in Parser::new_ext(source, Options::ENABLE_TABLES).into_offset_iter() {
        match event {
            Event::Start(Tag::CodeBlock(CodeBlockKind::Fenced(language))) => {
                let first = source[range.clone()]
                    .lines()
                    .next()
                    .unwrap_or_default()
                    .trim_start();
                let delimiter = first.chars().next().unwrap_or('`');
                let closing: String = first.chars().take_while(|&c| c == delimiter).collect();
                let opening = format!("{closing}{language}\n");
                codes.push((range, opening, closing));
            }
            Event::Start(Tag::Table(_)) => {
                let header: String = source[range.clone()]
                    .split_inclusive('\n')
                    .take(2)
                    .collect();
                if estimate(&header) <= HEIGHT_BUDGET / 4 {
                    tables.push((range, header));
                }
            }
            Event::End(
                TagEnd::Paragraph
                | TagEnd::Heading(_)
                | TagEnd::CodeBlock
                | TagEnd::Table
                | TagEnd::Item,
            ) => boundaries.push(range.end),
            _ => {}
        }
    }
    boundaries.push(source.len());
    boundaries.sort_unstable();
    boundaries.dedup();
    let mut ranges: Vec<Range<usize>> = Vec::new();
    let mut start = 0;
    let mut end = 0;
    let mut used = 0;
    for block in boundaries.windows(2) {
        for line in source[block[0]..block[1]].split_inclusive('\n') {
            let mut remaining = line;
            while !remaining.is_empty() {
                let cost = estimate(remaining);
                if used > 0 && used + cost > HEIGHT_BUDGET {
                    ranges.push(start..end);
                    start = end;
                    used = 0;
                }
                let bytes = if cost > HEIGHT_BUDGET {
                    remaining
                        .char_indices()
                        .nth((HEIGHT_BUDGET - 2) * 4)
                        .map_or(remaining.len(), |(i, _)| i)
                } else {
                    remaining.len()
                };
                used += estimate(&remaining[..bytes]);
                end += bytes;
                remaining = &remaining[bytes..];
                if !remaining.is_empty() {
                    ranges.push(start..end);
                    start = end;
                    used = 0;
                }
            }
        }
        // Blocos pequenos compartilham o fragmento; a próxima quebra prefere seu limite.
        if used > 0 && end < source.len() {
            let next = boundaries
                .iter()
                .copied()
                .find(|&b| b > end)
                .unwrap_or(source.len());
            if used + estimate(&source[end..next]) > HEIGHT_BUDGET {
                ranges.push(start..end);
                start = end;
                used = 0;
            }
        }
    }
    if end > start || ranges.is_empty() {
        ranges.push(start..end);
    }
    ranges
        .into_iter()
        .map(|range| {
            let original = source[range.clone()].to_owned();
            let mut display = String::new();
            if let Some((_, opening, _)) = codes
                .iter()
                .find(|(code, _, _)| code.start < range.start && range.start < code.end)
            {
                display.push_str(opening);
            }
            if let Some((_, header)) = tables
                .iter()
                .find(|(table, _)| table.start < range.start && range.start < table.end)
            {
                display.push_str(header);
            }
            display.push_str(&original);
            if let Some((_, _, closing)) = codes
                .iter()
                .find(|(code, _, _)| code.start < range.end && range.end < code.end)
            {
                if !display.ends_with('\n') {
                    display.push('\n');
                }
                display.push_str(closing);
                display.push('\n');
            }
            TranscriptPiece {
                source: original,
                display,
                range,
            }
        })
        .collect()
}

pub fn split_transcript(source: &str) -> Vec<String> {
    transcript_pieces(source)
        .into_iter()
        .map(|piece| piece.display)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fragments_preserve_source_budget_and_code_fences() {
        for source in [
            format!(
                "# Título\n\n{}\n\n~~~rust\n{}~~~\n",
                "**texto** 中文 😀 ".repeat(4000),
                "println!(\"olá\");\n".repeat(2500)
            ),
            format!("```text\n{}\n```", "é".repeat(9000)),
            "| a | b |\n|---|---|\n".to_owned() + &"| texto | outro |\n".repeat(3000),
            String::new(),
        ] {
            let pieces = transcript_pieces(&source);
            assert_eq!(
                pieces.iter().map(|p| p.source.as_str()).collect::<String>(),
                source
            );
            for piece in &pieces {
                assert!(
                    estimate(&piece.source) <= HEIGHT_BUDGET,
                    "{}",
                    estimate(&piece.source)
                );
                let starts = Parser::new(&piece.display)
                    .filter(|event| matches!(event, Event::Start(Tag::CodeBlock(_))))
                    .count();
                let ends = Parser::new(&piece.display)
                    .filter(|event| matches!(event, Event::End(TagEnd::CodeBlock)))
                    .count();
                assert_eq!(starts, ends);
            }
        }
    }

    #[test]
    fn continued_fences_keep_language_and_literal_lines() {
        for fence in ["```", "~~~~"] {
            let code = "println!(\"olá\");\n".repeat(2500);
            let source = format!("{fence}rust\n{code}{fence}\n");
            let mut joined = String::new();
            for piece in transcript_pieces(&source) {
                let blocks = crate::markdown::parse(&piece.display);
                assert_eq!(blocks.len(), 1);
                assert_eq!(blocks[0].kind, "code");
                assert_eq!(blocks[0].language, "rust");
                joined.push_str(&blocks[0].text);
            }
            assert_eq!(joined, code);
        }
    }
}
