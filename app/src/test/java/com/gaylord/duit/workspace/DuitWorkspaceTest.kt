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
import com.gaylord.duit.domain.model.StandaloneTaskCreation
import com.gaylord.duit.domain.model.SubtaskIdentifier
import com.gaylord.duit.domain.model.SubtaskTemplate
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.model.TaskRevisionIdentifier
import com.gaylord.duit.domain.model.TaskVisibility
import com.gaylord.duit.notifications.ReminderGateway
import com.gaylord.duit.notifications.ScheduledTaskReminder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DuitWorkspaceTest {
    @Test
    fun `interacted Occurrence projects its historical snapshot instead of the current Series Revision`() =
        runTest {
            val persistence = InMemoryTaskRepository()
            val ownerIdentifier = MemberIdentifier("member-1")
            val taskIdentifier = TaskIdentifier("task-tea")
            val date = LocalDate.of(2026, 8, 9)
            val recordedAt =
                MemberActionMoment(
                    instant = Instant.parse("2026-08-01T07:00:00Z"),
                    localDate = LocalDate.of(2026, 8, 1),
                    zoneId = ZoneId.of("UTC"),
                )
            val currentRevision =
                SeriesRevision(
                    identifier = SeriesRevisionIdentifier("revision-current"),
                    taskIdentifier = taskIdentifier,
                    title = "Current tea ritual",
                    subtaskTemplate =
                        listOf(SubtaskTemplate(SubtaskIdentifier("subtask-current"), "Use mint")),
                    recurrenceRule = RecurrenceRule.Daily(),
                    startsOn = date,
                    endsOnInclusive = null,
                    recordedAt = recordedAt,
                )
            persistence.putRecurringSeriesIfAbsent(
                RecurringSeriesCreation(
                    series =
                        RecurringSeries(
                            identifier = taskIdentifier,
                            ownerIdentifier = ownerIdentifier,
                            activeRevisionIdentifier = currentRevision.identifier,
                            visibility = TaskVisibility.Private,
                            createdAt = recordedAt,
                        ),
                    initialRevision = currentRevision,
                ),
            )
            val historicalRevision =
                currentRevision.copy(
                    identifier = SeriesRevisionIdentifier("revision-historical"),
                    title = "Historical tea ritual",
                    subtaskTemplate =
                        listOf(
                            SubtaskTemplate(
                                SubtaskIdentifier("subtask-historical"),
                                "Use ginger",
                            ),
                        ),
                    startsOn = date.minusDays(10),
                )
            val key = OccurrenceKey(taskIdentifier, date)
            persistence.putOccurrenceState(
                ownerIdentifier = ownerIdentifier,
                state =
                    OccurrenceState(
                        key = key,
                        snapshot =
                            OccurrenceSnapshot(
                                seriesRevision = historicalRevision,
                                nominalDate = date,
                                effectiveDate = date.plusDays(2),
                            ),
                        subtasks =
                            listOf(
                                OccurrenceSubtaskState(
                                    identifier = SubtaskIdentifier("subtask-historical"),
                                    isChecked = true,
                                ),
                            ),
                        outcome = null,
                    ),
            )

            val task =
                createWorkspace(
                    clock =
                        Clock.fixed(
                            date.atStartOfDay(ZoneId.of("UTC")).toInstant(),
                            ZoneId.of("UTC"),
                        ),
                    persistence = persistence,
                ).observePersonalAgenda().first().tasks.single()

            assertEquals("Historical tea ritual", task.title)
            assertEquals(
                SeriesRevisionIdentifier("revision-historical"),
                task.seriesRevisionIdentifier,
            )
            assertEquals(date, task.occurrence?.nominalDate)
            assertEquals(date.plusDays(2), task.occurrence?.effectiveDate)
            assertEquals(
                listOf(
                    AgendaSubtask(
                        identifier = SubtaskIdentifier("subtask-historical"),
                        label = "Use ginger",
                        isChecked = true,
                    ),
                ),
                task.occurrence?.subtasks,
            )
        }

    @Test
    fun `checking a Subtask persists the interacted actionable Occurrence before Complete`() =
        runTest {
            val persistence = InMemoryTaskRepository()
            val date = LocalDate.of(2026, 8, 9)
            val workspace =
                createWorkspace(
                    clock = Clock.fixed(
                        Instant.parse("2026-08-09T07:15:00Z"),
                        ZoneId.of("UTC"),
                    ),
                    persistence = persistence,
                )
            val taskIdentifier =
                workspace.execute(
                    MemberCommand.AddPrivateDailyTaskToToday(
                        commandIdentifier = CommandIdentifier("daily-herbs"),
                        title = "Water the herbs",
                        subtask = "Check the soil",
                    ),
                )
            val key = OccurrenceKey(taskIdentifier, date)
            val subtaskIdentifier = SubtaskIdentifier("subtask-task-daily-herbs")

            workspace.execute(
                MemberCommand.SetOccurrenceSubtaskChecked(
                    occurrenceKey = key,
                    subtaskIdentifier = subtaskIdentifier,
                    isChecked = true,
                ),
            )

            val reconstructed =
                createWorkspace(
                    clock = Clock.fixed(
                        Instant.parse("2026-08-09T08:00:00Z"),
                        ZoneId.of("UTC"),
                    ),
                    persistence = persistence,
                ).observePersonalAgenda().first()
            assertEquals(true, reconstructed.tasks.single().occurrence?.subtasks?.single()?.isChecked)
            assertEquals(null, reconstructed.tasks.single().occurrence?.outcome)
        }

    @Test
    fun `later outcome correction invalidates Complete and recompletion creates one new epoch`() =
        runTest {
            val date = LocalDate.of(2026, 8, 9)
            val clock = MutableClock(Instant.parse("2026-08-09T07:15:00Z"))
            val workspace = createWorkspace(clock = clock)
            val taskIdentifier =
                workspace.execute(
                    MemberCommand.AddPrivateDailyTaskToToday(
                        commandIdentifier = CommandIdentifier("daily-herbs"),
                        title = "Water the herbs",
                        subtask = "Check the soil",
                    ),
                )
            val key = OccurrenceKey(taskIdentifier, date)
            val firstCompletion =
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("epoch-first"),
                    occurrenceKey = key,
                    checkedSubtaskIdentifiers =
                        setOf(SubtaskIdentifier("subtask-task-daily-herbs")),
                )
            workspace.execute(firstCompletion)

            workspace.execute(
                MemberCommand.CorrectOccurrenceToActionable(
                    occurrenceKey = key,
                    completionEpoch = CompletionEpoch("epoch-first"),
                    checkedSubtaskIdentifiers = emptySet(),
                ),
            )
            workspace.execute(firstCompletion)
            val correctedAgenda = workspace.observePersonalAgenda().first()
            assertEquals(emptyList<AgendaTask>(), correctedAgenda.doneToday)
            assertEquals(null, correctedAgenda.tasks.single().occurrence?.outcome)
            assertEquals(false, correctedAgenda.tasks.single().occurrence?.subtasks?.single()?.isChecked)

            clock.advanceBy(Duration.ofMinutes(20))
            workspace.execute(
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("epoch-second"),
                    occurrenceKey = key,
                    checkedSubtaskIdentifiers = emptySet(),
                ),
            )
            workspace.execute(
                MemberCommand.UndoOccurrenceCompletion(
                    occurrenceKey = key,
                    completionEpoch = CompletionEpoch("epoch-first"),
                ),
            )

            val currentCompletion =
                (workspace.observePersonalAgenda().first().doneToday.single()
                    .occurrence?.outcome as OccurrenceOutcome.Complete).record
            assertEquals("epoch-second", currentCompletion.epoch.value)
        }

    @Test
    fun `reconstruction keeps one completed Occurrence and the next day starts unchecked`() =
        runTest {
            val persistence = InMemoryTaskRepository()
            val firstDate = LocalDate.of(2026, 8, 9)
            val firstWorkspace =
                createWorkspace(
                    clock = Clock.fixed(
                        Instant.parse("2026-08-09T07:15:00Z"),
                        ZoneId.of("UTC"),
                    ),
                    persistence = persistence,
                )
            val taskIdentifier =
                firstWorkspace.execute(
                    MemberCommand.AddPrivateDailyTaskToToday(
                        commandIdentifier = CommandIdentifier("daily-herbs"),
                        title = "Water the herbs",
                        subtask = "Check the soil",
                    ),
                )
            firstWorkspace.execute(
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("epoch-first"),
                    occurrenceKey = OccurrenceKey(taskIdentifier, firstDate),
                    checkedSubtaskIdentifiers =
                        setOf(SubtaskIdentifier("subtask-task-daily-herbs")),
                ),
            )

            val reconstructedToday =
                createWorkspace(
                    clock = Clock.fixed(
                        Instant.parse("2026-08-09T09:00:00Z"),
                        ZoneId.of("UTC"),
                    ),
                    persistence = persistence,
                ).observePersonalAgenda().first()
            val nextDay =
                createWorkspace(
                    clock = Clock.fixed(
                        Instant.parse("2026-08-10T09:00:00Z"),
                        ZoneId.of("UTC"),
                    ),
                    persistence = persistence,
                ).observePersonalAgenda().first()

            assertEquals(listOf("Water the herbs"), reconstructedToday.doneToday.map(AgendaTask::title))
            assertEquals(emptyList<AgendaTask>(), reconstructedToday.tasks)
            assertEquals(emptyList<AgendaTask>(), nextDay.doneToday)
            assertEquals(false, nextDay.tasks.single().occurrence?.subtasks?.single()?.isChecked)
            assertEquals(null, nextDay.tasks.single().occurrence?.outcome)
        }

    @Test
    fun `an older completion remains correctable from history without changing its snapshot`() =
        runTest {
            val persistence = InMemoryTaskRepository()
            val completedDate = LocalDate.of(2026, 8, 9)
            val firstWorkspace =
                createWorkspace(
                    clock = Clock.fixed(
                        Instant.parse("2026-08-09T07:15:00Z"),
                        ZoneId.of("UTC"),
                    ),
                    persistence = persistence,
                )
            val taskIdentifier =
                firstWorkspace.execute(
                    MemberCommand.AddPrivateDailyTaskToToday(
                        commandIdentifier = CommandIdentifier("daily-history"),
                        title = "Water the herbs",
                        subtask = "Check the soil",
                    ),
                )
            val occurrenceKey = OccurrenceKey(taskIdentifier, completedDate)
            firstWorkspace.execute(
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("epoch-first"),
                    occurrenceKey = occurrenceKey,
                    checkedSubtaskIdentifiers =
                        setOf(SubtaskIdentifier("subtask-task-daily-history")),
                ),
            )

            val nextDayWorkspace =
                createWorkspace(
                    clock = Clock.fixed(
                        Instant.parse("2026-08-10T07:15:00Z"),
                        ZoneId.of("UTC"),
                    ),
                    persistence = persistence,
                )
            val historicalTask = nextDayWorkspace.observePersonalHistory().first().completions.single()
            val historicalCompletion =
                (historicalTask.occurrence?.outcome as OccurrenceOutcome.Complete).record

            nextDayWorkspace.execute(
                MemberCommand.CorrectOccurrenceCompletion(
                    occurrenceKey = occurrenceKey,
                    completionEpoch = historicalCompletion.epoch,
                    correctedCompletedAt =
                        MemberActionMoment(
                            instant = Instant.parse("2026-08-09T08:05:00Z"),
                            localDate = completedDate,
                            zoneId = ZoneId.of("UTC"),
                        ),
                    checkedSubtaskIdentifiers = emptySet(),
                ),
            )

            val corrected =
                (nextDayWorkspace.observePersonalHistory().first().completions.single()
                    .occurrence?.outcome as OccurrenceOutcome.Complete).record
            assertEquals("Water the herbs", corrected.seriesRevision.title)
            assertEquals(completedDate, corrected.nominalDate)
            assertEquals(completedDate, corrected.effectiveDate)
            assertEquals(Instant.parse("2026-08-09T08:05:00Z"), corrected.completedAt.instant)
            assertEquals(false, corrected.subtasks.single().isChecked)
        }

    @Test
    fun `correcting old Complete to Actionable retains its snapshot for one truthful recompletion`() =
        runTest {
            val persistence = InMemoryTaskRepository()
            val completedDate = LocalDate.of(2026, 8, 9)
            val workspace =
                createWorkspace(
                    clock = Clock.fixed(
                        Instant.parse("2026-08-10T07:15:00Z"),
                        ZoneId.of("UTC"),
                    ),
                    persistence = persistence,
                )
            val creationWorkspace =
                createWorkspace(
                    clock = Clock.fixed(
                        Instant.parse("2026-08-09T07:15:00Z"),
                        ZoneId.of("UTC"),
                    ),
                    persistence = persistence,
                )
            val taskIdentifier =
                creationWorkspace.execute(
                    MemberCommand.AddPrivateDailyTaskToToday(
                        commandIdentifier = CommandIdentifier("daily-old"),
                        title = "Historical title",
                        subtask = "Historical Subtask",
                    ),
                )
            val key = OccurrenceKey(taskIdentifier, completedDate)
            creationWorkspace.execute(
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("epoch-first"),
                    occurrenceKey = key,
                ),
            )

            workspace.execute(
                MemberCommand.CorrectOccurrenceToActionable(
                    occurrenceKey = key,
                    completionEpoch = CompletionEpoch("epoch-first"),
                    checkedSubtaskIdentifiers = emptySet(),
                ),
            )
            assertEquals(emptyList<AgendaTask>(), workspace.observePersonalHistory().first().completions)
            workspace.execute(
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("epoch-second"),
                    occurrenceKey = key,
                ),
            )

            val recompleted =
                (workspace.observePersonalHistory().first().completions.single()
                    .occurrence?.outcome as OccurrenceOutcome.Complete).record
            assertEquals("epoch-second", recompleted.epoch.value)
            assertEquals("Historical title", recompleted.snapshot.seriesRevision.title)
            assertEquals(completedDate, recompleted.snapshot.nominalDate)
            assertEquals(completedDate, recompleted.snapshot.effectiveDate)
        }

    @Test
    fun `direct correction changes completion details without rewriting its snapshot dates or epoch`() =
        runTest {
            val date = LocalDate.of(2026, 8, 9)
            val workspace =
                createWorkspace(
                    clock = Clock.fixed(
                        Instant.parse("2026-08-09T07:15:00Z"),
                        ZoneId.of("UTC"),
                    ),
                )
            val taskIdentifier =
                workspace.execute(
                    MemberCommand.AddPrivateDailyTaskToToday(
                        commandIdentifier = CommandIdentifier("daily-herbs"),
                        title = "Water the herbs",
                        subtask = "Check the soil",
                    ),
                )
            val key = OccurrenceKey(taskIdentifier, date)
            val subtaskIdentifier = SubtaskIdentifier("subtask-task-daily-herbs")
            workspace.execute(
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("epoch-first"),
                    occurrenceKey = key,
                    checkedSubtaskIdentifiers = setOf(subtaskIdentifier),
                ),
            )

            workspace.execute(
                MemberCommand.CorrectOccurrenceCompletion(
                    occurrenceKey = key,
                    completionEpoch = CompletionEpoch("epoch-first"),
                    correctedCompletedAt =
                        MemberActionMoment(
                            instant = Instant.parse("2026-08-09T02:35:00Z"),
                            localDate = LocalDate.of(2026, 8, 9),
                            zoneId = ZoneId.of("Asia/Kolkata"),
                        ),
                    checkedSubtaskIdentifiers = emptySet(),
                ),
            )

            val corrected =
                (workspace.observePersonalAgenda().first().doneToday.single()
                    .occurrence?.outcome as OccurrenceOutcome.Complete).record
            assertEquals(
                CompletionObservation(
                    epoch = "epoch-first",
                    snapshotRevision = "revision-daily-herbs",
                    snapshotTitle = "Water the herbs",
                    nominalDate = date,
                    effectiveDate = date,
                    completedAt = Instant.parse("2026-08-09T02:35:00Z"),
                    completedLocalDate = date,
                    completedZone = ZoneId.of("Asia/Kolkata"),
                    checkedSubtasks = mapOf(subtaskIdentifier to false),
                ),
                corrected.toObservation(),
            )
        }

    @Test
    fun `stale Completion Epoch correction reports a no op`() =
        runTest {
            val date = LocalDate.of(2026, 8, 9)
            val workspace =
                createWorkspace(
                    clock = Clock.fixed(
                        Instant.parse("2026-08-09T07:15:00Z"),
                        ZoneId.of("UTC"),
                    ),
                )
            val taskIdentifier =
                workspace.execute(
                    MemberCommand.AddPrivateDailyTaskToToday(
                        commandIdentifier = CommandIdentifier("daily-herbs"),
                        title = "Water the herbs",
                        subtask = null,
                    ),
                )
            val key = OccurrenceKey(taskIdentifier, date)
            workspace.execute(
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("epoch-current"),
                    occurrenceKey = key,
                ),
            )

            val outcome =
                workspace.executeWithOutcome(
                    MemberCommand.CorrectOccurrenceToActionable(
                        occurrenceKey = key,
                        completionEpoch = CompletionEpoch("epoch-stale"),
                        checkedSubtaskIdentifiers = emptySet(),
                    ),
                )

            assertEquals(
                MemberCommandOutcome.OccurrenceCorrected(
                    taskIdentifier = taskIdentifier,
                    applied = false,
                ),
                outcome,
            )
            assertEquals(1, workspace.observePersonalAgenda().first().doneToday.size)
        }

    @Test
    fun `immediate Undo reopens only the Completion Epoch it was offered for`() =
        runTest {
            val date = LocalDate.of(2026, 8, 9)
            val workspace =
                createWorkspace(
                    clock = Clock.fixed(
                        Instant.parse("2026-08-09T07:15:00Z"),
                        ZoneId.of("UTC"),
                    ),
                )
            val taskIdentifier =
                workspace.execute(
                    MemberCommand.AddPrivateDailyTaskToToday(
                        commandIdentifier = CommandIdentifier("daily-herbs"),
                        title = "Water the herbs",
                        subtask = "Check the soil",
                    ),
                )
            val key = OccurrenceKey(taskIdentifier, date)
            val checkedSubtask = SubtaskIdentifier("subtask-task-daily-herbs")
            workspace.execute(
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("epoch-first"),
                    occurrenceKey = key,
                    checkedSubtaskIdentifiers = setOf(checkedSubtask),
                ),
            )

            workspace.execute(
                MemberCommand.UndoOccurrenceCompletion(
                    occurrenceKey = key,
                    completionEpoch = CompletionEpoch("epoch-first"),
                ),
            )

            val agenda = workspace.observePersonalAgenda().first()
            assertEquals(emptyList<AgendaTask>(), agenda.doneToday)
            assertEquals("Water the herbs", agenda.tasks.single().title)
            assertEquals(false, agenda.tasks.single().occurrence?.subtasks?.single()?.isChecked)
            assertEquals(null, agenda.tasks.single().occurrence?.outcome)
        }

    @Test
    fun `an Occurrence whose Nominal Date has not arrived cannot be completed`() =
        runTest {
            val today = LocalDate.of(2026, 8, 9)
            val workspace =
                createWorkspace(
                    clock = Clock.fixed(
                        Instant.parse("2026-08-09T07:15:00Z"),
                        ZoneId.of("UTC"),
                    ),
                )
            val taskIdentifier =
                workspace.execute(
                    MemberCommand.AddPrivateDailyTaskToToday(
                        commandIdentifier = CommandIdentifier("daily-herbs"),
                        title = "Water the herbs",
                    ),
                )

            try {
                workspace.execute(
                    MemberCommand.CompleteOccurrence(
                        commandIdentifier = CommandIdentifier("epoch-early"),
                        occurrenceKey = OccurrenceKey(taskIdentifier, today.plusDays(1)),
                    ),
                )
                fail("Expected a future Occurrence to be rejected")
            } catch (_: IllegalArgumentException) {
            }

            assertEquals(
                emptyList<AgendaTask>(),
                workspace.observePersonalHistory().first().completions,
            )
        }

    @Test
    fun `a completion time cannot be corrected into the future`() =
        runTest {
            val date = LocalDate.of(2026, 8, 9)
            val workspace =
                createWorkspace(
                    clock = Clock.fixed(
                        Instant.parse("2026-08-09T07:15:00Z"),
                        ZoneId.of("UTC"),
                    ),
                )
            val taskIdentifier =
                workspace.execute(
                    MemberCommand.AddPrivateDailyTaskToToday(
                        commandIdentifier = CommandIdentifier("daily-herbs"),
                        title = "Water the herbs",
                    ),
                )
            val key = OccurrenceKey(taskIdentifier, date)
            workspace.execute(
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("epoch-first"),
                    occurrenceKey = key,
                ),
            )

            try {
                workspace.execute(
                    MemberCommand.CorrectOccurrenceCompletion(
                        occurrenceKey = key,
                        completionEpoch = CompletionEpoch("epoch-first"),
                        correctedCompletedAt =
                            MemberActionMoment(
                                instant = Instant.parse("2026-08-09T23:30:00Z"),
                                localDate = date,
                                zoneId = ZoneId.of("UTC"),
                            ),
                        checkedSubtaskIdentifiers = emptySet(),
                    ),
                )
                fail("Expected a future completion time to be rejected")
            } catch (_: IllegalArgumentException) {
            }

            val completion =
                (workspace.observePersonalAgenda().first().doneToday.single()
                    .occurrence?.outcome as OccurrenceOutcome.Complete).record
            assertEquals(Instant.parse("2026-08-09T07:15:00Z"), completion.completedAt.instant)
        }

    @Test
    fun `Undo restores the prior actionable Subtask progress and leaves no completion history`() =
        runTest {
            val date = LocalDate.of(2026, 8, 9)
            val workspace =
                createWorkspace(
                    clock = Clock.fixed(
                        Instant.parse("2026-08-09T07:15:00Z"),
                        ZoneId.of("UTC"),
                    ),
                )
            val taskIdentifier =
                workspace.execute(
                    MemberCommand.AddPrivateDailyTaskToToday(
                        commandIdentifier = CommandIdentifier("daily-herbs"),
                        title = "Water the herbs",
                        subtask = "Check the soil",
                    ),
                )
            val key = OccurrenceKey(taskIdentifier, date)
            val subtaskIdentifier = SubtaskIdentifier("subtask-task-daily-herbs")
            workspace.execute(
                MemberCommand.SetOccurrenceSubtaskChecked(
                    occurrenceKey = key,
                    subtaskIdentifier = subtaskIdentifier,
                    isChecked = true,
                ),
            )
            workspace.execute(
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("epoch-first"),
                    occurrenceKey = key,
                    checkedSubtaskIdentifiers = emptySet(),
                ),
            )

            workspace.execute(
                MemberCommand.UndoOccurrenceCompletion(
                    occurrenceKey = key,
                    completionEpoch = CompletionEpoch("epoch-first"),
                ),
            )

            val agenda = workspace.observePersonalAgenda().first()
            assertEquals(emptyList<AgendaTask>(), agenda.doneToday)
            assertEquals(true, agenda.tasks.single().occurrence?.subtasks?.single()?.isChecked)
            assertEquals(null, agenda.tasks.single().occurrence?.outcome)
            assertEquals(
                emptyList<AgendaTask>(),
                workspace.observePersonalHistory().first().completions,
            )
        }

    @Test
    fun `reconstruction preserves one corrected completion and leaves the next day unchecked`() =
        runTest {
            val persistence = InMemoryTaskRepository()
            val completedDate = LocalDate.of(2026, 8, 9)
            val workspace =
                createWorkspace(
                    clock = Clock.fixed(
                        Instant.parse("2026-08-09T07:15:00Z"),
                        ZoneId.of("UTC"),
                    ),
                    persistence = persistence,
                )
            val taskIdentifier =
                workspace.execute(
                    MemberCommand.AddPrivateDailyTaskToToday(
                        commandIdentifier = CommandIdentifier("daily-herbs"),
                        title = "Water the herbs",
                        subtask = "Check the soil",
                    ),
                )
            val key = OccurrenceKey(taskIdentifier, completedDate)
            val subtaskIdentifier = SubtaskIdentifier("subtask-task-daily-herbs")
            workspace.execute(
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("epoch-first"),
                    occurrenceKey = key,
                    checkedSubtaskIdentifiers = emptySet(),
                ),
            )
            workspace.execute(
                MemberCommand.CorrectOccurrenceCompletion(
                    occurrenceKey = key,
                    completionEpoch = CompletionEpoch("epoch-first"),
                    correctedCompletedAt =
                        MemberActionMoment(
                            instant = Instant.parse("2026-08-09T06:05:00Z"),
                            localDate = completedDate,
                            zoneId = ZoneId.of("UTC"),
                        ),
                    checkedSubtaskIdentifiers = setOf(subtaskIdentifier),
                ),
            )

            val relaunched =
                createWorkspace(
                    clock = Clock.fixed(
                        Instant.parse("2026-08-10T09:00:00Z"),
                        ZoneId.of("UTC"),
                    ),
                    persistence = persistence,
                )

            val corrected =
                (relaunched.observePersonalHistory().first().completions.single()
                    .occurrence?.outcome as OccurrenceOutcome.Complete).record
            assertEquals(
                CompletionObservation(
                    epoch = "epoch-first",
                    snapshotRevision = "revision-daily-herbs",
                    snapshotTitle = "Water the herbs",
                    nominalDate = completedDate,
                    effectiveDate = completedDate,
                    completedAt = Instant.parse("2026-08-09T06:05:00Z"),
                    completedLocalDate = completedDate,
                    completedZone = ZoneId.of("UTC"),
                    checkedSubtasks = mapOf(subtaskIdentifier to true),
                ),
                corrected.toObservation(),
            )
            val nextDay = relaunched.observePersonalAgenda().first()
            assertEquals(emptyList<AgendaTask>(), nextDay.doneToday)
            assertEquals(false, nextDay.tasks.single().occurrence?.subtasks?.single()?.isChecked)
            assertEquals(null, nextDay.tasks.single().occurrence?.outcome)
        }

    @Test
    fun `Complete records the Daily Occurrence snapshot and moves it to Done today`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-08-09T06:30:00Z"))
            val date = LocalDate.of(2026, 8, 9)
            val workspace =
                createWorkspace(
                    clock = clock,
                    taskIdentities = stableTaskIdentities(),
                )
            val taskIdentifier =
                workspace.execute(
                    MemberCommand.AddPrivateDailyTaskToToday(
                        commandIdentifier = CommandIdentifier("daily-herbs"),
                        title = "Water the herbs",
                        subtask = "Check the soil",
                    ),
                )
            val occurrenceKey = OccurrenceKey(taskIdentifier, date)

            clock.advanceBy(Duration.ofMinutes(45))
            workspace.execute(
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("complete-herbs"),
                    occurrenceKey = occurrenceKey,
                    checkedSubtaskIdentifiers =
                        setOf(SubtaskIdentifier("subtask-task-daily-herbs")),
                ),
            )

            val agenda = workspace.observePersonalAgenda().first()
            val completion =
                (agenda.doneToday.single().occurrence?.outcome as OccurrenceOutcome.Complete)
                    .record
            assertEquals(emptyList<AgendaTask>(), agenda.tasks)
            assertEquals("Water the herbs", agenda.doneToday.single().title)
            assertEquals(
                CompletionObservation(
                    epoch = "complete-herbs",
                    snapshotRevision = "revision-daily-herbs",
                    snapshotTitle = "Water the herbs",
                    nominalDate = date,
                    effectiveDate = date,
                    completedAt = Instant.parse("2026-08-09T07:15:00Z"),
                    completedLocalDate = date,
                    completedZone = ZoneId.of("UTC"),
                    checkedSubtasks =
                        mapOf(SubtaskIdentifier("subtask-task-daily-herbs") to true),
                ),
                CompletionObservation(
                    epoch = completion.epoch.value,
                    snapshotRevision = completion.seriesRevision.identifier.value,
                    snapshotTitle = completion.seriesRevision.title,
                    nominalDate = completion.nominalDate,
                    effectiveDate = completion.effectiveDate,
                    completedAt = completion.completedAt.instant,
                    completedLocalDate = completion.completedAt.localDate,
                    completedZone = completion.completedAt.zoneId,
                    checkedSubtasks =
                        completion.subtasks.associate { subtask ->
                            subtask.identifier to subtask.isChecked
                        },
                ),
            )
        }

    @Test
    fun `Owner can add a private Daily Task with one Subtask and see today's Occurrence immediately`() =
        runTest {
            val date = LocalDate.of(2026, 8, 9)
            val workspace =
                createWorkspace(
                    clock =
                        Clock.fixed(
                            Instant.parse("2026-08-09T06:30:00Z"),
                            ZoneId.of("UTC"),
                        ),
                    taskIdentities = stableTaskIdentities(),
                )

            workspace.execute(
                MemberCommand.AddPrivateDailyTaskToToday(
                    commandIdentifier = CommandIdentifier("daily-herbs"),
                    title = "  Water the herbs  ",
                    subtask = "  Check the soil  ",
                ),
            )

            assertEquals(
                listOf(
                    AgendaTask(
                        identifier = TaskIdentifier("task-daily-herbs"),
                        revisionIdentifier = null,
                        seriesRevisionIdentifier =
                            SeriesRevisionIdentifier("revision-daily-herbs"),
                        title = "Water the herbs",
                        plannedDate = null,
                        visibility = TaskVisibility.Private,
                        reminderAt = null,
                        occurrence =
                            AgendaOccurrence(
                                key =
                                    OccurrenceKey(
                                        taskIdentifier = TaskIdentifier("task-daily-herbs"),
                                        nominalDate = date,
                                    ),
                                nominalDate = date,
                                effectiveDate = date,
                                subtasks =
                                    listOf(
                                        AgendaSubtask(
                                            identifier =
                                                SubtaskIdentifier(
                                                    "subtask-task-daily-herbs",
                                                ),
                                            label = "Check the soil",
                                            isChecked = false,
                                        ),
                                    ),
                            ),
                    ),
                ),
                workspace.observePersonalAgenda().first().tasks,
            )
        }

    @Test
    fun `an open Today receives exactly one fresh Occurrence when the local date advances`() =
        runTest {
            val firstDate = LocalDate.of(2026, 8, 9)
            val nextDate = firstDate.plusDays(1)
            val clock = MutableClock(Instant.parse("2026-08-09T23:59:59Z"))
            val workspace =
                createWorkspace(
                    clock = clock,
                    taskIdentities = stableTaskIdentities(),
                )
            workspace.execute(
                MemberCommand.AddPrivateDailyTaskToToday(
                    commandIdentifier = CommandIdentifier("daily-seedlings"),
                    title = "Water the seedlings",
                    subtask = "Check each tray",
                ),
            )
            val agendas = async { workspace.observePersonalAgenda().take(2).toList() }
            runCurrent()

            clock.advanceBy(Duration.ofSeconds(1))
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(
                listOf(
                    DailyAgendaObservation(
                        date = firstDate,
                        occurrenceKeys =
                            listOf(
                                OccurrenceKey(
                                    TaskIdentifier("task-daily-seedlings"),
                                    firstDate,
                                ),
                            ),
                        checkedSubtasks = listOf(false),
                    ),
                    DailyAgendaObservation(
                        date = nextDate,
                        occurrenceKeys =
                            listOf(
                                OccurrenceKey(
                                    TaskIdentifier("task-daily-seedlings"),
                                    nextDate,
                                ),
                            ),
                        checkedSubtasks = listOf(false),
                    ),
                ),
                agendas.await().map { agenda ->
                    DailyAgendaObservation(
                        date = agenda.date,
                        occurrenceKeys = agenda.tasks.mapNotNull { it.occurrence?.key },
                        checkedSubtasks =
                            agenda.tasks.flatMap { task ->
                                task.occurrence?.subtasks.orEmpty().map(AgendaSubtask::isChecked)
                            },
                    )
                },
            )
        }

    @Test
    fun `Daily Task reconstruction and command replay preserve one stable private Occurrence`() =
        runTest {
            val persistence = InMemoryTaskRepository()
            val date = LocalDate.of(2026, 8, 9)
            val moment = Instant.parse("2026-08-09T06:30:00Z")
            val command =
                MemberCommand.AddPrivateDailyTaskToToday(
                    commandIdentifier = CommandIdentifier("daily-private"),
                    title = "Water the herbs",
                    subtask = "Check the soil",
                )
            val ownerWorkspace =
                createWorkspace(
                    memberIdentifier = MemberIdentifier("member-owner"),
                    clock = Clock.fixed(moment, ZoneId.of("UTC")),
                    persistence = persistence,
                )
            val taskIdentifier = ownerWorkspace.execute(command)
            val reconstructedOwnerWorkspace =
                createWorkspace(
                    memberIdentifier = MemberIdentifier("member-owner"),
                    clock = Clock.fixed(moment.plusSeconds(60), ZoneId.of("UTC")),
                    persistence = persistence,
                )

            reconstructedOwnerWorkspace.execute(
                command.copy(
                    title = "A replay must not replace the Series",
                    subtask = null,
                ),
            )
            val ownerAgenda = reconstructedOwnerWorkspace.observePersonalAgenda().first()
            val otherMemberWorkspace =
                createWorkspace(
                    memberIdentifier = MemberIdentifier("member-other"),
                    clock = Clock.fixed(moment, ZoneId.of("UTC")),
                    persistence = persistence,
                )

            assertEquals(
                StableDailyObservation(
                    titles = listOf("Water the herbs"),
                    occurrenceKeys = listOf(OccurrenceKey(taskIdentifier, date)),
                    visibility = listOf(TaskVisibility.Private),
                    ownerIdentifier = MemberIdentifier("member-owner"),
                    otherMemberAgendaSize = 0,
                    otherMemberDetail = null,
                ),
                StableDailyObservation(
                    titles = ownerAgenda.tasks.map(AgendaTask::title),
                    occurrenceKeys = ownerAgenda.tasks.mapNotNull { it.occurrence?.key },
                    visibility = ownerAgenda.tasks.map(AgendaTask::visibility),
                    ownerIdentifier =
                        reconstructedOwnerWorkspace
                            .observeRecurringSeriesDetail(taskIdentifier)
                            .first()
                            ?.ownerIdentifier,
                    otherMemberAgendaSize =
                        otherMemberWorkspace.observePersonalAgenda().first().tasks.size,
                    otherMemberDetail =
                        otherMemberWorkspace
                            .observeRecurringSeriesDetail(taskIdentifier)
                            .first(),
                ),
            )
        }

    @Test
    fun `Daily Task rejects a blank title and treats a blank optional Subtask as absent`() =
        runTest {
            val workspace = createWorkspace()

            try {
                workspace.execute(
                    MemberCommand.AddPrivateDailyTaskToToday(
                        commandIdentifier = CommandIdentifier("blank-daily"),
                        title = "   ",
                    ),
                )
                fail("Expected a blank Daily Task title to be rejected")
            } catch (_: IllegalArgumentException) {
                Unit
            }
            workspace.execute(
                MemberCommand.AddPrivateDailyTaskToToday(
                    commandIdentifier = CommandIdentifier("daily-without-subtask"),
                    title = "  Water the herbs  ",
                    subtask = "   ",
                ),
            )

            assertEquals(
                DailyTitleObservation(
                    titles = listOf("Water the herbs"),
                    subtaskLabels = emptyList(),
                ),
                workspace.observePersonalAgenda().first().let { agenda ->
                    DailyTitleObservation(
                        titles = agenda.tasks.map(AgendaTask::title),
                        subtaskLabels =
                            agenda.tasks.flatMap { it.occurrence?.subtasks.orEmpty() }
                                .map(AgendaSubtask::label),
                    )
                },
            )
        }

    @Test
    fun `reusing a Standalone Task identity for a Daily Series fails without replacing it`() =
        runTest {
            val persistence = InMemoryTaskRepository()
            val workspace = createWorkspace(persistence = persistence)
            val commandIdentifier = CommandIdentifier("identity-collision")
            workspace.execute(
                MemberCommand.AddPrivateTaskToToday(
                    commandIdentifier = commandIdentifier,
                    title = "Keep the Standalone Task",
                ),
            )

            val failure =
                runCatching {
                    workspace.execute(
                        MemberCommand.AddPrivateDailyTaskToToday(
                            commandIdentifier = commandIdentifier,
                            title = "Do not overwrite it",
                        ),
                    )
                }.exceptionOrNull()

            assertEquals(IllegalStateException::class, failure?.javaClass?.kotlin)
            assertEquals(
                listOf("Keep the Standalone Task"),
                workspace.observePersonalAgenda().first().tasks.map(AgendaTask::title),
            )
        }

    @Test
    fun `Task title rejects blanks and normalizes harmless surrounding whitespace`() =
        runTest {
            val workspace = createWorkspace()

            try {
                workspace.execute(
                    MemberCommand.AddPrivateTaskToToday(
                        commandIdentifier = CommandIdentifier("blank-title"),
                        title = "   ",
                    ),
                )
                fail("Expected a blank Task title to be rejected")
            } catch (_: IllegalArgumentException) {
                Unit
            }

            workspace.execute(
                MemberCommand.AddPrivateTaskToToday(
                    commandIdentifier = CommandIdentifier("trimmed-title"),
                    title = "  Water the herbs  ",
                ),
            )

            assertEquals(
                listOf("Water the herbs"),
                workspace.observePersonalAgenda().first().tasks.map(AgendaTask::title),
            )
        }

    @Test
    fun `member can add a private Task to Today and see it after reconstruction`() = runTest {
        val persistence = InMemoryTaskRepository()
        val instant = Instant.parse("2026-08-07T20:00:00Z")
        val zone = ZoneId.of("Asia/Kolkata")
        val workspace =
            createWorkspace(
                clock = Clock.fixed(instant, ZoneId.of("UTC")),
                zoneProvider = { zone },
                taskIdentities = stableTaskIdentities(),
                persistence = persistence,
            )

        workspace.execute(
            MemberCommand.AddPrivateTaskToToday(
                commandIdentifier = CommandIdentifier("command-42"),
                title = "Water the balcony herbs",
            ),
        )
        val reconstructedWorkspace =
            createWorkspace(
                clock = Clock.fixed(instant, ZoneId.of("UTC")),
                zoneProvider = { zone },
                persistence = persistence,
            )

        assertEquals(
            PersonalAgenda(
                date = LocalDate.of(2026, 8, 8),
                tasks =
                    listOf(
                        AgendaTask(
                            identifier = TaskIdentifier("task-command-42"),
                            revisionIdentifier = TaskRevisionIdentifier("revision-command-42"),
                            title = "Water the balcony herbs",
                            plannedDate = LocalDate.of(2026, 8, 8),
                            visibility = TaskVisibility.Private,
                            reminderAt = null,
                        ),
                    ),
            ),
            reconstructedWorkspace.observePersonalAgenda().first(),
        )
    }

    @Test
    fun `Task Owner and initial Revision remain durable after reconstruction`() = runTest {
        val persistence = InMemoryTaskRepository()
        val instant = Instant.parse("2026-08-07T20:00:00Z")
        val zone = ZoneId.of("Asia/Kolkata")
        val workspace =
            createWorkspace(
                memberIdentifier = MemberIdentifier("member-alex"),
                clock = Clock.fixed(instant, ZoneId.of("UTC")),
                zoneProvider = { zone },
                taskIdentities = stableTaskIdentities(),
                persistence = persistence,
            )

        val taskIdentifier =
            workspace.execute(
                MemberCommand.AddPrivateTaskToToday(
                    commandIdentifier = CommandIdentifier("command-detail"),
                    title = "Water the balcony herbs",
                ),
            )
        val reconstructedWorkspace =
            createWorkspace(
                memberIdentifier = MemberIdentifier("member-alex"),
                clock = Clock.fixed(instant, ZoneId.of("UTC")),
                zoneProvider = { zone },
                persistence = persistence,
            )

        assertEquals(
            TaskDetail(
                identifier = TaskIdentifier("task-command-detail"),
                ownerIdentifier = MemberIdentifier("member-alex"),
                visibility = TaskVisibility.Private,
                createdAt =
                    MemberActionMoment(
                        instant = instant,
                        localDate = LocalDate.of(2026, 8, 8),
                        zoneId = zone,
                    ),
                initialRevision =
                    TaskRevisionDetail(
                        identifier = TaskRevisionIdentifier("revision-command-detail"),
                        title = "Water the balcony herbs",
                        plannedDate = LocalDate.of(2026, 8, 8),
                        effectiveDate = LocalDate.of(2026, 8, 8),
                        recordedAt =
                            MemberActionMoment(
                                instant = instant,
                                localDate = LocalDate.of(2026, 8, 8),
                                zoneId = zone,
                            ),
                    ),
                reminderAt = null,
            ),
            reconstructedWorkspace.observeTaskDetail(taskIdentifier).first(),
        )
    }

    @Test
    fun `member reminder uses the selected local wall time`() = runTest {
        val reminders = RecordingReminderGateway()
        val workspace =
            createWorkspace(
                clock = Clock.fixed(
                    Instant.parse("2026-08-07T08:00:00Z"),
                    ZoneId.of("UTC"),
                ),
                zoneProvider = { ZoneId.of("Asia/Kolkata") },
                taskIdentities = stableTaskIdentities(),
                persistence = InMemoryTaskRepository(),
                reminders = reminders,
            )

        workspace.execute(
            MemberCommand.AddPrivateTaskToToday(
                commandIdentifier = CommandIdentifier("command-7"),
                title = "Take the seed tray outside",
                reminderAt = LocalTime.of(17, 45),
            ),
        )

        assertEquals(
            listOf(
                ScheduledTaskReminder(
                    taskIdentifier = TaskIdentifier("task-command-7"),
                    date = LocalDate.of(2026, 8, 7),
                    time = LocalTime.of(17, 45),
                    zoneId = ZoneId.of("Asia/Kolkata"),
                    triggerAt = Instant.parse("2026-08-07T12:15:00Z"),
                ),
            ),
            reminders.scheduled,
        )
    }

    @Test
    fun `replaying the same Member command after reconstruction keeps one Task and reminder`() =
        runTest {
            val persistence = InMemoryTaskRepository()
            val reminders = RecordingReminderGateway()
            val instant = Instant.parse("2026-08-07T08:00:00Z")
            val zone = ZoneId.of("Asia/Kolkata")
            val command =
                MemberCommand.AddPrivateTaskToToday(
                    commandIdentifier = CommandIdentifier("command-9"),
                    title = "Take the seed tray outside",
                    reminderAt = LocalTime.of(17, 45),
                )
            val firstWorkspace =
                createWorkspace(
                    clock = Clock.fixed(instant, ZoneId.of("UTC")),
                    zoneProvider = { zone },
                    taskIdentities = stableTaskIdentities(),
                    persistence = persistence,
                    reminders = reminders,
                )

            firstWorkspace.execute(command)
            val reconstructedWorkspace =
                createWorkspace(
                    clock = Clock.fixed(instant.plusSeconds(60), ZoneId.of("UTC")),
                    zoneProvider = { zone },
                    taskIdentities = stableTaskIdentities(),
                    persistence = persistence,
                    reminders = reminders,
                )
            reconstructedWorkspace.execute(command)

            assertEquals(
                ReplayObservation(
                    agendaTaskIdentifiers = listOf(TaskIdentifier("task-command-9")),
                    scheduledTaskIdentifiers = listOf(TaskIdentifier("task-command-9")),
                ),
                ReplayObservation(
                    agendaTaskIdentifiers =
                        reconstructedWorkspace
                            .observePersonalAgenda()
                            .first()
                            .tasks
                            .map(AgendaTask::identifier),
                    scheduledTaskIdentifiers =
                        reminders.scheduled.map(ScheduledTaskReminder::taskIdentifier),
                ),
            )
        }
}

