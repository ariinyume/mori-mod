package io.github.ariinyume.morimod.ui

/**
 * 目的地枚举。
 *
 * ⚠️ 刻意**不在这里缓存标题字符串**：标题走 [S]，而 [S] 是随语言切换的活取值。
 *    枚举常量在类初始化时只求值一次，若把 `S.F1_TITLE` 写进构造参数，
 *    切语言后标题会永远停在首次加载那份（Kotlin 枚举初始化的经典坑）。
 *    需要标题的地方直接读 [S]，或调 [title]。
 *
 * ⚠️ v0.0.8 起枚举**不再承担路由状态**：
 *    之前页面状态存的是 `rememberSaveable` 里的 Int/字符串，页面切换依赖
 *    「状态写入 → 重组 → `when` 分支切换」。真机上连续两轮出现「点了页面不动」
 *    （水波纹正常 ⇒ 触摸没问题），所以页面切换改为真实 Activity 跳转
 *    （见 [PageActivity]），这里只保留「目的地」这一层语义，
 *    [route] 仅用于日志/备份文本，不参与任何状态比较。
 */
internal enum class Dest(val route: String) {
    HOME("home"),
    MEDIA("media"),
    BACKGROUND("background"),
    FONT("font"),
    ABOUT("about");

    /** 当前语言下的标题（每次都重新取）。 */
    fun title(): String = when (this) {
        HOME -> S.APP_TITLE
        MEDIA -> S.F1_TITLE
        BACKGROUND -> S.F2_TITLE
        FONT -> S.F4_TITLE
        ABOUT -> S.ABOUT_TITLE
    }
}
