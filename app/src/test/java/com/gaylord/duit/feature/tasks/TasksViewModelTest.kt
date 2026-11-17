package com.gaylord.duit.feature.tasks

import com.gaylord.duit.data.repository.TaskRepository
import com.gaylord.duit.domain.model.TaskVisibility
import com.gaylord.duit.domain.model.MemberActionMoment
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.OccurrenceKey
import com.gaylord.duit.domain.model.OccurrenceOutcome
import com.gaylord.duit.domain.model.OccurrenceState
import com.gaylord.duit.domain.model.RecurringSeriesCreation
import com.gaylord.duit.domain.model.SeriesRevisionIdentifier
import com.gaylord.duit.domain.model.StandaloneTaskCreation
import com.gaylord.duit.domain.model.SubtaskIdentifier
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.model.TaskRevisionIdentifier
import com.gaylord.duit.notifications.ReminderGateway
import com.gaylord.duit.notifications.ScheduledTaskReminder
import com.gaylord.duit.workspace.CommandIdentifier
import com.gaylord.duit.workspace.AuthenticatedIdentity
import com.gaylord.duit.workspace.AuthenticationGateway
import com.gaylord.duit.workspace.AuthenticationSignOut
import com.gaylord.duit.workspace.DuitWorkspace
import com.gaylord.duit.workspace.InitialTaskIdentifiers
import com.gaylord.duit.workspace.MemberApproval
import com.gaylord.duit.workspace.MemberApprovalGateway
import com.gaylord.duit.workspace.MemberCommand
import com.gaylord.duit.workspace.OwnerWorkspaceHydration
import com.gaylord.duit.workspace.OwnerWorkspaceHydrationGateway
import com.gaylord.duit.workspace.PersistedWorkspaceSessionGateway
import com.gaylord.duit.workspace.TaskIdentityGenerator
import com.gaylord.duit.workspace.WorkspaceSessionOperationCoordinator
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {
    @Test
    fun `Tasks observes one cheap library and only the selected Task history while started`() =
        runTest {
            val repository = TrackingTasksRepository()
            val workspace = createTasksWorkspace(repository)
            workspace.addDailyTask()
            val viewModel = TasksViewModel(workspace, backgroundScope)

            assertEquals(0, repository.activeLibraryObservers)
            assertEquals(0, repository.activeHistoryObservers)

            viewModel.onEvent(TasksEvent.Started)
            runCurrent()

            assertEquals(listOf("Water the herbs"), viewModel.state.value.library.titles())
            assertEquals(1, repository.activeLibraryObservers)
            assertEquals(0, repository.activeHistoryObservers)

            viewModel.onEvent(TasksEvent.SelectTask(DailyTaskIdentifier))
            runCurrent()

            assertEquals(DailyTaskIdentifier, viewModel.state.value.selectedTaskIdentifier)
            assertEquals(1, repository.activeLibraryObservers)
            assertEquals(1, repository.activeHistoryObservers)
            assertEquals(listOf(DailyTaskIdentifier), repository.observedHistoryTasks)

            viewModel.onEvent(TasksEvent.Stopped)
            runCurrent()

            assertEquals(0, repository.activeLibraryObservers)
            assertEquals(0, repository.activeHistoryObservers)

            viewModel.onEvent(TasksEvent.Started)
            runCurrent()

            assertEquals(1, repository.activeLibraryObservers)
            assertEquals(1, repository.activeHistoryObservers)

            viewModel.onEvent(TasksEvent.BackToLibrary)
            runCurrent()

            assertEquals(1, repository.activeLibraryObservers)
            assertEquals(0, repository.activeHistoryObservers)
        }

    @Test
    fun `typed correction events update time Subtasks and outcome through the Workspace seam`() =
        runTest {
            val repository = TrackingTasksRepository()
            val workspace = createTasksWorkspace(repository)
            workspace.addDailyTask()
            workspace.executeWithOutcome(
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("completion-current"),
                    occurrenceKey = DailyOccurrenceKey,
                    checkedSubtaskIdentifiers = setOf(SoilSubtaskIdentifier),
                ),
            )
            val viewModel = TasksViewModel(workspace, backgroundScope)
            viewModel.onEvent(TasksEvent.Started)
            runCurrent()
            viewModel.onEvent(TasksEvent.SelectTask(DailyTaskIdentifier))
            runCurrent()
            val original =
                (viewModel.state.value.history.completions.single().occurrence?.outcome
                    as OccurrenceOutcome.Complete).record
            val correctedMoment =
                MemberActionMoment(
                    instant = Instant.parse("2026-08-12T06:45:00Z"),
                    localDate = DailyDate,
                    zoneId = ZoneId.of("UTC"),
                )

            viewModel.onEvent(
                TasksEvent.CorrectCompletion(
                    occurrenceKey = DailyOccurrenceKey,
                    completionEpoch = original.epoch,
                    correctedCompletedAt = correctedMoment,
                    checkedSubtaskIdentifiers = emptySet(),
                ),
            )
            runCurrent()

            val corrected =
                (viewModel.state.value.history.completions.single().occurrence?.outcome
                    as OccurrenceOutcome.Complete).record
            assertEquals(correctedMoment, corrected.completedAt)
            assertEquals(false, corrected.subtasks.single().isChecked)
            assertEquals(1L, viewModel.state.value.correctionSuccessKey)

            viewModel.onEvent(
                TasksEvent.CorrectToActionable(
                    occurrenceKey = DailyOccurrenceKey,
                    completionEpoch = corrected.epoch,
                    checkedSubtaskIdentifiers = setOf(SoilSubtaskIdentifier),
                ),
            )
            runCurrent()

            assertEquals(emptyList<String>(), viewModel.state.value.history.completions.map { it.title })
            assertEquals(2L, viewModel.state.value.correctionSuccessKey)
            assertEquals(
                true,
                workspace.observePersonalAgenda().first().tasks.single()
                    .occurrence?.subtasks?.single()?.isChecked,
            )
        }

    @Test
    fun `Member transition immediately clears the previous owners Tasks state`() =
        runTest {
            val repository = TrackingTasksRepository()
            val authentication =
                MutableTasksAuthenticationGateway(AuthenticatedIdentity("member-alex"))
            val workspace =
                createAuthenticatedTasksWorkspace(
                    repository = repository,
                    authentication = authentication,
                    scope = backgroundScope,
                )
            workspace.restoreSession()
            workspace.addDailyTask()
            val viewModel = TasksViewModel(workspace, backgroundScope)
            viewModel.onEvent(TasksEvent.Started)
            runCurrent()
            viewModel.onEvent(TasksEvent.SelectTask(DailyTaskIdentifier))
            runCurrent()

            assertEquals(listOf("Water the herbs"), viewModel.state.value.library.titles())
            assertEquals(DailyTaskIdentifier, viewModel.state.value.selectedTaskIdentifier)

            val signOut = backgroundScope.launch { workspace.signOut() }
            runCurrent()
            signOut.join()
            runCurrent()

            assertEquals(emptyList<String>(), viewModel.state.value.library.titles())
            assertEquals(null, viewModel.state.value.selectedTaskIdentifier)
            assertEquals(emptyList<String>(), viewModel.state.value.history.completions.map { it.title })
            assertEquals(0, repository.activeLibraryObservers)
            assertEquals(0, repository.activeHistoryObservers)

            authentication.identity = AuthenticatedIdentity("member-sam")
            workspace.restoreSession()
            runCurrent()

            assertEquals(emptyList<String>(), viewModel.state.value.library.titles())
            assertEquals(null, viewModel.state.value.selectedTaskIdentifier)
            assertEquals(1, repository.activeLibraryObservers)

            workspace.addDailyTask(
                commandIdentifier = CommandIdentifier("feed-birds"),
                title = "Feed the birds",
            )
            runCurrent()

            assertEquals(listOf("Feed the birds"), viewModel.state.value.library.titles())
        }

    @Test
    fun `stale correction does not advance success and asks the screen to review latest state`() =
        runTest {
            val repository = TrackingTasksRepository()
            val workspace = createTasksWorkspace(repository)
            workspace.addDailyTask()
            workspace.executeWithOutcome(
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("completion-current"),
                    occurrenceKey = DailyOccurrenceKey,
                ),
            )
            val viewModel = TasksViewModel(workspace, backgroundScope)
            viewModel.onEvent(TasksEvent.Started)
            runCurrent()
            viewModel.onEvent(TasksEvent.SelectTask(DailyTaskIdentifier))
            runCurrent()
            val effect = async { viewModel.effects.first() }

            viewModel.onEvent(
                TasksEvent.CorrectToActionable(
                    occurrenceKey = DailyOccurrenceKey,
                    completionEpoch = com.gaylord.duit.domain.model.CompletionEpoch("stale"),
                    checkedSubtaskIdentifiers = emptySet(),
                ),
            )
            runCurrent()

            assertEquals(0L, viewModel.state.value.correctionSuccessKey)
            assertEquals(TasksEffect.CorrectionBecameStale, effect.await())
            assertEquals(1, viewModel.state.value.history.completions.size)
        }
}

