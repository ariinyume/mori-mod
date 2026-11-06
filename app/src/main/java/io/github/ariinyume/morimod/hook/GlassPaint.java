package io.github.ariinyume.morimod.hook;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * ★★★ v0.1.41（r12 路径 A）：<b>只换「颜料」，不接管绘制</b>。
 *
 * <h3>为什么要推倒重来</h3>
 * v0.1.31 ~ v0.1.40 的玻璃一直是这套做法：拦住 {@code View#draw} /
 * {@code Drawable#draw}，自己 {@code drawBitmap} 一张<b>整屏</b>壁纸，然后
 * {@code return true} 跳过宿主原来的绘制。
 *
 * <p>现在改成：反射取宿主 {@code BackgroundDrawable} 里那支 {@code Paint}
 * （dex 实证：2.22.0 上是 {@code k:Paint}，整个类只有这一个 Paint 字段），
 * 给它 {@code setShader(BitmapShader)}，然后 <b>{@code chain.proceed()} 让宿主自己画</b>。
 *
 * <p>★★★ v0.1.111：液态玻璃升级 —— 可选 {@link LiquidGlass#compose} 叠高光 +
 * {@link LiquidGlass#refraction} 边缘微放大。失败一律退回纯 BitmapShader。
 */
final class GlassPaint {

    private GlassPaint() {
    }

    /** 一次「换颜料」的凭据：画完必须还原，否则这支 Paint 会带着 shader 去画别的东西。 */
    static final class Token {
        final Paint paint;
        final Shader prevShader;
        final boolean prevFilter;
        final int prevAlpha;

        Token(Paint p) {
            paint = p;
            prevShader = p.getShader();
            prevFilter = p.isFilterBitmap();
            prevAlpha = p.getAlpha();
        }
    }

    /** 类 → 那支 Paint 字段（热路径不做反射扫描）。值是 Field 或 {@link #NONE}。 */
    private static final java.util.Map<Class<?>, Object> PAINT_FIELD =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final Object NONE = new Object();

    /** 复用的 Matrix（draw 都在 UI 线程）。 */
    private static final ThreadLocal<Matrix> MAT = ThreadLocal.withInitial(Matrix::new);
    private static final ThreadLocal<int[]> LOC = ThreadLocal.withInitial(() -> new int[2]);
    private static final ThreadLocal<RectF> PANEL = ThreadLocal.withInitial(RectF::new);

    /**
     * 找「这个背景 drawable 用哪支 Paint 落笔」。
     *
     * <p>按<b>类型</b>找，不看名字 —— release 包 R8 把字段名混淆成了 {@code k}。
     * 多个 Paint 字段时取第一个：2.22.0 的 {@code BackgroundDrawable} 只有一个。
     */
    private static java.lang.reflect.Field paintField(Class<?> cls) {
        Object cached = PAINT_FIELD.get(cls);
        if (cached != null) return cached == NONE ? null : (java.lang.reflect.Field) cached;
        java.lang.reflect.Field hit = null;
        try {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (f.getType() != Paint.class) continue;
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                hit = f;
                break;
            }
        } catch (Throwable t) {
            hit = null;
        }
        PAINT_FIELD.put(cls, hit == null ? NONE : hit);
        return hit;
    }

    /**
     * 把这块 drawable 的颜料换成 {@code blur}（一张已经糊好的整屏壁纸）。
     *
     * @param screenW 本窗口宽（v0.1.98 起是<b>窗口</b>尺寸，不再是屏幕尺寸）
     * @param screenH 本窗口高
     * @return null = 这条路用不上（找不到 Paint / 位图不可用），调用方照旧
     */
    static Token begin(Drawable d, View v, Rect b, Bitmap blur, int screenW, int screenH) {
        return begin(d, v, b, blur, screenW, screenH, false, false, 1f, LiquidGlass.Mode.REGULAR);
    }

    /**
     * 液态玻璃入口（v0.1.111）。
     *
     * @param liquid     是否启用折射 + 可选高光
     * @param specular   是否叠镜面高光（低端机可关）
     * @param refraction 折射系数；≤1.001 跳过
     * @param mode       REGULAR / CLEAR（影响高光强度）
     */
    static Token begin(Drawable d, View v, Rect b, Bitmap blur, int screenW, int screenH,
                       boolean liquid, boolean specular, float refraction,
                       LiquidGlass.Mode mode) {
        if (d == null || b == null || b.isEmpty()) return null;
        if (blur == null || blur.isRecycled() || blur.getWidth() <= 0 || blur.getHeight() <= 0) {
            return null;
        }
        if (screenW <= 0 || screenH <= 0) return null;
        java.lang.reflect.Field f = paintField(d.getClass());
        if (f == null) return null;
        Paint p;
        try {
            Object o = f.get(d);
            if (!(o instanceof Paint)) return null;
            p = (Paint) o;
        } catch (Throwable t) {
            return null;
        }
        BitmapShader sh = shader(blur, v, b, screenW, screenH,
                liquid ? refraction : 1f);
        if (sh == null) return null;
        Shader finalShader = sh;
        if (liquid && specular) {
            RectF panel = PANEL.get();
            panel.set(b);
            Shader composed = LiquidGlass.compose(sh, panel, mode);
            if (composed != null) finalShader = composed;
        }
        Token tk = new Token(p);
        try {
            p.setShader(finalShader);
            // BitmapShader 的插值由 Paint 的 filter 决定：不打开会有明显的马赛克方块。
            p.setFilterBitmap(true);
            // ★★★ v0.1.42（r13）：**别让 alpha 把玻璃吃掉**。
            if (tk.prevAlpha < 200) p.setAlpha(255);
        } catch (Throwable t) {
            end(tk);
            return null;
        }
        return tk;
    }

    /** 还原（必须放在 finally 里）。 */
    static void end(Token tk) {
        if (tk == null) return;
        try {
            tk.paint.setShader(tk.prevShader);
            tk.paint.setFilterBitmap(tk.prevFilter);
            tk.paint.setAlpha(tk.prevAlpha);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 建 shader，并把它的坐标系对齐到「这块 drawable 在<b>窗口</b>里占的那一格」。
     *
     * <p>★★★ v0.1.98（浮窗割裂 / 移位）：对齐基准从「屏幕」改成「窗口」。
     * <p>★★★ v0.1.111：可选折射（面板中心微放大）。
     */
    private static BitmapShader shader(Bitmap bmp, View v, Rect b, int sw, int sh,
                                       float refractionK) {
        int bw = bmp.getWidth();
        int bh = bmp.getHeight();
        // 与 coverRect 同一套算法：等比放大到刚好盖住整屏，再居中。
        float scale = Math.max((float) sw / bw, (float) sh / bh);
        float offX = (sw - bw * scale) / 2f;
        float offY = (sh - bh * scale) / 2f;
        float vx = 0f;
        float vy = 0f;
        try {
            int[] loc = LOC.get();
            // ★★★ v0.1.98：窗口坐标（不再是屏幕坐标），理由见方法头注释。
            stableWindowLoc(v, loc);
            vx = loc[0];
            vy = loc[1];
        } catch (Throwable ignored) {
        }
        try {
            Matrix m = MAT.get();
            m.reset();
            m.setScale(scale, scale);
            m.postTranslate(offX - vx - b.left, offY - vy - b.top);
            if (refractionK > 1.001f) {
                RectF panel = PANEL.get();
                panel.set(b);
                LiquidGlass.refraction(m, panel, refractionK);
            }
            BitmapShader s = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            s.setLocalMatrix(m);
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 面板在<b>本窗口内</b>的「布局终态」位置（去掉 translation / 滑入动画）。
     *
     * <p>★★★ v0.1.98：从 {@code getLocationOnScreen} 换成 {@code getLocationInWindow}。
     */
    static void stableWindowLoc(View v, int[] out) {
        v.getLocationInWindow(out);
        float tx = 0f;
        float ty = 0f;
        try {
            View cur = v;
            for (int i = 0; i < 14 && cur != null; i++) {
                tx += cur.getTranslationX();
                ty += cur.getTranslationY();
                Object p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
            }
        } catch (Throwable ignored) {
        }
        out[0] -= Math.round(tx);
        out[1] -= Math.round(ty);
    }

    /** 配置变了（换壁纸 / 关功能）⇒ 丢掉字段缓存（Paint 对象本身不变，只是重新找一遍）。 */
    static void reset() {
        PAINT_FIELD.clear();
    }
}
