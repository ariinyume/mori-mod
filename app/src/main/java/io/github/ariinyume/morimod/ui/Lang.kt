package io.github.ariinyume.morimod.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 设置页语言。
 *
 * 第一批只出简中（PRD D-17），此处按草图的「简中 / 繁中 / EN」按钮把三种语言真正做出来：
 * 语言选择随配置持久化，重启保留。
 */
internal enum class Lang(val code: String, val label: String) {
    ZH_CN("zh-CN", "简中"),
    ZH_TW("zh-TW", "繁中"),
    EN("en", "EN"),
    JA("ja", "日本語"),
    KO("ko", "한국어");

    /** 下一个语言（按钮循环：简中→繁中→EN→日本語→한국어）。 */
    fun next(): Lang = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(code: String?): Lang = entries.firstOrNull { it.code == code } ?: ZH_CN
    }
}

/**
 * 设置页全部文案的**类型化表格**。
 *
 * 为什么不用 `res/values-xx/strings.xml`：那套要配合 `AppCompatDelegate.setApplicationLocales`
 * 或重建 Activity，而本模块是**独立进程的普通 Activity + 跨进程配置**，
 * 用一份 Kotlin 表格 + 一层 `mutableStateOf` 更直接，也避免把 appcompat 拖进来。
 */
internal abstract class MoriStrings {
    // ── 应用 / 顶栏 ────────────────────────────────────────────────
    abstract val APP_TITLE: String
    abstract val HOME_SUBTITLE: String
    abstract val BACK: String

    // ── 主页：状态卡 ───────────────────────────────────────────────
    abstract val STATUS_TITLE: String
    abstract val STATUS_PLUGIN: String
    abstract val STATUS_HOST: String
    abstract val STATUS_RUNTIME: String
    abstract val STATUS_CHECKING: String
    abstract val STATUS_ACTIVE: String
    abstract val STATUS_INACTIVE: String
    abstract val STATUS_HOST_MISSING: String

    // ── 主页：分区与列表项 ─────────────────────────────────────────
    abstract val SEC_FEATURES: String
    abstract val SEC_BEAUTY: String
    abstract val SEC_OPTIMIZE: String
    abstract val SEC_MANAGE: String

    abstract val ITEM_BACKGROUND: String
    abstract val ITEM_FONT: String
    abstract val ITEM_LAYOUT: String
    abstract val ITEM_COVER_BLOCK: String
    abstract val ITEM_COVER_BLOCK_SUM: String
    /** 切歌/切轨卡顿优化（实验性）**总开关**：这条线上的所有改动都挂在它下面。 */
    abstract val ITEM_PERF: String
    abstract val ITEM_PERF_SUM: String
    /** 优化功能入口：字幕调整。 */
    abstract val ITEM_SUBTITLE: String
    abstract val F6_TITLE: String
    /** 字幕调整总开关。 */
    abstract val F6_ENABLE: String
    abstract val F6_ENABLE_SUM: String
    /** 播放页宿主字幕字号（x1.0–1.5）。 */
    abstract val ITEM_SUBTITLE_SIZE: String
    abstract val ITEM_SUBTITLE_SIZE_SUM: String
    abstract val ITEM_SUBTITLE_BG_CLEAR: String
    abstract val ITEM_SUBTITLE_BG_CLEAR_SUM: String
    abstract val ITEM_HIDE_ICON: String
    abstract val ITEM_DEBUG_LOG: String
    abstract val ITEM_DEBUG_LOG_SUM: String
    abstract val ITEM_BACKUP: String
    abstract val ITEM_BACKUP_SUM: String
    abstract val ITEM_ABOUT: String

    // ── F2 背景 ────────────────────────────────────────────────────
    abstract val F2_TITLE: String
    abstract val F2_ENABLE: String
    abstract val F2_CARD: String
    abstract val F2_BLUR: String

    /** 播放页背景模糊（v0.1.21，取代已删除的「遮罩浓度」）。 */
    abstract val F2_PLAY_BLUR: String
    /** 信息控件背景模糊（迷你条 / 导航底栏 / 信息面板）。 */
    abstract val F2_CHROME_BLUR: String
    abstract val F2_ACCENT: String
    abstract val F2_ACCENT_NOTE: String
    abstract val F2_ACCENT_STYLE: String
    abstract val F2_CURRENT_NONE: String
    abstract val F2_CURRENT: String

    /** 「我的背景」图库卡（v0.1.10）。 */
    abstract val F2_MY_BGS: String
    abstract val F2_MY_BGS_NOTE: String
    abstract val F2_MY_BGS_EMPTY: String

    /** 「桌面壁纸」来源（v0.1.10 取代旧的「全透明背景」）。 */

    /** 裁切页（v0.1.11）：选完图先按屏幕比例取景，再落成背景。 */
    abstract val CROP_TITLE: String
    abstract val CROP_HINT: String
    abstract val BTN_CROP_SKIP: String
    abstract val BTN_CROP_USE: String
    abstract val TOAST_CROP_FAILED: String

    /** 长按删除背景图的确认文案。 */
    abstract val F2_DELETE_BODY: String

    // ── F5 主页布局 ────────────────────────────────────────────────
    abstract val F5_TITLE: String
    abstract val F5_ENABLE: String
    abstract val F5_NOTE: String
    abstract val F5_MODULES: String
    abstract val F5_RESTORE: String
    abstract val F5_MOVE_UP: String
    abstract val F5_MOVE_DOWN: String
    abstract val F5_MOD_GENRE: String
    abstract val F5_MOD_RECENT_PURCHASE: String
    abstract val F5_MOD_RECENT_PLAYED: String
    abstract val F5_MOD_MY_SOUND: String
    abstract val F5_MOD_RECAP: String
    abstract val F5_MOD_SYNC: String
    abstract val F5_MOD_UNKNOWN: String
    abstract val TOAST_F5_NEED_LOGIN: String

    // ── F4 字体 ────────────────────────────────────────────────────
    abstract val F4_TITLE: String
    abstract val F4_CARD: String
    abstract val F4_CURRENT: String
    abstract val F4_NONE: String
    abstract val F4_SYSTEM_FONTS: String
    abstract val F4_RISK_NOTE: String

    /** 选了与宿主默认完全一致的字体时的提醒（选了等于没选，别让用户以为坏了）。 */
    abstract val F4_NOOP: String

    /** 内置字体区块标题。 */
    abstract val F4_MY_FONTS: String
    abstract val F4_MY_FONTS_NOTE: String
    abstract val F4_MY_FONTS_EMPTY: String
    abstract val F4_DELETE_TITLE: String
    abstract val F4_DELETE_BODY: String
    abstract val BTN_DELETE: String
    abstract val TOAST_FONT_DELETED: String
    abstract val F4_BUNDLED: String

    /** 内置字体的说明（含授权口径：OFL 允许商用 + 再分发）。 */
    abstract val F4_BUNDLED_NOTE: String
    abstract val F4_NOTE_NOTO: String

    /** 关于页：内置字体授权区块标题。 */
    abstract val ABOUT_FONTS: String

    /** 关于页：查看 OFL 1.1 许可证全文（OFL 要求用户能方便地看到条款）。 */
    abstract val BTN_VIEW_OFL: String
    abstract val F4_PREVIEW: String

    // ── 关于 ───────────────────────────────────────────────────────
    abstract val ABOUT_TITLE: String
    abstract val ABOUT_SUBTITLE: String
    abstract val ABOUT_VERSION: String
    abstract val ABOUT_CODE: String
    abstract val ABOUT_PACKAGE: String
    abstract val ABOUT_HOST: String
    abstract val ABOUT_HOST_VERSION: String
    abstract val ABOUT_HOST_NOT_INSTALLED: String
    abstract val ABOUT_COLOR_SOURCE: String
    abstract val ABOUT_COLOR_DYNAMIC: String
    abstract val ABOUT_COLOR_FALLBACK: String
    abstract val ABOUT_LICENSE: String
    abstract val ABOUT_FEEDBACK_SUM: String

    // ── 关于页四张卡（v0.1.22 用户第 7 条）────────────────────────────
    abstract val ABOUT_WHY_TITLE: String
    abstract val ABOUT_WHY_BODY: String
    abstract val ABOUT_DISCLAIMER_TITLE: String
    abstract val ABOUT_DISCLAIMER_BODY: String
    abstract val ABOUT_REPO_TITLE: String
    abstract val BTN_OPEN_REPO: String
    abstract val BTN_COPY_LINK: String

    // ── 按钮 ───────────────────────────────────────────────────────
    /** 卡片右上角开关的统一小标签。 */
    abstract val COMMON_ENABLE: String

    abstract val BTN_RESTORE: String
    abstract val BTN_CLEAR: String
    abstract val BTN_EXPORT: String
    abstract val BTN_PICK_IMAGE: String
    /** 「桌面壁纸」（v0.1.10 取代旧的「全透明背景」按钮）。 */
    abstract val BTN_PICK_FONT: String
    abstract val BTN_USE_SYSTEM_FONT: String
    abstract val BTN_COPY_INFO: String
    abstract val BTN_OK: String
    abstract val BTN_CANCEL: String

    // ── 对话框 ─────────────────────────────────────────────────────
    abstract val DLG_ADD_NAME: String
    abstract val DLG_ADD_NAME_HINT: String
    abstract val DLG_FIELD_TEXT: String
    abstract val DLG_CLEAR_TITLE: String
    abstract val DLG_CLEAR_TEXT: String
    abstract val DLG_DELETE_TITLE: String
    abstract val DLG_DELETE_TEXT: String
    abstract val DLG_HIDE_ICON_TITLE: String
    abstract val DLG_HIDE_ICON_TEXT: String
    abstract val DLG_BACKUP_TITLE: String
    abstract val DLG_BACKUP_HINT: String
    abstract val DLG_BACKUP_EXPORT: String
    abstract val DLG_BACKUP_IMPORT: String
    abstract val DLG_BACKUP_COPY_HINT: String

