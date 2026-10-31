package io.github.ariinyume.morimod.config;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * MoriMod 的**单档**功能配置（FR-0.3 规则 3/4）。
 *
 * <p>schema 带版本号；读到旧版本时迁移，迁移失败回落默认（全关）。
 * {@code profiles[]} 与 {@code activeProfileId} 为 v1.1 多配置档预留字段 ——
 * v1.0 恒定写入单档，v1.1 启用多档时无需改 schema 版本迁移逻辑（D-19）。
 *
 * <p>⚠️ 不含任何敏感数据：不记录曲目名、账号、播放记录（NFR-SEC2）。
 */
public final class MoriConfig {

    /**
     * 当前 schema 版本。
     *
     * <p>v2（v0.1.10）：`f2Transparent` 的语义从「把宿主背景清成透明」改为
     * 「使用桌面壁纸」（新字段 `f2DesktopWall`）。迁移只在**读到 v1 配置时做一次**，
     * 之后这个字段一律视为无效 —— 否则每次 clamp 都会去推导一次，
     * 那种隐式副作用在「旧广播被重投递」时会把用户选好的图顶掉。
     */
    public static final int SCHEMA_VERSION = 2;

    /**
     * 保存时刻（毫秒，v0.1.4）。
     *
     * <p>为什么需要它：宿主进程能读到**四份**配置（模块侧 remote prefs、模块侧 JSON 文件、
     * 宿主本地 JSON 副本、宿主本地 prefs），其中任意一份都可能是旧的 ——
     * 上一版固定按「本地副本优先」取，于是「先在设置页改字体 → 再开宿主」永远读到上一次的值，
     * 用户看到的就是「改了字体但没生效」。
     *
     * <p>现在改成**四份全读、取 savedAt 最大的那一份**（同分则按 remote 优先），
     * 谁新听谁的，不再猜哪条通道可靠。只有设置页保存时 stamp，宿主侧透传不改。
     */
    public long savedAt = 0L;

    // ── F1 播放控件自定义（仅面 A）────────────────────────────────────
    public boolean f1Enabled = false;
    /** 封面替换开关。 */
    public boolean f1Cover = true;
    /** 音轨名替换开关。 */
    public boolean f1Title = false;
    /** 社团名（artist）替换开关。 */
    public boolean f1Artist = false;

    // ── 作品封面屏蔽（v0.1.88 / r112 用户第 2 条）──────────────
    /**
     * 作品封面屏蔽：开启后宿主全页面的作品封面都不画（留空），
     * 但那块区域仍然可点（我们只跳绘制，不碰触摸事件）。
     */
    public boolean coverBlock = false;

    // ── F2 主背景自定义（仅主页）─────────────────────────────────────
    public boolean f2Enabled = false;
    /** 主页背景图（本地引用路径，空 = 未设置）。 */
    public String f2HomeBg = "";
    /**
     * 遮罩浓度 0–100（BR-2.4）—— <b>v0.1.21 起停用</b>。
     *
     * <p>用户反馈「调节它宿主 app 也没反应」：它只作用在 {@code DecorView} 的背景
     * {@code CoverDrawable} 上，而宿主内容层的每一层都由 {@code BgPeel} 直接画<b>位图</b>
     * （不含遮罩）⇒ 窗口层被完全盖住，调它视觉上永远零变化。
     * 设置页的滑条已删，{@link #clamp()} 把它钉死为 0，避免旧配置留下「窗口层比页面暗」
     * 的错位。
     */
    public int f2MaskAlpha = 0;

    /**
     * 播放页背景模糊 0–100（v0.1.21）。
     *
     * <h3>为什么要单开一个档位</h3>
     * 播放页（{@code playbackspbackground}，1081×1920 RGBA）是宿主自己那张「已经糊过的
     * 紫色背景图」，v0.1.21 起被换成用户自己的壁纸 —— 但播放页要压住封面/歌词，
     * 直接用清晰壁纸会抢内容，所以再给一个<b>只作用于播放页</b>的模糊档位，
     * 与全局 {@link #f2Blur} 叠加（先按 f2Blur 糊一次，再按本字段糊一次）。
     */
    public int f2PlayBlur = 0;

