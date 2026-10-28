package io.github.ariinyume.morimod.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ariinyume.morimod.config.MoriConfig
import io.github.ariinyume.morimod.ui.FileStore
import io.github.ariinyume.morimod.ui.MoriViewModel
import io.github.ariinyume.morimod.ui.S
import io.github.ariinyume.morimod.ui.components.BtnSize
import io.github.ariinyume.morimod.ui.components.BtnTone
import io.github.ariinyume.morimod.ui.components.CardHeader
import io.github.ariinyume.morimod.ui.components.CardTone
import io.github.ariinyume.morimod.ui.components.ConnectedButtonGroup
import io.github.ariinyume.morimod.ui.components.ImagePlaceholder
import io.github.ariinyume.morimod.ui.components.MoriCard
import io.github.ariinyume.morimod.ui.components.MoriConfirmDialog
import io.github.ariinyume.morimod.ui.components.MoriInputDialog
import io.github.ariinyume.morimod.ui.components.MoriListItem
import io.github.ariinyume.morimod.ui.components.MoriSwitch
import io.github.ariinyume.morimod.ui.components.MoriTopBar
import io.github.ariinyume.morimod.ui.components.Space
import io.github.ariinyume.morimod.ui.components.moriCombinedClickable
import io.github.ariinyume.morimod.ui.components.rememberFileImage

/**
 * 系统播放控件自定义（仅面 A：锁屏与通知栏）。
 *
 * 结构（与草图一致）：返回 + 大标题 → 替换封面图卡 → 替换音轨名卡 → 替换社团名卡。
 * 每张卡内部按提示词的「内部叠放」实现：卡片是容器，内容与按钮组叠在它上面，
 * 按钮组固定在卡片**下部居中**。
 */
