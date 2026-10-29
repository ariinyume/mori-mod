package io.github.ariinyume.morimod.hook;

import android.graphics.Bitmap;
import android.graphics.Color;

import io.github.ariinyume.morimod.config.MoriConfig;

/**
 * 强调色引擎（v0.1.22，用户第 4 条）。
 *
 * <h3>它解决什么</h3>
 * 用户换了自定义壁纸之后，宿主那套固定紫（{@code #6D47FA}/{@code #252451}）跟壁纸完全不搭。
 * 这里从<b>壁纸本身</b>抽一个种子色，按 Material 3 Expressive 的九种配色方案
 * （TonalSpot / Neutral / Vibrant / Expressive / Rainbow / FruitSalad / Monochrome /
 * Fidelity / Content）各生成一套「强调色」，然后：
 * <ul>
 *   <li><b>文字</b>：除白色（近白）以外，保留原亮度、把色相/彩度换成强调色 → 观感统一且对比不变；</li>
 *   <li><b>阴影 / 蒙层</b>：同一个色相、同一个 alpha → 「同透明度、观感一致」的阴影。</li>
 * </ul>
 *
 * <h3>为什么是 Lab/LCh 而不是 HSV</h3>
 * 需求里有两条硬约束：「文字亮度不能变（否则看不清）」「白色文字保持白色」。
 * HSV 的 V 不是感知亮度，换成强调色后深浅会跳；CIELAB 的 L* 才是感知亮度，
 * 所以这里全程走 sRGB ↔ Lab ≈ LCh，只动 (h, C)，不动 L。
 *
 * <h3>与 MCU（material-color-utilities）的差别</h3>
 * 真正的 Material 3 动态配色走 CAM16 + HCT，并在色域边界上做 tone 映射；
 * 这里用 CIELAB LCh 做同一件事的<b>近似</b>：色相旋转 + 彩度缩放 + 逐次收缩到 sRGB 色域内。
 * 九种方案的差别（色相偏移 / 彩度倍率）按 Expressive 规范的方向设置，
 * 结果是「风格可分辨、观感一致」，数值不会与 MCU 逐位相同。
 */
public final class AccentColor {

    private static final String TAG = "[MoriMod:Accent]";

    /** 采样的网格边长（壁纸缩到这个尺寸后取种子，成本可忽略）。 */
    private static final int SAMPLE = 56;

    /** 低于这个彩度的像素不参与「种子色」投票（都是灰，选出来没有意义）。 */
    private static final double MIN_SEED_CHROMA = 9.0;

    /** 视为「白色文字」的亮度门槛：>= 它就不动（用户第 4 条：白色文字除外）。 */
    private static final double WHITE_TONE = 91.0;

    /**
     * 近黑保护门槛：L* &le; 它的一律原样返回（v0.1.26 用户第 1 / 5 条）。
     *
     * <h3>为什么必须有</h3>
     * 宿主拿 {@code #19122c / #252451} 这一族做<b>压暗遮罩</b>（播放页台词条、
     * 模态 dim）。它们本身几乎是黑的，紫不紫肉眼看不出；可一旦被换色相，
     * 就变成一条<b>明显的彩色横条</b>（真机：r31 播放页封面下方那条棕色带，
     * 日志实证 {@code map #9919122c -> #99251200}）。近黑就该保持近黑。
     */
    private static final double DARK_TONE = 22.0;

    /**
     * ★★ v0.1.27 用户第 5 条：壁纸「整体有多彩」决定了强调色该有多浓。
     *
     * <h3>为什么必须有这道缩放</h3>
     * r32 现场：用户放了一张<b>黑色为主、白色/灰色为辅</b>的壁纸，抽出来的强调色
     * 却是<b>很浓的蓝</b>（日志 {@code seed hue=275 chroma=37}）。原因不是取色取错，
     * 而是<b>取完没有按壁纸的整体彩度收敛</b> —— 只要壁纸上有一小块蓝（比如天空的一角），
     * 旧算法按「彩度加权投票」就把它投成赢家，再原样灌进整套 UI。
     *
     * <p>现在拆成两件事：
     * <ol>
     *   <li><b>色相</b>按<b>面积</b>投票（谁占的像素多谁说话），小块高饱和区域赢不了；</li>
     *   <li><b>彩度</b>乘上 {@link #sVibrancy}（壁纸整体的平均彩度 / 16，钳到 0.05~1）。</li>
     * </ol>
     * 黑白灰壁纸平均彩度 ≈ 2 ⇒ vibrancy ≈ 0.12 ⇒ 强调色彩度只剩个位数，
     * 界面回到<b>中性灰</b>，既不会突变成蓝色，也不会「跳回宿主紫」。
     */
    private static volatile double sVibrancy = 1.0;

    /** 让「整体彩度 = 16」算满额活力；低于它的按比例收敛（灰壁纸不会凭空变彩）。 */
    private static final double VIBRANCY_FULL_CHROMA = 16.0;

    /** 活力下限：再灰的壁纸也留一丝色相，否则整套 UI 会变成纯灰度、按钮分不清层级。 */
    private static final double VIBRANCY_MIN = 0.10;

    /** 上一次算成功的种子（壁纸换成纯黑白时沿用，不跳回宿主紫）。 */
    private static double sLastH = -1.0;
    private static double sLastC = 0.0;

    /** 种子是否可用（壁纸是纯黑白灰时没有强调色可取）。 */
    private static volatile boolean sHasSeed;

    /** 当前是否生效（开关打开 + 种子可用）。 */
    private static volatile boolean sOn;

