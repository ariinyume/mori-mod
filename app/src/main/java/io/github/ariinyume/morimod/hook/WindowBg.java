package io.github.ariinyume.morimod.hook;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import java.lang.reflect.Method;

import io.github.ariinyume.morimod.config.MoriConfig;
import io.github.ariinyume.morimod.config.RemoteConfig;
import io.github.ariinyume.morimod.util.XposedCompat;

/**
 * 把用户背景图**直接钉在窗口上**（v0.1.5）。
 *
 * <h3>为什么资源替换之外还要这一层</h3>
 * F2 的资源替换（{@code Resources.getDrawable} / {@code openRawResourceFd} /
 * {@code AssetManager.open}）只在**宿主去取资源的那一刻**起作用。真机日志显示，
 * 宿主冷启动时 F2 八个钩子全部挂上、也只探到一个 {@code activityBackground}，
 * 而窗口底色在 {@code Activity.attach} 阶段就已经装进 {@code DecorView} 了 ——
 * 那时代码可能还没跑到替换、或者宿主自己缓存了 Drawable 对象，于是「选了背景没反应」。
 *
 * <p>这里在 {@code Activity.onResume} 之后**主动**把 {@code DecorView} 的背景设成用户图：
 * 不依赖宿主愿不愿意重新取资源，也不依赖它的缓存策略。
 *
 * <h3>为什么不直接改 RN 内部 View 的背景</h3>
 * 那会破坏宿主自己的层级（卡片、列表项都可能是 View 背景），而且 RN 每次重渲染都会覆盖回来。
 * DecorView 是窗口最外层，改它只有一种后果：整个窗口的底色变成用户的图 —— 正是「主程序背景」
 * 这个概念本身。如果宿主内容自身不透明（RN 画满），那也说明它「有自己的一套背景」，
 * 此时行为与改动前一致，不会更糟。
 *
 * <p>同时打一行**诊断日志**（{@code bg diag}），记录内容区第一层 View 的类型与背景，
 * 下次「背景还是不换」时可以直接看出是谁不透明挡住的，不必再猜。
 */
public final class WindowBg {

    private static final String TAG = "[MoriMod:BG]";

    /** 上一次应用的指纹（bg|mask|clear），避免同一份配置反复 setBackground 触发重绘。 */
    private static volatile String sAppliedKey = "";

    /** 诊断日志只打一次（每个进程）。 */
    private static volatile boolean sDiagLogged;

    /** 「想要位图但这轮没拿到」的日志条数（限次，别刷屏）。 */
    private static int sMissLogs;

    /** 这个 DecorView 是不是我们铺过的那个。 */
    private static boolean decorIsMine(View decor) {
        synchronized (ORIG_DECOR) {
            return ORIG_DECOR.containsKey(decor);
        }
    }

    /**
     * 见过的 Activity（弱引用，防止持有已销毁的 Activity 造成泄漏）。
     *
     * <p>{@code onResume} 里登记，配置变更时对它们全部重钉一次 ——
     * 这样「在设置页改完背景、回到宿主」不需要重启就能看到效果。
     */
    private static final java.util.Map<Activity, Boolean> SEEN =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 被我们换掉窗口背景的 DecorView → 宿主原本的背景（关掉开关时还原）。 */
    private static final java.util.Map<View, Drawable> ORIG_DECOR =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private WindowBg() {
    }

