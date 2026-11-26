use pulldown_cmark::{Event, Options, Parser, Tag, TagEnd};
use zaplaud_core::api::UrlPolicy;

// O pulldown-cmark não reconhece URLs soltas. Reserve o trecho antes de interpretar
// a pontuação Markdown dentro do caminho e recupere o destino original pelo offset.
const PLACEHOLDER: &str = "<https:zaplaud>";
const TRAILING: [char; 13] = ['.', ',', ':', ';', '!', '?', '"', '\'', '*', '_', '~', ']', '`'];

pub(super) fn mark(source: &str, options: Options) -> Option<(String, Vec<(usize, &str)>)> {
    if !source.contains("http://") && !source.contains("https://") {
        return None;
    }
    let mut marked = String::new();
    let mut links = Vec::new();
    let mut end = 0;
    let mut protected = 0;
    for (event, range) in Parser::new_ext(source, options).into_offset_iter() {
        match event {
            Event::Start(Tag::Link { .. } | Tag::Image { .. } | Tag::CodeBlock(_)) => protected += 1,
            Event::End(TagEnd::Link | TagEnd::Image | TagEnd::CodeBlock) => protected -= 1,
            Event::Text(_) if protected == 0 && range.end > end => {
                let start = range.start.max(end);
                for (offset, _) in source[start..range.end].match_indices("http") {
                    let start = start + offset;
                    if start < end || source[..start].trim_end_matches('_').ends_with(alphanumeric)
                    {
                        continue;
                    }
                    let Some(url) = bare(&source[start..]) else {
                        continue;
                    };
                    if marked.is_empty() {
                        marked.reserve(source.len());
                    }
                    marked.push_str(&source[end..start]);
                    // Uma barra invertida literal antes da URL não deve escapar o marcador `<`.
                    let backslashes = source[..start]
                        .bytes()
                        .rev()
                        .take_while(|&byte| byte == b'\\')
                        .count();
                    if backslashes % 2 != 0 {
                        marked.push('\\');
                    }
                    links.push((marked.len(), url));
                    marked.push_str(PLACEHOLDER);
                    end = start + url.len();
                }
            }
            _ => {}
        }
    }
    if links.is_empty() {
        return None;
    }
    marked.push_str(&source[end..]);
    Some((marked, links))
}

fn alphanumeric(c: char) -> bool {
    c.is_alphabetic() || c.is_numeric()
}

fn host_part(text: &str) -> Option<usize> {
    let mut chars = text.char_indices();
    let (_, first) = chars.next()?;
    if !alphanumeric(first) {
        return None;
    }
    let mut end = first.len_utf8();
    for (i, ch) in chars {
        if alphanumeric(ch) {
            end = i + ch.len_utf8();
        } else if !matches!(ch, '_' | '-') {
            break;
        }
        if !ch.is_ascii() {
            break;
        }
    }
    Some(end)
}

fn bare(text: &str) -> Option<&str> {
    // GFMFlavourDescriptor do Kotlin (markdown 0.7.3, gfm.flex): esquema minúsculo,
    // host em partes, porta numérica e caminho opcional iniciado por barra.
    let rest = text
        .strip_prefix("https://")
        .or_else(|| text.strip_prefix("http://"))?;
    let mut end = host_part(rest)?;
    while let Some(part) = rest[end..].strip_prefix('.').and_then(host_part) {
        end += part + 1;
    }
    if let Some(port) = rest[end..].strip_prefix(':') {
        let len = port.bytes().take_while(u8::is_ascii_digit).count();
        if len > 0 {
            end += len + 1;
        }
    }
    if let Some(path) = rest[end..].strip_prefix('/') {
        end += 1;
        let path_start = end;
        let mut chars = path.char_indices();
        let mut open = false;
        while let Some((i, ch)) = chars.next() {
            match ch {
                '<' => break,
                ch if ch.is_whitespace() => break,
                '(' => open = true,
                ')' if open => open = false,
                ')' => break,
                ']' => {
                    let Some((i, next)) = chars.next() else {
                        break;
                    };
                    if matches!(next, '[' | '(' | '<') {
                        break;
                    }
                    end = path_start + i + next.len_utf8();
                    continue;
                }
                _ => {}
            }
            end = path_start + i + ch.len_utf8();
        }
    }
    let mut url = &text[..text.len() - rest.len() + end];
    if url.ends_with('/') {
        url = &url[..url.trim_end_matches('/').len() + 1];
    } else {
        url = url.trim_end_matches(TRAILING);
        // Um parêntese balanceado faz parte da URL; o que fecha a frase não faz.
        if url.ends_with(')') {
            let mut balance = 0;
            for ch in url.bytes().rev() {
                match ch {
                    b')' => balance += 1,
                    b'(' => {
                        balance -= 1;
                        if balance == 0 {
                            break;
                        }
                    }
                    _ => {}
                }
            }
            while balance > 0 && url.ends_with(')') {
                url = url[..url.len() - 1].trim_end_matches(TRAILING);
                balance -= 1;
            }
        }
    }
    (!url.contains('\\') && UrlPolicy::remote_image(url).is_some()).then_some(url)
}
