package io.github.ariinyume.morimod.hook;

import java.lang.reflect.Method;
import java.util.List;

import io.github.ariinyume.morimod.util.XposedCompat;

/**
 * 全局强调色的<b>唯一挂点层</b>（v0.1.24 用户第 1 条）。
 *
 * <h3>为什么换了挂点</h3>
 * v0.1.22 / v0.1.23 挂的是「结果端」：
 * {@code TextView#setTextColor} + 反射改 {@code BackgroundDrawable} 的颜色字段。
 * 真机两轮下来用户看到的仍是「满屏紫」，原因有两层：
 * <ol>
 *   <li>{@code BackgroundDrawable} 的字段被 R8 改名成单个字母，靠「实例 int 字段恰好唯一」
 *       才猜得到；宿主几乎每个色块都有<b>自己的</b> {@code Paint} / {@code ColorStateList} /
 *       {@code ColorFilter}，改字段这条路<b>天然漏一大半</b>；</li>
 *   <li>真正决定颜色的是 JS 传下来的 prop，它在 native 侧有<b>唯一收口</b>：
 *       <pre>
 * JS style prop（color / backgroundColor / borderColor / tintColor / shadowColor / ripple）
 *   ↓
 * com.facebook.react.bridge.ColorPropConverter.getColor(Object, Context)
 *   ↓
 * setTextColor / BackgroundDrawable.paint.setColor / ReactSwitch.setTrackColor /
 * ImageView.setColorFilter / setImageTintList …
 *       </pre>
 *       在这个收口上改一次，等于把上面<b>所有</b>下游一起改了 —— 文字、按钮、开关、
 *       选项遮罩、描边、图标 tint，一个不漏。</li>
 * </ol>
 *
 * <h3>两个挂点</h3>
 * <ol>
 *   <li>{@code ColorPropConverter.getColor}（两个重载）—— 覆盖 prop 颜色的 95%；</li>
 *   <li>{@code LinearGradientView.setColors(int[])} —— 全包<b>唯一</b>不走
 *       {@code ColorPropConverter} 的色路（渐变数组），宿主散落的硬编码紫
 *       （{@code #4F6FE1 → #9231AA} 这类）就在这里。</li>
 * </ol>
 *
 * <p>⚠️ 热路径成本：两个 {@code volatile} 读 + 一次 {@code HashMap} 查表
 * （{@link AccentColor#remap}），动画色一帧多次也扛得住。
 */
public final class AccentLut {

    private static final String TAG = "[MoriMod:Accent]";

    /** RN 的颜色 prop 收口（dex 实证存在，且<b>未被混淆</b>）。 */
    private static final String CLS_COLOR_PROP = "com.facebook.react.bridge.ColorPropConverter";

    /** expo-linear-gradient 的渐变数组入口（dex 实证 {@code setColors:([I)V}）。 */
    private static final String CLS_GRADIENT = "expo.modules.lineargradient.LinearGradientView";

    private AccentLut() {
    }

    /** 装三个挂点；任一失败只降级自身。 */
    public static void install(ClassLoader cl) {
        int n = hookColorProp(cl);
        int g = hookGradient(cl);
        int p = hookPaint(cl);
        int t = hookTextColor(cl);
        log("LUT installed: colorProp=" + n + " gradient=" + g + " paint=" + p + " text=" + t);
    }

