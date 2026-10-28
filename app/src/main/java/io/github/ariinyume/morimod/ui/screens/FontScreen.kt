package io.github.ariinyume.morimod.ui.screens

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ariinyume.morimod.hook.ModuleAssets
import io.github.ariinyume.morimod.ui.FileStore
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
 * 主程序字体替换（全局强制替换，PRD D-10；不含字号缩放，D-18）。
 *
 * <h3>版式（v0.1.9 按设计稿微调）</h3>
 * 三张填充卡片，自上而下：
 * <ol>
 *   <li><b>启用字体替换</b>：标题 + 开关同一行（垂直居中）→ 当前字体 → 按钮组
 *       （使用系统内置字体 / 恢复默认）→「字体预览」+ 七行样张。</li>
 *   <li><b>用户导入字体</b>：标题 + 12sp 说明 → 导入按钮（XS 32dp）→ 单选列表
 *       （名字 + 「7 MB · OTF」这样的 12sp 副标题，长按删除）。</li>
 *   <li><b>插件内置字体</b>：两款 OFL 字体。</li>
 * </ol>
 *
 * <p>v0.1.9 的三处留白/位置调整（用户逐条给的值）：
 * 标题与返回按钮、与首张卡片各 **30dp**；三张卡之间 **20dp**；
 * 原来挤在第三张卡里的三行说明改成**卡外两段**（全部靠左），不再居中。
 *
 * <p>⚠️ 卡片之间 20dp、行间 14dp —— 提示词明确要求「不要太挤」。
 *   全局 [Space.CardGap] 是 12dp，本页单独放大，避免动到别的页面。
 *
 * <p>字体来源三选一（D-11）：本地字体文件 / 系统已装字体 / 随包内置字体。
 * 文件与内置优先于族名。内置两款均为 SIL OFL 1.1（可商用、可再分发）。
 */
