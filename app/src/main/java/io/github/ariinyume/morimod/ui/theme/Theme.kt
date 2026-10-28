package io.github.ariinyume.morimod.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 形状规格（提示词「形状」节）：按钮胶囊 / 卡片 20dp / 对话框 28dp。
 *
 * 用稳定的 `Shapes` 而不是 Expressive 的 `MaterialShapes` ——
 * 后者的形状是 `RoundedPolygon` 而非 `CornerBasedShape`，不能直接喂给 `Shapes`，
 * 且在 alpha14 里仍带实验性标注；这里只需要固定圆角半径，用 `Shapes` 最稳。
 */
internal val MoriShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),      // 卡片 20dp
    extraLarge = RoundedCornerShape(28.dp), // 对话框 28dp
)

/** 胶囊：按钮 / 开关轨道 / 芯片。 */
internal fun pillShape() = RoundedCornerShape(percent = 50)

/**
 * 当前是否启用了系统动态取色。
 *
 * 「关于」页需要如实展示配色来源（不谎称是壁纸取色），故用 CompositionLocal 传下去。
 */
val LocalDynamicColorUsed = staticCompositionLocalOf { true }

/** 便捷读取当前是否用动态取色。 */
val usesDynamicColors: Boolean
    @Composable @ReadOnlyComposable
    get() = LocalDynamicColorUsed.current

/**
 * MoriMod 主题根。
 *
 * 取色优先级：
 *  1. Android 12+ → 系统动态取色（`dynamicLightColorScheme` / `dynamicDarkColorScheme`）
 *  2. 否则        → Mono 系备用配色（[MonoFallbackLightScheme] / [MonoFallbackDarkScheme]）
 * 浅/深色**跟随系统**（`isSystemInDarkTheme()`），不做应用内手动切换。
 *
 * 动效固定 `MotionScheme.expressive()`。
 *
 * ⚠️ 用 `MaterialExpressiveTheme` 而非 `MaterialTheme`：alpha14 里
 *    `MaterialTheme(colorScheme, motionScheme, …)` 那个重载虽然存在，但
 *    `MaterialExpressiveTheme` 才是官方 Expressive 入口 —— 它会额外置
 *    `LocalUsingExpressiveTheme`，让组件启用 Expressive 变体。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MoriModTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val scheme: ColorScheme = when {
        dynamicAvailable && darkTheme -> dynamicDarkColorScheme(context)
        dynamicAvailable -> dynamicLightColorScheme(context)
        darkTheme -> MonoFallbackDarkScheme
        else -> MonoFallbackLightScheme
    }

    CompositionLocalProvider(LocalDynamicColorUsed provides dynamicAvailable) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            shapes = MoriShapes,
            typography = MoriTypography,
            content = content,
        )
    }
}