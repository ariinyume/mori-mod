package io.github.ariinyume.morimod.config;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import io.github.ariinyume.morimod.util.DiagLog;
import io.github.ariinyume.morimod.util.XPrefs;
import io.github.ariinyume.morimod.util.XposedCompat;

/**
 * 跨进程配置总线（FR-0.3）。
 *
 * <p>采用「<b>广播通知 + 各进程写自身本地 prefs</b>」模式 —— LSPosed 2.2.0 下被注入进程的
 * {@code getRemotePreferences} 是只读实现（铁律 17），不能靠写 remote prefs 同步。
 *
 * <p>宿主侧 {@link #installResponder(Context)} 装应答器；设置页侧使用各 {@code sendXxx}。
 */
public final class ConfigBus {

    private static final String TAG = "[MoriMod:ConfigBus]";
    private static volatile boolean sInstalled = false;

    /** 宿主侧「重新随机本曲目」的处理器（由 F1 能力层注入，避免 config→hook 的硬耦合）。 */
    public static volatile Runnable sRerollHandler;

    private ConfigBus() {
    }

    // ======================================================================
    // 宿主侧：安装应答器
    // ======================================================================

    /**
     * 宿主侧安装应答器（幂等）。
     *
     * @return 是否已就绪（true = 接收器已注册）。注册失败会把 guard 复位，
     *         允许下一次（比如 {@code onPackageReady} 的兜底路径）重试。
     */
    public static boolean installResponder(Context ctx) {
        if (ctx == null) return false;
        if (sInstalled) return true;
        sInstalled = true;
        final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i == null || i.getAction() == null) return;
                String action = i.getAction();
                try {
                    switch (action) {
                        case Protocol.ACTION_CONFIG_CHANGED: {
                            String json = i.getStringExtra(Protocol.EXTRA_CONFIG_JSON);
                            if (json != null && !json.isEmpty()) {
                                // ★★★ v0.1.5：**先判新旧，再落盘**。
                                //   广播是排队投递的（我们发的时候带了
                                //   FLAG_INCLUDE_STOPPED_PACKAGES），宿主停机期间积压的
                                //   那条 CONFIG_CHANGED 会在下次冷启动时投递 ——
                                //   如果先写本地副本再判断，旧配置已经把
                                //   `files/morimod_config.json` 覆盖了，
                                //   于是「本次读到的新字体」又被改回上一个。
                                //   真机日志（0.1.4，21:52）就是这样来回抖：
                                //     font=mmvkvhki4.ttf → (广播) → font=mmvkvi89b.ttf
                                MoriConfig incoming = MoriConfig.fromJson(json);
                                if (incoming != null && incoming.savedAt > 0L) {
                                    long cur = RemoteConfig.get().savedAt;
                                    if (cur > 0L && incoming.savedAt < cur) {
                                        XposedCompat.log(TAG
                                                + " stale config broadcast dropped: ts="
                                                + incoming.savedAt + " < " + cur);
                                        break;
                                    }
                                }
                                // 写自身本地副本（重启后仍能读到）。
                                // ⚠️ v0.0.9：**两份都写**。真机日志显示只写 prefs 时重启后读到空，
                                //    所以再加一份直读的 JSON 文件（files/morimod_config.json），
                                //    并在写完立刻回读自证 —— 下一轮日志能直接看出哪一份丢了。
                                boolean okPrefs = XPrefs.putString(appCtx, XPrefs.HOOK,
                                        XPrefs.K_CONFIG_JSON, json);
                                boolean okFile = incoming != null
                                        && ConfigStore.saveFile(appCtx, incoming);
                                String back = null;
                                try {
                                    MoriConfig readBack = ConfigStore.loadFile(appCtx);
                                    back = readBack == null ? "null" : readBack.summary();
                                } catch (Throwable ignored) {
                                    back = "err";
                                }
                                RemoteConfig.applyJson(json, "broadcast");
                                XposedCompat.log(TAG + " config applied from broadcast: "
                                        + RemoteConfig.get().summary());
                                XposedCompat.log(TAG + " persisted prefs=" + okPrefs
                                        + " file=" + okFile + " readback=" + back);
                                // ★ v0.1.1：配置里可能换了背景图/字体 ⇒ 立刻把新素材预取到
                                //   宿主本地缓存（广播通道）。取完会回调重载配置，
                                //   于是「设置页一保存，宿主这边就生效」不再依赖重启。
                                io.github.ariinyume.morimod.hook.AssetSync
                                        .prefetchFor(appCtx, RemoteConfig.get());
                            }
                            break;
                        }
                        case Protocol.ACTION_HOST_PING: {
                            Intent pong = new Intent(Protocol.ACTION_HOST_PONG);
                            pong.putExtra(Protocol.EXTRA_TAG, Protocol.TAG_HOST);
                            pong.putExtra(Protocol.EXTRA_BUILD,
                                    io.github.ariinyume.morimod.util.BuildInfo.versionCode());
                            pong.putExtra(Protocol.EXTRA_HOST_ALIVE, true);
                            pong.putExtra(Protocol.EXTRA_TRIGGER_SUMMARY, DiagLog.triggerSummary());
                            sendToSettings(appCtx, pong);
                            // 顺手补一条心跳：让模块侧的时间戳在「pong 到了但设置页还没处理」
                            // 这种边缘情况下也是新的（v0.1.2）。
                            io.github.ariinyume.morimod.hook.Heartbeat.beat("ping");
                            break;
                        }
                        case Protocol.ACTION_REROLL: {
                            Runnable h = sRerollHandler;
                            boolean ok = false;
                            if (h != null) {
                                h.run();
                                ok = true;
                            }
                            DiagLog.event("f1", "reroll requested ok=" + ok);
                            break;
                        }
                        case Protocol.ACTION_DIAG_REQ: {
                            Intent resp = new Intent(Protocol.ACTION_DIAG_RESP);
                            resp.putExtra(Protocol.EXTRA_DIAG_TEXT, DiagLog.dump());
                            sendToSettings(appCtx, resp);
                            break;
                        }
                        case Protocol.ACTION_DIAG_CLEAR: {
                            DiagLog.clear();
                            break;
                        }
                        default:
                            break;
                    }
                } catch (Throwable t) {
                    // 回调内异常绝不能抛回宿主（M-01）
                    XposedCompat.log(TAG + " onReceive failed action=" + action + " : " + t);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Protocol.ACTION_CONFIG_CHANGED);
        f.addAction(Protocol.ACTION_HOST_PING);
        f.addAction(Protocol.ACTION_REROLL);
        f.addAction(Protocol.ACTION_DIAG_REQ);
        f.addAction(Protocol.ACTION_DIAG_CLEAR);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                appCtx.registerReceiver(r, f);
            }
            XposedCompat.log(TAG + " responder installed (tag=host)");
            return true;
        } catch (Throwable t) {
            XposedCompat.log(TAG + " registerReceiver failed: " + t);
            sInstalled = false;      // 允许后续重试
            return false;
        }
    }

    private static void sendToSettings(Context ctx, Intent i) {
        try {
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            // 模块包名 = BuildConfig.APPLICATION_ID（设置页进程）
            i.setPackage(io.github.ariinyume.morimod.util.BuildInfo.applicationId());
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            XposedCompat.log(TAG + " sendToSettings failed: " + t);
        }
    }

    /**
     * 宿主启动时**主动向模块应用要一份配置**（v0.0.9）。
     *
     * <p>为什么必须这么做：`XposedInterface.getRemotePreferences()` 在注入进程里
     * 经常读到空，宿主自己目录下的副本也出现过读不到（真机日志 6/6 次
     * {@code config source=defaults}）。而模块应用里那份是设置页**当面 commit** 过的，
     * 一定存在。清单注册的 {@code ConfigQueryReceiver} 会让系统把模块进程拉起来应答，
     * 完全不依赖设置页是否打开。
     *
     * <p>重试节奏：0ms / 1.2s / 3.5s，共三次；期间若已经拿到非默认配置就停。
     * 全部在后台线程 + 主线程 Handler 上，**不阻塞宿主启动**。
     */
    public static void requestConfigWithRetry(Context ctx) {
        if (ctx == null) return;
        if (!sRequestPending.compareAndSet(false, true)) return;
        final Context appCtx = ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx;
        final long[] delays = {0L, 1200L, 3500L};
        final android.os.Handler h = new android.os.Handler(
                android.os.Looper.getMainLooper());
        for (int idx = 0; idx < delays.length; idx++) {
            final int attempt = idx + 1;
            h.postDelayed(() -> {
                try {
                    if (!"defaults".equals(RemoteConfig.source())) {
                        sRequestPending.set(false);
                        return;     // 已经拿到真配置，不再打扰
                    }
                    Intent req = new Intent(Protocol.ACTION_CONFIG_REQ);
                    req.setPackage(io.github.ariinyume.morimod.util.BuildInfo.applicationId());
                    req.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    appCtx.sendBroadcast(req);
                    XposedCompat.log(TAG + " config request sent (attempt " + attempt + ")");
                } catch (Throwable t) {
                    XposedCompat.log(TAG + " config request failed: " + t);
                }
            }, delays[idx]);
        }
        // 4 秒后放开闸门：万一三次都没成（模块被禁/被冻结），用户下次保存还能再触发
        h.postDelayed(() -> sRequestPending.set(false), 4200L);
    }

    /** 请求闸门（同一进程内避免重复排队）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sRequestPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 启动核对闸门（v0.1.3）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sSyncPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 宿主启动时**无条件**向模块应用核对一次配置（v0.1.3）。
     *
     * <h3>为什么必须无条件</h3>
     * 宿主能读到的那份副本（{@code files/morimod_config.json}）只有「宿主进程活着时收到
     * {@code CONFIG_CHANGED}」才会被写。而真实使用顺序几乎总是：
     * <pre>
     *   打开模块设置 → 选字体 / 选背景（此时宿主**没在运行**）→ 打开宿主
     * </pre>
     * 于是宿主读到的是**上一次**的设置 ⇒ 用户看到的永远是「我明明选了，可它没变」。
     * 这里在进程起来后（900ms，让宿主先跑完启动）要一份权威配置；
     * 模块应用那边回 {@code CONFIG_CHANGED}，宿主比对后：
     * 内容相同 → {@code applyJson} 直接跳过重载；内容不同 → 立刻生效。
     *
     * <p>代价：每次冷启动多一条跨包广播（模块进程通常已在运行）；
     * 换回来的是「设置页保存什么，宿主起来就是什么」。
     */
    public static void requestSync(Context ctx) {
        if (ctx == null) return;
        if (!sSyncPending.compareAndSet(false, true)) return;
        final Context appCtx = ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx;
        // ★ v0.1.4：**立刻**发，不再等 900ms。
        //   真机日志（19:09:47 启动）：广播请求 1s 后才发出、模块进程冷启动应答又花了好几秒，
        //   等配置到手已经过去 13 秒 —— 首页早就用旧字体画完了。
        //   配置新鲜度现在主要靠 {@code RemoteConfig#read} 的 remote 通道（同步、零往返），
        //   这条广播退化为「补差」：仍然尽早发，能赶上就赶上。
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                Intent req = new Intent(Protocol.ACTION_CONFIG_REQ);
                req.setPackage(io.github.ariinyume.morimod.util.BuildInfo.applicationId());
                req.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                appCtx.sendBroadcast(req);
                XposedCompat.log(TAG + " config sync request sent");
            } catch (Throwable t) {
                XposedCompat.log(TAG + " config sync request failed: " + t);
            } finally {
                sSyncPending.set(false);
            }
        });
    }

    // ======================================================================
    // 设置页侧：发送
    // ======================================================================

    public static void sendConfigChanged(Context ctx, String json) {
        Intent i = new Intent(Protocol.ACTION_CONFIG_CHANGED);
        i.putExtra(Protocol.EXTRA_CONFIG_JSON, json);
        sendToHost(ctx, i);
    }

    public static void sendHostScopePing(Context ctx) {
        sendToHost(ctx, new Intent(Protocol.ACTION_HOST_PING));
    }

    public static void sendReroll(Context ctx) {
        sendToHost(ctx, new Intent(Protocol.ACTION_REROLL));
    }

    public static void sendDiagReq(Context ctx) {
        sendToHost(ctx, new Intent(Protocol.ACTION_DIAG_REQ));
    }

    public static void sendDiagClear(Context ctx) {
        sendToHost(ctx, new Intent(Protocol.ACTION_DIAG_CLEAR));
    }

    private static void sendToHost(Context ctx, Intent i) {
        if (ctx == null) return;
        try {
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            Intent explicit = new Intent(i);
            explicit.setPackage(Protocol.HOST_PKG);
            ctx.sendBroadcast(explicit);
            // 再发一次隐式广播，覆盖「宿主进程已启动但显式包名被拦」的边缘情况
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            XposedCompat.log(TAG + " sendToHost failed: " + t);
        }
    }

    /** 版本号（供探测端比对）。 */
    public static int buildCode() {
        return io.github.ariinyume.morimod.util.BuildInfo.versionCode();
    }
}
