package io.github.ariinyume.morimod.hook;

import android.content.res.AssetManager;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.io.File;
import java.lang.reflect.Method;

import io.github.ariinyume.morimod.config.MoriConfig;
import io.github.ariinyume.morimod.config.RemoteConfig;
import io.github.ariinyume.morimod.util.DiagLog;
import io.github.ariinyume.morimod.util.XposedCompat;

/**
 * F4 · 全局强制替换字体（D-10 / BR-4.1 / BR-4.2）。
 *
 * <p>hook {@code android.graphics.Typeface} 的四个工厂方法；先过**图标字体硬编码黑名单**，
 * 未命中则返回用户字体（framework API，不受宿主 R8 影响，跨版本稳定）。
 *
 * <p>⚠️ BR-4.2：黑名单是编译期常量，**不提供用户配置入口**（SEC-06）。
 * ⚠️ BR-4.4：粗斜体请求必须一并处理（{@code create(base, style)} 重载），不合成加粗。
 */
public final class FeatureF4 {

    private static final String TAG = "[MoriMod:F4]";

    /**
     * 图标字体黑名单 —— 永不替换。命中即返回宿主原 Typeface，不做任何提示（BR-4.2）。
     * 含 PRD 明列的 MaterialSymbols / MaterialCommunityIcons / FontAwesome(全家族) /
     * expomodules.font，并补齐 RN 常用矢量图标族作为安全阀。
     */
    private static final String[] ICON_BLACKLIST = {
            "MaterialSymbols", "MaterialCommunityIcons", "MaterialIcons",
            "FontAwesome", "FontAwesome5Free", "FontAwesome5Brands", "FontAwesome5Solid",
            "expomodules.font", "ExpoFontLoader",
            "Ionicons", "Feather", "Entypo", "AntDesign", "Foundation", "Octicons",
            "Zocial", "SimpleLineIcons", "EvilIcons",
    };

    /**
     * 黑名单的**小写预计算**版本。
     *
     * <p>⚠️ v0.0.7 性能修正：`isIconName` 在 `Typeface.create*` 超热路径上，
     * 原实现对每次调用都做 19 次 `toLowerCase`（每次分配新字符串）。
     * 宿主是 RN，每帧都可能创建 Typeface，这个开销直接把 UI 线程拖慢
     * （用户体感就是「点什么都不动」）。这里只算一次。
     */
    private static final String[] ICON_BLACKLIST_LOWER;

    static {
        String[] lower = new String[ICON_BLACKLIST.length];
        for (int i = 0; i < ICON_BLACKLIST.length; i++) {
            lower[i] = ICON_BLACKLIST[i].toLowerCase(java.util.Locale.ROOT);
        }
        ICON_BLACKLIST_LOWER = lower;
    }

