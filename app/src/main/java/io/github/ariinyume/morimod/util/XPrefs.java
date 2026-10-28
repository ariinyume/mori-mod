package io.github.ariinyume.morimod.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本地 SharedPreferences 读写助手（FR-0.3）。
 *
 * <p>两个档位：
 * <ul>
 *   <li>{@link #HOOK} —— 宿主进程收到广播后写**自身本地**副本（应对 LSPosed 2.2.0
 *       remote prefs 只读，铁律 17）；</li>
 *   <li>{@link #UI} —— 设置页自身的界面偏好（如「曾确认授权」落盘位）。</li>
 * </ul>
 */
public final class XPrefs {

    public static final String HOOK = "morimod_hook";
    public static final String UI = "morimod_ui";

    /** 配置 JSON 的键（两个档位通用）。 */
    public static final String K_CONFIG_JSON = "config_json";
    /** 设置页：是否曾确认作用域已授权（跨重启保留，参考项目 code 1016 口径）。 */
    public static final String K_AUTHORIZED_ONCE = "authorized_once";

    private XPrefs() {
    }

    public static SharedPreferences of(Context ctx, String name) {
        if (ctx == null) return null;
        try {
            return ctx.getApplicationContext().getSharedPreferences(name, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String getString(Context ctx, String name, String key, String def) {
        SharedPreferences sp = of(ctx, name);
        if (sp == null) return def;
        try {
            return sp.getString(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static boolean putString(Context ctx, String name, String key, String value) {
        SharedPreferences sp = of(ctx, name);
        if (sp == null) return false;
        try {
            return sp.edit().putString(key, value).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean getBoolean(Context ctx, String name, String key, boolean def) {
        SharedPreferences sp = of(ctx, name);
        if (sp == null) return def;
        try {
            return sp.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static long getLong(Context ctx, String name, String key, long def) {
        SharedPreferences sp = of(ctx, name);
        if (sp == null) return def;
        try {
            return sp.getLong(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static boolean putLong(Context ctx, String name, String key, long value) {
        SharedPreferences sp = of(ctx, name);
        if (sp == null) return false;
        try {
            return sp.edit().putLong(key, value).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean putBoolean(Context ctx, String name, String key, boolean value) {
        SharedPreferences sp = of(ctx, name);
        if (sp == null) return false;
        try {
            return sp.edit().putBoolean(key, value).commit();
        } catch (Throwable t) {
            return false;
        }
    }
}
