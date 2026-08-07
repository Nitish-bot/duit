package com.gaylord.duit.feature.tasks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gaylord.duit.R
import com.gaylord.duit.designsystem.DuitDestinationPage

@Composable
fun TasksScreen(modifier: Modifier = Modifier) {
    DuitDestinationPage(
        title = R.string.destination_tasks,
        intro = R.string.tasks_intro,
        emptyTitle = R.string.tasks_empty_title,
        emptyBody = R.string.tasks_empty_body,
        modifier = modifier,
    )
}
