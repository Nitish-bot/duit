package com.gaylord.duit.workspace

import com.gaylord.duit.domain.model.MemberIdentifier
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@JvmInline
value class AuthenticatedIdentity(val uid: String)

interface AuthenticationGateway {
    suspend fun currentIdentity(): AuthenticatedIdentity?

    suspend fun signOut(): AuthenticationSignOut
}

sealed interface AuthenticationSignOut {
    data object Complete : AuthenticationSignOut

    data class Failed(val failure: AuthenticationSignOutFailure) : AuthenticationSignOut
}

enum class AuthenticationSignOutFailure {
    ServerUnavailable,
    LocalDataClearFailed,
    CredentialStateClearFailed,
}

fun interface MemberApprovalGateway {
    suspend fun approvalFor(identity: AuthenticatedIdentity): MemberApproval
}

sealed interface MemberApproval {
    data object Required : MemberApproval

    data object Approved : MemberApproval

    data class Failed(val failure: MemberApprovalFailure) : MemberApproval
}

enum class MemberApprovalFailure {
    ServerUnavailable,
}

fun interface OwnerWorkspaceHydrationGateway {
    suspend fun hydrate(memberIdentifier: MemberIdentifier): OwnerWorkspaceHydration
}

sealed interface OwnerWorkspaceHydration {
    data object Complete : OwnerWorkspaceHydration

    data class Failed(val failure: OwnerWorkspaceHydrationFailure) : OwnerWorkspaceHydration
}

enum class OwnerWorkspaceHydrationFailure {
    ServerUnavailable,
    LocalSessionRecordFailed,
}

interface PersistedWorkspaceSessionGateway {
    suspend fun wasHydrated(memberIdentifier: MemberIdentifier): Boolean

    suspend fun recordHydrated(memberIdentifier: MemberIdentifier)

    suspend fun pendingSignOut(): MemberIdentifier?

    /** Atomically records cleanup intent and prevents any hydrated workspace from reopening. */
    suspend fun beginSignOut(memberIdentifier: MemberIdentifier)

    /** Clears cleanup intent only after every account-local store has been cleared successfully. */
    suspend fun completeSignOut(memberIdentifier: MemberIdentifier)
}

enum class WorkspaceAccessAction {
    SignIn,
    Retry,
    SignOut,
}

enum class WorkspaceRestorationSource {
    OnDevice,
    Server,
}

sealed interface WorkspaceAccessState {
    val taskCommandsAllowed: Boolean
    val availableActions: Set<WorkspaceAccessAction>

    data object SignInRequired : WorkspaceAccessState {
        override val taskCommandsAllowed: Boolean = false
        override val availableActions: Set<WorkspaceAccessAction> =
            setOf(WorkspaceAccessAction.SignIn)
    }

    data class ApprovalRequired(
        val authenticatedUid: String,
    ) : WorkspaceAccessState {
        override val taskCommandsAllowed: Boolean = false
        override val availableActions: Set<WorkspaceAccessAction> =
            setOf(
                WorkspaceAccessAction.Retry,
                WorkspaceAccessAction.SignOut,
            )
    }

    data class Hydrating(
        val memberIdentifier: MemberIdentifier,
    ) : WorkspaceAccessState {
        override val taskCommandsAllowed: Boolean = false
        override val availableActions: Set<WorkspaceAccessAction> =
            setOf(WorkspaceAccessAction.SignOut)
    }

    data class ApprovalCheckFailed(
        val authenticatedUid: String,
        val failure: MemberApprovalFailure,
    ) : WorkspaceAccessState {
        override val taskCommandsAllowed: Boolean = false
        override val availableActions: Set<WorkspaceAccessAction> =
            setOf(
                WorkspaceAccessAction.Retry,
                WorkspaceAccessAction.SignOut,
            )
    }

    data class Failed(
        val memberIdentifier: MemberIdentifier,
        val failure: OwnerWorkspaceHydrationFailure,
    ) : WorkspaceAccessState {
        override val taskCommandsAllowed: Boolean = false
        override val availableActions: Set<WorkspaceAccessAction> =
            setOf(
                WorkspaceAccessAction.Retry,
                WorkspaceAccessAction.SignOut,
            )
    }

    data class SignOutFailed(
        val authenticatedUid: String,
        val failure: AuthenticationSignOutFailure,
    ) : WorkspaceAccessState {
        override val taskCommandsAllowed: Boolean = false
        override val availableActions: Set<WorkspaceAccessAction> =
            setOf(
                WorkspaceAccessAction.Retry,
                WorkspaceAccessAction.SignOut,
            )
    }

    data class SigningOut(
        val authenticatedUid: String,
    ) : WorkspaceAccessState {
        override val taskCommandsAllowed: Boolean = false
        override val availableActions: Set<WorkspaceAccessAction> = emptySet()
    }

