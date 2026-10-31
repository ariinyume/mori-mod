package io.github.ariinyume.morimod.ui

/**
 * 设置页文案的**统一入口**。
 *
 * 这里刻意只做转发（`get() = Strings.current.X`），不持有字面量：
 *  · 调用点写法保持 `S.F1_TITLE` 不变，三语言切换时自动拿到当前语言；
 *  · 字面量集中在 [Lang.kt] 的三张表里，一个文件看全所有语言；
 *  · [Strings.current] 是 `mutableStateOf`，切语言会触发重组。
 *
 * ⚠️ 读 S 的地方**不能缓存到 enum / object 的构造参数**里（只求值一次）——
 *    见 [Dest] 的注释。
 */
internal object S {
    private val s get() = Strings.current

    // ── 应用 / 顶栏 ────────────────────────────────────────────────
    val APP_TITLE get() = s.APP_TITLE
    val HOME_SUBTITLE get() = s.HOME_SUBTITLE
    val BACK get() = s.BACK

    // ── 状态卡 ─────────────────────────────────────────────────────
    val STATUS_TITLE get() = s.STATUS_TITLE
    val STATUS_PLUGIN get() = s.STATUS_PLUGIN
    val STATUS_HOST get() = s.STATUS_HOST
    val STATUS_RUNTIME get() = s.STATUS_RUNTIME
    val STATUS_CHECKING get() = s.STATUS_CHECKING
    val STATUS_ACTIVE get() = s.STATUS_ACTIVE
    val STATUS_INACTIVE get() = s.STATUS_INACTIVE
    val STATUS_HOST_MISSING get() = s.STATUS_HOST_MISSING

    // ── 分区 / 列表项 ──────────────────────────────────────────────
    val SEC_FEATURES get() = s.SEC_FEATURES
    val SEC_MANAGE get() = s.SEC_MANAGE

    val ITEM_MEDIA get() = s.ITEM_MEDIA
    val ITEM_MEDIA_SUM get() = s.ITEM_MEDIA_SUM
    val ITEM_BACKGROUND get() = s.ITEM_BACKGROUND
    val ITEM_GLASS get() = s.ITEM_GLASS
    val ITEM_FONT get() = s.ITEM_FONT
    val ITEM_LAYOUT get() = s.ITEM_LAYOUT
    val ITEM_COVER_BLOCK get() = s.ITEM_COVER_BLOCK
    val ITEM_COVER_BLOCK_SUM get() = s.ITEM_COVER_BLOCK_SUM
    val ITEM_HIDE_ICON get() = s.ITEM_HIDE_ICON
    val ITEM_DEBUG_LOG get() = s.ITEM_DEBUG_LOG
    val ITEM_DEBUG_LOG_SUM get() = s.ITEM_DEBUG_LOG_SUM
    val ITEM_BACKUP get() = s.ITEM_BACKUP
    val ITEM_BACKUP_SUM get() = s.ITEM_BACKUP_SUM
    val ITEM_ABOUT get() = s.ITEM_ABOUT

    // ── F1 ─────────────────────────────────────────────────────────
    val F1_TITLE get() = s.F1_TITLE
    val F1_ENABLE get() = s.F1_ENABLE
    val F1_CARD_COVER get() = s.F1_CARD_COVER
    val F1_CARD_COVER_SUM get() = s.F1_CARD_COVER_SUM
    val F1_CARD_TRACK get() = s.F1_CARD_TRACK
    val F1_CARD_ARTIST get() = s.F1_CARD_ARTIST
    val F1_CARD_NAME_SUM get() = s.F1_CARD_NAME_SUM
    val F1_EMPTY_COVER get() = s.F1_EMPTY_COVER
    val F1_EMPTY_NAME get() = s.F1_EMPTY_NAME

    fun enabledCount(on: Int, total: Int): String = s.F1_ENABLED_COUNT.format(on, total)

