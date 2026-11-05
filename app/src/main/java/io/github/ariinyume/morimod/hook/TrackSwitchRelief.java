package io.github.ariinyume.morimod.hook;

import android.view.View;
import android.view.ViewParent;

import io.github.ariinyume.morimod.config.MoriConfig;
import io.github.ariinyume.morimod.config.RemoteConfig;
import io.github.ariinyume.morimod.util.XposedCompat;
import io.github.libxposed.api.XposedInterface;

/**
 * 播放页曲名、作品名跑马灯保持不动（v0.1.105）。
 *
 * <p>2.23.0 真机层级（PLK110，1272×2772）上这两条是同一结构，只是高度和透明度不同：
 * <pre>
 * RNCMaskedView 1153×81
 *   ReactViewGroup 3018×81          ← setTranslationX，曲名（两份 ReactTextView 首尾相接）
 * RNCMaskedView 1153×77
 *   ReactViewGroup 3832×77 α=0.7    ← setTranslationX，作品名
 * </pre>
 * 开关打开后，这两条的平移一律丢掉，并在第一次命中时把已有偏移拉回 0，
 * 避免停在滚到一半的位置。关掉开关后下一次 setTranslationX 照常放行。
 */
public final class TrackSwitchRelief {

    private static final String TAG = "[MoriMod:Relief] ";

    private static volatile boolean sOn = false;
    private static volatile boolean sHooked = false;

    /** 自己把偏移拉回 0 时不要再被拦一次。 */
    private static final ThreadLocal<Boolean> SKIP =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static volatile int sScreenW = 0;
    private static final int[] sSeenW = new int[4];
    private static int sNoted = 0;

    private TrackSwitchRelief() {
    }

    public static void install(ClassLoader cl) {
        RemoteConfig.addListener(TrackSwitchRelief::onConfigChanged);
        apply(RemoteConfig.get());
    }

    private static void onConfigChanged() {
        try {
            apply(RemoteConfig.get());
        } catch (Throwable t) {
            XposedCompat.log(TAG + "onConfigChanged failed: " + t);
        }
    }

    private static void apply(MoriConfig cfg) {
        boolean want = cfg != null && cfg.perfSwitch;
        if (!want) {
            if (sOn) {
                sOn = false;
                XposedCompat.log(TAG + "off");
            }
            return;
        }
        if (sOn) return;
        if (!sHooked && !hookTranslation()) return;
        sOn = true;
        sNoted = 0;
        for (int i = 0; i < sSeenW.length; i++) sSeenW[i] = 0;
        XposedCompat.log(TAG + "on：曲名和作品名跑马灯保持不动");
    }

    private static synchronized boolean hookTranslation() {
        if (sHooked) return true;
        boolean ok = XposedCompat.hookMethod(
                View.class, "setTranslationX",
                new Class<?>[]{float.class},
                TrackSwitchRelief::onTranslationX,
                TAG);
        if (!ok) {
            XposedCompat.log(TAG + "setTranslationX 没挂上，本次不启用");
            return false;
        }
        sHooked = true;
        XposedCompat.log(TAG + "hooked setTranslationX");
        return true;
    }

    private static Object onTranslationX(XposedInterface.Chain chain) throws Throwable {
        if (Boolean.TRUE.equals(SKIP.get())) return chain.proceed();
        if (!sOn) return chain.proceed();
        Object self = chain.getThisObject();
        if (!(self instanceof View) || !isMarquee((View) self)) return chain.proceed();
        View v = (View) self;
        note(v);
        if (v.getTranslationX() != 0f) {
            SKIP.set(Boolean.TRUE);
            try {
                v.setTranslationX(0f);
            } finally {
                SKIP.set(Boolean.FALSE);
            }
        }
        return null;
    }

    /**
     * 遮罩的直接子 View，又宽又扁，宽过屏宽。
     * 曲名（约 81px）和作品名（约 77px、α=0.7）都是这一形态。
     */
    private static boolean isMarquee(View v) {
        int h = v.getHeight();
        int w = v.getWidth();
        if (h < 36 || h > 200) return false;
        if (w < h * 8) return false;
        int screen = screenWidth(v);
        if (screen > 0 && w < screen + screen / 5) return false;
        String cn = v.getClass().getName();
        if (cn == null || !cn.endsWith("ReactViewGroup")) return false;
        ViewParent parent = v.getParent();
        if (!(parent instanceof View)) return false;
        String pn = ((View) parent).getClass().getName();
        return pn != null && pn.endsWith("RNCMaskedView");
    }

    private static int screenWidth(View v) {
        int w = sScreenW;
        if (w > 0) return w;
        try {
            w = v.getResources().getDisplayMetrics().widthPixels;
        } catch (Throwable ignored) {
            return 0;
        }
        if (w > 0) sScreenW = w;
        return w;
    }

    private static void note(View v) {
        int w = v.getWidth();
        int n = sNoted;
        for (int i = 0; i < n && i < sSeenW.length; i++) {
            if (sSeenW[i] == w) return;
        }
        if (n >= sSeenW.length) return;
        sSeenW[n] = w;
        sNoted = n + 1;
        XposedCompat.log(TAG + "hold " + w + "x" + v.getHeight()
                + " tx=" + v.getTranslationX());
    }
}
