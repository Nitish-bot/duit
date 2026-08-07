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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.gaylord.duit.feature.plan.PlanScreen
import com.gaylord.duit.feature.tasks.TasksScreen
import com.gaylord.duit.feature.today.TodayScreen
import com.gaylord.duit.feature.together.TogetherScreen

@Composable
fun DuitApp(darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme()) {
    DuitTheme(darkTheme = darkTheme) {
        DuitShell()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DuitShell(navController: NavHostController = rememberNavController()) {
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
            composable(DuitDestination.Today.route) { TodayScreen() }
            composable(DuitDestination.Together.route) { TogetherScreen() }
            composable(DuitDestination.Plan.route) { PlanScreen() }
            composable(DuitDestination.Tasks.route) { TasksScreen() }
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
