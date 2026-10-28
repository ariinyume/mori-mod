package io.github.ariinyume.morimod.hook;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import io.github.ariinyume.morimod.config.Protocol;
import io.github.ariinyume.morimod.util.XposedCompat;

/**
 * 宿主侧心跳（v0.1.2）。
 *
 * <h3>解决什么问题</h3>
 * 用户反馈「宿主明明开在后台，设置页却写未运行」。设置页原来的判据是
 * 「发一次 ping，1.2 秒内没有 pong 就当没运行」—— 在真机上这条判据太脆：
 * 宿主在后台被系统冻结时，广播要排队 + 解冻才能投递，经常超过 1.2 秒；
 * 设置页自己刚开始时也在抢 CPU，于是「检测不到」。
 *
 * <h3>做法</h3>
 * 宿主进程活着就每 {@link #INTERVAL_MS} 往模块应用发一条心跳（进程起来时立刻一条）。
 * 模块应用有**清单注册**的接收器（{@code status.HostStatusReceiver}），
 * 把时间戳落到自己的 prefs —— 不需要设置页开着，也不需要模块进程活着。
 * 设置页只用「最后一次心跳距今多久」来判断，一个 45 秒的宽限窗口足够扛住
 * 冻结 / 排队 / 冷启动的抖动，而真正被杀掉的进程会自然过期。
 *
 * <p>⚠️ 只发广播，**不持有任何唤醒锁**，也不注册定时 Alarm ——
 * 宿主被冻结时心跳自然停下，恢复到前台后第一条心跳会把状态拉回来，
 * 不会给宿主带来额外的电量开销（Handler 在进程被冻结时本来就不跑）。
 */
public final class Heartbeat {

    private static final String TAG = "[MoriMod:Beat]";

    /** 心跳间隔。20s 足够让 45s 的宽限窗口有两三个样本，也不至于费电。 */
    private static final long INTERVAL_MS = 20_000L;

    private static volatile boolean sStarted;
    private static volatile Context sCtx;
    private static Handler sHandler;
    private static Runnable sTick;

    private Heartbeat() {
    }

    /**
     * 启动心跳（幂等）。
     *
     * @param ctx 必须是**宿主自己的** Application 上下文：用 systemContext 发广播时
     *            系统会以 {@code package=android} 的身份去查调用方，
     *            真机上会直接抛 {@code SecurityException: Given caller package android
     *            is not running in process ...}。
     */
    public static void start(Context ctx) {
        if (ctx == null) return;
        sCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        // 每次 start 都先补一条：进程可能刚从后台被唤醒，越早刷新状态越好
        beat("start");
        if (sStarted) return;
        sStarted = true;
        try {
            sHandler = new Handler(Looper.getMainLooper());
            sTick = () -> {
                beat("tick");
                Handler h = sHandler;
                if (h != null) h.postDelayed(sTick, INTERVAL_MS);
            };
            sHandler.postDelayed(sTick, INTERVAL_MS);
            XposedCompat.log(TAG + " heartbeat started, interval=" + INTERVAL_MS + "ms");
        } catch (Throwable t) {
            sStarted = false;
            XposedCompat.log(TAG + " heartbeat start failed: " + t);
        }
    }

    /** 立刻发一条心跳（宿主收到设置页 ping 时也顺手补一条，让时间戳保持新鲜）。 */
    public static void beat(String why) {
        Context ctx = sCtx;
        if (ctx == null) return;
        try {
            Intent i = new Intent(Protocol.ACTION_HOST_ALIVE);
            i.setPackage(io.github.ariinyume.morimod.util.BuildInfo.applicationId());
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            i.putExtra(Protocol.EXTRA_ALIVE_TS, System.currentTimeMillis());
            i.putExtra(Protocol.EXTRA_BUILD,
                    io.github.ariinyume.morimod.util.BuildInfo.versionCode());
            ctx.sendBroadcast(i);
            if (!"tick".equals(why)) {
                XposedCompat.log(TAG + " beat sent (" + why + ")");
            }
        } catch (Throwable t) {
            // 广播失败绝不能影响宿主
            XposedCompat.log(TAG + " beat failed: " + t);
        }
    }
}
