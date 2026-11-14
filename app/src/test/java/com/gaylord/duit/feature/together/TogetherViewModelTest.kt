package com.gaylord.duit.feature.together

import com.gaylord.duit.data.repository.TaskRepository
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.OccurrenceKey
import com.gaylord.duit.domain.model.OccurrenceState
import com.gaylord.duit.domain.model.RecurringSeriesCreation
import com.gaylord.duit.domain.model.SeriesRevisionIdentifier
import com.gaylord.duit.domain.model.StandaloneTaskCreation
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.model.TaskRevisionIdentifier
import com.gaylord.duit.domain.pairing.PairInvite
import com.gaylord.duit.domain.pairing.PairIdentifier
import com.gaylord.duit.domain.pairing.PairingStatus
import com.gaylord.duit.domain.pairing.PartnerIdentity
import com.gaylord.duit.notifications.ReminderGateway
import com.gaylord.duit.notifications.ScheduledTaskReminder
import com.gaylord.duit.pairing.PairingCallFailure
import com.gaylord.duit.pairing.PairingCallResult
import com.gaylord.duit.pairing.PairingGateway
import com.gaylord.duit.workspace.AuthenticatedIdentity
import com.gaylord.duit.workspace.AuthenticationGateway
import com.gaylord.duit.workspace.AuthenticationSignOut
import com.gaylord.duit.workspace.CommandIdentifier
import com.gaylord.duit.workspace.DuitWorkspace
import com.gaylord.duit.workspace.InitialTaskIdentifiers
import com.gaylord.duit.workspace.MemberApproval
import com.gaylord.duit.workspace.MemberApprovalGateway
import com.gaylord.duit.workspace.OwnerWorkspaceHydration
import com.gaylord.duit.workspace.OwnerWorkspaceHydrationGateway
import com.gaylord.duit.workspace.PersistedWorkspaceSessionGateway
import com.gaylord.duit.workspace.TaskIdentityGenerator
import com.gaylord.duit.workspace.WorkspaceSessionOperationCoordinator
import com.gaylord.duit.workspace.ZoneProvider
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TogetherViewModelTest {
    @Test
    fun `started Together refreshes when Ready arrives before its access collector runs`() =
        runTest {
            val gateway =
                RecordingPairingGateway(
                    statusResults =
                        ArrayDeque(
                            listOf(PairingCallResult.Success(PairingStatus.Unpaired)),
                        ),
                )
            val workspace =
                authenticatedPairingWorkspace(
                    gateway = gateway,
                    authentication =
                        MutableAuthenticationGateway(AuthenticatedIdentity("member-alex")),
                    scope = backgroundScope,
                )
            val viewModel = togetherViewModel(workspace)

            workspace.restoreSession()
            viewModel.onEvent(TogetherEvent.Started)
            runCurrent()

            assertEquals(TogetherState.Unpaired, viewModel.state.value)
            assertEquals(1, gateway.statusCalls)
        }

    @Test
    fun `started Together refreshes for a new Member without another lifecycle event`() =
        runTest {
            val pairIdentifier = PairIdentifier("pair-sam-riley")
            val partnerIdentity = PartnerIdentity("Riley Chen", null)
            val gateway =
                RecordingPairingGateway(
                    statusResults =
                        ArrayDeque(
                            listOf(
                                PairingCallResult.Success(PairingStatus.Unpaired),
                                PairingCallResult.Success(
                                    PairingStatus.Active(pairIdentifier, partnerIdentity),
                                ),
                            ),
                        ),
                )
            val authentication =
                MutableAuthenticationGateway(AuthenticatedIdentity("member-alex"))
            val workspace =
                authenticatedPairingWorkspace(
                    gateway = gateway,
                    authentication = authentication,
                    scope = backgroundScope,
                )
            val viewModel = togetherViewModel(workspace)
            workspace.restoreSession()
            runCurrent()
            viewModel.onEvent(TogetherEvent.Started)
            runCurrent()
            assertEquals(TogetherState.Unpaired, viewModel.state.value)

            val signOut = backgroundScope.launch { workspace.signOut() }
            runCurrent()
            signOut.join()
            authentication.identity = AuthenticatedIdentity("member-sam")
            workspace.restoreSession()
            runCurrent()

            assertEquals(
                TogetherState.ActivePair(pairIdentifier, partnerIdentity),
                viewModel.state.value,
            )
            assertEquals(2, gateway.statusCalls)
        }

    @Test
    fun `starting Together checks status and keeps personal workspace independent`() =
        runTest {
            val gateway =
                RecordingPairingGateway(
                    statusResults =
                        ArrayDeque(
                            listOf(PairingCallResult.Success(PairingStatus.Unpaired)),
                        ),
                )
            val viewModel = togetherViewModel(gateway)

            viewModel.onEvent(TogetherEvent.Started)
            runCurrent()

            assertEquals(TogetherState.Unpaired, viewModel.state.value)
        }

    @Test
    fun `offline Pairing failure stays recoverable within Together`() =
        runTest {
            val gateway =
                RecordingPairingGateway(
                    statusResults =
                        ArrayDeque(
                            listOf(
                                PairingCallResult.Failure(PairingCallFailure.ConnectionRequired),
                                PairingCallResult.Success(PairingStatus.Unpaired),
                            ),
                        ),
                )
            val viewModel = togetherViewModel(gateway)

            viewModel.onEvent(TogetherEvent.Started)
            runCurrent()
            assertEquals(TogetherState.ConnectionRequired, viewModel.state.value)

            viewModel.onEvent(TogetherEvent.Retry)
            runCurrent()

            assertEquals(TogetherState.Unpaired, viewModel.state.value)
        }

    @Test
    fun `creating an invite exposes its short-lived code`() =
        runTest {
            val invite = PairInvite("7K3M9P2Q", expiresAtEpochMillis = 1_786_406_100_000)
            val gateway =
                RecordingPairingGateway(
                    statusResults =
                        ArrayDeque(
                            listOf(
                                PairingCallResult.Success(PairingStatus.Unpaired),
                                PairingCallResult.Success(PairingStatus.Unpaired),
                            ),
                        ),
                    createInviteResults =
                        ArrayDeque(listOf(PairingCallResult.Success(invite))),
                )
            val viewModel =
                togetherViewModel(gateway, epochMillis = { 1_786_406_000_000 })
            viewModel.onEvent(TogetherEvent.Started)
            runCurrent()

            viewModel.onEvent(TogetherEvent.CreateInvite)
            runCurrent()

            assertEquals(TogetherState.InviteReady(invite), viewModel.state.value)
        }

    @Test
    fun `stopping Together does not orphan an in flight Pair Invite`() =
        runTest {
            val invite = PairInvite("7K3M9P2Q", expiresAtEpochMillis = 1_786_406_100_000)
            val pendingInvite = CompletableDeferred<PairingCallResult<PairInvite>>()
            val gateway =
                RecordingPairingGateway(
                    statusResults =
                        ArrayDeque(
                            listOf(
                                PairingCallResult.Success(PairingStatus.Unpaired),
                                PairingCallResult.Success(PairingStatus.Unpaired),
                            ),
                        ),
                    pendingCreateInvite = pendingInvite,
                )
            val viewModel =
                togetherViewModel(gateway, epochMillis = { 1_786_406_000_000 })
            viewModel.onEvent(TogetherEvent.Started)
            runCurrent()

            viewModel.onEvent(TogetherEvent.CreateInvite)
            runCurrent()
            viewModel.onEvent(TogetherEvent.Stopped)
            pendingInvite.complete(PairingCallResult.Success(invite))
            runCurrent()

            assertEquals(TogetherState.InviteReady(invite), viewModel.state.value)
            assertEquals(1, gateway.createInviteCalls)

            viewModel.onEvent(TogetherEvent.Started)
            runCurrent()

            assertEquals(TogetherState.InviteReady(invite), viewModel.state.value)
            assertEquals(1, gateway.createInviteCalls)
            assertEquals(2, gateway.statusCalls)
        }

    @Test
    fun `accepting then confirming an invite requires explicit identity review`() =
        runTest {
            val pending =
                PairingStatus.PendingConfirmation(
                    pairIdentifier = PairIdentifier("pair-alex-sam"),
                    partnerIdentity = PartnerIdentity("Sam Rivera", null),
                    selfConfirmed = false,
                    partnerConfirmed = false,
                )
            val waiting = pending.copy(selfConfirmed = true)
            val gateway =
                RecordingPairingGateway(
                    statusResults =
                        ArrayDeque(
                            listOf(
                                PairingCallResult.Success(PairingStatus.Unpaired),
                                PairingCallResult.Success(PairingStatus.Unpaired),
                            ),
                        ),
                    acceptInviteResults =
                        ArrayDeque(listOf(PairingCallResult.Success(pending))),
                    confirmResults = ArrayDeque(listOf(PairingCallResult.Success(waiting))),
                )
            val viewModel = togetherViewModel(gateway)
            viewModel.onEvent(TogetherEvent.Started)
            runCurrent()

            viewModel.onEvent(TogetherEvent.AcceptInvite("7K3M9P2Q"))
            runCurrent()
            assertEquals(
                TogetherState.PartnerIdentityReview(
                    pairIdentifier = pending.pairIdentifier,
                    partnerIdentity = pending.partnerIdentity,
                    partnerAlreadyConfirmed = false,
                ),
                viewModel.state.value,
            )

            viewModel.onEvent(TogetherEvent.ConfirmPartnerIdentity)
            runCurrent()

            assertEquals(
                TogetherState.WaitingForPartner(waiting.pairIdentifier, waiting.partnerIdentity),
                viewModel.state.value,
            )
        }

    @Test
    fun `pending confirmation refreshes with capped backoff until Pair is active`() =
        runTest {
            val pairIdentifier = PairIdentifier("pair-alex-sam")
            val partnerIdentity = PartnerIdentity("Sam Rivera", null)
            val pending =
                PairingStatus.PendingConfirmation(
                    pairIdentifier,
                    partnerIdentity,
                    selfConfirmed = true,
                    partnerConfirmed = false,
                )
            val active = PairingStatus.Active(pairIdentifier, partnerIdentity)
            val gateway =
                RecordingPairingGateway(
                    statusResults =
                        ArrayDeque(
                            listOf(
                                PairingCallResult.Success(pending),
                                PairingCallResult.Success(pending),
                                PairingCallResult.Success(active),
                            ),
                        ),
                )
            val viewModel =
                togetherViewModel(
                    gateway,
                    refreshPolicy = PairingRefreshPolicy(1_000, 2_000, 2),
                )

            viewModel.onEvent(TogetherEvent.Started)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(
                TogetherState.WaitingForPartner(pairIdentifier, partnerIdentity),
                viewModel.state.value,
            )

            advanceTimeBy(2_000)
            runCurrent()

            assertEquals(
                TogetherState.ActivePair(pairIdentifier, partnerIdentity),
                viewModel.state.value,
            )
        }

    @Test
    fun `invite expiry performs one final authoritative refresh before showing Unpaired`() =
        runTest {
            val start = 1_786_406_000_000
            val invite = PairInvite("7K3M9P2Q", expiresAtEpochMillis = start + 1_500)
            val pending =
                PairingStatus.PendingConfirmation(
                    PairIdentifier("pair-alex-sam"),
                    PartnerIdentity("Sam Rivera", null),
                    selfConfirmed = false,
                    partnerConfirmed = false,
                )
            val gateway =
                RecordingPairingGateway(
                    statusResults =
                        ArrayDeque(
                            listOf(
                                PairingCallResult.Success(PairingStatus.Unpaired),
                                PairingCallResult.Success(PairingStatus.Unpaired),
                                PairingCallResult.Success(PairingStatus.Unpaired),
                                PairingCallResult.Success(pending),
                            ),
                        ),
                    createInviteResults =
                        ArrayDeque(listOf(PairingCallResult.Success(invite))),
                )
            val viewModel =
                togetherViewModel(
                    gateway,
                    refreshPolicy = PairingRefreshPolicy(1_000, 2_000, 2),
                    epochMillis = { start + testScheduler.currentTime },
                )
            viewModel.onEvent(TogetherEvent.Started)
            runCurrent()
            viewModel.onEvent(TogetherEvent.CreateInvite)
            runCurrent()

            advanceTimeBy(1_500)
            runCurrent()

            assertEquals(
                TogetherState.PartnerIdentityReview(
                    pending.pairIdentifier,
                    pending.partnerIdentity,
                    partnerAlreadyConfirmed = false,
                ),
                viewModel.state.value,
            )
        }

    @Test
    fun `one failed background poll keeps waiting for the Partner`() =
        runTest {
            val pairIdentifier = PairIdentifier("pair-alex-sam")
            val partnerIdentity = PartnerIdentity("Sam Rivera", null)
            val waiting =
                TogetherState.WaitingForPartner(pairIdentifier, partnerIdentity)
            val pending =
                PairingStatus.PendingConfirmation(
                    pairIdentifier,
                    partnerIdentity,
                    selfConfirmed = true,
                    partnerConfirmed = false,
                )
            val gateway =
                RecordingPairingGateway(
                    statusResults =
                        ArrayDeque(
                            listOf(
                                PairingCallResult.Success(pending),
                                PairingCallResult.Failure(PairingCallFailure.ConnectionRequired),
                                PairingCallResult.Success(
                                    PairingStatus.Active(pairIdentifier, partnerIdentity),
                                ),
                            ),
                        ),
                )
            val viewModel =
                togetherViewModel(
                    gateway,
                    refreshPolicy = PairingRefreshPolicy(1_000, 2_000, 2),
                )
            viewModel.onEvent(TogetherEvent.Started)
            runCurrent()

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(waiting, viewModel.state.value)
            assertEquals(2, gateway.statusCalls)

            advanceTimeBy(2_000)
            runCurrent()

            assertEquals(
                TogetherState.ActivePair(pairIdentifier, partnerIdentity),
                viewModel.state.value,
            )
        }

    @Test
    fun `a device clock ahead of the server does not drop a fresh Pair Invite`() =
        runTest {
            val serverExpiry = 1_786_406_000_000
            val invite = PairInvite("7K3M9P2Q", expiresAtEpochMillis = serverExpiry)
            val gateway =
                RecordingPairingGateway(
                    statusResults =
                        ArrayDeque(
                            listOf(
                                PairingCallResult.Success(PairingStatus.Unpaired),
                                PairingCallResult.Success(PairingStatus.Unpaired),
                                PairingCallResult.Success(PairingStatus.Unpaired),
                            ),
                        ),
                    createInviteResults =
                        ArrayDeque(listOf(PairingCallResult.Success(invite))),
                )
            val viewModel =
                togetherViewModel(
                    gateway,
                    epochMillis = { serverExpiry + 20 * 60_000 + testScheduler.currentTime },
                )
            viewModel.onEvent(TogetherEvent.Started)
            runCurrent()
            viewModel.onEvent(TogetherEvent.CreateInvite)
            runCurrent()

            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(TogetherState.InviteReady(invite), viewModel.state.value)
        }

    @Test
    fun `rejected Pair Invite acceptance is recoverable and Retry returns to Unpaired`() =
        runTest {
            val gateway =
                RecordingPairingGateway(
                    statusResults =
                        ArrayDeque(
                            listOf(
                                PairingCallResult.Success(PairingStatus.Unpaired),
                                PairingCallResult.Success(PairingStatus.Unpaired),
                                PairingCallResult.Success(PairingStatus.Unpaired),
                            ),
                        ),
                    acceptInviteResults =
                        ArrayDeque(
                            listOf(
                                PairingCallResult.Failure(
                                    PairingCallFailure.Recoverable("That Pair Invite has expired."),
                                ),
                            ),
                        ),
                )
            val viewModel = togetherViewModel(gateway)
            viewModel.onEvent(TogetherEvent.Started)
            runCurrent()

            viewModel.onEvent(TogetherEvent.AcceptInvite("7K3M9P2Q"))
            runCurrent()
            assertEquals(
                TogetherState.RecoverableError("That Pair Invite has expired."),
                viewModel.state.value,
            )

            viewModel.onEvent(TogetherEvent.Retry)
            runCurrent()

            assertEquals(TogetherState.Unpaired, viewModel.state.value)
        }

    private fun TestScope.togetherViewModel(
        gateway: PairingGateway,
        refreshPolicy: PairingRefreshPolicy = PairingRefreshPolicy(),
        epochMillis: () -> Long = System::currentTimeMillis,
    ): TogetherViewModel =
        TogetherViewModel(
            workspace = pairingWorkspace(gateway),
            coroutineScope = backgroundScope,
            refreshPolicy = refreshPolicy,
            epochMillis = epochMillis,
        )

    private fun TestScope.togetherViewModel(
        workspace: DuitWorkspace,
        refreshPolicy: PairingRefreshPolicy = PairingRefreshPolicy(),
        epochMillis: () -> Long = System::currentTimeMillis,
    ): TogetherViewModel =
        TogetherViewModel(
            workspace = workspace,
            coroutineScope = backgroundScope,
            refreshPolicy = refreshPolicy,
            epochMillis = epochMillis,
        )
}

