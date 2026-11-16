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
 * BgPeel 职责拆分（v0.1.146 B7）· PanelGlass。
 * 共享 static 状态仍在 BgPeel；本类只承载本职责的方法。
 */
final class PanelGlass {

    private PanelGlass() {
    }

    /** 「宿主自己那一笔」的抽象：paintDialogGlass 需要把宿主的图按低透明度叠回来。 */
    interface HostDraw {
        Object draw() throws Throwable;
    }


    /**
     * 这张 expo-image 是不是「盖在壁纸上的半透明氛围图」。
     *
     * <p>★★ v0.1.23：以前返回「不画」（留洞），现在改成<b>画毛玻璃</b>。
     * 洞后面的内容靠绘制顺序，切后台回来一变就露白 ⇒ 用户第 1 条。
     * 用户对这一块的要求本来就是「贴合当前背景的高斯模糊」，画模糊壁纸正合适。
     */
    static boolean dropVeil(View v, Canvas c) {
        if (!BgPeel.sArmed) return false;
        if (CoverLayer.inReviewUi(v)) return false;
        if (BgPeel.sSW <= 0 || BgPeel.sSH <= 0) return false;
        int w;
        int h;
        try {
            w = v.getWidth();
            h = v.getHeight();
        } catch (Throwable t) {
            return false;
        }
        if (w <= 0 || h <= 0) return false;
        if (w < BgPeel.sFullW) return false;
        // ★★★ v0.1.38：**满屏的那一层不归这里**（它是页面底图，由 paintBlurBg 负责）。
        //   r43 里同一个 1272x2772 的层被这条抢走，刷成清晰壁纸 ⇒ 播放页「背景透明」。
        if (h * 100 >= (long) BgPeel.sSH * 90) return false;
        // ★★★ v0.1.41（r12 用户第 7 条）：**横向的图片层不是氛围层**。
        //   氛围层（PurePlay 的 localbg 780x920、播放页的 playbackspbackground）都是
        //   <b>竖屏铺满</b>的；而 r12 日志里反复被换掉的那块
        //   {@code ExpoImageView 1440x1080}（宽/高 = 1.33）是<b>播放页的封面横幅</b>
        //   —— 它被铺成壁纸 ⇒ 用户看到「作品封面死都不显示」。
        //   1.2 这条线把 1440x1080(1.33) / 1300x975(1.33) 挡在外面，
        //   而 1440x1698(0.85) 这种竖向氛围层不受影响。
        if (w > h * 1.2f) return false;
        if (h < (int) (BgPeel.sSH * BgPeel.VEIL_MIN_H_RATIO)) return false;
        if (!BgPeel.isTopAnchored(v, w, h)) return false;
        if (BgPeel.insidePager(v)) return false;      // 图片浏览里的照片：一张都不许换
        int kind = CoverLayer.imgKind(v);
        // ★★★ v0.1.36（r41 t0242 帧实证）：**ATMOS 也要接**。
        //   标题「PurePlay」下面那一大片紫色渐变，就是宿主那张
        //   {@code localbg.webp}（780x920 RGBA，自上而下紫→透明）渲染成的
        //   {@code ExpoImageView 1272x1500}。它的平均 alpha 落在 0.65~0.92 之间 ⇒
        //   {@link #imgKind} 判成 BgPeel.IMG_ATMOS（不是 VEIL）⇒ 这里直接放行 ⇒ 紫遮罩回来了。
        //   而 paintAtmos 又因为「高度只有 54% 屏高 < 80%」拒绝处理 ⇒ 两条路都不接。
        //   现在 VEIL / ATMOS 一律当「顶部氛围层」：原地画清晰壁纸。
        // ★★★ v0.1.38（r43 用户第 1 条）：**连 BgPeel.IMG_CONTENT 也接**。
        //   这一层只可能是 expo-image 画的贴顶通栏大图（宽度 ≥94% 屏宽、20%~90% 屏高），
        //   宿主在这个位置上放的就是装饰背景（PurePlay 的 localbg、作品详情页的头部底图）。
        //   以前卡「必须半透明」⇒ 那张图偶尔被解码成不透明 RGB（webp 走硬件解码时 alpha
        //   会丢）⇒ 判成 content ⇒ 放行 ⇒ 用户看到「紫遮罩有时候还是会显示」。
        //   内容图（主页立绘）是 {@code ReactImageView}，根本进不到这个钩子，不受影响。
        //   唯独装饰模糊底另有分支，不在这里抢。
        if (kind == BgPeel.IMG_BLUR) return false;
        // ★★ v0.1.25 用户补充需求：PurePlay 顶部那半屏「淡模糊」不要了。
        //   以前这里画的是**毛玻璃**（BgPeel.sWpBarBmp）—— 因为它原先是一层紫色氛围图，
        //   用户上一轮要的是「贴合背景的模糊」。这一轮明确说不需要这层模糊，
        //   于是改成画**清晰壁纸**：等于把那张氛围图原地换成背景本身，
        //   视觉上就是「顶部干干净净、没有任何叠加」。
        // ★★★ v0.1.37（r42 用户第 3 / 4 条）：**播放页例外**。
        //   播放页那张 {@code ExpoImageView 1272x2772}（整屏、带 alpha）走的正是这条路，
        //   r42 日志里 `paint veil clear -> ExpoImageView 1272x2772` 出现十几次 ——
        //   就是把播放页背景刷成了清晰壁纸，用户看到「背景直接透明 / 模糊不生效」。
        //   播放页那几张满屏氛围层一律用播放档（壁纸 + 播放页模糊档）。
        boolean play = PlayPage.playContext(v);
        Bitmap clear = play ? PlayPage.playBmp() : BgPeel.sWpBmp;
        if (clear == null) return false;
        Rect r = BgPeel.dstRect(w, h);
        if (!paintAligned(c, clear, v, r, false)) return false;
        BgPeel.sVeil++;
        BgPeel.sPainted = true;
        BgDiag.hit(play ? "paint veil play -> " : "paint veil clear -> ", v, r);
        return true;
    }


    /**
     * 玻璃表面该用哪张底图（F2：壁纸 + {@link #BgPeel.GLASS_BLUR_PCT}% 单次模糊）。
     */
    static Bitmap glassBmp(View v) {
        Bitmap cached = BgPeel.sWpGlassBmp;
        if (cached != null && !cached.isRecycled()) return cached;
        Bitmap base = PlayPage.isPlayWindow(v) ? PlayPage.playBmp() : BgPeel.sWpBmp;
        if (base == null || base.isRecycled()) base = BgPeel.sWpBmp;
        if (base == null || base.isRecycled()) return null;
        Bitmap g = null;
        try {
            g = io.github.ariinyume.morimod.util.BlurUtil.apply(base, BgPeel.GLASS_BLUR_PCT);
        } catch (Throwable ignored) {
        }
        if (g == null || g.isRecycled() || g == base) return null;
        BgPeel.sWpGlassBmp = g;
        return g;
    }


    /** 玻璃判定的「拒绝原因」留痕（每个签名一次，方便下一轮对账）。 */
    static void glassNo(String why, View v, int w, int h) {
        BgDiag.logOnce("gn:" + why + BgDiag.signature(v),
                "glass NO (" + why + ") " + BgPeel.name(v) + " " + w + "x" + h
                        + " top=" + BgPeel.topOf(v) + " scr=" + BgPeel.scrOf(v));
    }


    /** 玻璃判定的「接受原因」留痕。 */
    static void glassWhy(String why, View v, int w, int h) {
        BgDiag.logOnce("gy:" + why + BgDiag.signature(v),
                "glass OK (" + why + ") " + BgPeel.name(v) + " " + w + "x" + h
                        + " top=" + BgPeel.topOf(v) + " scr=" + BgPeel.scrOf(v));
    }


