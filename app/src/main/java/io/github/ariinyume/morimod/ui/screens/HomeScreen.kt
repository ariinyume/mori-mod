package io.github.ariinyume.morimod.ui.screens

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ariinyume.morimod.ui.Dest
import io.github.ariinyume.morimod.ui.Lang
import io.github.ariinyume.morimod.ui.MoriViewModel
import io.github.ariinyume.morimod.ui.S
import io.github.ariinyume.morimod.ui.components.BtnSize
import io.github.ariinyume.morimod.ui.components.BtnTone
import io.github.ariinyume.morimod.ui.components.CardTone
import io.github.ariinyume.morimod.ui.components.ConnectedButtonGroup
import io.github.ariinyume.morimod.ui.components.ConnectedItems
import io.github.ariinyume.morimod.ui.components.MoriCard
import io.github.ariinyume.morimod.ui.components.MoriConfirmDialog
import io.github.ariinyume.morimod.ui.components.MoriListItem
import io.github.ariinyume.morimod.ui.components.MoriSwitch
import io.github.ariinyume.morimod.ui.components.SectionHeader
import io.github.ariinyume.morimod.ui.components.Space
import io.github.ariinyume.morimod.ui.components.moriCombinedClickable
import io.github.ariinyume.morimod.ui.components.connectedItemShape
import io.github.ariinyume.morimod.ui.theme.ScreenTitleStyle
import io.github.ariinyume.morimod.util.BuildInfo

/**
 * 主页。
 *
 * 结构：可滚动内容（标题行 + 副标题 → 状态卡 → 「功能设置」5 项 → 「模块管理」4 项）。
 * 标题行**不吸顶**：它是列表的第一项，跟着一起滚走。
 *
 * ⚠️ 每个 `item {}` 内部**各自读一次 `vm.config` / `vm.hostScopeAlive`**：
 *    LazyColumn 的每一项是独立的组合作用域，只有在该作用域里读到的快照状态变化时
 *    才会重组。放在函数顶部读一次的话，项内容只会跟着父作用域走 —— 真机表现就是
 *    「开关点下去界面不动」（v0.0.4 的 bug）。配合 ViewModel 的「落盘即换新对象」
 *    （`config = config.copy()`），这一读就能真正生效。
 */
