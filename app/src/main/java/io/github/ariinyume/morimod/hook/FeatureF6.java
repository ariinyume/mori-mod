package io.github.ariinyume.morimod.hook;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.WeakHashMap;

import io.github.ariinyume.morimod.config.MoriConfig;
import io.github.ariinyume.morimod.config.RemoteConfig;
import io.github.ariinyume.morimod.util.XposedCompat;
import io.github.libxposed.api.XposedInterface;

/**
 * F6 · 播放页宿主字幕调整（字号 + 背景透明）。
 * <p>v0.2.11：放宽播放页判据；字号优先 {@code setTextSize}，失败时由
 * {@link #wrapDrawScale} 在 draw 时做 Canvas 缩放；背景透明登记到
 * {@link #shouldSkipHostBg} 跳过 RN BackgroundDrawable。
 */
public final class FeatureF6 {

    private static final String TAG = "[MoriMod:F6]";

    private static volatile boolean sHooked;
    private static final ThreadLocal<Boolean> SKIP =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final WeakHashMap<TextView, Float> sBasePx = new WeakHashMap<>();
    private static final WeakHashMap<TextView, Boolean> sSubs = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> sBgViews = new WeakHashMap<>();
    private static int sMissLogs;

    private FeatureF6() {
    }

    public static void install(ClassLoader cl) {
        if (sHooked) return;
        try {
            XposedCompat.hookMethod(
                    TextView.class, "setTextSize",
                    new Class<?>[]{int.class, float.class},
                    FeatureF6::aroundSetTextSize, TAG);
            XposedCompat.hookMethod(
                    TextView.class, "setTextSize",
                    new Class<?>[]{float.class},
                    FeatureF6::aroundSetTextSizeSp, TAG);
            XposedCompat.hookMethod(
                    TextView.class, "setText",
                    new Class<?>[]{CharSequence.class},
                    FeatureF6::afterSetText, TAG);
            sHooked = true;
            XposedCompat.log(TAG + " hooked TextView setTextSize/setText");
        } catch (Throwable t) {
            XposedCompat.log(TAG + " install failed: " + t);
        }
        RemoteConfig.addListener(() -> {
            try {
                reapplyAll();
            } catch (Throwable ignored) {
            }
        });
    }

