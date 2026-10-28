package io.github.ariinyume.morimod.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * M3 按钮尺寸（提示词：XS 32dp / S 40dp / M 56dp / L 96dp / XL 136dp）。
 *
 * 草图里卡片内的按钮组标的是 `size2 = 32`，即 **XS**；
 * 顶栏的语言按钮同样是 32dp。
 */
enum class BtnSize(val height: Dp, val padH: Dp, val icon: Dp, val gap: Dp) {
    XS(32.dp, 12.dp, 18.dp, 6.dp),
    S(40.dp, 16.dp, 20.dp, 8.dp),
    M(56.dp, 24.dp, 24.dp, 8.dp),
}

/** 相连按钮组的配色基调。 */
enum class BtnTone { FILLED, TONAL }

/**
 * M3 Expressive **相连按钮组**（Connected button group）。
 *
 * 规格来自提示词：
 *  · 同一 `Row` 内横向排成一行，3dp 间距；
 *  · 只把相邻的**内侧**圆角缩到 8dp，外侧保持胶囊形（见 [connectedButtonShape]）；
 *  · 末项可拉伸占满剩余宽度（`stretchLast`），或每个按钮等宽平分（`stretchAll`）。
 *
 * 颜色：`FILLED` 用 primary（草图里顶栏那颗 CTA 用它），
 * `TONAL` 用 secondaryContainer —— 卡片内的动作按钮用 tonal，
 * 否则一屏会有 4 个实心 primary 胶囊，视觉过重（提示词允许「根据内容适当调整」）。
 *
 * ⚠️ 没有直接用 M3 Expressive 的 `ButtonGroup` / `clickableItem`：那套的 item 形状
 *    由 `ToggleButtonShapes` 内部控制，外层还得再套一层 `Button` 才能渲染中文文案，
 *    两层各自带点击与涟漪反而容易打架。用标准 `Button` + 自由 `shape` 表达同一意图，
 *    颜色/涟漪/按压反馈仍全部走 M3 标准组件。
 */
@Composable
fun ConnectedButtonGroup(
    labels: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<ImageVector?>? = null,
    size: BtnSize = BtnSize.XS,
    tone: BtnTone = BtnTone.TONAL,
    enabled: Boolean = true,
    stretchLast: Boolean = false,
    stretchAll: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.ConnectedGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val isLast = index == labels.lastIndex
            val shape = connectedButtonShape(index, labels.size)
            val icon = icons?.getOrNull(index)
            val content: @Composable () -> Unit = {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.width(size.icon).height(size.icon),
                    )
                    Spacer(Modifier.width(size.gap))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
            // stretchAll = 每个按钮等宽平分；stretchLast 只拉伸末项。
            val btnModifier = if (stretchAll || (isLast && stretchLast)) {
                Modifier
                    .weight(1f)
                    .height(size.height)
            } else {
                Modifier.height(size.height)
            }
            when (tone) {
                BtnTone.FILLED -> Button(
                    onClick = { onSelect(index) },
                    enabled = enabled,
                    shape = shape,
                    contentPadding = PaddingValues(horizontal = size.padH),
                    modifier = btnModifier,
                    content = { content() },
                )
                BtnTone.TONAL -> FilledTonalButton(
                    onClick = { onSelect(index) },
                    enabled = enabled,
                    shape = shape,
                    contentPadding = PaddingValues(horizontal = size.padH),
                    modifier = btnModifier,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    content = { content() },
                )
            }
        }
    }
}

/**
 * M3 Expressive 粗轨道滑块。
 *
 * 提示词规格「16dp 粗轨道 + 竖长手柄 4×44dp，手柄左 primary / 右 secondaryContainer」
 * ——这正是 material3 1.5 alphas 起 `Slider` 的**默认**外观
 * （`SliderDefaults.Track` 粗轨道 + `SliderDefaults.Thumb` 竖长条），故直接用标准 `Slider`。
 */
@Composable
fun ThickSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(),
    )
}

/**
 * 「粗体标签 + 右端数值」一行，下面接滑块。
 *
 * 数值靠右（提示词：右侧文本向右拉伸占满剩余宽度 ⇒ 用 `weight(1f)` 推到最右），
 * 且与标签同一行、垂直居中、不换行；滑动时数值变化不会导致布局抖动。
 */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    valueText: String = "${value.toInt()}%",
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(2.dp))
        ThickSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

/**
 * 标准 M3 开关。轨道 52×32dp 是 material3 的原生尺寸，
 * 与提示词规格完全一致，故不做任何尺寸覆写。
 */
@Composable
fun MoriSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(),
    )
}
