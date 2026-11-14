package com.gaylord.duit.platform.firebase

import android.app.Activity
import com.gaylord.duit.data.repository.TaskRepository
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.notifications.ReminderGateway
import com.gaylord.duit.pairing.PairingGateway
import com.gaylord.duit.workspace.AuthenticatedIdentity
import com.gaylord.duit.workspace.AuthenticationGateway
import com.gaylord.duit.workspace.AuthenticationSignOut
import com.gaylord.duit.workspace.MemberApproval
import com.gaylord.duit.workspace.MemberApprovalFailure
import com.gaylord.duit.workspace.MemberApprovalGateway
import com.gaylord.duit.workspace.OwnerWorkspaceHydration
import com.gaylord.duit.workspace.OwnerWorkspaceHydrationFailure
import com.gaylord.duit.workspace.OwnerWorkspaceHydrationGateway
import com.gaylord.duit.workspace.PersistedWorkspaceSessionGateway

sealed interface FirebaseRuntimeAvailability {
    data object Available : FirebaseRuntimeAvailability

    data class Unavailable(
        val reason: FirebaseRuntimeUnavailableReason,
    ) : FirebaseRuntimeAvailability
}

enum class FirebaseRuntimeUnavailableReason {
    DevelopmentConfigurationMissing,
    ProductionConfigurationUnavailable,
}

sealed interface FirebaseSignInOutcome {
    data class SignedIn(
        val identity: AuthenticatedIdentity,
    ) : FirebaseSignInOutcome

    data object Cancelled : FirebaseSignInOutcome

    data class Failed(
        val failure: FirebaseSignInFailure,
    ) : FirebaseSignInOutcome
}

enum class FirebaseSignInFailure {
    RuntimeUnavailable,
    NoGoogleCredentialAvailable,
    InvalidGoogleCredential,
    CredentialProviderFailure,
    FirebaseAuthenticationFailure,
}

data class FirebaseWorkspaceServices(
    val tasks: TaskRepository,
    val reminders: ReminderGateway?,
    val pairing: PairingGateway,
)

interface FirebaseRuntime {
    val availability: FirebaseRuntimeAvailability
    val authentication: AuthenticationGateway
    val approval: MemberApprovalGateway
    val hydration: OwnerWorkspaceHydrationGateway
    val persistedSession: PersistedWorkspaceSessionGateway
    val workspaceServices: FirebaseWorkspaceServices?

    suspend fun signIn(activity: Activity): FirebaseSignInOutcome
}

internal class UnavailableFirebaseRuntime(
    reason: FirebaseRuntimeUnavailableReason,
) : FirebaseRuntime {
    override val availability: FirebaseRuntimeAvailability =
        FirebaseRuntimeAvailability.Unavailable(reason)
    override val authentication: AuthenticationGateway = UnavailableAuthenticationGateway
    override val approval: MemberApprovalGateway =
        MemberApprovalGateway {
            MemberApproval.Failed(MemberApprovalFailure.ServerUnavailable)
        }
    override val hydration: OwnerWorkspaceHydrationGateway =
        OwnerWorkspaceHydrationGateway {
            OwnerWorkspaceHydration.Failed(OwnerWorkspaceHydrationFailure.ServerUnavailable)
        }
    override val persistedSession: PersistedWorkspaceSessionGateway =
        UnavailablePersistedWorkspaceSessionGateway
    override val workspaceServices: FirebaseWorkspaceServices? = null

    override suspend fun signIn(activity: Activity): FirebaseSignInOutcome =
        FirebaseSignInOutcome.Failed(FirebaseSignInFailure.RuntimeUnavailable)
}

private data object UnavailableAuthenticationGateway : AuthenticationGateway {
    override suspend fun currentIdentity(): AuthenticatedIdentity? = null

    override suspend fun signOut() = AuthenticationSignOut.Complete
}

private data object UnavailablePersistedWorkspaceSessionGateway :
    PersistedWorkspaceSessionGateway {
    override suspend fun wasHydrated(
        memberIdentifier: MemberIdentifier,
    ): Boolean = false

    override suspend fun recordHydrated(
        memberIdentifier: MemberIdentifier,
    ) = Unit

    override suspend fun pendingSignOut(): MemberIdentifier? = null

    override suspend fun beginSignOut(memberIdentifier: MemberIdentifier) = Unit

    override suspend fun completeSignOut(memberIdentifier: MemberIdentifier) = Unit
}
