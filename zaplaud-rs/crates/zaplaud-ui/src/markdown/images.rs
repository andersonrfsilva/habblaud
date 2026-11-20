//! Política e decodificação das imagens `![alt](destino)` da conversa; não acessa a rede.

use base64::Engine;
use image::{AnimationDecoder, ImageDecoder, ImageFormat, codecs::gif::GifDecoder};
use slint::{Rgba8Pixel, SharedPixelBuffer};
use zaplaud_core::api::{MAX_IMAGE_BYTES, UrlPolicy};

const MAX_IMAGE_PIXELS: u64 = 4096 * 4096;
const MAX_GIF_FRAMES: usize = 300;
const MAX_GIF_BYTES: usize = 50_000_000;

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
    Animated {
        frames: Vec<SharedPixelBuffer<Rgba8Pixel>>,
        delays: Vec<u32>,
    },
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
    if reader.format() == Some(ImageFormat::Gif) {
        return decode_gif(&bytes);
    }
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
    Some(Decoded::Pixels(slint_pixels(&rgba)))
}

fn decode_gif(bytes: &[u8]) -> Option<Decoded> {
    let mut decoder = GifDecoder::new(std::io::Cursor::new(bytes)).ok()?;
    let (width, height) = decoder.dimensions();
    let frame_bytes = raster_bytes(width, height)?;
    let max_frames = (MAX_GIF_BYTES / frame_bytes).min(MAX_GIF_FRAMES);
    let mut limits = image::Limits::default();
    limits.max_alloc = Some(256 * 1024 * 1024);
    decoder.set_limits(limits).ok()?;
    let mut decoded = decoder.into_frames().map_while(Result::ok);
    let first = decoded.next()?;
    if max_frames < 2 {
        return Some(Decoded::Pixels(slint_pixels(first.buffer())));
    }
    let Some(second) = decoded.next() else {
        return Some(Decoded::Pixels(slint_pixels(first.buffer())));
    };
    let mut frames = vec![slint_pixels(first.buffer())];
    let mut delays = vec![gif_delay(&first)];
    drop(first);
    for frame in std::iter::once(second).chain(decoded) {
        // Todos os quadros compostos têm o tamanho lógico do GIF.
        if frames.len() >= max_frames {
            return frames.into_iter().next().map(Decoded::Pixels);
        }
        delays.push(gif_delay(&frame));
        frames.push(slint_pixels(frame.buffer()));
    }
    Some(Decoded::Animated { frames, delays })
}

fn gif_delay(frame: &image::Frame) -> u32 {
    let (numerator, denominator) = frame.delay().numer_denom_ms();
    let millis = numerator / denominator.max(1);
    if millis < 20 { 100 } else { millis }
}

fn slint_pixels(rgba: &image::RgbaImage) -> SharedPixelBuffer<Rgba8Pixel> {
    SharedPixelBuffer::clone_from_slice(rgba.as_raw(), rgba.width(), rgba.height())
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

    fn gif(width: u32, height: u32, delays: impl IntoIterator<Item = u32>) -> Vec<u8> {
        let mut bytes = Vec::new();
        let mut encoder = image::codecs::gif::GifEncoder::new(&mut bytes);
        for (index, delay) in delays.into_iter().enumerate() {
            let pixels =
                image::RgbaImage::from_pixel(width, height, image::Rgba([index as u8, 0, 0, 255]));
            encoder
                .encode_frame(image::Frame::from_parts(
                    pixels,
                    0,
                    0,
                    image::Delay::from_numer_denom_ms(delay, 1),
                ))
                .unwrap();
        }
        drop(encoder);
        bytes
    }

    #[test]
    fn gif_single_frame_is_static() {
        assert!(matches!(decode(gif(2, 2, [0])), Some(Decoded::Pixels(_))));
    }

    #[test]
    fn gif_multiple_frames_animate() {
        let Some(Decoded::Animated { frames, delays }) = decode(gif(2, 2, [0, 10, 20])) else {
            panic!("expected an animation");
        };
        assert_eq!(delays, [100, 100, 20]);
        assert_eq!(frames.len(), 3);
        for (index, pixels) in frames.iter().enumerate() {
            assert_eq!((pixels.width(), pixels.height()), (2, 2));
            assert_eq!(pixels.as_slice()[0].r, index as u8);
        }
    }

    #[test]
    fn gif_above_frame_limit_keeps_first_frame() {
        let Some(Decoded::Pixels(first)) = decode(gif(2, 2, [20; 301])) else {
            panic!("expected the first frame");
        };
        assert_eq!(first.as_slice()[0].r, 0);
    }

    #[test]
    fn gif_above_byte_limit_keeps_first_frame() {
        assert!(matches!(
            decode(gif(1200, 1200, [20; 9])),
            Some(Decoded::Pixels(_))
        ));
    }

    #[test]
    fn gif_truncated_mid_frame_keeps_valid_pixels() {
        let mut bytes = gif(64, 64, [20; 3]);
        bytes.truncate(bytes.len() * 3 / 4);
        assert!(decode(bytes).is_some());
    }
}
