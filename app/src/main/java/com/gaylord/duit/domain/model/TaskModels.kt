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

@JvmInline
value class SeriesRevisionIdentifier(val value: String)

@JvmInline
value class SubtaskIdentifier(val value: String)

data class OccurrenceKey(
    val taskIdentifier: TaskIdentifier,
    val nominalDate: LocalDate,
)

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

data class RecurringSeries(
    val identifier: TaskIdentifier,
    val ownerIdentifier: MemberIdentifier,
    val activeRevisionIdentifier: SeriesRevisionIdentifier,
    val visibility: TaskVisibility,
    val createdAt: MemberActionMoment,
)

sealed interface RecurrenceRule {
    data class Daily(
        val everyDays: Int = 1,
    ) : RecurrenceRule {
        init {
            require(everyDays > 0) { "Daily recurrence interval must be positive" }
        }
    }
}

data class SubtaskTemplate(
    val identifier: SubtaskIdentifier,
    val label: String,
) {
    init {
        require(label.isNotBlank()) { "Subtask label must not be blank" }
    }
}

data class SeriesRevision(
    val identifier: SeriesRevisionIdentifier,
    val taskIdentifier: TaskIdentifier,
    val title: String,
    val subtaskTemplate: List<SubtaskTemplate>,
    val recurrenceRule: RecurrenceRule,
    val startsOn: LocalDate,
    val endsOnInclusive: LocalDate?,
    val recordedAt: MemberActionMoment,
) {
    init {
        require(title.isNotBlank()) { "Recurring Series title must not be blank" }
        require(endsOnInclusive == null || !endsOnInclusive.isBefore(startsOn)) {
            "Recurring Series end date must not precede its start date"
        }
    }
}

data class RecurringSeriesCreation(
    val series: RecurringSeries,
    val initialRevision: SeriesRevision,
) {
    init {
        require(series.identifier == initialRevision.taskIdentifier)
        require(series.activeRevisionIdentifier == initialRevision.identifier)
    }
}