    /**
     * ★★★ v0.1.31 用户第 3 条：这一块底是不是「玻璃表面」（底部面板 / 信息卡片）。
     *
     * <h3>为什么终于不用几何比例了</h3>
     * 用户一句话把前面四版全否了：「概述 / 下载音轨 / 简介 / 导入这些内容根高度不一定，
     * 是有高有低的」。而同一块屏幕上还有两样<b>绝对不能糊</b>的东西 ——
     * 「作品详情页里的文件列表块」（1272x750）和「播放列表详情整页」。它们在
     * 宽 / 高 / 贴底 / 所属窗口上与信息卡片<b>完全重合</b>，所以任何按尺寸或按窗口
     * 的判据都必然两头错（v0.1.27~v0.1.30 连错四次）。
     *
     * <h3>新判据只看这块底自己的三件事</h3>
     * <ol>
     *   <li><b>够宽够高</b>：≥70% 屏宽、≥9% 屏高（排掉导航栏 7.5%、迷你播放 6%、图标、
     *       列表行）。<b>不设高度上限</b> —— 上限那一路（v0.1.35 的 85%）正是
     *       「概述面板整块变成清晰壁纸、和主界面融为一体」的成因；</li>
     *   <li><b>是「面板色」</b>：要么半透明（卡片那层淡色），要么<b>不透明的深色</b>
     *       （Luma &lt; 0.42 的紫面板）。<b>不透明的亮底一律不动</b> ——
     *       白色胶囊按钮就是这一类，换成壁纸会把按钮吃掉；</li>
     *   <li>不是图片层 / 视频层；</li>
     *   <li><b>在独立 Dialog 窗口里</b>（页面里的块一律不糊，见 {@link #inDialogWindow}）。</li>
     * </ol>
     * 白按钮、页面容器、列表行、导航栏、压暗遮罩全被挡在外面。
     */
    static boolean isGlassSurface(View v, Drawable d, Rect b) {
        if (d == null || b == null || b.isEmpty()) return false;
        if (BgPeel.sSW <= 0 || BgPeel.sSH <= 0) return false;
        int w = b.width();
        int h = b.height();
        if (w * 100 < (long) BgPeel.sSW * BgPeel.GLASS_MIN_W_PCT) return false;
        if (h * 100 < (long) BgPeel.sSH * BgPeel.GLASS_MIN_H_PCT) return false;
        // ★★★ v0.1.42（r13 用户第 3 条）：**加一条「面积」闸**，让短而宽的面板也能进来。
        //   「下载音轨 / 导入」这类底部面板只有 26~28% 屏高，以前靠
        //   `BgPeel.GLASS_MIN_H_PCT=9` 是能过的 —— 真正把它们挡在外面的是下面那条
        //   「读不出 solid 颜色就保守不动」。面积闸只用来把「一行 40px 的分隔条」
        //   这类东西挡掉，不会再和面板尺寸打架。
        if ((long) w * h * 100 < (long) BgPeel.sSW * BgPeel.sSH * BgPeel.GLASS_MIN_AREA_PCT) return false;
        if (v instanceof android.widget.TextView) return false;
        if (BgPeel.isVideoSurface(v) || BgPeel.isImageLayer(v)) return false;
        // ★★★ v0.1.37（r42 用户第 1 / 2 条）：**唯一的判别式只在「窗口」上**。
        //   r42 的 surface 日志把四张信息卡、面板背景板、以及「新建播放列表」对话框
        //   全都标成了 `dlg=1`，而页面（播放列表详情 / 摘选详情 / 作品详情）全是 `dlg=0`。
        //   于是判据收敛成一句：<b>不在独立 Dialog 窗口里 ⇒ 永远不是玻璃</b>。
        //   v0.1.32 的 `insideScreen` 兜底、v0.1.33 的 `!insideBottomSheet` 组合
        //   全部删掉 —— 它们只是在同一个判据上叠补丁，而且 r38/r40 的 `bs=0` 已经
        //   证明宿主根本不用 BottomSheet 那套类名。
        //   （@gorhom 的浮层万一被渲染在页面树里，`insideBottomSheet` 仍然放行。）
        if (!BgPeel.inFloatingLayer(v)) return false;
        // ★★★ v0.1.72（r74 第 2 条）：**播放页的主体容器不是面板，绝不涂玻璃**。
        //
        //   r74 现场（就是用户说的「封面上下那两条错位硬边，闪一下就恢复」）：
        //   第一次打开播放页那一瞬，封面容器 `/3/0/2/2/0 ReactViewGroup [0,284,1272,1238]`
        //   被判成玻璃（probe 里 `bg=CompositeBackgroundDrawable P=glassA`），
        //   于是它被铺成 <b>壁纸 + 38% 模糊</b>；而同一屏其它层铺的是 {@code playBmp}
        //   （{@code f2PlayBlur} 默认 0 ⇒ 清晰壁纸）。两块差了一整档模糊度，
        //   肉眼就是「封面上面一条、下面一条硬边」；落位后它重新被判成
        //   「叶子层 → drop」，硬边自己消失 —— 所以是"闪一下"。
        //   判据：播放页语境 + 满宽 + 高 ≥20% 屏 + 顶边在屏幕上半部 ⇒ 这是页面主体。
        //   （播放页里真正的面板都是底部弹层，顶边在下半部，不会被误伤。）
        // ★★★ v0.1.80（r83 第 3 条「偶发：背景变成封面的模糊版 + 封面不显示」）：
        //   **判据从 `playContext` 换成结构判据 `PlayPage.inPlayTree(v)`。**
        //   `playContext` 依赖活体进度条，在「首次打开 / 退后台回来」的那几帧会失效；
        //   一旦失效，这一段排除就不生效 ⇒ 封面容器被当成玻璃面板铺上
        //   「壁纸 + 38% 模糊」（背景看起来像封面的模糊放大版），
        //   而封面自己那一层的绘制被玻璃逻辑接管掉（封面不显示）。
        //   用户 r83 第 3 条的两张截图正是这一前一后。
        //   `inPlayTree` 走「祖先链里有没有满屏氛围底图」这条纯结构事实，
        //   不依赖进度条 ⇒ 那几帧也成立。
        if (PlayPage.inPlayTree(v)) {
            int[] pgeo = BgPeel.RGEO.get();
            if (BgPeel.rootGeometry(v, pgeo)
                    && w * 100 >= (long) pgeo[0] * 92 && h * 100 >= (long) pgeo[1] * 20) {
                int[] gl = BgPeel.LOC.get();
                try {
                    v.getLocationInWindow(gl);
                } catch (Throwable ignored) {
                    gl[0] = 0;
                    gl[1] = 0;
                }
                if (gl[1] * 100 < (long) pgeo[1] * 55) {
                    noteGlassFail("play main " + gl[1]);
                    return false;
                }
            }
        }
        // ★★★ v0.1.39：**只靠 {@link #mainWindowSheet} 命中时要更高**。
        //   Dialog 窗口里的块已经被 r42 日志反复确认过属性，
        //   但「主窗口 + 不在 Screen 里 + 贴底」这条是本轮新开的，万一宿主在
        //   页面外层另有一个贴底容器，9% 的高度下限就太宽松了。
        //   真正的底部弹层少说也占 1/4 屏高（底部面板本体），
        //   靠这一条把「页面里的单行 / 分隔条」彻底挡在外面。
        if (!BgPeel.inDialogWindow(v) && !BgPeel.insideBottomSheet(v)) {
            if (h * 100 < (long) BgPeel.sSH * 20) return false;
        }
        // 弹窗后面那层压暗遮罩（#9919122C / #19122ccc）也在 Dialog 窗口里，
        // 但它必须保持「压暗」的语义 —— 铺成模糊玻璃会把弹窗内容压在自己下面。
        if (BgPeel.isDimMask(d)) return false;
        // ★★★ v0.1.38（r43 用户第 3 条）：**删掉「已被外层玻璃盖过就跳过」这一条**。
        //   它是 v0.1.33「只画最外层」策略的产物，逻辑上成立的前提是「子卡片是透明的」。
        //   但这个宿主的子卡片是不透明深紫（#FF252451）：父层被我们铺成玻璃之后，
        //   子卡片照样把自己的紫底画上去 ⇒ 玻璃被整块盖死 ⇒
        //   用户连着四轮说的「只有一块有模糊、其余还是紫底」。
        //   r43 日志正好把两种情形都拍下来了：
        //     · `glass -> ReactViewGroup 1272x2534` + 子块 `cov=1`（紫底盖回去）
        //     · `surface …/1272x1492 dlg=1 => GLASS` 但没有任何 `glass ->`（被 cov 挡掉）
        //   现在改成「谁画不透明底，谁自己铺玻璃」：父层铺一层、每张卡片各铺一层，
        //   重叠绘制只是多贴一次同一张位图，代价可忽略，但视觉上终于整块都是玻璃。
        // ★★★ v0.1.42（r13 用户第 3 / 4 / 6 条）：**「读不出颜色」不再等于「不动」**。
        //
        //   v0.1.41 的尾巴是：
        //     `solid == null ⇒ return false`（复合底保守不动）
        //   实测代价是致命的 —— 宿主的面板底大多是
        //   {@code CompositeBackgroundDrawable}（LayerDrawable 包一个 BackgroundDrawable），
        //   内层那支 {@code BackgroundDrawable} 的颜色字段<b>读得出来才怪</b>，
        //   于是「下载音轨 / 导入」这些面板全部被判成「不是玻璃」
        //   ⇒ 宿主照自己那张深色 surface 画 ⇒ 用户看到的就是「背后变成深色底」。
        //   而「概述 / 简介」那种能读出颜色的面板就正常上了模糊 ——
        //   这正是用户问了很多轮的「为什么有的能做、有的做不了」。
        //
        //   现在的规则（在浮层里 + 够大 ⇒ 默认就是面板）：
        //     · 透明 ⇒ 不是面板；
        //     · 读得出颜色且很亮（Luma > 0.72）⇒ 白卡片 / 白胶囊按钮，不动；
        //     · 其余（能读出的深色 / 半透明 / 读不出的复合底）⇒ 玻璃。
        int op = d.getOpacity();
        if (op == PixelFormat.TRANSPARENT) return false;
        Integer solid = BgPeel.solidColorOf(d);
        if (solid != null && BgPeel.luma(solid) > BgPeel.GLASS_LIGHT_LUMA) return false;
        // ★★★ v0.1.43（r14 用户第 8 条）：**近全屏 + 贴顶 = Dialog 背板，不是面板**。
        //   把背板当玻璃 ⇒ 独立 Dialog 窗口里铺满模糊壁纸 ⇒ 透过透明窗口看到的
        //   不再是主窗口的 App 界面，而是一张静态壁纸（「弹窗后面宿主界面完全消失」）。
        //
        // ★★★ v0.1.44（r15 用户第 2 / 3 / 4 / 5 条）：**判定值必须与动画无关**。
        //   r15 两台机器的日志把这一条钉死了：概述面板（一加12: 1440x2888 top=280；
        //   一加15: 1272x2534 top=238，都是 91% 屏高）在<b>滑入动画中</b>整块被
        //   平移到屏幕外 —— 此刻 {@code getLocationInWindow} 返回 3168/3010，
        //   大于 BgPeel.sEdgeTop ⇒ 闸门放行 ⇒ 铺玻璃；260ms 后动画落位，
        //   同一块变回 280/238 ⇒ 闸门拒绝 ⇒ {@code swapLeaf} 随手把
        //   CompositeBackgroundDrawable 也删了 ⇒ <b>面板整块透明</b>。
        //   同一个震荡也打在播放页整屏容器（1440x3168 top=0）上：
        //   动画里给了玻璃、落位后被收回 ——「概述面板透明 / 播放页上方黑条 /
        //   PurePlay 与播放列表详情的模糊概率出现」是同一个真因的四种表现。
        //   修复：改用 {@link #topOf}（{@code getTop()}，<b>布局位置，不含动画位移</b>，
        //   两台机器全程恒为 280/238）+ 把高度闸从 88% 收到 97% ——
        //   真·背板是 {@code MATCH_PARENT}（top=0、高≈100%），落位前后都命中；
        //   概述这种「上缘留白 9%」的内容面板恒不命中，玻璃不再被回收。
        // ★★★ v0.1.45（r16 用户第 2② / 4 条）：**屏幕位置不可信的层不铺玻璃**。
        //   r16 日志里 PurePlay / 播放弹层的容器打出了 `scr=296921,3056`
        //   `scr=349347,3496` 这种完全不可能的屏幕坐标（独立窗口自己的坐标系）。
        //   在这种层上铺玻璃 = 把模糊壁纸铺在错误位置 ⇒ PurePlay 上面多出一块
        //   高斯模糊、播放页封面区出现色块/深色条。位置都读不对，就别动它的底。
        try {
            int[] loc = BgPeel.LOC.get();
            int[] geo = BgPeel.RGEO.get();
            // ★★★ v0.1.98：这道「位置不可信」的闸也换成窗口坐标。
            //   老写法拿屏幕坐标跟 BgPeel.sSW（主窗口宽）比 —— 浮窗里窗口在屏幕右侧
            //   ⇒ loc[0] 必然 ≥ BgPeel.sSW ⇒ 所有面板都被判成 offscreen-window，
            //   玻璃整片消失（比错位更糟）。
            if (!BgPeel.rootGeometry(v, geo)) return false;
            // ★★★ v0.1.140：位置一律走 windowLoc（坏 getLocationInWindow 时回落布局累加）。
            //   r180040：滑入中 winY=4052/4675、导入卡 scr=1313 → offscreen-dialog
            //   ⇒ 紫底；落位后再 OK ⇒「先紫后糊」。布局 top 全程稳定（1289/530）。
            if (!BgPeel.windowLoc(v, loc)) return false;
            if (BgPeel.inDialogWindow(v)) {
                if (loc[0] + w < -8 || loc[1] + h < -8
                        || loc[0] > geo[0] + 8 || loc[1] > geo[1] + 8) {
                    glassNo("offscreen-dialog", v, w, h);
                    return false;
                }
            } else if (loc[0] < -w || loc[0] >= geo[0] || loc[1] > geo[1] * 3) {
                glassNo("offscreen-window", v, w, h);
                return false;
            }
        } catch (Throwable ignored) {
        }
        if (BgPeel.topOf(v) <= 4 && h * 100 >= (long) BgPeel.sSH * 97) {
            glassNo("fullscreen-backdrop", v, w, h);
            return false;
        }
        if (solid == null) glassWhy("composite", v, w, h);
        else glassWhy("panel", v, w, h);
        return true;
    }


