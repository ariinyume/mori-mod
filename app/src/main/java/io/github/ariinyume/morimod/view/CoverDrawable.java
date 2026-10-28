package io.github.ariinyume.morimod.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 「cover」语义的背景 Drawable：按 bounds 等比缩放并居中裁剪（不拉伸变形，BR-2.2），
 * 可选叠加半透明遮罩保证前景可读（BR-2.4 / NFR-C5）。
 *
 * <p>纯代码绘制，不引用本模块资源 —— 该对象在**宿主进程**中使用，不能依赖模块资源表。
 */
public final class CoverDrawable extends Drawable {

    private final Bitmap bitmap;
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint maskPaint = new Paint();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    public CoverDrawable(Bitmap bitmap, int maskAlphaPct) {
        this.bitmap = bitmap;
        int a = Math.max(0, Math.min(100, maskAlphaPct));
        maskPaint.setColor(Color.BLACK);
        maskPaint.setAlpha((int) (a / 100f * 255f));
    }

    /**
     * 位图还能不能用（v0.1.12：这个对象现在被缓存复用，位图可能已被回收）。
     *
     * @return true = 可以继续画
     */
    public boolean valid() {
        return bitmap != null && !bitmap.isRecycled();
    }

    @Override
    public void draw(Canvas canvas) {
        if (bitmap == null || bitmap.isRecycled()) return;
        Rect bounds = getBounds();
        int bw = bounds.width();
        int bh = bounds.height();
        int iw = bitmap.getWidth();
        int ih = bitmap.getHeight();
        if (bw <= 0 || bh <= 0 || iw <= 0 || ih <= 0) return;

        // center-crop：等比放大到刚好覆盖 bounds，再居中裁掉多余
        float scale = Math.max((float) bw / iw, (float) bh / ih);
        float dw = iw * scale;
        float dh = ih * scale;
        float left = bounds.left + (bw - dw) / 2f;
        float top = bounds.top + (bh - dh) / 2f;
        dst.set(left, top, left + dw, top + dh);
        src.set(0, 0, iw, ih);

        canvas.drawBitmap(bitmap, src, dst, bmpPaint);
        if (maskPaint.getAlpha() > 0) {
            canvas.drawRect(bounds, maskPaint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        bmpPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        bmpPaint.setColorFilter(cf);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
