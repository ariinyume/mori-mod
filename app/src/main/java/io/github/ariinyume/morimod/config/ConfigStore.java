package io.github.ariinyume.morimod.config;

import android.content.Context;

import io.github.ariinyume.morimod.util.XPrefs;

/**
 * 配置的本地持久化（FR-0.3）。
 *
 * <p>存储位置全部落在**模块自有目录**（SEC-03）：设置页进程写自己的
 * {@code morimod_hook} SharedPreferences；宿主进程通过框架的
 * {@code getRemotePreferences("morimod_hook")} 读到同一份（只读，铁律 17）。
 *
 * <p>另存一份 {@code morimod_config.json}（原子写：临时文件 → rename），
 * 供备份导出与人工排查使用。
 *
 * <h3>★★★ v0.1.39：为什么多了「上一份」与「按 savedAt 取最新」</h3>
 * r44 一加 12 上出现<b>重启宿主后壁纸丢失</b>，日志实证：
 * <pre>
 *   config probe rprefs=- rfile=- lfile=548/ts1793103544060 lprefs=- -> local-file
 *   config reloaded: F2=false(bg=none) F4=true(file=set) media=0
 * </pre>
 * 也就是说<b>磁盘上那份配置本身就已经没有 bg / 素材池了</b>（548 字节），
 * 后面在设置页里点一下调试日志开关，才把它按「当前内存里的样子」重新写回去。
 *
 * <p>两种可能都指向同一个缺口：
 * <ol>
 *   <li>prefs 与 JSON 文件<b>两份副本不一致</b>（一次写失败、一次成功），
 *       而旧版 {@link #loadPrefs}「prefs 优先」—— 读到的可能是<b>旧的那份</b>，
 *       再一保存就把新那份覆盖成旧的 ⇒ 素材路径永久丢失；</li>
 *   <li>两份都读不到时静默回落默认值，用户随便动一个开关就落盘 ⇒ 同样丢。</li>
 * </ol>
 * 现在的做法：<b>①</b> 读取改成 {@link #loadBest}（两份都解析，取 {@code savedAt} 大的）；
 * <b>②</b> 每次写之前把旧文件留一份 {@code morimod_config.prev.json}；
 * <b>③</b> 保存时若发现「关键字段变空」而上一份里有，且用户<b>没有</b>明确要清空，
 * 就自动补回（见 {@code MoriViewModel#persist}）。
 */
public final class ConfigStore {

    public static final String FILE_NAME = "morimod_config.json";

    /** 上一份配置（每次覆盖前留档）。★ v0.1.39 防「素材/壁纸路径丢失」。 */
    public static final String PREV_NAME = "morimod_config.prev.json";

    private ConfigStore() {
    }

    /** 写本地 prefs（设置页进程调用；宿主靠 remote prefs 读到）。 */
    public static boolean savePrefs(Context ctx, MoriConfig cfg) {
        if (cfg == null) return false;
        cfg.clamp();
        return XPrefs.putString(ctx, XPrefs.HOOK, XPrefs.K_CONFIG_JSON, cfg.toJson());
    }

    public static MoriConfig loadPrefs(Context ctx) {
        String json = XPrefs.getString(ctx, XPrefs.HOOK, XPrefs.K_CONFIG_JSON, null);
        return MoriConfig.fromJson(json);
    }

    /**
     * ★★★ v0.1.39：**两份都读，取最新的一份**。
     *
     * <p>旧版是 {@code loadPrefs(ctx) ?: loadFile(ctx)}：只要 prefs 里有一份
     * （哪怕它是上一次写入失败后剩下的旧值），就再也不看 JSON 文件。
     * 而宿主进程读的恰恰是 JSON 文件 —— 两条通道一旦分叉，
     * 设置页保存时就会用旧值覆盖新值，「壁纸/字体莫名消失」由此而来。
     */
    public static MoriConfig loadBest(Context ctx) {
        MoriConfig prefs = loadPrefs(ctx);
        MoriConfig file = loadFile(ctx);
        if (prefs == null) return file;
        if (file == null) return prefs;
        return file.savedAt > prefs.savedAt ? file : prefs;
    }

    /** 原子写 JSON 文件（临时文件 → rename），避免被杀进程导致半截配置（FR-0.3 规则 2）。 */
    public static boolean saveFile(Context ctx, MoriConfig cfg) {
        if (ctx == null || cfg == null) return false;
        try {
            java.io.File dir = ctx.getFilesDir();
            if (dir == null) return false;
            java.io.File dst = new java.io.File(dir, FILE_NAME);
            // ★ v0.1.39：覆盖之前先留一份上一版，任何「写入了一个更空的配置」都能回查/回补。
            keepPrevious(dir, dst);
            java.io.File tmp = new java.io.File(dir, FILE_NAME + ".tmp");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                fos.write(cfg.toJson().getBytes("UTF-8"));
                fos.flush();
                fos.getFD().sync();
            }
            return tmp.renameTo(dst) || (dst.delete() && tmp.renameTo(dst));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把现有文件复制成 {@link #PREV_NAME}（<b>复制</b>，不是 rename）。
     *
     * <p>⚠️ 必须是复制：宿主进程随时可能在读 {@code morimod_config.json}，
     * 一次 rename 会让它瞬间读不到文件而回落默认值 —— 那正是「重启后壁纸丢失」的放大版。
     * 配置只有 1KB 上下，复制的成本可以忽略。
     */
    private static void keepPrevious(java.io.File dir, java.io.File dst) {
        try {
            if (!dst.exists() || dst.length() <= 0) return;
            java.io.File prev = new java.io.File(dir, PREV_NAME);
            if (prev.exists() && prev.length() == dst.length()
                    && prev.lastModified() >= dst.lastModified()) {
                return;                                  // 上一份已经是最新的
            }
            java.nio.file.Files.copy(dst.toPath(), prev.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable ignored) {
        }
    }

    /** 上一份配置（可能为 null）。 */
    public static MoriConfig loadPrevious(Context ctx) {
        if (ctx == null) return null;
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), PREV_NAME);
            if (!f.exists() || f.length() <= 0) return null;
            byte[] buf = new byte[(int) f.length()];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < buf.length) {
                    int r = fis.read(buf, off, buf.length - off);
                    if (r < 0) break;
                    off += r;
                }
            }
            return MoriConfig.fromJson(new String(buf, "UTF-8"));
        } catch (Throwable t) {
            return null;
        }
    }

    public static MoriConfig loadFile(Context ctx) {
        if (ctx == null) return null;
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), FILE_NAME);
            if (!f.exists()) return null;
            byte[] buf = new byte[(int) f.length()];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < buf.length) {
                    int r = fis.read(buf, off, buf.length - off);
                    if (r < 0) break;
                    off += r;
                }
            }
            return MoriConfig.fromJson(new String(buf, "UTF-8"));
        } catch (Throwable t) {
            return null;
        }
    }
}