    /** 重入闸：我们自己的调用（探族名 / 造型）不能再被替换，否则无限递归。 */
    private static final ThreadLocal<Boolean> IN = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * 图标字体的**身份快查表**（v0.1.2）。
     *
     * <p>{@code Paint#setTypeface} 是每帧级热路径，不能在里面做
     * {@code synchronized WeakHashMap.containsKey}（有锁 + 弱引用开销）。
     * 图标字体对象总共就那么几个，直接按身份扫一个只增不减的数组即可
     * （超上限就整体清空重来，不会无限增长）。
     */
    private static final Typeface[] ICON_FAST = new Typeface[64];
    private static volatile int sIconCount;

    private static volatile Typeface sBase;      // 用户字体基准
    private static volatile boolean sBaseReady;  // 已尝试解析
    /** 解析时的配置指纹（分别存两份，热路径上不做字符串拼接）。 */
    private static volatile String sBaseFile = "";
    private static volatile String sBaseFam = "";
    private static volatile boolean sFirstLogged;

    /** 字体文件读取失败次数（仅用于日志；**永不放弃重试**，见 resolveBase）。 */
    private static volatile int sFailCount;
    /**
     * 下一次允许重试的时刻（v0.1.4）。
     *
     * <p>⚠️ 这是「改了字体但要重启宿主」的最后一个坑（真机日志 19:16:01）：
     * 旧实现「失败 8 次就认命降级」，而那 8 次是在**同一帧里**发生的（热路径每帧几十次调用），
     * 也就是说 5 毫秒内就烧完了全部重试额度 —— 而素材跨进程搬运要 50~3000 毫秒。
     * 于是刚启动/刚换字体那一下必然「永久放弃」，用户看到的就是「字体换了没反应」。
     *
     * <p>现在改成**按时间冷却**：失败后 400ms 内不再尝试（不拖慢渲染），过了就再试，
     * 直到读到为止。素材一到，下一次文本布局就能用上。
     */
    private static volatile long sNextTryAt;

    /** 字体读取失败后的冷却时长（毫秒）。见 {@link #sNextTryAt}。 */
    private static final long RETRY_COOLDOWN_MS = 400L;

    /**
     * 「正在加载」占位时长（毫秒）。
     *
     * <p>读一个 8~16 MB 字体 + 建 native Typeface 可能要几百毫秒，
     * 这期间不能让别的线程再进 {@link #resolveBase} 重复读一遍（首帧会卡）。
     * 3 秒是上限：真卡到 3 秒说明出事了，允许再试。
     */
    private static final long LOAD_GUARD_MS = 3000L;

    /** 探测计数器（v0.1.3，见 {@link #probeLater()}）：只增不减，热路径上就是一次 volatile 自增。 */
    private static volatile int sTfCalls;
    private static volatile int sPaintCalls;
    /** 宿主用「系统默认字体」（{@code setTypeface(null)}）的次数 —— v0.1.3 起这种也要替换。 */
    private static volatile int sNullTfCalls;

    /** 派生样式缓存：下标 = Typeface.NORMAL/BOLD/ITALIC/BOLD_ITALIC（0..3）。 */
    private static final Typeface[] STYLED = new Typeface[4];
    /** 该缓存对应的基准字体（身份比较，基准换了整组作废）。 */
    private static volatile Typeface sStyledBase;

    /**
     * 粗体 / 粗斜体两份派生字体（身份标记）。
     *
     * <p>{@link #applyBold} 靠**对象身份**判断「这个 Typeface 是不是粗体变体」——
     * 没有公开 API 能从 {@code Typeface} 反查它是否请求过 BOLD，
     * 所以自己在派生时记下来。单字重字体下这两份的字形与常规一致，
     * 区别只在 Paint 的 {@code fakeBoldText} 标记上。
     */
    private static volatile Typeface sBoldFace;
    private static volatile Typeface sBoldItalicFace;

    private FeatureF4() {
    }

    /** 入参形态：族名 / 文件路径（createFrom*、create(String,int)）。 */
    private static final int MODE_NAME = 0;
    /** 入参形态：(Typeface base, int style)。 */
    private static final int MODE_BASE = 1;
    /** 入参形态：只有一个 int style（defaultFromStyle）。 */
    private static final int MODE_STYLE = 2;
    /** 入参形态：(String name, int weight, boolean italic) —— API 28+ 的字重重载。 */
    private static final int MODE_NAME_WEIGHT = 3;
    /** 入参形态：(Typeface base, int weight, boolean italic) —— API 28+。 */
    private static final int MODE_BASE_WEIGHT = 4;

    public static void install(ClassLoader cl) {
        try {
            Class<?> tf = XposedCompat.findClass("android.graphics.Typeface", cl);
            // ★ v0.1.3：不再写死重载签名，改成**按名字扫出所有存在的重载**再逐个挂钩。
            //   上一版写死的 `create(String, int, boolean)` 在真机上并不存在
            //   （日志：`[MoriMod:F4] hook create failed: android.graphics.Typeface#create(String, int, boolean)`），
            //   写死签名意味着「换个 ROM/版本就少挂几个入口」，而少挂的那一个恰好可能是
            //   RN 实际调用的那个。扫描法永远只挂真实存在的，失败也会单独记日志。
            int ok = 0;
            ok += hookAll(tf, "create");
            ok += hookAll(tf, "createFromAsset");
            ok += hookAll(tf, "createFromFile");
            ok += hookAll(tf, "defaultFromStyle");
            DiagLog.install("F4:Typeface", ok > 0, ok > 0 ? null : "no overload hooked");
            // ★★ v0.1.2 的真正主通路：见 {@link #hookPaint}。
            boolean paintHooked = hookPaint(cl);
            DiagLog.install("F4:Paint", paintHooked, paintHooked ? null : "Paint.setTypeface not found");
            RemoteConfig.addListener(FeatureF4::invalidate);
            XposedCompat.log(TAG + " hooked " + ok + " Typeface entries, paint=" + paintHooked);
            probeLater();
            // ★ v0.1.4：装完就在后台把用户字体解析好。
            //   宿主此刻还没 Application，但模块侧的 remote 通道已经可读（配置在 install 之前就读完了），
            //   所以这一步经常能赶在 RN 首帧之前完成 ⇒「先改设置再开 App」也能一次就对。
            prewarm();
        } catch (Throwable t) {
            DiagLog.install("F4:Typeface", false, t.getClass().getSimpleName());
            XposedCompat.log(TAG + " install failed: " + t);
        }
    }

    /** 把某个名字下的**全部重载**都挂上（存在的才挂，逐个记日志）。 */
    private static int hookAll(Class<?> cls, String name) {
        int ok = 0;
        for (Method m : XposedCompat.findMethodsByName(cls, name)) {
            int mod = m.getModifiers();
            // 只挂 public static：private/hidden 的内部助手（不同 ROM 名字会撞）
            // 一旦被我们改写返回值，破坏的是框架自己的初始化路径。
            if (!java.lang.reflect.Modifier.isStatic(mod)
                    || !java.lang.reflect.Modifier.isPublic(mod)) continue;
            if (m.getReturnType() != Typeface.class) continue;
            if (hookMethod(m)) ok++;
        }
        return ok;
    }

    private static boolean hookMethod(Method m) {
        final int mode = modeOf(m);
        try {
            XposedCompat.hookMethod(m, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(io.github.libxposed.api.XposedInterface.Chain chain,
                                       Object result) {
                    try {
                        return replace(chain, result, mode);
                    } catch (Throwable t) {
                        return result; // 钩子体绝不抛回宿主
                    }
                }
            });
            XposedCompat.log(TAG + " hooked Typeface#" + m.getName() + sigOf(m));
            return true;
        } catch (Throwable t) {
            XposedCompat.log(TAG + " hook Typeface#" + m.getName() + sigOf(m) + " failed: "
                    + t.getMessage());
            return false;
        }
    }

