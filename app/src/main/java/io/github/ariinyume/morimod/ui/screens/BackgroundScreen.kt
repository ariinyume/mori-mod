package io.github.ariinyume.morimod.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import io.github.ariinyume.morimod.config.MoriConfig
import io.github.ariinyume.morimod.ui.FileStore
import io.github.ariinyume.morimod.ui.MoriViewModel
import io.github.ariinyume.morimod.ui.S
import io.github.ariinyume.morimod.ui.components.BtnSize
import io.github.ariinyume.morimod.ui.components.BtnTone
import io.github.ariinyume.morimod.ui.components.CardTone
import io.github.ariinyume.morimod.ui.components.ConnectedButtonGroup
import io.github.ariinyume.morimod.ui.components.ImagePlaceholder
import io.github.ariinyume.morimod.ui.components.LabeledSlider
import io.github.ariinyume.morimod.ui.components.MoriCard
import io.github.ariinyume.morimod.ui.components.MoriSwitch
import io.github.ariinyume.morimod.ui.components.MoriTopBar
import io.github.ariinyume.morimod.ui.components.Space
import io.github.ariinyume.morimod.ui.components.moriClickable
import io.github.ariinyume.morimod.ui.components.moriCombinedClickable
import io.github.ariinyume.morimod.ui.components.rememberFileImage

/**
 * 主程序背景自定义（PRD D-05）。
 *
 * <h3>版式（v0.1.10 对齐字体页）</h3>
 * 用户明确要求「参考字体替换页面调整，特别是按钮颜色、选项间的间距」，所以这里
 * 和 `FontScreen` 完全同一套节奏：
 * <ul>
 *   <li>标题距返回按钮、距首张卡片都正好 **30dp**（`MoriTopBar(titleTop/titleBottom = 30)`，
 *       并且 `padV = 0` —— 否则顶栏自带的那 4dp 会把它顶成 34dp）；</li>
 *   <li>卡片之间 **20dp**（[cardGap]），卡内块之间 **14dp**（[rowGap]）；</li>
 *   <li>动作按钮用 **FILLED**（primary 实心胶囊）+ 等宽平分，和字体页一致，
 *       不再是之前那种 tonal 浅底（浅底在浅色卡上看不出可点）；</li>
 *   <li>三张填充卡：背景替换 / 我的背景 / 背景预览，说明文字放**卡片外面**（12sp、靠左）。</li>
 * </ul>
 *
 * ⚠️ `vm.config` / `vm.mediaOf(...)` 一律**在 item 作用域里读**：
 *    LazyColumn 的每一项是独立的重组作用域，在屏幕作用域读到的值只在整屏重组时更新，
 *    真机表现就是「开关 / 滑块点不动」（见 HomeScreen 顶部注释）。
 */
