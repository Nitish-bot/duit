package com.gaylord.duit.workspace

import com.gaylord.duit.data.repository.TaskRepository
import com.gaylord.duit.domain.model.MemberActionMoment
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.StandaloneTaskCreation
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.model.TaskRevisionIdentifier
import com.gaylord.duit.domain.model.TaskVisibility
import com.gaylord.duit.notifications.ReminderGateway
import com.gaylord.duit.notifications.ScheduledTaskReminder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DuitWorkspaceTest {
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

private fun stableTaskIdentities(): TaskIdentityGenerator =
    TaskIdentityGenerator { commandIdentifier ->
        InitialTaskIdentifiers(
            taskIdentifier = TaskIdentifier("task-${commandIdentifier.value}"),
            revisionIdentifier = TaskRevisionIdentifier("revision-${commandIdentifier.value}"),
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

    override suspend fun putStandaloneTaskIfAbsent(
        creation: StandaloneTaskCreation,
    ): StandaloneTaskCreation {
        val taskIdentifier = creation.task.identifier
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