    /** 种子色在 LCh 里的色相（度）与彩度。 */
    private static volatile double sSeedH;
    private static volatile double sSeedC;

    /**
     * 宿主那套紫的基准色（token {@code surface/on_surface_high = #c387e8}）。
     *
     * <p>★ v0.1.24 定案：强调色不再是「按彩度/亮度猜哪些颜色像背景」，而是
     * <b>围绕这一个色相开一扇 40° 的窗口</b> —— 宿主全包 43 个 token 加散落的
     * 硬编码紫（{@code #a36dc3 #d3afed #b48dbd #79559d #8b5fbf #d099f6 #3a3968
     * #252451 #19122c …}）都是它在不同亮度/彩度上的变体，落在同一扇窗里。
     */
    private static final int HOST_PURPLE = 0xFFC387E8;

    /** 色相窗口半宽（度）。宿主的紫都在 ±25° 内，留到 40 是为了吃下派生色。 */
    private static final double HUE_WINDOW = 40.0;

    /** 低于这个彩度就是灰 / 黑 / 白，一律不动（保护中性色与纯黑白 UI）。 */
    private static final double MIN_REMAP_CHROMA = 5.0;

    /** 懒算的宿主紫色相（度）；{@code < 0} 表示还没算。 */
    private static volatile double sHostH = -1.0;

    /** 当前风格的下标（{@link MoriConfig#ACCENT_STYLES}）。 */
    private static volatile int sStyle = -1;

    /** 当前生效的「壁纸 + 风格」指纹，避免每次都重算种子。 */
    private static volatile String sKey = "";

    /**
     * 颜色转换表：输入 RGB → 输出 RGB（不含 alpha）。
     *
     * <p>宿主的颜色 token 是有限的几十个，缓存后每帧只做一次 {@code HashMap} 查表
     * （{@code Paint#setColor} 在动画 / 每帧文字绘制里会被调用成百上千次，热路径不能算 LCh）。
     */
    private static final java.util.Map<Integer, Integer> sLut =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    /**
     * 反向表：输出 RGB → 宿主原色 RGB。
     *
     * <p>★★ v0.1.25 的关键补丁。{@code Paint#setColor} 是<b>「设进去就存着」</b>的
     * —— 一次 {@code setColor} 之后，同一块 Paint 可能被反复拿来画几十帧。
     * 于是「换风格」时那些 Paint 里存的是<b>上一套强调色</b>，既不会回到宿主原色，
     * 也不会走到新风格上（真机表现就是「换了风格跟没换一样」）。
     * 有了这张反向表，看到「这是我自己造出来的色」就先还原成宿主原色、再按当前风格重算，
     * 于是同一个值在任意时刻反复经过 LUT 都能收敛到当前风格。
     */
    private static final java.util.Map<Integer, Integer> sRev =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    /** 转换表上限（token 就几十个，给到 4K 是纯保险）。 */
    private static final int LUT_MAX = 4096;

    /**
     * ★★★ v0.1.47（Float 模块避让指令·改动 4）：「此刻在画别人的东西，先别染」开关。
     *
     * <p>Float 模块（悬浮窗字幕）在 {@code SubtitleLineView#onDraw} 调 {@code super} 之前
     * 反射把它置 {@code TRUE}、finally 里清掉 —— 这期间所有 remap 入口原样放行，
     * 用户在 Float 那边选的字幕颜色（哪怕是有彩色）不会被染成强调色。
     *
     * <p>⚠️ 置位方<b>必须</b>在 finally 里清位，否则绘制线程会一直处于「不染」状态。
     */
    public static final ThreadLocal<Boolean> SUSPEND = new ThreadLocal<>();

