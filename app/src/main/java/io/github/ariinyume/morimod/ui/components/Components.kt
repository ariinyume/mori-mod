package io.github.ariinyume.morimod.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ariinyume.morimod.ui.S
import io.github.ariinyume.morimod.ui.UiDiag
import io.github.ariinyume.morimod.ui.theme.ScreenTitleStyle
import io.github.ariinyume.morimod.ui.theme.SectionTitleStyle

/** 全局留白规格（提示词：屏幕边缘 16dp，组件之间 8〜16dp）。 */
object Space {
    /** 屏幕左右边缘。 */
    val ScreenH = 16.dp

    /** 卡片内边距（提示词：卡片内边距 20dp）。 */
    val CardPad = 20.dp

    /** 卡片之间。 */
    val CardGap = 12.dp

    /** 行内元素之间。 */
    val ItemGap = 8.dp

    /** 分区标题与内容之间。 */
    val SectionGap = 8.dp

    /** 分区标题**上方**的留白（把「状态卡 / 功能设置 / 模块管理」三组明显拉开）。 */
    val GroupGap = 24.dp

    /** M3 Expressive 相连组件之间的缝隙（按钮组 / 相连列表都是 3dp）。 */
    val ConnectedGap = 3.dp

    /** 相连组外侧圆角（列表 28dp）。 */
    val OuterCorner = 28.dp

    /** 相连组相邻内侧圆角（8dp）。 */
    val InnerCorner = 8.dp
}

// ══════════════════════════════════════════════════════════════════
//  点击反馈
// ══════════════════════════════════════════════════════════════════

/**
 * 统一的点击修饰（列表项 / 缩略图）。
 *
 * ⚠️ 这里刻意只用 `Modifier.clickable` 的**最朴素重载**，不要自作聪明：
 *    之前那版自己 `remember` 一个 `MutableInteractionSource`、显式传
 *    `LocalIndication`、再叠一层 `Modifier.scale` 做按压缩放 —— 真机上表现为
 *    **整行点不动**（连行内 Switch 也一起失灵），而 material3 自带组件（Button / Switch）
 *    全都正常。按压缩放只是锦上添花的动效，换来「整行点不动」不划算，直接去掉；
 *    涟漪仍然由 `clickable` 内部取 `LocalIndication` 提供。
 */
fun Modifier.moriClickable(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier = clickable(enabled = enabled, role = role, onClick = onClick)

/** 点一下 + 长按（封面缩略图：点选应用 / 长按删除）。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.moriCombinedClickable(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = combinedClickable(
    enabled = enabled,
    onClick = onClick,
    onLongClick = onLongClick,
)

// ══════════════════════════════════════════════════════════════════
//  相连形状（M3 Expressive：外侧圆角大、相邻内侧圆角小）
// ══════════════════════════════════════════════════════════════════

/**
 * 相连列表（**竖直**堆叠）中第 [index] 项（共 [count] 项）的形状。
 *
 * ⚠️ 外侧大圆角必须落在**组的上下两端**（首项顶边 / 末项底边），不能落在左右两侧：
 *    竖排时左右两边是「外侧自由边」，把 28dp 放到首项的左侧会让它的左上 / 左下
 *    比同组其它项明显更圆（v0.0.3 的真机表现就是这个 bug）。
 *    相邻内侧（项与项之间）统一 8dp。
 */
fun connectedItemShape(
    index: Int,
    count: Int,
    outer: Dp = Space.OuterCorner,
    inner: Dp = Space.InnerCorner,
): Shape {
    val first = index == 0
    val last = index == count - 1
    val top = if (first) outer else inner
    val bottom = if (last) outer else inner
    return RoundedCornerShape(
        topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom,
    )
}

/**
 * 相连**按钮组**里第 [index] 个按钮的形状。
 *
 * 外侧 = 半高（胶囊），相邻内侧 = 8dp —— 提示词「只把相邻的内侧圆角缩小到 8dp，
 * 外侧保持圆角」。胶囊端用 `CornerSize(50)` 表达，这样任意高度下都是完整半圆
 * （`RoundedCornerShape` 允许逐角混用百分比与 dp）。
 */
fun connectedButtonShape(index: Int, count: Int): Shape {
    if (count <= 1) return RoundedCornerShape(percent = 50)
    val first = index == 0
    val last = index == count - 1
    val pill = CornerSize(percent = 50)
    val small = CornerSize(Space.InnerCorner)
    return when {
        first -> RoundedCornerShape(
            topStart = pill, bottomStart = pill, topEnd = small, bottomEnd = small,
        )
        last -> RoundedCornerShape(
            topStart = small, bottomStart = small, topEnd = pill, bottomEnd = pill,
        )
        else -> RoundedCornerShape(Space.InnerCorner)
    }
}

// ══════════════════════════════════════════════════════════════════
//  顶栏
// ══════════════════════════════════════════════════════════════════

/**
 * 二级屏顶栏：左对齐的 M3 Expressive 大标题，标题上方一行放返回图标按钮。
 *
 * 草图里就是「先一个返回按钮，下面一行大标题」，不是居中的传统 AppBar。
 */
@Composable
fun MoriTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /**
     * 标题与上方返回按钮的间距。
     *
     * <p>v0.1.9：字体页按设计稿要求放到 30dp；其它页面保持默认，不动它的节奏。
     */
    titleTop: Dp = 4.dp,
    /** 标题与下方首个内容的间距（字体页同样是 30dp）。 */
    titleBottom: Dp = 8.dp,
    /**
     * 顶栏整体的上下留白。
     *
     * <p>字体页要「标题距返回按钮、距首张卡片都正好 30dp」，这层留白会把两个值
     * 各顶多 4dp，所以那一页传 0 —— 否则量出来就是 34dp。
     */
    padV: Dp = 4.dp,
    actions: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Space.ScreenH, vertical = padV),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                FilledIconButton(
                    onClick = onBack,
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = S.BACK,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (actions != null) { actions() }
        }
        Text(
            text = title,
            style = ScreenTitleStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = titleTop, bottom = titleBottom),
        )
    }
}

