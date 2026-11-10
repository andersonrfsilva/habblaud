use crate::{AppWindow, feature::FeatureContext};
use slint::{
    ComponentHandle,
    winit_030::{WinitWindowAccessor, winit::event::WindowEvent},
};
use std::{
    cell::RefCell,
    rc::Rc,
    sync::Arc,
    sync::atomic::{AtomicBool, Ordering},
};

pub fn attributes(
    attributes: slint::winit_030::winit::window::WindowAttributes,
) -> slint::winit_030::winit::window::WindowAttributes {
    #[cfg(target_os = "linux")]
    {
        use slint::winit_030::winit::platform::x11::WindowAttributesExtX11;
        attributes.with_name("zaplaud", "zaplaud")
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
pub fn setup(window: &AppWindow, context: &FeatureContext) {
    let Ok(store) = zaplaud_store::LocalStateStore::new() else {
        return;
    };
    let path = store.directories().data.join("window.json");
    let saved = std::fs::read(&path)
        .ok()
        .and_then(|data| serde_json::from_slice::<serde_json::Value>(&data).ok())
        .unwrap_or_default();
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
    let (sender, mut receiver) = tokio::sync::mpsc::unbounded_channel();
    context.runtime.spawn(async move {
        while let Some(mut value) = receiver.recv().await {
            // Debounce de eventos, não um temporizador periódico.
            while let Ok(Some(next)) =
                tokio::time::timeout(std::time::Duration::from_millis(200), receiver.recv()).await
            {
                value = next;
            }
            match serde_json::to_vec(&value) {
                Ok(bytes) => {
                    let temp = path.with_extension("json.tmp");
                    if let Err(error) =
                        std::fs::write(&temp, bytes).and_then(|_| std::fs::rename(temp, &path))
                    {
                        eprintln!("janela: {error}");
                    }
                }
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
        let _ = sender.send(value.clone());
        slint::winit_030::EventResult::Propagate
    });
}
