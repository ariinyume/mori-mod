package io.github.ariinyume.morimod.config;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 模块应用侧的**清单注册**配置应答器（v0.0.9）。
 *
 * <h3>为什么必须有它</h3>
 * 真机日志（2026-10-23 13:2x，v0.0.8）显示宿主每次进程启动都是
 * {@code config source=defaults}：跨进程读配置的两条路（宿主本地副本 / 框架
 * remote prefs）**都读不到**，配置只在「设置页当场保存并广播、且宿主还活着」的
 * 那几秒生效。用户按提示重启 DLsiteSound 之后，功能就全部回到关闭
 * —— 这就是「两个功能完全不生效」的全部成因。
 *
 * <p>本接收器由 {@code AndroidManifest} 静态注册，宿主用
 * {@link Protocol#ACTION_CONFIG_REQ} + {@code FLAG_INCLUDE_STOPPED_PACKAGES}
 * 发过来时，系统会把本模块进程拉起来应答 —— **不需要设置页开着**，也不需要
 * 用户手动打开任何界面。代价只有一次进程冷启动（几毫秒的读文件 + 回一条广播）。
 *
 * <h3>为什么这里不用 XposedCompat / DiagLog</h3>
 * 本类跑在**模块自己的进程**里，没有框架 API，也没有宿主上下文；用系统 Log
 * 打标记即可（用户导出的 LSPosed 日志包里 {@code full.log} 是 logcat 全量）。
 */
public final class ConfigQueryReceiver extends BroadcastReceiver {

    private static final String TAG = "MoriModUI";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!Protocol.ACTION_CONFIG_REQ.equals(intent.getAction())) return;
        try {
            // ① prefs 是最新的一份（设置页每次保存都 commit）
            MoriConfig cfg = ConfigStore.loadPrefs(context);
            String src = "prefs";
            // ② 退到 JSON 文件（prefs 被清过但文件还在的极端情况）
            if (cfg == null) {
                cfg = ConfigStore.loadFile(context);
                src = "file";
            }
            // ③ 都没有就给默认值（全关），至少让宿主拿到一份结构完整的 JSON
            if (cfg == null) {
                cfg = new MoriConfig();
                src = "defaults";
            }
            cfg.clamp();

            Intent resp = new Intent(Protocol.ACTION_CONFIG_CHANGED);
            resp.putExtra(Protocol.EXTRA_CONFIG_JSON, cfg.toJson());
            resp.setPackage(Protocol.HOST_PKG);
            resp.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(resp);
            Log.i(TAG, "CONFIG_REQ answered src=" + src + " " + cfg.summary());
        } catch (Throwable t) {
            // 接收器内异常绝不能抛回系统（会把宿主那条链路一起打断）
            Log.i(TAG, "CONFIG_REQ answer failed: " + t);
        }
    }
}
