package com.gaylord.duit.feature.together

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.pairing.PairInvite
import com.gaylord.duit.domain.pairing.PairingStatus
import com.gaylord.duit.workspace.DuitWorkspace
import com.gaylord.duit.workspace.PairCommand
import com.gaylord.duit.workspace.PairCommandFailure
import com.gaylord.duit.workspace.PairCommandOutcome
import com.gaylord.duit.workspace.WorkspaceAccessState
import com.gaylord.duit.workspace.WorkspaceLockedException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PairingRefreshPolicy(
    val initialDelayMillis: Long = 1_000,
    val maximumDelayMillis: Long = 15_000,
    val backoffMultiplier: Int = 2,
) {
    init {
        require(initialDelayMillis > 0)
        require(maximumDelayMillis >= initialDelayMillis)
        require(backoffMultiplier >= 1)
    }

    internal fun nextDelayMillis(currentDelayMillis: Long): Long {
        val multiplier = backoffMultiplier.toLong()
        return if (currentDelayMillis > maximumDelayMillis / multiplier) {
            maximumDelayMillis
        } else {
            minOf(maximumDelayMillis, currentDelayMillis * multiplier)
        }
    }
}

sealed interface TogetherEvent {
    data object Started : TogetherEvent

    data object Stopped : TogetherEvent

    data object Retry : TogetherEvent

    data object CreateInvite : TogetherEvent

    data class AcceptInvite(
        val code: String,
    ) : TogetherEvent

    data object ConfirmPartnerIdentity : TogetherEvent
}

