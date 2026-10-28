package io.github.ariinyume.morimod.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.ariinyume.morimod.config.MoriConfig;
import io.github.ariinyume.morimod.config.Protocol;
import io.github.ariinyume.morimod.util.DiagLog;
import io.github.ariinyume.morimod.util.XposedCompat;

/**
 * 宿主侧：把模块应用里的素材**预取到宿主自己的目录**（v0.1.1）。
 *
 * <h3>为什么是「预取 + 本地缓存」而不是「用的时候现读」</h3>
 * <ol>
 *   <li>素材读取在**超热路径**上（每个 drawable / 每次 RN 渲染都会问）。
 *       跨进程往返几十毫秒，放在那里必然掉帧；而且主线程不能等待（会死锁）。</li>
 *   <li>取回来的字节直接落到宿主自己的 {@code files/morimod_assets/}，
 *       之后读它就是**普通文件读**，跟读自己的文件没区别。</li>
 * </ol>
 *
 * <h3>通道</h3>
 * 显式广播（{@link Protocol#ACTION_ASSET_REQ} → {@link Protocol#ACTION_ASSET_CHUNK}）。
 * 这是本机唯一被日志证实打通的跨 uid 通道：v0.0.9 的 {@code CONFIG_REQ} /
 * {@code CONFIG_CHANGED} 双向都成功。Provider（包可见性）与 bindService（同样受限）
 * 两条路都已在真机上否掉，详见 {@code AssetQueryReceiver} 的类注释。
 *
 * <h3>时序</h3>
 * {@link #register(Context)} 在宿主进程起来时立刻调用；配置一落地就
 * {@link #prefetchFor} 把用到的素材全排队。每取完一个文件回调一次 {@link #setOnSynced}，
 * 由模块入口重载配置 ⇒ F2/F4 的缓存失效 ⇒ 下一次渲染就用上用户素材。
 * 宿主重启后缓存仍在，第一条渲染就是用户素材，不必再等跨进程。
 */
public final class AssetSync {

    private static final String TAG = "[MoriMod:Sync]";

    /** 宿主侧缓存目录名（在宿主自己的 filesDir 下，SELinux 允许本进程读）。 */
    public static final String DIR = "morimod_assets";

    /** 单文件上限，与模块侧 {@code AssetQueryReceiver.MAX_BYTES} 对齐。 */
    private static final int MAX_BYTES = 32 * 1024 * 1024;

    /** 同一次请求 15s 内不重复发（模块进程冷启动最坏 ~1s；15s 是「彻底没救」的判据）。 */
    private static final long RETRY_MS = 15_000L;

    private static volatile boolean sRegistered;
    private static volatile Runnable sOnSynced;
    /** 带文件名的回调（v0.1.4）：字体落地后要能单独触发一次界面重排。 */
    private static volatile java.util.function.Consumer<String> sOnFileSynced;

    /** rel → 请求时刻（在途去重 + 超时可重试）。 */
    private static final Map<String, Long> INFLIGHT = new ConcurrentHashMap<>();

    /** "rel|rid" → 拼装器。 */
    private static final Map<String, Sink> SINKS = new ConcurrentHashMap<>();

    private AssetSync() {
    }

    /** 每次有文件落地后回调（模块入口用它重载配置）。 */
    public static void setOnSynced(Runnable r) {
        sOnSynced = r;
    }

    /** 带相对名的回调（v0.1.4）。用于「字体文件刚落地 ⇒ 强制重排一次文字」。 */
    public static void setOnFileSynced(java.util.function.Consumer<String> c) {
        sOnFileSynced = c;
    }

    /** 缓存文件路径（不存在也返回路径，调用方自己判断）。 */
    public static File cachedFile(Context ctx, String rel) {
        if (ctx == null || rel == null || rel.isEmpty()) return null;
        return new File(new File(ctx.getFilesDir(), DIR), rel.replace('/', '_'));
    }

