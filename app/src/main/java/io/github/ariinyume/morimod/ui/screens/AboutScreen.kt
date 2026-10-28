package io.github.ariinyume.morimod.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.ariinyume.morimod.ui.MoriViewModel
import io.github.ariinyume.morimod.ui.S
import io.github.ariinyume.morimod.ui.components.BtnSize
import io.github.ariinyume.morimod.ui.components.BtnTone
import io.github.ariinyume.morimod.ui.components.CardTone
import io.github.ariinyume.morimod.ui.components.ConnectedButtonGroup
import io.github.ariinyume.morimod.ui.components.InfoRow
import io.github.ariinyume.morimod.ui.components.MoriCard
import io.github.ariinyume.morimod.ui.components.MoriTopBar
import io.github.ariinyume.morimod.ui.components.Space

/**
 * 关于页（v0.1.22 按用户给的目录重做）。
 *
 * <h3>版式</h3>
 * 用户第 7 条直接给了四张卡：<b>为什么做 MoriMod / 内置字体授权 / 免责声明 / 项目地址</b>，
 * 并且明确要求「把第一个信息卡片删除」—— 原来那张 logo + 版本 / 宿主 / 运行状态 / 许可证
 * 的信息卡整张都不再出现（那些数据仍可通过下方的「复制信息」拿到）。
 *
 * <p>免责声明是法律文本，<b>逐条照抄用户给的内容</b>，不做任何改写或缩写。
 */
@Composable
internal fun AboutScreen(
    vm: MoriViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current

    Column(modifier = modifier.fillMaxSize()) {
        // ★ v0.1.23 用户第 6 条：标题与卡片的间距跟「主程序背景自定义」页完全一致
        //   （那边是 titleTop / titleBottom = 30dp、padV = 0 —— 见 BackgroundScreen）。
        MoriTopBar(
            title = S.ABOUT_TITLE,
            onBack = onBack,
            titleTop = 30.dp,
            titleBottom = 30.dp,
            padV = 0.dp,
        )

        // ⚠️ 必须 weight(1f)，不能用 fillMaxSize()：Column 里 fillMaxSize 会让列表
        //    比可视区高出「顶栏那一段」，最后一行永远滚不进可视区（也点不到）。
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.CardGap),
        ) {
            // ── 卡一：为什么做 MoriMod ────────────────────────────────
            item {
                MoriCard(
                    modifier = Modifier.padding(horizontal = Space.ScreenH),
                    tone = CardTone.OUTLINED,
                ) {
                    Text(
                        text = S.ABOUT_WHY_TITLE,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(Space.ItemGap))
                    Body(S.ABOUT_WHY_BODY)
                }
            }

            // ── 卡二：内置字体授权（SIL OFL 1.1 要求对用户可见）───────
            item {
                var showOfl by remember { mutableStateOf(false) }
                MoriCard(
                    modifier = Modifier.padding(horizontal = Space.ScreenH),
                    tone = CardTone.OUTLINED,
                ) {
                    Text(
                        text = S.ABOUT_FONTS,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(Space.ItemGap))
                    BundledFontLicense.entries.forEach { e ->
                        InfoRow(label = e.label, value = e.copyright)
                        InfoRow(label = "License", value = e.license)
                        InfoRow(label = "Source", value = e.source)
                    }
                    // ★ v0.1.23 用户第 6 条：这段「以上两款以 SIL OFL 1.1 随包分发…」小字已删。
                    Spacer(Modifier.height(Space.CardGap))
                    ConnectedButtonGroup(
                        labels = listOf(S.BTN_VIEW_OFL),
                        onSelect = { showOfl = true },
                        size = BtnSize.XS,
                        // 用户第 6 条：关于页所有按钮都用强调色（primary）。
                        tone = BtnTone.FILLED,
                        stretchLast = true,
                    )
                }
                if (showOfl) {
                    OflDialog(onDismiss = { showOfl = false })
                }
            }

            // ── 卡三：免责声明 ───────────────────────────────────────
            item {
                MoriCard(
                    modifier = Modifier.padding(horizontal = Space.ScreenH),
                    tone = CardTone.OUTLINED,
                ) {
                    Text(
                        text = S.ABOUT_DISCLAIMER_TITLE,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(Space.ItemGap))
                    Body(S.ABOUT_DISCLAIMER_BODY)
                }
            }

            // ── 卡四：项目地址 ───────────────────────────────────────
            item {
                MoriCard(
                    modifier = Modifier.padding(horizontal = Space.ScreenH),
                    tone = CardTone.OUTLINED,
                ) {
                    Text(
                        text = S.ABOUT_REPO_TITLE,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(Space.ItemGap))
                    Text(
                        text = REPO_URL,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(Space.CardGap))
                    // ★ v0.1.23 用户第 6 条：「复制版本信息」按钮已删 ——
                    //   改成在主页**长按状态卡**复制（见 HomeScreen#StatusCard）。
                    ConnectedButtonGroup(
                        labels = listOf(S.BTN_OPEN_REPO, S.BTN_COPY_LINK),
                        onSelect = { i ->
                            if (i == 0) openUrl(ctx, REPO_URL) else copyText(ctx, REPO_URL, vm)
                        },
                        size = BtnSize.XS,
                        tone = BtnTone.FILLED,
                        stretchAll = true,
                    )
                }
            }
        }
    }
}

