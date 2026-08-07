package com.gaylord.duit.feature.plan

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gaylord.duit.R
import com.gaylord.duit.designsystem.DuitDestinationPage

@Composable
fun PlanScreen(modifier: Modifier = Modifier) {
    DuitDestinationPage(
        title = R.string.destination_plan,
        intro = R.string.plan_intro,
        emptyTitle = R.string.plan_empty_title,
        emptyBody = R.string.plan_empty_body,
        modifier = modifier,
    )
}