@Composable
internal fun BackgroundScreen(
    vm: MoriViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    // 与字体页同一套间距（见类注释）
    val titleGap = 30.dp
    val cardGap = 20.dp
    val rowGap = 14.dp

    var pendingDelete by remember { mutableStateOf<MoriConfig.MediaItem?>(null) }

    // ★ r112：「桌面壁纸」入口与其权限流程已按用户要求整条移除。
    // 选完图先进裁切页（v0.1.11），确认后才入库 + 设为背景
    var pendingCrop by remember { mutableStateOf<CropTarget?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            vm.reportPickCanceled()
            return@rememberLauncherForActivityResult
        }
        val path = vm.importUri(uri, FileStore.KIND_IMAGE)
        if (path == null) {
            vm.reportPickFailed()
        } else {
            // ⚠️ 先进裁切页：宿主背景是整屏 center-crop 的，让用户自己选哪块露出来，
            //    否则横图/留白多的图铺上去只剩中间一条，他没处说理。
            pendingCrop = CropTarget(path, FileStore.displayName(ctx, uri))
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        MoriTopBar(
            title = S.F2_TITLE,
            onBack = onBack,
            titleTop = titleGap,
            titleBottom = titleGap,
            padV = 0.dp,
        )

        // ⚠️ 必须 weight(1f)，不能用 fillMaxSize()：Column 里 fillMaxSize 会让列表
        //    比可视区高出「顶栏那一段」，最后一行永远滚不进可视区（也点不到）。
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // ── ① 背景替换 ────────────────────────────────────────────
            item {
                val cfg = vm.config
                MoriCard(
                    modifier = Modifier.padding(horizontal = Space.ScreenH),
                    tone = CardTone.FILLED,
                    contentPadding = PaddingValues(Space.CardPad),
                ) {
                    // 标题 + 开关同一行（v0.1.7 起开关不再被小字挤低半行）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = S.F2_CARD,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        MoriSwitch(
                            checked = cfg.f2Enabled,
                            onCheckedChange = { vm.setF2Enabled(it) },
                        )
                    }

                    Spacer(Modifier.height(rowGap))
                    Text(
                        text = currentLabel(cfg),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(Modifier.height(rowGap))
                    ConnectedButtonGroup(
                        labels = listOf(
                            S.BTN_PICK_IMAGE, S.BTN_RESTORE,
                        ),
                        onSelect = { i ->
                            when (i) {
                                0 -> picker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                                else -> vm.restoreBackgroundDefault()
                            }
                        },
                        size = BtnSize.XS,
                        tone = BtnTone.FILLED,
                        stretchAll = true,
                    )

                    Spacer(Modifier.height(rowGap))
                    LabeledSlider(
                        label = S.F2_BLUR,
                        value = cfg.f2Blur.toFloat(),
                        onValueChange = { vm.setF2Blur(it.toInt()) },
                        enabled = cfg.f2Enabled,
                        onValueChangeFinished = { vm.commitF2Blur() },
                    )

                    // ★ v0.1.21 用户第 2 条：遮罩浓度滑条已删（它只作用在被完全盖住的
                    //   窗口层上 ⇒ 拖了宿主毫无反应），换成「播放页背景模糊」。
                    //   播放页的底色是宿主自己的不透明背景图，我们会换成用户壁纸，
                    //   这个档位专门控制那一层的模糊程度（0–100%，整数步进）。
                    Spacer(Modifier.height(rowGap))
                    LabeledSlider(
                        label = S.F2_PLAY_BLUR,
                        value = cfg.f2PlayBlur.toFloat(),
                        onValueChange = { vm.setF2PlayBlur(kotlin.math.round(it).toInt()) },
                        enabled = cfg.f2Enabled,
                        onValueChangeFinished = { vm.commitF2PlayBlur() },
                    )
                }
            }

            // ── 卡外说明①：桌面壁纸是什么（v0.1.10 的新语义）──────────
            item {
                }

            // ── ② 我的背景（图库：点选应用 / 长按删除）────────────────
            item {
                val cfg = vm.config
                val pool = vm.mediaOf("background")
                MoriCard(
                    modifier = Modifier.padding(
                        start = Space.ScreenH,
                        end = Space.ScreenH,
                        top = cardGap,
                    ),
                    tone = CardTone.FILLED,
                ) {
                    Text(
                        text = S.F2_MY_BGS,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = S.F2_MY_BGS_NOTE,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(rowGap))
                    if (pool.isEmpty()) {
                        Text(
                            text = S.F2_MY_BGS_EMPTY,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    } else {
                        PoolGrid(
                            pool = pool,
                            currentPath = cfg.f2HomeBg,
                            onPick = { vm.setF2Background(it) },
                            onLongPress = { pendingDelete = it },
                        )
                    }
                }
            }

            // ── ③ 强调色跟自定义背景取色（v0.1.22 用户第 4 条）────────
            //   ★ 用户第 5 条：原来的「背景预览」卡 + 最下方说明文字已删。
            item {
                val cfg = vm.config
                var showStyle by remember { mutableStateOf(false) }
                MoriCard(
                    modifier = Modifier.padding(
                        start = Space.ScreenH,
                        end = Space.ScreenH,
                        top = cardGap,
                    ),
                    tone = CardTone.FILLED,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = S.F2_ACCENT,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        MoriSwitch(
                            checked = cfg.f2Accent,
                            onCheckedChange = { vm.setF2Accent(it) },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = S.F2_ACCENT_NOTE,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 开关开启后才出现「强调色风格」（用户第 4 条的原话）
                    if (cfg.f2Accent) {
                        Spacer(Modifier.height(rowGap))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .moriClickable { showStyle = true }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = S.F2_ACCENT_STYLE,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = cfg.f2AccentStyle,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                if (showStyle) {
                    AccentStyleDialog(
                        current = cfg.f2AccentStyle,
                        onPick = {
                            vm.setF2AccentStyle(it)
                            showStyle = false
                        },
                        onDismiss = { showStyle = false },
                    )
                }
            }
        }
    }

    // 裁切页：确认 = 按框裁出来当背景；跳过 = 原图直接用；关掉 = 删掉刚导入的原图
    val toCrop = pendingCrop
    if (toCrop != null) {
        CropDialog(
            sourcePath = toCrop.path,
            onCancel = {
                vm.discardImported(toCrop.path)
                pendingCrop = null
            },
            onSkip = {
                vm.useUncroppedBackground(toCrop.path, toCrop.label)
                pendingCrop = null
            },
            onConfirm = { cut ->
                vm.commitCroppedBackground(toCrop.path, cut, toCrop.label)
                pendingCrop = null
            },
        )
    }

    // 长按删除的二次确认：删掉的可能正是正在用的那张 ⇒ 会顺带恢复宿主默认，必须确认
    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(S.BTN_DELETE) },
            text = {
                Text(S.F2_DELETE_BODY)
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeMedia(toDelete.id)
                    pendingDelete = null
                }) { Text(S.BTN_DELETE) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(S.BTN_CANCEL) }
            },
        )
    }
}

