package io.github.ariinyume.morimod.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Mono 系备用配色 —— 仅在设备**不支持动态取色**（Android 12 以下）时启用。
 *
 * Android 12+ 一律走系统的 `dynamicLightColorScheme` / `dynamicDarkColorScheme`，
 * 这套配色只是兜底，所以刻意做成单色相（蓝紫）+ 极低彩度的 Mono 风格，
 * 保证在无动态取色的老设备上也不至于花。
 *
 * ⚠️ 这些常量是**唯一**允许写死色值的地方。UI 层任何组件都只能用
 *    ColorScheme 的角色名（primary / surfaceContainer / …），不得引用本文件的色值。
 */

// ── 浅色 Mono 备用配色 ────────────────────────────────────────────
internal val MonoPrimaryLight = Color(0xFF3A5BA0)
internal val MonoOnPrimaryLight = Color(0xFFFFFFFF)
internal val MonoPrimaryContainerLight = Color(0xFFD9E2FF)
internal val MonoOnPrimaryContainerLight = Color(0xFF001945)
internal val MonoSecondaryLight = Color(0xFF565E71)
internal val MonoOnSecondaryLight = Color(0xFFFFFFFF)
internal val MonoSecondaryContainerLight = Color(0xFFDAE2F9)
internal val MonoOnSecondaryContainerLight = Color(0xFF131C2B)
internal val MonoTertiaryLight = Color(0xFF6F5675)
internal val MonoOnTertiaryLight = Color(0xFFFFFFFF)
internal val MonoTertiaryContainerLight = Color(0xFFFAD8FD)
internal val MonoOnTertiaryContainerLight = Color(0xFF27132E)
internal val MonoErrorLight = Color(0xFFBA1A1A)
internal val MonoOnErrorLight = Color(0xFFFFFFFF)
internal val MonoErrorContainerLight = Color(0xFFFFDAD6)
internal val MonoOnErrorContainerLight = Color(0xFF410002)
internal val MonoBackgroundLight = Color(0xFFFEFBFF)
internal val MonoOnBackgroundLight = Color(0xFF1B1B1F)
internal val MonoSurfaceLight = Color(0xFFFEFBFF)
internal val MonoOnSurfaceLight = Color(0xFF1B1B1F)
internal val MonoSurfaceVariantLight = Color(0xFFE1E2EC)
internal val MonoOnSurfaceVariantLight = Color(0xFF44464F)
internal val MonoOutlineLight = Color(0xFF757780)
internal val MonoOutlineVariantLight = Color(0xFFC5C6D0)
internal val MonoSurfaceContainerLowestLight = Color(0xFFFFFFFF)
internal val MonoSurfaceContainerLowLight = Color(0xFFF8F3FF)
internal val MonoSurfaceContainerLight = Color(0xFFF2EDF7)
internal val MonoSurfaceContainerHighLight = Color(0xFFECE7F1)
internal val MonoSurfaceContainerHighestLight = Color(0xFFE6E1EB)
internal val MonoInverseSurfaceLight = Color(0xFF303034)
internal val MonoInverseOnSurfaceLight = Color(0xFFF3EFF4)
internal val MonoInversePrimaryLight = Color(0xFFADC6FF)
internal val MonoSurfaceTintLight = Color(0xFF3A5BA0)
internal val MonoScrimLight = Color(0xFF000000)

// ── 深色 Mono 备用配色 ────────────────────────────────────────────
internal val MonoPrimaryDark = Color(0xFFADC6FF)
internal val MonoOnPrimaryDark = Color(0xFF042C66)
internal val MonoPrimaryContainerDark = Color(0xFF1F4380)
internal val MonoOnPrimaryContainerDark = Color(0xFFD9E2FF)
internal val MonoSecondaryDark = Color(0xFFBEC6DC)
internal val MonoOnSecondaryDark = Color(0xFF283141)
internal val MonoSecondaryContainerDark = Color(0xFF3E4759)
internal val MonoOnSecondaryContainerDark = Color(0xFFDAE2F9)
internal val MonoTertiaryDark = Color(0xFFDBBCE0)
internal val MonoOnTertiaryDark = Color(0xFF3E2845)
internal val MonoTertiaryContainerDark = Color(0xFF563E5C)
internal val MonoOnTertiaryContainerDark = Color(0xFFFAD8FD)
internal val MonoErrorDark = Color(0xFFFFB4AB)
internal val MonoOnErrorDark = Color(0xFF690005)
internal val MonoErrorContainerDark = Color(0xFF93000A)
internal val MonoOnErrorContainerDark = Color(0xFFFFDAD6)
internal val MonoBackgroundDark = Color(0xFF131316)
internal val MonoOnBackgroundDark = Color(0xFFE4E1E6)
internal val MonoSurfaceDark = Color(0xFF131316)
internal val MonoOnSurfaceDark = Color(0xFFE4E1E6)
internal val MonoSurfaceVariantDark = Color(0xFF44464F)
internal val MonoOnSurfaceVariantDark = Color(0xFFC5C6D0)
internal val MonoOutlineDark = Color(0xFF8F909A)
internal val MonoOutlineVariantDark = Color(0xFF44464F)
internal val MonoSurfaceContainerLowestDark = Color(0xFF0E0E11)
internal val MonoSurfaceContainerLowDark = Color(0xFF1B1B1F)
internal val MonoSurfaceContainerDark = Color(0xFF1F1F23)
internal val MonoSurfaceContainerHighDark = Color(0xFF2A2A2E)
internal val MonoSurfaceContainerHighestDark = Color(0xFF353539)
internal val MonoInverseSurfaceDark = Color(0xFFE4E1E6)
internal val MonoInverseOnSurfaceDark = Color(0xFF303034)
internal val MonoInversePrimaryDark = Color(0xFF3A5BA0)
internal val MonoSurfaceTintDark = Color(0xFFADC6FF)
internal val MonoScrimDark = Color(0xFF000000)

