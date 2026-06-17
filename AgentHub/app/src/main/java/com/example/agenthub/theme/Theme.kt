package com.example.agenthub.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val OpenCodeColorScheme = darkColorScheme(
  primary = OpenCodeGreen,
  onPrimary = OpenCodeBlack,
  secondary = OpenCodeGreenDark,
  onSecondary = OpenCodeTextPrimary,
  tertiary = OpenCodeGreenLight,
  onTertiary = OpenCodeBlack,
  background = OpenCodeBlack,
  onBackground = OpenCodeTextPrimary,
  surface = OpenCodeSurface,
  onSurface = OpenCodeTextPrimary,
  surfaceVariant = OpenCodeSurfaceElevated,
  onSurfaceVariant = OpenCodeTextSecondary,
  outline = OpenCodeBorder,
)

@Composable
fun AgentHubTheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(colorScheme = OpenCodeColorScheme, typography = Typography, content = content)
}
