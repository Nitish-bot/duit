package com.gaylord.duit.workspace

import com.gaylord.duit.data.repository.TaskRepository
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.OccurrenceKey
import com.gaylord.duit.domain.model.OccurrenceState
import com.gaylord.duit.domain.model.RecurringSeriesCreation
import com.gaylord.duit.domain.model.SeriesRevisionIdentifier
import com.gaylord.duit.domain.model.StandaloneTaskCreation
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.model.TaskVisibility
import com.gaylord.duit.domain.pairing.PairInvite
import com.gaylord.duit.domain.pairing.PairIdentifier
import com.gaylord.duit.domain.pairing.PairingStatus
import com.gaylord.duit.pairing.PairingCallResult
import com.gaylord.duit.pairing.PairingGateway
import com.gaylord.duit.notifications.ReminderGateway
import com.gaylord.duit.notifications.ScheduledTaskReminder
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DuitWorkspaceAccessTest {
    @Test
    fun `creating a Pair Invite rechecks Pairing status through the workspace seam`() =
        runTest {
            val invite = PairInvite("7K3M9P2Q", expiresAtEpochMillis = 1_786_406_100_000)
            val pairing =
                RecordingAccessPairingGateway(
                    statusResults =
                        ArrayDeque(
                            listOf(PairingCallResult.Success(PairingStatus.Unpaired)),
                        ),
                    inviteResults = ArrayDeque(listOf(PairingCallResult.Success(invite))),
                )
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = FakeAuthentication(AuthenticatedIdentity("member-alex")),
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                    pairing = pairing,
                )
            workspace.restoreSession()

            val outcome = workspace.execute(PairCommand.CreateInvite)

            assertEquals(
                PairCommandObservation(
                    outcome = PairCommandOutcome.InviteCreated(invite),
                    status = PairingStatus.Unpaired,
                    calls = listOf("getPairingStatus", "createPairInvite"),
                ),
                PairCommandObservation(
                    outcome = outcome,
                    status = workspace.pairingStatus.value,
                    calls = pairing.calls,
                ),
            )
        }

    @Test
    fun `accepting a Pair Invite rechecks status and publishes Pending Confirmation`() =
        runTest {
            val pending =
                PairingStatus.PendingConfirmation(
                    pairIdentifier = PairIdentifier("pair-alex-sam"),
                    partnerIdentity =
                        com.gaylord.duit.domain.pairing.PartnerIdentity("Sam Rivera", null),
                    selfConfirmed = false,
                    partnerConfirmed = false,
                )
            val pairing =
                RecordingAccessPairingGateway(
                    statusResults =
                        ArrayDeque(
                            listOf(PairingCallResult.Success(PairingStatus.Unpaired)),
                        ),
                    acceptResults = ArrayDeque(listOf(PairingCallResult.Success(pending))),
                )
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = FakeAuthentication(AuthenticatedIdentity("member-alex")),
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                    pairing = pairing,
                )
            workspace.restoreSession()

            val outcome = workspace.execute(PairCommand.AcceptInvite("7K3M9P2Q"))

            assertEquals(PairCommandOutcome.StatusChanged(pending), outcome)
            assertEquals(pending, workspace.pairingStatus.value)
            assertEquals(
                listOf("getPairingStatus", "acceptPairInvite:7K3M9P2Q"),
                pairing.calls,
            )
        }

    @Test
    fun `Pairing result arriving after sign out cannot repopulate Member state`() =
        runTest {
            val pairing = DeferredStatusPairingGateway()
            val authentication = FakeAuthentication(AuthenticatedIdentity("member-alex"))
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = authentication,
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                    pairing = pairing,
                )
            workspace.restoreSession()

            val refresh = async { workspace.execute(PairCommand.RefreshStatus) }
            pairing.started.await()
            workspace.signOut()
            pairing.result.complete(
                PairingCallResult.Success(
                    PairingStatus.Active(
                        PairIdentifier("pair-alex-sam"),
                        com.gaylord.duit.domain.pairing.PartnerIdentity("Sam Rivera", null),
                    ),
                ),
            )

            assertEquals(PairCommandOutcome.MemberSessionChanged, refresh.await())
            assertEquals(null, workspace.pairingStatus.value)
            assertEquals(WorkspaceAccessState.SignInRequired, workspace.accessState.value)
        }

    @Test
    fun `Pairing result resolving in the Ready-state sign-out gap is discarded`() =
        runTest {
            val activePairing =
                PairingStatus.Active(
                    PairIdentifier("pair-alex-sam"),
                    com.gaylord.duit.domain.pairing.PartnerIdentity("Sam Rivera", null),
                )
            val pairing = DeferredStatusPairingGateway()
            var accessStateAtResolution: WorkspaceAccessState? = null
            lateinit var workspace: DuitWorkspace
            val persistedSession =
                ResolvePairingDuringSignOutPersistedSessionGateway(
                    pairing = pairing,
                    result = PairingCallResult.Success(activePairing),
                    beforeResolvingPairing = {
                        accessStateAtResolution = workspace.accessState.value
                    },
                )
            workspace =
                createAuthenticatedWorkspace(
                    authentication = FakeAuthentication(AuthenticatedIdentity("member-alex")),
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                    persistedSession = persistedSession,
                    pairing = pairing,
                )
            workspace.restoreSession()

            val refresh =
                async(Dispatchers.Unconfined) {
                    workspace.execute(PairCommand.RefreshStatus)
                }
            pairing.started.await()
            val signOut = async { workspace.signOut() }
            runCurrent()

            assertEquals(PairCommandOutcome.MemberSessionChanged, refresh.await())
            assertEquals(null, workspace.pairingStatus.value)
            assertEquals(
                WorkspaceAccessState.Ready(
                    memberIdentifier = MemberIdentifier("member-alex"),
                    restorationSource = WorkspaceRestorationSource.Server,
                ),
                accessStateAtResolution,
            )

            persistedSession.allowSignOutToFinish()
            signOut.await()
        }

    @Test
    fun `Pairing result from before sign out is discarded after the same Member signs in again`() =
        runTest {
            val pairing = DeferredStatusPairingGateway()
            val authentication = FakeAuthentication(AuthenticatedIdentity("member-alex"))
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = authentication,
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                    pairing = pairing,
                )
            workspace.restoreSession()

            val refresh = async { workspace.execute(PairCommand.RefreshStatus) }
            pairing.started.await()
            workspace.signOut()
            authentication.authenticate("member-alex")
            workspace.restoreSession()
            pairing.result.complete(
                PairingCallResult.Success(
                    PairingStatus.Active(
                        PairIdentifier("pair-alex-sam"),
                        com.gaylord.duit.domain.pairing.PartnerIdentity("Sam Rivera", null),
                    ),
                ),
            )

            assertEquals(PairCommandOutcome.MemberSessionChanged, refresh.await())
            assertEquals(null, workspace.pairingStatus.value)
            assertEquals(
                WorkspaceAccessState.Ready(
                    memberIdentifier = MemberIdentifier("member-alex"),
                    restorationSource = WorkspaceRestorationSource.Server,
                ),
                workspace.accessState.value,
            )
        }

    @Test
    fun `signed-out identity sees sign in required and cannot execute Task commands`() =
        runTest {
            val workspace = createAuthenticatedWorkspace(authentication = FakeAuthentication())

            workspace.restoreSession()

            assertEquals(
                LockedWorkspaceObservation(
                    accessState = WorkspaceAccessState.SignInRequired,
                    visibleTasks = emptyList(),
                    commandRejection = WorkspaceAccessState.SignInRequired,
                ),
                LockedWorkspaceObservation(
                    accessState = workspace.accessState.value,
                    visibleTasks = workspace.observePersonalAgenda().first().tasks,
                    commandRejection = workspace.executeAndObserveRejection(),
                ),
            )
        }

    @Test
    fun `unapproved identity sees no workspace data`() =
        runTest {
            val authenticatedUid = "firebase-uid-alex"
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = FakeAuthentication(AuthenticatedIdentity(authenticatedUid)),
                )

            workspace.restoreSession()

            val approvalRequired =
                WorkspaceAccessState.ApprovalRequired(authenticatedUid = authenticatedUid)

            assertEquals(
                ApprovalRequiredObservation(
                    accessState = approvalRequired,
                    availableActions =
                        setOf(
                            WorkspaceAccessAction.Retry,
                            WorkspaceAccessAction.SignOut,
                        ),
                    visibleTasks = emptyList(),
                    commandRejection = approvalRequired,
                ),
                ApprovalRequiredObservation(
                    accessState = workspace.accessState.value,
                    availableActions = workspace.accessState.value.availableActions,
                    visibleTasks = workspace.observePersonalAgenda().first().tasks,
                    commandRejection = workspace.executeAndObserveRejection(),
                ),
            )
        }

    @Test
    fun `approved fresh session stays locked while owner workspace hydrates`() =
        runTest {
            val memberIdentifier = MemberIdentifier("member-alex")
            val hydration = DeferredHydrationGateway()
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = FakeAuthentication(AuthenticatedIdentity("member-alex")),
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                    hydration = hydration,
                )

            val restoration = async { workspace.restoreSession() }
            runCurrent()

            val hydratingState = WorkspaceAccessState.Hydrating(memberIdentifier)
            assertEquals(
                LockedWorkspaceObservation(
                    accessState = hydratingState,
                    visibleTasks = emptyList(),
                    commandRejection = hydratingState,
                ),
                LockedWorkspaceObservation(
                    accessState = workspace.accessState.value,
                    visibleTasks = workspace.observePersonalAgenda().first().tasks,
                    commandRejection = workspace.executeAndObserveRejection(),
                ),
            )
            restoration.cancelAndJoin()
        }

    @Test
    fun `completed server hydration unlocks a genuinely empty owner workspace`() =
        runTest {
            val memberIdentifier = MemberIdentifier("member-alex")
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = FakeAuthentication(AuthenticatedIdentity("member-alex")),
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                )

            workspace.restoreSession()
            val stateAfterHydration = workspace.accessState.value
            val tasksAfterHydration = workspace.observePersonalAgenda().first().tasks
            val createdTaskIdentifier =
                workspace.execute(
                    MemberCommand.AddPrivateTaskToToday(
                        commandIdentifier = CommandIdentifier("first-task"),
                        title = "Water the herbs",
                    ),
                )

            assertEquals(
                ReadyWorkspaceObservation(
                    accessState =
                        WorkspaceAccessState.Ready(
                            memberIdentifier = memberIdentifier,
                            restorationSource = WorkspaceRestorationSource.Server,
                        ),
                    tasksAtUnlock = emptyList(),
                    createdTaskIdentifier = TaskIdentifier("task-first-task"),
                ),
                ReadyWorkspaceObservation(
                    accessState = stateAfterHydration,
                    tasksAtUnlock = tasksAfterHydration,
                    createdTaskIdentifier = createdTaskIdentifier,
                ),
            )
        }

    @Test
    fun `offline Pairing attempt requires a connection without disturbing the hydrated Owner workspace`() =
        runTest {
            val memberIdentifier = MemberIdentifier("member-alex")
            val pairing =
                RecordingAccessPairingGateway(
                    statusResults =
                        ArrayDeque(
                            listOf(
                                PairingCallResult.Failure(
                                    com.gaylord.duit.pairing.PairingCallFailure.ConnectionRequired,
                                ),
                            ),
                        ),
                )
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = FakeAuthentication(AuthenticatedIdentity("member-alex")),
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                    pairing = pairing,
                )
            workspace.restoreSession()
            workspace.execute(
                MemberCommand.AddPrivateDailyTaskToToday(
                    commandIdentifier = CommandIdentifier("before-pairing"),
                    title = "Water the herbs",
                ),
            )

            val outcome = workspace.execute(PairCommand.CreateInvite)
            workspace.execute(
                MemberCommand.AddPrivateDailyTaskToToday(
                    commandIdentifier = CommandIdentifier("after-pairing"),
                    title = "Stretch",
                ),
            )

            assertEquals(
                PairCommandOutcome.Failed(PairCommandFailure.ConnectionRequired),
                outcome,
            )
            assertEquals(listOf("getPairingStatus"), pairing.calls)
            assertEquals(null, workspace.pairingStatus.value)
            assertEquals(
                WorkspaceAccessState.Ready(
                    memberIdentifier = memberIdentifier,
                    restorationSource = WorkspaceRestorationSource.Server,
                ),
                workspace.accessState.value,
            )
            assertEquals(
                listOf("Water the herbs", "Stretch"),
                workspace.observePersonalAgenda().first().tasks.map(AgendaTask::title),
            )
        }

    @Test
    fun `approved hydrated unpaired Member can create a private Daily Task offline`() =
        runTest {
            val memberIdentifier = MemberIdentifier("member-alex")
            val persistence = AccessTestTaskRepository()
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = FakeAuthentication(AuthenticatedIdentity("member-alex")),
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                    persistence = persistence,
                )

            workspace.restoreSession()
            val taskIdentifier =
                workspace.execute(
                    MemberCommand.AddPrivateDailyTaskToToday(
                        commandIdentifier = CommandIdentifier("hydrated-daily"),
                        title = "  Water the herbs  ",
                        subtask = "  Check the soil  ",
                    ),
                )
            val agenda = workspace.observePersonalAgenda().first()

            assertEquals(
                WorkspaceAccessState.Ready(
                    memberIdentifier = memberIdentifier,
                    restorationSource = WorkspaceRestorationSource.Server,
                ),
                workspace.accessState.value,
            )
            assertEquals(
                HydratedDailyObservation(
                    taskIdentifier = TaskIdentifier("task-hydrated-daily"),
                    title = "Water the herbs",
                    visibility = TaskVisibility.Private,
                    occurrenceKey =
                        OccurrenceKey(
                            TaskIdentifier("task-hydrated-daily"),
                            LocalDate.of(2026, 8, 9),
                        ),
                    subtaskLabels = listOf("Check the soil"),
                ),
                agenda.tasks.single().let { task ->
                    HydratedDailyObservation(
                        taskIdentifier = taskIdentifier,
                        title = task.title,
                        visibility = task.visibility,
                        occurrenceKey = task.occurrence?.key,
                        subtaskLabels = task.occurrence?.subtasks.orEmpty().map(AgendaSubtask::label),
                    )
                },
            )
        }

    @Test
    fun `completed hydration stays locked when its offline-session marker is not durable`() =
        runTest {
            val memberIdentifier = MemberIdentifier("member-alex")
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = FakeAuthentication(AuthenticatedIdentity("member-alex")),
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                    persistedSession = FailingRecordPersistedWorkspaceSessionGateway,
                )

            workspace.restoreSession()
            val failedState =
                WorkspaceAccessState.Failed(
                    memberIdentifier = memberIdentifier,
                    failure = OwnerWorkspaceHydrationFailure.LocalSessionRecordFailed,
                )

            assertEquals(
                LockedWorkspaceObservation(
                    accessState = failedState,
                    visibleTasks = emptyList(),
                    commandRejection = failedState,
                ),
                LockedWorkspaceObservation(
                    accessState = workspace.accessState.value,
                    visibleTasks = workspace.observePersonalAgenda().first().tasks,
                    commandRejection = workspace.executeAndObserveRejection(),
                ),
            )
        }

    @Test
    fun `owner hydration failure stays locked with retry and sign-out affordances`() =
        runTest {
            val memberIdentifier = MemberIdentifier("member-alex")
            val failure = OwnerWorkspaceHydrationFailure.ServerUnavailable
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = FakeAuthentication(AuthenticatedIdentity("member-alex")),
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                    hydration =
                        OwnerWorkspaceHydrationGateway {
                            OwnerWorkspaceHydration.Failed(failure)
                        },
                )

            workspace.restoreSession()
            val failedState =
                WorkspaceAccessState.Failed(
                    memberIdentifier = memberIdentifier,
                    failure = failure,
                )

            assertEquals(
                FailedWorkspaceObservation(
                    accessState = failedState,
                    availableActions =
                        setOf(
                            WorkspaceAccessAction.Retry,
                            WorkspaceAccessAction.SignOut,
                        ),
                    visibleTasks = emptyList(),
                    commandRejection = failedState,
                ),
                FailedWorkspaceObservation(
                    accessState = workspace.accessState.value,
                    availableActions = workspace.accessState.value.availableActions,
                    visibleTasks = workspace.observePersonalAgenda().first().tasks,
                    commandRejection = workspace.executeAndObserveRejection(),
                ),
            )
        }

    @Test
    fun `retry after owner hydration failure can unlock editing`() =
        runTest {
            val memberIdentifier = MemberIdentifier("member-alex")
            var hydrationResult: OwnerWorkspaceHydration =
                OwnerWorkspaceHydration.Failed(
                    OwnerWorkspaceHydrationFailure.ServerUnavailable,
                )
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = FakeAuthentication(AuthenticatedIdentity("member-alex")),
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                    hydration = OwnerWorkspaceHydrationGateway { hydrationResult },
                )

            workspace.restoreSession()
            hydrationResult = OwnerWorkspaceHydration.Complete
            workspace.retryAccess()
            val createdTaskIdentifier =
                workspace.execute(
                    MemberCommand.AddPrivateTaskToToday(
                        commandIdentifier = CommandIdentifier("after-retry"),
                        title = "Open after retry",
                    ),
                )

            assertEquals(
                ReadyWorkspaceObservation(
                    accessState =
                        WorkspaceAccessState.Ready(
                            memberIdentifier = memberIdentifier,
                            restorationSource = WorkspaceRestorationSource.Server,
                        ),
                    tasksAtUnlock = emptyList(),
                    createdTaskIdentifier = TaskIdentifier("task-after-retry"),
                ),
                ReadyWorkspaceObservation(
                    accessState = workspace.accessState.value,
                    tasksAtUnlock = emptyList(),
                    createdTaskIdentifier = createdTaskIdentifier,
                ),
            )
        }

    @Test
    fun `retry after hydration failure revalidates Member approval before editing`() =
        runTest {
            var approvalResult: MemberApproval = MemberApproval.Approved
            var hydrationResult: OwnerWorkspaceHydration =
                OwnerWorkspaceHydration.Failed(
                    OwnerWorkspaceHydrationFailure.ServerUnavailable,
                )
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = FakeAuthentication(AuthenticatedIdentity("member-alex")),
                    approval = MemberApprovalGateway { approvalResult },
                    hydration = OwnerWorkspaceHydrationGateway { hydrationResult },
                )
            workspace.restoreSession()

            approvalResult = MemberApproval.Required
            hydrationResult = OwnerWorkspaceHydration.Complete
            workspace.retryAccess()
            val approvalRequired =
                WorkspaceAccessState.ApprovalRequired(authenticatedUid = "member-alex")

            assertEquals(
                LockedWorkspaceObservation(
                    accessState = approvalRequired,
                    visibleTasks = emptyList(),
                    commandRejection = approvalRequired,
                ),
                LockedWorkspaceObservation(
                    accessState = workspace.accessState.value,
                    visibleTasks = workspace.observePersonalAgenda().first().tasks,
                    commandRejection = workspace.executeAndObserveRejection(),
                ),
            )
        }

    @Test
    fun `retry after admin approval hydrates and unlocks the approved Member`() =
        runTest {
            val memberIdentifier = MemberIdentifier("member-alex")
            var approvalResult: MemberApproval = MemberApproval.Required
            val workspace =
                createAuthenticatedWorkspace(
                    authentication =
                        FakeAuthentication(AuthenticatedIdentity("member-alex")),
                    approval = MemberApprovalGateway { approvalResult },
                )

            workspace.restoreSession()
            approvalResult = MemberApproval.Approved
            workspace.retryAccess()
            val createdTaskIdentifier =
                workspace.execute(
                    MemberCommand.AddPrivateTaskToToday(
                        commandIdentifier = CommandIdentifier("after-approval"),
                        title = "Open after admin approval",
                    ),
                )

            assertEquals(
                ReadyWorkspaceObservation(
                    accessState =
                        WorkspaceAccessState.Ready(
                            memberIdentifier = memberIdentifier,
                            restorationSource = WorkspaceRestorationSource.Server,
                        ),
                    tasksAtUnlock = emptyList(),
                    createdTaskIdentifier = TaskIdentifier("task-after-approval"),
                ),
                ReadyWorkspaceObservation(
                    accessState = workspace.accessState.value,
                    tasksAtUnlock = emptyList(),
                    createdTaskIdentifier = createdTaskIdentifier,
                ),
            )
        }

    @Test
    fun `previously hydrated Member falls back to owner cache when approval server is offline`() =
        runTest {
            val memberIdentifier = MemberIdentifier("member-alex")
            val persistence = AccessTestTaskRepository()
            val persistedSession = InMemoryPersistedWorkspaceSessionGateway()
            val approval = MemberApprovalGateway { MemberApproval.Approved }
            val onlineWorkspace =
                createAuthenticatedWorkspace(
                    authentication =
                        FakeAuthentication(AuthenticatedIdentity("member-alex")),
                    approval = approval,
                    persistedSession = persistedSession,
                    persistence = persistence,
                )
            onlineWorkspace.restoreSession()
            onlineWorkspace.execute(
                MemberCommand.AddPrivateTaskToToday(
                    commandIdentifier = CommandIdentifier("durable-task"),
                    title = "Water the herbs",
                ),
            )
            val offlineWorkspace =
                createAuthenticatedWorkspace(
                    authentication =
                        FakeAuthentication(AuthenticatedIdentity("member-alex")),
                    approval =
                        MemberApprovalGateway {
                            MemberApproval.Failed(MemberApprovalFailure.ServerUnavailable)
                        },
                    hydration =
                        OwnerWorkspaceHydrationGateway {
                            error("A hydrated offline workspace must not be hydrated again")
                        },
                    persistedSession = persistedSession,
                    persistence = persistence,
                )

            offlineWorkspace.restoreSession()
            val agendaBeforeOfflineEdit = offlineWorkspace.observePersonalAgenda().first()
            val offlineTaskIdentifier =
                offlineWorkspace.execute(
                    MemberCommand.AddPrivateTaskToToday(
                        commandIdentifier = CommandIdentifier("offline-task"),
                        title = "Repot the basil",
                    ),
                )

            assertEquals(
                OfflineWorkspaceObservation(
                    accessState =
                        WorkspaceAccessState.Ready(
                            memberIdentifier = memberIdentifier,
                            restorationSource = WorkspaceRestorationSource.OnDevice,
                        ),
                    restoredTaskIdentifiers = listOf(TaskIdentifier("task-durable-task")),
                    offlineTaskIdentifier = TaskIdentifier("task-offline-task"),
                ),
                OfflineWorkspaceObservation(
                    accessState = offlineWorkspace.accessState.value,
                    restoredTaskIdentifiers =
                        agendaBeforeOfflineEdit.tasks.map(AgendaTask::identifier),
                    offlineTaskIdentifier = offlineTaskIdentifier,
                ),
            )
        }

    @Test
    fun `revoked approval locks a previously hydrated owner workspace`() =
        runTest {
            val memberIdentifier = MemberIdentifier("member-alex")
            val persistence = AccessTestTaskRepository()
            val persistedSession = InMemoryPersistedWorkspaceSessionGateway()
            val approvedWorkspace =
                createAuthenticatedWorkspace(
                    authentication =
                        FakeAuthentication(AuthenticatedIdentity(memberIdentifier.value)),
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                    persistedSession = persistedSession,
                    persistence = persistence,
                )
            approvedWorkspace.restoreSession()
            approvedWorkspace.execute(
                MemberCommand.AddPrivateTaskToToday(
                    commandIdentifier = CommandIdentifier("cached-task"),
                    title = "Must remain hidden",
                ),
            )
            val workspace =
                createAuthenticatedWorkspace(
                    authentication =
                        FakeAuthentication(AuthenticatedIdentity(memberIdentifier.value)),
                    approval = MemberApprovalGateway { MemberApproval.Required },
                    hydration =
                        OwnerWorkspaceHydrationGateway {
                            error("Revoked approval must not hydrate owner data")
                        },
                    persistedSession = persistedSession,
                    persistence = persistence,
                )

            workspace.restoreSession()
            val approvalRequired =
                WorkspaceAccessState.ApprovalRequired(authenticatedUid = memberIdentifier.value)

            assertEquals(
                LockedWorkspaceObservation(
                    accessState = approvalRequired,
                    visibleTasks = emptyList(),
                    commandRejection = approvalRequired,
                ),
                LockedWorkspaceObservation(
                    accessState = workspace.accessState.value,
                    visibleTasks = workspace.observePersonalAgenda().first().tasks,
                    commandRejection = workspace.executeAndObserveRejection(),
                ),
            )
        }

    @Test
    fun `sign out locks immediately and clears the hydrated session from this device`() =
        runTest {
            val memberIdentifier = MemberIdentifier("member-alex")
            val authentication =
                FakeAuthentication(AuthenticatedIdentity("member-alex"))
            val persistedSession = BlockingClearPersistedWorkspaceSessionGateway()
            val approval = MemberApprovalGateway { MemberApproval.Approved }
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = authentication,
                    approval = approval,
                    persistedSession = persistedSession,
                )
            workspace.restoreSession()

            val signOut = async { workspace.signOut() }
            runCurrent()
            val stateWhileLocalSessionClears = workspace.accessState.value
            val rejectionWhileLocalSessionClears = workspace.executeAndObserveRejection()
            workspace.restoreSession()
            val stateAfterRefreshWhileLocalSessionClears = workspace.accessState.value
            persistedSession.allowClear()
            signOut.await()
            workspace.restoreSession()
            val stateAfterAuthenticationRefresh = workspace.accessState.value
            val reconstructedWorkspace =
                createAuthenticatedWorkspace(
                    authentication =
                        FakeAuthentication(AuthenticatedIdentity("member-alex")),
                    approval = approval,
                    hydration =
                        OwnerWorkspaceHydrationGateway {
                            OwnerWorkspaceHydration.Failed(
                                OwnerWorkspaceHydrationFailure.ServerUnavailable,
                            )
                        },
                    persistedSession = persistedSession,
                )
            reconstructedWorkspace.restoreSession()

            assertEquals(
                SignOutObservation(
                    stateWhileLocalSessionClears =
                        WorkspaceAccessState.SigningOut(memberIdentifier.value),
                    rejectionWhileLocalSessionClears =
                        WorkspaceAccessState.SigningOut(memberIdentifier.value),
                    stateAfterRefreshWhileLocalSessionClears =
                        WorkspaceAccessState.SigningOut(memberIdentifier.value),
                    stateAfterAuthenticationRefresh = WorkspaceAccessState.SignInRequired,
                    reconstructedState =
                        WorkspaceAccessState.Failed(
                            memberIdentifier = memberIdentifier,
                            failure = OwnerWorkspaceHydrationFailure.ServerUnavailable,
                        ),
                ),
                SignOutObservation(
                    stateWhileLocalSessionClears = stateWhileLocalSessionClears,
                    rejectionWhileLocalSessionClears = rejectionWhileLocalSessionClears,
                    stateAfterRefreshWhileLocalSessionClears =
                        stateAfterRefreshWhileLocalSessionClears,
                    stateAfterAuthenticationRefresh = stateAfterAuthenticationRefresh,
                    reconstructedState = reconstructedWorkspace.accessState.value,
                ),
            )
        }

    @Test
    fun `failed safe sign out keeps cached workspace locked until retry`() =
        runTest {
            val authenticatedUid = "member-alex"
            val authentication =
                FakeAuthentication(
                    identity = AuthenticatedIdentity(authenticatedUid),
                    signOutResult =
                        AuthenticationSignOut.Failed(
                            AuthenticationSignOutFailure.ServerUnavailable,
                        ),
                )
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = authentication,
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                )
            workspace.restoreSession()

            workspace.signOut()
            val failedState =
                WorkspaceAccessState.SignOutFailed(
                    authenticatedUid = authenticatedUid,
                    failure = AuthenticationSignOutFailure.ServerUnavailable,
                )

            assertEquals(
                LockedWorkspaceObservation(
                    accessState = failedState,
                    visibleTasks = emptyList(),
                    commandRejection = failedState,
                ),
                LockedWorkspaceObservation(
                    accessState = workspace.accessState.value,
                    visibleTasks = workspace.observePersonalAgenda().first().tasks,
                    commandRejection = workspace.executeAndObserveRejection(),
                ),
            )

            authentication.signOutResult = AuthenticationSignOut.Complete
            workspace.signOut()
            assertEquals(WorkspaceAccessState.SignInRequired, workspace.accessState.value)
        }

    @Test
    fun `Activity cancellation cannot stop sign out and a recreated workspace joins cleanup`() =
        runTest {
            val memberIdentifier = MemberIdentifier("member-alex")
            val authentication = BlockingSignOutAuthentication(memberIdentifier.value)
            val persistedSession = InMemoryPersistedWorkspaceSessionGateway()
            val sessionOperations = WorkspaceSessionOperationCoordinator(backgroundScope)
            val firstWorkspace =
                createAuthenticatedWorkspace(
                    authentication = authentication,
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                    persistedSession = persistedSession,
                    sessionOperations = sessionOperations,
                )
            firstWorkspace.restoreSession()

            val activitySignOut = async { firstWorkspace.signOut() }
            authentication.signOutStarted.await()
            activitySignOut.cancelAndJoin()

            val recreatedWorkspace =
                createAuthenticatedWorkspace(
                    authentication = authentication,
                    approval =
                        MemberApprovalGateway {
                            error("Pending sign out must finish before approval lookup")
                        },
                    persistedSession = persistedSession,
                    sessionOperations = sessionOperations,
                )
            val recreatedRestore = async { recreatedWorkspace.restoreSession() }
            runCurrent()

            assertEquals(
                WorkspaceAccessState.SigningOut(memberIdentifier.value),
                recreatedWorkspace.accessState.value,
            )
            assertEquals(1, authentication.signOutCalls)

            authentication.allowSignOut()
            recreatedRestore.await()
            runCurrent()

            assertEquals(
                WorkspaceAccessState.SignInRequired,
                recreatedWorkspace.accessState.value,
            )
            assertEquals(null, persistedSession.pendingSignOut())
            assertEquals(1, authentication.signOutCalls)
        }

    @Test
    fun `process reconstruction resumes durable pending sign out before authentication restore`() =
        runTest {
            val memberIdentifier = MemberIdentifier("member-alex")
            val persistedSession = InMemoryPersistedWorkspaceSessionGateway()
            persistedSession.recordHydrated(memberIdentifier)
            persistedSession.beginSignOut(memberIdentifier)
            val authentication = FakeAuthentication(AuthenticatedIdentity(memberIdentifier.value))
            val reconstructedWorkspace =
                createAuthenticatedWorkspace(
                    authentication = authentication,
                    approval =
                        MemberApprovalGateway {
                            error("Pending sign out must finish before approval lookup")
                        },
                    persistedSession = persistedSession,
                    sessionOperations = WorkspaceSessionOperationCoordinator(backgroundScope),
                )

            reconstructedWorkspace.restoreSession()

            assertEquals(
                WorkspaceAccessState.SignInRequired,
                reconstructedWorkspace.accessState.value,
            )
            assertEquals(1, authentication.signOutCalls)
            assertEquals(null, persistedSession.pendingSignOut())
            assertEquals(false, persistedSession.wasHydrated(memberIdentifier))
        }

    @Test
    fun `credential-state clearance failure keeps durable sign out pending for retry`() =
        runTest {
            val memberIdentifier = MemberIdentifier("member-alex")
            val persistedSession = InMemoryPersistedWorkspaceSessionGateway()
            val authentication =
                FakeAuthentication(
                    identity = AuthenticatedIdentity(memberIdentifier.value),
                    signOutResult =
                        AuthenticationSignOut.Failed(
                            AuthenticationSignOutFailure.CredentialStateClearFailed,
                        ),
                )
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = authentication,
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                    persistedSession = persistedSession,
                )
            workspace.restoreSession()

            workspace.signOut()

            assertEquals(
                WorkspaceAccessState.SignOutFailed(
                    authenticatedUid = memberIdentifier.value,
                    failure = AuthenticationSignOutFailure.CredentialStateClearFailed,
                ),
                workspace.accessState.value,
            )
            assertEquals(memberIdentifier, persistedSession.pendingSignOut())

            authentication.signOutResult = AuthenticationSignOut.Complete
            workspace.retryAccess()

            assertEquals(WorkspaceAccessState.SignInRequired, workspace.accessState.value)
            assertEquals(null, persistedSession.pendingSignOut())
        }

    @Test
    fun `completed sign out cannot later overwrite a newly restored Member`() =
        runTest {
            val memberIdentifier = MemberIdentifier("member-alex")
            val authentication = FakeAuthentication(AuthenticatedIdentity(memberIdentifier.value))
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = authentication,
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                    sessionOperations = WorkspaceSessionOperationCoordinator(backgroundScope),
                )
            workspace.restoreSession()
            workspace.signOut()

            authentication.authenticate(memberIdentifier.value)
            workspace.restoreSession()
            runCurrent()

            assertEquals(
                WorkspaceAccessState.Ready(
                    memberIdentifier = memberIdentifier,
                    restorationSource = WorkspaceRestorationSource.Server,
                ),
                workspace.accessState.value,
            )
        }

    @Test
    fun `sign out does not clear authentication when local hydration marker cannot be cleared`() =
        runTest {
            val authenticatedUid = "member-alex"
            val authentication = FakeAuthentication(AuthenticatedIdentity(authenticatedUid))
            val workspace =
                createAuthenticatedWorkspace(
                    authentication = authentication,
                    approval = MemberApprovalGateway { MemberApproval.Approved },
                    persistedSession = FailingClearPersistedWorkspaceSessionGateway,
                )
            workspace.restoreSession()

            workspace.signOut()

            assertEquals(
                WorkspaceAccessState.SignOutFailed(
                    authenticatedUid = authenticatedUid,
                    failure = AuthenticationSignOutFailure.LocalDataClearFailed,
                ),
                workspace.accessState.value,
            )
            assertEquals(0, authentication.signOutCalls)
        }

    @Test
    fun `approval lookup failure is distinct from pending approval and can retry`() =
        runTest {
            val authenticatedUid = "firebase-uid-alex"
            val approvalFailure = MemberApprovalFailure.ServerUnavailable
            var approvalResult: MemberApproval = MemberApproval.Failed(approvalFailure)
            val workspace =
                createAuthenticatedWorkspace(
                    authentication =
                        FakeAuthentication(AuthenticatedIdentity(authenticatedUid)),
                    approval = MemberApprovalGateway { approvalResult },
                )

            workspace.restoreSession()
            val failedState =
                WorkspaceAccessState.ApprovalCheckFailed(
                    authenticatedUid = authenticatedUid,
                    failure = approvalFailure,
                )
            val stateBeforeRetry = workspace.accessState.value
            val actionsBeforeRetry = workspace.accessState.value.availableActions
            val tasksBeforeRetry = workspace.observePersonalAgenda().first().tasks
            val rejectionBeforeRetry = workspace.executeAndObserveRejection()
            approvalResult = MemberApproval.Required
            workspace.retryAccess()

            assertEquals(
                ApprovalFailureObservation(
                    accessState = failedState,
                    availableActions =
                        setOf(
                            WorkspaceAccessAction.Retry,
                            WorkspaceAccessAction.SignOut,
                        ),
                    visibleTasks = emptyList(),
                    commandRejection = failedState,
                    stateAfterRetry =
                        WorkspaceAccessState.ApprovalRequired(
                            authenticatedUid = authenticatedUid,
                        ),
                ),
                ApprovalFailureObservation(
                    accessState = stateBeforeRetry,
                    availableActions = actionsBeforeRetry,
                    visibleTasks = tasksBeforeRetry,
                    commandRejection = rejectionBeforeRetry,
                    stateAfterRetry = workspace.accessState.value,
                ),
            )
        }
}