@Composable
internal fun HomeScreen(
    vm: MoriViewModel,
    onNavigate: (Dest) -> Unit,
    onExportFromHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var confirmHideIcon by remember { mutableStateOf(false) }
    // 宿主包版本：一次查询就够（重装宿主需要重开设置页）
    val host = remember(ctx) { readHostInfo(ctx) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // ── 标题行：粗体标题（左） + 小胶囊语言按钮（**靠右**）──
        item {
            val cfg = vm.config
            HomeHeader(
                langLabel = Lang.of(cfg.lang).label,
                onLanguage = { vm.cycleLanguage() },
            )
        }

        item {
            Text(
                text = S.HOME_SUBTITLE,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = Space.ScreenH,
                    end = Space.ScreenH,
                    bottom = Space.CardGap,
                ),
            )
        }

        // ── 状态卡（真实数据：插件版本 / DLsiteSound 版本 / 运行状况）──
        item {
            StatusCard(
                plugin = "${BuildInfo.versionName()} (code ${BuildInfo.versionCode()})",
                host = host.version,
                state = when {
                    !host.installed -> HostState.MISSING
                    vm.hostScopeAlive == true -> HostState.ACTIVE
                    vm.hostScopeAlive == false -> HostState.INACTIVE
                    else -> HostState.CHECKING
                },
                modifier = Modifier.padding(horizontal = Space.ScreenH),
                // ★ v0.1.23 用户第 6 条：长按状态卡 = 复制版本信息（取代关于页那颗按钮）
                onLongPress = { vm.copyVersionInfo() },
            )
        }

        // ── 功能设置 ──
        // ⚠️「系统播放控件自定义」入口已移除：宿主最新版自带该功能，本模块这一项**搁置**
        //    （设置页没有入口，clamp() 里也强制 f1Enabled=false，能力层不会去动宿主通知栏）。
        item { SectionHeader(S.SEC_FEATURES) }
        item {
            ConnectedItems(
                count = 4,
                modifier = Modifier.padding(horizontal = Space.ScreenH),
            ) { i ->
                val shape = connectedItemShape(i, 4)
                when (i) {
                    0 -> MoriListItem(
                        icon = Icons.Rounded.Image,
                        title = S.ITEM_BACKGROUND,
                        shape = shape,
                        onClick = { onNavigate(Dest.BACKGROUND) },
                        showChevron = true,
                    )
                    1 -> MoriListItem(
                        icon = Icons.Rounded.EditNote,
                        title = S.ITEM_FONT,
                        shape = shape,
                        onClick = { onNavigate(Dest.FONT) },
                        showChevron = true,
                    )
                    2 -> MoriListItem(
                        icon = Icons.Rounded.Star,
                        title = S.ITEM_GLASS,
                        shape = shape,
                        // 未开发：如实反馈，不静默什么都不做
                        onClick = { vm.notify(S.TOAST_RESERVED) },
                        trailing = { WipTag() },
                    )
                    else -> MoriListItem(
                        icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                        title = S.ITEM_LAYOUT,
                        shape = shape,
                        onClick = { vm.notify(S.TOAST_RESERVED) },
                        trailing = { WipTag() },
                    )
                }
            }
        }

        // ── 模块管理 ──
        item { SectionHeader(S.SEC_MANAGE) }
        item {
            ConnectedItems(
                count = 4,
                modifier = Modifier.padding(horizontal = Space.ScreenH),
            ) { i ->
                val cfg = vm.config
                val shape = connectedItemShape(i, 4)
                when (i) {
                    0 -> MoriListItem(
                        icon = Icons.Rounded.DoNotDisturbOn,
                        title = S.ITEM_HIDE_ICON,
                        shape = shape,
                        onClick = {
                            // 打开要确认（有副作用），关掉直接生效
                            if (cfg.hideLauncherIcon) {
                                vm.setHideLauncherIcon(false)
                            } else {
                                confirmHideIcon = true
                            }
                        },
                        trailing = {
                            MoriSwitch(
                                checked = cfg.hideLauncherIcon,
                                onCheckedChange = { want ->
                                    if (want) confirmHideIcon = true
                                    else vm.setHideLauncherIcon(false)
                                },
                            )
                        },
                    )
                    1 -> MoriListItem(
                        icon = Icons.Rounded.Construction,
                        title = S.ITEM_DEBUG_LOG,
                        summary = S.ITEM_DEBUG_LOG_SUM,
                        shape = shape,
                        // 只改开关，不弹提示（用户明确要求）
                        onClick = { vm.setDebugLog(!cfg.debugLog) },
                        trailing = {
                            MoriSwitch(
                                checked = cfg.debugLog,
                                onCheckedChange = { vm.setDebugLog(it) },
                            )
                        },
                    )
                    2 -> BackupListItem(
                        shape = shape,
                        onExport = onExportFromHome,
                        onImport = { vm.importFromClipboard() },
                    )
                    else -> MoriListItem(
                        icon = Icons.Rounded.Error,
                        title = S.ITEM_ABOUT,
                        shape = shape,
                        onClick = { onNavigate(Dest.ABOUT) },
                        showChevron = true,
                    )
                }
            }
        }
    }

    if (confirmHideIcon) {
        MoriConfirmDialog(
            title = S.DLG_HIDE_ICON_TITLE,
            text = S.DLG_HIDE_ICON_TEXT,
            confirmText = S.BTN_OK,
            onConfirm = {
                vm.setHideLauncherIcon(true)
                confirmHideIcon = false
            },
            onDismiss = { confirmHideIcon = false },
        )
    }
}

/**
 * 主页标题行。
 *
 * `Mori MOD`（28sp 粗体）钉在左边，语言按钮是**靠右的小胶囊**（XS = 32dp 高），
 * 中间用 `weight(1f)` 的 Spacer 顶开 —— 既不是拉伸按钮，也不是居中。
 */