private fun pairingWorkspace(gateway: PairingGateway): DuitWorkspace =
    DuitWorkspace(
        memberIdentifier = MemberIdentifier("member-alex"),
        clock = Clock.fixed(Instant.parse("2026-08-09T08:00:00Z"), ZoneId.of("UTC")),
        zoneProvider = ZoneProvider { ZoneId.of("Asia/Kolkata") },
        taskIdentities =
            TaskIdentityGenerator { commandIdentifier: CommandIdentifier ->
                InitialTaskIdentifiers(
                    taskIdentifier = TaskIdentifier("task-${commandIdentifier.value}"),
                    revisionIdentifier = TaskRevisionIdentifier("revision-${commandIdentifier.value}"),
                    seriesRevisionIdentifier =
                        SeriesRevisionIdentifier("series-revision-${commandIdentifier.value}"),
                )
            },
        persistence = EmptyTaskRepository,
        reminders = EmptyReminderGateway,
        pairing = gateway,
    )

private fun authenticatedPairingWorkspace(
    gateway: PairingGateway,
    authentication: AuthenticationGateway,
    scope: CoroutineScope,
): DuitWorkspace =
    DuitWorkspace.authenticated(
        clock = Clock.fixed(Instant.parse("2026-08-09T08:00:00Z"), ZoneId.of("UTC")),
        zoneProvider = ZoneProvider { ZoneId.of("Asia/Kolkata") },
        taskIdentities =
            TaskIdentityGenerator { commandIdentifier: CommandIdentifier ->
                InitialTaskIdentifiers(
                    taskIdentifier = TaskIdentifier("task-${commandIdentifier.value}"),
                    revisionIdentifier = TaskRevisionIdentifier("revision-${commandIdentifier.value}"),
                    seriesRevisionIdentifier =
                        SeriesRevisionIdentifier("series-revision-${commandIdentifier.value}"),
                )
            },
        persistence = EmptyTaskRepository,
        reminders = EmptyReminderGateway,
        pairing = gateway,
        authentication = authentication,
        approval = MemberApprovalGateway { MemberApproval.Approved },
        hydration = OwnerWorkspaceHydrationGateway { OwnerWorkspaceHydration.Complete },
        persistedSession = AlwaysHydratedWorkspaceSessionGateway,
        sessionOperations = WorkspaceSessionOperationCoordinator(scope),
    )

