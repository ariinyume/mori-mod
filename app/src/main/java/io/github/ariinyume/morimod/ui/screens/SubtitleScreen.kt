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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import io.github.ariinyume.morimod.ui.MoriViewModel
import io.github.ariinyume.morimod.ui.S
import io.github.ariinyume.morimod.ui.components.CardTone
import io.github.ariinyume.morimod.ui.components.MoriCard
import io.github.ariinyume.morimod.ui.components.MoriSwitch
import io.github.ariinyume.morimod.ui.components.MoriTopBar
import io.github.ariinyume.morimod.ui.components.Space
import io.github.ariinyume.morimod.ui.components.ThickSlider

/**
 * 字幕调整二级页：总开关 + 字号倍率 + 字幕背景透明。
 */
@Composable
internal fun SubtitleScreen(
    vm: MoriViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleGap = 30.dp
    val rowGap = 14.dp

    Column(modifier = modifier.fillMaxSize()) {
        MoriTopBar(
            title = S.F6_TITLE,
            onBack = onBack,
            titleTop = titleGap,
            titleBottom = titleGap,
            padV = 0.dp,
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item {
                val cfg = vm.config
                MoriCard(
                    modifier = Modifier.padding(horizontal = Space.ScreenH),
                    tone = CardTone.FILLED,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = S.F6_ENABLE,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        MoriSwitch(
                            checked = cfg.f6Enabled,
                            onCheckedChange = { vm.setF6Enabled(it) },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(Space.CardGap)) }

            item {
                val cfg = vm.config
                val on = cfg.f6Enabled
                MoriCard(
                    modifier = Modifier
                        .padding(horizontal = Space.ScreenH)
                        .alpha(if (on) 1f else 0.38f),
                    tone = CardTone.FILLED,
                ) {
                    Text(
                        text = S.ITEM_SUBTITLE_SIZE,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(rowGap))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "×" + String.format("%.1f", cfg.f6SubtitleScale),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    ThickSlider(
                        value = cfg.f6SubtitleScale,
                        onValueChange = { if (on) vm.setF6SubtitleScale(it) },
                        valueRange = 1.0f..1.5f,
                        steps = 4,
                        enabled = on,
                    )
                }
            }

            item { Spacer(Modifier.height(Space.CardGap)) }

            item {
                val cfg = vm.config
                val on = cfg.f6Enabled
                MoriCard(
                    modifier = Modifier
                        .padding(horizontal = Space.ScreenH)
                        .alpha(if (on) 1f else 0.38f),
                    tone = CardTone.FILLED,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = S.ITEM_SUBTITLE_BG_CLEAR,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        MoriSwitch(
                            checked = cfg.f6SubtitleBgTransparent,
                            onCheckedChange = { if (on) vm.setF6SubtitleBgTransparent(it) },
                            enabled = on,
                        )
                    }
                }
            }
        }
    }
}