private val DailyDate = LocalDate.of(2026, 8, 12)
private val DailyTaskIdentifier = TaskIdentifier("task-water-herbs")
private val SoilSubtaskIdentifier = SubtaskIdentifier("subtask-task-water-herbs")
private val DailyOccurrenceKey = OccurrenceKey(DailyTaskIdentifier, DailyDate)

private suspend fun DuitWorkspace.addDailyTask(
    commandIdentifier: CommandIdentifier = CommandIdentifier("water-herbs"),
    title: String = "Water the herbs",
) {
    execute(
        MemberCommand.AddPrivateDailyTaskToToday(
            commandIdentifier = commandIdentifier,
            title = title,
            subtask = "Check the soil",
        ),
    )
}

private fun List<com.gaylord.duit.workspace.RecurringSeriesLibraryEntry>.titles(): List<String> =
    map { it.detail.initialRevision.title }

private fun createTasksWorkspace(repository: TaskRepository): DuitWorkspace =
    DuitWorkspace(
        memberIdentifier = MemberIdentifier("member-1"),
        clock = Clock.fixed(Instant.parse("2026-08-12T07:15:00Z"), ZoneId.of("UTC")),
        zoneProvider = { ZoneId.of("UTC") },
        taskIdentities =
            TaskIdentityGenerator { commandIdentifier ->
                InitialTaskIdentifiers(
                    taskIdentifier = TaskIdentifier("task-${commandIdentifier.value}"),
                    revisionIdentifier =
                        TaskRevisionIdentifier("revision-${commandIdentifier.value}"),
                    seriesRevisionIdentifier =
                        SeriesRevisionIdentifier("series-revision-${commandIdentifier.value}"),
                )
            },
        persistence = repository,
        reminders = TasksTestReminderGateway,
    )

