use crate::{AppWindow, feature::FeatureContext};
use slint::{
    ComponentHandle,
    winit_030::{WinitWindowAccessor, winit::event::WindowEvent},
};
use std::{
    cell::RefCell,
    io::{self, Write},
    path::{Path, PathBuf},
    rc::Rc,
    sync::atomic::{AtomicBool, AtomicU64, Ordering},
    sync::{Arc, Mutex, OnceLock},
};

pub fn attributes(
    attributes: slint::winit_030::winit::window::WindowAttributes,
) -> slint::winit_030::winit::window::WindowAttributes {
    #[cfg(target_os = "linux")]
    {
        let attributes = slint::winit_030::winit::platform::x11::WindowAttributesExtX11::with_name(
            attributes, "zaplaud", "zaplaud",
        );
        slint::winit_030::winit::platform::wayland::WindowAttributesExtWayland::with_name(
            attributes, "zaplaud", "zaplaud",
        )
    }
    #[cfg(not(target_os = "linux"))]
    {
        attributes
    }
}

type VisibilityHandler = Box<dyn Fn(bool)>;
thread_local! { static VISIBILITY: RefCell<Vec<VisibilityHandler>> = RefCell::new(Vec::new()); }
thread_local! { static FOCUS: RefCell<Vec<VisibilityHandler>> = RefCell::new(Vec::new()); }
pub fn on_focus(handler: impl Fn(bool) + 'static) {
    FOCUS.with(|handlers| handlers.borrow_mut().push(Box::new(handler)));
}
pub fn on_visibility(handler: impl Fn(bool) + 'static) {
    VISIBILITY.with(|handlers| handlers.borrow_mut().push(Box::new(handler)));
}

struct Persistence {
    path: PathBuf,
    pending: Mutex<Option<serde_json::Value>>,
    writing: Mutex<()>,
}
static PERSISTENCE: OnceLock<Arc<Persistence>> = OnceLock::new();
static TEMP_ID: AtomicU64 = AtomicU64::new(0);

fn saved_object(data: &[u8]) -> serde_json::Value {
    serde_json::from_slice::<serde_json::Value>(data)
        .ok()
        .filter(serde_json::Value::is_object)
        .unwrap_or_else(|| serde_json::json!({}))
}

fn write_atomic(path: &Path, value: &serde_json::Value) -> io::Result<()> {
    let temp = path.with_extension(format!(
        "json.{}.{}.tmp",
        std::process::id(),
        TEMP_ID.fetch_add(1, Ordering::Relaxed)
    ));
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temp)?;
    let result = (|| {
        serde_json::to_writer(&mut file, value)?;
        file.flush()?;
        file.sync_all()?;
        std::fs::rename(&temp, path)
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(temp);
    }
    result
}

impl Persistence {
    fn flush(&self) -> io::Result<()> {
        let _writing = self
            .writing
            .lock()
            .map_err(|_| io::Error::other("janela: gravação bloqueada"))?;
        let value = self
            .pending
            .lock()
            .map_err(|_| io::Error::other("janela: estado bloqueado"))?
            .clone();
        if let Some(value) = value {
            write_atomic(&self.path, &value)?;
            let mut pending = self
                .pending
                .lock()
                .map_err(|_| io::Error::other("janela: estado bloqueado"))?;
            if pending.as_ref() == Some(&value) {
                *pending = None;
            }
        }
        Ok(())
    }
}

pub fn flush() -> io::Result<()> {
    if let Some(persistence) = PERSISTENCE.get() {
        persistence.flush()?;
    }
    Ok(())
}
pub fn setup(window: &AppWindow, context: &FeatureContext) {
    let weak = window.as_weak();
    let runtime = context.runtime.clone();
    let read = runtime.spawn_blocking(|| {
        let store = zaplaud_store::LocalStateStore::new()?;
        let path = store.directories().data.join("window.json");
        let saved = std::fs::read(&path)
            .ok()
            .map(|data| saved_object(&data))
            .unwrap_or_else(|| serde_json::json!({}));
        Ok::<_, zaplaud_store::StoreError>((path, saved))
    });
    let _ = slint::spawn_local(async move {
        match read.await {
            Ok(Ok((path, saved))) => {
                if let Some(window) = weak.upgrade() {
                    setup_loaded(&window, &runtime, path, saved);
                }
            }
            Ok(Err(error)) => eprintln!("janela: {error}"),
            Err(error) => eprintln!("janela: {error}"),
        }
    });
}