/** 分区小标题（14sp 粗体）。上方留 [Space.GroupGap]，把各组明显分开。 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = SectionTitleStyle,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(
            start = Space.ScreenH,
            end = Space.ScreenH,
            top = Space.GroupGap,
            bottom = Space.SectionGap,
        ),
    )
}

// ══════════════════════════════════════════════════════════════════
//  卡片
// ══════════════════════════════════════════════════════════════════

/** 卡片风格：填充（surfaceContainerHighest）/ 描边（surface + outlineVariant）/ 浮起。 */
enum class CardTone { FILLED, OUTLINED, ELEVATED }

/**
 * 20dp 圆角的 M3 Expressive 卡片。
 *
 * 颜色角色按提示词：填充 = `surfaceContainerHighest`，
 * 浮起 = `surfaceContainerLow` + Level 1 阴影，描边 = 1dp `outlineVariant`。
 */
@Composable
fun MoriCard(
    modifier: Modifier = Modifier,
    tone: CardTone = CardTone.FILLED,
    height: Dp? = null,
    contentPadding: PaddingValues = PaddingValues(Space.CardPad),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors: CardColors = when (tone) {
        CardTone.FILLED -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        CardTone.OUTLINED -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        )
        CardTone.ELEVATED -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }
    val elevation = if (tone == CardTone.ELEVATED) 1.dp else 0.dp
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (height != null) Modifier.heightIn(min = height) else Modifier),
        shape = MaterialTheme.shapes.large,        // 20dp
        colors = colors,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = if (tone == CardTone.OUTLINED) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content,
        )
    }
}

/** 卡片标题 + 说明（标题 titleMedium，正文 bodyMedium，间距 4dp）。 */
@Composable
fun CardHeader(
    title: String,
    supporting: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        // ★ v0.1.7：没有副标题时，右侧控件（开关等）与标题**居中对齐**，
        //   否则 Top 对齐会让开关顶边贴着标题顶边，视觉上比文字高半行。
        verticalAlignment = if (supporting.isNullOrEmpty()) {
            Alignment.CenterVertically
        } else {
            Alignment.Top
        },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!supporting.isNullOrEmpty()) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.ItemGap))
            trailing()
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  列表项
// ══════════════════════════════════════════════════════════════════

/**
 * 列表项：最小 72dp 高，24dp 图标放在 40dp 的 `primaryContainer` 圆上。
 *
 * 主文本 bodyLarge，辅助文本 bodyMedium(`onSurfaceVariant`)，
 * 背景由调用方给的 [shape] + `surfaceContainerLow` 决定（相连列表样式）。
 *
 * @param trailing 右侧插槽（开关 / 按钮）。
 * @param onClick 为 null 时整行不可点（纯展示行）。
 */
@Composable
fun MoriListItem(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    shape: Shape = MaterialTheme.shapes.large,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val clickable = onClick != null && enabled
    val ctx = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .alpha(if (enabled) 1f else 0.38f)
            .then(
                if (clickable) {
                    // ⚠️ v0.0.8：点击先过 [UiDiag] 探针再进业务。
                    //    所有列表项的点击都收敛到这一处 ⇒ 日志/Toast 一定能覆盖到，
                    //    不会出现「某个条目漏了打点导致判断不了」。
                    Modifier.moriClickable(enabled = true) {
                        UiDiag.click(ctx, title)
                        onClick!!.invoke()
                    }
                } else {
                    Modifier
                }
            )
            .padding(start = Space.CardPad, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
        }

        Spacer(Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!summary.isNullOrEmpty()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (trailing != null) {
            Spacer(Modifier.width(Space.ItemGap))
            Box(contentAlignment = Alignment.Center) { trailing() }
        } else if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** 相连列表容器：项之间 3dp，逐项给出 M3 Expressive 的相连形状。 */
@Composable
fun ConnectedItems(
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(index: Int) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.ConnectedGap),
    ) {
        repeat(count) { i -> content(i) }
    }
}

/** 「标签 : 值」一行。 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Space.ItemGap))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f),
        )
    }
}

/** 空状态占位：可选图标 + 说明文案。 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp, horizontal = Space.ScreenH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.ItemGap),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一个纯色圆角容器（用于 F2 的透明背景预览等）。 */
@Composable
fun ColorSwatch(
    color: Color,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
) {
    Surface(color = color, shape = shape, modifier = modifier) {}
}