private data class LockedWorkspaceObservation(
    val accessState: WorkspaceAccessState,
    val visibleTasks: List<AgendaTask>,
    val commandRejection: WorkspaceAccessState,
)

private data class ApprovalRequiredObservation(
    val accessState: WorkspaceAccessState,
    val availableActions: Set<WorkspaceAccessAction>,
    val visibleTasks: List<AgendaTask>,
    val commandRejection: WorkspaceAccessState,
)

private data class ReadyWorkspaceObservation(
    val accessState: WorkspaceAccessState,
    val tasksAtUnlock: List<AgendaTask>,
    val createdTaskIdentifier: TaskIdentifier,
)

private data class HydratedDailyObservation(
    val taskIdentifier: TaskIdentifier,
    val title: String,
    val visibility: TaskVisibility,
    val occurrenceKey: OccurrenceKey?,
    val subtaskLabels: List<String>,
)

private data class FailedWorkspaceObservation(
    val accessState: WorkspaceAccessState,
    val availableActions: Set<WorkspaceAccessAction>,
    val visibleTasks: List<AgendaTask>,
    val commandRejection: WorkspaceAccessState,
)

private data class OfflineWorkspaceObservation(
    val accessState: WorkspaceAccessState,
    val restoredTaskIdentifiers: List<TaskIdentifier>,
    val offlineTaskIdentifier: TaskIdentifier,
)

