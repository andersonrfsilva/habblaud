use chrono::{DateTime, Local, TimeZone};

pub fn entry_time(at: i64, now: i64) -> Option<String> {
    platform_entry_time(at, now)
}
pub fn platform_entry_time(at: i64, now: i64) -> Option<String> {
    entry_time_in(at, now, &Local)
}
pub fn entry_time_in<T: TimeZone>(at: i64, now: i64, zone: &T) -> Option<String>
where
    T::Offset: std::fmt::Display,
{
    let at = zone.timestamp_millis_opt(at).single()?;
    let now = zone.timestamp_millis_opt(now).single()?;
    Some(
        at.format(if at.date_naive() == now.date_naive() {
            "%H:%M"
        } else {
            "%d/%m %H:%M"
        })
        .to_string(),
    )
}
pub fn platform_deadline(at: i64) -> Option<String> {
    deadline_in(at, &Local)
}
pub fn deadline_in<T: TimeZone>(at: i64, zone: &T) -> Option<String>
where
    T::Offset: std::fmt::Display,
{
    // Convenção SHORT pt-BR conferida contra DateFormat do JVM.
    Some(
        zone.timestamp_millis_opt(at)
            .single()?
            .format("%d/%m/%Y %H:%M")
            .to_string(),
    )
}
pub fn retry_after_deadline(value: Option<&str>, now: i64) -> i64 {
    if let Some(seconds) = value.and_then(|value| value.parse::<i64>().ok()) {
        let millis = seconds.clamp(0, i64::MAX / 1000) * 1000;
        return now.wrapping_add(millis.min(i64::MAX.wrapping_sub(now)));
    }
    value
        .and_then(|value| DateTime::parse_from_rfc2822(value).ok())
        .map_or_else(
            || now.wrapping_add(60_000),
            |date| date.timestamp_millis().max(now),
        )
}
pub fn retry_after_millis(value: Option<&str>, now: i64) -> i64 {
    if let Some(seconds) = value.and_then(|value| value.parse::<i64>().ok()) {
        return seconds.clamp(0, 86_400) * 1000;
    }
    value
        .and_then(|value| DateTime::parse_from_rfc2822(value).ok())
        .map_or(0, |date| {
            date.timestamp_millis()
                .wrapping_sub(now)
                .clamp(0, 86_400_000)
        })
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RelativeTime {
    pub key: &'static str,
    pub amount: Option<i64>,
}
pub fn relative_time(timestamp: i64, now: i64) -> RelativeTime {
    let minutes = now.wrapping_sub(timestamp).max(0) / 60_000;
    let (key, amount) = match minutes {
        0 => ("time_now", None),
        1..60 => ("time_minutes", Some(minutes)),
        60..1440 => ("time_hours", Some(minutes / 60)),
        _ => ("time_days", Some(minutes / 1440)),
    };
    RelativeTime { key, amount }
}
pub fn local_time(server: i64, server_now: i64, now: i64) -> i64 {
    crate::model::local_time(server, server_now, now)
}
pub fn status_line_text(activity: Option<&str>) -> Option<String> {
    let value = super::kotlin_trim(activity?);
    let trimmed = if let Some(value) = value.strip_suffix('…') {
        value
    } else {
        let dots = value.chars().rev().take_while(|c| *c == '.').count().min(3);
        &value[..value.len() - dots]
    }
    .trim_matches(super::kotlin_whitespace);
    (!trimmed.is_empty()).then(|| trimmed.to_owned())
}
pub fn stopwatch(ms: i64) -> String {
    let total = ms.max(0) / 1000;
    let (hours, minutes, seconds) = (total / 3600, total % 3600 / 60, total % 60);
    if hours > 0 {
        format!("{hours}:{minutes:02}:{seconds:02}")
    } else {
        format!("{minutes}:{seconds:02}")
    }
}
