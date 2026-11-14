package com.gaylord.duit.feature.today

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.gaylord.duit.data.repository.TaskRepository
import com.gaylord.duit.domain.model.CompletionEpoch
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
import com.gaylord.duit.workspace.DuitWorkspace
import com.gaylord.duit.workspace.InitialTaskIdentifiers
import com.gaylord.duit.workspace.TaskIdentityGenerator
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {
    @Test
    fun `adding publishes the Agenda and a keyed form-clear signal`() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val viewModelStore = ViewModelStore()
        try {
            val viewModel =
                ViewModelProvider(
                    viewModelStore,
                    TodayViewModel.Factory(createWorkspace()),
                )[TodayViewModel::class.java]
            viewModel.onEvent(
                TodayEvent.AddPrivateTaskToToday(
                    commandIdentifier = CommandIdentifier("plant-basil"),
                    title = "Plant basil",
                ),
            )
            runCurrent()

            assertEquals(false, viewModel.state.value.isCommandPending)
            assertEquals(
                CommandIdentifier("plant-basil"),
                viewModel.state.value.taskAdditionSuccess?.commandIdentifier,
            )
            assertEquals(
                TodayObservation(
                    date = LocalDate.of(2026, 8, 12),
                    taskTitles = listOf("Plant basil"),
                    doneTitles = emptyList(),
                ),
                viewModel.state.value.personalAgenda.toObservation(),
            )
        } finally {
            viewModelStore.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `pending command state survives Activity recreation through the ViewModel`() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val viewModelStore = ViewModelStore()
        val persistence = TodayTestTaskRepository(standalonePutGate = CompletableDeferred())
        try {
            val factory = TodayViewModel.Factory(createWorkspace(persistence))
            val beforeRecreation =
                ViewModelProvider(viewModelStore, factory)[TodayViewModel::class.java]

            beforeRecreation.onEvent(
                TodayEvent.AddPrivateTaskToToday(
                    commandIdentifier = CommandIdentifier("plant-basil"),
                    title = "Plant basil",
                ),
            )
            runCurrent()
            assertEquals(true, beforeRecreation.state.value.isCommandPending)

            val afterRecreation =
                ViewModelProvider(viewModelStore, factory)[TodayViewModel::class.java]
            assertEquals(true, afterRecreation.state.value.isCommandPending)

            persistence.standalonePutGate?.complete(Unit)
            runCurrent()
            assertEquals(false, afterRecreation.state.value.isCommandPending)
            assertEquals(
                CommandIdentifier("plant-basil"),
                afterRecreation.state.value.taskAdditionSuccess?.commandIdentifier,
            )
        } finally {
            viewModelStore.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `completion feedback uses persisted epoch expires and duplicate Complete invents none`() =
        runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val viewModelStore = ViewModelStore()
        try {
            val viewModel =
                ViewModelProvider(
                    viewModelStore,
                    TodayViewModel.Factory(createWorkspace()),
                )[TodayViewModel::class.java]
            viewModel.onEvent(
                TodayEvent.AddPrivateDailyTaskToToday(
                    commandIdentifier = CommandIdentifier("water-herbs"),
                    title = "Water the herbs",
                    subtask = "Check the soil",
                ),
            )
            runCurrent()
            val occurrenceKey =
                requireNotNull(viewModel.state.value.personalAgenda.tasks.single().occurrence).key
            viewModel.onEvent(
                TodayEvent.Occurrence(
                    TodayOccurrenceAction.Complete(
                        commandIdentifier = CommandIdentifier("completion-1"),
                        occurrenceKey = occurrenceKey,
                        checkedSubtaskIdentifiers =
                            setOf(SubtaskIdentifier("subtask-task-water-herbs")),
                    ),
                ),
            )
            runCurrent()

            val completed = viewModel.state.value.personalAgenda.doneToday.single()
            val completion =
                (completed.occurrence?.outcome as OccurrenceOutcome.Complete).record
            assertEquals("completion-1", completion.epoch.value)
            assertEquals(
                CompletionEpoch("completion-1"),
                viewModel.state.value.completionFeedback?.completionEpoch,
            )

            advanceTimeBy(CompletionUndoWindowMillis)
            runCurrent()
            assertEquals(null, viewModel.state.value.completionFeedback)

            viewModel.onEvent(
                TodayEvent.Occurrence(
                    TodayOccurrenceAction.Complete(
                        commandIdentifier = CommandIdentifier("invented-duplicate-epoch"),
                        occurrenceKey = occurrenceKey,
                        checkedSubtaskIdentifiers = emptySet(),
                    ),
                ),
            )
            runCurrent()

            assertEquals(null, viewModel.state.value.completionFeedback)
            val stillPersisted =
                (viewModel.state.value.personalAgenda.doneToday.single()
                    .occurrence?.outcome as OccurrenceOutcome.Complete).record
            assertEquals(CompletionEpoch("completion-1"), stillPersisted.epoch)
        } finally {
            viewModelStore.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
        }

    @Test
    fun `Undo clears matching feedback and restores the actionable occurrence`() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val viewModelStore = ViewModelStore()
        try {
            val viewModel =
                ViewModelProvider(
                    viewModelStore,
                    TodayViewModel.Factory(createWorkspace()),
                )[TodayViewModel::class.java]
            viewModel.onEvent(
                TodayEvent.AddPrivateDailyTaskToToday(
                    commandIdentifier = CommandIdentifier("water-herbs"),
                    title = "Water the herbs",
                    subtask = "Check the soil",
                ),
            )
            runCurrent()
            val occurrenceKey =
                requireNotNull(viewModel.state.value.personalAgenda.tasks.single().occurrence).key
            viewModel.onEvent(
                TodayEvent.Occurrence(
                    TodayOccurrenceAction.Complete(
                        commandIdentifier = CommandIdentifier("completion-1"),
                        occurrenceKey = occurrenceKey,
                        checkedSubtaskIdentifiers = emptySet(),
                    ),
                ),
            )
            runCurrent()
            val epoch = requireNotNull(viewModel.state.value.completionFeedback).completionEpoch

            viewModel.onEvent(
                TodayEvent.Occurrence(
                    TodayOccurrenceAction.Undo(
                        occurrenceKey = occurrenceKey,
                        completionEpoch = epoch,
                    ),
                ),
            )
            runCurrent()

            assertEquals(null, viewModel.state.value.completionFeedback)
            assertEquals(listOf("Water the herbs"), viewModel.state.value.personalAgenda.tasks.map { it.title })
            assertEquals(emptyList<String>(), viewModel.state.value.personalAgenda.doneToday)
        } finally {
            viewModelStore.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `Today applies Subtask progress and direct completion corrections through typed events`() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)
            val viewModelStore = ViewModelStore()
            try {
                val viewModel =
                    ViewModelProvider(
                        viewModelStore,
                        TodayViewModel.Factory(createWorkspace()),
                    )[TodayViewModel::class.java]
                viewModel.onEvent(
                    TodayEvent.AddPrivateDailyTaskToToday(
                        commandIdentifier = CommandIdentifier("water-herbs"),
                        title = "Water the herbs",
                        subtask = "Check the soil",
                    ),
                )
                runCurrent()
                val occurrenceKey =
                    requireNotNull(
                        viewModel.state.value.personalAgenda.tasks.single().occurrence,
                    ).key
                val subtaskIdentifier = SubtaskIdentifier("subtask-task-water-herbs")

                viewModel.onEvent(
                    TodayEvent.Occurrence(
                        TodayOccurrenceAction.SetSubtaskChecked(
                            occurrenceKey = occurrenceKey,
                            subtaskIdentifier = subtaskIdentifier,
                            isChecked = true,
                        ),
                    ),
                )
                runCurrent()
                assertEquals(
                    true,
                    viewModel.state.value.personalAgenda.tasks.single()
                        .occurrence?.subtasks?.single()?.isChecked,
                )

                viewModel.onEvent(
                    TodayEvent.Occurrence(
                        TodayOccurrenceAction.Complete(
                            commandIdentifier = CommandIdentifier("completion-1"),
                            occurrenceKey = occurrenceKey,
                            checkedSubtaskIdentifiers = setOf(subtaskIdentifier),
                        ),
                    ),
                )
                runCurrent()
                val completion =
                    (viewModel.state.value.personalAgenda.doneToday.single()
                        .occurrence?.outcome as OccurrenceOutcome.Complete).record
                val correctedMoment =
                    MemberActionMoment(
                        instant = Instant.parse("2026-08-12T06:45:00Z"),
                        localDate = LocalDate.of(2026, 8, 12),
                        zoneId = ZoneId.of("UTC"),
                    )

                val correctionKeyBefore = viewModel.state.value.correctionSuccessKey
                viewModel.onEvent(
                    TodayEvent.Occurrence(
                        TodayOccurrenceAction.CorrectCompletion(
                            occurrenceKey = occurrenceKey,
                            completionEpoch = completion.epoch,
                            correctedCompletedAt = correctedMoment,
                            checkedSubtaskIdentifiers = emptySet(),
                        ),
                    ),
                )
                runCurrent()
                val firstCorrectionKey = viewModel.state.value.correctionSuccessKey
                assertEquals(true, firstCorrectionKey > correctionKeyBefore)
                val corrected =
                    (viewModel.state.value.personalAgenda.doneToday.single()
                        .occurrence?.outcome as OccurrenceOutcome.Complete).record
                assertEquals(correctedMoment, corrected.completedAt)
                assertEquals(false, corrected.subtasks.single().isChecked)

                viewModel.onEvent(
                    TodayEvent.Occurrence(
                        TodayOccurrenceAction.CorrectToActionable(
                            occurrenceKey = occurrenceKey,
                            completionEpoch = completion.epoch,
                            checkedSubtaskIdentifiers = setOf(subtaskIdentifier),
                        ),
                    ),
                )
                runCurrent()

                assertEquals(firstCorrectionKey + 1, viewModel.state.value.correctionSuccessKey)
                assertEquals(emptyList<String>(), viewModel.state.value.personalAgenda.doneToday)
                assertEquals(
                    true,
                    viewModel.state.value.personalAgenda.tasks.single()
                        .occurrence?.subtasks?.single()?.isChecked,
                )
            } finally {
                viewModelStore.clear()
                runCurrent()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `stale correction does not advance success and asks the screen to review latest state`() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)
            val viewModelStore = ViewModelStore()
            try {
                val viewModel =
                    ViewModelProvider(
                        viewModelStore,
                        TodayViewModel.Factory(createWorkspace()),
                    )[TodayViewModel::class.java]
                viewModel.onEvent(
                    TodayEvent.AddPrivateDailyTaskToToday(
                        commandIdentifier = CommandIdentifier("water-herbs"),
                        title = "Water the herbs",
                        subtask = "Check the soil",
                    ),
                )
                runCurrent()
                val occurrenceKey =
                    requireNotNull(viewModel.state.value.personalAgenda.tasks.single().occurrence).key
                viewModel.onEvent(
                    TodayEvent.Occurrence(
                        TodayOccurrenceAction.Complete(
                            commandIdentifier = CommandIdentifier("completion-current"),
                            occurrenceKey = occurrenceKey,
                            checkedSubtaskIdentifiers = emptySet(),
                        ),
                    ),
                )
                runCurrent()
                val effect = async { viewModel.effects.first() }

                viewModel.onEvent(
                    TodayEvent.Occurrence(
                        TodayOccurrenceAction.CorrectToActionable(
                            occurrenceKey = occurrenceKey,
                            completionEpoch = CompletionEpoch("stale"),
                            checkedSubtaskIdentifiers = emptySet(),
                        ),
                    ),
                )
                runCurrent()

                assertEquals(0L, viewModel.state.value.correctionSuccessKey)
                assertEquals(TodayEffect.CorrectionBecameStale, effect.await())
                assertEquals(1, viewModel.state.value.personalAgenda.doneToday.size)
            } finally {
                viewModelStore.clear()
                runCurrent()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `a rejected Today command clears pending state and emits failure`() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val viewModelStore = ViewModelStore()
        try {
            val viewModel =
                ViewModelProvider(
                    viewModelStore,
                    TodayViewModel.Factory(createWorkspace()),
                )[TodayViewModel::class.java]
            val effect = async { viewModel.effects.first() }
            runCurrent()
            viewModel.onEvent(
                TodayEvent.AddPrivateTaskToToday(
                    commandIdentifier = CommandIdentifier("blank-title"),
                    title = "   ",
                ),
            )
            runCurrent()

            assertEquals(false, viewModel.state.value.isCommandPending)
            assertEquals(TodayEffect.WorkspaceActionFailed, effect.await())
            assertEquals(emptyList<String>(), viewModel.state.value.personalAgenda.tasks)
        } finally {
            viewModelStore.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}

private data class TodayObservation(
    val date: LocalDate,
    val taskTitles: List<String>,
    val doneTitles: List<String>,
)

private fun com.gaylord.duit.workspace.PersonalAgenda.toObservation(): TodayObservation =
    TodayObservation(
        date = date,
        taskTitles = tasks.map { it.title },
        doneTitles = doneToday.map { it.title },
    )

private fun createWorkspace(
    persistence: TaskRepository = TodayTestTaskRepository(),
): DuitWorkspace =
    DuitWorkspace(
        memberIdentifier = MemberIdentifier("member-1"),
        clock = Clock.fixed(
            Instant.parse("2026-08-12T07:15:00Z"),
            ZoneId.of("UTC"),
        ),
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
        persistence = persistence,
        reminders = TodayTestReminderGateway,
    )

private class TodayTestTaskRepository(
    val standalonePutGate: CompletableDeferred<Unit>? = null,
) : TaskRepository {
    private val standaloneTasks =
        MutableStateFlow<Map<TaskIdentifier, StandaloneTaskCreation>>(emptyMap())
    private val recurringSeries =
        MutableStateFlow<Map<TaskIdentifier, RecurringSeriesCreation>>(emptyMap())
    private val occurrenceStates =
        MutableStateFlow<Map<OccurrenceKey, OccurrenceState>>(emptyMap())

    override suspend fun putStandaloneTaskIfAbsent(
        creation: StandaloneTaskCreation,
    ): StandaloneTaskCreation {
        standalonePutGate?.await()
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

    override fun observeRecurringSeries(
        ownerIdentifier: MemberIdentifier,
    ): Flow<List<RecurringSeriesCreation>> =
        recurringSeries.map { stored ->
            stored.values.filter { it.series.ownerIdentifier == ownerIdentifier }
        }

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
        occurrenceStates.map { stored ->
            stored.values.filter { state ->
                state.key.taskIdentifier == taskIdentifier &&
                    recurringSeries.value[taskIdentifier]
                        ?.series
                        ?.ownerIdentifier == ownerIdentifier
            }
        }
}

private object TodayTestReminderGateway : ReminderGateway {
    override suspend fun reconcile(reminder: ScheduledTaskReminder) = Unit
}
