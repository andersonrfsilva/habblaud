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
    window.run()?;
    Ok(())
}