    /**
     * 强调色跟自定义背景取色（v0.1.22）。
     *
     * <p>开启后：从当前壁纸里抽一个种子色，按 {@link #f2AccentStyle} 指定的
     * Material 3 Expressive 色彩规范生成强调色，宿主界面里<b>除白色以外</b>的文字
     * 与所有阴影蒙层都跟着它走（实现见 {@code hook/AccentColor}）。
     *
     * <p>关掉就完全回到宿主原样（我们不缓存、不保留任何改色）。
     */
    public boolean f2Accent = false;

    /**
     * 强调色风格（v0.1.22）：{@code TonalSpot} / {@code Neutral} / {@code Vibrant} /
     * {@code Expressive} / {@code Rainbow} / {@code FruitSalad} / {@code Monochrome} /
     * {@code Fidelity} / {@code Content}。
     *
     * <p>九个名字直接沿用 Material 3 Expressive 的配色方案名，不翻译。
     */
    public String f2AccentStyle = "TonalSpot";

    /**
     * 九个配色方案名（Material 3 Expressive 原样保留，不翻译）。
     *
     * <p>放在 config 里而不是 {@code hook/AccentColor} 里：{@code config} 是两个进程
     * 唯一的耦合点，能力层只能单向依赖它，不能反过来。
     */
    public static final String[] ACCENT_STYLES = {
            "TonalSpot", "Neutral", "Vibrant", "Expressive", "Rainbow",
            "FruitSalad", "Monochrome", "Fidelity", "Content",
    };

    /** 名字是否合法（非法一律回落第一个）。 */
    public static boolean validAccentStyle(String name) {
        if (name == null) return false;
        for (String s : ACCENT_STYLES) {
            if (s.equals(name)) return true;
        }
        return false;
    }

    /**
     * 历史字段：「全透明背景」（v0.1.2 引入，v0.1.10 废弃）。
     *
     * <p>⚠️⚠️ 它当年的实现是「把宿主自己的背景资源换成一张全透明图」，真机上的结果
     * 是**整屏发白** —— 宿主的深色底被清掉，白字压在白色上，界面看起来一片白雾
     * （第 14 轮录像 + 日志 {@code bg=set,clear=true} 实证）。
     *
     * <p>v0.1.10 起这个模式**不再存在**：用户要的「全透明」被重新定义为
     * 「宿主背景 = 当前桌面壁纸」（见 {@link #f2DesktopWall}）。
     * 这里只为兼容旧配置/旧广播保留字段，读到 true 时由 {@link #clamp()} 迁移掉。
     */
    public boolean f2Transparent = false;

    // ★ r112：`f2DesktopWall`（桌面壁纸）已按用户要求整条移除。

    /**
     * 背景模糊强度 0–100（v0.1.6）。
     *
     * <p>为什么用户要它：宿主自己在背景上叠了一层全屏渐变/遮罩，把那层挡住之后
     * 壁纸会「太清楚、抢内容」—— 于是需要自己控制模糊程度，而不是全靠宿主决定。
     * 实现在 {@code util/BlurUtil}（降采样 + 两遍盒式模糊，一次算完缓存住），
     * 与遮罩**叠乘**：先模糊、再压黑。
     */
    public int f2Blur = 0;