    // ── F2 ─────────────────────────────────────────────────────────
    val F2_TITLE get() = s.F2_TITLE
    val F2_ENABLE get() = s.F2_ENABLE
    val F2_CARD get() = s.F2_CARD
    val F2_BLUR get() = s.F2_BLUR
    val F2_PLAY_BLUR get() = s.F2_PLAY_BLUR
    val F2_ACCENT get() = s.F2_ACCENT
    val F2_ACCENT_NOTE get() = s.F2_ACCENT_NOTE
    val F2_ACCENT_STYLE get() = s.F2_ACCENT_STYLE
    val F2_CURRENT_NONE get() = s.F2_CURRENT_NONE
    val F2_CURRENT get() = s.F2_CURRENT
    val F2_MY_BGS get() = s.F2_MY_BGS
    val F2_MY_BGS_NOTE get() = s.F2_MY_BGS_NOTE
    val F2_MY_BGS_EMPTY get() = s.F2_MY_BGS_EMPTY
    val CROP_TITLE get() = s.CROP_TITLE
    val CROP_HINT get() = s.CROP_HINT
    val BTN_CROP_SKIP get() = s.BTN_CROP_SKIP
    val BTN_CROP_USE get() = s.BTN_CROP_USE
    val TOAST_CROP_FAILED get() = s.TOAST_CROP_FAILED
    val F2_DELETE_BODY get() = s.F2_DELETE_BODY

    // ── F4 ─────────────────────────────────────────────────────────
    val F4_TITLE get() = s.F4_TITLE
    val F4_CARD get() = s.F4_CARD
    val F4_CURRENT get() = s.F4_CURRENT
    val F4_NONE get() = s.F4_NONE
    val F4_SYSTEM_FONTS get() = s.F4_SYSTEM_FONTS
    val F4_RISK_NOTE get() = s.F4_RISK_NOTE
    val F4_NOOP get() = s.F4_NOOP
    val F4_MY_FONTS get() = s.F4_MY_FONTS
    val F4_MY_FONTS_NOTE get() = s.F4_MY_FONTS_NOTE
    val F4_MY_FONTS_EMPTY get() = s.F4_MY_FONTS_EMPTY
    val F4_DELETE_TITLE get() = s.F4_DELETE_TITLE
    val F4_DELETE_BODY get() = s.F4_DELETE_BODY
    val BTN_DELETE get() = s.BTN_DELETE
    val TOAST_FONT_DELETED get() = s.TOAST_FONT_DELETED
    val F4_BUNDLED get() = s.F4_BUNDLED
    val F4_BUNDLED_NOTE get() = s.F4_BUNDLED_NOTE
    val F4_NOTE_NOTO get() = s.F4_NOTE_NOTO
    val ABOUT_FONTS get() = s.ABOUT_FONTS
    val BTN_VIEW_OFL get() = s.BTN_VIEW_OFL
    val F4_PREVIEW get() = s.F4_PREVIEW

    // ── 关于 ───────────────────────────────────────────────────────
    val ABOUT_TITLE get() = s.ABOUT_TITLE
    val ABOUT_SUBTITLE get() = s.ABOUT_SUBTITLE
    val ABOUT_VERSION get() = s.ABOUT_VERSION
    val ABOUT_CODE get() = s.ABOUT_CODE
    val ABOUT_PACKAGE get() = s.ABOUT_PACKAGE
    val ABOUT_HOST get() = s.ABOUT_HOST
    val ABOUT_HOST_VERSION get() = s.ABOUT_HOST_VERSION
    val ABOUT_HOST_NOT_INSTALLED get() = s.ABOUT_HOST_NOT_INSTALLED
    val ABOUT_COLOR_SOURCE get() = s.ABOUT_COLOR_SOURCE
    val ABOUT_COLOR_DYNAMIC get() = s.ABOUT_COLOR_DYNAMIC
    val ABOUT_COLOR_FALLBACK get() = s.ABOUT_COLOR_FALLBACK
    val ABOUT_LICENSE get() = s.ABOUT_LICENSE
    val ABOUT_FEEDBACK_SUM get() = s.ABOUT_FEEDBACK_SUM
    val ABOUT_WHY_TITLE get() = s.ABOUT_WHY_TITLE
    val ABOUT_WHY_BODY get() = s.ABOUT_WHY_BODY
    val ABOUT_DISCLAIMER_TITLE get() = s.ABOUT_DISCLAIMER_TITLE
    val ABOUT_DISCLAIMER_BODY get() = s.ABOUT_DISCLAIMER_BODY
    val ABOUT_REPO_TITLE get() = s.ABOUT_REPO_TITLE
    val BTN_OPEN_REPO get() = s.BTN_OPEN_REPO
    val BTN_COPY_LINK get() = s.BTN_COPY_LINK