    /** 由真实参数类型反推「入参形态」，不再靠调用点手工标注（少一处能写错的地方）。 */
    private static int modeOf(Method m) {
        Class<?>[] p = m.getParameterTypes();
        if (p.length == 1 && p[0] == int.class) return MODE_STYLE;
        boolean base = p.length > 0 && p[0] == Typeface.class;
        boolean weighted = p.length >= 3;
        if (base) return weighted ? MODE_BASE_WEIGHT : MODE_BASE;
        return weighted ? MODE_NAME_WEIGHT : MODE_NAME;
    }

    private static String sigOf(Method m) {
        StringBuilder sb = new StringBuilder("(");
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(p[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    /**
     * 15 秒后打一行**探测日志**：钩子到底有没有被调用过。
     *
     * <p>为什么必须要有它：上一轮真机日志里两个功能「一条命中日志都没有」，
     * 而仅凭「没日志」无法区分「钩子没生效」和「钩子生效了但一次都没被调用」。
     * 这三个计数器把这件事一次性钉死：
     * <ul>
     *   <li>{@code tf=0 paint=0} ⇒ 挂钩没生效（框架/内联问题）；</li>
     *   <li>{@code paint>0} 但界面没变 ⇒ 替换逻辑或素材读取的问题；</li>
     *   <li>{@code nullTf>0} ⇒ 宿主确实用「系统默认字体」，v0.1.3 起这种也会被替换。</li>
     * </ul>
     */
    private static void probeLater() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(15_000L);
            } catch (InterruptedException ignored) {
                return;
            }
            XposedCompat.log(TAG + " probe tf=" + sTfCalls + " paint=" + sPaintCalls
                    + " nullTf=" + sNullTfCalls + " base=" + (sBase != null)
                    + " fail=" + sFailCount + " cfg=" + RemoteConfig.get().f4Enabled);
        }, "MoriMod-F4-probe");
        t.setDaemon(true);
        t.start();
    }

    private static Object replace(io.github.libxposed.api.XposedInterface.Chain chain,
                                  Object result, int mode) {
        if (Boolean.TRUE.equals(IN.get())) return result;
        sTfCalls++;
        MoriConfig cfg = RemoteConfig.get();
        if (!cfg.f4Enabled) return result;

        int style = Typeface.NORMAL;
        Object[] args = chain.getArgs().toArray();

        if (mode == MODE_BASE || mode == MODE_BASE_WEIGHT) {
            // create(Typeface base, int style) / create(Typeface base, int weight, boolean italic)
            Object base = args.length > 0 ? args[0] : null;
            style = mode == MODE_BASE
                    ? intOr(args.length > 1 ? args[1] : null, Typeface.NORMAL)
                    : styleOf(args.length > 1 ? args[1] : null, args.length > 2 ? args[2] : null);
            // 基准是图标字体 ⇒ 跳过
            if (base instanceof Typeface && isIconFace((Typeface) base)) {
                DiagLog.countOnce("F4:blacklist-hit");
                return result;
            }
        } else if (mode == MODE_STYLE) {
            // defaultFromStyle(int style)
            style = intOr(args.length > 0 ? args[0] : null, Typeface.NORMAL);
        } else {
            Object src = args.length > 0 ? args[0] : null;
            String nm = src instanceof File ? ((File) src).getPath()
                    : (src instanceof String ? (String) src : null);
            if (nm != null && isIconName(nm)) {
                // 登记图标字体对象：后续 create(base,style) / Paint 见到它就跳过（保命）
                if (result instanceof Typeface) rememberIconFace((Typeface) result);
                DiagLog.countOnce("F4:blacklist-hit");
                return result;
            }
            style = mode == MODE_NAME_WEIGHT
                    ? styleOf(args.length > 1 ? args[1] : null, args.length > 2 ? args[2] : null)
                    : intOr(args.length > 1 ? args[1] : null, Typeface.NORMAL);
        }

        Typeface base = resolveBase(cfg);
        if (base == null) return result;

        try {
            Typeface styled = styledFor(base, style);
            if (styled == null) return result;
            if (!sFirstLogged) {
                sFirstLogged = true;
                DiagLog.trigger("F4:Typeface");
            }
            return styled;
        } catch (Throwable t) {
            return result;
        }
    }

    /**
     * 取「基准字体 + style」的派生字体。
     *
     * <p>⚠️ v0.0.7：`Typeface.create` 在 RN 里是每帧级调用（每次文本布局都可能派生
     * 粗/斜体）。原实现每次新建 Typeface 对象，等于在宿主 UI 线程上做无谓的
     * native 调用。style 只有 4 种（NORMAL/BOLD/ITALIC/BOLD_ITALIC），
     * 按 (基准, style) 缓存后重复创建归零。
     *
     * <p>基准字体对象换了（用户改字体 / 配置变更）就整组失效 —— 用对象身份比较，
     * 不依赖字符串，天然跟着 [resolveBase] 的缓存走。
     */
    private static Typeface styledFor(Typeface base, int style) {
        IN.set(Boolean.TRUE);
        try {
            if (style < 0 || style > 3) {
                return Typeface.create(base, style);   // 非常规位标志：不缓存
            }
            if (sStyledBase != base) {
                sStyledBase = base;
                for (int i = 0; i < STYLED.length; i++) STYLED[i] = null;
                sBoldFace = null;
                sBoldItalicFace = null;
            }
            Typeface cached = STYLED[style];
            if (cached != null) return cached;
            Typeface t = Typeface.create(base, style);
            STYLED[style] = t;
            if (style == Typeface.BOLD) sBoldFace = t;
            else if (style == (Typeface.BOLD | Typeface.ITALIC)) sBoldItalicFace = t;
            return t;
        } finally {
            IN.set(Boolean.FALSE);
        }
    }

    /** 解析用户字体基准（文件优先，其次系统族名）。 */
    private static Typeface resolveBase(MoriConfig cfg) {
        // ⚠️ 这里是 Paint#setTypeface 的热路径，**不能**每次拼字符串做指纹
        //    （每帧几十上百次调用，拼接就是每帧上百次分配）。
        String file0 = cfg.f4FontFile == null ? "" : cfg.f4FontFile;
        String fam0 = cfg.f4FontFamily == null ? "" : cfg.f4FontFamily;
        if (sBaseReady && file0.equals(sBaseFile) && fam0.equals(sBaseFam)) return sBase;

        // ★ v0.1.5：冷却 / 正在加载 期间**返回上一次的基准字体**，不返回 null。
        //   旧实现在这里先把 `sBase = null` 再加载，于是：
        //     · 并发时（prewarm 线程与首帧同时进来）另一个线程看到 sBaseReady=true
        //       就直接拿到 null ⇒ 那一次 setTypeface 被整体跳过；
        //     · 换了字体但文件还没搬完时，界面会先回落到系统字体再跳回来（闪一下）。
        //   现在「加载中」用 sNextTryAt 占位（不置 null），失败则保留旧字体继续用。
        long now = System.currentTimeMillis();
        if (now < sNextTryAt) return sBase;
        sNextTryAt = now + LOAD_GUARD_MS;     // 占位：同一时刻只有一个线程去读 8MB

        Typeface got = null;
        if (cfg.f4FontFile != null && !cfg.f4FontFile.isEmpty()) {
            // v0.0.9：「使用系统字体」改成列**真实的字体文件**（/system/fonts/*.ttf|ttc），
            // 这些路径本进程直接可读，没必要走 ModuleAssets 的字节搬运 + 落临时文件。
            got = loadDirectFile(cfg.f4FontFile);
            if (got != null) {
                XposedCompat.log(TAG + " system font file loaded: " + cfg.f4FontFile);
            } else {
                got = ModuleAssets.loadTypeface(cfg.f4FontFile);
                if (got != null) {
                    XposedCompat.log(TAG + " user font file loaded: " + cfg.f4FontFile);
                }
            }
        }
        if (got == null && cfg.f4FontFamily != null && !cfg.f4FontFamily.isEmpty()) {
            IN.set(Boolean.TRUE);
            try {
                got = Typeface.create(cfg.f4FontFamily, Typeface.NORMAL);
            } catch (Throwable ignored) {
                got = null;
            } finally {
                IN.set(Boolean.FALSE);
            }
            if (got != null) {
                XposedCompat.log(TAG + " user font family resolved: " + cfg.f4FontFamily);
                // ⚠️ v0.0.9：sans-serif 就是 Android 默认族，替换后**看起来完全没变化**。
                //    真机上用户选的正是它，于是判定「字体不生效」。这里留一条自证日志。
                if (got == Typeface.DEFAULT) {
                    XposedCompat.log(TAG + " 注意：所选系统族与宿主默认一致（"
                            + cfg.f4FontFamily + "），视觉上不会有变化");
                }
            }
        }

        if (got != null) {
            sBase = got;
            sBaseFile = file0;
            sBaseFam = fam0;
            sBaseReady = true;
            sNextTryAt = 0L;
            sFailCount = 0;
            verifyMetrics(got);
            return got;
        }

        // ── 全部来源都没拿到 ──
        if (cfg.f4FontFile != null && !cfg.f4FontFile.isEmpty()) {
            // ⚠️⚠️ v0.1.4：**永远不放弃**，只做时间冷却。
            //   旧版「8 次就认命降级」的 8 次在同一帧内就烧完了（见 sNextTryAt 注释），
            //   素材 50~3000ms 后才到，于是「刚换字体 → 重启宿主 → 没生效」。
            sFailCount++;
            sBaseReady = false;
            sNextTryAt = System.currentTimeMillis() + RETRY_COOLDOWN_MS;
            if (sFailCount <= 3 || sFailCount % 20 == 0) {
                XposedCompat.log(TAG + " 字体文件暂不可读（第 " + sFailCount + " 次，"
                        + RETRY_COOLDOWN_MS + "ms 后重试）: " + cfg.f4FontFile);
            }
            // 顺手把素材预取再排一次队（幂等，真的在缓存里就不会发请求）
            android.content.Context app = io.github.ariinyume.morimod.util.AppEnv.app();
            if (app != null) {
                try {
                    AssetSync.prefetch(app, ModuleAssets.relativeName(cfg.f4FontFile));
                } catch (Throwable ignored) {
                }
            }
        } else {
            sBaseReady = true;   // 没配字体，确认"不需要基准"
            sNextTryAt = 0L;
            if (sBase == null) DiagLog.degrade("F4", "未指定可用字体，保持宿主原字体");
        }
        return sBase;
    }

    /** 度量自证用的混合串：拉丁 + 汉字 + 数字，任何字体都会有差异。 */
    private static final String METRIC_SAMPLE = "Mori字体Ag0123";

    /**
     * 字体**是否真的生效**的自证（v0.1.5）。
     *
     * <p>为什么必须有它：{@code Typeface.createFromFile} 对一个"表目录越界"的残文件
     * 依然返回非 null 对象，加载日志一行不差，可渲染时就是系统字形 ——
     * 上一轮 2471 次 setTypeface 全部改写成功、界面却毫无变化，全靠猜。
     * 这里用 {@link Paint#measureText} 量同一段混合文字：
     * 自定义字体与系统默认字体的**字体度量必然不同**，量出来完全一样就说明没生效。
     *
     * <p>只在基准字体真的换了一份时打一行，不在热路径上。
     */
    private static void verifyMetrics(Typeface tf) {
        try {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTextSize(64f);
            float def;
            float custom;
            IN.set(Boolean.TRUE);            // 不能让自己被自己的 setTypeface 钩子改写
            try {
                p.setTypeface(Typeface.DEFAULT);
                def = p.measureText(METRIC_SAMPLE);
                p.setTypeface(tf);
                custom = p.measureText(METRIC_SAMPLE);
            } finally {
                IN.set(Boolean.FALSE);
            }
            boolean same = Math.abs(def - custom) < 0.5f;
            XposedCompat.log(TAG + " metrics default=" + def + " custom=" + custom
                    + (same ? " **一致 ⇒ 字体未真正生效**" : " 不同 ⇒ 字体生效"));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 直接按路径加载字体（不打字节搬运）。
     *
     * <p>只对**本进程可读**的绝对路径生效（{@code /system/fonts/NotoSansCJK-Regular.ttc}
     * 这类系统字体）。模块私有目录里的字体仍然走 {@link ModuleAssets#loadTypeface}，
     * 因为跨 uid 必须经框架 / ContentProvider。
     *
     * <p>失败返回 null，由调用方继续走下一档 —— 绝不抛。
     */
    private static Typeface loadDirectFile(String path) {
        try {
            if (path == null || !path.startsWith("/")) return null;
            File f = new File(path);
            if (!f.isFile() || !f.canRead()) return null;
            IN.set(Boolean.TRUE);           // 自身调用不能再被替换（防递归）
            try {
                return Typeface.createFromFile(f);
            } finally {
                IN.set(Boolean.FALSE);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 配置变更 → 失效缓存，下次重建（并在后台**预热**，赶在首帧之前把字体读出来）。 */
    private static void invalidate() {
        MoriConfig now = RemoteConfig.get();
        String nf = now.f4FontFile == null ? "" : now.f4FontFile;
        String nfam = now.f4FontFamily == null ? "" : now.f4FontFamily;

        // ★★ v0.1.10：判据从「基准字体对象变了没」换成「**字体配置指纹**变了没」。
        //
        //   旧实现用 `prevBase != null && 指纹变了` 当条件（lateChange），于是有一个
        //   必然踩到的空档：用户本来没启用字体替换（f4Enabled=false），此时
        //   `replace()` / `prewarm()` 都直接 return，基准字体从来没解析过 ⇒ prevBase 是
        //   null ⇒ 直接点「插件内置字体」（enabled 由 false 变 true）时**不触发重排**。
        //   字体其实已经加载成功（日志有 font prewarm ok），但已经画好的文字不会自己
        //   重排（TextView 缓存了 Layout 里的字宽度量）⇒ 用户看到「选了没反应」，
        //   只有等他去拨一次开关、让 RN 重新渲染一次才突然生效 —— 就是这条。
        //
        //   现在把 enabled 也纳入指纹：只要「启用状态 / 字体文件 / 字体族」任一变化，
        //   就重排；并且连排 3 次（间隔 500ms），因为新字体是异步解析的，
        //   第一次重排很可能赶在它加载完之前。
        String key = (now.f4Enabled ? "1" : "0") + "|" + nf + "|" + nfam;
        boolean changed = !key.equals(sLastKey);
        sLastKey = key;

        sBaseReady = false;
        sBase = null;
        sBaseFile = "";
        sBaseFam = "";
        sFailCount = 0;
        sNextTryAt = 0L;
        sFirstLogged = false;
        sStyledBase = null;
        sBoldFace = null;
        sBoldItalicFace = null;
        for (int i = 0; i < STYLED.length; i++) STYLED[i] = null;
        prewarm();
        // 字体晚到时，已经画好的文字不会自己重排（Layout 缓存了旧字体度量）
        // ⇒ 必须补一次强制重排，而且要多试几次（见上面注释）。
        if (changed) {
            XposedCompat.log(TAG + " font config changed -> relayout ("
                    + (now.f4Enabled ? "on" : "off") + ", file="
                    + (nf.isEmpty() ? "-" : nf.substring(nf.lastIndexOf('/') + 1)) + ")");
            UiRefresh.relayoutText(3, 500L);
        }
    }

    /** 上一次处理过的字体配置指纹（见 {@link #invalidate}）。 */
    private static volatile String sLastKey = "";

    /**
     * 后台预热用户字体（v0.1.4）。
     *
     * <p>为什么要预热：{@code resolveBase} 只在**有文本要布局**时才被触发，
     * 而那一刻正是首帧 —— 读 16MB 字体 + 跨进程搬运会把首帧拖慢，且一旦这次失败，
     * 已经画好的文字不会自己重排。提前在后台把基准字体解析好，
     * 首帧就只剩一次 {@code sBase != null} 判断。
     *
     * <p>拿到字体后再打一行日志，配合「用户改了字体但没生效」的排查：
     * 只要看到 {@code font prewarm ok} 而界面没变，问题就在渲染层而不是素材层。
     */
    private static void prewarm() {
        MoriConfig cfg = RemoteConfig.get();
        if (!cfg.f4Enabled) return;
        if (cfg.f4FontFile == null || cfg.f4FontFile.isEmpty()) return;
        Thread t = new Thread(() -> {
            try {
                Typeface tf = resolveBase(RemoteConfig.get());
                if (tf != null) {
                    XposedCompat.log(TAG + " font prewarm ok: " + RemoteConfig.get().f4FontFile);
                }
            } catch (Throwable ignored) {
            }
        }, "MoriMod-F4-prewarm");
        t.setDaemon(true);
        t.start();
    }

    private static boolean isIconName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String b : ICON_BLACKLIST_LOWER) {
            if (lower.contains(b)) return true;
        }
        return false;
    }

    /**
     * 登记一个图标字体对象（{@code Paint} 层遇到它就原样放行）。
     *
     * <p>数组满 64 个就整体清空重来 —— 图标族数量是固定的十几个，
     * 真到 64 说明是宿主在动态造字体，此时清空比无限增长安全。
     */
    private static void rememberIconFace(Typeface tf) {
        if (tf == null) return;
        int n = sIconCount;
        if (n >= ICON_FAST.length) {
            for (int i = 0; i < ICON_FAST.length; i++) ICON_FAST[i] = null;
            sIconCount = 0;
            n = 0;
        }
        ICON_FAST[n] = tf;
        sIconCount = n + 1;
    }

    private static boolean isIconFace(Typeface tf) {
        if (tf == null) return false;
        int n = sIconCount;
        for (int i = 0; i < n; i++) {
            if (ICON_FAST[i] == tf) return true;
        }
        return false;
    }

    /** {@code int} 入参安全取值（框架可能给我们 null 或别的类型）。 */
    private static int intOr(Object v, int def) {
        return v instanceof Integer ? (Integer) v : def;
    }

    /**
     * 把 API 28+ 的 {@code (weight, italic)} 折算成老的四态 style。
     *
     * <p>⚠️ 这是**粗体丢失**的根因之一：单字重字体（思源 / 得意黑 / 用户导入的 ttf）
     * 在 {@code Typeface.create(base, BOLD)} 时并不会拿到真正的粗体字面，
     * Android 也不会自动合成加粗 —— 除非 Paint 打开了 {@code fakeBoldText}。
     * 所以这里只负责把「请求了 700 及以上」认出来，真正合成加粗在 {@link #hookPaint}。
     *
     * @param weightObj 字重（400 / 500 / 700…）
     * @param italicObj 是否斜体
     */
    private static int styleOf(Object weightObj, Object italicObj) {
        int weight = intOr(weightObj, 400);
        boolean italic = Boolean.TRUE.equals(italicObj);
        int style = weight >= 600 ? Typeface.BOLD : Typeface.NORMAL;
        if (italic) style |= Typeface.ITALIC;
        return style;
    }

    /**
     * 钩 {@code Paint#setTypeface(Typeface)} —— v0.1.2 的**主通路**，一次解决两个老问题。
     *
     * <h3>① 「时好时坏」：RN 自己缓存 Typeface，第一次赢就永远赢</h3>
     * React Native 的 {@code ReactFontManager} 按 (family, style, weight) 缓存
     * {@code Typeface} 对象。如果进程里第一次取字体发生在「配置还没读到」（当时是默认全关）时，
     * 我们返回宿主原字体、它被缓存住，之后整条链再也不调 {@code Typeface.create} ——
     * 即使配置随后到位也永远换不上。真机日志正是这样：13 次进程启动只有 2 次打出
     * {@code user font file loaded}。
     * 而 {@code Paint#setTypeface} **每次文本布局都会调**，在它这里替换就绕过了所有缓存。
     *
     * <h3>② 「粗细都一样」：单字重字体没有真正的 Bold 字面</h3>
     * 宿主原来用「常规 + 粗体」两个真实字面。换成单字重字体后，
     * {@code Typeface.create(base, BOLD)} 拿到的还是同一份字形 ⇒ 视觉上粗细完全一致。
     * 这里在替换的同时按 style 打开 {@code paint.setFakeBoldText(true)}，
     * 让 Android 做合成加粗 —— 标题仍然比正文粗，正文保持常规。
     */
    private static boolean hookPaint(ClassLoader cl) {
        try {
            Class<?> paint = XposedCompat.findClass("android.graphics.Paint", cl);
            Method m = XposedCompat.findMethodExact(paint, "setTypeface", Typeface.class);
            XposedCompat.hookMethod(m, new XposedCompat.VoidHook() {
                @Override
                protected void afterVoid(io.github.libxposed.api.XposedInterface.Chain chain) {
                    try {
                        onPaintTypeface(chain);
                    } catch (Throwable ignored) {
                        // 钩子体绝不抛回宿主
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            XposedCompat.log(TAG + " hook Paint.setTypeface failed: " + t.getMessage());
            return false;
        }
    }

    private static void onPaintTypeface(io.github.libxposed.api.XposedInterface.Chain chain) {
        if (Boolean.TRUE.equals(IN.get())) return;
        MoriConfig cfg = RemoteConfig.get();
        if (!cfg.f4Enabled) return;
        Object thisObj = chain.getThisObject();
        Object arg0 = chain.getArg(0);
        if (!(thisObj instanceof Paint)) return;
        sPaintCalls++;

        // ★★ v0.1.3 关键修复：{@code setTypeface(null)} 表示「用系统默认字体」，
        //    这是 **宿主最常见的一种调用**（TextView 没显式指定字体时 TextPaint 的
        //    typeface 本来就是 null）。旧实现直接 return ⇒ 这些控件永远是系统字体，
        //    用户看到的就是「换了自定义字体，页面一点没变」。
        //    null 不是「不要字体」，恰恰是「用默认字体」，必须一起换掉。
        Typeface tf = (arg0 instanceof Typeface) ? (Typeface) arg0 : null;
        if (arg0 != null && tf == null) return;          // 未知类型：放行
        if (tf != null && isIconFace(tf)) return;        // 图标字体：原样放行，否则图标变方块

        // 解析基准时把重入闸关上：它内部会调用 Typeface.createFromFile，
        // 那些调用是我们自己发起的，不该再被本钩子接一遍。
        Typeface base;
        IN.set(Boolean.TRUE);
        try {
            base = resolveBase(cfg);
        } finally {
            IN.set(Boolean.FALSE);
        }
        if (base == null) return;

        if (tf == null) {
            // 系统默认字体：换成基准字体的常规体，并保留调用方自己的合成样式标记
            // （加粗标题在上游已经走过 defaultFromStyle(BOLD)，不会落到这里）。
            sNullTfCalls++;
            Typeface normal = styledFor(base, Typeface.NORMAL);
            if (normal == null) return;
            IN.set(Boolean.TRUE);
            try {
                ((Paint) thisObj).setTypeface(normal);
            } finally {
                IN.set(Boolean.FALSE);
            }
            return;
        }
        if (tf == base) {                           // 已经是我们给的基准字体
            applyBold((Paint) thisObj, tf);
            return;
        }
        Typeface styled = styledFor(base, tf.getStyle());
        if (styled == null) return;
        if (tf == styled) {                         // 已经是派生好的那一份，不必再 set 一次
            applyBold((Paint) thisObj, tf);
            return;
        }
        IN.set(Boolean.TRUE);
        try {
            ((Paint) thisObj).setTypeface(styled);
        } finally {
            IN.set(Boolean.FALSE);
        }
        applyBold((Paint) thisObj, styled);
        if (!sFirstLogged) {
            sFirstLogged = true;
            DiagLog.trigger("F4:Paint");
        }
    }

    /** 单字重字体的合成加粗：只有 BOLD 变体才打开，其余一律关掉。 */
    private static void applyBold(Paint paint, Typeface tf) {
        boolean bold = tf == sBoldFace || tf == sBoldItalicFace;
        if (paint.isFakeBoldText() != bold) paint.setFakeBoldText(bold);
    }
}