    /**
     * 宿主页面底色的**不透明度** 0–100（v0.2.0）。
     *
     * <h3>为什么是这个方案（真机第 12 轮血证）</h3>
     * v0.1.6 把全屏层背景**置空** ⇒ 上一屏内容从底下透出来（重影）；
     * v0.1.7 改成往全屏层**贴位图** ⇒ 真机上整屏内容重复绘制、文字堆成一团（"爆炸"）。
     * 两次都是在动「宿主自己那层不透明的底色」，代价是 RN 的绘制顺序被打乱。
     *
     * <p>现在改成**最小改动**：不贴图、不清空、不改层级，只把铺满窗口的
     * {@code ColorDrawable} 换成**同一个颜色 + 更低 alpha**。
     * 颜色的 RGB 一字节不动 ⇒ 不会串色、不会错位、不会半屏以上重绘；
     * 只是让壁纸从这层半透明底色后面透出来。
     *
     * <p>100 = 完全不透明（等于不干预，宿还原样）；55 = 默认，壁纸可见但文字仍有底。
     */
    public int f2PageAlpha = 55;

    // ── F3 液态玻璃（第二批 v1.1，仅记录偏好，宿主侧尚未消费）──────────
    public boolean f3Enabled = false;

    // ── F4 自定义字体（全局强制替换）─────────────────────────────────
    public boolean f4Enabled = false;
    /** 字体族名（系统已装字体）；与 f4FontFile 二选一，文件优先。 */
    public String f4FontFamily = "";
    /** 本地字体文件引用路径（空 = 未设置）。 */
    public String f4FontFile = "";

    /**
     * 「我的字体」——用户导入过的字体文件路径列表（v0.1.2）。
     *
     * <p>以前选一次字体就等于「当前字体」，换个字体以后旧文件再也找不回来
     * （只能重新去文件管理器里挑一遍）。这里记一份清单，字体页给一张独立卡片：
     * 点一下就复用，长按就删除。
     *
     * <p>只存**模块自己 files/fonts 下的绝对路径**（导入时已复制进来），
     * 所以删文件不会波及用户原来的文件。
     */
    public final List<String> f4FontLib = new ArrayList<>();

    // ── F5 主页布局（第二批 v1.1）───────────────────────────────────
    public boolean f5Enabled = false;

    // ── 界面偏好 ─────────────────────────────────────────────────────
    /** 设置页语言：zh-CN | zh-TW | en。 */
    public String lang = "zh-CN";
    /** 是否隐藏模块桌面图标（作用于 manifest 里的 LauncherAlias）。 */
    public boolean hideLauncherIcon = false;

    // ── 诊断 ─────────────────────────────────────────────────────────
    /** 调试日志开关（诊断级日志总闸）。 */
    public boolean debugLog = false;

    // ── 多配置档（v1.1 预留）─────────────────────────────────────────
    /** 预留：v1.0 恒长为 1。 */
    public final List<String> profiles = new ArrayList<>();
    /** 预留：v1.0 恒为 "default"。 */
    public String activeProfileId = "default";

    // ── 素材库清单（随配置一起备份，不复制二进制，FR-0.4）─────────────
    public final List<MediaItem> mediaPool = new ArrayList<>();
    public final List<NameItem> namePool = new ArrayList<>();

    /** 图库项（封面 / 背景）。 */
    public static final class MediaItem {
        public String id = "";
        /** cover | background */
        public String type = "cover";
        public String path = "";
        public String label = "";
        public boolean enabled = true;
    }

    /** 名称库项（音轨名 / 社团名）。 */
    public static final class NameItem {
        public String id = "";
        /** track | artist */
        public String type = "track";
        public String text = "";
        public boolean enabled = true;
    }

    public MoriConfig() {
        if (profiles.isEmpty()) {
            profiles.add("default");
        }
    }