private data class SignOutObservation(
    val stateWhileLocalSessionClears: WorkspaceAccessState,
    val rejectionWhileLocalSessionClears: WorkspaceAccessState,
    val stateAfterRefreshWhileLocalSessionClears: WorkspaceAccessState,
    val stateAfterAuthenticationRefresh: WorkspaceAccessState,
    val reconstructedState: WorkspaceAccessState,
)

private data class ApprovalFailureObservation(
    val accessState: WorkspaceAccessState,
    val availableActions: Set<WorkspaceAccessAction>,
    val visibleTasks: List<AgendaTask>,
    val commandRejection: WorkspaceAccessState,
    val stateAfterRetry: WorkspaceAccessState,
)

private data class PairCommandObservation(
    val outcome: PairCommandOutcome,
    val status: PairingStatus?,
    val calls: List<String>,
)

private suspend fun DuitWorkspace.executeAndObserveRejection(): WorkspaceAccessState {
    try {
        execute(
            MemberCommand.AddPrivateTaskToToday(
                commandIdentifier = CommandIdentifier("locked-command"),
                title = "This must stay locked",
            ),
        )
        fail("Expected Task command to be rejected")
    } catch (exception: WorkspaceLockedException) {
        return exception.accessState
    }

    error("JUnit fail should have thrown")
}

