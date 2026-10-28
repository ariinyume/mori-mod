package io.github.ariinyume.morimod.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ariinyume.morimod.config.Protocol
import io.github.ariinyume.morimod.ui.screens.HomeScreen
import io.github.ariinyume.morimod.ui.theme.MoriModTheme
import kotlinx.coroutines.delay

/**
 * 设置页唯一入口（跑在模块自己的进程里，宿主未启动也能打开）。
 *
 * ⚠️ v0.0.8 起**主页与二级页分成两个 Activity**：
 *    本 Activity 只承载主页，二级页由 [PageActivity] 承载。
 *    原因是 v0.0.6 / v0.0.7 连续两轮出现「条目有水波纹、页面纹丝不动」，
 *    而「在一个 Composition 里用状态切页面」这条链路是唯一可疑环节
 *    （水波纹在按下那一帧就画，说明触摸本身没问题）。详见 [PageActivity] 注释。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiDiag.log("SettingsActivity.onCreate")
        enableEdgeToEdge()
        setContent {
            MoriModTheme {
                MoriApp()
            }
        }
    }
}

/**
 * 主页骨架。
 *
 * ⚠️ 不用 `Scaffold`（material3 1.5.0-alpha 的 Scaffold 是 SubcomposeLayout，
 *    content / snackbar / fab 各占一层盒子，配合 `enableEdgeToEdge()` 时
 *    子层测量与命中区可能不一致）。这里层级压到最扁：
 *    背景 Box → 内容 Box(`windowInsetsPadding(systemBars)`) → SnackbarHost。
 */
@Composable
private fun MoriApp() {
    val vm: MoriViewModel = viewModel()
    val snackbar = remember { SnackbarHostState() }
    val ctx = LocalContext.current

    // ── 宿主作用域探测 ────────────────────────────────────────────────
    // 必须**持续**探：第一次进设置页时宿主大概率还没起来，只探一次会把状态
    // 钉死在「未运行」，用户之后再去开 DLsiteSound 也不会自动变。
    LaunchedEffect(Unit) {
        vm.tickHostStatus()              // 心跳时间戳可能已经是新鲜的 ⇒ 第一帧就能显示运行中
        vm.pingHost()
        while (true) {
            delay(HOST_PROBE_INTERVAL_MS)
            vm.pingHost()
            // 状态由「最后一次见到宿主距今多久」决定，不再由单次问答是否赶上截止时间决定。
            // 见 MoriViewModel.tickHostStatus 的注释（v0.1.2 修「宿主开着却显示未运行」）。
            vm.tickHostStatus()
        }
    }

    // 监听宿主应答（ping/pong），回填作用域状态。
    HostPongReceiver(vm)

    // 一次性提示 → Snackbar
    val toast = vm.toast
    LaunchedEffect(toast) {
        if (toast != null) {
            // `showSnackbar` 是**排队**的：不 dismiss 的话连点两项时新提示要等
            // 上一条 4 秒走完才出现，体感就是「点了没反应」。
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(toast, duration = SnackbarDuration.Short)
            vm.consumeToast()
        }
    }

    /**
     * 跳二级页：**命令式 startActivity**，不经任何 Compose 状态。
     * 失败了也只可能是 Activity 没注册（会抛 ActivityNotFoundException 并落日志）。
     */
    val navigate: (Dest) -> Unit = { dest ->
        val page = PageActivity.pageOf(dest)
        UiDiag.log("navigate " + dest.name + " -> " + page)
        try {
            ctx.startActivity(PageActivity.intent(ctx, page))
        } catch (t: Throwable) {
            UiDiag.log("startActivity failed: " + t.javaClass.name + " " + t.message)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HomeScreen(
            vm = vm,
            onNavigate = navigate,
            onExportFromHome = { vm.exportToClipboard() },
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        )

        // Snackbar 单独一层，覆盖在内容之上，不参与内容布局。
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}

/**
 * 探测节奏。
 *
 * <p>⚠️ v0.1.2：这里不再有「1.2 秒没 pong 就判未运行」的硬超时 —— 真机上宿主在后台
 * 被冻结时，广播的排队 + 解冻延迟轻易超过 1.2 秒，于是「明明开着却写未运行」。
 * 现在 ping 只是个**加速器**（正常情况下 pong 立刻回来，状态秒变），
 * 真正判定交给 {@code MoriViewModel.tickHostStatus} 的 45 秒宽限窗口，
 * 那个窗口同时被宿主的 20 秒心跳喂着。
 */
private const val HOST_PROBE_INTERVAL_MS = 1500L

/**
 * 注册一个临时接收器，收宿主对 ping 的应答。
 *
 * ⚠️ 必须用 `RECEIVER_EXPORTED`：应答来自 **DLsiteSound 那个进程**（不同 UID），
 *    用 `RECEIVER_NOT_EXPORTED` 会被系统直接拦掉，表现就是状态永远停在「检测中」。
 *    广播本身靠 `setPackage(模块包名)` 定向，不会外泄给别的应用。
 */
@Composable
private fun HostPongReceiver(vm: MoriViewModel) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action != Protocol.ACTION_HOST_PONG) return
                vm.onHostPong(i.getBooleanExtra(Protocol.EXTRA_HOST_ALIVE, false))
            }
        }
        val filter = IntentFilter(Protocol.ACTION_HOST_PONG)
        val registered = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(r, filter)
            }
            true
        } catch (t: Throwable) {
            false
        }
        onDispose {
            if (registered) {
                try {
                    ctx.unregisterReceiver(r)
                } catch (t: Throwable) {
                    // 已注销，忽略
                }
            }
        }
    }
}
