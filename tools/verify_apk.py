#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MoriMod 出包核验（离线、零依赖）。

判据全部打在**最终 APK** 上：
  A. META-INF/xposed 三件套内容正确、行尾为 LF、无注释行（铁律 10）
  B. 版本号三处进包一致（versionName / versionCode / 文件名）
  C. 关键类真的编进包（dex 字符串层：类描述符）
  D. 关键常量真的编进包（action 前缀、资源白名单、图标黑名单）
  E. 禁止项：不应出现 io.github.ariinyume.dlsitesoundfloat 残留

用法: python tools/verify_apk.py <apk路径>
退出码 0 = 全绿。
"""
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

FAIL = []
PASS = []


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


def ok(label, cond, detail=""):
    (PASS if cond else FAIL).append(label + ((" -> " + detail) if detail else ""))


def read_zip_text(zf, name):
    raw = zf.read(name)
    return raw


def find_build_tool(tool):
    """
    在 $ANDROID_HOME/build-tools/* 里定位一个 build-tool（aapt2 / apksigner / dexdump）。

    ⚠️ 必须**按版本号排序取最高**，不能写死版本：
    本项目 buildToolsVersion 从 34.0.0 升到 36.0.0 后，写死的老路径会「找不到 →
    静默跳过判据」，让验证看起来通过其实没跑（踩过一次）。
    """
    roots = []
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(env, "")
        if v:
            roots.append(v)
    # 兜底：本机 local.properties 里的 MORIMOD_ANDROID_SDK（不进仓库）
    _sdk = local_props().get("MORIMOD_ANDROID_SDK", "")
    if _sdk:
        roots.append(_sdk)

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


def find_aapt2():
    return find_build_tool("aapt2")


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else None
    if not apk or not os.path.isfile(apk):
        print("APK not found:", apk)
        return 2

    zf = zipfile.ZipFile(apk)
    names = zf.namelist()

    # ---- A. META-INF/xposed 三件套 ----
    for f, expect in [
        ("META-INF/xposed/java_init.list", "io.github.ariinyume.morimod.MoriModModule"),
        ("META-INF/xposed/scope.list", "jp.co.eisys.dlsitesound"),
        ("META-INF/xposed/module.prop", "targetApiVersion=102"),
    ]:
        ok("存在 " + f, f in names)
        if f not in names:
            continue
        raw = read_zip_text(zf, f)
        text = raw.decode("utf-8", "replace")
        ok(f + " 无注释行", not any(l.lstrip().startswith("#") for l in text.splitlines()))
        ok(f + " 为 LF 行尾", b"\r\n" not in raw)
        ok(f + " 含期望内容", expect in text, repr(text.strip()[:80]))

    # scope.list 只允许宿主一个包
    if "META-INF/xposed/scope.list" in names:
        sc = read_zip_text(zf, "META-INF/xposed/scope.list").decode("utf-8", "replace").split()
        ok("scope.list 仅宿主一个包", sc == ["jp.co.eisys.dlsitesound"], str(sc))

    # ---- B. 版本号 ----
    # 期望值从 app/build.gradle 实读，而不是写死在脚本里 ——
    # 版本号每次发版都会递增，写死必然在某次发版后变成假 FAIL（踩过一次）。
    import subprocess
    want_code = want_name = want_apkname = None
    gradle = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "build.gradle")
    if os.path.isfile(gradle):
        g = open(gradle, encoding="utf-8", errors="replace").read()
        m = re.search(r"def\s+appVersionCode\s*=\s*(\d+)", g)
        want_code = m.group(1) if m else None
        m = re.search(r"def\s+appVersionName\s*=\s*'([^']+)'", g)
        want_name = m.group(1) if m else None
        m = re.search(r"def\s+appVersionTag\s*=\s*'([^']+)'", g)
        want_tag = m.group(1) if m else None
        if want_tag and want_code:
            # 产物名规则：DLsiteMoriMod-<tag>-code<code>-<buildType>.apk
            want_apkname = re.compile(
                r"^DLsiteMoriMod-%s-code%s-(debug|release)\.apk$" % (re.escape(want_tag), want_code)
            )
    ok("能从 build.gradle 读出版本号", want_code and want_name and want_apkname is not None,
       "code=%s name=%s" % (want_code, want_name))

    fn_hint = os.path.basename(apk)

    # aapt2：按 build-tools 目录**自动选最高版本**（本项目已从 34.0.0 升到 36.0.0，
    # 写死 34.0.0 会导致判据被静默跳过 —— 那才是真正的风险）。
    aapt2 = find_aapt2()
    if aapt2:
        out = subprocess.run([aapt2, "dump", "badging", apk],
                             capture_output=True, text=True, encoding="utf-8",
                             errors="replace").stdout
        m = re.search(r"versionCode='(\d+)'", out)
        ok("versionCode == build.gradle", bool(m) and m.group(1) == want_code,
           m.group(1) if m else "?")
        m2 = re.search(r"versionName='([^']+)'", out)
        ok("versionName == build.gradle", bool(m2) and m2.group(1) == want_name,
           m2.group(1) if m2 else "?")
        m3 = re.search(r"package: name='([^']+)'", out)
        ok("applicationId == io.github.ariinyume.morimod",
           bool(m3) and m3.group(1) == "io.github.ariinyume.morimod", m3.group(1) if m3 else "?")
        # release 包必须真的被签名过。
        # ⚠️ 用 apksigner 判，而不是 aapt2 badging —— badging 根本不输出签名信息，
        #    之前那版判据写的是 "signatures:" in out，永远为假，是条错判据。
        # v1 在 minSdk 24 下不签（AGP 会跳过），所以只要求 v2 或 v3 成立。
        if "-release." in fn_hint:
            signer = find_build_tool("apksigner")
            if signer:
                import subprocess as _sp
                vout = _sp.run([signer, "verify", "--verbose", apk],
                               capture_output=True, text=True, encoding="utf-8",
                               errors="replace")
                blob2 = (vout.stdout or "") + (vout.stderr or "")
                ok("release 包 apksigner 校验通过",
                   "Verifies" in blob2 and ("v2 scheme" in blob2 or "v3 scheme" in blob2),
                   "; ".join([ln.strip() for ln in blob2.splitlines()
                              if "scheme" in ln or ln.strip() == "Verifies"][:4]))
            else:
                ok("release 包 apksigner 校验通过", False, "未找到 apksigner")
    else:
        ok("aapt2 可用（未跳过 badging 判据）", False, "未找到 aapt2")

    ok("文件名符合产物命名规则",
       bool(want_apkname and want_apkname.match(fn_hint)), fn_hint)

    # ---- C/D. dex 字符串层 ----
    dex_names = [n for n in names if n.endswith(".dex")]
    ok("含 dex", len(dex_names) > 0, str(len(dex_names)))
    blob = b""
    for n in dex_names:
        blob += zf.read(n)

    def has(s):
        return s.encode("utf-8") in blob

    def has_class(fqcn):
        """类是否编进包：dex 里类型以描述符 Lpkg/Name; 编码（点号→斜杠）。"""
        desc = "L" + fqcn.replace(".", "/") + ";"
        return has(desc)

    ok("入口类编进包", has_class("io.github.ariinyume.morimod.MoriModModule"))
    ok("Protocol 类编进包", has_class("io.github.ariinyume.morimod.config.Protocol"))
    ok("SettingsActivity 编进包", has_class("io.github.ariinyume.morimod.ui.SettingsActivity"))
    ok("FeatureF1 编进包", has_class("io.github.ariinyume.morimod.hook.FeatureF1"))
    ok("FeatureF2 编进包", has_class("io.github.ariinyume.morimod.hook.FeatureF2"))
    ok("FeatureF4 编进包", has_class("io.github.ariinyume.morimod.hook.FeatureF4"))
    ok("ConfigBus 编进包", has_class("io.github.ariinyume.morimod.config.ConfigBus"))
    ok("RemoteConfig 编进包", has_class("io.github.ariinyume.morimod.config.RemoteConfig"))
    ok("ModuleAssets 编进包", has_class("io.github.ariinyume.morimod.hook.ModuleAssets"))
    ok("AppEnv 编进包", has_class("io.github.ariinyume.morimod.util.AppEnv"))
    # v0.0.9：跨进程配置 / 素材两条新链路的核心类。二者都由 manifest 引用，
    # R8 必须保留原名 —— 这里按 JVM 描述符查，查不到就说明组件被裁了（装上去会静默无效）。
    ok("ConfigQueryReceiver 编进包（清单接收器）",
       has_class("io.github.ariinyume.morimod.config.ConfigQueryReceiver"))
    # v0.1.2：宿主运行状态检测改成「心跳 + 45s 宽限窗口」。
    # 这两个类一旦被裁：状态卡回到「1.2 秒没 pong 就判未运行」，也就是用户报的那个 bug。
    ok("HostStatusReceiver 编进包（清单接收器，落盘心跳时间）",
       has_class("io.github.ariinyume.morimod.status.HostStatusReceiver"))
    ok("Heartbeat 编进包（宿主侧 20s 心跳）",
       has_class("io.github.ariinyume.morimod.hook.Heartbeat"))
    # v0.1.1：素材真正的跨 uid 通道 = 显式广播（分片）。这两个类由清单/主链路引用，
    # 被裁掉 = 素材永远读不到 = 「背景/字体完全不生效」原样复现。
    ok("AssetQueryReceiver 编进包（模块侧素材应答器）",
       has_class("io.github.ariinyume.morimod.assets.AssetQueryReceiver"))
    ok("AssetSync 编进包（宿主侧预取 + 本地缓存）",
       has_class("io.github.ariinyume.morimod.hook.AssetSync"))

    ok("action 前缀编进包", has("io.github.ariinyume.morimod.action."))
    ok("宿主包名编进包", has("jp.co.eisys.dlsitesound"))
    ok("宿主 Metadata 类名编进包", has("expo.modules.audio.Metadata"))
    ok("背景资源白名单编进包", has("main_visual"))
    # v0.0.9 常量层：配置主动索取 action + 宿主本地副本文件名 + 系统字体目录
    ok("配置主动索取 action 编进包", has("io.github.ariinyume.morimod.action.CONFIG_REQ"))
    # v0.1.1：素材通道的两个 action + 宿主侧缓存目录名。任一条被折叠/改写，
    # 宿主就永远拿不到用户素材（表现与「功能不生效」一致，且不报错）。
    ok("素材索取 action 编进包", has("io.github.ariinyume.morimod.action.ASSET_REQ"))
    ok("素材分片 action 编进包", has("io.github.ariinyume.morimod.action.ASSET_CHUNK"))
    ok("宿主素材缓存目录名编进包", has("morimod_assets"))
    # v0.1.2：心跳 action + 落盘档位名。任一被折叠/改写 ⇒ 设置页拿不到时间戳，
    # 状态卡又会变回「宿主开着却写未运行」。
    ok("心跳 action 编进包", has("io.github.ariinyume.morimod.action.HOST_ALIVE"))
    ok("心跳落盘档位名编进包", has("morimod_status"))
    ok("模块包名常量编进包", has("io.github.ariinyume.morimod"))
    ok("宿主配置副本文件名编进包", has("morimod_config.json"))
    ok("系统字体目录编进包（真实字体列表来源）", has("/system/fonts"))
    ok("图标黑名单 MaterialSymbols 编进包", has("MaterialSymbols"))
    ok("图标黑名单 FontAwesome 编进包", has("FontAwesome"))

    # ---- D2b. v0.1.2 修复项的运行期常量 ----
    # ① 背景替换的真正入口：宿主背景是 STORED(未压缩) webp，Fresco 走 openRawResourceFd。
    #    这条重载名一旦没编进包，就等于「背景替换永远不生效」（上一版就是这个表现）。
    ok("背景 fd 读取入口 openRawResourceFd 编进包（STORED 资源实际走这条）",
       has("openRawResourceFd"))
    ok("背景替换运行期日志锚点编进包", has("window bg applied: "))
    # ② 「我的字体」清单字段：少了它，字体页那张卡永远是空的。
    ok("我的字体清单字段 f4FontLib 编进包", has("f4FontLib"))
    # ③ v0.1.10：旧的 f2Transparent（把宿主背景清成透明 ⇒ 整屏发白）已废弃，
    #    新的「桌面壁纸」来源必须编进包，否则那个按钮点了不会有任何反应。
    ok("桌面壁纸来源字段 f2DesktopWall 编进包", has("f2DesktopWall"))
    ok("旧的全透明字段仅为兼容保留（仍然可读，便于迁移）", has("f2Transparent"))

    # ---- D2. v0.1.0 随包内置字体（SIL OFL 1.1 两款）----
    # ① 文件真的进包且大小对得住：截断 / 空文件不会让构建失败，只会让运行期
    #    「选了内置字体毫无反应」—— 必须按字节数量级兜住。
    for nm, min_bytes in [
        ("assets/fonts/source_han_sans_sc.otf", 15_000_000),
        ("assets/fonts/smiley_sans.otf", 1_500_000),
        ("assets/fonts/OFL-1.1.txt", 3_000),
        ("assets/fonts/NOTICE.txt", 1_000),
    ]:
        ok("内置资源存在 " + nm, nm in names)
        if nm in names:
            sz = zf.getinfo(nm).file_size
            ok(nm + " 大小合理（≥%d B）" % min_bytes, sz >= min_bytes, "bytes=%d" % sz)
    # ② OFL 第 1 条要求「随字体一起分发许可证与版权声明」⇒ 内容级判据，
    #    文件名对了但内容是占位符同样算不合规。
    if "assets/fonts/OFL-1.1.txt" in names:
        t = zf.read("assets/fonts/OFL-1.1.txt").decode("utf-8", "replace")
        ok("OFL-1.1.txt 是许可证全文",
           "SIL OPEN FONT LICENSE" in t.upper() and "Version 1.1" in t,
           "%d 字符" % len(t))
    if "assets/fonts/NOTICE.txt" in names:
        t = zf.read("assets/fonts/NOTICE.txt").decode("utf-8", "replace")
        ok("NOTICE.txt 含两款字体的署名", "Source Han" in t and "Smiley" in t,
           "%d 字符" % len(t))
    # ③ 运行期路由常量：配置里存 asset:fonts/xxx，读取时**直接把本模块 APK 当 zip 解**
    #    （v0.1.2 起彻底不再依赖任何跨进程通道 —— bindService 被包可见性否掉，已删除）。
    ok("内置素材前缀 asset: 编进包", has("asset:"))
    ok("内置字体从模块 APK 解包（assets/ 前缀 + 缺条目诊断都在）",
       has("assets/") and has("APK 里没有该条目"))
    ok("内置字体①文件名编进包", has("fonts/source_han_sans_sc.otf"))
    ok("内置字体②文件名编进包", has("fonts/smiley_sans.otf"))

    # ---- E. 禁止项 ----
    ok("无参考项目包名残留", not has("io.github.ariinyume.dlsitesoundfloat"))
    ok("无 DLsiteFloat 横幅残留", not has("DLsiteFloat"))

    zf.close()

    print("=" * 62)
    for p in PASS:
        print("  PASS  " + p)
    print("-" * 62)
    for f in FAIL:
        print("  FAIL  " + f)
    print("=" * 62)
    print("PASS=%d FAIL=%d" % (len(PASS), len(FAIL)))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
