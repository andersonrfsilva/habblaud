use crate::{AppEvents, AppWindow, ConversationState, avatars, list, state::Event};
use slint::ComponentHandle;
use zaplaud_core::model::SessionItem;

pub fn setup(window: &AppWindow, context: &crate::feature::FeatureContext) {
    let sender = &context.commands;
    let sender = sender.clone();
    window.global::<ConversationState>().on_close(move || {
        let _ = sender.send(Event::Close);
    });
}
pub fn publish(window: &AppWindow, selected: Option<SessionItem>) {
    let conversation = window.global::<ConversationState>();
    let previous = conversation.get_key();
    let key = selected
        .as_ref()
        .and_then(|s| s.key.serialize().ok())
        .unwrap_or_default();
    conversation.set_selected(selected.is_some());
    conversation.set_key(key.clone().into());
    if let Some(item) = selected {
        conversation.set_subtitle(list::subtitle(&item, false).into());
        conversation.set_initials(
            item.character_name
                .as_deref()
                .unwrap_or(&item.title)
                .chars()
                .next()
                .map(|c| c.to_uppercase().collect::<String>())
                .unwrap_or_default()
                .into(),
        );
        conversation.set_avatar(
            item.avatar
                .as_ref()
                .map(|a| avatars::get(&avatars::key(&item.key.origin, a)))
                .unwrap_or_default(),
        );
        conversation.set_title(item.title.into());
    }
    if previous.as_str() != key {
        window
            .global::<AppEvents>()
            .invoke_selection_changed(key.into());
    }
}