    public static void install(ClassLoader cl) {
        int ok = 0;
        try {
            for (Method m : XposedCompat.findMethodsByName(Activity.class, "onResume")) {
                try {
                    XposedCompat.hookMethod(m, new XposedCompat.VoidHook() {
                        @Override
                        protected void afterVoid(io.github.libxposed.api.XposedInterface.Chain chain) {
                            try {
                                Object self = chain.getThisObject();
                                if (self instanceof Activity) {
                                    Activity a = (Activity) self;
                                    SEEN.put(a, Boolean.TRUE);
                                    apply(a);
                                    // ★ v0.1.6：把宿主自己盖在上面的全屏层剥掉。
                                    //   用户的现象是「打开的一瞬间能看到壁纸，随后被挡住」——
                                    //   窗口背景我们设上了，是 RN 的全屏渐变/底色盖住了它。
                                    BgPeel.schedule(a);
                                }
                            } catch (Throwable ignored) {
                                // 钩子体绝不抛回宿主
                            }
                        }
                    });
                    ok++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XposedCompat.log(TAG + " Activity#onResume 挂钩失败: " + t);
        }
        XposedCompat.log(TAG + " hooked " + ok + " Activity#onResume");

        // ★ v0.1.7：离开前台就停掉 BgPeel 的视图树轮询（别在后台白跑）。
        int pz = 0;
        try {
            for (Method m : XposedCompat.findMethodsByName(Activity.class, "onPause")) {
                try {
                    XposedCompat.hookMethod(m, new XposedCompat.VoidHook() {
                        @Override
                        protected void afterVoid(io.github.libxposed.api.XposedInterface.Chain chain) {
                            try {
                                Object self = chain.getThisObject();
                                if (self instanceof Activity) BgPeel.onPause((Activity) self);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    pz++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XposedCompat.log(TAG + " Activity#onPause 挂钩失败: " + t);
        }
        XposedCompat.log(TAG + " hooked " + pz + " Activity#onPause");

        // 配置变了（应用内改背景）也要立刻重钉一次
        RemoteConfig.addListener(WindowBg::reapplyAll);
    }

    /**
     * ⛔ v0.1.17：**不再碰任何系统栏 API**（v0.1.15/16 那套「拆导航栏对比度 scrim」
     * 整段删除，理由见 {@link #apply} 里的说明）。
     */
    private static final boolean BARS_UNTOUCHED = true;

    /**
     * 位图到手了、但窗口层还没铺上：补铺一次（v0.1.14）。
     *
     * <h3>为什么必须有这个</h3>
     * {@code apply} 只在 {@code onResume} 和配置变更时被调用。冷启动那一刻
     * {@code FeatureF2.cachedDrawable} 很可能还拿不到位图（素材通道没连上），
     * 于是 {@code d == null} 直接返回、<b>之后再没有任何重试机会</b>；
     * 而 {@link BgPeel} 800ms 后的轮询已经把页面底色换成了壁纸 ——
     * 结果「页面底层有壁纸、窗口层是空的」这种半成品状态就永久停在那里。
     *
     * <p>{@link BgPeel#apply} 每 800ms 叫一次；铺过了就立刻返回（{@link #applied()} 短路）。
     *
     * @param a 当前前台的 Activity（可为 null，此时用最近登记过的）
     */
    public static void retryIfMissing(Activity a) {
        if (applied()) return;
        Activity target = a;
        if (target == null) {
            synchronized (SEEN) {
                for (Activity x : SEEN.keySet()) {
                    if (x != null && !x.isFinishing()) {
                        target = x;
                        break;
                    }
                }
            }
        }
        if (target == null) return;
        apply(target);
    }

    /** 配置变更 → 对当前所有 Activity 重新钉一遍。 */
    public static void reapplyAll() {
        sAppliedKey = "";      // 强制重算
        BgPeel.invalidate();   // 换图/改模糊/改遮罩：内容层也要用新参数重铺
        try {
            java.util.List<Activity> list;
            synchronized (SEEN) {
                list = new java.util.ArrayList<>(SEEN.keySet());
            }
            for (Activity a : list) {
                apply(a);
                BgPeel.apply(a);     // 顺带把新出现的全屏层剥一遍
            }
        } catch (Throwable ignored) {
        }
    }

    /** 窗口背景是否已成功钉上（{@link BgPeel} 用它当前置闸门，见其类注释）。 */
    public static boolean applied() {
        return !sAppliedKey.isEmpty();
    }

    /** 清掉「已应用」状态（恢复默认时用，避免旧指纹挡住新配置）。 */
    public static void reset() {
        sAppliedKey = "";
    }

    private static void apply(Activity a) {
        if (a == null) return;
        MoriConfig cfg = RemoteConfig.get();
        View decor = null;
        try {
            Window w = a.getWindow();
            decor = w == null ? null : w.getDecorView();
        } catch (Throwable ignored) {
        }

        // v0.1.10：只有「真的有壁纸可铺」才动窗口。
        // ⚠️ 绝不能再走「设成透明」那条路 —— 宿主 window 不是 translucent，
        //    透明只会露出白色（v0.1.9 的整屏发白就是这么来的）。
        Drawable d = FeatureF2.windowDrawable(cfg);
        if (d != null && decor != null) {
            // ⛔ v0.1.17：这里原来会拆导航栏的对比度 scrim（Android 10+ 默认开，
            //    不拆的话壁纸会被压成一块纯黑）。但调那两个 Window 接口会让窗口走
            //    relayout，宿主自己请求的「隐藏系统栏」（播放页沉浸式）就被顶回去，
            //    r20/r22 连续两轮用户反馈「导航栏被锁死显示 → 随后开始闪烁」。
            //    整个模块里碰系统栏的只有这一处 ⇒ 全部移除，系统栏交还系统。
        }
        if (d == null) {
            // ★ v0.1.11：必须区分两种「拿不到」——
            //   ① 用户真的关掉了（没有来源）：还原宿主原背景，天经地义；
            //   ② 配置想要壁纸、但**这一轮读不到**（桌面壁纸取不到 / 素材通道还没连上）：
            //      这时**什么都不做**。旧版在这里无条件 restoreAll + repaint，
            //      于是每一轮轮询都「还原 → 再贴」，屏幕上的壁纸一亮一灭。
            if (!FeatureF2.hasWallpaper(cfg)) {
                sAppliedKey = "";
                restoreDecor();
                BgPeel.restoreAll();     // 内容层上铺的壁纸也要还回去
                BgPeel.repaint(decor);   // 渐变层需要一次重绘才会被画回来
            } else if (sMissLogs < 3) {
                // v0.1.14：窗口层「想要但这一轮拿不到位图」必须留痕 ——
                // 旧版这里静默返回，日志上完全看不出「窗口层其实是空的」。
                sMissLogs++;
                XposedCompat.log(TAG + " window bg 暂缺位图（等素材通道），本轮跳过");
            }
            return;
        }
        if (decor == null) return;

        String key = (cfg.f2DesktopWall ? "@desk" : cfg.f2HomeBg)
                + "|" + cfg.f2MaskAlpha + "|b" + cfg.f2Blur;
        // 同一个 Activity 重复 onResume（切页返回）时不必再设一遍；
        // 但如果这个 DecorView 换过了（Activity 重建），必须重新铺。
        String scoped = key + "@" + System.identityHashCode(a);
        if (scoped.equals(sAppliedKey) && decorIsMine(decor)) return;

        try {
            // 第一次动这个 DecorView 之前把原背景留档 —— 关掉开关时要还原，
            // 否则壁纸会一直挂在窗口上（用户会觉得"关不掉"）。
            synchronized (ORIG_DECOR) {
                if (!ORIG_DECOR.containsKey(decor)) ORIG_DECOR.put(decor, decor.getBackground());
            }
            decor.setBackground(d);
            sAppliedKey = scoped;
            XposedCompat.log(TAG + " window bg applied: " + a.getClass().getName()
                    + " (" + (cfg.f2DesktopWall ? "desktop" : "image") + ")");
            diagOnce(decor);
        } catch (Throwable t) {
            XposedCompat.log(TAG + " setBackground failed: " + t);
        }
    }

    /** 关掉背景替换：把 DecorView 还原成宿主原本的背景。 */
    private static void restoreDecor() {
        java.util.List<View> keys;
        synchronized (ORIG_DECOR) {
            if (ORIG_DECOR.isEmpty()) return;
            keys = new java.util.ArrayList<>(ORIG_DECOR.keySet());
        }
        for (View v : keys) {
            Drawable orig;
            synchronized (ORIG_DECOR) {
                orig = ORIG_DECOR.remove(v);
            }
            try {
                if (v != null) {
                    v.setBackground(orig);
                    // 宿主原背景回来了，但内容层/渐变层是按旧状态画的 ⇒ 重绘一次
                    v.postInvalidateOnAnimation();
                }
            } catch (Throwable ignored) {
            }
        }
        XposedCompat.log(TAG + " decor restored (" + keys.size() + ")");
    }

    /**
     * 打一行「谁在挡着」的诊断：内容区第一层（RN 根 View）的类型与背景。
     *
     * <p>{@code bg=none} 说明它是透明的 ⇒ 我们设的窗口背景能透出来；
     * {@code bg=ColorDrawable#ffXXXXXX} 说明宿主自己画了一层不透明底色，
     * 那它才是真正要处理的对象。
     */
    private static void diagOnce(View decor) {
        if (sDiagLogged) return;
        sDiagLogged = true;
        try {
            View content = decor.findViewById(android.R.id.content);
            String info = "content=null";
            if (content instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) content;
                StringBuilder sb = new StringBuilder("content=").append(g.getClass().getSimpleName());
                int n = Math.min(g.getChildCount(), 4);
                for (int i = 0; i < n; i++) {
                    View c = g.getChildAt(i);
                    sb.append(" [").append(i).append(' ').append(c.getClass().getSimpleName())
                            .append(" ").append(c.getWidth()).append('x').append(c.getHeight())
                            .append(" bg=").append(describe(c.getBackground()))
                            .append(']');
                }
                info = sb.toString();
            }
            XposedCompat.log(TAG + " bg diag: " + info);
        } catch (Throwable ignored) {
        }
    }

    private static String describe(Drawable d) {
        if (d == null) return "none";
        StringBuilder sb = new StringBuilder(d.getClass().getSimpleName());
        try {
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                sb.append('#').append(Integer.toHexString(
                        ((android.graphics.drawable.ColorDrawable) d).getColor()));
            }
            if (d.getAlpha() != 255) sb.append("/alpha=").append(d.getAlpha());
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }
}
