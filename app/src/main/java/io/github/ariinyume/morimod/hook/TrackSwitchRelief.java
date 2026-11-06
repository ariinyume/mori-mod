package io.github.ariinyume.morimod.hook;

import android.view.View;
import android.view.ViewParent;

import io.github.ariinyume.morimod.config.MoriConfig;
import io.github.ariinyume.morimod.config.RemoteConfig;
import io.github.ariinyume.morimod.util.XposedCompat;
import io.github.libxposed.api.XposedInterface;

/**
 * 播放页曲名、作品名跑马灯保持不动（v0.1.105 / v0.1.122 加固）。
 *
 * <p>2.23.0 真机层级（PLK110，1272×2772）上这两条是同一结构，只是高度和透明度不同：
 * <pre>
 * RNCMaskedView 1153×81
 *   ReactViewGroup 3018×81          ← setTranslationX，曲名（两份 ReactTextView 首尾相接）
 * RNCMaskedView 1153×77
 *   ReactViewGroup 3832×77 α=0.7    ← setTranslationX，作品名
 * </pre>
 * 开关打开后，平移 / setX / scrollX / offsetLeftAndRight 一律丢掉，并在每次绘制时再钉一次，
 * 避免 native 动画漏网造成轻微抖动。关掉开关后下一次位移照常放行。
 */
public final class TrackSwitchRelief {

    private static final String TAG = "[MoriMod:Relief] ";

    private static volatile boolean sOn = false;
    private static volatile boolean sHooked = false;

    /** 自己把偏移拉回 0 时不要再被拦一次。 */
    private static final ThreadLocal<Boolean> SKIP =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static volatile int sScreenW = 0;
    private static final int[] sSeenW = new int[6];
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
        if (!sHooked && !hookAll()) return;
        sOn = true;
        sNoted = 0;
        for (int i = 0; i < sSeenW.length; i++) sSeenW[i] = 0;
        XposedCompat.log(TAG + "on：曲名和作品名跑马灯保持不动");
    }

    private static synchronized boolean hookAll() {
        if (sHooked) return true;
        boolean okTx = XposedCompat.hookMethod(
                View.class, "setTranslationX",
                new Class<?>[]{float.class},
                TrackSwitchRelief::onTranslationX,
                TAG);
        boolean okX = XposedCompat.hookMethod(
                View.class, "setX",
                new Class<?>[]{float.class},
                TrackSwitchRelief::onSetX,
                TAG);
        boolean okScroll = XposedCompat.hookMethod(
                View.class, "setScrollX",
                new Class<?>[]{int.class},
                TrackSwitchRelief::onScrollX,
                TAG);
        boolean okOff = XposedCompat.hookMethod(
                View.class, "offsetLeftAndRight",
                new Class<?>[]{int.class},
                TrackSwitchRelief::onOffsetLeftRight,
                TAG);
        if (!okTx) {
            XposedCompat.log(TAG + "setTranslationX 没挂上，本次不启用");
            return false;
        }
        sHooked = true;
        XposedCompat.log(TAG + "hooked setTranslationX"
                + (okX ? "+setX" : "")
                + (okScroll ? "+setScrollX" : "")
                + (okOff ? "+offsetLR" : ""));
        return true;
    }

    /**
     * 绘制热路径兜底：漏网的 native 位移在下一帧画之前钉回 0。
     */
    public static void pinFromDraw(View v) {
        if (!sOn || v == null) return;
        if (Boolean.TRUE.equals(SKIP.get())) return;
        if (!isMarquee(v)) return;
        freezeHard(v);
    }

    private static Object onTranslationX(XposedInterface.Chain chain) throws Throwable {
        if (Boolean.TRUE.equals(SKIP.get())) return chain.proceed();
        if (!sOn) return chain.proceed();
        Object self = chain.getThisObject();
        if (!(self instanceof View) || !isMarquee((View) self)) return chain.proceed();
        View v = (View) self;
        note(v, "tx");
        freezeHard(v);
        return null;
    }

    private static Object onSetX(XposedInterface.Chain chain) throws Throwable {
        if (Boolean.TRUE.equals(SKIP.get())) return chain.proceed();
        if (!sOn) return chain.proceed();
        Object self = chain.getThisObject();
        if (!(self instanceof View) || !isMarquee((View) self)) return chain.proceed();
        View v = (View) self;
        note(v, "x");
        freezeHard(v);
        return null;
    }

    private static Object onScrollX(XposedInterface.Chain chain) throws Throwable {
        if (Boolean.TRUE.equals(SKIP.get())) return chain.proceed();
        if (!sOn) return chain.proceed();
        Object self = chain.getThisObject();
        if (!(self instanceof View) || !isMarquee((View) self)) return chain.proceed();
        View v = (View) self;
        note(v, "sx");
        freezeHard(v);
        return null;
    }

    private static Object onOffsetLeftRight(XposedInterface.Chain chain) throws Throwable {
        if (Boolean.TRUE.equals(SKIP.get())) return chain.proceed();
        if (!sOn) return chain.proceed();
        Object self = chain.getThisObject();
        if (!(self instanceof View) || !isMarquee((View) self)) return chain.proceed();
        Object arg = chain.getArg(0);
        if (arg instanceof Integer && ((Integer) arg) == 0) return chain.proceed();
        note((View) self, "olr");
        freezeHard((View) self);
        return null;
    }

    private static void freezeHard(View v) {
        SKIP.set(Boolean.TRUE);
        try {
            if (v.getTranslationX() != 0f) v.setTranslationX(0f);
            if (v.getScrollX() != 0) v.setScrollX(0);
            try {
                // 清掉可能还在跑的属性动画，避免下一帧又推一把。
                v.animate().cancel();
            } catch (Throwable ignored) {
            }
        } finally {
            SKIP.set(Boolean.FALSE);
        }
    }

    /**
     * 遮罩的直接子 View，又宽又扁。
     * 优先认 RNCMaskedView 父节点（不必等布局到两倍屏宽），减少开滚那几帧漏网。
     */
    private static boolean isMarquee(View v) {
        int h = v.getHeight();
        int w = v.getWidth();
        if (h < 36 || h > 220) return false;
        if (w < h * 4) return false;
        String cn = v.getClass().getName();
        if (cn == null) return false;
        // 只认 RN 文本行，别误伤首页横向滚动的 ReactViewGroup 长条。
        boolean reactRow = cn.endsWith("ReactViewGroup") || cn.contains("ReactText");
        if (!reactRow) return false;
        ViewParent parent = v.getParent();
        if (parent instanceof View) {
            View p = (View) parent;
            String pn = p.getClass().getName();
            if (pn != null && pn.endsWith("RNCMaskedView")) {
                int pw = p.getWidth();
                // 比遮罩更宽 = 跑马灯内容条；遮罩未量完时也先按扁条钉住。
                if (pw <= 0 || w > pw + 8) return true;
            }
        }
        // 无 MaskedView 时仍要求明显长于两屏，避免钉死普通横向列表内容行。
        int screen = screenWidth(v);
        return screen > 0 && w >= screen * 2 && w >= h * 8;
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

    private static void note(View v, String via) {
        int w = v.getWidth();
        int n = sNoted;
        for (int i = 0; i < n && i < sSeenW.length; i++) {
            if (sSeenW[i] == w) return;
        }
        if (n >= sSeenW.length) return;
        sSeenW[n] = w;
        sNoted = n + 1;
        XposedCompat.log(TAG + "hold " + via + " " + w + "x" + v.getHeight()
                + " tx=" + v.getTranslationX());
    }
}
