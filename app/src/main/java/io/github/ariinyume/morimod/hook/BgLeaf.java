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
 * BgPeel 职责拆分（v0.1.146 B7）· BgLeaf。
 * 共享 static 状态仍在 BgPeel；本类只承载本职责的方法。
 */
final class BgLeaf {

    private BgLeaf() {
    }

    /** react-native-screens 页壳：短暂无子节点时也不能当「空叶子」换底。 */
    static boolean isScreenShell(View v) {
        if (v == null) return false;
        try {
            String n = v.getClass().getName();
            if (n == null) return false;
            return n.contains("ScreenContentWrapper")
                    || n.endsWith(".Screen")
                    || n.contains("ScreenStack")
                    || n.contains("ScreensCoordinator");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★ 全宽渐变层（{@code LinearGradientView#onDraw}）：把渐变换成壁纸。
     *
     * <p>拦的是 {@code onDraw}，子 View 由 {@code dispatchDraw} 画 ⇒ 内容零损失。
     * 这条是「播放页背景不生效」（§1）和「PurePlay 紫遮罩」（§3）的落点。
     */
    static boolean dropGradient(View v, Canvas c) {
        if (!BgPeel.sArmed) return false;
        if (WindowBg.videoFullscreen()) return false;
        if (BgPeel.sSW <= 0 || BgPeel.sSH <= 0) return false;
        try {
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0) return false;
            if (!BgPeel.isFullWidth(v)) return false;
            Rect r = BgPeel.dstRect(w, h);
            // ★★★ v0.1.41（r12 路径 A）：**面板头部的渐变直接不画**。
            //   用户第 5 条前半「模糊区上方多出一行淡阴影的条状区域，再分层出现模糊」：
            //   这块渐变和面板体不是同一个 View，两层各自完成时刻不同 ⇒ 分层；
            //   它本身就是一条半透明紫 ⇒ 被看成「淡阴影条」。
            //   路径 A 之后，面板的玻璃来自每块自己的底色 Paint，
            //   这条渐变纯属多余 ⇒ drop（不画）＝ 直接透出下面的玻璃。
            if (BgPeel.isSheetLike(v) && BgPeel.inFloatingLayer(v)) {
                BgPeel.sScrim++;
                BgPeel.sPainted = true;
                BgDiag.hit("drop sheet grad -> ", v, r);
                return true;
            }
            // ★★★ v0.1.153：顶部矮渐变铺同款壁纸（挡穿透），paintTopMask 内挖搜索钮洞。
            int[] loc = BgPeel.LOC.get();
            v.getLocationInWindow(loc);
            if (loc[1] <= BgPeel.sEdgeTop
                    && (BgPeel.sSH <= 0 || h * 100 < (long) BgPeel.sSH * 25)) {
                if (BgPeel.paintTopMask(v, c, r)) {
                    BgPeel.sGrad++;
                    BgDiag.hit("paint top grad -> ", v, r);
                    return true;
                }
                BgPeel.sGrad++;
                BgPeel.sPainted = true;
                BgDiag.hit("drop top grad -> ", v, r);
                return true;
            }
            Bitmap bmp = BgPeel.sWpBmp;
            if (bmp == null) return false;
            if (!PanelGlass.paintAligned(c, bmp, v, r, false)) return false;
            BgPeel.sGrad++;
            BgPeel.sPainted = true;
            BgDiag.hit("drop gradient -> ", v, r);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★★★ v0.1.148：lightNav 不再动顶栏。
     * v0.1.147 的 drop 把宿主顶栏遮罩吃掉 ⇒ 上划内容从遮罩穿过去；遮罩必须留给宿主。
     */
    static boolean swapLeafLight(View v, Canvas c) {
        return false;
    }


    /**
     * 把 {@code ExpoBlurView} 里所有毛玻璃子层置 INVISIBLE。
     *
     * <p>v0.1.43：不只靠一个反射字段 —— 同时扫字段 + 直接遍历子 View，
     * 并清 {@code RenderEffect}（API 31+）。
     */
    static void hideBlurDescendants(Object self,
                                            java.util.List<java.lang.reflect.Field> fields) {
        if (!BgPeel.sArmed || self == null) return;
        if (fields != null) {
            for (java.lang.reflect.Field f : fields) {
                try {
                    Object o = f.get(self);
                    if (o instanceof View) muteBlurView((View) o);
                } catch (Throwable ignored) {
                }
            }
        }
        if (self instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) self;
            try {
                int n = vg.getChildCount();
                for (int i = 0; i < n; i++) {
                    View c = vg.getChildAt(i);
                    if (c == null) continue;
                    String cn;
                    try {
                        cn = c.getClass().getName();
                    } catch (Throwable t) {
                        continue;
                    }
                    if (cn.contains("blurview.BlurView") || cn.endsWith(".BlurView")) {
                        muteBlurView(c);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }


    static void hookDrawable(ClassLoader cl, String clsName, boolean requireOpaque) {
        try {
            Class<?> cls = XposedCompat.findClass(clsName, cl);
            Method draw;
            try {
                draw = cls.getDeclaredMethod("draw", Canvas.class);
            } catch (NoSuchMethodException e) {
                BgPeel.log(clsName + " 没有 draw(Canvas)");
                return;
            }
            draw.setAccessible(true);
            XposedCompat.hookMethod(draw, chain -> {
                try {
                    Object self = chain.getThisObject();
                    Object arg0 = chain.getArg(0);
                    if (self instanceof Drawable && arg0 instanceof Canvas) {
                        // 封面槽的不透明底（列表白块 / PurePlay 纯色块）不要画，
                        // 改由封面那一层自己铺半透明主题色。
                        View plate = BgPeel.ownerView((Drawable) self);
                        if (plate != null && CoverLayer.opaqueCoverPlate(plate, (Drawable) self)) {
                            return null;
                        }
                        long tWp = System.nanoTime();
                        boolean wp = BgPeel.paintWallpaper((Drawable) self, (Canvas) arg0, requireOpaque);
                        long wpMs = (System.nanoTime() - tWp) / 1000000L;
                        if (wpMs >= 8L && BgPeel.sSlowDraw < BgPeel.SLOW_DRAW_BUDGET) {
                            BgPeel.sSlowDraw++;
                            BgPeel.log("slowpaint " + wpMs + "ms hit=" + wp + " on "
                                    + self.getClass().getSimpleName());
                        }
                        if (wp) {
                            // ★★★ v0.1.93：记下「这块背景归我们管」的那个 View。
                            //   重绘时只作废它，别去碰 ScreenStack 那种容器 ——
                            //   容器重录一次 80ms+（见 repaintLayers 的注释）。
                            BgPeel.rememberBgOwner((Drawable) self);
                            return null;   // 已经画好壁纸，宿主原本的纯色/渐变不画
                        }
                    }
                } catch (Throwable ignored) {
                    // 判定/绘制失败就原样画：宁可没效果，也绝不能白屏
                }
                // ⚠️ v0.1.24：这里**不再**反射改 drawable 的背景色字段。
                //   v0.1.23 那样做会「染两次」—— 宿主这块底的颜色本来就出自
                //   ColorPropConverter，LUT（AccentLut）已经把它换成强调色了，
                //   再按 LCh 转一遍色相就是二次映射（颜色会一路漂移）。
                return chain.proceed();
            });
            BgPeel.sHooked = true;
            BgPeel.log("hooked 1 " + clsName + "#draw");
        } catch (Throwable t) {
            BgPeel.log("钩子未装 " + clsName + ": " + t);
        }
    }


    /**
     * ★ v0.1.19 新增：钩 {@code expo-linear-gradient} 的 {@code onDraw}。
     *
     * <h3>为什么必须钩 onDraw，而不是 View#draw</h3>
     * 播放页的紫色渐变<b>带着子内容</b>（封面、进度条、按钮全在里面）。用
     * {@code View#draw} 只能「整层不画」，等于把内容一起丢了（v0.1.17 的血证）。
     * 而 {@code onDraw} 只负责画<b>渐变本身</b>，子 View 由 {@code dispatchDraw} 画 ——
     * 把它拦掉，渐变没了、内容一个不少。
     *
     * <p>判据只看「铺满宽度」（≥94% 屏宽）：卡片、按钮、进度条的渐变都够不到。
     */
    static void hookGradientView(ClassLoader cl) {
        try {
            Class<?> cls = XposedCompat.findClass(BgPeel.CLS_GRADIENT_VIEW, cl);
            Method onDraw;
            try {
                onDraw = cls.getDeclaredMethod("onDraw", Canvas.class);
            } catch (NoSuchMethodException e) {
                BgPeel.log(BgPeel.CLS_GRADIENT_VIEW + " 没有 onDraw(Canvas)");
                return;
            }
            onDraw.setAccessible(true);
            XposedCompat.hookMethod(onDraw, chain -> {
                try {
                    Object self = chain.getThisObject();
                    Object arg0 = chain.getArg(0);
                    if (self instanceof View && arg0 instanceof Canvas
                            && dropGradient((View) self, (Canvas) arg0)) {
                        return null;   // 渐变换成壁纸，子内容照常由 dispatchDraw 画
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            BgPeel.sHooked = true;
            BgPeel.log("hooked 1 " + BgPeel.CLS_GRADIENT_VIEW + "#onDraw（渐变层原地换壁纸）");
        } catch (Throwable t) {
            BgPeel.log("钩子未装 " + BgPeel.CLS_GRADIENT_VIEW + ": " + t);
        }
    }


    /**
     * ★★ v0.1.24 用户第 3 条：钩 {@code ExpoImageViewWrapper#setBlurRadius}。
     *
     * <p>宿主的「封面模糊背景」不是我们能从位图上认出来的（它就是一张普通照片，
     * 只是被 Glide 的模糊 transformation 预先糊过），唯一可靠的标志是<b>那个 prop</b>。
     * 这里在 setter 上把 {@code ExpoImageView} 记进 {@link #BLURRED}，
     * 绘制时 {@link #imgKind} 查到就直接判 {@link #BgPeel.IMG_BLUR}。
     *
     * <p>setBlurRadius 在 prop 更新时调用（不是每帧），成本可忽略。
     */
    /**
     * ★★★ v0.1.57（r57 真机层级 dump 定位）：<b>Skia 画布层</b>。
     *
     * <h3>为什么前六版全都没打中</h3>
     * 播放页那块「纯模糊占位 / 裁成几块 / 透明」的层，层级 dump 里长这样：
     * <pre>
     * ReactViewGroup   0,0-1272,954  #8b8
     * SkiaPictureView  0,0-1272,954  #25d0      ← react-native-skia
     * SkiaTextureView  0,0-1272,954
     * ReactViewGroup   0,0-1272,954  #878  alpha=0.0
     * </pre>
     * 它是 {@code TextureView}：内容由 Skia 在<b>自己的渲染线程</b>画进 SurfaceTexture，
     * 再由合成器直接贴上来 —— <b>根本不走 {@code View#draw}</b>。
     * 所以挂在 draw / blurRadius / 位图 / 几何上的判据对它一次都无效
     * （日志里 `paint play blur big` 始终 0 条就是这个原因）。
     *
     * <h3>唯一能拦住它的地方</h3>
     * {@code TextureView#draw(Canvas)} —— 父层的 dispatchDraw 会调它把纹理贴上去。
     * 在这里<b>铺我们的壁纸并 return null</b>，等于把它盖掉；
     * 而且按屏幕对齐，和下面各层同源同位，不会出现接缝。
     *
     * <p>适用范围收得很紧：只在「播放页 / 播放页窗口的根级层」上动手，
     * 尺寸要求 ≥55% 屏宽 且 ≥18% 屏高；其他页面（含列表页封面）一根手指都不碰。
     */
    static void hookTextureView(ClassLoader cl) {
        try {
            Class<?> cls = XposedCompat.findClass("android.view.TextureView", cl);
            Method draw = cls.getDeclaredMethod("draw", Canvas.class);
            draw.setAccessible(true);
            XposedCompat.hookMethod(draw, chain -> {
                try {
                    Object self = chain.getThisObject();
                    Object a0 = chain.getArg(0);
                    if (BgPeel.sArmed && self instanceof View && a0 instanceof Canvas) {
                        View v = (View) self;
                        Canvas c = (Canvas) a0;
                        // ★★★ v0.1.151：横屏视频全屏 / 非 Skia TextureView 一律放行。
                        //   FullscreenPlayerActivity 的视频面若被当成满屏底换壁纸，
                        //   用户只看到自定义背景、看不到画面（关壁纸后立即恢复）。
                        if (WindowBg.videoFullscreen()) {
                            return chain.proceed();
                        }
                        try {
                            String cn = v.getClass().getName();
                            if (cn == null || !cn.contains("Skia")) {
                                return chain.proceed();
                            }
                        } catch (Throwable t) {
                            return chain.proceed();
                        }
                        int w = v.getWidth();
                        int h = v.getHeight();
                        // PurePlay 播放页的方形封面常走 Skia，不进 ImageView。
                        // 开了封面屏蔽时，按封面尺寸铺半透明主题色，满屏底仍然只换壁纸。
                        if (w > 0 && h > 0 && CoverLayer.coverBlocked(v)) {
                            CoverLayer.paintCoverMask(c, v);
                            BgDiag.traceCover(v, "coverBlock-skia");
                            return null;
                        }
                        if (w > 0 && h > 0
                                && v.getAlpha() >= 0.05f
                                // ★★★ v0.1.58（r58 adb 实测）：这个宿主的播放页背景是一张
                                //   **满屏 SkiaTextureView（1272x2772）**。
                                //   满屏级的 Skia 画布按定义就是「底」，所以这一条
                                //   **不再依赖播放页判定** —— 只要它铺满整屏就直接接管。
                                //   这样「迷你条往返后透明」也不会再漏：那一屏里
                                //   playContext 常常还没成立，但 1272x2772 这张纹理在。
                                // ★★★ v0.1.84（r87 用户「开防偷窥后封面变壁纸模糊块」）：
                                //   **只接管满屏 Skia，绝不再碰非满屏层。**
                                //   之前这里还挂着 `PlayPage.playContext(v) || PlayPage.isPlayWindow(v)` 两道闸，
                                //   它们会把「1272x954」这类非满屏 Skia 层也接管掉 ——
                                //   而那块正是宿主开「防偷窥」后用 Skia 糊出来的**作品封面**（内容），
                                //   一接管就把封面换成播放档壁纸 ⇒ 播放页封面不显示、
                                //   媒体库「作品」的封面变成卡着当前壁纸的模糊块（r87）。
                                //   且这两道闸只在「音频播放中」为真，正好对上「只有播放时才触发」。
                                //   满屏底（1272x2772）另有 CoverDrawable 兜底；
                                //   非满屏的 Skia 层里没有别的「底」了 —— 一律交还宿主去画。
                                && (w * 100 >= (long) BgPeel.sSW * 90 && h * 100 >= (long) BgPeel.sSH * 90)) {
                            // ★★★ v0.1.58（r58 用户第 1 / 3 条）：**改「整屏贴图」，并且必有兜底**。
                            //
                            //   · 用户第 1 条「封面上方那条是不带模糊的自定义背景底部」=
                            //     位图对齐偏了。`paintAligned` 依赖 `coverRect` 的中心裁切，
                            //     一旦这块纹理的画布原点/裁剪和屏幕不完全一致就整体错位。
                            //     这里改成最稳的写法：**把整张壁纸按「屏幕」映射到本地坐标**
                            //     （dst = -loc .. -loc+屏幕尺寸），不依赖任何中心裁切。
                            //   · 用户第 3 条「背景依旧透明」= 这块纹理没画、我们也没铺 ⇒
                            //     透过播放页看到了下层的作品详情页。所以**无论取不取到位图，
                            //     都至少填一层不透明色**，绝不留洞。
                            int[] loc = BgPeel.LOC.get();
                            int[] geo = BgPeel.RGEO.get();
                            // ★★★ v0.1.98：窗口坐标 + 本窗口尺寸（浮窗下窗口 ≠ 屏幕）。
                            //   这一层是播放页的满屏 Skia 底，用它铺「屏幕」的话，
                            //   浮窗里会整体平移 BgPeel.sSW/BgPeel.sSH 与窗口的差 ⇒ 与页面层割裂。
                            try {
                                v.getLocationInWindow(loc);
                            } catch (Throwable ignored) {
                                loc[0] = 0;
                                loc[1] = 0;
                            }
                            if (!BgPeel.rootGeometry(v, geo)) {
                                geo[0] = BgPeel.sSW;
                                geo[1] = BgPeel.sSH;
                            }
                            Rect dst = new Rect(-loc[0], -loc[1],
                                    -loc[0] + geo[0], -loc[1] + geo[1]);
                            // ★★★ v0.1.60：满屏 Skia 画布**总是接管**（否则宿主那层会露出来），
                            //   但用哪张底图要按「这一层是不是播放页」来选 ——
                            //   列表页/媒体库用清晰壁纸，播放页用播放档（壁纸+模糊）。
                            //   否则就会出现「媒体库封面变成播放档壁纸的模糊色块」（用户第 3 条）。
                            // ★★★ v0.1.62（r62 adb 层级实测）：**接缝的根因找到了**。
                            //
                            //   播放页上其实<b>三层叠着</b>（层级 dump 顺序 = 绘制顺序）：
                            //   <pre>
                            //   SkiaTextureView  1272x2772   ← 满屏底
                            //   ExpoImageView    1272x2913   ← 满屏模糊底（走 paintBlurBg）
                            //   SkiaTextureView  1272x954    ← 顶部横幅，画在最上面！
                            //   </pre>
                            //   原先这里按 {@code PlayPage.playContext(v)} 选底图 —— 而这块顶部横幅
                            //   是**窗口根级**的 Skia 层，逐层判定经常是 false，
                            //   于是它铺的是<b>清晰壁纸</b>，下面两层铺的是<b>播放档（壁纸+模糊）</b>
                            //   ⇒ 顶部那块和主体「不是同一张、模糊度也不同」= 用户说的
                            //   「另一截壁纸 / 越来越多色彩切片」。
                            //   现在：只要这个窗口是播放页窗口（2.5 秒旗子），一律用播放档，
                            //   三层同源同位，接缝自然消失。
                            boolean onPlay = PlayPage.playContext(v) || PlayPage.isPlayWindow(v);
                            Bitmap bmp = onPlay ? PlayPage.playBmp() : BgPeel.sWpBmp;
                            if (bmp != null && !bmp.isRecycled()) {
                                BgPeel.WASH_PAINT.setColor(0xFFFFFFFF);
                                BgPeel.WASH_PAINT.setAlpha(255);
                                BgPeel.WASH_PAINT.setFilterBitmap(true);
                                c.drawBitmap(bmp, null, dst, BgPeel.WASH_PAINT);
                            } else {
                                c.drawColor(0xFF14203A);   // 兜底：绝不留透明洞
                            }
                            BgPeel.sBar++;
                            BgPeel.sPainted = true;
                            BgDiag.hit("paint texture -> ", v, dst);
                            return null;   // 宿主这层 Skia 纹理不画
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            BgPeel.log("hooked TextureView#draw（Skia 渲染层 → 播放页换成壁纸）");
        } catch (Throwable t) {
            BgPeel.log("钩子未装 TextureView#draw: " + t);
        }
    }


    /** 单层：INVISIBLE + 清 RenderEffect + alpha=0（三保险）。 */
    static void muteBlurView(View v) {
        if (v == null) return;
        try {
            boolean changed = false;
            if (v.getVisibility() != View.INVISIBLE) {
                v.setVisibility(View.INVISIBLE);
                changed = true;
            }
            if (v.getAlpha() > 0.01f) {
                v.setAlpha(0f);
                changed = true;
            }
            try {
                Method sre = View.class.getMethod("setRenderEffect",
                        android.graphics.RenderEffect.class);
                sre.invoke(v, new Object[]{null});
            } catch (Throwable ignored) {
            }
            if (changed) {
                BgPeel.log("blur layer hidden -> " + BgPeel.name(v) + " " + v.getWidth() + "x" + v.getHeight());
            }
        } catch (Throwable ignored) {
        }
    }


    /**
     * ★ 满屏层 / 顶底栏叶子：要么换成壁纸，要么整层不画。
     *
     * <h3>判据顺序（每一条都是真机血证）</h3>
     * <ol>
     *   <li>{@code TextView}：只加阴影，别的都不动（{@link #ensureShadow}）。</li>
     *   <li>满屏 + <b>有子 View</b>：放行。跳了子内容全没（v0.1.17）。</li>
     *   <li>视频 / 图片 / 文字层：放行（它们画的是内容）。</li>
     *   <li>满屏 + 叶子 + 半透明（View 或背景）：<b>不画</b> ⇒ 露出壁纸（遮罩消失）。</li>
     *   <li>满屏 + 叶子 + 不透明：换成壁纸。</li>
     *   <li>全宽叶子条 + <b>不透明底</b>：换成模糊壁纸（毛玻璃）。</li>
     *   <li>全宽叶子条 + <b>透明/半透明</b>：<b>不画</b>。宿主本来就在这上面画半透明
     *       scrim，涂成不透明会把画在它前面的图标糊掉（r24「放大镜上半截消失」）；
     *       不画则直接透出壁纸（用户要的「导航栏直接透出背景」）。</li>
     * </ol>
     */
    static boolean swapLeaf(View v, Canvas c) {
        if (!BgPeel.sArmed) return false;
        if (WindowBg.videoFullscreen()) return false;
        if (BgPeel.sSW <= 0 || BgPeel.sSH <= 0) return false;
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) return false;
        int kids = 0;
        try {
            kids = v instanceof ViewGroup ? ((ViewGroup) v).getChildCount() : 0;
        } catch (Throwable ignored) {
        }
        Rect r = BgPeel.dstRect(w, h);
        try {
            if ((long) w * h >= BgPeel.sMinArea) {
                BgPeel.noteBig(v, w, h, kids);
                // ★ 铁律①：容器永不跳过（r23「按钮/选项/列表消失」的唯一原因）
                if (kids > 0) return false;
                if (BgPeel.isVideoSurface(v)) return false;
                // ★★★ v0.1.146：WebView / RNCWebView 自己画 HTML（隐私政策、使用条款）。
                //   kids=0 但不是「空叶子」—— 铺壁纸会整页盖住正文（设定→帮助/关于）。
                if (BgPeel.isWebContentView(v)) return false;
                // ★★★ v0.1.147：Screen 容器短暂 kids=0 时绝不能整页铺壁纸并跳过 proceed，
                //   否则迷你条 / 页面内容整页变空占位。
                if (isScreenShell(v)) return false;
                // ★ 图片层不碰：它画的是内容，不是底色
                //   ★★ v0.1.21：唯一例外 —— **满屏不透明氛围底图**（播放页那张
                //   playbackspbackground）。它不是内容，是铺在最底下的底色，
                //   换成壁纸只可能露出壁纸；RGB 内容图（主页立绘）在 imgKind 里被判成
                //   content 直接放行。顺手留一条 imglayer 诊断，下一轮能直接看到
                //   播放页到底是不是这条路画的。
                if (BgPeel.isImageLayer(v)) {
                    if (w >= BgPeel.sFullW && h >= (int) (BgPeel.sSH * BgPeel.VEIL_MIN_H_RATIO)) BgPeel.noteImage(v, w, h);
                    // ★★★ v0.1.77（r80 第 4 / 5 条）：**内容图放行，不进 atmos / fallback。**
                    //   `paintAtmos` / `paintPlayFallback` 都会把这层换成壁纸（return true 不画原图）——
                    //   对「氛围底图」是对的，对「用户点开的那张图 / 概述里的插图」就是「图不显示」。
                    //   两者靠 imgKind 分：底色是 ATMOS/BLUR，内容图是 CONTENT。
                    if (CoverLayer.imgKind(v) == BgPeel.IMG_CONTENT) return false;
                    // ★★★ v0.1.41：弹层玻璃**只留在 {@code ExpoImageView#draw} 那条钩子里**
                    //   （那里才有 chain，才能把宿主那张紫图按低透明度叠回来）。
                    //   这里如果再处理一次，就是「同一块图被两条路各画一遍」——
                    //   v0.1.40 的「分层出现模糊」有它一份。
                    if (PanelGlass.paintAtmos(v, c)) return true;
                    // ★★ v0.1.29 用户第 1 条：满屏图片层「这张图压根读不出来」时补一层
                    //   播放页壁纸 —— 见 paintPlayFallback 的注释。
                    if (PlayPage.paintPlayFallback(v, c, w, h)) return true;
                    return false;
                }

                // ★★ v0.1.23 用户第 1 条：满屏叶子层**永远不留洞**。
                //   v0.1.19~v0.1.22 遇到「满屏 + 半透明」就整层不画 —— 那是个洞。
                //   正常切页面时洞后面刚有别的层画过（侥幸），切后台再回前台绘制顺序一变，
                //   洞后面什么都没有 ⇒ 播放页背后「全透明」（用户连着反馈两轮）。
                //   现在改成**画壁纸**：它下面是壁纸，画同一份像素等于「透出壁纸」，
                //   但没有任何洞，也不多遮任何东西（它是叶子，没有子内容可丢）。
                boolean translucent = v.getAlpha() < 0.99f;
                Drawable bg = v.getBackground();
                if (!translucent && bg != null && bg.getOpacity() != PixelFormat.OPAQUE) {
                    translucent = true;
                }

                // ★★ v0.1.23：**模态弹窗的压暗遮罩必须继续放过**（原样由宿主画）。
                //   它画在弹窗内容之上，一旦涂成不透明毛玻璃，弹窗后面的东西就全被糊掉了
                //   —— 这正是 v0.1.23 用户第 2 条「面板一出来，背后的主界面也跟着糊」的机制。
                //   判据看背景色本身：极暗 / 极亮 + 低彩度 + 半透明 = 宿主的标准 dim 遮罩
                //   （token `overlay/modal1 = #19122ccc`）。
                //   ★ v0.1.24：这里从「不画」改成「放行」—— 不画会留洞（切后台回来露白），
                //   而放行等于交给宿主自己压暗，颜色已经被 LUT 换成强调色，观感一致且零风险。
                // ★★★ v0.1.146：只丢近满屏模态压暗；字幕行 / 设定列表行放行宿主自绘。
                if (translucent && BgPeel.isModalDimSurface(v, bg)) {
                    // ★★★ v0.1.39（r44 用户第 2 / 3 条）：**从「放行」改成「不画」**。
                    //   宿主的弹层遮罩是 token `overlay/modal1 = #9919122C`
                    //   —— 它不是中性灰，是**带紫的深蓝黑**；
                    //   按 60% 带 alpha 铺在亮壁纸上，肉眼就是一层
                    //   「淡紫色的阴影」（用户这一轮的原话）。
                    //   而且它会把**面板周围整片区域**都变成紫灰，
                    //   与“背景应该是壁纸”完全矛盾。
                    //
                    //   v0.1.24 之所以改成放行，担心的是「不画 = 留洞（切后台回来露白）」。
                    //   但这一层是**弹层遮罩**，它在页面内容上方：不画就直接透出
                    //   下面已经铺好的壁纸 / 玻璃，不是一个空洞。
                    //   （用户上一轮已经明确说过「背后的内容淡化效果没了（这个没问题）」。）
                    BgPeel.sScrim++;
                    BgPeel.sPainted = true;
                    BgDiag.hit("drop dim -> ", v, r);
                    return true;
                }

                // ★★ v0.1.23 用户第 3 条：这一层正在做**缩放转场**（打开作品信息页）时，
                //   父层把 scale concat 进了画布，清晰壁纸会跟着放大 / 错位。
                //   用户对这个场景的要求就是「这一块走贴合当前背景的高斯模糊」——
                //   模糊图本来就看不出接缝，缩放几个像素完全自然。
                //   ★ v0.1.27：门槛用 BgPeel.ZOOM_BLUR_MIN（8%）而不是 2% —— 翻图片、
                //   轻微回弹这些「2% 级」的缩放不该把整屏换成毛玻璃。
                // ★★★ v0.1.72（r74）：**档位改成和邻居一致，不再强行用它家毛玻璃**。
                //   错位本身已经在 {@link #paintAligned} 里按 scale 补偿掉了，
                //   唯一还会暴露问题的就是「同一屏里这一层是糊图、旁边几层是清晰壁纸」
                //   —— 那本身就是一条硬边。播放页统一用播放档，其余统一用清晰壁纸。
                if (Math.abs(BgPeel.treeScale(v) - 1f) > BgPeel.ZOOM_BLUR_MIN) {
                    boolean playZoom = PlayPage.playContext(v) || PlayPage.isPlayWindow(v);
                    Bitmap zoomBmp = playZoom ? PlayPage.playBmp() : BgPeel.sWpBmp;
                    if (zoomBmp == null) zoomBmp = BgPeel.sWpBarBmp;
                    if (zoomBmp == null) return false;
                    if (!PanelGlass.paintAligned(c, zoomBmp, v, r, false)) return false;
                    BgPeel.sZoom++;
                    BgPeel.sPainted = true;
                    BgDiag.hit("paint zoom -> ", v, r);
                    return true;
                }

                // ★★ v0.1.26 用户第 2 条：这里以前是「半透明 ⇒ 画毛玻璃」。真机后果是
                //   **整个主界面被糊了一层**（打开作品信息页 / 面板时，这一层是全屏的
                //   半透明底，画什么全屏图就等于给全屏加了一层），用户连着两轮说
                //   「主界面还是会弱模糊，不要这个弱模糊」。
                //   它本来就是「页面底色 / 压暗层」⇒ 应该画**清晰壁纸**（= 透出壁纸），
                //   而不是毛玻璃。模糊只留给真的需要玻璃质感的条与面板。
                // ★★ v0.1.27 用户第 1 条（这是本轮最关键的一条）：
                //   「页面显示的内容（作品封面、音轨信息等）呈现内容显示淡化」。
                //   真因不是模糊，而是这一层：宿主给整页盖了一层 **alpha < 1** 的叶子
                //   （`big ReactViewGroup 1272x2772 kids=0 alpha=0.7`，r32 日志实证）。
                //   View 的 alpha 是通过 `saveLayerAlpha` 施加在**整层画布**上的 ⇒
                //   我们在这里画一张不透明壁纸，等于把壁纸按 0.7 叠在**已经画好的内容上**
                //   ⇒ 内容被「洗淡」，而壁纸反而透出来。
                //   正确动作只有一个：**放行**，让宿主自己画它那层压暗/淡出。
                //   （放行不会留洞：页面底色在上面那条不透明的叶子分支里已经画过了。）
                // ★★ v0.1.28 用户第 1 条「启动宿主第一次播放，播放页背景一定透明」：
                //   上面那句「放行不会留洞」只在 **alpha 已经接近 1** 时成立。
                //   播放页是淡入进场的：这一层的 alpha 从 0 → 1 走动画，
                //   动画前几帧它几乎什么都没画，我们一放行 ⇒ 屏幕上一个洞 ⇒ 全屏透明。
                //   现在按 alpha 三段处理：
                //     · α ≥ 0.99（只是背景半透明）：画壁纸 —— 等价替换，不洗任何内容；
                //     · 0.5 ≤ α < 0.99（压暗 / 淡出层）：放行 —— 洗淡就得靠宿主自己画；
                //     · α < 0.5（淡入前段 / 几乎全透明）：画壁纸 —— 它本来没画出东西，
                //       补上壁纸才没有洞。
                if (translucent) {
                    // ★★★ v0.1.42（r13 用户第 8 条）：**半透明的大叶子一律不画**。
                    //   「弹窗后面的宿主界面完全消失」的元凶就是这里：宿主那个
                    //   RN Modal 在 Dialog 窗口里带一层 1440×3358 / alpha=0.7 的全屏叶子，
                    //   v0.1.41 把它当成「α≥0.99 的底色」整块涂上了<b>不透明壁纸</b>
                    //   —— 那层在 Dialog 窗口里、盖在主窗口之上 ⇒ 弹窗后面的 App 界面
                    //   被一张壁纸整个盖掉（帧 f_0154：只剩壁纸 + 一个白色确认框）。
                    //   Dialog 窗口是透明的，壁纸本来就在主窗口最底下 ⇒ 不画 = 直接透出来。
                    BgPeel.sOverlay++;
                    BgPeel.sPainted = true;
                    BgDiag.hit("drop overlay -> ", v, r);
                    return true;
                }

                Bitmap bmp = PlayPage.pageBmp(v);
                if (bmp == null) bmp = BgPeel.sWpBarBmp;
                if (bmp == null) return false;
                // ★★★ v0.1.42（r13 用户第 8 条）：**浮层里的整屏叶子绝不铺壁纸**。
                //   浮层都跑在独立 Dialog 窗口里，那个窗口是<b>透明</b>的 ——
                //   我们在这里画的每一笔都在「主窗口的 App 界面」之上；
                //   铺一张不透明壁纸 = 把弹窗背后的宿主界面整块盖掉。
                //   （用户第 8 条「显示弹窗后面的宿主 app 界面依旧完全消失」就是它。）
                //   不画才是正确的：壁纸在主窗口 DecorView 上，自然透出来。
                if (BgPeel.inFloatingLayer(v)) {
                    BgPeel.sScrim++;
                    BgDiag.hit("drop leaf float -> ", v, r);
                    return true;
                }
                if (!PanelGlass.paintAligned(c, bmp, v, r, false)) return false;
                // ★★ v0.1.25 用户第 4 条「页面下边缘透出一部分底部导航栏」：
                //   记下「整页底色用的是哪张图 + 它到哪儿结束」，好让紧挨着的底部栏
                //   接着画同一张图（见 barTop 那段），不出现一条模糊度不同的接缝。
                PlayPage.notePageBackdrop(v, h, bmp);
                BgPeel.sLeaf++;
                BgDiag.hit("paint leaf -> ", v, r);
                BgPeel.sPainted = true;
                return true;
            }

            // ★ 全宽叶子条（顶栏 / 底栏 / 迷你播放 / 顶部 scrim）
            if (kids == 0 && BgPeel.isBarLike(v)) {
                if (BgPeel.isVideoSurface(v) || BgPeel.isImageLayer(v)) return false;
                int[] loc = BgPeel.LOC.get();
                v.getLocationInWindow(loc);
                boolean atTop = loc[1] <= BgPeel.sEdgeTop;

                // ★★★ v0.1.151：顶栏叶子一律铺壁纸（含半透明 scrim），挡住上划穿透。
                //   搜索按钮靠 elevateLibraryChrome 抬升，不再靠 drop 留洞保图标。
                if (atTop) {
                    if (BgPeel.paintTopMask(v, c, r)) {
                        BgPeel.sBar++;
                        BgDiag.hit("paint top fill -> ", v, r);
                        return true;
                    }
                    return false;
                }

                // ★★ v0.1.20 修「迷你播放器偶尔变成全透明」：
                //   底部区（迷你播放 / 底部标签栏）**绝不能 drop** —— drop 就是留个洞，
                //   洞后面不一定有壁纸（那一帧可能还没画）⇒ 一瞬间全透明。
                //   本来不透明 ⇒ 换模糊壁纸；本来透明 ⇒ 原样放行（宿主透明就别动它）。
                Drawable bg = v.getBackground();
                boolean opaque = bg != null && bg.getOpacity() == PixelFormat.OPAQUE
                        && v.getAlpha() >= 0.99f;
                if (!opaque) return false;
                // v0.1.150：底栏叶子与 Drawable 路径共用 paintBottomMask。
                if (BgPeel.paintBottomMask(v, c, r)) {
                    BgPeel.sBar++;
                    BgDiag.hit("paint bar leaf -> ", v, r);
                    return true;
                }
                return false;
            }

            // ★★ v0.1.21 用户第 3 条：底部面板本体如果是<b>叶子</b>（一张纯色/图片铺成，
            //   内容在兄弟层上），同样要处理。叶子没有子内容 ⇒ drop 绝不丢东西。
            //   ★★★ v0.1.41（r12 路径 A）：这里从「铺玻璃」改成 **drop**。
            //   铺玻璃＝画一张整屏位图，落点靠 getLocationInWindow 算 ⇒ 就是
            //   「卡住一块壁纸」和「面板消失」的那条路。现在面板的玻璃来自
            //   它自己的底色 drawable（见 paintDrawableGlass），这条叶子层不画即可。
            // ★★★ v0.1.97（r119 用户第 1 条）：**「叶子」整层不画的前提是它不画图。**
            //
            //   r119 现场（logcat，作品封面那层，连续十几次逐字一致）：
            //   <pre>
            //   MoriTrace: ExpoImageView 1272x954 r=0 kind=0 bmp=540x420/sharp -> host
            //   MoriCover: ExpoImageView 1272x954 ... drawable=BitmapDrawable bmp=540x420/sharp -> host-expo
            //   MoriHit  : drop sheet leaf -> ExpoImageView 1272x954 a=1.0 kids=0   ← 就在同毫秒
            //   </pre>
            //   链路是这样的：{@code ExpoImageView} 自己重写了 {@code draw}，我们的
            //   ExpoImage 钩子先跑 → 三条底图判据全不成立（34% 屏高，不是底）⇒
            //   `chain.proceed()` → `super.draw()` → **{@code View#draw} 钩子又跑一遍** →
            //   这里按「宽度≥86% 屏宽 + 高度 12%~92% + 贴底 + 在浮层里 + 没有子 View」
            //   判成「底部面板的叶子底色」⇒ <b>整层不画</b> ⇒ 作品封面直接消失。
            //   所以「封面不显示」根本不是码率/图层问题，是我们自己吃掉了它。
            //
            //   而「叶子可以整层不画」这条规则本来只对<b>一块纯底色 / 毛玻璃</b>成立
            //   （面板本体用纯色铺成、内容在兄弟层上）。只要这层自己还要画一张图，
            //   drop 就等于把内容吃掉 —— 加一道「自己不画图才允许 drop」的闸，
            //   纯色叶子（ReactViewGroup + background）判据完全不受影响。
            //
            //   ⚠️ 只在<b>主窗口那条宽松判据</b>（{@link #mainWindowSheet}）上放宽：
            //   真正的底部面板 / 弹层（{@link #insideBottomSheet} / {@link #inDialogWindow}）
            //   行为<b>一个字都不改</b> —— 那里确实有「纯图片铺成的紫面板底」依赖整层 drop。
            boolean realFloat = BgPeel.insideBottomSheet(v) || BgPeel.inDialogWindow(v);
            if (kids == 0 && !BgPeel.isVideoSurface(v) && BgPeel.isSheetLike(v)
                && (realFloat || (BgPeel.mainWindowSheet(v) && !BgPeel.paintsOwnImage(v)))) {
                BgPeel.sScrim++;
                BgPeel.sPainted = true;
                BgDiag.hit("drop sheet leaf -> ", v, r);
                return true;
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

}
