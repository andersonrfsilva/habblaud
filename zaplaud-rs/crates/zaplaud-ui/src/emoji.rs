//! Fallback emoji compartilhado por Text, TextInput e StyledText.

use slint::fontique_011::{fontique, shared_collection};
use std::sync::Arc;

pub const PROBE: &str = "🚀 👩🏽‍💻 ✅ ⚠️ 🇧🇷 🇺🇸 👨‍👩‍👧 1️⃣ çãé";
pub const TEXT_SYMBOLS: &str = "· • ✓ ‹ › ← → ↑ ↓ ↗ ▶ ◉ ○ ⋮ ✢ ✳ ✶ ✻ ✽ ⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏";
const OUTLINE: &[u8] = include_bytes!("../ui/fonts/NotoEmoji.ttf");

/// Preserva a fonte primária do sistema e antecipa emoji aos fallbacks de símbolos.
/// Software precisa de contornos: não consegue pintar a fonte colorida do sistema.
pub fn setup(software: bool) -> Result<(), Box<dyn std::error::Error>> {
    let mut collection = shared_collection();
    let symbols = collection.register_fonts(
        fontique::Blob::new(Arc::new(
            include_bytes!("../ui/fonts/DejaVuSans.ttf").as_slice(),
        )),
        None,
    );
    if symbols.is_empty() {
        return Err("Fonte de símbolos embutida inválida".into());
    }
    let registered = collection.register_fonts(fontique::Blob::new(Arc::new(OUTLINE)), None);
    let outline = registered.first().ok_or("Noto Emoji embutida inválida")?.0;
    let mut emoji: Vec<_> = if software {
        Vec::new()
    } else {
        collection
            .generic_families(fontique::GenericFamily::Emoji)
            .collect()
    };
    emoji.push(outline);
    collection.set_generic_families(fontique::GenericFamily::Emoji, emoji.iter().copied());
    #[cfg(target_os = "macos")]
    {
        // Fontique usa Helvetica para sans-serif; o cliente nativo usa a fonte de interface do macOS.
        let system: Vec<_> = collection
            .generic_families(fontique::GenericFamily::SystemUi)
            .collect();
        if !system.is_empty() {
            collection
                .set_generic_families(fontique::GenericFamily::SansSerif, system.iter().copied());
            collection
                .set_generic_families(fontique::GenericFamily::UiSansSerif, system.iter().copied());
        }
    }
    for generic in [
        fontique::GenericFamily::SansSerif,
        fontique::GenericFamily::SystemUi,
        fontique::GenericFamily::UiSansSerif,
    ] {
        let original: Vec<_> = collection.generic_families(generic).collect();
        // O Parley consulta toda a lista primária antes da família genérica Emoji.
        collection.set_generic_families(
            generic,
            original
                .iter()
                .take(1)
                .chain(emoji.iter())
                .chain(original.iter().skip(1))
                .copied(),
        );
    }
    Ok(())
}
