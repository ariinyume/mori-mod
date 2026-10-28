package io.github.ariinyume.morimod.hook;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import io.github.ariinyume.morimod.util.DiagLog;
import io.github.ariinyume.morimod.util.XposedCompat;

/**
 * 读取用户素材（图片 / 字体）。
 *
 * <p>用户在设置页通过系统选择器挑文件后，设置页把文件**复制进模块自己的目录**
 * （{@code /data/data/<模块>/files/media|fonts/...}），配置里记的是**绝对路径**；
 * 宿主进程读同一份文件只能走框架的 {@code openRemoteFile(name)}，
 * 而它要的是**相对于模块 files 目录的名字**（{@code media/mxxx.jpg}）。
 *
 * <p>⚠️ v0.0.5 直接把绝对路径喂给 openRemoteFile ⇒ 一律打不开 ⇒ F2/F4 静默降级
 * 「保持宿主原样」。这就是真机上「选了图/字体毫无反应」的第二个根因。
 * 这里统一做路径归一化，并对未知形态的路径逐个候选尝试（向后兼容已保存的旧配置）。
 */
public final class ModuleAssets {

    private static final String TAG = "[MoriMod:Assets]";
    /** 单个素材读取上限（图片 24MB / 字体 32MB），防内存压力（E-10）。 */
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    /** 解码最长边上限（AC-2.6 降采样）。 */
    private static final int MAX_DECODE_DIM = 2048;

    private ModuleAssets() {
    }

    /**
     * 把配置里记的路径归一化成 {@code openRemoteFile} 认的名字。
     *
     * <p>模块把素材放在 files/media、files/fonts 两个子目录，绝对路径形如
     * {@code /data/user/0/<pkg>/files/media/mxxx.jpg} ⇒ 归一成 {@code media/mxxx.jpg}。
     */
    static String relativeName(String path) {
        if (path == null) return null;
        String p = path.trim();
        if (p.isEmpty()) return null;
        if (!p.startsWith("/")) return p;                 // 本来就是相对名
        // 只处理「模块自己的 files 目录下」的绝对路径；其它（/system、/data/...）原样返回，
        // 交给 readBytes 的「直接路径」分支处理。
        int i = p.indexOf("/files/");
        if (i >= 0 && i + 7 < p.length()) {
            String modulePkg = io.github.ariinyume.morimod.util.BuildInfo.applicationId();
            if (modulePkg != null && p.contains("/" + modulePkg + "/")) {
                return p.substring(i + 7);                 // files 之后的部分：media/xxx.jpg
            }
        }
        return p;
    }

    /**
     * 配置里记「随包内置字体」时用的前缀。
     *
     * <p>例如 {@code asset:fonts/smiley_sans.otf}。用前缀而不是魔数路径，
     * 是为了跟「用户导入的文件」（绝对路径）和「系统字体文件」（/system/fonts/…）
     * 三种来源区分得开 —— 三条路的读法完全不同。
     */
    public static final String ASSET_PREFIX = "asset:";

    /** 本模块 APK 路径缓存（{@code ""} = 查过但拿不到）。 */
    private static volatile String sModuleApk;

