package com.gaylord.duit.app.runtime

import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.model.SeriesRevisionIdentifier
import com.gaylord.duit.domain.model.TaskRevisionIdentifier
import com.gaylord.duit.platform.firebase.FirebaseRuntime
import com.gaylord.duit.workspace.DuitWorkspace
import com.gaylord.duit.workspace.InitialTaskIdentifiers
import com.gaylord.duit.workspace.TaskIdentityGenerator
import com.gaylord.duit.workspace.WorkspaceSessionOperationCoordinator
import com.gaylord.duit.workspace.ZoneProvider
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal val runtimeWorkspaceSessionScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

private val runtimeWorkspaceSessionOperations =
    WorkspaceSessionOperationCoordinator(runtimeWorkspaceSessionScope)

private val runtimeWorkspaceLock = Any()

@Volatile
private var runtimeWorkspace: DuitWorkspace? = null

internal fun createRuntimeWorkspace(firebaseRuntime: FirebaseRuntime): DuitWorkspace =
    runtimeWorkspace ?: synchronized(runtimeWorkspaceLock) {
        runtimeWorkspace ?: buildRuntimeWorkspace(firebaseRuntime).also { runtimeWorkspace = it }
    }

private fun buildRuntimeWorkspace(firebaseRuntime: FirebaseRuntime): DuitWorkspace {
    val workspaceServices = firebaseRuntime.workspaceServices

    return DuitWorkspace.authenticated(
        clock = Clock.systemUTC(),
        zoneProvider = ZoneProvider { ZoneId.systemDefault() },
        taskIdentities =
            TaskIdentityGenerator { commandIdentifier ->
                InitialTaskIdentifiers(
                    taskIdentifier = TaskIdentifier("task-${commandIdentifier.value}"),
                    revisionIdentifier =
                        TaskRevisionIdentifier("revision-${commandIdentifier.value}"),
                    seriesRevisionIdentifier =
                        SeriesRevisionIdentifier("revision-${commandIdentifier.value}"),
                )
            },
        persistence = workspaceServices?.tasks,
        reminders = workspaceServices?.reminders,
        authentication = firebaseRuntime.authentication,
        approval = firebaseRuntime.approval,
        hydration = firebaseRuntime.hydration,
        persistedSession = firebaseRuntime.persistedSession,
        sessionOperations = runtimeWorkspaceSessionOperations,
    )
}