fn setup_loaded(
    window: &AppWindow,
    runtime: &tokio::runtime::Handle,
    path: PathBuf,
    saved: serde_json::Value,
) {
    let saved = Rc::new(RefCell::new(saved));
    {
        let value = saved.borrow();
        if let (Some(width), Some(height)) = (value["width"].as_u64(), value["height"].as_u64())
            && (400..=16384).contains(&width)
            && (300..=16384).contains(&height)
        {
            window
                .window()
                .set_size(slint::PhysicalSize::new(width as u32, height as u32));
        }
        if std::env::var_os("WAYLAND_DISPLAY").is_none()
            && let (Some(x), Some(y)) = (value["x"].as_i64(), value["y"].as_i64())
            && let (Ok(x), Ok(y)) = (i32::try_from(x), i32::try_from(y))
        {
            window
                .window()
                .set_position(slint::PhysicalPosition::new(x, y));
        }
        window
            .window()
            .set_maximized(value["maximized"].as_bool().unwrap_or(false));
    }
    let persistence = Arc::new(Persistence {
        path,
        pending: Mutex::new(None),
        writing: Mutex::new(()),
    });
    let _ = PERSISTENCE.set(persistence.clone());
    let writer = persistence.clone();
    let (sender, mut receiver) = tokio::sync::mpsc::unbounded_channel();
    runtime.spawn(async move {
        while receiver.recv().await.is_some() {
            // Debounce de eventos, não um temporizador periódico.
            while let Ok(Some(())) =
                tokio::time::timeout(std::time::Duration::from_millis(200), receiver.recv()).await
            {
            }
            let writer = writer.clone();
            match tokio::task::spawn_blocking(move || writer.flush()).await {
                Ok(Ok(())) => {}
                Ok(Err(error)) => eprintln!("janela: {error}"),
                Err(error) => eprintln!("janela: {error}"),
            }
        }
    });
    let ready = Arc::new(AtomicBool::new(false));
    let initialized = ready.clone();
    let weak = window.as_weak();
    let _ = slint::spawn_local(async move {
        if let Some(window) = weak.upgrade()
            && window.window().winit_window().await.is_ok()
        {
            initialized.store(true, Ordering::Relaxed);
        }
    });
    window.window().on_winit_window_event(move |window, event| {
        if let WindowEvent::Focused(value) = event {
            FOCUS.with(|handlers| {
                for handler in handlers.borrow().iter() {
                    handler(*value);
                }
            });
        }
        if let WindowEvent::Occluded(occluded) = event {
            VISIBILITY.with(|handlers| {
                for handler in handlers.borrow().iter() {
                    handler(!occluded);
                }
            });
        }
        if !ready.load(Ordering::Relaxed) {
            return slint::winit_030::EventResult::Propagate;
        }
        let mut value = saved.borrow_mut();
        let maximized = window
            .with_winit_window(|native| native.is_maximized())
            .unwrap_or(window.is_maximized());
        value["maximized"] = maximized.into();
        match event {
            WindowEvent::Resized(size) => {
                if !maximized && size.width > 0 && size.height > 0 {
                    value["width"] = size.width.into();
                    value["height"] = size.height.into();
                }
            }
            WindowEvent::Moved(position) if !maximized => {
                value["x"] = position.x.into();
                value["y"] = position.y.into();
            }
            _ => return slint::winit_030::EventResult::Propagate,
        }
        if let Ok(mut pending) = persistence.pending.lock() {
            *pending = Some(value.clone());
            let _ = sender.send(());
        }
        slint::winit_030::EventResult::Propagate
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn malformed_and_non_object_window_state_remains_editable() {
        for data in [
            b"invalid".as_slice(),
            b"null",
            b"[]",
            b"1",
            b"true",
            b"\"text\"",
        ] {
            let mut value = saved_object(data);
            value["width"] = 800.into();
            assert_eq!(value["width"], 800);
        }
        assert_eq!(saved_object(br#"{"width":900}"#)["width"], 900);
    }

    #[test]
    fn shutdown_flush_writes_latest_state_without_waiting_for_debounce() -> io::Result<()> {
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("window.json");
        let persistence = Persistence {
            path: path.clone(),
            pending: Mutex::new(Some(serde_json::json!({"width":800}))),
            writing: Mutex::new(()),
        };
        persistence.flush()?;
        *persistence.pending.lock().unwrap() = Some(serde_json::json!({"width":900}));
        persistence.flush()?;
        persistence.flush()?;
        assert_eq!(saved_object(&std::fs::read(path)?)["width"], 900);
        assert_eq!(std::fs::read_dir(dir.path())?.count(), 1);
        Ok(())
    }

    #[test]
    fn failed_atomic_write_keeps_pending_state_and_cleans_temp_file() -> io::Result<()> {
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("window.json");
        std::fs::create_dir(&path)?;
        let persistence = Persistence {
            path: path.clone(),
            pending: Mutex::new(Some(serde_json::json!({"width":800}))),
            writing: Mutex::new(()),
        };
        assert!(persistence.flush().is_err());
        assert!(path.is_dir());
        assert!(persistence.pending.lock().unwrap().is_some());
        assert_eq!(std::fs::read_dir(dir.path())?.count(), 1);
        std::fs::remove_dir(&path)?;
        persistence.flush()?;
        assert_eq!(saved_object(&std::fs::read(path)?)["width"], 800);
        Ok(())
    }
}
