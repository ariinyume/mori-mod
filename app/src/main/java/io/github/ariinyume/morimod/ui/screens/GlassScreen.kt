package io.github.ariinyume.morimod.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ariinyume.morimod.ui.MoriViewModel
import io.github.ariinyume.morimod.ui.S
import io.github.ariinyume.morimod.ui.components.BtnSize
import io.github.ariinyume.morimod.ui.components.BtnTone
import io.github.ariinyume.morimod.ui.components.CardTone
import io.github.ariinyume.morimod.ui.components.ConnectedButtonGroup
import io.github.ariinyume.morimod.ui.components.LabeledSlider
import io.github.ariinyume.morimod.ui.components.MoriCard
import io.github.ariinyume.morimod.ui.components.MoriSwitch
import io.github.ariinyume.morimod.ui.components.MoriTopBar
import io.github.ariinyume.morimod.ui.components.Space

/**
 * 液态玻璃主题设置页（方案 S5）。
 *
 * <p>版式对齐 [BackgroundScreen] / [FontScreen]：标题上下 30dp、卡片间距 20dp、
 * 卡内 14dp；每个 LazyColumn item 内部各读一次 `vm.config`。
 *
 * <p>依赖 F2 壁纸：没背景时开关可开，但宿主侧不会渲染玻璃（§八）。
 */
@Composable
internal fun GlassScreen(
    vm: MoriViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleGap = 30.dp
    val cardGap = 20.dp
    val rowGap = 14.dp

    Column(modifier = modifier.fillMaxSize()) {
        MoriTopBar(
            title = S.F3_TITLE,
            onBack = onBack,
            titleTop = titleGap,
            titleBottom = titleGap,
            padV = 0.dp,
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                val cfg = vm.config
                MoriCard(
                    modifier = Modifier.padding(horizontal = Space.ScreenH),
                    tone = CardTone.FILLED,
                    contentPadding = PaddingValues(Space.CardPad),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = S.F3_ENABLE,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        MoriSwitch(
                            checked = cfg.f3Enabled,
                            onCheckedChange = { vm.setF3Enabled(it) },
                        )
                    }
                    Spacer(Modifier.height(rowGap))
                    Text(
                        text = S.F3_ENABLE_NOTE,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { Spacer(Modifier.height(cardGap)) }

            item {
                val cfg = vm.config
                MoriCard(
                    modifier = Modifier.padding(horizontal = Space.ScreenH),
                    tone = CardTone.FILLED,
                    contentPadding = PaddingValues(Space.CardPad),
                ) {
                    Text(
                        text = S.F3_MODE,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(rowGap))
                    ConnectedButtonGroup(
                        labels = listOf(S.F3_MODE_REGULAR, S.F3_MODE_CLEAR),
                        onSelect = { i ->
                            vm.setF3Mode(if (i == 1) "clear" else "regular")
                        },
                        size = BtnSize.XS,
                        tone = BtnTone.FILLED,
                        stretchAll = true,
                        enabled = cfg.f3Enabled,
                    )
                    // 高亮当前档：用副文案提示
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (cfg.f3Mode == "clear") S.F3_MODE_CLEAR_SUM else S.F3_MODE_REGULAR_SUM,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(rowGap))
                    LabeledSlider(
                        label = S.F3_BLUR,
                        value = cfg.f3Blur.toFloat(),
                        onValueChange = { vm.setF3Blur(kotlin.math.round(it).toInt()) },
                        enabled = cfg.f3Enabled,
                        onValueChangeFinished = { vm.commitF3Blur() },
                    )

                    Spacer(Modifier.height(rowGap))
                    LabeledSlider(
                        label = S.F3_TINT,
                        value = cfg.f3Tint.toFloat(),
                        onValueChange = { vm.setF3Tint(kotlin.math.round(it).toInt()) },
                        enabled = cfg.f3Enabled,
                        onValueChangeFinished = { vm.commitF3Tint() },
                    )
                    Text(
                        text = S.F3_TINT_NOTE,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(rowGap))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = S.F3_SPECULAR,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        MoriSwitch(
                            checked = cfg.f3Specular,
                            onCheckedChange = { vm.setF3Specular(it) },
                            enabled = cfg.f3Enabled,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(cardGap)) }

            item {
                val cfg = vm.config
                MoriCard(
                    modifier = Modifier.padding(horizontal = Space.ScreenH),
                    tone = CardTone.FILLED,
                    contentPadding = PaddingValues(Space.CardPad),
                ) {
                    Text(
                        text = S.F3_COVERAGE,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(rowGap))
                    ConnectedButtonGroup(
                        labels = listOf(S.F3_COV_PANELS, S.F3_COV_FULL),
                        onSelect = { i ->
                            vm.setF3Coverage(if (i == 1) "full" else "panels")
                        },
                        size = BtnSize.XS,
                        tone = BtnTone.FILLED,
                        stretchAll = true,
                        enabled = cfg.f3Enabled,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (cfg.f3Coverage == "full") S.F3_COV_FULL_SUM else S.F3_COV_PANELS_SUM,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = S.F3_FOOTNOTE,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.ScreenH),
                )
            }
        }
    }
}
