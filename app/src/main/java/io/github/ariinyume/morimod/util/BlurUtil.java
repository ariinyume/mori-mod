package io.github.ariinyume.morimod.util;

import android.graphics.Bitmap;

/**
 * 背景模糊（v0.1.6，跑在**宿主进程**，所以只用 android.graphics，不依赖任何第三方库）。
 *
 * <h3>为什么不用 RenderEffect / RenderScript</h3>
 * <ul>
 *   <li>{@code RenderEffect} 要 API 31，本模块 minSdk 24；</li>
 *   <li>{@code RenderScript} 在 API 31 起 deprecated，部分 ROM 已裁掉 libRS；</li>
 *   <li>背景图是**静态**的，一次算完缓存住，根本不需要实时效果 —— 纯 CPU 就够，
 *       而且比每帧过一遍 GPU 更省电（用户选完就再也不算了）。</li>
 * </ul>
 *
 * <h3>做法</h3>
 * <ol>
 *   <li>按半径降采样（半径越大缩得越狠）—— 大半径下这一步省掉 90% 的像素量，
 *       视觉上完全看不出差别（模糊本身就是丢高频）；</li>
 *   <li>在缩放后的图上做 <b>四遍</b>可分离盒式模糊，<b>横竖各两遍</b>；</li>
 *   <li>用双线性放大回原尺寸，边缘自然平滑。</li>
 * </ol>
 *
 * <h3>⚠️ v0.1.20：为什么必须是「横竖各两遍」</h3>
 * 旧实现是 <b>横 → 竖 → 横</b>（H,V,H）。这<b>不是</b>「更均衡」，而是<b>最不均衡</b>：
 * 水平方向走了两个盒式核（近似三角核的平方，等效半径 ≈ 1.4×r），
 * 竖直方向只有一个（等效半径 = r）⇒ 横向糊得多、纵向糊得少。
 * 于是条带区域会出现<b>一条条水平方向的痕迹</b>（r25 用户反馈「模糊能看到一条条的痕迹」）。
 * ⇒ 现在固定 4 遍、横竖各 2 遍，两个方向的有效核完全一致。
 *
 * <p>复杂度 ≈ O(W·H / scale²)，一张 1440×3200 的壁纸在 pct=50 时约 20~60ms（一次性）。
 */
public final class BlurUtil {

    /** 半径占**短边**的比例上限（pct=100 时）。再大就纯色了，没有意义。 */
    private static final float MAX_RADIUS_RATIO = 0.10f;

    /**
     * 降采样倍数的上限。
     *
     * <p>旧实现是 {@code scale = radius / 3}，radius=43 时缩 14 倍、然后放大 14 倍 ——
     * 双线性放大 14 倍会露出明显的块，和横向条纹叠在一起就是用户看到的「痕迹」。
     * 收到 6 倍以内，放大损失可以忽略。
     */
    private static final int MAX_DOWNSCALE = 6;

    private BlurUtil() {
    }

    /**
     * 按强度模糊一张位图。
     *
     * @param src 原图（**不会被修改**）
     * @param pct 0–100；0 表示不处理，直接返回 src 本身
     * @return 模糊后的新位图；任何异常都退回 src（宁可没模糊，也不能让宿主崩）
     */
    public static Bitmap apply(Bitmap src, int pct) {
        if (src == null || src.isRecycled()) return src;
        int p = Math.max(0, Math.min(100, pct));
        if (p == 0) return src;

        int w = src.getWidth();
        int h = src.getHeight();
        if (w < 8 || h < 8) return src;

        int radius = Math.max(1, Math.round(Math.min(w, h) * MAX_RADIUS_RATIO * (p / 100f)));
        // 半径越大缩得越狠：缩几倍后半径也缩几倍，实际像素数少几倍，视觉等价。
        // 但缩太多再双线性放大 = 明显块状 ⇒ 封顶 MAX_DOWNSCALE。
        int scale = Math.max(1, Math.min(MAX_DOWNSCALE, Math.round(radius / 2f)));
        int sw = Math.max(2, w / scale);
        int sh = Math.max(2, h / scale);

        Bitmap small = null;
        try {
            small = (sw == w && sh == h) ? src : Bitmap.createScaledBitmap(src, sw, sh, true);
            if (small == null) return src;

            int bw = small.getWidth();
            int bh = small.getHeight();
            int[] pix = new int[bw * bh];
            small.getPixels(pix, 0, bw, 0, 0, bw, bh);

            int r = Math.max(1, radius / scale);
            // ★ 横竖各两遍（对称核）。旧实现 H,V,H 是横 2 遍竖 1 遍 ⇒ 横向糊过剩、
            //   纵向不足 ⇒ 条带区出现一条条水平痕迹（r25 用户反馈）。
            boxPass(pix, bw, bh, r, true);
            boxPass(pix, bw, bh, r, false);
            boxPass(pix, bw, bh, r, true);
            boxPass(pix, bw, bh, r, false);
            small.setPixels(pix, 0, bw, 0, 0, bw, bh);

            if (bw == w && bh == h) {
                Bitmap out = small;
                small = null;                   // 交给调用方，finally 不再回收
                return out;
            }
            Bitmap up = Bitmap.createScaledBitmap(small, w, h, true);
            return up != null ? up : src;
        } catch (Throwable t) {
            DiagLog.event("blur", "模糊失败 " + t.getClass().getSimpleName());
            return src;
        } finally {
            if (small != null && small != src && !small.isRecycled()) {
                try {
                    small.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 一遍可分离盒式模糊（水平或垂直），对 ARGB 四个通道一起做。
     *
     * <p>先把一行/列拷进临时数组再滑窗，写入时直接落到 pix —— 二者不重叠，所以不用双缓冲。
     * 边缘用**夹取**填充（clamp），不会出现黑边。
     */
    private static void boxPass(int[] pix, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h;
        int lines = horizontal ? h : w;
        if (len <= 1) return;
        int div = 2 * r + 1;
        int[] line = new int[len];

        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) {
                line[i] = horizontal ? pix[l * w + i] : pix[i * w + l];
            }
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = line[clamp(i, len)];
                sa += (c >>> 24) & 0xff;
                sr += (c >> 16) & 0xff;
                sg += (c >> 8) & 0xff;
                sb += c & 0xff;
            }
            for (int i = 0; i < len; i++) {
                int px = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                if (horizontal) {
                    pix[l * w + i] = px;
                } else {
                    pix[i * w + l] = px;
                }
                int out = line[clamp(i - r, len)];
                int in = line[clamp(i + r + 1, len)];
                sa += ((in >>> 24) & 0xff) - ((out >>> 24) & 0xff);
                sr += ((in >> 16) & 0xff) - ((out >> 16) & 0xff);
                sg += ((in >> 8) & 0xff) - ((out >> 8) & 0xff);
                sb += (in & 0xff) - (out & 0xff);
            }
        }
    }

    private static int clamp(int i, int len) {
        if (i < 0) return 0;
        if (i >= len) return len - 1;
        return i;
    }
}
