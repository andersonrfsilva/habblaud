use parley::{LayoutContext, PositionedLayoutItem};
use slint::fontique_011::shared_collection;

struct FontOnlyPlatform;

impl slint::platform::Platform for FontOnlyPlatform {
    fn create_window_adapter(
        &self,
    ) -> Result<std::rc::Rc<dyn slint::platform::WindowAdapter>, slint::PlatformError> {
        Err("O teste de fontes não cria janelas".into())
    }
}

#[test]
fn probe_has_no_notdef_and_paints_with_the_app_font_stack() -> Result<(), Box<dyn std::error::Error>>
{
    slint::platform::set_platform(Box::new(FontOnlyPlatform))?;
    for software in [false, true] {
        zaplaud_ui::emoji::setup(software)?;
        #[cfg(target_os = "macos")]
        {
            use slint::fontique_011::fontique::GenericFamily;
            let collection = shared_collection();
            assert_eq!(
                collection.generic_families(GenericFamily::SansSerif).next(),
                collection.generic_families(GenericFamily::SystemUi).next(),
                "sans-serif deve preservar a fonte de interface nativa do macOS",
            );
        }
        let mut fonts = parley::FontContext {
            collection: shared_collection(),
            source_cache: parley::fontique::SourceCache::new_shared(),
        };
        let mut context = LayoutContext::<()>::new();
        let mut scale = swash::scale::ScaleContext::new();
        for token in zaplaud_ui::emoji::PROBE
            .split_whitespace()
            .chain(zaplaud_ui::emoji::TEXT_SYMBOLS.split_whitespace())
        {
            let text_symbol = zaplaud_ui::emoji::TEXT_SYMBOLS
                .split_whitespace()
                .any(|symbol| symbol == token);
            let mut builder = context.ranged_builder(&mut fonts, token, 1.0, true);
            if text_symbol {
                builder.push_default(parley::StyleProperty::FontFamily(
                    parley::FontFamily::Source(std::borrow::Cow::Borrowed("\"DejaVu Sans\"")),
                ));
            }
            let mut layout = builder.build(token);
            layout.break_all_lines(None);
            let mut painted = 0;
            for line in layout.lines() {
                for item in line.items() {
                    let PositionedLayoutItem::GlyphRun(run) = item else {
                        continue;
                    };
                    let font = run.run().font();
                    let font = swash::FontRef::from_index(font.data.as_ref(), font.index as usize)
                        .ok_or("Fonte selecionada inválida")?;
                    let mut scaler = scale.builder(font).size(32.0).build();
                    for glyph in run.glyphs() {
                        assert_ne!(glyph.id, 0, ".notdef em {token:?}, software={software}");
                        if glyph.advance == 0.0 {
                            continue;
                        }
                        let sources: &[swash::scale::Source] = if software {
                            &[swash::scale::Source::Outline]
                        } else {
                            &[
                                swash::scale::Source::ColorOutline(0),
                                swash::scale::Source::ColorBitmap(
                                    swash::scale::StrikeWith::BestFit,
                                ),
                                swash::scale::Source::Outline,
                            ]
                        };
                        let image = swash::scale::Render::new(sources)
                            .render(&mut scaler, glyph.id.try_into()?);
                        assert!(
                            image.is_some(),
                            "Glifo invisível em {token:?}, software={software}"
                        );
                        if text_symbol {
                            assert!(
                                matches!(
                                    image.as_ref().map(|image| image.content),
                                    Some(swash::scale::image::Content::Mask)
                                ),
                                "Símbolo de interface colorido em {token:?}, software={software}",
                            );
                        }
                        painted += 1;
                    }
                }
            }
            let expected = if token == "çãé" { 3 } else { 1 };
            assert_eq!(
                painted, expected,
                "Sequência decomposta em {token:?}, software={software}"
            );
        }
    }
    Ok(())
}