    // ── 提示 ───────────────────────────────────────────────────────
    abstract val TOAST_SAVED: String
    abstract val TOAST_SAVE_FAILED: String
    abstract val TOAST_COPIED: String
    abstract val TOAST_PICK_FAILED: String
    abstract val TOAST_FONT_UNSUPPORTED: String
    abstract val TOAST_PICK_CANCELED: String
    abstract val TOAST_EXPORTED: String
    abstract val TOAST_EXPORT_FAILED: String
    abstract val TOAST_EXPORT_COPIED: String
    abstract val TOAST_IMPORTED: String
    abstract val TOAST_IMPORT_EMPTY: String
    abstract val TOAST_IMPORT_CHECKSUM: String
    abstract val TOAST_IMPORT_FORMAT: String
    abstract val TOAST_REQUIRES_HOST: String
    abstract val TOAST_RESERVED: String

    /** 列表项右侧的「待开发」小标签（比 toast 更早被看见，避免用户以为功能坏了）。 */
    abstract val TAG_WIP: String
    /** 「实验性」小标签：开了会改变宿主行为，且不保证在所有机型上都好看。 */
    abstract val TAG_EXPERIMENTAL: String
    abstract val TOAST_DEFAULT_RESTORED: String
    /** 删掉了正在使用的背景图 ⇒ 已自动恢复宿主默认。 */
    abstract val TOAST_BG_RESTORED: String
    abstract val TOAST_ICON_HIDDEN: String
    abstract val TOAST_ICON_SHOWN: String
    abstract val TOAST_ICON_HIDE_FAILED: String
    abstract val TOAST_DELETED: String

    // ── 导出文件名 ─────────────────────────────────────────────────
}

/** 简体中文（默认）。 */
internal object ZhCn : MoriStrings() {
    override val APP_TITLE = "Mori MOD"
    override val HOME_SUBTITLE = "DLsiteSound 体验增强模块"
    override val BACK = "返回"

    override val STATUS_TITLE = "状态卡"
    override val STATUS_PLUGIN = "插件版本"
    override val STATUS_HOST = "DLsiteSound 版本"
    override val STATUS_RUNTIME = "运行状况"
    override val STATUS_CHECKING = "检测中…"
    override val STATUS_ACTIVE = "正常运行"
    override val STATUS_INACTIVE = "未运行"
    override val STATUS_HOST_MISSING = "未安装"

    override val SEC_FEATURES = "功能设置"
    override val SEC_BEAUTY = "美化功能"
    override val SEC_OPTIMIZE = "优化功能"
    override val SEC_MANAGE = "模块管理"

    override val ITEM_BACKGROUND = "背景自定义"
    override val ITEM_FONT = "字体替换"
    override val ITEM_LAYOUT = "主页模块布局调整"
    override val ITEM_COVER_BLOCK = "作品封面屏蔽"
    override val ITEM_COVER_BLOCK_SUM = "全页面不显示作品封面，位置仍可点击"
    override val ITEM_PERF = "播放页曲名 / 作品名停止滚动"
    override val ITEM_PERF_SUM = "实验性。播放页曲名和作品名不再滚动"
    override val ITEM_SUBTITLE = "字幕调整"
    override val F6_TITLE = "字幕调整"
    override val F6_ENABLE = "启用字幕调整"
    override val F6_ENABLE_SUM = "关闭时字幕大小与背景透明均不生效"
    override val ITEM_SUBTITLE_SIZE = "字幕大小"
    override val ITEM_SUBTITLE_SIZE_SUM = "播放页宿主字幕字号，范围 ×1.0–1.5"
    override val ITEM_SUBTITLE_BG_CLEAR = "字幕背景透明"
    override val ITEM_SUBTITLE_BG_CLEAR_SUM = "打开后字幕条底色透明；关闭则保留紫/强调色底"
    override val ITEM_HIDE_ICON = "隐藏模块桌面图标"
    override val ITEM_DEBUG_LOG = "调试日志"
    override val ITEM_DEBUG_LOG_SUM = "默认关闭，打开后写入 LSPosed 日志"
    override val ITEM_BACKUP = "导出与备份"
    override val ITEM_BACKUP_SUM = "导出配置文本，或从备份还原"
    override val ITEM_ABOUT = "关于"

    override val F2_TITLE = "主程序背景自定义"
    override val F2_ENABLE = "启用替换"
    override val F2_CARD = "主程序背景替换"
    override val F2_BLUR = "主背景模糊"
    override val F2_PLAY_BLUR = "播放页背景模糊"
    override val F2_CHROME_BLUR = "信息控件背景模糊"
    override val F2_ACCENT = "强调色跟自定义背景取色"
    override val F2_ACCENT_NOTE = "开启后，宿主里除白色以外的文字与所有阴影蒙层都会跟随壁纸取出的强调色；关掉即还原宿主默认。"
    override val F2_ACCENT_STYLE = "强调色风格"
    override val F2_CURRENT_NONE = "当前背景：宿主默认"
    override val F2_CURRENT = "当前背景"
    override val F2_MY_BGS = "我的背景"
    override val F2_MY_BGS_NOTE = "支持 jpg/png/webp：点一项即可换用，长按删除"
    override val F2_MY_BGS_EMPTY = "还没有导入过背景图。"
    override val CROP_TITLE = "裁剪背景"
    override val CROP_HINT = "双指缩放、拖动调整位置；框内这块会按屏幕比例铺满宿主背景"
    override val BTN_CROP_SKIP = "跳过裁剪"
    override val BTN_CROP_USE = "使用这块"
    override val TOAST_CROP_FAILED = "裁剪失败，已保留原图"
    override val F2_DELETE_BODY = "会从图库里删掉这张图。如果它正被用作宿主背景，背景会一并恢复成宿主默认。"

    override val F5_TITLE = "主页模块布局"
    override val F5_ENABLE = "启用主页布局调整"
    override val F5_NOTE = "该功能仅影响处于登录态的宿主首页。"
    override val F5_MODULES = "主页区块"
    override val F5_RESTORE = "恢复默认布局"
    override val F5_MOVE_UP = "上移"
    override val F5_MOVE_DOWN = "下移"
    override val F5_MOD_GENRE = "类型"
    override val F5_MOD_RECENT_PURCHASE = "最近购买"
    override val F5_MOD_RECENT_PLAYED = "最近听过的作品"
    override val F5_MOD_MY_SOUND = "稍后再听"
    override val F5_MOD_RECAP = "回顾"
    override val F5_MOD_SYNC = "同步已购买的作品"
    override val F5_MOD_UNKNOWN = "未知区块"
    override val TOAST_F5_NEED_LOGIN = "DLsiteSound未登录，无法使用此功能。"

    override val F4_TITLE = "主程序字体替换"
    override val F4_CARD = "启用字体替换"
    override val F4_CURRENT = "当前字体"
    override val F4_NONE = "（未设置素材）"
    override val F4_SYSTEM_FONTS = "系统字体"
    override val F4_RISK_NOTE = "改动后如字体显示异常，重启DLsiteSound即可正常显示。"
    override val F4_NOTE_NOTO = "插件内置的字体仅便于简体中文环境阅读，其他语言环境可能出现异体字或缺字。"
    override val F4_MY_FONTS = "用户导入字体"
    override val F4_MY_FONTS_NOTE = "支持 .ttf/.otf：点一项即可换用，长按删除"
    override val F4_MY_FONTS_EMPTY = "还没有导入过字体文件。"
    override val F4_DELETE_TITLE = "删除字体"
    override val F4_DELETE_BODY = "会从模块目录里删掉这份字体副本（不影响你原来的文件）。如果它正是当前字体，替换会同时关闭。"
    override val BTN_DELETE = "删除"
    override val TOAST_FONT_DELETED = "字体已删除"
    override val F4_BUNDLED = "插件内置字体"
    override val F4_NOOP = "所选字体与宿主默认一致，替换后看不出变化；请换一个字体文件或其它族。"
    override val F4_BUNDLED_NOTE = "随包内置的圆构黑体、普力士均为 SIL OFL 1.1：允许商用，也允许随本模块再分发（需保留版权声明与许可证，见「关于 → 内置字体授权」）。钉钉进步体、阿里巴巴普惠体只授权「使用」、不允许再分发字体文件，故未内置，需要时请自行选择文件导入。"
    override val ABOUT_FONTS = "内置字体授权"
    override val BTN_VIEW_OFL = "查看 OFL 1.1 全文"
    override val F4_PREVIEW = "字体预览"

