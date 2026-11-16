package io.github.ariinyume.morimod.hook;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Method;

import io.github.ariinyume.morimod.config.MoriConfig;
import io.github.ariinyume.morimod.config.RemoteConfig;
import io.github.ariinyume.morimod.util.XposedCompat;

/**
 * BgPeel 职责拆分（v0.1.146 B7）· CoverLayer。
 * 共享 static 状态仍在 BgPeel；本类只承载本职责的方法。
 */
final class CoverLayer {

    private CoverLayer() {
    }

    /** 采样 24×24 网格求平均 alpha（0–1）。硬件位图取不到像素 ⇒ 当不透明处理。 */    /**
     * ★ v0.1.41（r12 用户第 7 条）：这张图片层「有多不透明」。
     *
     * <p>{@link #imgKind} 算过就复用缓存；没算过（HARDWARE 位图 / 非 BitmapDrawable）
     * 就现算一次并记住。<b>读不出来一律当 1.0</b>（不透明）——
     * 宁可少铺一块玻璃，也绝不再把作品封面铺成模糊底（那正是「封面死都不显示」）。
     */
    static float alphaAvgOf(View v) {
        Bitmap b = BgPeel.imageBitmapOf(v);
        if (b == null || b.isRecycled()) return 1f;
        Float c = BgPeel.AVG_ALPHA.get(b);
        if (c != null) return c;
        float a = 1f;
        Bitmap probe = null;
        try {
            probe = b;
            if (b.getConfig() == Bitmap.Config.HARDWARE) {
                probe = b.copy(Bitmap.Config.ARGB_8888, false);
            }
            if (probe != null && probe.getConfig() != Bitmap.Config.HARDWARE) {
                a = avgAlpha(probe);
            }
        } catch (Throwable ignored) {
        } finally {
            if (probe != null && probe != b) {
                try {
                    probe.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
        BgPeel.AVG_ALPHA.put(b, a);
        return a;
    }


    static float avgAlpha(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        if (w <= 0 || h <= 0) return 1f;
        int stepX = Math.max(1, w / 24);
        int stepY = Math.max(1, h / 24);
        long sum = 0L;
        int n = 0;
        for (int y = stepY / 2; y < h; y += stepY) {
            for (int x = stepX / 2; x < w; x += stepX) {
                sum += (b.getPixel(x, y) >>> 24) & 0xff;
                n++;
            }
        }
        return n == 0 ? 1f : sum / (float) (n * 255f);
    }


    static boolean bfsMonthlyCue(View v, int depth, int visited) {
        // 月度回顾文案实测在 Dialog 树深度 ~18；6 层会漏掉「分享月度 / 最常听」。
        if (v == null || depth > 22 || visited > 160) return false;
        if (cueHit(v, BgPeel.MONTHLY_REVIEW_CUES)) return true;
        if (!(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        int n = g.getChildCount();
        if (n > 14) n = 14;
        int next = visited + 1;
        for (int i = 0; i < n; i++) {
            if (bfsMonthlyCue(g.getChildAt(i), depth + 1, next + i)) return true;
        }
        return false;
    }


    static boolean childFillsCover(View plate, View c) {
        if (c == null || !looksLikeCoverArt(c)) return false;
        int cw = c.getWidth();
        int ch = c.getHeight();
        if (cw < 96 || ch < 96) return false;
        int pw = plate.getWidth();
        int ph = plate.getHeight();
        if (pw <= 0 || ph <= 0) return false;
        return cw * 100 >= pw * 70 && ch * 100 >= ph * 70;
    }


    static boolean chromatic(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max - min > 18 && max > 40;
    }


    static int colorFrom(View v, Drawable drawn) {
        if (drawn instanceof android.graphics.drawable.ColorDrawable) {
            return ((android.graphics.drawable.ColorDrawable) drawn).getColor();
        }
        if (drawn != null) {
            Integer c = BgPeel.solidColorOf(drawn);
            if (c != null) return c;
        }
        return plateColor(v);
    }


    /**
     * 这一层是不是「作品封面」，且当前开着「作品封面屏蔽」。
     *
     * <p>命中后不画原图，改铺一层淡主题色。圆角交给宿主自己裁。
     * 开屏 Splash 子树、回顾 / 月度总结、小方图标、通栏氛围图一律不算。
     * 播放页接近满宽的横幅封面、PurePlay 方形封面要算。
     */
    /**
     * 封面屏蔽总闸。
     * <p>★★★ v0.1.152：不再要求 {@link BgPeel#sArmed}。
     * 旧版把封面屏蔽绑在「自定义壁纸已武装」上 —— 开关封面屏蔽时若壁纸缓存被
     * {@link FeatureF2} 误清、armed 抖一下，用户会看成「背景替换被连带关掉」；
     * 且无壁纸时封面屏蔽也应独立可用。
     */
    static boolean coverBlockOn() {
        return RemoteConfig.get().coverBlock;
    }


    static boolean coverBlocked(View v) {
        try {
            if (!coverBlockOn()) return false;
            // 先认图再扫树：非 Image/Skia 直接跳过，底栏/选项卡切换少很多 inReviewUi。
            if (!looksLikeCoverArt(v)) return false;
            if (!maybeCoverSlot(v)) return false;
            int w = v.getWidth();
            int h = v.getHeight();
            if (!coverShaped(w, h)) return false;
            if (underSplash(v)) return false;
            if (inReviewUi(v)) return false;
            if (imgKind(v) == BgPeel.IMG_BLUR) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }


    /**
     * center-crop：把位图等比放大到刚好覆盖「整个屏幕」，再居中裁剪。
     *
     * <p>与 {@code CoverDrawable} 的算法一致 —— 顶底栏和页面必须用同一个缩放，
     * 否则毛玻璃和清晰区会错位。
     */
    static RectF coverRect(Bitmap bmp, float left, float top) {
        return coverRect(bmp, left, top, BgPeel.sSW, BgPeel.sSH);
    }


    /**
     * ★★★ v0.1.72（r74）：目标矩形可指定的版本 —— 给「转场缩放补偿」用。
     *
     * <p>屏幕坐标系里要铺满 {@code vw × vh}；调用方传进来的 {@code vw/vh} 已经是
     * 「屏幕尺寸 ÷ 画布累计缩放」，{@code left/top} 也已经是 {@code −loc ÷ 缩放}。
     * 父层再把这套局部坐标乘回 scale ⇒ 落点与尺寸和屏幕严格对齐。
     *
     * <p>scale=1 时与两参数版逐像素等价（{@code vw=BgPeel.sSW, vh=BgPeel.sSH}），
     * 所以非转场画面完全不受影响。
     */
    static RectF coverRect(Bitmap bmp, float left, float top, float vw, float vh) {
        RectF dst = BgPeel.DST.get();
        int iw = bmp.getWidth();
        int ih = bmp.getHeight();
        if (vw < 1f) vw = 1f;
        if (vh < 1f) vh = 1f;
        float scale = Math.max(vw / iw, vh / ih);
        float dw = iw * scale;
        float dh = ih * scale;
        dst.set(left + (vw - dw) / 2f, top + (vh - dh) / 2f,
                left + (vw + dw) / 2f, top + (vh + dh) / 2f);
        return dst;
    }


    /**
     * 作品封面的形状。
     * <ul>
     *   <li>通栏且偏高（PurePlay 顶部 localbg）→ 氛围层，排除；</li>
     *   <li>通栏但只有三成屏高（播放页 1272×954 封面）→ 算封面；</li>
     *   <li>小方图（logo / CD / 头像）→ 排除；大的正方形 PurePlay 封面仍算。</li>
     * </ul>
     */
    static boolean coverShaped(int w, int h) {
        if (!coverSized(w, h)) return false;
        if (BgPeel.sSW > 0 && w * 100 >= (long) BgPeel.sSW * 96
                && BgPeel.sSH > 0 && h * 100 >= (long) BgPeel.sSH * 40) {
            return false;
        }
        return !iconSquare(w, h);
    }


    /** 小图标以下、整屏底图以上。通栏氛围层和小方图标另有排除。 */
    static boolean coverSized(int w, int h) {
        if (w < 140 || h < 96) return false;
        if (BgPeel.sSW <= 0 || BgPeel.sSH <= 0) return false;
        if (w * 100 >= (long) BgPeel.sSW * 96 && h * 100 >= (long) BgPeel.sSH * 96) return false;
        if ((long) w * h * 100L > (long) BgPeel.sSW * BgPeel.sSH * 70L) return false;
        return true;
    }


    /** 遮罩用的主题色：优先拿旁边那层淡色底的 RGB，透明度固定在类型框那一档。 */
    static int coverVeilColor(View v) {
        int rgb = themeRgbNear(v);
        if (rgb == 0) rgb = BgPeel.sCoverThemeRgb;
        if (rgb == 0) rgb = AccentColor.washTint(255) & 0x00FFFFFF;
        return (BgPeel.COVER_VEIL_ALPHA << 24) | (rgb & 0x00FFFFFF);
    }


    static boolean cueHit(View v, String[] cues) {
        try {
            if (v instanceof android.widget.TextView) {
                if (cueHit(((android.widget.TextView) v).getText(), cues)) return true;
            }
            return cueHit(v.getContentDescription(), cues);
        } catch (Throwable t) {
            return false;
        }
    }


    static boolean cueHit(CharSequence cs, String[] cues) {
        if (cs == null || cues == null) return false;
        String s = cs.toString();
        if (s.length() == 0 || s.length() > 80) return false;
        for (int i = 0; i < cues.length; i++) {
            if (s.contains(cues[i])) return true;
        }
        return false;
    }


    static boolean cueInChildren(View v, String[] cues, int depth) {
        if (v == null || cues == null || depth > 3) return false;
        if (cueHit(v, cues)) return true;
        if (!(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        int n = g.getChildCount();
        if (n > 8) n = 8;
        for (int i = 0; i < n; i++) {
            if (cueInChildren(g.getChildAt(i), cues, depth + 1)) return true;
        }
        return false;
    }


    /** 列表白槽常常是 ReactViewGroup → Wrapper → FrameLayout → ExpoImageView，要多钻几层。 */
    static boolean findCoverDesc(View plate, View node, int depth) {
        if (plate == null || node == null || depth > 4) return false;
        if (depth > 0 && childFillsCover(plate, node)) return true;
        if (!(node instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) node;
        int n = g.getChildCount();
        if (n <= 0 || n > 8) return false;
        for (int i = 0; i < n; i++) {
            if (findCoverDesc(plate, g.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }


    /** Fresco / RN Image 自己重写了 {@code draw}，基类钩子进不去。 */
    static void hookDraweeCover(ClassLoader cl) {
        String[] names = {
                "com.facebook.drawee.view.DraweeView",
                "com.facebook.react.views.image.ReactImageView",
        };
        for (int i = 0; i < names.length; i++) {
            try {
                Class<?> cls = XposedCompat.findClass(names[i], cl);
                Method draw = cls.getDeclaredMethod("draw", Canvas.class);
                draw.setAccessible(true);
                XposedCompat.hookMethod(draw, chain -> {
                    try {
                        Object self = chain.getThisObject();
                        Object a0 = chain.getArg(0);
                        if (self instanceof View && a0 instanceof Canvas) {
                            View v = (View) self;
                            Canvas c = (Canvas) a0;
                            if (coverBlocked(v)) {
                                paintCoverMask(c, v);
                                BgDiag.traceCover(v, "coverBlock-drawee");
                                return null;
                            }
                            // ★★★ v0.1.135：月度回顾满屏底是 ReactImageView，必须在这条路上换。
                            if (PanelGlass.paintReviewBg(v, c)) {
                                return null;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                BgPeel.log("hooked cover " + names[i] + "#draw");
            } catch (Throwable ignored) {
            }
        }
    }


    /**
     * ★★ v0.1.20 核心新增：钩 {@code expo.modules.image.ExpoImageView#draw}。
     *
     * <h3>为什么前面六版都打不中 PurePlay 的紫遮罩 / 播放页的紫色底</h3>
     * 报告《PurePlay 页面背景机制》逐字还原了 bundle：那一层是
     * <pre>
     * &lt;Image source={LOCALBG} contentFit="fill" pointerEvents="none"
     *        style={[styles.backgroundOverlayImage, {width: W, height: W * 1.1795}]} /&gt;
     * </pre>
     * 也就是<b>一张普通内容图</b>（{@code assets_images_localbg.webp}，780×920 RGBA，
     * 自上而下紫→透明的 alpha 全烘在位图里）。它<b>不是</b> background、不走 Resources 取图、
     * 更不是渐变 View ⇒ 我们对 background drawable / 资源 / 渐变的全部钩子都碰不到它。
     *
     * <p>播放页的 {@code playbackspbackground}（1081×1920 RGBA）是同一套写法，
     * 这就是「播放页自定义背景不生效」的同一个病。
     *
     * <h3>判据（不依赖比例魔数）</h3>
     * <ol>
     *   <li>满宽（≥94% 屏宽）+ 高度 ≥18% 屏高 —— 排除图标 / 头像 / 封面；</li>
     *   <li>贴屏幕顶 或 满屏 —— 排除列表中间的卡片图；</li>
     *   <li><b>位图有 alpha 通道 + 采样平均 alpha &lt; 0.92</b> —— 这是报告 §4 说的
     *       「RGBA + 大面积 alpha」共同特征。{@code main_visual}（主页立绘）是 <b>RGB</b>，
     *       直接在这里被排除，绝不会像 r23 那样把整屏内容杀掉。</li>
     * </ol>
     *
     * <p>命中 ⇒ 整层不画（{@code return null}）。它本来就是「盖在壁纸上的氛围层」，
     * 不画 = 干净透出壁纸；最坏情况也只是露出宿主原本的底色，不会白屏、不会丢内容。
     */
    static void hookExpoImage(ClassLoader cl) {
        try {
            Class<?> cls = XposedCompat.findClass(BgPeel.CLS_EXPO_IMAGE, cl);
            Method draw;
            try {
                draw = cls.getDeclaredMethod("draw", Canvas.class);
            } catch (NoSuchMethodException e) {
                BgPeel.log(BgPeel.CLS_EXPO_IMAGE + " 没有 draw(Canvas)");
                return;
            }
            draw.setAccessible(true);
            XposedCompat.hookMethod(draw, chain -> {
                // ★★★ v0.1.46（r46 用户第 2 条）：防偷窥罩色的变量必须在 try 外声明 ——
                //   罩色要等宿主**画完**再叠（和 hookViewDraw 里的写法一致）。
                View v = null;
                Canvas cv = null;
                try {
                    Object self = chain.getThisObject();
                    Object arg0 = chain.getArg(0);
                    if (self instanceof View) {
                        v = (View) self;
                        cv = arg0 instanceof Canvas ? (Canvas) arg0 : null;
                        // ★★★ v0.1.46：**防偷窥封面就是 ExpoImageView**。
                        //   r46 真机日志：整轮 `wash privacy` 一条都没有，但
                        //   `sheet miss ExpoImageView 655x491` 反复出现 —— 罩色判据
                        //   （privacyWash）只挂在 {@code View#draw} 钩子里，而
                        //   ExpoImageView **自己重写了 draw(Canvas)**，虚分派永远走不到
                        //   View.draw ⇒ 罩色这条路一次都没机会执行。
                        //   这里在宿主画完之后叠同一层强调色罩，与 hookViewDraw 等效。
                        if (coverBlocked(v)) {
                            BgPeel.sPainted = true;
                            if (cv != null) paintCoverMask(cv, v);
                            BgDiag.traceCover(v, "coverBlock");
                            return null;
                        }
                        // ★★★ v0.1.148：切页时列表缩略图只放行；页级大底（满宽且 ≥40% 屏高）
                        //   仍跑 blur/veil/atmos，避免 PurePlay/播放页回归。
                        if (PlayPage.lightNav()) {
                            boolean pageLayer = false;
                            try {
                                int w0 = v.getWidth();
                                int h0 = v.getHeight();
                                pageLayer = BgPeel.sSW > 0 && BgPeel.sSH > 0
                                        && w0 >= BgPeel.sFullW
                                        && h0 * 100 >= (long) BgPeel.sSH * 40;
                            } catch (Throwable ignored) {
                            }
                            if (!pageLayer || cv == null) {
                                return chain.proceed();
                            }
                            if (PanelGlass.paintReviewBg(v, cv)) {
                                BgDiag.traceExpo(v, "review");
                                return null;
                            }
                            if (PanelGlass.paintBlurBg(v, cv)) {
                                BgDiag.traceExpo(v, "big");
                                return null;
                            }
                            if (PanelGlass.dropVeil(v, cv)) {
                                BgDiag.traceExpo(v, "veil");
                                return null;
                            }
                            if (PanelGlass.paintAtmos(v, cv)) {
                                BgDiag.traceExpo(v, "atmos");
                                return null;
                            }
                            return chain.proceed();
                        }
                        // ★★★ v0.1.135：月度回顾底优先于 paintBlurBg（CONTENT 会放行宿主）。
                        if (cv != null && PanelGlass.paintReviewBg(v, cv)) {
                            BgDiag.traceExpo(v, "review");
                            return null;
                        }
                        if (cv != null && PanelGlass.paintBlurBg(v, cv)) {
                            BgDiag.traceExpo(v, "big");
                            BgDiag.traceCover(v, "host-expo-big");
                            return null;
                        }
                        // ② 半透明氛围叠加图（PurePlay 顶部紫遮罩）：换成清晰壁纸
                        if (cv != null && PanelGlass.dropVeil(v, cv)) {
                            BgDiag.traceExpo(v, "veil");
                            BgDiag.traceCover(v, "host-expo-veil");
                            return null;
                        }
                        // ③ 不透明氛围底图（播放页 playbackspbackground）：换成本地壁纸（可按档位糊）
                        if (cv != null && PanelGlass.paintAtmos(v, cv)) {
                            BgDiag.traceExpo(v, "atmos");
                            BgDiag.traceCover(v, "host-expo-atmos");
                            return null;
                        }
                        // ★★★ v0.1.37：弹层里的大块图片底（半透明紫面板）→ 先铺玻璃，
                        //   否则那层紫会把底下已经铺好的玻璃重新盖掉（见 paintDialogGlass）。
                        // ★★★ v0.1.41：改成「局部铺玻璃 + 把宿主那张紫图按低透明度叠回」，
                        //   坐标一律用本层尺寸（不再用整屏位图对齐，见 paintLocal）。
                        if (cv != null && PanelGlass.paintDialogGlass(v, cv, chain::proceed)) {
                            BgDiag.traceExpo(v, "dlg");
                            BgDiag.traceCover(v, "host-expo-dlg");
                            return null;
                        }
                        // v0.1.71（r73）：把「这块大图到底被谁接管 / 为什么没被接管」写成一行
                        // 实时决策（logcat，每块图每秒最多一条）。
                        // 之所以要它：r73 的层级探针只能看到 `P=paint play blur big` 这种
                        // 历史归属 —— 分不清「这一刻仍在接管」还是「早先接管过一次」。
                        BgDiag.traceExpo(v, "host");
                        BgDiag.traceCover(v, "host-expo");
                    }
                } catch (Throwable ignored) {
                }
                Object r = chain.proceed();
                return r;
            });
            BgPeel.sHooked = true;
            BgPeel.log("hooked 1 " + BgPeel.CLS_EXPO_IMAGE
                    + "#draw（模糊底图→壁纸+模糊 / 半透明→毛玻璃 / 不透明→换壁纸）");
        } catch (Throwable t) {
            BgPeel.log("钩子未装 " + BgPeel.CLS_EXPO_IMAGE + ": " + t);
        }
    }


    /** 边长差在 25% 以内、短边不到屏宽 24% 的正方形：图标，不是作品封面。 */
    static boolean iconSquare(int w, int h) {
        int min = Math.min(w, h);
        int max = Math.max(w, h);
        if (min <= 0 || max * 100 > min * 125) return false;
        int limit = BgPeel.sSW > 0 ? (int) (BgPeel.sSW * 0.24f) : 300;
        return min < limit;
    }


    static int imgKind(View v) {
        // ★★ v0.1.24 用户第 3 条：宿主明确要求过模糊（blurRadius > 0）⇒ 装饰底，
        //   和位图本身有没有 alpha 通道无关（封面照是 RGB，读不出 alpha）。
        //   ★ v0.1.27：判定改成「这张位图被标过」而不是「这个 View 被标过」。
        //   ★★ v0.1.28 用户第 1 / 2 条：**还必须真的是「铺满整屏」的那一层**。
        //   以前只要 View 宽过线就认 —— 播放页的<b>正面封面</b>（r33 日志
        //   `ReactImageView 2300x1726`，高只占 62%）和宿主那张 10px 模糊底共用同一张
        //   artwork 位图，位图级的标记一绑上，正面封面也被当成装饰底整层顶掉
        //   ⇒ 用户看到「作品封面不显示」「最后一张图片背后多一层模糊」。
        //   装饰底在 bundle 里就是 `PlayerBackground` / `PlaylistDetailBackground`，
        //   两者都是 `contentFit=cover` **铺满整屏**；封面卡片永远没有 85% 屏高。
        if (BgPeel.blurRadiusOf(v) > 0 && BgPeel.isScreenFillDecor(v)) return BgPeel.IMG_BLUR;
        // ★★★ v0.1.95（r117 用户第 1 条「播放页背景变成放大的作品封面、封面本身不显示」）：
        //   **radius 读不到，位图像素自己会说话。**
        //
        //   r117 现场（logcat MoriTrace，同一块 1272x2772 的两个时刻）：
        //   <pre>
        //   ExpoImageView 1272x2772 r=10 kind=3 bmp=140x105/blur -> big    ← 正常（接管，铺壁纸）
        //   ExpoImageView 1272x2772 r=0  kind=0 bmp=140x105/blur -> host   ← 异常（交还宿主，糊封面）
        //   </pre>
        //   `blurRadiusOf` 的第一行是 `BgPeel.BLUR_PENDING.get(v)`，**拿不到就直接 return 0**。
        //   而那个标记只在 `ExpoImageViewWrapper#setBlurRadius` 被调用时写入 ——
        //   RN 只在 prop <b>变化</b>时才调 setter：新开一个作品、或从后台回前台，
        //   View 是新建/复用的，prop 没"变化" ⇒ setter 不跑 ⇒ 标记缺席 ⇒ 半径读 0。
        //   于是 `imgKind` 落到 BgPeel.IMG_CONTENT，`paintBlurBg` 在
        //   `kind == BgPeel.IMG_CONTENT && !blurDeclared` 处整层放行 ⇒ 宿主那张糊封面直接上屏。
        //
        //   而这块图**根本不是内容图**：它是宿主把封面缩到 140x105 再糊好的背景底
        //   （像素级证据 `isBlurredBitmap` 为真，现场日志已经打出来 `/blur`）。
        //   所以把「满屏 + 不在分页容器 + 位图确实是糊的」作为第三条判据 ——
        //   纯像素事实，**不依赖任何会迟到的状态**（setter 有没有跑、playContext 建没建立）。
        //
        //   ⚠️ 为什么不会误伤内容图（v0.1.75 那两条「满屏大图变壁纸 / 闪烁」）：
        //     ① 内容图是<b>清晰</b>的，`isBlurredBitmap` 直接否掉；
        //     ② 图片浏览/漫画在 ViewPager 里翻同一层，`insidePager` 否掉；
        //     ③ 作品封面是 1272x954（34% 屏高），`isScreenFillDecor` 要 85% 否掉。
        //   三道闸缺一不可，所以只会命中「真·满屏糊底」。
        if (BgPeel.isScreenFillDecor(v) && !BgPeel.insidePager(v) && BgPeel.isBlurredBitmap(BgPeel.privacyBitmapOf(v))) {
            return BgPeel.IMG_BLUR;
        }
        Drawable d;
        try {
            if (!(v instanceof ImageView)) return BgPeel.IMG_CONTENT;
            d = ((ImageView) v).getDrawable();
        } catch (Throwable t) {
            return BgPeel.IMG_CONTENT;
        }
        if (!(d instanceof BitmapDrawable)) return BgPeel.IMG_CONTENT;
        Bitmap b;
        try {
            b = ((BitmapDrawable) d).getBitmap();
        } catch (Throwable t) {
            return BgPeel.IMG_CONTENT;
        }
        if (b == null || b.isRecycled()) return BgPeel.IMG_CONTENT;
        Integer cached = BgPeel.VEIL_CACHE.get(b);
        if (cached != null) return cached;
        int kind = BgPeel.IMG_CONTENT;
        float avg = 1f;
        boolean hasA = false;
        String cfg = "?";
        try {
            hasA = b.hasAlpha();
            cfg = String.valueOf(b.getConfig());
            // ★★★ v0.1.36：**硬件位图也要分析**。
            //   以前 `b.getConfig() == HARDWARE` 就直接当内容图放行（读不到像素），
            //   于是「PurePlay 紫遮罩 / 播放页显示封面模糊版 / 背景消失」这三种
            //   时有时无的症状全在这里漏掉 —— 同一张图走 HARDWARE 解码就漏、
            //   走 ARGB_8888 就中，表现成「有时候好有时候不好」。
            //   现在拷一份软件副本**只做分析**（一次性，结果进 BgPeel.VEIL_CACHE，不重复开销）。
            Bitmap probe = b;
            try {
                if (b.getConfig() == Bitmap.Config.HARDWARE) {
                    Bitmap soft = b.copy(Bitmap.Config.ARGB_8888, false);
                    if (soft != null) {
                        probe = soft;
                        hasA = soft.hasAlpha();
                    }
                }
            } catch (Throwable ignored) {
            }
            if (probe.getConfig() != Bitmap.Config.HARDWARE && hasA) {
                avg = avgAlpha(probe);
                // ★ v0.1.22：三段式。只有「真的半透明叠加」才允许留洞（整层不画），
                //   其余一律当底图原地换壁纸 —— 见 BgPeel.VEIL_DROP_MAX_ALPHA 的注释。
                // ★★★ v0.1.82（r85 第 2 条「缓存图不显示、在线图正常」的真凶）：
                //   **「有 alpha 通道」不等于「半透明」。**
                //   本地缓存的图是 PNG（带 alpha 通道，但像素 alpha 全是 255），
                //   于是 `hasA == true` ⇒ 被判成 BgPeel.IMG_ATMOS ⇒ 被 `paintAtmos`
                //   整层换成壁纸 ⇒ 图不显示；
                //   而在线图是 JPEG（无 alpha 通道）⇒ `hasA == false` ⇒ BgPeel.IMG_CONTENT
                //   ⇒ 放行 ⇒ 正常显示。**这正是「在线正常、本地不正常」的全部原因。**
                //   现在按<b>像素实际不透明度</b>再分一层：avg ≥ 0.97 就是内容图。
                //   播放页那些装饰底图不靠这条（它们在 3928 行就按 blurRadius 命中 BgPeel.IMG_BLUR）。
                if (avg < BgPeel.VEIL_DROP_MAX_ALPHA) {
                    kind = BgPeel.IMG_VEIL;
                } else if (avg >= 0.97f) {
                    kind = BgPeel.IMG_CONTENT;
                } else {
                    kind = BgPeel.IMG_ATMOS;
                }
            }
            if (probe != b) {
                try {
                    probe.recycle();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return BgPeel.IMG_CONTENT;
        }
        BgPeel.VEIL_CACHE.put(b, kind);
        BgPeel.AVG_ALPHA.put(b, avg);
        if (BgPeel.sImgLogs < BgPeel.IMG_LOG_BUDGET) {
            BgPeel.sImgLogs++;
            BgPeel.log("img " + b.getWidth() + "x" + b.getHeight() + " cfg=" + cfg
                    + " alpha=" + hasA + " avg=" + String.format(java.util.Locale.ROOT, "%.2f", avg)
                    + " -> " + (kind == BgPeel.IMG_VEIL ? "veil" : kind == BgPeel.IMG_ATMOS ? "atmos" : "content")
                    + (avg >= BgPeel.VEIL_MAX_ALPHA ? "(solid)" : ""));
        }
        return kind;
    }


    static boolean inReviewUi(View v) {
        if (v == null) return false;
        long frame = android.os.SystemClock.uptimeMillis() / 32L;
        int id = System.identityHashCode(v);
        if (frame != BgPeel.sReviewCacheFrame) {
            BgPeel.sReviewCacheFrame = frame;
            BgPeel.REVIEW_HIT.clear();
            BgPeel.REVIEW_MISS.clear();
        } else {
            if (BgPeel.REVIEW_HIT.get(id, false)) return true;
            if (BgPeel.REVIEW_MISS.get(id, false)) return false;
        }
        boolean hit = inReviewUiUncached(v);
        if (hit) BgPeel.REVIEW_HIT.put(id, true);
        else BgPeel.REVIEW_MISS.put(id, true);
        return hit;
    }


    static boolean inReviewUiUncached(View v) {
        // 媒体库单列也常 dlg=1（ViewPager2），绝不能「凡 Dialog 都回顾」。
        // 根扫描只用「月度回顾」专有文案（已剔除首页「你听了 / 喜欢的类型」），
        // 首页根不会命中；月度页无论 Dialog 还是全屏 Activity 都能命中。
        if (rootHasReviewCue(v)) return true;
        boolean dlg = BgPeel.inDialogWindow(v);
        if (dlg && !PlayPage.playContext(v) && !PlayPage.isPlayWindow(v)
                && !underViewPager(v) && isReviewCdShape(v)) {
            // 月度回顾大滚动 CD：独立 Dialog、无 ViewPager。
            // PurePlay 播放页同是 Dialog 方图，但有 playContext / seek。
            return true;
        }
        View cur = v;
        for (int i = 0; i < 10 && cur != null; i++) {
            if (cueHit(cur, BgPeel.REVIEW_CARD_CUES) || cueHit(cur, BgPeel.MONTHLY_REVIEW_CUES)) {
                return true;
            }
            if (isFullPageContainer(cur)) {
                // 满屏容器绝不能 cueInChildren(REVIEW_CARD)：首页 ScrollView 里有「你听了」
                // 会把立刻收听整列封面都当成回顾。
                if (cueInChildren(cur, BgPeel.MONTHLY_REVIEW_CUES, 0)) return true;
            } else if (isLikelyReviewCard(cur)) {
                if (cueInChildren(cur, BgPeel.REVIEW_CARD_CUES, 0)
                        || cueInChildren(cur, BgPeel.MONTHLY_REVIEW_CUES, 0)) {
                    return true;
                }
            }
            Object par;
            try {
                par = cur.getParent();
            } catch (Throwable t) {
                break;
            }
            cur = par instanceof View ? (View) par : null;
        }
        return false;
    }


    /** 只认几何上的满屏，不再把任意 Scroll/Recycler 名字当成整页。 */
    static boolean isFullPageContainer(View v) {
        try {
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0 || BgPeel.sSW <= 0 || BgPeel.sSH <= 0) return false;
            return w * 100 >= (long) BgPeel.sSW * 90 && h * 100 >= (long) BgPeel.sSH * 45;
        } catch (Throwable t) {
            return false;
        }
    }


    /** 回顾小卡片：非整屏宽、也不占大半屏高，才允许对子树扫「你听了」。 */
    static boolean isLikelyReviewCard(View cur) {
        if (cur == null) return false;
        int w = cur.getWidth();
        int h = cur.getHeight();
        if (w <= 0 || h <= 0) return false;
        if (BgPeel.sSW > 0 && w * 100 >= (long) BgPeel.sSW * 92) return false;
        if (BgPeel.sSH > 0 && h * 100 >= (long) BgPeel.sSH * 50) return false;
        return true;
    }


    /** 媒体库单列缩略图：小于半屏的竖图 / 横图槽。 */
    static boolean isListThumbSlot(int w, int h) {
        if (BgPeel.sSW <= 0 || BgPeel.sSH <= 0) return false;
        if (!coverSized(w, h)) return false;
        return w * 100 < (long) BgPeel.sSW * 48 && h * 100 < (long) BgPeel.sSH * 32;
    }


    /** PurePlay 导入封面：接近正方形，边长超过屏宽 24%。 */
    static boolean isNearSquareSlot(int w, int h) {
        int min = Math.min(w, h);
        int max = Math.max(w, h);
        if (min <= 0 || max * 100 > min * 130) return false;
        int limit = BgPeel.sSW > 0 ? (int) (BgPeel.sSW * 0.24f) : 300;
        return min >= limit;
    }


    static boolean isNearWhiteFill(int c) {
        int a = (c >>> 24) & 0xFF;
        if (a < 210) return false;
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        return r > 220 && g > 220 && b > 220;
    }


    /** PurePlay / 列表里略竖的封面槽（不一定严格正方形）。 */
    static boolean isPortraitThumbSlot(int w, int h) {
        if (BgPeel.sSW <= 0 || BgPeel.sSH <= 0) return false;
        if (!coverSized(w, h)) return false;
        if (h < w) return false;
        if (h * 100 > w * 165) return false;
        return w * 100 < (long) BgPeel.sSW * 48 && h * 100 < (long) BgPeel.sSH * 42;
    }


    /** 月度回顾里的方形 CD / 底部横向大滚动 CD 条。 */
    static boolean isReviewCdShape(View v) {
        if (v == null) return false;
        int w = v.getWidth();
        int h = v.getHeight();
        if (w < 160 || h < 160) return false;
        int min = Math.min(w, h);
        int max = Math.max(w, h);
        int limit = BgPeel.sSW > 0 ? (int) (BgPeel.sSW * 0.28f) : 350;
        // 近方形大 CD
        if (max * 100 <= min * 130 && min >= limit) return true;
        // 底部横向滚动 CD 位图：很宽、有一定高度，但不是整屏氛围层
        if (BgPeel.sSW > 0 && w * 100 >= (long) BgPeel.sSW * 70
                && h >= (int) (limit * 0.65f)
                && (BgPeel.sSH <= 0 || h * 100 < (long) BgPeel.sSH * 55)) {
            return true;
        }
        return false;
    }


    static boolean isSolidCoverFillColor(int c) {
        int a = (c >>> 24) & 0xFF;
        if (a < 210) return false;
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        if (r > 220 && g > 220 && b > 220) return true;
        return chromatic(c & 0x00FFFFFF);
    }


    /**
     * ★★★ v0.1.142：是不是系统 / androidx 开屏根（{@code SplashScreenView}）。
     * 类名带 Splash 即可，不绑具体包名（ROM / androidx 路径会变）。
     */
    static boolean isSplashView(View v) {
        if (v == null) return false;
        try {
            return v.getClass().getSimpleName().indexOf("Splash") >= 0;
        } catch (Throwable t) {
            return false;
        }
    }


    static boolean isTextButtonPlate(View v) {
        if (v == null) return false;
        View cur = v;
        for (int i = 0; i < 5 && cur != null; i++) {
            try {
                String n = cur.getClass().getName();
                if (n != null && n.contains("Button")) return true;
            } catch (Throwable ignored) {
            }
            Object par;
            try {
                par = cur.getParent();
            } catch (Throwable t) {
                break;
            }
            cur = par instanceof View ? (View) par : null;
        }
        if (cueInChildren(v, BgPeel.BUTTON_CUES, 0)) return true;
        return false;
    }


    /** ImageView / Expo / Fresco / Skia 纹理都可能是封面。 */
    static boolean looksLikeCoverArt(View v) {
        if (v instanceof ImageView) return true;
        if (v instanceof android.view.TextureView) return true;
        try {
            String n = v.getClass().getName();
            return n.contains("Image") || n.contains("Drawee") || n.contains("Skia");
        } catch (Throwable t) {
            return false;
        }
    }


    /**
     * 尺寸上才可能是封面图。
     * 注意：播放页横幅（≈满宽 × 三成高）、PurePlay 大方图（≈七成屏宽）都要放行；
     * 「宽 ≥70% 不当槽」只用于挖实心底（见 opaqueCoverPlate），绝不能挡封面图本身。
     */
    static boolean maybeCoverSlot(View v) {
        if (v == null) return false;
        int w = v.getWidth();
        int h = v.getHeight();
        return w >= 140 && h >= 96;
    }


    /**
     * ★ v0.1.40：玻璃失败诊断。
     *
     * <p>这条日志是为了回答那个问题：「为什么有的面板能上模糊、有的上不去？」
     * —— 以前这条路上<b>失败也不报错</b>，静静地就变成了「铺一张清晰壁纸」，
     * 所以十来个版本都没查出来。现在失败必须留痕（限 8 条）。
     */
    /**
     * ★ v0.1.40：封面被「只保绘制、不接管」这条规则放行的次数（诊断用，限 3 条）。
     */
    static void noteCoverKept() {
        if (BgPeel.sCoverKeptLogs >= 3) return;
        BgPeel.sCoverKeptLogs++;
        BgPeel.log("cover kept (不接管绘制)");
    }


    /**
     * 封面槽上那层不透明纯色不要画（列表白底、PurePlay 实心主题色块）。
     * 半透明主题框保留。回顾放过。
     * 注意：不能单凭「实心彩底 + 封面尺寸」就挖空 —— 首页回顾大卡片会被挖成全透方块。
     * 无子图时只对「近方形 / 列表缩略图」动手。
     */
    static boolean opaqueCoverPlate(View v) {
        return opaqueCoverPlate(v, null);
    }


    /**
     * {@code drawn}：正在 draw 的那层 drawable。View.getBackground() 有时是复合层，
     * plateColor 读不到，但钩子里的 ColorDrawable / BackgroundDrawable 颜色是准的。
     */
    static boolean opaqueCoverPlate(View v, Drawable drawn) {
        try {
            if (!BgPeel.sArmed) return false;
            if (splashWhitePlate(v)) return true;
            if (!coverBlockOn()) return false;
            if (!maybeCoverSlot(v)) return false;
            int w = v.getWidth();
            int h = v.getHeight();
            // 宽条（回顾文案底、同步按钮）绝不挖空。
            if (tooWideToDig(w)) return false;
            // 扁胶囊按钮（没看过 / 导入）：宽远大于高，不是封面槽。
            if (h > 0 && w * 100 > h * 155) return false;
            if (isTextButtonPlate(v)) return false;
            if (inReviewUi(v)) return false;
            if (!coverShaped(w, h)) return false;
            int c = colorFrom(v, drawn);
            int a = (c == 0 && drawn == null) ? plateAlpha(v)
                    : (c == 0 ? -1 : ((c >>> 24) & 0xFF));
            // 半透明主题框（双列那种）保留。
            if (a >= 0 && a < 210) return false;
            // 包着封面图的实心底（列表白底 / PurePlay 槽）→ 挖掉。
            if (wrapsCoverArt(v)) return true;
            if (!isSolidCoverFillColor(c)) return false;
            // 纯白：只挖「列表缩略图」几何；扁胶囊按钮已在上面排除。
            if (isNearWhiteFill(c)) {
                return isListThumbSlot(w, h) || isPortraitThumbSlot(w, h);
            }
            // 无子图的实心主题色：只清 PurePlay 近方槽。
            return isNearSquareSlot(w, h);
        } catch (Throwable t) {
            return false;
        }
    }


    /**
     * 只铺一层淡主题色。不画壁纸（会钉在屏幕上，滑动时卡住），也不自己裁圆角。
     * 底下若已是半透明主题框（媒体库双列那种），就什么都不加。
     * {@code force}：实心底刚被跳过、必须自己罩一层时用，忽略父级半透明早退。
     */
    static void paintCoverMask(Canvas c, View v) {
        paintCoverMask(c, v, false);
    }


    static void paintCoverMask(Canvas c, View v, boolean force) {
        if (c == null || v == null) return;
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) return;
        if (!force) {
            int plateA = tightPlateAlpha(v);
            if (plateA >= 0 && plateA < 210) return;
        }
        try {
            BgPeel.WASH_PAINT.setXfermode(null);
            BgPeel.WASH_PAINT.setShader(null);
            BgPeel.WASH_PAINT.setStyle(Paint.Style.FILL);
            BgPeel.WASH_PAINT.setAntiAlias(true);
            BgPeel.WASH_PAINT.setColor(coverVeilColor(v));
            c.drawRect(0, 0, w, h, BgPeel.WASH_PAINT);
        } catch (Throwable ignored) {
        }
    }


    /** 这一层自己的纯色底透明度；没有纯色底返回 -1。 */
    static int plateAlpha(View v) {
        int c = plateColor(v);
        if (c == 0) return -1;
        return (c >>> 24) & 0xFF;
    }


    /** 这一层自己的纯色底；没有纯色底返回 0。 */
    static int plateColor(View v) {
        try {
            Drawable d = v.getBackground();
            if (d == null) return 0;
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                return ((android.graphics.drawable.ColorDrawable) d).getColor();
            }
            Integer c = BgPeel.solidColorOf(d);
            return c == null ? 0 : c;
        } catch (Throwable t) {
            return 0;
        }
    }


    static boolean rescueCover(View v) {
        try {
            if (!BgPeel.sArmed || BgPeel.sSH <= 0) return false;
            if (!(v instanceof android.widget.ImageView)) return false;
            // ★★★ v0.1.46（r46 用户第 4② 条）：补一条兜底判据。
            //   「迷你条状态下退后台→回前台→再展开播放页」时，播放页窗口会被重建，
            //   {@link #isPlayWindow}（靠 setBlurRadius(10) 记账）很可能还没来得及
            //   重新记账 ⇒ 封面 alpha=0 这一帧救不回来 ⇒「作品封面不显示」。
            //   现在：播放页窗口认不出时，退而求其次用「当前确实是播放语境
            //   （{@link #playContext}，v0.1.46 已把迷你条从判据里排掉）+ 尺寸够大 +
            //   alpha 几乎为 0」这三条一起来认 —— 依然不可能碰到正常显示的图。
            // ★ v0.1.60：窗口内的层一律参与救援（见下方尺寸闸）。
            //   注：日志实证 `ReactImageView 2300x1726 alpha=3.3E-4` 时
            //   `cover alpha fix` 就已经打过了 —— 说明「把 alpha 拉回 1」这一步是成功的，
            //   封面仍然不显示的原因不在 alpha，而在**它所在的那棵子树整体 alpha=0**
            //   （和「播放页透明」同一个病根）。所以这一轮不再放宽这里，
            //   统一交给窗口根底板（`paint root bg`）去解决。
            // ★★★ v0.1.66（r69 回归修复）：补一条**窗口信号** ——
            //   「2 秒内刚铺过播放档底图」（`BgPeel.sPlayPaintedAt`）。
            //   封面自己 alpha 常常是 1、`isPlayWindow` 在封面这一层又判不出来，
            //   只靠下面那条 alpha≤0.05 的判据根本进不来，祖先救援也就跑不到。
            boolean win = PlayPage.isPlayWindow(v);
            boolean sig = win || (System.currentTimeMillis() - BgPeel.sPlayPaintedAt < 2000L);
            if (!win && !sig && !(PlayPage.playContext(v) && v.getAlpha() <= 0.05f)) return false;
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0) return false;
            if (w * 10 < (long) BgPeel.sFullW * 4) return false;
            if (h * 100 < (long) BgPeel.sSH * 22 || h * 100 > (long) BgPeel.sSH * 88) return false;
            // ★ v0.1.65：播放窗口里不再因「自己 alpha 正常」就放过 ——
            //   封面自己常常是 1.0，被压成 0 的是它上面几层的容器
            //   （r68 dump：`ReactViewGroup 1272x2772 alpha=0.0` 里面才是封面）。
            //   放它过去，下面的祖先救援才有机会执行。
            if (!win && !sig && v.getAlpha() > 0.35f) return false;
            // ★★★ v0.1.31 用户第 4 条：位图「就绪」的判据放宽到 drawable 非空。
            //   宿主的封面是 {@code ReactImageView}，它内部是 Drawee 那一套，
            //   {@link #imageBitmapOf} 只认 BitmapDrawable ⇒ 永远读不到（r35 里
            //   `cover alpha fix` 一条都没打出来，正是这个原因）。有 drawable
            //   就说明图已经挂上了，够我们判断「这不是占位层」。
            if (!BgPeel.drawableReady(v)) return false;
            // ★★★ v0.1.64（r64 用户第 4 条「先恢复播放页的作品封面显示」）：
            //   两个致命问题，都是我上一版留下的：
            //
            //   ① **改 alpha 被 post 到下一帧**。旧代码 `BgPeel.MAIN.post(() -> v.setAlpha(1f))`，
            //      而 `rescueCover` 是在 {@code View#draw} 里调的 —— 等于「这一帧先按
            //      alpha≈0 画完，再排队改」，宿主若每帧都把 alpha 压回 0（淡入动画没跑完 /
            //      或者它压根不想显示），我们就永远慢一帧，屏幕上永远是空的。
            //      现在**同步改**：就在宿主画它之前把 alpha 拉回 1，这一帧就是可见的。
            //
            //   ② **有节流**（`coverFixAllowed` 800ms 一次）。宿主若持续压 alpha，
            //      800ms 才校正一次 ⇒ 肉眼看到的就是「一直不显示」。
            //      播放页窗口里改为<b>每帧都校正</b>（只在 alpha 真的 <0.35 时才动手，
            //      改完就没下一次了，不会形成循环）。
            boolean allowFix = win || BgPeel.coverFixAllowed(v);
            if (!allowFix) return false;
            try {
                v.setAlpha(1f);
                if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
                // ③ **祖先也可能被压到 0** —— 这是 r68 实证的真病根：
                //    `ReactViewGroup 1272x2772 alpha=0.0` 里面才装着封面/标题/按钮。
                // ★★★ v0.1.66（r69 回归）：**只沿「封面这条链」往上走 5 层**，
                //   不再要求祖先自己也能判定成播放页（ReactViewGroup 上那条常为 false），
                //   改用窗口信号 `sig`。
                //   为什么必须收窄：v0.1.65 曾在「所有全屏大容器」上无差别拉 alpha，
                //   结果把宿主的**隐藏模态**（R18 提示页 / 全屏触摸拦截层）一起揭了出来
                //   ⇒ 用户侧表现为「提示成人向作品被隐藏 + 按钮点不动 + 放不了」。
                //   沿封面链走只可能碰到「装着封面/标题/按钮的那几个容器」，不会揭出无关模态。
                Object up = v.getParent();
                for (int i = 0; i < 5 && up instanceof View; i++) {
                    View pv = (View) up;
                    if (sig && pv.getAlpha() < 0.05f) {
                        pv.setAlpha(1f);
                        if (BgPeel.sAlphaFixLogs < 3) {
                            BgPeel.sAlphaFixLogs++;
                            BgPeel.log("cover ancestor lift -> " + BgPeel.name(pv) + " "
                                    + pv.getWidth() + "x" + pv.getHeight());
                        }
                    }
                    up = pv.getParent();
                }
                if (BgPeel.sCoverFixLogs < 3) {
                    BgPeel.sCoverFixLogs++;
                    BgPeel.log("cover alpha fix -> " + BgPeel.name(v) + " " + w + "x" + h
                            + (win ? " win" : " ctx"));
                }
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }


    static boolean rootHasReviewCue(View v) {
        View root;
        try {
            root = v.getRootView();
        } catch (Throwable t) {
            return false;
        }
        if (root == null) return false;
        long frame = android.os.SystemClock.uptimeMillis() / 48L;
        int id = System.identityHashCode(root);
        if (frame == BgPeel.sRootCueFrame && id == BgPeel.sRootCueId) return BgPeel.sRootCueHit;
        BgPeel.sRootCueFrame = frame;
        BgPeel.sRootCueId = id;
        // 只认月度回顾文案：首页根上也有「你听了」卡片，不能整窗放行。
        BgPeel.sRootCueHit = bfsMonthlyCue(root, 0, 0);
        return BgPeel.sRootCueHit;
    }


    /** 开屏 logo 背后那层近白方底：不画，否则圆角外一圈白框会闪一下。 */
    static boolean splashWhitePlate(View v) {
        if (!underSplash(v)) return false;
        int w = v.getWidth();
        int h = v.getHeight();
        if (w < 80 || h < 80) return false;
        int min = Math.min(w, h);
        int max = Math.max(w, h);
        if (max * 100 > min * 130) return false;
        int c = plateColor(v);
        int a = (c >>> 24) & 0xFF;
        if (a < 200) return false;
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        return r > 220 && g > 220 && b > 220;
    }


    static int themeRgbNear(View v) {
        View p = v;
        for (int i = 0; i < 4 && p != null; i++) {
            int c = plateColor(p);
            int a = (c >>> 24) & 0xFF;
            int rgb = c & 0x00FFFFFF;
            if (a >= 8 && a <= 210 && chromatic(rgb)) {
                BgPeel.sCoverThemeRgb = rgb;
                return rgb;
            }
            Object par;
            try {
                par = p.getParent();
            } catch (Throwable t) {
                break;
            }
            p = par instanceof View ? (View) par : null;
        }
        return 0;
    }


    /** 紧贴封面的那层底色透明度；没有可读纯色底返回 -1。 */
    static int tightPlateAlpha(View image) {
        View p;
        try {
            Object par = image.getParent();
            p = par instanceof View ? (View) par : null;
        } catch (Throwable t) {
            return -1;
        }
        int iw = image.getWidth();
        int ih = image.getHeight();
        for (int i = 0; i < 3 && p != null; i++) {
            int pw = p.getWidth();
            int ph = p.getHeight();
            boolean tight = pw > 0 && ph > 0
                    && pw <= iw + Math.max(24, iw / 5)
                    && ph <= ih + Math.max(24, ih / 5);
            if (!tight) break;
            int a = plateAlpha(p);
            if (a >= 0) return a;
            Object par;
            try {
                par = p.getParent();
            } catch (Throwable t) {
                break;
            }
            p = par instanceof View ? (View) par : null;
        }
        return -1;
    }


    /** 挖实心底时用：宽条（回顾文案 / 大按钮）绝不挖空。 */
    static boolean tooWideToDig(int w) {
        return BgPeel.sSW > 0 && w * 100 >= (long) BgPeel.sSW * 70;
    }


    /**
     * 只认「自己落在 Splash 子树里」。
     * 以前顺带扫整棵 DecorView，开屏尚未关掉时立刻收听的封面全被放过，
     * 要等切后台 / 切底栏才盖上 —— 就是用户说的启动瞬间露馅。
     */
    static boolean underSplash(View v) {
        View cur = v;
        for (int i = 0; i < 14 && cur != null; i++) {
            try {
                if (isSplashView(cur)) return true;
            } catch (Throwable ignored) {
            }
            Object par;
            try {
                par = cur.getParent();
            } catch (Throwable t) {
                break;
            }
            cur = par instanceof View ? (View) par : null;
        }
        return false;
    }


    static boolean underViewPager(View v) {
        View cur = v;
        for (int i = 0; i < 12 && cur != null; i++) {
            try {
                String n = cur.getClass().getName();
                if (n != null && n.contains("ViewPager")) return true;
            } catch (Throwable ignored) {
            }
            Object par;
            try {
                par = cur.getParent();
            } catch (Throwable t) {
                break;
            }
            cur = par instanceof View ? (View) par : null;
        }
        return false;
    }


    static boolean wrapsCoverArt(View v) {
        return findCoverDesc(v, v, 0);
    }

}
