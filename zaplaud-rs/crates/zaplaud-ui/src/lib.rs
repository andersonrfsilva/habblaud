pub mod conversation_rows;
pub mod emoji;
pub mod markdown;
pub mod transcript;

#[cfg(target_os = "android")]
extern crate self as zaplaud_ui;
#[cfg(target_os = "android")]
include!("main.rs");