    override val ABOUT_TITLE = "关于"
    override val ABOUT_SUBTITLE = "DLsiteSound 体验增强模块"
    override val ABOUT_VERSION = "插件版本"
    override val ABOUT_CODE = "构建号"
    override val ABOUT_PACKAGE = "包名"
    override val ABOUT_HOST = "宿主包名"
    override val ABOUT_HOST_VERSION = "宿主版本"
    override val ABOUT_HOST_NOT_INSTALLED = "未安装"
    override val ABOUT_COLOR_SOURCE = "配色来源"
    override val ABOUT_COLOR_DYNAMIC = "系统动态取色"
    override val ABOUT_COLOR_FALLBACK = "内置 Mono 备用配色"
    override val ABOUT_LICENSE = "开源许可"
    override val ABOUT_FEEDBACK_SUM = "遇到异常请附上 LSPosed 日志。"
    override val ABOUT_WHY_TITLE = "为什么做 MoriMod？"
    override val ABOUT_WHY_BODY = "想要把主页/播放页背景换成自己喜欢的图片。\n\n" +
        "想要把应用字体换成适合阅读的字体。\n\n" +
        "虽然作者制作这个模块纯粹是为了好玩，但如果能提升你的一点点使用体验我将不胜荣幸。\n\n" +
        "感谢你的下载与使用，愿你享受每一段音频。"
    override val ABOUT_DISCLAIMER_TITLE = "免责声明"
    override val ABOUT_DISCLAIMER_BODY = "仅供个人学习、研究、测试与自用，不得用于违法违规、侵害他人权益或破坏平台正常服务。\n\n" +
        "本模块由 AI 辅助开发，仅能保证在开发过程中绝不包含人工主观恶意输入与恶意行为；AI 产生的代码可能无法完全符合开发预期，请在使用前自行确认。\n\n" +
        "本模块为独立开发的第三方工具，与 DLsiteSound（eisys 株式会社）及各手机厂商无任何隶属或合作关系。\n\n" +
        "使用本模块需要 Root 与 Xposed 框架，可能违反部分设备的保修条款或某些 ROM 的安全策略，可能带来系统不稳定、失去保修、安全风险等后果，由此带来的任何风险由使用者自行承担。\n\n" +
        "第三方修改模块运行时可能导致账号异常、设备不稳定、数据丢失等风险，请提前备份重要数据并优先使用非重要账号和非关键场景测试。\n\n" +
        "继续使用即表示你已知悉上述风险；如不同意本声明，请停止使用并卸载模块。"
    override val ABOUT_REPO_TITLE = "项目地址"
    override val BTN_OPEN_REPO = "打开"
    override val BTN_COPY_LINK = "复制链接"

    override val COMMON_ENABLE = "启用替换"
    override val BTN_RESTORE = "恢复默认"
    override val BTN_CLEAR = "清空列表"
    override val BTN_EXPORT = "导出列表"
    override val BTN_PICK_IMAGE = "选择图片"
    override val BTN_PICK_FONT = "导入本地字体文件"
    override val BTN_USE_SYSTEM_FONT = "使用系统内置字体"
    override val BTN_COPY_INFO = "复制版本信息"
    override val BTN_OK = "确定"
    override val BTN_CANCEL = "取消"

    override val DLG_ADD_NAME = "添加名称"
    override val DLG_ADD_NAME_HINT = "支持换行批量导入，每行一个"
    override val DLG_FIELD_TEXT = "文本"
    override val DLG_CLEAR_TITLE = "清空列表？"
    override val DLG_CLEAR_TEXT = "该列表中的条目将被全部删除，且无法恢复。"
    override val DLG_DELETE_TITLE = "确认删除？"
    override val DLG_DELETE_TEXT = "删除后无法恢复。"
    override val DLG_HIDE_ICON_TITLE = "隐藏桌面图标？"
    override val DLG_HIDE_ICON_TEXT = "隐藏后桌面图标消失（重启后仍然隐藏）。之后要再进来：LSPosed 管理器 → 模块 → 长按本模块 →「启动」。"
    override val DLG_BACKUP_TITLE = "导出与备份"
    override val DLG_BACKUP_HINT = "导出为单文件文本；导入时校验完整性，校验不通过则不覆盖现有配置。"
    override val DLG_BACKUP_EXPORT = "导出"
    override val DLG_BACKUP_IMPORT = "导入"
    override val DLG_BACKUP_COPY_HINT = "再点一次「导出」即复制到剪贴板。"

    override val TOAST_SAVED = "已保存"
    override val TOAST_SAVE_FAILED = "保存失败，请重试"
    override val TOAST_COPIED = "已复制到剪贴板"
    override val TOAST_PICK_FAILED = "读取所选文件失败"
    override val TOAST_FONT_UNSUPPORTED = "非受支持的文件"
    override val TOAST_PICK_CANCELED = "已取消选择"
    override val TOAST_EXPORTED = "已导出"
    override val TOAST_EXPORT_FAILED = "导出失败"
    override val TOAST_EXPORT_COPIED = "配置文本已复制到剪贴板"
    override val TOAST_IMPORTED = "导入成功"
    override val TOAST_IMPORT_EMPTY = "剪贴板里没有配置文本"
    override val TOAST_IMPORT_CHECKSUM = "校验失败，配置未被覆盖"
    override val TOAST_IMPORT_FORMAT = "格式不符，配置未被覆盖"
    override val TOAST_REQUIRES_HOST = "请先打开 DLsiteSound 再试"
    override val TOAST_RESERVED = "待开发功能，请等待更新"
    override val TAG_WIP = "待开发"
    override val TAG_EXPERIMENTAL = "实验性"
    override val TOAST_DEFAULT_RESTORED = "已恢复宿主默认"
    override val TOAST_BG_RESTORED = "已恢复为宿主默认背景"
    override val TOAST_ICON_HIDDEN = "桌面图标已隐藏"
    override val TOAST_ICON_SHOWN = "桌面图标已恢复"
    override val TOAST_ICON_HIDE_FAILED = "修改桌面图标失败"
    override val TOAST_DELETED = "已删除"

}

/** 繁體中文。 */
internal object ZhTw : MoriStrings() {
    override val APP_TITLE = "Mori MOD"
    override val HOME_SUBTITLE = "DLsiteSound 體驗增強模組"
    override val BACK = "返回"

    override val STATUS_TITLE = "狀態卡"
    override val STATUS_PLUGIN = "外掛版本"
    override val STATUS_HOST = "DLsiteSound 版本"
    override val STATUS_RUNTIME = "執行狀況"
    override val STATUS_CHECKING = "偵測中…"
    override val STATUS_ACTIVE = "正常執行"
    override val STATUS_INACTIVE = "未執行"
    override val STATUS_HOST_MISSING = "未安裝"

    override val SEC_FEATURES = "功能設定"
    override val SEC_BEAUTY = "美化功能"
    override val SEC_OPTIMIZE = "最佳化功能"
    override val SEC_MANAGE = "模組管理"

    override val ITEM_BACKGROUND = "背景自訂"
    override val ITEM_FONT = "字型替換"
    override val ITEM_LAYOUT = "首頁模組版面調整"
    override val ITEM_COVER_BLOCK = "作品封面屏蔽"
    override val ITEM_COVER_BLOCK_SUM = "全頁面不顯示作品封面，位置仍可點擊"
    override val ITEM_PERF = "播放頁曲名 / 作品名停止滾動"
    override val ITEM_PERF_SUM = "實驗性。播放頁曲名和作品名不再滾動"
    override val ITEM_SUBTITLE = "字幕調整"
    override val F6_TITLE = "字幕調整"
    override val F6_ENABLE = "啟用字幕調整"
    override val F6_ENABLE_SUM = "關閉時字幕大小與背景透明均不生效"
    override val ITEM_SUBTITLE_SIZE = "字幕大小"
    override val ITEM_SUBTITLE_SIZE_SUM = "播放頁宿主字幕字號，範圍 ×1.0–1.5"
    override val ITEM_SUBTITLE_BG_CLEAR = "字幕背景透明"
    override val ITEM_SUBTITLE_BG_CLEAR_SUM = "開啟後字幕條底色透明；關閉則保留紫／強調色底"
    override val ITEM_HIDE_ICON = "隱藏模組桌面圖示"
    override val ITEM_DEBUG_LOG = "除錯日誌"
    override val ITEM_DEBUG_LOG_SUM = "預設關閉，開啟後寫入 LSPosed 日誌"
    override val ITEM_BACKUP = "匯出與備份"
    override val ITEM_BACKUP_SUM = "匯出設定文字，或從備份還原"
    override val ITEM_ABOUT = "關於"

    override val F2_TITLE = "主程式背景自訂"
    override val F2_ENABLE = "啟用替換"
    override val F2_CARD = "主程式背景替換"
    override val F2_BLUR = "主背景模糊"
    override val F2_PLAY_BLUR = "播放頁背景模糊"
    override val F2_CHROME_BLUR = "資訊控件背景模糊"
    override val F2_ACCENT = "強調色跟自訂背景取色"
    override val F2_ACCENT_NOTE = "開啟後，宿主裡除白色以外的文字與所有陰影蒙層都會跟隨壁紙取出的強調色；關掉即還原宿主預設。"
    override val F2_ACCENT_STYLE = "強調色風格"
    override val F2_CURRENT_NONE = "目前背景：宿主預設"
    override val F2_CURRENT = "目前背景"
    override val F2_MY_BGS = "我的背景"
    override val F2_MY_BGS_NOTE = "支援 jpg/png/webp：點一項即可換用，長按刪除"
    override val F2_MY_BGS_EMPTY = "尚未匯入背景圖。"
    override val CROP_TITLE = "裁剪背景"
    override val CROP_HINT = "雙指縮放、拖動調整位置；框內這塊會按螢幕比例鋪滿宿主背景"
    override val BTN_CROP_SKIP = "略過裁剪"
    override val BTN_CROP_USE = "使用這塊"
    override val TOAST_CROP_FAILED = "裁剪失敗，已保留原圖"
    override val F2_DELETE_BODY = "會從圖庫裡刪掉這張圖。如果它正被用作宿主背景，背景會一併還原成宿主預設。"

