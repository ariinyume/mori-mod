package io.github.ariinyume.morimod.data;

import io.github.ariinyume.morimod.util.DiagLog;

/**
 * 会话映射（Session Assignment，§7.1.5 / §9.3）。
 *
 * <p>裁决 C-2：全量随机、不要求稳定对应性；但产品侧保留底线 ——
 * <b>单次播放会话内必须保持显示一致</b>（不闪烁）。会话映射表为<b>内存级</b>，
 * 切曲/停止即失效，宿主重启后重新随机。
 *
 * <p>会话标识 = 宿主原始 metadata 的身份（原音轨名 + 原社团名）。宿主重绘、
 * 通知刷新、结构事件重放时该标识不变 ⇒ 命中缓存 ⇒ 不闪烁。
 */
public final class SessionAssignment {

    private static volatile String sKey;
    private static volatile boolean sAssigned;

    /** 已解析的取值（null = 该项不可用，回落宿主原值）。 */
    private static volatile String sCoverPath;
    private static volatile String sTitle;
    private static volatile String sArtist;

    private SessionAssignment() {
    }

    /**
     * 确认当前会话。会话标识变了就作废旧映射（下次 {@link #isAssigned()} 为 false，
     * 由调用方重新随机）。
     */
    public static synchronized void ensure(String sessionKey) {
        String k = sessionKey == null ? "" : sessionKey;
        if (!k.equals(sKey)) {
            sKey = k;
            sAssigned = false;
            sCoverPath = null;
            sTitle = null;
            sArtist = null;
            DiagLog.event("f1", "new session " + DiagLog.anonymize(k));
        }
    }

    public static boolean isAssigned() {
        return sAssigned;
    }

    public static synchronized void assign(String coverPath, String title, String artist) {
        sCoverPath = coverPath;
        sTitle = title;
        sArtist = artist;
        sAssigned = true;
    }

    /** 用户点「重新随机本曲目」：作废当前映射，本次会话后续保持新值。 */
    public static synchronized void reroll() {
        sAssigned = false;
        sCoverPath = null;
        sTitle = null;
        sArtist = null;
        DiagLog.event("f1", "reroll current session");
    }

    public static String coverPath() {
        return sCoverPath;
    }

    public static String title() {
        return sTitle;
    }

    public static String artist() {
        return sArtist;
    }
}
