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

@JvmInline
value class CompletionEpoch(val value: String) {
    init {
        require(value.isNotBlank()) { "Completion Epoch must not be blank" }
    }
}

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

data class OccurrenceSubtaskState(
    val identifier: SubtaskIdentifier,
    val isChecked: Boolean,
)

data class OccurrenceSnapshot(
    val seriesRevision: SeriesRevision,
    val nominalDate: LocalDate,
    val effectiveDate: LocalDate,
)

data class CompletionRecord(
    val epoch: CompletionEpoch,
    val snapshot: OccurrenceSnapshot,
    val completedAt: MemberActionMoment,
    val subtasks: List<OccurrenceSubtaskState>,
) {
    val seriesRevision: SeriesRevision
        get() = snapshot.seriesRevision

    val nominalDate: LocalDate
        get() = snapshot.nominalDate

    val effectiveDate: LocalDate
        get() = snapshot.effectiveDate

    init {
        require(subtasks.map(OccurrenceSubtaskState::identifier).toSet().size == subtasks.size) {
            "Completion Record Subtask identifiers must be unique"
        }
        require(
            subtasks.map(OccurrenceSubtaskState::identifier).toSet() ==
                seriesRevision.subtaskTemplate.map(SubtaskTemplate::identifier).toSet(),
        ) {
            "Completion Record Subtasks must match its historical Series Revision"
        }
    }
}

sealed interface OccurrenceOutcome {
    data class Complete(
        val record: CompletionRecord,
    ) : OccurrenceOutcome
}

/**
 * One whole-document, last-write-wins record for an Occurrence the Owner has interacted with.
 * Expected Occurrences with no interaction have no persisted state.
 */
data class OccurrenceState(
    val key: OccurrenceKey,
    val snapshot: OccurrenceSnapshot,
    val subtasks: List<OccurrenceSubtaskState>,
    val outcome: OccurrenceOutcome?,
    val actionableSubtasksBeforeCompletion: List<OccurrenceSubtaskState>? = null,
    val invalidatedCompletionEpochs: Set<CompletionEpoch> = emptySet(),
) {
    init {
        require(snapshot.seriesRevision.taskIdentifier == key.taskIdentifier) {
            "Occurrence snapshot belongs to another Task"
        }
        require(snapshot.nominalDate == key.nominalDate) {
            "Occurrence snapshot belongs to another Occurrence"
        }
        require(
            subtasks.map(OccurrenceSubtaskState::identifier).toSet() ==
                snapshot.seriesRevision.subtaskTemplate.map(SubtaskTemplate::identifier).toSet(),
        ) {
            "Occurrence State Subtasks must match its historical definition"
        }
        require(subtasks.map(OccurrenceSubtaskState::identifier).toSet().size == subtasks.size) {
            "Occurrence State Subtask identifiers must be unique"
        }
        (outcome as? OccurrenceOutcome.Complete)?.record?.let { completion ->
            require(completion.snapshot == snapshot) {
                "Completion Record must use the Occurrence snapshot"
            }
            require(completion.subtasks == subtasks) {
                "Completed Occurrence State must match its Completion Record Subtasks"
            }
            require(actionableSubtasksBeforeCompletion != null) {
                "Completed Occurrence State must retain its actionable state for immediate Undo"
            }
            require(completion.epoch !in invalidatedCompletionEpochs) {
                "The current Completion Epoch must not already be invalidated"
            }
        }
        require(outcome is OccurrenceOutcome.Complete || actionableSubtasksBeforeCompletion == null) {
            "Only a completed Occurrence State may retain immediate Undo state"
        }
        actionableSubtasksBeforeCompletion?.let { actionableSubtasks ->
            require(
                actionableSubtasks.map(OccurrenceSubtaskState::identifier).toSet() ==
                    snapshot.seriesRevision.subtaskTemplate
                        .map(SubtaskTemplate::identifier)
                        .toSet(),
            ) {
                "Immediate Undo Subtasks must match the historical definition"
            }
            require(
                actionableSubtasks.map(OccurrenceSubtaskState::identifier).toSet().size ==
                    actionableSubtasks.size,
            ) {
                "Immediate Undo Subtask identifiers must be unique"
            }
        }
    }
}
