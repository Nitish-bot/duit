package com.gaylord.duit.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gaylord.duit.R
import com.gaylord.duit.app.runtime.createRuntimeWorkspace
import com.gaylord.duit.app.runtime.runtimeWorkspaceSessionScope
import com.gaylord.duit.feature.tasks.TasksEffect
import com.gaylord.duit.feature.tasks.TasksViewModel
import com.gaylord.duit.feature.today.TodayEffect
import com.gaylord.duit.feature.today.TodayViewModel
import com.gaylord.duit.platform.firebase.FirebaseRuntime
import com.gaylord.duit.platform.firebase.FirebaseRuntimeFactory
import com.gaylord.duit.platform.firebase.FirebaseSignInFailure
import com.gaylord.duit.platform.firebase.FirebaseSignInOutcome
import com.gaylord.duit.workspace.DuitWorkspace
import com.gaylord.duit.workspace.WorkspaceAccessAction
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var firebaseRuntime: FirebaseRuntime
    private lateinit var workspace: DuitWorkspace
    private lateinit var todayViewModel: TodayViewModel
    private lateinit var tasksViewModel: TasksViewModel
    private var workspaceActionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        firebaseRuntime = FirebaseRuntimeFactory.create(applicationContext)
        workspace = createRuntimeWorkspace(firebaseRuntime)
        todayViewModel =
            ViewModelProvider(
                this,
                TodayViewModel.Factory(workspace),
            )[TodayViewModel::class.java]
        tasksViewModel =
            ViewModelProvider(
                this,
                TasksViewModel.Factory(workspace),
            )[TasksViewModel::class.java]

        setContent {
            val accessState by workspace.accessState.collectAsStateWithLifecycle()
            val todayState by todayViewModel.state.collectAsStateWithLifecycle()
            val tasksState by tasksViewModel.state.collectAsStateWithLifecycle()

            DuitApp(
                accessState = accessState,
                onAccessAction = ::handleAccessAction,
                todayState = todayState,
                onTodayEvent = todayViewModel::onEvent,
                tasksState = tasksState,
                onTasksEvent = tasksViewModel::onEvent,
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                todayViewModel.effects.collect { effect ->
                    when (effect) {
                        TodayEffect.WorkspaceActionFailed -> showWorkspaceActionFailure()
                        TodayEffect.CorrectionBecameStale -> showStaleCorrection()
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tasksViewModel.effects.collect { effect ->
                    when (effect) {
                        TasksEffect.WorkspaceActionFailed -> showWorkspaceActionFailure()
                        TasksEffect.CorrectionBecameStale -> showStaleCorrection()
                    }
                }
            }
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

    private fun showWorkspaceActionFailure() {
        Toast.makeText(this, R.string.workspace_action_failed, Toast.LENGTH_LONG).show()
    }

    private fun showStaleCorrection() {
        Toast.makeText(this, R.string.completion_correction_stale, Toast.LENGTH_LONG).show()
    }
}