class TogetherViewModel internal constructor(
    private val workspace: DuitWorkspace,
    coroutineScope: CoroutineScope?,
    private val refreshPolicy: PairingRefreshPolicy,
    private val epochMillis: () -> Long,
) : ViewModel() {
    constructor(workspace: DuitWorkspace) : this(
        workspace = workspace,
        coroutineScope = null,
        refreshPolicy = PairingRefreshPolicy(),
        epochMillis = System::currentTimeMillis,
    )

    private val scope = coroutineScope ?: viewModelScope
    private val mutableState = MutableStateFlow<TogetherState>(TogetherState.Loading)
    private var commandJob: Job? = null
    private var trackedRefreshJob: Job? = null
    private var togetherStarted = false
    private var activeInvite: PairInvite? = null
    private var activeInviteDeadlineEpochMillis: Long = 0
    private var observedMemberIdentifier: MemberIdentifier? =
        (workspace.accessState.value as? WorkspaceAccessState.Ready)?.memberIdentifier

    val state: StateFlow<TogetherState> = mutableState.asStateFlow()

    init {
        scope.launch {
            workspace.accessState.collect { accessState ->
                val memberIdentifier =
                    (accessState as? WorkspaceAccessState.Ready)?.memberIdentifier
                if (memberIdentifier != observedMemberIdentifier) {
                    observedMemberIdentifier = memberIdentifier
                    resetMemberPairingState(refresh = true)
                }
            }
        }
    }

    fun onEvent(event: TogetherEvent) {
        when (event) {
            TogetherEvent.Started -> startTogether()
            TogetherEvent.Stopped -> stopTogether()
            TogetherEvent.Retry -> retry()
            TogetherEvent.CreateInvite -> createPairInvite()
            is TogetherEvent.AcceptInvite -> acceptPairInvite(event.code)
            TogetherEvent.ConfirmPartnerIdentity -> confirmPartnerIdentity()
        }
    }

    private fun startTogether() {
        if (togetherStarted) return
        togetherStarted = true
        if (mutableState.value is TogetherState.InviteReady) {
            startTrackedRefreshIfNeeded()
        } else if (commandJob?.isActive != true) {
            mutableState.value = TogetherState.Loading
            execute(PairCommand.RefreshStatus)
        }
    }

    private fun stopTogether() {
        if (!togetherStarted) return
        togetherStarted = false
        trackedRefreshJob?.cancel()
    }

    private fun retry() {
        if (!togetherStarted) return
        mutableState.value = TogetherState.Loading
        trackedRefreshJob?.cancel()
        execute(PairCommand.RefreshStatus)
    }

    private fun createPairInvite() {
        if (
            !togetherStarted ||
            mutableState.value != TogetherState.Unpaired ||
            commandJob?.isActive == true
        ) {
            return
        }
        mutableState.value = TogetherState.Loading
        trackedRefreshJob?.cancel()
        execute(PairCommand.CreateInvite)
    }

    private fun acceptPairInvite(code: String) {
        if (
            code.isBlank() ||
            !togetherStarted ||
            mutableState.value != TogetherState.Unpaired ||
            commandJob?.isActive == true
        ) {
            return
        }
        mutableState.value = TogetherState.Loading
        trackedRefreshJob?.cancel()
        execute(PairCommand.AcceptInvite(code))
    }

    private fun confirmPartnerIdentity() {
        val review = mutableState.value as? TogetherState.PartnerIdentityReview ?: return
        if (!togetherStarted || commandJob?.isActive == true) return
        mutableState.value = TogetherState.Loading
        trackedRefreshJob?.cancel()
        execute(PairCommand.ConfirmPair(review.pairIdentifier))
    }

    private fun execute(command: PairCommand) {
        if (commandJob?.isActive == true) return
        commandJob = scope.launch { render(executePairCommand(command)) }
    }

    private fun render(outcome: PairCommandOutcome) {
        when (outcome) {
            is PairCommandOutcome.StatusChanged -> render(outcome.status)
            is PairCommandOutcome.InviteCreated -> {
                activeInvite = outcome.invite
                activeInviteDeadlineEpochMillis = localInviteDeadline(outcome.invite)
                mutableState.value = TogetherState.InviteReady(outcome.invite)
                startTrackedRefreshIfNeeded()
            }
            is PairCommandOutcome.Failed -> render(outcome.failure)
            PairCommandOutcome.MemberSessionChanged ->
                // The access collector is the single place that refreshes for a changed Member.
                resetMemberPairingState(refresh = false)
        }
    }

    private fun render(failure: PairCommandFailure) {
        mutableState.value =
            when (failure) {
                PairCommandFailure.ConnectionRequired -> TogetherState.ConnectionRequired
                is PairCommandFailure.Recoverable ->
                    TogetherState.RecoverableError(failure.message)
            }
    }

    private fun render(status: PairingStatus) {
        when (status) {
            PairingStatus.Unpaired -> {
                val invite = activeInvite?.takeIf { activeInviteDeadlineEpochMillis > epochMillis() }
                activeInvite = invite
                mutableState.value =
                    if (invite == null) TogetherState.Unpaired else TogetherState.InviteReady(invite)
            }
            is PairingStatus.PendingConfirmation -> {
                activeInvite = null
                mutableState.value =
                    if (status.selfConfirmed) {
                        TogetherState.WaitingForPartner(
                            status.pairIdentifier,
                            status.partnerIdentity,
                        )
                    } else {
                        TogetherState.PartnerIdentityReview(
                            status.pairIdentifier,
                            status.partnerIdentity,
                            status.partnerConfirmed,
                        )
                    }
            }
            is PairingStatus.Active -> {
                activeInvite = null
                mutableState.value =
                    TogetherState.ActivePair(status.pairIdentifier, status.partnerIdentity)
            }
        }
        startTrackedRefreshIfNeeded()
    }

    private fun startTrackedRefreshIfNeeded() {
        if (
            !togetherStarted ||
            trackedRefreshJob?.isActive == true ||
            !mutableState.value.requiresStatusRefresh()
        ) {
            return
        }
        trackedRefreshJob =
            scope.launch {
                var nextDelayMillis = refreshPolicy.initialDelayMillis
                while (isActive && togetherStarted && mutableState.value.requiresStatusRefresh()) {
                    val remainingMillis =
                        (mutableState.value as? TogetherState.InviteReady)
                            ?.let { activeInviteDeadlineEpochMillis - epochMillis() }
                    val delayMillis =
                        if (remainingMillis != null && remainingMillis > 0) {
                            minOf(nextDelayMillis, remainingMillis)
                        } else {
                            nextDelayMillis
                        }
                    delay(delayMillis)

                    // At local expiry this remains an authoritative server check: acceptance may
                    // have committed just before the code expired.
                    val outcome = executePairCommand(PairCommand.RefreshStatus)
                    // One failed background poll must not end waiting; keep the current state.
                    if (
                        outcome is PairCommandOutcome.Failed &&
                        mutableState.value.requiresStatusRefresh()
                    ) {
                        nextDelayMillis = refreshPolicy.nextDelayMillis(nextDelayMillis)
                        continue
                    }
                    render(outcome)
                    nextDelayMillis = refreshPolicy.nextDelayMillis(nextDelayMillis)
                }
            }
    }

    private suspend fun executePairCommand(command: PairCommand): PairCommandOutcome =
        try {
            workspace.execute(command)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: WorkspaceLockedException) {
            PairCommandOutcome.MemberSessionChanged
        }

    /**
     * The device clock may be wrong, so the deadline is measured from receipt using the server's
     * remaining lifetime, falling back to the fixed Pair Invite lifetime.
     */
    private fun localInviteDeadline(invite: PairInvite): Long {
        val receivedAt = epochMillis()
        val remaining = invite.expiresAtEpochMillis - receivedAt
        val lifetime =
            if (remaining in 1..PairInviteLifetimeMillis) remaining else PairInviteLifetimeMillis
        return receivedAt + lifetime
    }

    private fun resetMemberPairingState(refresh: Boolean) {
        commandJob?.cancel()
        commandJob = null
        trackedRefreshJob?.cancel()
        trackedRefreshJob = null
        activeInvite = null
        mutableState.value = TogetherState.Loading
        if (
            refresh &&
            togetherStarted &&
            workspace.accessState.value is WorkspaceAccessState.Ready
        ) {
            execute(PairCommand.RefreshStatus)
        }
    }

    class Factory(
        private val workspace: DuitWorkspace,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TogetherViewModel::class.java))
            return TogetherViewModel(workspace) as T
        }
    }
}

/** The server's fixed Pair Invite lifetime. */
private const val PairInviteLifetimeMillis = 15 * 60_000L

private fun TogetherState.requiresStatusRefresh(): Boolean =
    this is TogetherState.InviteReady ||
        this is TogetherState.PartnerIdentityReview ||
        this is TogetherState.WaitingForPartner