    static void noteGlassFail(String why) {
        if (BgPeel.sGlassFailLogs >= 8) return;
        BgPeel.sGlassFailLogs++;
        BgPeel.log("glass MISS (" + why + ") → 保留宿主原底色");
    }


    /**
     * ★ 所有层共用的绘制：把位图铺在<b>本窗口</b>上，再用这一层自己的范围裁剪。
     *
     * <p>Canvas 在 {@code View#draw} 里已经被平移到该 View 的左上角，所以「窗口矩形」
     * 在本地坐标里就是 {@code (-locX, -locY, w, h)}。不论哪一层、尺寸差多少，
     * 画出来的都是<b>同一份像素</b> ⇒ 叠多少层都严丝合缝（修 v0.1.16 的重影）。
     *
     * <p>⚠️ 只用 {@code drawBitmap}，<b>不碰 Drawable 实例</b> —— 同一个实例可能同时挂在
     * {@code DecorView} 背景上，{@code setBounds} 会 {@code invalidateSelf} 打成无限重绘
     * （v0.1.17 播放页闪屏 / 黑屏的根因）。
     */
    static boolean paintAligned(Canvas c, Bitmap bmp, View v, Rect clip,
                                        boolean zoomDegrade) {
        if (bmp == null || bmp.isRecycled() || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) return false;
        // ★★★ v0.1.72（r74）：**「缩放转场就降级成毛玻璃」这条补丁已删除**。
        //   它（v0.1.23 起）解决的是「父层把 scale concat 进画布 ⇒ 清晰壁纸跟着放大 /
        //   错位」，本质是「用模糊盖住错位」。可它只作用于<b>调用方显式要求</b>的那一层，
        //   同一屏里别的层不降级 ⇒ 根层糊、子层清楚 ⇒ 转场中反而多出一条硬边，
        //   正是用户 r74 第 2 条说的「封面上下两条错位硬边，闪一下就恢复」。
        //   本轮在下面对 dst 做<b>真正的 scale 补偿</b>（见 {@link #canvasScale}），
        //   壁纸在整个转场里都钉死在屏幕上 ⇒ 错位从根上不存在，降级自然多余。
        int[] loc = BgPeel.LOC.get();
        int[] geo = BgPeel.RGEO.get();
        try {
            // ★★★ v0.1.98（浮窗割裂 / 移位的定案改动）：**坐标基准从「屏幕」改成「窗口」**。
            //
            //   老做法：locations 用 getLocationOnScreen（含窗口在屏幕上的偏移），
            //   目标矩形用 BgPeel.sSW×BgPeel.sSH（主窗口尺寸）⇒ 壁纸铺的是「整块屏幕」，
            //   窗口只是它上面开的一个洞。全屏时窗口 ≡ 屏幕，看不出问题；
            //   一旦进浮窗（窗口在屏幕 (x0,y0)、尺寸只有半屏），
            //      · 每一层画的是「壁纸在屏幕坐标里的那一片」，落点随 loc 整体平移 ⇒ 移位；
            //      · 不同层 loc 不同，同一屏里各画各的一片 ⇒ 交界处图案对不上 ⇒ 割裂；
            //      · 而窗口背景 CoverDrawable 是按 bounds（窗口）居中等比裁的，两套基准互不相干。
            //   getLocationInWindow 天然给出「本窗口内的坐标」，配上本窗口的宽高，
            //   所有层（含 Dialog 自己的窗口）都落在同一套窗口坐标里，
            //   与 DecorView 上的 CoverDrawable 逐像素同源 ⇒ 结构上不可能再割裂。
            // ★★★ v0.1.140：滑入动画中 getLocationInWindow 会飞出窗外（4052…）；
            //   改走 windowLoc，坏坐标回落布局累加，避免局部糊 / 分层。
            if (!BgPeel.windowLoc(v, loc)) return false;
            if (!BgPeel.rootGeometry(v, geo)) return false;
        } catch (Throwable t) {
            return false;
        }
        // ★★ v0.1.23 定案（r28）：画布的坐标系 = 「本层的本地坐标系」。
        //   View#draw 收到的 Canvas 已经被父层平移到这一层的原点，所以「屏幕坐标」
        //   在这里就是 (屏幕坐标 − 本层在窗口里的位置)。v0.1.22 试图用
        //   Canvas#getMatrix 的逆矩阵把坐标系拉回窗口空间 —— 硬件画布上那个矩阵
        //   并不包含父层的平移（实测 r28：底部导航栏 / 迷你播放 / 面板全部画到屏幕外，
        //   信息页则整体向下错位 loc.y 个像素）。这里退回 r23~r27 用的、被真机验证过的算法。
        //
        // ★★★ v0.1.72（r74，本轮的硬骨头）：**转场缩放必须除掉**。
        //   上面那条算法隐含一个假设：本层画布与屏幕是 1:1（只有平移）。
        //   而 RN 的页面转场（播放页展开 / 收成迷你条 / 打开详情页）是给
        //   <b>整棵子树</b>加 scale ⇒ 画布已经被父层缩小/放大过，
        //   于是「本地坐标」比「屏幕坐标」小/大了 1/scale 倍：
        //       screen = loc + scale · local        ⇒  local = (screen − loc) / scale
        //   旧代码按 scale=1 直接减 loc，位图就被<b>再乘一次 scale</b> 画出去 ——
        //   转场中每一层各画一块「放大/缩小且错位」的壁纸，层与层的矩形边界就是
        //   用户看到的那两条硬边；转场一结束 scale 回到 1，硬边自己消失
        //   （正是 r74 第 2 条「闪一下就恢复」）。
        //   现在把 scale 除掉：dst 用 (屏幕矩形 − loc) / scale 表示，
        //   父层再乘回 scale ⇒ 落点与尺寸都与屏幕严格对齐，全程钉死不动。
        float cs = BgPeel.canvasScale(v);
        float inv = 1f / cs;
        RectF dst = CoverLayer.coverRect(bmp, -loc[0] * inv, -loc[1] * inv, geo[0] * inv, geo[1] * inv);
        Rect src = BgPeel.SRC.get();
        src.set(0, 0, bmp.getWidth(), bmp.getHeight());
        int save = c.save();
        boolean drew = false;
        try {
            c.clipRect(clip);          // clip 本来就是本地坐标，直接用
            c.drawBitmap(bmp, src, dst, BgPeel.BMP_PAINT);
            drew = true;
        } catch (Throwable ignored) {
        } finally {
            c.restoreToCount(save);
        }
        return drew;
    }


