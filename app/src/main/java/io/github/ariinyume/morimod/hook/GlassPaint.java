package io.github.ariinyume.morimod.hook;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
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
 * <p>这条路在「面板跑在独立 Dialog 窗口」时有三处不可控：
 * <ol>
 *   <li><b>坐标</b>：位置只由 {@code coverRect(bmp, -loc[0], -loc[1])} 决定，而 {@code loc}
 *       取自 {@code getLocationInWindow()} —— Dialog 窗口原点 ≠ 屏幕原点
 *       （r12 日志：面板 {@code top=280}）⇒ 整块位图被推离面板；</li>
 *   <li><b>裁剪</b>：{@code clipRect(bounds)} 一裁，偏移后的位图<b>一个像素都不剩</b>；</li>
 *   <li><b>静默</b>：{@code Canvas#drawBitmap} 画在裁剪区外<b>不抛异常</b> ⇒
 *       {@code drew = true} ⇒ 上层认为成功 ⇒ <b>面板整块消失</b>
 *       （用户第 4 / 9 条的真相：消失的不是宿主界面，是面板自己不透明的底）。</li>
 * </ol>
 *
 * <p>现在改成：反射取宿主 {@code BackgroundDrawable} 里那支 {@code Paint}
 * （dex 实证：2.22.0 上是 {@code k:Paint}，整个类只有这一个 Paint 字段），
 * 给它 {@code setShader(BitmapShader)}，然后 <b>{@code chain.proceed()} 让宿主自己画</b>。
 *
 * <p>好处是结构性的，不是调参调出来的：
 * <ul>
 *   <li>不跳过宿主绘制 ⇒ <b>不可能</b>再出现「面板消失」，最差也只是模糊图错位；</li>
 *   <li>圆角由宿主自己的 {@code j:Path} 完成（dex 证明 {@code BackgroundDrawable}
 *       <b>没有</b> {@code float[]}，圆角在 Path 里）⇒ 第 5 条后半自动解决；</li>
 *   <li>子内容与滚动完全交给 RN ⇒ 第 3 条「卡住一块壁纸」不再可能；</li>
 *   <li>每块面板只有一处颜料 ⇒ 天然统一（第 6 条），也不会「分层出现」。</li>
 * </ul>
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
     * @return null = 这条路用不上（找不到 Paint / 位图不可用），调用方照旧
     */
    static Token begin(Drawable d, View v, Rect b, Bitmap blur, int screenW, int screenH) {
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
        BitmapShader sh = shader(blur, v, b, screenW, screenH);
        if (sh == null) return null;
        Token tk = new Token(p);
        try {
            p.setShader(sh);
            // BitmapShader 的插值由 Paint 的 filter 决定：不打开会有明显的马赛克方块。
            p.setFilterBitmap(true);
            // ★★★ v0.1.42（r13）：**别让 alpha 把玻璃吃掉**。
            //   RN 的 BackgroundDrawable 是拿同一支 Paint 反复用的，某些帧它的
            //   alpha 会被压到很低（压暗/淡入过渡）。shader 一样受 alpha 调制 ⇒
            //   玻璃被画成近乎透明 ⇒ 面板看起来「和背景融为一体 / 还是深色」。
            //   只在 alpha 明显偏低时抬到不透明；本来半透明的面板保持原样。
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
     * 建 shader，并把它的坐标系对齐到「这块 drawable 在屏幕上占的那一格」。
     *
     * <p>对齐只用 {@code getLocationOnScreen()} —— 它把窗口偏移也算进去，
     * Dialog / Popup 里也准（{@code getLocationInWindow} 在 Dialog 里原点不是屏幕原点，
     * 这正是 v0.1.40 之前玻璃画到屏幕外的根因）。
     *
     * <p>Matrix 是「先 cover 缩放，再平移到 (屏幕位置 − 本块位置)」：
     * 本地点 {@code (b.left, b.top)} 恰好落在壁纸的 {@code (offX, offY)} 上 ⇒
     * 透过玻璃看到的就是<b>这块面板背后那一片</b>壁纸，与页面背景严丝合缝。
     */
    private static BitmapShader shader(Bitmap bmp, View v, Rect b, int sw, int sh) {
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
            // ★★★ v0.1.43（r14 用户第 2 / 3 条）：**用「去掉位移动画」后的稳定屏幕坐标**。
            //
            //   r14 日志铁证：同一块面板同一秒里反复出现
            //     glassA … scr=0,5454 / 5011 / 4709   ← getLocationOnScreen 含动画
            //     glassA … top=2286                   ← getLocationInWindow 已在终态
            //   屏高只有 3168 ⇒ scrY>屏高时 BitmapShader 落到 CLAMP 边缘
            //   （壁纸底边深色像素）⇒ 用户看到「先深色底，再变成壁纸+模糊」。
            //   有的面板窗口停在「贴底偏移」态，scrY 一直越界 ⇒「背后一直是深色底」。
            //
            //   做法：屏幕坐标减去自身到根的 translationX/Y 累计 —— RN Modal /
            //   BottomSheet 的滑入几乎都走 translationY；去掉之后得到布局终态位置，
            //   第一帧玻璃就对准壁纸，不再闪深色。
            stableScreenLoc(v, loc, sw, sh);
            vx = loc[0];
            vy = loc[1];
        } catch (Throwable ignored) {
        }
        try {
            Matrix m = MAT.get();
            m.reset();
            m.setScale(scale, scale);
            m.postTranslate(offX - vx - b.left, offY - vy - b.top);
            BitmapShader s = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            s.setLocalMatrix(m);
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 面板在屏幕上的「布局终态」位置（去掉 translation / 窗口位移动画）。
     *
     * @param sw 真屏宽（BgPeel 传入）
     * @param sh 真屏高
     */
    static void stableScreenLoc(View v, int[] out, int sw, int sh) {
        v.getLocationOnScreen(out);
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
        // 窗口整体被 WindowManager 平移时，translation 链上读不到 ——
        // getLocationOnScreen 仍越界。改用 window 坐标 +「终态窗口原点」：
        //   · 近全屏 Dialog（rootH ≥ 90% 屏高）终态原点 = (0, 0)
        //   · 矮窗口终态贴底原点 = (0, sh - rootH)
        if (sh > 0 && (out[1] < -8 || out[1] >= sh - 4 || out[0] < -8 || out[0] >= sw - 4)) {
            try {
                View root = v.getRootView();
                if (root == null) return;
                int rh = root.getHeight();
                int rw = root.getWidth();
                if (rh <= 0) return;
                int[] inWin = new int[2];
                v.getLocationInWindow(inWin);
                int rootY;
                if (rh * 100 >= sh * 90) {
                    rootY = 0;
                } else {
                    rootY = Math.max(0, sh - rh);
                }
                int rootX = (rw > 0 && rw * 100 < sw * 90) ? Math.max(0, (sw - rw) / 2) : 0;
                out[0] = rootX + inWin[0];
                out[1] = rootY + inWin[1];
            } catch (Throwable ignored) {
            }
        }
    }

    /** 配置变了（换壁纸 / 关功能）⇒ 丢掉字段缓存（Paint 对象本身不变，只是重新找一遍）。 */
    static void reset() {
        PAINT_FIELD.clear();
    }
}