private object EmptyTaskRepository : TaskRepository {
    override suspend fun putStandaloneTaskIfAbsent(
        creation: StandaloneTaskCreation,
    ): StandaloneTaskCreation = creation

    override fun observeStandaloneTasks(
        ownerIdentifier: MemberIdentifier,
        plannedDate: LocalDate,
    ): Flow<List<StandaloneTaskCreation>> = flowOf(emptyList())

    override fun observeStandaloneTask(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
    ): Flow<StandaloneTaskCreation?> = flowOf(null)

    override suspend fun putRecurringSeriesIfAbsent(
        creation: RecurringSeriesCreation,
    ): RecurringSeriesCreation = creation

    override fun observeRecurringSeries(
        ownerIdentifier: MemberIdentifier,
    ): Flow<List<RecurringSeriesCreation>> = flowOf(emptyList())

    override fun observeRecurringSeries(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
    ): Flow<RecurringSeriesCreation?> = flowOf(null)

    override suspend fun putOccurrenceState(
        ownerIdentifier: MemberIdentifier,
        state: OccurrenceState,
    ): OccurrenceState = state

    override fun observeOccurrenceState(
        ownerIdentifier: MemberIdentifier,
        occurrenceKey: OccurrenceKey,
    ): Flow<OccurrenceState?> = flowOf(null)