private fun createAuthenticatedWorkspace(
    authentication: AuthenticationGateway,
    approval: MemberApprovalGateway = MemberApprovalGateway { MemberApproval.Required },
    hydration: OwnerWorkspaceHydrationGateway =
        OwnerWorkspaceHydrationGateway { OwnerWorkspaceHydration.Complete },
    persistedSession: PersistedWorkspaceSessionGateway = InMemoryPersistedWorkspaceSessionGateway(),
    persistence: TaskRepository = AccessTestTaskRepository(),
    pairing: PairingGateway? = null,
    sessionOperations: WorkspaceSessionOperationCoordinator =
        WorkspaceSessionOperationCoordinator(
            CoroutineScope(Dispatchers.Unconfined),
        ),
): DuitWorkspace =
    DuitWorkspace.authenticated(
        clock = Clock.fixed(java.time.Instant.parse("2026-08-09T08:00:00Z"), ZoneId.of("UTC")),
        zoneProvider = ZoneProvider { ZoneId.of("Asia/Kolkata") },
        taskIdentities = TaskIdentityGenerator { commandIdentifier ->
            InitialTaskIdentifiers(
                taskIdentifier = TaskIdentifier("task-${commandIdentifier.value}"),
                revisionIdentifier =
                    com.gaylord.duit.domain.model.TaskRevisionIdentifier(
                        "revision-${commandIdentifier.value}",
                    ),
                seriesRevisionIdentifier =
                    SeriesRevisionIdentifier("revision-${commandIdentifier.value}"),
            )
        },
        persistence = persistence,
        reminders = AccessTestReminderGateway,
        pairing = pairing,
        authentication = authentication,
        approval = approval,
        hydration = hydration,
        persistedSession = persistedSession,
        sessionOperations = sessionOperations,
    )

