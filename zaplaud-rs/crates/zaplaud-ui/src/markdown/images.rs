//! Política e decodificação das imagens `![alt](destino)` da conversa; não acessa a rede.

use base64::Engine;
use image::ImageDecoder;
use slint::{Rgba8Pixel, SharedPixelBuffer};
use zaplaud_core::api::{MAX_IMAGE_BYTES, UrlPolicy};

const MAX_IMAGE_PIXELS: u64 = 4096 * 4096;

/// `data` e `file` carregam sozinhas; `remote` só depois do toque, porque o pedido automático
/// deixaria texto injetado na resposta vazar dados pela URL. Vazio: o destino fica só como link.
pub fn kind(dest: &str) -> &'static str {
    if dest.starts_with("data:image/") {
        "data"
    } else if host_path(dest).is_some() {
        "file"
    } else if UrlPolicy::remote_image(dest).is_some() {
        "remote"
    } else {
        ""
    }
}

/// Caminho absoluto no host, lido por `/api/files/raw`.
pub fn host_path(dest: &str) -> Option<&str> {
    let path = dest.strip_prefix("file://").unwrap_or(dest);
    (path.starts_with('/') && !path.starts_with("//")).then_some(path)
}

/// Link comum cujo destino termina em extensão de imagem também é desenhado.
pub fn is_image_link(dest: &str) -> bool {
    let path = dest.split(['?', '#']).next().unwrap_or_default();
    path.rsplit_once('.').is_some_and(|(_, extension)| {
        ["png", "jpg", "jpeg", "gif", "webp", "svg"]
            .iter()
            .any(|known| extension.eq_ignore_ascii_case(known))
    })
}

pub fn data_bytes(dest: &str) -> Option<Vec<u8>> {
    let (_, encoded) = dest.split_once(";base64,")?;
    let encoded = encoded.trim();
    if encoded.len() > MAX_IMAGE_BYTES.div_ceil(3) * 4 {
        return None;
    }
    base64::engine::general_purpose::STANDARD
        .decode(encoded)
        .ok()
        .filter(|bytes| !bytes.is_empty() && bytes.len() <= MAX_IMAGE_BYTES)
}

pub enum Decoded {
    Pixels(SharedPixelBuffer<Rgba8Pixel>),
    /// `slint::Image` não atravessa threads; o SVG é lido na thread da interface.
    Svg(Vec<u8>),
}

pub fn decode(bytes: Vec<u8>) -> Option<Decoded> {
    if bytes.trim_ascii_start().starts_with(b"<") {
        return Some(Decoded::Svg(bytes));
    }
    let mut reader = image::ImageReader::new(std::io::Cursor::new(&bytes))
        .with_guessed_format()
        .ok()?;
    // Limita a descompressão: uma imagem pequena no fio não pode pedir gigabytes de pixels.
    let mut limits = image::Limits::default();
    limits.max_alloc = Some(256 * 1024 * 1024);
    reader.limits(limits.clone());
    let mut decoder = reader.into_decoder().ok()?;
    let (width, height) = decoder.dimensions();
    raster_bytes(width, height)?;
    limits.reserve(decoder.total_bytes()).ok()?;
    decoder.set_limits(limits).ok()?;
    let rgba = image::DynamicImage::from_decoder(decoder)
        .ok()?
        .into_rgba8();
    Some(Decoded::Pixels(SharedPixelBuffer::clone_from_slice(
        rgba.as_raw(),
        rgba.width(),
        rgba.height(),
    )))
}

pub fn raster_bytes(width: u32, height: u32) -> Option<usize> {
    let pixels = u64::from(width) * u64::from(height);
    if pixels == 0 || pixels > MAX_IMAGE_PIXELS {
        return None;
    }
    Some(pixels as usize * 4)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_data_host_files_and_web_urls_are_images() {
        assert_eq!(kind("data:image/png;base64,AAAA"), "data");
        assert_eq!(kind("/tmp/print.png"), "file");
        assert_eq!(host_path("file:///tmp/print.png"), Some("/tmp/print.png"));
        assert_eq!(kind("https://example.com/a.png"), "remote");
        for dest in [
            "//example.com/a.png",
            "https://user@example.com/a.png",
            "https://@example.com/a.png",
            "javascript:alert(1)",
            "a.png",
            "data:text/html;base64,AAAA",
            "file://host/a.png",
        ] {
            assert_eq!(kind(dest), "", "{dest}");
        }
    }

    #[test]
    fn data_uri_decodes_to_pixels_and_garbage_is_refused() {
        let png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
        let Some(Decoded::Pixels(pixels)) = decode(data_bytes(png).unwrap()) else {
            panic!("png")
        };
        assert_eq!((pixels.width(), pixels.height()), (1, 1));
        assert!(decode(b"not an image".to_vec()).is_none());
    }
}