/** 无动态取色能力时的浅色兜底。 */
internal val MonoFallbackLightScheme = lightColorScheme(
    primary = MonoPrimaryLight,
    onPrimary = MonoOnPrimaryLight,
    primaryContainer = MonoPrimaryContainerLight,
    onPrimaryContainer = MonoOnPrimaryContainerLight,
    inversePrimary = MonoInversePrimaryLight,
    secondary = MonoSecondaryLight,
    onSecondary = MonoOnSecondaryLight,
    secondaryContainer = MonoSecondaryContainerLight,
    onSecondaryContainer = MonoOnSecondaryContainerLight,
    tertiary = MonoTertiaryLight,
    onTertiary = MonoOnTertiaryLight,
    tertiaryContainer = MonoTertiaryContainerLight,
    onTertiaryContainer = MonoOnTertiaryContainerLight,
    background = MonoBackgroundLight,
    onBackground = MonoOnBackgroundLight,
    surface = MonoSurfaceLight,
    onSurface = MonoOnSurfaceLight,
    surfaceVariant = MonoSurfaceVariantLight,
    onSurfaceVariant = MonoOnSurfaceVariantLight,
    surfaceTint = MonoSurfaceTintLight,
    inverseSurface = MonoInverseSurfaceLight,
    inverseOnSurface = MonoInverseOnSurfaceLight,
    error = MonoErrorLight,
    onError = MonoOnErrorLight,
    errorContainer = MonoErrorContainerLight,
    onErrorContainer = MonoOnErrorContainerLight,
    outline = MonoOutlineLight,
    outlineVariant = MonoOutlineVariantLight,
    scrim = MonoScrimLight,
    surfaceContainerLowest = MonoSurfaceContainerLowestLight,
    surfaceContainerLow = MonoSurfaceContainerLowLight,
    surfaceContainer = MonoSurfaceContainerLight,
    surfaceContainerHigh = MonoSurfaceContainerHighLight,
    surfaceContainerHighest = MonoSurfaceContainerHighestLight,
)

/** 无动态取色能力时的深色兜底。 */
internal val MonoFallbackDarkScheme = darkColorScheme(
    primary = MonoPrimaryDark,
    onPrimary = MonoOnPrimaryDark,
    primaryContainer = MonoPrimaryContainerDark,
    onPrimaryContainer = MonoOnPrimaryContainerDark,
    inversePrimary = MonoInversePrimaryDark,
    secondary = MonoSecondaryDark,
    onSecondary = MonoOnSecondaryDark,
    secondaryContainer = MonoSecondaryContainerDark,
    onSecondaryContainer = MonoOnSecondaryContainerDark,
    tertiary = MonoTertiaryDark,
    onTertiary = MonoOnTertiaryDark,
    tertiaryContainer = MonoTertiaryContainerDark,
    onTertiaryContainer = MonoOnTertiaryContainerDark,
    background = MonoBackgroundDark,
    onBackground = MonoOnBackgroundDark,
    surface = MonoSurfaceDark,
    onSurface = MonoOnSurfaceDark,
    surfaceVariant = MonoSurfaceVariantDark,
    onSurfaceVariant = MonoOnSurfaceVariantDark,
    surfaceTint = MonoSurfaceTintDark,
    inverseSurface = MonoInverseSurfaceDark,
    inverseOnSurface = MonoInverseOnSurfaceDark,
    error = MonoErrorDark,
    onError = MonoOnErrorDark,
    errorContainer = MonoErrorContainerDark,
    onErrorContainer = MonoOnErrorContainerDark,
    outline = MonoOutlineDark,
    outlineVariant = MonoOutlineVariantDark,
    scrim = MonoScrimDark,
    surfaceContainerLowest = MonoSurfaceContainerLowestDark,
    surfaceContainerLow = MonoSurfaceContainerLowDark,
    surfaceContainer = MonoSurfaceContainerDark,
    surfaceContainerHigh = MonoSurfaceContainerHighDark,
    surfaceContainerHighest = MonoSurfaceContainerHighestDark,
)