private fun createAuthenticatedTasksWorkspace(
    repository: TaskRepository,
    authentication: AuthenticationGateway,
    scope: CoroutineScope,
): DuitWorkspace =
    DuitWorkspace.authenticated(
        clock = Clock.fixed(Instant.parse("2026-08-12T07:15:00Z"), ZoneId.of("UTC")),
        zoneProvider = { ZoneId.of("UTC") },
        taskIdentities =
            TaskIdentityGenerator { commandIdentifier ->
                InitialTaskIdentifiers(
                    taskIdentifier = TaskIdentifier("task-${commandIdentifier.value}"),
                    revisionIdentifier =
                        TaskRevisionIdentifier("revision-${commandIdentifier.value}"),
                    seriesRevisionIdentifier =
                        SeriesRevisionIdentifier("series-revision-${commandIdentifier.value}"),
                )
            },
        persistence = repository,
        reminders = TasksTestReminderGateway,
        pairing = null,
        authentication = authentication,
        approval = MemberApprovalGateway { MemberApproval.Approved },
        hydration = OwnerWorkspaceHydrationGateway { OwnerWorkspaceHydration.Complete },
        persistedSession = AlwaysHydratedTasksSessionGateway,
        sessionOperations = WorkspaceSessionOperationCoordinator(scope),
    )

private class TrackingTasksRepository : TaskRepository {
    private val standaloneTasks =
        MutableStateFlow<Map<TaskIdentifier, StandaloneTaskCreation>>(emptyMap())
    private val recurringSeries =
        MutableStateFlow<Map<TaskIdentifier, RecurringSeriesCreation>>(emptyMap())
    private val occurrenceStates =
        MutableStateFlow<Map<OccurrenceKey, OccurrenceState>>(emptyMap())

    var activeLibraryObservers: Int = 0
        private set
    var activeHistoryObservers: Int = 0
        private set
    val observedHistoryTasks = mutableListOf<TaskIdentifier>()

    override suspend fun putStandaloneTaskIfAbsent(
        creation: StandaloneTaskCreation,
    ): StandaloneTaskCreation {
        val identifier = creation.task.identifier
        standaloneTasks.value[identifier]?.let { return it }
        standaloneTasks.value = standaloneTasks.value + (identifier to creation)
        return creation
    }

