package io.github.ariinyume.morimod.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ariinyume.morimod.ui.screens.AboutScreen
import io.github.ariinyume.morimod.ui.screens.BackgroundScreen
import io.github.ariinyume.morimod.ui.screens.FontScreen
import io.github.ariinyume.morimod.ui.screens.MediaControlScreen
import io.github.ariinyume.morimod.ui.theme.MoriModTheme

/**
 * 二级页宿主（v0.0.8 引入）。
 *
 * 为什么不再在 [SettingsActivity] 里用 `when(dest)` 切页面：
 * v0.0.6 / v0.0.7 真机现象是「条目有水波纹、页面纹丝不动」。
 * 水波纹由 `Modifier.clickable` 在**按下**那一刻绘制，能出波纹说明触摸已经进了
 * Compose 树，剩下唯一可疑的环节就是「回调之后的页面切换」—— 那一段完全依赖
 * `mutableStateOf` → 重组 → `when` 分支切换，任何一环被削弱都表现为「点了没反应」。
 *
 * 这里把页面切换改成**真正的 Activity 跳转**：
 *  · `onClick` 里只做一件事 `startActivity(...)`，是命令式调用，不经过任何 Compose 状态；
 *  · 返回 = `finish()`，系统返回手势/返回键天然正确，不需要 `BackHandler`；
 *  · 页面之间互不干扰，二级页崩溃也不会带走主页。
 *
 * 页面标识用 Intent extra 的**字符串**传递，但接收端用**显式 `when`** 解析
 * （不用枚举序号：序号会随枚举增删漂移，跨版本不安全；字符串 + 显式 mapping 才有明确出口）。
 */
class PageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent?.getStringExtra(EXTRA_PAGE).orEmpty()
        UiDiag.log("PageActivity.onCreate page=$page")
        enableEdgeToEdge()
        setContent {
            MoriModTheme {
                PageHost(page = page, onBack = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_PAGE = "page"

        const val PAGE_MEDIA = "media"
        const val PAGE_BACKGROUND = "background"
        const val PAGE_FONT = "font"
        const val PAGE_ABOUT = "about"

        /** 构造跳转意图；[page] 必须是上面四个常量之一。 */
        internal fun intent(ctx: Context, page: String): Intent =
            Intent(ctx, PageActivity::class.java).putExtra(EXTRA_PAGE, page)

        /** [Dest] → 页面标识（显式 `when`，不用 `name`/序号，避免改动枚举时静默错位）。 */
        internal fun pageOf(dest: Dest): String = when (dest) {
            Dest.HOME -> PAGE_ABOUT      // 主页不走这里；兜底给一个安全页，绝不空白
            Dest.MEDIA -> PAGE_MEDIA
            Dest.BACKGROUND -> PAGE_BACKGROUND
            Dest.FONT -> PAGE_FONT
            Dest.ABOUT -> PAGE_ABOUT
        }
    }
}

/**
 * 二级页内容。
 *
 * ⚠️ 每个页面各自 `viewModel()`：Activity 之间不共享 ViewModel，
 *    但配置本身落在 SharedPreferences/JSON 文件里（[io.github.ariinyume.morimod.config.ConfigStore]），
 *    所以跨页读写天然一致。
 */
@Composable
private fun PageHost(page: String, onBack: () -> Unit) {
    val vm: MoriViewModel = viewModel()
    val snackbar = remember { SnackbarHostState() }
    val pageModifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.systemBars)

    // ★ v0.1.5：二级页也要能显示一次性提示。
    //   旧版只有 SettingsActivity 挂了 SnackbarHost，二级页设置的 `vm.toast`
    //   既没人渲染、也不会被消费 —— 于是「非受支持的文件」永远看不见
    //   （而且这句子还会残留到用户回到主页时才突然弹出来）。
    val toast = vm.toast
    LaunchedEffect(toast) {
        if (toast != null) {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(toast, duration = SnackbarDuration.Short)
            vm.consumeToast()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (page) {
            PageActivity.PAGE_MEDIA ->
                MediaControlScreen(vm = vm, onBack = onBack, modifier = pageModifier)

            PageActivity.PAGE_BACKGROUND ->
                BackgroundScreen(vm = vm, onBack = onBack, modifier = pageModifier)

            PageActivity.PAGE_FONT ->
                FontScreen(vm = vm, onBack = onBack, modifier = pageModifier)

            else ->
                AboutScreen(vm = vm, onBack = onBack, modifier = pageModifier)
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}
