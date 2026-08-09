package com.gaylord.duit.platform.firebase

import com.gaylord.duit.workspace.AuthenticationSignOut
import com.gaylord.duit.workspace.AuthenticationSignOutFailure
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import java.util.concurrent.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Owns the replaceable Firestore instance so account changes cannot share a local cache. */
internal class FirebaseFirestoreSession(
    private val firebaseApp: FirebaseApp,
) {
    private val mutex = Mutex()
    private var firestore: FirebaseFirestore = FirebaseFirestore.getInstance(firebaseApp)
    private var terminated: Boolean = false

    fun current(): FirebaseFirestore = firestore

    /**
     * Drains and clears account-local state. A non-null UID forces a server read before any
     * destructive step; null is reserved for resuming after Firebase Auth was already cleared.
     */
    suspend fun clearForSignOut(
        authenticatedUidRequiringOnlineGate: String?,
    ): AuthenticationSignOut =
        mutex.withLock {
            if (!terminated) {
                if (authenticatedUidRequiringOnlineGate != null) {
                    try {
                        firestore.collection(MemberApprovalsCollection)
                            .document(authenticatedUidRequiringOnlineGate)
                            .get(Source.SERVER)
                            .await()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        return@withLock AuthenticationSignOut.Failed(
                            AuthenticationSignOutFailure.ServerUnavailable,
                        )
                    }
                }

                try {
                    firestore.waitForPendingWrites().await()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    return@withLock AuthenticationSignOut.Failed(
                        AuthenticationSignOutFailure.ServerUnavailable,
                    )
                }

                try {
                    firestore.terminate().await()
                    terminated = true
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    return@withLock AuthenticationSignOut.Failed(
                        AuthenticationSignOutFailure.LocalDataClearFailed,
                    )
                }
            }

            try {
                firestore.clearPersistence().await()
                firestore = FirebaseFirestore.getInstance(firebaseApp)
                terminated = false
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

private const val MemberApprovalsCollection = "memberApprovals"
