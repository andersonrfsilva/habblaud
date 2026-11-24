use crate::{AppWindow, feature::{AlertInput, FeatureContext}};

pub fn setup(_: &AppWindow, _: &FeatureContext, _: tokio::sync::mpsc::UnboundedReceiver<AlertInput>) {}
pub fn on_permission_open(_: impl Fn(String, String) + 'static) {}
pub fn raise(_: &AppWindow) {}