    /**
     * 读素材字节，**四条路依次尝试**；全失败返回 null。
     *
     * <ol>
     *   <li><b>随包内置</b>（{@code asset:fonts/xxx.otf}）：直接打开**本模块 APK**
     *       这个 zip，取 {@code assets/fonts/xxx.otf}。{@code /data/app/**}{@code /base.apk}
     *       是 0644，任何 uid 都读得到 —— 这条**不需要跨进程通信**，是内置字体最稳的一档。</li>
     *   <li><b>直接路径</b>：{@code /system/fonts/NotoSansCJK.ttc} 这类本进程就能读的
     *       绝对路径（v0.0.9 起「使用系统字体」选的是真实字体文件）。</li>
     *   <li><b>宿主本地缓存</b>（v0.1.1 主通路）：{@code AssetSync} 用**显式广播**把
     *       模块私有目录里的文件预取到宿主自己的 {@code files/morimod_assets/}，
     *       之后就是普通文件读 —— 零跨进程、零等待，可以直接放在渲染热路径上。</li>
     *   <li><b>框架 {@code openRemoteFile}</b>：名义上是「模块共享数据目录」，
     *       而我们的文件写在 {@code files/} 下，多数机器上找不到 —— 留作最后兜底。</li>
     * </ol>
     *
     * <p>⚠️ v0.1.2 删掉了 bindService 那条：Android 11+ 的包可见性对**显式组件**同样生效，
     * 真机日志每次启动都是 {@code bind requested=false}，白跑一趟而已。
     *
     * <p>⚠️ v0.1.0 的 ContentProvider 通路**已删除**：宿主按 authority 解析 provider
     * 会被 Android 11+ 的包可见性挡住（真机日志
     * {@code E ActivityThread: Failed to find provider info for io.github.ariinyume.morimod.assets}），
     * 而调用方宿主的 manifest 我们改不了 ⇒ 那条路不可能通。
     */
    public static byte[] readBytes(String nameOrPath) {
        if (nameOrPath == null || nameOrPath.isEmpty()) return null;

        // ⓪ 随包内置字体：从本模块 APK 里直接解。
        if (nameOrPath.startsWith(ASSET_PREFIX)) {
            String asset = nameOrPath.substring(ASSET_PREFIX.length());
            byte[] bundled = readFromModuleApk(asset);
            if (bundled != null) return bundled;
            logReadFailure(nameOrPath, "拿不到模块 APK 或该条目不存在");
            return null;
        }

        // ① 直接路径（/system/fonts/*、/sdcard/... 等本进程可读的绝对路径）。
        //    ⚠️ 必须在 relativeName 之前判：/system/fonts/xxx.ttf 里也含 "fonts/"，
        //    先归一化会被截成 "fonts/xxx.ttf" 再拿去 openRemoteFile，必然打不开。
        if (nameOrPath.startsWith("/")) {
            byte[] direct = readFile(new java.io.File(nameOrPath));
            if (direct != null) return direct;
        }

        String rel = relativeName(nameOrPath);
        if (rel == null) return null;

        // ② 宿主本地缓存（v0.1.1 主通路）：AssetSync 已把模块私有目录里的文件
        //    **预取**到宿主自己的 files/morimod_assets/ —— 读它就是普通文件读，
        //    零跨进程、零等待，可以放在渲染热路径上。
        android.content.Context appCtx = io.github.ariinyume.morimod.util.AppEnv.app();
        if (appCtx != null) {
            java.io.File cached = AssetSync.cachedFile(appCtx, rel);
            byte[] hit = readFile(cached);
            if (hit != null) {
                if (!loggedOnce("cache-ok|" + rel)) {
                    XposedCompat.log(TAG + " read via local cache: " + rel);
                }
                return hit;
            }
        }

        // ③ 框架 remote file（第二档：多候选名，兼容不同框架实现）
        List<String> candidates = new ArrayList<>(3);
        candidates.add(rel);
        if (!rel.startsWith("files/")) candidates.add("files/" + rel);
        if (nameOrPath.startsWith("/")) candidates.add(nameOrPath);
        for (String cand : candidates) {
            byte[] b = readOne(cand);
            if (b != null) {
                XposedCompat.log(TAG + " remote name resolved via openRemoteFile: " + cand);
                return b;
            }
        }

        // 全都不通 ⇒ 排队预取（广播通道，真机已证实），下一轮渲染就能从本地缓存读到。
        if (appCtx != null) AssetSync.prefetch(appCtx, rel);

        logReadFailure(rel, "本地缓存 + openRemoteFile 都不通（已排队重新预取）");
        return null;
    }

