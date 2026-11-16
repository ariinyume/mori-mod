package io.github.ariinyume.morimod.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ariinyume.morimod.ui.MoriViewModel
import io.github.ariinyume.morimod.ui.S
import io.github.ariinyume.morimod.ui.components.BtnSize
import io.github.ariinyume.morimod.ui.components.BtnTone
import io.github.ariinyume.morimod.ui.components.CardTone
import io.github.ariinyume.morimod.ui.components.ConnectedButtonGroup
import io.github.ariinyume.morimod.ui.components.MoriCard
import io.github.ariinyume.morimod.ui.components.MoriSwitch
import io.github.ariinyume.morimod.ui.components.MoriTopBar
import io.github.ariinyume.morimod.ui.components.Space

/**
 * F5 · 宿主主页区块显隐与排序（路线 2）。
 *
 * 版式对齐背景自定义页：大标题上下 30dp；区块单行（拖动手柄 + 名称 | 上下移 + 开关）。
 * 点选条目高亮抬起，上下移动时带 Lazy 项位移动画。
 */
@Composable
internal fun LayoutScreen(
    vm: MoriViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        vm.ensureF5Catalog()
        vm.tickHostStatus()
    }
    val cfg = vm.config
    val modules = cfg.f5Modules
    val listState = rememberLazyListState()
    val titleGap = 30.dp
    val cardGap = 20.dp
    var selectedId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = Space.ScreenH,
            end = Space.ScreenH,
            top = 0.dp,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(cardGap),
    ) {
        item {
            MoriTopBar(
                title = S.F5_TITLE,
                onBack = onBack,
                titleTop = titleGap,
                titleBottom = titleGap,
                padV = 0.dp,
            )
        }

        item {
            MoriCard(tone = CardTone.FILLED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = S.F5_ENABLE,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    MoriSwitch(
                        checked = cfg.f5Enabled,
                        onCheckedChange = { vm.setF5Enabled(it) },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = S.F5_NOTE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Text(
                text = S.F5_MODULES,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        itemsIndexed(modules, key = { _, e -> e.id }) { index, entry ->
            val selected = selectedId == entry.id
            ModuleRow(
                modifier = Modifier.animateItem(
                    fadeInSpec = null,
                    fadeOutSpec = null,
                    placementSpec = spring(stiffness = 380f, dampingRatio = 0.82f),
                ),
                title = f5ModuleTitle(entry.id),
                visible = entry.visible,
                enabled = cfg.f5Enabled,
                selected = selected,
                canUp = index > 0,
                canDown = index < modules.size - 1,
                onSelect = { selectedId = entry.id },
                onVisible = { vm.setF5Visible(entry.id, it) },
                onUp = {
                    selectedId = entry.id
                    vm.moveF5(entry.id, -1)
                },
                onDown = {
                    selectedId = entry.id
                    vm.moveF5(entry.id, +1)
                },
                onMoveByDrag = { from, to ->
                    selectedId = entry.id
                    if (from != to) vm.reorderF5(from, to)
                },
                index = index,
            )
        }

        item {
            ConnectedButtonGroup(
                labels = listOf(S.F5_RESTORE),
                icons = listOf(Icons.Rounded.RestartAlt),
                onSelect = { vm.resetF5Layout() },
                size = BtnSize.S,
                tone = BtnTone.TONAL,
                enabled = cfg.f5Enabled,
                stretchAll = true,
            )
        }
    }
}

@Composable
private fun ModuleRow(
    title: String,
    visible: Boolean,
    enabled: Boolean,
    selected: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    index: Int,
    onSelect: () -> Unit,
    onVisible: (Boolean) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onMoveByDrag: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragAccum by remember { mutableStateOf(0f) }
    val rowH = 56.dp
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.03f else 1f,
        animationSpec = spring(stiffness = 420f, dampingRatio = 0.78f),
        label = "f5-row-scale",
    )
    val lift by animateFloatAsState(
        targetValue = if (selected) 8f else 0f,
        animationSpec = spring(stiffness = 420f, dampingRatio = 0.78f),
        label = "f5-row-lift",
    )
    val bg = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(rowH)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = -lift * 0.35f
                shadowElevation = lift
            }
            .shadow(
                elevation = if (selected) 6.dp else 0.dp,
                shape = MaterialTheme.shapes.large,
                clip = false,
            )
            .clip(MaterialTheme.shapes.large)
            .background(bg)
            .border(1.5.dp, borderColor, MaterialTheme.shapes.large)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onSelect,
            )
            .pointerInput(enabled, index) {
                if (!enabled) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        onSelect()
                        dragAccum = 0f
                    },
                    onDragEnd = {
                        val step = (rowH.toPx()).coerceAtLeast(1f)
                        val steps = (dragAccum / step).toInt()
                        if (steps != 0) {
                            onMoveByDrag(index, (index + steps).coerceAtLeast(0))
                        }
                        dragAccum = 0f
                    },
                    onDragCancel = { dragAccum = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragAccum += dragAmount.y
                    },
                )
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.DragHandle,
            contentDescription = null,
            tint = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onUp,
            enabled = enabled && canUp,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Rounded.ArrowUpward,
                contentDescription = S.F5_MOVE_UP,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(
            onClick = onDown,
            enabled = enabled && canDown,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Rounded.ArrowDownward,
                contentDescription = S.F5_MOVE_DOWN,
                modifier = Modifier.size(20.dp),
            )
        }
        MoriSwitch(
            checked = visible,
            onCheckedChange = onVisible,
            enabled = enabled,
        )
    }
}

internal fun f5ModuleTitle(id: String): String = when (id) {
    "genre" -> S.F5_MOD_GENRE
    "recent_purchase" -> S.F5_MOD_RECENT_PURCHASE
    "recent_played" -> S.F5_MOD_RECENT_PLAYED
    "my_sound" -> S.F5_MOD_MY_SOUND
    "recap" -> S.F5_MOD_RECAP
    "sync" -> S.F5_MOD_SYNC
    else -> S.F5_MOD_UNKNOWN + " ($id)"
}