    override val F5_TITLE = "首頁模組版面"
    override val F5_ENABLE = "啟用首頁版面調整"
    override val F5_NOTE = "此功能僅影響處於登入態的宿主首頁。"
    override val F5_MODULES = "首頁區塊"
    override val F5_RESTORE = "恢復預設版面"
    override val F5_MOVE_UP = "上移"
    override val F5_MOVE_DOWN = "下移"
    override val F5_MOD_GENRE = "類型"
    override val F5_MOD_RECENT_PURCHASE = "最近購買"
    override val F5_MOD_RECENT_PLAYED = "最近聽過的作品"
    override val F5_MOD_MY_SOUND = "稍後再聽"
    override val F5_MOD_RECAP = "回顧"
    override val F5_MOD_SYNC = "同步已購買的作品"
    override val F5_MOD_UNKNOWN = "未知區塊"
    override val TOAST_F5_NEED_LOGIN = "DLsiteSound未登入，無法使用此功能。"

    override val F4_TITLE = "主程式字型替換"
    override val F4_CARD = "啟用字型替換"
    override val F4_CURRENT = "目前字型"
    override val F4_NONE = "（未設定素材）"
    override val F4_SYSTEM_FONTS = "系統字型"
    override val F4_RISK_NOTE = "改動後如字型顯示異常，重新啟動 DLsiteSound 即可正常顯示。"
    override val F4_NOTE_NOTO = "外掛內建字型僅便於簡體中文環境閱讀，其他語言環境可能出現異體字或缺字。"
    override val F4_MY_FONTS = "使用者匯入字型"
    override val F4_MY_FONTS_NOTE = "支援 .ttf/.otf：點一項即可換用，長按刪除"
    override val F4_MY_FONTS_EMPTY = "還沒有匯入過字型檔。"
    override val F4_DELETE_TITLE = "刪除字型"
    override val F4_DELETE_BODY = "會從模組目錄裡刪掉這份字型副本（不影響你原本的檔案）。如果它正是目前字型，替換會同時關閉。"
    override val BTN_DELETE = "刪除"
    override val TOAST_FONT_DELETED = "字型已刪除"
    override val F4_BUNDLED = "外掛內建字型"
    override val F4_NOOP = "所選字型與宿主預設一致，替換後看不出變化；請換一個字型檔或其它族。"
    override val F4_BUNDLED_NOTE = "隨包內建的圓構黑體、普力士皆為 SIL OFL 1.1：允許商用，也允許隨本模組再分發（需保留版權聲明與授權條款，見「關於 → 內建字型授權」）。釘釘進步體、阿里巴巴普惠體僅授權「使用」、不允許再分發字型檔，故未內建，需要時請自行選擇檔案匯入。"
    override val ABOUT_FONTS = "內建字型授權"
    override val BTN_VIEW_OFL = "檢視 OFL 1.1 全文"
    override val F4_PREVIEW = "字型預覽"

    override val ABOUT_TITLE = "關於"
    override val ABOUT_SUBTITLE = "DLsiteSound 體驗增強模組"
    override val ABOUT_VERSION = "外掛版本"
    override val ABOUT_CODE = "組建號"
    override val ABOUT_PACKAGE = "套件名稱"
    override val ABOUT_HOST = "宿主套件名稱"
    override val ABOUT_HOST_VERSION = "宿主版本"
    override val ABOUT_HOST_NOT_INSTALLED = "未安裝"
    override val ABOUT_COLOR_SOURCE = "配色來源"
    override val ABOUT_COLOR_DYNAMIC = "系統動態取色"
    override val ABOUT_COLOR_FALLBACK = "內建 Mono 備用配色"
    override val ABOUT_LICENSE = "開源授權"
    override val ABOUT_FEEDBACK_SUM = "遇到異常請附上 LSPosed 日誌。"
    override val ABOUT_WHY_TITLE = "為什麼做 MoriMod？"
    override val ABOUT_WHY_BODY = "想要把主頁／播放頁背景換成自己喜歡的圖片。\n\n" +
        "想要把應用字型換成適合閱讀的字型。\n\n" +
        "雖然作者製作這個模組純粹是為了好玩，但如果能提升你的一點點使用體驗我將不勝榮幸。\n\n" +
        "感謝你的下載與使用，願你享受每一段音訊。"
    override val ABOUT_DISCLAIMER_TITLE = "免責聲明"
    override val ABOUT_DISCLAIMER_BODY = "僅供個人學習、研究、測試與自用，不得用於違法違規、侵害他人權益或破壞平台正常服務。\n\n" +
        "本模組由 AI 輔助開發，僅能保證在開發過程中絕不包含人工主觀惡意輸入與惡意行為；AI 產生的程式碼可能無法完全符合開發預期，請在使用前自行確認。\n\n" +
        "本模組為獨立開發的第三方工具，與 DLsiteSound（eisys 株式會社）及各手機廠商無任何隸屬或合作關係。\n\n" +
        "使用本模組需要 Root 與 Xposed 框架，可能違反部分裝置的保固條款或某些 ROM 的安全策略，可能帶來系統不穩定、失去保固、安全風險等後果，由此帶來的任何風險由使用者自行承擔。\n\n" +
        "第三方修改模組執行時可能導致帳號異常、裝置不穩定、資料遺失等風險，請提前備份重要資料並優先使用非重要帳號和非關鍵場景測試。\n\n" +
        "繼續使用即表示你已知悉上述風險；如不同意本聲明，請停止使用並卸載模組。"
    override val ABOUT_REPO_TITLE = "專案網址"
    override val BTN_OPEN_REPO = "開啟"
    override val BTN_COPY_LINK = "複製連結"

    override val COMMON_ENABLE = "啟用替換"
    override val BTN_RESTORE = "還原預設"
    override val BTN_CLEAR = "清空清單"
    override val BTN_EXPORT = "匯出清單"
    override val BTN_PICK_IMAGE = "選擇圖片"
    override val BTN_PICK_FONT = "匯入本機字型檔"
    override val BTN_USE_SYSTEM_FONT = "使用系統內建字型"
    override val BTN_COPY_INFO = "複製版本資訊"
    override val BTN_OK = "確定"
    override val BTN_CANCEL = "取消"

    override val DLG_ADD_NAME = "新增名稱"
    override val DLG_ADD_NAME_HINT = "支援換行批次匯入，每行一個"
    override val DLG_FIELD_TEXT = "文字"
    override val DLG_CLEAR_TITLE = "清空清單？"
    override val DLG_CLEAR_TEXT = "該清單中的項目將被全部刪除，且無法復原。"
    override val DLG_DELETE_TITLE = "確認刪除？"
    override val DLG_DELETE_TEXT = "刪除後無法復原。"
    override val DLG_HIDE_ICON_TITLE = "隱藏桌面圖示？"
    override val DLG_HIDE_ICON_TEXT = "隱藏後桌面圖示會消失（重開機後仍然隱藏）。之後要再進來：LSPosed 管理器 → 模組 → 長按本模組 →「啟動」。"
    override val DLG_BACKUP_TITLE = "匯出與備份"
    override val DLG_BACKUP_HINT = "匯出為單檔文字；匯入時會驗證完整性，驗證不通過則不會覆寫現有設定。"
    override val DLG_BACKUP_EXPORT = "匯出"
    override val DLG_BACKUP_IMPORT = "匯入"
    override val DLG_BACKUP_COPY_HINT = "再點一次「匯出」即複製到剪貼簿。"

    override val TOAST_SAVED = "已儲存"
    override val TOAST_SAVE_FAILED = "儲存失敗，請重試"
    override val TOAST_COPIED = "已複製到剪貼簿"
    override val TOAST_PICK_FAILED = "讀取所選檔案失敗"
    override val TOAST_FONT_UNSUPPORTED = "非受支援的檔案"
    override val TOAST_PICK_CANCELED = "已取消選擇"
    override val TOAST_EXPORTED = "已匯出"
    override val TOAST_EXPORT_FAILED = "匯出失敗"
    override val TOAST_EXPORT_COPIED = "設定文字已複製到剪貼簿"
    override val TOAST_IMPORTED = "匯入成功"
    override val TOAST_IMPORT_EMPTY = "剪貼簿裡沒有設定文字"
    override val TOAST_IMPORT_CHECKSUM = "驗證失敗，設定未被覆寫"
    override val TOAST_IMPORT_FORMAT = "格式不符，設定未被覆寫"
    override val TOAST_REQUIRES_HOST = "請先開啟 DLsiteSound 再試"
    override val TOAST_RESERVED = "待開發功能，請等待更新"
    override val TAG_WIP = "待開發"
    override val TAG_EXPERIMENTAL = "實驗性"
    override val TOAST_DEFAULT_RESTORED = "已還原宿主預設"
    override val TOAST_BG_RESTORED = "已還原為宿主預設背景"
    override val TOAST_ICON_HIDDEN = "桌面圖示已隱藏"
    override val TOAST_ICON_SHOWN = "桌面圖示已還原"
    override val TOAST_ICON_HIDE_FAILED = "修改桌面圖示失敗"
    override val TOAST_DELETED = "已刪除"

}

/** English. */
internal object En : MoriStrings() {
    override val APP_TITLE = "Mori MOD"
    override val HOME_SUBTITLE = "DLsiteSound experience enhancer"
    override val BACK = "Back"

    override val STATUS_TITLE = "Status"
    override val STATUS_PLUGIN = "Module version"
    override val STATUS_HOST = "DLsiteSound version"
    override val STATUS_RUNTIME = "Runtime"
    override val STATUS_CHECKING = "Checking…"
    override val STATUS_ACTIVE = "Running"
    override val STATUS_INACTIVE = "Not running"
    override val STATUS_HOST_MISSING = "Not installed"

    override val SEC_FEATURES = "Features"
    override val SEC_BEAUTY = "Appearance"
    override val SEC_OPTIMIZE = "Tweaks"
    override val SEC_MANAGE = "Module"

