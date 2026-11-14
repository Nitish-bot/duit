package com.gaylord.duit.workspace

import com.gaylord.duit.data.repository.TaskRepository
import com.gaylord.duit.domain.model.CompletionEpoch
import com.gaylord.duit.domain.model.CompletionRecord
import com.gaylord.duit.domain.model.MemberActionMoment
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.OccurrenceKey
import com.gaylord.duit.domain.model.OccurrenceOutcome
import com.gaylord.duit.domain.model.OccurrenceSnapshot
import com.gaylord.duit.domain.model.OccurrenceState
import com.gaylord.duit.domain.model.OccurrenceSubtaskState
import com.gaylord.duit.domain.model.RecurrenceRule
import com.gaylord.duit.domain.model.RecurringSeries
import com.gaylord.duit.domain.model.RecurringSeriesCreation
import com.gaylord.duit.domain.model.SeriesRevision
import com.gaylord.duit.domain.model.SeriesRevisionIdentifier
import com.gaylord.duit.domain.model.StandaloneTask
import com.gaylord.duit.domain.model.StandaloneTaskCreation
import com.gaylord.duit.domain.model.SubtaskIdentifier
import com.gaylord.duit.domain.model.SubtaskTemplate
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.model.TaskReminderConfiguration
import com.gaylord.duit.domain.model.TaskRevision
import com.gaylord.duit.domain.model.TaskRevisionIdentifier
import com.gaylord.duit.domain.model.TaskVisibility
import com.gaylord.duit.domain.recurrence.RecurrenceEngine
import com.gaylord.duit.notifications.ReminderGateway
import com.gaylord.duit.notifications.ScheduledTaskReminder
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive

