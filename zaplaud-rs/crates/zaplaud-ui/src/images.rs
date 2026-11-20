//! Carrega as imagens citadas no Markdown e as entrega ao Slint por `MarkdownImages`.

use crate::{AppWindow, GifFrame, MarkdownImages, feature::FeatureContext, strings, update_ui};
use slint::{ComponentHandle, ModelRc, VecModel};
use std::cell::RefCell;
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use tokio::sync::{OwnedSemaphorePermit, Semaphore};
use zaplaud_core::api::HabblaudApi;
use zaplaud_ui::markdown::images::{self, Decoded};

const CACHE_BYTES: usize = 64 * 1024 * 1024;
const DECODE_SLOTS: usize = 2;
const MIB: usize = 1024 * 1024;
const DECODE_BUDGET: usize = images::MAX_DECODE_BYTES / MIB;

struct DecodeBudget {
    slots: Arc<Semaphore>,
    memory: Arc<Semaphore>,
    #[cfg(test)]
    peak_mib: std::sync::atomic::AtomicUsize,
}

impl Default for DecodeBudget {
    fn default() -> Self {
        Self {
            slots: Arc::new(Semaphore::new(DECODE_SLOTS)),
            memory: Arc::new(Semaphore::new(DECODE_BUDGET)),
            #[cfg(test)]
            peak_mib: Default::default(),
        }
    }
}

impl DecodeBudget {
    async fn decode(
        &self,
        bytes: Vec<u8>,
        slot: OwnedSemaphorePermit,
        generation: Arc<AtomicU64>,
        started: u64,
    ) -> Option<(Option<Decoded>, [OwnedSemaphorePermit; 2])> {
        let mib = images::decode_reservation(&bytes)?.div_ceil(MIB) as u32;
        let memory = self.memory.clone().acquire_many_owned(mib).await.ok()?;
        if generation.load(Ordering::Relaxed) != started {
            return None;
        }
        #[cfg(test)]
        self.peak_mib.fetch_max(
            DECODE_BUDGET - self.memory.available_permits(),
            Ordering::Relaxed,
        );
        tokio::task::spawn_blocking(move || {
            let decoded = if generation.load(Ordering::Relaxed) == started {
                images::decode(bytes)
            } else {
                None
            };
            // A alocação continua viva na fila da interface até entrar no cache.
            (decoded, [memory, slot])
        })
        .await
        .ok()
    }
}

// A troca de sessão descarta o cache e invalida cargas ainda em andamento.
thread_local! { static IMAGES: RefCell<Images> = RefCell::default(); }

struct CachedImage {
    poster: slint::Image,
    frames: ModelRc<GifFrame>,
}

impl CachedImage {
    fn still(poster: slint::Image) -> Self {
        Self {
            poster,
            frames: ModelRc::default(),
        }
    }
}

#[derive(Default)]
struct Images {
    api: Option<HabblaudApi>,
    entries: HashMap<Arc<str>, (CachedImage, usize)>,
    order: VecDeque<Arc<str>>,
    bytes: usize,
    loading: HashSet<Arc<str>>,
    failed: HashSet<Arc<str>>,
}

