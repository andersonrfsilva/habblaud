use crate::{AppWindow, Clock, ListState, state::now_millis};
use slint::{ComponentHandle, Model};
use std::{
    cell::RefCell,
    collections::BTreeMap,
    rc::Rc,
    time::{Duration, Instant},
};

#[derive(Default)]
struct Demand {
    rows: BTreeMap<usize, (f32, f32)>,
    offset: f32,
    height: f32,
    list_visible: bool,
}

fn needs_clock(visible: bool, list: bool, conversation: bool) -> bool {
    visible && (list || conversation)
}

#[derive(Default)]
struct Schedule {
    next: Option<Duration>,
}

impl Schedule {
    fn advance(&mut self, now: Duration, needed: bool) -> bool {
        if !needed {
            self.next = None;
            return false;
        }
        if self.next.is_none_or(|next| now >= next) {
            self.next = Some(now.saturating_add(Duration::from_secs(1)));
            return true;
        }
        false
    }

    fn timer_fired(&mut self, now: Duration, needed: bool) -> bool {
        // O timer Slint já venceu; diferenças entre relógios não devem pular um segundo.
        self.advance(now.max(self.next.unwrap_or(now)), needed)
    }
}

pub fn refresh_list(window: &AppWindow) {
    window
        .global::<Clock>()
        .invoke_viewport(f32::NAN, 0., false);
}

pub fn setup(window: &AppWindow) {
    let timer = Rc::new(slint::Timer::default());
    let weak = window.as_weak();
    let shared_timer = timer.clone();
    let schedule = Rc::new(RefCell::new(Schedule::default()));
    let started = Instant::now();
    window.global::<Clock>().on_refresh(move || {
        let Some(window) = weak.upgrade() else { return };
        let clock = window.global::<Clock>();
        let needed = needs_clock(
            clock.get_visible(),
            clock.get_list_needed() && clock.get_list_visible(),
            clock.get_conversation_needed() && clock.get_conversation_visible(),
        );
        let tick = schedule.borrow_mut().advance(started.elapsed(), needed);
        if needed {
            if !shared_timer.running() {
                let weak = window.as_weak();
                let schedule = schedule.clone();
                shared_timer.start(
                    slint::TimerMode::Repeated,
                    Duration::from_secs(1),
                    move || {
                        if let Some(window) = weak.upgrade() {
                            let clock = window.global::<Clock>();
                            let needed = needs_clock(
                                clock.get_visible(),
                                clock.get_list_needed() && clock.get_list_visible(),
                                clock.get_conversation_needed() && clock.get_conversation_visible(),
                            );
                            if schedule.borrow_mut().timer_fired(started.elapsed(), needed) {
                                clock.set_tick(clock.get_tick().wrapping_add(1));
                            }
                        }
                    },
                );
            }
        } else {
            shared_timer.stop();
        }
        if tick {
            clock.set_tick(clock.get_tick().wrapping_add(1));
        }
    });
    window.global::<Clock>().on_spinner(|codex, tick, focused| {
        if !focused {
            return if codex { "•" } else { "✻" }.into();
        }
        const FRAMES: [&str; 10] = ["·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢"];
        const CODEX: [&str; 10] = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"];
        let index = tick.rem_euclid(10) as usize;
        if codex { CODEX[index] } else { FRAMES[index] }.into()
    });
    window.global::<Clock>().on_elapsed(|since, _tick| {
        since
            .parse::<i64>()
            .map(|since| zaplaud_core::rules::time::stopwatch(now_millis().saturating_sub(since)))
            .unwrap_or_default()
            .into()
    });
    window
        .global::<Clock>()
        .on_activity(|activity, since, _tick| {
            crate::list::activity_text(Some(activity.as_str()), since.parse().ok(), now_millis())
                .into()
        });
    let demand = Rc::new(RefCell::new(Demand::default()));
    let weak = window.as_weak();
    let viewport = demand.clone();
    window
        .global::<Clock>()
        .on_viewport(move |offset, height, visible| {
            let Some(window) = weak.upgrade() else { return };
            let mut demand = viewport.borrow_mut();
            if !offset.is_nan() {
                demand.offset = offset;
                demand.height = height;
                demand.list_visible = visible;
            }
            update_demand(&window, &demand);
        });
    let weak = window.as_weak();
    window
        .global::<Clock>()
        .on_row_layout(move |index, y, height| {
            let Some(window) = weak.upgrade() else { return };
            let Ok(index) = usize::try_from(index) else {
                return;
            };
            let mut demand = demand.borrow_mut();
            demand.rows.insert(index, (y, height));
            demand
                .rows
                .retain(|index, _| *index < window.global::<ListState>().get_rows().row_count());
            if demand.rows.len() > 256 {
                let offset = demand.offset;
                let viewport_height = demand.height;
                demand.rows.retain(|_, (y, height)| {
                    *y + offset + *height > 0. && *y + offset < viewport_height
                });
            }
            update_demand(&window, &demand);
        });
}

fn update_demand(window: &AppWindow, demand: &Demand) {
    let model = window.global::<ListState>().get_rows();
    let needed = demand.list_visible
        && demand.rows.iter().any(|(index, (y, height))| {
            y + demand.offset + height > 0.
                && y + demand.offset < demand.height
                && model
                    .row_data(*index)
                    .is_some_and(|row| row.working && !row.since.is_empty())
        });
    window.global::<Clock>().set_list_needed(needed);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clock_runs_only_for_visible_elapsed_text() {
        for visible in [false, true] {
            for list in [false, true] {
                for conversation in [false, true] {
                    assert_eq!(
                        needs_clock(visible, list, conversation),
                        visible && (list || conversation)
                    );
                }
            }
        }
    }

    #[test]
    fn fake_time_does_not_tick_when_hidden_or_without_consumers() {
        let mut schedule = Schedule::default();
        let second = Duration::from_secs;
        assert!(!schedule.advance(second(0), false));
        assert!(!schedule.advance(second(60), false));
        assert!(schedule.advance(second(60), true));
        assert!(!schedule.advance(Duration::from_millis(60_999), true));
        assert!(schedule.advance(second(61), true));
        assert!(!schedule.advance(second(62), false));
        assert!(!schedule.advance(second(120), false));
        assert!(schedule.advance(second(121), true));
        assert!(!schedule.advance(second(121), true));
    }

    #[test]
    fn timer_deadline_wins_over_small_clock_drift() {
        let mut schedule = Schedule::default();
        assert!(schedule.advance(Duration::ZERO, true));
        assert!(schedule.timer_fired(Duration::from_millis(999), true));
        assert!(schedule.timer_fired(Duration::from_millis(1999), true));
        assert!(!schedule.timer_fired(Duration::from_secs(3), false));
    }
}
