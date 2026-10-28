package io.github.ariinyume.morimod.status;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import io.github.ariinyume.morimod.config.Protocol;
import io.github.ariinyume.morimod.util.XPrefs;

/**
 * 宿主心跳的落盘点（v0.1.2，**清单注册**）。
 *
 * <p>宿主每 20 秒往本应用发一条 {@link Protocol#ACTION_HOST_ALIVE}；
 * 这里把时间戳写进本应用自己的 prefs，设置页据此判断「宿主最后活着是什么时候」。
 *
 * <h3>为什么用清单接收器而不是动态注册</h3>
 * 清单接收器能把**已经死掉的**模块进程拉起来应答（同一个机制在 v0.0.9 的
 * {@code ConfigQueryReceiver} 上被真机日志证实有效）。这样即使用户从没打开过设置页，
 * 时间戳也一直在刷新；等用户打开设置页时，状态卡第一帧就是对的，
 * 而不是先显示「检测中」再等一次问答。
 *
 * <p>⚠️ 这是本模块唯一会被**外部任意应用**触发的入口，所以：
 * 只认 action，字段只取一个 long，写入前做范围校验（拒绝明显离谱的时间戳），
 * 任何异常都吞掉 —— 清单接收器崩了会弹「模块已停止运行」的系统对话框，
 * 那是绝不能出现的。
 */
public final class HostStatusReceiver extends BroadcastReceiver {

    /** prefs 档位（跟配置分开，避免互相影响）。 */
    public static final String PREFS = "morimod_status";

    /** 最后一次心跳的系统时间（{@code System.currentTimeMillis()}）。 */
    public static final String K_LAST_ALIVE = "last_alive_ms";

    /** 最后一次心跳里带的模块版本号（设置页可用来提示「宿主里跑的是旧版本」）。 */
    public static final String K_BUILD = "host_build_code";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!Protocol.ACTION_HOST_ALIVE.equals(intent.getAction())) return;
        try {
            long ts = intent.getLongExtra(Protocol.EXTRA_ALIVE_TS, 0L);
            long now = System.currentTimeMillis();
            // 只接受「合理区间」：客户端时钟偏差 10 分钟内才算数，未来时间一律归一到 now
            if (ts <= 0L || ts > now + 600_000L) ts = now;
            Context app = context.getApplicationContext() != null
                    ? context.getApplicationContext() : context;
            XPrefs.putLong(app, PREFS, K_LAST_ALIVE, ts);
            int build = intent.getIntExtra(Protocol.EXTRA_BUILD, 0);
            if (build > 0) XPrefs.putLong(app, PREFS, K_BUILD, build);
        } catch (Throwable ignored) {
            // 绝不抛回系统
        }
    }
}
