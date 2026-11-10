mod resource_gen;

use std::error::Error;
use std::path::PathBuf;

fn main() -> Result<(), Box<dyn Error>> {
    let manifest =
        PathBuf::from(std::env::var_os("CARGO_MANIFEST_DIR").ok_or("CARGO_MANIFEST_DIR ausente")?);
    let source = manifest.join("../../../android/shared/src/commonMain");
    let output = PathBuf::from(std::env::var_os("OUT_DIR").ok_or("OUT_DIR ausente")?);
    for input in ["build.rs", "resource_gen.rs", "resource_runtime.rs", "ui"] {
        println!("cargo:rerun-if-changed={input}");
    }
    for input in resource_gen::generate(&source, &output)? {
        println!("cargo:rerun-if-changed={}", input.display());
    }
    slint_build::compile_with_config(
        "ui/markdown/markdown.slint",
        slint_build::CompilerConfiguration::new().with_include_paths(vec![output.clone()]),
    )?;
    slint_build::compile_with_config(
        "ui/app.slint",
        slint_build::CompilerConfiguration::new().with_include_paths(vec![output]),
    )?;
    Ok(())
}