private class RecordingAccessPairingGateway(
    private val statusResults: ArrayDeque<PairingCallResult<PairingStatus>> = ArrayDeque(),
    private val inviteResults: ArrayDeque<PairingCallResult<PairInvite>> = ArrayDeque(),
    private val acceptResults:
        ArrayDeque<PairingCallResult<PairingStatus.PendingConfirmation>> = ArrayDeque(),
) : PairingGateway {
    val calls = mutableListOf<String>()

    override suspend fun getPairingStatus(): PairingCallResult<PairingStatus> {
        calls += "getPairingStatus"
        return statusResults.removeFirst()
    }

    override suspend fun createPairInvite(): PairingCallResult<PairInvite> {
        calls += "createPairInvite"
        return inviteResults.removeFirst()
    }

    override suspend fun acceptPairInvite(
        code: String,
    ): PairingCallResult<PairingStatus.PendingConfirmation> {
        calls += "acceptPairInvite:$code"
        return acceptResults.removeFirst()
    }

    override suspend fun confirmPair(
        pairIdentifier: PairIdentifier,
    ): PairingCallResult<PairingStatus> = error("Not used")
}

private class DeferredStatusPairingGateway : PairingGateway {
    val started = CompletableDeferred<Unit>()
    val result = CompletableDeferred<PairingCallResult<PairingStatus>>()

