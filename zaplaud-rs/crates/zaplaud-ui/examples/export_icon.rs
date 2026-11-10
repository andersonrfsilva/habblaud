//! Exporta o vetor canônico sem depender de Quick Look ou librsvg.
use std::{error::Error, path::Path};

fn main() -> Result<(), Box<dyn Error>> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if !(args.len() == 3 || args.len() == 4 && args[3] == "--macos") {
        return Err("Uso: export_icon <svg> <png> <tamanho> [--macos]".into());
    }
    let size: u32 = args[2].parse()?;
    if size == 0 || size > 4096 {
        return Err("Tamanho deve estar entre 1 e 4096".into());
    }
    let mut source = std::fs::read_to_string(&args[0])?;
    if args.len() == 4 {
        // O vetor adaptativo Android é quadrado; o Dock espera margem transparente e cantos arredondados.
        source = format!(
            r##"<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024"><defs><clipPath id="dock"><rect x="100" y="100" width="824" height="824" rx="184"/></clipPath></defs><g clip-path="url(#dock)"><g transform="translate(100 100) scale({})">{source}</g></g></svg>"##,
            824.0 / 108.0
        );
    }
    let tree = resvg::usvg::Tree::from_data(source.as_bytes(), &Default::default())?;
    let mut pixmap = resvg::tiny_skia::Pixmap::new(size, size).ok_or("Tamanho inválido")?;
    let scale = size as f32 / tree.size().width();
    resvg::render(
        &tree,
        resvg::tiny_skia::Transform::from_scale(scale, scale),
        &mut pixmap.as_mut(),
    );
    pixmap.save_png(Path::new(&args[1]))?;
    Ok(())
}