    data class Ready(
        val memberIdentifier: MemberIdentifier,
        val restorationSource: WorkspaceRestorationSource,
    ) : WorkspaceAccessState {
        override val taskCommandsAllowed: Boolean = true
        override val availableActions: Set<WorkspaceAccessAction> = emptySet()
    }
}

class WorkspaceLockedException(
    val accessState: WorkspaceAccessState,
) : IllegalStateException("Task commands are unavailable while workspace access is $accessState")

internal interface WorkspaceAccessController {
    val state: StateFlow<WorkspaceAccessState>

    suspend fun restoreSession()

    suspend fun retryAccess()

    suspend fun signOut()
}

internal class ReadyWorkspaceAccessController(
    memberIdentifier: MemberIdentifier,
) : WorkspaceAccessController {
    override val state: StateFlow<WorkspaceAccessState> =
        MutableStateFlow(
            WorkspaceAccessState.Ready(
                memberIdentifier = memberIdentifier,
                restorationSource = WorkspaceRestorationSource.OnDevice,
            ),
        ).asStateFlow()

    override suspend fun restoreSession() = Unit

    override suspend fun retryAccess() = Unit

    override suspend fun signOut() = Unit
}

internal data class WorkspaceSignOutOperation(
    val memberIdentifier: MemberIdentifier,
    val result: Deferred<AuthenticationSignOut>,
)

/**
 * Owns account cleanup in a process-level scope and deduplicates it across Activity recreation.
 * The persisted gateway remains the authority after process death; the in-memory request closes
 * the smaller window before that durable write completes.
 */
internal class WorkspaceSessionOperationCoordinator(
    private val applicationScope: CoroutineScope,
) {
    private val operationLock = Any()

    @Volatile
    private var requestedMemberIdentifier: MemberIdentifier? = null

    private var activeSignOut: WorkspaceSignOutOperation? = null

    fun pendingSignOutRequest(): MemberIdentifier? = requestedMemberIdentifier

    fun startOrJoinSignOut(
        memberIdentifier: MemberIdentifier,
        operation: suspend (MemberIdentifier) -> AuthenticationSignOut,
    ): WorkspaceSignOutOperation =
        synchronized(operationLock) {
            activeSignOut?.let { return@synchronized it }

            val cleanupMemberIdentifier = requestedMemberIdentifier ?: memberIdentifier
            requestedMemberIdentifier = cleanupMemberIdentifier

            lateinit var signOutOperation: WorkspaceSignOutOperation
            val result =
                applicationScope.async(start = CoroutineStart.LAZY) {
                    val outcome = operation(cleanupMemberIdentifier)
                    if (outcome == AuthenticationSignOut.Complete) {
                        synchronized(operationLock) {
                            requestedMemberIdentifier = null
                        }
                    }
                    outcome
                }
            signOutOperation =
                WorkspaceSignOutOperation(
                    memberIdentifier = cleanupMemberIdentifier,
                    result = result,
                )
            activeSignOut = signOutOperation
            result.invokeOnCompletion {
                synchronized(operationLock) {
                    if (activeSignOut === signOutOperation) activeSignOut = null
                }
            }
            result.start()
            signOutOperation
        }
}