    override val ITEM_BACKGROUND = "Background"
    override val ITEM_FONT = "Font"
    override val ITEM_LAYOUT = "Home layout"
    override val ITEM_COVER_BLOCK = "Hide work covers"
    override val ITEM_COVER_BLOCK_SUM = "Hide cover art everywhere; the area stays tappable"
    override val ITEM_PERF = "Stop title scrolling"
    override val ITEM_PERF_SUM = "Experimental. Track and work titles stay still on the player"
    override val ITEM_SUBTITLE = "Subtitles"
    override val F6_TITLE = "Subtitle settings"
    override val F6_ENABLE = "Enable subtitle adjustments"
    override val F6_ENABLE_SUM = "When off, size and background transparency do nothing"
    override val ITEM_SUBTITLE_SIZE = "Subtitle size"
    override val ITEM_SUBTITLE_SIZE_SUM = "Host player subtitle scale, ×1.0–1.5"
    override val ITEM_SUBTITLE_BG_CLEAR = "Transparent subtitle background"
    override val ITEM_SUBTITLE_BG_CLEAR_SUM = "On: clear the bar behind subtitles. Off: keep purple/accent bar"
    override val ITEM_HIDE_ICON = "Hide launcher icon"
    override val ITEM_DEBUG_LOG = "Debug log"
    override val ITEM_DEBUG_LOG_SUM = "Off by default; writes to the LSPosed log"
    override val ITEM_BACKUP = "Export & backup"
    override val ITEM_BACKUP_SUM = "Export config text, or restore from a backup"
    override val ITEM_ABOUT = "About"

    override val F2_TITLE = "App background"
    override val F2_ENABLE = "Enable"
    override val F2_CARD = "App background replacement"
    override val F2_BLUR = "Main background blur"
    override val F2_PLAY_BLUR = "Player background blur"
    override val F2_CHROME_BLUR = "Info chrome blur"
    override val F2_ACCENT = "Accent from wallpaper"
    override val F2_ACCENT_NOTE = "Tints every non-white text and all shadow overlays in the host with an accent colour extracted from your wallpaper. Turn off to restore the host defaults."
    override val F2_ACCENT_STYLE = "Accent style"
    override val F2_CURRENT_NONE = "Current: host default"
    override val F2_CURRENT = "Current background"
    override val F2_MY_BGS = "My backgrounds"
    override val F2_MY_BGS_NOTE = "jpg / png / webp: tap to apply, long-press to delete"
    override val F2_MY_BGS_EMPTY = "No background imported yet."
    override val CROP_TITLE = "Crop background"
    override val CROP_HINT = "Pinch to zoom, drag to move. The framed area fills the app background at screen ratio."
    override val BTN_CROP_SKIP = "Skip cropping"
    override val BTN_CROP_USE = "Use this area"
    override val TOAST_CROP_FAILED = "Crop failed — original image kept"
    override val F2_DELETE_BODY = "This removes the image from your library. If it is the current app background, the background is restored to the host default as well."

    override val F5_TITLE = "Home layout"
    override val F5_ENABLE = "Enable home layout tweaks"
    override val F5_NOTE = "This only affects the host home screen while you are signed in."
    override val F5_MODULES = "Home sections"
    override val F5_RESTORE = "Restore default layout"
    override val F5_MOVE_UP = "Move up"
    override val F5_MOVE_DOWN = "Move down"
    override val F5_MOD_GENRE = "Genres"
    override val F5_MOD_RECENT_PURCHASE = "Recently Purchased"
    override val F5_MOD_RECENT_PLAYED = "Recently Listened"
    override val F5_MOD_MY_SOUND = "Listen Later"
    override val F5_MOD_RECAP = "PlayBack"
    override val F5_MOD_SYNC = "Sync purchased works"
    override val F5_MOD_UNKNOWN = "Unknown section"
    override val TOAST_F5_NEED_LOGIN = "DLsiteSound is not signed in. This feature is unavailable."


    override val F4_TITLE = "App font"
    override val F4_CARD = "Enable font replacement"
    override val F4_CURRENT = "Current font"
    override val F4_NONE = "(not set)"
    override val F4_SYSTEM_FONTS = "System fonts"
    override val F4_RISK_NOTE = "If text looks wrong afterwards, restart DLsiteSound to restore normal display."
    override val F4_NOTE_NOTO = "Bundled fonts are tuned for Simplified Chinese reading; other locales may show variant glyphs or missing characters."
    override val F4_MY_FONTS = "My fonts"
    override val F4_MY_FONTS_NOTE = "Import a font file with the button below (.ttf / .otf only). Tap an item to apply it; long-press to delete."
    override val F4_MY_FONTS_EMPTY = "No imported font files yet."
    override val F4_DELETE_TITLE = "Delete font"
    override val F4_DELETE_BODY = "This removes the module's copy of the font (your original file is untouched). If it is the current font, replacement turns off too."
    override val BTN_DELETE = "Delete"
    override val TOAST_FONT_DELETED = "Font deleted"
    override val F4_BUNDLED = "Bundled fonts"
    override val F4_NOOP = "That font matches the host default, so nothing will change. Pick another file or family."
    override val F4_BUNDLED_NOTE = "Both bundled fonts are SIL OFL 1.1: commercial use is allowed, and so is redistribution together with this module (keep the copyright notice and license — see About → Bundled font licenses). DingTalk JinBuTi and Alibaba PuHuiTi grant usage only, not redistribution of the font files, so they are not bundled; import them manually if needed."
    override val ABOUT_FONTS = "Bundled font licenses"
    override val BTN_VIEW_OFL = "View full OFL 1.1"
    override val F4_PREVIEW = "Preview"

    override val ABOUT_TITLE = "About"
    override val ABOUT_SUBTITLE = "DLsiteSound experience enhancer"
    override val ABOUT_VERSION = "Module version"
    override val ABOUT_CODE = "Build"
    override val ABOUT_PACKAGE = "Package"
    override val ABOUT_HOST = "Host package"
    override val ABOUT_HOST_VERSION = "Host version"
    override val ABOUT_HOST_NOT_INSTALLED = "Not installed"
    override val ABOUT_COLOR_SOURCE = "Color source"
    override val ABOUT_COLOR_DYNAMIC = "System dynamic color"
    override val ABOUT_COLOR_FALLBACK = "Built-in Mono fallback"
    override val ABOUT_LICENSE = "License"
    override val ABOUT_FEEDBACK_SUM = "Please attach the LSPosed log when reporting issues."
    override val ABOUT_WHY_TITLE = "Why MoriMod?"
    override val ABOUT_WHY_BODY = "I wanted to replace the home / player background with images I like.\n\n" +
        "I wanted a typeface that is comfortable to read.\n\n" +
        "This module was made just for fun — if it improves your experience even a little, I would be delighted.\n\n" +
        "Thank you for downloading and using it. Enjoy every track."
    override val ABOUT_DISCLAIMER_TITLE = "Disclaimer"
    override val ABOUT_DISCLAIMER_BODY = "For personal study, research, testing and private use only. Do not use it for anything illegal, for infringing the rights of others, or for disrupting the platform's normal service.\n\n" +
        "This module is AI-assisted. It is only guaranteed that no deliberate malicious input or malicious behaviour by a human was included during development; AI-generated code may not fully match the intent, so please verify before use.\n\n" +
        "This is an independently developed third-party tool. It has no affiliation or partnership with DLsiteSound (eisys Inc.) or any phone manufacturer.\n\n" +
        "Using this module requires Root and an Xposed framework. It may void the warranty of some devices or violate the security policy of some ROMs, and may cause instability, loss of warranty or security risks. You bear all such risks.\n\n" +
        "Third-party modules may cause account anomalies, device instability or data loss at runtime. Back up important data first and test with non-critical accounts and scenarios.\n\n" +
        "Continued use means you acknowledge the risks above. If you do not agree, stop using it and uninstall the module."
    override val ABOUT_REPO_TITLE = "Project"
    override val BTN_OPEN_REPO = "Open"
    override val BTN_COPY_LINK = "Copy link"

    override val COMMON_ENABLE = "Enable"
    override val BTN_RESTORE = "Restore"
    override val BTN_CLEAR = "Clear list"
    override val BTN_EXPORT = "Export list"
    override val BTN_PICK_IMAGE = "Choose image"
    override val BTN_PICK_FONT = "Choose font file"
    override val BTN_USE_SYSTEM_FONT = "Use system font"
    override val BTN_COPY_INFO = "Copy version info"
    override val BTN_OK = "OK"
    override val BTN_CANCEL = "Cancel"

    override val DLG_ADD_NAME = "Add names"
    override val DLG_ADD_NAME_HINT = "One per line; batch import supported"
    override val DLG_FIELD_TEXT = "Text"
    override val DLG_CLEAR_TITLE = "Clear this list?"
    override val DLG_CLEAR_TEXT = "Every entry in this list will be deleted permanently."
    override val DLG_DELETE_TITLE = "Delete this item?"
    override val DLG_DELETE_TEXT = "This cannot be undone."
    override val DLG_HIDE_ICON_TITLE = "Hide launcher icon?"
    override val DLG_HIDE_ICON_TEXT = "The launcher icon disappears and stays hidden after a reboot. To reopen this screen later: LSPosed manager → Modules → long-press this module → Launch."
    override val DLG_BACKUP_TITLE = "Export & backup"
    override val DLG_BACKUP_HINT = "Exports a single text blob. Import verifies its checksum and never overwrites a valid config on failure."
    override val DLG_BACKUP_EXPORT = "Export"
    override val DLG_BACKUP_IMPORT = "Import"
    override val DLG_BACKUP_COPY_HINT = "Tap “Export” again to copy to the clipboard."

