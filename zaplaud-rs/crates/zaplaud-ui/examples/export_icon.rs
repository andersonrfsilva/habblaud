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
    let pixmap = rasterize(std::fs::read_to_string(&args[0])?, size, args.len() == 4)?;
    pixmap.save_png(Path::new(&args[1]))?;
    Ok(())
}

fn rasterize(
    mut source: String,
    size: u32,
    macos: bool,
) -> Result<resvg::tiny_skia::Pixmap, Box<dyn Error>> {
    if macos {
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
    Ok(pixmap)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonical_icon_keeps_artwork_and_dock_transparency() -> Result<(), Box<dyn Error>> {
        let source = include_str!(concat!(env!("OUT_DIR"), "/ic_zaplaud.svg"));
        let linux = rasterize(source.into(), 1024, false)?;
        let dock = rasterize(source.into(), 1024, true)?;
        assert_eq!(linux.pixel(0, 0).ok_or("pixel ausente")?.alpha(), 255);
        assert_eq!(dock.pixel(0, 0).ok_or("pixel ausente")?.alpha(), 0);
        assert_eq!(dock.pixel(100, 100).ok_or("pixel ausente")?.alpha(), 0);
        assert_eq!(dock.pixel(512, 100).ok_or("pixel ausente")?.alpha(), 255);
        assert_eq!(dock.pixel(512, 99).ok_or("pixel ausente")?.alpha(), 0);
        assert!(
            dock.data()
                .chunks_exact(4)
                .any(|pixel| pixel[0] > 200 && pixel[3] == 255)
        );
        Ok(())
    }
}
