use std::collections::BTreeMap;
use std::error::Error;
use std::fmt::Write as _;
use std::fs;
use std::path::{Path, PathBuf};

type Result<T, E = Box<dyn Error>> = std::result::Result<T, E>;
const ANDROID: &str = "http://schemas.android.com/apk/res/android";

pub fn generate(source: &Path, output: &Path) -> Result<Vec<PathBuf>> {
    let resources = source.join("composeResources");
    let strings = resources.join("values/strings.xml");
    let theme = source.join("kotlin/br/com/xlinx/habblaud/ZaplaudApp.kt");
    let drawables = resources.join("drawable");
    fs::create_dir_all(output)?;
    fs::write(
        output.join("strings.rs"),
        generate_strings(&fs::read_to_string(&strings)?)?,
    )?;
    let mut slint = generate_theme(&fs::read_to_string(&theme)?)?;
    let avatar = source.join("kotlin/br/com/xlinx/habblaud/CharacterAvatar.kt");
    let status = source.join("kotlin/br/com/xlinx/habblaud/StatusColors.kt");
    slint.push_str(&generate_avatar_colors(&fs::read_to_string(&avatar)?)?);
    let warnings = color_literals(&fs::read_to_string(&status)?)?;
    if warnings.len() != 2 {
        return Err("Cores de alerta Kotlin não reconhecidas".into());
    }
    writeln!(
        slint,
        "export global StatusColors {{ out property <color> warning: Theme.dark ? {} : {}; }}",
        warnings[0], warnings[1]
    )?;
    slint.push_str("\nexport global Icons {\n");
    let mut inputs = vec![strings, theme, avatar, status, drawables.clone()];
    let mut icons = fs::read_dir(drawables)?
        .map(|entry| entry.map(|entry| entry.path()))
        .collect::<std::io::Result<Vec<_>>>()?;
    icons.sort();
    for icon in icons
        .into_iter()
        .filter(|path| path.extension().is_some_and(|ext| ext == "xml"))
    {
        let name = icon
            .file_stem()
            .and_then(|name| name.to_str())
            .ok_or("Nome de drawable inválido")?;
        let destination = output.join(format!("{name}.svg"));
        fs::write(&destination, vector_to_svg(&fs::read_to_string(&icon)?)?)?;
        writeln!(
            slint,
            "    out property <image> {}: @image-url({:?});",
            name.replace('_', "-"),
            destination.to_string_lossy()
        )?;
        inputs.push(icon);
    }
    slint.push_str("}\n");
    fs::write(output.join("resources.slint"), slint)?;
    Ok(inputs)
}

fn android_text(value: &str) -> Result<String> {
    let mut result = String::new();
    let mut chars = value.chars();
    while let Some(character) = chars.next() {
        if character != '\\' {
            result.push(character);
            continue;
        }
        match chars.next().ok_or("Escape Android incompleto")? {
            'n' => result.push('\n'),
            't' => result.push('\t'),
            'r' => result.push('\r'),
            '\\' => result.push('\\'),
            '\'' => result.push('\''),
            '"' => result.push('"'),
            '@' => result.push('@'),
            '?' => result.push('?'),
            'u' => {
                let digits: String = chars.by_ref().take(4).collect();
                if digits.len() != 4 {
                    return Err("Escape Unicode Android incompleto".into());
                }
                let code = u32::from_str_radix(&digits, 16)?;
                result.push(char::from_u32(code).ok_or("Escape Unicode Android inválido")?);
            }
            other => return Err(format!("Escape Android não suportado: \\{other}").into()),
        }
    }
    Ok(result)
}

fn node_text(node: roxmltree::Node<'_, '_>) -> Result<String> {
    android_text(
        &node
            .descendants()
            .filter(|node| node.is_text())
            .filter_map(|node| node.text())
            .collect::<String>(),
    )
}