    override val TOAST_SAVED = "Saved"
    override val TOAST_SAVE_FAILED = "Save failed, please retry"
    override val TOAST_COPIED = "Copied to clipboard"
    override val TOAST_PICK_FAILED = "Could not read the selected file"
    override val TOAST_FONT_UNSUPPORTED = "Unsupported file"
    override val TOAST_PICK_CANCELED = "Selection cancelled"
    override val TOAST_EXPORTED = "Exported"
    override val TOAST_EXPORT_FAILED = "Export failed"
    override val TOAST_EXPORT_COPIED = "Config text copied to the clipboard"
    override val TOAST_IMPORTED = "Imported"
    override val TOAST_IMPORT_EMPTY = "No config text on the clipboard"
    override val TOAST_IMPORT_CHECKSUM = "Checksum failed — config left untouched"
    override val TOAST_IMPORT_FORMAT = "Wrong format — config left untouched"
    override val TOAST_REQUIRES_HOST = "Open DLsiteSound first"
    override val TOAST_RESERVED = "Not available yet — please wait for an update"
    override val TAG_WIP = "Coming soon"
    override val TAG_EXPERIMENTAL = "BETA"
    override val TOAST_DEFAULT_RESTORED = "Restored host default"
    override val TOAST_BG_RESTORED = "Restored host default background"
    override val TOAST_ICON_HIDDEN = "Launcher icon hidden"
    override val TOAST_ICON_SHOWN = "Launcher icon restored"
    override val TOAST_ICON_HIDE_FAILED = "Could not change the launcher icon"
    override val TOAST_DELETED = "Deleted"

}

internal object Ja : MoriStrings() {
    override val APP_TITLE = "Mori MOD"
    override val HOME_SUBTITLE = "DLsiteSound experience enhancer"
    override val BACK = "戻る"

    override val STATUS_TITLE = "Status"
    override val STATUS_PLUGIN = "Module version"
    override val STATUS_HOST = "DLsiteSound version"
    override val STATUS_RUNTIME = "Runtime"
    override val STATUS_CHECKING = "Checking…"
    override val STATUS_ACTIVE = "Running"
    override val STATUS_INACTIVE = "Not running"
    override val STATUS_HOST_MISSING = "Not installed"

    override val SEC_FEATURES = "機能設定"
    override val SEC_BEAUTY = "美化"
    override val SEC_OPTIMIZE = "最適化"
    override val SEC_MANAGE = "モジュール管理"

    override val ITEM_BACKGROUND = "背景カスタム"
    override val ITEM_FONT = "フォント置換"
    override val ITEM_LAYOUT = "ホームレイアウト"
    override val ITEM_COVER_BLOCK = "作品カバーを非表示"
    override val ITEM_COVER_BLOCK_SUM = "全画面で作品カバーを非表示（タップは可能）"
    override val ITEM_PERF = "曲名・作品名のスクロールを停止"
    override val ITEM_PERF_SUM = "実験的。再生画面の曲名・作品名を固定"
    override val ITEM_SUBTITLE = "字幕調整"
    override val F6_TITLE = "字幕調整"
    override val F6_ENABLE = "字幕調整を有効化"
    override val F6_ENABLE_SUM = "オフのとき字幕サイズと背景透明は効きません"
    override val ITEM_SUBTITLE_SIZE = "字幕サイズ"
    override val ITEM_SUBTITLE_SIZE_SUM = "再生画面の字幕倍率、×1.0–1.5"
    override val ITEM_SUBTITLE_BG_CLEAR = "字幕背景を透明に"
    override val ITEM_SUBTITLE_BG_CLEAR_SUM = "オンで字幕バーの地色を消す。オフで紫／アクセント色の地を残す"
    override val ITEM_HIDE_ICON = "ランチャーアイコンを非表示"
    override val ITEM_DEBUG_LOG = "デバッグログ"
    override val ITEM_DEBUG_LOG_SUM = "既定オフ。LSPosedログに出力"
    override val ITEM_BACKUP = "エクスポートとバックアップ"
    override val ITEM_BACKUP_SUM = "設定テキストの書き出し／バックアップから復元"
    override val ITEM_ABOUT = "情報"

    override val F2_TITLE = "アプリ背景"
    override val F2_ENABLE = "有効化"
    override val F2_CARD = "アプリ背景の差し替え"
    override val F2_BLUR = "メイン背景ぼかし"
    override val F2_PLAY_BLUR = "再生画面の背景ぼかし"
    override val F2_CHROME_BLUR = "情報UI背景ぼかし"
    override val F2_ACCENT = "壁紙からアクセント色"
    override val F2_ACCENT_NOTE = "Tints every non-white text and all shadow overlays in the host with an accent colour extracted from your wallpaper. Turn off to restore the host defaults."
    override val F2_ACCENT_STYLE = "Accent style"
    override val F2_CURRENT_NONE = "Current: host default"
    override val F2_CURRENT = "Current background"
    override val F2_MY_BGS = "My backgrounds"
    override val F2_MY_BGS_NOTE = "jpg / png / webp: tap to apply, long-press to delete"
    override val F2_MY_BGS_EMPTY = "No background imported yet."
    override val CROP_TITLE = "Crop background"
    override val CROP_HINT = "Pinch to zoom, drag to move. The framed area fills the app background at screen ratio."
    override val BTN_CROP_SKIP = "Skip cropping"
    override val BTN_CROP_USE = "Use this area"
    override val TOAST_CROP_FAILED = "Crop failed — original image kept"
    override val F2_DELETE_BODY = "This removes the image from your library. If it is the current app background, the background is restored to the host default as well."

    override val F5_TITLE = "ホームレイアウト"
    override val F5_ENABLE = "ホームレイアウト調整を有効化"
    override val F5_NOTE = "この機能はログイン中のホストホーム画面にのみ影響します。"
    override val F5_MODULES = "ホームセクション"
    override val F5_RESTORE = "デフォルトレイアウトに戻す"
    override val F5_MOVE_UP = "上へ"
    override val F5_MOVE_DOWN = "下へ"
    override val F5_MOD_GENRE = "ジャンル"
    override val F5_MOD_RECENT_PURCHASE = "最近の購入"
    override val F5_MOD_RECENT_PLAYED = "最近聴いた作品"
    override val F5_MOD_MY_SOUND = "あとで聴く"
    override val F5_MOD_RECAP = "プレイバック"
    override val F5_MOD_SYNC = "購入した作品を同期"
    override val F5_MOD_UNKNOWN = "不明なセクション"
    override val TOAST_F5_NEED_LOGIN = "DLsiteSoundにログインしていないため、この機能は使えません。"


    override val F4_TITLE = "アプリフォント"
    override val F4_CARD = "フォント置換を有効化"
    override val F4_CURRENT = "現在のフォント"
    override val F4_NONE = "（未設定）"
    override val F4_SYSTEM_FONTS = "システムフォント"
    override val F4_RISK_NOTE = "表示がおかしくなったら DLsiteSound を再起動してください。"
    override val F4_NOTE_NOTO = "同梱フォントは簡体字中国語向けです。他言語では異体字や欠字が出ることがあります。"
    override val F4_MY_FONTS = "My fonts"
    override val F4_MY_FONTS_NOTE = "Import a font file with the button below (.ttf / .otf only). Tap an item to apply it; long-press to delete."
    override val F4_MY_FONTS_EMPTY = "No imported font files yet."
    override val F4_DELETE_TITLE = "Delete font"
    override val F4_DELETE_BODY = "This removes the module's copy of the font (your original file is untouched). If it is the current font, replacement turns off too."
    override val BTN_DELETE = "Delete"
    override val TOAST_FONT_DELETED = "Font deleted"
    override val F4_BUNDLED = "Bundled fonts"
    override val F4_NOOP = "That font matches the host default, so nothing will change. Pick another file or family."
    override val F4_BUNDLED_NOTE = "Both bundled fonts are SIL OFL 1.1: commercial use is allowed, and so is redistribution together with this module (keep the copyright notice and license — see About → Bundled font licenses). DingTalk JinBuTi and Alibaba PuHuiTi grant usage only, not redistribution of the font files, so they are not bundled; import them manually if needed."
    override val ABOUT_FONTS = "同梱フォントのライセンス"
    override val BTN_VIEW_OFL = "OFL 1.1 全文を見る"
    override val F4_PREVIEW = "プレビュー"