@Composable
internal fun FontScreen(
    vm: MoriViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cfg = vm.config
    var showFamilyPicker by remember { mutableStateOf(false) }
    // 长按「用户导入字体」里的一项 → 待删除的路径（null = 没有待确认的删除）
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current

    // 系统字体（真实字体文件 + 通用族名；一次性读取，够用；不做全文模糊搜索）
    val systemFonts = remember { listSystemFonts() }

    /**
     * 一次性提示。
     *
     * ⚠️ v0.1.5：这里**必须**用原生 Toast，不能只靠 `vm.toast`。
     * 本页跑在 `PageActivity` 里，而 SnackbarHost 只挂在 `SettingsActivity` 的
     * `MoriApp` 上 —— 跨 Activity 的 toast 根本没人渲染，所以 v0.1.4 的
     * 「非受支持的文件」提示一次都没出现过。Toast 由系统窗口管理器负责，
     * 跟宿主页面结构无关，一定能看见。
     */
    fun say(msg: String) {
        try {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            // 极端情况（通知被禁）静默
        }
    }

    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            vm.reportPickCanceled()
            return@rememberLauncherForActivityResult
        }
        // ★ v0.1.4：只接受 .ttf / .otf（BR-4.x）。
        //   为什么不在契约里用 MIME 白名单：各家文件管理器给的 MIME 五花八门
        //   （application/octet-stream、application/x-font、空值…），
        //   按 MIME 过滤会让用户的合法 ttf 变成「选不到」——比拒绝更糟。
        //   所以放开选择、**选完立刻判**，不符合就明确告诉他「非受支持的文件」。
        if (FileStore.fontExt(ctx, uri) == null) {
            say(S.TOAST_FONT_UNSUPPORTED)
            return@rememberLauncherForActivityResult
        }
        val path = vm.importUri(uri, FileStore.KIND_FONT)
        if (path == null) say(S.TOAST_PICK_FAILED) else vm.setF4File(path)
    }

    val currentName = when {
        // 随包内置字体：显示人话名字，别把 asset:fonts/xxx.otf 甩给用户
        cfg.f4FontFile.startsWith(ModuleAssets.ASSET_PREFIX) -> {
            val asset = cfg.f4FontFile.removePrefix(ModuleAssets.ASSET_PREFIX)
            BUNDLED_FONTS.firstOrNull { it.asset == asset }?.label ?: asset.substringAfterLast('/')
        }
        cfg.f4FontFile.isNotEmpty() -> FileStore.tidyName(cfg.f4FontFile.substringAfterLast('/'))
        cfg.f4FontFamily.isNotEmpty() -> cfg.f4FontFamily
        else -> null
    }

    // 本页统一排版数值（提示词：标题上下 30dp、卡片间距 20dp、行距不挤）
    val cardGap = 20.dp
    val rowGap = 14.dp
    val titleGap = 30.dp

    Column(modifier = modifier.fillMaxSize()) {
        // ★ v0.1.9：标题与返回按钮、与下方首张卡片之间都是 30dp（设计稿明确值）。
        MoriTopBar(
            title = S.F4_TITLE,
            onBack = onBack,
            titleTop = titleGap,
            titleBottom = titleGap,
            padV = 0.dp,            // 顶栏自带的 4dp 会把 30dp 顶成 34dp
        )

        // ⚠️ 必须 weight(1f)，不能用 fillMaxSize()：Column 里 fillMaxSize 会让列表
        //    比可视区高出「顶栏那一段」，最后一行永远滚不进可视区（也点不到）。
        LazyColumn(
            modifier = Modifier.weight(1f),
            // 顶栏已经留了 30dp，这里不能再加，否则「标题→卡片」就变成 34dp 了
            contentPadding = PaddingValues(top = 0.dp, bottom = 40.dp),
        ) {
            // ── ① 启用替换 / 当前字体 / 预览 ───────────────────────────
            item {
                val cfg = vm.config      // 见 HomeScreen 顶部注释：必须在 item 作用域里读
                MoriCard(
                    modifier = Modifier.padding(horizontal = Space.ScreenH),
                    tone = CardTone.FILLED,
                ) {
                    // 标题与开关**同一行**、垂直居中（提示词明确要求，不要竖着堆叠）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = S.F4_CARD,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        MoriSwitch(
                            checked = cfg.f4Enabled,
                            onCheckedChange = { vm.setF4Enabled(it) },
                        )
                    }

                    Spacer(Modifier.height(rowGap))
                    Text(
                        text = "${S.F4_CURRENT}：${currentName ?: S.F4_NONE}",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(Modifier.height(rowGap))
                    ConnectedButtonGroup(
                        labels = listOf(S.BTN_USE_SYSTEM_FONT, S.BTN_RESTORE),
                        onSelect = { i ->
                            when (i) {
                                0 -> showFamilyPicker = true
                                else -> vm.restoreFontDefault()
                            }
                        },
                        size = BtnSize.XS,
                        tone = BtnTone.FILLED,
                        stretchAll = true,
                    )

                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = S.F4_PREVIEW,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    // 预览**常驻**：没选字体时用系统默认字体渲染，用户也能看到
                    // 「换成这一款会长什么样」的基准线。
                    FontPreview(fontFile = cfg.f4FontFile, family = cfg.f4FontFamily)
                }
            }

            // ── ② 用户导入字体 ────────────────────────────────────────
            item {
                val cfg = vm.config
                val mine = cfg.f4FontLib.filter { java.io.File(it).isFile }
                MoriCard(
                    modifier = Modifier.padding(
                        start = Space.ScreenH,
                        end = Space.ScreenH,
                        top = cardGap,
                    ),
                    tone = CardTone.FILLED,
                ) {
                    Text(
                        text = S.F4_MY_FONTS,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = S.F4_MY_FONTS_NOTE,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(rowGap))
                    ConnectedButtonGroup(
                        labels = listOf(S.BTN_PICK_FONT),
                        onSelect = { fontPicker.launch(FONT_MIME_TYPES) },
                        size = BtnSize.XS,
                        tone = BtnTone.FILLED,
                        stretchAll = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (mine.isEmpty()) {
                        Text(
                            text = S.F4_MY_FONTS_EMPTY,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            mine.forEach { path ->
                                MyFontRow(
                                    path = path,
                                    selected = cfg.f4FontFile == path,
                                    onClick = { vm.setF4Choice(filePath = path, family = "") },
                                    onLongClick = { pendingDelete = path },
                                )
                            }
                        }
                    }
                }
            }

            // ── ③ 插件内置字体（随包附带，SIL OFL 1.1）─────────────────
            item {
                val cfg = vm.config
                MoriCard(
                    modifier = Modifier.padding(
                        start = Space.ScreenH,
                        end = Space.ScreenH,
                        top = cardGap,
                    ),
                    tone = CardTone.FILLED,
                ) {
                    Text(
                        text = S.F4_BUNDLED,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        BUNDLED_FONTS.forEach { f ->
                            BundledFontRow(
                                font = f,
                                selected = cfg.f4FontFile == ModuleAssets.ASSET_PREFIX + f.asset,
                                onClick = {
                                    vm.setF4Choice(
                                        filePath = ModuleAssets.ASSET_PREFIX + f.asset,
                                        family = "",
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // ── ③-备注 卡外说明（v0.1.9：按设计稿挪到卡片外面，两段、全部靠左）──
            item {
                FontNote(S.F4_NOTE_NOTO, top = cardGap)
                FontNote(S.F4_RISK_NOTE, top = 8.dp)
            }

            item {
                val cfg = vm.config    // 每个 item 各自读一次，才有独立的重组作用域
                // 选了 sans-serif = 宿主默认 ⇒ 提醒一句，避免用户以为「功能坏了」
                if (cfg.f4FontFamily == "sans-serif" && cfg.f4FontFile.isEmpty()) {
                    Text(
                        text = S.F4_NOOP,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(
                            start = Space.ScreenH,
                            end = Space.ScreenH,
                            top = cardGap,
                        ),
                    )
                }
            }
        }
    }

    if (showFamilyPicker) {
        SystemFontDialog(
            fonts = systemFonts,
            currentFamily = cfg.f4FontFamily,
            currentFile = cfg.f4FontFile,
            onPick = { choice ->
                vm.setF4Choice(filePath = choice.file, family = choice.family)
                showFamilyPicker = false
            },
            onDismiss = { showFamilyPicker = false },
        )
    }

    // 长按删除的二次确认：删的是模块目录里的副本，但仍然是不可撤销的，必须确认
    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(S.F4_DELETE_TITLE) },
            text = {
                val fn = toDelete.substringAfterLast('/')
                Text("${fn.substringBeforeLast('.', fn)}\n\n${S.F4_DELETE_BODY}")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeFontLib(toDelete)
                    pendingDelete = null
                }) { Text(S.BTN_DELETE) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(S.BTN_CANCEL) }
            },
        )
    }
}

/**
 * 卡片外侧的 12sp 说明（v0.1.9：按设计稿从卡片里挪出来，两段、全部靠左）。
 *
 * <p>原先是三行挤在「插件内置字体」卡内部，最后一行居中；设计稿里这三句其实在
 * 卡片**外面**、合成两段。现在只由调用点决定段间距（[top]），对齐固定靠左。
 */
@Composable
private fun FontNote(text: String, top: Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Space.ScreenH, end = Space.ScreenH, top = top),
    )
}

