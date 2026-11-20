use crate::{
    AppWindow, DeliveriesState, FilesCrumbRow, FilesDownloadRow, FilesEntryRow, FilesRootRow,
    FilesState, deliveries,
    feature::{AppSnapshot, FeatureContext},
    state::Event,
    update_ui,
};
use slint::{ComponentHandle, ModelRc, VecModel};
use std::{
    path::{Path, PathBuf},
    sync::Arc,
};
use tokio::sync::mpsc;
use zaplaud_core::{
    api::HabblaudApi,
    error::Error,
    model::{FileListing, FileRoot},
};

pub(crate) enum Command {
    Deliveries(deliveries::Command),
    Open(bool),
    Close,
    Root(usize),
    Navigate(String),
    Activate(usize),
    Parent,
    Roots(
        u64,
        Result<Vec<FileRoot>, Error>,
        Option<(String, Option<String>)>,
    ),
    Listed(u64, Result<FileListing, Error>),
    Progress(i32, u64, Option<u64>),
    Finished(i32, String, Result<(), Error>),
    Cancel(i32),
    OpenDownload(i32, bool),
    /// Arquivo do servidor tocado num link da conversa (caminho absoluto no host).
    DownloadLink(String),
}
enum DownloadTarget {
    File(String),
    Delivery(String),
}
struct Download {
    id: i32,
    attachment_id: Option<String>,
    path: PathBuf,
    name: String,
    status: String,
    progress: f32,
    running: bool,
    done: bool,
    task: tokio::task::JoinHandle<()>,
}
#[derive(Default)]
struct Browser {
    visible: bool,
    loading: bool,
    error: String,
    roots: Vec<FileRoot>,
    root: usize,
    path: String,
    listing: Option<FileListing>,
    generation: u64,
}

pub fn setup(window: &AppWindow, context: &FeatureContext) {
    let (send, receive) = mpsc::unbounded_channel();
    deliveries::setup(window, &send);
    let global = window.global::<FilesState>();
    global.set_title(crate::strings::FILES_TITLE.into());
    global.set_root_label(crate::strings::FILES_ROOT.into());
    global.set_loading_label(crate::strings::FILES_LOADING.into());
    global.set_empty_label(crate::strings::FILES_EMPTY.into());
    global.set_close_label(crate::strings::FILES_CLOSE.into());
    global.set_parent_label(crate::strings::FILES_PARENT.into());
    global.set_download_label(crate::strings::FILES_DOWNLOAD.into());
    global.set_downloads_label(crate::strings::FILES_DOWNLOADS.into());
    global.set_open_label(crate::strings::FILES_OPEN.into());
    global.set_show_folder_label(crate::strings::FILES_SHOW_FOLDER.into());
    let sender = send.clone();
    global.on_open(move |session| {
        let _ = sender.send(Command::Open(session));
    });
    let sender = send.clone();
    global.on_close(move || {
        let _ = sender.send(Command::Close);
    });
    let sender = send.clone();
    global.on_root_chosen(move |index| {
        if index >= 0 {
            let _ = sender.send(Command::Root(index as usize));
        }
    });
    let sender = send.clone();
    global.on_navigate(move |path| {
        let _ = sender.send(Command::Navigate(path.into()));
    });
    let sender = send.clone();
    global.on_activate(move |index| {
        if index >= 0 {
            let _ = sender.send(Command::Activate(index as usize));
        }
    });
    let sender = send.clone();
    global.on_parent(move || {
        let _ = sender.send(Command::Parent);
    });
    let sender = send.clone();
    global.on_cancel(move |id| {
        let _ = sender.send(Command::Cancel(id));
    });
    let sender = send.clone();
    global.on_open_download(move |id, folder| {
        let _ = sender.send(Command::OpenDownload(id, folder));
    });
    let sender = send.clone();
    global.on_download_link(move |path| {
        let _ = sender.send(Command::DownloadLink(path.into()));
    });
    let weak = window.as_weak();
    let snapshots = context.snapshots.subscribe();
    let commands = context.commands.clone();
    context
        .runtime
        .spawn(run(weak, snapshots, commands, send, receive));
}

