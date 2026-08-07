package com.gaylord.duit.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun DuitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) {
            duitDarkColorScheme()
        } else {
            duitLightColorScheme()
        },
        typography = DuitTypography,
        shapes = DuitShapes,
        content = content,
    )
}
