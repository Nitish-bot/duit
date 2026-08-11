package com.gaylord.duit.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.gaylord.duit.R
import com.gaylord.duit.app.runtime.createRuntimeWorkspace
import com.gaylord.duit.app.runtime.runtimeWorkspaceSessionScope
import com.gaylord.duit.platform.firebase.FirebaseRuntime
import com.gaylord.duit.platform.firebase.FirebaseRuntimeFactory
import com.gaylord.duit.platform.firebase.FirebaseSignInFailure
import com.gaylord.duit.platform.firebase.FirebaseSignInOutcome
import com.gaylord.duit.workspace.CommandIdentifier
import com.gaylord.duit.workspace.DuitWorkspace
import com.gaylord.duit.workspace.MemberCommand
import com.gaylord.duit.workspace.PersonalAgenda
import com.gaylord.duit.workspace.WorkspaceAccessAction
import com.gaylord.duit.workspace.WorkspaceCapabilityUnavailableException
import com.gaylord.duit.workspace.WorkspaceLockedException
import java.time.LocalDate
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var firebaseRuntime: FirebaseRuntime
    private lateinit var workspace: DuitWorkspace
    private var workspaceActionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        firebaseRuntime = FirebaseRuntimeFactory.create(applicationContext)
        workspace = createRuntimeWorkspace(firebaseRuntime)

        setContent {
            val accessState by workspace.accessState.collectAsState()
            val agendaFlow = remember(workspace) { workspace.observePersonalAgenda() }
            val personalAgenda by agendaFlow.collectAsState(
                initial = PersonalAgenda(date = LocalDate.now(), tasks = emptyList()),
            )

            DuitApp(
                accessState = accessState,
                onAccessAction = ::handleAccessAction,
                personalAgenda = personalAgenda,
                onAddPrivateTaskToToday = ::addPrivateTaskToToday,
                onAddPrivateDailyTaskToToday = ::addPrivateDailyTaskToToday,
            )
        }

        workspaceActionJob = lifecycleScope.launch { workspace.restoreSession() }
    }

    private fun handleAccessAction(action: WorkspaceAccessAction) {
        if (action == WorkspaceAccessAction.SignOut) {
            workspaceActionJob?.cancel()
            val workspaceToSignOut = workspace
            workspaceActionJob = runtimeWorkspaceSessionScope.launch(
                start = CoroutineStart.UNDISPATCHED,
            ) {
                workspaceToSignOut.signOut()
            }
            return
        }

        if (workspaceActionJob?.isActive == true) return

        workspaceActionJob =
            lifecycleScope.launch {
                when (action) {
                    WorkspaceAccessAction.SignIn -> signIn()
                    WorkspaceAccessAction.Retry -> workspace.retryAccess()
                    WorkspaceAccessAction.SignOut -> error("Sign out is handled before serialization")
                }
            }
    }

    private fun addPrivateTaskToToday(
        commandIdentifier: CommandIdentifier,
        title: String,
        onResult: (Boolean) -> Unit,
    ) = submitTaskCreation(
        command =
            MemberCommand.AddPrivateTaskToToday(
                commandIdentifier = commandIdentifier,
                title = title,
            ),
        onResult = onResult,
    )

    private fun addPrivateDailyTaskToToday(
        commandIdentifier: CommandIdentifier,
        title: String,
        subtask: String?,
        onResult: (Boolean) -> Unit,
    ) = submitTaskCreation(
        command =
            MemberCommand.AddPrivateDailyTaskToToday(
                commandIdentifier = commandIdentifier,
                title = title,
                subtask = subtask,
            ),
        onResult = onResult,
    )

    private fun submitTaskCreation(
        command: MemberCommand,
        onResult: (Boolean) -> Unit,
    ) {
        if (workspaceActionJob?.isActive == true) {
            onResult(false)
            return
        }

        workspaceActionJob =
            lifecycleScope.launch {
                try {
                    workspace.execute(command)
                    onResult(true)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: WorkspaceLockedException) {
                    onResult(false)
                    showTaskCreationFailure()
                } catch (_: WorkspaceCapabilityUnavailableException) {
                    onResult(false)
                    showTaskCreationFailure()
                } catch (_: Exception) {
                    onResult(false)
                    showTaskCreationFailure()
                }
            }
    }

    private suspend fun signIn() {
        when (val outcome = firebaseRuntime.signIn(this)) {
            is FirebaseSignInOutcome.SignedIn -> workspace.restoreSession()
            FirebaseSignInOutcome.Cancelled -> Unit
            is FirebaseSignInOutcome.Failed -> showSignInFailure(outcome.failure)
        }
    }

    private fun showSignInFailure(failure: FirebaseSignInFailure) {
        val message =
            when (failure) {
                FirebaseSignInFailure.RuntimeUnavailable ->
                    R.string.firebase_runtime_unavailable
                FirebaseSignInFailure.NoGoogleCredentialAvailable ->
                    R.string.firebase_no_google_account
                FirebaseSignInFailure.InvalidGoogleCredential,
                FirebaseSignInFailure.CredentialProviderFailure,
                FirebaseSignInFailure.FirebaseAuthenticationFailure,
                -> R.string.firebase_sign_in_failed
            }

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showTaskCreationFailure() {
        Toast.makeText(this, R.string.task_creation_failed, Toast.LENGTH_LONG).show()
    }
}