    /** 避让开关此刻是否生效（所有 remap 入口的第一行）。 */
    private static boolean suspended() {
        try {
            return Boolean.TRUE.equals(SUSPEND.get());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★★★ v0.1.47（Float 模块避让指令·改动 3）：不透明的无彩色（纯白 / 灰 / 纯黑）
     * 没有色相可换，直接放行 —— 典型受害者是悬浮窗字幕（默认 {@code #FFFFFFFF}）。
     *
     * <p>⚠️ 与旧逻辑的取舍：只放行 <b>alpha == 0xFF</b> 的无彩色。
     * 「半透明中性色」<b>不能</b>放 —— 那是 v0.1.42（r13 用户第 1 条）特意做的
     * 「中性半透明小字也要上强调色」，全放行会让一加 12 的底部导航标签退回灰色。
     * 悬浮窗字幕默认是不透明白，这条判据对它已经足够。
     */
    private static boolean neutralOpaque(int argb) {
        if ((argb >>> 24) != 0xFF) return false;
        int r = (argb >> 16) & 0xff;
        int g = (argb >> 8) & 0xff;
        int b = argb & 0xff;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 6;
    }

    /** 「map #xxx -> #xxx」诊断日志预算。 */
    private static int sMapLogs;

    private AccentColor() {
    }

    // ── ★★★ v0.1.39（r44 一加 12 第 1 条）：小字专用重映射 ─────────────

    /**
     * 小字能接受的彩度下限。
     *
     * <p>{@link #MIN_REMAP_CHROMA} 是 5.0 —— 那是按「正文 / 图标」定的：再低的彩度
     * 就是中性灰，动了反而脏。但宿主<b>底部导航栏的文字</b>和<b>按钮里的小字</b>
     * 用的是低彩度的紫灰（彩度 1.5~5），正好卡在这道门槛下面 ⇒
     * 用户看到的永远是「小字不变色」。
     *
     * <p>放宽到 1.5 仍然只影响「<b>带紫倾向</b>的低彩度色」
     * （{@code remapSmallText} 里保留了 {@code b > r && r >= g} 的预筛），
     * 纯中性灰（r=g=b）依旧原样放行。
     */
    private static final double SMALL_TEXT_MIN_CHROMA = 1.5;

    /** 小字重映射的独立缓存（值域与正文不同，不能共用 {@link #sLut}）。 */
    private static final java.util.Map<Integer, Integer> sLutSmall =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    /** 小字里最低要给的彩度：低于它就算「变色了」也看不出来。 */
    private static final double SMALL_TEXT_MIN_OUT_C = 5.0;

    /**
     * ★★★ v0.1.39：<b>小字专用</b>的强调色重映射（给 {@code TextView#setTextColor} 用）。
     *
     * <p>先走正常 {@link #remap}；它没改（说明是「低彩度紫灰」被中性色保护放过了）
     * 才走这里的放宽逻辑。只放宽<b>彩度下限</b>，色相窗口 / 近白 / 近黑保护一律保留。
     */
    public static int remapSmallText(int argb) {
        // ★ v0.1.47：Float 模块避让 + 不透明无彩色放行（见 neutralOpaque 的注释：
        //   半透明中性色仍要上强调色，那是 v0.1.42 的正式行为，不能一起放掉）
        if (suspended() || neutralOpaque(argb)) return argb;
        int normal = remap(argb);
        if (normal != argb || !sOn) return normal;
        int a = argb >>> 24;
        if (a == 0) return argb;
        int rgb = argb & 0xFFFFFF;
        if (rgb == 0xFFFFFF) return argb;
        // 只需要「带紫倾向」的低彩度色 —— 与 Paint 那条预筛同一个判据
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        // ★★★ v0.1.42（r13 用户第 1 条）：**中性色的半透明小字也要上强调色**。
        //
        //   13 轮都没修好这一条的原因是一次探针读数：
        //     `tc csl px=12 sd=3.50 sp=3.4 small=1 #8a000000->#8a000000`
        //   底部导航标签 / 按钮说明文字在宿主里是 {@code rgba(0,0,0,0.54)} 这种
        //   <b>中性半透明</b>色（不是紫！），而下面那条 {@code b <= r || r < g} 的
        //   「紫倾向」预筛把它直接放行 ⇒ 永远不变色。一加 15 上看起来正常，
        //   是因为那台机器的主题给的是带紫的值；一加 12 给的是中性值。
        //   ⇒ 中性 + 半透明 = 一定是「压在底上的辅助文字」，给它强调色的色相，
        //   并把 L* 抬到能看见的层次（{@code #8a000000} 的 L*=0，不抬就是纯黑，
        //   染多少色相都看不出来）。
        boolean neutral = (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b))) <= 6;
        if (!neutral && (b <= r || r < g)) return argb;
        Integer hit = sLutSmall.get(rgb);
        if (hit != null) return (a << 24) | hit;
        try {
            double[] lch = toLch(argb);
            double l = lch[0];
            double c;
            if (neutral) {
                if (a == 0xff) return argb;          // 不透明的纯黑/纯白：正文，不动
                if (l < SMALL_TEXT_NEUTRAL_MIN_L) l = SMALL_TEXT_NEUTRAL_MIN_L;
                c = Math.max(14.0, SMALL_TEXT_MIN_OUT_C * 2.4) * Math.max(0.35, sVibrancy);
            } else {
                if (l >= WHITE_TONE || l <= DARK_TONE) return argb;
                if (lch[1] < SMALL_TEXT_MIN_CHROMA) return argb;
                if (hueDist(lch[2], hostHue()) > HUE_WINDOW) return argb;
                c = Math.max(SMALL_TEXT_MIN_OUT_C, Math.min(96.0, lch[1] * 3.0 * sVibrancy));
            }
            int out = fromLch(a, l, Math.min(c, 96.0),
                    sanitize(sSeedH + styleHueShift())) & 0xFFFFFF;
            if (sLutSmall.size() < LUT_MAX) sLutSmall.put(rgb, out);
            if (sMapLogs < 8) {
                sMapLogs++;
                log("map small #" + String.format(java.util.Locale.ROOT, "%08x", argb)
                        + " -> #" + String.format(java.util.Locale.ROOT, "%08x", out)
                        + (neutral ? " (neutral)" : ""));
            }
            return (a << 24) | out;
        } catch (Throwable t) {
            return argb;
        }
    }

    /** 中性色小字被抬到的最低明度（L*）。低于它染色看不出来。 */
    private static final double SMALL_TEXT_NEUTRAL_MIN_L = 46.0;

    // ── 对外 ────────────────────────────────────────────────────────

    /** 现在能不能给宿主改色（热路径只读这两个 volatile）。 */
    public static boolean on() {
        return sOn;
    }

    /** 强调色的色相（度），诊断用。 */
    public static double hue() {
        return sSeedH;
    }

    /** 当前风格名，诊断用。 */
    public static String styleName() {
        int i = sStyle;
        return i < 0 || i >= MoriConfig.ACCENT_STYLES.length ? "-" : MoriConfig.ACCENT_STYLES[i];
    }

    /**
     * 按最新配置 + 壁纸刷新一次（由 {@code BgPeel.apply} 每轮调用，内部有指纹短路）。
     *
     * @param bmp 当前壁纸位图（可能为 null = 没有壁纸）
     */
    public static void update(Bitmap bmp, MoriConfig cfg) {
        boolean want = cfg != null && cfg.f2Accent && cfg.f2Enabled;
        String style = cfg == null || !MoriConfig.validAccentStyle(cfg.f2AccentStyle)
                ? MoriConfig.ACCENT_STYLES[0] : cfg.f2AccentStyle;
        // ⚠️ 指纹里必须带 want：否则「关掉开关」时壁纸和风格都没变 ⇒ 走短路返回，
        //    notifyChanged 不触发 ⇒ 文字/阴影永远停在上一套强调色（关不掉了）。
        String key = style + "|" + want + "|" + (bmp == null ? "none" : System.identityHashCode(bmp));
        if (key.equals(sKey)) {
            // 指纹没变：连开关状态都不用重算，只在 armed 掉下去时同步关掉
            boolean was = sOn;
            sOn = want && sHasSeed;
            if (was != sOn) logOnce("state -> " + (sOn ? "on" : "off") + " want=" + want);
            return;
        }
        sKey = key;
        sStyle = indexOf(style);
        sHasSeed = false;
        sOn = false;
        if (!want || bmp == null || bmp.isRecycled()) {
            // ★ r28 血证：整场日志 0 条 Accent ⇒ 根本不知道是「开关没读到」还是「采样失败」。
            //   现在每个出口都留痕，下一轮一眼定案。
            logOnce("off want=" + want + " bmp=" + (bmp == null ? "null"
                    : (bmp.isRecycled() ? "recycled" : "ok")));
            notifyChanged();
            return;
        }
        try {
            sampleSeed(bmp);
        } catch (Throwable t) {
            sHasSeed = false;
        }
        sOn = want && sHasSeed;
        logOnce("state -> " + (sOn ? "on" : "off") + " style=" + styleName()
                + " seed=" + sHasSeed);
        notifyChanged();
    }

    /** 关掉功能时清干净（不保留任何残留状态）。 */
    public static void reset() {
        sOn = false;
        sHasSeed = false;
        sKey = "";
        sStyle = -1;
        sLogs = 0;
        sLastMsg = "";
        sVibrancy = 1.0;
        notifyChanged();
    }

    /**
     * ★★ v0.1.24 核心：把一个宿主颜色「过一遍强调色 LUT」。
     *
     * <h3>为什么不是「猜哪些颜色是背景」</h3>
     * v0.1.22 / v0.1.23 都是在<b>结果端</b>猜：读 {@code BackgroundDrawable} 的 int 字段、
     * 读 {@code TextView} 当前色，再按彩度/亮度判断「这块该不该染」。真机两轮下来
     * 用户看到的仍然是「满屏紫」——因为真正决定颜色的地方根本不在这两处：
     * <pre>
     * JS style prop（color / backgroundColor / borderColor / tintColor / shadowColor）
     *   ↓ ★ 全包唯一收口
     * ColorPropConverter.getColor(Object, Context) → Integer
     *   ↓
     * setTextColor / BackgroundDrawable.setColor / setTrackColor / setColorFilter …
     * </pre>
     *
     * <h3>规则（只动色相，绝不动亮度）</h3>
     * <ol>
     *   <li>alpha = 0 ⇒ 原样；</li>
     *   <li>L* ≥ {@link #WHITE_TONE} ⇒ 白色保持白色（用户明确要求）；</li>
     *   <li>C &lt; {@link #MIN_REMAP_CHROMA} ⇒ 灰 / 黑 / 白，原样（中性色保护）；</li>
     *   <li>色相在宿主紫 {@link #HUE_WINDOW} 窗口内 ⇒ 换成强调色相，
     *       <b>L* 与 alpha 一分不动</b>（⇒ 对比度、层次、透明度全部保持原样）。</li>
     * </ol>
     *
     * <p>窗口外的颜色（红心 / 绿色播放键 / 蓝紫渐变里的蓝端）一律不动 ——
     * 宁可漏一个，也不能把封面、分类色条、状态色搞花。
     *
     * @param argb 宿主原本的 ARGB
     * @return 换色后的 ARGB；不需要换就是原值
     */
    public static int remap(int argb) {
        // ★ v0.1.47：Float 模块避让（SUSPEND 置位期间原样放行）
        if (suspended() || neutralOpaque(argb)) return argb;
        int a = argb >>> 24;
        if (a == 0) return argb;
        int rgb = argb & 0xFFFFFF;
        // ① 反向还原（见 sRev 的注释）：值可能是上一套强调色的产物
        Integer origin = sRev.get(rgb);
        if (origin != null) rgb = origin;
        // ★ v0.1.26：关掉开关时也要走完反向还原 —— 否则画面上「已经是强调色」的那些
        //   值永远回不到宿主原色（真机表现就是「关了跟没关一样」）。
        if (!sOn) return (a << 24) | rgb;
        // ② 正向表
        Integer hit = sLut.get(rgb);
        if (hit != null) return (a << 24) | hit;
        // ③ 真算
        int out = remapSlow((a << 24) | rgb);
        int outRgb = out & 0xFFFFFF;
        if (sLut.size() < LUT_MAX) sLut.put(rgb, outRgb);
        if (outRgb != rgb && sRev.size() < LUT_MAX) sRev.put(outRgb, rgb);
        if (outRgb != rgb && sMapLogs < 8) {
            sMapLogs++;
            log("map #" + String.format(java.util.Locale.ROOT, "%08x", (a << 24) | rgb)
                    + " -> #" + String.format(java.util.Locale.ROOT, "%08x", out));
        }
        return (a << 24) | outRgb;
    }

    /**
     * ★★ v0.1.25 用户第 1/2 条：{@code Paint#setColor} 用的<b>超热路径</b>版本。
     *
     * <h3>为什么还要再加一个挂点</h3>
     * v0.1.24 只挂了 {@code ColorPropConverter}（「颜色 prop 收口」），真机结果：
     * 只有零星几个色被换掉（日志里 {@code map #ffc387e8 -> …} 确实打出来了），
     * 但<b>文字、图标、加载圈、开关、遮罩依旧是紫的</b>。原因有两层：
     * <ol>
     *   <li>RN 0.81 走 Fabric 新架构，颜色是 <b>JS 侧 {@code processColor} 就算好的 int</b>，
     *       C++ 直接塞进 props ⇒ {@code ColorPropConverter} 只在少数老组件上被调到；</li>
     *   <li>即使是走它的那些，值也在 <b>prop 更新那一刻</b>就被写进 View /
     *       {@code Drawable} / {@code Paint} 了，之后<b>不会重读</b>。</li>
     * </ol>
     * 唯一「每帧都会重新问一次」的地方就是 {@code Paint#setColor}：
     * 文字每帧按 span 设色、矢量图标每帧按 fill 设色、加载圈每帧按 color 设色、
     * 背景 drawable 在 setColor 时设色。挂在这里，等于把宿主的调色板在
     * <b>落笔的那一刻</b>换掉 —— 与「它是什么组件」彻底无关。
     *
     * <h3>成本</h3>
     * 第一道是三个移位 + 两次比较的<b>纯算术预筛</b>：
     * 宿主紫族（含 #c387e8 / #3a3968 / #19122c 这类深紫）都满足 {@code B > R ≥ G}，
     * 而白 / 黑 / 灰（{@code B == R}）、暖色（{@code B < R}）、青色（{@code R < G}）
     * 在这里就被挡掉 —— 一帧上千次调用里能穿过这道门的只有个位数，
     * 后面的 LCh 换算与查表根本走不到。
     */
    public static int remapPaint(int argb) {
        // ★ v0.1.47：Float 模块避让（SUSPEND 置位期间原样放行）。
        //   （无彩色本来就在下面的 b<=r 预筛里放行，这里不重复判。）
        if (suspended()) return argb;
        int r = (argb >> 16) & 0xff;
        int g = (argb >> 8) & 0xff;
        int b = argb & 0xff;
        if (b <= r || r < g) return argb;      // 不是紫 / 蓝紫族：立刻放行
        if (!sOn) {
            // 关掉开关时也要把「已经写进 Paint 的强调色」还原成宿主原色。
            // 只查反向表（一次哈希），不做 LCh —— 热路径成本与开关无关。
            Integer origin = sRev.get(argb & 0xFFFFFF);
            return origin == null ? argb : ((argb & 0xFF000000) | origin);
        }
        int out = remap(argb);
        // 单独留一条「这一路真的生效了」的证据：如果下轮日志里一条 paint map 都没有，
        // 说明 Paint#setColor 被 AOT 内联了（钩子挂上但调用点不经过），要换挂点。
        if (out != argb && sPaintMaps < 4) {
            sPaintMaps++;
            log("paint map #" + String.format(java.util.Locale.ROOT, "%08x", argb)
                    + " -> #" + String.format(java.util.Locale.ROOT, "%08x", out));
        }
        return out;
    }

    /** 「落笔时刻」生效次数（诊断预算 4 条）。 */
    private static volatile int sPaintMaps;

    /** {@link #remap(int)} 的真算部分（带缓存的是外面那个）。 */
    private static int remapSlow(int argb) {
        int a = argb >>> 24;
        if (a == 0) return argb;
        int rgb = argb & 0xFFFFFF;
        if (rgb == 0xFFFFFF) return argb;
        double[] lch = toLch(argb);
        if (lch[0] >= WHITE_TONE) return argb;          // 近白：保持白
        if (lch[0] <= DARK_TONE) return argb;           // 近黑遮罩：保持原样（见 DARK_TONE）
        if (lch[1] < MIN_REMAP_CHROMA) return argb;     // 灰 / 黑：中性色保护
        if (hueDist(lch[2], hostHue()) > HUE_WINDOW) return argb;
        double mul = styleChromaMul();
        // ★★ v0.1.25 用户第 1 条：这里以前有一条「淡色托底」——
        //   彩度乘完之后如果低于原彩度的 45% 就拉回 45%。它把 Neutral / Monochrome
        //   这两种「本来就该几乎无彩」的风格直接抹平了（日志实证：
        //   style=Monochrome 却算出 chroma=23 的蓝紫）⇒ 用户换风格看不到差别。
        //   现在只有「有彩风格」（mul ≥ 0.5）才托底，避免 10% 的淡紫被乘成灰。
        // ★★ v0.1.27 用户第 5 条：再乘一次「壁纸活力」。这一步是
        //   「黑白灰壁纸抽出蓝色」的正面解法 —— 壁纸不彩，UI 就不该彩。
        double c = lch[1] * mul * sVibrancy;
        if (mul >= 0.5 && sVibrancy >= 0.5 && lch[1] >= 10.0) {
            c = Math.max(c, lch[1] * 0.45 * sVibrancy);
        }
        double floor = sVibrancy <= VIBRANCY_MIN + 0.001 ? 0.0 : 2.5;
        c = Math.max(floor, Math.min(c, 96.0));
        return fromLch(a, lch[0], c, sanitize(sSeedH + styleHueShift()));
    }

    /** 渐变里的颜色：整条数组就地过一遍 LUT。 */
    public static void remapArray(int[] colors) {
        if (colors == null || colors.length == 0 || !sOn || suspended()) return;
        try {
            for (int i = 0; i < colors.length; i++) {
                int c = colors[i];
                int n = remap(c);
                if (n != c) colors[i] = n;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 宿主紫的色相（度），只在第一次调用时算。 */
    private static double hostHue() {
        double h = sHostH;
        if (h < 0) {
            h = toLch(HOST_PURPLE)[2];
            sHostH = h;
        }
        return h;
    }

    /** 两个色相之间的最小夹角（度）。 */
    private static double hueDist(double a, double b) {
        double d = Math.abs(sanitize(a) - sanitize(b));
        return d > 180.0 ? 360.0 - d : d;
    }

    /**
     * 「观感一致、同透明度」的阴影 / 蒙层颜色。
     *
     * @param alpha 0–255，与宿主原本那层保持一致
     */
    public static int shadow(int alpha) {
        if (alpha <= 0) return 0;
        if (!sOn) return (alpha << 24);
        double c = Math.max(6.0, Math.min(sSeedC * styleChromaMul() * 0.55, 42.0));
        double h = sanitize(sSeedH + styleHueShift());
        return fromLch(alpha, 18.0, c, h);
    }

    /**
     * ★★★ v0.1.48（r48 用户第 1 条）：**防偷窥封面的「强调色罩」用的染色**。
     *
     * <h3>r47 为什么「生效了却看不出来」</h3>
     * 真机日志（r48）里罩色明明打出来了：
     * <pre>
     *   wash privacy -> ExpoImageView 1153x357 r=0 px
     * </pre>
     * 但用户看到的还是原来的紫色。原因就在颜色本身：这里用的是 {@link #shadow}，
     * 而 {@code shadow} 是<b>文字阴影</b>色 —— LCh 的 <b>L 固定 18（近黑）</b>、
     * 彩度还要乘 0.55。19% 的这种颜色叠在浅紫封面上只会「压暗一格」，
     * <b>色相根本没变</b> ⇒ 人眼判定「还是那个紫色」。
     *
     * <p>染色必须满足两条：<b>L 落在中段</b>（太亮像起雾、太暗像压黑，中段才是「上色」）、
     * <b>彩度接近种子色的本色</b>（不乘 0.55）。另外 alpha 也要给足 ——
     * 48/255 ≈ 19% 的叠色在浅底上肉眼几乎无感。
     *
     * @param alpha 0~255
     */
    public static int washTint(int alpha) {
        if (alpha <= 0) return 0;
        if (!sOn) return (alpha << 24);
        // ★★★ v0.1.56（r56 用户第 1 条）：**不再从宿主紫推导，直接构造一个高彩度的强调色**。
        //
        //   前两版的教训：
        //   · {@code remap(HOST_PURPLE)} 会保留宿主紫的<b>亮度 62</b>，再乘风格彩度系数 ——
        //     壁纸种子偏紫时（日志 `seed hue=275`）算出来是<b>淡紫</b>，和「原来的紫」几乎一样；
        //   · {@code fromLch(52, 种子彩度)} 也是同一类问题（彩度太低）。
        //
        //   现在固定：<b>亮度 55、彩度 70</b>（高彩度）、色相取强调色色相。
        //   彩度 70 的颜色在任何亮度下都是「一眼能看出被染过」的鲜明色，
        //   不可能再和宿主那层淡紫混为一谈。
        double h = sanitize(sSeedH + styleHueShift());
        return fromLch(alpha, 55.0, 70.0, h);
    }

    // ── 种子色 ──────────────────────────────────────────────────────

    /**
     * 从壁纸里挑种子色。
     *
     * <p>做法：缩到 {@link #SAMPLE}² ，逐像素算 LCh，按「彩度 × 亮度窗口」加权投票到 36 个
     * 色相桶里，取票数最高的桶，再把该桶像素按权重平均成最终种子。
     * 亮度窗口（20–88）是为了排除纯白背景与近黑阴影 —— 它们不是「这张图的颜色」。
     */
    private static void sampleSeed(Bitmap src) {
        Bitmap small = Bitmap.createScaledBitmap(src, SAMPLE, SAMPLE, true);
        if (small == null) return;
        boolean owned = small != src;
        try {
            // ★★ v0.1.26 用户第 1 条：**两轮采样**。
            //   r31 日志实证：壁纸偏黑时第一轮一个像素都不合格（L* 要 20~88、C 要 ≥9）
            //   ⇒ 「seed none（壁纸没有可用彩度）」⇒ state -> off ⇒ 界面整片跳回宿主紫。
            //   用户的描述是「自定义背景图为偏黑色时，重点色会跳回紫色」，就是这条。
            //   第二轮把亮度窗口放到 4~96、彩度门槛放到 2.5（暗部极淡的色彩也算数）。
            double[] s = voteSeed(small, 20.0, 88.0, MIN_SEED_CHROMA);
            if (s == null) s = voteSeed(small, 4.0, 96.0, 2.5);
            // ★★ v0.1.27 用户第 5 条：活力 = 壁纸整体的平均彩度。
            //   先用「面积」选出主色相，再按整体彩度决定这个色相该有多浓。
            //   黑白灰壁纸 ⇒ 平均彩度 2 左右 ⇒ 活力 0.12 ⇒ 强调色几乎中性；
            //   蓝调壁纸 ⇒ 平均彩度 15+ ⇒ 活力 1.0 ⇒ 该多蓝就多蓝。
            double meanC = s != null ? s[2] : 0.0;
            sVibrancy = Math.max(VIBRANCY_MIN,
                    Math.min(meanC / VIBRANCY_FULL_CHROMA, 1.0));
            if (s != null) {
                sSeedH = s[0];
                sSeedC = Math.max(MIN_SEED_CHROMA, s[1]);
                sLastH = sSeedH;
                sLastC = sSeedC;
                sHasSeed = true;
                log("seed hue=" + Math.round(sSeedH) + " chroma=" + Math.round(sSeedC)
                        + " meanC=" + Math.round(meanC)
                        + " vib=" + String.format(java.util.Locale.ROOT, "%.2f", sVibrancy)
                        + " style=" + styleName());
                return;
            }
            // 两轮都没彩度 ⇒ 壁纸是纯黑白灰。**绝不能关掉强调色**（那就是「跳回紫色」），
            // 也**绝不能凭空给一个彩色**（v0.1.26 的兜底蓝就是「黑白壁纸抽出蓝色」的元凶）。
            // 正确做法：色相沿用上一次（或一个中性的冷灰），彩度归零 ⇒ 整套 UI 走中性灰。
            if (sLastH >= 0) {
                sSeedH = sLastH;
                sSeedC = 0.0;
            } else {
                sSeedH = NEUTRAL_HUE;
                sSeedC = 0.0;
            }
            sHasSeed = true;
            sVibrancy = VIBRANCY_MIN;
            log("seed neutral（纯黑白灰壁纸）hue=" + Math.round(sSeedH)
                    + " meanC=" + Math.round(meanC) + " style=" + styleName());
        } catch (Throwable t) {
            sHasSeed = false;
            logOnce("sample failed: " + t);
        } finally {
            if (owned) {
                try {
                    small.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 纯灰壁纸用的中性色相（不偏红也不偏蓝的冷灰，只管「有方向」不至于取到宿主紫）。 */
    private static final double NEUTRAL_HUE = 250.0;

    /**
     * 一轮投票：把 {@code [lMin, lMax]} 亮度窗口里、彩度 ≥ {@code cMin} 的像素
     * 按<b>面积</b>加权投到 36 个色相桶，取票数最高的桶。
     *
     * <p>★★ v0.1.27 用户第 5 条：以前权重是 {@code (彩度 - 门槛) × 亮度居中程度}，
     * 等于「谁最艳谁说话」—— 一张黑白灰壁纸上只要有一小块蓝（天空、屏幕反光），
     * 那一小块就能压过占 90% 面积的中性区，抽出来的强调色变成<b>很浓的蓝</b>。
     * 现在权重改成<b>面积</b>（每像素 1 票，只保留轻度亮度偏好），
     * 真正占面积的那个色相才会赢；浓淡交给 {@link #sVibrancy} 统一收敛。
     *
     * @return {色相°, 该桶平均彩度, 全图平均彩度}；一个合格像素都没有时返回 {@code null}
     */
    private static double[] voteSeed(Bitmap small, double lMin, double lMax, double cMin) {
        double[] vote = new double[36];
        double[] sumC = new double[36];
        double[] sumA = new double[36];
        double[] sumB = new double[36];
        double allC = 0;
        int allN = 0;
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int p = small.getPixel(x, y);
                if ((p >>> 24) < 200) continue;          // 半透明像素不算
                double[] lch = toLch(p);
                // 全图平均彩度：不看门槛，只看「这张壁纸整体有多彩」（§sVibrancy）
                if (lch[0] >= 2 && lch[0] <= 98) {
                    allC += lch[1];
                    allN++;
                }
                if (lch[0] < lMin || lch[0] > lMax) continue;
                if (lch[1] < cMin) continue;
                int bin = (int) (sanitize(lch[2]) / 10.0) % 36;
                // 面积投票：一个像素一票，只在亮度上留一点偏好（太黑/太白不稳）
                double w = 1.0 - Math.abs(lch[0] - 55.0) / 120.0;
                if (w <= 0) continue;
                vote[bin] += w;
                sumC[bin] += lch[1] * w;
                // 用加权的 sin/cos 平均，避免 350°/10° 跨界断裂
                sumA[bin] += Math.cos(Math.toRadians(lch[2])) * w;
                sumB[bin] += Math.sin(Math.toRadians(lch[2])) * w;
            }
        }
        double meanC = allN == 0 ? 0.0 : allC / allN;
        int best = -1;
        double bestVote = 0;
        for (int i = 0; i < 36; i++) {
            if (vote[i] > bestVote) {
                bestVote = vote[i];
                best = i;
            }
        }
        if (best < 0 || bestVote <= 0) return null;
        return new double[]{
                sanitize(Math.toDegrees(Math.atan2(sumB[best], sumA[best]))),
                sumC[best] / bestVote,
                meanC};
    }

    // ── 九种风格 ────────────────────────────────────────────────────

    private static int indexOf(String name) {
        for (int i = 0; i < MoriConfig.ACCENT_STYLES.length; i++) {
            if (MoriConfig.ACCENT_STYLES[i].equals(name)) return i;
        }
        return 0;
    }

    /**
     * 风格的色相偏移（度）。
     *
     * <p>取值方向对齐 Material 3 Expressive：Expressive / FruitSalad / Rainbow 会故意
     * 离开种子色相做「有性格」的配色，TonalSpot / Fidelity / Content 保持种子色相。
     */
    private static double styleHueShift() {
        switch (sStyle) {
            case 3: return 35.0;    // Expressive
            case 4: return -40.0;   // Rainbow（取种子色的对侧，最「彩」）
            case 5: return 60.0;    // FruitSalad
            default: return 0.0;
        }
    }

    /** 风格的彩度倍率。Monochrome / Neutral 刻意压到几乎无彩。 */
    private static double styleChromaMul() {
        switch (sStyle) {
            case 1: return 0.10;    // Neutral
            case 2: return 1.85;    // Vibrant
            case 3: return 1.55;    // Expressive
            case 4: return 1.70;    // Rainbow
            case 5: return 1.45;    // FruitSalad
            case 6: return 0.00;    // Monochrome
            default: return 1.00;   // TonalSpot / Fidelity / Content
        }
    }

    private static void notifyChanged() {
        try {
            // 换风格 / 换壁纸 / 开关 ⇒ 正向表作废（键还是那些键，值变了）。
            // ⚠️ 反向表**不能清**：它记的是「上一套强调色造出来的值 → 宿主原色」，
            //    正是换风格之后把画面上那些旧色拉回新风格所需要的（见 sRev 的注释）。
            sLut.clear();
            // ★ v0.1.39：小字那张表同理（它的值也是按当前风格算出来的）。
            sLutSmall.clear();
            sMapLogs = 0;
        } catch (Throwable ignored) {
        }
        try {
            BgPeel.onAccentChanged();
        } catch (Throwable ignored) {
        }
    }

    /** 打点预算（每种状态最多几条，避免每轮刷屏）。 */
    private static volatile int sLogs;
    private static final int LOG_BUDGET = 8;

    /** 限次日志：强调色的诊断信息只需要「出现过」就行，不需要每帧一条。 */
    /** 诊断预算内打一条，且<b>同一句话只打一次</b>（r32 出现了 21 条重复的 off want=false）。 */
    private static void logOnce(String msg) {
        if (msg != null && msg.equals(sLastMsg)) return;
        sLastMsg = msg;
        if (sLogs >= LOG_BUDGET) return;
        sLogs++;
        log(msg);
    }

    /** 上一条诊断内容（去重用）。 */
    private static volatile String sLastMsg = "";

    private static void log(String msg) {
        try {
            io.github.ariinyume.morimod.util.XposedCompat.log(TAG + " " + msg);
        } catch (Throwable ignored) {
        }
    }

    // ── sRGB ↔ CIELAB / LCh ─────────────────────────────────────────

    private static final double XN = 0.95047;
    private static final double YN = 1.00000;
    private static final double ZN = 1.08883;
    private static final double EPS = 216.0 / 24389.0;
    private static final double KAPPA = 24389.0 / 27.0;

    /** @return {L*, C, h°} */
    private static double[] toLch(int argb) {
        double r = lin(((argb >> 16) & 0xff) / 255.0);
        double g = lin(((argb >> 8) & 0xff) / 255.0);
        double b = lin((argb & 0xff) / 255.0);
        double x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / XN;
        double y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / YN;
        double z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / ZN;
        double fx = f(x);
        double fy = f(y);
        double fz = f(z);
        double l = 116.0 * fy - 16.0;
        double a = 500.0 * (fx - fy);
        double bb = 200.0 * (fy - fz);
        double c = Math.sqrt(a * a + bb * bb);
        double h = sanitize(Math.toDegrees(Math.atan2(bb, a)));
        return new double[]{Math.max(0.0, l), c, h};
    }

    /**
     * LCh → ARGB，带「色域收缩」。
     *
     * <p>换色后经常落到 sRGB 之外（尤其是高彩度 + 中亮度），直接截断每通道会把色相带偏。
     * 这里先试原彩度，出界就二分收缩彩度 —— 保证「色相对、不爆色」。
     */
    private static int fromLch(int alpha, double l, double c, double h) {
        double lo = 0.0;
        double hi = Math.max(0.0, c);
        double[] rgb = lchToRgb(l, c, h);
        if (rgb == null) {
            for (int i = 0; i < 14; i++) {
                double mid = (lo + hi) / 2.0;
                if (lchToRgb(l, mid, h) == null) {
                    hi = mid;
                } else {
                    lo = mid;
                }
            }
            rgb = lchToRgb(l, lo, h);
            if (rgb == null) rgb = lchToRgb(l, 0.0, h);
        }
        if (rgb == null) return (alpha << 24);
        int a = Math.max(0, Math.min(255, alpha));
        return Color.argb(a, clamp255(rgb[0]), clamp255(rgb[1]), clamp255(rgb[2]));
    }

    /** @return 线性 sRGB {r,g,b}（0–1）；出界返回 null */
    private static double[] lchToRgb(double l, double c, double h) {
        double rad = Math.toRadians(sanitize(h));
        double a = c * Math.cos(rad);
        double bb = c * Math.sin(rad);
        double fy = (l + 16.0) / 116.0;
        double fx = fy + a / 500.0;
        double fz = fy - bb / 200.0;
        double x = XN * finv(fx);
        double y = YN * finv(fy);
        double z = ZN * finv(fz);
        double r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z;
        double g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z;
        double bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z;
        if (outside(r) || outside(g) || outside(bl)) return null;
        return new double[]{srgb(r), srgb(g), srgb(bl)};
    }

    private static boolean outside(double c) {
        return c < -0.0005 || c > 1.0005;
    }

    private static double lin(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double srgb(double c) {
        return c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055;
    }

    private static double f(double t) {
        return t > EPS ? Math.cbrt(t) : (KAPPA * t + 16.0) / 116.0;
    }

    private static double finv(double t) {
        double t3 = t * t * t;
        return t3 > EPS ? t3 : (116.0 * t - 16.0) / KAPPA;
    }

    private static int clamp255(double v) {
        int i = (int) Math.round(v * 255.0);
        return i < 0 ? 0 : Math.min(i, 255);
    }

    private static double sanitize(double deg) {
        double d = deg % 360.0;
        return d < 0 ? d + 360.0 : d;
    }
}
