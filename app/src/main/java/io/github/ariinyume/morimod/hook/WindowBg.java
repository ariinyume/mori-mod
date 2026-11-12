package io.github.ariinyume.morimod.hook;

import android.app.Activity;
import android.content.res.Resources;
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

    /**
     * ★★★ v0.1.142：开屏 {@code SplashScreenView} 原背景（宿主紫底 #1C1B3D）。
     *
     * <p>自定义背景开启时把它透明掉，露出已经钉在 DecorView 上的壁纸；
     * S logo 仍是 Splash 的子 View，不受影响。关掉开关时按这张表还原。
     */
    private static final java.util.Map<View, Drawable> ORIG_SPLASH =
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
                                    // ★★★ v0.1.46：回到前台先重置播放页/封面救援状态，
                                    //   否则「迷你条状态下退后台→回前台→再展开播放页」
                                    //   那一帧封面 alpha 仍卡在 0、底图也不重画
                                    //   （用户第 4② 条「背景透明 + 封面不显示」）。
                                    //   ★ v0.1.47：带上 Activity —— 除了清缓存还要把
                                    //   整棵视图树 invalidate 一遍（RenderNode 里录着的
                                    //   「透明帧」不清掉，状态翻转了也看不出来）。
                                    BgPeel.onForeground(a);
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

        // ★★★ v0.1.143：onCreate / onStart 就钉壁纸 + 揭开屏。
        //   r142 真机：paint splash 要等到 onResume 后 ~1.6s（16:55:20 → 16:55:22），
        //   用户看到的「紫底很久」几乎全是这段空窗。越早 apply，紫底越短。
        hookActivityEarly(Activity.class, "onCreate");
        hookActivityEarly(Activity.class, "onStart");

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

        // ★★★ v0.1.143：SplashScreenView 一挂到 DecorView 就立刻换底，
        //   不等下一帧 ColorDrawable#draw / 下一轮 poll。
        hookSplashAddView();

        // ★★★ v0.1.143：splashscreen_background 取色时直接透明
        //   （仅开屏色，不动 activityBackground —— 那条换成透明会整屏发白）。
        hookSplashColor(cl);

        // ★★★ v0.1.144：拦截宿主把紫底设回来；TypedArray 路径也拦。
        hookSplashBackgroundSetters();
        hookTypedArraySplashColor();

        // 配置变了（应用内改背景）也要立刻重钉一次
        RemoteConfig.addListener(WindowBg::reapplyAll);
    }

    /** onCreate / onStart：登记 Activity 并尽早 apply（不等 onResume）。 */
    private static void hookActivityEarly(Class<?> act, String name) {
        int n = 0;
        try {
            for (Method m : XposedCompat.findMethodsByName(act, name)) {
                try {
                    XposedCompat.hookMethod(m, new XposedCompat.VoidHook() {
                        @Override
                        protected void afterVoid(io.github.libxposed.api.XposedInterface.Chain chain) {
                            try {
                                Object self = chain.getThisObject();
                                if (!(self instanceof Activity)) return;
                                Activity a = (Activity) self;
                                SEEN.put(a, Boolean.TRUE);
                                apply(a);
                                hookSplashExit(a);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XposedCompat.log(TAG + " Activity#" + name + " 挂钩失败: " + t);
        }
        XposedCompat.log(TAG + " hooked " + n + " Activity#" + name);
    }

    /**
     * SplashScreenView 被 addView 进来的瞬间钉壁纸。
     * 覆盖常见重载；失败只降级，不影响宿主布局。
     */
    private static void hookSplashAddView() {
        int n = 0;
        Class<?>[][] sigs = {
                {View.class},
                {View.class, int.class},
                {View.class, ViewGroup.LayoutParams.class},
                {View.class, int.class, ViewGroup.LayoutParams.class},
        };
        for (Class<?>[] sig : sigs) {
            try {
                Method m = XposedCompat.findMethodExact(ViewGroup.class, "addView", sig);
                XposedCompat.hookMethod(m, new XposedCompat.VoidHook() {
                    @Override
                    protected void afterVoid(io.github.libxposed.api.XposedInterface.Chain chain) {
                        try {
                            Object arg0 = chain.getArg(0);
                            if (!(arg0 instanceof View)) return;
                            View child = (View) arg0;
                            if (!isSplashName(child)) return;
                            Object self = chain.getThisObject();
                            if (self instanceof View) {
                                Activity a = findActivity((View) self);
                                if (a != null) {
                                    SEEN.put(a, Boolean.TRUE);
                                    apply(a);
                                }
                            }
                            pinSplashWallpaper(child);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                n++;
            } catch (Throwable ignored) {
            }
        }
        XposedCompat.log(TAG + " hooked " + n + " ViewGroup#addView (splash)");
    }

    /**
     * 开屏主题色 {@code splashscreen_background} → 透明（自定义背景开启且位图就绪时）。
     * SplashScreenView 构造时就会读到透明色，紫底从源头消掉。
     */
    private static void hookSplashColor(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> res = Resources.class;
            Method[] ms = {
                    XposedCompat.findMethodExact(res, "getColor", int.class),
                    XposedCompat.findMethodExact(res, "getColor", int.class,
                            Resources.Theme.class),
            };
            for (Method m : ms) {
                if (m == null) continue;
                try {
                    XposedCompat.hookMethod(m, new XposedCompat.SimpleHook() {
                        @Override
                        protected Object after(io.github.libxposed.api.XposedInterface.Chain chain,
                                               Object result) {
                            try {
                                if (!(result instanceof Integer)
                                        && !(result instanceof Number)) {
                                    return result;
                                }
                                Object thisObj = chain.getThisObject();
                                Object arg0 = chain.getArg(0);
                                if (!(thisObj instanceof Resources)
                                        || !(arg0 instanceof Integer)) {
                                    return result;
                                }
                                if (!shouldClearSplashColor(
                                        (Resources) thisObj, (Integer) arg0)) {
                                    return result;
                                }
                                if (sSplashColorLogs < 4) {
                                    sSplashColorLogs++;
                                    XposedCompat.log(TAG
                                            + " splashscreen_background -> transparent");
                                }
                                return 0;      // fully transparent
                            } catch (Throwable ignored) {
                                return result;
                            }
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XposedCompat.log(TAG + " splash color hook FAILED: " + t);
        }
        XposedCompat.log(TAG + " hooked " + n + " Resources#getColor (splash)");
    }

    private static volatile int sSplashColorLogs;
    private static volatile int sSplashColorId;
    private static final java.util.Set<Integer> SPLASH_COLOR_MISS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static boolean shouldClearSplashColor(Resources res, int id) {
        if (id == 0) return false;
        MoriConfig cfg = RemoteConfig.get();
        if (!FeatureF2.hasWallpaper(cfg)) return false;
        // 位图还没好就别透明 —— 否则开屏会露出白/黑洞
        if (FeatureF2.wallpaperBitmap(cfg) == null) return false;
        if (id == sSplashColorId && sSplashColorId != 0) return true;
        if (SPLASH_COLOR_MISS.contains(id)) return false;
        try {
            String name = res.getResourceEntryName(id);
            if (name != null && name.contains("splashscreen_background")) {
                sSplashColorId = id;
                return true;
            }
        } catch (Throwable ignored) {
        }
        SPLASH_COLOR_MISS.add(id);
        return false;
    }

    /** 已装过开屏退出监听的 Activity（弱引用，防泄漏）。 */
    private static final java.util.Map<Activity, Boolean> SPLASH_EXIT =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * API 31+：接管开屏退出动画，首帧就把 Splash 底换成壁纸并立刻 remove，
     * 跳过宿主那一段紫底淡出。
     */
    private static void hookSplashExit(Activity a) {
        if (a == null || android.os.Build.VERSION.SDK_INT < 31) return;
        if (SPLASH_EXIT.containsKey(a)) return;
        try {
            Object splash = a.getClass().getMethod("getSplashScreen").invoke(a);
            if (splash == null) return;
            Class<?> listenerClz = Class.forName(
                    "android.window.SplashScreen$OnExitAnimationListener");
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClz.getClassLoader(),
                    new Class<?>[]{listenerClz},
                    (proxy, method, args) -> {
                        try {
                            // 系统认为可以退开屏（内容已就绪）才走到这里。
                            // 先保证底是壁纸，再 remove —— 过渡 = 壁纸+logo → 主页，
                            // 不会再出现「光秃壁纸卡住」那一帧。
                            if (args != null && args.length > 0 && args[0] != null) {
                                Object provider = args[0];
                                Object view = provider.getClass()
                                        .getMethod("getView").invoke(provider);
                                if (view instanceof View) {
                                    pinSplashWallpaper((View) view);
                                }
                                provider.getClass().getMethod("remove").invoke(provider);
                                XposedCompat.log(TAG + " splash exit: pinned+removed");
                            }
                        } catch (Throwable t) {
                            XposedCompat.log(TAG + " splash exit FAILED: " + t);
                        }
                        return null;
                    });
            splash.getClass()
                    .getMethod("setOnExitAnimationListener", listenerClz)
                    .invoke(splash, listener);
            SPLASH_EXIT.put(a, Boolean.TRUE);
            XposedCompat.log(TAG + " splash exit listener installed");
        } catch (Throwable t) {
            // 老 androidx SplashScreen / ROM 差异：静默跳过
        }
    }

    private static boolean isSplashName(View v) {
        if (v == null) return false;
        try {
            return v.getClass().getSimpleName().indexOf("Splash") >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 宿主开屏紫（{@code splashscreen_background} / {@code activityBackground}）。 */
    private static final int HOST_SPLASH_PURPLE = 0xFF1C1B3D;

    /**
     * 给 Splash 挂一份**独立** CoverDrawable（不能复用 DecorView 那份单例）。
     *
     * <p>★★★ v0.1.145：<b>不再强制 remove 开屏</b>。
     * r144 真机用户反馈「紫 → 卡住显示壁纸 → 再进主页」，就是
     * {@code splash force-removed} 把 Splash（含 S logo）拆掉后，
     * RN 还没画完，中间空了一段只有 DecorView 壁纸。
     * 正确做法：开屏层留着，只把紫底换成壁纸，S logo 继续在；
     * 等系统 / Expo 正常退出开屏时再走 {@link #hookSplashExit}。
     */
    private static void pinSplashWallpaper(View splash) {
        if (splash == null) return;
        MoriConfig cfg = RemoteConfig.get();
        if (!FeatureF2.hasWallpaper(cfg)) return;
        android.graphics.Bitmap bmp = FeatureF2.wallpaperBitmap(cfg);
        if (bmp == null || bmp.isRecycled()) {
            clearOneSplash(splash);
            return;
        }
        Drawable cur;
        try {
            cur = splash.getBackground();
        } catch (Throwable t) {
            cur = null;
        }
        if (cur instanceof io.github.ariinyume.morimod.view.CoverDrawable) return;
        rememberSplashOrig(splash, cur);
        try {
            // 设背景时暂时放开闸，避免 setBackground 钩子重入
            sPinningSplash = true;
            splash.setBackground(new io.github.ariinyume.morimod.view.CoverDrawable(
                    bmp, cfg.f2MaskAlpha));
            XposedCompat.log(TAG + " splash wallpaper pinned: "
                    + splash.getClass().getSimpleName()
                    + " " + splash.getWidth() + "x" + splash.getHeight());
        } catch (Throwable t) {
            clearOneSplash(splash);
        } finally {
            sPinningSplash = false;
        }
    }

    private static volatile boolean sPinningSplash;

    /**
     * 宿主 / 系统 Splash API 常在布局后再 {@code setBackgroundColor(#1C1B3D)}，
     * 把我们钉的壁纸盖回去。这里直接吞掉紫，改钉壁纸。
     */
    private static void hookSplashBackgroundSetters() {
        int n = 0;
        try {
            Method m = XposedCompat.findMethodExact(View.class, "setBackgroundColor", int.class);
            XposedCompat.hookMethod(m, chain -> {
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof View && !sPinningSplash
                            && isSplashName((View) self)
                            && wallpaperReady()) {
                        Object arg0 = chain.getArg(0);
                        int color = arg0 instanceof Integer ? (Integer) arg0 : 0;
                        if (isHostSplashPurple(color) || color != 0) {
                            pinSplashWallpaper((View) self);
                            return null;      // 不让紫底设上去
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            n++;
        } catch (Throwable t) {
            XposedCompat.log(TAG + " setBackgroundColor hook FAILED: " + t);
        }
        try {
            Method m = XposedCompat.findMethodExact(View.class, "setBackground", Drawable.class);
            XposedCompat.hookMethod(m, chain -> {
                try {
                    Object self = chain.getThisObject();
                    Object arg0 = chain.getArg(0);
                    if (self instanceof View && !sPinningSplash
                            && isSplashName((View) self)
                            && wallpaperReady()) {
                        if (arg0 instanceof io.github.ariinyume.morimod.view.CoverDrawable) {
                            return chain.proceed();
                        }
                        // 任何非壁纸底（含 ColorDrawable 紫）都改钉壁纸
                        pinSplashWallpaper((View) self);
                        return null;
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            n++;
        } catch (Throwable t) {
            XposedCompat.log(TAG + " setBackground hook FAILED: " + t);
        }
        XposedCompat.log(TAG + " hooked " + n + " View#setBackground* (splash)");
    }

    /**
     * 主题解析常走 {@code TypedArray.getColor}，不经 {@code Resources.getColor}。
     * 命中宿主开屏紫就改透明（仅在壁纸就绪时）。
     */
    private static void hookTypedArraySplashColor() {
        int n = 0;
        try {
            Class<?> ta = android.content.res.TypedArray.class;
            Method m = XposedCompat.findMethodExact(ta, "getColor", int.class, int.class);
            XposedCompat.hookMethod(m, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(io.github.libxposed.api.XposedInterface.Chain chain,
                                       Object result) {
                    try {
                        if (!(result instanceof Integer)) return result;
                        int c = (Integer) result;
                        if (!isHostSplashPurple(c)) return result;
                        if (!wallpaperReady()) return result;
                        if (sSplashColorLogs < 6) {
                            sSplashColorLogs++;
                            XposedCompat.log(TAG
                                    + " TypedArray splash purple -> transparent");
                        }
                        return 0;
                    } catch (Throwable t) {
                        return result;
                    }
                }
            });
            n++;
        } catch (Throwable t) {
            XposedCompat.log(TAG + " TypedArray.getColor hook FAILED: " + t);
        }
        XposedCompat.log(TAG + " hooked " + n + " TypedArray#getColor (splash)");
    }

    private static boolean wallpaperReady() {
        try {
            MoriConfig cfg = RemoteConfig.get();
            return FeatureF2.hasWallpaper(cfg)
                    && FeatureF2.wallpaperBitmap(cfg) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isHostSplashPurple(int color) {
        // 忽略 alpha 差异：#FF1C1B3D / #1C1B3D
        return (color & 0x00FFFFFF) == (HOST_SPLASH_PURPLE & 0x00FFFFFF);
    }

    private static Activity findActivity(View v) {
        try {
            android.content.Context c = v.getContext();
            while (c instanceof android.content.ContextWrapper) {
                if (c instanceof Activity) return (Activity) c;
                c = ((android.content.ContextWrapper) c).getBaseContext();
            }
        } catch (Throwable ignored) {
        }
        return null;
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
        // ★★★ v0.1.46：不再只看「我们记过账」，还要看**这层背景现在还在不在**。
        //   宿主在某些时机会把 DecorView 背景换回去（切后台 / 播放页收起再展开 /
        //   播放页窗口重建），此时 {@code sAppliedKey} 还留着旧指纹，
        //   旧版直接 return ⇒ 窗口层一直是宿主那块深藏青
        //   （用户第 4① 条「播放页上方一条深色带」、第 4② 条「从迷你播放器展开后
        //   背景像透明」都是这个洞透出来的底色）。
        //   现在每个 onResume 都核实一次（只是读一个 drawable 引用，开销可忽略）。
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
        if (applied() && pinStillThere(target)) return;
        apply(target);
    }

    /** 记过账的那个 DecorView 现在挂的还是不是我们的背景。 */
    private static boolean pinStillThere(Activity a) {
        try {
            Window w = a.getWindow();
            View decor = w == null ? null : w.getDecorView();
            if (decor == null) return true;      // 拿不到就不折腾，避免反复重铺
            if (!decorIsMine(decor)) return false;
            return decor.getBackground() instanceof io.github.ariinyume.morimod.view.CoverDrawable;
        } catch (Throwable t) {
            return true;
        }
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

    /** 「apply 进来了但没铺上」的诊断条数（限 4 条/进程，足够定位分支又不刷屏）。 */
    private static volatile int sSkipLogs;

    private static void apply(Activity a) {
        // ★★★ v0.1.46：**整个方法体自己兜住异常**。
        //   以前 apply 的异常只能靠调用方（onResume 钩子 / reapplyAll）的
        //   {@code catch (Throwable ignored)} 吞掉 —— 结果是 r46 那样：
        //   日志里 `window bg applied` 从 19:08 之后一条都没有，
        //   但又没有任何失败痕迹，完全无法定位卡在哪个分支。
        //   而窗口层一旦没铺上，透出来的就是宿主自己的
        //   {@code ColorDrawable#FF1C1B3D}（深藏青）——
        //   正是用户截图里「播放页上方那条深色带」。
        try {
            applyInner(a);
        } catch (Throwable t) {
            XposedCompat.log(TAG + " window bg FAILED: " + t);
        }
    }

    private static void applyInner(Activity a) {
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
        if (decor == null) {
            if (sSkipLogs < 4) {
                sSkipLogs++;
                XposedCompat.log(TAG + " window bg skip: decor=null (activity="
                        + a.getClass().getName() + ")");
            }
            return;
        }

        String key = cfg.f2HomeBg
                + "|" + cfg.f2MaskAlpha + "|b" + cfg.f2Blur;
        // 同一个 Activity 重复 onResume（切页返回）时不必再设一遍；
        // 但如果这个 DecorView 换过了（Activity 重建），必须重新铺。
        String scoped = key + "@" + System.identityHashCode(a);
        if (scoped.equals(sAppliedKey) && decorIsMine(decor)) {
            // ★★★ v0.1.142：窗口层已钉上，但 SplashScreenView 可能是*之后*才挂上来的
            //   （冷启动：先 DecorView，再系统 Splash API 塞子 View）。每轮都扫一次。
            clearSplashPurple(decor);
            return;
        }

        try {
            // 第一次动这个 DecorView 之前把原背景留档 —— 关掉开关时要还原，
            // 否则壁纸会一直挂在窗口上（用户会觉得"关不掉"）。
            synchronized (ORIG_DECOR) {
                if (!ORIG_DECOR.containsKey(decor)) ORIG_DECOR.put(decor, decor.getBackground());
            }
            decor.setBackground(d);
            sAppliedKey = scoped;
            XposedCompat.log(TAG + " window bg applied: " + a.getClass().getName()
                    + " (image)");
            // ★★★ v0.1.142：顺手揭掉开屏紫底（见 {@link #clearSplashPurple}）。
            clearSplashPurple(decor);
            diagOnce(decor);
        } catch (Throwable t) {
            XposedCompat.log(TAG + " setBackground failed: " + t);
        }
    }

    /**
     * ★★★ v0.1.142：自定义背景已钉上时，把 {@code SplashScreenView} 的紫底揭掉。
     *
     * <p>宿主开屏主题色是 {@code #FF1C1B3D}（{@code splashscreen_background}）。
     * 真机日志里它是 DecorView 的直接子 View，自己挂着不透明 {@code ColorDrawable}，
     * 盖住我们已经铺好的窗口壁纸 —— 用户看到的就是启动瞬间那一屏紫。
     *
     * <p>这里只把 Splash 自己的背景透明掉（或清空），<b>不</b>把壁纸 Drawable
     * 再挂到 Splash 上：{@link FeatureF2#windowDrawable} 是单例
     * {@link io.github.ariinyume.morimod.view.CoverDrawable}，同一份不能同时
     * callback 给 DecorView 和 Splash。透出 DecorView 那层就够了，S logo 仍在。
     *
     * <p>幂等：每轮 {@link #apply} / {@link #retryIfMissing} 都可调用；
     * Splash 已揭过或已消失时直接返回。
     */
    public static void clearSplashPurple(View decor) {
        if (decor == null) return;
        try {
            findAndClearSplash(decor, 0);
        } catch (Throwable t) {
            XposedCompat.log(TAG + " splash clear FAILED: " + t);
        }
    }

    private static void findAndClearSplash(View v, int depth) {
        if (v == null || depth > 3) return;
        String sn;
        try {
            sn = v.getClass().getSimpleName();
        } catch (Throwable t) {
            return;
        }
        if (sn != null && sn.indexOf("Splash") >= 0) {
            // ★★★ v0.1.143：优先钉壁纸；位图未就绪时再退回透明
            pinSplashWallpaper(v);
            return;
        }
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        int n = g.getChildCount();
        for (int i = 0; i < n; i++) {
            findAndClearSplash(g.getChildAt(i), depth + 1);
        }
    }

    private static void clearOneSplash(View splash) {
        Drawable cur;
        try {
            cur = splash.getBackground();
        } catch (Throwable t) {
            return;
        }
        if (cur instanceof io.github.ariinyume.morimod.view.CoverDrawable) return;
        // 已经透明 / 空 ⇒ 不用再动（避免每轮 setBackground 触发多余重绘）
        if (cur == null) {
            rememberSplashOrig(splash, null);
            return;
        }
        if (cur instanceof android.graphics.drawable.ColorDrawable) {
            int c = ((android.graphics.drawable.ColorDrawable) cur).getColor();
            if (android.graphics.Color.alpha(c) == 0) {
                rememberSplashOrig(splash, cur);
                return;
            }
        }
        rememberSplashOrig(splash, cur);
        try {
            splash.setBackgroundColor(0);
            XposedCompat.log(TAG + " splash purple cleared: "
                    + splash.getClass().getSimpleName()
                    + " " + splash.getWidth() + "x" + splash.getHeight());
        } catch (Throwable t) {
            XposedCompat.log(TAG + " splash setBackground failed: " + t);
        }
    }

    private static void rememberSplashOrig(View splash, Drawable cur) {
        synchronized (ORIG_SPLASH) {
            if (!ORIG_SPLASH.containsKey(splash)) {
                ORIG_SPLASH.put(splash, cur);
            }
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
        restoreSplash();
    }

    /** 还原开屏紫底（关自定义背景时）。 */
    private static void restoreSplash() {
        java.util.List<View> keys;
        synchronized (ORIG_SPLASH) {
            if (ORIG_SPLASH.isEmpty()) return;
            keys = new java.util.ArrayList<>(ORIG_SPLASH.keySet());
        }
        int n = 0;
        for (View v : keys) {
            Drawable orig;
            synchronized (ORIG_SPLASH) {
                orig = ORIG_SPLASH.remove(v);
            }
            try {
                if (v != null) {
                    v.setBackground(orig);
                    n++;
                }
            } catch (Throwable ignored) {
            }
        }
        if (n > 0) XposedCompat.log(TAG + " splash restored (" + n + ")");
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
