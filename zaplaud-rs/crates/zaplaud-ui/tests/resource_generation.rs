#[path = "../resource_gen.rs"]
mod resource_gen;

mod strings {
    #![allow(dead_code)]
    include!(concat!(env!("OUT_DIR"), "/strings.rs"));
}
mod native_strings {
    #![allow(dead_code)]
    include!(concat!(env!("OUT_DIR"), "/native_strings.rs"));
}

#[test]
fn native_picker_copy_is_generated_separately() -> Result<(), Box<dyn std::error::Error>> {
    let xml = fs::read_to_string(
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("ui/native-strings.xml"),
    )?;
    let document = roxmltree::Document::parse(&xml)?;
    let nodes: Vec<_> = document
        .descendants()
        .filter(|node| node.has_tag_name("string"))
        .collect();
    assert_eq!(native_strings::ALL.len(), nodes.len());
    for node in nodes {
        assert_eq!(
            native_strings::lookup(node.attribute("name").ok_or("Nome ausente")?),
            node.text()
        );
    }
    assert!(native_strings::lookup("picker_paths").is_some());
    Ok(())
}

use std::fs;
use std::path::PathBuf;

fn source() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../android/shared/src/commonMain")
}

#[test]
fn every_kotlin_string_and_plural_is_generated() -> Result<(), Box<dyn std::error::Error>> {
    let xml = fs::read_to_string(source().join("composeResources/values/strings.xml"))?;
    let document = roxmltree::Document::parse(&xml)?;
    let nodes: Vec<_> = document
        .descendants()
        .filter(|node| node.has_tag_name("string"))
        .collect();
    assert_eq!(strings::ALL.len(), nodes.len());
    assert!(nodes.len() > 200);
    for node in nodes {
        assert_eq!(
            strings::lookup(node.attribute("name").ok_or("Nome ausente")?),
            node.text()
        );
    }
    let items = document
        .descendants()
        .filter(|node| node.has_tag_name("item"))
        .count();
    assert_eq!(strings::PLURALS.len(), items);
    assert_eq!(strings::SELECT_CONVERSATION, "Selecione uma conversa");
    assert_eq!(
        strings::format("permission_suggestion", &["Bash", "projeto"]).as_deref(),
        Some("Sempre permitir Bash (projeto)")
    );
    assert_eq!(
        strings::format("context_usage", &["50", "64", "128"]).as_deref(),
        Some("Contexto em uso: 50% (64 mil de 128 mil tokens)")
    );
    assert_eq!(
        strings::format("http_error", &["403"]).as_deref(),
        Some("O servidor retornou HTTP 403.")
    );
    assert_eq!(
        strings::format("connected", &["%1$s"]).as_deref(),
        Some("Conectado a %1$s")
    );
    assert_eq!(strings::format("connected", &[]), None);
    assert_eq!(strings::lookup("does_not_exist"), None);
    assert_eq!(
        strings::format_plural("unread_replies", 1, &["1"]).as_deref(),
        Some("1 resposta não lida")
    );
    assert_eq!(
        strings::format_plural("unread_replies", 2, &["2"]).as_deref(),
        Some("2 respostas não lidas")
    );
    Ok(())
}

#[test]
fn xml_and_android_escapes_are_decoded_before_rust_escaping()
-> Result<(), Box<dyn std::error::Error>> {
    let generated = resource_gen::generate_strings(
        r#"<resources><string name="escaped">A &amp; B &lt;x&gt; &quot;q&quot; \' \\ \n \u00e9 %1$s</string></resources>"#,
    )?;
    let expected = "A & B <x> \"q\" ' \\ \n é %1$s";
    assert!(generated.contains(&format!("pub const ESCAPED: &str = {expected:?};")));
    Ok(())
}

