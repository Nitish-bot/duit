package com.gaylord.duit.platform.firebase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import com.gaylord.duit.notifications.ReminderGateway
import com.gaylord.duit.notifications.ScheduledTaskReminder
import com.gaylord.duit.workspace.AuthenticationSignOut
import com.gaylord.duit.workspace.AuthenticationSignOutFailure
import com.gaylord.duit.workspace.CommandIdentifier
import com.gaylord.duit.workspace.DuitWorkspace
import com.gaylord.duit.workspace.InitialTaskIdentifiers
import com.gaylord.duit.workspace.MemberCommand
import com.gaylord.duit.workspace.OwnerWorkspaceHydration
import com.gaylord.duit.workspace.TaskIdentityGenerator
import com.gaylord.duit.workspace.ZoneProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import java.time.Instant
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirestoreTaskRepositoryEmulatorTest {
    private val repository: TaskRepository =
        FirestoreTaskRepository(firestoreProvider = { firestore })

    @Before
    fun requireRepositoryEmulatorOptIn() {
        assumeTrue(
            "Run this conformance class through npm run test:android-repository",
            repositoryEmulatorIsEnabled(),
        )
    }

    @Test
    fun completedOccurrenceStateIsImmediateOfflineAndSurvivesCleanServerHydration() =
        runRepositoryTest {
        val ownerIdentifier = MemberIdentifier("member-${UUID.randomUUID()}")
        val creation = dailyCreation(ownerIdentifier)
        val state = completedOccurrenceState(creation)

        firestore.disableNetwork().await()
        try {
            repository.putRecurringSeriesIfAbsent(creation)
            assertEquals(
                state,
                repository.putOccurrenceState(ownerIdentifier, state),
            )

            firestore.terminate().await()
            firestore = connectFirestoreToEmulator(firebaseApp)
            firestore.disableNetwork().await()
            assertEquals(
                state,
                withTimeout(RepositoryObservationTimeoutMillis) {
                    repository.observeOccurrenceState(ownerIdentifier, state.key)
                        .filterNotNull()
                        .first()
                },
            )
            assertEquals(
                listOf(state),
                withTimeout(RepositoryObservationTimeoutMillis) {
                    repository.observeOccurrenceStates(ownerIdentifier, state.key.taskIdentifier)
                        .first()
                },
            )
        } finally {
            firestore.enableNetwork().await()
        }
        withTimeout(ServerSynchronizationTimeoutMillis) {
            firestore.waitForPendingWrites().await()
        }

        val occurrenceStates =
            firestore.collection("users")
                .document(ownerIdentifier.value)
                .collection("tasks")
                .document(creation.series.identifier.value)
                .collection("occurrenceStates")
                .get(Source.SERVER)
                .await()
        assertEquals(listOf(state.key.nominalDate.toString()), occurrenceStates.documents.map { it.id })

        firestore.terminate().await()
        firestore.clearPersistence().await()
        firestore = connectFirestoreToEmulator(firebaseApp)
        assertEquals(
            OwnerWorkspaceHydration.Complete,
            FirestoreOwnerWorkspaceHydrationGateway { firestore }
                .hydrate(ownerIdentifier),
        )
        assertEquals(
            state,
            withTimeout(RepositoryObservationTimeoutMillis) {
                repository.observeOccurrenceState(ownerIdentifier, state.key)
                    .filterNotNull()
                    .first()
            },
        )
    }

    @Test
    fun occurrenceCommandsStaySingularThroughOfflineRestartAndCleanHydration() =
        runRepositoryTest {
        val ownerIdentifier = MemberIdentifier("member-${UUID.randomUUID()}")
        val date = LocalDate.of(2026, 8, 9)
        val taskIdentifier = TaskIdentifier("task-${UUID.randomUUID()}")
        val subtaskIdentifier = SubtaskIdentifier("subtask-${UUID.randomUUID()}")
        val completionMoment = Instant.parse("2026-08-09T07:15:00Z")
        val correctedMoment = Instant.parse("2026-08-09T07:05:00Z")
        val workspace =
            occurrenceWorkspace(
                ownerIdentifier = ownerIdentifier,
                instant = completionMoment,
                taskIdentifier = taskIdentifier,
                subtaskIdentifier = subtaskIdentifier,
            )
        val key = OccurrenceKey(taskIdentifier, date)

        firestore.disableNetwork().await()
        try {
            workspace.execute(
                MemberCommand.AddPrivateDailyTaskToToday(
                    commandIdentifier = CommandIdentifier("create-${UUID.randomUUID()}"),
                    title = "Water the basil",
                    subtask = "Check the soil",
                ),
            )
            workspace.execute(
                MemberCommand.SetOccurrenceSubtaskChecked(
                    occurrenceKey = key,
                    subtaskIdentifier = subtaskIdentifier,
                    isChecked = true,
                ),
            )
            workspace.execute(
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("completion-first"),
                    occurrenceKey = key,
                    checkedSubtaskIdentifiers = setOf(subtaskIdentifier),
                ),
            )
            workspace.execute(
                MemberCommand.UndoOccurrenceCompletion(
                    occurrenceKey = key,
                    completionEpoch = CompletionEpoch("completion-first"),
                ),
            )
            workspace.execute(
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("completion-second"),
                    occurrenceKey = key,
                    checkedSubtaskIdentifiers = setOf(subtaskIdentifier),
                ),
            )
            workspace.execute(
                MemberCommand.CorrectOccurrenceToActionable(
                    occurrenceKey = key,
                    completionEpoch = CompletionEpoch("completion-second"),
                    checkedSubtaskIdentifiers = emptySet(),
                ),
            )
            workspace.execute(
                MemberCommand.CompleteOccurrence(
                    commandIdentifier = CommandIdentifier("completion-third"),
                    occurrenceKey = key,
                ),
            )
            workspace.execute(
                MemberCommand.CorrectOccurrenceCompletion(
                    occurrenceKey = key,
                    completionEpoch = CompletionEpoch("completion-third"),
                    correctedCompletedAt =
                        MemberActionMoment(
                            instant = correctedMoment,
                            localDate = date,
                            zoneId = ZoneId.of("UTC"),
                        ),
                    checkedSubtaskIdentifiers = setOf(subtaskIdentifier),
                ),
            )
            workspace.execute(
                MemberCommand.UndoOccurrenceCompletion(
                    occurrenceKey = key,
                    completionEpoch = CompletionEpoch("completion-first"),
                ),
            )

            assertCorrectedOccurrence(
                workspace = workspace,
                expectedKey = key,
                expectedMoment = correctedMoment,
                expectedSubtaskIdentifier = subtaskIdentifier,
            )

            firestore.terminate().await()
            firestore = connectFirestoreToEmulator(firebaseApp)
            firestore.disableNetwork().await()
            assertCorrectedOccurrence(
                workspace =
                    occurrenceWorkspace(
                        ownerIdentifier = ownerIdentifier,
                        instant = completionMoment,
                        taskIdentifier = taskIdentifier,
                        subtaskIdentifier = subtaskIdentifier,
                    ),
                expectedKey = key,
                expectedMoment = correctedMoment,
                expectedSubtaskIdentifier = subtaskIdentifier,
            )
        } finally {
            firestore.enableNetwork().await()
        }
        withTimeout(ServerSynchronizationTimeoutMillis) {
            firestore.waitForPendingWrites().await()
        }
        val serverStates =
            firestore.collection("users")
                .document(ownerIdentifier.value)
                .collection("tasks")
                .document(taskIdentifier.value)
                .collection("occurrenceStates")
                .get(Source.SERVER)
                .await()
        assertEquals(listOf(date.toString()), serverStates.documents.map { it.id })

        firestore.terminate().await()
        firestore.clearPersistence().await()
        firestore = connectFirestoreToEmulator(firebaseApp)
        assertEquals(
            OwnerWorkspaceHydration.Complete,
            FirestoreOwnerWorkspaceHydrationGateway { firestore }
                .hydrate(ownerIdentifier),
        )
        assertCorrectedOccurrence(
            workspace =
                occurrenceWorkspace(
                    ownerIdentifier = ownerIdentifier,
                    instant = completionMoment,
                    taskIdentifier = taskIdentifier,
                    subtaskIdentifier = subtaskIdentifier,
                ),
            expectedKey = key,
            expectedMoment = correctedMoment,
            expectedSubtaskIdentifier = subtaskIdentifier,
        )

        val nextDayAgenda =
            occurrenceWorkspace(
                ownerIdentifier = ownerIdentifier,
                instant = Instant.parse("2026-08-10T07:15:00Z"),
                taskIdentifier = taskIdentifier,
                subtaskIdentifier = subtaskIdentifier,
            ).observePersonalAgenda().first()
        assertEquals(false, nextDayAgenda.tasks.single().occurrence?.subtasks?.single()?.isChecked)
    }

    private fun occurrenceWorkspace(
        ownerIdentifier: MemberIdentifier,
        instant: Instant,
        taskIdentifier: TaskIdentifier,
        subtaskIdentifier: SubtaskIdentifier,
    ): DuitWorkspace =
        DuitWorkspace(
            memberIdentifier = ownerIdentifier,
            clock = Clock.fixed(instant, ZoneId.of("UTC")),
            zoneProvider = ZoneProvider { ZoneId.of("UTC") },
            taskIdentities =
                TaskIdentityGenerator {
                    InitialTaskIdentifiers(
                        taskIdentifier = taskIdentifier,
                        revisionIdentifier =
                            TaskRevisionIdentifier("standalone-${taskIdentifier.value}"),
                        seriesRevisionIdentifier =
                            SeriesRevisionIdentifier("series-${taskIdentifier.value}"),
                        initialSubtaskIdentifier = subtaskIdentifier,
                    )
                },
            persistence = repository,
            reminders = NoOpReminderGateway,
        )

    private suspend fun assertCorrectedOccurrence(
        workspace: DuitWorkspace,
        expectedKey: OccurrenceKey,
        expectedMoment: Instant,
        expectedSubtaskIdentifier: SubtaskIdentifier,
    ) {
        val completionTask = workspace.observePersonalHistory().first().completions.single()
        val completion =
            (completionTask.occurrence?.outcome as OccurrenceOutcome.Complete).record

        assertEquals(expectedKey, requireNotNull(completionTask.occurrence).key)
        assertEquals("completion-third", completion.epoch.value)
        assertEquals(expectedMoment, completion.completedAt.instant)
        assertEquals(
            listOf(OccurrenceSubtaskState(expectedSubtaskIdentifier, isChecked = true)),
            completion.subtasks,
        )
    }

    @Test
    fun offlineCreationSurvivesRepositoryReconstructionAndReplaysAtTheOwnerPaths() =
        runRepositoryTest {
        val ownerIdentifier = MemberIdentifier("member-${UUID.randomUUID()}")
        val creation = creation(ownerIdentifier)
        val replay =
            creation.copy(
                initialRevision = creation.initialRevision.copy(title = "Do not replace me"),
            )

        firestore.disableNetwork().await()
        try {
            assertEquals(creation, repository.putStandaloneTaskIfAbsent(creation))

            firestore.terminate().await()
            firestore = connectFirestoreToEmulator(firebaseApp)
            firestore.disableNetwork().await()

            assertEquals(
                creation,
                withTimeout(RepositoryObservationTimeoutMillis) {
                    repository.observeStandaloneTask(
                        ownerIdentifier = ownerIdentifier,
                        taskIdentifier = creation.task.identifier,
                    ).filterNotNull().first()
                },
            )
            assertEquals(
                listOf(creation),
                withTimeout(RepositoryObservationTimeoutMillis) {
                    repository.observeStandaloneTasks(
                        ownerIdentifier = ownerIdentifier,
                        plannedDate = creation.initialRevision.plannedDate,
                    ).first { tasks -> tasks.any { it.task.identifier == creation.task.identifier } }
                },
            )
            assertEquals(creation, repository.putStandaloneTaskIfAbsent(replay))
        } finally {
            firestore.enableNetwork().await()
        }

        withTimeout(ServerSynchronizationTimeoutMillis) {
            firestore.waitForPendingWrites().await()
        }
        val owner =
            firestore.collection("users")
                .document(ownerIdentifier.value)
        val taskSnapshot =
            owner.collection("tasks")
                .document(creation.task.identifier.value)
                .get(Source.SERVER)
                .await()
        val revisionSnapshot =
            owner.collection("tasks")
                .document(creation.task.identifier.value)
                .collection("revisions")
                .document(creation.initialRevision.identifier.value)
                .get(Source.SERVER)
                .await()
        val reminderSnapshot =
            owner.collection("taskReminders")
                .document(creation.task.identifier.value)
                .get(Source.SERVER)
                .await()

        assertTrue(taskSnapshot.exists())
        assertTrue(revisionSnapshot.exists())
        assertTrue(reminderSnapshot.exists())
        assertEquals(creation.task.ownerIdentifier.value, taskSnapshot.getString("ownerUid"))
        assertEquals(creation.initialRevision.title, revisionSnapshot.getString("title"))
        assertEquals(creation.reminder?.time.toString(), reminderSnapshot.getString("localTime"))
    }

    @Test
    fun offlineDailySeriesSurvivesReconstructionAndSynchronizesWithoutReplacement() =
        runRepositoryTest {
        val ownerIdentifier = MemberIdentifier("member-${UUID.randomUUID()}")
        val creation = dailyCreation(ownerIdentifier)
        val replay =
            creation.copy(
                initialRevision =
                    creation.initialRevision.copy(title = "Do not replace the Daily Series"),
            )

        firestore.disableNetwork().await()
        try {
            assertEquals(creation, repository.putRecurringSeriesIfAbsent(creation))

            firestore.terminate().await()
            firestore = connectFirestoreToEmulator(firebaseApp)
            firestore.disableNetwork().await()

            assertEquals(
                creation,
                withTimeout(RepositoryObservationTimeoutMillis) {
                    repository.observeRecurringSeries(
                        ownerIdentifier = ownerIdentifier,
                        taskIdentifier = creation.series.identifier,
                    ).filterNotNull().first()
                },
            )
            assertEquals(
                listOf(creation),
                withTimeout(RepositoryObservationTimeoutMillis) {
                    repository.observeRecurringSeries(ownerIdentifier)
                        .first { series ->
                            series.any { it.series.identifier == creation.series.identifier }
                        }
                },
            )
            assertEquals(creation, repository.putRecurringSeriesIfAbsent(replay))
        } finally {
            firestore.enableNetwork().await()
        }

        withTimeout(ServerSynchronizationTimeoutMillis) {
            firestore.waitForPendingWrites().await()
        }

        firestore.terminate().await()
        firestore.clearPersistence().await()
        firestore = connectFirestoreToEmulator(firebaseApp)
        assertEquals(
            OwnerWorkspaceHydration.Complete,
            FirestoreOwnerWorkspaceHydrationGateway { firestore }
                .hydrate(ownerIdentifier),
        )
        assertEquals(
            creation,
            withTimeout(RepositoryObservationTimeoutMillis) {
                repository.observeRecurringSeries(
                    ownerIdentifier = ownerIdentifier,
                    taskIdentifier = creation.series.identifier,
                ).filterNotNull().first()
            },
        )
    }

    @Test
    fun cachedStandaloneTaskIdentityCannotBeOverwrittenByADailySeries() =
        runRepositoryTest {
        val ownerIdentifier = MemberIdentifier("member-${UUID.randomUUID()}")
        val standalone = creation(ownerIdentifier)
        val daily = dailyCreation(ownerIdentifier)
        val collidingDaily =
            daily.copy(
                series = daily.series.copy(identifier = standalone.task.identifier),
                initialRevision =
                    daily.initialRevision.copy(taskIdentifier = standalone.task.identifier),
            )

        firestore.disableNetwork().await()
        try {
            repository.putStandaloneTaskIfAbsent(standalone)

            val failure =
                runCatching {
                    repository.putRecurringSeriesIfAbsent(collidingDaily)
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(
                standalone,
                withTimeout(RepositoryObservationTimeoutMillis) {
                    repository.observeStandaloneTask(
                        ownerIdentifier = ownerIdentifier,
                        taskIdentifier = standalone.task.identifier,
                    ).filterNotNull().first()
                },
            )
        } finally {
            firestore.enableNetwork().await()
        }
        withTimeout(ServerSynchronizationTimeoutMillis) {
            firestore.waitForPendingWrites().await()
        }
        Unit
    }

    @Test
    fun fractionalFirestoreIntegerIsRejectedThroughTheRepository() =
        runRepositoryTest {
        val ownerIdentifier = MemberIdentifier("member-${UUID.randomUUID()}")
        val taskIdentifier = TaskIdentifier("task-${UUID.randomUUID()}")
        val revisionIdentifier = TaskRevisionIdentifier("revision-${UUID.randomUUID()}")
        val plannedDate = LocalDate.parse("2026-08-09")
        val owner =
            firestore.collection("users")
                .document(ownerIdentifier.value)
        val task = owner.collection("tasks").document(taskIdentifier.value)
        val revision =
            task.collection("revisions")
                .document(revisionIdentifier.value)
        val write = firestore.batch()
        write.set(
            task,
            mapOf(
                "schemaVersion" to 1L,
                "kind" to "STANDALONE",
                "taskId" to taskIdentifier.value,
                "ownerUid" to ownerIdentifier.value,
                "activeRevisionId" to revisionIdentifier.value,
                "visibility" to "PRIVATE",
                "createdAtInstantMillis" to 1.5,
                "createdAtLocalDate" to plannedDate.toString(),
                "createdAtZoneId" to "UTC",
                "plannedDate" to plannedDate.toString(),
                "hasReminder" to false,
            ),
        )
        write.set(
            revision,
            mapOf(
                "schemaVersion" to 1L,
                "revisionId" to revisionIdentifier.value,
                "taskId" to taskIdentifier.value,
                "title" to "Malformed Task",
                "plannedDate" to plannedDate.toString(),
                "effectiveDate" to plannedDate.toString(),
                "recordedAtInstantMillis" to 0L,
                "recordedAtLocalDate" to plannedDate.toString(),
                "recordedAtZoneId" to "UTC",
            ),
        )
        withTimeout(ServerSynchronizationTimeoutMillis) {
            write.commit().await()
        }

        val failure =
            runCatching {
                withTimeout(RepositoryObservationTimeoutMillis) {
                    repository.observeStandaloneTask(
                        ownerIdentifier = ownerIdentifier,
                        taskIdentifier = taskIdentifier,
                    ).filterNotNull().first()
                }
            }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertNotNull(failure)
        assertTrue(failure?.message?.contains("must be an integer") == true)
    }

    @Test
    fun signOutWithNoPendingWritesStillRequiresTheServer() =
        runRepositoryTest {
        val firestoreSession = FirebaseFirestoreSession(firebaseApp)
        firestore.disableNetwork().await()
        try {
            assertEquals(
                AuthenticationSignOut.Failed(
                    AuthenticationSignOutFailure.ServerUnavailable,
                ),
                withTimeout(RepositoryObservationTimeoutMillis) {
                    firestoreSession.clearForSignOut(
                        authenticatedUidRequiringOnlineGate = "member-with-no-pending-writes",
                    )
                },
            )
        } finally {
            firestore.enableNetwork().await()
        }
    }

    private fun creation(ownerIdentifier: MemberIdentifier): StandaloneTaskCreation {
        val taskIdentifier = TaskIdentifier("task-${UUID.randomUUID()}")
        val revisionIdentifier = TaskRevisionIdentifier("revision-${UUID.randomUUID()}")
        val moment =
            MemberActionMoment(
                instant = Instant.parse("2026-08-09T01:35:30.123Z"),
                localDate = LocalDate.parse("2026-08-09"),
                zoneId = ZoneId.of("Asia/Kolkata"),
            )

        return StandaloneTaskCreation(
            task =
                StandaloneTask(
                    identifier = taskIdentifier,
                    ownerIdentifier = ownerIdentifier,
                    activeRevisionIdentifier = revisionIdentifier,
                    visibility = TaskVisibility.Private,
                    createdAt = moment,
                ),
            initialRevision =
                TaskRevision(
                    identifier = revisionIdentifier,
                    taskIdentifier = taskIdentifier,
                    title = "Water the basil",
                    plannedDate = moment.localDate,
                    effectiveDate = moment.localDate,
                    recordedAt = moment,
                ),
            reminder =
                TaskReminderConfiguration(
                    taskIdentifier = taskIdentifier,
                    date = moment.localDate,
                    time = LocalTime.of(7, 5, 30),
                    zoneId = moment.zoneId,
                ),
        )
    }

    private fun dailyCreation(ownerIdentifier: MemberIdentifier): RecurringSeriesCreation {
        val taskIdentifier = TaskIdentifier("task-${UUID.randomUUID()}")
        val revisionIdentifier = SeriesRevisionIdentifier("revision-${UUID.randomUUID()}")
        val moment =
            MemberActionMoment(
                instant = Instant.parse("2026-08-09T01:35:30.123Z"),
                localDate = LocalDate.parse("2026-08-09"),
                zoneId = ZoneId.of("Asia/Kolkata"),
            )

        return RecurringSeriesCreation(
            series =
                RecurringSeries(
                    identifier = taskIdentifier,
                    ownerIdentifier = ownerIdentifier,
                    activeRevisionIdentifier = revisionIdentifier,
                    visibility = TaskVisibility.Private,
                    createdAt = moment,
                ),
            initialRevision =
                SeriesRevision(
                    identifier = revisionIdentifier,
                    taskIdentifier = taskIdentifier,
                    title = "Water the basil",
                    subtaskTemplate =
                        listOf(
                            SubtaskTemplate(
                                identifier = SubtaskIdentifier("subtask-soil"),
                                label = "Check the soil",
                            ),
                        ),
                    recurrenceRule = RecurrenceRule.Daily(everyDays = 1),
                    startsOn = moment.localDate,
                    endsOnInclusive = null,
                    recordedAt = moment,
                ),
        )
    }

    private fun completedOccurrenceState(
        creation: RecurringSeriesCreation,
    ): OccurrenceState {
        val key = OccurrenceKey(creation.series.identifier, creation.initialRevision.startsOn)
        val completedSubtasks =
            creation.initialRevision.subtaskTemplate.map { template ->
                OccurrenceSubtaskState(template.identifier, isChecked = true)
            }
        val actionableSubtasks =
            completedSubtasks.map { subtask -> subtask.copy(isChecked = false) }
        val record =
            CompletionRecord(
                epoch = CompletionEpoch("completion-${UUID.randomUUID()}"),
                snapshot =
                    OccurrenceSnapshot(
                        seriesRevision = creation.initialRevision,
                        nominalDate = key.nominalDate,
                        effectiveDate = key.nominalDate,
                    ),
                completedAt =
                    MemberActionMoment(
                        instant = Instant.parse("2026-08-09T02:15:00.456Z"),
                        localDate = LocalDate.parse("2026-08-09"),
                        zoneId = ZoneId.of("Asia/Kolkata"),
                    ),
                subtasks = completedSubtasks,
            )
        return OccurrenceState(
            key = key,
            snapshot = record.snapshot,
            subtasks = completedSubtasks,
            outcome = OccurrenceOutcome.Complete(record),
            actionableSubtasksBeforeCompletion = actionableSubtasks,
            invalidatedCompletionEpochs = setOf(CompletionEpoch("invalidated-earlier-epoch")),
        )
    }

    companion object {
        private lateinit var firebaseApp: FirebaseApp
        private lateinit var firestore: FirebaseFirestore

        @JvmStatic
        @BeforeClass
        fun connectToFirestoreEmulator() {
            if (!repositoryEmulatorIsEnabled()) return
            val context = ApplicationProvider.getApplicationContext<Context>()
            val options =
                FirebaseOptions.Builder()
                    .setApplicationId("1:1234567890:android:duit-repository-test")
                    .setApiKey("duit-repository-test-api-key")
                    .setProjectId(AndroidRepositoryTestProjectIdentifier)
                    .build()
            firebaseApp =
                FirebaseApp.initializeApp(
                    context,
                    options,
                    "duit-repository-test-${UUID.randomUUID()}",
                )
            firestore = connectFirestoreToEmulator(firebaseApp)
        }

        @JvmStatic
        @AfterClass
        fun disconnectFromFirestoreEmulator() = runBlocking {
            if (!::firestore.isInitialized) return@runBlocking
            withTimeoutOrNull(FirestoreCleanupTimeoutMillis) {
                firestore.terminate().await()
                firestore.clearPersistence().await()
            }
            firebaseApp.delete()
        }

        private fun repositoryEmulatorIsEnabled(): Boolean =
            InstrumentationRegistry.getArguments()
                .getString(RunRepositoryEmulatorArgument) == "true"
    }
}