    override suspend fun getPairingStatus(): PairingCallResult<PairingStatus> {
        started.complete(Unit)
        return result.await()
    }

    override suspend fun createPairInvite(): PairingCallResult<PairInvite> = error("Not used")

    override suspend fun acceptPairInvite(
        code: String,
    ): PairingCallResult<PairingStatus.PendingConfirmation> = error("Not used")

    override suspend fun confirmPair(
        pairIdentifier: PairIdentifier,
    ): PairingCallResult<PairingStatus> = error("Not used")
}

private class ResolvePairingDuringSignOutPersistedSessionGateway(
    private val pairing: DeferredStatusPairingGateway,
    private val result: PairingCallResult<PairingStatus>,
    private val beforeResolvingPairing: () -> Unit,
) : PersistedWorkspaceSessionGateway {
    private val finishSignOut = CompletableDeferred<Unit>()
    private var hydrated = false
    private var pendingSignOut: MemberIdentifier? = null

    override suspend fun wasHydrated(memberIdentifier: MemberIdentifier): Boolean = hydrated

    override suspend fun recordHydrated(memberIdentifier: MemberIdentifier) {
        hydrated = true
    }

    override suspend fun pendingSignOut(): MemberIdentifier? = pendingSignOut

    override suspend fun beginSignOut(memberIdentifier: MemberIdentifier) {
        beforeResolvingPairing()
        pairing.result.complete(result)
        pendingSignOut = memberIdentifier
        finishSignOut.await()
        hydrated = false
    }

    override suspend fun completeSignOut(memberIdentifier: MemberIdentifier) {
        pendingSignOut = null
    }

    fun allowSignOutToFinish() {
        finishSignOut.complete(Unit)
    }
}

