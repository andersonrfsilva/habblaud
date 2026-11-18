use slint::platform::{
    Platform, WindowAdapter, WindowEvent,
    software_renderer::{MinimalSoftwareWindow, RepaintBufferType},
};
use slint::{ComponentHandle, Model, ModelRc, Rgb8Pixel, VecModel};
use std::{rc::Rc, time::Duration};
use zaplaud_ui::conversation_rows::{
    AppWindow, ConversationRow, ConversationState, ConversationViewState, DiagramState, FilesState,
    TranscriptProbe,
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
