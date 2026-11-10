use slint::{ComponentHandle, ModelExt, ModelRc, VecModel};
use std::{
    cell::RefCell,
    collections::{BTreeMap, HashSet},
    path::PathBuf,
    rc::Rc,
    time::Duration,
};
use zaplaud_ui::conversation_rows::{
    ConversationViewState, MarkdownGeometry, Theme, TranscriptProbe, build_rows,
};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let path = args.next().ok_or("uso: transcript_probe CACHE [--size WxH] [--output DIR] [--fraction N] [--page-up N] [--expand-all] [--hold SEGUNDOS]")?;
    let (mut width, mut height) = (1440., 900.);
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
    let cache: serde_json::Value = serde_json::from_slice(&std::fs::read(path)?)?;
    let entries: Vec<zaplaud_core::model::TerminalEntry> =
        serde_json::from_value(cache.get("entries").ok_or("cache sem entries")?.clone())?;
    let expanded: HashSet<_> = if expanded {
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
        cache
            .get("truncated")
            .and_then(|v| v.as_bool())
            .unwrap_or(false),
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
    state.set_rows(ModelRc::new(
        Rc::new(VecModel::from(rows)).map(|r| r.into_ui()),
    ));
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
    let weak = window.as_weak();
    let timer = slint::Timer::default();
    let mut step = 0usize;
    let mut failed = false;
    let result = Rc::new(RefCell::new(false));
    let result_timer = result.clone();
    timer.start(slint::TimerMode::Repeated, Duration::from_millis(250), move || {
        let Some(window) = weak.upgrade() else { return; };
        let action_count = pages + 3;
        if step % 3 == 0 && step / 3 < action_count {
            match step / 3 { 0 => window.invoke_scroll_to(fraction), n if n <= pages => window.invoke_page_up(), _ => window.invoke_jump_to_end() }
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
                    if height + 0.5 < *required {
                        return Err(format!("texto excede bloco: altura={height}, necessário={required}, largura={width}").into());
                    }
                    let &(row_y, row_height, _) = geometry.borrow().get(id).ok_or("bloco sem linha correspondente")?;
                    if *y < row_y - 0.5 || y + height > row_y + row_height + 0.5 {
                        return Err(format!("bloco excede linha: início={y}, fim={}, linha={row_y}..{}", y + height, row_y + row_height).into());
                    }
                }
                for (index, &(y,h,content)) in rows.iter().enumerate() {
                    if h + 0.5 < content { return Err(format!("conteúdo excede linha {index}: altura={h}, conteúdo={content}").into()); }
                    if let Some(next) = rows.get(index+1) && y + h > next.0 + 0.5 { return Err(format!("sobreposição entre linhas {index}/{}: fim={}, próximo={}",index+1,y+h,next.0).into()); }
                }
                println!("position={} instantiated={} overlap=0",step/3,rows.len());
                Ok(())
            };
            if let Err(error) = capture() { eprintln!("{error}"); failed = true; }
        } else if step >= action_count * 3 + (hold * 4) as usize {
            *result_timer.borrow_mut() = failed;
            let _ = slint::quit_event_loop();
        }
        step += 1;
    });
    slint::run_event_loop()?;
    if *result.borrow() {
        return Err("falha na geometria do transcript".into());
    }
    Ok(())
}
