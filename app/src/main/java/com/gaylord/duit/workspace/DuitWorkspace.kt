package com.gaylord.duit.workspace

import com.gaylord.duit.data.repository.TaskRepository
import com.gaylord.duit.domain.model.MemberActionMoment
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.StandaloneTask
import com.gaylord.duit.domain.model.StandaloneTaskCreation
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.model.TaskReminderConfiguration
import com.gaylord.duit.domain.model.TaskRevision
import com.gaylord.duit.domain.model.TaskRevisionIdentifier
import com.gaylord.duit.domain.model.TaskVisibility
import com.gaylord.duit.notifications.ReminderGateway
import com.gaylord.duit.notifications.ScheduledTaskReminder
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DuitWorkspace(
    private val memberIdentifier: MemberIdentifier,
    private val clock: Clock,
    private val zoneProvider: ZoneProvider,
    private val taskIdentities: TaskIdentityGenerator,
    private val persistence: TaskRepository,
    private val reminders: ReminderGateway,
) {
    fun observePersonalAgenda(): Flow<PersonalAgenda> {
        val date = currentMemberMoment().localDate

        return persistence
            .observeStandaloneTasks(
                ownerIdentifier = memberIdentifier,
                plannedDate = date,
            ).map { creations ->
                PersonalAgenda(
                    date = date,
                    tasks = creations.map(StandaloneTaskCreation::toAgendaTask),
                )
            }
    }

    fun observeTaskDetail(taskIdentifier: TaskIdentifier): Flow<TaskDetail?> =
        persistence
            .observeStandaloneTask(
                ownerIdentifier = memberIdentifier,
                taskIdentifier = taskIdentifier,
            ).map { creation -> creation?.toTaskDetail() }

    suspend fun execute(command: MemberCommand): TaskIdentifier =
        when (command) {
            is MemberCommand.AddPrivateTaskToToday -> addPrivateTaskToToday(command)
        }

    private suspend fun addPrivateTaskToToday(
        command: MemberCommand.AddPrivateTaskToToday,
    ): TaskIdentifier {
        val moment = currentMemberMoment()
        val identifiers = taskIdentities.identifiersFor(command.commandIdentifier)
        val creation =
            StandaloneTaskCreation(
                task =
                    StandaloneTask(
                        identifier = identifiers.taskIdentifier,
                        ownerIdentifier = memberIdentifier,
                        activeRevisionIdentifier = identifiers.revisionIdentifier,
                        visibility = TaskVisibility.Private,
                        createdAt = moment,
                    ),
                initialRevision =
                    TaskRevision(
                        identifier = identifiers.revisionIdentifier,
                        taskIdentifier = identifiers.taskIdentifier,
                        title = command.title,
                        plannedDate = moment.localDate,
                        effectiveDate = moment.localDate,
                        recordedAt = moment,
                    ),
                reminder =
                    command.reminderAt?.let { time ->
                        TaskReminderConfiguration(
                            taskIdentifier = identifiers.taskIdentifier,
                            date = moment.localDate,
                            time = time,
                            zoneId = moment.zoneId,
                        )
                    },
            )
        val durableCreation = persistence.putStandaloneTaskIfAbsent(creation)

        durableCreation.reminder?.let { reminders.reconcile(it.toScheduledReminder()) }

        return durableCreation.task.identifier
    }

    private fun currentMemberMoment(): MemberActionMoment {
        val instant = clock.instant()
        val zoneId = zoneProvider.currentZone()

        return MemberActionMoment(
            instant = instant,
            localDate = instant.atZone(zoneId).toLocalDate(),
            zoneId = zoneId,
        )
    }
}

@JvmInline
value class CommandIdentifier(val value: String)

sealed interface MemberCommand {
    data class AddPrivateTaskToToday(
        val commandIdentifier: CommandIdentifier,
        val title: String,
        val reminderAt: LocalTime? = null,
    ) : MemberCommand
}

data class InitialTaskIdentifiers(
    val taskIdentifier: TaskIdentifier,
    val revisionIdentifier: TaskRevisionIdentifier,
)

fun interface TaskIdentityGenerator {
    fun identifiersFor(commandIdentifier: CommandIdentifier): InitialTaskIdentifiers
}

data class PersonalAgenda(
    val date: LocalDate,
    val tasks: List<AgendaTask>,
)

data class AgendaTask(
    val identifier: TaskIdentifier,
    val revisionIdentifier: TaskRevisionIdentifier,
    val title: String,
    val plannedDate: LocalDate,
    val visibility: TaskVisibility,
    val reminderAt: LocalTime?,
)

data class TaskDetail(
    val identifier: TaskIdentifier,
    val ownerIdentifier: MemberIdentifier,
    val visibility: TaskVisibility,
    val createdAt: MemberActionMoment,
    val initialRevision: TaskRevisionDetail,
    val reminderAt: LocalTime?,
)

data class TaskRevisionDetail(
    val identifier: TaskRevisionIdentifier,
    val title: String,
    val plannedDate: LocalDate,
    val effectiveDate: LocalDate,
    val recordedAt: MemberActionMoment,
)

fun interface ZoneProvider {
    fun currentZone(): ZoneId
}

private fun StandaloneTaskCreation.toAgendaTask(): AgendaTask =
    AgendaTask(
        identifier = task.identifier,
        revisionIdentifier = initialRevision.identifier,
        title = initialRevision.title,
        plannedDate = initialRevision.plannedDate,
        visibility = task.visibility,
        reminderAt = reminder?.time,
    )

private fun StandaloneTaskCreation.toTaskDetail(): TaskDetail =
    TaskDetail(
        identifier = task.identifier,
        ownerIdentifier = task.ownerIdentifier,
        visibility = task.visibility,
        createdAt = task.createdAt,
        initialRevision =
            TaskRevisionDetail(
                identifier = initialRevision.identifier,
                title = initialRevision.title,
                plannedDate = initialRevision.plannedDate,
                effectiveDate = initialRevision.effectiveDate,
                recordedAt = initialRevision.recordedAt,
            ),
        reminderAt = reminder?.time,
    )

private fun TaskReminderConfiguration.toScheduledReminder(): ScheduledTaskReminder =
    ScheduledTaskReminder(
        taskIdentifier = taskIdentifier,
        date = date,
        time = time,
        zoneId = zoneId,
        triggerAt = LocalDateTime.of(date, time).atZone(zoneId).toInstant(),
    )
