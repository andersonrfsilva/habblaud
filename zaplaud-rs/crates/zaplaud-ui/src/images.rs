//! Carrega as imagens citadas no Markdown e as entrega ao Slint por `MarkdownImages`.

use crate::{AppWindow, MarkdownImages, feature::FeatureContext, strings, update_ui};
use slint::ComponentHandle;
use std::cell::RefCell;
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::Arc;
use zaplaud_core::api::HabblaudApi;
use zaplaud_ui::markdown::images::{self, Decoded};

const CACHE_BYTES: usize = 64 * 1024 * 1024;

// A troca de sessão descarta o cache e invalida cargas ainda em andamento.
thread_local! { static IMAGES: RefCell<Images> = RefCell::default(); }

#[derive(Default)]
struct Images {
    api: Option<HabblaudApi>,
    generation: u64,
    entries: HashMap<Arc<str>, (slint::Image, usize)>,
    order: VecDeque<Arc<str>>,
    bytes: usize,
    loading: HashSet<Arc<str>>,
    failed: HashSet<Arc<str>>,
}

impl Images {
    fn insert(&mut self, dest: Arc<str>, image: slint::Image, bytes: usize) {
        while self.bytes + bytes > CACHE_BYTES {
            let Some(oldest) = self.order.pop_front() else {
                return;
            };
            if let Some((_, released)) = self.entries.remove(&oldest) {
                self.bytes -= released;
            }
        }
        self.bytes += bytes;
        self.order.push_back(dest.clone());
        self.entries.insert(dest, (image, bytes));
    }
}

pub fn setup(window: &AppWindow, context: &FeatureContext) {
    let global = window.global::<MarkdownImages>();
    global.set_failed_label(strings::IMAGE_FAILED.into());
    global.on_get(|dest, _| {
        IMAGES.with(|images| {
            images
                .borrow()
                .entries
                .get(dest.as_str())
                .map(|(image, _)| image.clone())
                .unwrap_or_default()
        })
    });
    global
        .on_failed(|dest, _| IMAGES.with(|images| images.borrow().failed.contains(dest.as_str())));
    let (weak, mut snapshots) = (window.as_weak(), context.snapshots.subscribe());
    context.runtime.spawn(async move {
        let mut selected = None;
        loop {
            let snapshot = match snapshots.recv().await {
                Ok(snapshot) => snapshot,
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                Err(_) => break,
            };
            let key = snapshot.selected.as_ref().map(|item| &item.key);
            if selected.as_ref() != key {
                selected = key.cloned();
                let api = key.and_then(|key| {
                    snapshot
                        .servers
                        .iter()
                        .find(|server| server.api.origin() == key.origin)
                        .map(|server| server.api.clone())
                });
                // A conversa nova recria as linhas e pede as imagens de novo; a memória não cresce sem fim.
                update_ui(&weak, move |window| {
                    IMAGES.with(|images| {
                        let mut images = images.borrow_mut();
                        *images = Images {
                            api,
                            generation: images.generation.wrapping_add(1),
                            ..Images::default()
                        };
                    });
                    let global = window.global::<MarkdownImages>();
                    global.set_revision(global.get_revision().wrapping_add(1));
                });
            }
        }
    });
    let (weak, runtime) = (window.as_weak(), context.runtime.clone());
    global.on_load(move |dest| {
        let kind = images::kind(dest.as_str());
        if kind.is_empty() {
            return;
        }
        let Some((dest, api, started)) = IMAGES.with(|images| {
            let mut images = images.borrow_mut();
            let text = dest.as_str();
            if images.entries.contains_key(text)
                || images.loading.contains(text)
                || kind != "remote" && images.failed.contains(text)
            {
                return None;
            }
            let dest: Arc<str> = Arc::from(text);
            images.failed.remove(text);
            images.loading.insert(dest.clone());
            Some((dest, images.api.clone(), images.generation))
        }) else {
            return;
        };
        let weak = weak.clone();
        runtime.spawn(async move {
            let bytes = match (kind, images::host_path(&dest), api) {
                ("data", ..) => images::data_bytes(&dest),
                ("file", Some(path), Some(api)) => api.files_image(path).await.ok(),
                ("remote", ..) => HabblaudApi::remote_image(&dest).await.ok(),
                _ => None,
            };
            let decoded = match bytes {
                Some(bytes) => tokio::task::spawn_blocking(move || images::decode(bytes))
                    .await
                    .ok()
                    .flatten(),
                None => None,
            };
            // A sessão pode mudar antes da conclusão chegar à thread da interface.
            update_ui(&weak, move |window| {
                let current = IMAGES.with(|images| images.borrow().generation == started);
                if !current {
                    return;
                }
                let image = decoded.and_then(|decoded| match decoded {
                    Decoded::Pixels(pixels) => {
                        let bytes = images::raster_bytes(pixels.width(), pixels.height())?;
                        Some((slint::Image::from_rgba8(pixels), bytes))
                    }
                    Decoded::Animated { frames, .. } => {
                        let first = frames.into_iter().next()?;
                        let bytes = images::raster_bytes(first.width(), first.height())?;
                        Some((slint::Image::from_rgba8(first), bytes))
                    }
                    Decoded::Svg(svg) => {
                        let image = slint::Image::load_from_svg_data(&svg).ok()?;
                        let size = image.size();
                        let bytes = svg
                            .len()
                            .max(images::raster_bytes(size.width, size.height)?);
                        Some((image, bytes))
                    }
                });
                IMAGES.with(|images| {
                    let mut images = images.borrow_mut();
                    images.loading.remove(dest.as_ref());
                    if let Some((image, bytes)) = image {
                        images.insert(dest, image, bytes);
                    } else {
                        images.failed.insert(dest);
                    }
                });
                let global = window.global::<MarkdownImages>();
                global.set_revision(global.get_revision().wrapping_add(1));
            });
        });
    });
}
