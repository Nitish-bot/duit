package com.gaylord.duit.platform.firebase

import android.app.Activity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.gaylord.duit.workspace.AuthenticatedIdentity
import com.gaylord.duit.workspace.AuthenticationGateway
import com.gaylord.duit.workspace.AuthenticationSignOut
import com.gaylord.duit.workspace.AuthenticationSignOutFailure
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import java.util.concurrent.CancellationException
import kotlinx.coroutines.tasks.await

internal class FirebaseAuthenticationAdapter(
    private val auth: FirebaseAuth,
    private val credentialManager: CredentialManager,
    private val googleServerClientId: String,
    private val firestoreSession: FirebaseFirestoreSession,
) : AuthenticationGateway {
    override suspend fun currentIdentity(): AuthenticatedIdentity? =
        auth.currentUser?.uid?.let(::AuthenticatedIdentity)

    override suspend fun signOut(): AuthenticationSignOut {
        // A null user can occur only while resuming an earlier attempt that already passed the
        // online gate, cleared Firestore, and called Firebase Auth sign-out before Credential
        // Manager clearance failed or the process stopped.
        val cacheClearance =
            firestoreSession.clearForSignOut(
                authenticatedUidRequiringOnlineGate = auth.currentUser?.uid,
            )
        if (cacheClearance is AuthenticationSignOut.Failed) return cacheClearance

        auth.signOut()
        return try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            AuthenticationSignOut.Complete
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            AuthenticationSignOut.Failed(
                AuthenticationSignOutFailure.CredentialStateClearFailed,
            )
        }
    }

    suspend fun signIn(activity: Activity): FirebaseSignInOutcome {
        val googleIdOption =
            GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(googleServerClientId)
                .setAutoSelectEnabled(false)
                .build()
        val request =
            GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

        return try {
            val credential = credentialManager.getCredential(activity, request).credential
            if (
                credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return FirebaseSignInOutcome.Failed(
                    FirebaseSignInFailure.InvalidGoogleCredential,
                )
            }
            val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val firebaseCredential = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
            val authenticatedUser =
                auth.signInWithCredential(firebaseCredential).await().user
                    ?: return FirebaseSignInOutcome.Failed(
                        FirebaseSignInFailure.FirebaseAuthenticationFailure,
                    )

            FirebaseSignInOutcome.SignedIn(AuthenticatedIdentity(authenticatedUser.uid))
        } catch (_: GetCredentialCancellationException) {
            FirebaseSignInOutcome.Cancelled
        } catch (_: NoCredentialException) {
            FirebaseSignInOutcome.Failed(FirebaseSignInFailure.NoGoogleCredentialAvailable)
        } catch (_: GetCredentialException) {
            FirebaseSignInOutcome.Failed(FirebaseSignInFailure.CredentialProviderFailure)
        } catch (_: GoogleIdTokenParsingException) {
            FirebaseSignInOutcome.Failed(FirebaseSignInFailure.InvalidGoogleCredential)
        } catch (_: FirebaseAuthException) {
            FirebaseSignInOutcome.Failed(FirebaseSignInFailure.FirebaseAuthenticationFailure)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            FirebaseSignInOutcome.Failed(FirebaseSignInFailure.FirebaseAuthenticationFailure)
        }
    }
}