impl Images {
    fn insert(&mut self, dest: Arc<str>, image: CachedImage, bytes: usize) {
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
                .map(|(image, _)| image.poster.clone())
                .unwrap_or_default()
        })
    });
    global.on_frames(|dest, _| {
        IMAGES.with(|images| {
            images
                .borrow()
                .entries
                .get(dest.as_str())
                .map(|(image, _)| image.frames.clone())
                .unwrap_or_default()
        })
    });
    global
        .on_failed(|dest, _| IMAGES.with(|images| images.borrow().failed.contains(dest.as_str())));
    let generation = Arc::new(AtomicU64::new(0));
    let reset_generation = generation.clone();
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
                let generation = reset_generation.clone();
                update_ui(&weak, move |window| {
                    generation.fetch_add(1, Ordering::Relaxed);
                    IMAGES.with(|images| {
                        let mut images = images.borrow_mut();
                        *images = Images {
                            api,
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
    let budget = Arc::new(DecodeBudget::default());
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
            Some((dest, images.api.clone(), generation.load(Ordering::Relaxed)))
        }) else {
            return;
        };
        let weak = weak.clone();
        let budget = budget.clone();
        let generation = generation.clone();
        runtime.spawn(async move {
            let Ok(slot) = budget.slots.clone().acquire_owned().await else {
                return;
            };
            if generation.load(Ordering::Relaxed) != started {
                return;
            }
            let bytes = match (kind, images::host_path(&dest), api) {
                ("data", ..) => images::data_bytes(&dest),
                ("file", Some(path), Some(api)) => api.files_image(path).await.ok(),
                ("remote", ..) => HabblaudApi::remote_image(&dest).await.ok(),
                _ => None,
            };
            let result = match bytes {
                Some(bytes) if generation.load(Ordering::Relaxed) == started => {
                    budget
                        .decode(bytes, slot, generation.clone(), started)
                        .await
                }
                _ => None,
            };
            // A sessão pode mudar antes da conclusão chegar à thread da interface.
            update_ui(&weak, move |window| {
                if generation.load(Ordering::Relaxed) != started {
                    return;
                }
                let (decoded, _reservation) = match result {
                    Some((decoded, reservation)) => (decoded, Some(reservation)),
                    None => (None, None),
                };
                let image = decoded.and_then(|decoded| match decoded {
                    Decoded::Pixels(pixels) => {
                        let bytes = images::raster_bytes(pixels.width(), pixels.height())?;
                        Some((CachedImage::still(slint::Image::from_rgba8(pixels)), bytes))
                    }
                    Decoded::Animated { frames, delays } => {
                        let first = frames.first()?;
                        let bytes =
                            images::raster_bytes(first.width(), first.height())? * frames.len();
                        let frames: Vec<_> = frames
                            .into_iter()
                            .zip(delays)
                            .map(|(pixels, delay)| GifFrame {
                                picture: slint::Image::from_rgba8(pixels),
                                delay: delay as i32,
                            })
                            .collect();
                        let poster = frames.first()?.picture.clone();
                        let frames = ModelRc::new(VecModel::from(frames));
                        Some((CachedImage { poster, frames }, bytes))
                    }
                    Decoded::Svg(svg) => {
                        let image = slint::Image::load_from_svg_data(&svg).ok()?;
                        let size = image.size();
                        let bytes = svg
                            .len()
                            .max(images::raster_bytes(size.width, size.height)?);
                        Some((CachedImage::still(image), bytes))
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

#[cfg(test)]
mod tests {
    use super::*;
    use image::{Delay, Frame, Rgba, RgbaImage, codecs::gif::GifEncoder};

    #[tokio::test]
    async fn eight_near_limit_gifs_share_the_decode_budget() {
        let mut gif = Vec::new();
        let mut encoder = GifEncoder::new(&mut gif);
        for color in 0..30 {
            encoder
                .encode_frame(Frame::from_parts(
                    RgbaImage::from_pixel(640, 640, Rgba([color, 0, 0, 255])),
                    0,
                    0,
                    Delay::from_numer_denom_ms(100, 1),
                ))
                .unwrap();
        }
        drop(encoder);
        let mib = images::decode_reservation(&gif).unwrap().div_ceil(MIB);
        assert_eq!(mib, 54);
        let budget = Arc::new(DecodeBudget::default());
        let generation = Arc::new(AtomicU64::new(0));
        let ready = Arc::new(tokio::sync::Barrier::new(8));
        let mut tasks = tokio::task::JoinSet::new();
        for _ in 0..8 {
            let (budget, generation, ready, gif) = (
                budget.clone(),
                generation.clone(),
                ready.clone(),
                gif.clone(),
            );
            tasks.spawn(async move {
                ready.wait().await;
                let slot = budget.slots.clone().acquire_owned().await.unwrap();
                let (decoded, reservation) = budget.decode(gif, slot, generation, 0).await.unwrap();
                let Some(Decoded::Animated { frames, .. }) = decoded else {
                    panic!("expected a GIF below the animation limit to load");
                };
                assert_eq!(frames.len(), 30);
                assert_eq!((frames[0].width(), frames[0].height()), (640, 640));
                drop(frames);
                drop(reservation);
            });
        }
        let mut finished = 0;
        while let Some(result) = tasks.join_next().await {
            result.unwrap();
            finished += 1;
        }
        let peak = budget.peak_mib.load(Ordering::Relaxed);
        assert_eq!(finished, 8);
        assert_eq!(peak, DECODE_SLOTS * mib);
        assert!(peak <= DECODE_BUDGET);
        assert_eq!(budget.memory.available_permits(), DECODE_BUDGET);
        assert_eq!(budget.slots.available_permits(), DECODE_SLOTS);
    }

    #[tokio::test]
    async fn superseded_decode_releases_its_reservation() {
        let budget = DecodeBudget::default();
        let slot = budget.slots.clone().acquire_owned().await.unwrap();
        let generation = Arc::new(AtomicU64::new(0));
        let held = budget.memory.acquire().await.unwrap();
        let mut queued = Box::pin(budget.decode(vec![], slot, generation.clone(), 0));
        assert!(futures_util::poll!(&mut queued).is_pending());
        generation.store(1, Ordering::Relaxed);
        drop(held);
        assert!(queued.await.is_none());
        assert_eq!(budget.peak_mib.load(Ordering::Relaxed), 0);
        assert_eq!(budget.memory.available_permits(), DECODE_BUDGET);
        assert_eq!(budget.slots.available_permits(), DECODE_SLOTS);
    }
}
