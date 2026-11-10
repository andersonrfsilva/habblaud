use slint::{ComponentHandle, Model, ModelExt, ModelRc, VecModel};
use std::{
    cell::RefCell,
    collections::{BTreeMap, HashSet},
    path::PathBuf,
    rc::Rc,
    time::Duration,
};
use zaplaud_ui::conversation_rows::{
    Clock, ConversationViewState, MarkdownGeometry, Theme, TranscriptProbe, build_rows,
};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let path = args.next().ok_or("uso: transcript_probe CACHE [--size WxH] [--output DIR] [--fraction N] [--page-up N] [--expand-all] [--hold SEGUNDOS]")?;
    let (mut width, mut height) = (1440_f32, 900_f32);
    let mut output = PathBuf::from("/tmp/zaplaud-transcript-probe");
    let mut fraction = 1.;
    let mut pages = 4usize;
    let mut expanded = false;
    let mut hold = 0u64;
    let mut working = false;
    let mut focused = false;
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--size" => {
                let size = args.next().ok_or("--size sem valor")?;
                let (w, h) = size.split_once('x').ok_or("tamanho inválido")?;
                width = w.parse()?;
                height = h.parse()?;
            }
            "--output" => output = args.next().ok_or("--output sem valor")?.into(),
            "--fraction" => {
                fraction = args
                    .next()
                    .ok_or("--fraction sem valor")?
                    .parse::<f32>()?
                    .clamp(0., 1.)
            }
            "--page-up" => pages = args.next().ok_or("--page-up sem valor")?.parse()?,
            "--hold" => hold = args.next().ok_or("--hold sem valor")?.parse()?,
            "--expand-all" => expanded = true,
            "--working" => working = true,
            "--focused" => focused = true,
            "--unfocused" => focused = false,
            _ => return Err(format!("opção desconhecida: {arg}").into()),
        }
    }
    if !width.is_finite() || !height.is_finite() || width <= 0. || height <= 0. {
        return Err("tamanho deve ser positivo e finito".into());
    }
    let mut cache: serde_json::Value = serde_json::from_slice(&std::fs::read(path)?)?;
    let truncated = cache
        .get("truncated")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let mut entries: Vec<zaplaud_core::model::TerminalEntry> =
        serde_json::from_value(cache.get_mut("entries").ok_or("cache sem entries")?.take())?;
    drop(cache);
    let mut expanded: HashSet<_> = if expanded {
        entries.iter().map(|e| e.id.clone()).collect()
    } else {
        HashSet::new()
    };
    let rows = build_rows(
        &entries,
        "all",
        &expanded,
        &BTreeMap::new(),
        false,
        truncated,
        0,
    );
    let renderer = std::env::var("SLINT_BACKEND").unwrap_or_else(|_| "winit-software".into());
    slint::BackendSelector::new()
        .backend_name("winit".into())
        .renderer_name(renderer.strip_prefix("winit-").unwrap_or(&renderer).into())
        .select()?;
    zaplaud_ui::emoji::setup(renderer == "winit-software")?;
    let window = TranscriptProbe::new()?;
    window
        .window()
        .set_size(slint::LogicalSize::new(width, height));
    window.global::<Theme>().set_dark(false);
    let state = window.global::<ConversationViewState>();
    state.set_working(working);
    state.set_window_focused(focused);
    let mut model = Rc::new(VecModel::from(rows));
    state.set_rows(ModelRc::new(model.clone().map(|r| r.into_ui())));
    let geometry = Rc::new(RefCell::new(BTreeMap::<String, (f32, f32, f32)>::new()));
    let measured = geometry.clone();
    state.on_geometry(move |id, y, h, content| {
        measured
            .borrow_mut()
            .insert(id.to_string(), (y, h, content));
    });
    let text_geometry = Rc::new(RefCell::new(Vec::<(String, f32, f32, f32, f32)>::new()));
    let measured_text = text_geometry.clone();
    window
        .global::<MarkdownGeometry>()
        .on_measured(move |id, y, height, required, width| {
            measured_text
                .borrow_mut()
                .push((id.to_string(), y, height, required, width));
        });
    std::fs::create_dir_all(&output)?;
    window.show()?;
    let activity_timer = slint::Timer::default();
    if working {
        let clock = window.global::<Clock>();
        clock.set_focused(focused);
        clock.on_spinner(|_, tick, focused| {
            if focused {
                ["·", "✢", "✳", "✶", "✻"][tick.rem_euclid(5) as usize]
            } else {
                "✻"
            }
            .into()
        });
        let started = std::time::Instant::now();
        clock.on_elapsed(move |_, _| format!("{}s", started.elapsed().as_secs()).into());
        let weak = window.as_weak();
        activity_timer.start(
            slint::TimerMode::Repeated,
            Duration::from_secs(if cfg!(target_os = "macos") && !focused {
                15
            } else {
                1
            }),
            move || {
                if let Some(window) = weak.upgrade() {
                    let clock = window.global::<Clock>();
                    clock.set_tick(clock.get_tick().wrapping_add(1));
                }
            },
        );
    }
    let weak = window.as_weak();
    let timer = Rc::new(slint::Timer::default());
    let timer_weak = Rc::downgrade(&timer);
    let mut step = 0usize;
    let mut failed = false;
    let result = Rc::new(RefCell::new(false));
    let result_timer = result.clone();
    timer.start(slint::TimerMode::Repeated, Duration::from_millis(250), move || {
        let Some(window) = weak.upgrade() else { return; };
        let action_count = pages + 11;
        if step.is_multiple_of(3) && step / 3 < action_count {
            let action = step / 3;
            match action {
                0 => window.invoke_scroll_to(fraction),
                n if n <= pages => window.invoke_page_up(),
                n if n == pages + 1 => window.invoke_scroll_to(0.5),
                n if n == pages + 2 || n == pages + 10 => window.invoke_jump_to_end(),
                n if n == pages + 4 => window.window().set_size(slint::LogicalSize::new((width * 0.6).max(320.), height)),
                n if n == pages + 5 => window.window().set_size(slint::LogicalSize::new(width, height)),
                _ => {
                    if action == pages + 3 || action == pages + 7 {
                        window.global::<ConversationViewState>().set_following(false);
                        expanded = if expanded.is_empty() { entries.iter().map(|entry| entry.id.clone()).collect() } else { HashSet::new() };
                    }
                    if action == pages + 6 {
                        entries.push(zaplaud_core::model::TerminalEntry {
                            id: "probe:stream".into(), kind: "assistant".into(),
                            text: "Atualização de streaming **sintética** ✅. ".repeat(80),
                            ..Default::default()
                        });
                    }
                    if action == pages + 7 && let Some(last) = entries.last_mut() {
                        last.text.push_str(&"\n\nContinuação sintética com nova altura. ".repeat(100));
                    }
                    if action == pages + 8 { entries.pop(); }
                    let session_entries = if action == pages + 8 { &entries[..entries.len().div_ceil(2)] } else { &entries };
                    let rows = build_rows(session_entries, "all", &expanded, &BTreeMap::new(), false, truncated, 0);
                    if action == pages + 8 || action == pages + 9 {
                        model = Rc::new(VecModel::from(rows));
                        let state = window.global::<ConversationViewState>();
                        state.set_rows(ModelRc::new(model.clone().map(|r| r.into_ui())));
                        state.set_following(true);
                        state.set_end_request(state.get_end_request().wrapping_add(1));
                    } else {
                        let old = model.row_count();
                        for (index, row) in rows.into_iter().enumerate() {
                            if index < old { model.set_row_data(index, row); } else { model.push(row); }
                        }
                        if action == pages + 6 { window.invoke_jump_to_end(); }
                    }
                }
            }
        } else if step % 3 == 1 && step / 3 < action_count {
            geometry.borrow_mut().clear();
            let state = window.global::<ConversationViewState>();
            state.set_geometry_request(state.get_geometry_request().wrapping_add(1));
            text_geometry.borrow_mut().clear();
            window.global::<MarkdownGeometry>().set_request(state.get_geometry_request());
        } else if step / 3 < action_count {
            let capture = || -> Result<(),Box<dyn std::error::Error>> {
                let snapshot = window.window().take_snapshot()?;
                image::save_buffer(output.join(format!("{:02}.png",step/3)), snapshot.as_bytes(),snapshot.width(),snapshot.height(),image::ColorType::Rgba8)?;
                let mut rows: Vec<_> = geometry.borrow().values().copied().collect();
                rows.sort_by(|a,b|a.0.total_cmp(&b.0));
                if rows.is_empty() { return Err("nenhuma geometria de linha foi observada".into()); }
                for (id, y, height, required, width) in text_geometry.borrow().iter() {
                    if height + 0.01 < *required {
                        return Err(format!("texto excede bloco: altura={height}, necessário={required}, largura={width}").into());
                    }
                    let &(row_y, row_height, _) = geometry.borrow().get(id).ok_or("bloco sem linha correspondente")?;
                    if *y < row_y - 0.01 || y + height > row_y + row_height + 0.01 {
                        return Err(format!("bloco excede linha: início={y}, fim={}, linha={row_y}..{}", y + height, row_y + row_height).into());
                    }
                }
                for (index, &(y,h,content)) in rows.iter().enumerate() {
                    if h + 0.01 < content { return Err(format!("conteúdo excede linha {index}: altura={h}, conteúdo={content}").into()); }
                    if let Some(next) = rows.get(index+1) && y + h > next.0 { return Err(format!("sobreposição entre linhas {index}/{}: fim={}, próximo={}",index+1,y+h,next.0).into()); }
                }
                println!("position={} instantiated={} overlap=0",step/3,rows.len());
                Ok(())
            };
            if let Err(error) = capture() { eprintln!("{error}"); failed = true; }
        } else {
            *result_timer.borrow_mut() = failed;
            if let Some(timer) = timer_weak.upgrade() { timer.stop(); }
            println!("hold={hold}s");
            slint::Timer::single_shot(Duration::from_secs(hold), || { let _ = slint::quit_event_loop(); });
        }
        step += 1;
    });
    slint::run_event_loop()?;
    if *result.borrow() {
        return Err("falha na geometria do transcript".into());
    }
    Ok(())
}