    /**
     * ★★★ v0.1.39（r44 一加 12 第 1 条）：<b>小字不变色</b>的补丁。
     *
     * <h3>为什么 Paint#setColor 覆盖不到</h3>
     * {@code Paint#setColor} 那条钩子对<b>正文 / 图标 / 描边</b>都有效，但它有一道
     * 「中性色保护」：彩度低于 5 的颜色原样放行。宿主<b>底部导航栏的文字</b>和
     * <b>按钮里的小字</b>正好是<b>低彩度的紫灰</b>（彩度 1.5~5）⇒ 被判成中性色
     * ⇒ 界面上「图标变了、字没变」，用户看到的就是「小字不变色」。
     *
     * <p>这里补一个更靠前的挂点：{@code TextView#setTextColor(int)}。
     * 它同时能拿到<b>字号</b>（{@code getTextSize()}），所以只在<b>小字</b>上放宽
     * 彩度下限（见 {@link AccentColor#remapSmallText}），大字号仍走原来的严格规则
     * —— 不会把整套正文染成彩色。
     *
     * <p>⚠️ 只钩 {@code (int)} 这个重载，{@code (ColorStateList)} 不动：
     * 宿主用状态色的地方（选中/按下变色）必须保持宿主自己的状态机。
     */
    private static int hookTextColor(ClassLoader cl) {
        try {
            Class<?> cls = XposedCompat.findClass("android.widget.TextView", cl);
            java.lang.reflect.Method m = cls.getDeclaredMethod("setTextColor", int.class);
            m.setAccessible(true);
            XposedCompat.hookMethod(m, chain -> {
                int c;
                Object self = chain.getThisObject();
                try {
                    Object a0 = chain.getArg(0);
                    if (!(a0 instanceof Integer)) return chain.proceed();
                    c = (Integer) a0;
                } catch (Throwable t) {
                    return chain.proceed();
                }
                // 只处理「小字」：正文/标题保持原有严格规则
                boolean small;
                float px = 0f;
                float dens = 0f;
                try {
                    px = ((android.widget.TextView) self).getTextSize();
                    dens = ((android.view.View) self).getResources()
                            .getDisplayMetrics().scaledDensity;
                    small = dens > 0f && px / dens <= SMALL_TEXT_SP;
                } catch (Throwable t) {
                    small = false;
                }
                if (!small) {
                    probeText(self, "int", c, c, px, dens, false);
                    return chain.proceed();
                }
                int n;
                try {
                    n = AccentColor.remapSmallText(c);
                } catch (Throwable t) {
                    n = c;
                }
                probeText(self, "int", c, n, px, dens, true);
                if (n == c) return chain.proceed();
                return chain.proceed(new Object[]{Integer.valueOf(n)});
            });
            log("hooked 1 TextView#setTextColor（小字也跟强调色走）");
            hookTextColorStateList(cls);
            return 1;
        } catch (Throwable t) {
            log("钩 TextView#setTextColor 失败: " + t);
            return 0;
        }
    }

