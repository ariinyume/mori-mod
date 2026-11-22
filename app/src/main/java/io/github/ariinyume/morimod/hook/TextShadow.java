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
import android.text.Layout;
import android.text.TextPaint;
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
 * BgPeel 职责拆分（v0.1.146 B7）· TextShadow。
 * 共享 static 状态仍在 BgPeel；本类只承载本职责的方法。
 */
final class TextShadow {

    private TextShadow() {
    }

    /**
     * 把加过的文字阴影全部撤掉（v0.1.20 用户第 5 条）。
     *
     * <p>{@link #ensureShadow} 在 {@code BgPeel.sArmed} 为假时直接 return，所以不会自己清 ——
     * 必须在这里主动遍历（只有真的加过阴影的那批 TextView 在表里，量很小）。
     */
    static void clearAllShadows() {
        java.util.List<TextView> list;
        synchronized (BgPeel.SHADOWED) {
            if (BgPeel.SHADOWED.isEmpty()) {
                BgPeel.LIGHT_BG.clear();
                return;
            }
            list = new java.util.ArrayList<>(BgPeel.SHADOWED.keySet());
        }
        for (TextView tv : list) {
            try {
                try {
                    tv.setShadowLayer(0f, 0f, 0f, 0);
                } catch (Throwable t) {
                    Paint p = tv.getPaint();
                    if (p != null) p.clearShadowLayer();
                    tv.invalidate();
                }
                BgPeel.SHADOWED.remove(tv);
            } catch (Throwable ignored) {
            }
        }
        BgPeel.LIGHT_BG.clear();
    }