#[test]
fn schemes_preserve_every_explicit_kotlin_color() -> Result<(), Box<dyn std::error::Error>> {
    let kotlin = fs::read_to_string(source().join("kotlin/br/com/xlinx/habblaud/ZaplaudApp.kt"))?;
    let theme = resource_gen::generate_theme(&kotlin)?;
    assert!(theme.contains("light-primary: #176B58;"));
    assert!(theme.contains("dark-primary: #9CDCC6;"));
    assert!(theme.contains("light-surface: #ffffff;"));
    assert!(theme.contains("dark-surface: #171F1B;"));
    let baseline: serde_json::Value =
        serde_json::from_str(include_str!("../theme/m3-baseline.json"))?;
    let roles = baseline["light"].as_object().ok_or("Sem esquema claro")?;
    assert_eq!(roles.len(), 48);
    for role in roles.keys() {
        let mut kebab = String::new();
        for character in role.chars() {
            if character.is_uppercase() {
                kebab.push('-');
            }
            kebab.extend(character.to_lowercase());
        }
        for scheme in ["light", "dark"] {
            assert!(baseline[scheme].get(role).is_some());
            assert!(theme.contains(&format!("property <color> {scheme}-{kebab}:")));
        }
        assert!(theme.contains(&format!("property <color> {kebab}: dark ?")));
    }
    let changed = resource_gen::generate_theme(&kotlin.replace("0xFF176B58", "0xFF123456"))?;
    assert!(changed.contains("light-primary: #123456;"));
    assert!(!changed.contains("light-primary: #176B58;"));
    let error = resource_gen::generate_theme("lightColorScheme() darkColorScheme()")
        .err()
        .ok_or("Esquemas vazios foram aceitos")?;
    assert!(error.to_string().contains("Nenhuma cor encontrada"));
    Ok(())
}

#[test]
fn every_drawable_is_generated_and_parsed_as_svg() -> Result<(), Box<dyn std::error::Error>> {
    let output = PathBuf::from(env!("OUT_DIR"));
    let slint = fs::read_to_string(output.join("resources.slint"))?;
    let mut count = 0;
    for entry in fs::read_dir(source().join("composeResources/drawable"))? {
        let path = entry?.path();
        if path.extension().is_none_or(|extension| extension != "xml") {
            continue;
        }
        let name = path
            .file_stem()
            .and_then(|name| name.to_str())
            .ok_or("Nome inválido")?;
        let xml = fs::read_to_string(&path)?;
        let svg = resource_gen::vector_to_svg(&xml)?;
        assert_eq!(fs::read_to_string(output.join(format!("{name}.svg")))?, svg);
        let tree = usvg::Tree::from_str(&svg, &usvg::Options::default())?;
        assert!(
            !tree.root().children().is_empty(),
            "{name} não contém caminhos"
        );
        let vector = roxmltree::Document::parse(&xml)?;
        let document = roxmltree::Document::parse(&svg)?;
        assert_eq!(
            vector
                .descendants()
                .filter(|node| node.has_tag_name("path"))
                .count(),
            document
                .descendants()
                .filter(|node| node.has_tag_name("path"))
                .count()
        );
        assert!(slint.contains(&format!("property <image> {}:", name.replace('_', "-"))));
        count += 1;
    }
    assert!(count > 0);
    assert_eq!(slint.matches("property <image>").count(), count);
    Ok(())
}

#[test]
fn generation_returns_all_rerun_inputs() -> Result<(), Box<dyn std::error::Error>> {
    let output = PathBuf::from(env!("OUT_DIR")).join("resource-test");
    let inputs = resource_gen::generate(&source(), &output)?;
    assert!(
        inputs
            .iter()
            .any(|path| path.ends_with("values/strings.xml"))
    );
    assert!(inputs.iter().any(|path| path.ends_with("ZaplaudApp.kt")));
    assert!(inputs.iter().any(|path| path.ends_with("drawable")));
    let drawable_count = inputs
        .iter()
        .filter(|path| {
            path.extension().is_some_and(|extension| extension == "xml")
                && !path.ends_with("strings.xml")
                && !path.ends_with("native-strings.xml")
        })
        .count();
    let generated = fs::read_dir(&output)?
        .filter_map(Result::ok)
        .filter(|entry| {
            entry
                .path()
                .extension()
                .is_some_and(|extension| extension == "svg")
        })
        .count();
    assert_eq!(drawable_count, generated);
    fs::remove_dir_all(output)?;
    Ok(())
}

#[test]
fn vector_alpha_and_fill_rule_survive_conversion() -> Result<(), Box<dyn std::error::Error>> {
    let svg = resource_gen::vector_to_svg(
        r##"<vector xmlns:android="http://schemas.android.com/apk/res/android" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="#8000FF00" android:fillAlpha="0.5" android:fillType="evenOdd" android:pathData="M0,0h24v24z"/></vector>"##,
    )?;
    assert!(svg.contains("fill=\"#00FF00\""));
    assert!(svg.contains("fill-rule=\"evenodd\""));
    usvg::Tree::from_str(&svg, &usvg::Options::default())?;
    Ok(())
}
