package com.gaylord.duit.app

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gaylord.duit.R
import com.gaylord.duit.designsystem.BotanicalMark
import com.gaylord.duit.designsystem.DuitTheme
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.feature.plan.PlanScreen
import com.gaylord.duit.feature.tasks.TasksScreen
import com.gaylord.duit.feature.tasks.TasksEventHandler
import com.gaylord.duit.feature.tasks.TasksUiState
import com.gaylord.duit.feature.today.TodayScreen
import com.gaylord.duit.feature.today.TodayEventHandler
import com.gaylord.duit.feature.today.TodayUiState
import com.gaylord.duit.feature.together.TogetherScreen
import com.gaylord.duit.feature.together.TogetherEvent
import com.gaylord.duit.feature.together.TogetherState
import com.gaylord.duit.workspace.WorkspaceAccessAction
import com.gaylord.duit.workspace.WorkspaceAccessState
import com.gaylord.duit.workspace.WorkspaceRestorationSource

@Composable
fun DuitApp(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    accessState: WorkspaceAccessState = DefaultWorkspaceAccessState,
    onAccessAction: (WorkspaceAccessAction) -> Unit = {},
    todayState: TodayUiState = TodayUiState(),
    onTodayEvent: TodayEventHandler = {},
    togetherState: TogetherState = TogetherState.Loading,
    onTogetherEvent: (TogetherEvent) -> Unit = {},
    tasksState: TasksUiState = TasksUiState(),
    onTasksEvent: TasksEventHandler = {},
) {
    DuitTheme(darkTheme = darkTheme) {
        when (accessState) {
            is WorkspaceAccessState.Ready -> DuitShell(
                todayState = todayState,
                onTodayEvent = onTodayEvent,
                togetherState = togetherState,
                onTogetherEvent = onTogetherEvent,
                tasksState = tasksState,
                onTasksEvent = onTasksEvent,
            )
            else -> WorkspaceAccessGate(
                accessState = accessState,
                onAccessAction = onAccessAction,
            )
        }
    }
}

@Composable
private fun WorkspaceAccessGate(
    accessState: WorkspaceAccessState,
    onAccessAction: (WorkspaceAccessAction) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BotanicalMark(modifier = Modifier.size(40.dp))
                Text(
                    text = stringResource(R.string.brand_name),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (accessState) {
                WorkspaceAccessState.SignInRequired -> {
                    AccessGateHeading(R.string.access_sign_in_title)
                    AccessGateBody(R.string.access_sign_in_body)
                    AccessGatePrimaryAction(
                        label = R.string.access_sign_in_google,
                        action = WorkspaceAccessAction.SignIn,
                        onAccessAction = onAccessAction,
                    )
                }
                is WorkspaceAccessState.ApprovalRequired -> {
                    AccessGateHeading(R.string.access_approval_title)
                    AccessGateBody(R.string.access_approval_body)
                    FirebaseUid(accessState.authenticatedUid)
                    ApprovalInstructions(accessState.authenticatedUid)
                    RecoveryActions(onAccessAction)
                }
                is WorkspaceAccessState.ApprovalCheckFailed -> {
                    AccessGateHeading(R.string.access_approval_check_failed_title)
                    AccessGateBody(R.string.access_approval_check_failed_body)
                    FirebaseUid(accessState.authenticatedUid)
                    RecoveryActions(onAccessAction)
                }
                is WorkspaceAccessState.Hydrating -> {
                    val loadingDescription =
                        stringResource(R.string.access_hydrating_progress_description)

                    AccessGateHeading(R.string.access_hydrating_title)
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = loadingDescription
                        },
                    )
                    AccessGateBody(R.string.access_hydrating_body)
                    AccessGateSecondaryAction(
                        label = R.string.access_sign_out,
                        action = WorkspaceAccessAction.SignOut,
                        onAccessAction = onAccessAction,
                    )
                }
                is WorkspaceAccessState.Failed -> {
                    AccessGateHeading(R.string.access_failed_title)
                    AccessGateBody(R.string.access_failed_body)
                    RecoveryActions(onAccessAction)
                }
                is WorkspaceAccessState.SignOutFailed -> {
                    AccessGateHeading(R.string.access_sign_out_failed_title)
                    AccessGateBody(R.string.access_sign_out_failed_body)
                    RecoveryActions(onAccessAction)
                }
                is WorkspaceAccessState.SigningOut -> {
                    val loadingDescription =
                        stringResource(R.string.access_signing_out_progress_description)

                    AccessGateHeading(R.string.access_signing_out_title)
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = loadingDescription
                        },
                    )
                    AccessGateBody(R.string.access_signing_out_body)
                }
                is WorkspaceAccessState.Ready -> Unit
            }
        }
    }
}

@Composable
private fun AccessGateHeading(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        modifier = Modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.headlineMedium,
    )
}

