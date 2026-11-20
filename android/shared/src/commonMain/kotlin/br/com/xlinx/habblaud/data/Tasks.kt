package br.com.xlinx.habblaud.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class TaskProgress(val done: Int, val total: Int, val current: String?)
internal data class TaskPhase(val name: String?, val tasks: List<AgentTask>)

internal fun parseAgentTasks(value: JsonElement?): List<AgentTask> = (value as? JsonArray)?.mapNotNull { entry ->
    val task = entry as? JsonObject ?: return@mapNotNull null
    val id = task.taskString("id") ?: return@mapNotNull null
    val title = task.taskString("title") ?: return@mapNotNull null
    val status = when (task.taskString("status")) {
        "pending" -> TaskStatus.PENDING
        "in_progress" -> TaskStatus.IN_PROGRESS
        "completed" -> TaskStatus.COMPLETED
        "abandoned" -> TaskStatus.ABANDONED
        else -> return@mapNotNull null
    }
    AgentTask(id, title, status, task.taskString("activeForm"), task.taskString("phase"))
}.orEmpty()

private fun JsonObject.taskString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun taskProgress(tasks: List<AgentTask>): TaskProgress? {
    var done = 0
    var total = 0
    var current: String? = null
    for (task in tasks) {
        if (task.status == TaskStatus.ABANDONED) continue
        total++
        if (task.status == TaskStatus.COMPLETED) done++
        if (task.status == TaskStatus.IN_PROGRESS && current == null) current = task.displayTitle
    }
    return if (total == 0) null else TaskProgress(done, total, current)
}

internal fun taskPhases(tasks: List<AgentTask>): List<TaskPhase> = buildList {
    var start = 0
    while (start < tasks.size) {
        val name = tasks[start].phase
        var end = start + 1
        while (end < tasks.size && tasks[end].phase == name) end++
        add(TaskPhase(name, tasks.subList(start, end)))
        start = end
    }
}
