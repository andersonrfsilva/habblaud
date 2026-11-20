//! Fragmentos limitados para coordenadas físicas i16 do renderizador por software.
use pulldown_cmark::{CodeBlockKind, Event, Options, Parser, Tag, TagEnd};
use std::ops::Range;

// Quatro caracteres por linha deixam margem inclusive nas células de 148 px e em
// listas estreitas. 160 linhas a 40 px + bordas ficam abaixo de 10.000 px lógicos.
// A matriz gráfica mede também seleção, títulos e escalas 1, 2 e 3.
pub const HEIGHT_BUDGET: usize = 160;
/// Px lógicos por unidade do orçamento, segundo a conta acima.
const PX_PER_UNIT: usize = 40;

/// Custo de um desenho: altura natural (teto da exibida) mais o respiro do bloco de código.
fn diagram_cost(height: u32) -> usize {
    (height as usize).div_ceil(PX_PER_UNIT) + 2
}

// O desenho mais alto aceito cabe inteiro num fragmento.
const _: () = assert!(
    (crate::markdown::MAX_DIAGRAM_HEIGHT as usize).div_ceil(PX_PER_UNIT) + 2 <= HEIGHT_BUDGET
);

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

/// Cercas `svg`/`mermaid` fechadas que desenham: ficam inteiras num fragmento e custam a altura
/// do desenho, não o texto. As demais (inválidas, em streaming, grandes demais) seguem como código.
fn drawn_fences(source: &str) -> Vec<(Range<usize>, usize)> {
    let mut fences = Vec::new();
    if !source.contains("svg") && !source.contains("mermaid") {
        return fences;
    }
    let mut fence: Option<(Range<usize>, String, String)> = None;
    for (event, range) in Parser::new_ext(source, Options::ENABLE_TABLES).into_offset_iter() {
        match event {
            Event::Start(Tag::CodeBlock(CodeBlockKind::Fenced(language)))
                if matches!(language.trim(), "svg" | "mermaid") =>
            {
                fence = Some((range, language.to_string(), String::new()));
            }
            Event::Text(text) => {
                if let Some((_, _, content)) = &mut fence {
                    content.push_str(&text);
                }
            }
            Event::End(TagEnd::CodeBlock) => {
                if let Some((range, language, content)) = fence.take()
                    && crate::markdown::fence_closed(&source[range.clone()])
                    && let Some(diagram) = crate::markdown::diagram(&language, &content)
                {
                    fences.push((range, diagram_cost(diagram.height)));
                }
            }
            _ => {}
        }
    }
    fences
}