    /**
     * ★★★ v0.1.40（r45 一加 12 第 1 条）：<b>{@code setTextColor(ColorStateList)} 这个重载</b>。
     *
     * <h3>为什么一加 12 上「只有小字不变色」而一加 15 正常</h3>
     * 两个机器宿主版本一致、模块版本一致，差别只在<b>RN 走哪个重载</b>：
     * 一加 12 上（系统字体缩放 / 无障碍设置不同）RN 给底部导航标签下发的是
     * <b>状态色列表</b>（选中 / 未选中两档），走 {@code setTextColor(ColorStateList)}；
     * 那个重载不会经过 {@code setTextColor(int)}，<b>整条小字补丁等于没挂上</b>。
     *
     * <p>这里把状态列表里每一档颜色各自过一遍 {@link AccentColor#remapSmallText}，
     * 再重新组装成新的 {@code ColorStateList} 交回宿主 —— 选中 / 未选中的
     * <b>状态机本身不动</b>，只换颜色值。
     */
    private static int hookTextColorStateList(Class<?> cls) {
        try {
            java.lang.reflect.Method m =
                    cls.getDeclaredMethod("setTextColor", android.content.res.ColorStateList.class);
            m.setAccessible(true);
            XposedCompat.hookMethod(m, chain -> {
                Object self = chain.getThisObject();
                Object a0 = chain.getArg(0);
                if (!(a0 instanceof android.content.res.ColorStateList)) return chain.proceed();
                boolean small;
                float px = 0f;
                float dens = 0f;
                try {
                    px = ((android.widget.TextView) self).getTextSize();
                    dens = ((android.view.View) self).getResources()
                            .getDisplayMetrics().scaledDensity;
                    small = dens > 0f && px / dens <= SMALL_TEXT_SP;
                } catch (Throwable t) {
                    small = false;
                }
                android.content.res.ColorStateList src0 = (android.content.res.ColorStateList) a0;
                int d0 = 0;
                try {
                    d0 = src0.getDefaultColor();
                } catch (Throwable ignored) {
                }
                probeText(self, "csl", d0, d0, px, dens, small);
                if (!small) return chain.proceed();
                android.content.res.ColorStateList src = (android.content.res.ColorStateList) a0;
                // ★★★ v0.1.41（r12 用户第 1 条）：**保留宿主原有的全部状态**。
                //   旧实现是按 8 个「猜的」状态重建列表 —— 宿主的底部导航如果用
                //   {@code state_focused} / {@code state_activated}，新列表里没有那一条
                //   ⇒ getColorForState 落到 default ⇒ 选中的小字颜色丢掉 / 看起来「不变色」。
                //   这就是「一加 12 有、一加 15 没有」的机型差异来源（两台机器 RN 下发的
                //   状态数不同）。现在直接反射读 mStateSpecs / mColors，逐项过
                //   remapSmallText，状态机与条目顺序原样保留。
                int[][] fullSpecs = stateSpecsOf(src);
                int[] fullColors = stateColorsOf(src);
                if (fullSpecs != null && fullColors != null
                        && fullSpecs.length == fullColors.length && fullColors.length > 0) {
                    int[] out = new int[fullColors.length];
                    boolean ch = false;
                    for (int i = 0; i < fullColors.length; i++) {
                        int c = fullColors[i];
                        int n = c == 0 ? c : AccentColor.remapSmallText(c);
                        out[i] = n;
                        if (n != c) ch = true;
                    }
                    probeText(self, "csl" + fullColors.length, d0,
                            out.length > 0 ? out[out.length - 1] : d0, px, dens, true);
                    if (!ch) return chain.proceed();
                    return chain.proceed(new Object[]{
                            new android.content.res.ColorStateList(fullSpecs, out)});
                }
                try {
                    if (!src.isStateful()) {
                        int c = src.getDefaultColor();
                        int n = AccentColor.remapSmallText(c);
                        if (n == c) return chain.proceed();
                        return chain.proceed(new Object[]{
                                android.content.res.ColorStateList.valueOf(n)});
                    }
                    // ⚠️ ColorStateList#getStateSpecs / getStateCount 是 @hide，
                    //    编译期拿不到。这里改成「按已知状态逐个探色」：
                    //    状态集从最具体到最宽泛排列，getColorForState 是公开 API。
                    int[][] specs = new int[][]{
                            new int[]{android.R.attr.state_enabled, android.R.attr.state_pressed},
                            new int[]{android.R.attr.state_enabled, android.R.attr.state_selected},
                            new int[]{android.R.attr.state_enabled, android.R.attr.state_checked},
                            new int[]{android.R.attr.state_pressed},
                            new int[]{android.R.attr.state_selected},
                            new int[]{android.R.attr.state_checked},
                            new int[]{-android.R.attr.state_enabled},
                            new int[]{},
                    };
                    int def = src.getDefaultColor();
                    int[] cols = new int[specs.length];
                    boolean changed = false;
                    for (int i = 0; i < specs.length; i++) {
                        int c = src.getColorForState(specs[i], def);
                        int n = AccentColor.remapSmallText(c);
                        cols[i] = n;
                        if (n != c) changed = true;
                    }
                    if (!changed) return chain.proceed();
                    return chain.proceed(new Object[]{
                            new android.content.res.ColorStateList(specs, cols)});
                } catch (Throwable t) {
                    return chain.proceed();
                }
            });
            log("hooked 1 TextView#setTextColor(ColorStateList)（状态色列表里的小字）");
            return 1;
        } catch (Throwable t) {
            log("钩 setTextColor(ColorStateList) 失败: " + t);
            return 0;
        }
    }

    /**
     * 「小字」的字号上限（sp）。
     *
     * <p>★★★ v0.1.43：15 → 18。白胶囊按钮里的标签常见 14~16sp，
     * 卡在 15 线上就会漏掉（用户第 1 条「白色按钮内文字不变强调色」）。
     * 正文 / 标题通常 ≥20sp，仍走严格 remap。
     */
    private static final float SMALL_TEXT_SP = 18f;

