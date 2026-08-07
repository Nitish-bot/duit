package com.gaylord.duit.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import com.gaylord.duit.R

@Composable
internal fun duitLightColorScheme(): ColorScheme {
    val cream = colorResource(R.color.duit_cream)

    return lightColorScheme(
        primary = colorResource(R.color.duit_sage),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD6EBDD),
        onPrimaryContainer = Color(0xFF17372A),
        secondary = Color(0xFF58665D),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDCE8DE),
        onSecondaryContainer = Color(0xFF172A20),
        tertiary = Color(0xFF944A42),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFDAD5),
        onTertiaryContainer = Color(0xFF3B0907),
        background = cream,
        onBackground = Color(0xFF1B211D),
        surface = cream,
        surfaceContainer = Color(0xFFF3F1E7),
        onSurface = Color(0xFF1B211D),
        surfaceVariant = Color(0xFFE1E9E2),
        onSurfaceVariant = Color(0xFF414943),
        outline = Color(0xFF717A73),
        outlineVariant = Color(0xFFC1C9C2),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
    )
}

@Composable
internal fun duitDarkColorScheme(): ColorScheme {
    val night = colorResource(R.color.duit_night)

    return darkColorScheme(
        primary = Color(0xFFB1D5BE),
        onPrimary = Color(0xFF1D392A),
        primaryContainer = Color(0xFF2E5040),
        onPrimaryContainer = Color(0xFFD3F2DC),
        secondary = Color(0xFFC0CCC2),
        onSecondary = Color(0xFF2A332D),
        secondaryContainer = Color(0xFF3B4740),
        onSecondaryContainer = Color(0xFFDCE8DE),
        tertiary = Color(0xFFFFB4AB),
        onTertiary = Color(0xFF561E1A),
        tertiaryContainer = Color(0xFF73332D),
        onTertiaryContainer = Color(0xFFFFDAD5),
        background = night,
        onBackground = Color(0xFFE1E9E3),
        surface = night,
        surfaceContainer = Color(0xFF1B2520),
        onSurface = Color(0xFFE1E9E3),
        surfaceVariant = Color(0xFF414943),
        onSurfaceVariant = Color(0xFFC1C9C2),
        outline = Color(0xFF8B938D),
        outlineVariant = Color(0xFF414943),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
    )
}