    /**
     * ★★ v0.1.21 用户第 1 条：<b>不透明的满屏氛围底图</b>换成「播放页壁纸」。
     *
     * <h3>为什么 v0.1.20 的「不画」解决不了播放页</h3>
     * 播放页那张 {@code playbackspbackground}（1081×1920 RGBA）虽然带 alpha 通道，
     * 但<b>整张都是不透明的</b>（平均 alpha ≈ 1.0）—— 它不像 PurePlay 的 {@code localbg}
     * 那样是「紫→透明」的叠加蒙层，而是<b>实打实的底图</b>。上一版只对
     * {@code avgAlpha < 0.92} 的图下手，所以播放页永远命中不了
     * （r26 日志 {@code veil=0} 就是这个原因），用户看到的一直是「模糊的作品背景」。
     *
     * <p>现在按「不透明 ⇒ <b>原地换成壁纸</b>」处理：和 {@code View#draw} 里换满屏叶子层
     * 同一个道理 —— 它本来就是铺在最底下的底色，换掉只可能露出壁纸，不可能丢内容。
     * 唯一要守住的是「别碰内容图」：{@code main_visual}（主页立绘）是 <b>RGB</b>，没有
     * alpha 通道，在这里被判成 {@link #BgPeel.IMG_CONTENT} 直接放行。
     *
     * <p>糊的档位来自用户新加的「播放页背景模糊」（{@link MoriConfig#f2PlayBlur}）。
     */
    static boolean paintAtmos(View v, Canvas c) {
        if (!BgPeel.sArmed) return false;
        if (CoverLayer.inReviewUi(v)) return false;
        if (BgPeel.sSW <= 0 || BgPeel.sSH <= 0) return false;
        int w;
        int h;
        try {
            w = v.getWidth();
            h = v.getHeight();
        } catch (Throwable t) {
            return false;
        }
        if (w <= 0 || h <= 0) return false;
        if (w < BgPeel.sFullW) return false;
        // ★★ v0.1.28：氛围底图必须**接近满屏**。r33 里播放页那张大封面
        //   （ReactImageView 2300x1726，62% 屏高）也满足「满宽 + 18% 高」，
        //   一旦它的位图被读成 RGBA 就会被当成氛围底整层换成壁纸 ⇒「作品封面不显示」。
        if (h * 100 < (long) BgPeel.sSH * 80) return false;
        if (!BgPeel.isTopAnchored(v, w, h)) return false;
        // ★★★ v0.1.81（r84 第 3 条「图片点开完全不显示」）：
        //   **图片浏览里的图一律放行** —— 这条保护 r22 只加在 `dropVeil` 里，
        //   `paintAtmos` 一直漏着。图片查看器里那张图是「满宽 + 满高 + 贴顶」，
        //   尺寸判据全部命中；只要 `imgKind` 把它读成 ATMOS（本地缓存的
        //   BitmapDrawable 能读出 alpha，反而更容易被误分类），就会被整层换成壁纸
        //   ⇒ 图不显示、滑动时是一块块「和背景一样的图块」。
        if (BgPeel.insidePager(v)) return false;
        if (CoverLayer.imgKind(v) != BgPeel.IMG_ATMOS) return false;
        // ★★ v0.1.27 用户第 4 / 6 条：这里以前画的是 {@code BgPeel.sWpPlayBmp}（播放页那张
        //   「壁纸 + f2PlayBlur」，默认 20% 模糊）—— 等于把<b>播放页的呈现</b>套到所有
        //   「满屏不透明氛围图」上。真机后果两条：
        //     · 播放列表 / 摘选详情页一打开就是一层糊的（用户第 6 条：「难道和播放页
        //       调用的是同一个壁纸呈现效果？」—— 就是这里）；
        //     · 图片浏览里最后一张图背后多一层模糊（第 4 条）。
        //   ★★ v0.1.28 用户第 1 条：可一刀切成「全部清晰」又走过了头 ——
        //   播放页真正那张底图（{@code playbackspbackground}）也走这条路，于是
        //   「播放页背景为自定义壁纸时模糊不生效」（r33 原话）。
        // ★★★ v0.1.37（r42）：不再用「≥92% 屏高」猜播放页 —— 那条判据把
        //   播放列表详情页也一起糊了（v0.1.27 用户第 6 条），却又认不出真正的播放页
        //   （r42 的两张截图都是清晰壁纸）。现在唯一判据换成「屏幕上有一个活着的进度条」。
        boolean playPage = PlayPage.playContext(v);
        Bitmap bmp;
        if (playPage) {
            bmp = BgPeel.sWpPlayBmp != null ? BgPeel.sWpPlayBmp : (BgPeel.sWpBmp != null ? BgPeel.sWpBmp : BgPeel.sWpBarBmp);
        } else {
            bmp = BgPeel.sWpBmp != null ? BgPeel.sWpBmp : BgPeel.sWpBarBmp;
        }
        if (bmp == null) return false;
        Rect r = BgPeel.dstRect(w, h);
        if (!paintAligned(c, bmp, v, r, false)) return false;
        PlayPage.notePageBackdrop(v, h, bmp);
        BgPeel.sAtmos++;
        BgDiag.hit(playPage ? "paint atmos play -> " : "paint atmos clear -> ", v, r);
        return true;
    }