    /** 深拷贝（设置页草稿用，避免就地改已保存对象）。 */
    public MoriConfig copy() {
        MoriConfig c = new MoriConfig();
        c.savedAt = savedAt;
        c.f1Enabled = f1Enabled; c.f1Cover = f1Cover; c.f1Title = f1Title; c.f1Artist = f1Artist;
        c.coverBlock = coverBlock;
        c.f2Enabled = f2Enabled; c.f2HomeBg = f2HomeBg; c.f2MaskAlpha = f2MaskAlpha;
        c.f2Transparent = f2Transparent;
        c.f2Blur = f2Blur;
        c.f2PlayBlur = f2PlayBlur;
        c.f2Accent = f2Accent;
        c.f2AccentStyle = f2AccentStyle;
        c.f2PageAlpha = f2PageAlpha;
        c.f3Enabled = f3Enabled;
        c.f4Enabled = f4Enabled; c.f4FontFamily = f4FontFamily; c.f4FontFile = f4FontFile;
        c.f4FontLib.clear();
        c.f4FontLib.addAll(f4FontLib);
        c.f5Enabled = f5Enabled;
        c.lang = lang; c.hideLauncherIcon = hideLauncherIcon;
        c.debugLog = debugLog;
        c.activeProfileId = activeProfileId;
        c.profiles.clear();
        c.profiles.addAll(profiles);
        c.mediaPool.clear();
        for (MediaItem m : mediaPool) {
            MediaItem n = new MediaItem();
            n.id = m.id; n.type = m.type; n.path = m.path; n.label = m.label; n.enabled = m.enabled;
            c.mediaPool.add(n);
        }
        c.namePool.clear();
        for (NameItem n0 : namePool) {
            NameItem n = new NameItem();
            n.id = n0.id; n.type = n0.type; n.text = n0.text; n.enabled = n0.enabled;
            c.namePool.add(n);
        }
        return c;
    }

    /** 边界钳制 + 不变式（任何读配置的路径都遵守同一条规则）。 */
    public void clamp() {
        // ★ v0.1.21：遮罩浓度滑条已删（它只作用在被完全盖住的窗口层上 ⇒ 调了没反应）。
        //   钉死 0，旧配置也不会留下「窗口层比页面暗一档」的错位。
        f2MaskAlpha = 0;
        if (f2Blur < 0) f2Blur = 0;
        if (f2Blur > 100) f2Blur = 100;
        if (f2PlayBlur < 0) f2PlayBlur = 0;
        if (f2PlayBlur > 100) f2PlayBlur = 100;
        if (!validAccentStyle(f2AccentStyle)) {
            f2AccentStyle = "TonalSpot";
        }
        if (f2PageAlpha < 0) f2PageAlpha = 0;
        if (f2PageAlpha > 100) f2PageAlpha = 100;
        if (f2HomeBg == null) f2HomeBg = "";
        // ── F2：两种壁纸来源互斥 + 旧字段一律失效（v0.1.10）──────────────
        //   ① f2Transparent 已废弃（它的实现是「把宿主背景清成透明」，真机表现是
        //      整屏发白）。这里只做无效化，**不推导** f2DesktopWall ——
        //      迁移只在 fromJson 读到 v1 配置时做一次，避免隐式副作用。
        //   ② 图片与桌面壁纸只能有一个生效，桌面壁纸优先。
        f2Transparent = false;
        if (f4FontFamily == null) f4FontFamily = "";
        if (f4FontFile == null) f4FontFile = "";
        // 「我的字体」清单：去 null / 去空 / 去重，保持用户添加顺序。
        // 导入的备份可能来自别的设备（路径完全不同），所以**不校验文件是否存在** ——
        // UI 渲染时会自己过滤掉不存在的项。
        for (int i = f4FontLib.size() - 1; i >= 0; i--) {
            String p = f4FontLib.get(i);
            if (p == null || p.isEmpty() || f4FontLib.indexOf(p) != i) f4FontLib.remove(i);
        }
        if (lang == null || lang.isEmpty()) lang = "zh-CN";
        if (profiles.isEmpty()) profiles.add("default");
        // ── F1 系统播放控件自定义：**已搁置**（宿主最新版自带该功能）──────────
        //    设置页入口已隐藏。这里强制关掉 + 清子项，保证即使旧配置/导入备份
        //    里 f1Enabled=true，能力层也不会去动宿主的通知栏与锁屏样式。
        f1Enabled = false;
        f1Cover = true;
        f1Title = false;
        f1Artist = false;
        // ── F3/F5 是后续批次功能，宿主侧**尚未消费**这两个开关 ──────────────
        //    不再强制归零：设置页把它们作为偏好持久化，用户重开设置页仍能看到上次选择。
        //    真正生效要等 FeatureF3 / FeatureF5。
    }