internal class AuthenticatedWorkspaceAccessController(
    private val authentication: AuthenticationGateway,
    private val approval: MemberApprovalGateway,
    private val hydration: OwnerWorkspaceHydrationGateway,
    private val persistedSession: PersistedWorkspaceSessionGateway,
    private val sessionOperations: WorkspaceSessionOperationCoordinator,
) : WorkspaceAccessController {
    private val mutableState =
        MutableStateFlow<WorkspaceAccessState>(WorkspaceAccessState.SignInRequired)

    override val state: StateFlow<WorkspaceAccessState> = mutableState.asStateFlow()

    override suspend fun restoreSession() {
        if (mutableState.value is WorkspaceAccessState.SigningOut) return

        val pendingSignOut =
            sessionOperations.pendingSignOutRequest()
                ?: try {
                    persistedSession.pendingSignOut()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    mutableState.value =
                        WorkspaceAccessState.SignOutFailed(
                            authenticatedUid = "",
                            failure = AuthenticationSignOutFailure.LocalDataClearFailed,
                        )
                    return
                }
        if (pendingSignOut != null) {
            resumeSignOut(pendingSignOut)
            return
        }

        val identity = authentication.currentIdentity()
        if (identity == null) {
            mutableState.value = WorkspaceAccessState.SignInRequired
            return
        }

        val memberIdentifier = MemberIdentifier(identity.uid)
        val wasHydrated = persistedSession.wasHydrated(memberIdentifier)

        when (val memberApproval = approval.approvalFor(identity)) {
            MemberApproval.Required -> {
                mutableState.value =
                    WorkspaceAccessState.ApprovalRequired(authenticatedUid = identity.uid)
            }
            MemberApproval.Approved -> {
                if (wasHydrated) {
                    mutableState.value =
                        WorkspaceAccessState.Ready(
                            memberIdentifier = memberIdentifier,
                            restorationSource = WorkspaceRestorationSource.OnDevice,
                        )
                    return
                }

                mutableState.value = WorkspaceAccessState.Hydrating(memberIdentifier)
                when (val result = hydration.hydrate(memberIdentifier)) {
                    OwnerWorkspaceHydration.Complete -> {
                        try {
                            persistedSession.recordHydrated(memberIdentifier)
                            if (sessionOperations.pendingSignOutRequest() == null) {
                                mutableState.value =
                                    WorkspaceAccessState.Ready(
                                        memberIdentifier = memberIdentifier,
                                        restorationSource = WorkspaceRestorationSource.Server,
                                    )
                            }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            if (sessionOperations.pendingSignOutRequest() == null) {
                                mutableState.value =
                                    WorkspaceAccessState.Failed(
                                        memberIdentifier = memberIdentifier,
                                        failure =
                                            OwnerWorkspaceHydrationFailure.LocalSessionRecordFailed,
                                    )
                            }
                        }
                    }
                    is OwnerWorkspaceHydration.Failed -> {
                        mutableState.value =
                            WorkspaceAccessState.Failed(
                                memberIdentifier = memberIdentifier,
                                failure = result.failure,
                            )
                    }
                }
            }
            is MemberApproval.Failed -> {
                mutableState.value =
                    if (wasHydrated) {
                        WorkspaceAccessState.Ready(
                            memberIdentifier = memberIdentifier,
                            restorationSource = WorkspaceRestorationSource.OnDevice,
                        )
                    } else {
                        WorkspaceAccessState.ApprovalCheckFailed(
                            authenticatedUid = identity.uid,
                            failure = memberApproval.failure,
                        )
                    }
            }
        }
    }

    override suspend fun retryAccess() {
        when (mutableState.value) {
            is WorkspaceAccessState.ApprovalRequired,
            is WorkspaceAccessState.ApprovalCheckFailed,
            is WorkspaceAccessState.Failed,
            is WorkspaceAccessState.SignOutFailed -> restoreSession()
            else -> Unit
        }
    }

    override suspend fun signOut() {
        val pendingSignOut = sessionOperations.pendingSignOutRequest()
        val memberIdentifier =
            pendingSignOut
                ?: mutableState.value.authenticatedUidOrNull()?.let(::MemberIdentifier)
                ?: authentication.currentIdentity()?.uid?.let(::MemberIdentifier)

        if (memberIdentifier == null) {
            mutableState.value = WorkspaceAccessState.SignInRequired
            return
        }

        resumeSignOut(memberIdentifier)
    }

    private suspend fun resumeSignOut(memberIdentifier: MemberIdentifier) {
        val signOut =
            sessionOperations.startOrJoinSignOut(memberIdentifier) { cleanupMemberIdentifier ->
                completeSignOut(cleanupMemberIdentifier)
            }
        mutableState.value = WorkspaceAccessState.SigningOut(signOut.memberIdentifier.value)

        applySignOutResult(
            memberIdentifier = signOut.memberIdentifier,
            result = signOut.result.await(),
        )
    }

    private fun applySignOutResult(
        memberIdentifier: MemberIdentifier,
        result: AuthenticationSignOut,
    ) {
        when (result) {
            AuthenticationSignOut.Complete -> {
                mutableState.value = WorkspaceAccessState.SignInRequired
            }
            is AuthenticationSignOut.Failed -> {
                mutableState.value =
                    WorkspaceAccessState.SignOutFailed(
                        authenticatedUid = memberIdentifier.value,
                        failure = result.failure,
                    )
            }
        }
    }

    private suspend fun completeSignOut(
        memberIdentifier: MemberIdentifier,
    ): AuthenticationSignOut {
        try {
            persistedSession.beginSignOut(memberIdentifier)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return AuthenticationSignOut.Failed(
                AuthenticationSignOutFailure.LocalDataClearFailed,
            )
        }

        val authenticationSignOut = authentication.signOut()
        if (authenticationSignOut is AuthenticationSignOut.Failed) return authenticationSignOut

        return try {
            persistedSession.completeSignOut(memberIdentifier)
            AuthenticationSignOut.Complete
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            AuthenticationSignOut.Failed(
                AuthenticationSignOutFailure.LocalDataClearFailed,
            )
        }
    }
}

private fun WorkspaceAccessState.authenticatedUidOrNull(): String? =
    when (this) {
        is WorkspaceAccessState.ApprovalCheckFailed -> authenticatedUid
        is WorkspaceAccessState.ApprovalRequired -> authenticatedUid
        is WorkspaceAccessState.Failed -> memberIdentifier.value
        is WorkspaceAccessState.Hydrating -> memberIdentifier.value
        is WorkspaceAccessState.Ready -> memberIdentifier.value
        is WorkspaceAccessState.SignOutFailed -> authenticatedUid
        is WorkspaceAccessState.SigningOut -> authenticatedUid
        WorkspaceAccessState.SignInRequired -> null
    }