    /**
     * 把「宿主的封面模糊底图」换成「自定义壁纸 + 用户设定的模糊」（{@link MoriConfig#f2PlayBlur}）。
     *
     * <p>为什么整层换掉而不是叠一层：这张图在 bundle 里就是
     * {@code <ArtworkPrivacyImage contentFit="cover" blurRadius={10}>}，铺满整屏、位于内容之下，
     * 属于「底色」而不是内容。换掉它的效果<b>恰好等于</b>用户要的那句
     * 「固定为自定义背景再叠加模糊」，而且从此与「有没有封面」「是否隐私模式」彻底无关。
     */
    static boolean paintBlurBg(View v, Canvas c) {
        if (!BgPeel.sArmed) return false;
        // ★★★ v0.1.135：月度回顾底优先（CONTENT 放行分支会把蓝紫底交还宿主）。
        if (paintReviewBg(v, c)) return true;
        if (BgPeel.sSW <= 0 || BgPeel.sSH <= 0) return false;
        int w;
        int h;
        try {
            w = v.getWidth();
            h = v.getHeight();
        } catch (Throwable t) {
            return false;
        }
        if (w <= 0 || h <= 0) return false;
        // ★★★ v0.1.49（r49 用户第 2 / 3 条）：**播放页里的「大块模糊图」也归我们管**。
        //
        //   r49 真机日志：播放页除了那两块满屏底图（1272x2772 / 1272x2913），
        //   还有一块 <b>1272x954 的 ExpoImageView</b>（≈34% 屏高）：
        //   <pre>
        //   15:20:23.594  blur bg r=10 1272x2772 top=0 pg=0 ->clear
        //   sheet miss ExpoImageView 1272x954 top=0 inScr=0 ...（没有 blur bg 接管）
        //   </pre>
        //   它不满屏 ⇒ 旧代码在上面的 {@code isScreenFillDecor} 就直接放行了，
        //   于是<b>宿主自己那张模糊封面占位</b>照原样画出来 ——
        //   这就是用户说的「作品封面不显示（为纯模糊占位）」和
        //   「背景被裁剪成几块」（一半是我们的清晰壁纸、一半是宿主的糊图）以及
        //   「背景透明」（我们自己什么都没铺，只剩宿主那一层）。
        //
        //   播放页里的模糊块一定是「底」而不是内容（内容图不会带 blurRadius），
        //   所以整块换成<b>播放档壁纸并对齐到屏幕</b> —— 和周围同源同位，
        //   接缝自然消失。非播放页一律不走这条（防偷窥封面还要留着上罩色，见 privacyWash）。
        boolean playEarly = PlayPage.playContext(v);
        if (!BgPeel.isScreenFillDecor(v)) {
            // ★★★ v0.1.50（r50 用户第 2 / 3 条）：判据不能只看 blurRadius prop。
            //   r49 版这条分支<b>一次都没进</b>（日志里 `paint play blur big` 零条）——
            //   因为宿主这块横幅压根没设 blurRadius（和防偷窥封面一样，r=0），
            //   它是「直接下发一张糊好的图」。所以补上像素证据：
            //   <b>位图自己就是糊的 ⇒ 它是底，不是内容</b>。
            //   清晰的作品封面（评分≈46+）永远不会被这条命中。
            //   ★★★ v0.1.52：再加一条「窗口级」入口 —— `PlayPage.isPlayWindow(v) && 不在 Screen 里`。
            //   这个宿主的播放页摊在窗口根上（inScr=0），而列表页都在 Screen 里
            //   ⇒ 用「窗口是播放页窗口 + 这一层不在 Screen 内」就能精确覆盖播放页的
            //   根级横幅，又绝不会误伤列表页（r52 日志里那块 1272x954 就是根级的）。
            //   ★★★ v0.1.54（r54 adb 实测）：真机截图证明播放页顶部还留着<b>一整块</b>
            //   宿主的糊图（满宽 × ≈38% 屏高，正是那块 1272x954 的横幅），
            //   而位图判据对它取不到位图（`privacyBitmapOf` 返回 null）⇒ 上一条没进。
            //   于是再加一条**纯几何**兜底：播放页 / 播放页窗口里、
            //   <b>贴页顶 + 满宽 + ≥18% 屏高</b> 的层一定是「背景横幅」而不是内容
            //   （作品封面是 contain 居中，永远不贴页顶）。
            //
            // ★★★ v0.1.71（r73 adb 实证）：**这条几何兜底就是「作品封面永远不显示」的凶手，已删除。**
            //   现场 trace（logcat MoriTrace，播放页停在前台）：
            //   <pre>
            //   ExpoImageView 1272x954 top=0 a=1.0 r=0 kind=0 bmp=500x500/sharp
            //                  playCtx=1 playWin=1 float=1 -> big
            //   ExpoImageView 1272x2772 top=0 r=10 kind=3 bmp=125x125/blur  -> big
            //   ExpoImageView 1272x2913 top=0 r=0  kind=2 bmp=780x1688/blur -> big
            //   </pre>
            //   那块 1272x954 就是 <b>player-thumbnail（作品封面）</b>：位图是清晰的 500x500，
            //   `blurRadius=0`、`isBlurredBitmap=0` —— 三条像素判据一条都不成立，
            //   只有 `topBanner` 成立。原因是 {@code topOf} 用的是 {@code View#getTop()}
            //   （**相对父容器**），封面装在 FrameLayout 里 ⇒ 永远是 0，于是「贴页顶」恒为真。
            //   我们随即把这块封面矩形涂成壁纸并对宿主 `return null`（连它自己的 draw 都不调）
            //   ⇒ 封面消失；被涂的壁纸与周围底图对齐方式不同 ⇒ 用户看到的「封面上方另一截壁纸」。
            //
            //   而 r54 当初要修的那块「宿主糊图」本来就有像素证据（`isBlurredBitmap` 为真），
            //   几何兜底是多余的 —— 所以这里<b>只认模糊证据</b>：清晰的内容图永远不接管。
            //
            // ★★★ v0.1.74（r88 第 1 条「首次打开：色块只闪现在作品封面上方，1~2 秒恢复」）：
            //   **高度门槛 18% → 70%，把「作品封面」这个尺寸档彻底排除。**
            //   防偷窥开启后，作品封面<b>本身</b>就是一张「宿主糊好的图」——
            //   于是 `isBlurredBitmap` 为真、尺寸 1272×954（≈34% 屏高）、
            //   又挂在播放页里 ⇒ 上面这条判据在开页的<b>头几帧</b>（此时封面还是
            //   `ExpoImageView`，还没换成 Skia 那个 1272x954 的糊封面）把它当成
            //   「页面底图」接管掉 ⇒ 那块矩形被涂成壁纸（= 用户看到的「色块」）；
            //   等宿主把封面换成 Skia 层（r87 已改成绝不接管非满屏 Skia）就恢复正常
            //   ⇒ 画面「1~2 秒后自己好了」，与用户描述逐字吻合。
            //
            //   播放页真正的底图只有两张，实测高度是 100%（1272×2772）与 105%（1272×2913），
            //   所以 70% 这条线既不误伤底图，又能把「内容尺寸」的图全部放回宿主。
            //   （`paintAtmos` 的门槛是 80%，`paintPlayFallback` 是 92%，同一套量级。）
            boolean bigBlur = (playEarly || (PlayPage.isPlayWindow(v) && BgPeel.screenOf(v) == null))
                    && w * 100 >= (long) BgPeel.sSW * 55 && h * 100 >= (long) BgPeel.sSH * 70
                    && (BgPeel.blurRadiusOf(v) >= BgPeel.IMG_BLUR
                        || BgPeel.isBlurredBitmap(BgPeel.privacyBitmapOf(v)));
            if (bigBlur) {
                Bitmap pb = PlayPage.playBmp();
                if (pb != null && paintAligned(c, pb, v, BgPeel.dstRect(w, h), false)) {
                    BgPeel.sBar++;
                    BgPeel.sPainted = true;
                    BgDiag.hit("paint play blur big -> ", v, BgPeel.dstRect(w, h));
                    return true;
                }
            }
            return false;
        }
        // ★★★ v0.1.96（r118 用户第 1 条，本轮只保「壁纸」这一件事）：
        //   **播放页里的「满屏层」是底，不是内容 —— 不看半径、也不看位图。**
        //
        //   r117 的现场已经把两个洞同时钉死了：
        //   <pre>
        //   ExpoImageView 1272x2772 r=10 kind=3 bmp=140x105/blur -> big   ← 正常（接管 ⇒ 壁纸）
        //   ExpoImageView 1272x2772 r=0  kind=0 bmp=140x105/blur -> host  ← 异常（交还宿主 ⇒ 糊封面）
        //   </pre>
        //   v0.1.95 只补了「位图读得到、且确实是糊的」这一半。可宿主这张底是
        //   「封面缩到 140x105 再糊好」的独立位图，只要某一帧 drawable 还没挂上
        //   （Fresco 换源、Skia 层接管、位图被回收），{@code privacyBitmapOf} 就是 null
        //   ⇒ 像素判据失效 ⇒ kind 仍落 BgPeel.IMG_CONTENT ⇒ 下面 3120 行整层放行 ⇒
        //   那张糊封面铺满整屏，且它在这棵子树里的位置<b>压住作品封面</b>
        //   —— 用户描述的「背景是放大的封面糊图 + 封面本身也不显示」就是一个成因。
        //
        //   而播放页的骨架是固定的：底图（实测 100% / 105% 屏高）、作品封面（34%）、
        //   文字与控件。所以「满宽 + 贴顶 + ≥92% 屏高 + 确实在播放页里」这条纯结构事实
        //   就足以定性为「底」，任何会迟到的状态（setter 跑没跑、位图挂没挂）都影响不到它。
        //   图片浏览 / 漫画的分页容器里同样有满屏层，用 {@link #insidePager} 挡掉。
        //
        //   ⚠️ 代价：播放页里万一真有一张满屏内容图，会被铺成壁纸（用户已明确：
        //   本轮只看壁纸效果，内容不追究）。相比「壁纸永远被糊封面盖住」，这个代价更小。
        boolean playVeil = h * 100 >= (long) BgPeel.sSH * 92 && BgPeel.topOf(v) <= 0
                && !BgPeel.insidePager(v)
                && (playEarly || (PlayPage.isPlayWindow(v) && BgPeel.screenOf(v) == null) || PlayPage.underPlayRoot(v));
        if (playVeil && PlayPage.showPlayVeil(v, c, w, h)) {
            return true;
        }
        int radius = BgPeel.blurRadiusOf(v);
        int kind = CoverLayer.imgKind(v);
        // 「铺满整屏 + 贴顶」就是页面背景（不管宿主有没有给 blurRadius）。
        boolean fillDecor = BgPeel.isScreenFillDecor(v) && BgPeel.topOf(v) <= 0;
        // ★★★ v0.1.38（r43 用户第 2 条）：**满屏底图一律归这里管，绝不放行**。
        //   r43 日志里同一个 {@code ExpoImageView 1272x2772} 有的帧走
        //   `blur bg r=10 ->play`，有的帧走 `paint veil clear`（dropVeil 那条），
        //   于是用户看到「有时正常、有时背景透明/模糊不生效」。
        //   现在只要「满宽 + 贴顶 + ≥92% 屏高」就一定是页面底图：
        //   播放页铺播放档，其余页铺清晰壁纸 —— <b>任何情况下都不会让这层回到宿主手里</b>。
        // ★★★ v0.1.75（r78 第 4 / 5 条）：**满屏的「内容图」不是页面底图。**
        //   用户这两条：① 作品文件夹里的图片/漫画点开全不显示，滑动时是一块块
        //   「和背景一样的图片块」；② 「概述」里的图片持续闪烁。
        //   根因同一个：内容图（kind == BgPeel.IMG_CONTENT）只要<b>满屏 + 贴顶</b>，
        //   就会命中下面这条 `fullBg`，于是被我们整块换成壁纸 ——
        //   大图变壁纸 ⇒ 看不到；每帧重铺 ⇒ 闪烁。
        //   页面底图（氛围图 / 模糊底）的 kind 是 ATMOS / BLUR，不是 CONTENT，
        //   所以把 CONTENT 排除掉不影响播放页。
        boolean fullBg = fillDecor && h * 100 >= (long) BgPeel.sSH * 92 && kind != BgPeel.IMG_CONTENT;
        // ★ v0.1.76：满屏底图出现 = 「可能是播放页」的弱信号，只用来给心跳的
        //   「主动扫进度条」放行 —— 免得在普通页面上每 500ms 白扫 400 个 View。
        if (fullBg) BgPeel.sFullBgSeenAt = System.currentTimeMillis();
        // ★★★ v0.1.77（r80 第 4 / 5 条）：**内容图一律不接管。**
        //   用户这两条：本地缓存的图片点开不显示、概述里的图片干脆不加载了。
        //   真凶就在下面这段：`kind == BgPeel.IMG_CONTENT` 且「满屏 + 贴顶 + 不在 Screen 里」
        //   时，后面的条件全都不成立 ⇒ 不 return ⇒ 一路铺成壁纸。
        //   而「本地缓存图能读到 BitmapDrawable、在线图读不到（Fresco）」正好解释了
        //   「在线正常、本地不正常」—— 只有读得到 bitmap 的那条才会被判成 CONTENT。
        // ★★★ v0.1.80（r104 用户第 2 条）：**「宿主声明了模糊半径的满屏层」是底，不是内容图。**
        //
        //   用户这一条：「偶尔」播放页背景会退回<b>模糊版的作品封面</b>。
        //   那条路径正是这里：宿主的播放页底图是 {@code <作品封面> + blurRadius=10}
        //   （先拿清晰封面、再由 expo 自己糊），我们读到的位图是<b>清晰</b>的
        //   ⇒ {@code imgKind} 判成 BgPeel.IMG_CONTENT ⇒ 上面那行直接放行给宿主
        //   ⇒ 屏幕上就是「糊掉的作品封面」。
        //   而同一张底图如果宿主下发的是<b>已经糊好</b>的位图（缓存命中那一次），
        //   kind 就是 BgPeel.IMG_BLUR ⇒ 我们接管 ⇒ 正常显示壁纸。
        //   ——「偶尔」两个字就是这么来的。
        //
        //   判据收紧到三条同时成立（缺一不可）：
        //     ① {@code radius > 0}：宿主**亲口声明**这层是模糊装饰
        //        （{@link #blurRadiusOf} 内含「满屏 + 不在分页容器里」两道闸，
        //        图片浏览那种满屏照片读不出半径，永远不会走到这里）；
        //     ② 满屏 + 贴顶（{@code fillDecor}）；
        //     ③ 它确实在播放页里（活体进度条 / 播放页窗口 / 播放页根子树）。
        boolean blurDeclared = radius > 0 && fillDecor
                && (PlayPage.playContext(v) || PlayPage.isPlayWindow(v) || PlayPage.underPlayRoot(v));
        if (kind == BgPeel.IMG_CONTENT && !blurDeclared) {
            // ★★★ v0.1.81：放行前纠偏。这一层如果上一刻还被我们铺成播放档
            //   （`wasPaintedAsPlay`），这一帧却判成内容图 ⇒ 判定临时失效（半径读 0、
            //   播放窗口刚被清空等）。错帧一旦录进 RenderNode，3084 行那个单向
            //   invalidate（只许「清晰 → 播放档」）永远救不回来 ⇒ 一直糊着，
            //   直到用户开关一次封面屏蔽触发全树重画。这里节流地补一次 invalidate，
            //   让它按恢复后的判据重录；带次数上限，判定真坏死时不会变成重绘风暴。
            PlayPage.healPlayBackdrop(v, fillDecor);
            return false;
        }
        if (kind != BgPeel.IMG_BLUR && !fullBg && !blurDeclared) {
            // veil / atmos 各有更精确的分支，这里不抢；
            // 只有「读不出像素的满屏底图」才兜底（r40 里 radius 时有时无，源头就是它）。
            if (kind != BgPeel.IMG_CONTENT || radius > 0 || !fillDecor || BgPeel.insideScreen(v)) return false;
        }
        // ★★★ v0.1.37（r42 用户第 3 / 4 条）：**把播放页模糊还回来**。
        //   v0.1.35 为了让「播放列表详情不再整页糊」把这里一刀切成 play=false，
        //   结果播放页（媒体库作品音轨 / PurePlay 音轨）也一起变成清晰壁纸 ——
        //   用户截图里那片清晰的手持笔记本照片就是这么来的。
        //
        //   r42 日志给了新的实证：**这一整轮只有一条 `blur bg r=10 1272x2772`**，
        //   也就是说「整屏 + 宿主声明了 blurRadius ≥ 8」在这个宿主上只出现在播放页。
        //   v0.1.34 之所以一糊就是整页，真凶不是这条判据，而是它顺手调了
        //   {@code ensurePlayWindowBg} 把<b>窗口根</b>换成模糊壁纸 ——
        //   离开播放页时没人还原 ⇒ 整个 App 都是糊的（r39 / r40 两次）。
        //   所以：判据留（半径 + seekbar 存活），<b>绝不碰窗口背景</b>。
        // ★★★ v0.1.40：**`radius >= 8` 不再是播放页证据**。
        //   宿主的「播放列表 / 摘选详情 / 作品详情」整页底图**同样带 blurRadius=10**
        //   （r34 起反复实证），拿半径判播放页 ⇒ 那些页面一律被铺成播放档（糊）。
        //   用户这一轮第 9 条「点开自己建的播放列表又糊了」就是它。
        //   现在只认唯一的活体证据：真的有一个 seekbar 在屏幕上（见 isPlayLive）。
        // ★★★ v0.1.62：**同一个窗口里的所有满屏底必须用同一档底图**。
        //   原先只按逐层的 playContext 选档，于是同一屏里
        //   `SkiaTextureView 1272x2772` / `ExpoImageView 1272x2913` / `SkiaTextureView 1272x954`
        //   三层可能分别落到「清晰壁纸」和「播放档（壁纸+模糊）」两种底图上
        //   —— 那就是「背景被裁剪成几块 / 越来越多色彩切片」。
        //   窗口级旗子（2.5 秒 TTL）一置位，这个窗口里全部统一为播放档。
        // ★★★ v0.1.75（r78 第 1 条）：**时间窗口判据全部删除。**
        //   上一版这里挂着 `BgPeel.sPlayOnScreenAt < 800ms`。它是「整个窗口里有一条活着的
        //   进度条」的产物 —— 迷你播放条存在时它对<b>任何页面</b>都成立
        //   ⇒ 首页 / 媒体库 / 设定也按「播放档」铺底、还跟着档位翻转去 invalidate
        //   ⇒ 叠加成「迷你条在的时候滑动特别卡」。
        //   现在只认对象级事实：播放窗口 / 播放页根子树 / 曾经被 paint play 画过。
        boolean play = PlayPage.playContext(v) || PlayPage.isPlayWindow(v) || PlayPage.underPlayRoot(v)
                || PlayPage.wasPaintedAsPlay(v);
        if (play) {
            PlayPage.markPlayWindow(v);
            // ★★★ v0.1.63：**记录「这一秒刚铺过播放档底图」**。
            //   窗口根的兜底底板用它当信号 —— 不再依赖 playContext/isPlayWindow
            //   在根那一层能不能判出来（实测那两条在根上经常都为 false，
            //   所以 0.1.59 的 `paint root bg` 一次都没打出来）。
            BgPeel.sPlayPaintedAt = System.currentTimeMillis();
            // v0.1.67：播放页被认出来 ⇒ 顺手把层级写进日志（每 3 秒最多一次）
            BgDiag.auditPlayTree((View) v);
            // v0.1.70：同一时刻也往 logcat 打一份「带坐标 + 归属」的层级（adb 可直读）
            BgDiag.probePlayTree((View) v);
        }
        // ★★★ v0.1.74（r76 第 1 条的正解）：**档位翻转时强制重画这一层。**
        //   档位（清晰壁纸 / 播放档模糊）是由 playContext 这类<b>会失效</b>的判据算的：
        //   首次打开播放页的头几帧、以及回前台之后，判据常常还没恢复 ⇒ 这一层被画成
        //   清晰壁纸，而这笔指令已经录进 RenderNode ⇒ 之后判据恢复了也不会重画，
        //   屏幕就一直停在清晰版（用户：「回前台后背景不模糊了」）。
        //   所以只要这一层的档位和「上一次画它时用的档位」不一致，就 invalidate 一次，
        //   让它按新档位重录。翻转只发生一两次，代价可以忽略。
        // ⚠️ 只允许「清晰 → 播放档」这一个方向的翻转重绘。
        //   反向（播放档 → 清晰）如果也 invalidate，就会和 {@link #BgPeel.sPlayOnScreenAt}
        //   那种<b>时变</b>判据形成来回翻转 ⇒ 每帧一次 invalidate ⇒ 整页疯狂重绘
        //   （r77「页面上下滑动超级卡顿」的头号嫌疑）。
        // ⚠️ v0.1.76：**只有「满屏底图」才值得为档位纠偏去 invalidate。**
        //   迷你条存在时 `playContext` 对列表页的层也可能成立（都落到窗口根），
        //   而滚动时每一张新出现的卡片都是「第一次」⇒ 每张卡片各来一次 invalidate
        //   ⇒ 实测 50th 直接飙到 22ms。卡片本来也不该走播放档，这条闸把误伤关掉。
        // ★★★ v0.1.79（r82 架构重做）：**播放页里的满屏底图，底板挂上后一律不画。**
        //   这正是「一层对象级底板 + 其余全 drop」的另一半：
        //   播放页根负责显示壁纸（background），子层只贡献自己的内容（封面/文字/控件）。
        boolean wasPlayTier = PlayPage.wasPaintedAsPlay(v);
        if (fullBg && BgPeel.sPlayRootBgBmp != null && PlayPage.underPlayRoot(v)) {
            BgPeel.sPainted = true;
            Rect rr = BgPeel.CLIP0.get();
            rr.set(0, 0, w, h);
            BgDiag.hit("drop play backdrop -> ", v, rr);
            return true;
        }
        if (play && !wasPlayTier && fullBg) {
            try {
                v.invalidate();
            } catch (Throwable ignored) {
            }
        }
        Bitmap bmp = play ? PlayPage.playBmp() : BgPeel.sWpBmp;
        if (bmp == null) bmp = play ? BgPeel.sWpBarBmp : BgPeel.sWpBarBmp;
        // ★★★ v0.1.74（r76 第三轮）：**不再 drop 内层满屏底图。**
        //   上一版把它们 drop 掉，只留根层那个 drawable 当唯一底，
        //   结果两条新问题同时冒出来：
        //     · 根层的 drawable bounds 带 top inset(18px) ⇒ 屏幕最顶一条黑；
        //     · 把裁剪扩到整层去补那条黑 ⇒ 又把宿主的「顶部压暗层」盖掉了
        //       （用户：「第一次打开播放页，顶部区域不对」）。
        //   内层那两张满屏底图（1272×2772 / 1272×2913）的 bounds 是<b>真正全屏</b>，
        //   而且它们在宿主内容层<b>下面</b> ⇒ 交给它们画，顶部自然覆盖、也压不到 scrim。
        try {
            long nowT = System.currentTimeMillis();
            if (nowT - BgPeel.sBgTraceAt > 500L && RemoteConfig.debugLog()) {
                BgPeel.sBgTraceAt = nowT;
                BgPeel.log("bgTrace " + BgPeel.name(v) + " " + w + "x" + h + " kind=" + kind
                        + " r=" + radius + " full=" + (fullBg ? 1 : 0)
                        + " play=" + (play ? 1 : 0) + " cs=" + BgPeel.canvasScale(v)
                        + " bmp=" + (bmp == null ? "null" : "ok"));
            }
        } catch (Throwable ignored) {
        }
        if (bmp == null) return false;
        Rect r = BgPeel.dstRect(w, h);
        if (!paintAligned(c, bmp, v, r, false)) return false;
        // ★★ v0.1.27 用户第 6 条：把「这个半径对应到哪个页面」留痕。
        //   ★★ v0.1.28 用户第 1 / 2 条：**每一张模糊底都记**（不再只记播放档）。
        //   下一轮日志里 `blur bg r=? ->play/clear` 就是判别「播放页 / 图片浏览 / 列表详情」
        //   的真凭实据：同一个 r 值出现在图片浏览里，就说明宿主用的是同一个组件，
        //   得换别的判据（不能只靠半径分档）。
        if (BgPeel.sBlurLogs < BgPeel.BLUR_LOG_BUDGET) {
            BgPeel.sBlurLogs++;
            BgPeel.log("blur bg r=" + radius + " " + w + "x" + h + " top=" + BgPeel.topOf(v)
                    + " pg=" + (play ? 1 : 0)
                    + (play ? " ->play" : " ->clear"));
        }
        PlayPage.notePageBackdrop(v, h, bmp);
        BgPeel.sBlurBg++;
        BgPeel.sPainted = true;
        BgDiag.hit(play ? "paint play blur -> " : "paint cover clear -> ", v, r);
        return true;
    }