private data class ReplayObservation(
    val agendaTaskIdentifiers: List<TaskIdentifier>,
    val scheduledTaskIdentifiers: List<TaskIdentifier>,
)

private data class CompletionObservation(
    val epoch: String,
    val snapshotRevision: String,
    val snapshotTitle: String,
    val nominalDate: LocalDate,
    val effectiveDate: LocalDate,
    val completedAt: Instant,
    val completedLocalDate: LocalDate,
    val completedZone: ZoneId,
    val checkedSubtasks: Map<SubtaskIdentifier, Boolean>,
)

private fun CompletionRecord.toObservation(): CompletionObservation =
    CompletionObservation(
        epoch = epoch.value,
        snapshotRevision = seriesRevision.identifier.value,
        snapshotTitle = seriesRevision.title,
        nominalDate = nominalDate,
        effectiveDate = effectiveDate,
        completedAt = completedAt.instant,
        completedLocalDate = completedAt.localDate,
        completedZone = completedAt.zoneId,
        checkedSubtasks = subtasks.associate { subtask -> subtask.identifier to subtask.isChecked },
    )

private data class DailyAgendaObservation(
    val date: LocalDate,
    val occurrenceKeys: List<OccurrenceKey>,
    val checkedSubtasks: List<Boolean>,
)

