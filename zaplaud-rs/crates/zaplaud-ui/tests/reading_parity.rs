use slint::platform::{
    Platform, WindowAdapter, WindowEvent,
    software_renderer::{MinimalSoftwareWindow, RepaintBufferType},
};
use slint::{
    ComponentHandle, Image, Model, ModelRc, Rgb8Pixel, Rgba8Pixel, SharedPixelBuffer, VecModel,
};
use std::{cell::Cell, rc::Rc, time::Duration};
use zaplaud_ui::conversation_rows::{
    AppWindow, ConversationRow, ConversationState, ConversationViewState, DiagramState, FilesState,
    GifFrame, MarkdownImages, MdBlock, TranscriptProbe,
};

struct Headless(Rc<MinimalSoftwareWindow>);
impl Platform for Headless {
    fn create_window_adapter(&self) -> Result<Rc<dyn WindowAdapter>, slint::PlatformError> {
        Ok(self.0.clone())
    }
}

fn frame(window: &MinimalSoftwareWindow) -> Vec<Rgb8Pixel> {
    slint::platform::update_timers_and_animations();
    window.request_redraw();
    let mut pixels = vec![Rgb8Pixel::default(); 800 * 600];
    window.draw_if_needed(|renderer| {
        renderer.render(&mut pixels, 800);
    });
    pixels
}

#[test]
fn visible_gif_layout_does_not_reenter_the_frame_binding() {
    let window = MinimalSoftwareWindow::new(RepaintBufferType::NewBuffer);
    slint::platform::set_platform(Box::new(Headless(window.clone()))).unwrap();
    let probe = TranscriptProbe::new().unwrap();
    probe.set_requested_width(800.);
    probe.set_requested_height(600.);
    probe.show().unwrap();
    window.set_size(slint::PhysicalSize::new(800, 600));

    let picture = |r, g, b| {
        let mut pixels = SharedPixelBuffer::<Rgba8Pixel>::new(32, 32);
        pixels.make_mut_slice().fill(Rgba8Pixel { r, g, b, a: 255 });
        Image::from_rgba8(pixels)
    };
    let poster = picture(193, 38, 111);
    let frames = ModelRc::new(VecModel::from(vec![
        GifFrame {
            picture: poster.clone(),
            delay: 5000,
        },
        GifFrame {
            picture: picture(17, 71, 201),
            delay: 5000,
        },
    ]));
    let images = probe.global::<MarkdownImages>();
    images.on_get(move |_, _| poster.clone());
    let requested = Rc::new(Cell::new(false));
    let requested_by_view = requested.clone();
    images.on_frames(move |_, _| {
        requested_by_view.set(true);
        frames.clone()
    });
    probe
        .global::<ConversationViewState>()
        .set_rows(ModelRc::new(VecModel::from(vec![ConversationRow {
            id: "gif".into(),
            entry_id: "gif".into(),
            kind: "assistant".into(),
            blocks: ModelRc::new(VecModel::from(vec![MdBlock {
                kind: "image".into(),
                text: "/tmp/fictitious.gif".into(),
                ..Default::default()
            }])),
            first: true,
            last: true,
            ..Default::default()
        }])));
    assert!(frame(&window).contains(&Rgb8Pixel {
        r: 193,
        g: 38,
        b: 111
    }));
    assert!(requested.get(), "the visible GIF requests its frames");
}

#[test]
fn reopening_uneven_history_restores_the_saved_row_without_scrolling() {
    let window = MinimalSoftwareWindow::new(RepaintBufferType::NewBuffer);
    slint::platform::set_platform(Box::new(Headless(window.clone()))).unwrap();
    let probe = TranscriptProbe::new().unwrap();
    probe.set_requested_width(800.);
    probe.set_requested_height(600.);
    probe.show().unwrap();
    window.set_size(slint::PhysicalSize::new(800, 600));
    let state = probe.global::<ConversationViewState>();
    let measured = Rc::new(Cell::new(None));
    let anchor_y = measured.clone();
    state.on_geometry(move |id, y, _, _| {
        if id == "reading-anchor" {
            anchor_y.set(Some(y));
        }
    });
    for anchor in [27, 28, 29, 30, 31, 32, 60, 103, 171, 208] {
        state.set_reset_request(state.get_reset_request() + 1);
        state.set_rows(ModelRc::default());
        frame(&window);
        let rows = (0..220)
            .map(|index| {
                let lines = [2, 19, 25, 24, 18, 2, 7, 4, 8][index % 9];
                let id = if index == anchor {
                    "reading-anchor".into()
                } else {
                    format!("entry-{index}")
                };
                ConversationRow {
                    entry_id: id.clone().into(),
                    id: id.into(),
                    kind: "system".into(),
                    fragment: "Linha fictícia de uma mensagem\n".repeat(lines).into(),
                    first: true,
                    last: true,
                    ..Default::default()
                }
            })
            .collect::<Vec<_>>();
        state.set_rows(ModelRc::new(VecModel::from(rows)));
        state.set_following(false);
        state.set_anchor_index(anchor as i32);
        state.set_anchor_offset(-27.);
        state.set_anchor_ticks(46);
        state.set_stable_ticks(0);
        state.set_measured_height(-1.);
        state.set_scan_index(-1);
        state.set_restore_pending(true);
        frame(&window);
        for _ in 0..46 {
            if !state.get_restore_pending() {
                break;
            }
            std::thread::sleep(Duration::from_millis(35));
            frame(&window);
        }
        assert!(
            !state.get_restore_pending(),
            "anchor {anchor} did not settle within 46 frames: scan={}, y={}, ticks={}",
            state.get_scan_index(),
            state.get_scroll_y(),
            state.get_anchor_ticks()
        );
        let settled_y = state.get_scroll_y();
        for _ in 0..5 {
            std::thread::sleep(Duration::from_millis(35));
            frame(&window);
            assert_eq!(state.get_scroll_y(), settled_y, "anchor {anchor} moved");
        }
        measured.set(None);
        state.set_geometry_request(state.get_geometry_request() + 1);
        frame(&window);
        let y = measured.get().unwrap_or_else(|| {
            panic!(
                "anchor {anchor} is not visible: scan={}, y={}, ticks={}",
                state.get_scan_index(),
                state.get_scroll_y(),
                state.get_anchor_ticks(),
            )
        });
        assert!((y + 27.).abs() < 2., "anchor {anchor} offset: {y}");
    }
}

