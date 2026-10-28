package io.github.ariinyume.morimod.config;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.CopyOnWriteArrayList;

import io.github.ariinyume.morimod.util.XPrefs;
import io.github.ariinyume.morimod.util.XposedCompat;

/**
 * 配置读取的**唯一入口**（FR-0.3）。
 *
 * <h3>读取链（读到即止）</h3>
 * <ol>
 *   <li><b>本进程本地 prefs</b>（{@code morimod_hook}）—— 宿主进程收到广播后写自身副本；</li>
 *   <li><b>框架 remote prefs</b>（同名 group）—— 读设置页写下的那一份
 *       （应对「宿主没收到广播」的兜底路径，FR-0.3 异常分支）；</li>
 *   <li><b>默认值（全关）</b>。</li>
 * </ol>
 *
 * <p><b>永不抛异常</b>：读取失败一律按「全部功能关闭」处理，绝不中断宿主（FR-0.3 规则 5、
 * NFR-S1）。
 *
 * <p>⚠️ 铁律 49（参考项目）：静态缓存 + 首次读取依赖外部就绪 = 残缺值被永久固化。
 * 所以 {@link #applyJson} 留了重读入口，且 {@link #get()} 在未加载时返回默认而非 null。
 */
public final class RemoteConfig {

    private static volatile MoriConfig sCache;
    private static volatile boolean sLoaded = false;
    private static volatile String sSource = "defaults";
    /** 上一次真正生效的 JSON（v0.1.3）：用来判断「这次重读有没有变化」，没变化就不通知，避免无谓重载。 */
    private static volatile String sLastJson = "";

    private static final CopyOnWriteArrayList<Runnable> sListeners = new CopyOnWriteArrayList<>();

    private RemoteConfig() {
    }

    /** 当前配置（永不为 null）。 */
    public static MoriConfig get() {
        MoriConfig c = sCache;
        if (c == null) {
            c = new MoriConfig();
            c.clamp();
        }
        return c;
    }

    public static String source() {
        return sSource;
    }

    public static boolean debugLog() {
        return get().debugLog;
    }

    /** 注册重载监听（参数变更后由能力层重放）。 */
    public static void addListener(Runnable r) {
        if (r != null && !sListeners.contains(r)) sListeners.add(r);
    }

    public static void removeListener(Runnable r) {
        sListeners.remove(r);
    }