    override fun observeOccurrenceStates(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
    ): Flow<List<OccurrenceState>> = flowOf(emptyList())
}

private object EmptyReminderGateway : ReminderGateway {
    override suspend fun reconcile(reminder: ScheduledTaskReminder) = Unit
}

private class MutableAuthenticationGateway(
    var identity: AuthenticatedIdentity?,
) : AuthenticationGateway {
    override suspend fun currentIdentity(): AuthenticatedIdentity? = identity

    override suspend fun signOut(): AuthenticationSignOut {
        identity = null
        return AuthenticationSignOut.Complete
    }
}

private object AlwaysHydratedWorkspaceSessionGateway : PersistedWorkspaceSessionGateway {
    override suspend fun wasHydrated(memberIdentifier: MemberIdentifier): Boolean = true

    override suspend fun recordHydrated(memberIdentifier: MemberIdentifier) = Unit

    override suspend fun pendingSignOut(): MemberIdentifier? = null

    override suspend fun beginSignOut(memberIdentifier: MemberIdentifier) = Unit

    override suspend fun completeSignOut(memberIdentifier: MemberIdentifier) = Unit
}

private class RecordingPairingGateway(
    private val statusResults: ArrayDeque<PairingCallResult<PairingStatus>> = ArrayDeque(),
    private val createInviteResults: ArrayDeque<PairingCallResult<PairInvite>> = ArrayDeque(),
    private val acceptInviteResults:
        ArrayDeque<PairingCallResult<PairingStatus.PendingConfirmation>> = ArrayDeque(),
    private val confirmResults: ArrayDeque<PairingCallResult<PairingStatus>> = ArrayDeque(),
    private val pendingCreateInvite: CompletableDeferred<PairingCallResult<PairInvite>>? = null,
) : PairingGateway {
    var statusCalls: Int = 0
        private set
    var createInviteCalls: Int = 0
        private set

    override suspend fun getPairingStatus(): PairingCallResult<PairingStatus> =
        statusResults.removeFirst().also { statusCalls += 1 }

    override suspend fun createPairInvite(): PairingCallResult<PairInvite> =
        (pendingCreateInvite?.await() ?: createInviteResults.removeFirst())
            .also { createInviteCalls += 1 }

    override suspend fun acceptPairInvite(
        code: String,
    ): PairingCallResult<PairingStatus.PendingConfirmation> = acceptInviteResults.removeFirst()

    override suspend fun confirmPair(
        pairIdentifier: PairIdentifier,
    ): PairingCallResult<PairingStatus> = confirmResults.removeFirst()
}
