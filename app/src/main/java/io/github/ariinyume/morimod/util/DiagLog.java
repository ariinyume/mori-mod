package io.github.ariinyume.morimod.util;

import android.os.SystemClock;
import android.text.format.DateFormat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地运行诊断（FR-0.5、§10.2）。
 *
 * <p><b>纯本地环形缓冲</b>，容量固定；任何情况下不联网上报（NFR-SEC1/SEC2）。
 * 记录：模块版本、宿主版本、各靶点安装结果、各靶点触发计数（含首触发时间）、
 * 重放次数、降级事件、F4 黑名单命中次数。
 *
 * <p>⚠️ 脱敏（NFR-SEC3）：曲目身份只记哈希前 8 位；不记录曲目名称、社团名称、
 * 文件全路径。
 */
public final class DiagLog {

    private static final int CAP = 400;
    private static final long T0 = SystemClock.elapsedRealtime();

    private static final Deque<String> sLines = new ArrayDeque<>();
    /** 靶点安装结果：名字 → "ok" / "fail:<原因>"。 */
    private static final Map<String, String> sInstalls = new LinkedHashMap<>();
    /** 靶点触发计数：名字 → 次数。 */
    private static final Map<String, Integer> sTriggers = new LinkedHashMap<>();
    /** 靶点首触发时间（相对 T0，毫秒）：名字 → 时刻。 */
    private static final Map<String, Long> sFirstHit = new LinkedHashMap<>();
    /** 计数类：重放次数 / 黑名单命中 / 降级次数 / 素材失效。 */
    private static final Map<String, Integer> sCounters = new LinkedHashMap<>();

    private static volatile String sHeader = "";

    private DiagLog() {
    }

    /** 设定头信息（模块版本 / 宿主版本），进程启动时调用一次。 */
    public static void header(String text) {
        sHeader = text == null ? "" : text;
    }

    public static synchronized void event(String category, String detail) {
        long ms = SystemClock.elapsedRealtime() - T0;
        String line = "+" + ms + "ms [" + category + "] " + (detail == null ? "" : detail);
        sLines.addLast(line);
        while (sLines.size() > CAP) sLines.removeFirst();
    }

    /** 靶点安装结果。 */
    public static synchronized void install(String target, boolean ok, String why) {
        sInstalls.put(target, ok ? "ok" : ("fail:" + (why == null ? "?" : why)));
        event("hook", target + " -> " + (ok ? "installed" : "FAILED " + why));
    }

    /** 靶点触发（首次记录时刻）。 */
    public static synchronized void trigger(String target) {
        Integer n = sTriggers.get(target);
        int c = (n == null ? 0 : n) + 1;
        sTriggers.put(target, c);
        if (c == 1) {
            sFirstHit.put(target, SystemClock.elapsedRealtime() - T0);
            event("trigger", target + " first hit");
        }
    }

    public static synchronized void count(String counter) {
        Integer n = sCounters.get(counter);
        sCounters.put(counter, (n == null ? 0 : n) + 1);
        event("count", counter);
    }

    public static synchronized void countOnce(String counter) {
        if (!sCounters.containsKey(counter)) {
            count(counter);
        }
    }

    public static synchronized void degrade(String feature, String reason) {
        count("degrade:" + feature);
        event("degrade", feature + " -> " + reason);
    }

    /** 一行紧凑摘要，供 PONG 携带（设置页据此判「靶点未触发 / 已生效」）。 */
    public static synchronized String triggerSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("build=").append(BuildInfo.versionCode());
        sb.append(" installs=").append(sInstalls.size());
        int failed = 0;
        for (String v : sInstalls.values()) if (v.startsWith("fail")) failed++;
        sb.append(" failed=").append(failed);
        int trig = 0;
        for (Integer v : sTriggers.values()) trig += v;
        sb.append(" triggers=").append(trig);
        return sb.toString();
    }

    /** 是否有任一靶点被触发过（= 已生效的最低证据）。 */
    public static synchronized boolean anyTriggered() {
        for (Integer v : sTriggers.values()) if (v != null && v > 0) return true;
        return false;
    }

    /** 完整转储（供设置页「复制日志」）。 */
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("MoriMod diagnostics  ").append(DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis())).append('\n');
        if (!sHeader.isEmpty()) sb.append(sHeader).append('\n');
        sb.append("--- installs ---\n");
        for (Map.Entry<String, String> e : sInstalls.entrySet()) {
            sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        sb.append("--- triggers ---\n");
        for (Map.Entry<String, Integer> e : sTriggers.entrySet()) {
            Long t = sFirstHit.get(e.getKey());
            sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue())
                    .append(" (first@").append(t == null ? "?" : t + "ms").append(")\n");
        }
        sb.append("--- counters ---\n");
        for (Map.Entry<String, Integer> e : sCounters.entrySet()) {
            sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        sb.append("--- events (last ").append(CAP).append(") ---\n");
        for (String l : sLines) sb.append("  ").append(l).append('\n');
        return sb.toString();
    }

    public static synchronized void clear() {
        sLines.clear();
        sInstalls.clear();
        sTriggers.clear();
        sFirstHit.clear();
        sCounters.clear();
        event("diag", "cleared");
    }

    /** 曲目身份脱敏：只记哈希前 8 位，不记名称（NFR-SEC3）。 */
    public static String anonymize(String s) {
        if (s == null) return "null";
        int h = s.hashCode();
        return "h" + Integer.toHexString(h) + "/len" + s.length();
    }
}