private data class StableDailyObservation(
    val titles: List<String>,
    val occurrenceKeys: List<OccurrenceKey>,
    val visibility: List<TaskVisibility>,
    val ownerIdentifier: MemberIdentifier?,
    val otherMemberAgendaSize: Int,
    val otherMemberDetail: RecurringSeriesDetail?,
)

private data class DailyTitleObservation(
    val titles: List<String>,
    val subtaskLabels: List<String>,
)

internal fun stableTaskIdentities(): TaskIdentityGenerator =
    TaskIdentityGenerator { commandIdentifier ->
        InitialTaskIdentifiers(
            taskIdentifier = TaskIdentifier("task-${commandIdentifier.value}"),
            revisionIdentifier = TaskRevisionIdentifier("revision-${commandIdentifier.value}"),
            seriesRevisionIdentifier =
                SeriesRevisionIdentifier("revision-${commandIdentifier.value}"),
        )
    }

private fun createWorkspace(
    memberIdentifier: MemberIdentifier = MemberIdentifier("member-1"),
    clock: Clock = Clock.systemUTC(),
    zoneProvider: ZoneProvider = ZoneProvider { ZoneId.of("UTC") },
    taskIdentities: TaskIdentityGenerator = stableTaskIdentities(),
    persistence: TaskRepository = InMemoryTaskRepository(),
    reminders: ReminderGateway = UnusedReminderGateway,
): DuitWorkspace =
    DuitWorkspace(
        memberIdentifier = memberIdentifier,
        clock = clock,
        zoneProvider = zoneProvider,
        taskIdentities = taskIdentities,
        persistence = persistence,
        reminders = reminders,
    )