    /**
     * 注册回包接收器（幂等）。
     *
     * <p>用**动态注册**而不是清单：宿主是别人的应用，我们只在自己进程活着时收包，
     * 没必要也不应该往宿主的组件表里加东西。
     */
    public static void register(Context ctx) {
        if (ctx == null || sRegistered) return;
        final Context app = ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                try {
                    onChunk(app, i);
                } catch (Throwable t) {
                    XposedCompat.log(TAG + " onChunk failed: " + t);
                }
            }
        };
        try {
            IntentFilter f = new IntentFilter(Protocol.ACTION_ASSET_CHUNK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(r, f);
            }
            sRegistered = true;
            XposedCompat.log(TAG + " receiver registered");
        } catch (Throwable t) {
            XposedCompat.log(TAG + " registerReceiver failed: " + t);
        }
    }

    /** 按配置把用到的素材排队预取（背景图、用户导入的字体；内置字体不走这条）。 */
    public static void prefetchFor(Context ctx, MoriConfig cfg) {
        if (ctx == null || cfg == null) return;
        if (cfg.f2Enabled && cfg.f2HomeBg != null && !cfg.f2HomeBg.isEmpty()) {
            queue(ctx, cfg.f2HomeBg);
        }
        if (cfg.f4Enabled && cfg.f4FontFile != null && !cfg.f4FontFile.isEmpty()) {
            queue(ctx, cfg.f4FontFile);
        }
    }

    /** 绝对路径 → 相对名后预取（内置字体 {@code asset:} 与系统字体直接读，不必跨进程）。 */
    private static void queue(Context ctx, String path) {
        if (path == null) return;
        if (path.startsWith(ModuleAssets.ASSET_PREFIX)) return;
        if (path.startsWith("/") && !path.contains("/files/")) return;
        prefetch(ctx, ModuleAssets.relativeName(path));
    }

    /** 请求一个素材（在途/已缓存则跳过）。 */
    public static void prefetch(Context ctx, String rel) {
        if (ctx == null || rel == null || rel.isEmpty()) return;
        register(ctx);
        File cache = cachedFile(ctx, rel);
        if (cache != null && cache.isFile() && cache.length() > 0) return;

        long now = System.currentTimeMillis();
        Long prev = INFLIGHT.get(rel);
        if (prev != null && now - prev < RETRY_MS) return;
        INFLIGHT.put(rel, now);

        long rid = now ^ (rel.hashCode() << 16);
        try {
            Intent req = new Intent(Protocol.ACTION_ASSET_REQ);
            req.setPackage(io.github.ariinyume.morimod.util.BuildInfo.applicationId());
            req.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            req.putExtra(Protocol.EXTRA_ASSET_REL, rel);
            req.putExtra(Protocol.EXTRA_ASSET_RID, rid);
            ctx.sendBroadcast(req);
            XposedCompat.log(TAG + " asset req sent " + rel + " rid=" + rid);
        } catch (Throwable t) {
            INFLIGHT.remove(rel);
            XposedCompat.log(TAG + " asset req failed " + rel + " : " + t);
        }
    }

    private static void onChunk(Context ctx, Intent i) {
        if (i == null) return;
        String rel = i.getStringExtra(Protocol.EXTRA_ASSET_REL);
        long rid = i.getLongExtra(Protocol.EXTRA_ASSET_RID, 0L);
        int seq = i.getIntExtra(Protocol.EXTRA_ASSET_SEQ, 0);
        int n = i.getIntExtra(Protocol.EXTRA_ASSET_N, 0);
        if (rel == null || rel.isEmpty()) return;

        if (n <= 0) {                       // 否定应答：文件不存在 / 不可读
            INFLIGHT.remove(rel);
            XposedCompat.log(TAG + " asset denied " + rel);
            DiagLog.event("asset", "模块侧没有该素材 " + rel);
            return;
        }

        String key = rel + "|" + rid;
        Sink sink = SINKS.get(key);
        if (sink == null) {
            sink = new Sink(n);
            SINKS.put(key, sink);
        }
        byte[] data = i.getByteArrayExtra(Protocol.EXTRA_ASSET_DATA);
        boolean done = sink.push(seq, data);
        if (!done) return;

        SINKS.remove(key);
        byte[] all = sink.bytes();
        if (all == null || all.length == 0 || all.length > MAX_BYTES) {
            INFLIGHT.remove(rel);
            XposedCompat.log(TAG + " asset assemble bad " + rel + " len="
                    + (all == null ? -1 : all.length));
            return;
        }
        File dst = cachedFile(ctx, rel);
        if (dst == null) return;
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            INFLIGHT.remove(rel);
            return;
        }
        File tmp = new File(dst.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(all);
            out.flush();
            out.getFD().sync();
        } catch (Throwable t) {
            INFLIGHT.remove(rel);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            XposedCompat.log(TAG + " write cache failed " + rel + " : " + t);
            return;
        }
        if (!tmp.renameTo(dst)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            INFLIGHT.remove(rel);
            return;
        }
        INFLIGHT.remove(rel);
        XposedCompat.log(TAG + " asset cached " + rel + " bytes=" + all.length
                + " chunks=" + n);
        DiagLog.event("asset", "素材已缓存 " + rel + " (" + all.length + " B)");
        fireOnSynced(rel);
    }

    private static void fireOnSynced(String rel) {
        final Runnable r = sOnSynced;
        final java.util.function.Consumer<String> f = sOnFileSynced;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (r != null) r.run();
            } catch (Throwable t) {
                XposedCompat.log(TAG + " onSynced failed: " + t);
            }
            try {
                if (f != null) f.accept(rel);
            } catch (Throwable t) {
                XposedCompat.log(TAG + " onFileSynced failed: " + t);
            }
        });
    }

    /** 分片拼装器（按 seq 顺序追加；乱序片直接丢弃，靠模块侧重发整轮兜底）。 */
    private static final class Sink {
        private final int n;
        private final ByteArrayOutputStream bos;
        private int next;
        private boolean broken;

        Sink(int n) {
            this.n = n;
            this.bos = new ByteArrayOutputStream(Math.min(MAX_BYTES, n * 200 * 1024));
        }

        synchronized boolean push(int seq, byte[] data) {
            if (broken) return false;
            if (seq != next || data == null) {
                if (seq != next) broken = true;   // 丢片 ⇒ 本轮作废，等下一次请求
                return false;
            }
            bos.write(data, 0, data.length);
            next++;
            return next >= n;
        }

        synchronized byte[] bytes() {
            return broken ? null : bos.toByteArray();
        }
    }
}