    override val ABOUT_TITLE = "情報"
    override val ABOUT_SUBTITLE = "DLsiteSound 体験強化モジュール"
    override val ABOUT_VERSION = "モジュール版"
    override val ABOUT_CODE = "ビルド番号"
    override val ABOUT_PACKAGE = "パッケージ名"
    override val ABOUT_HOST = "ホストパッケージ"
    override val ABOUT_HOST_VERSION = "ホスト版"
    override val ABOUT_HOST_NOT_INSTALLED = "未インストール"
    override val ABOUT_COLOR_SOURCE = "配色の出典"
    override val ABOUT_COLOR_DYNAMIC = "システムダイナミックカラー"
    override val ABOUT_COLOR_FALLBACK = "内蔵 Mono フォールバック"
    override val ABOUT_LICENSE = "ライセンス"
    override val ABOUT_FEEDBACK_SUM = "不具合報告時は LSPosed ログを添付してください。"
    override val ABOUT_WHY_TITLE = "なぜ MoriMod を作ったのか？"
    override val ABOUT_WHY_BODY = "ホーム／再生画面の背景を、好きな画像に差し替えたかった。\n\n" +
        "読みやすいフォントに差し替えたかった。\n\n" +
        "作者は純粋に楽しむために作っていますが、少しでも体験が良くなれば嬉しいです。\n\n" +
        "ダウンロードとご利用に感謝します。すべての音声を楽しんでください。"
    override val ABOUT_DISCLAIMER_TITLE = "免責事項"
    override val ABOUT_DISCLAIMER_BODY = "個人の学習・研究・テスト・自用のみを目的とし、違法行為や他人の権利侵害、プラットフォームの正常なサービス妨害には用いないでください。\n\n" +
        "本モジュールは AI 支援で開発されています。開発過程で人為的な悪意入力・悪意行為を含めないことのみ保証し、AI 生成コードが意図と完全一致するとは限りません。使用前にご自身で確認してください。\n\n" +
        "本モジュールは独立開発のサードパーティツールであり、DLsiteSound（eisys 株式会社）および各端末メーカーと一切の所属・提携関係はありません。\n\n" +
        "利用には Root と Xposed フレームワークが必要で、一部端末の保証条項や ROM のセキュリティ方針に抵触する可能性があり、不安定化・保証失効・セキュリティリスクなどを招くことがあります。リスクは利用者自身が負担します。\n\n" +
        "サードパーティ改変モジュールはアカウント異常・端末不安定・データ損失などを招くことがあります。重要データをバックアップし、非重要アカウント／非重要場面で先にテストしてください。\n\n" +
        "継続利用は上記リスクを了承したものとみなします。同意しない場合は使用を停止し、モジュールをアンインストールしてください。"
    override val ABOUT_REPO_TITLE = "プロジェクト"
    override val BTN_OPEN_REPO = "開く"
    override val BTN_COPY_LINK = "リンクをコピー"

    override val COMMON_ENABLE = "有効化"
    override val BTN_RESTORE = "Restore"
    override val BTN_CLEAR = "Clear list"
    override val BTN_EXPORT = "Export list"
    override val BTN_PICK_IMAGE = "Choose image"
    override val BTN_PICK_FONT = "Choose font file"
    override val BTN_USE_SYSTEM_FONT = "Use system font"
    override val BTN_COPY_INFO = "Copy version info"
    override val BTN_OK = "OK"
    override val BTN_CANCEL = "Cancel"

    override val DLG_ADD_NAME = "Add names"
    override val DLG_ADD_NAME_HINT = "One per line; batch import supported"
    override val DLG_FIELD_TEXT = "Text"
    override val DLG_CLEAR_TITLE = "Clear this list?"
    override val DLG_CLEAR_TEXT = "Every entry in this list will be deleted permanently."
    override val DLG_DELETE_TITLE = "Delete this item?"
    override val DLG_DELETE_TEXT = "This cannot be undone."
    override val DLG_HIDE_ICON_TITLE = "Hide launcher icon?"
    override val DLG_HIDE_ICON_TEXT = "The launcher icon disappears and stays hidden after a reboot. To reopen this screen later: LSPosed manager → Modules → long-press this module → Launch."
    override val DLG_BACKUP_TITLE = "エクスポートとバックアップ"
    override val DLG_BACKUP_HINT = "Exports a single text blob. Import verifies its checksum and never overwrites a valid config on failure."
    override val DLG_BACKUP_EXPORT = "Export"
    override val DLG_BACKUP_IMPORT = "Import"
    override val DLG_BACKUP_COPY_HINT = "Tap “Export” again to copy to the clipboard."

    override val TOAST_SAVED = "保存しました"
    override val TOAST_SAVE_FAILED = "Save failed, please retry"
    override val TOAST_COPIED = "Copied to clipboard"
    override val TOAST_PICK_FAILED = "Could not read the selected file"
    override val TOAST_FONT_UNSUPPORTED = "Unsupported file"
    override val TOAST_PICK_CANCELED = "Selection cancelled"
    override val TOAST_EXPORTED = "Exported"
    override val TOAST_EXPORT_FAILED = "Export failed"
    override val TOAST_EXPORT_COPIED = "Config text copied to the clipboard"
    override val TOAST_IMPORTED = "Imported"
    override val TOAST_IMPORT_EMPTY = "No config text on the clipboard"
    override val TOAST_IMPORT_CHECKSUM = "Checksum failed — config left untouched"
    override val TOAST_IMPORT_FORMAT = "Wrong format — config left untouched"
    override val TOAST_REQUIRES_HOST = "Open DLsiteSound first"
    override val TOAST_RESERVED = "Not available yet — please wait for an update"
    override val TAG_WIP = "Coming soon"
    override val TAG_EXPERIMENTAL = "BETA"
    override val TOAST_DEFAULT_RESTORED = "Restored host default"
    override val TOAST_BG_RESTORED = "Restored host default background"
    override val TOAST_ICON_HIDDEN = "Launcher icon hidden"
    override val TOAST_ICON_SHOWN = "Launcher icon restored"
    override val TOAST_ICON_HIDE_FAILED = "Could not change the launcher icon"
    override val TOAST_DELETED = "Deleted"


}

internal object Ko : MoriStrings() {
    override val APP_TITLE = "Mori MOD"
    override val HOME_SUBTITLE = "DLsiteSound experience enhancer"
    override val BACK = "뒤로"

    override val STATUS_TITLE = "Status"
    override val STATUS_PLUGIN = "Module version"
    override val STATUS_HOST = "DLsiteSound version"
    override val STATUS_RUNTIME = "Runtime"
    override val STATUS_CHECKING = "Checking…"
    override val STATUS_ACTIVE = "Running"
    override val STATUS_INACTIVE = "Not running"
    override val STATUS_HOST_MISSING = "Not installed"

    override val SEC_FEATURES = "기능 설정"
    override val SEC_BEAUTY = "꾸미기"
    override val SEC_OPTIMIZE = "최적화"
    override val SEC_MANAGE = "모듈 관리"

    override val ITEM_BACKGROUND = "배경 사용자 지정"
    override val ITEM_FONT = "글꼴 바꾸기"
    override val ITEM_LAYOUT = "홈 레이아웃"
    override val ITEM_COVER_BLOCK = "작품 커버 숨기기"
    override val ITEM_COVER_BLOCK_SUM = "모든 화면에서 커버 숨김(탭은 가능)"
    override val ITEM_PERF = "곡명/작품명 스크롤 중지"
    override val ITEM_PERF_SUM = "실험적. 재생 화면 곡명·작품명 고정"
    override val ITEM_SUBTITLE = "자막 조정"
    override val F6_TITLE = "자막 조정"
    override val F6_ENABLE = "자막 조정 사용"
    override val F6_ENABLE_SUM = "끄면 자막 크기와 배경 투명이 적용되지 않습니다"
    override val ITEM_SUBTITLE_SIZE = "자막 크기"
    override val ITEM_SUBTITLE_SIZE_SUM = "재생 화면 자막 배율, ×1.0–1.5"
    override val ITEM_SUBTITLE_BG_CLEAR = "자막 배경 투명"
    override val ITEM_SUBTITLE_BG_CLEAR_SUM = "켜면 자막 바 배경이 투명해지고, 끄면 보라/강조색 배경을 유지합니다"
    override val ITEM_HIDE_ICON = "런처 아이콘 숨기기"
    override val ITEM_DEBUG_LOG = "디버그 로그"
    override val ITEM_DEBUG_LOG_SUM = "기본 꺼짐. LSPosed 로그에 기록"
    override val ITEM_BACKUP = "내보내기 및 백업"
    override val ITEM_BACKUP_SUM = "설정 텍스트 내보내기 / 백업에서 복원"
    override val ITEM_ABOUT = "정보"

    override val F2_TITLE = "앱 배경"
    override val F2_ENABLE = "사용"
    override val F2_CARD = "앱 배경 바꾸기"
    override val F2_BLUR = "메인 배경 흐림"
    override val F2_PLAY_BLUR = "재생 화면 배경 흐림"
    override val F2_CHROME_BLUR = "정보 UI 배경 흐림"
    override val F2_ACCENT = "벽지에서 강조색"
    override val F2_ACCENT_NOTE = "Tints every non-white text and all shadow overlays in the host with an accent colour extracted from your wallpaper. Turn off to restore the host defaults."
    override val F2_ACCENT_STYLE = "Accent style"
    override val F2_CURRENT_NONE = "Current: host default"
    override val F2_CURRENT = "Current background"
    override val F2_MY_BGS = "My backgrounds"
    override val F2_MY_BGS_NOTE = "jpg / png / webp: tap to apply, long-press to delete"
    override val F2_MY_BGS_EMPTY = "No background imported yet."
    override val CROP_TITLE = "Crop background"
    override val CROP_HINT = "Pinch to zoom, drag to move. The framed area fills the app background at screen ratio."
    override val BTN_CROP_SKIP = "Skip cropping"
    override val BTN_CROP_USE = "Use this area"
    override val TOAST_CROP_FAILED = "Crop failed — original image kept"
    override val F2_DELETE_BODY = "This removes the image from your library. If it is the current app background, the background is restored to the host default as well."

    override val F5_TITLE = "홈 레이아웃"
    override val F5_ENABLE = "홈 레이아웃 조정 사용"
    override val F5_NOTE = "로그인한 상태의 호스트 홈 화면에만 적용됩니다."
    override val F5_MODULES = "홈 섹션"
    override val F5_RESTORE = "기본 레이아웃으로 복원"
    override val F5_MOVE_UP = "위로"
    override val F5_MOVE_DOWN = "아래로"
    override val F5_MOD_GENRE = "장르"
    override val F5_MOD_RECENT_PURCHASE = "최근 구입"
    override val F5_MOD_RECENT_PLAYED = "최근 들은 작품"
    override val F5_MOD_MY_SOUND = "나중에 듣기"
    override val F5_MOD_RECAP = "PlayBack"
    override val F5_MOD_SYNC = "구입한 작품 동기화"
    override val F5_MOD_UNKNOWN = "알 수 없는 섹션"
    override val TOAST_F5_NEED_LOGIN = "DLsiteSound에 로그인되어 있지 않아 이 기능을 사용할 수 없습니다."