    // ── 按钮 ───────────────────────────────────────────────────────
    val COMMON_ENABLE get() = s.COMMON_ENABLE
    val BTN_ADD_IMAGE get() = s.BTN_ADD_IMAGE
    val BTN_ADD_NAME get() = s.BTN_ADD_NAME
    val BTN_RESTORE get() = s.BTN_RESTORE
    val BTN_CLEAR get() = s.BTN_CLEAR
    val BTN_EXPORT get() = s.BTN_EXPORT
    val BTN_PICK_IMAGE get() = s.BTN_PICK_IMAGE
    val BTN_PICK_FONT get() = s.BTN_PICK_FONT
    val BTN_USE_SYSTEM_FONT get() = s.BTN_USE_SYSTEM_FONT
    val BTN_COPY_INFO get() = s.BTN_COPY_INFO
    val BTN_OK get() = s.BTN_OK
    val BTN_CANCEL get() = s.BTN_CANCEL

    // ── 对话框 ─────────────────────────────────────────────────────
    val DLG_ADD_NAME get() = s.DLG_ADD_NAME
    val DLG_ADD_NAME_HINT get() = s.DLG_ADD_NAME_HINT
    val DLG_FIELD_TEXT get() = s.DLG_FIELD_TEXT
    val DLG_CLEAR_TITLE get() = s.DLG_CLEAR_TITLE
    val DLG_CLEAR_TEXT get() = s.DLG_CLEAR_TEXT
    val DLG_DELETE_TITLE get() = s.DLG_DELETE_TITLE
    val DLG_DELETE_TEXT get() = s.DLG_DELETE_TEXT
    val DLG_HIDE_ICON_TITLE get() = s.DLG_HIDE_ICON_TITLE
    val DLG_HIDE_ICON_TEXT get() = s.DLG_HIDE_ICON_TEXT
    val DLG_BACKUP_TITLE get() = s.DLG_BACKUP_TITLE
    val DLG_BACKUP_HINT get() = s.DLG_BACKUP_HINT
    val DLG_BACKUP_EXPORT get() = s.DLG_BACKUP_EXPORT
    val DLG_BACKUP_IMPORT get() = s.DLG_BACKUP_IMPORT
    val DLG_BACKUP_COPY_HINT get() = s.DLG_BACKUP_COPY_HINT

    // ── 提示 ───────────────────────────────────────────────────────
    val TOAST_SAVED get() = s.TOAST_SAVED
    val TOAST_SAVE_FAILED get() = s.TOAST_SAVE_FAILED
    val TOAST_COPIED get() = s.TOAST_COPIED
    val TOAST_PICK_FAILED get() = s.TOAST_PICK_FAILED
    val TOAST_FONT_UNSUPPORTED get() = s.TOAST_FONT_UNSUPPORTED
    val TOAST_PICK_CANCELED get() = s.TOAST_PICK_CANCELED
    val TOAST_POOL_RESTORED get() = s.TOAST_POOL_RESTORED
    val TOAST_POOL_CLEARED get() = s.TOAST_POOL_CLEARED
    val TOAST_EXPORTED get() = s.TOAST_EXPORTED
    val TOAST_EXPORT_FAILED get() = s.TOAST_EXPORT_FAILED
    val TOAST_EXPORT_COPIED get() = s.TOAST_EXPORT_COPIED
    val TOAST_IMPORTED get() = s.TOAST_IMPORTED
    val TOAST_IMPORT_EMPTY get() = s.TOAST_IMPORT_EMPTY
    val TOAST_IMPORT_CHECKSUM get() = s.TOAST_IMPORT_CHECKSUM
    val TOAST_IMPORT_FORMAT get() = s.TOAST_IMPORT_FORMAT
    val TOAST_REQUIRES_HOST get() = s.TOAST_REQUIRES_HOST
    val TOAST_RESERVED get() = s.TOAST_RESERVED
    val TAG_WIP get() = s.TAG_WIP
    val TOAST_GLASS_SAVED get() = s.TOAST_GLASS_SAVED
    val TOAST_DEFAULT_RESTORED get() = s.TOAST_DEFAULT_RESTORED
    val TOAST_BG_RESTORED get() = s.TOAST_BG_RESTORED
    val TOAST_NO_SELECTION get() = s.TOAST_NO_SELECTION
    val TOAST_ICON_HIDDEN get() = s.TOAST_ICON_HIDDEN
    val TOAST_ICON_SHOWN get() = s.TOAST_ICON_SHOWN
    val TOAST_ICON_HIDE_FAILED get() = s.TOAST_ICON_HIDE_FAILED
    val TOAST_ADDED get() = s.TOAST_ADDED
    val TOAST_DELETED get() = s.TOAST_DELETED

    val EXPORT_FILE_TRACK get() = s.EXPORT_FILE_TRACK
    val EXPORT_FILE_ARTIST get() = s.EXPORT_FILE_ARTIST
}
