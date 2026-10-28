package io.github.ariinyume.morimod.util;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * 读「系统当前桌面壁纸」（v0.1.10）。
 *
 * <h3>为什么要读桌面壁纸，而不是把窗口设透明</h3>
 * 用户对「全透明背景」的定义是**宿主背景变成自己的桌面壁纸**。真正的透明窗口
 * 做不到 —— 宿主的 window 在 manifest 里不是 {@code translucent}，运行时改不了，
 * 把 DecorView 背景设成透明只会露出白色/黑色（v0.1.9 真机就是「整屏发白」）。
 *
 * <p>所以这里直接取系统壁纸位图，当成普通背景图铺进宿主：视觉上等价于
 * 「宿主背景 = 桌面」，但完全不依赖窗口层级、不碰宿主的任何资源。
 *
 * <h3>兼容与降级</h3>
 * <ul>
 *   <li>三家取法依次尝试：{@code getDrawable()} → {@code getFastDrawable()} →
 *       {@code getBitmap()}（API 27+）。任一成功即返回。</li>
 *   <li>纯色壁纸会拿到 {@code ColorDrawable}，也照常转成位图（就是一片同色，正确结果）。</li>
 *   <li>全部失败返回 {@code null}：调用方**回落宿主原背景**，绝不画一片黑/白。</li>
 *   <li>成功结果缓存在静态字段（一张壁纸通常几 MB）；配置变更时由
 *       {@code FeatureF2.invalidate()} 清掉。失败**不缓存**，但带 2 秒冷却，
 *       避免在渲染热路径上反复抛异常。</li>
 * </ul>
 */
public final class WallpaperBg {

    private static final String TAG = "[MoriMod:Wall]";

    /** 转成位图时的短边上限（再大只是浪费内存，屏幕上根本看不出）。 */
    private static final int MAX_SIDE = 2400;

    private static volatile Bitmap sBmp;
    private static volatile long sNextTryAt;
    private static volatile boolean sFailLogged;

    private WallpaperBg() {
    }

    /**
     * 取当前桌面壁纸（可能是缓存的同一张）。宿主进程用这个。
     *
     * @return 壁纸位图；读不到返回 {@code null}（调用方必须按「没有壁纸」处理）
     */
    public static Bitmap get() {
        return get(AppEnv.app());
    }

    /**
     * 用指定上下文读壁纸（设置页进程也要读一次，好在按钮上给出「读不到」的即时反馈）。
     *
     * @param ctx 上下文；{@code null} 直接返回 null
     */
    public static Bitmap get(Context ctx) {
        Bitmap cached = sBmp;
        if (cached != null && !cached.isRecycled()) return cached;

        long now = System.currentTimeMillis();
        if (now < sNextTryAt) return null;
        if (ctx == null) {
            sNextTryAt = now + 2000L;
            return null;
        }

        Bitmap b = read(ctx);
        if (b == null) {
            sNextTryAt = System.currentTimeMillis() + 2000L;
            if (!sFailLogged) {
                sFailLogged = true;
                XposedCompat.log(TAG + " 读不到桌面壁纸，本轮回落宿主背景");
            }
            return null;
        }
        sBmp = b;
        sFailLogged = false;
        XposedCompat.log(TAG + " 壁纸已读到 " + b.getWidth() + "x" + b.getHeight());
        return b;
    }

    /** 配置变更 / 用户换了壁纸：下次重新读。 */
    public static void invalidate() {
        sBmp = null;
        sNextTryAt = 0L;
        sFailLogged = false;
    }

    private static Bitmap read(Context ctx) {
        WallpaperManager wm;
        try {
            wm = WallpaperManager.getInstance(ctx);
        } catch (Throwable t) {
            XposedCompat.log(TAG + " getInstance 失败: " + t);
            return null;
        }
        if (wm == null) {
            XposedCompat.log(TAG + " WallpaperManager == null");
            return null;
        }

        // 每一步都留痕：这个 ROM 到底卡在哪一条，只有日志能说清（不要靠猜）。
        String why = "";
        Drawable d = null;
        try {
            d = wm.getDrawable();
            if (d == null) why += "drawable=null;";
        } catch (Throwable t) {
            why += "drawable×" + t.getClass().getSimpleName() + ";";
        }
        if (d == null) {
            try {
                d = wm.getFastDrawable();
                if (d == null) why += "fast=null;";
            } catch (Throwable t) {
                why += "fast×" + t.getClass().getSimpleName() + ";";
            }
        }
        if (d instanceof BitmapDrawable) {
            Bitmap raw = ((BitmapDrawable) d).getBitmap();
            if (raw != null && !raw.isRecycled()) {
                XposedCompat.log(TAG + " 路径=getDrawable/BitmapDrawable");
                return raw;
            }
        }
        if (d != null) {
            Bitmap made = toBitmap(d);
            if (made != null) {
                XposedCompat.log(TAG + " 路径=" + d.getClass().getSimpleName() + "→canvas");
                return made;
            }
            why += "toBitmap=null;";
        }
        // 最后一档：直接解壁纸文件（部分 ROM 的 getDrawable 会返回空，但文件是好的）。
        // ⚠️ WallpaperManager 没有 getBitmap()（编译期就能发现）—— 前几版凭印象写错过。
        int[] flags = {WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK};
        for (int flag : flags) {
            try {
                android.os.ParcelFileDescriptor pfd = wm.getWallpaperFile(flag);
                if (pfd == null) {
                    why += "file" + flag + "=null;";
                    continue;
                }
                try {
                    Bitmap raw = android.graphics.BitmapFactory.decodeFileDescriptor(
                            pfd.getFileDescriptor());
                    if (raw != null && !raw.isRecycled()) {
                        XposedCompat.log(TAG + " 路径=getWallpaperFile(" + flag + ")");
                        return raw;
                    }
                    why += "decode" + flag + "=null;";
                } finally {
                    try {
                        pfd.close();
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable t) {
                why += "file" + flag + "×" + t.getClass().getSimpleName() + ";";
            }
        }
        XposedCompat.log(TAG + " 全部入口失败: " + why);
        return null;
    }

    /** 把任意 Drawable 画到一张位图上（bounds 用它的固有尺寸，超大就等比缩到 {@link #MAX_SIDE}）。 */
    private static Bitmap toBitmap(Drawable d) {
        try {
            int w = d.getIntrinsicWidth();
            int h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                w = 1080;
                h = 2400;
            }
            float scale = 1f;
            int longer = Math.max(w, h);
            if (longer > MAX_SIDE) scale = (float) MAX_SIDE / longer;
            int bw = Math.max(1, (int) (w * scale));
            int bh = Math.max(1, (int) (h * scale));
            Bitmap out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            d.setBounds(0, 0, bw, bh);
            d.draw(c);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}
