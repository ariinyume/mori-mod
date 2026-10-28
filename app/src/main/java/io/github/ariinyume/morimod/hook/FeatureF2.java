package io.github.ariinyume.morimod.hook;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import java.lang.reflect.Method;

import io.github.ariinyume.morimod.config.MoriConfig;
import io.github.ariinyume.morimod.config.RemoteConfig;
import io.github.ariinyume.morimod.util.DiagLog;
import io.github.ariinyume.morimod.util.WallpaperBg;
import io.github.ariinyume.morimod.util.XposedCompat;
import io.github.ariinyume.morimod.view.CoverDrawable;

/**
 * F2 · 主程序背景自定义（仅主页，D-05 / BR-2.x）。
 *
 * <h3>v0.1.10：这一层**不再做任何资源替换**</h3>
 * 资源替换（{@code getDrawable} / {@code openRawResource*} / {@code AssetManager.open}）
 * 在前几轮里被反复证明是**最危险**的一条路：
 * <ul>
 *   <li>{@code activityBackground} 是一份**颜色资源**，宿主会在窗口、页、卡片各种 bounds
 *       上取它。换成整屏位图后，每一处都在缩放绘制一张全屏照片 ⇒ 合成彻底失控，
 *       用户看到的是「整屏爆炸」（v0.1.7 真机 {@code bg=9}，一次贴 9 层）。</li>
 *   <li>「全透明」把同一份资源换成全透明图 ⇒ 宿主的深色底被清掉，
 *       白字压在白色上 ⇒ **整屏发白**（v0.1.9 真机，日志 {@code bg=set,clear=true}）。</li>
 * </ul>
 *
 * <p>所以壁纸现在只有**两条明确路径**，都带精确的层级控制：
 * <ol>
 *   <li>{@link WindowBg} —— 窗口层（DecorView）；</li>
 *   <li>{@link BgPeel} —— 宿主内容层里那层「铺满窗口的容器」。</li>
 * </ol>
 * 两个入口都从这里拿 {@link #windowDrawable(MoriConfig)}（壁纸 + 模糊 + 遮罩合成好的
 * {@link CoverDrawable}），不再经过宿主的资源表。
 *
 * <p>钩子**保留不摘**：它们的返回值一律原样放行，但每次调用都会把资源项名记进
 * 诊断探针（{@link #entryNameOf}）—— 下一轮如果背景又不对，日志里能直接看到
 * 宿主实际在取哪些资源，不必再猜。
 */
public final class FeatureF2 {

    private static final String TAG = "[MoriMod:F2]";

    /** v1.0 开放页面的资源项名特征（contains 匹配，自动覆盖 assets_ 前缀与 _tablet 变体）。 */
    private static final String[] OPEN_PAGES = {
            "main_visual",
            // ★★★ v0.1.5 真凶：**`activityBackground` 才是用户看到的"主程序背景"**
            //   （`color/activityBackground = #ff1c1b3d`，style 的 android:windowBackground
            //   指向它）。之前只认 main_visual ⇒ 从来没命中过 ⇒ 背景"永远不生效"。
            "activityBackground",
    };

    /**
     * 预留但暂不开放的页面（命中时**不替换**）。
     * 保留在此以便后续逐页开放时只需移动常量。
     */
    public static final String[] RESERVED_PAGES = {
            "localbg", "localbgtab", "player_background", "playbackspbackground",
            "playbacktabletbackground", "playbackdisk", "playback_card_bg",
            "1_jpop", "2_jazz", "3_dtm", "4_relax",
    };

    /** Drawable 路径缓存（壁纸位图，供 CoverDrawable 每次重绘复用）。 */
    private static volatile Bitmap sCachedBmp;
    private static volatile String sCachedBmpKey = "";

    /** 降级日志只报一次（渲染是热路径）。 */
    private static volatile boolean sDegradeLogged;

    /**
     * 资源 id → 资源项名（不命中记为 {@link #MISS}）。
     *
     * <p>⚠️ 这是 v0.0.7 的性能命门：`getResourceEntryName` 对**不属于该 Resources**
     * 的 id 会抛 {@code NotFoundException}，异常构造 + 栈回溯在 ART 上很贵。
     * 而 `openRawResource` / `getDrawable` 都是宿主超热路径（每个图标、每张背景、
     * 每次 RN 渲染都会走），若每次都抛一次异常，宿主 UI 线程会被拖到「点什么都不动」。
     * 命中结果缓存后，同一条资源 id 只查一次。
     */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, String> ENTRY_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 缓存里表示「该 id 不是目标页」的哨兵（不能用 null，ConcurrentHashMap 不收 null）。 */
    private static final String MISS = "";

