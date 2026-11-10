#[cfg(target_os = "linux")]
mod linux {
    use crate::{AppWindow, feature::FeatureContext, strings, update_ui};
    use ksni::TrayMethods;
    use slint::ComponentHandle;
    struct Tray {
        window: slint::Weak<AppWindow>,
    }
    impl ksni::Tray for Tray {
        fn id(&self) -> String {
            "zaplaud".into()
        }
        fn title(&self) -> String {
            strings::APP_NAME.into()
        }
        fn icon_name(&self) -> String {
            "zaplaud".into()
        }
        fn icon_pixmap(&self) -> Vec<ksni::Icon> {
            vec![ksni::Icon {
                width: 64,
                height: 64,
                data: include_bytes!(concat!(env!("OUT_DIR"), "/tray.argb")).to_vec(),
            }]
        }
        fn tool_tip(&self) -> ksni::ToolTip {
            ksni::ToolTip {
                title: strings::APP_NAME.into(),
                ..Default::default()
            }
        }
        fn activate(&mut self, _x: i32, _y: i32) {
            update_ui(&self.window, |window| crate::alerts::raise(&window));
        }
        fn menu(&self) -> Vec<ksni::MenuItem<Self>> {
            vec![
                ksni::menu::StandardItem {
                    label: strings::TRAY_OPEN.into(),
                    activate: Box::new(|tray: &mut Self| tray.activate(0, 0)),
                    ..Default::default()
                }
                .into(),
                ksni::menu::StandardItem {
                    label: strings::TRAY_QUIT.into(),
                    activate: Box::new(|_: &mut Self| {
                        let _ = slint::quit_event_loop();
                    }),
                    ..Default::default()
                }
                .into(),
            ]
        }
    }
    pub fn setup(window: &AppWindow, context: &FeatureContext) {
        let tray = Tray {
            window: window.as_weak(),
        };
        context.runtime.spawn(async move {
            match tray.spawn().await {
                Ok(handle) => {
                    std::future::pending::<()>().await;
                    drop(handle);
                }
                Err(error) => eprintln!("bandeja indisponível: {error}"),
            }
        });
    }
}
pub fn setup(window: &crate::AppWindow, context: &crate::feature::FeatureContext) {
    #[cfg(target_os = "linux")]
    linux::setup(window, context);
    #[cfg(not(target_os = "linux"))]
    let _ = (window, context);
}