private class FakeAuthentication(
    private var identity: AuthenticatedIdentity? = null,
    var signOutResult: AuthenticationSignOut = AuthenticationSignOut.Complete,
) : AuthenticationGateway {
    var signOutCalls: Int = 0
        private set

    override suspend fun currentIdentity(): AuthenticatedIdentity? = identity

    override suspend fun signOut(): AuthenticationSignOut {
        signOutCalls += 1
        if (signOutResult == AuthenticationSignOut.Complete) identity = null
        return signOutResult
    }

    fun authenticate(uid: String) {
        identity = AuthenticatedIdentity(uid)
    }
}

private data object FailingClearPersistedWorkspaceSessionGateway :
    PersistedWorkspaceSessionGateway {
    override suspend fun wasHydrated(memberIdentifier: MemberIdentifier): Boolean = false

    override suspend fun recordHydrated(memberIdentifier: MemberIdentifier) = Unit

    override suspend fun pendingSignOut(): MemberIdentifier? = null

    override suspend fun beginSignOut(memberIdentifier: MemberIdentifier): Unit =
        error("Local marker cannot be cleared")

    override suspend fun completeSignOut(memberIdentifier: MemberIdentifier) = Unit
}

private data object FailingRecordPersistedWorkspaceSessionGateway :
    PersistedWorkspaceSessionGateway {
    override suspend fun wasHydrated(memberIdentifier: MemberIdentifier): Boolean = false

    override suspend fun recordHydrated(memberIdentifier: MemberIdentifier): Unit =
        error("Hydration marker cannot be recorded")

    override suspend fun pendingSignOut(): MemberIdentifier? = null

    override suspend fun beginSignOut(memberIdentifier: MemberIdentifier) = Unit

    override suspend fun completeSignOut(memberIdentifier: MemberIdentifier) = Unit
}

