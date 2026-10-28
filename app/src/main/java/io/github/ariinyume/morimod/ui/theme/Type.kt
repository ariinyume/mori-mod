package io.github.ariinyume.morimod.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字型：Roboto + M3 Expressive **emphasized** 字型。
 *
 * 标题类（headline / title）统一换成对应的 `…Emphasized` 变体 ——
 * 那是 Expressive 的加粗强调字型，字重更重、字距更紧，正是提示词要的观感。
 * 正文/标签保持常规字重，避免整屏都太重。
 *
 * ⚠️ `…Emphasized` 在 material3 里带 `@ExperimentalMaterial3ExpressiveApi`，
 *    所以整个文件标 OptIn。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal val MoriTypography = Typography().run {
    copy(
        displayLarge = displayLargeEmphasized,
        displayMedium = displayMediumEmphasized,
        displaySmall = displaySmallEmphasized,
        headlineLarge = headlineLargeEmphasized,
        headlineMedium = headlineMediumEmphasized,
        headlineSmall = headlineSmallEmphasized,
        titleLarge = titleLargeEmphasized,
        titleMedium = titleMediumEmphasized,
        titleSmall = titleSmallEmphasized,
        // 正文保持常规字重
        bodyLarge = bodyLarge,
        bodyMedium = bodyMedium,
        bodySmall = bodySmall,
        labelLarge = labelLarge,
        labelMedium = labelMedium,
        labelSmall = labelSmall,
    )
}

/** 屏幕大标题（草图：28sp 粗体，左对齐）。 */
internal val ScreenTitleStyle
    @Composable get() = MoriTypography.headlineMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    )

/** 分区小标题用：14sp 粗体（比正文略大、比屏幕大标题明显小，做「分组标签」而不是「标题」）。 */
internal val SectionTitleStyle
    @Composable get() = MoriTypography.titleMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )