package io.github.ariinyume.morimod.hook;

import android.graphics.Canvas;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.ariinyume.morimod.config.MoriConfig;
import io.github.ariinyume.morimod.config.RemoteConfig;
import io.github.ariinyume.morimod.util.XposedCompat;

/**
 * Step 0：切歌/切轨卡顿的**只读诊断探针**（v0.1.99，实验性）。
 *
 * <h3>它要回答的问题</h3>
 * v0.1.94 之后插件自己制造的 200ms 尖峰已经治好（266.8ms → 27.1ms，关插件基准 19.2ms），
 * 真机 trace 里剩下的卡顿来自宿主：静态播放页上 {@code RNCMaskedView 1153×77} 每帧都在画、
 * GPU 满载 99%、120Hz 下只有 89fps。但 trace **只告诉我们「谁在画」，没告诉我们两件事**：
 * <ol>
 *   <li>是<b>谁</b>让它每一帧都要重画的（动画本身？全屏渐变层？还是根视图级 invalidate？）；</li>
 *   <li>每帧重画的<b>面积有多大</b>（一层 1153×77 的小遮罩，和整屏 1272×2772 是两种病）。</li>
 * </ol>
 * 这两件事的答案不同，对策就完全不同：前者可以给那一层独立 RenderNode 一劳永逸；
 * 后者加 layer 只会白搭（全屏层没有可复用的上一帧），只能降载。所以**必须先测**。
 *
 * <h3>为什么钩 {@code View#draw(Canvas)} 就是「谁在重画」</h3>
 * 硬件加速下屏幕每帧刷新<b>不会</b>调用 {@code View.draw} —— 复用 RenderNode 直接合成。
 * {@code View.draw} 只在 {@code updateDisplayListIfDirty()} 判定该视图「脏」、
 * 需要**重新录制显示列表**时才被调用。所以「每秒被调用多少次」精确等于
 * 「这个视图每秒被重新录制多少次」，而 {@code canvas} 的宽高就是这个视图被录制的尺寸。
 * 这比 {@code dispatchDraw} 干净得多：后者每帧每层都会走一遍，测不出差异。
 *
 * <h3>为什么还要钩 {@code invalidateInternal}</h3>
 * {@code View.invalidate()} / {@code invalidate(l,t,r,b)} / {@code postInvalidate} 最终都汇到
 * {@code invalidateInternal(int,int,int,int,boolean)} 这一个漏斗。在这里按类计数，
 * 就能把「谁点了火」和「谁被重画」对上号。调用栈按 {@value #STACK_EVERY_MS} 毫秒采样一次
 * （全量抓栈会把探针自己变成新的卡顿源），聚合成「类.方法 ← 类.方法」签名。
 *
 * <h3>克制条款（这个类是诊断工具，绝不能成为新的性能问题）</h3>
 * <ul>
 *   <li>开关关闭时<b>连 hook 都不注册</b>（{@link #apply}），不是「注册了再 return」——
 *       在每秒上万次调用的路径上，被拦截本身就是要付的代价；</li>
 *   <li>单次采样 {@link #BUDGET_MS} 后自动停手并打总表，绝不留一个常驻热钩子；</li>
 *   <li>汇总与写日志全部在独立 {@code HandlerThread} 上做，主线程只做几次自增；</li>
 *   <li>hook 体全程 try/catch，任何异常都吞掉 —— 它是探针，不是功能，绝不能影响宿主。</li>
 * </ul>
 */
public final class PerfProbe {

    private static final String TAG = "[MoriMod:Perf]";

    /** 采样窗口（毫秒）：每窗口输出一段汇总。 */
    private static final long WINDOW_MS = 2000L;

    /** 一次探针最长采集时长；到点自动停手。 */
    private static final long BUDGET_MS = 180_000L;

    /** 抓调用栈的最小间隔（采样率上限 4 次/秒）。 */
    private static final long STACK_EVERY_MS = 250L;

    /** 窗口内最多列几个视图 / 总表最多列几个视图。 */
    private static final int TOP_WINDOW = 4;
    private static final int TOP_FINAL = 20;

    /** 单个视图最多记几种调用方签名（防无界增长）。 */
    private static final int MAX_CALLERS = 24;

    /** 「满屏」判据：自身录制面积 ≥ 根视图面积的这个比例。与 BgPeel 的 0.80 同一口径。 */
    private static final float FULLSCREEN_RATIO = 0.80f;

    // ── 运行时状态 ──────────────────────────────────────────────────
    private static volatile boolean sHooked = false;
    private static volatile boolean sOn = false;
    private static volatile long sDeadline = 0L;
    private static volatile long sStart = 0L;
    private static volatile long sNextFlush = 0L;
    private static volatile long sLastFlush = 0L;
    private static volatile long sNextStack = 0L;
    private static volatile long sRootArea = 0L;
    private static volatile int sRootTick = 0;

    /** 全局窗口计数。 */
    private static volatile long sDrawTotal = 0L;
    private static volatile long sDrawArea = 0L;
    private static volatile long sFullCount = 0L;
    private static volatile long sInvTotal = 0L;
    private static volatile long sInvRoot = 0L;

    /** 按视图类聚合（key 用 Class 本身：getName() 有缓存，但取它仍不如直接拿对象便宜）。 */
    private static final ConcurrentHashMap<Class<?>, Stat> sStats = new ConcurrentHashMap<>();

    private static volatile Handler sBg = null;

    private PerfProbe() {
    }

    /** 单个视图类的统计。窗口计数在每次 flush 后清零，总量一直累计到本次探针结束。 */
    private static final class Stat {
        long drawCount;
        long drawArea;
        long drawMax;
        long drawFull;
        long invCount;
        long invRoot;

        long tDrawCount;
        long tDrawArea;
        long tDrawMax;
        long tDrawFull;
        long tInvCount;
        long tInvRoot;

        /**
         * 只在这个视图「被失效」时按采样写入。
         *
         * <p>⚠️ 必须是并发容器：写入发生在**主线程**（invalidate 钩子里），
         * 遍历发生在**后台线程**（flushWindow 取最热签名）。用普通 HashMap 会
         * 在每次汇总时抛 ConcurrentModificationException —— 被 try/catch 吞掉后
         * 表现为「整个窗口一行日志都没有」，查起来极其费劲。
         */
        final Map<String, int[]> callers = new ConcurrentHashMap<>();

        void addDraw(long area, boolean full) {
            drawCount++;
            drawArea += area;
            if (area > drawMax) drawMax = area;
            if (full) drawFull++;
            tDrawCount++;
            tDrawArea += area;
            if (area > tDrawMax) tDrawMax = area;
            if (full) tDrawFull++;
        }

        void addInv(boolean onRoot) {
            invCount++;
            if (onRoot) invRoot++;
            tInvCount++;
            if (onRoot) tInvRoot++;
        }

        void resetWindow() {
            drawCount = 0L;
            drawArea = 0L;
            drawMax = 0L;
            drawFull = 0L;
            invCount = 0L;
            invRoot = 0L;
            // ⚠️ callers **不清**：它按 MAX_CALLERS 封顶，永远不会无界增长，
            //    而「谁点的火」恰恰是最需要在**最后那张总表**里看到的东西。
            //    早先这里跟着窗口一起 clear，结果总表里调用方一列全是空的 ——
            //    排查时最贵的一列信息就这么被自己扔掉了。
        }
    }

    // ==================================================================
    // 入口
    // ==================================================================

    /**
     * 装上配置监听（幂等）。**本身不注册任何热钩子** ——
     * 是否真的开始采集，由 {@link #apply} 按 {@code perfSwitch} 决定。
     *
     * <p>为什么要在开关关闭时也调用它：用户可能「先开宿主、后打开开关」，
     * 那时候必须能立刻开始采集，而不是要求他重启宿主。所以监听器要常驻，
     * 但热路径上的 hook 只在开关打开时才挂。
     */
    public static void install(ClassLoader cl) {
        RemoteConfig.addListener(PerfProbe::onConfigChanged);
        apply(RemoteConfig.get());
    }

    /** 配置变化（可能来自广播/设置页保存）。 */
    private static void onConfigChanged() {
        try {
            apply(RemoteConfig.get());
        } catch (Throwable t) {
            XposedCompat.log(TAG + " onConfigChanged failed: " + t);
        }
    }

    /**
     * 按当前配置决定「开始采集 / 停止采集」。
     *
     * <p>⚠️ 开关关闭时**不注册 hook**：这是「关闭时禁止所有相关改动调起」的落点。
     * 已经注册过的 hook 无法真正解挂（libxposed 的 {@code deoptimize} 只是还原解释执行），
     * 所以关掉时靠方法体首行的 volatile 判断立刻停手 —— 此时只剩一次内存读的代价。
     */
    private static void apply(MoriConfig cfg) {
        boolean want = cfg != null && cfg.perfSwitch;
        if (want) {
            if (sOn) return;                       // 已经在采集中，别重开
            if (!sHooked && !hookAll()) return;    // 挂不上就什么都别做
            arm();
        } else if (sOn) {
            stop("开关已关闭");
        }
    }

    /** 开始一次新的采样（重置计时与窗口计数，总量保留上一轮的也不影响：这里一并清零）。 */
    private static void arm() {
        long now = SystemClock.uptimeMillis();
        sStats.clear();
        sDrawTotal = 0L;
        sDrawArea = 0L;
        sFullCount = 0L;
        sInvTotal = 0L;
        sInvRoot = 0L;
        sRootArea = 0L;
        sRootTick = 0;
        sStart = now;
        sDeadline = now + BUDGET_MS;
        sNextFlush = now + WINDOW_MS;
        sLastFlush = now;
        sNextStack = now;
        sOn = true;
        XposedCompat.log(TAG + " probe armed: 只读采集 " + (BUDGET_MS / 1000) + "s。"
                + "请在宿主里正常切几次音轨，然后回来看本段日志；"
                + "期间不做任何绘制干预。");
    }

    /** 停手 + 打总表。 */
    private static void stop(String why) {
        if (!sOn) return;
        sOn = false;
        XposedCompat.log(TAG + " probe stop (" + why + ")，共采集 "
                + ((SystemClock.uptimeMillis() - sStart) / 1000) + "s，输出总表：");
        Handler bg = bg();
        if (bg != null) bg.post(PerfProbe::dumpFinal);
    }

    // ==================================================================
    // 挂钩
    // ==================================================================

    /** @return 是否至少挂上了 {@code View#draw}（那是主指标，挂了才有意义） */
    private static synchronized boolean hookAll() {
        if (sHooked) return true;
        boolean drawOk = false;
        try {
            drawOk = XposedCompat.hookMethod(
                    View.class, "draw", new Class<?>[]{Canvas.class},
                    new XposedCompat.VoidHook() {
                        @Override
                        protected void afterVoid(io.github.libxposed.api.XposedInterface.Chain chain) {
                            onDraw(chain);
                        }
                    }, TAG);
        } catch (Throwable t) {
            XposedCompat.log(TAG + " hook View#draw failed: " + t);
        }
        if (!drawOk) {
            XposedCompat.log(TAG + " 主指标（View#draw）挂不上，本次不采集");
            return false;
        }
        // 第二个钩子失败不影响主指标：能测出「谁在重画」就已经能定靶。
        try {
            XposedCompat.hookMethod(
                    View.class, "invalidateInternal",
                    new Class<?>[]{int.class, int.class, int.class, int.class, boolean.class},
                    new XposedCompat.VoidHook() {
                        @Override
                        protected void afterVoid(io.github.libxposed.api.XposedInterface.Chain chain) {
                            onInvalidate(chain);
                        }
                    }, TAG);
        } catch (Throwable t) {
            XposedCompat.log(TAG + " hook View#invalidateInternal failed（只少「谁点的火」一列）: " + t);
        }
        sHooked = true;
        XposedCompat.log(TAG + " hooks ready");
        return true;
    }

    /** {@code View#draw(Canvas)} 之后：记一次「这个视图被重新录制了」。 */
    private static void onDraw(io.github.libxposed.api.XposedInterface.Chain chain) {
        if (!sOn) return;
        try {
            long now = SystemClock.uptimeMillis();
            if (now > sDeadline) {
                stop("采集时长用尽");
                return;
            }
            Object self = chain.getThisObject();
            if (!(self instanceof View)) return;
            View v = (View) self;
            Stat st = stat(v.getClass());

            Object a0 = chain.getArg(0);
            long area = 0L;
            if (a0 instanceof Canvas) {
                Canvas c = (Canvas) a0;
                area = (long) c.getWidth() * (long) c.getHeight();
            }
            if (area <= 0L) {
                area = (long) v.getWidth() * (long) v.getHeight();
            }

            // 根视图面积每 128 次刷新一次（getRootView 要沿父链走，别每次都走）。
            if ((sRootTick++ & 0x7F) == 0 || sRootArea <= 0L) {
                try {
                    View root = v.getRootView();
                    if (root != null) sRootArea = (long) root.getWidth() * (long) root.getHeight();
                } catch (Throwable ignored) {
                }
            }
            long ra = sRootArea;
            boolean full = ra > 0L && area >= (long) (ra * FULLSCREEN_RATIO);

            st.addDraw(area, full);
            sDrawTotal++;
            sDrawArea += area;
            if (full) sFullCount++;

            if (now >= sNextFlush) {
                sNextFlush = now + WINDOW_MS;
                Handler bg = bg();
                if (bg != null) bg.post(PerfProbe::flushWindow);
            }
        } catch (Throwable ignored) {
        }
    }

    /** {@code View#invalidateInternal(...)} 之后：记一次「这个视图要求重画」。 */
    private static void onInvalidate(io.github.libxposed.api.XposedInterface.Chain chain) {
        if (!sOn) return;
        try {
            Object self = chain.getThisObject();
            if (!(self instanceof View)) return;
            View v = (View) self;
            Stat st = stat(v.getClass());

            boolean onRoot;
            try {
                onRoot = v.getRootView() == v;
            } catch (Throwable t) {
                onRoot = false;
            }
            st.addInv(onRoot);
            sInvTotal++;
            if (onRoot) sInvRoot++;

            long now = SystemClock.uptimeMillis();
            if (now >= sNextStack) {
                sNextStack = now + STACK_EVERY_MS;
                captureCaller(st);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 采样一次调用方签名。
     *
     * <p>抓栈本身有成本（几十微秒），所以按 {@link #STACK_EVERY_MS} 限流 ——
     * 探针不能把自己变成新的卡顿源。签名只取 3 帧，够定位「谁在动它」即可。
     */
    private static void captureCaller(Stat st) {
        try {
            if (st.callers.size() >= MAX_CALLERS) return;
            StackTraceElement[] frames = new Throwable().getStackTrace();
            if (frames == null || frames.length < 4) return;
            StringBuilder sb = new StringBuilder();
            int taken = 0;
            // 跳过「我们自己的」和「框架桥接的」帧，剩下的第一帧就是
            // View.invalidateInternal（漏斗本身），后面两帧才是真正的调用方。
            // 不按固定下标切，是因为拦截链的中间帧数量会随框架版本变 ——
            // 写死 index 4 在换框架后会静默偏一帧，签名就成了废话。
            for (int i = 0; i < frames.length && taken < 3; i++) {
                String full = frames[i].getClassName();
                if (full == null) continue;
                if (full.startsWith("io.github.ariinyume.morimod.hook.PerfProbe")) continue;
                if (full.startsWith("io.github.ariinyume.morimod.util.XposedCompat")) continue;
                if (full.startsWith("io.github.libxposed")) continue;
                String cls = full;
                int dot = cls.lastIndexOf('.');
                if (dot >= 0) cls = cls.substring(dot + 1);
                if (taken > 0) sb.append(" <- ");
                sb.append(cls).append('.').append(frames[i].getMethodName());
                taken++;
            }
            if (sb.length() == 0) return;
            String key = sb.toString();
            int[] prev = st.callers.putIfAbsent(key, new int[]{1});
            if (prev != null) prev[0]++;
        } catch (Throwable ignored) {
        }
    }

    private static Stat stat(Class<?> k) {
        Stat st = sStats.get(k);
        if (st != null) return st;
        Stat created = new Stat();
        Stat prev = sStats.putIfAbsent(k, created);
        return prev != null ? prev : created;
    }

    // ==================================================================
    // 汇总输出（全部在后台线程）
    // ==================================================================

    private static Handler bg() {
        Handler h = sBg;
        if (h != null) return h;
        synchronized (PerfProbe.class) {
            if (sBg == null) {
                try {
                    HandlerThread t = new HandlerThread("MoriPerfProbe");
                    t.setDaemon(true);
                    t.start();
                    sBg = new Handler(t.getLooper());
                } catch (Throwable e) {
                    XposedCompat.log(TAG + " probe thread failed: " + e);
                    return null;
                }
            }
            return sBg;
        }
    }

    /** 一个视图类的快照（排序用，避免在遍历时直接改 Stat）。 */
    private static final class Snap {
        final String name;
        final long drawCount, drawMax, drawFull, invCount, invRoot;
        final String caller;

        Snap(String name, long drawCount, long drawMax, long drawFull,
             long invCount, long invRoot, String caller) {
            this.name = name;
            this.drawCount = drawCount;
            this.drawMax = drawMax;
            this.drawFull = drawFull;
            this.invCount = invCount;
            this.invRoot = invRoot;
            this.caller = caller;
        }
    }

    private static void flushWindow() {
        try {
            long now = SystemClock.uptimeMillis();
            long elapsed = now - sStart;
            List<Snap> draws = snapshot(false);
            List<Snap> invs = snapshot(true);
            // ⚠️ 窗口计数每轮清零，所以速率必须用**本窗口时长**算，不能用总时长 ——
            //    用总时长会把「每秒 120 次」稀释成「每秒 3 次」，直接把人带沟里。
            double sec = Math.max(0.05, (now - sLastFlush) / 1000.0);
            sLastFlush = now;

            long d = sDrawTotal, da = sDrawArea, f = sFullCount, iv = sInvTotal;
            XposedCompat.log(TAG + String.format(
                    "+%.1fs(剩%ds) 重录制=%d次(%.1f/s) 面积=%.2fGpx 满屏层=%d(%.0f%%) 失效=%d次(%.1f/s)",
                    elapsed / 1000.0, Math.max(0, (sDeadline - SystemClock.uptimeMillis()) / 1000),
                    d, d / sec, da / 1e9, f, d == 0 ? 0 : f * 100.0 / d, iv, iv / sec));

            int n = 0;
            for (Snap s : draws) {
                if (n++ >= TOP_WINDOW) break;
                if (s.drawCount <= 0) continue;
                XposedCompat.log(TAG + "  draw " + s.name + " " + s.drawCount + "次("
                        + String.format("%.1f/s", s.drawCount / sec) + ") 峰值" + s.drawMax + "px"
                        + (s.drawFull > 0 ? " 满屏" + s.drawFull + "次" : ""));
            }
            n = 0;
            for (Snap s : invs) {
                if (n++ >= TOP_WINDOW) break;
                if (s.invCount <= 0) continue;
                XposedCompat.log(TAG + "  inv  " + s.name + " " + s.invCount + "次("
                        + String.format("%.1f/s", s.invCount / sec) + ")"
                        + (s.invRoot > 0 ? " 根级" + s.invRoot + "次" : "")
                        + (s.caller == null ? "" : " <- " + s.caller));
            }

            // 窗口计数清零（总量保留到 stop）
            for (Stat st : sStats.values()) st.resetWindow();
            sDrawTotal = 0L;
            sDrawArea = 0L;
            sFullCount = 0L;
            sInvTotal = 0L;
            sInvRoot = 0L;
        } catch (Throwable t) {
            XposedCompat.log(TAG + " flush failed: " + t);
        }
    }

    private static void dumpFinal() {
        try {
            long elapsed = SystemClock.uptimeMillis() - sStart;
            List<Snap> draws = snapshotTotal(false);
            List<Snap> invs = snapshotTotal(true);
            double sec = Math.max(0.001, elapsed / 1000.0);
            XposedCompat.log(TAG + " ===== 总表 " + String.format("%.1fs", elapsed / 1000.0) + " =====");
            int n = 0;
            for (Snap s : draws) {
                if (n++ >= TOP_FINAL) break;
                if (s.drawCount <= 0) continue;
                XposedCompat.log(TAG + "  draw# " + s.name + " 共" + s.drawCount + "次("
                        + String.format("%.1f/s", s.drawCount / sec) + ") 峰值" + s.drawMax + "px"
                        + (s.drawFull > 0 ? " 满屏" + s.drawFull + "次" : ""));
            }
            n = 0;
            for (Snap s : invs) {
                if (n++ >= TOP_FINAL) break;
                if (s.invCount <= 0) continue;
                XposedCompat.log(TAG + "  inv#  " + s.name + " 共" + s.invCount + "次("
                        + String.format("%.1f/s", s.invCount / sec) + ")"
                        + (s.invRoot > 0 ? " 根级" + s.invRoot + "次" : "")
                        + (s.caller == null ? "" : " <- " + s.caller));
            }
            XposedCompat.log(TAG + " ===== 总表结束；把上面这段贴出来即可定靶 =====");
        } catch (Throwable t) {
            XposedCompat.log(TAG + " final dump failed: " + t);
        }
    }

    /** 按窗口计数快照（true = 失效维度）。 */
    private static List<Snap> snapshot(boolean byInvalidate) {
        List<Snap> out = new ArrayList<>(sStats.size());
        for (Map.Entry<Class<?>, Stat> e : sStats.entrySet()) {
            Stat s = e.getValue();
            if (byInvalidate) {
                if (s.invCount <= 0) continue;
                out.add(new Snap(label(e.getKey()), 0, 0, 0, s.invCount, s.invRoot, topCaller(s)));
            } else {
                if (s.drawCount <= 0) continue;
                out.add(new Snap(label(e.getKey()), s.drawCount, s.drawMax, s.drawFull, 0, 0, null));
            }
        }
        out.sort(byInvalidate
                ? Comparator.comparingLong((Snap s) -> -s.invCount)
                : Comparator.comparingLong((Snap s) -> -s.drawCount));
        return out;
    }

    /** 按累计计数快照。 */
    private static List<Snap> snapshotTotal(boolean byInvalidate) {
        List<Snap> out = new ArrayList<>(sStats.size());
        for (Map.Entry<Class<?>, Stat> e : sStats.entrySet()) {
            Stat s = e.getValue();
            if (byInvalidate) {
                if (s.tInvCount <= 0) continue;
                out.add(new Snap(label(e.getKey()), 0, 0, 0, s.tInvCount, s.tInvRoot, topCaller(s)));
            } else {
                if (s.tDrawCount <= 0) continue;
                out.add(new Snap(label(e.getKey()), s.tDrawCount, s.tDrawMax, s.tDrawFull, 0, 0, null));
            }
        }
        out.sort(byInvalidate
                ? Comparator.comparingLong((Snap s) -> -s.invCount)
                : Comparator.comparingLong((Snap s) -> -s.drawCount));
        return out;
    }

    /** 该类最热的一条调用方签名（可能为 null）。 */
    private static String topCaller(Stat s) {
        String best = null;
        int bestN = 0;
        for (Map.Entry<String, int[]> e : s.callers.entrySet()) {
            int v = e.getValue()[0];
            if (v > bestN) {
                bestN = v;
                best = e.getKey();
            }
        }
        return best;
    }

    /** 类名：优先 simpleName（日志要短），匿名为空时退回去掉包名的全名。 */
    private static String label(Class<?> k) {
        if (k == null) return "?";
        String n = k.getSimpleName();
        if (n != null && !n.isEmpty()) return n;
        String f = k.getName();
        int i = f.lastIndexOf('.');
        return i >= 0 ? f.substring(i + 1) : f;
    }
}