    /**
     * ★ v0.1.41：读 {@code ColorStateList} 的状态表（{@code mStateSpecs : int[][]}）。
     * <p>先按名字取；名字变了就按类型找。取不到返回 null（调用方回退到「猜状态」老路）。
     */
    private static int[][] stateSpecsOf(android.content.res.ColorStateList csl) {
        if (csl == null) return null;
        try {
            java.lang.reflect.Field f = android.content.res.ColorStateList.class
                    .getDeclaredField("mStateSpecs");
            f.setAccessible(true);
            Object o = f.get(csl);
            if (o instanceof int[][]) return (int[][]) o;
        } catch (Throwable ignored) {
        }
        try {
            for (java.lang.reflect.Field f : android.content.res.ColorStateList.class.getDeclaredFields()) {
                if (f.getType() != int[][].class) continue;
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object o = f.get(csl);
                if (o instanceof int[][]) return (int[][]) o;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** ★ v0.1.41：读 {@code ColorStateList} 的颜色表（{@code mColors : int[]}）。 */
    private static int[] stateColorsOf(android.content.res.ColorStateList csl) {
        if (csl == null) return null;
        try {
            java.lang.reflect.Field f = android.content.res.ColorStateList.class
                    .getDeclaredField("mColors");
            f.setAccessible(true);
            Object o = f.get(csl);
            if (o instanceof int[]) return (int[]) o;
        } catch (Throwable ignored) {
        }
        try {
            for (java.lang.reflect.Field f : android.content.res.ColorStateList.class.getDeclaredFields()) {
                if (f.getType() != int[].class) continue;
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object o = f.get(csl);
                if (o instanceof int[]) return (int[]) o;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 探针②的日志条数 / 预算（v0.1.41：把「小字不变色」从猜测变成读数）。 */
    private static volatile int sTcLogs;
    private static final int TC_LOG_BUDGET = 44;

    /**
     * ★★★ v0.1.41（r12 用户第 1 条）：{@code setTextColor} 探针。
     *
     * <p>一加 12 上「底部导航小字 / 按钮内文字不变色」，一加 15 正常，同一宿主同一模块。
     * 唯一可能是系统层的字体缩放 / 显示大小让 {@code px / scaledDensity} 落在
     * {@link #SMALL_TEXT_SP} 的两侧 —— 但前几轮日志里 {@code remapSmallText}
     * 一条都没有，而 {@code sMapLogs} 预算只有 8 条又被 {@code paint map} 吃光，
     * 所以「没打」根本不能证明「没调用」。
     *
     * <p>这条日志不走 {@link AccentColor} 的预算，独立计数，一次看清：
     * 走的是哪个重载、px / scaledDensity 各是多少、被判成小字没有、进出色值。
     */
    private static void probeText(Object self, String way, int in, int out,
                                  float px, float dens, boolean small) {
        // ★★★ v0.1.42（r13 用户第 1 条）：**按「这一条文本长什么样」去重**。
        //   以前是「总共 20 条」的硬预算，宿主启动时那 10 来个 12px 的文字
        //   一上来就把预算吃光 ⇒ 后面底部导航栏真正调色的那几次<b>一条都看不到</b>
        //   （r12 / r13 的日志里 `tc` 全是 px=12 #8a000000 就是这个原因）。
        //   现在同一组（重载 + 字号 + 进出色）只打一条，上限 40 —— 一次就能看全。
        String key = "tc:" + way + ":" + (int) px + ":" + in + ":" + out + ":" + (small ? 1 : 0);
        if (TC_KEYS.size() > 400) TC_KEYS.clear();
        if (TC_KEYS.contains(key)) return;
        if (sTcLogs >= TC_LOG_BUDGET) return;
        TC_KEYS.add(key);
        sTcLogs++;
        String txt = "-";
        try {
            CharSequence cs = ((android.widget.TextView) self).getText();
            if (cs != null) {
                String s = cs.toString().replace('\n', ' ');
                txt = s.length() > 6 ? s.substring(0, 6) : s;
            }
        } catch (Throwable ignored) {
        }
        log("tc " + way
                + " px=" + (int) px
                + " sd=" + String.format(java.util.Locale.ROOT, "%.2f", dens)
                + " sp=" + (dens > 0f ? String.format(java.util.Locale.ROOT, "%.1f", px / dens) : "?")
                + " small=" + (small ? 1 : 0)
                + " #" + String.format(java.util.Locale.ROOT, "%08x", in)
                + "->#" + String.format(java.util.Locale.ROOT, "%08x", out)
                + " n=" + txt);
    }

    private static final java.util.Set<String> TC_KEYS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * ★★ v0.1.25 用户第 1/2 条：<b>真正吃到全部颜色的挂点</b>
     * —— {@code android.graphics.Paint#setColor(int)}。
     *
     * <h3>为什么前两个挂点不够</h3>
     * v0.1.24 只挂了 {@code ColorPropConverter}（外加渐变），真机结果只有零星几个色变，
     * 文字 / 矢量图标 / 加载圈 / 开关 / 遮罩照旧是紫的。两条原因：
     * <ol>
     *   <li>RN 0.81 走 Fabric，颜色是 <b>JS 侧 {@code processColor} 就算成 int</b> 再下来的，
     *       Java 那个转换器只在少数老组件上被调到；</li>
     *   <li>即便调到了，值也在 prop 更新的那一刻就<b>写死在</b> View / Drawable / Paint 里，
     *       之后不重读 ⇒ 改了风格也不会变。</li>
     * </ol>
     *
     * <p>{@code Paint#setColor} 是唯一「<b>每帧落笔前都会再问一次</b>」的地方：
     * 文字按 span 设色、矢量图标按 fill 设色、{@code ActivityIndicator} 按 color 画弧、
     * 背景 drawable 在 setColor 时设色 —— 挂在这里与「是什么组件」无关，
     * 也不会因为 prop 不重发而漏掉。
     *
     * <p>热路径保护见 {@link AccentColor#remapPaint(int)}：先做一次纯算术预筛
     * （{@code B > R ≥ G} 才可能是宿主紫族），绝大多数调用在第一行就返回了。
     */
    private static int hookPaint(ClassLoader cl) {
        try {
            Class<?> cls = XposedCompat.findClass("android.graphics.Paint", cl);
            Method m = cls.getDeclaredMethod("setColor", int.class);
            m.setAccessible(true);
            XposedCompat.hookMethod(m, chain -> {
                int c;
                try {
                    Object a0 = chain.getArg(0);
                    if (!(a0 instanceof Integer)) return chain.proceed();
                    c = (Integer) a0;
                } catch (Throwable t) {
                    return chain.proceed();
                }
                int n;
                try {
                    n = AccentColor.remapPaint(c);
                } catch (Throwable t) {
                    n = c;
                }
                // 绝大多数调用在这里原样放行；只有真的要换色才走「换参」这条路
                if (n == c) return chain.proceed();
                return chain.proceed(new Object[]{Integer.valueOf(n)});
            });
            log("hooked 1 Paint#setColor（落笔时刻换色：文字 / 图标 / 加载圈 / 描边 / 底色）");
            return 1;
        } catch (Throwable t) {
            log("钩 Paint#setColor 失败: " + t);
            return 0;
        }
    }

    /**
     * 颜色 prop 收口。
     *
     * <p>两个重载都要：
     * <ul>
     *   <li>{@code getColor(Object, Context) → Integer} —— 返回装箱值；</li>
     *   <li>{@code getColor(Object, Context, int) → int} —— 带默认值的重载
     *       （{@code placeholder} / {@code tintColor} 这类走它）。</li>
     * </ul>
     * 只钩「第一个参数是 Object」的，别的一律不动（该类还有若干私有静态工具方法）。
     */
    private static int hookColorProp(ClassLoader cl) {
        Class<?> cls;
        try {
            cls = XposedCompat.findClass(CLS_COLOR_PROP, cl);
        } catch (Throwable t) {
            log("找不到 " + CLS_COLOR_PROP + ": " + t);
            return 0;
        }
        List<Method> ms = XposedCompat.findMethodsByName(cls, "getColor");
        int hooked = 0;
        for (Method m : ms) {
            try {
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 2 || p.length > 3) continue;
                if (p[0] != Object.class) continue;
                m.setAccessible(true);
                XposedCompat.hookMethod(m, chain -> {
                    Object result = chain.proceed();
                    if (!(result instanceof Integer)) return result;
                    try {
                        int c = (Integer) result;
                        int n = AccentColor.remap(c);
                        return n == c ? result : Integer.valueOf(n);
                    } catch (Throwable ignored) {
                        return result;
                    }
                });
                hooked++;
            } catch (Throwable t) {
                log("钩 ColorPropConverter#getColor 失败: " + t);
            }
        }
        log("hooked " + hooked + "/" + ms.size() + " ColorPropConverter#getColor");
        return hooked;
    }

    /**
     * 渐变数组就地改色。
     *
     * <p>就地改写 {@code chain.getArg(0)} 那个 {@code int[]} 是安全的：
     * 它是 JS 每次 render 新建的数组（RN 侧 bridge 反序列化产物），
     * 改它等价于「JS 传下来的就是强调色」，不会污染宿主自己的常量表。
     */
    private static int hookGradient(ClassLoader cl) {
        Class<?> cls;
        try {
            cls = XposedCompat.findClass(CLS_GRADIENT, cl);
        } catch (Throwable t) {
            log("找不到 " + CLS_GRADIENT + ": " + t);
            return 0;
        }
        int hooked = 0;
        for (Method m : XposedCompat.findMethodsByName(cls, "setColors")) {
            try {
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || p[0] != int[].class) continue;
                m.setAccessible(true);
                XposedCompat.hookMethod(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object a0 = chain.getArg(0);
                        if (a0 instanceof int[]) AccentColor.remapArray((int[]) a0);
                    } catch (Throwable ignored) {
                    }
                    return result;
                });
                hooked++;
            } catch (Throwable t) {
                log("钩 LinearGradientView#setColors 失败: " + t);
            }
        }
        log("hooked " + hooked + " LinearGradientView#setColors");
        return hooked;
    }

    private static void log(String msg) {
        try {
            XposedCompat.log(TAG + " " + msg);
        } catch (Throwable ignored) {
        }
    }
}