internal class InMemoryTaskRepository : TaskRepository {
    private val creations =
        MutableStateFlow<Map<TaskIdentifier, StandaloneTaskCreation>>(emptyMap())
    private val recurringCreations =
        MutableStateFlow<Map<TaskIdentifier, RecurringSeriesCreation>>(emptyMap())
    private val occurrenceStates =
        MutableStateFlow<Map<OccurrenceKey, OccurrenceState>>(emptyMap())

    override suspend fun putStandaloneTaskIfAbsent(
        creation: StandaloneTaskCreation,
    ): StandaloneTaskCreation {
        val taskIdentifier = creation.task.identifier
        check(taskIdentifier !in recurringCreations.value) {
            "Task identity ${taskIdentifier.value} already belongs to a Recurring Series"
        }
        val existing = creations.value[taskIdentifier]
        if (existing != null) return existing

        creations.value = creations.value + (taskIdentifier to creation)
        return creation
    }

    override fun observeStandaloneTasks(
        ownerIdentifier: MemberIdentifier,
        plannedDate: LocalDate,
    ): Flow<List<StandaloneTaskCreation>> =
        creations.map { stored ->
            stored.values.filter { creation ->
                creation.task.ownerIdentifier == ownerIdentifier &&
                    creation.initialRevision.plannedDate == plannedDate
            }
        }