@Composable
internal fun MediaControlScreen(
    vm: MoriViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current

    var pendingDeleteMedia by remember { mutableStateOf<String?>(null) }
    var pendingDeleteName by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf<String?>(null) }   // "cover" | "track" | "artist"
    var addNameType by remember { mutableStateOf<String?>(null) }
    var addNameText by remember { mutableStateOf("") }
    var exportType by remember { mutableStateOf<String?>(null) }

    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            vm.reportPickCanceled()
            return@rememberLauncherForActivityResult
        }
        val path = vm.importUri(uri, FileStore.KIND_IMAGE)
        if (path == null) vm.reportPickFailed()
        else vm.addMedia(path, "cover", FileStore.displayName(ctx, uri))
    }

    // 「导出列表」：先问文件名，再由系统选保存位置
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val type = exportType
        exportType = null
        if (uri == null) {
            vm.reportPickCanceled()
            return@rememberLauncherForActivityResult
        }
        if (type == null) {
            vm.notify(S.TOAST_NO_SELECTION)
            return@rememberLauncherForActivityResult
        }
        val text = vm.exportText(type, isMedia = false)
        if (text.isEmpty()) {
            vm.notify(S.TOAST_NO_SELECTION)
        } else if (FileStore.writeText(ctx, uri, text)) {
            vm.notify(S.TOAST_EXPORTED)
        } else {
            vm.notify(S.TOAST_EXPORT_FAILED)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        MoriTopBar(title = S.F1_TITLE, onBack = onBack)

        // ⚠️ 必须 weight(1f)，不能用 fillMaxSize()：Column 里 fillMaxSize 会让列表
        //    比可视区高出「顶栏那一段」，最后一行永远滚不进可视区（也点不到）。
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.CardGap),
        ) {
            // ── 启用总开关 ──
            item {
                // ⚠️ 在 item 作用域里读 config：LazyColumn 的每一项是独立重组作用域，
                //    只有在这里读到的快照状态变化才会让这一项重组（否则开关点了不动）。
                val cfg = vm.config
                MoriCard(
                    modifier = Modifier.padding(horizontal = Space.ScreenH),
                    tone = CardTone.FILLED,
                ) {
                    CardHeader(
                        title = S.F1_ENABLE,
                        trailing = {
                            MoriSwitch(
                                checked = cfg.f1Enabled,
                                onCheckedChange = { vm.setF1Enabled(it) },
                            )
                        },
                    )
                }
            }

            // ── 替换封面图 ──
            item {
                CoverCard(
                    vm = vm,
                    modifier = Modifier.padding(horizontal = Space.ScreenH),
                    onAdd = {
                        coverPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onDelete = { pendingDeleteMedia = it },
                    onClear = { confirmClear = "cover" },
                )
            }

            // ── 替换音轨名 ──
            item {
                NameCard(
                    vm = vm,
                    type = "track",
                    title = S.F1_CARD_TRACK,
                    icon = Icons.Rounded.AudioFile,
                    modifier = Modifier.padding(horizontal = Space.ScreenH),
                    onAdd = { addNameText = ""; addNameType = "track" },
                    onDelete = { pendingDeleteName = it },
                    onClear = { confirmClear = "track" },
                    onExport = {
                        exportType = "track"
                        exportLauncher.launch(S.EXPORT_FILE_TRACK)
                    },
                )
            }

            // ── 替换社团名 ──
            item {
                NameCard(
                    vm = vm,
                    type = "artist",
                    title = S.F1_CARD_ARTIST,
                    icon = Icons.Rounded.Person,
                    modifier = Modifier.padding(horizontal = Space.ScreenH),
                    onAdd = { addNameText = ""; addNameType = "artist" },
                    onDelete = { pendingDeleteName = it },
                    onClear = { confirmClear = "artist" },
                    onExport = {
                        exportType = "artist"
                        exportLauncher.launch(S.EXPORT_FILE_ARTIST)
                    },
                )
            }
        }
    }

    // ── 删除确认 ──
    pendingDeleteMedia?.let { id ->
        MoriConfirmDialog(
            title = S.DLG_DELETE_TITLE,
            text = S.DLG_DELETE_TEXT,
            confirmText = S.BTN_CLEAR,
            destructive = true,
            onConfirm = { vm.removeMedia(id); pendingDeleteMedia = null },
            onDismiss = { pendingDeleteMedia = null },
        )
    }
    pendingDeleteName?.let { id ->
        MoriConfirmDialog(
            title = S.DLG_DELETE_TITLE,
            text = S.DLG_DELETE_TEXT,
            confirmText = S.BTN_CLEAR,
            destructive = true,
            onConfirm = { vm.removeName(id); pendingDeleteName = null },
            onDismiss = { pendingDeleteName = null },
        )
    }

    // ── 清空列表确认 ──
    confirmClear?.let { type ->
        MoriConfirmDialog(
            title = S.DLG_CLEAR_TITLE,
            text = S.DLG_CLEAR_TEXT,
            confirmText = S.BTN_CLEAR,
            destructive = true,
            onConfirm = {
                vm.clearPool(type, isMedia = (type == "cover"))
                confirmClear = null
            },
            onDismiss = { confirmClear = null },
        )
    }

    // ── 批量添加名称 ──
    addNameType?.let { type ->
        MoriInputDialog(
            title = S.DLG_ADD_NAME,
            label = S.DLG_FIELD_TEXT,
            hint = S.DLG_ADD_NAME_HINT,
            value = addNameText,
            onValueChange = { addNameText = it },
            confirmText = S.BTN_ADD_NAME,
            onConfirm = {
                vm.addNames(addNameText, type)
                addNameType = null
                addNameText = ""
            },
            onDismiss = { addNameType = null },
        )
    }
}

/**
 * 替换封面图卡（草图 256dp）。
 *
 * 内部叠放：卡片本身是容器，图片矩阵与按钮组都画在它上面，
 * 按钮组**下部居中**。
 */