/**
 * 正文段落：bodyMedium + onSurfaceVariant。
 *
 * <p>★ v0.1.25 用户第 5 条（这次是**两个**参数，别再混成一句）：
 * <ul>
 *   <li><b>行间距</b>（同一段内相邻两行）= 字号 × {@code 1.3}；</li>
 *   <li><b>段间距</b>（两个自然段之间）= 字号 × {@code 1.5}。</li>
 * </ul>
 *
 * <h3>为什么以前「只改了行间距没改段间距」</h3>
 * 上一版把整段正文当成<b>一个</b> {@code Text}，里面用 {@code \n\n} 分段 ——
 * 但 Compose 的 {@code lineHeight} 是<b>每一行</b>的行高，空行也只是一行，
 * 于是「段间距」永远等于「一个行高」，改 {@code lineHeightRatio} 两个一起动，
 * 想只调段间距根本无从下手。现在按 {@code \n\n} 真拆成多段，各自一个 {@code Text}，
 * 段与段之间插一个精确的 {@code Spacer} ⇒ 行距、段距彻底解耦。
 */
@Composable
private fun Body(
    text: String,
    lineHeightRatio: Float = 1.3f,
    paraGapRatio: Float = 1.5f,
) {
    val base = MaterialTheme.typography.bodyMedium
    // 段间距按「字号 × 1.5」换算成 dp（Compose 的 Spacer 只吃 Dp，TextUnit 要过一层密度）
    val paraGap = with(LocalDensity.current) { (base.fontSize * paraGapRatio).toDp() }
    val paras = remember(text) {
        text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    Column {
        paras.forEachIndexed { i, p ->
            if (i > 0) Spacer(Modifier.height(paraGap))
            Text(
                text = p,
                style = base.copy(lineHeight = base.fontSize * lineHeightRatio),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 项目地址（用户给定，一字不改）。 */
private const val REPO_URL = "https://github.com/ariinyume/mori-mod"

/**
 * 内置字体的版权信息。
 *
 * <p>数值直接取自两份字体文件自身的 name 表（copyright / license 字段），
 * 不是第三方站点转述 —— 这样「关于」页展示的和字体文件里写的必然一致。
 */
private data class FontLicense(
    val label: String,
    val copyright: String,
    val license: String,
    val source: String,
)

private object BundledFontLicense {
    val entries = listOf(
        FontLicense(
            label = "思源黑体 SC Regular",
            copyright = "© 2014-2021 Adobe, Reserved Font Name 'Source'",
            license = "SIL OFL 1.1",
            source = "github.com/adobe-fonts/source-han-sans",
        ),
        FontLicense(
            label = "得意黑 Smiley Sans Oblique",
            copyright = "© 2022–2024 atelierAnchor",
            license = "SIL OFL 1.1",
            source = "github.com/atelier-anchor/smiley-sans",
        ),
    )
}

/** OFL 1.1 全文对话框（内容来自 APK 内的 assets/fonts/OFL-1.1.txt）。 */
@Composable
private fun OflDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember {
        try {
            ctx.assets.open("fonts/OFL-1.1.txt").use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            "assets/fonts/OFL-1.1.txt 读取失败：$t"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("SIL Open Font License 1.1") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                item {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(S.BTN_CANCEL) }
        },
    )
}

/** 复制任意文本 + 统一的「已复制」提示。 */
private fun copyText(ctx: Context, text: String, vm: MoriViewModel) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MoriMod", text))
        vm.notify(S.TOAST_COPIED)
    } catch (t: Throwable) {
        // 剪贴板不可用不是致命错误，静默即可
    }
}

/** 用系统浏览器打开链接（没有浏览器 / 被拦都不崩）。 */
private fun openUrl(ctx: Context, url: String) {
    try {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (t: Throwable) {
        // 打不开就什么都不做：链接本身在页面上是可读、可复制的
    }
}