async fn run(
    weak: slint::Weak<AppWindow>,
    mut snapshots: tokio::sync::broadcast::Receiver<Arc<AppSnapshot>>,
    commands: mpsc::UnboundedSender<Event>,
    send: mpsc::UnboundedSender<Command>,
    mut receive: mpsc::UnboundedReceiver<Command>,
) {
    let mut snapshot: Option<Arc<AppSnapshot>> = None;
    let mut browser = Browser::default();
    let mut deliveries = deliveries::Controller::default();
    let mut api: Option<HabblaudApi> = None;
    let mut request: Option<tokio::task::JoinHandle<()>> = None;
    let mut downloads: Vec<Download> = Vec::new();
    let mut next_id = 0i32;
    loop {
        let command = tokio::select! {
            value = snapshots.recv() => {
                match value {
                    Ok(value) => {
                        if deliveries.selection_changed(&value, &weak) {
                            close_deliveries(&mut deliveries, &mut downloads, &weak);
                        }
                        snapshot = Some(value);
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {}
                    Err(_) => break,
                }
                continue;
            }
            value=receive.recv()=>{let Some(value)=value else {break};value}
        };
        let downloads_only = matches!(
            &command,
            Command::Progress(..)
                | Command::Finished(..)
                | Command::Cancel(..)
                | Command::OpenDownload(..)
        );
        let mut download_source = None;
        match command {
            Command::Deliveries(command) => {
                use deliveries::Command as DeliveryCommand;
                match command {
                    DeliveryCommand::Open => {
                        if let Some(snapshot) = &snapshot {
                            deliveries.open(snapshot, &weak, &send);
                        }
                    }
                    DeliveryCommand::Close => {
                        close_deliveries(&mut deliveries, &mut downloads, &weak);
                    }
                    DeliveryCommand::Refresh => deliveries.refresh(&weak, &send),
                    DeliveryCommand::Loaded(generation, result) => {
                        deliveries.loaded(generation, result, &weak, &commands);
                    }
                    DeliveryCommand::Download(attachment, name) => {
                        let Some(api) = deliveries.api() else {
                            continue;
                        };
                        if downloads.iter().any(|download| {
                            download.running
                                && download.attachment_id.as_deref() == Some(attachment.as_str())
                        }) {
                            continue;
                        }
                        let error = start_download(
                            api.clone(),
                            DownloadTarget::Delivery(attachment),
                            &name,
                            &mut next_id,
                            &mut downloads,
                            &send,
                        )
                        .err()
                        .map(|error| {
                            crate::strings::FILES_ERROR.replace("%1$s", &error.to_string())
                        })
                        .unwrap_or_default();
                        update_ui(&weak, move |window| {
                            window
                                .global::<DeliveriesState>()
                                .set_download_error(error.into());
                        });
                        publish_downloads(&weak, &downloads, true);
                    }
                }
                continue;
            }
            Command::Open(session) => {
                let Some(snapshot) = &snapshot else { continue };
                let origin = snapshot
                    .selected
                    .as_ref()
                    .filter(|_| session)
                    .map(|s| s.key.origin.as_str())
                    .or(snapshot.filter.as_deref());
                let server = origin
                    .and_then(|origin| snapshot.servers.iter().find(|s| s.api.origin() == origin))
                    .or_else(|| snapshot.servers.first());
                let Some(server) = server else { continue };
                api = Some(server.api.clone());
                browser.visible = true;
                browser.loading = true;
                browser.error.clear();
                browser.listing = None;
                browser.roots.clear();
                browser.generation += 1;
                if let Some(task) = request.take() {
                    task.abort();
                }
                let api = server.api.clone();
                let sender = send.clone();
                let generation = browser.generation;
                let selected = snapshot
                    .selected
                    .as_ref()
                    .filter(|_| session)
                    .map(|s| (s.key.session_id.clone(), s.project.clone()));
                request = Some(tokio::spawn(async move {
                    let result = api.files_roots().await;
                    let _ = sender.send(Command::Roots(generation, result, selected));
                }));
            }
            Command::Close => {
                browser.visible = false;
                browser.generation += 1;
                if let Some(task) = request.take() {
                    task.abort();
                }
            }
            Command::Roots(generation, result, selected) if generation == browser.generation => {
                browser.loading = false;
                match result {
                    Ok(roots) => {
                        browser.root = choose_root(&roots, selected.as_ref());
                        browser.roots = roots;
                        if let Some(root) = browser.roots.get(browser.root) {
                            let path = selected
                                .as_ref()
                                .and_then(|s| s.1.as_ref())
                                .filter(|p| within(&root.path, p))
                                .cloned()
                                .unwrap_or_else(|| root.path.clone());
                            let _ = send.send(Command::Navigate(path));
                        } else {
                            browser.error = crate::strings::FILES_NO_ROOTS.into();
                        }
                    }
                    Err(error) => {
                        unauthorized(&commands, api.as_ref(), &error);
                        browser.error = error_text(&error);
                    }
                }
            }
            Command::Root(index) => {
                if let Some(root) = browser.roots.get(index) {
                    browser.root = index;
                    let _ = send.send(Command::Navigate(root.path.clone()));
                }
            }
            Command::Parent => {
                if let Some(root) = browser.roots.get(browser.root) {
                    let crumbs = breadcrumbs(root, &browser.path);
                    if crumbs.len() > 1 {
                        let _ = send.send(Command::Navigate(crumbs[crumbs.len() - 2].1.clone()));
                    }
                }
            }
            Command::Navigate(path) => {
                let Some(root) = browser.roots.get(browser.root) else {
                    continue;
                };
                if !within(&root.path, &path) {
                    continue;
                }
                let Some(api) = api.clone() else { continue };
                browser.path = path.clone();
                browser.loading = true;
                browser.error.clear();
                browser.listing = None;
                browser.generation += 1;
                if let Some(task) = request.take() {
                    task.abort();
                }
                let sender = send.clone();
                let generation = browser.generation;
                request = Some(tokio::spawn(async move {
                    let result = api.files_list(&path).await;
                    let _ = sender.send(Command::Listed(generation, result));
                }));
            }
            Command::Listed(generation, result) if generation == browser.generation => {
                browser.loading = false;
                match result {
                    Ok(mut listing) => {
                        listing.entries.sort_by_cached_key(|entry| {
                            (entry.entry_type != "dir", entry.name.to_lowercase())
                        });
                        browser.path = listing.path.clone();
                        browser.listing = Some(listing);
                    }
                    Err(error) => {
                        unauthorized(&commands, api.as_ref(), &error);
                        browser.error = error_text(&error);
                    }
                }
            }
            Command::Activate(index) => {
                let Some(entry) = browser.listing.as_ref().and_then(|l| l.entries.get(index))
                else {
                    continue;
                };
                let remote = join_remote(&browser.path, &entry.name);
                if entry.entry_type == "dir" {
                    let _ = send.send(Command::Navigate(remote));
                    continue;
                }
                let Some(api) = api.clone() else { continue };
                if let Err(error) = start_download(
                    api,
                    DownloadTarget::File(remote),
                    &entry.name,
                    &mut next_id,
                    &mut downloads,
                    &send,
                ) {
                    browser.error = error.to_string();
                }
            }
            Command::DownloadLink(path) => {
                let Some(snapshot) = &snapshot else { continue };
                let Some(server) = snapshot.selected.as_ref().and_then(|item| {
                    snapshot
                        .servers
                        .iter()
                        .find(|s| s.api.origin() == item.key.origin)
                }) else {
                    continue;
                };
                let name = path.rsplit('/').next().unwrap_or_default().to_owned();
                match start_download(
                    server.api.clone(),
                    DownloadTarget::File(path),
                    &name,
                    &mut next_id,
                    &mut downloads,
                    &send,
                ) {
                    // A janela de arquivos mostra o progresso, o cancelar e o erro do servidor (sem permissão etc.).
                    Ok(()) if !browser.visible => {
                        let _ = send.send(Command::Open(true));
                    }
                    Ok(()) => {}
                    Err(error) => {
                        browser.visible = true;
                        browser.error = error.to_string();
                    }
                }
            }
            Command::Progress(id, received, total) => {
                if let Some(download) = downloads.iter_mut().find(|d| d.id == id && d.running) {
                    download_source = Some(download.attachment_id.is_some());
                    download.progress = total
                        .filter(|n| *n > 0)
                        .map(|total| received as f32 / total as f32)
                        .unwrap_or(0.);
                    download.status = match total {
                        Some(total) => format!("{} / {}", bytes(received), bytes(total)),
                        None => bytes(received),
                    };
                }
            }
            Command::Finished(id, origin, result) => {
                if let Some(download) = downloads.iter_mut().find(|d| d.id == id && d.running) {
                    download_source = Some(download.attachment_id.is_some());
                    download.running = false;
                    match result {
                        Ok(()) => {
                            download.done = true;
                            download.progress = 1.;
                            download.status = crate::strings::FILES_COMPLETED.into();
                        }
                        Err(error) => {
                            if matches!(error, Error::Unauthorized | Error::Api { status: 401, .. })
                            {
                                let _ = commands.send(Event::Unauthorized(origin));
                            }
                            download.status = error_text(&error);
                        }
                    }
                }
            }
            Command::Cancel(id) => {
                if let Some(download) = downloads.iter_mut().find(|d| d.id == id && d.running) {
                    download_source = Some(download.attachment_id.is_some());
                    download.task.abort();
                    download.running = false;
                    download.status = crate::strings::FILES_CANCELLED.into();
                }
            }
            Command::OpenDownload(id, folder) => {
                if let Some(download) = downloads.iter().find(|d| d.id == id && d.done) {
                    let path = if folder {
                        download.path.parent().unwrap_or(&download.path)
                    } else {
                        &download.path
                    };
                    let _ = crate::conversation_view::open_system(path.as_os_str());
                }
            }
            _ => {}
        }
        if let Some(deliveries) = download_source {
            publish_downloads(&weak, &downloads, deliveries);
        } else if !downloads_only {
            publish(&weak, &browser, &downloads);
        }
    }
    if let Some(task) = request {
        task.abort();
    }
    for download in downloads {
        download.task.abort();
    }
}

fn start_download(
    api: HabblaudApi,
    target: DownloadTarget,
    name: &str,
    next_id: &mut i32,
    downloads: &mut Vec<Download>,
    send: &mpsc::UnboundedSender<Command>,
) -> std::io::Result<()> {
    let name = safe_name(name);
    let path = download_path(&name, downloads)?;
    let id = *next_id;
    *next_id = next_id.saturating_add(1);
    let attachment_id = match &target {
        DownloadTarget::File(_) => None,
        DownloadTarget::Delivery(id) => Some(id.clone()),
    };
    let sender = send.clone();
    let destination = path.clone();
    let task = tokio::spawn(async move {
        let mut last: Option<std::time::Instant> = None;
        let progress = |received, total| {
            if last.is_none_or(|last| last.elapsed() >= std::time::Duration::from_millis(100)) {
                last = Some(std::time::Instant::now());
                let _ = sender.send(Command::Progress(id, received, total));
            }
        };
        let result = match target {
            DownloadTarget::File(path) => api.files_download(&path, &destination, progress).await,
            DownloadTarget::Delivery(id) => {
                api.download_delivery(&id, &destination, progress).await
            }
        };
        let _ = sender.send(Command::Finished(id, api.origin().into(), result));
    });
    downloads.push(Download {
        id,
        attachment_id,
        path,
        name,
        status: crate::strings::FILES_DOWNLOADING.into(),
        progress: 0.,
        running: true,
        done: false,
        task,
    });
    Ok(())
}

fn close_deliveries(
    deliveries: &mut deliveries::Controller,
    downloads: &mut Vec<Download>,
    weak: &slint::Weak<AppWindow>,
) {
    downloads.retain(|download| {
        if download.attachment_id.is_some() {
            download.task.abort();
            false
        } else {
            true
        }
    });
    deliveries.close(weak);
}
pub(crate) fn unauthorized(
    commands: &mpsc::UnboundedSender<Event>,
    api: Option<&HabblaudApi>,
    error: &Error,
) {
    if matches!(error, Error::Unauthorized | Error::Api { status: 401, .. })
        && let Some(api) = api
    {
        let _ = commands.send(Event::Unauthorized(api.origin().into()));
    }
}
fn error_text(error: &Error) -> String {
    let detail = match error {
        Error::Api {
            status: 403,
            detail,
            ..
        } => {
            if detail.is_empty() {
                crate::strings::FILES_DISABLED.into()
            } else {
                detail.clone()
            }
        }
        _ => crate::error_message(error),
    };
    crate::strings::FILES_ERROR.replace("%1$s", &detail)
}
fn within(root: &str, path: &str) -> bool {
    path == root
        || path
            .strip_prefix(root)
            .is_some_and(|tail| root.ends_with(['/', '\\']) || tail.starts_with(['/', '\\']))
}
fn join_remote(path: &str, name: &str) -> String {
    format!(
        "{}{}{}",
        path.trim_end_matches(['/', '\\']),
        if path.contains('\\') && !path.contains('/') {
            "\\"
        } else {
            "/"
        },
        name
    )
}
fn choose_root(roots: &[FileRoot], selected: Option<&(String, Option<String>)>) -> usize {
    selected
        .and_then(|s| {
            roots
                .iter()
                .position(|r| r.room_id.as_deref() == Some(s.0.as_str()))
                .or_else(|| {
                    s.1.as_ref()
                        .and_then(|p| roots.iter().position(|r| within(&r.path, p)))
                })
        })
        .unwrap_or(0)
}
fn breadcrumbs(root: &FileRoot, path: &str) -> Vec<(String, String)> {
    let mut out = vec![(root.label.clone(), root.path.clone())];
    if !within(&root.path, path) {
        return out;
    }
    let mut at = root.path.clone();
    for part in path[root.path.len()..]
        .split(['/', '\\'])
        .filter(|p| !p.is_empty())
    {
        at = join_remote(&at, part);
        out.push((part.into(), at.clone()));
    }
    out
}
fn bytes(size: u64) -> String {
    let mut value = size as f64;
    let units = ["B", "KB", "MB", "GB", "TB"];
    let mut unit = 0;
    while value >= 1024. && unit < units.len() - 1 {
        value /= 1024.;
        unit += 1;
    }
    if unit == 0 {
        format!("{size} B")
    } else {
        format!("{value:.1} {}", units[unit])
    }
}
fn publish(weak: &slint::Weak<AppWindow>, browser: &Browser, downloads: &[Download]) {
    let visible = browser.visible;
    let loading = browser.loading;
    let error = browser.error.clone();
    let root_index = browser.root as i32;
    let roots = browser
        .roots
        .iter()
        .map(|r| FilesRootRow {
            label: r.label.clone().into(),
            path: r.path.clone().into(),
        })
        .collect::<Vec<_>>();
    let crumbs = browser
        .roots
        .get(browser.root)
        .map(|r| breadcrumbs(r, &browser.path))
        .unwrap_or_default()
        .into_iter()
        .map(|(label, path)| FilesCrumbRow {
            label: label.into(),
            path: path.into(),
        })
        .collect::<Vec<_>>();
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0);
    let entries = browser
        .listing
        .as_ref()
        .map(|l| {
            l.entries
                .iter()
                .map(|e| FilesEntryRow {
                    name: e.name.clone().into(),
                    path: join_remote(&l.path, &e.name).into(),
                    directory: e.entry_type == "dir",
                    detail: format!(
                        "{}{}{}",
                        if e.entry_type == "file" {
                            bytes(e.size)
                        } else {
                            String::new()
                        },
                        if e.entry_type == "file" { " · " } else { "" },
                        zaplaud_core::rules::time::entry_time(e.mtime as i64, now)
                            .unwrap_or_default()
                    )
                    .into(),
                })
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    let downloads = download_rows(downloads, false);
    let truncated = browser
        .listing
        .as_ref()
        .filter(|l| l.truncated)
        .map(|l| crate::strings::FILES_TRUNCATED.replace("%1$d", &l.entries.len().to_string()))
        .unwrap_or_default();
    update_ui(weak, move |window| {
        let state = window.global::<FilesState>();
        state.set_visible(visible);
        state.set_loading(loading);
        if loading {
            state.set_selected_index(-1);
        }
        state.set_error(error.into());
        state.set_root_index(root_index);
        state.set_truncated_label(truncated.into());
        state.set_roots(ModelRc::new(VecModel::from(roots)));
        state.set_crumbs(ModelRc::new(VecModel::from(crumbs)));
        state.set_entries(ModelRc::new(VecModel::from(entries)));
        state.set_downloads(ModelRc::new(VecModel::from(downloads)));
    });
}

fn download_rows(downloads: &[Download], deliveries: bool) -> Vec<FilesDownloadRow> {
    downloads
        .iter()
        .filter(|download| download.attachment_id.is_some() == deliveries)
        .map(|download| FilesDownloadRow {
            id: download.id,
            name: download.name.clone().into(),
            status: download.status.clone().into(),
            progress: download.progress,
            running: download.running,
            done: download.done,
        })
        .collect()
}

fn publish_downloads(weak: &slint::Weak<AppWindow>, downloads: &[Download], deliveries: bool) {
    let rows = download_rows(downloads, deliveries);
    update_ui(weak, move |window| {
        let model = ModelRc::new(VecModel::from(rows));
        if deliveries {
            window.global::<DeliveriesState>().set_downloads(model);
        } else {
            window.global::<FilesState>().set_downloads(model);
        }
    });
}
fn safe_name(name: &str) -> String {
    let name: String = name
        .chars()
        .filter(|c| !c.is_control() && !matches!(c, '/' | '\\'))
        .collect();
    let name = name
        .trim()
        .trim_start_matches(|character: char| character == '.' || character.is_whitespace());
    if name.is_empty() {
        crate::strings::FILES_FALLBACK_NAME.into()
    } else {
        name.into()
    }
}
#[cfg(target_os = "android")]
fn downloads_dir() -> std::io::Result<PathBuf> {
    Ok(zaplaud_store::DesktopDirs::resolve()?
        .data
        .join("Downloads"))
}

#[cfg(not(target_os = "android"))]
fn downloads_dir() -> std::io::Result<PathBuf> {
    let home = std::env::var_os("HOME")
        .map(PathBuf::from)
        .ok_or_else(|| std::io::Error::other("Diretório pessoal indisponível"))?;
    if cfg!(target_os = "linux") {
        let config = std::env::var_os("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| home.join(".config"));
        match std::fs::read_to_string(config.join("user-dirs.dirs")) {
            Ok(text) => {
                for line in text.lines() {
                    if let Some(value) = line.trim().strip_prefix("XDG_DOWNLOAD_DIR=")
                        && let Some(path) = parse_download_dir(value.trim(), &home)
                    {
                        return Ok(path);
                    }
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error),
        }
    }
    Ok(home.join("Downloads"))
}

#[cfg(any(not(target_os = "android"), test))]
fn parse_download_dir(value: &str, home: &Path) -> Option<PathBuf> {
    let value = value.strip_prefix('"')?.strip_suffix('"')?;
    let relative = value == "$HOME" || value.starts_with("$HOME/");
    let value = if relative {
        value.strip_prefix("$HOME")?
    } else {
        value
    };
    let mut decoded = String::new();
    let mut characters = value.chars();
    while let Some(character) = characters.next() {
        decoded.push(if character == '\\' {
            characters.next()?
        } else {
            character
        });
    }
    let path = if relative {
        home.join(decoded.trim_start_matches('/'))
    } else {
        PathBuf::from(decoded)
    };
    path.is_absolute().then_some(path)
}
fn download_path(name: &str, downloads: &[Download]) -> std::io::Result<PathBuf> {
    let dir = downloads_dir()?;
    std::fs::create_dir_all(&dir)?;
    let original = Path::new(name);
    let stem = original.file_stem().unwrap_or_default().to_string_lossy();
    let extension = original
        .extension()
        .map(|e| format!(".{}", e.to_string_lossy()))
        .unwrap_or_default();
    for index in 0u64.. {
        let path = if index == 0 {
            dir.join(name)
        } else {
            dir.join(format!("{stem} ({index}){extension}"))
        };
        if !path.try_exists()? && !downloads.iter().any(|d| d.path == path && d.running) {
            return Ok(path);
        }
    }
    Err(std::io::Error::other("Nome de arquivo indisponível"))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn names_and_boundaries() {
        assert_eq!(safe_name("../.a/\\b\n.txt"), "ab.txt");
        assert_eq!(safe_name("..."), "arquivo");
        assert_eq!(safe_name(".. .hidden"), "hidden");
        assert!(within("/work", "/work/sub"));
        assert!(!within("/work", "/workspace"));
        assert_eq!(join_remote("C:\\work", "a"), "C:\\work\\a");
    }
    #[test]
    fn root_and_breadcrumbs() {
        let root = FileRoot {
            path: "/work".into(),
            label: "Pasta".into(),
            room_id: None,
        };
        assert_eq!(
            breadcrumbs(&root, "/work/a/b").last(),
            Some(&("b".into(), "/work/a/b".into()))
        );
        assert_eq!(bytes(50 * 1024 * 1024), "50.0 MB");
        assert_eq!(
            parse_download_dir("\"$HOME/Baixados\"", Path::new("/home/test")),
            Some(PathBuf::from("/home/test/Baixados"))
        );
        assert_eq!(
            parse_download_dir("\"/tmp/Meus \\\"arquivos\\\"\"", Path::new("/home/test")),
            Some(PathBuf::from("/tmp/Meus \"arquivos\""))
        );
        assert_eq!(
            parse_download_dir("\"relativo\"", Path::new("/home/test")),
            None
        );
    }
}
