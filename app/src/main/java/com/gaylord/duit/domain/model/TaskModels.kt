package com.gaylord.duit.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@JvmInline
value class MemberIdentifier(val value: String)

@JvmInline
value class TaskIdentifier(val value: String)

@JvmInline
value class TaskRevisionIdentifier(val value: String)

sealed interface TaskVisibility {
    data object Private : TaskVisibility
}

data class MemberActionMoment(
    val instant: Instant,
    val localDate: LocalDate,
    val zoneId: ZoneId,
)

data class StandaloneTask(
    val identifier: TaskIdentifier,
    val ownerIdentifier: MemberIdentifier,
    val activeRevisionIdentifier: TaskRevisionIdentifier,
    val visibility: TaskVisibility,
    val createdAt: MemberActionMoment,
)

data class TaskRevision(
    val identifier: TaskRevisionIdentifier,
    val taskIdentifier: TaskIdentifier,
    val title: String,
    val plannedDate: LocalDate,
    val effectiveDate: LocalDate,
    val recordedAt: MemberActionMoment,
)

data class TaskReminderConfiguration(
    val taskIdentifier: TaskIdentifier,
    val date: LocalDate,
    val time: LocalTime,
    val zoneId: ZoneId,
)

data class StandaloneTaskCreation(
    val task: StandaloneTask,
    val initialRevision: TaskRevision,
    val reminder: TaskReminderConfiguration?,
) {
    init {
        require(task.identifier == initialRevision.taskIdentifier)
        require(task.activeRevisionIdentifier == initialRevision.identifier)
        require(reminder == null || reminder.taskIdentifier == task.identifier)
    }
}