    /**
     * ★★★ v0.1.37（r42 用户第 2 条）：<b>弹层里的大块图片层也要铺玻璃</b>。
     *
     * <h3>为什么背景 drawable 那条路治不了三张卡</h3>
     * 「下载音轨 / PurePlay 简介 / PurePlay 导入」这三张的面板底<b>不是</b>背景 drawable，
     * 而是一张 {@code ExpoImageView}（r42 日志：{@code Sheet miss ExpoImageView 1153x864
     * … dlg=1}）。图片层走的是 {@code isImageLayer} 分支，那里只有「氛围图 / 兜底」两条路，
     * 都不接「弹层里的内容底」⇒ 宿主那张<b>半透明紫图</b>照原样画出来 ⇒
     * 用户看到的就是「内容完全和主界面融为一体」——因为那层紫根本挡不住背后的内容。
     *
     * <p>判据只有三条，全部收在「弹层里」这个大前提下：
     * <ul>
     *   <li>在独立 Dialog 窗口里（页面里的图永远不会被碰）；</li>
     *   <li>够宽（≥60% 屏宽）且够大（≥25% 屏面积）—— 小图标 / 缩略图不动；</li>
     *   <li>不是视频层。</li>
     * </ul>
     *
     * <p>⚠️ 刻意<b>不</b>查 {@code coveredByGlass}：这一层本来画的是「半透明紫」，
     * 跳过它就等于把这层紫放回去盖住底下已经铺好的玻璃（r42 的「只有一块糊、其余紫底」
     * 就是这么来的）。重复画同一张位图的代价可以忽略。
     */
    static boolean paintDialogGlass(View v, Canvas c, HostDraw host) {
        if (!BgPeel.sArmed) return false;
        if (BgPeel.sSW <= 0 || BgPeel.sSH <= 0) return false;
        if (c == null || host == null) return false;
        // ★★★ v0.1.39：主窗口里那种「挂在 RN 根视图上的弹层」也要接（见 #mainWindowSheet）。
        if (!BgPeel.inFloatingLayer(v)) return false;
        // ★★★ v0.1.41（r12 用户第 7 条）：**播放页里的图片层一律不接**。
        //   音轨播放页跑在独立窗口里（dlg=1），里面那块 {@code ExpoImageView 1440x1080}
        //   是<b>封面横幅</b>；把它当「半透明面板底」铺成玻璃 ⇒ 封面被换掉
        //   ⇒ 用户连着两轮说「封面死都不显示」。
        // ★★★ v0.1.81（r84 第 2 条「偶发：封面不显示」）：
        //   判据从 `isPlayWindow || playContext` 换成**纯结构**的 `inPlayTree`。
        //   原来那两条依赖活体进度条，在「首次打开 / 树重建」那几帧失效 ⇒
        //   播放页里的封面横幅被当成「半透明面板底」铺成壁纸 ⇒ 封面永久消失
        //   （用户 17:43:58 那张截图：背景是壁纸的模糊版、封面卡片没了）。
        //   `inPlayTree` 走「祖先链里有没有满屏氛围底图」，与进度条无关 ⇒ 那几帧也成立。
        if (PlayPage.inPlayTree(v)) return false;
        int w;
        int h;
        try {
            w = v.getWidth();
            h = v.getHeight();
        } catch (Throwable t) {
            return false;
        }
        if (w <= 0 || h <= 0) return false;
        if (w * 100 < (long) BgPeel.sSW * BgPeel.DIALOG_IMG_MIN_W_PCT) return false;
        if ((long) w * h * 100 < (long) BgPeel.sSW * BgPeel.sSH * BgPeel.DIALOG_IMG_MIN_AREA_PCT) return false;
        if (BgPeel.isVideoSurface(v)) return false;
        // ⚠️ 必须是**半透明**的图（VEIL / ATMOS）。这一条是关键的安全阀：
        //   弹层里也可能有真照片（封面大图 / 预览图），它们是不透明 RGB ⇒ BgPeel.IMG_CONTENT
        //   ⇒ 这里一律不动。而「和背后内容融为一体」的那三张面板底恰恰就是
        //   半透明紫（用户的描述本身就是这个意思：能透出后面的内容）。
        int kind = CoverLayer.imgKind(v);
        if (kind != BgPeel.IMG_VEIL && kind != BgPeel.IMG_ATMOS) return false;
        // ★★★ v0.1.41（r12 用户第 7 条）：**不透明的图绝不是面板底**。
        //   r12 日志里 `dialog glass -> ExpoImageView 1440x1080` 出现 3 次 ——
        //   那 1080px 高的一层是<b>作品封面</b>，被当成「半透明紫面板」铺成了玻璃，
        //   于是「封面死都不显示」。封面照片 avg ≈ 1.0（只是编码带 alpha 通道），
        //   三张面板底 avg 在 0.6~0.9 ⇒ 一条 0.90 的线正好把它们分开。
        float av = CoverLayer.alphaAvgOf(v);
        if (av >= BgPeel.GLASS_IMG_MAX_ALPHA) {
            noteGlassFail("dlg opaque " + (int) (av * 100));
            return false;
        }
        Bitmap glass = glassBmp(v);
        if (glass == null) {
            noteGlassFail("dlg bmp");
            return false;
        }
        // ★★★ v0.1.41（r12 证据 A）：**位置一律用本层尺寸（0,0,w,h），不再用整屏位图对齐**。
        //   老代码走 paintAligned → CoverLayer.coverRect(bmp, -loc) ，loc 来自 getLocationInWindow；
        //   面板所在的 Dialog 窗口 top=280 ⇒ 位图整体下移 280px 后被 clipRect 裁掉，
        //   而 drawBitmap 画在裁剪区外不抛异常 ⇒ 静默成功 ⇒ 面板整块消失。
        //   现在 paintLocal 用 getLocationOnScreen 只管「取哪一片壁纸」，
        //   dst 的落点永远是本地 (0,0,w,h) —— 落点不可能错。
        Rect r = BgPeel.dstRect(w, h);
        int save = c.save();
        boolean ok;
        try {
            ok = paintLocal(c, glass, v, r);
            if (!ok) return false;
            // 宿主原本那张半透明紫图按 {@link #BgPeel.GLASS_IMG_TINT_PCT} 叠回来：
            // 完全不要它 ⇒ 面板和背景糊成一片、看不出边界；全要它 ⇒ 紫遮罩回来。
            int s2 = c.saveLayerAlpha(0, 0, w, h, (int) (BgPeel.GLASS_IMG_TINT_PCT * 255f));
            try {
                host.draw();
            } catch (Throwable ignored) {
                // 宿主这张图画不出来也无所谓：玻璃底已经在上面了，观感正常
            } finally {
                c.restoreToCount(s2);
            }
        } catch (Throwable t) {
            return false;
        } finally {
            c.restoreToCount(save);
        }
        BgPeel.sSheet++;
        BgPeel.sPainted = true;
        BgPeel.GLASSED.put(v, Boolean.TRUE);
        BgDiag.hit("dialog glassA -> ", v, r);
        if (BgPeel.sGlassLogs < BgPeel.GLASS_LOG_BUDGET) {
            BgPeel.sGlassLogs++;
            BgPeel.log("glassA img -> " + BgPeel.name(v) + " " + w + "x" + h + " kind="
                    + (kind == BgPeel.IMG_VEIL ? "veil" : "atmos")
                    + " tint=" + (int) (BgPeel.GLASS_IMG_TINT_PCT * 100) + "%");
        }
        return true;
    }


