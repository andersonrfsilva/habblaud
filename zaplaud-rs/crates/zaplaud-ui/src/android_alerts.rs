use crate::{AppWindow, feature::FeatureContext};

pub fn setup(_: &AppWindow, _: &FeatureContext) {}
pub fn focused(_: bool) {}
pub fn on_permission_open(_: impl Fn(String) + 'static) {}
pub fn raise(_: &AppWindow) {}
