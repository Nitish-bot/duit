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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
class DuitWorkspace private constructor(
    private val clock: Clock,
    private val zoneProvider: ZoneProvider,
    private val taskIdentities: TaskIdentityGenerator,
    private val persistence: TaskRepository?,
    private val reminders: ReminderGateway?,
    private val accessController: WorkspaceAccessController,
) {
    constructor(
        memberIdentifier: MemberIdentifier,
        clock: Clock,
        zoneProvider: ZoneProvider,
        taskIdentities: TaskIdentityGenerator,
        persistence: TaskRepository,
        reminders: ReminderGateway,
    ) : this(
        clock = clock,
        zoneProvider = zoneProvider,
        taskIdentities = taskIdentities,
        persistence = persistence,
        reminders = reminders,
        accessController = ReadyWorkspaceAccessController(memberIdentifier),
    )

    val accessState: StateFlow<WorkspaceAccessState> = accessController.state

    suspend fun restoreSession() {
        accessController.restoreSession()
    }

    suspend fun retryAccess() {
        accessController.retryAccess()
    }

    suspend fun signOut() {
        accessController.signOut()
    }

    fun observePersonalAgenda(): Flow<PersonalAgenda> =
        accessState.flatMapLatest { state ->
            val date = currentMemberMoment().localDate
            val memberIdentifier = (state as? WorkspaceAccessState.Ready)?.memberIdentifier

            if (memberIdentifier == null) {
                return@flatMapLatest flowOf(PersonalAgenda(date = date, tasks = emptyList()))
            }

            requireTaskPersistence()
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
        accessState.flatMapLatest { state ->
            val memberIdentifier = (state as? WorkspaceAccessState.Ready)?.memberIdentifier
                ?: return@flatMapLatest flowOf(null)

            requireTaskPersistence()
                .observeStandaloneTask(
                    ownerIdentifier = memberIdentifier,
                    taskIdentifier = taskIdentifier,
                ).map { creation -> creation?.toTaskDetail() }
        }

    suspend fun execute(command: MemberCommand): TaskIdentifier {
        val memberIdentifier = requireReadyMemberIdentifier()

        return when (command) {
            is MemberCommand.AddPrivateTaskToToday ->
                addPrivateTaskToToday(
                    memberIdentifier = memberIdentifier,
                    command = command,
                )
        }
    }

    private suspend fun addPrivateTaskToToday(
        memberIdentifier: MemberIdentifier,
        command: MemberCommand.AddPrivateTaskToToday,
    ): TaskIdentifier {
        val title = command.title.trim()
        require(title.isNotBlank()) { "Task title must not be blank" }
        val moment = currentMemberMoment()
        val persistence = requireTaskPersistence()
        val reminderGateway =
            command.reminderAt?.let {
                reminders
                    ?: throw WorkspaceCapabilityUnavailableException(
                        WorkspaceCapability.ReminderScheduling,
                    )
            }
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
                        title = title,
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

        durableCreation.reminder?.let { reminderGateway?.reconcile(it.toScheduledReminder()) }

        return durableCreation.task.identifier
    }

    private fun requireReadyMemberIdentifier(): MemberIdentifier {
        val state = accessState.value
        return (state as? WorkspaceAccessState.Ready)?.memberIdentifier
            ?: throw WorkspaceLockedException(state)
    }

    private fun requireTaskPersistence(): TaskRepository =
        persistence
            ?: throw WorkspaceCapabilityUnavailableException(
                WorkspaceCapability.TaskPersistence,
            )

    private fun currentMemberMoment(): MemberActionMoment {
        val instant = clock.instant()
        val zoneId = zoneProvider.currentZone()

        return MemberActionMoment(
            instant = instant,
            localDate = instant.atZone(zoneId).toLocalDate(),
            zoneId = zoneId,
        )
    }

    companion object {
        internal fun authenticated(
            clock: Clock,
            zoneProvider: ZoneProvider,
            taskIdentities: TaskIdentityGenerator,
            persistence: TaskRepository?,
            reminders: ReminderGateway?,
            authentication: AuthenticationGateway,
            approval: MemberApprovalGateway,
            hydration: OwnerWorkspaceHydrationGateway,
            persistedSession: PersistedWorkspaceSessionGateway,
            sessionOperations: WorkspaceSessionOperationCoordinator,
        ): DuitWorkspace =
            DuitWorkspace(
                clock = clock,
                zoneProvider = zoneProvider,
                taskIdentities = taskIdentities,
                persistence = persistence,
                reminders = reminders,
                accessController =
                    AuthenticatedWorkspaceAccessController(
                        authentication = authentication,
                        approval = approval,
                        hydration = hydration,
                        persistedSession = persistedSession,
                        sessionOperations = sessionOperations,
                    ),
            )
    }
}

enum class WorkspaceCapability {
    TaskPersistence,
    ReminderScheduling,
}

class WorkspaceCapabilityUnavailableException(
    val capability: WorkspaceCapability,
) : IllegalStateException("Workspace capability is unavailable: $capability")

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
