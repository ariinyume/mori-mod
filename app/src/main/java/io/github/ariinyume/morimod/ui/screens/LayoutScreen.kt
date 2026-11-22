package io.github.ariinyume.morimod.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import kotlin.math.roundToInt

/**
 * F5 · 宿主主页区块显隐与排序（路线 2）。
 *
 * 长按拖动：跟手位移 + 其它行视觉避让；松手才落盘换位（避免中途 persist 乱跳）。
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
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val rowStepPx = with(density) { (56.dp + cardGap).toPx() }

    val dragFrom = modules.indexOfFirst { it.id == draggingId }
    val dragTarget = if (dragFrom >= 0) {
        (dragFrom + (dragOffsetY / rowStepPx.coerceAtLeast(1f)).roundToInt())
            .coerceIn(0, modules.lastIndex)
    } else {
        -1
    }

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
            val dragging = draggingId == entry.id
            // 拖动中：被拖行跟手；中间行让位；松手再 reorder
            val settleY = when {
                dragging -> dragOffsetY
                dragFrom >= 0 && dragTarget > dragFrom
                    && index in (dragFrom + 1)..dragTarget -> -rowStepPx
                dragFrom >= 0 && dragTarget < dragFrom
                    && index in dragTarget until dragFrom -> rowStepPx
                else -> 0f
            }
            ModuleRow(
                modifier = Modifier
                    .zIndex(if (dragging) 2f else if (selected) 1f else 0f)
                    .then(
                        if (draggingId == null) {
                            Modifier.animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = null,
                                placementSpec = spring(stiffness = 380f, dampingRatio = 0.82f),
                            )
                        } else {
                            Modifier
                        },
                    ),
                title = f5ModuleTitle(entry.id),
                visible = entry.visible,
                enabled = cfg.f5Enabled,
                selected = selected || dragging,
                dragOffsetY = settleY,
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
                onDragStart = {
                    selectedId = entry.id
                    draggingId = entry.id
                    dragOffsetY = 0f
                },
                onDrag = { dy -> dragOffsetY += dy },
                onDragEnd = {
                    val from = modules.indexOfFirst { it.id == entry.id }
                    val to = if (from >= 0) {
                        (from + (dragOffsetY / rowStepPx.coerceAtLeast(1f)).roundToInt())
                            .coerceIn(0, modules.lastIndex)
                    } else {
                        from
                    }
                    if (from >= 0 && to != from) vm.reorderF5(from, to)
                    draggingId = null
                    dragOffsetY = 0f
                },
                rowKey = entry.id,
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
    dragOffsetY: Float,
    canUp: Boolean,
    canDown: Boolean,
    onSelect: () -> Unit,
    onVisible: (Boolean) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    rowKey: String,
    modifier: Modifier = Modifier,
) {
    val rowH = 56.dp
    val shape = MaterialTheme.shapes.large
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.03f else 1f,
        animationSpec = spring(stiffness = 420f, dampingRatio = 0.78f),
        label = "f5-row-scale",
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(rowH)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = dragOffsetY
            }
            .then(
                if (selected) {
                    Modifier.shadow(
                        elevation = 8.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f),
                        spotColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.36f),
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(bg, shape)
            .border(1.5.dp, borderColor, shape)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onSelect,
            )
            .pointerInput(enabled, rowKey) {
                if (!enabled) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.y)
                    },
                )
            }
            .padding(horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
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