    /**
     * ★ v0.2.3 / v0.2.6：是否月度回顾 UI。
     * <p>不依赖类名含 Dialog；token / 底图 / 软确认根 / 文案 cue 任一命中即可。
     */
    static boolean isReviewUi(View v) {
        if (v == null) return false;
        try {
            View root = v.getRootView();
            if (root != null && BgPeel.sReviewToken != null
                    && root.getWindowToken() == BgPeel.sReviewToken) {
                return true;
            }
            View soft = BgPeel.sReviewUiRoot == null ? null : BgPeel.sReviewUiRoot.get();
            if (soft != null) {
                // soft 可能是 DecorView，也可能是故事容器：祖先命中即可
                if (soft == root || soft == v) return true;
                View cur = v;
                for (int i = 0; i < 24 && cur != null; i++) {
                    if (cur == soft) return true;
                    ViewParent p = cur.getParent();
                    cur = p instanceof View ? (View) p : null;
                }
            }
            View marked = BgPeel.sReviewBgRef == null ? null : BgPeel.sReviewBgRef.get();
            if (marked != null) {
                View mr = marked.getRootView();
                if (mr != null && mr == root) return true;
            }
            // 底图 token 尚未钉住时：文案 cue 也能先上阴影
            if (CoverLayer.cueHit(v, BgPeel.MONTHLY_REVIEW_CUES)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    static void ensureShadow(TextView tv) {
        if (!BgPeel.sArmed) return;
        try {
            boolean reviewUi = isReviewUi(tv);
            CharSequence cs = null;
            try {
                cs = tv.getText();
                // 单字符默认跳过；月度回顾榜单序号「1」…「5」仍要阴影
                if (cs != null && cs.length() <= 1) {
                    boolean reviewDigit = reviewUi && cs.length() == 1
                            && Character.isDigit(cs.charAt(0));
                    if (!reviewDigit) return;
                }
            } catch (Throwable ignored) {
            }
            float ts = tv.getTextSize();
            if (ts <= 0f) return;
            // 超宽跑马灯字条：必须保留阴影；只是不要为它反复 invalidate。
            boolean wideMarquee = BgPeel.sSW > 0 && tv.getWidth() > BgPeel.sSW + BgPeel.sSW / 5;
            Float last = BgPeel.SHADOWED.get(tv);
            // ★ v0.2.6：白底按钮字仍不加阴影；首页回顾卡片短文案在壁纸上必须有阴影。
            if (!reviewUi && CoverLayer.cueHit(tv, BgPeel.BUTTON_CUES)) {
                if (last != null && last > 0f) {
                    try {
                        tv.setShadowLayer(0f, 0f, 0f, 0);
                    } catch (Throwable t) {
                        Paint p = tv.getPaint();
                        if (p != null) p.clearShadowLayer();
                        if (!wideMarquee) tv.invalidate();
                    }
                    BgPeel.SHADOWED.put(tv, 0f);
                } else {
                    BgPeel.SHADOWED.put(tv, 0f);
                }
                return;
            }
            // ★ v0.2.9：全页面统一轻阴影（字号 8%、无偏移）；不再给回顾页加重/软件层。
            float ratio = BgPeel.TEXT_SHADOW_RADIUS_RATIO;
            float radius = Math.max(ts * ratio, 0f);
            float dy = 0f;
            float dx = 0f;
            int col = shadowColor();
            // v0.1.121/122：半径缓存命中时仍写回 Paint（不 invalidate）。
            if (last != null && Math.abs(last - radius) < 0.01f) {
                if (last > 0f) {
                    Paint p0 = tv.getPaint();
                    if (p0 != null) p0.setShadowLayer(radius, dx, dy, col);
                }
                return;
            }
            // ★★★ v0.1.42（r13 用户第 2 条）：**阴影不再看「坐在什么底上」**。
            //   原来那条判据是想「亮底上不打黑阴影」，但它量化的是<b>宿主声明的颜色</b>
            //   —— 而我们已经把这棵树的底色换成了壁纸，甚至把原来那层深色底 drop 掉了
            //   ⇒ 宿主那个颜色<b>在屏幕上根本不存在</b>。用它判断的直接后果就是：
            //   媒体页菜单栏被选中、变成粗体的那几个字被判成「坐在亮底上」⇒ r=0
            //   ⇒ 主动 clearShadowLayer() ⇒ 用户连着几轮说的「选中粗体没有阴影」。
            //   阴影只是「黑、80%、半径 = 字号 8%」，在真·浅底上几乎看不出来，
            //   在壁纸上却是可读性的关键 ⇒ 一律加，不再做二选一。
            // 探针只在首次见到该签名时采样；稳态切页不再跑 onLightSurface。
            String shKey = "sh:" + BgDiag.signature(tv);
            if (!BgPeel.ONCE.containsKey(shKey) && BgPeel.sShadowLogs < BgPeel.SHADOW_LOG_BUDGET) {
                boolean light = onLightSurface(tv);
                if (BgDiag.logOnce(shKey, "shadow ts=" + (int) ts + " r=" + (int) radius
                        + " light=" + (light ? 1 : 0)
                        + " dark=" + (textIsDark(tv) ? 1 : 0)
                        + " tx=#" + String.format(java.util.Locale.ROOT, "%08x", BgPeel.curColor(tv))
                        + " n=" + textOf(tv))) {
                    BgPeel.sShadowLogs++;
                }
            }
            // ★★★ v0.1.127：首次 / 半径变化走 TextView#setShadowLayer。
            //   只改 Paint 时 mShadowRadius 仍是 0；RN 随后 setTextColor →
            //   updateTextColors 只恢复 mShadow*，阴影被冲掉，直到页内再切功能
            //   触发一次完整重录才「突然有阴影」。这条 API 会写 mShadow* 并 invalidate。
            if (radius <= 0f) {
                try {
                    tv.setShadowLayer(0f, 0f, 0f, 0);
                } catch (Throwable t) {
                    Paint p = tv.getPaint();
                    if (p != null) p.clearShadowLayer();
                }
            } else if (wideMarquee) {
                // 超宽条：只改 Paint，避免 invalidate 放大切页抖动。
                Paint p = tv.getPaint();
                if (p != null) p.setShadowLayer(radius, dx, dy, col);
            } else {
                try {
                    tv.setShadowLayer(radius, dx, dy, col);
                } catch (Throwable t) {
                    Paint p = tv.getPaint();
                    if (p != null) p.setShadowLayer(radius, dx, dy, col);
                    tv.invalidate();
                }
            }
            BgPeel.SHADOWED.put(tv, radius);
        } catch (Throwable ignored) {
        }
    }


    /**
     * 这个 TextView 是不是坐在「亮底」上（用户第 6 / 第 4 条）。
     *
     * <h3>v0.1.21：为什么改成「真渲染采样」，而不是读 drawable 里的颜色字段</h3>
     * v0.1.20 用的是反射扫 <b>名字里带 color</b> 的 int 字段 —— 但 RN 的
     * {@code BackgroundDrawable} 被 R8 混淆成 {@code h : I}（dex 实证），字段名里根本没有
     * "color" ⇒ 永远读不到 ⇒ 白底按钮照样被加黑阴影（用户连着两轮反馈）。
     *
     * <p>现在换成两条都不依赖类名的判据：
     * <ol>
     *   <li><b>文字本身是深色</b>（{@code getCurrentTextColor()} 亮度 &lt; {@link #BgPeel.DARK_TEXT_LUMA}）
     *       ⇒ 它必然坐在浅底上（深字压深底谁都看不见），直接不加阴影；</li>
     *   <li>否则沿父链找最近一层<b>实心背景</b>，把它的 drawable <b>缩小画进 8×8 位图</b>求平均色
     *       （纯读操作，不改 bounds ⇒ 不会触发宿主的无限重绘）。
     *       ⚠️ 跳过「我们本来就会换掉的那几类」（满屏 / 条 / 面板），否则采到的是壁纸自己的颜色。</li>
     * </ol>
     *
     * <p>结果按 TextView 缓存（WeakHashMap），一支文字一辈子算一次。
     */
    static boolean onLightSurface(TextView tv) {
        // ★★ v0.1.26 用户第 3 条：**判据全部改成静态属性**，一次都不看「现成像素」。
        //   以前这里把 View 自己的 drawable 缩成 8×8 位图求平均色 —— 按下按钮时
        //   ripple / state 变化会让采样翻面，阴影突然出现一帧（用户上一轮的抱怨）。
        //   现在只用两样东西：文字自己的颜色、自己那块底**烘死**的颜色。二者都与
        //   按下状态无关 ⇒ 阴影要么一直有，要么一直没有，绝不闪。
        // ★★ v0.1.27 用户第 2 条：**必须沿父链找底**。
        //   r32 现场：「媒体库里的『没看过』按钮日常是淡主题色，点击后变白，变白时
        //   文字就挂上了阴影」。真因是这里只看 `tv.getBackground()` —— 按钮的白色胶囊
        //   是 **Text 的父 View** 的背景，Text 自己没底 ⇒ 判成「坐在壁纸上」⇒ 加阴影。
        //   现在规则改成一句话：<b>只要这三层里有任何一块不透明的底（按钮 / 卡片 / 列表行），
        //   就不加阴影</b>；对比度交给那块底自己负责。只有真的直接坐在壁纸 / 图片上才加。
        try {
            // ★★★ v0.1.41（r12 用户第 2 条）：**删掉「深字 ⇒ 坐在亮底上」这条规则**。
            //   它把规则写反了：选中态的文案被强调色 LUT 换成 #ff8a4a49 / #ffc79592
            //   这类偏深色 ⇒ textIsDark 为 true ⇒ 这里返回 true ⇒ radius=0
            //   ⇒ 主动 clearShadowLayer() ⇒「只有选中的粗体没有阴影」。
            //   未选中的浅灰字不满足 textIsDark ⇒ 反而正常加上了阴影
            //   —— 与用户看到的现象完全对上。
            //
            //   现在唯一判据是「坐在什么底上」，与字色深浅无关：
            //     · 半透明底（玻璃 / 壁纸透过来）⇒ 需要阴影；
            //     · 不透明 + 亮度 ≥ BgPeel.LIGHT_BG_LUMA（白胶囊按钮）⇒ 不需要；
            //     · 不透明 + 深色（深色卡片）⇒ 也不需要（深底上的浅字本来就有对比）。
            View cur = tv;
            for (int i = 0; i < 3 && cur != null; i++) {
                Drawable d = cur.getBackground();
                if (d != null) {
                    // 半透明底（玻璃卡片 / 压暗层）⇒ 壁纸透过来，仍然需要阴影
                    if (d.getOpacity() != PixelFormat.OPAQUE) return false;
                    Integer c = BgPeel.solidColorOf(d);             // RN BackgroundDrawable 里的 int
                    if (c != null) {
                        int a = (c >>> 24) & 0xff;
                        if (a < 200) return false;           // 实色但半透明：还是叠在图上
                        return BgPeel.luma(c) >= BgPeel.LIGHT_BG_LUMA;     // 亮实心底：不需要阴影
                    }
                    return true;                             // 不透明但读不出颜色：保守当实底
                }
                ViewParent p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
            }
            return false;                                    // 直接坐在壁纸 / 玻璃上 ⇒ 加阴影
        } catch (Throwable t) {
            return false;
        }
    }


    /**
     * ★★★ v0.1.126：主动给树上文字补阴影（不整树 invalidate）。
     *
     * <h3>为什么要单独走这一条</h3>
     * v0.1.94 起 {@link #repaintLayers} 在武装翻转时<b>故意不作废任何 View</b>
     * （{@code BgPeel.REPAINT_LAYERS_ENABLED=false}），避免切歌 200ms+。副作用是：
     * 壁纸刚 armed / 强调色刚生效时，已经画过的 TextView <b>不会再走</b>
     * {@code View#draw → ensureShadow}，阴影要等用户滑动/切页才出现 ——
     * 这就是「字体阴影没有及时渲染」。
     *
     * <p>这里只 DFS TextView，清掉半径缓存后调 {@link #ensureShadow}
     * （它自己会按需 invalidate 单行文字），容器与列表一律不碰。
     */
    static void refreshTextShadows(View root, String why) {
        if (!BgPeel.sArmed || root == null) return;
        int n = 0;
        try {
            n = refreshTextShadowsWalk(root, 0, 0);
        } catch (Throwable ignored) {
        }
        if (n > 0 && BgPeel.sDbgRedraw < BgPeel.DBG_REDRAW_BUDGET) {
            BgPeel.sDbgRedraw++;
            BgPeel.log("shadowRefresh ← " + why + " n=" + n);
        }
    }


    static int refreshTextShadowsWalk(View v, int depth, int n) {
        if (v == null || depth > 40 || n >= 240) return n;
        try {
            if (v instanceof TextView) {
                TextView tv = (TextView) v;
                if (BgPeel.inAppWindow(tv) && !BgPeel.foreignModuleView(tv)) {
                    // 清缓存强制走「设阴影 + invalidate」分支，否则半径命中只改 Paint
                    // 不重录 display list，硬件层上仍是「没阴影」的旧帧。
                    BgPeel.SHADOWED.remove(tv);
                    ensureShadow(tv);
                    n++;
                }
                return n;
            }
        } catch (Throwable ignored) {
            return n;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int c;
            try {
                c = g.getChildCount();
            } catch (Throwable t) {
                return n;
            }
            for (int i = 0; i < c && n < 240; i++) {
                try {
                    n = refreshTextShadowsWalk(g.getChildAt(i), depth + 1, n);
                } catch (Throwable ignored) {
                }
            }
        }
        return n;
    }


    static void scheduleShadowRefresh(final View root, final String why) {
        if (!BgPeel.sArmed || root == null) return;
        try {
            // 月度回顾窗允许刷；勿被 play 误判挡掉
            boolean review = false;
            try {
                if (BgPeel.sReviewToken != null
                        && root.getWindowToken() == BgPeel.sReviewToken) {
                    review = true;
                }
            } catch (Throwable ignored) {
            }
            if (!review && (PlayPage.isPlayWindow(root) || PlayPage.playContext(root))) return;
        } catch (Throwable ignored) {
        }
        long now = System.currentTimeMillis();
        if (now - BgPeel.sShadowSchedAt < 100L) return;
        BgPeel.sShadowSchedAt = now;
        final long[] delays = {36L, 200L};
        for (final long delay : delays) {
            BgPeel.MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!BgPeel.sArmed || root.getWindowToken() == null) return;
                        if (!root.isShown()) return;
                        refreshTextShadows(root, why + "@" + delay);
                    } catch (Throwable ignored) {
                    }
                }
            }, delay);
        }
    }


    /**
     * 文字阴影颜色：默认纯黑；开了强调色就换成「同一 alpha 的强调色调」。
     *
     * <p>用户第 4 条要的是「阴影蒙层与强调色观感一致、透明度不变」——
     * 所以这里只换 RGB，alpha 固定 {@link #BgPeel.TEXT_SHADOW_ALPHA}。
     */
    static int shadowColor() {
        return AccentColor.on() ? AccentColor.shadow(BgPeel.TEXT_SHADOW_ALPHA)
                : (BgPeel.TEXT_SHADOW_ALPHA << 24);
    }

    /**
     * ★ v0.2.10：月度回顾故事页补轻量四方向描边。
     * <p>宿主 2.24.0 回顾文案来自远端 i18n，仍走 RN TextView；ColorOS 常吞掉
     * {@code setShadowLayer}，截图上呈「纯白无阴影」。仅在 {@link #isReviewUi} 时补描，
     * 首页回顾卡 / 普通列表不走此路径，避免深描边污染。
     */
    static void paintFauxShadow(Canvas cv, TextView tv) {
        if (cv == null || tv == null || !BgPeel.sArmed) return;
        try {
            if (!isReviewUi(tv)) return;
            Layout layout = tv.getLayout();
            if (layout == null) return;
            CharSequence cs = tv.getText();
            if (cs == null || cs.length() == 0) return;
            TextPaint tp = tv.getPaint();
            if (tp == null) return;
            int old = tp.getColor();
            float oldSw = tp.getStrokeWidth();
            Paint.Style oldStyle = tp.getStyle();
            // 与 ensureShadow 同 alpha；步长约字号 4%，四向轻描
            int shadow = shadowColor();
            float step = Math.max(1.0f, tv.getTextSize() * 0.04f);
            int pl = tv.getTotalPaddingLeft();
            int pt = tv.getTotalPaddingTop();
            cv.save();
            cv.translate(pl, pt);
            tp.setColor(shadow);
            final float[][] offs = {
                    {-step, 0}, {step, 0}, {0, -step}, {0, step},
            };
            for (float[] o : offs) {
                cv.save();
                cv.translate(o[0], o[1]);
                layout.draw(cv);
                cv.restore();
            }
            tp.setColor(old);
            tp.setStrokeWidth(oldSw);
            tp.setStyle(oldStyle);
            cv.restore();
        } catch (Throwable ignored) {
        }
    }


    /** 文字本身是不是深色（深字 ⇒ 只可能坐在浅底上）。 */
    static boolean textIsDark(TextView tv) {
        try {
            int c = tv.getCurrentTextColor();
            if (Color.alpha(c) < 200) return false;   // 透明/半透明文字：不猜
            return BgPeel.luma(c) < BgPeel.DARK_TEXT_LUMA;
        } catch (Throwable t) {
            return false;
        }
    }


    /** 诊断用：TextView 前 6 个字（看日志就知道是哪一行文字）。 */
    static String textOf(TextView tv) {
        try {
            CharSequence cs = tv.getText();
            if (cs == null) return "-";
            String s = cs.toString().replace('\n', ' ');
            return s.length() > 6 ? s.substring(0, 6) : s;
        } catch (Throwable t) {
            return "-";
        }
    }

}
