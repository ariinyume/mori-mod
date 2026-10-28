#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MoriMod 出包核验 · 第三层：资源层（aapt2）。

为什么需要这一层：前两层（APK 结构 / dex 字节码）都管不到
`resources.arsc` 里的主题 parent、夜间限定符、adaptive-icon 的 drawable 引用、
inset 百分比 —— 这些**编译通过 + dex 里毫无痕迹**，错了也发现不了。

⚠️ 本层判据必须适配 **release（R8 + shrinkResources）** 的实测形态：
  · res 文件名被混淆缩短（`res/mipmap-anydpi-v26/ic_launcher.xml` → `res/BW.xml`）
    ⇒ adaptive-icon 的路径要**先从 resources 表反查**，不能写死；
  · 未被引用的资源被 shrinkResources 裁掉
    ⇒ 断言「实际编进包的颜色」，不是「values/*.xml 里写了的颜色」；
  · 主题 parent 打在**框架主题资源 id**（0x01030241 = Theme.Material.Light.NoActionBar）
    而不是 `style/Theme.Material3.*` 名字 —— 设置页已是纯 Compose。

覆盖 skill §3.7 的两坑：
  · `dump xmltree` 只给资源 id（@0x7f…）⇒ 先 `dump resources` 建 id→名 映射再回填；
  · 属性键带完整命名空间 URL ⇒ 归一化成 `android:` 再取值。
  · `A:` 属性行在**下一行** ⇒ 正则必须 re.S。
  · `android:inset="26%"` 编译后是 fraction 0.26（不是 26.0）。

用法: python tools/verify_res.py <apk路径>
退出码 0 = 全绿。
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PASS, FAIL = [], []

# release（R8+shrinkResources）实测：主题 parent 落在框架主题 id 上
FRAMEWORK_THEME_LIGHT_NOACTIONBAR = "0x01030241"
FRAMEWORK_THEME_NOACTIONBAR = "0x0103022e"


def ok(label, cond, detail=""):
    (PASS if cond else FAIL).append(label + ((" -> " + detail) if detail else ""))


def local_props():
    """读仓库根的 local.properties（本机私密配置，不进仓库，见 .gitignore）。"""
    d = {}
    try:
        import io
        for line in io.open(os.path.join(ROOT, "local.properties"),
                            encoding="utf-8", errors="replace"):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                d[k.strip()] = v.strip()
    except OSError:
        pass
    return d


def _attr_ref(value):
    """把 `@0x7f080000` 这种属性值转成 resolve() 能吃的形式。"""
    return value.strip() if value.strip().startswith("@") else value.strip()


def parse_xmltree(text):
    """
    把 aapt2 `dump xmltree` 的输出解析成 `[(元素路径, [(属性名, 原始值)])]`。

    ⚠️ 为什么不用一长串正则：xmltree 的属性行在元素行的**下一行**，
    且命名空间 URL 很长，正则既难读又容易写错（本项目在 heredoc 里
    还被 shell 把 `\\s` 吞成 `//s` 过一次）。按缩进建栈最稳。
    """
    stack = []          # [(缩进, 元素名)]
    out = []
    for line in text.splitlines():
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip())
        s = line.strip()
        if s.startswith("E: "):
            name = s[3:].split(" (line=")[0].strip()
            while stack and stack[-1][0] >= indent:
                stack.pop()
            stack.append((indent, name))
            out.append((" / ".join(n for _, n in stack), []))
        elif s.startswith("A: ") and out:
            body = s[3:].replace(
                "http://schemas.android.com/apk/res/android:", "android:")
            if "=" not in body:
                continue
            key, val = body.split("=", 1)
            key = re.sub(r"\(0x[0-9a-f]+\)$", "", key.strip())
            val = val.strip()
            # aapt2 会写成 `"值" (Raw: "值")` ⇒ 只取第一个引号里的内容
            if val.startswith('"'):
                end = val.find('"', 1)
                val = val[1:end] if end > 0 else val
            out[-1][1].append((key, val))
    return out


def find_build_tool(tool):
    """在 $ANDROID_HOME/build-tools/* 里定位 build-tool，**按版本号取最高**。

    ⚠️ 不能写死版本号：buildToolsVersion 升到 36.0.0 后写死老路径会「找不到 →
    静默跳过判据」，让验证看起来通过其实没跑（本项目踩过两次）。
    """
    roots = [os.environ.get(e, "") for e in ("ANDROID_HOME", "ANDROID_SDK_ROOT")]
    # 兜底：本机 local.properties 里的 MORIMOD_ANDROID_SDK（不进仓库）
    roots.append(local_props().get("MORIMOD_ANDROID_SDK", ""))

    def ver_key(name):
        return tuple(int(x) if x.isdigit() else 0 for x in re.findall(r"\d+", name))

    cands = []
    for r in roots:
        bt = os.path.join(r, "build-tools")
        if not os.path.isdir(bt):
            continue
        for d in os.listdir(bt):
            for ext in (".exe", ".bat", ""):
                p = os.path.join(bt, d, tool + ext)
                if os.path.isfile(p):
                    cands.append((ver_key(d), p))
    if cands:
        cands.sort(reverse=True)
        return cands[0][1]
    return None


def gradle_prop(prop):
    """从 app/build.gradle 实读期望值，避免脚本里写死版本号后再忘记改。"""
    p = os.path.join(ROOT, "app", "build.gradle")
    if not os.path.isfile(p):
        return None
    m = re.search(r"def\s+%s\s*=\s*['\"]?([\w.\-]+)" % prop,
                  open(p, encoding="utf-8").read())
    return m.group(1) if m else None


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else None
    if not apk or not os.path.isfile(apk):
        print("[FATAL] APK 不存在:", apk)
        return 2
    aapt2 = find_build_tool("aapt2")
    if not aapt2:
        print("[FATAL] 找不到 aapt2（设 ANDROID_HOME）")
        return 2

    def run(*args):
        r = subprocess.run([aapt2] + list(args), capture_output=True,
                           encoding="utf-8", errors="replace")
        return r.stdout or ""

    badging = run("dump", "badging", apk)
    res = run("dump", "resources", apk)

    # ---------- 0. id -> 资源名映射（xmltree 只给 id）----------
    idmap = {}
    for m in re.finditer(r"^\s+resource (0x[0-9a-f]+) ([\w.]+/[\w.]+)\s*$", res, re.M):
        idmap[m.group(1)] = m.group(2)

    def resolve(tok):
        m = re.match(r"@(0x[0-9a-f]+)$", tok or "")
        return idmap.get(m.group(1), tok) if m else tok

    # ---------- A. badging ----------
    def g(pat, default=""):
        m = re.search(pat, badging)
        return m.group(1) if m else default

    exp_code = gradle_prop("appVersionCode")
    exp_name = gradle_prop("appVersionName")

    ok("package = io.github.ariinyume.morimod",
       g(r"package: name='([^']+)'") == "io.github.ariinyume.morimod",
       g(r"package: name='([^']+)'"))
    ok("versionCode = %s（与 build.gradle 一致）" % exp_code,
       g(r"versionCode='(\d+)'") == exp_code, g(r"versionCode='(\d+)'"))
    ok("versionName = %s（与 build.gradle 一致）" % exp_name,
       g(r"versionName='([^']+)'") == exp_name, g(r"versionName='([^']+)'"))
    ok("compileSdk = 36（Compose 1.8.2 / material3 1.5.0-alpha14 基线）",
       g(r"compileSdkVersion='(\d+)'") == "36", g(r"compileSdkVersion='(\d+)'"))
    ok("minSdk = 24（PRD D-20 不设下限）",
       g(r"minSdkVersion:'(\d+)'") == "24", g(r"minSdkVersion:'(\d+)'"))
    ok("targetSdk = 36", g(r"targetSdkVersion:'(\d+)'") == "36",
       g(r"targetSdkVersion:'(\d+)'"))
    ok("application-label = MoriMod",
       "application: label='MoriMod'" in badging, g(r"application: label='([^']+)'"))
    # ⚠️ v0.0.3 起 LAUNCHER 入口搬到了 `activity-alias` 上（「隐藏模块桌面图标」的
    #    作用对象）。实测 **aapt2 badging 根本不扫 activity-alias**，所以
    #    `launchable-activity:` 这一行会整行消失 —— 这不是缺陷，是 badging 的行为。
    #    改成直接核**打包后的 AndroidManifest.xml**，判据反而更强
    #    （能同时验证 targetActivity / enabled / intent-filter）。
    man = run("dump", "xmltree", apk, "--file", "AndroidManifest.xml")
    nodes = parse_xmltree(man)

    def find_node(tag, name_value):
        """按「元素名 + android:name 值」找节点，返回属性 dict。"""
        for path, attrs in nodes:
            if not path.endswith(tag):
                continue
            d = dict(attrs)
            if name_value in d.get("android:name", ""):
                return d
        return None

    acts = [p for p, _ in nodes if p.endswith("activity")]
    ok("manifest 里 SettingsActivity 仍是真实 activity",
       find_node("activity", "io.github.ariinyume.morimod.ui.SettingsActivity") is not None,
       str(sorted(set(acts))[:4]))

    # ★ LSPosed 模块管理器的「启动 / 打开设置」入口（v0.0.5）
    #   管理器按 `de.robv.android.xposed.category.MODULE_SETTINGS` 查模块的设置 Activity
    #   再用 setClassName 拉起 —— 与有没有桌面图标无关，
    #   所以「隐藏模块桌面图标」之后仍然进得来（否则用户会被锁在外面）。
    sett = find_node("activity", "io.github.ariinyume.morimod.ui.SettingsActivity")
    if sett:
        ok("SettingsActivity exported = true（管理器要能跨应用拉起）",
           sett.get("android:exported") in ("true", "0xffffffff"), str(sett.get("android:exported")))
    xposed_cat = [
        dict(a).get("android:name", "")
        for p, a in nodes
        if "activity" in p and p.endswith("category")
    ]
    ok("SettingsActivity 声明 MODULE_SETTINGS category（LSPosed「启动」入口）",
       "de.robv.android.xposed.category.MODULE_SETTINGS" in xposed_cat, str(xposed_cat))
    xposed_act = [
        dict(a).get("android:name", "")
        for p, a in nodes
        if "activity" in p and p.endswith("action")
    ]
    ok("SettingsActivity 声明 MODULE_SETTINGS action（按 action 查的实现也覆盖）",
       "de.robv.android.xposed.category.MODULE_SETTINGS" in xposed_act, str(xposed_act))

    alias = find_node("activity-alias", "io.github.ariinyume.morimod.ui.LauncherAlias")
    ok("manifest 含 activity-alias（桌面图标别名的载体）", alias is not None)
    if alias:
        ok("alias.targetActivity = ui.SettingsActivity",
           alias.get("android:targetActivity", "") ==
           "io.github.ariinyume.morimod.ui.SettingsActivity",
           alias.get("android:targetActivity", "MISS"))
        ok("alias enabled = true（否则装完没有桌面图标）",
           alias.get("android:enabled") == "true" or alias.get("android:enabled") == "0xffffffff",
           str(alias.get("android:enabled")))
        ok("alias 图标引 @mipmap/ic_launcher",
           resolve(_attr_ref(alias.get("android:icon", ""))) == "mipmap/ic_launcher",
           resolve(_attr_ref(alias.get("android:icon", ""))))
        ok("alias label 引 @string/app_name",
           resolve(_attr_ref(alias.get("android:label", ""))) == "string/app_name",
           resolve(_attr_ref(alias.get("android:label", ""))))

    # alias 下面必须挂着 MAIN + LAUNCHER 的 intent-filter，否则桌面根本不出现图标
    launch = False
    for path, attrs in nodes:
        if "activity-alias" in path and path.endswith("category"):
            d = dict(attrs)
            if "android.intent.category.LAUNCHER" in d.get("android:name", ""):
                launch = True
    ok("alias 的 intent-filter 含 LAUNCHER 类别", launch)
    ok("alias 的 intent-filter 含 MAIN action",
       any(dict(a).get("android:name", "") == "android.intent.action.MAIN"
           for p, a in nodes if "activity-alias" in p and p.endswith("action")))

    # ---------- v0.0.9：配置应答 receiver + 素材 provider ----------
    # 这两条是「功能完全不生效」的根修载体：
    #   · receiver 让宿主**在设置页没开的情况下**也能要到配置（清单注册 ⇒ 能拉起模块进程）；
    #   · provider 让宿主按 ContentResolver 读模块私有目录里的背景图/字体，不依赖框架 API。
    recv = find_node("receiver", "io.github.ariinyume.morimod.config.ConfigQueryReceiver")
    ok("manifest 含配置应答 receiver（ConfigQueryReceiver）", recv is not None)
    if recv:
        ok("receiver exported = true（宿主是另一个 uid，不导出就发不进来）",
           recv.get("android:exported") in ("true", "0xffffffff"),
           str(recv.get("android:exported")))
        ok("receiver 监听 CONFIG_REQ action",
           any(dict(a).get("android:name", "") ==
               "io.github.ariinyume.morimod.action.CONFIG_REQ"
               for p, a in nodes if "receiver" in p and p.endswith("action")))

    # ---------- v0.1.2：宿主心跳（清单接收器）----------
    # 清单注册 ⇒ 模块进程被杀也能被拉起落盘时间戳；不导出宿主就发不进来。
    hb = find_node("receiver", "io.github.ariinyume.morimod.status.HostStatusReceiver")
    ok("manifest 含心跳 receiver（HostStatusReceiver）", hb is not None)
    if hb:
        ok("心跳 receiver exported = true（宿主是另一个 uid）",
           hb.get("android:exported") in ("true", "0xffffffff"),
           str(hb.get("android:exported")))
        ok("心跳 receiver 监听 HOST_ALIVE action",
           any(dict(a).get("android:name", "") ==
               "io.github.ariinyume.morimod.action.HOST_ALIVE"
               for p, a in nodes if "receiver" in p and p.endswith("action")))
    ok("manifest 已无素材 service（绑定式通道已废弃）",
       find_node("service", "io.github.ariinyume.morimod.assets.AssetService") is None)

    # ---------- v0.1.1：素材走显式广播（分片回传） ----------
    # 清单注册 ⇒ 模块进程被杀也能被拉起应答；action 必须是协议里的那一条，
    # 少一个字符就变成「发了没人收」，且完全静默。
    ar = find_node("receiver", "io.github.ariinyume.morimod.assets.AssetQueryReceiver")
    ok("manifest 含素材应答 receiver（AssetQueryReceiver）", ar is not None)
    if ar:
        ok("素材 receiver exported = true（宿主是另一个 uid，不导出就发不进来）",
           ar.get("android:exported") in ("true", "0xffffffff"),
           str(ar.get("android:exported")))
        ok("素材 receiver 监听 ASSET_REQ action",
           any(dict(a).get("android:name", "") ==
               "io.github.ariinyume.morimod.action.ASSET_REQ"
               for p, a in nodes if "receiver" in p and p.endswith("action")))

    # ---------- B. 主题：Compose 窗口层 ----------
    m = re.search(r"^\s+resource 0x[0-9a-f]+ style/Theme\.MoriMod\s*\n"
                  r"\s+\(\) \(style\) size=\d+ parent=(\S+)", res, re.M)
    ok("style/Theme.MoriMod 存在", m is not None,
       m.group(1) if m else "MISS")
    if m:
        ok("日间主题 parent = 框架 Theme.Material.Light.NoActionBar（纯 Compose，不依赖 MD3 View 库）",
           m.group(1).lower() == FRAMEWORK_THEME_LIGHT_NOACTIONBAR, m.group(1))
    # ⚠️ 日间块与 (night) 块之间还夹着若干属性行（0x01010450=true 等），
    #    不能用 `[^\n]*\n\s+\(night\)` 直接相邻匹配 —— 实测踩过。
    mn = re.search(r"^\s+resource 0x[0-9a-f]+ style/Theme\.MoriMod\s*\n"
                   r"\s+\(\) \(style\)[^\n]*\n(?:\s+0x[0-9a-f]+=[^\n]*\n)+"
                   r"\s+\(night\) \(style\) size=\d+ parent=(\S+)", res, re.M)
    ok("夜间主题覆盖编进包（values-night）", mn is not None)
    if mn:
        ok("夜间主题 parent = 框架 Theme.Material.NoActionBar",
           mn.group(1).lower() == FRAMEWORK_THEME_NOACTIONBAR, mn.group(1))
    for item in ("android:statusBarColor", "android:navigationBarColor"):
        pass
    # 状态栏/导航栏透明 + windowDrawsSystemBarBackgrounds（enableEdgeToEdge 的前提）
    blk = re.search(r"^\s+resource 0x[0-9a-f]+ style/Theme\.MoriMod\s*\n((?:\s+.*\n)+?)(?=\s+resource|\Z)",
                    res, re.M)
    ids = set(re.findall(r"0x01010[0-9a-f]+=", blk.group(1) if blk else ""))
    ok("窗口层：4 个 android 窗口属性编进包（透明状态栏/导航栏 + lightStatusBar + drawsSystemBarBackgrounds）",
       len(ids) >= 4, str(sorted(ids)))

    # ---------- C. 颜色：只断言「实际编进包」的（shrinkResources 会裁掉未引用项）----------
    def color_value(name, qual=""):
        blk = re.search(r"^\s+resource 0x[0-9a-f]+ color/%s\s*\n((?:\s+\([^)]*\) #\w+\s*\n)+)" % name,
                        res, re.M)
        if not blk:
            return None
        for line in blk.group(1).splitlines():
            mm = re.match(r"\s+\((night)?\) (#\w+)", line)
            if mm and (mm.group(1) or "") == qual:
                return mm.group(2)
        return None

    ok("color/mori_ic_bg（日间，adaptive-icon 背景色）",
       color_value("mori_ic_bg") == "#ffa9d8f5", str(color_value("mori_ic_bg")))
    ok("color/mori_ic_bg（night 覆盖，夜间夜空色）",
       color_value("mori_ic_bg", "night") == "#ff1b2a4a",
       str(color_value("mori_ic_bg", "night")))

    # ---------- D. mipmap：密度 + night 限定符 + anydpi-v26 ----------
    quads = re.findall(r"\((night-)?(mdpi|hdpi|xhdpi|xxhdpi|xxxhdpi)\) \(file\) res/\S+\.png", res)
    day = sorted(set(q[1] for q in quads if not q[0]))
    night = sorted(set(q[1] for q in quads if q[0]))
    ok("图标 5 档日间密度", day == ["hdpi", "mdpi", "xhdpi", "xxhdpi", "xxxhdpi"], str(day))
    ok("图标 5 档 night 密度", night == ["hdpi", "mdpi", "xhdpi", "xxhdpi", "xxxhdpi"], str(night))

    # anydpi-v26 的 adaptive-icon：⚠️ release 下 res 文件名被混淆缩短，必须反查
    icon_xml = None
    im = re.search(r"^\s+resource 0x[0-9a-f]+ mipmap/ic_launcher\s*\n((?:\s+\([^\n]*\n)+)",
                   res, re.M)
    if im:
        a = re.search(r"\(anydpi-v26\) \(file\) (res/\S+\.xml)", im.group(1))
        icon_xml = a.group(1) if a else None
    ok("mipmap/ic_launcher 含 anydpi-v26 XML（自适应图标）", icon_xml is not None, str(icon_xml))
    ok("badging 的 application-icon 指向该 XML",
       bool(icon_xml) and ("application: label='MoriMod' icon='%s'" % icon_xml) in badging,
       icon_xml or "MISS")

    # ---------- E. strings ----------
    for name, val in [("app_name", "MoriMod"),
                      ("settings_label", "MoriMod 设置"),
                      ("app_description", None)]:
        mm = re.search(r'^\s+resource 0x[0-9a-f]+ string/%s\s*\n\s+\(\) "([^"]*)"' % name, res, re.M)
        got = mm.group(1) if mm else None
        if val is None:
            ok("string/%s 非空（LSPosed 模块简介）" % name, bool(got), got or "MISS")
        else:
            ok("string/%s = %s" % (name, val), got == val, got or "MISS")

    # ---------- F. adaptive-icon xmltree（id 回填 + 命名空间归一 + 跨行 A: 行）----------
    if icon_xml:
        tree = run("dump", "xmltree", apk, "--file", icon_xml)
        ok("adaptive-icon 根元素",
           re.search(r"^\s*E: adaptive-icon", tree, re.M) is not None)
        # 🔴 xmltree 的 A: 行在**下一行** ⇒ 必须 DOTALL 跨行取，别用行内 `.*`
        bg = re.search(r"E:\s*background.*?drawable\(0x[0-9a-f]+\)=@(0x[0-9a-f]+)", tree, re.S)
        ok("background 引 @color/mori_ic_bg",
           resolve("@" + bg.group(1)) == "color/mori_ic_bg" if bg else False,
           resolve("@" + bg.group(1)) if bg else "MISS")
        # 🔴 v0.0.4 回归闸门：foreground **必须**是独立资源，绝不能是 @mipmap/ic_launcher
        #    （自引用 ⇒ Android 8+ 展开时无限递归 ⇒ 桌面图标 / LSPosed 模块列表全拿不到图标）
        fg = re.search(r"E:\s*foreground.*?drawable\(0x[0-9a-f]+\)=@(0x[0-9a-f]+)", tree, re.S)
        fg_name = resolve("@" + fg.group(1)) if fg else "MISS"
        ok("foreground 引 @mipmap/ic_launcher_foreground（独立满幅资源）",
           fg_name == "mipmap/ic_launcher_foreground", fg_name)
        ok("foreground **未**自引用 @mipmap/ic_launcher（自引用 = 桌面图标失效）",
           fg_name != "mipmap/ic_launcher", fg_name)
        ok("foreground 已不再用 <inset> 包一层（改为整幅铺满）",
           re.search(r"E:\s*inset", tree) is None)

    # ---------- F1. 前景资源 5 档日间 + 5 档 night ----------
    fgres = re.search(r"^\s+resource 0x[0-9a-f]+ mipmap/ic_launcher_foreground\s*\n"
                      r"((?:\s+\([^\n]*\n)+)", res, re.M)
    ok("mipmap/ic_launcher_foreground 编进包", fgres is not None)
    if fgres:
        fq = re.findall(r"\((night-)?(mdpi|hdpi|xhdpi|xxhdpi|xxxhdpi)\) \(file\)", fgres.group(1))
        fd = sorted(set(q[1] for q in fq if not q[0]))
        fn = sorted(set(q[1] for q in fq if q[0]))
        ok("前景 5 档日间密度", fd == ["hdpi", "mdpi", "xhdpi", "xxhdpi", "xxxhdpi"], str(fd))
        ok("前景 5 档 night 密度", fn == ["hdpi", "mdpi", "xhdpi", "xxhdpi", "xxxhdpi"], str(fn))

    # ---------- F2. UI 内嵌 logo（mori-logo 那套，日 / 夜两套）----------
    # ⚠️ release 下 res 文件名被混淆缩短（mori_logo.png -> res/xx.png），
    #    所以只能按**资源名**断言，不能按路径。
    #    v0.0.4 起主页状态卡改用「小状态图标」（√/×/?），mori_logo 只留在「关于」页。
    logo = re.search(r"^\s+resource 0x[0-9a-f]+ drawable/mori_logo\s*\n((?:\s+\([^\n]*\n)+)",
                     res, re.M)
    ok("drawable/mori_logo 编进包（关于页大图用）", logo is not None)
    if logo:
        quals = set(re.findall(r"\((night-)?[\w-]*\) \(file\)", logo.group(1)))
        ok("mori_logo 有日间 + 夜间两套（values-night 限定符）",
           any("night" not in q for q in quals) and any("night" in q for q in quals),
           str(sorted(quals)))

    # ---------- F3. 包可见性 <queries>（Android 11+ 读宿主版本的唯一前提）----------
    # 不声明它，getPackageInfo("jp.co.eisys.dlsitesound") 一律抛 NotFoundException
    # ⇒ 状态卡 / 关于页永远显示「未安装」（v0.0.3 的真机 bug）。
    qn = [dict(a) for p, a in nodes if p.endswith("queries / package")]
    ok("manifest 声明 <queries> 宿主包（Android 11+ 包可见性）",
       any(d.get("android:name") == "jp.co.eisys.dlsitesound" for d in qn),
       str([d.get("android:name") for d in qn]))

    # ---------- G. §3.7b pathData 层适用性（显式记录，不静默跳过）----------
    vec = []
    for dirpath, _, files in os.walk(os.path.join(ROOT, "app/src/main/res")):
        for f in files:
            p = os.path.join(dirpath, f)
            try:
                with open(p, "r", encoding="utf-8") as fh:
                    if "<vector" in fh.read():
                        vec.append(os.path.relpath(p, ROOT))
            except (UnicodeDecodeError, OSError):
                pass
    ok("自有 res 无 <vector>（图标全用 adaptive-icon + PNG，pathData 层不适用）",
       not vec, str(vec))

    # ---------- H. 无 View 体系 MD3 依赖残留 ----------
    ok("资源表里无 style/Theme.Material3.*（MD3 View 库未被打进来）",
       "style/Theme.Material3." not in res)
    ok("资源表里无 res/values-night/themes.xml 之外的夜间主题重复定义",
       res.count("style/Theme.MoriMod") == 1,
       "count=%d" % res.count("style/Theme.MoriMod"))

    print("=" * 62)
    for p in PASS:
        print("  PASS  " + p)
    print("-" * 62)
    for f in FAIL:
        print("  FAIL  " + f)
    print("=" * 62)
    print("RES-PASS=%d RES-FAIL=%d" % (len(PASS), len(FAIL)))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