    override fun observeStandaloneTask(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
    ): Flow<StandaloneTaskCreation?> =
        creations.map { stored ->
            stored[taskIdentifier]?.takeIf { creation ->
                creation.task.ownerIdentifier == ownerIdentifier
            }
        }

    override suspend fun putRecurringSeriesIfAbsent(
        creation: RecurringSeriesCreation,
    ): RecurringSeriesCreation {
        val taskIdentifier = creation.series.identifier
        check(taskIdentifier !in creations.value) {
            "Task identity ${taskIdentifier.value} already belongs to a Standalone Task"
        }
        val existing = recurringCreations.value[taskIdentifier]
        if (existing != null) return existing

        recurringCreations.value = recurringCreations.value + (taskIdentifier to creation)
        return creation
    }

    override suspend fun putRecurringSeriesVisibility(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
        visibility: TaskVisibility,
    ): RecurringSeriesCreation? {
        val existing =
            recurringCreations.value[taskIdentifier]
                ?.takeIf { it.series.ownerIdentifier == ownerIdentifier }
                ?: return null
        val updated = existing.copy(series = existing.series.copy(visibility = visibility))
        recurringCreations.value = recurringCreations.value + (taskIdentifier to updated)
        return updated
    }

    override fun observeRecurringSeries(
        ownerIdentifier: MemberIdentifier,
    ): Flow<List<RecurringSeriesCreation>> =
        recurringCreations.map { stored ->
            stored.values.filter { creation ->
                creation.series.ownerIdentifier == ownerIdentifier
            }
        }

