use crate::{AppWindow, feature::FeatureContext};

pub fn setup(_: &AppWindow, _: &FeatureContext) {}
pub fn on_focus(_: impl Fn(bool) + 'static) {}
pub fn on_visibility(_: impl Fn(bool) + 'static) {}
pub fn flush() -> std::io::Result<()> {
    Ok(())
}