@OptIn(ExperimentalCoroutinesApi::class)
class DuitWorkspace private constructor(
    private val clock: Clock,
    private val zoneProvider: ZoneProvider,
    private val taskIdentities: TaskIdentityGenerator,
    private val persistence: TaskRepository?,
    private val reminders: ReminderGateway?,
    private val accessController: WorkspaceAccessController,
    private val memberDates: Flow<LocalDate>,
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
        memberDates = observeMemberDates(clock, zoneProvider),
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
        combine(
            accessState,
            memberDates.distinctUntilChanged(),
        ) { state, date -> WorkspaceDate(state, date) }
            .flatMapLatest { (state, date) ->
                val memberIdentifier = (state as? WorkspaceAccessState.Ready)?.memberIdentifier

                if (memberIdentifier == null) {
                    return@flatMapLatest flowOf(PersonalAgenda(date = date, tasks = emptyList()))
                }

                val persistence = requireTaskPersistence()
                val recurringAgendaTasks =
                    persistence.observeRecurringAgendaTasks(
                        ownerIdentifier = memberIdentifier,
                        date = date,
                    )
                combine(
                    persistence.observeStandaloneTasks(
                        ownerIdentifier = memberIdentifier,
                        plannedDate = date,
                    ),
                    recurringAgendaTasks,
                ) { standaloneCreations, recurringTasks ->
                    val (doneToday, activeRecurringTasks) =
                        recurringTasks.partition { task ->
                            task.occurrence?.outcome is OccurrenceOutcome.Complete
                        }
                    PersonalAgenda(
                        date = date,
                        tasks =
                            standaloneCreations.map(StandaloneTaskCreation::toAgendaTask) +
                                activeRecurringTasks,
                        doneToday = doneToday,
                    )
                }
            }

    fun observePersonalHistory(): Flow<PersonalHistory> =
        accessState.flatMapLatest { state ->
            val memberIdentifier = (state as? WorkspaceAccessState.Ready)?.memberIdentifier
                ?: return@flatMapLatest flowOf(PersonalHistory())

            requireTaskPersistence().observeCompletedOccurrenceHistory(memberIdentifier)
        }

    fun observePersonalHistory(taskIdentifier: TaskIdentifier): Flow<PersonalHistory> =
        accessState.flatMapLatest { state ->
            val memberIdentifier = (state as? WorkspaceAccessState.Ready)?.memberIdentifier
                ?: return@flatMapLatest flowOf(PersonalHistory())

            requireTaskPersistence().observeCompletedOccurrenceHistory(
                ownerIdentifier = memberIdentifier,
                taskIdentifier = taskIdentifier,
            )
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

    fun observeRecurringSeriesDetail(
        taskIdentifier: TaskIdentifier,
    ): Flow<RecurringSeriesDetail?> =
        accessState.flatMapLatest { state ->
            val memberIdentifier = (state as? WorkspaceAccessState.Ready)?.memberIdentifier
                ?: return@flatMapLatest flowOf(null)

            requireTaskPersistence()
                .observeRecurringSeries(
                    ownerIdentifier = memberIdentifier,
                    taskIdentifier = taskIdentifier,
                ).map { creation -> creation?.toRecurringSeriesDetail() }
        }

    fun observeRecurringTaskLibrary(): Flow<List<RecurringSeriesLibraryEntry>> =
        accessState.flatMapLatest { state ->
            val memberIdentifier = (state as? WorkspaceAccessState.Ready)?.memberIdentifier
                ?: return@flatMapLatest flowOf(emptyList())

            requireTaskPersistence().observeRecurringSeries(memberIdentifier).map { creations ->
                creations.map { creation ->
                    RecurringSeriesLibraryEntry(detail = creation.toRecurringSeriesDetail())
                }
            }
        }

    suspend fun execute(command: MemberCommand): TaskIdentifier =
        executeWithOutcome(command).taskIdentifier

    suspend fun executeWithOutcome(command: MemberCommand): MemberCommandOutcome {
        val memberIdentifier = requireReadyMemberIdentifier()

        return when (command) {
            is MemberCommand.AddPrivateTaskToToday ->
                MemberCommandOutcome.TaskChanged(
                    addPrivateTaskToToday(
                        memberIdentifier = memberIdentifier,
                        command = command,
                    ),
                )
            is MemberCommand.AddPrivateDailyTaskToToday ->
                MemberCommandOutcome.TaskChanged(
                    addPrivateDailyTaskToToday(
                        memberIdentifier = memberIdentifier,
                        command = command,
                    ),
                )
            is MemberCommand.CompleteOccurrence ->
                completeOccurrence(
                    memberIdentifier = memberIdentifier,
                    command = command,
                )
            is MemberCommand.CorrectOccurrenceCompletion ->
                correctOccurrenceCompletion(
                    memberIdentifier = memberIdentifier,
                    command = command,
                )
            is MemberCommand.CorrectOccurrenceToActionable ->
                correctOccurrenceToActionable(
                    memberIdentifier = memberIdentifier,
                    command = command,
                )
            is MemberCommand.SetOccurrenceSubtaskChecked ->
                MemberCommandOutcome.TaskChanged(
                    setOccurrenceSubtaskChecked(
                        memberIdentifier = memberIdentifier,
                        command = command,
                    ),
                )
            is MemberCommand.UndoOccurrenceCompletion ->
                MemberCommandOutcome.TaskChanged(
                    undoOccurrenceCompletion(
                        memberIdentifier = memberIdentifier,
                        command = command,
                    ),
                )
        }
    }

    private suspend fun setOccurrenceSubtaskChecked(
        memberIdentifier: MemberIdentifier,
        command: MemberCommand.SetOccurrenceSubtaskChecked,
    ): TaskIdentifier {
        val persistence = requireTaskPersistence()
        val creation =
            persistence.observeRecurringSeries(
                ownerIdentifier = memberIdentifier,
                taskIdentifier = command.occurrenceKey.taskIdentifier,
            ).first()
                ?: throw IllegalArgumentException("Recurring Series does not exist")
        require(
            RecurrenceEngine.occurrenceKeyOn(
                taskIdentifier = creation.series.identifier,
                revision = creation.initialRevision,
                nominalDate = command.occurrenceKey.nominalDate,
            ) == command.occurrenceKey,
        ) { "The requested date is not an expected Occurrence" }
        val existing =
            persistence.observeOccurrenceState(
                ownerIdentifier = memberIdentifier,
                occurrenceKey = command.occurrenceKey,
            ).first()
        val occurrenceDefinition = existing?.snapshot?.seriesRevision ?: creation.initialRevision
        require(
            occurrenceDefinition.subtaskTemplate.any {
                it.identifier == command.subtaskIdentifier
            },
        ) { "Subtask does not belong to the Occurrence definition" }
        require(existing?.outcome !is OccurrenceOutcome.Complete) {
            "Correct a completed Occurrence through its Completion Record"
        }
        val currentSubtasks =
            existing?.subtasks
                ?: creation.initialRevision.subtaskTemplate.map { template ->
                    OccurrenceSubtaskState(template.identifier, isChecked = false)
                }
        val updatedSubtasks =
            currentSubtasks.map { subtask ->
                if (subtask.identifier == command.subtaskIdentifier) {
                    subtask.copy(isChecked = command.isChecked)
                } else {
                    subtask
                }
            }
        if (updatedSubtasks == currentSubtasks) return command.occurrenceKey.taskIdentifier

        persistence.putOccurrenceState(
            ownerIdentifier = memberIdentifier,
            state =
                OccurrenceState(
                    key = command.occurrenceKey,
                    snapshot =
                        existing?.snapshot
                            ?: OccurrenceSnapshot(
                                seriesRevision = creation.initialRevision,
                                nominalDate = command.occurrenceKey.nominalDate,
                                effectiveDate = command.occurrenceKey.nominalDate,
                            ),
                    subtasks = updatedSubtasks,
                    outcome = null,
                    invalidatedCompletionEpochs = existing?.invalidatedCompletionEpochs.orEmpty(),
                ),
        )
        return command.occurrenceKey.taskIdentifier
    }

    private suspend fun correctOccurrenceToActionable(
        memberIdentifier: MemberIdentifier,
        command: MemberCommand.CorrectOccurrenceToActionable,
    ): MemberCommandOutcome.OccurrenceCorrected {
        val persistence = requireTaskPersistence()
        val state =
            persistence.observeOccurrenceState(
                ownerIdentifier = memberIdentifier,
                occurrenceKey = command.occurrenceKey,
            ).first()
        val completion = (state?.outcome as? OccurrenceOutcome.Complete)?.record
        if (completion?.epoch != command.completionEpoch) {
            return MemberCommandOutcome.OccurrenceCorrected(
                taskIdentifier = command.occurrenceKey.taskIdentifier,
                applied = false,
            )
        }

        val templateIdentifiers =
            completion.seriesRevision.subtaskTemplate.map(SubtaskTemplate::identifier).toSet()
        require(command.checkedSubtaskIdentifiers.all(templateIdentifiers::contains)) {
            "Checked Subtasks must belong to the historical Occurrence definition"
        }
        val correctedSubtasks =
            completion.seriesRevision.subtaskTemplate.map { template ->
                OccurrenceSubtaskState(
                    identifier = template.identifier,
                    isChecked = template.identifier in command.checkedSubtaskIdentifiers,
                )
            }
        persistence.putOccurrenceState(
            ownerIdentifier = memberIdentifier,
            state =
                state.copy(
                    subtasks = correctedSubtasks,
                    outcome = null,
                    actionableSubtasksBeforeCompletion = null,
                    invalidatedCompletionEpochs =
                        state.invalidatedCompletionEpochs + completion.epoch,
                ),
        )
        return MemberCommandOutcome.OccurrenceCorrected(
            taskIdentifier = command.occurrenceKey.taskIdentifier,
            applied = true,
        )
    }

    private suspend fun correctOccurrenceCompletion(
        memberIdentifier: MemberIdentifier,
        command: MemberCommand.CorrectOccurrenceCompletion,
    ): MemberCommandOutcome.OccurrenceCorrected {
        require(
            command.correctedCompletedAt.instant
                .atZone(command.correctedCompletedAt.zoneId)
                .toLocalDate() == command.correctedCompletedAt.localDate,
        ) { "Corrected completion date must match its instant and zone" }
        require(!command.correctedCompletedAt.instant.isAfter(clock.instant())) {
            "A completion time cannot be in the future"
        }
        val persistence = requireTaskPersistence()
        val state =
            persistence.observeOccurrenceState(
                ownerIdentifier = memberIdentifier,
                occurrenceKey = command.occurrenceKey,
            ).first()
        val completion = (state?.outcome as? OccurrenceOutcome.Complete)?.record
        if (completion?.epoch != command.completionEpoch) {
            return MemberCommandOutcome.OccurrenceCorrected(
                taskIdentifier = command.occurrenceKey.taskIdentifier,
                applied = false,
            )
        }

        val templateIdentifiers =
            completion.seriesRevision.subtaskTemplate.map(SubtaskTemplate::identifier).toSet()
        require(command.checkedSubtaskIdentifiers.all(templateIdentifiers::contains)) {
            "Checked Subtasks must belong to the historical Occurrence definition"
        }
        val correctedSubtasks =
            completion.seriesRevision.subtaskTemplate.map { template ->
                OccurrenceSubtaskState(
                    identifier = template.identifier,
                    isChecked = template.identifier in command.checkedSubtaskIdentifiers,
                )
            }
        val correctedCompletion =
            completion.copy(
                completedAt = command.correctedCompletedAt,
                subtasks = correctedSubtasks,
            )
        persistence.putOccurrenceState(
            ownerIdentifier = memberIdentifier,
            state =
                state.copy(
                    subtasks = correctedSubtasks,
                    outcome = OccurrenceOutcome.Complete(correctedCompletion),
                ),
        )
        return MemberCommandOutcome.OccurrenceCorrected(
            taskIdentifier = command.occurrenceKey.taskIdentifier,
            applied = true,
        )
    }

    private suspend fun undoOccurrenceCompletion(
        memberIdentifier: MemberIdentifier,
        command: MemberCommand.UndoOccurrenceCompletion,
    ): TaskIdentifier {
        val persistence = requireTaskPersistence()
        val state =
            persistence.observeOccurrenceState(
                ownerIdentifier = memberIdentifier,
                occurrenceKey = command.occurrenceKey,
            ).first()
        val completion = (state?.outcome as? OccurrenceOutcome.Complete)?.record
        if (completion?.epoch != command.completionEpoch) {
            return command.occurrenceKey.taskIdentifier
        }

        persistence.putOccurrenceState(
            ownerIdentifier = memberIdentifier,
            state =
                state.copy(
                    subtasks =
                        requireNotNull(state.actionableSubtasksBeforeCompletion) {
                            "Completed Occurrence State is missing its immediate Undo state"
                        },
                    outcome = null,
                    actionableSubtasksBeforeCompletion = null,
                    invalidatedCompletionEpochs =
                        state.invalidatedCompletionEpochs + completion.epoch,
                ),
        )
        return command.occurrenceKey.taskIdentifier
    }

    private suspend fun completeOccurrence(
        memberIdentifier: MemberIdentifier,
        command: MemberCommand.CompleteOccurrence,
    ): MemberCommandOutcome.OccurrenceCompleted {
        val persistence = requireTaskPersistence()
        val creation =
            persistence.observeRecurringSeries(
                ownerIdentifier = memberIdentifier,
                taskIdentifier = command.occurrenceKey.taskIdentifier,
            ).first()
                ?: throw IllegalArgumentException("Recurring Series does not exist")
        val expectedKey =
            RecurrenceEngine.occurrenceKeyOn(
                taskIdentifier = creation.series.identifier,
                revision = creation.initialRevision,
                nominalDate = command.occurrenceKey.nominalDate,
            )
        require(expectedKey == command.occurrenceKey) {
            "The requested date is not an expected Occurrence"
        }
        require(!command.occurrenceKey.nominalDate.isAfter(currentMemberMoment().localDate)) {
            "An Occurrence cannot be completed before its Nominal Date"
        }
        val existing =
            persistence.observeOccurrenceState(
                ownerIdentifier = memberIdentifier,
                occurrenceKey = command.occurrenceKey,
            ).first()
        val requestedEpoch = CompletionEpoch(command.commandIdentifier.value)
        if (requestedEpoch in existing?.invalidatedCompletionEpochs.orEmpty()) {
            return MemberCommandOutcome.OccurrenceCompleted(
                taskIdentifier = command.occurrenceKey.taskIdentifier,
                completion = null,
                applied = false,
            )
        }
        val existingCompletion =
            (existing?.outcome as? OccurrenceOutcome.Complete)?.record
        if (existingCompletion != null) {
            return MemberCommandOutcome.OccurrenceCompleted(
                taskIdentifier = command.occurrenceKey.taskIdentifier,
                completion = existingCompletion,
                applied = false,
            )
        }

        val snapshot =
            existing?.snapshot
                ?: OccurrenceSnapshot(
                    seriesRevision = creation.initialRevision,
                    nominalDate = command.occurrenceKey.nominalDate,
                    effectiveDate = command.occurrenceKey.nominalDate,
                )
        val templateIdentifiers =
            snapshot.seriesRevision.subtaskTemplate.map(SubtaskTemplate::identifier).toSet()
        require(command.checkedSubtaskIdentifiers.all(templateIdentifiers::contains)) {
            "Checked Subtasks must belong to the Occurrence definition"
        }
        val subtasks =
            snapshot.seriesRevision.subtaskTemplate.map { template ->
                OccurrenceSubtaskState(
                    identifier = template.identifier,
                    isChecked = template.identifier in command.checkedSubtaskIdentifiers,
                )
            }
        val actionableSubtasksBeforeCompletion =
            existing?.subtasks
                ?: snapshot.seriesRevision.subtaskTemplate.map { template ->
                    OccurrenceSubtaskState(
                        identifier = template.identifier,
                        isChecked = false,
                    )
                }
        val completion =
            CompletionRecord(
                epoch = requestedEpoch,
                snapshot = snapshot,
                completedAt = currentMemberMoment(),
                subtasks = subtasks,
            )
        persistence.putOccurrenceState(
            ownerIdentifier = memberIdentifier,
            state =
                OccurrenceState(
                    key = command.occurrenceKey,
                    snapshot = snapshot,
                    subtasks = subtasks,
                    outcome = OccurrenceOutcome.Complete(completion),
                    actionableSubtasksBeforeCompletion = actionableSubtasksBeforeCompletion,
                    invalidatedCompletionEpochs = existing?.invalidatedCompletionEpochs.orEmpty(),
                ),
        )
        return MemberCommandOutcome.OccurrenceCompleted(
            taskIdentifier = command.occurrenceKey.taskIdentifier,
            completion = completion,
            applied = true,
        )
    }

    private suspend fun addPrivateDailyTaskToToday(
        memberIdentifier: MemberIdentifier,
        command: MemberCommand.AddPrivateDailyTaskToToday,
    ): TaskIdentifier {
        val title = command.title.trim()
        require(title.isNotBlank()) { "Task title must not be blank" }
        val subtaskLabel = command.subtask?.trim()?.takeIf(String::isNotBlank)
        val moment = currentMemberMoment()
        val identifiers = taskIdentities.identifiersFor(command.commandIdentifier)
        val creation =
            RecurringSeriesCreation(
                series =
                    RecurringSeries(
                        identifier = identifiers.taskIdentifier,
                        ownerIdentifier = memberIdentifier,
                        activeRevisionIdentifier = identifiers.seriesRevisionIdentifier,
                        visibility = TaskVisibility.Private,
                        createdAt = moment,
                    ),
                initialRevision =
                    SeriesRevision(
                        identifier = identifiers.seriesRevisionIdentifier,
                        taskIdentifier = identifiers.taskIdentifier,
                        title = title,
                        subtaskTemplate =
                            subtaskLabel?.let { label ->
                                listOf(
                                    SubtaskTemplate(
                                        identifier = identifiers.initialSubtaskIdentifier,
                                        label = label,
                                    ),
                                )
                            }.orEmpty(),
                        recurrenceRule = RecurrenceRule.Daily(everyDays = 1),
                        startsOn = moment.localDate,
                        endsOnInclusive = null,
                        recordedAt = moment,
                    ),
            )

        return requireTaskPersistence()
            .putRecurringSeriesIfAbsent(creation)
            .series
            .identifier
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
                memberDates = observeMemberDates(clock, zoneProvider),
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

    data class AddPrivateDailyTaskToToday(
        val commandIdentifier: CommandIdentifier,
        val title: String,
        val subtask: String? = null,
    ) : MemberCommand

    data class CompleteOccurrence(
        val commandIdentifier: CommandIdentifier,
        val occurrenceKey: OccurrenceKey,
        val checkedSubtaskIdentifiers: Set<SubtaskIdentifier> = emptySet(),
    ) : MemberCommand

    data class CorrectOccurrenceCompletion(
        val occurrenceKey: OccurrenceKey,
        val completionEpoch: CompletionEpoch,
        val correctedCompletedAt: MemberActionMoment,
        val checkedSubtaskIdentifiers: Set<SubtaskIdentifier>,
    ) : MemberCommand

    data class CorrectOccurrenceToActionable(
        val occurrenceKey: OccurrenceKey,
        val completionEpoch: CompletionEpoch,
        val checkedSubtaskIdentifiers: Set<SubtaskIdentifier>,
    ) : MemberCommand

    data class SetOccurrenceSubtaskChecked(
        val occurrenceKey: OccurrenceKey,
        val subtaskIdentifier: SubtaskIdentifier,
        val isChecked: Boolean,
    ) : MemberCommand

    data class UndoOccurrenceCompletion(
        val occurrenceKey: OccurrenceKey,
        val completionEpoch: CompletionEpoch,
    ) : MemberCommand
}

sealed interface MemberCommandOutcome {
    val taskIdentifier: TaskIdentifier

    data class TaskChanged(
        override val taskIdentifier: TaskIdentifier,
    ) : MemberCommandOutcome

    data class OccurrenceCompleted(
        override val taskIdentifier: TaskIdentifier,
        val completion: CompletionRecord?,
        val applied: Boolean,
    ) : MemberCommandOutcome {
        init {
            require(applied.not() || completion != null) {
                "An applied completion outcome must contain its persisted Completion Record"
            }
        }
    }

    data class OccurrenceCorrected(
        override val taskIdentifier: TaskIdentifier,
        val applied: Boolean,
    ) : MemberCommandOutcome
}

data class InitialTaskIdentifiers(
    val taskIdentifier: TaskIdentifier,
    val revisionIdentifier: TaskRevisionIdentifier,
    val seriesRevisionIdentifier: SeriesRevisionIdentifier,
    val initialSubtaskIdentifier: SubtaskIdentifier =
        SubtaskIdentifier("subtask-${taskIdentifier.value}"),
)

fun interface TaskIdentityGenerator {
    fun identifiersFor(commandIdentifier: CommandIdentifier): InitialTaskIdentifiers
}

data class PersonalAgenda(
    val date: LocalDate,
    val tasks: List<AgendaTask>,
    val doneToday: List<AgendaTask> = emptyList(),
)

data class PersonalHistory(
    val completions: List<AgendaTask> = emptyList(),
)

data class AgendaTask(
    val identifier: TaskIdentifier,
    val revisionIdentifier: TaskRevisionIdentifier?,
    val title: String,
    val plannedDate: LocalDate?,
    val visibility: TaskVisibility,
    val reminderAt: LocalTime?,
    val seriesRevisionIdentifier: SeriesRevisionIdentifier? = null,
    val occurrence: AgendaOccurrence? = null,
)

data class AgendaOccurrence(
    val key: OccurrenceKey,
    val nominalDate: LocalDate,
    val effectiveDate: LocalDate,
    val subtasks: List<AgendaSubtask>,
    val outcome: OccurrenceOutcome? = null,
)

data class AgendaSubtask(
    val identifier: SubtaskIdentifier,
    val label: String,
    val isChecked: Boolean,
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

data class RecurringSeriesDetail(
    val identifier: TaskIdentifier,
    val ownerIdentifier: MemberIdentifier,
    val visibility: TaskVisibility,
    val createdAt: MemberActionMoment,
    val initialRevision: SeriesRevision,
)

data class RecurringSeriesLibraryEntry(
    val detail: RecurringSeriesDetail,
)

fun interface ZoneProvider {
    fun currentZone(): ZoneId
}

private data class WorkspaceDate(
    val accessState: WorkspaceAccessState,
    val date: LocalDate,
)

private fun observeMemberDates(
    clock: Clock,
    zoneProvider: ZoneProvider,
): Flow<LocalDate> =
    flow {
        var emittedDate: LocalDate? = null
        while (currentCoroutineContext().isActive) {
            val instant = clock.instant()
            val zoneId = zoneProvider.currentZone()
            val date = instant.atZone(zoneId).toLocalDate()
            if (date != emittedDate) {
                emit(date)
                emittedDate = date
            }

            val nextMidnight = date.plusDays(1).atStartOfDay(zoneId).toInstant()
            val untilMidnightMillis =
                Duration.between(instant, nextMidnight).toMillis().coerceAtLeast(1L)
            delay(minOf(untilMidnightMillis, DateAndZonePollIntervalMillis))
        }
    }

private const val DateAndZonePollIntervalMillis = 60_000L

private fun StandaloneTaskCreation.toAgendaTask(): AgendaTask =
    AgendaTask(
        identifier = task.identifier,
        revisionIdentifier = initialRevision.identifier,
        title = initialRevision.title,
        plannedDate = initialRevision.plannedDate,
        visibility = task.visibility,
        reminderAt = reminder?.time,
    )

@OptIn(ExperimentalCoroutinesApi::class)
private fun TaskRepository.observeRecurringAgendaTasks(
    ownerIdentifier: MemberIdentifier,
    date: LocalDate,
): Flow<List<AgendaTask>> =
    observeRecurringSeries(ownerIdentifier).flatMapLatest { creations ->
        val agendaTasks =
            creations.mapNotNull { creation ->
                val occurrenceKey =
                    RecurrenceEngine.occurrenceKeyOn(
                        taskIdentifier = creation.series.identifier,
                        revision = creation.initialRevision,
                        nominalDate = date,
                    ) ?: return@mapNotNull null
                observeOccurrenceState(
                    ownerIdentifier = ownerIdentifier,
                    occurrenceKey = occurrenceKey,
                ).map { state -> creation.toAgendaTask(occurrenceKey, state) }
            }

        if (agendaTasks.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(agendaTasks) { tasks -> tasks.toList() }
        }
    }

@OptIn(ExperimentalCoroutinesApi::class)
private fun TaskRepository.observeCompletedOccurrenceHistory(
    ownerIdentifier: MemberIdentifier,
): Flow<PersonalHistory> =
    observeRecurringSeries(ownerIdentifier).flatMapLatest { creations ->
        val histories =
            creations.map { creation ->
                observeOccurrenceStates(
                    ownerIdentifier = ownerIdentifier,
                    taskIdentifier = creation.series.identifier,
                ).map { states ->
                    states.mapNotNull(OccurrenceState::toCompletedAgendaTaskOrNull)
                }
            }

        if (histories.isEmpty()) {
            flowOf(PersonalHistory())
        } else {
            combine(histories) { taskHistories ->
                PersonalHistory(
                    completions =
                        taskHistories
                            .flatMap(List<AgendaTask>::toList)
                            .sortedWith(
                                compareByDescending<AgendaTask> {
                                    it.occurrence?.nominalDate
                                }.thenBy { it.identifier.value },
                            ),
                )
            }
        }
    }

private fun TaskRepository.observeCompletedOccurrenceHistory(
    ownerIdentifier: MemberIdentifier,
    taskIdentifier: TaskIdentifier,
): Flow<PersonalHistory> =
    observeOccurrenceStates(
        ownerIdentifier = ownerIdentifier,
        taskIdentifier = taskIdentifier,
    ).map { states ->
        PersonalHistory(
            completions =
                states
                    .mapNotNull(OccurrenceState::toCompletedAgendaTaskOrNull)
                    .sortedByDescending { task -> task.occurrence?.nominalDate },
        )
    }

private fun OccurrenceState.toCompletedAgendaTaskOrNull(): AgendaTask? {
    val completion = (outcome as? OccurrenceOutcome.Complete)?.record ?: return null
    val revision = snapshot.seriesRevision
    return AgendaTask(
        identifier = key.taskIdentifier,
        revisionIdentifier = null,
        title = revision.title,
        plannedDate = null,
        visibility = TaskVisibility.Private,
        reminderAt = null,
        seriesRevisionIdentifier = revision.identifier,
        occurrence =
            AgendaOccurrence(
                key = key,
                nominalDate = snapshot.nominalDate,
                effectiveDate = snapshot.effectiveDate,
                subtasks =
                    revision.subtaskTemplate.map { template ->
                        AgendaSubtask(
                            identifier = template.identifier,
                            label = template.label,
                            isChecked =
                                subtasks.first { it.identifier == template.identifier }.isChecked,
                        )
                    },
                outcome = OccurrenceOutcome.Complete(completion),
            ),
    )
}

private fun RecurringSeriesCreation.toAgendaTask(
    occurrenceKey: OccurrenceKey,
    state: OccurrenceState?,
): AgendaTask {
    val revision = state?.snapshot?.seriesRevision ?: initialRevision
    val nominalDate = state?.snapshot?.nominalDate ?: occurrenceKey.nominalDate
    val effectiveDate = state?.snapshot?.effectiveDate ?: occurrenceKey.nominalDate
    return AgendaTask(
        identifier = series.identifier,
        revisionIdentifier = null,
        title = revision.title,
        plannedDate = null,
        visibility = series.visibility,
        reminderAt = null,
        seriesRevisionIdentifier = revision.identifier,
        occurrence =
            AgendaOccurrence(
                key = occurrenceKey,
                nominalDate = nominalDate,
                effectiveDate = effectiveDate,
                subtasks =
                    revision.subtaskTemplate.map { subtask ->
                        AgendaSubtask(
                            identifier = subtask.identifier,
                            label = subtask.label,
                            isChecked =
                                state?.subtasks
                                    ?.firstOrNull { it.identifier == subtask.identifier }
                                    ?.isChecked
                                    ?: false,
                        )
                    },
                outcome = state?.outcome,
            ),
    )
}

private fun RecurringSeriesCreation.toRecurringSeriesDetail(): RecurringSeriesDetail =
    RecurringSeriesDetail(
        identifier = series.identifier,
        ownerIdentifier = series.ownerIdentifier,
        visibility = series.visibility,
        createdAt = series.createdAt,
        initialRevision = initialRevision,
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