    // ── JSON ────────────────────────────────────────────────────────

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("schema", SCHEMA_VERSION);
            o.put("savedAt", savedAt);
            o.put("f1Enabled", f1Enabled);
            o.put("f1Cover", f1Cover);
        o.put("coverBlock", coverBlock);
            o.put("f1Title", f1Title);
            o.put("f1Artist", f1Artist);
            o.put("f2Enabled", f2Enabled);
            o.put("f2HomeBg", f2HomeBg);
            o.put("f2MaskAlpha", f2MaskAlpha);
            o.put("f2Transparent", f2Transparent);
                o.put("f2Blur", f2Blur);
            o.put("f2PlayBlur", f2PlayBlur);
            o.put("f2Accent", f2Accent);
            o.put("f2AccentStyle", f2AccentStyle);
            o.put("f2PageAlpha", f2PageAlpha);
            o.put("f3Enabled", f3Enabled);
            o.put("f4Enabled", f4Enabled);
            o.put("f4FontFamily", f4FontFamily);
            o.put("f4FontFile", f4FontFile);
            JSONArray lib = new JSONArray();
            for (String p : f4FontLib) lib.put(p);
            o.put("f4FontLib", lib);
            o.put("f5Enabled", f5Enabled);
            o.put("lang", lang);
            o.put("hideLauncherIcon", hideLauncherIcon);
            o.put("debugLog", debugLog);
            o.put("activeProfileId", activeProfileId);
            JSONArray prof = new JSONArray();
            for (String p : profiles) prof.put(p);
            o.put("profiles", prof);
            JSONArray media = new JSONArray();
            for (MediaItem m : mediaPool) {
                JSONObject j = new JSONObject();
                j.put("id", m.id); j.put("type", m.type); j.put("path", m.path);
                j.put("label", m.label); j.put("enabled", m.enabled);
                media.put(j);
            }
            o.put("mediaPool", media);
            JSONArray names = new JSONArray();
            for (NameItem n : namePool) {
                JSONObject j = new JSONObject();
                j.put("id", n.id); j.put("type", n.type); j.put("text", n.text);
                j.put("enabled", n.enabled);
                names.put(j);
            }
            o.put("namePool", names);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    /** 解析；任何异常/版本不符 → 返回 null（调用方回落默认）。 */
    public static MoriConfig fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            int schema = o.optInt("schema", -1);
            if (schema < 1) return null;      // 未来版本或损坏
            MoriConfig c = new MoriConfig();
            c.savedAt = o.optLong("savedAt", 0L);
            c.f1Enabled = o.optBoolean("f1Enabled", false);
            c.f1Cover = o.optBoolean("f1Cover", true);
        c.coverBlock = o.optBoolean("coverBlock", false);
            c.f1Title = o.optBoolean("f1Title", false);
            c.f1Artist = o.optBoolean("f1Artist", false);
            c.f2Enabled = o.optBoolean("f2Enabled", false);
            c.f2HomeBg = o.optString("f2HomeBg", "");
            c.f2MaskAlpha = o.optInt("f2MaskAlpha", 0);
            c.f2Transparent = o.optBoolean("f2Transparent", false);
                c.f2Blur = o.optInt("f2Blur", 0);
            c.f2PlayBlur = o.optInt("f2PlayBlur", 0);
            c.f2Accent = o.optBoolean("f2Accent", false);
            c.f2AccentStyle = o.optString("f2AccentStyle", "TonalSpot");
            c.f2PageAlpha = o.optInt("f2PageAlpha", 55);
            c.f3Enabled = o.optBoolean("f3Enabled", false);
            c.f4Enabled = o.optBoolean("f4Enabled", false);
            c.f4FontFamily = o.optString("f4FontFamily", "");
            c.f4FontFile = o.optString("f4FontFile", "");
            JSONArray lib = o.optJSONArray("f4FontLib");
            if (lib != null) {
                for (int i = 0; i < lib.length(); i++) {
                    String p = lib.optString(i, "");
                    if (!p.isEmpty()) c.f4FontLib.add(p);
                }
            }
            c.f5Enabled = o.optBoolean("f5Enabled", false);
            c.lang = o.optString("lang", "zh-CN");
            c.hideLauncherIcon = o.optBoolean("hideLauncherIcon", false);
            c.debugLog = o.optBoolean("debugLog", false);
            c.activeProfileId = o.optString("activeProfileId", "default");
            JSONArray prof = o.optJSONArray("profiles");
            c.profiles.clear();
            if (prof != null) {
                for (int i = 0; i < prof.length(); i++) c.profiles.add(prof.optString(i));
            }
            if (c.profiles.isEmpty()) c.profiles.add("default");
            JSONArray media = o.optJSONArray("mediaPool");
            if (media != null) {
                for (int i = 0; i < media.length(); i++) {
                    JSONObject j = media.optJSONObject(i);
                    if (j == null) continue;
                    MediaItem m = new MediaItem();
                    m.id = j.optString("id", "");
                    m.type = j.optString("type", "cover");
                    m.path = j.optString("path", "");
                    m.label = j.optString("label", "");
                    m.enabled = j.optBoolean("enabled", true);
                    c.mediaPool.add(m);
                }
            }
            JSONArray names = o.optJSONArray("namePool");
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    JSONObject j = names.optJSONObject(i);
                    if (j == null) continue;
                    NameItem n = new NameItem();
                    n.id = j.optString("id", "");
                    n.type = j.optString("type", "track");
                    n.text = j.optString("text", "");
                    n.enabled = j.optBoolean("enabled", true);
                    c.namePool.add(n);
                }
            }
            // ── v1 → v2 迁移（只做一次，见 SCHEMA_VERSION 注释）──────────────
            //    v1 里的「全透明」（f2Transparent）在真机上表现为整屏发白；
            //    它想表达的是「宿主背景 = 我的桌面」，所以迁移成新的桌面壁纸来源：
            //      · 没选图 → 用桌面壁纸（这正是用户当时想要的效果）
            //      · 已选图 → 丢弃这条脏数据，图才是用户真正选的
            if (schema < 2 && c.f2Transparent) {
                c.f2Transparent = false;
                }
            c.clamp();
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 一行摘要（进诊断日志，脱敏）。 */
    public String summary() {
        return "F1=" + f1Enabled + "(c" + f1Cover + "/t" + f1Title + "/a" + f1Artist + ")"
                + " F2=" + f2Enabled + "(bg=" + (f2HomeBg.isEmpty() ? "none" : "set")
                 + ",mask=" + f2MaskAlpha + ",clear=" + f2Transparent
                + ",blur=" + f2Blur + ",playBlur=" + f2PlayBlur
                + ",accent=" + f2Accent + "/" + f2AccentStyle
                + ",pageA=" + f2PageAlpha + ")"
                + " F4=" + f4Enabled + "(fam=" + (f4FontFamily.isEmpty() ? "-" : f4FontFamily)
                + ",file=" + (f4FontFile.isEmpty() ? "none" : "set") + ")"
                + " lang=" + lang + " hideIcon=" + hideLauncherIcon
                + " media=" + mediaPool.size() + " names=" + namePool.size();
    }
}