#[test]
fn fractional_scrolling_keeps_its_position_after_the_gesture() {
    struct ScrollClock(Rc<MinimalSoftwareWindow>, Rc<Cell<u64>>);
    impl Platform for ScrollClock {
        fn create_window_adapter(&self) -> Result<Rc<dyn WindowAdapter>, slint::PlatformError> {
            Ok(self.0.clone())
        }

        fn duration_since_start(&self) -> Duration {
            Duration::from_millis(self.1.get())
        }
    }

    let window = MinimalSoftwareWindow::new(RepaintBufferType::NewBuffer);
    let clock = Rc::new(Cell::new(0));
    slint::platform::set_platform(Box::new(ScrollClock(window.clone(), clock.clone()))).unwrap();
    let probe = TranscriptProbe::new().unwrap();
    probe.set_requested_width(800.);
    probe.set_requested_height(600.);
    probe.show().unwrap();
    window.set_size(slint::PhysicalSize::new(800, 600));
    let state = probe.global::<ConversationViewState>();
    let row = |index| {
        let id: slint::SharedString = format!("fictitious-{index}").into();
        ConversationRow {
            entry_id: id.clone(),
            id,
            kind: "system".into(),
            fragment: "Histórico fictício\n".repeat(4).into(),
            first: true,
            last: true,
            ..Default::default()
        }
    };
    let mut pixels = vec![Rgb8Pixel::default(); 800 * 600];
    let mut tick = || {
        clock.set(clock.get() + 10);
        slint::platform::update_timers_and_animations();
        window.draw_if_needed(|renderer| {
            renderer.render(&mut pixels, 800);
        });
    };
    for fraction in [1., 0.5] {
        let rows = Rc::new(VecModel::from((0..120).map(row).collect::<Vec<_>>()));
        state.set_rows(ModelRc::new(rows.clone()));
        tick();
        probe.invoke_scroll_to(fraction);
        for _ in 0..10 {
            tick();
        }

        let start = state.get_scroll_y();
        for elapsed in 0..=50 {
            if elapsed % 5 == 0 {
                probe.window().dispatch_event(WindowEvent::PointerScrolled {
                    position: slint::LogicalPosition::new(650., 300.),
                    delta_x: 0.,
                    delta_y: 0.125,
                });
            }
            tick();
        }
        let released = state.get_scroll_y();
        assert!((released - start - 1.375).abs() < 0.01);
        for _ in 0..200 {
            tick();
            assert_eq!(
                state.get_scroll_y(),
                released,
                "jump after scrolling from {fraction}"
            );
        }
        assert!(!state.get_following());
        rows.push(row(120));
        for _ in 0..10 {
            tick();
        }
        assert_eq!(
            state.get_scroll_y(),
            released,
            "new message moved the history"
        );
    }
}

