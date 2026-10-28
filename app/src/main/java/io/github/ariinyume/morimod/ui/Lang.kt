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
    EN("en", "EN");

    /** 下一个语言（按钮循环切换）。 */
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
    abstract val SEC_MANAGE: String

    abstract val ITEM_MEDIA: String
    abstract val ITEM_MEDIA_SUM: String
    abstract val ITEM_BACKGROUND: String
    abstract val ITEM_GLASS: String
    abstract val ITEM_FONT: String
    abstract val ITEM_LAYOUT: String
    abstract val ITEM_HIDE_ICON: String
    abstract val ITEM_DEBUG_LOG: String
    abstract val ITEM_DEBUG_LOG_SUM: String
    abstract val ITEM_BACKUP: String
    abstract val ITEM_BACKUP_SUM: String
    abstract val ITEM_ABOUT: String

    // ── F1 播放控件 ────────────────────────────────────────────────
    abstract val F1_TITLE: String
    abstract val F1_ENABLE: String
    abstract val F1_CARD_COVER: String
    abstract val F1_CARD_COVER_SUM: String
    abstract val F1_CARD_TRACK: String
    abstract val F1_CARD_ARTIST: String
    abstract val F1_CARD_NAME_SUM: String
    abstract val F1_EMPTY_COVER: String
    abstract val F1_EMPTY_NAME: String
    abstract val F1_ENABLED_COUNT: String

    // ── F2 背景 ────────────────────────────────────────────────────
    abstract val F2_TITLE: String
    abstract val F2_ENABLE: String
    abstract val F2_CARD: String
    abstract val F2_BLUR: String

    /** 播放页背景模糊（v0.1.21，取代已删除的「遮罩浓度」）。 */
    abstract val F2_PLAY_BLUR: String
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
    abstract val F2_DESKTOP_NOTE: String

    /** 裁切页（v0.1.11）：选完图先按屏幕比例取景，再落成背景。 */
    abstract val CROP_TITLE: String
    abstract val CROP_HINT: String
    abstract val BTN_CROP_SKIP: String
    abstract val BTN_CROP_USE: String
    abstract val TOAST_CROP_FAILED: String

    /** 长按删除背景图的确认文案。 */
    abstract val F2_DELETE_BODY: String

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

    abstract val BTN_ADD_IMAGE: String
    abstract val BTN_ADD_NAME: String
    abstract val BTN_RESTORE: String
    abstract val BTN_CLEAR: String
    abstract val BTN_EXPORT: String
    abstract val BTN_PICK_IMAGE: String
    /** 「桌面壁纸」（v0.1.10 取代旧的「全透明背景」按钮）。 */
    abstract val BTN_DESKTOP_WALL: String
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
    abstract val TOAST_POOL_RESTORED: String
    abstract val TOAST_POOL_CLEARED: String
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
    abstract val TOAST_GLASS_SAVED: String
    abstract val TOAST_DEFAULT_RESTORED: String
    abstract val TOAST_DESKTOP_WALL: String
    abstract val TOAST_WALLPAPER_FAILED: String
    abstract val TOAST_WALLPAPER_DENIED: String
    /** 删掉了正在使用的背景图 ⇒ 已自动恢复宿主默认。 */
    abstract val TOAST_BG_RESTORED: String
    abstract val TOAST_NO_SELECTION: String
    abstract val TOAST_ICON_HIDDEN: String
    abstract val TOAST_ICON_SHOWN: String
    abstract val TOAST_ICON_HIDE_FAILED: String
    abstract val TOAST_ADDED: String
    abstract val TOAST_DELETED: String

    // ── 导出文件名 ─────────────────────────────────────────────────
    abstract val EXPORT_FILE_TRACK: String
    abstract val EXPORT_FILE_ARTIST: String
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
    override val STATUS_ACTIVE = "已注入宿主"
    override val STATUS_INACTIVE = "未运行"
    override val STATUS_HOST_MISSING = "未安装"

    override val SEC_FEATURES = "功能设置"
    override val SEC_MANAGE = "模块管理"

    override val ITEM_MEDIA = "系统播放控件自定义"
    override val ITEM_MEDIA_SUM = "仅针对通知栏 / 锁屏进行替换"
    override val ITEM_BACKGROUND = "主程序背景自定义"
    override val ITEM_GLASS = "主程序液态玻璃主题"
    override val ITEM_FONT = "主程序字体替换"
    override val ITEM_LAYOUT = "主页模块布局调整"
    override val ITEM_HIDE_ICON = "隐藏模块桌面图标"
    override val ITEM_DEBUG_LOG = "调试日志"
    override val ITEM_DEBUG_LOG_SUM = "默认关闭，打开后写入 LSPosed 日志"
    override val ITEM_BACKUP = "导出与备份"
    override val ITEM_BACKUP_SUM = "导出配置文本，或从备份还原"
    override val ITEM_ABOUT = "关于"

    override val F1_TITLE = "系统播放控件自定义"
    override val F1_ENABLE = "启用替换"
    override val F1_CARD_COVER = "替换封面图"
    override val F1_CARD_COVER_SUM = "以矩阵形式摆出已经添加的图片，点选应用图片"
    override val F1_CARD_TRACK = "替换音轨名"
    override val F1_CARD_ARTIST = "替换社团名"
    override val F1_CARD_NAME_SUM = "以列表形式出现已添加的名称，支持批量导入"
    override val F1_EMPTY_COVER = "还没有图片，点「添加图片」选一张"
    override val F1_EMPTY_NAME = "还没有名称，点「添加名称」批量导入"
    override val F1_ENABLED_COUNT = "已启用 %1\$d / %2\$d"

    override val F2_TITLE = "主程序背景自定义"
    override val F2_ENABLE = "启用替换"
    override val F2_CARD = "主程序背景替换"
    override val F2_BLUR = "背景模糊"
    override val F2_PLAY_BLUR = "播放页背景模糊"
    override val F2_ACCENT = "强调色跟自定义背景取色"
    override val F2_ACCENT_NOTE = "开启后，宿主里除白色以外的文字与所有阴影蒙层都会跟随壁纸取出的强调色；关掉即还原宿主默认。"
    override val F2_ACCENT_STYLE = "强调色风格"
    override val F2_CURRENT_NONE = "当前背景：宿主默认"
    override val F2_CURRENT = "当前背景"
    override val F2_MY_BGS = "我的背景"
    override val F2_MY_BGS_NOTE = "支持 jpg/png/webp：点一项即可换用，长按删除"
    override val F2_MY_BGS_EMPTY = "还没有导入过背景图。"
    override val F2_DESKTOP_NOTE = "把宿主背景换成你系统当前的桌面壁纸"
    override val CROP_TITLE = "裁剪背景"
    override val CROP_HINT = "双指缩放、拖动调整位置；框内这块会按屏幕比例铺满宿主背景"
    override val BTN_CROP_SKIP = "跳过裁剪"
    override val BTN_CROP_USE = "使用这块"
    override val TOAST_CROP_FAILED = "裁剪失败，已保留原图"
    override val F2_DELETE_BODY = "会从图库里删掉这张图。如果它正被用作宿主背景，背景会一并恢复成宿主默认。"

    override val F4_TITLE = "主程序字体替换"
    override val F4_CARD = "启用字体替换"
    override val F4_CURRENT = "当前字体"
    override val F4_NONE = "（未设置素材）"
    override val F4_SYSTEM_FONTS = "系统字体"
    override val F4_RISK_NOTE = "改动后如字体显示异常，重启 DLsiteSound 即可正常显示"
    override val F4_NOTE_NOTO = "DLsiteSound 默认字体为 Noto Sans JP，与 思源黑体 是同一套字形，插件内置的 思源黑体 SC 更聚焦简体中文显示"
    override val F4_MY_FONTS = "用户导入字体"
    override val F4_MY_FONTS_NOTE = "支持 .ttf/.otf：点一项即可换用，长按删除"
    override val F4_MY_FONTS_EMPTY = "还没有导入过字体文件。"
    override val F4_DELETE_TITLE = "删除字体"
    override val F4_DELETE_BODY = "会从模块目录里删掉这份字体副本（不影响你原来的文件）。如果它正是当前字体，替换会同时关闭。"
    override val BTN_DELETE = "删除"
    override val TOAST_FONT_DELETED = "字体已删除"
    override val F4_BUNDLED = "插件内置字体"
    override val F4_NOOP = "所选字体与宿主默认一致，替换后看不出变化；请换一个字体文件或其它族。"
    override val F4_BUNDLED_NOTE = "随包内置的两款均为 SIL OFL 1.1：允许商用，也允许随本模块再分发（需保留版权声明与许可证，见「关于 → 内置字体授权」）。钉钉进步体、阿里巴巴普惠体只授权「使用」、不允许再分发字体文件，故未内置，需要时请自行选择文件导入。"
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
    override val ABOUT_WHY_BODY = "看厌了紫色背景，想要把主页 / 播放页背景换成自己喜欢的图片，以便视觉上完全归于自己。\n\n" +
        "自带的字体 Noto San JP 在简体中文环境下经常显示字形异常，想要把应用字体换成喜欢的字体并调节字幕显示大小，以便长时间阅读更舒适。\n\n" +
        "想让界面更精致。\n\n" +
        "质感、顺手、可控，就是作者制作这个模块的原动力。\n\n" +
        "最后，感谢你的下载与使用，愿你享受每一段音频。"
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
    override val BTN_ADD_IMAGE = "添加图片"
    override val BTN_ADD_NAME = "添加名称"
    override val BTN_RESTORE = "恢复默认"
    override val BTN_CLEAR = "清空列表"
    override val BTN_EXPORT = "导出列表"
    override val BTN_PICK_IMAGE = "选择图片"
    override val BTN_DESKTOP_WALL = "桌面壁纸"
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
    override val TOAST_POOL_RESTORED = "已恢复默认显示"
    override val TOAST_POOL_CLEARED = "列表已清空"
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
    override val TOAST_GLASS_SAVED = "液态玻璃为第二批功能，偏好已记录"
    override val TOAST_DEFAULT_RESTORED = "已恢复宿主默认"
    override val TOAST_DESKTOP_WALL = "已用当前桌面壁纸作背景"
    override val TOAST_WALLPAPER_FAILED = "读不到桌面壁纸：请先在系统里设置一张壁纸"
    override val TOAST_WALLPAPER_DENIED = "需要「图片」权限才能读取桌面壁纸"
    override val TOAST_BG_RESTORED = "已恢复为宿主默认背景"
    override val TOAST_NO_SELECTION = "请先选择一个文件"
    override val TOAST_ICON_HIDDEN = "桌面图标已隐藏"
    override val TOAST_ICON_SHOWN = "桌面图标已恢复"
    override val TOAST_ICON_HIDE_FAILED = "修改桌面图标失败"
    override val TOAST_ADDED = "已添加"
    override val TOAST_DELETED = "已删除"

    override val EXPORT_FILE_TRACK = "morimod-track-names.txt"
    override val EXPORT_FILE_ARTIST = "morimod-artist-names.txt"
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
    override val STATUS_ACTIVE = "已注入宿主"
    override val STATUS_INACTIVE = "未執行"
    override val STATUS_HOST_MISSING = "未安裝"

    override val SEC_FEATURES = "功能設定"
    override val SEC_MANAGE = "模組管理"

    override val ITEM_MEDIA = "系統播放控制項自訂"
    override val ITEM_MEDIA_SUM = "僅針對通知列 / 鎖定畫面進行替換"
    override val ITEM_BACKGROUND = "主程式背景自訂"
    override val ITEM_GLASS = "主程式液態玻璃主題"
    override val ITEM_FONT = "主程式字型替換"
    override val ITEM_LAYOUT = "首頁模組版面調整"
    override val ITEM_HIDE_ICON = "隱藏模組桌面圖示"
    override val ITEM_DEBUG_LOG = "除錯日誌"
    override val ITEM_DEBUG_LOG_SUM = "預設關閉，開啟後寫入 LSPosed 日誌"
    override val ITEM_BACKUP = "匯出與備份"
    override val ITEM_BACKUP_SUM = "匯出設定文字，或從備份還原"
    override val ITEM_ABOUT = "關於"

    override val F1_TITLE = "系統播放控制項自訂"
    override val F1_ENABLE = "啟用替換"
    override val F1_CARD_COVER = "替換封面圖"
    override val F1_CARD_COVER_SUM = "以矩陣形式排出現有圖片，點選套用圖片"
    override val F1_CARD_TRACK = "替換音軌名"
    override val F1_CARD_ARTIST = "替換社團名"
    override val F1_CARD_NAME_SUM = "以清單形式列出已新增的名稱，支援批次匯入"
    override val F1_EMPTY_COVER = "尚無圖片，點「新增圖片」選一張"
    override val F1_EMPTY_NAME = "尚無名稱，點「新增名稱」批次匯入"
    override val F1_ENABLED_COUNT = "已啟用 %1\$d / %2\$d"

    override val F2_TITLE = "主程式背景自訂"
    override val F2_ENABLE = "啟用替換"
    override val F2_CARD = "主程式背景替換"
    override val F2_BLUR = "背景模糊"
    override val F2_PLAY_BLUR = "播放頁背景模糊"
    override val F2_ACCENT = "強調色跟自訂背景取色"
    override val F2_ACCENT_NOTE = "開啟後，宿主裡除白色以外的文字與所有陰影蒙層都會跟隨壁紙取出的強調色；關掉即還原宿主預設。"
    override val F2_ACCENT_STYLE = "強調色風格"
    override val F2_CURRENT_NONE = "目前背景：宿主預設"
    override val F2_CURRENT = "目前背景"
    override val F2_MY_BGS = "我的背景"
    override val F2_MY_BGS_NOTE = "支援 jpg/png/webp：點一項即可換用，長按刪除"
    override val F2_MY_BGS_EMPTY = "尚未匯入背景圖。"
    override val F2_DESKTOP_NOTE = "把宿主背景換成你系統目前的桌面桌布"
    override val CROP_TITLE = "裁剪背景"
    override val CROP_HINT = "雙指縮放、拖動調整位置；框內這塊會按螢幕比例鋪滿宿主背景"
    override val BTN_CROP_SKIP = "略過裁剪"
    override val BTN_CROP_USE = "使用這塊"
    override val TOAST_CROP_FAILED = "裁剪失敗，已保留原圖"
    override val F2_DELETE_BODY = "會從圖庫裡刪掉這張圖。如果它正被用作宿主背景，背景會一併還原成宿主預設。"

    override val F4_TITLE = "主程式字型替換"
    override val F4_CARD = "啟用字型替換"
    override val F4_CURRENT = "目前字型"
    override val F4_NONE = "（未設定素材）"
    override val F4_SYSTEM_FONTS = "系統字型"
    override val F4_RISK_NOTE = "改動後如字型顯示異常，重新啟動 DLsiteSound 即可正常顯示"
    override val F4_NOTE_NOTO = "DLsiteSound 預設字型為 Noto Sans JP，與 思源黑體 是同一套字形，外掛內建的 思源黑體 SC 更聚焦簡體中文顯示"
    override val F4_MY_FONTS = "使用者匯入字型"
    override val F4_MY_FONTS_NOTE = "支援 .ttf/.otf：點一項即可換用，長按刪除"
    override val F4_MY_FONTS_EMPTY = "還沒有匯入過字型檔。"
    override val F4_DELETE_TITLE = "刪除字型"
    override val F4_DELETE_BODY = "會從模組目錄裡刪掉這份字型副本（不影響你原本的檔案）。如果它正是目前字型，替換會同時關閉。"
    override val BTN_DELETE = "刪除"
    override val TOAST_FONT_DELETED = "字型已刪除"
    override val F4_BUNDLED = "外掛內建字型"
    override val F4_NOOP = "所選字型與宿主預設一致，替換後看不出變化；請換一個字型檔或其它族。"
    override val F4_BUNDLED_NOTE = "隨包內建的兩款皆為 SIL OFL 1.1：允許商用，也允許隨本模組再分發（需保留版權聲明與授權條款，見「關於 → 內建字型授權」）。釘釘進步體、阿里巴巴普惠體僅授權「使用」、不允許再分發字型檔，故未內建，需要時請自行選擇檔案匯入。"
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
    override val ABOUT_WHY_BODY = "看膩了紫色背景，想要把主頁 / 播放頁背景換成自己喜歡的圖片，以便視覺上完全歸於自己。\n\n" +
        "內建的字型 Noto San JP 在簡體中文環境下經常顯示字形異常，想要把應用字型換成喜歡的字型並調節字幕顯示大小，以便長時間閱讀更舒適。\n\n" +
        "想讓介面更精緻。\n\n" +
        "質感、順手、可控，就是作者製作這個模組的原動力。\n\n" +
        "最後，感謝你的下載與使用，願你享受每一段音訊。"
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
    override val BTN_ADD_IMAGE = "新增圖片"
    override val BTN_ADD_NAME = "新增名稱"
    override val BTN_RESTORE = "還原預設"
    override val BTN_CLEAR = "清空清單"
    override val BTN_EXPORT = "匯出清單"
    override val BTN_PICK_IMAGE = "選擇圖片"
    override val BTN_DESKTOP_WALL = "桌面桌布"
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
    override val TOAST_POOL_RESTORED = "已還原預設顯示"
    override val TOAST_POOL_CLEARED = "清單已清空"
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
    override val TOAST_GLASS_SAVED = "液態玻璃為第二批功能，偏好已記錄"
    override val TOAST_DEFAULT_RESTORED = "已還原宿主預設"
    override val TOAST_DESKTOP_WALL = "已用目前桌面桌布作背景"
    override val TOAST_WALLPAPER_FAILED = "讀不到桌面桌布：請先在系統裡設定一張桌布"
    override val TOAST_WALLPAPER_DENIED = "需要「圖片」權限才能讀取桌面桌布"
    override val TOAST_BG_RESTORED = "已還原為宿主預設背景"
    override val TOAST_NO_SELECTION = "請先選擇一個檔案"
    override val TOAST_ICON_HIDDEN = "桌面圖示已隱藏"
    override val TOAST_ICON_SHOWN = "桌面圖示已還原"
    override val TOAST_ICON_HIDE_FAILED = "修改桌面圖示失敗"
    override val TOAST_ADDED = "已新增"
    override val TOAST_DELETED = "已刪除"

    override val EXPORT_FILE_TRACK = "morimod-track-names.txt"
    override val EXPORT_FILE_ARTIST = "morimod-artist-names.txt"
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
    override val STATUS_ACTIVE = "Injected"
    override val STATUS_INACTIVE = "Not running"
    override val STATUS_HOST_MISSING = "Not installed"

    override val SEC_FEATURES = "Features"
    override val SEC_MANAGE = "Module"

    override val ITEM_MEDIA = "Playback controls"
    override val ITEM_MEDIA_SUM = "Replaces notification & lock screen only"
    override val ITEM_BACKGROUND = "App background"
    override val ITEM_GLASS = "Liquid glass theme"
    override val ITEM_FONT = "App font"
    override val ITEM_LAYOUT = "Home layout"
    override val ITEM_HIDE_ICON = "Hide launcher icon"
    override val ITEM_DEBUG_LOG = "Debug log"
    override val ITEM_DEBUG_LOG_SUM = "Off by default; writes to the LSPosed log"
    override val ITEM_BACKUP = "Export & backup"
    override val ITEM_BACKUP_SUM = "Export config text, or restore from a backup"
    override val ITEM_ABOUT = "About"

    override val F1_TITLE = "Playback controls"
    override val F1_ENABLE = "Enable"
    override val F1_CARD_COVER = "Replace artwork"
    override val F1_CARD_COVER_SUM = "Tap an image in the grid to apply it"
    override val F1_CARD_TRACK = "Replace track title"
    override val F1_CARD_ARTIST = "Replace circle name"
    override val F1_CARD_NAME_SUM = "Listed below; batch import supported"
    override val F1_EMPTY_COVER = "No images yet — tap “Add image”"
    override val F1_EMPTY_NAME = "No names yet — tap “Add name”"
    override val F1_ENABLED_COUNT = "%1\$d / %2\$d enabled"

    override val F2_TITLE = "App background"
    override val F2_ENABLE = "Enable"
    override val F2_CARD = "App background replacement"
    override val F2_BLUR = "Background blur"
    override val F2_PLAY_BLUR = "Player background blur"
    override val F2_ACCENT = "Accent from wallpaper"
    override val F2_ACCENT_NOTE = "Tints every non-white text and all shadow overlays in the host with an accent colour extracted from your wallpaper. Turn off to restore the host defaults."
    override val F2_ACCENT_STYLE = "Accent style"
    override val F2_CURRENT_NONE = "Current: host default"
    override val F2_CURRENT = "Current background"
    override val F2_MY_BGS = "My backgrounds"
    override val F2_MY_BGS_NOTE = "jpg / png / webp: tap to apply, long-press to delete"
    override val F2_MY_BGS_EMPTY = "No background imported yet."
    override val F2_DESKTOP_NOTE = "Use your current system wallpaper as the app background"
    override val CROP_TITLE = "Crop background"
    override val CROP_HINT = "Pinch to zoom, drag to move. The framed area fills the app background at screen ratio."
    override val BTN_CROP_SKIP = "Skip cropping"
    override val BTN_CROP_USE = "Use this area"
    override val TOAST_CROP_FAILED = "Crop failed — original image kept"
    override val F2_DELETE_BODY = "This removes the image from your library. If it is the current app background, the background is restored to the host default as well."

    override val F4_TITLE = "App font"
    override val F4_CARD = "Enable font replacement"
    override val F4_CURRENT = "Current font"
    override val F4_NONE = "(not set)"
    override val F4_SYSTEM_FONTS = "System fonts"
    override val F4_RISK_NOTE = "If text looks wrong afterwards, restart DLsiteSound"
    override val F4_NOTE_NOTO = "DLsiteSound ships Noto Sans JP (the same typeface family as Source Han Sans); the bundled Source Han Sans SC focuses on Simplified Chinese glyphs"
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
    override val ABOUT_WHY_BODY = "I got tired of the purple background and wanted to replace the home / player background with images I actually like, so the app feels entirely mine.\n\n" +
        "The bundled font Noto San JP often renders broken glyphs in a Simplified Chinese environment, so I wanted to swap in a font I like and adjust the subtitle size for comfortable long listening sessions.\n\n" +
        "I wanted the interface to feel more refined.\n\n" +
        "Texture, fluency, control - that is what drives this module.\n\n" +
        "Finally, thank you for downloading and using it. Enjoy every audio you listen to."
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
    override val BTN_ADD_IMAGE = "Add image"
    override val BTN_ADD_NAME = "Add name"
    override val BTN_RESTORE = "Restore"
    override val BTN_CLEAR = "Clear list"
    override val BTN_EXPORT = "Export list"
    override val BTN_PICK_IMAGE = "Choose image"
    override val BTN_DESKTOP_WALL = "Wallpaper"
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
    override val TOAST_POOL_RESTORED = "Default display restored"
    override val TOAST_POOL_CLEARED = "List cleared"
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
    override val TOAST_GLASS_SAVED = "Liquid glass arrives in the second batch; preference saved"
    override val TOAST_DEFAULT_RESTORED = "Restored host default"
    override val TOAST_DESKTOP_WALL = "Using your current wallpaper"
    override val TOAST_WALLPAPER_FAILED = "Can't read the wallpaper — set one in system settings first"
    override val TOAST_WALLPAPER_DENIED = "Photos permission is needed to read your wallpaper"
    override val TOAST_BG_RESTORED = "Restored host default background"
    override val TOAST_NO_SELECTION = "Pick a file first"
    override val TOAST_ICON_HIDDEN = "Launcher icon hidden"
    override val TOAST_ICON_SHOWN = "Launcher icon restored"
    override val TOAST_ICON_HIDE_FAILED = "Could not change the launcher icon"
    override val TOAST_ADDED = "Added"
    override val TOAST_DELETED = "Deleted"

    override val EXPORT_FILE_TRACK = "morimod-track-names.txt"
    override val EXPORT_FILE_ARTIST = "morimod-artist-names.txt"
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
        }
    }
}

/** 格式化辅助：`%1\$d / %2\$d` 这类占位在本模块用最简单的方式替换。 */
internal fun MoriStrings.fmt(template: String, vararg args: Any): String =
    String.format(template, *args)
