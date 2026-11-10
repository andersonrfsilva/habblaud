//! Mostra as famílias genéricas resolvidas pelo mesmo catálogo usado pelo Slint.
use slint::fontique_011::{fontique::GenericFamily, shared_collection};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    for phase in ["before", "after"] {
        if phase == "after" {
            zaplaud_ui::emoji::setup(true)?;
        }
        let mut collection = shared_collection();
        for generic in [GenericFamily::SansSerif, GenericFamily::SystemUi] {
            let ids: Vec<_> = collection.generic_families(generic).collect();
            for id in ids {
                if let Some(family) = collection.family(id) {
                    println!("{phase} {generic:?}: {}", family.name());
                    for font in family.fonts() {
                        println!("  weight={:?} style={:?}", font.weight(), font.style());
                    }
                }
            }
        }
    }
    Ok(())
}
