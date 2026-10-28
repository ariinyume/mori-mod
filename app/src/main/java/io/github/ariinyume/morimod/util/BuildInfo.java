package io.github.ariinyume.morimod.util;

import io.github.ariinyume.morimod.BuildConfig;

/** 模块自身构建信息的唯一出口（跨进程都可用：类来自模块 APK）。 */
public final class BuildInfo {

    private BuildInfo() {
    }

    public static int versionCode() {
        return BuildConfig.VERSION_CODE;
    }

    public static String versionName() {
        return BuildConfig.VERSION_NAME;
    }

    public static String applicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    /** 版本横幅（常开，供排查「装的是哪一版」）。 */
    public static String banner() {
        return "==== BUILD " + versionName() + " / code " + versionCode();
    }
}