    /** 缓存上限：超过就整体清空（宿主资源 id 是有限集合，正常远达不到）。 */
    private static final int ENTRY_CACHE_MAX = 8192;

    private FeatureF2() {
    }

    public static void install(ClassLoader cl) {
        // ★★★ v0.1.4 血证：`TypedValue` 的正确包名是 **`android.util.TypedValue`**。
        //   写成 `android.content.res.TypedValue` 时 findClass 抛异常，而那行当时在整个
        //   try 的最前面 ⇒ 后面 6 个 Resources 钩子全部没装 ⇒ 背景替换从来不可能生效。
        //   现在一律用类字面量：编译期就检查，且不依赖宿主的 ClassLoader。
        Class<?> res = Resources.class;
        Class<?> theme = Resources.Theme.class;
        Class<?> typedValue = android.util.TypedValue.class;

        RemoteConfig.addListener(FeatureF2::invalidate);

        int ok = 0;
        // ── 原生 Drawable 路径 ──
        ok += hook(res, "getDrawable", new Class<?>[]{int.class});
        ok += hook(res, "getDrawable", new Class<?>[]{int.class, theme});
        ok += hook(res, "getDrawableForDensity",
                new Class<?>[]{int.class, int.class, theme});
        // ── RN / Fresco 路径 ──
        ok += hook(res, "openRawResource", new Class<?>[]{int.class});
        ok += hook(res, "openRawResource",
                new Class<?>[]{int.class, typedValue});
        ok += hookFd(res, "openRawResourceFd", new Class<?>[]{int.class});
        DiagLog.install("F2:Resources", ok > 0, ok > 0 ? null : "no overload hooked");
        XposedCompat.log(TAG + " hooked " + ok + "/6 Resources entries");

        int amOk = 0;
        try {
            Class<?> am = android.content.res.AssetManager.class;
            amOk += hookAm(am, "open", new Class<?>[]{String.class});
            amOk += hookAm(am, "open", new Class<?>[]{String.class, int.class});
        } catch (Throwable t) {
            XposedCompat.log(TAG + " AssetManager hooks skipped: " + t);
        }
        XposedCompat.log(TAG + " hooked " + amOk + "/2 AssetManager entries");
        DiagLog.install("F2:AssetManager", amOk > 0, amOk > 0 ? null : "no overload hooked");

        // ★ v0.1.5：窗口层直接钉背景（资源层从来没在真机上命中过）。
        WindowBg.install(cl);
        // ★ v0.1.6：再把宿主自己盖在上面的**全屏层**处理掉，否则「壁纸只在启动一瞬间可见」。
        BgPeel.install(cl);
    }

