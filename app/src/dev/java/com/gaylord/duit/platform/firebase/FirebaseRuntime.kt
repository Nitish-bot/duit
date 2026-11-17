package com.gaylord.duit.platform.firebase

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import com.gaylord.duit.BuildConfig
import com.gaylord.duit.workspace.AuthenticationGateway
import com.gaylord.duit.workspace.MemberApprovalGateway
import com.gaylord.duit.workspace.OwnerWorkspaceHydrationGateway
import com.gaylord.duit.workspace.PersistedWorkspaceSessionGateway
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions

object FirebaseRuntimeFactory {
    @Volatile
    private var runtime: FirebaseRuntime? = null

    fun create(context: Context): FirebaseRuntime =
        runtime ?: synchronized(this) {
            runtime ?: createOnce(context.applicationContext).also { runtime = it }
        }

    private fun createOnce(context: Context): FirebaseRuntime {
        if (!BuildConfig.FIREBASE_CONFIGURED) {
            return UnavailableFirebaseRuntime(
                FirebaseRuntimeUnavailableReason.DevelopmentConfigurationMissing,
            )
        }

        val firebaseApp =
            FirebaseApp.getApps(context).firstOrNull { app ->
                app.name == DevelopmentFirebaseAppName
            } ?: FirebaseApp.initializeApp(
                context,
                firebaseOptions(),
                DevelopmentFirebaseAppName,
            )
        FirebaseAppCheck.getInstance(firebaseApp)
            .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        val auth = FirebaseAuth.getInstance(firebaseApp)
        val firestoreSession = FirebaseFirestoreSession(firebaseApp)
        val credentialManager = CredentialManager.create(context)
        val authentication =
            FirebaseAuthenticationAdapter(
                auth = auth,
                credentialManager = credentialManager,
                googleServerClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID,
                firestoreSession = firestoreSession,
            )

        return AvailableFirebaseRuntime(
            authenticationAdapter = authentication,
            approval = FirestoreMemberApprovalGateway(firestoreSession::current),
            hydration = FirestoreOwnerWorkspaceHydrationGateway(firestoreSession::current),
            persistedSession = DataStorePersistedWorkspaceSessionGateway(context),
            workspaceServices =
                FirebaseWorkspaceServices(
                    tasks = FirestoreTaskRepository(firestoreSession::current),
                    reminders = null,
                    pairing =
                        FirebaseFunctionsPairingGateway(
                            functions = { FirebaseFunctions.getInstance(firebaseApp) },
                            backendUrl = BuildConfig.PAIRING_BACKEND_URL,
                        ),
                ),
        )
    }

    private fun firebaseOptions(): FirebaseOptions =
        FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .apply {
                BuildConfig.FIREBASE_GCM_SENDER_ID
                    .takeIf(String::isNotBlank)
                    ?.let(::setGcmSenderId)
                BuildConfig.FIREBASE_STORAGE_BUCKET
                    .takeIf(String::isNotBlank)
                    ?.let(::setStorageBucket)
            }
            .build()
}

private class AvailableFirebaseRuntime(
    private val authenticationAdapter: FirebaseAuthenticationAdapter,
    override val approval: MemberApprovalGateway,
    override val hydration: OwnerWorkspaceHydrationGateway,
    override val persistedSession: PersistedWorkspaceSessionGateway,
    override val workspaceServices: FirebaseWorkspaceServices,
) : FirebaseRuntime {
    override val availability: FirebaseRuntimeAvailability = FirebaseRuntimeAvailability.Available
    override val authentication: AuthenticationGateway = authenticationAdapter
    override suspend fun signIn(activity: Activity): FirebaseSignInOutcome =
        authenticationAdapter.signIn(activity)
}

private const val DevelopmentFirebaseAppName = "duit-development"