    /** F2 面板玻璃：画布铺模糊壁纸并轻叠宿主原色。 */
    static boolean paintDrawableGlass(Drawable d, Canvas c, View v, Rect b) {
        if (!BgPeel.sArmed || BgPeel.sSW <= 0 || BgPeel.sSH <= 0) return false;
        if (Boolean.TRUE.equals(BgPeel.REENTRY.get())) return false;
        if (!isGlassSurface(v, d, b)) return false;
        Bitmap g = glassBmp(v);
        if (g == null) {
            noteGlassFail("bmp");
            return false;
        }
        if (!paintAligned(c, g, v, b, false)) return false;
        int op = d.getOpacity();
        float tint = (op == PixelFormat.OPAQUE) ? BgPeel.GLASS_SOLID_TINT_PCT : BgPeel.GLASS_TINT_PCT;
        BgPeel.keepTint(c, d, b, tint);
        BgPeel.sSheet++;
        BgPeel.sPainted = true;
        BgPeel.GLASSED.put(v, Boolean.TRUE);
        if (BgPeel.sGlassLogs < BgPeel.GLASS_LOG_BUDGET) {
            BgPeel.sGlassLogs++;
            BgPeel.log("glass -> " + BgPeel.name(v) + " " + b.width() + "x" + b.height()
                    + " bmp=" + g.getWidth() + "x" + g.getHeight());
        }
        BgDiag.hit("glass -> ", v, b);
        return true;
    }


    /**
     * ★★★ v0.1.41（r12 路径 A）：<b>局部绘制</b> —— 落点永远错不了的那一版。
     *
     * <h3>和 {@link #paintAligned} 的唯一差别</h3>
     * <ul>
     *   <li>裁剪：这里的 {@code clip} 是<b>本层局部矩形</b> {@code (0,0,w,h)}，
     *       不是整窗矩形 ⇒ 位图不可能被推到裁剪区外。</li>
     *   <li>（v0.1.98 起两者位置来源统一：{@code getLocationInWindow()} + 本窗口尺寸。）</li>
     * </ul>
     *
     * <p>为什么这一条能吃掉「面板整块消失」：老路径里 {@code dst} 由 {@code -loc} 算出，
     * {@code clipRect} 用的是 local 矩形，两者一旦不是同一个坐标系，位图就整块落在
     * 裁剪区外 —— 而 {@code drawBitmap} <b>不抛异常</b>，于是 {@code drew=true}、
     * 静默成功、面板变透明。现在落点和裁剪同在本地坐标系，结构上不可能错。
     */
    static boolean paintLocal(Canvas c, Bitmap bmp, View v, Rect clip) {
        if (bmp == null || bmp.isRecycled() || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) {
            return false;
        }
        if (clip == null || clip.isEmpty()) return false;
        int[] loc = BgPeel.LOC.get();
        int[] geo = BgPeel.RGEO.get();
        try {
            // ★★★ v0.1.98 / v0.1.140：窗口坐标；坏则布局回退（见 windowLoc）。
            if (!BgPeel.windowLoc(v, loc)) return false;
            if (!BgPeel.rootGeometry(v, geo)) return false;
        } catch (Throwable t) {
            return false;
        }
        // ★★★ v0.1.72（r74）：和 {@link #paintAligned} 同一条修正 —— 转场缩放要除掉。
        float cs = BgPeel.canvasScale(v);
        float inv = 1f / cs;
        RectF dst = CoverLayer.coverRect(bmp, -loc[0] * inv, -loc[1] * inv, geo[0] * inv, geo[1] * inv);
        Rect src = BgPeel.SRC.get();
        src.set(0, 0, bmp.getWidth(), bmp.getHeight());
        int save = c.save();
        boolean drew = false;
        try {
            c.clipRect(clip);
            c.drawBitmap(bmp, src, dst, BgPeel.BMP_PAINT);
            drew = true;
        } catch (Throwable ignored) {
        } finally {
            c.restoreToCount(save);
        }
        return drew;
    }


