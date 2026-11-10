use slint::ComponentHandle;

include!(concat!(env!("OUT_DIR"), "/emoji_probe.rs"));

fn main() -> Result<(), Box<dyn std::error::Error>> {
    if std::env::var_os("ZAPLAUD_PROBE_VULKAN").is_some() {
        slint::BackendSelector::new()
            .backend_name("winit".into())
            .renderer_name("skia".into())
            .require_vulkan()
            .select()?;
    }
    if std::env::var_os("ZAPLAUD_PROBE_APP_FONTS").is_some() {
        zaplaud_ui::emoji::setup(
            std::env::var("SLINT_BACKEND").is_ok_and(|backend| backend == "winit-software"),
        )?;
    }
    let window = EmojiProbe::new()?;
    window
        .global::<Theme>()
        .set_dark(std::env::var("ZAPLAUD_THEME").is_ok_and(|theme| theme == "dark"));
    window.set_symbols(zaplaud_ui::emoji::TEXT_SYMBOLS.into());
    window.set_sample(zaplaud_ui::emoji::PROBE.into());
    window.set_blocks(
        std::rc::Rc::new(slint::VecModel::from(vec![MdBlock {
            kind: "text".into(),
            text: zaplaud_ui::emoji::PROBE.into(),
            rich: slint::StyledText::from_plain_text(zaplaud_ui::emoji::PROBE),
            ..Default::default()
        }]))
        .into(),
    );
    if let Some(path) = std::env::var_os("ZAPLAUD_PROBE_SNAPSHOT") {
        let weak = window.as_weak();
        slint::Timer::single_shot(std::time::Duration::from_secs(2), move || {
            if let Some(window) = weak.upgrade() {
                let result = window
                    .window()
                    .take_snapshot()
                    .map_err(|error| error.to_string())
                    .and_then(|pixels| {
                        image::save_buffer(
                            std::path::Path::new(&path),
                            pixels.as_bytes(),
                            pixels.width(),
                            pixels.height(),
                            image::ColorType::Rgba8,
                        )
                        .map_err(|error| error.to_string())
                    });
                if let Err(error) = result {
                    eprintln!("snapshot: {error}");
                }
            }
            let _ = slint::quit_event_loop();
        });
    }
    window.run()?;
    Ok(())
}