/// Custo de `range`, com as cercas desenhadas inteiras dentro dele pelo custo do desenho.
fn range_cost(source: &str, range: Range<usize>, fences: &[(Range<usize>, usize)]) -> usize {
    match fences
        .iter()
        .find(|(fence, _)| range.start <= fence.start && fence.end <= range.end)
    {
        Some((fence, drawn)) => {
            estimate(&source[range.start..fence.start])
                + drawn
                + range_cost(source, fence.end..range.end, fences)
        }
        None => estimate(&source[range]),
    }
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
    let fences = drawn_fences(source);
    boundaries.sort_unstable();
    boundaries.dedup();
    let mut ranges: Vec<Range<usize>> = Vec::new();
    let mut start = 0;
    let mut end = 0;
    let mut used = 0;
    for block in boundaries.windows(2) {
        // Uma cerca desenhada termina num limite de bloco e nunca é partida.
        let fence = fences
            .iter()
            .find(|(fence, _)| block[0] <= fence.start && fence.end <= block[1]);
        let (before, after) = fence.map_or((block[1], block[1]), |(f, _)| (f.start, f.end));
        for (segment, drawn) in [
            (block[0]..before, None),
            (before..after, fence.map(|(_, drawn)| *drawn)),
            (after..block[1], None),
        ] {
            if let Some(drawn) = drawn {
                if used > 0 && used + drawn > HEIGHT_BUDGET {
                    ranges.push(start..end);
                    start = end;
                    used = 0;
                }
                used += drawn;
                end = segment.end;
                continue;
            }
            for line in source[segment].split_inclusive('\n') {
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
        }
        // Blocos pequenos compartilham o fragmento; a próxima quebra prefere seu limite.
        if used > 0 && end < source.len() {
            let next = boundaries
                .iter()
                .copied()
                .find(|&b| b > end)
                .unwrap_or(source.len());
            if used + range_cost(source, end..next, &fences) > HEIGHT_BUDGET {
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

    /// SVG fictício de 30 linhas com ~80 caracteres, como os diagramas que os agentes mandam.
    fn real_svg(view_box: &str) -> String {
        let mut svg = format!(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"{view_box}\" font-size=\"12\">\n"
        );
        for i in 0..28 {
            svg.push_str(&format!(
                "  <rect x=\"{}\" y=\"{}\" width=\"80\" height=\"20\" fill=\"var(--surface)\"/><text x=\"4\">Etapa {i}</text>\n",
                i % 5 * 90,
                i * 10
            ));
        }
        svg.push_str("</svg>\n");
        svg
    }

    fn real_mermaid() -> String {
        let mut diagram = "flowchart TD\n".to_owned();
        for i in 0..29 {
            diagram.push_str(&format!(
                "  N{i}[Etapa fictícia {i} do processo] --> N{}[Etapa fictícia {} do processo]\n",
                i + 1,
                i + 1
            ));
        }
        diagram
    }

    /// (blocos desenhados, blocos de código da linguagem sem desenho) no caminho da tela.
    fn drawn(source: &str, language: &str) -> (usize, usize) {
        use slint::Model;
        let markdown = crate::conversation_rows::MarkdownSource::new(source);
        let (mut drawn, mut code) = (0, 0);
        for piece in transcript_pieces(source) {
            for block in markdown.model(&piece.display, piece.range).iter() {
                if block.kind == "code" && block.language == language {
                    if block.diagram.size().width > 0 {
                        drawn += 1;
                    } else {
                        code += 1;
                    }
                }
            }
        }
        (drawn, code)
    }

    #[test]
    fn real_size_svg_fence_is_drawn_whole() {
        let svg = real_svg("0 0 900 300");
        assert!(estimate(&svg) > HEIGHT_BUDGET * 3);
        let source = format!("Antes do desenho.\n\n```svg\n{svg}```\n\nDepois do desenho.\n");
        assert_eq!(drawn(&source, "svg"), (1, 0));
    }

    #[test]
    fn real_size_mermaid_fence_is_drawn_whole() {
        let diagram = real_mermaid();
        assert!(estimate(&diagram) > HEIGHT_BUDGET * 3);
        let source =
            format!("Antes do desenho.\n\n```mermaid\n{diagram}```\n\nDepois do desenho.\n");
        assert_eq!(drawn(&source, "mermaid"), (1, 0));
    }

    /// Todo fragmento cabe no orçamento, contando o desenho pela altura, e nas coordenadas i16
    /// do renderizador em escala 3.
    fn assert_budget(source: &str) -> Vec<TranscriptPiece> {
        let pieces = transcript_pieces(source);
        assert_eq!(
            pieces.iter().map(|p| p.source.as_str()).collect::<String>(),
            source
        );
        for piece in &pieces {
            let cost = range_cost(
                &piece.source,
                0..piece.source.len(),
                &drawn_fences(&piece.source),
            );
            assert!(cost <= HEIGHT_BUDGET, "{cost}");
        }
        assert!(HEIGHT_BUDGET * PX_PER_UNIT * 3 < i16::MAX as usize);
        pieces
    }

    #[test]
    fn invalid_or_oversized_svg_stays_split_code() {
        let invalid = real_svg("0 0 900 300").replace("</svg>", "<rect");
        let source = format!("```svg\n{invalid}```\n");
        let (drawn_count, code) = drawn(&source, "svg");
        assert_eq!(drawn_count, 0);
        assert!(code > 1);
        assert_budget(&source);
        let mut big = real_svg("0 0 900 300");
        let line = big.lines().nth(1).unwrap().to_owned() + "\n";
        big.insert_str(big.find('\n').unwrap() + 1, &line.repeat(2_200));
        assert!(big.len() > 200_000);
        let source = format!("```svg\n{big}```\n");
        assert_eq!(drawn(&source, "svg").0, 0);
        assert_budget(&source);
    }

    #[test]
    fn tall_drawings_fit_one_piece_or_stay_code() {
        let tall = format!(
            "Antes.\n\n```svg\n{}```\n\nDepois.\n",
            real_svg("0 0 100 4000")
        );
        assert_eq!(drawn(&tall, "svg"), (1, 0));
        assert_budget(&tall);
        let too_tall = format!("```svg\n{}```\n", real_svg("0 0 100 40000"));
        let (drawn_count, code) = drawn(&too_tall, "svg");
        assert_eq!(drawn_count, 0);
        assert!(code > 1);
        // Fonte curta não protege: a altura vem da imagem.
        let short =
            "```svg\n<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 40000\"/>\n```\n";
        assert_eq!(drawn(short, "svg"), (0, 1));
    }

    #[test]
    fn drawing_shares_pieces_with_neighbours_within_budget() {
        let svg = real_svg("0 0 900 300");
        let small = format!("Antes do desenho.\n\n```svg\n{svg}```\n\nDepois do desenho.\n");
        assert_eq!(assert_budget(&small).len(), 1);
        let text = "linha de texto do relatório fictício\n".repeat(300);
        let long = format!("{text}\n```svg\n{svg}```\n\n{text}\n```svg\n{svg}```\n");
        assert!(assert_budget(&long).len() > 2);
        assert_eq!(drawn(&long, "svg"), (2, 0));
    }

    #[test]
    fn only_fences_closed_in_the_source_are_drawn() {
        let svg = real_svg("0 0 900 300");
        let diagram = real_mermaid();
        for (open, body, close, language) in [
            ("```svg", &svg, "```", "svg"),
            ("~~~svg", &svg, "~~~", "svg"),
            ("````svg", &svg, "````", "svg"),
            ("```mermaid", &diagram, "```", "mermaid"),
        ] {
            let streaming = format!("Antes.\n\n{open}\n{body}");
            let (drawn_count, code) = drawn(&streaming, language);
            assert_eq!(drawn_count, 0, "{open}");
            assert!(code > 1, "{open}");
            assert_budget(&streaming);
            let closed = format!("{streaming}{close}\n");
            assert_eq!(drawn(&closed, language), (1, 0), "{open}");
            assert_budget(&closed);
        }
        // Três crases não fecham uma cerca aberta com quatro.
        let short_close = format!("````svg\n{svg}```\n");
        assert_eq!(drawn(&short_close, "svg").0, 0);
    }
}