    /**
     * 从**本模块 APK**（一个 zip）里取 {@code assets/<asset>}。
     *
     * <p>{@code /data/app/**}{@code /base.apk} 权限是 0644，宿主 uid 读得到；
     * 不需要 Service / Provider / 框架，是最可靠的一条。
     */
    private static byte[] readFromModuleApk(String asset) {
        try {
            String apk = moduleApkPath();
            if (apk == null) {
                logReadFailure(ASSET_PREFIX + asset, "拿不到模块 APK 路径");
                return null;
            }
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk)) {
                java.util.zip.ZipEntry e = zf.getEntry("assets/" + asset);
                if (e == null) {
                    logReadFailure(ASSET_PREFIX + asset, "APK 里没有该条目");
                    return null;
                }
                try (InputStream in = zf.getInputStream(e)) {
                    return drain(in, asset);
                }
            }
        } catch (Throwable t) {
            logReadFailure(ASSET_PREFIX + asset, "APK 解包失败 " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** 本模块 APK 路径（框架给的 ApplicationInfo.sourceDir，不受包可见性影响）。 */
    private static String moduleApkPath() {
        String p = sModuleApk;
        if (p != null) return p.isEmpty() ? null : p;
        try {
            io.github.libxposed.api.XposedInterface api = XposedCompat.api();
            android.content.pm.ApplicationInfo ai = api == null ? null : api.getModuleApplicationInfo();
            p = ai == null ? null : ai.sourceDir;
        } catch (Throwable t) {
            p = null;
        }
        sModuleApk = (p == null ? "" : p);
        return p;
    }

    /** 读取失败只在**每个名字第一次**打日志（素材是热路径，刷屏会淹掉别的证据）。 */
    private static void logReadFailure(String rel, String why) {
        if (!loggedOnce("fail|" + rel)) return;
        XposedCompat.log(TAG + " 素材读取全链路失败（" + why + "）: " + rel);
        DiagLog.event("asset", "素材读取失败 " + rel + " : " + why);
    }

    /** 每个 key 只放行一次（跨线程安全）。 */
    private static boolean loggedOnce(String key) {
        return sLogged.add(key);
    }

    private static final java.util.Set<String> sLogged =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 直接读文件（限制大小，防呆）。 */
    private static byte[] readFile(java.io.File f) {
        try {
            if (!f.isFile() || !f.canRead() || f.length() <= 0) return null;
            try (InputStream in = new FileInputStream(f)) {
                return drain(in, f.getName());
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把流读干（超过上限返回 null，防内存压力）。 */
    private static byte[] drain(InputStream in, String name) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int r;
        while ((r = in.read(buf)) > 0) {
            total += r;
            if (total > MAX_BYTES) {
                DiagLog.event("asset", name + " 超过上限，跳过（E-10）");
                return null;
            }
            bos.write(buf, 0, r);
        }
        return bos.size() == 0 ? null : bos.toByteArray();
    }

    /**
     * 通过框架 {@code openRemoteFile} 读模块 files/ 下的一个文件（v0.1.4）。
     *
     * <p>给 {@code RemoteConfig} 用：配置读取需要在**进程最早期、同步**拿到最新一份，
     * 而广播往返要 2~13 秒（真机实测），完全赶不上首帧。
     * 这条通道由框架直接开 fd，不经广播、不拉进程，是唯一能「立刻」读到模块侧文件的路径。
     *
     * <p>读不到就是 null（该能力在部分框架版本上不可用，属正常）。
     */
    public static byte[] readRemoteFile(String name) {
        if (name == null || name.isEmpty()) return null;
        byte[] a = readOne(name);
        if (a != null) return a;
        return readOne("files/" + name);
    }

    private static byte[] readOne(String name) {
        ParcelFileDescriptor pfd = null;
        try {
            io.github.libxposed.api.XposedInterface api = XposedCompat.api();
            if (api == null) return null;
            pfd = api.openRemoteFile(name);
            if (pfd == null) return null;
            try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                return drain(in, name);
            }
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (pfd != null) pfd.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 解码为降采样后的 Bitmap（两次采样，先量边界再解码）。 */
    public static Bitmap decodeBitmap(String nameOrPath) {
        byte[] bytes = readBytes(nameOrPath);
        if (bytes == null) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            int maxDim = Math.max(o.outWidth, o.outHeight);
            while (maxDim / sample > MAX_DECODE_DIM) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o2);
            if (bmp == null) {
                DiagLog.event("asset", nameOrPath + " 解码失败");
            }
            return bmp;
        } catch (Throwable t) {
            DiagLog.event("asset", nameOrPath + " 解码异常 " + t.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 把背景图 + 黑色遮罩合成一张 PNG 字节流（RN 图片加载器路径用）。
     *
     * <p>为什么要烘进像素：RN 侧会按 resizeMode=cover 做裁剪，
     * 若把遮罩留到 View 层画，裁剪后位置就会偏。烘进像素后怎么裁都对齐。
     *
     * @param src     已解码的背景位图（不会被修改）
     * @param maskPct 遮罩浓度 0–100
     * @return PNG 字节；失败返回 null
     */
    public static byte[] composePng(Bitmap src, int maskPct) {
        if (src == null || src.isRecycled()) return null;
        int pct = Math.max(0, Math.min(100, maskPct));
        Bitmap work = src;
        Bitmap masked = null;
        try {
            if (pct > 0) {
                masked = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                        Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(masked);
                c.drawBitmap(src, 0f, 0f, null);
                Paint p = new Paint();
                p.setColor(Color.BLACK);
                p.setAlpha((int) (pct / 100f * 255f));
                c.drawRect(0f, 0f, masked.getWidth(), masked.getHeight(), p);
                work = masked;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(
                    Math.min(8 * 1024 * 1024, Math.max(64 * 1024, work.getByteCount() / 4)));
            if (!work.compress(Bitmap.CompressFormat.PNG, 100, bos)) {
                return null;
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            DiagLog.event("asset", "背景合成失败 " + t.getClass().getSimpleName());
            return null;
        } finally {
            if (masked != null && masked != src) masked.recycle();
        }
    }

    /** 载入字体文件。 */
    public static Typeface loadTypeface(String nameOrPath) {
        byte[] bytes = readBytes(nameOrPath);
        if (bytes == null) return null;

        // ★★★ v0.1.5 血证：**必须校验字体文件完整性**。
        //   真机日志（0.1.4）把这件事钉死了：
        //     [MoriMod:F4] user font file loaded: …/fonts/mmvkvi89b.ttf
        //     [MoriMod:F4] font prewarm ok:        …/fonts/mmvkvi89b.ttf
        //     [MoriMod:F4] probe tf=589 paint=2471 nullTf=418 base=true cfg=true
        //   —— 钩子全部命中（2471 次 setTypeface 被改写）、字体文件也"加载成功"，
        //   但界面字体**一点没变**。唯一解释是拿到的 Typeface 没有可用字形。
        //   原因：素材是**分片广播**搬过来的（`asset cached … chunks=41`），
        //   任何一片丢失/超时都会留下一个"头 4 字节正确、表目录却越界"的残文件；
        //   `Typeface.createFromFile` 对这种文件**不抛异常**，只是没有字形 ⇒
        //   渲染时静默回落系统字体。所以这里先自己验表目录。
        String defect = fontDefect(bytes);
        if (defect != null) {
            XposedCompat.log(TAG + " 字体文件不完整（" + defect + "，" + bytes.length
                    + " 字节），丢弃并重新预取: " + nameOrPath);
            DiagLog.event("asset", "字体不完整 " + defect + " : " + nameOrPath);
            purgeCachedCopy(nameOrPath);      // 删掉坏缓存，逼 AssetSync 重新搬一遍
            return null;
        }

        try {
            // ⚠️⚠️ v0.1.5：临时文件**不再删除**。
            //   旧实现 `File.createTempFile(...)` + 载入后 `tmp.delete()`，
            //   注释里写的是「Typeface 已持有 mmap 句柄」。这在多数 ROM 上成立，
            //   但 Android 的 minikin 在新版本里对字体文件是**惰性映射**：
            //   unlink 之后再缺页中断，字形表读不出来，渲染就回落默认字体
            //   —— 与"加载成功但界面没变"的现象完全一致。
            //   现在落到宿主 cacheDir 下一个稳定的、名字带内容长度的文件，
            //   **一次写好留着复用**：既不担心被回收，也省掉每次启动重写 8MB。
            java.io.File f = fontCacheFile(nameOrPath, bytes.length);
            if (f == null) return null;
            if (!f.exists() || f.length() != bytes.length) {
                java.io.File parent = f.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) return null;
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                    fos.write(bytes);
                    fos.flush();
                    fos.getFD().sync();
                }
            }
            Typeface tf = Typeface.createFromFile(f);
            if (tf == null) {
                DiagLog.event("asset", "Typeface.createFromFile 返回 null: " + nameOrPath);
                return null;
            }
            return tf;
        } catch (Throwable t) {
            DiagLog.event("asset", "字体载入失败 " + nameOrPath + " : " + t.getClass().getSimpleName());
            XposedCompat.log(TAG + " font load failed: " + t + " : " + nameOrPath);
            return null;
        }
    }

    /**
     * 字体落地文件（宿主 cacheDir 下，跨进程可读；**永不删除**，供下次进程直接复用）。
     *
     * <p>名字里带「路径哈希 + 字节数」：同一个配置项永远映射到同一个文件，
     * 内容变了（用户重新导入同名不同版本的字体）字节数通常会变 ⇒ 自动重写；
     * 同哈希同长度视为同一份内容，直接复用，省掉每次启动写 8MB。
     */
    private static java.io.File fontCacheFile(String nameOrPath, int size) {
        android.content.Context app = io.github.ariinyume.morimod.util.AppEnv.app();
        java.io.File dir;
        if (app != null) {
            dir = new java.io.File(app.getCacheDir(), "morimod_fonts");
        } else {
            // 极端情况（Application 还没 attach）：退到 java.io.tmpdir。
            // 宿主进程的 tmpdir 就是它自己的 cache，权限没问题。
            String tmp = System.getProperty("java.io.tmpdir");
            if (tmp == null) return null;
            dir = new java.io.File(tmp, "morimod_fonts");
        }
        String ext = ".ttf";
        int dot = nameOrPath.lastIndexOf('.');
        if (dot > 0 && dot > nameOrPath.length() - 6) ext = nameOrPath.substring(dot);
        String key = Integer.toHexString(nameOrPath.hashCode()) + "-" + size + ext;
        return new java.io.File(dir, key);
    }

    /** 删掉本地缓存里的坏副本（只删 morimod_assets 下的，别的一律不碰）。 */
    private static void purgeCachedCopy(String nameOrPath) {
        try {
            android.content.Context app = io.github.ariinyume.morimod.util.AppEnv.app();
            if (app == null) return;
            String rel = relativeName(nameOrPath);
            if (rel == null) return;
            java.io.File f = AssetSync.cachedFile(app, rel);
            if (f != null && f.isFile()) {
                long n = f.length();
                if (f.delete()) {
                    XposedCompat.log(TAG + " 已删除损坏的素材缓存（" + n + " 字节）: " + rel);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 校验 sfnt 字体结构，返回缺陷描述（null = 完整）。
     *
     * <p>sfnt（TrueType / OpenType）结构：4 字节版本 + 2 字节表数 + 6 字节头，
     * 随后每个表 16 字节（tag / checksum / offset / length）。只要有任何一张表的
     * {@code offset + length} 超出文件末尾，就说明文件被截断了 ——
     * 这种文件 {@code Typeface.createFromFile} 仍会返回非 null 对象，但没有字形。
     */
    private static String fontDefect(byte[] b) {
        if (b == null || b.length < 12) return "too-small";
        int tag = ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16)
                | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
        boolean sfnt = tag == 0x00010000          // TrueType
                || tag == 0x4F54544F              // 'OTTO' CFF OpenType
                || tag == 0x74727565              // 'true'
                || tag == 0x74797031;             // 'typ1'
        if (tag == 0x74746366) return null;       // 'ttcf' 集合：结构不同，只验魔数
        if (!sfnt) return "bad-magic-0x" + Integer.toHexString(tag);
        int numTables = ((b[4] & 0xff) << 8) | (b[5] & 0xff);
        if (numTables <= 0 || numTables > 512) return "bad-numTables-" + numTables;
        int dirEnd = 12 + numTables * 16;
        if (b.length < dirEnd) return "truncated-dir(" + b.length + "<" + dirEnd + ")";
        for (int i = 0; i < numTables; i++) {
            int o = 12 + i * 16 + 8;
            long off = ((long) (b[o] & 0xff) << 24) | ((long) (b[o + 1] & 0xff) << 16)
                    | ((long) (b[o + 2] & 0xff) << 8) | (long) (b[o + 3] & 0xff);
            long len = ((long) (b[o + 4] & 0xff) << 24) | ((long) (b[o + 5] & 0xff) << 16)
                    | ((long) (b[o + 6] & 0xff) << 8) | (long) (b[o + 7] & 0xff);
            if (off + len > b.length) {
                return "table-" + i + "-oob(" + off + "+" + len + ">" + b.length + ")";
            }
        }
        return null;
    }
}
