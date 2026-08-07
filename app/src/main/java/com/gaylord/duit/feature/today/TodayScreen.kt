package com.gaylord.duit.feature.today

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gaylord.duit.R
import com.gaylord.duit.designsystem.DuitDestinationPage

@Composable
fun TodayScreen(modifier: Modifier = Modifier) {
    DuitDestinationPage(
        title = R.string.destination_today,
        intro = R.string.today_intro,
        emptyTitle = R.string.today_empty_title,
        emptyBody = R.string.today_empty_body,
        modifier = modifier,
    )
}