private class InMemoryPersistedWorkspaceSessionGateway : PersistedWorkspaceSessionGateway {
    private val hydratedMembers = mutableSetOf<MemberIdentifier>()
    private var pendingSignOutMemberIdentifier: MemberIdentifier? = null

    override suspend fun wasHydrated(memberIdentifier: MemberIdentifier): Boolean =
        memberIdentifier in hydratedMembers

    override suspend fun recordHydrated(memberIdentifier: MemberIdentifier) {
        check(pendingSignOutMemberIdentifier == null)
        hydratedMembers += memberIdentifier
    }

    override suspend fun pendingSignOut(): MemberIdentifier? = pendingSignOutMemberIdentifier

    override suspend fun beginSignOut(memberIdentifier: MemberIdentifier) {
        check(
            pendingSignOutMemberIdentifier == null ||
                pendingSignOutMemberIdentifier == memberIdentifier,
        )
        pendingSignOutMemberIdentifier = memberIdentifier
        hydratedMembers.clear()
    }

    override suspend fun completeSignOut(memberIdentifier: MemberIdentifier) {
        check(
            pendingSignOutMemberIdentifier == null ||
                pendingSignOutMemberIdentifier == memberIdentifier,
        )
        hydratedMembers.clear()
        pendingSignOutMemberIdentifier = null
    }
}

private class BlockingClearPersistedWorkspaceSessionGateway : PersistedWorkspaceSessionGateway {
    private val hydratedMembers = mutableSetOf<MemberIdentifier>()
    private val clearPermission = CompletableDeferred<Unit>()
    private var pendingSignOutMemberIdentifier: MemberIdentifier? = null

    override suspend fun wasHydrated(memberIdentifier: MemberIdentifier): Boolean =
        memberIdentifier in hydratedMembers

    override suspend fun recordHydrated(memberIdentifier: MemberIdentifier) {
        hydratedMembers += memberIdentifier
    }

    override suspend fun pendingSignOut(): MemberIdentifier? = pendingSignOutMemberIdentifier

    override suspend fun beginSignOut(memberIdentifier: MemberIdentifier) {
        clearPermission.await()
        check(
            pendingSignOutMemberIdentifier == null ||
                pendingSignOutMemberIdentifier == memberIdentifier,
        )
        pendingSignOutMemberIdentifier = memberIdentifier
        hydratedMembers.clear()
    }

    override suspend fun completeSignOut(memberIdentifier: MemberIdentifier) {
        check(pendingSignOutMemberIdentifier == memberIdentifier)
        pendingSignOutMemberIdentifier = null
        hydratedMembers.clear()
    }

    fun allowClear() {
        clearPermission.complete(Unit)
    }
}

private class BlockingSignOutAuthentication(
    uid: String,
) : AuthenticationGateway {
    private var identity: AuthenticatedIdentity? = AuthenticatedIdentity(uid)
    private val signOutPermission = CompletableDeferred<Unit>()
    val signOutStarted = CompletableDeferred<Unit>()

    var signOutCalls: Int = 0
        private set

    override suspend fun currentIdentity(): AuthenticatedIdentity? = identity

    override suspend fun signOut(): AuthenticationSignOut {
        signOutCalls += 1
        signOutStarted.complete(Unit)
        signOutPermission.await()
        identity = null
        return AuthenticationSignOut.Complete
    }

    fun allowSignOut() {
        signOutPermission.complete(Unit)
    }
}

private class DeferredHydrationGateway : OwnerWorkspaceHydrationGateway {
    private val result = CompletableDeferred<OwnerWorkspaceHydration>()

    override suspend fun hydrate(memberIdentifier: MemberIdentifier): OwnerWorkspaceHydration =
        result.await()
}

private class AccessTestTaskRepository : TaskRepository {
    private val creations =
        MutableStateFlow<Map<TaskIdentifier, StandaloneTaskCreation>>(emptyMap())
    private val recurringCreations =
        MutableStateFlow<Map<TaskIdentifier, RecurringSeriesCreation>>(emptyMap())
    private val occurrenceStates =
        MutableStateFlow<Map<OccurrenceKey, OccurrenceState>>(emptyMap())

    override suspend fun putStandaloneTaskIfAbsent(
        creation: StandaloneTaskCreation,
    ): StandaloneTaskCreation {
        val existing = creations.value[creation.task.identifier]
        if (existing != null) return existing

        creations.value = creations.value + (creation.task.identifier to creation)
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
            stored[taskIdentifier]?.takeIf { it.task.ownerIdentifier == ownerIdentifier }
        }

    override suspend fun putRecurringSeriesIfAbsent(
        creation: RecurringSeriesCreation,
    ): RecurringSeriesCreation {
        val existing = recurringCreations.value[creation.series.identifier]
        if (existing != null) return existing

        recurringCreations.value =
            recurringCreations.value + (creation.series.identifier to creation)
        return creation
    }

    override suspend fun putRecurringSeriesVisibility(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
        visibility: TaskVisibility,
    ): RecurringSeriesCreation? = error("Not used by this test")

    override fun observeRecurringSeries(
        ownerIdentifier: MemberIdentifier,
    ): Flow<List<RecurringSeriesCreation>> =
        recurringCreations.map { stored ->
            stored.values.filter { it.series.ownerIdentifier == ownerIdentifier }
        }

    override fun observeRecurringSeries(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
    ): Flow<RecurringSeriesCreation?> =
        recurringCreations.map { stored ->
            stored[taskIdentifier]?.takeIf { it.series.ownerIdentifier == ownerIdentifier }
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

private object AccessTestReminderGateway : ReminderGateway {
    override suspend fun reconcile(reminder: ScheduledTaskReminder): Unit =
        error("Not used by access-state tests")
}