    private static Object aroundSetTextSize(XposedInterface.Chain chain) throws Throwable {
        if (Boolean.TRUE.equals(SKIP.get())) return chain.proceed();
        Object self = chain.getThisObject();
        Object r = chain.proceed();
        if (self instanceof TextView) {
            try {
                maybeScale((TextView) self);
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    private static Object aroundSetTextSizeSp(XposedInterface.Chain chain) throws Throwable {
        if (Boolean.TRUE.equals(SKIP.get())) return chain.proceed();
        Object self = chain.getThisObject();
        Object r = chain.proceed();
        if (self instanceof TextView) {
            try {
                maybeScale((TextView) self);
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    private static Object afterSetText(XposedInterface.Chain chain) throws Throwable {
        Object r = chain.proceed();
        if (Boolean.TRUE.equals(SKIP.get())) return r;
        Object self = chain.getThisObject();
        if (self instanceof TextView) {
            try {
                maybeScale((TextView) self);
                applySubtitleBgPolicy((TextView) self);
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    /** 播放页 TextView.draw 前：强制字号 + 字幕底透明策略。 */
    static void onTextDraw(Canvas cv, TextView tv) {
        if (tv == null) return;
        try {
            maybeScale(tv);
            applySubtitleBgPolicy(tv);
        } catch (Throwable ignored) {
        }
    }

    /**
     * draw 时 Canvas 缩放兜底（RN 常在下一帧把 setTextSize 冲掉）。
     * @return true = 已缩放并 proceed，调用方勿再 proceed
     */
    static boolean wrapDrawScale(XposedInterface.Chain chain, TextView tv, Canvas cv)
            throws Throwable {
        if (tv == null || cv == null || !masterOn()) return false;
        if (!looksLikeHostSubtitle(tv)) return false;
        MoriConfig cfg = RemoteConfig.get();
        float scale = cfg != null ? cfg.f6SubtitleScale : 1f;
        if (scale < 1.05f) return false;
        // 已用 setTextSize 拉到目标附近则不必再 scale（避免双重放大）
        Float base = sBasePx.get(tv);
        if (base != null && base > 0f) {
            float want = base * scale;
            if (Math.abs(tv.getTextSize() - want) < 1.5f) return false;
        }
        float cx = tv.getWidth() * 0.5f;
        float cy = tv.getHeight() * 0.5f;
        int save = cv.save();
        try {
            cv.scale(scale, scale, cx, cy);
            chain.proceed();
            sSubs.put(tv, Boolean.TRUE);
            return true;
        } finally {
            try {
                cv.restoreToCount(save);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 在 BackgroundDrawable#draw 路径跳过字幕条底色。 */
    static boolean shouldSkipHostBg(View v) {
        if (v == null) return false;
        MoriConfig cfg = RemoteConfig.get();
        if (cfg == null || !cfg.f6Enabled || !cfg.f6SubtitleBgTransparent) return false;
        try {
            if (sBgViews.containsKey(v)) return true;
            if (v instanceof TextView && sSubs.containsKey(v) && looksLikeHostSubtitle((TextView) v)) {
                return true;
            }
            // 父链上若登记了字幕条，子层纯色底也跳过
            Object p = v.getParent();
            int guard = 0;
            while (p instanceof View && guard++ < 4) {
                View pv = (View) p;
                if (sBgViews.containsKey(pv)) return true;
                p = pv.getParent();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean masterOn() {
        MoriConfig cfg = RemoteConfig.get();
        return cfg != null && cfg.f6Enabled;
    }

    private static void maybeScale(TextView tv) {
        if (!looksLikeHostSubtitle(tv)) {
            restoreIfTracked(tv);
            return;
        }
        if (!masterOn()) {
            restoreIfTracked(tv);
            return;
        }
        MoriConfig cfg = RemoteConfig.get();
        float scale = cfg != null ? cfg.f6SubtitleScale : 1f;
        if (scale < 1.01f) {
            restoreIfTracked(tv);
            sSubs.put(tv, Boolean.TRUE);
            applySubtitleBgPolicy(tv);
            return;
        }
        float cur = tv.getTextSize();
        if (cur <= 0f) return;
        Float saved = sBasePx.get(tv);
        float base;
        if (saved != null && saved > 0f) {
            base = saved;
        } else {
            base = cur / scale;
            if (base < 8f) base = cur;
            sBasePx.put(tv, base);
        }
        float want = base * Math.max(1f, scale);
        if (Math.abs(cur - want) < 0.5f) {
            sSubs.put(tv, Boolean.TRUE);
            return;
        }
        SKIP.set(Boolean.TRUE);
        try {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, want);
            sSubs.put(tv, Boolean.TRUE);
            XposedCompat.log(TAG + " scale " + scale + " base=" + (int) base
                    + " -> " + (int) want + " n=" + TextShadow.textOf(tv));
        } finally {
            SKIP.set(Boolean.FALSE);
        }
    }

    private static void restoreIfTracked(TextView tv) {
        Float base = sBasePx.get(tv);
        if (base == null || base <= 0f) return;
        if (Math.abs(tv.getTextSize() - base) < 0.5f) {
            sBasePx.remove(tv);
            return;
        }
        SKIP.set(Boolean.TRUE);
        try {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, base);
        } catch (Throwable ignored) {
        } finally {
            SKIP.set(Boolean.FALSE);
            sBasePx.remove(tv);
        }
    }

    private static void reapplyAll() {
        for (TextView tv : new java.util.ArrayList<>(sSubs.keySet())) {
            if (tv == null) continue;
            try {
                maybeScale(tv);
                applySubtitleBgPolicy(tv);
                tv.invalidate();
            } catch (Throwable ignored) {
            }
        }
        for (View v : new java.util.ArrayList<>(sBgViews.keySet())) {
            if (v == null) continue;
            try {
                applyBgTransparent(v);
                v.invalidate();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 宿主播放页内嵌字幕行。
     * <p>v0.2.11：0.2.10 过严（必须 isPlayWindow∧位置带）导致整晚 0 次 scale；
     * 恢复 playContext / underPlayRoot，并放宽几何。
     */
    static boolean looksLikeHostSubtitle(TextView tv) {
        if (tv == null || !BgPeel.sArmed) return false;
        try {
            if (BgPeel.foreignModuleView(tv)) return false;
            boolean play = PlayPage.playContext(tv)
                    || PlayPage.isPlayWindow(tv)
                    || PlayPage.inPlayTree(tv)
                    || PlayPage.underPlayRoot(tv);
            if (!play) return false;
            CharSequence cs = tv.getText();
            if (cs == null) return false;
            String s = cs.toString().trim();
            if (s.length() < 2) return false;
            // 明显 UI / 曲名
            if (s.contains("附有字幕") || s.contains("分享设置") || s.contains("分享設定")
                    || s.equals("字幕") || s.contains("Settings")) {
                return false;
            }
            if (s.startsWith("音轨") || s.startsWith("音軌") || s.startsWith("Track ")) {
                return false;
            }
            if (s.startsWith("【") && s.length() > 24) return false;
            int[] loc = new int[2];
            tv.getLocationOnScreen(loc);
            int sh = BgPeel.sSH;
            int sw = BgPeel.sSW;
            if (sh <= 0 || sw <= 0) return false;
            // 封面区一带：约 10%–70%（真机字幕常压在封面中下）
            if (loc[1] < sh * 10 / 100 || loc[1] > sh * 70 / 100) {
                miss("y", tv, s);
                return false;
            }
            int w = Math.max(tv.getWidth(), tv.getMeasuredWidth());
            if (w > 0 && w * 100 < sw * 28) {
                miss("w", tv, s);
                return false;
            }
            float ts = tv.getTextSize();
            if (ts <= 0f) return false;
            // 过大字号多半是时长数字
            if (ts > sh * 0.09f) {
                miss("ts", tv, s);
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void miss(String why, TextView tv, String s) {
        if (!masterOn() || sMissLogs >= 12) return;
        // 只记可能是字幕的长句，避免刷屏
        if (s == null || s.length() < 6) return;
        sMissLogs++;
        XposedCompat.log(TAG + " miss " + why + " n=" + TextShadow.textOf(tv)
                + " ts=" + (int) tv.getTextSize());
    }

    private static void applySubtitleBgPolicy(TextView tv) {
        if (!looksLikeHostSubtitle(tv)) return;
        if (!masterOn()) return;
        View bar = findSubtitleBar(tv);
        if (bar != null) {
            sBgViews.put(bar, Boolean.TRUE);
            applyBgTransparent(bar);
        }
        sBgViews.put(tv, Boolean.TRUE);
        applyBgTransparent(tv);
        sSubs.put(tv, Boolean.TRUE);
    }

    private static View findSubtitleBar(TextView tv) {
        try {
            Object p = tv.getParent();
            int guard = 0;
            while (p instanceof View && guard++ < 8) {
                View v = (View) p;
                int w = v.getWidth();
                int h = v.getHeight();
                if (BgPeel.sSW > 0 && BgPeel.sSH > 0
                        && w * 100 >= (long) BgPeel.sSW * 40
                        && h > 0 && h * 100 < (long) BgPeel.sSH * 22
                        && h * 100 >= (long) BgPeel.sSH * 2) {
                    return v;
                }
                // 有实心底的通宽父层也算
                Drawable bg = v.getBackground();
                if (bg != null && w * 100 >= (long) BgPeel.sSW * 50
                        && h > 0 && h * 100 < (long) BgPeel.sSH * 20) {
                    return v;
                }
                p = v.getParent();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void applyBgTransparent(View v) {
        if (v == null) return;
        MoriConfig cfg = RemoteConfig.get();
        boolean wantClear = cfg != null && cfg.f6Enabled && cfg.f6SubtitleBgTransparent;
        try {
            if (wantClear) {
                Drawable cur = v.getBackground();
                if (cur != null && !(cur instanceof ColorDrawable
                        && ((ColorDrawable) cur).getColor() == Color.TRANSPARENT)) {
                    v.setBackground(new ColorDrawable(Color.TRANSPARENT));
                }
                if (v instanceof ViewGroup) {
                    muteBlurKids((ViewGroup) v);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void muteBlurKids(ViewGroup g) {
        try {
            int n = Math.min(g.getChildCount(), 8);
            for (int i = 0; i < n; i++) {
                View c = g.getChildAt(i);
                if (c == null) continue;
                String cn = c.getClass().getName();
                if (cn.contains("BlurView") || cn.contains("ExpoBlur")) {
                    if (c.getVisibility() != View.INVISIBLE) {
                        c.setVisibility(View.INVISIBLE);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