/**
 * 随包内置的字体（{@code assets/fonts/} 目录下）。
 *
 * <p>只放 **SIL OFL 1.1** 的两款：OFL 第 2 条明文允许「与任何软件捆绑再分发」，
 * 只要随包附带版权声明 + 许可证（本 APK 的 {@code assets/fonts/NOTICE.txt} 与
 * {@code assets/fonts/OFL-1.1.txt} 就是这两样，「关于」页也能看）。
 *
 * <p>⚠️ **钉钉进步体 / 阿里巴巴普惠体刻意没有内置**：二者虽「永久免费商用」，
 * 但授权条款只给「使用」，明确不含再分发字体文件（钉钉甚至直接写明禁止
 * 「上传、发布、转载钉钉进步体文件」）。用户要用手动选文件即可。
 */
private data class BundledFont(
    val label: String,
    val asset: String,
    val license: String,
    val source: String,
)

private val BUNDLED_FONTS = listOf(
    BundledFont(
        label = "思源黑体 SC Regular",
        asset = "fonts/source_han_sans_sc.otf",
        license = "SIL OFL 1.1",
        source = "Adobe",
    ),
    BundledFont(
        label = "得意黑 Smiley Sans Oblique",
        asset = "fonts/smiley_sans.otf",
        license = "SIL OFL 1.1",
        source = "atelierAnchor",
    ),
)