pub fn generate_strings(xml: &str) -> Result<String> {
    let document = roxmltree::Document::parse(xml)?;
    let mut strings = BTreeMap::new();
    let mut plurals = Vec::new();
    for node in document
        .root_element()
        .children()
        .filter(|node| node.is_element())
    {
        let name = node.attribute("name").ok_or("Recurso sem nome")?;
        match node.tag_name().name() {
            "string" => {
                if strings.insert(name, node_text(node)?).is_some() {
                    return Err(format!("Texto duplicado: {name}").into());
                }
            }
            "plurals" => {
                for item in node.children().filter(|node| node.has_tag_name("item")) {
                    plurals.push((
                        name,
                        item.attribute("quantity").ok_or("Plural sem quantidade")?,
                        node_text(item)?,
                    ));
                }
            }
            other => return Err(format!("Recurso não suportado: {other}").into()),
        }
    }
    if strings.is_empty() {
        return Err("Nenhum texto encontrado em strings.xml".into());
    }
    let mut rust = String::from("// Gerado dos recursos Kotlin; não editar.\n");
    for (name, value) in &strings {
        if !name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
            || name.starts_with(|c: char| c.is_ascii_digit())
        {
            return Err(format!("Identificador de texto inválido: {name}").into());
        }
        writeln!(
            rust,
            "pub const {}: &str = {:?};",
            name.to_ascii_uppercase(),
            value
        )?;
    }
    rust.push_str("pub const ALL: &[(&str, &str)] = &[\n");
    for name in strings.keys() {
        writeln!(rust, "    ({name:?}, {}),", name.to_ascii_uppercase())?;
    }
    rust.push_str("];\npub const PLURALS: &[(&str, &str, &str)] = &[\n");
    for (name, quantity, value) in plurals {
        writeln!(rust, "    ({name:?}, {quantity:?}, {value:?}),")?;
    }
    rust.push_str("];\n");
    rust.push_str(include_str!("resource_runtime.rs"));
    Ok(rust)
}

fn kebab_case(name: &str) -> String {
    let mut output = String::new();
    for character in name.chars() {
        if character.is_ascii_uppercase() {
            output.push('-');
        }
        output.push(character.to_ascii_lowercase());
    }
    output
}

fn scheme(source: &str, name: &str) -> Result<BTreeMap<String, String>> {
    let marker = format!("{name}ColorScheme(");
    let start = source
        .find(&marker)
        .ok_or_else(|| format!("Esquema {name} não encontrado em ZaplaudApp.kt"))?
        + marker.len();
    let mut depth = 1;
    let mut end = None;
    for (offset, character) in source[start..].char_indices() {
        match character {
            '(' => depth += 1,
            ')' => depth -= 1,
            _ => {}
        }
        if depth == 0 {
            end = Some(start + offset);
            break;
        }
    }
    let body = &source[start..end.ok_or("Esquema Kotlin incompleto")?];
    let mut colors = BTreeMap::new();
    for assignment in body
        .split(',')
        .map(str::trim)
        .filter(|part| !part.is_empty())
    {
        let (key, value) = assignment
            .split_once('=')
            .ok_or("Cor Kotlin sem atribuição")?;
        let value = value.trim();
        let color = if value == "Color.White" {
            "#ffffff".to_owned()
        } else if value == "Color.Black" {
            "#000000".to_owned()
        } else {
            let hex = value
                .strip_prefix("Color(0x")
                .and_then(|value| value.strip_suffix(')'))
                .ok_or_else(|| format!("Expressão de cor Kotlin não suportada: {value}"))?;
            if hex.len() != 8 || !hex.chars().all(|c| c.is_ascii_hexdigit()) {
                return Err(format!("Cor Kotlin inválida: {value}").into());
            }
            // Kotlin usa ARGB; Slint usa RGBA.
            if &hex[..2] == "FF" {
                format!("#{}", &hex[2..])
            } else {
                format!("#{}{}", &hex[2..], &hex[..2])
            }
        };
        colors.insert(kebab_case(key.trim()), color);
    }
    if colors.is_empty() {
        return Err(format!("Nenhuma cor encontrada no esquema {name} de ZaplaudApp.kt").into());
    }
    Ok(colors)
}

pub fn generate_theme(source: &str) -> Result<String> {
    let baseline: serde_json::Value = serde_json::from_str(include_str!("theme/m3-baseline.json"))?;
    let palette = |name: &str| -> Result<BTreeMap<String, String>> {
        let entries = baseline[name]
            .as_object()
            .ok_or("Baseline M3 sem esquema")?;
        let mut colors = BTreeMap::new();
        for (role, value) in entries {
            let argb = value.as_str().ok_or("Cor M3 inválida")?;
            let hex = argb.strip_prefix('#').ok_or("Cor M3 sem #")?;
            if hex.len() != 8 || !hex.bytes().all(|c| c.is_ascii_hexdigit()) {
                return Err("Cor M3 não é ARGB".into());
            }
            colors.insert(kebab_case(role), format!("#{}{}", &hex[2..], &hex[..2]));
        }
        colors.extend(scheme(source, name)?);
        Ok(colors)
    };
    let light = palette("light")?;
    let dark = palette("dark")?;
    if light.keys().ne(dark.keys()) {
        return Err("Papéis M3 diferentes entre claro e escuro".into());
    }
    let mut slint = String::from(
        "// Gerado de ZaplaudApp.kt; não editar.\nexport global Theme {\n    in-out property <bool> dark: false;\n",
    );
    for (name, colors) in [("light", &light), ("dark", &dark)] {
        for (key, color) in colors {
            writeln!(slint, "    out property <color> {name}-{key}: {color};")?;
        }
    }
    for key in light.keys().filter(|key| dark.contains_key(*key)) {
        writeln!(
            slint,
            "    out property <color> {key}: dark ? dark-{key} : light-{key};"
        )?;
    }
    slint.push_str("}\n");
    Ok(slint)
}

