package io.github.ariinyume.morimod.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import io.github.ariinyume.morimod.config.ConfigBus;
import io.github.ariinyume.morimod.config.Protocol;

/**
 * 作用域授权探测（FR-0.1）。
 *
 * <p>原理：LSPosed 作用域按进程授权 —— 设置页发 PING，被注入的宿主进程回 PONG，
 * 「收到带 host tag 的 PONG」就是「该进程已授权且模块已在里面跑起来」的可观测证据。
 *
 * <p>⚠️ 铁律 16：判「已激活」必须 {@code hostAuthorized && hostAlive} 相与 ——
 * 单用 hostAlive 会把「作用域没勾选」误判成已激活。
 *
 * <p>⚠️ 铁律 975：窗口内多轮重发 —— 宿主退到后台被系统降级时单发一次极易丢。
 */
public final class ScopeProbe {

    private static final String TAG = "[MoriMod:ScopeProbe]";

    /** 默认探测窗口。 */
    public static final long DEFAULT_WINDOW_MS = 2800L;
    /** 窗口内重发 PING 的间隔。 */
    private static final long PING_REPEAT_MS = 400L;

    public static final class Result {
        /** 宿主进程里有本模块在跑（= 用户勾选了宿主作用域）。 */
        public boolean hostAuthorized;
        /** 宿主当前真的在用（模块已加载并运行）。 */
        public boolean hostAlive = false;
        public int hostBuild = -1;
        /** 宿主侧靶点摘要（installs/failed/triggers）。 */
        public String triggerSummary = "";

        @Override
        public String toString() {
            return "ScopeProbe.Result{host=" + hostAuthorized + "@" + hostBuild
                    + ", alive=" + hostAlive + ", " + triggerSummary + "}";
        }
    }

    public interface Callback {
        /** 探测收口后回调（保证只调一次，且在主线程）。 */
        void onResult(Result r);
    }

    private ScopeProbe() {
    }

    public static void probe(Context ctx, Callback cb) {
        probe(ctx, DEFAULT_WINDOW_MS, cb);
    }

    public static void probe(Context ctx, long windowMs, Callback cb) {
        if (ctx == null) {
            if (cb != null) cb.onResult(new Result());
            return;
        }
        final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        final Result result = new Result();
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] finished = {false};

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i == null || i.getAction() == null) return;
                if (!Protocol.ACTION_HOST_PONG.equals(i.getAction())) return;
                // 必须校验 tag：PONG 是同一 action，只按 action 判定会误认
                if (!Protocol.TAG_HOST.equals(i.getStringExtra(Protocol.EXTRA_TAG))) return;
                result.hostAuthorized = true;
                result.hostBuild = i.getIntExtra(Protocol.EXTRA_BUILD, -1);
                result.hostAlive = i.getBooleanExtra(Protocol.EXTRA_HOST_ALIVE, false);
                String ts = i.getStringExtra(Protocol.EXTRA_TRIGGER_SUMMARY);
                if (ts != null) result.triggerSummary = ts;
            }
        };

        final Runnable finish = new Runnable() {
            @Override
            public void run() {
                if (finished[0]) return;
                finished[0] = true;
                try {
                    appCtx.unregisterReceiver(receiver);
                } catch (Throwable ignored) {
                }
                // 铁律 16：授权与存活性相与
                boolean activated = result.hostAuthorized && result.hostAlive;
                LogGate.debug(TAG, "probe done in " + windowMs + "ms: " + result
                        + " activated=" + activated);
                if (cb != null) {
                    try {
                        cb.onResult(result);
                    } catch (Throwable t) {
                        XposedCompat.log(TAG + " callback failed: " + t);
                    }
                }
            }
        };

        IntentFilter f = new IntentFilter(Protocol.ACTION_HOST_PONG);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            } else {
                appCtx.registerReceiver(receiver, f);
            }
        } catch (Throwable t) {
            XposedCompat.log(TAG + " registerReceiver failed: " + t);
            finish.run();
            return;
        }

        final long deadline = SystemClock.uptimeMillis() + Math.max(200L, windowMs);
        final Runnable[] pingLoop = new Runnable[1];
        pingLoop[0] = new Runnable() {
            @Override
            public void run() {
                if (finished[0]) return;
                ConfigBus.sendHostScopePing(appCtx);
                long now = SystemClock.uptimeMillis();
                if (now + PING_REPEAT_MS <= deadline) {
                    handler.postDelayed(pingLoop[0], PING_REPEAT_MS);
                }
            }
        };
        pingLoop[0].run();
        handler.postDelayed(finish, Math.max(200L, windowMs));
    }
}