/**
 * 「用户导入字体」的一行：**点选复用 / 长按删除**。
 *
 * <p>点击用 `Modifier.clickable` / `combinedClickable` 的**简单重载** —— 记忆里踩过一次坑：
 * 自绘点击（自定义 interactionSource + indication + graphicsLayer 缩放）会让整行点不动。
 *
 * <p>副标题显示文件大小 + 扩展名（设计稿：`7MB · OTF`），用来区分同名不同版本的字体
 * （用户经常反复导入同一家族）。
 */
@Composable
private fun MyFontRow(
    path: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // ★ v0.1.5：显示**原始文件名**（导入时不再改名，见 FileStore.importUri），
    //   去掉扩展名只是为了好看：`思源黑体` / `AlibabaPuHuiTi-3-55-Regular`。
    //   ★ v0.1.6：旧版本导出的名字是 `xxx..ttf`，`substringBeforeLast` 会留下
    //   末尾那个点（用户看到的「每一项后面有个点」就是它）。这里先收点再 trim。
    val fileName = FileStore.tidyName(path.substringAfterLast('/'))
    val name = fileName.substringBeforeLast('.', fileName).trimEnd('.')
    val meta = remember(path) {
        val f = java.io.File(path)
        val kb = (f.length() + 512) / 1024
        val size = if (kb >= 1024) "${kb / 1024}MB" else "$kb KB"
        "$size · ${fileName.substringAfterLast('.').uppercase()}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 内置字体的一行（选中即写入 {@code asset:fonts/xxx} 并启用替换）。 */
@Composable
private fun BundledFontRow(font: BundledFont, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = font.label,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${font.license} · ${font.source}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 字体文件选择器的 MIME 类型：ttf / otf。
 *
 * <p>末尾那条通配 MIME 不能删：不少文件管理器/网盘把字体报成
 * `application/octet-stream` 或干脆不给 MIME，只按 MIME 过滤会让用户**选不到自己的字体**。
 * 真正的类型校验放在选完之后（[FileStore.fontExt]），不合法就提示「非受支持的文件」。
 */
private val FONT_MIME_TYPES = arrayOf(
    "font/ttf",
    "font/otf",
    "application/x-font-ttf",
    "application/x-font-opentype",
    "*/*",
)

/**
 * 读系统字体列表。
 *
 * <h3>为什么不能只列那几个通用族名（v0.0.9 根因之一）</h3>
 * 旧实现是「没有 {@code android.hardware.fonts} 特性就只给 {@code sans-serif}」。
 * 真机（OnePlus / Android 17）恰好没有这个特性 ⇒ 用户唯一能选的就是
 * {@code sans-serif} —— 而它就是 Android 的**默认族**，替换完等于什么都没变，
 * 于是「字体不生效」。日志实锤：{@code F4=true(fam=sans-serif)}。
 *
 * <p>现在改成扫描 {@code /system/fonts} 下的真实字体文件（world-readable，
 * 宿主进程同样读得到），再加上通用族名兜底。选文件走 {@code f4FontFile}，
 * 选族名走 {@code f4FontFamily}，二者互斥。
 *
 * <p>⚠️ 不用 {@code Typeface.systemAvailableFamilies}：它是 @hide 隐藏 API，
 * 编译期直接 Unresolved reference，反射调用会被隐藏 API 限制拦。
 */
private fun listSystemFonts(): List<FontChoice> {
    val out = ArrayList<FontChoice>()
    // ① 真实字体文件（这些才有肉眼可见的差别）
    val dir = java.io.File("/system/fonts")
    val files = dir.listFiles()
    if (files != null) {
        files.asSequence()
            .filter { it.isFile && it.canRead() }
            .filter { isFontFileName(it.name) }
            .sortedBy { it.name.lowercase() }
            // 去重：同名不同后缀不太可能出现，但 ttc 里可能有多份
            .distinctBy { it.name.lowercase() }
            .forEach { out.add(FontChoice(label = it.name, family = "", file = it.absolutePath)) }
    }
    // ② 通用族名（老机型 / 文件扫描为空时的兜底；sans-serif 去掉，它是默认值）
    for (f in GENERIC_FAMILIES) {
        out.add(FontChoice(label = "（通用）$f", family = f, file = ""))
    }
    return out
}

/** 只认字体后缀（避免把 fonts.xml 之类的配置当字体）。 */
private fun isFontFileName(name: String): Boolean {
    val n = name.lowercase()
    return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc")
}

/** 通用族名兜底。刻意**不含 sans-serif**：那就是宿主默认，选了不会有任何变化。 */
private val GENERIC_FAMILIES = listOf(
    "serif",
    "monospace",
    "serif-monospace",
    "sans-serif-condensed",
    "sans-serif-light",
    "sans-serif-medium",
    "sans-serif-black",
    "casual",
    "cursive",
)

/** 一项字体选择：文件优先，其次族名（与能力层的优先级一致）。 */
private data class FontChoice(val label: String, val family: String, val file: String)

/** 系统字体选择对话框（单选列表）。 */
@Composable
private fun SystemFontDialog(
    fonts: List<FontChoice>,
    currentFamily: String,
    currentFile: String,
    onPick: (FontChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(S.F4_SYSTEM_FONTS) },
        text = {
            if (fonts.isEmpty()) {
                Text(S.F4_NONE, style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(fonts) { f ->
                        val selected = if (f.file.isNotEmpty()) {
                            f.file == currentFile
                        } else {
                            currentFile.isEmpty() && f.family == currentFamily
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(f) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = { onPick(f) })
                            Spacer(Modifier.size(Space.ItemGap))
                            Text(
                                text = f.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S.BTN_CANCEL) }
        },
    )
}

/**
 * 预览样张（设计稿逐行给的七行，16sp）。
 *
 * 简 / 繁 / 韩 / 日 / 大写 / 小写 / 数字与符号 —— 中英日韩混排 + 标点，
 * 一款字体到底缺不缺字形，看这一块就够了（缺字会变成方块，一眼可见）。
 */
private val PREVIEW_LINES = listOf(
    "这是一段预览文本",
    "這是一段預覽文本",
    "이것은 미리보기 텍스트야",
    "これはプレビュー用のテキストです",
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
    "abcdefghijklmnopqrstuvwxyz",
    "0123456789  !@@#$ %^&* ()_+-=[]{}|;:,.<>?",
)

/**
 * 用**当前选中的那份字体**渲染样张。
 *
 * 用原生 `TextView` 而不是 Compose `Text`：Compose 的 FontFamily 只认随包资源 /
 * GoogleFont，要把「用户手机上的那个 ttf 文件」塞进去还得额外做一层，`setTypeface` 最直接。
 *
 * <p>⚠️ v0.2.0：预览**常驻显示**。以前"没选字体就整块不出现"，用户会觉得
 * 「预览这一块不见了」；现在没选字体就按系统默认字体渲染，作为对照基准。
 */
@Composable
private fun FontPreview(fontFile: String, family: String) {
    val ctx = LocalContext.current
    val typeface = remember(fontFile, family) {
        loadPreviewTypeface(ctx, fontFile, family)
    }
    // ⚠️ 颜色必须在组合作用域里取好：update{} 不是 @Composable，里面读 MaterialTheme 编译不过
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val lineGap = (6 * (ctx.resources.displayMetrics.density)).toInt()

    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { c ->
            TextView(c).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, 0, 0, 0)
                setLineSpacing(lineGap.toFloat(), 1f)
                includeFontPadding = false
                maxLines = PREVIEW_LINES.size
            }
        },
        update = { tv ->
            tv.typeface = typeface      // null = 系统默认（正好是"未设置素材"时的对照）
            tv.text = PREVIEW_LINES.joinToString("\n")
            tv.setTextColor(textColor)
        },
    )
}

/**
 * 解析预览用 Typeface：内置 asset 优先，其次文件路径，最后系统族名
 * （三种来源的优先级与能力层 {@code FeatureF4.resolveBase} 保持一致，
 *  否则预览和实际效果会对不上）。解析不出来就返回 null = 系统默认字体。
 */
private fun loadPreviewTypeface(ctx: Context, fontFile: String, family: String): Typeface? = try {
    when {
        fontFile.startsWith(ModuleAssets.ASSET_PREFIX) -> Typeface.createFromAsset(
            ctx.assets,
            fontFile.removePrefix(ModuleAssets.ASSET_PREFIX),
        )
        fontFile.isNotEmpty() -> Typeface.createFromFile(java.io.File(fontFile))
        family.isNotEmpty() -> Typeface.create(family, Typeface.NORMAL)
        else -> null
    }
} catch (t: Throwable) {
    null
}