    /**
     * 读取配置（幂等；已加载且 force=false 时直接返回）。
     *
     * <p>读取链（读到即止）：
     * <ol>
     *   <li><b>{@code files/morimod_config.json}</b>（本进程的 filesDir）——
     *       宿主进程收到广播后写的本地副本。**直的接文件 IO**，不经 SharedPreferences，
     *       因为真机日志显示 prefs 那条路重启后读不到（见 {@link Protocol#ACTION_CONFIG_REQ} 注释）。</li>
     *   <li><b>本进程本地 prefs</b>（{@code morimod_hook}）—— 同上的备份档。</li>
     *   <li><b>框架 remote prefs</b> —— 读设置页写的那一份（多数版本读不到，属正常）。</li>
     *   <li><b>默认值</b>（全关）。</li>
     * </ol>
     *
     * <p><b>永不抛异常</b>：读取失败一律按「全部功能关闭」处理，绝不中断宿主（FR-0.3 规则 5、
     * NFR-S1）。
     */
    public static synchronized MoriConfig read(Context ctx, boolean force) {
        if (sLoaded && !force) return get();

        // ★★ v0.1.4：**四份全读，取 savedAt 最大的那一份**。
        //
        //   进度回溯（真机日志 2026-10-23 19:09:47 那次启动）：
        //     19:09:47.122  config reloaded … F4=true(font=source_han_sans_sc.otf)  ← 本地副本（旧字体）
        //     19:10:00.2    config reloaded … font=mmvktstrs.ttf                    ← 13 秒后广播才到
        //   用户的操作顺序是「先在设置页换字体 → 再开宿主」，而宿主本地副本只有在
        //   「宿主进程活着时收到 CONFIG_CHANGED」才更新 ⇒ 冷启动必然读到上一次的配置，
        //   13 秒后拿到正确配置时首页早就渲染完了 ⇒ 表现为「换了字体但没生效」。
        //
        //   这一版不再依赖任何单条通道的时效性：模块侧的两份（remote prefs / 模块 JSON）
        //   在设置保存的**同一瞬间**就已落盘，是真正的「最新」；宿主的本地副本继续当
        //   离线兜底。四份都解析成 MoriConfig，比 savedAt，谁新听谁的。
        Candidate best = null;
        Candidate remotePrefs = null;
        Candidate remoteFile = null;
        Candidate localFile = null;
        Candidate localPrefs = null;

        // ① 框架 remote prefs（模块自己的 morimod_hook）—— 同步、无往返、永远最新
        remotePrefs = candidate("remote-prefs", () -> {
            io.github.libxposed.api.XposedInterface api = XposedCompat.api();
            if (api == null) return null;
            SharedPreferences sp = api.getRemotePreferences(XPrefs.HOOK);
            return sp == null ? null : sp.getString(XPrefs.K_CONFIG_JSON, null);
        });

        // ② 框架 remote file（模块 files/ 下的 JSON，另一条同步通道）
        remoteFile = candidate("remote-file", () -> {
            byte[] b = io.github.ariinyume.morimod.hook.ModuleAssets
                    .readRemoteFile(ConfigStore.FILE_NAME);
            return b == null ? null : new String(b, "UTF-8");
        });

        // ③ 本进程本地 JSON（宿主文件，离线兜底）
        if (ctx != null) {
            localFile = candidate("local-file", () -> {
                MoriConfig fromFile = ConfigStore.loadFile(ctx);
                return fromFile == null ? null : fromFile.toJson();
            });
        }

        // ④ 本进程本地 prefs（同上，第二档兜底）
        localPrefs = candidate("local-prefs", () ->
                XPrefs.getString(ctx, XPrefs.HOOK, XPrefs.K_CONFIG_JSON, null));

        // 仲裁：savedAt 大者胜；全为 0（老版本写的数据）时按「越靠前越可信」的顺序。
        Candidate[] order = {remotePrefs, remoteFile, localFile, localPrefs};
        for (Candidate c : order) {
            if (c == null) continue;
            if (best == null || c.cfg.savedAt > best.cfg.savedAt) best = c;
        }

        MoriConfig parsed;
        String src;
        if (best != null) {
            parsed = best.cfg;
            src = best.name;
        } else {
            parsed = new MoriConfig();
            parsed.clamp();
            src = "defaults";
        }

        // 探测日志（每次进程启动一行）：四份各读到多少字节、savedAt 多少。
        // 有了它，「为什么读到的不是最新」一眼可判，不必再靠猜。
        String verdict = "probe rprefs=" + len(remotePrefs) + "/ts" + ts(remotePrefs)
                + " rfile=" + len(remoteFile) + "/ts" + ts(remoteFile)
                + " lfile=" + len(localFile) + "/ts" + ts(localFile)
                + " lprefs=" + len(localPrefs) + "/ts" + ts(localPrefs)
                + " -> " + src;
        if (!verdict.equals(sLastProbe)) {
            sLastProbe = verdict;
            XposedCompat.log("[MoriMod] config " + verdict);
        }

        // ★★ v0.1.3 血证修复：**一次读空绝不覆盖已生效的配置**。
        //    真机日志（2026-10-23 18:26:47）：
        //      [MoriMod] asset synced -> reapply config
        //      [MoriMod] config reloaded: F2=false(bg=none) F4=false(file=none)
        //    —— 素材同步回调捕获的是 initHost 时的 cfgCtx，那是 **systemContext**
        //    （package=android），它的 getFilesDir()/prefs 都落不到宿主目录 ⇒ 读到空 ⇒
        //    回落默认 ⇒ 用户刚选好的背景/字体当场被关掉，两个功能因此「完全不生效」。
        //    这里再加一道闸：强制重读却什么都没读到时，保持现状。
        if (force && sLoaded && isBlankSrc(src)) {
            io.github.ariinyume.morimod.util.XposedCompat.log(
                    "[MoriMod] 重读配置得到空结果（ctx=" + ctxName(ctx) + "），保持现状 "
                            + sSource + " : " + get().summary());
            return get();
        }

        sCache = parsed;
        sLoaded = true;
        sSource = src;
        sLastJson = parsed.toJson();

        // ★ 远端赢了的场合：顺手把宿主的本地副本刷新成同一份，
        //   这样下次「模块进程起不来」时离线兜底也是最新的。
        if (ctx != null && (best == remotePrefs || best == remoteFile)) {
            try {
                ConfigStore.saveFile(ctx, parsed);
            } catch (Throwable ignored) {
            }
        }
        return parsed;
    }

    /** 上一次探测结论（同结论不重复打日志，避免热路径刷屏）。 */
    private static volatile String sLastProbe = "";

    /** 一个配置来源的探针结果（读不到就是 null）。 */
    private static final class Candidate {
        final String name;
        final MoriConfig cfg;
        final int bytes;

