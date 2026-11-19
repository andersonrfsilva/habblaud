use crate::{
    AppWindow, DeliveriesState, DeliveryAttachmentRow, DeliveryRow, feature::AppSnapshot,
    files::Command as FilesCommand, state::Event, strings, update_ui,
};
use slint::{ComponentHandle, ModelRc, VecModel};
use tokio::{sync::mpsc, task::JoinHandle};
use zaplaud_core::{
    Error,
    api::HabblaudApi,
    model::{Delivery, SessionKey, deliveries_of},
    rules::time::entry_time,
};

type Sender = mpsc::UnboundedSender<FilesCommand>;

pub(crate) enum Command {
    Open,
    Close,
    Refresh,
    Loaded(u64, Result<Vec<Delivery>, Error>),
    Download(String, String),
}

pub(crate) fn setup(window: &AppWindow, send: &Sender) {
    let state = window.global::<DeliveriesState>();
    state.set_title(strings::DELIVERIES_TITLE.into());
    state.set_menu_label(strings::DELIVERIES_MENU.into());
    state.set_loading_label(strings::DELIVERIES_LOADING.into());
    state.set_empty_label(strings::DELIVERIES_EMPTY.into());
    state.set_refresh_label(strings::DELIVERIES_REFRESH.into());
    state.set_request_label(strings::DELIVERIES_REQUEST.into());
    state.set_download_label(strings::DELIVERIES_DOWNLOAD.into());
    let sender = send.clone();
    state.on_open(move || {
        let _ = sender.send(FilesCommand::Deliveries(Command::Open));
    });
    let sender = send.clone();
    state.on_close(move || {
        let _ = sender.send(FilesCommand::Deliveries(Command::Close));
    });
    let sender = send.clone();
    state.on_refresh(move || {
        let _ = sender.send(FilesCommand::Deliveries(Command::Refresh));
    });
    let sender = send.clone();
    state.on_download(move |id, name| {
        let _ = sender.send(FilesCommand::Deliveries(Command::Download(
            id.into(),
            name.into(),
        )));
    });
    let sender = send.clone();
    state.on_cancel(move |id| {
        let _ = sender.send(FilesCommand::Cancel(id));
    });
    let sender = send.clone();
    state.on_open_download(move |id, folder| {
        let _ = sender.send(FilesCommand::OpenDownload(id, folder));
    });
}

#[derive(Default)]
pub(crate) struct Controller {
    target: Option<(SessionKey, HabblaudApi)>,
    generation: u64,
    request: Option<JoinHandle<()>>,
    available: bool,
}

impl Controller {
    pub(crate) fn selection_changed(
        &mut self,
        snapshot: &AppSnapshot,
        weak: &slint::Weak<AppWindow>,
    ) -> bool {
        let selected = snapshot.selected.as_ref().filter(|item| !item.sub);
        let available = selected.is_some();
        if self.available != available {
            self.available = available;
            update_ui(weak, move |window| {
                window.global::<DeliveriesState>().set_available(available);
            });
        }
        self.target
            .as_ref()
            .is_some_and(|(key, _)| selected.is_none_or(|item| item.key != *key))
    }

    pub(crate) fn open(
        &mut self,
        snapshot: &AppSnapshot,
        weak: &slint::Weak<AppWindow>,
        send: &Sender,
    ) {
        let Some(item) = snapshot.selected.as_ref().filter(|item| !item.sub) else {
            return;
        };
        let Some(server) = snapshot
            .servers
            .iter()
            .find(|s| s.api.origin() == item.key.origin)
        else {
            return;
        };
        self.target = Some((item.key.clone(), server.api.clone()));
        self.refresh(weak, send);
    }

    pub(crate) fn refresh(&mut self, weak: &slint::Weak<AppWindow>, send: &Sender) {
        let Some((key, api)) = self.target.clone() else {
            return;
        };
        self.abort_request();
        let generation = self.generation;
        let sender = send.clone();
        self.request = Some(tokio::spawn(async move {
            let result = api
                .deliveries()
                .await
                .map(|items| deliveries_of(items, &key));
            let _ = sender.send(FilesCommand::Deliveries(Command::Loaded(
                generation, result,
            )));
        }));
        update_ui(weak, |window| {
            let state = window.global::<DeliveriesState>();
            state.set_loading(true);
            state.set_error("".into());
            state.set_download_error("".into());
            state.set_visible(true);
        });
    }

    pub(crate) fn loaded(
        &mut self,
        generation: u64,
        result: Result<Vec<Delivery>, Error>,
        weak: &slint::Weak<AppWindow>,
        commands: &mpsc::UnboundedSender<Event>,
    ) {
        let Some(api) = self.api().filter(|_| generation == self.generation) else {
            return;
        };
        let (items, error) = match result {
            Ok(items) => (items, ""),
            Err(error) => {
                crate::files::unauthorized(commands, Some(api), &error);
                let label = if matches!(error, Error::Api { status: 403, .. }) {
                    strings::DELIVERIES_BLOCKED
                } else {
                    strings::DELIVERIES_ERROR
                };
                (Vec::new(), label)
            }
        };
        self.request = None;
        update_ui(weak, move |window| {
            let state = window.global::<DeliveriesState>();
            state.set_rows(ModelRc::new(VecModel::from(delivery_rows(items))));
            state.set_error(error.into());
            state.set_loading(false);
        });
    }

    pub(crate) fn close(&mut self, weak: &slint::Weak<AppWindow>) {
        self.target = None;
        self.abort_request();
        update_ui(weak, |window| {
            let state = window.global::<DeliveriesState>();
            state.set_visible(false);
            state.set_rows(ModelRc::default());
            state.set_downloads(ModelRc::default());
        });
    }

    pub(crate) fn api(&self) -> Option<&HabblaudApi> {
        self.target.as_ref().map(|(_, api)| api)
    }

    fn abort_request(&mut self) {
        self.generation = self.generation.wrapping_add(1);
        if let Some(task) = self.request.take() {
            task.abort();
        }
    }
}

impl Drop for Controller {
    fn drop(&mut self) {
        self.abort_request();
    }
}

fn delivery_rows(items: Vec<Delivery>) -> Vec<DeliveryRow> {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis() as i64)
        .unwrap_or_default();
    items
        .into_iter()
        .map(|item| {
            let count = item.attachments.len();
            DeliveryRow {
                id: item.id.into(),
                headline: item.headline.into(),
                time: entry_time(item.finished_at, now).unwrap_or_default().into(),
                summary: item.summary.unwrap_or_default().into(),
                count: if count == 0 {
                    String::new()
                } else {
                    strings::format_plural("deliveries_files", count as i64, &[&count.to_string()])
                        .unwrap_or_default()
                }
                .into(),
                request: item.request.into(),
                response: item.text.into(),
                attachments: ModelRc::new(VecModel::from(
                    item.attachments
                        .into_iter()
                        .map(|attachment| DeliveryAttachmentRow {
                            id: attachment.id.into(),
                            name: attachment.name.into(),
                            caption: attachment.caption.unwrap_or_default().into(),
                        })
                        .collect::<Vec<_>>(),
                )),
            }
        })
        .collect()
}
