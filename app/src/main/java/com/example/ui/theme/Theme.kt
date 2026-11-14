package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = SignalCyan,
    onPrimary = OnSignalCyan,
    primaryContainer = SignalCyanContainer,
    onPrimaryContainer = SignalCyan,
    secondary = TelemetryBlue,
    onSecondary = ObsidianBg,
    secondaryContainer = TelemetryBlueContainer,
    onSecondaryContainer = TelemetryBlue,
    tertiary = CloudEmerald,
    onTertiary = ObsidianBg,
    tertiaryContainer = CloudEmeraldContainer,
    onTertiaryContainer = CloudEmerald,
    error = RecordingCrimson,
    onError = TextPrimary,
    errorContainer = RecordingCrimsonContainer,
    onErrorContainer = RecordingCrimson,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = GraphiteSurface,
    onSurface = TextPrimary,
    surfaceVariant = GraphiteElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLow = GraphiteSurface,
    surfaceContainer = GraphiteElevated,
    surfaceContainerHigh = GraphiteContainerHigh,
    outline = HairlineBorder,
    outlineVariant = HairlineBorder
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightPrimary,
    secondary = TelemetryBlue,
    tertiary = CloudEmerald,
    error = RecordingCrimson,
    errorContainer = RecordingCrimsonContainer,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightOutline,
    outlineVariant = LightOutline
)

val CamVaultShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun CamVaultTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CamVaultTypography,
        shapes = CamVaultShapes,
        content = content
    )
}