fn color_literals(source: &str) -> Result<Vec<String>> {
    source
        .split("Color(0x")
        .skip(1)
        .map(|part| {
            let hex = part.split(')').next().ok_or("Cor Kotlin incompleta")?;
            if hex.len() != 8 || !hex.bytes().all(|c| c.is_ascii_hexdigit()) {
                return Err("Cor Kotlin inválida".into());
            }
            Ok(format!("#{}{}", &hex[2..], &hex[..2]))
        })
        .collect()
}

pub fn generate_avatar_colors(source: &str) -> Result<String> {
    let line = |needle: &str| {
        source
            .lines()
            .find(|line| line.contains(needle))
            .ok_or("Paleta de avatar Kotlin ausente")
    };
    let palette = color_literals(line("val colors =")?)?;
    if palette.len() != 5 {
        return Err("Paleta de avatar Kotlin mudou".into());
    }
    let mut output = String::from("export global AvatarColors {\n");
    for (index, color) in palette.iter().enumerate() {
        writeln!(output, "out property <color> background-{index}: {color};")?;
    }
    for (name, needle) in [
        ("claude", "\"claude\" ->"),
        ("gemini", "\"gemini\" ->"),
        ("foreground", "name.take(1)"),
    ] {
        let colors = color_literals(line(needle)?)?;
        if colors.len() != 1 {
            return Err("Cor de avatar Kotlin ambígua".into());
        }
        writeln!(output, "out property <color> {name}: {};", colors[0])?;
    }
    output.push_str("}\n");
    Ok(output)
}

fn xml_escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('"', "&quot;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

pub fn vector_to_svg(xml: &str) -> Result<String> {
    let document = roxmltree::Document::parse(xml)?;
    let vector = document.root_element();
    if !vector.has_tag_name("vector") {
        return Err("Drawable não é um vector Android".into());
    }
    let attribute = |name| {
        vector
            .attribute((ANDROID, name))
            .ok_or_else(|| format!("Vector sem {name}"))
    };
    let width = attribute("viewportWidth")?;
    let height = attribute("viewportHeight")?;
    let mut svg = format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 {} {}\" width=\"{}\" height=\"{}\">\n",
        xml_escape(width),
        xml_escape(height),
        xml_escape(width),
        xml_escape(height)
    );
    for comment in document
        .descendants()
        .filter(|node| node.is_comment())
        .filter_map(|node| node.text())
    {
        writeln!(svg, "<!--{}-->", comment)?;
    }
    for path in vector.children().filter(|node| node.is_element()) {
        if !path.has_tag_name("path") {
            return Err(
                format!("Elemento vector não suportado: {}", path.tag_name().name()).into(),
            );
        }
        let data = path
            .attribute((ANDROID, "pathData"))
            .ok_or("Path sem pathData")?;
        let fill = path.attribute((ANDROID, "fillColor")).unwrap_or("#000000");
        let (fill, alpha) = if fill.starts_with('#') && fill.len() == 9 {
            (
                format!("#{}", &fill[3..]),
                f64::from(u8::from_str_radix(&fill[1..3], 16)?) / 255.0,
            )
        } else {
            (fill.to_owned(), 1.0)
        };
        let alpha = alpha
            * path
                .attribute((ANDROID, "fillAlpha"))
                .unwrap_or("1")
                .parse::<f64>()?;
        let rule = match path.attribute((ANDROID, "fillType")) {
            Some("evenOdd") => "evenodd",
            Some("nonZero") | None => "nonzero",
            Some(other) => return Err(format!("fillType não suportado: {other}").into()),
        };
        writeln!(
            svg,
            "<path d=\"{}\" fill=\"{}\" fill-opacity=\"{alpha}\" fill-rule=\"{rule}\"/>",
            xml_escape(data),
            xml_escape(&fill)
        )?;
    }
    svg.push_str("</svg>\n");
    Ok(svg)
}
#[cfg(test)]
mod theme_tests {
    use super::*;
    #[test]
    fn every_explicit_override_wins_over_baseline() -> Result<()> {
        let source = include_str!(
            "../../../android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ZaplaudApp.kt"
        );
        let generated = generate_theme(source)?;
        for name in ["light", "dark"] {
            for (role, color) in scheme(source, name)? {
                assert!(generated.contains(&format!("{name}-{role}: {color};")));
            }
        }
        Ok(())
    }
}
