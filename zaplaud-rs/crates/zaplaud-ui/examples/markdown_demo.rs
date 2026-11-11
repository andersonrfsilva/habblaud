use slint::ComponentHandle;
use zaplaud_ui::markdown::{MarkdownDemo, Theme, message_model};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let fixture: serde_json::Value = serde_json::from_str(include_str!(
        "../../zaplaud-core/tests/fixtures/conversation-init.json"
    ))?;
    let mut documents = vec![format!(
        "# Título h1\n\n## Título h2\n\n### Título h3\n\n#### Título h4\n\n##### Título h5\n\n###### Título h6\n\nTexto **forte**, *itálico*, ~~riscado~~ e `código inline`, [abrir link](https://example.com).\n\n> Uma citação com **estilo**.\n\n1. Primeiro\n   - Filho\n     - Neto\n2. Segundo\n\n- [ ] Pendente\n- [x] Concluído\n\n---\n\n```rust\nlet exemplo = \"{}\";\n```\n\n| A | B | C | D | E | F |\n| :-- | :-: | --: | -- | -- | -- |\n| esquerda | centro | direita | **forte** | `código` | [link](https://example.com) |\n\n![Imagem desabilitada](https://example.com/image.png)\n\n<b>HTML literal</b>",
        "x".repeat(300)
    )];
    if let Some(entries) = fixture.get("entries").and_then(serde_json::Value::as_array) {
        documents.extend(
            entries
                .iter()
                .filter_map(|entry| entry.get("text").and_then(serde_json::Value::as_str))
                .map(str::to_owned),
        );
    }
    // O cenário padrão fixa 200 mensagens mistas para medir virtualização.
    let originals = documents.len();
    while documents.len() < 200 {
        let index = documents.len() % originals;
        documents.push(documents[index].clone());
    }
    let messages = message_model(documents);
    let window = MarkdownDemo::new()?;
    window
        .global::<Theme>()
        .set_dark(std::env::args().any(|arg| arg == "--dark"));
    window.set_messages(messages);
    let weak = window.as_weak();
    window.on_copy(move |text| {
        if let Some(window) = weak.upgrade() {
            window.invoke_copy_text(text.clone());
            println!(
                "copy:{}:{}",
                text.len(),
                text.chars().take(40).collect::<String>()
            );
        }
    });
    window.on_link_clicked(|url| println!("link-clicked:{url}"));
    window.run()?;
    Ok(())
}
