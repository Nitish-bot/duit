package com.gaylord.duit.workspace

import com.gaylord.duit.data.repository.TaskRepository
import com.gaylord.duit.domain.model.MemberActionMoment
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.OccurrenceKey
import com.gaylord.duit.domain.model.RecurringSeriesCreation
import com.gaylord.duit.domain.model.SeriesRevisionIdentifier
import com.gaylord.duit.domain.model.StandaloneTaskCreation
import com.gaylord.duit.domain.model.SubtaskIdentifier
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DuitWorkspaceTest {
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

private fun stableTaskIdentities(): TaskIdentityGenerator =
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

private class InMemoryTaskRepository : TaskRepository {
    private val creations =
        MutableStateFlow<Map<TaskIdentifier, StandaloneTaskCreation>>(emptyMap())
    private val recurringCreations =
        MutableStateFlow<Map<TaskIdentifier, RecurringSeriesCreation>>(emptyMap())

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