@Composable
private fun AccessGateBody(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun FirebaseUid(authenticatedUid: String) {
    Text(
        text = stringResource(R.string.access_approval_uid_label),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
    Text(
        text = authenticatedUid,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun ApprovalInstructions(authenticatedUid: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.access_approval_step_open_console),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(
                R.string.access_approval_step_create_document,
                authenticatedUid,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.access_approval_step_set_field),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.access_approval_step_retry),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RecoveryActions(onAccessAction: (WorkspaceAccessAction) -> Unit) {
    AccessGatePrimaryAction(
        label = R.string.access_retry,
        action = WorkspaceAccessAction.Retry,
        onAccessAction = onAccessAction,
    )
    AccessGateSecondaryAction(
        label = R.string.access_sign_out,
        action = WorkspaceAccessAction.SignOut,
        onAccessAction = onAccessAction,
    )
}

@Composable
private fun AccessGatePrimaryAction(
    @StringRes label: Int,
    action: WorkspaceAccessAction,
    onAccessAction: (WorkspaceAccessAction) -> Unit,
) {
    Button(
        onClick = { onAccessAction(action) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text(text = stringResource(label))
    }
}

@Composable
private fun AccessGateSecondaryAction(
    @StringRes label: Int,
    action: WorkspaceAccessAction,
    onAccessAction: (WorkspaceAccessAction) -> Unit,
) {
    TextButton(
        onClick = { onAccessAction(action) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text(text = stringResource(label))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DuitShell(
    navController: NavHostController = rememberNavController(),
    todayState: TodayUiState = TodayUiState(),
    onTodayEvent: TodayEventHandler = {},
    togetherState: TogetherState = TogetherState.Loading,
    onTogetherEvent: (TogetherEvent) -> Unit = {},
    tasksState: TasksUiState = TasksUiState(),
    onTasksEvent: TasksEventHandler = {},
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: DuitDestination.Today.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BotanicalMark(modifier = Modifier.size(32.dp))
                        Text(
                            text = stringResource(R.string.brand_name),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = {
            DuitPrimaryNavigation(
                currentRoute = currentRoute,
                onDestinationSelected = { destination ->
                    if (destination.route != currentRoute) {
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = DuitDestination.Today.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            composable(DuitDestination.Today.route) {
                TodayScreen(
                    state = todayState,
                    onEvent = onTodayEvent,
                )
            }
            composable(DuitDestination.Together.route) {
                TogetherScreen(
                    state = togetherState,
                    onEvent = onTogetherEvent,
                )
            }
            composable(DuitDestination.Plan.route) { PlanScreen() }
            composable(DuitDestination.Tasks.route) {
                TasksScreen(
                    state = tasksState,
                    onEvent = onTasksEvent,
                )
            }
        }
    }
}

@Composable
private fun DuitPrimaryNavigation(
    currentRoute: String,
    onDestinationSelected: (DuitDestination) -> Unit,
) {
    if (LocalDensity.current.fontScale >= LargeTextNavigationThreshold) {
        LargeTextNavigation(
            currentRoute = currentRoute,
            onDestinationSelected = onDestinationSelected,
        )
    } else {
        CompactNavigation(
            currentRoute = currentRoute,
            onDestinationSelected = onDestinationSelected,
        )
    }
}

@Composable
private fun CompactNavigation(
    currentRoute: String,
    onDestinationSelected: (DuitDestination) -> Unit,
) {
    NavigationBar(
        modifier = Modifier.selectableGroup(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        DuitDestination.entries.forEach { destination ->
            CompactNavigationItem(
                destination = destination,
                selected = currentRoute == destination.route,
                onClick = { onDestinationSelected(destination) },
            )
        }
    }
}

@Composable
private fun RowScope.CompactNavigationItem(
    destination: DuitDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .weight(1f)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
            )
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                )
                .padding(horizontal = 18.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(destination.icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = contentColor,
            )
        }
        Text(
            text = stringResource(destination.label),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun LargeTextNavigation(
    currentRoute: String,
    onDestinationSelected: (DuitDestination) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .selectableGroup()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DuitDestination.entries.chunked(2).forEach { rowDestinations ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rowDestinations.forEach { destination ->
                        LargeTextNavigationItem(
                            destination = destination,
                            selected = currentRoute == destination.route,
                            onClick = { onDestinationSelected(destination) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LargeTextNavigationItem(
    destination: DuitDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .heightIn(min = 64.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
            )
            .semantics(mergeDescendants = true) {},
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(destination.icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(destination.label),
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Clip,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private enum class DuitDestination(
    val route: String,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
) {
    Today(
        route = "today",
        label = R.string.destination_today,
        icon = R.drawable.ic_today,
    ),
    Together(
        route = "together",
        label = R.string.destination_together,
        icon = R.drawable.ic_together,
    ),
    Plan(
        route = "plan",
        label = R.string.destination_plan,
        icon = R.drawable.ic_plan,
    ),
    Tasks(
        route = "tasks",
        label = R.string.destination_tasks,
        icon = R.drawable.ic_tasks,
    ),
}

private const val LargeTextNavigationThreshold = 1.5f

private val DefaultWorkspaceAccessState =
    WorkspaceAccessState.Ready(
        memberIdentifier = MemberIdentifier("local-member"),
        restorationSource = WorkspaceRestorationSource.OnDevice,
    )