    override val F4_TITLE = "앱 글꼴"
    override val F4_CARD = "글꼴 바꾸기 사용"
    override val F4_CURRENT = "현재 글꼴"
    override val F4_NONE = "(설정 안 됨)"
    override val F4_SYSTEM_FONTS = "시스템 글꼴"
    override val F4_RISK_NOTE = "표시가 이상하면 DLsiteSound를 다시 시작하면 정상으로 돌아갑니다."
    override val F4_NOTE_NOTO = "내장 글꼴은 간체 중국어 환경에 맞춰져 있으며, 다른 언어에서는 이체자나 결자가 나타날 수 있습니다."
    override val F4_MY_FONTS = "My fonts"
    override val F4_MY_FONTS_NOTE = "Import a font file with the button below (.ttf / .otf only). Tap an item to apply it; long-press to delete."
    override val F4_MY_FONTS_EMPTY = "No imported font files yet."
    override val F4_DELETE_TITLE = "Delete font"
    override val F4_DELETE_BODY = "This removes the module's copy of the font (your original file is untouched). If it is the current font, replacement turns off too."
    override val BTN_DELETE = "Delete"
    override val TOAST_FONT_DELETED = "Font deleted"
    override val F4_BUNDLED = "Bundled fonts"
    override val F4_NOOP = "That font matches the host default, so nothing will change. Pick another file or family."
    override val F4_BUNDLED_NOTE = "Both bundled fonts are SIL OFL 1.1: commercial use is allowed, and so is redistribution together with this module (keep the copyright notice and license — see About → Bundled font licenses). DingTalk JinBuTi and Alibaba PuHuiTi grant usage only, not redistribution of the font files, so they are not bundled; import them manually if needed."
    override val ABOUT_FONTS = "내장 글꼴 라이선스"
    override val BTN_VIEW_OFL = "OFL 1.1 전문 보기"
    override val F4_PREVIEW = "미리보기"

    override val ABOUT_TITLE = "정보"
    override val ABOUT_SUBTITLE = "DLsiteSound 경험 향상 모듈"
    override val ABOUT_VERSION = "모듈 버전"
    override val ABOUT_CODE = "빌드 번호"
    override val ABOUT_PACKAGE = "패키지 이름"
    override val ABOUT_HOST = "호스트 패키지"
    override val ABOUT_HOST_VERSION = "호스트 버전"
    override val ABOUT_HOST_NOT_INSTALLED = "미설치"
    override val ABOUT_COLOR_SOURCE = "배색 출처"
    override val ABOUT_COLOR_DYNAMIC = "시스템 다이나믹 컬러"
    override val ABOUT_COLOR_FALLBACK = "내장 Mono 대체 배색"
    override val ABOUT_LICENSE = "라이선스"
    override val ABOUT_FEEDBACK_SUM = "문제 제보 시 LSPosed 로그를 첨부해 주세요."
    override val ABOUT_WHY_TITLE = "왜 MoriMod를 만들었나요?"
    override val ABOUT_WHY_BODY = "홈/재생 화면 배경을 내가 좋아하는 이미지로 바꾸고 싶었습니다.\n\n" +
        "읽기 편한 글꼴로 바꾸고 싶었습니다.\n\n" +
        "작가는 순전히 재미로 만들었지만, 사용 경험이 조금이나마 나아진다면 기쁠 것입니다.\n\n" +
        "다운로드와 이용에 감사드리며, 모든 오디오를 즐기시길 바랍니다."
    override val ABOUT_DISCLAIMER_TITLE = "면책 조항"
    override val ABOUT_DISCLAIMER_BODY = "개인 학습·연구·테스트·자용 목적으로만 사용하며, 위법 행위나 타인 권리 침해, 플랫폼 정상 서비스 방해에는 사용하지 마세요.\n\n" +
        "본 모듈은 AI 보조로 개발되었습니다. 개발 과정에서 인위적 악의 입력·악의 행위가 포함되지 않았음만 보장하며, AI 생성 코드가 의도와 완전히 일치한다고 보장하지 않습니다. 사용 전 직접 확인해 주세요.\n\n" +
        "본 모듈은 독립 개발된 서드파티 도구이며, DLsiteSound(eisys 주식회사) 및 각 단말기 제조사와 어떠한 소속·제휴 관계도 없습니다.\n\n" +
        "사용에는 Root와 Xposed 프레임워크가 필요하며, 일부 기기 보증 조항이나 ROM 보안 정책에 위배될 수 있고, 불안정·보증 상실·보안 위험 등을 초래할 수 있습니다. 위험은 사용자 본인이 부담합니다.\n\n" +
        "서드파티 개조 모듈은 계정 이상·기기 불안정·데이터 손실 등을 초래할 수 있습니다. 중요 데이터를 백업하고, 비중요 계정/비중요 상황에서 먼저 테스트하세요.\n\n" +
        "계속 사용하면 위 위험을 인지한 것으로 간주합니다. 동의하지 않으면 사용을 중단하고 모듈을 제거하세요."
    override val ABOUT_REPO_TITLE = "프로젝트"
    override val BTN_OPEN_REPO = "열기"
    override val BTN_COPY_LINK = "링크 복사"

    override val COMMON_ENABLE = "사용"
    override val BTN_RESTORE = "Restore"
    override val BTN_CLEAR = "Clear list"
    override val BTN_EXPORT = "Export list"
    override val BTN_PICK_IMAGE = "Choose image"
    override val BTN_PICK_FONT = "Choose font file"
    override val BTN_USE_SYSTEM_FONT = "Use system font"
    override val BTN_COPY_INFO = "Copy version info"
    override val BTN_OK = "OK"
    override val BTN_CANCEL = "Cancel"

    override val DLG_ADD_NAME = "Add names"
    override val DLG_ADD_NAME_HINT = "One per line; batch import supported"
    override val DLG_FIELD_TEXT = "Text"
    override val DLG_CLEAR_TITLE = "Clear this list?"
    override val DLG_CLEAR_TEXT = "Every entry in this list will be deleted permanently."
    override val DLG_DELETE_TITLE = "Delete this item?"
    override val DLG_DELETE_TEXT = "This cannot be undone."
    override val DLG_HIDE_ICON_TITLE = "Hide launcher icon?"
    override val DLG_HIDE_ICON_TEXT = "The launcher icon disappears and stays hidden after a reboot. To reopen this screen later: LSPosed manager → Modules → long-press this module → Launch."
    override val DLG_BACKUP_TITLE = "내보내기 및 백업"
    override val DLG_BACKUP_HINT = "Exports a single text blob. Import verifies its checksum and never overwrites a valid config on failure."
    override val DLG_BACKUP_EXPORT = "Export"
    override val DLG_BACKUP_IMPORT = "Import"
    override val DLG_BACKUP_COPY_HINT = "Tap “Export” again to copy to the clipboard."

    override val TOAST_SAVED = "저장됨"
    override val TOAST_SAVE_FAILED = "Save failed, please retry"
    override val TOAST_COPIED = "Copied to clipboard"
    override val TOAST_PICK_FAILED = "Could not read the selected file"
    override val TOAST_FONT_UNSUPPORTED = "Unsupported file"
    override val TOAST_PICK_CANCELED = "Selection cancelled"
    override val TOAST_EXPORTED = "Exported"
    override val TOAST_EXPORT_FAILED = "Export failed"
    override val TOAST_EXPORT_COPIED = "Config text copied to the clipboard"
    override val TOAST_IMPORTED = "Imported"
    override val TOAST_IMPORT_EMPTY = "No config text on the clipboard"
    override val TOAST_IMPORT_CHECKSUM = "Checksum failed — config left untouched"
    override val TOAST_IMPORT_FORMAT = "Wrong format — config left untouched"
    override val TOAST_REQUIRES_HOST = "Open DLsiteSound first"
    override val TOAST_RESERVED = "Not available yet — please wait for an update"
    override val TAG_WIP = "Coming soon"
    override val TAG_EXPERIMENTAL = "BETA"
    override val TOAST_DEFAULT_RESTORED = "Restored host default"
    override val TOAST_BG_RESTORED = "Restored host default background"
    override val TOAST_ICON_HIDDEN = "Launcher icon hidden"
    override val TOAST_ICON_SHOWN = "Launcher icon restored"
    override val TOAST_ICON_HIDE_FAILED = "Could not change the launcher icon"
    override val TOAST_DELETED = "Deleted"


}

/**
 * 当前语言表格。
 *
 * 用 `mutableStateOf` 让语言切换**触发重组**；
 * [S] 的所有成员都是 `get()` 转发到这里，故调用点写法保持 `S.XXX` 不变。
 */
internal object Strings {
    var current: MoriStrings by mutableStateOf(ZhCn)
        private set

    /** 按语言代码切换（幂等）。 */
    fun apply(lang: Lang) {
        current = when (lang) {
            Lang.ZH_CN -> ZhCn
            Lang.ZH_TW -> ZhTw
            Lang.EN -> En
            Lang.JA -> Ja
            Lang.KO -> Ko
        }
    }
}

/** 格式化辅助：`%1\$d / %2\$d` 这类占位在本模块用最简单的方式替换。 */
internal fun MoriStrings.fmt(template: String, vararg args: Any): String =
    String.format(template, *args)