@Composable
private fun HomeHeader(langLabel: String, onLanguage: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Space.ScreenH,
                end = Space.ScreenH,
                top = 20.dp,
                bottom = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = S.APP_TITLE,
            style = ScreenTitleStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        FilledTonalButton(
            onClick = onLanguage,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.height(BtnSize.XS.height),
            contentPadding = PaddingValues(horizontal = BtnSize.XS.padH),
        ) {
            Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = null,
                modifier = Modifier.size(BtnSize.XS.icon),
            )
            Spacer(Modifier.width(BtnSize.XS.gap))
            Text(
                text = langLabel,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

/** 状态卡左侧那颗小图标的语义。 */
private enum class HostState { CHECKING, ACTIVE, INACTIVE, MISSING }

/**
 * 状态卡。
 *
 * 不出现「状态卡」三个字；左侧只放一颗**小图标**指示插件运行状态
 * （√ 已注入 / × 未运行、未安装 / ? 检测中）。应用 logo 不在这里 ——
 * 它在桌面图标、LSPosed 模块列表（自适应图标）与「关于」页。
 */
@Composable
private fun StatusCard(
    plugin: String,
    host: String,
    state: HostState,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    val runtime = when (state) {
        HostState.CHECKING -> S.STATUS_CHECKING
        HostState.ACTIVE -> S.STATUS_ACTIVE
        HostState.INACTIVE -> S.STATUS_INACTIVE
        HostState.MISSING -> S.STATUS_HOST_MISSING
    }
    val (glyph, tint) = when (state) {
        HostState.ACTIVE -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
        HostState.INACTIVE -> Icons.Rounded.Cancel to MaterialTheme.colorScheme.error
        HostState.MISSING -> Icons.Rounded.Cancel to MaterialTheme.colorScheme.error
        HostState.CHECKING ->
            Icons.AutoMirrored.Rounded.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
    }
    // ★ 长按复制版本信息（v0.1.23 用户第 6 条）。点击不绑定动作 ——
    //   状态卡本身不可点，留给长按，避免误触。
    val longPress = if (onLongPress != null) {
        Modifier.moriCombinedClickable(onLongClick = onLongPress) { }
    } else {
        Modifier
    }
    MoriCard(modifier = modifier.then(longPress), tone = CardTone.FILLED) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = glyph,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(StatusGlyphSize),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${S.STATUS_PLUGIN} $plugin",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${S.STATUS_HOST} $host",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${S.STATUS_RUNTIME} $runtime",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state == HostState.ACTIVE) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 状态小图标的直径（用户要求「只需要小图标」）。 */
private val StatusGlyphSize = 28.dp

/**
 * 「待开发」小标签。
 *
 * ⚠️ v0.0.7 加的：液态玻璃 / 主页布局这两项目前只有一条 Snackbar 反馈，
 *    用户点完看页面没跳转，很容易判断成「点了没反应 / 功能坏了」。
 *    把状态**常驻**在行尾，用户点之前就知道它是待开发，点击后仍给一条 toast 兜底。
 */
@Composable
private fun WipTag() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = S.TAG_WIP,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
        )
    }
}

/**
 * 「导出与备份」行。
 *
 * 两个按钮**直接干活，不弹对话框**（用户明确要求）：
 *  · 导出 = 把配置文本复制到剪贴板；
 *  · 导入 = 读剪贴板里的配置文本并覆盖（与「导出」对称，不再是选文件）。
 * 结果一律用 Snackbar 反馈，所以这一行不再需要小字说明。
 */
@Composable
private fun BackupListItem(
    shape: Shape,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Space.CardPad),
        verticalArrangement = Arrangement.spacedBy(Space.ItemGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Construction,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = S.ITEM_BACKUP,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        ConnectedButtonGroup(
            labels = listOf(S.DLG_BACKUP_EXPORT, S.DLG_BACKUP_IMPORT),
            icons = listOf(Icons.Rounded.ArrowUpward, Icons.Rounded.ArrowDownward),
            onSelect = { if (it == 0) onExport() else onImport() },
            size = BtnSize.XS,
            tone = BtnTone.TONAL,
            stretchAll = true,
        )
    }
}

/** 宿主包是否已安装 + 版本串。 */
private class HostInfo(val version: String, val installed: Boolean)

/**
 * 读宿主已安装版本；未安装则如实显示。
 *
 * ⚠️ Android 11+ 需要 manifest 里的 `<queries>` 声明宿主包，否则这里**永远**是「未安装」。
 */
private fun readHostInfo(ctx: Context): HostInfo {
    return try {
        val info = ctx.packageManager.getPackageInfo(HOST_PKG, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        HostInfo("${info.versionName} (code $code)", true)
    } catch (t: Throwable) {
        HostInfo(S.STATUS_HOST_MISSING, false)
    }
}

/** 宿主包名（与 config 层与 PRD 保持一致）。 */
internal const val HOST_PKG = "jp.co.eisys.dlsitesound"
