mod resource_gen;

use std::error::Error;
use std::path::PathBuf;

fn main() -> Result<(), Box<dyn Error>> {
    let manifest =
        PathBuf::from(std::env::var_os("CARGO_MANIFEST_DIR").ok_or("CARGO_MANIFEST_DIR ausente")?);
    let source = manifest.join("../../../android/shared/src/commonMain");
    let output = PathBuf::from(std::env::var_os("OUT_DIR").ok_or("OUT_DIR ausente")?);
    for input in [
        "build.rs",
        "resource_gen.rs",
        "resource_runtime.rs",
        "theme/m3-baseline.json",
        "ui",
    ] {
        println!("cargo:rerun-if-changed={input}");
    }
    for input in resource_gen::generate(&source, &output)? {
        println!("cargo:rerun-if-changed={}", input.display());
    }
    let svg = std::fs::read(output.join("ic_zaplaud.svg"))?;
    let tree = resvg::usvg::Tree::from_data(&svg, &resvg::usvg::Options::default())?;
    let mut icon = resvg::tiny_skia::Pixmap::new(64, 64).ok_or("Ícone inválido")?;
    resvg::render(
        &tree,
        resvg::tiny_skia::Transform::from_scale(
            64.0 / tree.size().width(),
            64.0 / tree.size().height(),
        ),
        &mut icon.as_mut(),
    );
    let argb: Vec<u8> = icon
        .data()
        .chunks_exact(4)
        .flat_map(|pixel| [pixel[3], pixel[0], pixel[1], pixel[2]])
        .collect();
    std::fs::write(output.join("tray.argb"), argb)?;
    slint_build::compile_with_config(
        "ui/markdown/markdown.slint",
        slint_build::CompilerConfiguration::new().with_include_paths(vec![output.clone()]),
    )?;
    println!("cargo:rerun-if-changed=examples/emoji_probe.slint");
    slint_build::compile_with_config(
        "examples/emoji_probe.slint",
        slint_build::CompilerConfiguration::new().with_include_paths(vec![output.clone()]),
    )?;
    slint_build::compile_with_config(
        "ui/app.slint",
        slint_build::CompilerConfiguration::new().with_include_paths(vec![output]),
    )?;
    Ok(())
}