    /**
     * 绘制：已标记的满屏底（或同窗满屏贴顶图）铺 {@link #BgPeel.sWpBmp}。
     * 必须在 paintBlurBg 的 CONTENT 放行之前调用。
     */
    static boolean paintReviewBg(View v, Canvas c) {
        if (!BgPeel.sArmed || v == null || c == null) return false;
        Bitmap bmp = BgPeel.sWpBmp;
        if (bmp == null || bmp.isRecycled()) return false;
        if (!BgPeel.isFullscreenReviewBackdrop(v)) return false;
        View marked = BgPeel.sReviewBgRef == null ? null : BgPeel.sReviewBgRef.get();
        boolean hit = marked == v;
        if (!hit) {
            try {
                android.os.IBinder tok = BgPeel.sReviewToken;
                if (tok != null && v.getWindowToken() == tok) {
                    hit = true;
                }
            } catch (Throwable ignored) {
            }
        }
        // 绘制路径补救：probe 错过时当场接管。
        // ★★★ v0.1.140：不用宽松「满屏图+方图」structural，避免导入/下载窗误标。
        // ★★★ v0.1.146：多语言 cue + CD 形；近满屏无 CD 的确认只留给 probe。
        if (!hit) {
            try {
                if (!BgPeel.inDialogWindow(v)) return false;
                View root = v.getRootView();
                if (root == null) return false;
                if (!(CoverLayer.rootHasReviewCue(root)
                        || BgPeel.a11yHasMonthlyCue(root, 0, 0)
                        || BgPeel.findReviewCdInTree(root, 0, 0))) {
                    return false;
                }
                BgPeel.sReviewToken = v.getWindowToken();
                BgPeel.sReviewBgRef = new java.lang.ref.WeakReference<>(v);
                hit = true;
                BgPeel.reviewLog("review bg arm@draw -> " + BgPeel.name(v)
                        + " " + v.getWidth() + "x" + v.getHeight());
            } catch (Throwable t) {
                return false;
            }
        }
        if (!hit) return false;
        int w;
        int h;
        try {
            w = v.getWidth();
            h = v.getHeight();
        } catch (Throwable t) {
            return false;
        }
        if (w <= 0 || h <= 0) return false;
        if (!paintAligned(c, bmp, v, BgPeel.dstRect(w, h), false)) return false;
        BgPeel.sPainted = true;
        if (BgPeel.sReviewHitLogs < 4) {
            BgPeel.sReviewHitLogs++;
            BgPeel.log("review bg hit -> " + BgPeel.name(v) + " " + w + "x" + h);
        }
        return true;
    }


    /**
     * ★★ v0.1.25 用户第 3 条：<b>只糊面板这一块</b>（面板容器版）。
     *
     * <h3>为什么还要有这个方法</h3>
     * v0.1.24 只在「面板的 <b>背景 drawable</b>」上换了底。可宿主的底部面板是
     * {@code react-native-modal} 里的一个大 View，它的背景 drawable 常常只铺到
     * 「标题 + 标签栏」那一层，下面的列表区是子层各画各的 ——
     * 于是用户看到「概述那块没糊、下载那块跟背后的模糊糊成一片」。
     *
     * <p>这里在<b>容器自己的 draw 之前</b>，把整个面板矩形先涂成「背景 + 高斯模糊」，
     * 然后照常 {@code chain.proceed()} 让宿主把子内容画上去。
     * 因为 {@code View#draw} 的顺序是「背景 → 自己 → 子 View」，
     * 我们在最前面铺的这层永远在最底下，不会盖住任何内容。
     *
     * <p>不返回 true：它只是「铺底」，宿主那一整棵子树必须继续画。
     */
    static boolean paintSheetPanel(View v, Canvas c) {
        if (!BgPeel.sArmed) return false;
        if (BgPeel.sSW <= 0 || BgPeel.sSH <= 0) return false;
        int w;
        int h;
        try {
            w = v.getWidth();
            h = v.getHeight();
        } catch (Throwable t) {
            return false;
        }
        if (w <= 0 || h <= 0) return false;
        // ★★ v0.1.29 诊断：把「独立窗口里够大、但不是面板窗口」的块登记下来（限 6 条）。
        //   r33/r34 就是靠这条把「作品详情页是整页窗口」敲死的；留着它，
        //   下一轮万一又冒出「某块该糊的没糊 / 不该糊的糊了」，日志里能直接看到它的
        //   窗口尺寸，不用再猜比例。
        if (BgPeel.sMidLogs < BgPeel.MID_LOG_BUDGET && h * 4 > BgPeel.sSH && w * 2 > BgPeel.sSW
                && !BgPeel.inMainWindow(v)) {
            try {
                Activity sa = BgPeel.sPoll;
                View ad = sa == null ? null : BgPeel.decorOf(sa);
                View rt = v.getRootView();
                if (rt != null && ad != null && rt != ad) {
                    BgPeel.sMidLogs++;
                    BgPeel.log("mid block " + BgPeel.name(v) + " " + w + "x" + h
                            + " top=" + BgPeel.topOf(v) + " root=" + rt.getWidth() + "x" + rt.getHeight()
                            + " bg=" + BgPeel.desc(v.getBackground()));
                }
            } catch (Throwable ignored) {
            }
        }
        // ★★★ v0.1.31 用户第 1 / 2 条：这里**只保留「贴底升起」这一种几何**。
        //   窗口 / 内容根比例那套判据全部删掉 —— 它们把「作品详情页的文件列表块」
        //   和「播放列表详情整页」一起判成了面板（用户第 1、2 条就是这个）。
        //   现在「有自己底色的块」走 {@link #isGlassSurface}，这里只兜
        //   「<b>没有</b>底色的贴底容器」（宿主把底交给头部那张叶子的时候）。
        if (!BgPeel.isSheetLike(v)) {
            BgPeel.noteSheetMiss(v, w, h);
            return false;
        }
        // ★★★ v0.1.33：这一条和 isGlassSurface 共用同一套「浮层」判据 ——
        //   不在 Dialog 窗口里、或者已经被外层玻璃盖过的，一律不铺。
        // ★★★ v0.1.38：这里也不再因为「外层已经有玻璃」而跳过。
        //   没底色的容器铺一层玻璃是安全的：真正不透明的子块各自也会铺，
        //   透明子块则正好靠这一层显出玻璃 —— 两种情形都对。
        if (!BgPeel.inFloatingLayer(v)) return false;
        if (BgPeel.isVideoSurface(v) || BgPeel.isImageLayer(v)) return false;
        // ★★ v0.1.26 用户第 2 条：**不再要求「自己带背景」**。
        //   r31 真机分类得很清楚：
        //     · 有模糊的 —— 点击三个点 / 新建播放列表 / 新建摘选播放列表；
        //     · 没模糊的 —— 概述、下载音轨、PurePlay 简介、PurePlay 导入。
        //   差别就在「面板的可见底是不是挂在它自己身上」：后一组的面板
        //   是一个**没背景的布局容器**（底由头部那张叶子提供），旧判据直接 return false
        //   ⇒ 列表区永远是透明的。现在只要「在面板里」就铺一层玻璃。
        //   ★★ v0.1.28：但**有背景的那种一律交给 {@link #paintWallpaper}**。
        //   理由很硬：我们在 {@code chain.proceed()} 之前画，宿主紧接着就会
        //   {@code drawBackground} 把它不透明的底盖回来 —— 铺了等于白烧一遍全屏图。
        //   背景那条路已经实现了「玻璃 + 把半透明底叠回来」，效果完全等价且更省。
        //   这一条同时把 r33 那个 `sheet=272/帧` 的怪现象按住了：一大片
        //   「有背景的大块」不再每帧各画一次全屏位图。
        if (v.getBackground() != null) return false;
        // ★★★ v0.1.31 用户第 2 条：**父层也必须贴底**。
        //   「作品详情页里那块文件列表」（1272x750、底边贴屏幕底）自己贴底，
        //   但它的父层是整页滚动容器 —— 顶天立地，不贴底 ⇒ 在这里被挡掉。
        //   真面板的父子是一条「贴底 → 贴底」的链。
        if (!BgPeel.isBottomAnchored((View) v.getParent())) return false;
        // 只铺「大块内容区」：面板里的单行（几十像素高）不铺，
        // 否则一个列表几十行，每行都画一遍全屏图，纯白烧 GPU。
        // ★ v0.1.29：12.5% 屏高（用户第 2 条里的「下载音轨」是一张小卡片，比 1/3 屏小）。
        if (h * 8 < BgPeel.sSH) return false;
        try {
            if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() == 0) return false;
        } catch (Throwable ignored) {
        }
        // 太窄的小块（图标 / 头像 / 分隔线）不铺：铺了反而像一块污渍。
        if (w * 2 < BgPeel.sSW) return false;
        // ★★★ v0.1.140：容器铺底继续停用（与 v0.1.41 同结论）。
        //   v0.1.139 用 paintLocal 重开后，概述只糊中间一条、菜单顶部分层 ——
        //   ScrollView 与有底色的父面板各采各的位，动画中 winY 再一飘就错位。
        //   有底色面板改走 paintDrawableGlass + windowLoc（布局回退），不再靠这条兜。
        BgPeel.noteSheetMiss(v, w, h);
        return false;
    }


    static void recycleGlass(Bitmap b) {
        if (b == null || b.isRecycled()) return;
        try {
            b.recycle();
        } catch (Throwable ignored) {
        }
    }

}