@Composable
private fun CoverCard(
    vm: MoriViewModel,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = vm.mediaOf("cover")
    val cardMin = 256.dp
    MoriCard(modifier = modifier, tone = CardTone.FILLED) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = cardMin - Space.CardPad * 2),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 48.dp),   // 给底部按钮组让位
            ) {
                CardHeader(title = S.F1_CARD_COVER, supporting = S.F1_CARD_COVER_SUM)
                Spacer(Modifier.height(Space.ItemGap))
                if (items.isEmpty()) {
                    Text(
                        text = S.F1_EMPTY_COVER,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 22.dp),
                    )
                } else {
                    Text(
                        text = S.enabledCount(items.count { it.enabled }, items.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Space.ItemGap))
                    // 手写分块网格：LazyVerticalGrid 嵌在 LazyColumn 里会因
                    // 「无限高约束」直接崩溃，这里用 Row/Column 分块最稳。
                    items.chunked(4).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Space.ItemGap),
                        ) {
                            row.forEach { m ->
                                CoverThumb(
                                    item = m,
                                    onClick = { vm.toggleMedia(m.id) },
                                    onLongClick = { onDelete(m.id) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // 补齐空位，保证最后一行不会被拉伸变形
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                        Spacer(Modifier.height(Space.ItemGap))
                    }
                }
            }

            // 下部居中的相连按钮组
            ConnectedButtonGroup(
                labels = listOf(S.BTN_ADD_IMAGE, S.BTN_RESTORE, S.BTN_CLEAR),
                onSelect = { i ->
                    when (i) {
                        0 -> onAdd()
                        1 -> vm.restorePool("cover", isMedia = true)
                        else -> onClear()
                    }
                },
                size = BtnSize.XS,
                tone = BtnTone.TONAL,
                stretchLast = false,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** 封面缩略图：点一下切换启用（草图的「点选应用图片」），长按删除。 */
@Composable
private fun CoverThumb(
    item: MoriConfig.MediaItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bmp = rememberFileImage(item.path, targetPx = 256)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .then(
                if (item.enabled) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                }
            )
            .moriCombinedClickable(onLongClick = onLongClick, onClick = onClick),
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = item.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ImagePlaceholder(cornerRadius = 12.dp)
        }
        if (item.enabled) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(18.dp),
            )
        }
    }
}

/**
 * 替换音轨名 / 社团名卡（草图 280dp）。
 *
 * 内部叠放：名称列表在上，相连按钮组固定在卡片下部。
 */
@Composable
private fun NameCard(
    vm: MoriViewModel,
    type: String,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = vm.namesOf(type)
    val cardMin = 280.dp
    MoriCard(modifier = modifier, tone = CardTone.FILLED) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = cardMin - Space.CardPad * 2),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 48.dp),
            ) {
                CardHeader(title = title, supporting = S.F1_CARD_NAME_SUM)
                Spacer(Modifier.height(Space.ItemGap))
                if (items.isEmpty()) {
                    Text(
                        text = S.F1_EMPTY_NAME,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 22.dp),
                    )
                } else {
                    Text(
                        text = S.enabledCount(items.count { it.enabled }, items.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    items.forEach { n ->
                        NameRow(
                            item = n,
                            icon = icon,
                            onToggle = { vm.toggleName(n.id) },
                            onDelete = { onDelete(n.id) },
                        )
                    }
                }
            }

            ConnectedButtonGroup(
                labels = listOf(S.BTN_ADD_NAME, S.BTN_RESTORE, S.BTN_CLEAR, S.BTN_EXPORT),
                onSelect = { i ->
                    when (i) {
                        0 -> onAdd()
                        1 -> vm.restorePool(type, isMedia = false)
                        2 -> onClear()
                        else -> onExport()
                    }
                },
                size = BtnSize.XS,
                tone = BtnTone.TONAL,
                stretchLast = false,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** 名称库一项：名称 + 启用开关 + 删除。 */
@Composable
private fun NameRow(
    item: MoriConfig.NameItem,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(NameRowIcon),
        )
        Spacer(Modifier.width(Space.ItemGap))
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggle, modifier = Modifier.size(NameRowButton)) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = if (item.enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(NameRowButton)) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 名称行左侧小图标的尺寸。 */
private val NameRowIcon = 18.dp

/** 名称行右侧按钮的尺寸。 */
private val NameRowButton = 36.dp
