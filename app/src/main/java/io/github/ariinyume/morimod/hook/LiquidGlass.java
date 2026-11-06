package io.github.ariinyume.morimod.hook;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * ★★★ 液态玻璃光学内核（方案 §五）：纯计算、无状态。
 *
 * <p>能烘进位图的一律烘（饱和度 / tint LUT / 噪点）；高光用 {@link #compose}
 * 在面板局部坐标合成；折射只靠 {@link Bitmap} shader 的 {@code localMatrix}。
 *
 * <p>第一版 tint 用 §2.6.2 固定 0.66 档（不做按背景自适应，见 §2.7.3）。
 */
final class LiquidGlass {

    /** 对齐宿主 {@code glassEffectStyle}。 */
    enum Mode {
        REGULAR,
        CLEAR;

        static Mode of(String s) {
            if (s != null && s.equalsIgnoreCase("clear")) return CLEAR;
            return REGULAR;
        }
    }

    /** REGULAR 默认模糊强度（%），对齐现有 {@code GLASS_BLUR_PCT}。 */
    static final int BLUR_REGULAR = 38;
    /** CLEAR 档更轻。 */
    static final int BLUR_CLEAR = 18;
    /** 降采样：1/3 分辨率（方案 S2）。 */
    private static final int DOWNSCALE = 3;
    /** 饱和度提升（宁小勿大，§五 #2）。 */
    private static final float SATURATION = 1.30f;
    /** 折射放大系数（面板中心）。 */
    static final float REFRACTION_K = 1.03f;
    /** 噪点 tile 边长。 */
    private static final int NOISE_TILE = 8;
    /** 噪点叠加强度。 */
    private static final int NOISE_ALPHA = 5; // ≈ α 0.02

    /** Rec.709 亮度 LUT（0..255 → 重映射后的通道倍率用不出，直接输出亮度目标）。 */
    private static final int[] LUT_REGULAR = buildLut(0.66f, 23f);
    private static final int[] LUT_CLEAR = buildLut(0.90f, 0f);

    private LiquidGlass() {
    }

    /**
     * 一次性：模糊 → 降采样 → 饱和度 → tint LUT → 噪点。
     *
     * @param src     清晰壁纸（不会被修改）
     * @param mode    REGULAR / CLEAR
     * @param blurPct 0–100；≤0 用档位默认
     * @param tintPct 压暗强度 0–100；100 = 完全不压暗（最透）；默认 66 对齐 0.66
     * @return 新位图；失败返回 null（调用方退回旧磨砂路径）
     */
    static Bitmap bake(Bitmap src, Mode mode, int blurPct, int tintPct) {
        if (src == null || src.isRecycled()) return null;
        if (mode == null) mode = Mode.REGULAR;
        int blur = blurPct > 0 ? clamp(blurPct, 0, 100)
                : (mode == Mode.CLEAR ? BLUR_CLEAR : BLUR_REGULAR);
        int tint = clamp(tintPct, 0, 100);

        Bitmap blurred = null;
        Bitmap small = null;
        Bitmap out = null;
        try {
            blurred = io.github.ariinyume.morimod.util.BlurUtil.apply(src, blur);
            if (blurred == null || blurred.isRecycled() || blurred == src) return null;

            int w = Math.max(2, blurred.getWidth() / DOWNSCALE);
            int h = Math.max(2, blurred.getHeight() / DOWNSCALE);
            small = Bitmap.createScaledBitmap(blurred, w, h, true);
            if (small == null || small.isRecycled()) return null;

            // 饱和度：画到可变位图上（ColorMatrix 只影响绘制，不改原像素）
            out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint sat = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(SATURATION);
            sat.setColorFilter(new ColorMatrixColorFilter(cm));
            c.drawBitmap(small, 0, 0, sat);

            applyTintLut(out, mode, tint);
            applyNoise(out);
            return out;
        } catch (Throwable t) {
            if (out != null && !out.isRecycled()) {
                try {
                    out.recycle();
                } catch (Throwable ignored) {
                }
            }
            return null;
        } finally {
            // blurred 可能就是 BlurUtil 新建的，也可能（异常路径）是 src —— 只回收新建的
            if (blurred != null && blurred != src && !blurred.isRecycled()) {
                try {
                    blurred.recycle();
                } catch (Throwable ignored) {
                }
            }
            if (small != null && small != out && !small.isRecycled()) {
                try {
                    small.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 把 backdrop shader 与面板局部 specular 合成一支。
     * 失败（个别 ROM 的 ComposeShader + 硬件画布）⇒ 退回 backdrop。
     */
    static Shader compose(Shader backdrop, RectF panel, Mode mode) {
        if (backdrop == null) return null;
        if (panel == null || panel.isEmpty()) return backdrop;
        try {
            Shader spec = specular(panel, mode == null ? Mode.REGULAR : mode);
            if (spec == null) return backdrop;
            return new android.graphics.ComposeShader(
                    backdrop, spec, PorterDuff.Mode.SRC_OVER);
        } catch (Throwable t) {
            return backdrop;
        }
    }

    /** 斜向镜面高光 + 顶部内高光（面板局部坐标）。 */
    static Shader specular(RectF panel, Mode mode) {
        if (panel == null || panel.isEmpty()) return null;
        float l = panel.left;
        float top = panel.top;
        float r = panel.right;
        float bot = panel.bottom;
        // CLEAR 档高光更弱一点，避免发灰
        int peak = mode == Mode.CLEAR ? 0x26FFFFFF : 0x42FFFFFF; // α≈0.15 / 0.26
        try {
            return new LinearGradient(
                    l, top, r, bot,
                    new int[]{peak, 0x00FFFFFF, 0x14FFFFFF, 0x00000000},
                    new float[]{0f, 0.35f, 0.55f, 1f},
                    Shader.TileMode.CLAMP);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 在已有 cover 缩放矩阵末尾叠「以面板中心 ×k」折射。
     * {@code panel} 是 drawable bounds（局部坐标）。
     */
    static void refraction(Matrix m, RectF panel, float k) {
        if (m == null || panel == null || panel.isEmpty()) return;
        if (k <= 1.001f) return;
        float cx = panel.centerX();
        float cy = panel.centerY();
        m.postScale(k, k, cx, cy);
    }

    // ── 内部 ───────────────────────────────────────────────────────

    /**
     * §2.6.2：{@code out = factor·bg + whiteBoost·(1 − min(L,90)/90)}。
     * {@code tintPct}：100 = 不压暗（factor→1、boost→0）；66 = 实测档。
     */
    private static int[] buildLut(float factor, float whiteBoost) {
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            float boost = whiteBoost * (1f - Math.min(i, 90f) / 90f);
            int o = Math.round(factor * i + boost);
            if (o < 0) o = 0;
            if (o > 255) o = 255;
            lut[i] = o;
        }
        return lut;
    }

    /** 按用户 tintPct 在「恒等」与「档位 LUT」之间插值后套到像素。 */
    private static void applyTintLut(Bitmap bmp, Mode mode, int tintPct) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pix = new int[w * h];
        bmp.getPixels(pix, 0, w, 0, 0, w, h);
        int[] base = mode == Mode.CLEAR ? LUT_CLEAR : LUT_REGULAR;
        // tintPct=100 → 恒等；tintPct=66 → 满档 LUT；线性插到目标
        float t = (100 - tintPct) / 34f; // 66→1.0，100→0，0→≈2.9 再夹紧
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            lut[i] = Math.round(i + (base[i] - i) * t);
        }
        for (int i = 0; i < pix.length; i++) {
            int c = pix[i];
            int a = (c >>> 24) & 0xff;
            int r = (c >> 16) & 0xff;
            int g = (c >> 8) & 0xff;
            int b = c & 0xff;
            // Rec.709 亮度 → 各通道按「目标亮度 / 原亮度」等比缩放，保留色相
            int L = (int) (0.2126f * r + 0.7152f * g + 0.0722f * b + 0.5f);
            if (L < 0) L = 0;
            if (L > 255) L = 255;
            int target = lut[L];
            if (L == 0) {
                r = g = b = target;
            } else {
                float s = target / (float) L;
                r = clamp((int) (r * s + 0.5f), 0, 255);
                g = clamp((int) (g * s + 0.5f), 0, 255);
                b = clamp((int) (b * s + 0.5f), 0, 255);
            }
            pix[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        bmp.setPixels(pix, 0, w, 0, 0, w, h);
    }

    /** 8×8 噪点 tile，α≈0.02，抗色带。 */
    private static void applyNoise(Bitmap bmp) {
        int tw = NOISE_TILE;
        Bitmap tile = Bitmap.createBitmap(tw, tw, Bitmap.Config.ARGB_8888);
        int[] n = new int[tw * tw];
        // 固定种子，避免每帧闪
        int seed = 0x4D6F7269; // 'Mori'
        for (int i = 0; i < n.length; i++) {
            seed = seed * 1103515245 + 12345;
            int v = ((seed >>> 16) & 0xff);
            n[i] = (NOISE_ALPHA << 24) | (v << 16) | (v << 8) | v;
        }
        tile.setPixels(n, 0, tw, 0, 0, tw, tw);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint();
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        for (int y = 0; y < h; y += tw) {
            for (int x = 0; x < w; x += tw) {
                c.drawBitmap(tile, x, y, p);
            }
        }
        tile.recycle();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