        Candidate(String name, MoriConfig cfg, int bytes) {
            this.name = name;
            this.cfg = cfg;
            this.bytes = bytes;
        }
    }

    private static Candidate candidate(String name, JsonSource s) {
        String json;
        try {
            json = s.get();
        } catch (Throwable t) {
            return null;
        }
        if (json == null || json.isEmpty()) return null;
        MoriConfig c = MoriConfig.fromJson(json);
        if (c == null) return null;
        return new Candidate(name, c, json.length());
    }

    private interface JsonSource {
        String get() throws Throwable;
    }

    private static String len(Candidate c) {
        return c == null ? "-" : String.valueOf(c.bytes);
    }

    private static String ts(Candidate c) {
        return c == null ? "-" : String.valueOf(c.cfg.savedAt);
    }

    /** 读空的三态（defaults / corrupt->defaults）。 */
    private static boolean isBlankSrc(String src) {
        return "defaults".equals(src) || "corrupt->defaults".equals(src);
    }

    /** 只用于日志：上下文来源一眼可见（systemContext 的包名是 android，正是踩坑点）。 */
    private static String ctxName(Context ctx) {
        if (ctx == null) return "null";
        try {
            return ctx.getPackageName() + "/" + (ctx.getApplicationContext() == null ? "app?" : "app");
        } catch (Throwable t) {
            return "err";
        }
    }

    /** 由广播携带的 JSON 直接生效（宿主进程路径）。 */
    public static synchronized void applyJson(String json, String src) {
        MoriConfig parsed = MoriConfig.fromJson(json);
        if (parsed == null) {
            io.github.ariinyume.morimod.util.DiagLog.event("config",
                    "广播配置解析失败（源=" + src + "），保持现状");
            return;
        }
        // ★★★ v0.1.5 血证（"关了宿主改字体，再开还是不生效"的真凶）：
        //   广播是**排队**投递的。用户在宿主关闭期间改字体，那条 CONFIG_CHANGED
        //   会一直躺在系统队列里（我们发广播时带了 FLAG_INCLUDE_STOPPED_PACKAGES），
        //   宿主下次冷启动时才开始投递 —— 于是「刚读到的新配置」被「上一次的旧配置」
        //   当场覆盖回去。真机日志（0.1.4，21:52 那次）完整记录了这次抖动：
        //     config reloaded … font=mmvkvhki4.ttf     ← 本次刚读到的（新）
        //     config applied from broadcast
        //     assets: … font=mmvkvi89b.ttf             ← 旧广播覆盖回来了
        //     relayout after font change: 55 views
        //   用户看到的正是「还是之前那个字体」。
        //   修法：广播里的 savedAt 比当前配置更旧 ⇒ 直接丢弃（0 视作"未知"，放行，
        //   避免老版本写的配置被永久挡住）。
        long incomingTs = parsed.savedAt;
        long currentTs = sLoaded ? get().savedAt : 0L;
        if (incomingTs > 0L && currentTs > 0L && incomingTs < currentTs) {
            io.github.ariinyume.morimod.util.XposedCompat.log(
                    "[MoriMod] 丢弃过期配置广播（源=" + src + "，ts=" + incomingTs
                            + " < 当前 " + currentTs + "）");
            return;
        }
        // ★ v0.1.3：内容一模一样就不重载 —— 每次进程启动都会向模块应用核对一次配置，
        //   绝大多数情况下 JSON 完全相同，此时重载只会白白刷日志 + 让能力层丢缓存。
        boolean same = parsed.toJson().equals(sLastJson);
        sCache = parsed;
        sLoaded = true;
        sSource = src;
        sLastJson = parsed.toJson();
        if (!same) notifyListeners();
    }

    /** 就地更新缓存（设置页保存后调用，避免自己发自己收）。 */
    public static synchronized void set(MoriConfig cfg) {
        if (cfg == null) return;
        cfg.clamp();
        sCache = cfg;
        sLoaded = true;
        sSource = "settings";
        sLastJson = cfg.toJson();
        notifyListeners();
    }

    /** 重读（force）。内容没变就不通知监听器。 */
    public static synchronized void reload(Context ctx) {
        String before = sLastJson;
        read(ctx, true);
        if (!before.equals(sLastJson)) notifyListeners();
    }

    private static void notifyListeners() {
        for (Runnable r : sListeners) {
            try {
                r.run();
            } catch (Throwable t) {
                io.github.ariinyume.morimod.util.XposedCompat.log(
                        "[MoriMod] config listener failed: " + t);
            }
        }
    }
}
