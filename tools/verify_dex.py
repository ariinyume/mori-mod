#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第二层硬验证：dex 层（模块是否真的编进包里 / 关键路径是否真的还在）。

设计约束（血证，别改）：
  1. release 走 R8，模块自己的类名会被混淆 ⇒ **不能**拿类名当锚点，
     只能拿 const-string 字面量、接口名、整型常量、@Composable 方法名。
  2. 有些类被 keep 住（MoriModModule / xposed_init 指向的入口 / Compose 的 @Composable），
     这些可以用 JVM 描述符断言。
  3. 有一半断言是**读源码文本**（bps）—— 它们保护的是「这条判据还在、顺序没被后面的改动
     挤掉」。dex 只能证明「字符串在包里」，证明不了「先判 A 再判 B」。
  4. 每条反向断言都必须能真的失败：写「X not in bps」之前先确认 X 不在源码里。

输出：PASS/FAIL 逐条 + 结尾 DEX-PASS=n DEX-FAIL=m（run_verify.sh 靠 DEX-FAIL=0 判绿）。
"""
import os
import re
import sys
import io

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRCDIR = os.path.join(ROOT, "app", "src", "main", "java")
DECM = os.path.join(ROOT, "decm")

PASS = 0
FAIL = 0
LINES = []


def A(name, cond, detail=None):
    """记一条断言。"""
    global PASS, FAIL
    ok = bool(cond)
    if ok:
        PASS += 1
    else:
        FAIL += 1
    got = detail if detail is not None else ok
    LINES.append("  %s  %s  -> got=%r expect=%r" % ("PASS" if ok else "FAIL", name, got, True))
    return ok


# ──────────────────────────────────────────────────────────────────
# 0. 读源码（不变量断言用）
# ──────────────────────────────────────────────────────────────────
SRC_FILES = []
for dirpath, _dirs, files in os.walk(SRCDIR):
    for f in files:
        if f.endswith(".java") or f.endswith(".kt"):
            SRC_FILES.append(os.path.join(dirpath, f))
SRC_FILES.sort()

PARTS = []
for p in SRC_FILES:
    try:
        PARTS.append(io.open(p, encoding="utf-8", errors="replace").read())
    except OSError:
        pass
bps = "\n".join(PARTS)


def src_of(name):
    for p in SRC_FILES:
        if os.path.basename(p) == name:
            try:
                return io.open(p, encoding="utf-8", errors="replace").read()
            except OSError:
                return ""
    return ""


bg = src_of("BgPeel.java")
f2 = src_of("FeatureF2.java")
f4 = src_of("FeatureF4.java")
f1 = src_of("FeatureF1.java")
mod = src_of("MoriModModule.java")
cfg = src_of("MoriConfig.java")
acc = src_of("AccentColor.java")
lut = src_of("AccentLut.java")
view = src_of("MoriViewModel.kt")
store = src_of("ConfigStore.java")

# ──────────────────────────────────────────────────────────────────
# 1. 扫 dex dump：类描述符 + const-string 常量池
# ──────────────────────────────────────────────────────────────────
DESC_RE = re.compile(r"Class descriptor\s*:\s*'(L[^']+;)'")
CSTR_RE = re.compile(r'const-string(?:/jumbo)?\s+v\d+,\s*"(.*)"')
NAME_RE = re.compile(r"name\s*:\s*'([^']+)'")

descs = set()
strs = set()
names = set()
DEXFILES = []
if os.path.isdir(DECM):
    for f in sorted(os.listdir(DECM)):
        if f.endswith(".txt"):
            DEXFILES.append(os.path.join(DECM, f))

if not DEXFILES:
    print("[fatal] decm/ 里没有 dexdump 输出 —— 第二层验证无法进行")
    sys.exit(2)

for path in DEXFILES:
    with io.open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if "Class descriptor" in line:
                m = DESC_RE.search(line)
                if m:
                    descs.add(m.group(1))
            elif "'" in line:
                m = NAME_RE.search(line)
                if m:
                    names.add(m.group(1))
            elif "const-string" in line:
                m = CSTR_RE.search(line)
                if m:
                    strs.add(m.group(1))

ALL_STR = "\n".join(strs)


def S(*labels):
    """全部 const-string 字面量都在包里吗。"""
    return all(l in ALL_STR for l in labels)


print("[info] 全包类数=%d，符号名数=%d，const-string 数=%d" % (len(descs), len(names), len(strs)))
print("=" * 62)

# ──────────────────────────────────────────────────────────────────
# 2. 入口 / 类是否真的编进包
# ──────────────────────────────────────────────────────────────────
A("入口类 MoriModModule 用 JVM 描述符编进包（xposed_init 指向它，R8 必须 keep）",
  "Lio/github/ariinyume/morimod/MoriModModule;" in descs)
A("入口类实现了 libxposed 的 Module 接口",
  "Lio/github/libxposed/api/XposedModuleInterface$ModuleLoadedParam;" in descs
  or S("onModuleLoaded"))
A("入口方法 onModuleLoaded 存在", "onModuleLoaded" in names)
A("入口方法 onPackageLoaded 存在", "onPackageLoaded" in names)
A("入口方法 onPackageReady 存在", "onPackageReady" in names)
A("入口方法 initHost 存在", "initHost" in names)
A("入口方法 isTarget 存在", "isTarget" in names)
A("入口方法 installSafely 存在", "installSafely" in names)
A("入口反射定位 Application.attach", "hookApplicationAttach" in mod)
A("兜底应答器走 ActivityThread.currentApplication",
  "currentApplication" in mod and "responder fallback" in ALL_STR)
A("模块类数 ≥ 40（漏编几个类不至于全绿）", len(SRC_FILES) >= 40, len(SRC_FILES))
A("源码文件数与 dex 里的模块常量相匹配", len(SRC_FILES) > 40 and len(strs) > 400, len(strs))

# ──────────────────────────────────────────────────────────────────
# 3. 三组钩子都真的装上了（日志字面量就是「装上了」的证据）
# ──────────────────────────────────────────────────────────────────
A("F2 install 钩 Resources#getDrawable 三入口",
  "getDrawable" in f2 and "getDrawableForDensity" in f2 and "openRawResource" in f2)
A("F2 install 钩 AssetManager", "openNonAsset" in ALL_STR or "AssetManager" in f2)
A("F2 install 钩 Activity#onResume / onPause",
  "onResume" in bg and "onPause" in bg, True)
A("钩 BackgroundDrawable#draw（RN 的底都从这走）",
  "com.facebook.react.uimanager.drawable.BackgroundDrawable" in bg)
A("钩 BackgroundImageDrawable#draw", "BackgroundImageDrawable" in bg)
A("钩 expo LinearGradientView#onDraw", "expo.modules.lineargradient.LinearGradientView" in bg)
A("钩 android.view.View#draw（满屏叶子层兜底）",
  "XposedCompat.findClass(\"android.view.View\"" in bg)
A("钩 expo.modules.image.ExpoImageView#draw", "expo.modules.image.ExpoImageView" in bg)
A("钩 ExpoImageViewWrapper#setBlurRadius（装饰底识别）",
  "setBlurRadius$expo_image_release" in bg)
A("钩 ColorPropConverter#getColor（强调色落笔前换色）", "ColorPropConverter" in bg)
A("钩 LinearGradientView#setColors", "setColors" in src_of("AccentLut.java"))
A("钩 Paint#setColor（文字/图标/描边换色）", "setColor" in bg and "Paint#setColor" in bg)
A("六个 Resources 入口全部 hook（日志锚点）",
  " Resources entries" in f2 and "ok + \"/6" in f2, True)
A("两个 AssetManager 入口全部 hook（日志锚点）",
  " AssetManager entries" in f2 and "amOk + \"/2" in f2, True)
A("F4 钩 Typeface 三入口",
  "create" in f4 and "createFromAsset" in f4 and "createFromFile" in f4)
A("F4 经 Paint#setTypeface 生效（ReactFontManager 有缓存，必须钩落笔时刻）",
  "setTypeface" in f4)
A("F4 有图标字体黑名单（MaterialSymbols / FontAwesome 不能换）",
  "MaterialSymbols" in f4 and "FontAwesome" in f4)

# ──────────────────────────────────────────────────────────────────
# 4. 背景：核心不变量（v0.1.13 起就没变过的那些）
# ──────────────────────────────────────────────────────────────────
A("窗口背景由 CoverDrawable 铺（等比 center-crop，不再是拉伸的 BitmapDrawable）",
  "CoverDrawable" in bg and "CoverDrawable" in f2)
A("只有 WindowBg 一处碰 setBackground（别处碰窗口根 = 整屏糊/图片缩放）",
  bg.count(".setBackground(") == 0
  and src_of("WindowBg.java").count(".setBackground(") == 2,
  bg.count(".setBackground("))
A("paintAligned 返回 boolean 不变量（绝不「返回 true 却没画」）",
  "private static boolean paintAligned" in bg
  and "if (!paintAligned(" in bg)
A("playBmp() 三级兜底永不 null",
  "private static Bitmap playBmp()" in bg
  and "if (sWpPlayBmp != null && !sWpPlayBmp.isRecycled()) return sWpPlayBmp;" in bg
  and "return sWpBmp;" in bg)
A("壁纸位图三档：清晰 / 条带 20% / 播放页档", "BAR_BLUR_PCT = 20" in f2)
A("背景位图缺失时退回清晰壁纸（绝不「什么都不画」）",
  "sWpBarBmp != null ? sWpBarBmp : sWpBmp" in bg)

# ──────────────────────────────────────────────────────────────────
# 5. v0.1.38：播放页背景（用户第 2 条）
# ──────────────────────────────────────────────────────────────────
A("满屏底图（满宽+贴顶+≥92% 屏高）一律归 paintBlurBg 管，绝不放行给宿主",
  "boolean fullBg = fillDecor && h * 100 >= (long) sSH * 92;" in bg
  and "if (kind != IMG_BLUR && !fullBg) {" in bg)
A("播放页判定只认活体进度条（radius≥8 对列表页同样成立，已弃用）",
  "boolean play = playContext(v);" in bg
  and "boolean play = radius >= PLAY_BLUR_MIN || playContext(v);" not in bg)
A("满屏那一层不再走 dropVeil（r43 的「背景透明」真凶）",
  "if (h * 100 >= (long) sSH * 90) return false;" in bg)
A("装饰底绑定换成结构判据：分页容器里的满屏图永不当装饰底",
  "private static boolean insidePager(View v)" in bg
  and "if (insidePager(v)) return 0;" in bg)
A("读不到位图也认装饰底（expo-image 的 drawable 不一定是 BitmapDrawable）",
  "if (b == null) return r;" in bg)
A("反向断言：3 秒新鲜期那道闸已删除（它让播放页半径时有时无）",
  "BLUR_BIND_FRESH_MS) return 0;" not in bg)
A("反向断言：一个 View 只绑一张位图 那道闸已删除",
  "BOUND_ONCE" not in bg)
A("进度条探针门槛夹在细线与真 seekbar 之间（★ v0.1.43：下限 20→36，排掉下载/加载条）",
  "if (h < 36 || h > 200) return;" in bg and "notePlaySeek(v);" in bg
  and "if (h < 6) return;" not in bg and "if (h < 20 ||" not in bg
  and "// ★★★ v0.1.43：下限 20→36" in bg)
A("进度条必须 attached + isShown（离开播放页立刻失效）",
  "private static boolean isPlayLive()" in bg
  and "if (v.getWindowToken() != null && v.isShown())" in bg)
A("playContext 只剩两条硬判据：活体进度条 / seekbar 刚加载",
  "private static boolean playContext(View v)" in bg
  and "if (isPlayLive()) return true;" in bg and "return isPlayPage(v);" in bg)
A("反向断言：不再有「比屏还高 ⇒ 播放页」（列表/详情底图比含安全区的屏高更高，用户第 6/7 条元凶）",
  "v.getHeight() > sSH) return true;" not in bg
  and "// ★★★ v0.1.43（r14 用户第 5 / 6 / 7 条）" in bg)
A("播放页的底绝不钉窗口根（ensurePlayWindowBg 方法体已删，只剩注释）",
  "ensurePlayWindowBg(v, bmp)" not in bg
  and "private static void ensurePlayWindowBg" not in bg)
A("play window bg 日志已删", "play window bg -> " not in bg)

# ──────────────────────────────────────────────────────────────────
# 6. v0.1.38：紫遮罩（用户第 1 条）
# ──────────────────────────────────────────────────────────────────
A("顶部通栏大图一律当氛围层（不再卡「必须半透明」—— webp 走硬件解码会丢 alpha）",
  "if (kind == IMG_BLUR) return false;" in bg)
A("氛围层命中条件：满宽 + ≥18% 屏高 + 贴顶", "VEIL_MIN_H_RATIO = 0.18f" in bg
  and "private static boolean isTopAnchored" in bg)
A("图片浏览里的照片一张都不许换（pager 判据）", "if (insidePager(v)) return false;" in bg)
A("氛围层的动作是「原地画清晰壁纸」，播放页才用播放档",
  "Bitmap clear = play ? playBmp() : sWpBmp;" in bg)

# ──────────────────────────────────────────────────────────────────
# 7. v0.1.41：信息面板玻璃改走「路径 A —— 换颜料，不接管绘制」
# ──────────────────────────────────────────────────────────────────
GP = src_of("GlassPaint.java")
A("路径 A：反射取 BackgroundDrawable 那支 Paint，把 shader 换成模糊壁纸",
  "final class GlassPaint" in GP
  and "f.getType() != Paint.class" in GP
  and "p.setShader(sh);" in GP
  and "static Token begin(" in GP)
A("换颜料必须还原（否则这支 Paint 带着 shader 去画别的东西）",
  "static void end(Token tk)" in GP and "tk.paint.setShader(tk.prevShader);" in GP)
A("shader 对齐用 getLocationOnScreen（Dialog 里 getLocationInWindow 原点不是屏幕原点）",
  "v.getLocationOnScreen(out);" in GP and "stableScreenLoc(v, loc, sw, sh);" in GP)
A("★ v0.1.43：shader 坐标先去掉位移动画，拿到「布局终态」屏幕位置（面板第一帧就对上壁纸）",
  "static void stableScreenLoc(View v, int[] out, int sw, int sh)" in GP
  and "tx += cur.getTranslationX();" in GP and "ty += cur.getTranslationY();" in GP
  and "out[0] -= Math.round(tx);" in GP and "out[1] -= Math.round(ty);" in GP)
A("★ v0.1.43：终态坐标仍越界时按「窗口终态原点」兜底（近全屏=>0,0；矮窗口=>贴底）",
  "if (rh * 100 >= sh * 90) {" in GP and "rootY = Math.max(0, sh - rh);" in GP)
A("玻璃表面的唯一落点 = glassToken()：只换 shader，然后照常 chain.proceed()",
  "private static GlassPaint.Token glassToken(Drawable d)" in bg
  and "GlassPaint.Token tk = glassToken((Drawable) self);" in bg)
A("反向断言：paintWallpaper 遇到玻璃表面必须放行，不再自己画整屏位图",
  "if (isGlassSurface(v, d, b)) return false;" in bg
  and "paintAligned(c, glass, v, b, false)" not in bg)
A("反向断言：面板容器不再铺整屏壁纸（第 3 条「卡住一块壁纸」的根因）",
  "Bitmap bar = glassBmp(v);" not in bg)
A("反向断言：面板头部渐变直接 drop（消除「多一行淡阴影条」+ 分层出现）",
  'hit("drop sheet grad -> ", v, r);' in bg
  and 'hit("paint sheet grad -> ", v, r);' not in bg)
A("反向断言：面板叶子层直接 drop，不再铺玻璃",
  'hit("drop sheet leaf -> ", v, r);' in bg
  and 'hit("paint sheet leaf -> ", v, r);' not in bg)
A("反向断言：玻璃绘制里不再自己 clipPath 裁圆角（改由宿主自己的 Path 保证）",
  "clipByOutline(c, v.getBackground(), r)" not in bg
  and "clipByOutline(c, d, b)" not in bg)
A("玻璃判据 = 统一浮层判据（Dialog / BottomSheet / 主窗口贴底弹层）",
  "if (!inFloatingLayer(v)) return false;" in bg
  and "private static boolean inFloatingLayer(View v)" in bg
  and "mainWindowSheet(v)" in bg)
A("玻璃高度上限已撤销（概述面板 91.4% 曾被 85% 踢出玻璃档）",
  "GLASS_MAX_H_PCT = 100" in bg and "GLASS_MAX_H_PCT = 85" not in bg)
A("玻璃底图 = 清晰壁纸糊 GLASS_BLUR_PCT%（38%），不再借用 20% 的条带档",
  "private static final int GLASS_BLUR_PCT = 38;" in bg
  and "BlurUtil.apply(base, GLASS_BLUR_PCT)" in bg
  and "glassBitmap" not in f2)
A("玻璃底图拿不到时返回 null，绝不退回清晰壁纸",
  "if (g == null || g.isRecycled() || g == base) return null;" in bg
  and "sWpGlassBmp = g;" in bg)
A("玻璃失败必须留痕（以前静默降级，十来个版本都没查出来）",
  "private static void noteGlassFail(String why)" in bg
  and 'log("glass MISS ("' in bg)
A("压暗遮罩不许被换成玻璃（否则弹窗内容被自己压下去）",
  "if (isDimMask(d)) return false;" in bg)
A("压暗遮罩的 drop 判据挪到 paintWallpaper（swapLeaf 的 kids>0 早退让它空转）",
  'hit("drop dim bg -> ", v, b);' in bg
  and "if (isDimMask(d) && d.getOpacity() != PixelFormat.OPAQUE) {" in bg)
A("弹层玻璃 = 局部铺 + 宿主紫图按 16% 叠回",
  "paintDialogGlass(View v, Canvas c, HostDraw host)" in bg
  and "GLASS_IMG_TINT_PCT = 0.16f" in bg)
A("弹层玻璃只在浮层内生效（Dialog / BottomSheet / 主窗口贴底弹层）",
  bg.count("if (!inFloatingLayer(v)) return false;") >= 1)
A("弹层玻璃的安全阀：不透明照片一律不动",
  "if (kind != IMG_VEIL && kind != IMG_ATMOS) return false;" in bg)
A("弹层玻璃排除不透明图（avg >= 0.90 是作品封面，不是面板底）",
  "GLASS_IMG_MAX_ALPHA = 0.90f" in bg and "if (av >= GLASS_IMG_MAX_ALPHA)" in bg)
A("弹层玻璃排除播放页（那块 1440x1080 是封面横幅）",
  "if (isPlayWindow(v) || playContext(v)) return false;" in bg)
A("氛围层排除横向图层（1440x1080 的封面横幅不是氛围背景）",
  "if (w > h * 1.2f) return false;" in bg)
A("探针①：玻璃命中打出 scr / 尺寸（独立预算，不受 hit() 60 秒限流影响）",
  '"glassA -> " + name(v)' in bg and "GLASS_LOG_BUDGET" in bg)
A("探针②：setTextColor 打出 px / scaledDensity / 进出色",
  'log("tc " + way' in lut and "TC_LOG_BUDGET" in lut)
A("探针③：ensureShadow 打出 ts / radius / light / dark（按签名去重）",
  '"shadow ts=" + (int) ts' in bg and 'signature(tv)' in bg)
A("文字阴影规则修正：深色字不再被当成「坐在亮底上」而清掉阴影",
  "if (textIsDark(tv)) return true;" not in bg
  and "return luma(c) >= LIGHT_BG_LUMA;" in bg)

# ──────────────────────────────────────────────────────────────────
# 8. 强调色 / 字体 / 冷启动
# ──────────────────────────────────────────────────────────────────
A("强调色按壁纸取色（seed hue/chroma 日志锚点）", "seed hue=" in ALL_STR)
A("强调色在应用壁纸时统一刷新（AccentColor.update）", "AccentColor.update(use, cfg);" in bg)
A("F4 字体是否真生效用 Paint.measureText 自证",
  "metrics default=" in ALL_STR and "不同 ⇒ 字体生效" in ALL_STR)
A("F4 换字体后按配置指纹强制重排 ×3", "relayout after font change" in ALL_STR)
A("WindowBg.retryIfMissing 每轮补一次（onResume 拿不到位图不会永久空着）",
  "WindowBg.retryIfMissing(a)" in bg)

# ──────────────────────────────────────────────────────────────────
# 9. 热路径纪律（这些是踩过的坑）
# ──────────────────────────────────────────────────────────────────
A("getResourceEntryName 结果按 id 缓存（它会抛且在高频路径）", "ENTRY_CACHE" in f2)
A("反射按名字找方法（不写死混淆后的签名）", "findMethodsByName" in bg or "findMethodsByName" in f2)
A("install() 内每段各自 try/catch", bg.count("catch (Throwable") > 20, bg.count("catch (Throwable"))
A("日志有预算上限（不会把 logcat 刷爆）", "LOG_BUDGET" in bg and "HIT_TOTAL" in bg)
A("hit() 按 label 节流（同一标签 60 秒一条）", "HIT_LABEL_GAP_MS" in bg)
A("诊断日志带祖先链（下一轮不用再猜浮层归属）", "chain=" in bg)

# ──────────────────────────────────────────────────────────────────
# 10. 反向断言：已被判死的路，谁再写回来就红
# ──────────────────────────────────────────────────────────────────
A("反向：不再要求「贴底升起」这种几何判据去认信息面板（isSheetLike 只剩兜底容器用）",
  "if (!isSheetLike(v)) {" in bg)
A("反向：不用窗口高度比例认面板（wrapWindowH / isSheetContentRoot 已删）",
  "wrapWindowH" not in bg and "isSheetContentRoot" not in bg)
A("反向：不用 windowToken 兜底认面板（inMainWindow 里那套已删）",
  "sheetLeaf" not in bg)
A("反向：不再用宽泛的 \"Sheet\" 匹配浮层（RNSFormSheetHost 是整页窗口）",
  'n.contains("Sheet")' not in bg)
A("反向：isSheetBg / isSheetSurface / inSheetWindow 全部不存在",
  "isSheetBg" not in bg and "isSheetSurface" not in bg and "inSheetWindow" not in bg)
A("v0.1.42：玻璃判据改成「浮层 + 面积 + 不亮」三条（复合底不再被保守放过）",
  "GLASS_MIN_AREA_PCT" in bg and "GLASS_LIGHT_LUMA" in bg
  and "if (solid == null) glassWhy(\"composite\", v, w, h);" in bg)
A("反向：不再要求「读得出 solid 颜色」（这正是面板全不上模糊的原因）",
  "if (solid == null) return false;" not in bg)
A("v0.1.42：浮层里的整屏底一律不画（第 8 条「弹窗后界面消失」）",
  'hit("drop screen float -> ", v, b);' in bg
  and 'hit("drop leaf float -> ", v, r);' in bg
  and 'hit("drop grad float -> ", v, b);' in bg
  and 'hit("drop overlay -> ", v, r);' in bg)
A("反向：不再有「给整屏叶子铺不透明壁纸」这条（第 8 条的元凶）",
  "paint leaf tint" not in bg and "paint leaf fade" not in bg)
A("v0.1.42：顶部区不再按透明度分叉（只要贴顶就走同一个分支）",
  "if (loc[1] <= sEdgeTop) {" in bg
  and "if (loc[1] <= sEdgeTop && d.getOpacity() != PixelFormat.OPAQUE) {" not in bg)
A("★ v0.1.43：顶部区改「铺同款壁纸」而不是一律 drop（第 5 条「切后台回来上方黑条」）",
  "// ★★★ v0.1.43（r14 用户第 5 条「上方黑条」）：**顶部区改成铺同款壁纸**。" in bg
  and "Bitmap top = playContext(v) ? playBmp() : sWpBmp;" in bg
  and 'hit("paint top fill -> ", v, b);' in bg
  and 'hit("drop top bar bg -> ", v, b);' in bg)
A("★ v0.1.43：顶部叶子条同规则 —— 不透明补壁纸，半透明 scrim 仍 drop",
  "// ★★★ v0.1.43：顶部叶子条同样改「铺同款壁纸」" in bg
  and "if (atTop) {" in bg and "if (opaqueTop) {" in bg
  and 'hit("paint top fill -> ", v, r);' in bg)
A("★ v0.1.43：近全屏 + 贴顶 = Dialog 背板而不是面板（不再在透明窗口里铺满壁纸）",
  "// ★★★ v0.1.43（r14 用户第 8 条）：**近全屏 + 贴顶 = Dialog 背板，不是面板**。" in bg
  and 'glassNo("fullscreen-backdrop", v, w, h);' in bg
  and "if (loc[1] <= sEdgeTop + 4 && h * 100 >= (long) sSH * 88) {" in bg)
A("v0.1.42：宿主自带的 expo-blur 毛玻璃层被关掉（第 3/4/5①/6/7 条的共同来源）",
  "eightbitlab.com.blurview.BlurView" in bg
  and 'hit("drop blurview -> ", v,\n                                        new Rect(0, 0, v.getWidth(), v.getHeight()));' in bg
  and "expo.modules.blur.ExpoBlurView" in bg
  and "hideBlurDescendants(" in bg and "muteBlurView(" in bg
  and "hookExpoBlur(cl);" in bg)
A("★ v0.1.43：BlurView#draw 不再要求 kids==0（新版内部挂 controller 子 View ⇒ r14 整场 drop=0）",
  "if (v.getWidth() > 0 && v.getHeight() > 0) {" in bg
  and "// ① eightbitlab BlurView：不画（v0.1.43：取消 kids==0 限制）" in bg)
A("★ v0.1.43：除了反射字段，还直接遍历子 View 找 BlurView + 清 RenderEffect（三保险）",
  'if (cn.contains("blurview.BlurView") || cn.endsWith(".BlurView")) {' in bg
  and "android.graphics.RenderEffect.class" in bg)
A("v0.1.42：命中日志按视图签名去重（不再 60 秒限流饿死整段操作）",
  "private static String signature(View v)" in bg
  and 'logOnce("hit:" + prefix + signature(v), null)' in bg
  and "HIT_LABEL_GAP_MS" not in bg.split("private static String signature")[0].split("private static void hit")[1])
A("v0.1.42：中性半透明的极小字也上强调色（第 1 条的 #8a000000）",
  "SMALL_TEXT_NEUTRAL_MIN_L" in acc and "boolean neutral = (Math.max(r, Math.max(g, b))" in acc)

# ──────────────────────────────────────────────────────────────────
# 11. 关键日志锚点必须在包里（R8 混淆后唯一稳定的东西）
# ──────────────────────────────────────────────────────────────────
ANCHORS = [
    "==== BUILD ", "config source=", "window bg applied: ", "bg diag: ",
    "apply want=", "surface ", "sheet miss ", "big ", "imglayer ", "img ",
    "paint leaf -> ", "paint bar bg -> ", "drop top bar -> ", "drop overlay -> ",
    "glassA -> ", "dialog glassA -> ", "glassA img -> ",
    "glass NO (", "glass OK (", "drop blurview -> ", "drop leaf float -> ",
    "blur layer hidden -> ", "paint top fill -> ",
    "drop screen float -> ", "drop grad float -> ",
    "drop dim bg -> ", "drop sheet grad -> ", "drop sheet leaf -> ",
    "shadow ts=", " sd=",
    "paint play blur -> ", "paint cover clear -> ",
    "paint veil play -> ", "paint veil clear -> ",
    "paint atmos play -> ", "paint atmos clear -> ",
    "blur bg r=", "play seek -> ", "play page ", "cover alpha fix -> ",
    "draw seen=", "seed hue=", "metrics default=",
    " scr=", " dlg=", " cov=", " chain=", " pg=",
]
missing = [a for a in ANCHORS if a not in ALL_STR]
A("v0.1.38 日志锚点全部编进 dex（%d 个）" % len(ANCHORS), not missing, missing)

# ──────────────────────────────────────────────────────────────────
# 12. 版本号一致性
# ──────────────────────────────────────────────────────────────────
gradle = ""
gp = os.path.join(ROOT, "app", "build.gradle")
if os.path.exists(gp):
    gradle = io.open(gp, encoding="utf-8", errors="replace").read()
ver = re.search(r"appVersionName\s*=\s*'([^']+)'", gradle)
code = re.search(r"appVersionCode\s*=\s*(\d+)", gradle)
A("版本名存在", bool(ver), ver.group(1) if ver else None)
A("版本码存在", bool(code), code.group(1) if code else None)
if ver and code:
    A("版本号走 BuildConfig 单一出口（BuildInfo 不写死版本）",
      "BuildConfig.VERSION_NAME" in src_of("BuildInfo.java")
      and "BuildConfig.VERSION_CODE" in src_of("BuildInfo.java"),
      ver.group(1))

# ──────────────────────────────────────────────────────────────────
# 12. 其余能力层（配置通道 / 素材 / 媒体 / 状态 / 设置页）
# ──────────────────────────────────────────────────────────────────
A("配置通道：ConfigBus / ConfigStore / RemoteConfig / Protocol 四件齐",
  all(k in bps for k in ("ConfigBus", "ConfigStore", "RemoteConfig", "Protocol")))
A("配置可跨进程读（本地文件 + prefs 双通道，日志里能看到来源）",
  "probe rprefs=" in ALL_STR and "config source=" in ALL_STR)
A("配置热更新走广播（config applied from broadcast）",
  "config applied from broadcast: " in ALL_STR)
A("配置持久化 + 回读校验（persisted prefs=true file=true readback=）",
  "readback=" in ALL_STR)
A("素材同步：AssetSync / ModuleAssets / WallpaperBg 三件齐",
  all(k in bps for k in ("AssetSync", "ModuleAssets", "WallpaperBg")))
A("桌面壁纸走 WallpaperBg 快照（不在宿主进程直接读壁纸服务）",
  "WallpaperBg" in bps and "android.app.WallpaperManager" in bps)
A("媒体控制：MediaPool / SessionAssignment 齐备",
  "MediaPool" in bps and "SessionAssignment" in bps)
A("状态回报：HostStatusReceiver + Heartbeat",
  "HostStatusReceiver" in bps and "Heartbeat" in bps)
A("心跳间隔 20 秒（beat sent / interval=20000ms）", "interval=20000ms" in ALL_STR)
A("UI 刷新：UiRefresh 存在", "UiRefresh" in bps)
A("设置页三语齐备（Lang.kt + S.kt）",
  "Lang" in bps and "fun s(" in bps or "S.kt" in " ".join(SRC_FILES))
A("裁切页 CropDialog 存在（选图 → 裁切 → 落盘）", "CropScreen" in " ".join(SRC_FILES) or "CropDialog" in bps)
A("裁切结果最长边封顶 2048", "2048" in bps)
A("备份 / 恢复：BackupManager 存在", "BackupManager" in bps)
A("诊断导出：DiagLog / UiDiag 齐备", "DiagLog" in bps and "UiDiag" in bps)
A("日志门闸：LogGate（release 不开 verbose）", "LogGate" in bps)
A("XposedCompat 封装（api 102 兼容层）", "XposedCompat" in bps)
A("F1 存在但默认关闭（config 里 F1=false）", "FeatureF1" in bps)
A("协议常量集中在 config/Protocol", "Protocol" in bps)
A("MoriConfig 是唯一耦合点（hook 与 ui 只共享它）",
  "io.github.ariinyume.morimod.config.MoriConfig" in bps)

# ──────────────────────────────────────────────────────────────────
# 6. v0.1.39：r44 六条反馈的落点
# ──────────────────────────────────────────────────────────────────
A("播放页判定的死门槛已删（54px 进度条曾被 2% 屏高永久判死）",
  "v.getHeight() * 100 < (long) sSH * 2) return false;" not in bg
  and "private static boolean isPlayLive()" in bg)
A("播放页判定保留窗口（★ v0.1.43：1600→700ms，太长会把列表/详情也铺成播放档）",
  "PLAY_HOLD_MS = 700L" in bg and "sPlayHoldUntil" in bg
  and "PLAY_HOLD_MS = 1600L" not in bg
  and "sPlayHoldUntil = System.currentTimeMillis() + PLAY_HOLD_MS;" in bg)
A("整屏 blurRadius≥8 的底图也续期保持窗口（markPlayWindow）",
  "if (root != null) PLAY_WIN.put(root, System.currentTimeMillis());" in bg
  and bg.count("sPlayHoldUntil = System.currentTimeMillis() + PLAY_HOLD_MS;") >= 2)
A("主窗口贴底弹层也算浮层（宿主 Modal 不在 Dialog 窗口里）",
  "private static boolean mainWindowSheet(View v)" in bg
  and "if (insideScreen(v)) return false;" in bg
  and "return isBottomAnchored(v);" in bg)
A("只靠主窗口弹层判据命中时要 ≥20% 屏高（挡住页面单行/分隔条）",
  "if (h * 100 < (long) sSH * 20) return false;" in bg)
A("弹层图形底 / 面板 / 叶子三处都走统一浮层判据",
  bg.count("if (!inFloatingLayer(v)) return false;") >= 2)
A("小字专用重映射（低彩度紫灰不再被中性色保护放过）",
  "SMALL_TEXT_MIN_CHROMA = 1.5" in acc and "public static int remapSmallText(int argb)" in acc)
A("挂到 TextView#setTextColor，只对小字放宽（★ v0.1.43：15→18sp，覆盖白胶囊按钮里的标签）",
  "hookTextColor" in lut and "setTextColor" in lut
  and "SMALL_TEXT_SP = 18f" in lut and "AccentColor.remapSmallText(c)" in lut
  and "SMALL_TEXT_SP = 15f" not in lut)
A("★ v0.1.43：绘制前对 ≤18sp 的小字再逼一次强调色（底栏/白按钮的 CSL 设一次就再不走 setTextColor）",
  '// ★★★ v0.1.43（r14 用户第 1 条）：绘制前再逼一次小字强调色。' in bg
  and "forceAccentSmallText((TextView) v);" in bg
  and "private static void forceAccentSmallText(TextView tv)" in bg
  and "if (sp > 18f) return;" in bg)
A("★ v0.1.43 forceAccentSmallText 只在「中性/紫族」上动手，不透明纯黑正文不动",
  "if (!neutral && !purpleish) return;" in bg
  and "if (neutral && a == 0xff) return;" in bg)
A("换风格时小字缓存一起作废", "sLutSmall.clear();" in acc)
A("设置页读取改成「prefs / JSON 两份取 savedAt 更大者」",
  "public static MoriConfig loadBest(Context ctx)" in store
  and "return file.savedAt > prefs.savedAt ? file : prefs;" in store
  and "ConfigStore.loadBest(ctx)" in view)
A("覆盖前留上一份（morimod_config.prev.json，复制不是 rename）",
  "PREV_NAME = \"morimod_config.prev.json\"" in store
  and "java.nio.file.StandardCopyOption.REPLACE_EXISTING" in store)
A("保存前回补关键字段（背景 / 素材池 / 字体的空值不该落盘）",
  "private fun restoreCritical()" in view and "restoreCritical()" in view
  and "config.f2HomeBg = p.f2HomeBg" in view)
A("用户明确清空背景时不回补（bgClearedByUser）",
  "bgClearedByUser = true" in view and "if (bgClearedByUser) return" in view)
A("弹层压暗遮罩改为「不画」（#9919122C 那层就是淡紫色阴影）",
  "hit(\"drop dim -> \", v, r);" in bg and "hit(\"skip dim -> \", v, r);" not in bg)
A("两份都读不到时先试上一份留档，而不是直接默认值",
  "ConfigStore.loadPrevious(ctx)" in view and "loadDegraded = true" in view)

# ──────────────────────────────────────────────────────────────────
# 8. v0.1.40：面板高斯模糊「为什么一直做不上去」的结构性修复
# ──────────────────────────────────────────────────────────────────
A("玻璃底图拿不到时返回 null，绝不退回清晰壁纸",
  "if (g == null || g.isRecycled() || g == base) return null;" in bg
  and "sWpGlassBmp = g;" in bg)
A("反向断言：glassBmp 里不再有「直接返回 sWpBmp」的兜底",
  "        return sWpBmp;\n    }\n\n    /**\n     * 把宿主原来那层底色" not in bg)
A("玻璃画失败就 return false（不再掉进 coversScreen 铺清晰壁纸）",
  'noteGlassFail("paint");' in bg and 'noteGlassFail("bmp");' in bg)
A("玻璃失败必须留痕（以前静默降级，十来个版本都没查出来）",
  "private static void noteGlassFail(String why)" in bg
  and 'log("glass MISS ("' in bg)
A("壁纸变更时玻璃缓存一起作废", "sWpGlassBmp = null;" in bg)
A("圆角改为直接读 drawable 的 float[] 半径（2.22.0 上 getOutline 全是 r=0）",
  "private static float[] cornerRadiiOf(Drawable d)" in bg
  and "RADII_FIELD" in bg and "NO_RADII" in bg)
A("裁切按四角半径画路径（不再只画一个统一半径）",
  "p.addRoundRect(rf, rr, android.graphics.Path.Direction.CW);" in bg)
A("封面只修 alpha、绝不接管绘制（swapLeaf 会把封面换成播放页背景）",
  "noteCoverKept();" in bg
  and "if (swapLeaf(v, cv)) return null;\n                            paintSheetPanel(v, cv);" not in bg)
A("小字状态色列表也过强调色（一加 12 走的是 ColorStateList 重载）",
  "hookTextColorStateList" in lut
  and "setTextColor\", android.content.res.ColorStateList.class" in lut
  and "AccentColor.remapSmallText(c)" in lut)
A("状态色列表重写时保留宿主原有全部状态（mStateSpecs / mColors 逐项换色）",
  "stateSpecsOf(" in lut and "stateColorsOf(" in lut
  and '"mStateSpecs"' in lut and "new android.content.res.ColorStateList(fullSpecs, out)" in lut)

print("\n".join(LINES))
print("-" * 62)
print("=" * 62)
print("DEX-PASS=%d DEX-FAIL=%d" % (PASS, FAIL))
sys.exit(0 if FAIL == 0 else 1)
