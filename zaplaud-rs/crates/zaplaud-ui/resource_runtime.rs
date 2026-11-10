/// Busca um texto pelo identificador original dos recursos Kotlin.
pub fn lookup(name: &str) -> Option<&'static str> {
    ALL.iter().find_map(|(key, value)| (*key == name).then_some(*value))
}

/// Escolhe a quantidade em português, conforme o recurso Kotlin.
pub fn plural(name: &str, count: i64) -> Option<&'static str> {
    let quantity = if count == 1 { "one" } else { "other" };
    PLURALS.iter().find_map(|(key, form, value)| {
        (*key == name && *form == quantity).then_some(*value)
    })
}

/// Substitui argumentos posicionais `%1$s` e `%1$d`, sem reinterpretar os argumentos.
/// Retorna `None` quando o recurso ou um argumento obrigatório não existe.
pub fn format(name: &str, args: &[&str]) -> Option<String> {
    format_text(lookup(name)?, args)
}

pub fn format_plural(name: &str, count: i64, args: &[&str]) -> Option<String> {
    format_text(plural(name, count)?, args)
}

fn format_text(template: &str, args: &[&str]) -> Option<String> {
    let mut result = String::with_capacity(template.len());
    let mut rest = template;
    while let Some(start) = rest.find('%') {
        result.push_str(&rest[..start]);
        rest = &rest[start + 1..];
        if let Some(next) = rest.strip_prefix('%') {
            result.push('%');
            rest = next;
            continue;
        }
        let digits = rest.bytes().take_while(u8::is_ascii_digit).count();
        if digits > 0 && rest.as_bytes().get(digits) == Some(&b'$')
            && matches!(rest.as_bytes().get(digits + 1), Some(b's' | b'd'))
        {
            let index = rest[..digits].parse::<usize>().ok()?.checked_sub(1)?;
            result.push_str(args.get(index)?);
            rest = &rest[digits + 2..];
        } else {
            result.push('%');
        }
    }
    result.push_str(rest);
    Some(result)
}
