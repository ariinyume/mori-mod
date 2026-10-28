package io.github.ariinyume.morimod.assets;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

import io.github.ariinyume.morimod.config.Protocol;

/**
 * 模块应用侧：把 {@code files/media|fonts} 里的素材**分片广播**给宿主（v0.1.1）。
 *
 * <h3>为什么是广播，不是 Provider / Service</h3>
 * 宿主是另一个 uid，读不到本模块私有目录（SELinux 每应用独享 MLS 类别，chmod 无效）。
 * 三条候选通道在真机上的实测结果：
 * <ul>
 *   <li><b>ContentProvider</b>（v0.0.9）—— 宿主日志
 *       {@code E ActivityThread: Failed to find provider info for
 *       io.github.ariinyume.morimod.assets}。根因是 Android 11+ 的包可见性：
 *       按 authority 访问对方 provider，调用方 manifest 必须有
 *       {@code <queries><provider .../></queries>}，而宿主 manifest 我们改不了。</li>
 *   <li><b>bindService 显式组件</b>（v0.1.1 初版）—— 同样受包可见性约束：
 *       缺 {@code <queries><package/>} 时 {@code bindService} 直接返回 false
 *       （官方文档 + 多个实测案例一致）。</li>
 *   <li><b>显式广播</b> —— 本机唯一被日志证实打通的通道：
 *       v0.0.9 起宿主发 {@code CONFIG_REQ}、模块回 {@code CONFIG_CHANGED} 都成功。</li>
 * </ul>
 *
 * <h3>为什么分片</h3>
 * Binder 单次事务上限约 1MB，广播的 extras 走 Binder ⇒ 大图/字体必须切片。
 * 每片 {@link #CHUNK} 字节（留足余量），{@code seq} 递增，{@code n} 为总片数。
 * 文件不存在时只回一片 {@code n=0} 的否定应答，宿主据此停止等待。
 *
 * <h3>安全</h3>
 * 只读、只认 {@code media/} 与 {@code fonts/} 下的单层文件名（{@link #isSafe}），
 * 且只发给 {@link Protocol#HOST_PKG}（显式包名）。收到任何第三方发来的
 * {@code ASSET_REQ} 也不会越权 —— 白名单先于读文件。
 */
public final class AssetQueryReceiver extends BroadcastReceiver {

    private static final String TAG = "MoriModAssets";

    /** 单片字节数。Binder 上限约 1MB，这里留足余量（16MB 字体 = 82 片）。 */
    private static final int CHUNK = 200 * 1024;

    /** 单文件上限，与宿主侧 {@code ModuleAssets.MAX_BYTES} 对齐。 */
    private static final int MAX_BYTES = 32 * 1024 * 1024;

    private static final String[] ALLOWED_DIRS = {"media/", "fonts/"};

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Protocol.ACTION_ASSET_REQ.equals(intent.getAction())) return;
        String rel = intent.getStringExtra(Protocol.EXTRA_ASSET_REL);
        long rid = intent.getLongExtra(Protocol.EXTRA_ASSET_RID, 0L);
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        try {
            serve(app, rel, rid);
        } catch (Throwable t) {
            // 广播回调里绝不抛
            Log.w(TAG, "serve failed rel=" + rel + " : " + t);
            reply(app, rel, rid, 0, 0, null);
        }
    }

    private void serve(Context ctx, String rel, long rid) {
        if (!isSafe(rel)) {
            Log.w(TAG, "reject unsafe rel=" + rel);
            reply(ctx, rel, rid, 0, 0, null);
            return;
        }
        File f = new File(ctx.getFilesDir(), rel);
        if (!f.isFile() || f.length() <= 0 || f.length() > MAX_BYTES) {
            Log.w(TAG, "not serveable rel=" + rel + " exists=" + f.exists()
                    + " len=" + f.length());
            reply(ctx, rel, rid, 0, 0, null);
            return;
        }
        byte[] all;
        try (InputStream in = new FileInputStream(f)) {
            all = readAll(in);
        } catch (Throwable t) {
            Log.w(TAG, "read failed rel=" + rel + " : " + t);
            reply(ctx, rel, rid, 0, 0, null);
            return;
        }
        if (all == null) {
            reply(ctx, rel, rid, 0, 0, null);
            return;
        }
        int n = (all.length + CHUNK - 1) / CHUNK;
        for (int seq = 0; seq < n; seq++) {
            int from = seq * CHUNK;
            int len = Math.min(CHUNK, all.length - from);
            byte[] part = new byte[len];
            System.arraycopy(all, from, part, 0, len);
            reply(ctx, rel, rid, seq, n, part);
        }
        Log.i(TAG, "served rel=" + rel + " bytes=" + all.length + " chunks=" + n);
    }

    /** 回一片（{@code n=0} 表示否定应答）。 */
    private void reply(Context ctx, String rel, long rid, int seq, int n, byte[] data) {
        try {
            Intent i = new Intent(Protocol.ACTION_ASSET_CHUNK);
            i.setPackage(Protocol.HOST_PKG);
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            i.putExtra(Protocol.EXTRA_ASSET_REL, rel);
            i.putExtra(Protocol.EXTRA_ASSET_RID, rid);
            i.putExtra(Protocol.EXTRA_ASSET_SEQ, seq);
            i.putExtra(Protocol.EXTRA_ASSET_N, n);
            if (data != null) i.putExtra(Protocol.EXTRA_ASSET_DATA, data);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            Log.w(TAG, "reply failed seq=" + seq + " : " + t);
        }
    }

    /**
     * 只允许 {@code media/}、{@code fonts/} 下的单层文件名。
     *
     * <p>⚠️ v0.1.6 修正：旧版用 {@code rel.contains("..")} 一刀切，把**文件名里带连续点**
     * 的合法素材也一起拒了。真机日志：
     * <pre>
     *   [MoriMod:Sync] asset denied fonts/DingTalkJinBuTi-Regular..ttf
     * </pre>
     * 而那个文件是设置页自己写出来的（导入时扩展名多补了一个点）——
     * 于是用户「重新添加本地字体后反而用不了」，宿主静默回落系统字体。
     *
     * <p>防穿越其实不需要那么粗的判据：**逐段**看，只要没有任何一段**等于** {@code ..}
     * 或 {@code .}，就不可能在目录上跳出去。{@code X..ttf} 是一段普普通通的文件名，放行。
     */
    private static boolean isSafe(String rel) {
        if (rel == null || rel.isEmpty()) return false;
        if (rel.contains("//") || rel.charAt(0) == '/') return false;
        String[] seg = rel.split("/", -1);
        for (String s : seg) {
            if (s.isEmpty() || ".".equals(s) || "..".equals(s)) return false;
        }
        boolean allowed = false;
        for (String d : ALLOWED_DIRS) {
            if (rel.startsWith(d) && rel.length() > d.length()) {
                allowed = true;
                break;
            }
        }
        if (!allowed) return false;
        return rel.indexOf('/', rel.indexOf('/') + 1) < 0;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(256 * 1024);
        byte[] buf = new byte[32 * 1024];
        int total = 0;
        int r;
        while ((r = in.read(buf)) > 0) {
            total += r;
            if (total > MAX_BYTES) return null;
            bos.write(buf, 0, r);
        }
        return bos.size() == 0 ? null : bos.toByteArray();
    }
}