private fun runRepositoryTest(block: suspend () -> Unit) =
    runBlocking {
        withTimeout(AndroidRepositoryTestTimeoutMillis) {
            block()
        }
    }

private object NoOpReminderGateway : ReminderGateway {
    override suspend fun reconcile(reminder: ScheduledTaskReminder) = Unit
}

private fun connectFirestoreToEmulator(firebaseApp: FirebaseApp): FirebaseFirestore =
    FirebaseFirestore.getInstance(firebaseApp).also { firestore ->
        firestore.useEmulator(repositoryEmulatorHost(), AndroidRepositoryTestFirestorePort)
    }

private fun repositoryEmulatorHost(): String =
    InstrumentationRegistry.getArguments()
        .getString(RepositoryEmulatorHostArgument)
        ?.takeIf(String::isNotBlank)
        ?: AndroidDeviceLoopbackHost

private const val AndroidRepositoryTestProjectIdentifier = "demo-duit-android-repository"
private const val RunRepositoryEmulatorArgument = "runFirestoreRepositoryEmulator"
private const val RepositoryEmulatorHostArgument = "firestoreRepositoryEmulatorHost"
private const val AndroidDeviceLoopbackHost = "127.0.0.1"
private const val AndroidRepositoryTestFirestorePort = 8081
private const val RepositoryObservationTimeoutMillis = 10_000L
private const val ServerSynchronizationTimeoutMillis = 20_000L
private const val FirestoreCleanupTimeoutMillis = 5_000L
private const val AndroidRepositoryTestTimeoutMillis = 60_000L
