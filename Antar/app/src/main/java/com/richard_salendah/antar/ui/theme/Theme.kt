package com.richard_salendah.antar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary              = md_light_primary,
    onPrimary            = md_light_onPrimary,
    primaryContainer     = md_light_primaryContainer,
    onPrimaryContainer   = md_light_onPrimaryContainer,
    secondary            = md_light_secondary,
    onSecondary          = md_light_onSecondary,
    secondaryContainer   = md_light_secondaryContainer,
    onSecondaryContainer = md_light_onSecondaryContainer,
    error                = md_light_error,
    onError              = md_light_onError,
    errorContainer       = md_light_errorContainer,
    onErrorContainer     = md_light_onErrorContainer,
    background           = md_light_background,
    onBackground         = md_light_onBackground,
    surface              = md_light_surface,
    onSurface            = md_light_onSurface,
    surfaceVariant       = md_light_surfaceVariant,
    onSurfaceVariant     = md_light_onSurfaceVariant,
    outline              = md_light_outline,
    outlineVariant       = md_light_outlineVariant,
)

@Composable
fun AntarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography  = AntarTypography,
        content     = content,
    )
}