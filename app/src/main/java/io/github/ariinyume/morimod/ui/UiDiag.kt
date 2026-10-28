package io.github.ariinyume.morimod.ui

import android.content.Context
import android.util.Log

/**
 * 设置页的**取证探针**（v0.0.8）。
 *
 * 为什么需要它：v0.0.6 / v0.0.7 两轮都出现「点条目毫无反应」，而 LSPosed 日志里
 * 我们的设置页进程**一行输出都没有**（模块只注入宿主进程，设置页不受 hook），
 * 导致只能靠猜。这里给设置页自己加一条明确通道：
 *
 *  · `Log.i("MoriModUI", …)` —— 用户导出的 LSPosed 包里 `full.log` 就是 logcat
 *    全量，直接能看到我们的输出，不需要额外抓日志；
 *  · 调试开关打开时再叠一条**系统 Toast**（独立窗口，不经过 Compose 的状态/重组链路）。
 *    看到 Toast 就说明「手指点击 → clickable 回调」这一段是通的，
 *    问题只可能在「回调之后的界面刷新」；看不到 Toast 则说明点击根本没被识别。
 *
 * 生产环境把「调试日志」开关关掉即静默，只剩 Log（日志级别 I，开销可忽略）。
 */
object UiDiag {

    private const val TAG = "MoriModUI"

    /** 无条件写 logcat（用户导出的 LSPosed 包里能看到）。 */
    fun log(msg: String) {
        Log.i(TAG, msg)
    }

    /**
     * 点击探针：**只写日志，不再弹 Toast**（v0.1.23 用户第 7 条）。
     *
     * <h3>为什么去掉 Toast</h3>
     * 用户明确要求删掉「点击 主程序背景自定义 / 主程序字体替换 / 关于」时弹的提示。
     * 那三条提示就是这里发的（调试日志开关打开时）—— 对一个已经用了十几轮模块的用户来说，
     * 「点一下就弹一次『▸ 点击：xxx』」纯属噪音。日志保留：真出问题时还能靠它定位。
     *
     * @param what 被点的东西（列表项标题 / 按钮名）
     */
    @Suppress("UNUSED_PARAMETER")
    fun click(ctx: Context, what: String) {
        Log.i(TAG, "CLICK «$what»")
    }

    /** 一次性的「里程碑」提示（例如页面已打开）：同样只写日志，不弹 Toast。 */
    @Suppress("UNUSED_PARAMETER")
    fun milestone(ctx: Context, what: String) {
        Log.i(TAG, "MILESTONE $what")
    }
}