    override fun observeRecurringSeries(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
    ): Flow<RecurringSeriesCreation?> =
        recurringCreations.map { stored ->
            stored[taskIdentifier]?.takeIf { creation ->
                creation.series.ownerIdentifier == ownerIdentifier
            }
        }

    override suspend fun putOccurrenceState(
        ownerIdentifier: MemberIdentifier,
        state: OccurrenceState,
    ): OccurrenceState {
        check(
            recurringCreations.value[state.key.taskIdentifier]
                ?.series
                ?.ownerIdentifier == ownerIdentifier,
        ) { "Occurrence State owner does not own its Recurring Series" }
        occurrenceStates.value = occurrenceStates.value + (state.key to state)
        return state
    }

    override fun observeOccurrenceState(
        ownerIdentifier: MemberIdentifier,
        occurrenceKey: OccurrenceKey,
    ): Flow<OccurrenceState?> =
        occurrenceStates.map { stored ->
            stored[occurrenceKey]?.takeIf {
                recurringCreations.value[occurrenceKey.taskIdentifier]
                    ?.series
                    ?.ownerIdentifier == ownerIdentifier
            }
        }

    override fun observeOccurrenceStates(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
    ): Flow<List<OccurrenceState>> =
        occurrenceStates.map { stored ->
            if (
                recurringCreations.value[taskIdentifier]
                    ?.series
                    ?.ownerIdentifier != ownerIdentifier
            ) {
                emptyList()
            } else {
                stored.values.filter { it.key.taskIdentifier == taskIdentifier }
            }
        }
}

private object UnusedReminderGateway : ReminderGateway {
    override suspend fun reconcile(reminder: ScheduledTaskReminder): Unit =
        error("Not used by this test")
}

private class RecordingReminderGateway : ReminderGateway {
    private val remindersByTask = linkedMapOf<TaskIdentifier, ScheduledTaskReminder>()

    val scheduled: List<ScheduledTaskReminder>
        get() = remindersByTask.values.toList()

    override suspend fun reconcile(reminder: ScheduledTaskReminder) {
        remindersByTask[reminder.taskIdentifier] = reminder
    }
}

private class MutableClock(
    private var currentInstant: Instant,
    private val clockZone: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun getZone(): ZoneId = clockZone

    override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)

    override fun instant(): Instant = currentInstant

    fun advanceBy(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
    }
}
