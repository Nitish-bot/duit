package com.gaylord.duit.workspace

import com.gaylord.duit.domain.model.MemberActionMoment
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.OccurrenceKey
import com.gaylord.duit.domain.model.ShareGeneration
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.model.TaskVisibility
import com.gaylord.duit.domain.pairing.PairIdentifier
import com.gaylord.duit.domain.pairing.PairInvite
import com.gaylord.duit.domain.pairing.PairingStatus
import com.gaylord.duit.domain.pairing.PartnerIdentity
import com.gaylord.duit.domain.sharing.SharedDailyView
import com.gaylord.duit.domain.sharing.SharedViewReference
import com.gaylord.duit.notifications.ReminderGateway
import com.gaylord.duit.notifications.ScheduledTaskReminder
import com.gaylord.duit.pairing.PairingCallResult
import com.gaylord.duit.pairing.PairingGateway
import com.gaylord.duit.sharing.InMemorySharedViewGateway
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuitWorkspaceSharingTest {
    private val today = LocalDate.of(2026, 8, 9)
    private val pair = PairIdentifier("pair-1")
    private val alice = MemberIdentifier("member-alice")
    private val bob = MemberIdentifier("member-bob")
    private val taskIdentifier = TaskIdentifier("task-tea")
    private val key = OccurrenceKey(taskIdentifier, today)

    @Test
    fun `a new Daily Task is Private even while the Member is paired`() =
        runTest {
            val fixture = pairedFixture()

            fixture.alice.execute(AddTea)

            assertEquals(TaskVisibility.Private, fixture.visibilityOfTea())
            assertTrue(fixture.gateway.publishedViews.isEmpty())
        }

    @Test
    fun `an unpaired Member cannot share a Daily Task`() =
        runTest {
            val fixture = pairedFixture(status = PairingStatus.Unpaired)
            fixture.alice.execute(AddTea)

            val outcome = fixture.alice.executeWithOutcome(Share("share-1"))

            assertEquals(
                MemberCommandOutcome.SharingUnavailable(
                    taskIdentifier,
                    SharingUnavailableReason.NotPaired,
                ),
                outcome,
            )
            assertEquals(TaskVisibility.Private, fixture.visibilityOfTea())
            assertTrue(fixture.gateway.publishedViews.isEmpty())
        }

    @Test
    fun `sharing before Pairing status is known stays Private`() =
        runTest {
            val fixture = pairedFixture(refreshStatus = false)
            fixture.alice.execute(AddTea)

            val outcome = fixture.alice.executeWithOutcome(Share("share-1"))

            assertTrue(outcome is MemberCommandOutcome.SharingUnavailable)
            assertEquals(TaskVisibility.Private, fixture.visibilityOfTea())
        }

    @Test
    fun `sharing publishes a redacted view for the current Pair under a fresh generation`() =
        runTest {
            val fixture = pairedFixture()
            fixture.alice.execute(AddTea)

            val outcome = fixture.alice.executeWithOutcome(Share("share-1"))

            assertEquals(MemberCommandOutcome.TaskChanged(taskIdentifier), outcome)
            val reference =
                SharedViewReference(
                    ownerIdentifier = alice,
                    pairIdentifier = pair,
                    partnerIdentifier = bob,
                    taskIdentifier = taskIdentifier,
                    shareGeneration = ShareGeneration("share-1"),
                )
            assertEquals(
                TaskVisibility.Shared(pair, ShareGeneration("share-1"), bob),
                fixture.visibilityOfTea(),
            )
            assertEquals(
                listOf(
                    SharedDailyView(
                        reference = reference,
                        title = "Tea",
                        subtaskLabels = listOf("Boil water"),
                        everyDays = 1,
                        startsOn = today,
                        endsOnInclusive = null,
                        sharedOn = today,
                    ),
                ),
                fixture.gateway.publishedViews,
            )
        }

    @Test
    fun `completions are mirrored as dates from the grant onward and never earlier`() =
        runTest {
            val fixture = pairedFixture()
            fixture.alice.execute(AddTea)
            fixture.alice.execute(MemberCommand.CompleteOccurrence(CommandIdentifier("done-before"), key))
            val epoch = fixture.epochOf(key)
            fixture.alice.execute(MemberCommand.UndoOccurrenceCompletion(key, epoch))
            fixture.alice.execute(MemberCommand.CompleteOccurrence(CommandIdentifier("done-before-2"), key))

            fixture.alice.execute(Share("share-1"))

            assertEquals(emptySet<LocalDate>(), fixture.gateway.completedDates(taskIdentifier))

            fixture.alice.execute(
                MemberCommand.UndoOccurrenceCompletion(key, fixture.epochOf(key)),
            )
            fixture.alice.execute(MemberCommand.CompleteOccurrence(CommandIdentifier("done-1"), key))
            assertEquals(setOf(today), fixture.gateway.completedDates(taskIdentifier))

            fixture.alice.execute(
                MemberCommand.UndoOccurrenceCompletion(key, fixture.epochOf(key)),
            )
            assertEquals(emptySet<LocalDate>(), fixture.gateway.completedDates(taskIdentifier))
        }

    @Test
    fun `correcting a completion to actionable clears its date and a time correction adds no detail`() =
        runTest {
            val fixture = pairedFixture()
            fixture.alice.execute(AddTea)
            fixture.alice.execute(Share("share-1"))
            fixture.alice.execute(MemberCommand.CompleteOccurrence(CommandIdentifier("done-1"), key))
            val epoch = fixture.epochOf(key)

            fixture.alice.execute(
                MemberCommand.CorrectOccurrenceCompletion(
                    occurrenceKey = key,
                    completionEpoch = epoch,
                    correctedCompletedAt =
                        MemberActionMoment(
                            instant = Instant.parse("2026-08-09T05:00:00Z"),
                            localDate = today,
                            zoneId = ZoneId.of("UTC"),
                        ),
                    checkedSubtaskIdentifiers = emptySet(),
                ),
            )
            assertEquals(setOf(today), fixture.gateway.completedDates(taskIdentifier))

            fixture.alice.execute(
                MemberCommand.CorrectOccurrenceToActionable(key, epoch, emptySet()),
            )
            assertEquals(emptySet<LocalDate>(), fixture.gateway.completedDates(taskIdentifier))
        }

    @Test
    fun `a Private Task never reaches the Shared View store`() =
        runTest {
            val fixture = pairedFixture()
            fixture.alice.execute(AddTea)

            fixture.alice.execute(MemberCommand.CompleteOccurrence(CommandIdentifier("done-1"), key))

            assertTrue(fixture.gateway.publishedViews.isEmpty())
            assertEquals(emptySet<LocalDate>(), fixture.gateway.completedDates(taskIdentifier))
        }

    @Test
    fun `making a Task Private revokes immediately and is safe to repeat`() =
        runTest {
            val fixture = pairedFixture()
            fixture.alice.execute(AddTea)
            fixture.alice.execute(Share("share-1"))
            fixture.alice.execute(MemberCommand.CompleteOccurrence(CommandIdentifier("done-1"), key))

            fixture.alice.execute(MemberCommand.MakeTaskPrivate(taskIdentifier))
            fixture.alice.execute(MemberCommand.MakeTaskPrivate(taskIdentifier))

            assertEquals(TaskVisibility.Private, fixture.visibilityOfTea())
            assertTrue(fixture.gateway.publishedViews.isEmpty())
            assertEquals(emptySet<LocalDate>(), fixture.gateway.completedDates(taskIdentifier))

            fixture.alice.execute(MemberCommand.UndoOccurrenceCompletion(key, fixture.epochOf(key)))
            fixture.alice.execute(MemberCommand.CompleteOccurrence(CommandIdentifier("done-2"), key))
            assertEquals(emptySet<LocalDate>(), fixture.gateway.completedDates(taskIdentifier))
        }

    @Test
    fun `sharing again creates a new generation that does not restore earlier completions`() =
        runTest {
            val fixture = pairedFixture()
            fixture.alice.execute(AddTea)
            fixture.alice.execute(Share("share-1"))
            fixture.alice.execute(MemberCommand.CompleteOccurrence(CommandIdentifier("done-1"), key))
            fixture.alice.execute(MemberCommand.MakeTaskPrivate(taskIdentifier))

            fixture.alice.execute(Share("share-2"))

            assertEquals(
                TaskVisibility.Shared(pair, ShareGeneration("share-2"), bob),
                fixture.visibilityOfTea(),
            )
            assertEquals(
                listOf(ShareGeneration("share-2")),
                fixture.gateway.publishedViews.map { it.reference.shareGeneration },
            )
            assertEquals(emptySet<LocalDate>(), fixture.gateway.completedDates(taskIdentifier))
        }

    @Test
    fun `sharing a Task that is already Shared keeps its grant`() =
        runTest {
            val fixture = pairedFixture()
            fixture.alice.execute(AddTea)
            fixture.alice.execute(Share("share-1"))

            fixture.alice.execute(Share("share-2"))

            assertEquals(
                TaskVisibility.Shared(pair, ShareGeneration("share-1"), bob),
                fixture.visibilityOfTea(),
            )
        }

    @Test
    fun `the Partner reads the shared Task with its completions and cannot change it`() =
        runTest {
            val fixture = pairedFixture()
            fixture.alice.execute(AddTea)
            fixture.alice.execute(Share("share-1"))
            fixture.alice.execute(MemberCommand.CompleteOccurrence(CommandIdentifier("done-1"), key))

            val seen = fixture.bob.observePartnerSharedTasks().first()

            assertEquals(1, seen.size)
            assertEquals("Tea", seen.single().view.title)
            assertEquals(setOf(today), seen.single().completedDates)
            assertNull(fixture.bobTaskPersistence.observeRecurringSeries(bob, taskIdentifier).first())
        }

    @Test
    fun `a Member without an active Pair sees no Partner Shared Tasks`() =
        runTest {
            val fixture = pairedFixture()
            fixture.alice.execute(AddTea)
            fixture.alice.execute(Share("share-1"))

            val unpairedBob =
                workspaceFor(bob, PairingStatus.Unpaired, fixture.gateway, InMemoryTaskRepository())
            unpairedBob.execute(PairCommand.RefreshStatus)

            assertTrue(unpairedBob.observePartnerSharedTasks().first().isEmpty())
        }

    private class Fixture(
        val alice: DuitWorkspace,
        val bob: DuitWorkspace,
        val gateway: InMemorySharedViewGateway,
        val alicePersistence: InMemoryTaskRepository,
        val bobTaskPersistence: InMemoryTaskRepository,
        val aliceId: MemberIdentifier,
        val task: TaskIdentifier,
    ) {
        suspend fun visibilityOfTea(): TaskVisibility =
            alice.observeRecurringSeriesDetail(task).first()!!.visibility

        suspend fun epochOf(key: OccurrenceKey) =
            (
                alicePersistence.observeOccurrenceState(aliceId, key).first()!!.outcome
                    as com.gaylord.duit.domain.model.OccurrenceOutcome.Complete
            ).record.epoch
    }

    private suspend fun pairedFixture(
        status: PairingStatus? = null,
        refreshStatus: Boolean = true,
    ): Fixture {
        val gateway = InMemorySharedViewGateway()
        val alicePersistence = InMemoryTaskRepository()
        val bobPersistence = InMemoryTaskRepository()
        val aliceStatus =
            status ?: PairingStatus.Active(pair, PartnerIdentity("Bob", null), bob)
        val bobStatus = PairingStatus.Active(pair, PartnerIdentity("Alice", null), alice)
        val aliceWorkspace = workspaceFor(alice, aliceStatus, gateway, alicePersistence)
        val bobWorkspace = workspaceFor(bob, bobStatus, gateway, bobPersistence)
        if (refreshStatus) {
            aliceWorkspace.execute(PairCommand.RefreshStatus)
            bobWorkspace.execute(PairCommand.RefreshStatus)
        }
        return Fixture(aliceWorkspace, bobWorkspace, gateway, alicePersistence, bobPersistence, alice, taskIdentifier)
    }

    private fun workspaceFor(
        member: MemberIdentifier,
        status: PairingStatus,
        gateway: InMemorySharedViewGateway,
        persistence: InMemoryTaskRepository,
    ): DuitWorkspace =
        DuitWorkspace(
            memberIdentifier = member,
            clock = Clock.fixed(today.atTime(9, 0).atZone(ZoneId.of("UTC")).toInstant(), ZoneId.of("UTC")),
            zoneProvider = ZoneProvider { ZoneId.of("UTC") },
            taskIdentities = stableTaskIdentities(),
            persistence = persistence,
            reminders =
                object : ReminderGateway {
                    override suspend fun reconcile(reminder: ScheduledTaskReminder) = Unit
                },
            pairing = StaticStatusPairingGateway(status),
            sharedViews = gateway,
        )

    private companion object {
        val AddTea =
            MemberCommand.AddPrivateDailyTaskToToday(
                commandIdentifier = CommandIdentifier("tea"),
                title = "Tea",
                subtask = "Boil water",
            )

        fun Share(generation: String) =
            MemberCommand.ShareDailyTaskWithPartner(
                commandIdentifier = CommandIdentifier(generation),
                taskIdentifier = TaskIdentifier("task-tea"),
            )
    }
}

internal class StaticStatusPairingGateway(
    private val status: PairingStatus,
) : PairingGateway {
    override suspend fun getPairingStatus(): PairingCallResult<PairingStatus> =
        PairingCallResult.Success(status)

    override suspend fun createPairInvite(): PairingCallResult<PairInvite> = error("Not used")

    override suspend fun acceptPairInvite(
        code: String,
    ): PairingCallResult<PairingStatus.PendingConfirmation> = error("Not used")

    override suspend fun confirmPair(
        pairIdentifier: PairIdentifier,
    ): PairingCallResult<PairingStatus> = error("Not used")
}
