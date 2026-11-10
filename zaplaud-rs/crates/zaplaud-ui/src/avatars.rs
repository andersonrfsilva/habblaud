use slint::{Image, Rgba8Pixel, SharedPixelBuffer};
use std::{cell::RefCell, collections::VecDeque};
use zaplaud_core::model::Avatar;

pub const MAX_DECODED_BYTES: usize = 4 * 1024 * 1024;
/// A integração com zaplaud-store fornece bytes PNG; nenhuma escrita em disco nesta fase.
pub trait AvatarStorage: Send + Sync {
    fn read(&self, key: &str) -> Result<Option<Vec<u8>>, String>;
    fn save(&self, key: &str, png: &[u8]) -> Result<(), String>;
}
pub struct MemoryOnly;
impl AvatarStorage for MemoryOnly {
    fn read(&self, _: &str) -> Result<Option<Vec<u8>>, String> {
        Ok(None)
    }
    fn save(&self, _: &str, _: &[u8]) -> Result<(), String> {
        Ok(())
    }
}
struct Entry {
    key: String,
    image: Image,
    bytes: usize,
}
#[derive(Default)]
struct AvatarCache {
    entries: VecDeque<Entry>,
    bytes: usize,
}
thread_local! { static CACHE: RefCell<AvatarCache> = RefCell::new(AvatarCache::default()); }

pub fn key(origin: &str, avatar: &Avatar) -> String {
    format!(
        "{origin}|{}|{}|{}|{}",
        avatar.seed,
        avatar.look,
        avatar.kind,
        avatar.parts.as_deref().unwrap_or_default()
    )
}
pub fn get(key: &str) -> Image {
    CACHE.with_borrow_mut(|cache| {
        if let Some(index) = cache.entries.iter().position(|entry| entry.key == key)
            && let Some(entry) = cache.entries.remove(index)
        {
            let image = entry.image.clone();
            cache.entries.push_back(entry);
            image
        } else {
            Image::default()
        }
    })
}
/// O decoder aceita só o PNG 60x60 servido pelo contrato, evitando bombas de descompressão.
pub fn decode(png: &[u8]) -> Option<Vec<u8>> {
    use image::ImageDecoder;
    let decoder = image::codecs::png::PngDecoder::new(std::io::Cursor::new(png)).ok()?;
    if decoder.dimensions() != (60, 60) || decoder.total_bytes() > 60 * 60 * 8 {
        return None;
    }
    let image = image::DynamicImage::from_decoder(decoder)
        .ok()?
        .into_rgba8();
    Some(image.into_raw())
}
pub fn insert(key: String, rgba: &[u8]) -> Vec<String> {
    if rgba.len() != 60 * 60 * 4 {
        return Vec::new();
    }
    CACHE.with_borrow_mut(|cache| {
        if let Some(index) = cache.entries.iter().position(|e| e.key == key)
            && let Some(old) = cache.entries.remove(index)
        {
            cache.bytes -= old.bytes;
        }
        let mut removed = Vec::new();
        while cache.bytes + rgba.len() > MAX_DECODED_BYTES {
            if let Some(old) = cache.entries.pop_front() {
                cache.bytes -= old.bytes;
                removed.push(old.key);
            } else {
                break;
            }
        }
        let buffer = SharedPixelBuffer::<Rgba8Pixel>::clone_from_slice(rgba, 60, 60);
        cache.bytes += rgba.len();
        cache.entries.push_back(Entry {
            key,
            image: Image::from_rgba8(buffer),
            bytes: rgba.len(),
        });
        removed
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_invalid_and_wrong_dimensions() {
        assert!(decode(b"not png").is_none());
    }
    #[test]
    fn decoded_cache_evicts_within_four_mebibytes() {
        let bytes = vec![0; 60 * 60 * 4];
        let count = MAX_DECODED_BYTES / bytes.len();
        for n in 0..=count {
            insert(n.to_string(), &bytes);
        }
        assert_eq!(get("0").size().width, 0);
        CACHE.with_borrow(|cache| assert!(cache.bytes <= MAX_DECODED_BYTES));
    }
}
