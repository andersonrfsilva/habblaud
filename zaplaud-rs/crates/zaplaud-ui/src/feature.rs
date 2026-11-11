use crate::state::{AppState, Event};
use std::sync::Arc;
use tokio::sync::{broadcast, mpsc};
use zaplaud_core::{
    api::HabblaudApi,
    model::{ReadingState, SessionItem},
};

/// Cada recurso assina seu próprio receptor; um consumidor lento não bloqueia rede nem UI.
#[derive(Clone)]
pub struct FeatureContext {
    pub commands: mpsc::UnboundedSender<Event>,
    pub snapshots: broadcast::Sender<Arc<AppSnapshot>>,
    pub runtime: tokio::runtime::Handle,
}
#[derive(Clone)]
pub struct ServerSnapshot {
    pub api: HabblaudApi,
    pub reading: ReadingState,
    pub needs_login: bool,
    pub snapshot_revision: u64,
}
#[derive(Clone)]
pub struct AppSnapshot {
    pub servers: Vec<ServerSnapshot>,
    pub selected: Option<SessionItem>,
    pub filter: Option<String>,
}
impl AppSnapshot {
    pub fn from_state(state: &AppState) -> Self {
        Self {
            filter: state.filter.clone(),
            servers: state
                .servers
                .iter()
                .map(|server| ServerSnapshot {
                    api: server.api.clone(),
                    reading: server.reading.state.clone(),
                    needs_login: server.reading.needs_login,
                    snapshot_revision: server.reading.snapshot_revision,
                })
                .collect(),
            selected: state
                .servers
                .iter()
                .find_map(|server| server.reading.state.selected.clone()),
        }
    }
}