#[test]
fn restore_settles_once_cancels_on_navigation_and_pins_expire() {
    let window = MinimalSoftwareWindow::new(RepaintBufferType::NewBuffer);
    slint::platform::set_platform(Box::new(Headless(window.clone()))).unwrap();
    let probe = TranscriptProbe::new().unwrap();
    probe.set_requested_width(800.);
    probe.set_requested_height(600.);
    probe.show().unwrap();
    window.set_size(slint::PhysicalSize::new(800, 600));
    let state = probe.global::<ConversationViewState>();
    let rows = Rc::new(VecModel::from(vec![ConversationRow {
        id: "reply".into(),
        entry_id: "reply".into(),
        kind: "system".into(),
        fragment: "Linha de leitura\n".repeat(80).into(),
        first: true,
        last: true,
        ..Default::default()
    }]));
    state.set_rows(ModelRc::new(rows.clone()));
    state.set_following(false);
    state.set_anchor_index(0);
    state.set_anchor_offset(-200.);
    state.set_anchor_ticks(46);
    state.set_restore_pending(true);
    frame(&window);
    for lines in 81..84 {
        std::thread::sleep(Duration::from_millis(35));
        rows.set_row_data(
            0,
            ConversationRow {
                id: "reply".into(),
                entry_id: "reply".into(),
                kind: "system".into(),
                fragment: "Linha de leitura\n".repeat(lines).into(),
                first: true,
                last: true,
                ..Default::default()
            },
        );
        frame(&window);
        assert!(
            state.get_restore_pending(),
            "late height changes delay saved offset"
        );
    }
    for _ in 0..10 {
        std::thread::sleep(Duration::from_millis(35));
        frame(&window);
    }
    assert!(
        !state.get_restore_pending(),
        "stable row completes restore: ticks={}, stable={}, scan={}, height={}",
        state.get_anchor_ticks(),
        state.get_stable_ticks(),
        state.get_scan_index(),
        state.get_measured_height()
    );
    assert_eq!(
        state.get_anchor_ticks(),
        0,
        "restore leaves no continuous compensation"
    );
    assert!(
        (state.get_scroll_y() + 200.).abs() < 2.,
        "saved offset applies after measurement"
    );
    state.set_restore_pending(true);
    state.set_anchor_ticks(46);
    probe.invoke_page_up();
    assert!(
        !state.get_restore_pending(),
        "navigation permanently cancels pending restore"
    );
    assert_eq!(state.get_anchor_ticks(), 0);
    state.set_anchor_ticks(2);
    for _ in 0..3 {
        std::thread::sleep(Duration::from_millis(35));
        frame(&window);
    }
    assert_eq!(state.get_anchor_ticks(), 0, "toggle pin expires");
    let before = state.get_scroll_y();
    rows.push(ConversationRow {
        id: "next".into(),
        entry_id: "next".into(),
        kind: "system".into(),
        fragment: "Nova entrada\n".repeat(30).into(),
        first: true,
        last: true,
        ..Default::default()
    });
    frame(&window);
    assert_eq!(
        state.get_scroll_y(),
        before,
        "incoming entries leave reading-above still"
    );
    probe.invoke_jump_to_end();
    for _ in 0..4 {
        frame(&window);
    }
    rows.push(ConversationRow {
        id: "last".into(),
        entry_id: "last".into(),
        kind: "system".into(),
        fragment: "Última entrada\n".repeat(30).into(),
        first: true,
        last: true,
        ..Default::default()
    });
    for _ in 0..4 {
        frame(&window);
    }
    assert!(state.get_following());
    let last_bottom = Rc::new(std::cell::Cell::new(None));
    let measured = last_bottom.clone();
    state.on_geometry(move |id, y, height, _| {
        if id == "last" {
            measured.set(Some(y + height));
        }
    });
    state.set_geometry_request(state.get_geometry_request() + 1);
    for _ in 0..4 {
        frame(&window);
    }
    let bottom = last_bottom
        .get()
        .expect("following materializes last entry");
    assert!(
        (bottom - 600.).abs() < 2.,
        "following keeps last entry at viewport bottom: {bottom}"
    );
    probe.hide().unwrap();
    drop(probe);

    let app = AppWindow::new().unwrap();
    app.global::<ConversationState>().set_selected(true);
    app.global::<FilesState>().set_close_label("Fechar".into());
    let diagram =
        zaplaud_ui::markdown::parse("```mermaid\nflowchart LR\nA[Pedido] --> B[Processar]\n```")
            .into_iter()
            .find(|block| block.diagram.size().width > 0)
            .unwrap()
            .diagram;
    let diagram_state = app.global::<DiagramState>();
    diagram_state.invoke_open(diagram);
    app.show().unwrap();
    window.set_size(slint::PhysicalSize::new(800, 600));
    let fitted = frame(&window);
    assert!(
        fitted
            .iter()
            .filter(|pixel| pixel.r == 255 && pixel.g == 255 && pixel.b == 255)
            .count()
            > 300_000
    );
    for _ in 0..20 {
        app.window()
            .dispatch_event(WindowEvent::KeyPressed { text: "+".into() });
        frame(&window);
    }
    let maximum = frame(&window);
    app.window()
        .dispatch_event(WindowEvent::KeyPressed { text: "+".into() });
    assert_eq!(maximum, frame(&window), "zoom clamps at 8x");
    assert_ne!(maximum, fitted, "zoom changes rendered image");
    app.window()
        .dispatch_event(WindowEvent::KeyPressed { text: "0".into() });
    assert_eq!(fitted, frame(&window), "reset fits diagram again");
    app.window().dispatch_event(WindowEvent::KeyReleased {
        text: slint::platform::Key::Escape.into(),
    });
    assert!(
        !diagram_state.get_visible(),
        "Escape closes fullscreen only"
    );
    assert!(app.global::<ConversationState>().get_selected());
}