/** 待裁切的一张图：刚导入的落盘路径 + 展示名（入库时当 label）。 */
private data class CropTarget(val path: String, val label: String)

/** 「当前背景」那一行：未设置 / 某个文件名。 */private fun currentLabel(cfg: MoriConfig): String = when {
    cfg.f2HomeBg.isEmpty() -> S.F2_CURRENT_NONE
    else -> "${S.F2_CURRENT}：${cfg.f2HomeBg.substringAfterLast('/')}"
}

/** 卡片外侧的 12sp 说明（和字体页的 [FontNote] 同一套规格）。 */
@Composable
private fun BgNote(text: String, top: Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Space.ScreenH, end = Space.ScreenH, top = top),
    )
}

/** 背景图库：点选即设为当前背景，长按删除（4 列缩略图）。 */
@Composable
private fun PoolGrid(
    pool: List<MoriConfig.MediaItem>,
    currentPath: String,
    onPick: (String) -> Unit,
    onLongPress: (MoriConfig.MediaItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.ItemGap)) {
        // 每行 4 个：手写 chunked，嵌套 LazyVerticalGrid 会崩（真机踩过）
        pool.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.ItemGap),
            ) {
                row.forEach { m ->
                    PoolThumb(
                        item = m,
                        selected = m.path == currentPath,
                        modifier = Modifier.weight(1f),
                        onPick = { onPick(m.path) },
                        onLongPress = { onLongPress(m) },
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PoolThumb(
    item: MoriConfig.MediaItem,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onPick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val bmp = rememberFileImage(item.path, targetPx = 256)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(64.dp)
            .clip(shape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = shape,
            )
            .moriCombinedClickable(
                onLongClick = onLongPress,
                onClick = onPick,
            ),
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = item.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ImagePlaceholder(cornerRadius = 12.dp, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * 「强调色风格」选择弹窗（v0.1.22 用户第 4 条）。
 *
 * <p>版式参考用户给的截图：圆角浮层 + 一列纯文字选项，选中的那一条带
 * 浅色药丸底 + 左侧对勾，其它行左侧留出同样的缩进 —— 这样点选时文字不会左右跳。
 * 九个名字直接沿用 Material 3 Expressive 的配色方案名，不翻译。
 */
@Composable
private fun AccentStyleDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 220.dp, max = 280.dp)
                    .padding(vertical = 12.dp, horizontal = 8.dp),
            ) {
                MoriConfig.ACCENT_STYLES.forEach { name ->
                    val selected = name == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                }
                            )
                            .moriClickable { onPick(name) }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Spacer(Modifier.width(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}
