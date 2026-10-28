package io.github.ariinyume.morimod.util;

import io.github.ariinyume.morimod.config.RemoteConfig;

/**
 * 诊断日志闸门（参考项目 2.2.14 / code 993 口径）。
 *
 * <p>把「里程碑 / 告警」与「诊断」分成两条路：
 * <ul>
 *   <li>里程碑 / 告警 —— 走 {@link XposedCompat#log(String)}，<b>常开</b>；</li>
 *   <li>诊断级 —— 走 {@link #debug}，仅在设置页「调试日志」打开时输出。</li>
 * </ul>
 *
 * <p>⚠️ 本类只改「打多少日志」，不参与任何功能判定。
 */
public final class LogGate {

    private LogGate() {
    }

    public static boolean enabled() {
        return RemoteConfig.debugLog();
    }

    public static void debug(String tag, String msg) {
        if (!RemoteConfig.debugLog()) return;
        XposedCompat.log(tag + " " + msg);
    }

    public static void debug(String msg) {
        if (!RemoteConfig.debugLog()) return;
        XposedCompat.log(msg);
    }
}
