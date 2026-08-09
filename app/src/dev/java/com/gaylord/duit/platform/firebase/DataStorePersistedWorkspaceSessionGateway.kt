package com.gaylord.duit.platform.firebase

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.workspace.PersistedWorkspaceSessionGateway
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

internal class DataStorePersistedWorkspaceSessionGateway(
    context: Context,
) : PersistedWorkspaceSessionGateway {
    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { context.preferencesDataStoreFile(SessionDataStoreFileName) },
        )

    override suspend fun wasHydrated(memberIdentifier: MemberIdentifier): Boolean =
        safely(default = false) {
            dataStore.data.first()[HydratedMemberIdentifiers]
                ?.contains(memberIdentifier.value) == true
        }

    override suspend fun recordHydrated(memberIdentifier: MemberIdentifier) {
        dataStore.edit { preferences ->
            check(preferences[PendingSignOutMemberIdentifier] == null) {
                "Cannot record hydration while sign out is pending"
            }

            val hydratedMembers = preferences[HydratedMemberIdentifiers].orEmpty()
            preferences[HydratedMemberIdentifiers] = hydratedMembers + memberIdentifier.value
        }
    }

    override suspend fun pendingSignOut(): MemberIdentifier? =
        dataStore.data.first()[PendingSignOutMemberIdentifier]?.let(::MemberIdentifier)

    override suspend fun beginSignOut(memberIdentifier: MemberIdentifier) {
        dataStore.edit { preferences ->
            val pendingMemberIdentifier = preferences[PendingSignOutMemberIdentifier]
            check(
                pendingMemberIdentifier == null ||
                    pendingMemberIdentifier == memberIdentifier.value,
            ) {
                "Cannot replace another Member's pending sign out"
            }
            preferences[PendingSignOutMemberIdentifier] = memberIdentifier.value
            preferences.remove(HydratedMemberIdentifiers)
        }
    }

    override suspend fun completeSignOut(memberIdentifier: MemberIdentifier) {
        dataStore.edit { preferences ->
            val pendingMemberIdentifier = preferences[PendingSignOutMemberIdentifier]
            check(
                pendingMemberIdentifier == null ||
                    pendingMemberIdentifier == memberIdentifier.value,
            ) {
                "Cannot complete another Member's pending sign out"
            }
            preferences.remove(HydratedMemberIdentifiers)
            preferences.remove(PendingSignOutMemberIdentifier)
        }
    }

    private suspend fun <Result> safely(
        default: Result,
        action: suspend () -> Result,
    ): Result =
        try {
            action()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            default
        }
}

private const val SessionDataStoreFileName = "firebase_workspace_session"

private val HydratedMemberIdentifiers = stringSetPreferencesKey("hydrated_member_identifiers")

private val PendingSignOutMemberIdentifier =
    stringPreferencesKey("pending_sign_out_member_identifier")