    override fun observeStandaloneTasks(
        ownerIdentifier: MemberIdentifier,
        plannedDate: LocalDate,
    ): Flow<List<StandaloneTaskCreation>> =
        standaloneTasks.map { stored ->
            stored.values.filter { creation ->
                creation.task.ownerIdentifier == ownerIdentifier &&
                    creation.initialRevision.plannedDate == plannedDate
            }
        }

    override fun observeStandaloneTask(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
    ): Flow<StandaloneTaskCreation?> =
        standaloneTasks.map { stored ->
            stored[taskIdentifier]?.takeIf { it.task.ownerIdentifier == ownerIdentifier }
        }

    override suspend fun putRecurringSeriesIfAbsent(
        creation: RecurringSeriesCreation,
    ): RecurringSeriesCreation {
        val identifier = creation.series.identifier
        recurringSeries.value[identifier]?.let { return it }
        recurringSeries.value = recurringSeries.value + (identifier to creation)
        return creation
    }

    override suspend fun putRecurringSeriesVisibility(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
        visibility: TaskVisibility,
    ): RecurringSeriesCreation? {
        val existing =
            recurringSeries.value[taskIdentifier]
                ?.takeIf { it.series.ownerIdentifier == ownerIdentifier }
                ?: return null
        val updated = existing.copy(series = existing.series.copy(visibility = visibility))
        recurringSeries.value = recurringSeries.value + (taskIdentifier to updated)
        return updated
    }

    override fun observeRecurringSeries(
        ownerIdentifier: MemberIdentifier,
    ): Flow<List<RecurringSeriesCreation>> =
        recurringSeries
            .map { stored ->
                stored.values.filter { it.series.ownerIdentifier == ownerIdentifier }
            }.onStart { activeLibraryObservers += 1 }
            .onCompletion { activeLibraryObservers -= 1 }

    override fun observeRecurringSeries(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
    ): Flow<RecurringSeriesCreation?> =
        recurringSeries.map { stored ->
            stored[taskIdentifier]?.takeIf { it.series.ownerIdentifier == ownerIdentifier }
        }

    override suspend fun putOccurrenceState(
        ownerIdentifier: MemberIdentifier,
        state: OccurrenceState,
    ): OccurrenceState {
        check(
            recurringSeries.value[state.key.taskIdentifier]
                ?.series
                ?.ownerIdentifier == ownerIdentifier,
        )
        occurrenceStates.value = occurrenceStates.value + (state.key to state)
        return state
    }

    override fun observeOccurrenceState(
        ownerIdentifier: MemberIdentifier,
        occurrenceKey: OccurrenceKey,
    ): Flow<OccurrenceState?> =
        occurrenceStates.map { stored ->
            stored[occurrenceKey]?.takeIf {
                recurringSeries.value[occurrenceKey.taskIdentifier]
                    ?.series
                    ?.ownerIdentifier == ownerIdentifier
            }
        }

    override fun observeOccurrenceStates(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
    ): Flow<List<OccurrenceState>> =
        occurrenceStates
            .map { stored ->
                stored.values.filter { state ->
                    state.key.taskIdentifier == taskIdentifier &&
                        recurringSeries.value[taskIdentifier]
                            ?.series
                            ?.ownerIdentifier == ownerIdentifier
                }
            }.onStart {
                activeHistoryObservers += 1
                observedHistoryTasks += taskIdentifier
            }.onCompletion { activeHistoryObservers -= 1 }
}

private object TasksTestReminderGateway : ReminderGateway {
    override suspend fun reconcile(reminder: ScheduledTaskReminder) = Unit
}

private class MutableTasksAuthenticationGateway(
    var identity: AuthenticatedIdentity?,
) : AuthenticationGateway {
    override suspend fun currentIdentity(): AuthenticatedIdentity? = identity

    override suspend fun signOut(): AuthenticationSignOut {
        identity = null
        return AuthenticationSignOut.Complete
    }
}

private object AlwaysHydratedTasksSessionGateway : PersistedWorkspaceSessionGateway {
    override suspend fun wasHydrated(memberIdentifier: MemberIdentifier): Boolean = true

    override suspend fun recordHydrated(memberIdentifier: MemberIdentifier) = Unit

    override suspend fun pendingSignOut(): MemberIdentifier? = null

    override suspend fun beginSignOut(memberIdentifier: MemberIdentifier) = Unit

    override suspend fun completeSignOut(memberIdentifier: MemberIdentifier) = Unit
}
