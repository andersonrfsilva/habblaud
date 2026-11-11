//! Mostra as famílias genéricas resolvidas pelo mesmo catálogo usado pelo Slint.
use slint::fontique_011::{
    fontique::{FontStyle, FontWeight, FontWidth, GenericFamily},
    shared_collection,
};

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
                        println!(
                            "  weight={:?} style={:?} variable_weight={}",
                            font.weight(),
                            font.style(),
                            font.has_weight_axis()
                        );
                    }
                    for weight in [400., 500., 700.] {
                        let requested = FontWeight::new(weight);
                        if let Some(font) = family.match_font(
                            FontWidth::NORMAL,
                            FontStyle::Normal,
                            requested,
                            false,
                        ) {
                            let synthesis =
                                font.synthesis(FontWidth::NORMAL, FontStyle::Normal, requested);
                            println!(
                                "  requested={weight} selected={:?} embolden={} variable_weight={}",
                                font.weight(),
                                synthesis.embolden(),
                                font.has_weight_axis()
                            );
                        }
                    }
                }
            }
        }
    }
    Ok(())
}
