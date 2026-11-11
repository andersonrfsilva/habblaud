//! Estado transitório das ações do elenco; nenhum POST é repetido automaticamente.
use crate::{
    Error,
    api::{HabblaudApi, ResumeSession},
    model::{CastChoice, CastJobs, SessionItem},
};
use std::collections::HashSet;

#[derive(Clone, Debug, Default)]
pub struct State {
    pub availability: String,
    pub runtime: Option<String>,
    pub characters: Vec<CastChoice>,
    pub jobs: CastJobs,
    pub result: String,
    pub submitted_cast: Option<String>,
    pub sending: bool,
}
impl State {
    pub async fn refresh(&mut self, api: &HabblaudApi, load_cast: bool) -> Result<(), Error> {
        match api.cast_jobs().await {
            Ok(jobs) => {
                self.availability = if jobs.available {
                    "available"
                } else {
                    "unavailable"
                }
                .into();
                if self.result == "pending"
                    && let Some(status) = self
                        .submitted_cast
                        .as_ref()
                        .and_then(|id| jobs.jobs.get(id))
                    && matches!(status.as_str(), "done" | "failed")
                {
                    self.result = status.clone();
                }
                self.jobs = jobs;
                if self.jobs.available && load_cast {
                    self.load(api).await?;
                }
                Ok(())
            }
            Err(error) => {
                self.runtime = None;
                self.availability = if matches!(error, Error::Api { status: 403, .. }) {
                    "forbidden"
                } else {
                    "offline"
                }
                .into();
                Err(error)
            }
        }
    }
    pub async fn load(&mut self, api: &HabblaudApi) -> Result<(), Error> {
        self.runtime = None;
        self.runtime = api.cast_runtime().await?;
        self.characters = api
            .cast()
            .await?
            .into_iter()
            .map(|member| member.choice)
            .collect();
        Ok(())
    }
    pub fn active_job(&self, id: &str) -> bool {
        self.jobs
            .jobs
            .get(id)
            .is_some_and(|s| matches!(s.as_str(), "queued" | "sent" | "waiting"))
    }
    pub fn free(
        &self,
        sessions: &[SessionItem],
        selected: Option<&SessionItem>,
    ) -> Vec<CastChoice> {
        let open: HashSet<&str> = sessions
            .iter()
            .filter(|s| s.agent_id.is_some())
            .filter_map(|s| s.cast.as_deref())
            .collect();
        let mut choices: Vec<_> = self
            .characters
            .iter()
            .filter(|c| !open.contains(c.id.as_str()) && !self.active_job(&c.id))
            .cloned()
            .collect();
        if let Some(selected) = selected {
            choices.sort_by_key(|c| selected.cast.as_deref() != Some(c.id.as_str()));
        }
        choices
    }
    pub fn can_resume(&self, selected: &SessionItem) -> bool {
        crate::rules::composer::can_resume_cast(selected, self.runtime.as_deref())
    }
    pub fn can_dismiss(&self, selected: &SessionItem) -> bool {
        selected.agent_id.is_some()
            && selected.cast_opened
            && selected.cast.as_ref().is_some_and(|id| {
                id.starts_with("novo-") || self.characters.iter().any(|c| c.id == *id)
            })
    }
    pub async fn submit(
        &mut self,
        api: &HabblaudApi,
        choice: &str,
        resume: Option<&SessionItem>,
        dismiss: Option<&str>,
    ) -> Result<(), Error> {
        if resume.is_some_and(|session| !self.can_resume(session)) {
            return Err(Error::InvalidResponse("runtime da sessão"));
        }
        if self.sending {
            return Ok(());
        }
        self.sending = true;
        self.result.clear();
        let resume = resume.map(|s| ResumeSession {
            room: s.project.as_deref().unwrap_or_default(),
            session: &s.key.session_id,
            account: &s.key.account,
        });
        let result = if let Some(agent) = dismiss {
            api.dismiss_agent(agent).await
        } else if choice.is_empty() {
            api.new_cast(resume).await
        } else {
            api.start_cast(choice, resume).await
        };
        self.sending = false;
        match result {
            Ok(submission) => {
                self.submitted_cast = submission.cast;
                self.result = "pending".into();
                Ok(())
            }
            Err(error) => {
                self.result = match &error {
                    Error::Api { status: 409, .. } => "busy",
                    Error::Api { status: 403, .. } => "forbidden",
                    Error::Api {
                        status: 400..=499, ..
                    }
                    | Error::Unauthorized => "failed",
                    _ => "unknown",
                }
                .into();
                Err(error)
            }
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn resume_requires_matching_runtime_absolute_project_and_valid_id() {
        let mut item = SessionItem::default();
        item.key.provider = "omp".into();
        item.key.session_id = "abcde123-1234".into();
        item.project = Some("/project".into());
        let state = State {
            runtime: Some("omp".into()),
            ..State::default()
        };
        assert!(state.can_resume(&item));
        item.agent_id = Some("live".into());
        assert!(!state.can_resume(&item));
        item.agent_id = None;
        item.key.session_id = "invalid-session".into();
        assert!(!state.can_resume(&item));
    }
    #[test]
    fn choices_exclude_open_jobs_and_prioritize_owner() {
        let member = |id: &str| CastChoice {
            id: id.into(),
            name: id.into(),
            avatar: None,
        };
        let mut state = State {
            characters: vec![
                member("open"),
                member("busy"),
                member("free"),
                member("owner"),
            ],
            ..State::default()
        };
        state.jobs.jobs.insert("busy".into(), "waiting".into());
        let open = SessionItem {
            agent_id: Some("agent".into()),
            cast: Some("open".into()),
            ..SessionItem::default()
        };
        let selected = SessionItem {
            cast: Some("owner".into()),
            project: Some("/project".into()),
            ..SessionItem::default()
        };
        let ids: Vec<_> = state
            .free(&[open], Some(&selected))
            .into_iter()
            .map(|choice| choice.id)
            .collect();
        assert_eq!(ids, ["owner", "free"]);
    }
}