    private static int hook(Class<?> res, String name, Class<?>[] sig) {
        try {
            Method m = XposedCompat.findMethodExact(res, name, sig);
            XposedCompat.hookMethod(m, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(io.github.libxposed.api.XposedInterface.Chain chain,
                                       Object result) {
                    try {
                        probeOnly(chain);
                    } catch (Throwable ignored) {
                        // 探针失败绝不影响宿主
                    }
                    return result;
                }
            });
            XposedCompat.log(TAG + " hooked Resources#" + name + "/" + sig.length);
            return 1;
        } catch (Throwable t) {
            XposedCompat.log(TAG + " hook " + name + " failed: " + t.getMessage());
            return 0;
        }
    }

    /**
     * {@code openRawResourceFd(int)} 单独挂钩（返回类型不同）。
     *
     * <p>v0.1.10 起同样只做探针：宿主背景图是 STORED 的 webp，Fresco 优先走 fd 直读，
     * 这条入口的价值现在只剩「看清它在取什么」。
     */
    private static int hookFd(Class<?> res, String name, Class<?>[] sig) {
        try {
            Method m = XposedCompat.findMethodExact(res, name, sig);
            XposedCompat.hookMethod(m, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(io.github.libxposed.api.XposedInterface.Chain chain,
                                       Object result) {
                    try {
                        probeOnly(chain);
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            XposedCompat.log(TAG + " hooked Resources#" + name);
            return 1;
        } catch (Throwable t) {
            XposedCompat.log(TAG + " hook " + name + " failed: " + t.getMessage());
            return 0;
        }
    }

    /** {@code AssetManager#open(String[, int])} —— 同样只探针。 */
    private static int hookAm(Class<?> am, String name, Class<?>[] sig) {
        try {
            Method m = XposedCompat.findMethodExact(am, name, sig);
            XposedCompat.hookMethod(m, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(io.github.libxposed.api.XposedInterface.Chain chain,
                                       Object result) {
                    try {
                        Object arg0 = chain.getArg(0);
                        if (arg0 instanceof String) probeAsset((String) arg0);
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedCompat.log(TAG + " hook AssetManager." + name + " failed: " + t.getMessage());
            return 0;
        }
    }

    /**
     * 只记资源项名，不做任何替换（v0.1.10）。
     *
     * <p>为什么留着：真机上宿主到底在取什么资源，只有这里看得见。上一轮连续几次
     * 都在猜「是不是 main_visual」，全靠这条探针才定位到 {@code activityBackground}。
     */
    private static void probeOnly(io.github.libxposed.api.XposedInterface.Chain chain) {
        Object thisObj = chain.getThisObject();
        Object arg0 = chain.getArg(0);
        if (!(thisObj instanceof Resources) || !(arg0 instanceof Integer)) return;
        entryNameOf((Resources) thisObj, (Integer) arg0);
    }

    /**
     * AssetManager 打开名的探针。
     *
     * <p>为什么需要：资源层探针在真机上只捞到一个 {@code activityBackground}，
     * 说明宿主的图片**根本不经过 Resources**。如果它走 {@code assets/}（Expo / RN 的
     * {@code asset://}），那就只有这里能看到。
     */
    private static void probeAsset(String name) {
        if (name == null || name.isEmpty()) return;
        if (sAssetProbeCount >= PROBE_MAX) return;
        if (!ASSET_PROBED.add(name)) return;
        sAssetProbeCount++;
        String l = name.toLowerCase(java.util.Locale.ROOT);
        boolean interesting = l.contains("visual") || l.contains("background")
                || l.contains("_bg") || l.contains("bg_") || l.contains("localbg")
                || l.contains("theme") || l.contains("main_") || l.contains("splash")
                || l.contains("player") || l.contains("cover") || l.contains("image")
                || l.endsWith(".webp") || l.endsWith(".png") || l.endsWith(".jpg");
        XposedCompat.log(TAG + (interesting ? " asset* " : " asset  ") + name);
    }

    private static final java.util.Set<String> ASSET_PROBED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile int sAssetProbeCount;

    /**
     * 带缓存的资源项名查询（见 {@link #ENTRY_CACHE} 注释：不缓存会把宿主拖到几乎 ANR）。
     *
     * @return 资源项名；不属于该 Resources 时返回 null
     */
    private static String entryNameOf(Resources res, int id) {
        String cached = ENTRY_CACHE.get(id);
        if (cached != null) {
            // ★★★ v0.1.34：播放页指纹必须**每次**打点。只在首次解析时打一次的话，
            //   用户退出播放页再进另一个作品，绑定就失效了（r39「还是糊」的隐患之一）。
            if (PLAY_MARK_IDS.contains(id)) BgPeel.notePlayPage();
            return cached.isEmpty() ? null : cached;
        }

        String entry;
        try {
            entry = res.getResourceEntryName(id);
        } catch (Throwable t) {
            entry = MISS;      // 不属于这套 Resources（最常见：系统 Resources 收到 app 的资源 id）
        }
        if (entry == null) entry = MISS;
        if (ENTRY_CACHE.size() > ENTRY_CACHE_MAX) ENTRY_CACHE.clear();
        ENTRY_CACHE.put(id, entry);
        if (!entry.isEmpty()) {
            probe(entry);
            if (isPlayMark(entry)) {
                PLAY_MARK_IDS.add(id);
                BgPeel.notePlayPage();
            }
        }
        return entry.isEmpty() ? null : entry;
    }

    /**
     * ★★★ v0.1.34：播放页指纹。
     *
     * <p>r39 日志里 {@code seekbar_track_material} / {@code seekbar_thumb_material_anim}
     * 只在播放页那一段出现；播放列表 / 分类详情页不会加载进度条。
     * 宿主对这两种页面的背景图用的是同一个组件、同一个 {@code blurRadius=10}，
     * 唯一能区分的就剩「有没有 seekbar」。
     */
    private static boolean isPlayMark(String entry) {
        String l = entry.toLowerCase(java.util.Locale.ROOT);
        return l.contains("seekbar");
    }

    /** 已确认为「播放页指纹」的资源 id（命中即打点，O(1)）。 */
    private static final java.util.Set<Integer> PLAY_MARK_IDS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 资源名探针。
     *
     * <p>★ 不再只记"像背景的"：真机教训是过滤太窄时，宿主到底取了什么完全看不见
     * （日志里只有一条 activityBackground，导致连续几轮都在猜）。现在全部记下来、
     * 重点名标 {@code res*}。
     */
    private static void probe(String entry) {
        if (sProbeCount >= PROBE_MAX) return;
        if (!PROBED.add(entry)) return;
        sProbeCount++;
        String l = entry.toLowerCase(java.util.Locale.ROOT);
        boolean interesting = l.contains("visual") || l.contains("background")
                || l.contains("_bg") || l.contains("bg_") || l.contains("localbg")
                || l.contains("theme") || l.contains("main_") || l.contains("splash")
                || l.contains("player") || l.contains("cover");
        XposedCompat.log(TAG + (interesting ? " res* " : " res  ") + entry);
    }

    private static final java.util.Set<String> PROBED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int PROBE_MAX = 160;
    private static volatile int sProbeCount;

    /** 资源 id 是否是 F2 关心的那几张（只用于诊断摘要，不影响任何行为）。 */
    private static boolean isOpenPage(String entry) {
        if (entry == null) return false;
        for (String p : OPEN_PAGES) {
            if (entry.contains(p)) return true;
        }
        return false;
    }

    /**
     * 给 {@link WindowBg} / {@link BgPeel} 用：窗口/内容层背景 Drawable。
     *
     * <p>{@code null} = 这次不要动宿主背景（关掉开关、没选图、壁纸读不到）。
     *
     * <p>⚠️ 这里**绝不能**返回「透明」—— v0.1.9 的整屏发白就是它造成的。
     * 要「不干预」就返回 null，让调用方把宿主原背景还原回去。
     */
    public static Drawable windowDrawable(MoriConfig cfg) {
        return cachedDrawable(cfg);
    }

    /**
     * 同一份配置复用**同一个** {@link CoverDrawable} 实例（v0.1.12）。
     *
     * <p>为什么要复用：它现在被用在 {@code LinearGradientView#onDraw} 里 —— 那是
     * **每帧都跑**的热路径。每帧 new 一个 drawable + 两个 Paint，GC 压力会直接反映成掉帧。
     * 缓存键包含「来源 + 遮罩 + 模糊」，任一变化就重建（配置变更时会先
     * {@link #invalidate()} 清空）。
     *
     * @return {@code null} = 这次没有可画的壁纸（关掉 / 没选 / 位图读不到）
     */
    public static Drawable cachedDrawable(MoriConfig cfg) {
        if (cfg == null || !cfg.f2Enabled) return null;
        String key = (cfg.f2DesktopWall ? "@desk" : cfg.f2HomeBg)
                + "|m" + cfg.f2MaskAlpha + "|b" + cfg.f2Blur;
        CoverDrawable cur = sDrawable;
        if (cur != null && cur.valid() && key.equals(sDrawableKey)) return cur;

        Bitmap bmp = cachedBitmap(cfg);
        if (bmp == null) {
            sBmpOk = false;
            return null;
        }
        sBmpOk = true;
        CoverDrawable made = new CoverDrawable(bmp, cfg.f2MaskAlpha);
        sDrawable = made;
        sDrawableKey = key;
        return made;
    }

    /** 当前这份配置对应的 drawable（可能为 null）。 */
    private static volatile CoverDrawable sDrawable;

    private static volatile String sDrawableKey = "";

    /** 顶栏 / 底栏毛玻璃用的「模糊壁纸位图」（v0.1.18 改成位图，不再用 Drawable）。 */
    private static volatile Bitmap sBarBmp;

    private static volatile String sBarKey = "";

    /** 顶栏 / 底栏的模糊强度（0–100，越大越糊）。r25 用户指定：20%。 */
    public static final int BAR_BLUR_PCT = 20;

    /** 顶栏 / 底栏压在上面的黑色蒙层（%），保证图标文字在亮壁纸上也看得清。r25：8%。 */
    public static final int BAR_MASK_PCT = 8;

    /**
     * ★ 顶栏 / 底栏的毛玻璃底（v0.1.17）。
     *
     * <p>和 {@link #cachedDrawable} 是<b>同一张图、同一套 center-crop</b>，
     * 只是先做了一次强模糊 ⇒ 在「设定」顶栏、迷你播放控件、底部标签栏的位置上画它，
     * 就等于把壁纸糊了一层（区域高斯模糊），而不会和清晰壁纸错位。
     *
     * <p>只解码一次并缓存（换图 / 改模糊才会重建）。解码失败返回 {@code null}，
     * 调用方保持宿主原样。
     */
    public static Bitmap barBitmap(MoriConfig cfg) {
        if (cfg == null || !cfg.f2Enabled) return null;
        String key = (cfg.f2DesktopWall ? "@desk" : cfg.f2HomeBg)
                + "|bar" + BAR_BLUR_PCT + "|" + cfg.f2Blur;
        Bitmap cur = sBarBmp;
        if (cur != null && !cur.isRecycled() && key.equals(sBarKey)) return cur;

        // ★ 必须基于**用户已配好遮罩/模糊的那张位图**再糊一层，
        //   否则顶栏会比页面亮一个档次（r22 就是这么错的）。
        Bitmap base = cachedBitmap(cfg);
        if (base == null) return null;
        Bitmap blurred = io.github.ariinyume.morimod.util.BlurUtil.apply(base, BAR_BLUR_PCT);
        if (blurred == null) return null;
        sBarBmp = blurred;
        sBarKey = key;
        return blurred;
    }

    /** ★ 画壁纸用的**位图**（v0.1.18）。 */
    public static Bitmap wallpaperBitmap(MoriConfig cfg) {
        if (cfg == null || !cfg.f2Enabled) return null;
        Bitmap b = cachedBitmap(cfg);
        return b == null || b.isRecycled() ? null : b;
    }

    /** 播放页用的（再糊一层 {@link MoriConfig#f2PlayBlur}）壁纸位图（v0.1.21）。 */
    private static volatile Bitmap sPlayBmp;
    private static volatile String sPlayKey = "";

    /**
     * ★★ 播放页背景（v0.1.21 用户第 1 条）。
     *
     * <p>宿主播放页的底色是它自己那张「已经糊过的紫色背景图」
     * （{@code assets_images_playbackspbackground.webp}，1081×1920 RGBA），由 expo-image
     * 当内容图画上去。v0.1.21 起 {@code BgPeel} 把它整层换成<b>用户壁纸</b>；
     * 但播放页要压住封面和歌词，清晰壁纸会抢内容 ⇒ 这里再按用户档位糊一层。
     *
     * <p>与 {@link #barBitmap} 的区别：那条固定 20%（顶栏/底栏毛玻璃），这条是用户可调的
     * 0–100%。两条都基于 {@link #cachedBitmap}（已含全局 {@code f2Blur}），所以不会比页面亮。
     *
     * <p>0% 时**直接复用**清晰壁纸（不复制、不再模糊）。
     */
    public static Bitmap playBgBitmap(MoriConfig cfg) {
        if (cfg == null || !cfg.f2Enabled) return null;
        int pct = Math.max(0, Math.min(100, cfg.f2PlayBlur));
        String key = (cfg.f2DesktopWall ? "@desk" : cfg.f2HomeBg)
                + "|play" + pct + "|" + cfg.f2Blur;
        Bitmap cur = sPlayBmp;
        if (cur != null && !cur.isRecycled() && key.equals(sPlayKey)) return cur;

        Bitmap base = cachedBitmap(cfg);
        if (base == null) return null;
        Bitmap out = pct > 0
                ? io.github.ariinyume.morimod.util.BlurUtil.apply(base, pct)
                : base;
        if (out == null) return null;
        sPlayBmp = out;
        sPlayKey = key;
        return out;
    }

    // ★★★ v0.1.39（r44 用户第 1 条）：**「糊两遍」的玻璃底图已删除**。
    //   v0.1.38 用它让面板「看得出是毛玻璃」，真机结果是壁纸被糊成色块，
    //   面板里看不到任何背景轮廓 —— 用户第 1 条就是「概述页面的高斯模糊和之前不一样了」。
    //   现在玻璃统一用 barBitmap()（壁纸 + BAR_BLUR_PCT 单次模糊），见 BgPeel#glassBmp。

    /**
     * 配置上「想要铺壁纸」吗（图片或桌面壁纸，且开关打开）。
     *
     * <p>⚠️⚠️ 这只是**意愿**，不代表真的画得出来 —— 壁纸可能读不到
     * （桌面壁纸在部分 ROM 上取不到、素材通道还没连上、文件被删）。
     * 要判断「能不能真的画」必须用 {@link #canPaint(MoriConfig)}。
     */
    public static boolean hasWallpaper(MoriConfig cfg) {
        if (cfg == null || !cfg.f2Enabled) return false;
        if (cfg.f2DesktopWall) return true;
        return cfg.f2HomeBg != null && !cfg.f2HomeBg.isEmpty();
    }

    /**
     * 壁纸位图**是否真的拿到了**（v0.1.11）。
     *
     * <h3>为什么必须单独有这个判据</h3>
     * v0.1.10 真机：用户选「桌面壁纸」，宿主进程里 {@code WallpaperManager} 读不到，
     * 于是 {@code windowDrawable()} 全程返回 {@code null}（一层都没铺），<b>但</b>
     * {@link #hasWallpaper} 依然为 true ⇒ {@code BgPeel} 把全屏渐变层的
     * {@code onDraw} 跳过了。结果：宿主的深色渐变消失、壁纸又没铺上，
     * 露出渐变层下面那层 {@code #F2F2F2} 浅灰白 ⇒ <b>整屏发白</b>。
     *
     * <p>所以「跳过渐变」这类**破坏性**操作，判据必须是这个，而不是意愿。
     *
     * <p>性能：`LinearGradientView#onDraw` 是每帧热路径，这里不重新解码 ——
     * 只读 {@link #sBmpOk}（由 {@link #cachedBitmap} 在每次取图后更新）。
     */
    public static boolean canPaint(MoriConfig cfg) {
        return hasWallpaper(cfg) && sBmpOk;
    }

    /** 最近一次取壁纸位图的结果（{@link #cachedBitmap} 维护，每帧只读）。 */
    private static volatile boolean sBmpOk;

    /**
     * 用户壁纸位图（含模糊处理）。
     *
     * <p>缓存键 = 素材来源 + 模糊强度：同一张图不同模糊并存时不会互相顶掉。
     * 模糊只在这里做（一次性），{@link CoverDrawable} 每帧只是画一张位图。
     */
    private static Bitmap cachedBitmap(MoriConfig cfg) {
        boolean desk = cfg.f2DesktopWall;
        int blur = Math.max(0, Math.min(100, cfg.f2Blur));
        String key = desk ? "@desktop" : cfg.f2HomeBg;
        String ck = key + "|b" + blur;
        if (ck.equals(sCachedBmpKey) && sCachedBmp != null && !sCachedBmp.isRecycled()) {
            return sCachedBmp;
        }
        // ⚠️ 宿主进程没有 READ_MEDIA_IMAGES，WallpaperManager 的 drawable / fast /
        //    file×2 四个入口**全部抛 SecurityException**（r16 真机日志，每 2 秒刷一屏）。
        //    桌面壁纸现在由设置页进程读好 → 存成普通素材 → 走 f2HomeBg，这里不再尝试。
        Bitmap b = desk ? null : ModuleAssets.decodeBitmap(key);
        if (b == null) {
            // 不缓存失败：素材可能还在等跨 uid 服务连上，下一次渲染再试。
            // 但降级日志只报一次 —— 这里是超热路径，每次都写会把日志刷爆。
            if (!sDegradeLogged) {
                sDegradeLogged = true;
                DiagLog.degrade("F2", desk
                        ? "读不到桌面壁纸，本轮回落宿主原背景"
                        : "背景素材暂不可用，本轮回落宿主原图（BR-2.5）");
            }
            return null;
        }
        if (blur > 0) {
            Bitmap blurred = io.github.ariinyume.morimod.util.BlurUtil.apply(b, blur);
            if (blurred != b) {
                // ⚠️ 桌面壁纸是系统缓存里的对象，不能 recycle（下一次还会拿到它）；
                //    用户图是我们自己 decode 出来的，及时还内存。
                if (!desk) {
                    try {
                        b.recycle();
                    } catch (Throwable ignored) {
                    }
                }
                b = blurred;
            }
        }
        sCachedBmp = b;
        sCachedBmpKey = ck;
        return b;
    }

    private static void invalidate() {
        sCachedBmp = null;
        sCachedBmpKey = "";
        sDrawable = null;
        sDrawableKey = "";
        sBarBmp = null;
        sBarKey = "";
        sPlayBmp = null;
        sPlayKey = "";
        sBmpOk = false;
        sDegradeLogged = false;
        WallpaperBg.invalidate();
    }
}
