package com.gaylord.duit.feature.together

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gaylord.duit.R
import com.gaylord.duit.designsystem.DuitDestinationPage

@Composable
fun TogetherScreen(modifier: Modifier = Modifier) {
    DuitDestinationPage(
        title = R.string.destination_together,
        intro = R.string.together_intro,
        emptyTitle = R.string.together_empty_title,
        emptyBody = R.string.together_empty_body,
        modifier = modifier,
    )
}
