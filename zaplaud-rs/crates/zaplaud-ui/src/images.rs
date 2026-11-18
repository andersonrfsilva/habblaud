//! Carrega as imagens citadas no Markdown e as entrega ao Slint por `MarkdownImages`.

use crate::{AppWindow, MarkdownImages, feature::FeatureContext, update_ui};
use slint::ComponentHandle;
use std::cell::RefCell;
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};
use zaplaud_core::api::HabblaudApi;
use zaplaud_ui::markdown::images::{self, Decoded};

// A chave é só o destino: a troca de sessão esvazia tudo e avança GENERATION, e a carga que
// termina depois da troca é descartada; assim servidores não se misturam.
thread_local! { static IMAGES: RefCell<HashMap<String, slint::Image>> = RefCell::default(); }
/// Destinos em andamento, mais as falhas automáticas, que não devem repetir a cada redesenho.
static BUSY: Mutex<Option<HashSet<String>>> = Mutex::new(None);
static GENERATION: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

fn generation() -> u64 {
    GENERATION.load(std::sync::atomic::Ordering::SeqCst)
}

fn busy<T>(apply: impl FnOnce(&mut HashSet<String>) -> T) -> T {
    apply(BUSY.lock().unwrap_or_else(|e| e.into_inner()).get_or_insert_default())
}

pub fn setup(window: &AppWindow, context: &FeatureContext) {
    let global = window.global::<MarkdownImages>();
    global.on_get(|dest, _| IMAGES.with(|images| images.borrow().get(dest.as_str()).cloned().unwrap_or_default()));
    let api: Arc<Mutex<Option<HabblaudApi>>> = Arc::default();
    let (current, weak, mut snapshots) = (api.clone(), window.as_weak(), context.snapshots.subscribe());
    context.runtime.spawn(async move {
        let mut selected = None;
        loop {
            let snapshot = match snapshots.recv().await {
                Ok(snapshot) => snapshot,
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                Err(_) => break,
            };
            let key = snapshot.selected.as_ref().map(|item| item.key.clone());
            *current.lock().unwrap_or_else(|e| e.into_inner()) = key.as_ref().and_then(|key| {
                snapshot.servers.iter().find(|server| server.api.origin() == key.origin).map(|server| server.api.clone())
            });
            if selected != key {
                selected = key;
                // A conversa nova recria as linhas e pede as imagens de novo; a memória não cresce sem fim.
                GENERATION.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                busy(HashSet::clear);
                update_ui(&weak, |window| {
                    IMAGES.with(|images| images.borrow_mut().clear());
                    let global = window.global::<MarkdownImages>();
                    global.set_revision(global.get_revision().wrapping_add(1));
                });
            }
        }
    });
    let (weak, runtime) = (window.as_weak(), context.runtime.clone());
    global.on_load(move |dest| {
        let dest = dest.to_string();
        let kind = images::kind(&dest);
        if kind.is_empty() || IMAGES.with(|images| images.borrow().contains_key(&dest)) || !busy(|set| set.insert(dest.clone())) {
            return;
        }
        let api = api.lock().unwrap_or_else(|e| e.into_inner()).clone();
        let weak = weak.clone();
        let started = generation();
        runtime.spawn(async move {
            let bytes = match (kind, images::host_path(&dest), api) {
                ("data", ..) => images::data_bytes(&dest),
                ("file", Some(path), Some(api)) => api.files_image(path).await.ok(),
                ("remote", ..) => HabblaudApi::remote_image(&dest).await.ok(),
                _ => None,
            };
            let decoded = match bytes {
                Some(bytes) => tokio::task::spawn_blocking(move || images::decode(bytes)).await.ok().flatten(),
                None => None,
            };
            // A sessão mudou durante a carga: o destino pode ser de outro servidor.
            if started != generation() {
                return;
            }
            // A remota volta ao botão para nova tentativa.
            if decoded.is_some() || kind == "remote" {
                busy(|set| set.remove(&dest));
            }
            let Some(decoded) = decoded else { return };
            update_ui(&weak, move |window| {
                if started != generation() {
                    return;
                }
                let image = match decoded {
                    Decoded::Pixels(pixels) => slint::Image::from_rgba8(pixels),
                    Decoded::Svg(svg) => match slint::Image::load_from_svg_data(&svg) {
                        Ok(image) => image,
                        Err(_) => return,
                    },
                };
                IMAGES.with(|images| images.borrow_mut().insert(dest, image));
                let global = window.global::<MarkdownImages>();
                global.set_revision(global.get_revision().wrapping_add(1));
            });
        });
    });
}
