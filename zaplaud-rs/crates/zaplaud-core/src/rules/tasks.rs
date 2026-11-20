use crate::model::{AgentTask, TaskStatus};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TaskProgress<'a> {
    pub done: usize,
    pub total: usize,
    pub current: Option<&'a str>,
}

pub fn task_progress(tasks: &[AgentTask]) -> Option<TaskProgress<'_>> {
    let mut progress = TaskProgress {
        done: 0,
        total: 0,
        current: None,
    };
    for task in tasks {
        match task.status {
            TaskStatus::Abandoned => continue,
            TaskStatus::Completed => progress.done += 1,
            TaskStatus::InProgress if progress.current.is_none() => {
                progress.current = Some(task.display_title());
            }
            _ => {}
        }
        progress.total += 1;
    }
    (progress.total > 0).then_some(progress)
}

pub fn task_phases(tasks: &[AgentTask]) -> impl Iterator<Item = (Option<&str>, &[AgentTask])> {
    tasks
        .chunk_by(|a, b| a.phase == b.phase)
        .map(|group| (group[0].phase.as_deref(), group))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::TaskStatus::*;

    fn task(title: &str, status: TaskStatus, active_form: Option<&str>) -> AgentTask {
        AgentTask {
            id: title.into(),
            title: title.into(),
            status,
            active_form: active_form.map(str::to_owned),
            phase: None,
        }
    }

    #[test]
    fn excludes_abandoned_and_uses_first_active_task() {
        let tasks = [
            task("Feita", Completed, None),
            task("Abandonada", Abandoned, None),
            task("Primeira", InProgress, Some("Fazendo a primeira")),
            task("Segunda", InProgress, None),
            task("Pendente", Pending, None),
        ];
        assert_eq!(
            task_progress(&tasks),
            Some(TaskProgress {
                done: 1,
                total: 4,
                current: Some("Fazendo a primeira"),
            })
        );
        assert_eq!(task_progress(&tasks[3..4]).unwrap().current, Some("Segunda"));
    }

    #[test]
    fn counts_tasks_without_an_active_one() {
        assert_eq!(
            task_progress(&[task("Feita", Completed, None)]),
            Some(TaskProgress {
                done: 1,
                total: 1,
                current: None,
            })
        );
    }

    #[test]
    fn no_progress_when_nothing_counts() {
        assert_eq!(task_progress(&[]), None);
        assert_eq!(task_progress(&[task("Abandonada", Abandoned, None)]), None);
    }

    #[test]
    fn groups_adjacent_phases_without_reordering() {
        let tasks = [Some("Z"), Some("Z"), None, Some("A"), Some("Z")].map(|phase| AgentTask {
            phase: phase.map(str::to_owned),
            ..task("Tarefa", Pending, None)
        });
        let mut phases = task_phases(&tasks);
        assert_eq!(phases.next(), Some((Some("Z"), &tasks[..2])));
        assert_eq!(phases.next(), Some((None, &tasks[2..3])));
        assert_eq!(phases.next(), Some((Some("A"), &tasks[3..4])));
        assert_eq!(phases.next(), Some((Some("Z"), &tasks[4..])));
        assert_eq!(phases.next(), None);
    }

    #[test]
    fn no_phase_is_one_unnamed_group() {
        let tasks = [task("Tarefa", Pending, None), task("Outra", Completed, None)];
        let mut phases = task_phases(&tasks);
        assert_eq!(phases.next(), Some((None, tasks.as_slice())));
        assert_eq!(phases.next(), None);
        assert_eq!(task_phases(&[]).next(), None);
    }
}
