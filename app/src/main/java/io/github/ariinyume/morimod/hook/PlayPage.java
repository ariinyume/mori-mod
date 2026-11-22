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
 * BgPeel 职责拆分（v0.1.146 B7）· PlayPage。
 * 共享 static 状态仍在 BgPeel；本类只承载本职责的方法。
 */
final class PlayPage {

    private PlayPage() {
    }

    static View anchorCached(View v) {
        if (v == null) return null;
        View a = BgPeel.PC_ANCHOR.get(v);
        if (a != null) return a;
        try {
            a = pageAnchorOf(v);
        } catch (Throwable t) {
            a = null;
        }
        BgPeel.PC_ANCHOR.put(v, a == null ? v : a);
        return a == null ? v : a;
    }


    /**
     * 页面切换事件：**只记时间戳 + 一次非侵入的「别摘页」尝试**（v0.1.80 / r104）。
     *
     * <p>r88~r103 在这里做了两件重活（整窗 invalidate、22 条全树快照 × 8 秒），
     * 用户 r104 第 5 条「返回主页后画面会抖一下」就是它们 —— 切页那一两百毫秒里
     * 我们在主线程上遍历整棵树、拼字符串、逐条写 logcat。
     *
     * <p>现在：
     * <ul>
     *   <li>只写一个时间戳（其它判据用得到它）；</li>
     *   <li>快照只在 {@code debugLog} 打开时拍，且**只拍一条**（当场那一瞬）；</li>
     *   <li>顺手调一次 {@link #tryDetachOff}（关掉 react-native-screens 的「非活动页摘除」）。
     *       这条路是官方支持的行为开关，<b>不改任何页面状态</b>；
     *       页面一直在树里 ⇒ 返回时没有「重新挂载 + 重渲染」的空窗，
     *       也就不需要 r103 那种把 activityState 硬改回 ON_TOP 的劫持。</li>
     * </ul>
     */
    static void armPageRepaint(final View anyScreenView, final String st) {
        try {
            BgPeel.sPageSwitchAt = System.currentTimeMillis();
            beginLightNav(520);
            // ★ v0.2.9：切页时若 review soft-arm 挂在主窗上，立刻清掉，防深阴影污染
            try {
                android.view.View soft = BgPeel.sReviewUiRoot == null
                        ? null : BgPeel.sReviewUiRoot.get();
                if (soft == null || !BgPeel.isReviewDialogCandidate(soft)) {
                    BgPeel.clearReviewBgMark();
                }
            } catch (Throwable ignored) {
            }
            BgPeel.sSnapFrom = BgPeel.sPageSwitchAt;
            // ★ 空窗遮罩已按用户要求整条回退（r106 用户第 2 条：
            //   「现在变成了所有页面切换都有了右滑的效果，而且还会卡顿一下，比之前更差」）。
            //   回退范围：抓拍 + VeilView + 两条触发点，全部移除；抓拍函数本身保留给诊断用。
            // ★ r107：r106 那两拍「页面上场后补重绘」已按用户要求撤回
            //   （实测会把播放页封面弄丢 —— 补拍时封面那层还被判成「非当前页内容」）。
            // ★★★ v0.1.151：tryDetachOff 挪到下一帧 —— 切页同步祖先链反射会先卡一帧再滑。
            if (anyScreenView != null) {
                BgPeel.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            BgPeel.tryDetachOff(anyScreenView);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
            if (!RemoteConfig.debugLog()) return;
            BgDiag.snapArm(true);
            BgPeel.sSnapAt = 0L;              // 允许这一发无条件打印
            BgDiag.snapOnce();                // 只拍「切页那一瞬」这一条
            if (BgPeel.sTraceLeft > 0) {
                BgPeel.sTraceLeft--;
                android.util.Log.i("MoriSnap", "page switch");
            }
        } catch (Throwable ignored) {
        }
    }


    /**
     * ★★★ v0.1.46：这支进度条是不是坐在一条「矮条」里（迷你播放条 / 底部操作条）。
     *
     * <p>沿祖先链往上找<b>通宽（≥90% 屏宽）</b>的容器：播放页的 seekbar 坐在整屏
     * 容器里，而迷你播放条/底部操作条的通宽容器只有一条矮条的高度。
     * 找到通宽且高度 ≥35% 屏高的容器 ⇒ 是正常页面 ⇒ 返回 false。
     *
     * <p>最多往上走 10 层，纯读布局属性，无分配、无绘制开销。
     */
    static boolean barAncestor(View v) {
        if (BgPeel.sSH <= 0) return false;
        try {
            Object p = v.getParent();
            for (int i = 0; i < 10 && p instanceof View; i++) {
                View pv = (View) p;
                int pw = pv.getWidth();
                int ph = pv.getHeight();
                if (pw > 0 && ph > 0 && pw * 100 >= (long) BgPeel.sSW * 90) {
                    if (ph * 100 >= (long) BgPeel.sSH * 35) return false;   // 整页容器 ⇒ 正常
                    return true;                                     // 矮条 ⇒ 迷你条
                }
                p = pv.getParent();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }


    /**
     * 底栏 / 媒体库选项卡切换后短暂降载。
     * <p>★★★ v0.1.151：允许续期（媒体库 ViewPager 手指横滑会连续 onPageScrolled，
     *   旧「窗内不续期」只盖住起滑后 240ms，后半段又掉回全量壁纸热路径 ⇒ 先卡一帧再滑）。
     *   只在「新截止更晚」时延长，不会被短窗口回缩。
     */
    static void beginLightNav(int ms) {
        if (ms < 80) ms = 80;
        long until = System.currentTimeMillis() + (long) ms;
        if (until > BgPeel.sLightUntil) {
            BgPeel.sLightUntil = until;
        }
    }


    /** 这一块已经被某个玻璃祖先盖过了（只画最外层，避免一格一格画）。 */
    static boolean coveredByGlass(View v) {
        View p = null;
        try {
            Object par = v.getParent();
            p = par instanceof View ? (View) par : null;
        } catch (Throwable t) {
            return false;
        }
        for (int i = 0; i < 12 && p != null; i++) {
            if (BgPeel.GLASSED.containsKey(p)) return true;
            Object par;
            try {
                par = p.getParent();
            } catch (Throwable t) {
                return false;
            }
            p = par instanceof View ? (View) par : null;
        }
        return false;
    }


    /**
     * ★★★ v0.1.72（r74 第 3 条，五稿）：<b>把播放页的底图挂成这一层的 background。</b>
     *
     * <h3>为什么 canvas 绘制救不了「透明」</h3>
     * 我们在 {@code View#draw} 里画壁纸，这笔指令被录进该层自己的 <b>RenderNode</b>。
     * 宿主切后台会销毁 Surface，回来时只要这一层的 RenderNode <b>没被重新录制</b>，
     * 屏幕上就没有任何壁纸（r74 实测：`draw seen=8 wall=0 play=0`，底图那几层根本没画）。
     * 而旧 RenderNode 里存的又正好是「我们 return true 跳过宿主绘制」的结果 ⇒ 整层空白。
     *
     * <h3>为什么 background 能救</h3>
     * {@code View#setBackground} 是<b>对象级状态</b>，不是一次性绘制指令：
     * 下一帧无论是新录还是复用，{@code View#draw} 都会先把 background 画出来。
     * 于是「壁纸」从「每帧有没有来得及画」变成「这层有没有挂壁纸」——
     * 后者只在换壁纸时才变，与转场 / 切后台完全无关。
     *
     * <h3>为什么不会像 r38 那样把主界面糊掉</h3>
     * 只对 {@code inFloatingLayer} 里<b>满屏的播放页根</b>动手（调用点已经筛过），
     * 从不碰 DecorView / 窗口根 / 列表页。
     *
     * <p>设置动作 post 到主线程并带忙碌闸：在 {@code draw} 调用栈里直接 setBackground
     * 会触发 requestLayout，容易变成重入循环。
     */
    static void ensurePlayRootBg(final View v, final Bitmap bmp) {
        try {
            if (v == null || bmp == null || bmp.isRecycled()) return;
            // ★★★ v0.1.73（r76 第 3 条）：**判重只看账本，不再和宿主拉锯。**
            //   宿主（ReactViewGroup）每次 props 更新都会重设自己的背景，
            //   若我们每次都「发现被改回去了再设一遍」，转场里就是
            //   setBackground ↔ 宿主 setBackground 反复打架 —— 每次都要
            //   invalidate 整层，这就是底部标签栏切换卡顿的一份。
            //   现在每个 View 只设一次：设上了是赚到（对象级底板防透明），
            //   被宿主盖掉也无所谓 —— 底图仍然由 {@code paint play screen bg}
            //   那条 canvas 路径提供，而且那一条已经保证铺满整层（含顶部 18px）。
            if (BgPeel.ROOT_BG.get(v) == bmp) return;
            if (BgPeel.sRootBgBusy) return;
            BgPeel.sRootBgBusy = true;
            BgPeel.MAIN.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (bmp.isRecycled() || v.getParent() == null) return;
                        v.setBackground(new io.github.ariinyume.morimod.view.CoverDrawable(bmp, 0));
                        BgPeel.ROOT_BG.put(v, bmp);
                        BgPeel.sPlayRootBgView = v;
                        BgPeel.sPlayRootBgBmp = bmp;
                        if (BgPeel.sRedoLogs < 12) {
                            BgPeel.sRedoLogs++;
                            BgPeel.log("play root bg -> " + BgPeel.name(v) + " "
                                    + v.getWidth() + "x" + v.getHeight());
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        BgPeel.sRootBgBusy = false;
                    }
                }
            });
        } catch (Throwable ignored) {
            BgPeel.sRootBgBusy = false;
        }
    }


    /**
     * 把「播放页」相关的全部账目作废（v0.1.81）。
     *
     * <p>后台/前台是天然的确定性边界：后台里没有任何一帧会被画出来，
     * 所以从这里开始把上一轮记的播放页痕迹全部丢掉，回前台后让每一页按<b>当时</b>
     * 的事实重新认一次。{@code BgPeel.PLAY_PAINT_PAGE}（「这层曾经被画成播放档」）
     * 必须一起清 —— 它是对象级记忆、本来永不过期，一旦误判就一直是糊的。
     */
    static void forgetPlayState() {
        try {
            BgPeel.PLAY_WIN.clear();
            BgPeel.PLAY_PAINT_PAGE.clear();
            BgPeel.PLAY_BG.clear();
            BgPeel.sPlayWinPage = null;
            BgPeel.sPlayScreen = null;
            BgPeel.sSeekPageAnchor = null;
            BgPeel.sPlayHoldUntil = 0L;
            BgPeel.sPlayHoldPage = null;
            BgPeel.sPlayPaintedAt = 0L;
        } catch (Throwable ignored) {
        }
    }


    /**
     * ★★★ v0.1.78（r81 第 1 / 2 条）：这一层的子树里有没有「满屏氛围底图」。
     *
     * <p>为什么要这条：播放页的识别原来全靠 {@code BgPeel.sPlaySeek}，而它在
     * 「退后台 → 回前台 → 再展开」这条路上会 detach ⇒ 所有基于它的判据一起失效
     * ⇒ 播放页根一直 drop ⇒ 持续透明。
     *
     * <p>而播放页有一个**纯结构**特征：根的直接子树里挂着两张
     * 「1272×2772 / 1272×2913」的氛围底图（{@code ExpoImageView}）。
     * 信息卡、普通 Dialog 的满屏底都是 {@code ReactViewGroup} 而不是 ImageView，
     * 所以这条不会误伤「信息卡背后宿主页面」那条修复。
     */
    static boolean hasFullBackdropChild(View v) {
        if (v == null || BgPeel.sSW <= 0 || BgPeel.sSH <= 0) return false;
        long now = System.currentTimeMillis();
        if (v == BgPeel.sFbView && now - BgPeel.sFbAt < 300L) return BgPeel.sFbVal;
        boolean val;
        try {
            val = scanFullBackdrop(v, 0);
        } catch (Throwable t) {
            val = false;
        }
        BgPeel.sFbView = v;
        BgPeel.sFbVal = val;
        BgPeel.sFbAt = now;
        return val;
    }


    /**
     * ★★★ v0.1.82：这层的子树里有没有「进度条形状」的 View。
     *
     * <p>与 {@link #containsPlaySeek} 的区别：那个要求进度条已经被记账，这个只看形状，
     * 所以**首次打开的头几帧也成立**。深度限 8、每层最多看 12 个子，300ms 缓存。
     */
    static boolean hasSeekShape(View v) {
        if (v == null || BgPeel.sSW <= 0 || BgPeel.sSH <= 0) return false;
        long now = System.currentTimeMillis();
        if (v == BgPeel.sSkView && now - BgPeel.sSkAt < 300L) return BgPeel.sSkVal;
        boolean val;
        try {
            val = scanSeekShape(v, 0);
        } catch (Throwable t) {
            val = false;
        }
        BgPeel.sSkView = v;
        BgPeel.sSkVal = val;
        BgPeel.sSkAt = now;
        return val;
    }


    /**
     * ★★★ v0.1.81：<b>放行播放页满屏底之前的档位纠偏</b>。
     *
     * <p>「偶尔背景退回模糊版作品封面」的另一半：某一帧判定临时失效（半径读 0、
     * 播放窗口刚被清空等），宿主的糊封面被放行上屏，而这条错误指令已经录进
     * RenderNode —— {@code paintBlurBg} 里的 invalidate 只允许「清晰 → 播放档」
     * 一个方向（防 r77 那种来回翻转的重绘风暴），反向没有任何人重画
     * ⇒ 画面就一直停在糊封面上，直到用户开关一次封面屏蔽（触发全树重画）才恢复。
     *
     * <p>条件收紧到「这层上一刻确实被我们铺成播放档」（{@link #wasPaintedAsPlay}），
     * 也就是<b>真的发生了档位翻转</b>才补一次 invalidate；全局 4 秒节流 +
     * 每个 View 最多 8 次，判定真坏死时不会变成重绘风暴。
     */
    static void healPlayBackdrop(View v, boolean fillDecor) {
        try {
            if (!fillDecor || !wasPaintedAsPlay(v)) return;
            long now = System.currentTimeMillis();
            if (now - BgPeel.sHealAt < 4000L) return;
            int[] n = BgPeel.HEAL_CNT.get(v);
            if (n == null) {
                n = new int[1];
                BgPeel.HEAL_CNT.put(v, n);
            }
            if (n[0] >= 8) return;
            n[0]++;
            BgPeel.sHealAt = now;
            BgPeel.log("heal play backdrop (tier flip) -> " + BgPeel.name(v) + " #" + n[0]);
            v.invalidate();
        } catch (Throwable ignored) {
        }
    }


    /**
     * ★★★ v0.1.47：700ms 保持窗也必须<b>页面级</b>。
     *
     * <p>保持窗的本意是「切后台那一下进度条短暂 detach，别让播放页闪一帧清晰壁纸」。
     * 旧实现是全局时间戳 ⇒ 离开播放页后的 700ms 里，列表页的顶栏也会被判成播放页
     * （肉眼看到的就是「返回列表时页头糊一下再变清晰」）。现在把锚点一起记下来，
     * 只有和锚点同页的层才能吃到这个保持窗。
     */
    static boolean holdActiveFor(View judge) {
        if (judge == null) return false;
        if (System.currentTimeMillis() >= BgPeel.sPlayHoldUntil) return false;
        java.lang.ref.WeakReference<View> ref = BgPeel.sPlayHoldPage;
        View anchor = ref == null ? null : ref.get();
        if (anchor == null) return false;      // 锚点已被回收 ⇒ 视为过期
        return samePage(anchor, judge);
    }


    /**
     * ★★★ v0.1.80（r83 第 3 条）：这一层是不是**在播放页那棵树里**（可以是它的任意子孙）。
     *
     * <p>与 {@link #isPlayRoot} 的区别：那个只认「播放页根」，这个认「根 + 所有子孙」。
     * 用在「这一块封面 / 这张图要不要被当成玻璃面板」这类判断上。
     *
     * <p>核心是最后那条**纯结构**判据：沿祖先链找「谁的子树里挂着满屏氛围底图」。
     * 它不依赖进度条，所以「首次打开 / 退后台回来」那几帧也成立 ——
     * 正是那几帧里 `playContext` 失效，才导致封面被涂成玻璃、背景变成模糊版。
     */
    static boolean inPlayTree(View v) {
        if (v == null) return false;
        // ★★★ v0.1.83（r86 第 4 条）：同样改成 O(深度)。
        //   上一版这里沿祖先链每层调一次 `hasFullBackdropChild`（遍历子树），
        //   而调用点是「每个 View 的每一帧 draw」—— 这是「打开作品页就卡」的主因。
        View r = BgPeel.sPlayRootRef == null ? null : BgPeel.sPlayRootRef.get();
        if (r != null) {
            try {
                View cur = v;
                for (int i = 0; i < 14 && cur != null; i++) {
                    if (cur == r) return true;
                    android.view.ViewParent p = cur.getParent();
                    cur = p instanceof View ? (View) p : null;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
        if (playContext(v) || isPlayWindow(v) || underPlayRoot(v) || wasPaintedAsPlay(v)) {
            return true;
        }
        try {
            View cur = v;
            for (int i = 0; i < 10 && cur != null; i++) {
                if (hasFullBackdropChild(cur)) return true;
                android.view.ViewParent p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }


    /** 此刻屏幕上有一个活着的进度条 ⇒ 这是播放页（见 {@link #notePlaySeek}）。
     *
     *  ★★★ v0.1.47（r47 用户第 2 / 3 条）：**必须传入「正在判的这一层」**。
     *
     *  <h3>为什么 0.1.46 修了迷你条还是糊</h3>
     *  0.1.46 把迷你条的滑块从记账里排掉了，但真正的漏洞在消费端：
     *  {@code isPlayLive()} 是一个<b>全局</b>信号 —— 它只看「上一次记的那支进度条
     *  还活着吗」，完全不看「活着的那支进度条和当前要画的这一层是不是同一个页面」。
     *  于是只要任何一支满足门槛的进度条活着（迷你播放条在播、或 react-native-screens
     *  把播放页保活在后台），{@code playContext()} 对**每一个页面**都返回 true。
     *
     *  <p>后果集中在两条「顶栏补底」的路径上（{@code paintWallpaper} / {@code swapLeaf}
     *  里的 {@code paint top fill}）：
     *  <pre>Bitmap top = playContext(v) ? playBmp() : BgPeel.sWpBmp;</pre>
     *  播放档位图（壁纸+重模糊）被铺到<b>列表页 / PurePlay / 播放列表页的顶部导航栏</b>上
     *  —— 而这些页面的页面底色走的是别的路径（铺清晰壁纸），于是用户看到的就是
     *  「只有页头上半区域是高斯模糊」。
     *
     *  <p>修法：活体进度条必须和当前这一层<b>同页</b>才算数（见 {@link #samePage}）。
     */
    static boolean isPlayLive(View judge) {
        java.lang.ref.WeakReference<View> ref = BgPeel.sPlaySeek;
        if (ref == null) return holdActiveFor(judge);
        View v = ref.get();
        if (v == null) {
            BgPeel.sPlaySeek = null;
            return holdActiveFor(judge);
        }
        try {
            // ★★★ v0.1.39：删掉这里那道 `v.getHeight() * 100 < BgPeel.sSH * 2` 的死门槛。
            //   高度/宽度筛选在 notePlaySeek 记账时已经做过一次，这里再判一次
            //   只会把宿主那条 54px 的进度条永久判死（= 播放页判定从没生效）。
            if (v.getWindowToken() != null && v.isShown()) {
                BgPeel.sPlayHoldUntil = System.currentTimeMillis() + BgPeel.PLAY_HOLD_MS;
                BgPeel.sPlayHoldPage = new java.lang.ref.WeakReference<>(pageAnchorOf(v));
                // ★★★ v0.1.47：活体进度条在别的页面（迷你条 / 保活的播放页）
                //   不能给当前这一层当「播放页证据」。
                return samePage(v, judge);
            }
        } catch (Throwable t) {
            // 读属性失败：按「刚刚还活着」处理，不要让异常打断绘制
        }
        return holdActiveFor(judge);
    }


    /**
     * 这块整屏模糊底是不是<b>播放页</b>的。
     *
     * <p>规则（见 {@link #BgPeel.PLAY_PAGE_TTL_MS}）：seekbar 刚加载过 ⇒ 把<b>当前这块所在的
     * 页面容器</b>绑定成播放页；此后只有同一个容器算播放页。
     * 列表页是另一个 {@code Screen} 实例 ⇒ 第一帧就返回 false，铺清晰壁纸。
     */
    static boolean isPlayPage(View v) {
        long now = System.currentTimeMillis();
        // ★★★ v0.1.48（r48 用户第 2 / 3 条）：**绑定的粒度必须是「页面锚点」，
        //   不能再是「Screen 容器」**。
        //
        //   真机日志（r48，14:01:06 ~ 14:01:12）：
        //   <pre>
        //   14:01:06.204  res  seekbar_track_material          ← 播放页素材开始加载
        //   14:01:06.626  blur bg r=10 1272x2772 pg=0 ->clear  ← 底图被判成「非播放页」
        //   14:01:12.785  blur bg r=10 1272x2772 pg=1 ->play   ← 6 秒后才翻过来
        //   sheet miss ExpoImageView 1272x954 ... inScr=0      ← 播放页**不在 Screen 里**
        //   </pre>
        //   这个宿主的播放页摊在<b>窗口根</b>上（{@code inScr=0}），
        //   而旧实现 `BgPeel.sPlayScreen = sc == null ? null : …` 在 sc==null 时**存了个 null**
        //   ⇒ 和「没有绑定」完全无法区分 ⇒ 每次判都落到 miss。
        //   于是播放页打开后的前几百毫秒（进度条还没画第一帧、素材刚加载）里，
        //   整屏底图被铺成<b>清晰壁纸</b>，别的层（封面横幅 / 面板）还在宿主手里
        //   ⇒ 用户看到的就是「背景被裁剪成几块」+「封面是纯模糊占位」，
        //   而等进度条那一路判定生效（{@link #isPlayLive}）画面才统一。
        //
        //   现在统一用 {@link #pageAnchorOf}：能找到 Screen 就是那个 Screen，
        //   找不到就是<b>窗口根</b> —— 永不为 null，也就永远不会退化。
        View pg = pageAnchorOf(v);
        java.lang.ref.WeakReference<View> ref = BgPeel.sPlayScreen;
        View bound = ref == null ? null : ref.get();
        if (pg != null && bound == pg) return true;

        // ★★★ v0.1.47：**已经见过真进度条时，它的页面锚点才是权威答案**。
        //   旧实现一旦把页面绑错（绑的那一帧恰好不在播放页上 ——
        //   比如宿主在列表页偷偷拉 seekbar 素材、或走了迷你条那只样式同类的
        //   ProgressBar），这个错误绑定会<b>一直沿用</b>，
        //   于是列表页从头就被当成播放页 ⇒ 页头顶栏整条糊（r47 用户第 2 条）。
        java.lang.ref.WeakReference<View> aref = BgPeel.sSeekPageAnchor;
        View anchor = aref == null ? null : aref.get();
        // ⚠️ 只有落在**真正的页面容器**（Screen）上的锚点才拿来纠错。
        //   落在窗口根上的锚点不能当判据：这个宿主的播放页摊在窗口根上，
        //   但它同时还有一批 {@code inScr=1} 的层，拿「窗口根」当唯一答案会
        //   在同一页里出现 true/false 抖动 —— 那正是「背景被裁成几块」的来源。
        if (anchor != null && anchor.getRootView() != anchor) {
            if (pg != null && anchor == pg) return true;
            judgePlayPage("off-page " + (pg == null ? "null" : BgPeel.name(pg)));
            return false;
        }

        // ★★★ v0.1.50（r50 用户第 2 / 3 条）：**「宽限期」不能被第一个问的人吃掉**。
        //
        //   r49 真机日志：
        //   <pre>
        //   15:39:39.542  res  seekbar_track_material        ← notePlayPage 触发
        //   15:39:40.148  blur bg r=10 1272x2772 pg=0 ->clear ← 0.6 秒后仍判 miss
        //   15:39:54.023  blur bg r=10 1272x2772 pg=1 ->play  ← 14 秒后才翻
        //   </pre>
        //   旧逻辑是「谁先问谁绑定」：宽限期内<b>任意</b>一层先问就会把
        //   {@code BgPeel.sPlayBindPending} 消耗掉，并且绑到<b>那一刻它所在的页面</b>——
        //   而那一层很可能还在上一页（转场动画里两页同时存在）⇒ 绑错 ⇒
        //   真正的播放页反而被判 off-page。
        //   现在改成：宽限期内<b>所有层</b>都算播放页（整段窗口一起算），
        //   等真进度条出现，锚点会再把范围收窄到它所在的那一页。
        boolean grace = now - BgPeel.sPlayPageAt <= BgPeel.PLAY_PAGE_TTL_MS;
        if (BgPeel.sPlayBindPending && grace) {
            BgPeel.sPlayBindPending = false;
            BgPeel.sPlayScreen = new java.lang.ref.WeakReference<View>(pg);
            judgePlayPage("bind " + (pg == null ? "null" : BgPeel.name(pg)));
            // ★★★ v0.1.51（r51 用户第 2 / 4 条）：**这里不再整树重绘**。
            //   r47 加的 `BgPeel.invalidateTree(整棵 DecorView)` 每绑定一次就把几百个 View
            //   的 RenderNode 全部作废重录 —— 播放页弹出时的卡顿、以及底部导航切换的
            //   卡顿，主要就是它。实际上「第一帧铺错」这件事已经被下面的宽限期
            //   + 根层兜底解决，不需要靠蛮力重绘。
            BgPeel.invalidateRootSoft(v);
            return true;
        }
        // ★★★ v0.1.50：宽限期内**每一层都算播放页**（不管谁先问、问几次）。
        //   这是「播放页第一帧就整屏一致」的唯一可靠办法 ——
        //   素材加载那一刻就是播放页真正挂载的时刻（宿主只在播放页拉这些图）。
        if (grace) {
            judgePlayPage("grace " + (pg == null ? "null" : BgPeel.name(pg)));
            return true;
        }
        // 换了页面 ⇒ 旧绑定作废，否则「从播放页返回列表页」会一直沿用旧的 true。
        if (bound != null && pg != bound) BgPeel.sPlayScreen = null;
        judgePlayPage("miss " + (pg == null ? "null" : BgPeel.name(pg)));
        return false;
    }


    /**
     * ★★★ v0.1.79（r82）：这一层是不是**播放页根**（新架构下唯一要挂底板的那一层）。
     *
     * <p>四条判据按可靠度排序，任一成立即可：
     * <ol>
     *   <li>{@code v == BgPeel.sPlayRootBgView} —— 已经挂过底板的那个 View（最硬）；</li>
     *   <li>{@code BgDiag.containsPlaySeek(v)} —— 子树里装着活着的进度条；</li>
     *   <li>{@code hasFullBackdropChild(v)} —— 子树里有满屏氛围底图（纯结构，
     *       不依赖进度条，「退后台再回来」也成立）；</li>
     *   <li>{@code wasPaintedAsPlay(v)} —— 历史上被 {@code paint play…} 画过。</li>
     * </ol>
     * 信息卡 / 普通 Dialog 这四条都不满足 ⇒ 仍然走 drop，不会盖住宿主页面。
     */
    static boolean isPlayRoot(View v) {
        if (v == null) return false;
        // ★★★ v0.1.83（r86 第 4 条「打开作品页 + 底栏切换又卡了」）：
        //   **热路径只做 O(深度) 的引用比较，绝不再遍历子树。**
        //   上一版这里挂 `hasSeekShape` / `hasFullBackdropChild`，两个都是 O(子树)，
        //   而「每一层每一帧」都要问一次 ⇒ 打开作品页、切底栏时直接卡住。
        //   现在「播放页根是哪个」由心跳（500ms）算一次并记进 {@link #BgPeel.sPlayRootRef}，
        //   绘制路径只比较引用 / 走父链（≤12 次 getParent）。
        View r = BgPeel.sPlayRootRef == null ? null : BgPeel.sPlayRootRef.get();
        if (r != null) {
            return v == r || underPlayRoot(v) || wasPaintedAsPlay(v);
        }
        // 兜底：心跳还没扫到根（刚启动 / 首次打开的头一两百毫秒）时才允许重判。
        if (v == livePlayRoot()) return true;          // v0.1.80：只认「还挂在树里」的那一个
        if (BgDiag.containsPlaySeek(v)) return true;
        if (hasSeekShape(v)) return true;
        if (hasFullBackdropChild(v)) return true;
        return wasPaintedAsPlay(v);
    }


    /** 这个窗口是不是播放页窗口（v0.1.30；v0.1.80 起带上页面归属，见 {@link #BgPeel.sPlayWinPage}）。 */
    static boolean isPlayWindow(View v) {
        try {
            View root = v.getRootView();
            if (root == null) return false;
            Long t = BgPeel.PLAY_WIN.get(root);
            if (t == null || System.currentTimeMillis() - t >= BgPeel.PLAY_WIN_TTL_MS) return false;
            // ★ v0.1.80：同一窗口里有多个页面 —— 只认「标记时所在的那一页」。
            java.lang.ref.WeakReference<View> ref = BgPeel.sPlayWinPage;
            View marked = ref == null ? null : ref.get();
            if (marked == null) return true;             // 没记到页 ⇒ 保持旧行为
            View pg = BgPeel.screenOf(v);
            if (pg == null) return true;                 // 根级层（该宿主的播放页就摊在根上）
            return pg == marked;
        } catch (Throwable t) {
            return false;
        }
    }


    /** 播放页判定的留痕（每进程 6 条，够看清「为什么这块没糊」）。 */
    static void judgePlayPage(String s) {
        if (BgPeel.sPlayJudgeLogs >= BgPeel.PLAY_JUDGE_LOG_BUDGET) return;
        BgPeel.sPlayJudgeLogs++;
        BgPeel.log("play page " + s);
    }


    static boolean lightNav() {
        return System.currentTimeMillis() < BgPeel.sLightUntil;
    }


    /**
     * ★★★ v0.1.80（r104）：<b>播放页根一旦被摘下窗口就作废。</b>
     *
     * <p>`BgPeel.sPlayRootBgView` 是对象级记忆（只有 restoreAll 才清），而播放页会进出栈；
     * 旧的根对象虽然已经不在了，但 {@code isPlayRoot/underPlayRoot} 仍会拿它去比 ——
     * 「这一页是播放页」的结论就被一个死对象续着，主页/列表页偶尔吃到播放档底图。
     * 这里做一次 O(1) 的存活检查，死引用就地清掉。
     */
    static View livePlayRoot() {
        View r = BgPeel.sPlayRootBgView;
        if (r == null) return null;
        try {
            if (r.getParent() == null) {
                BgPeel.sPlayRootBgView = null;
                BgPeel.sPlayRootBgBmp = null;
                BgPeel.sPlayRootRef = null;
                return null;
            }
        } catch (Throwable ignored) {
        }
        return r;
    }


    /** 记下「这个窗口是播放页」——{@code setBlurRadius(10)} 只会出现在播放页。 */
    static void markPlayWindow(View v) {
        try {
            // ★★★ v0.1.81（r105 用户第 1 / 4 条）：**只有真的画在屏幕上才算数。**
            //
            //   用户这一轮的现象：停在作品文件夹里退回后台、再回前台，
            //   自定义背景直接变成「播放页那档模糊」；其中一次甚至停在主页回前台也中招。
            //
            //   机制：退回后台时，播放页那几屏只是被 rns 挪走/停画，<b>并没有卸载</b>；
            //   回前台重建的那几帧里它们仍在树里、仍会走 draw ⇒ 仍然触发
            //   {@code setBlurRadius(10)} ⇒ {@link #markPlayWindow} 把「播放页窗口」重新点亮，
            //   而当前真正可见的那一页里，贴在<b>根级</b>（不属于任何 Screen）的大层
            //   会因为 {@code BgPeel.screenOf()==null} 被判成「播放页的根级层」（见 isPlayWindow）
            //   ⇒ 整页被铺成播放档（壁纸 + 播放模糊）。
            //
            //   挡住它的唯一低成本判据就是「这一层此刻是否真的在屏幕上」：
            //   离屏页（被平移出去 / 尺寸为 0 / 完全被裁掉）一律不点亮播放页标记。
            // ★ r107：r105 的 onScreenNow 闸已撤回（同属上一轮，且会让播放页标记失忆）
            View root = v.getRootView();
            boolean first = false;
            if (root != null) {
                first = BgPeel.PLAY_WIN.put(root, System.currentTimeMillis()) == null;
                // ★ v0.1.80：标记的「页」只在能确定是某一页 Screen 时才记；
                //   根级层（没有 Screen）不覆盖已有记录 —— 否则播放页里那些
                //   inScr=0 的层会把标记冲成「无页」，隔离立刻失效。
                try {
                    View pg = BgPeel.screenOf(v);
                    if (pg != null) BgPeel.sPlayWinPage = new java.lang.ref.WeakReference<>(pg);
                } catch (Throwable ignored) {
                }
                // ★★★ v0.1.47（r47 用户第 3 条）：**第一次认定播放页时整棵树重画一遍**。
                //
                //   播放页是淡入进场的：第一帧进度条还没活、Screen 绑定还没形成，
                //   这时 `playContext()` 对所有层都是 false ⇒ 顶栏那条不透明叶子被
                //   铺上「清晰壁纸」（`paint top fill` 的 else 分支）。
                //   硬件加速下每个 View 的绘制指令记在自己的 RenderNode 里，
                //   之后再也没人 invalidate 它 ⇒ 这条清晰/错位的带子**一直挂在页头上**
                //   （用户看到的就是「播放页上方一条不带模糊的自定义背景，像截到了
                //   壁纸的另一端」）。等底图把它标成播放页窗口时，把整棵树 invalidate
                //   一遍，所有层都用此刻的正确判据重新录一遍。
                //   ★★★ v0.1.51：改成**轻量失效**（见 invalidateRootSoft）。
                //   整树 invalidate 会把几百个 RenderNode 全部作废重录，
                //   这是 r51 用户第 2 条「播放页弹出特别卡顿」的主因。
                if (first) BgPeel.invalidateRootSoft(root);
            }
            // ★★★ v0.1.39：把「播放页」的保持窗口一起续上。
            //   有整屏 blurRadius≥8 的底图 = 铁证在播放页；这样即使进度条那一帧
            //   被宿主 detach（切后台最常见），同一屏里其余的层也不会各自判成清晰壁纸
            //   ——「背景正常但封面区域是没加模糊的自定义背景」就是这么来的。
            //   ★ v0.1.47：保持窗同样带页面锚点（不再全局污染别的页面）。
            BgPeel.sPlayHoldUntil = System.currentTimeMillis() + BgPeel.PLAY_HOLD_MS;
            BgPeel.sPlayHoldPage = new java.lang.ref.WeakReference<>(pageAnchorOf(v));
        } catch (Throwable ignored) {
        }
    }


    /**
     * 记下「整页底色」用的是什么图、到哪儿结束（v0.1.25 用户第 4 条）。
     */
    static void notePageBackdrop(View v, int h, Bitmap bmp) {
        try {
            int[] loc = BgPeel.LOC.get();
            v.getLocationInWindow(loc);
            BgPeel.sPageBottom = loc[1] + h;
            BgPeel.sPageBmp = bmp;
        } catch (Throwable ignored) {
        }
    }


    /**
     * ★★★ v0.1.34：宿主<b>播放页</b>的 seekbar 加载时打点（由 {@code FeatureF2} 的资源探针调用）。
     *
     * <p>这是本轮唯一能把「播放页」和「播放列表 / 分类详情」分开的信号：
     * 两者背景图是同一个组件、同一个 {@code blurRadius=10}、同一个整屏尺寸，
     * 只有播放页会去拿 {@code seekbar_track_material} / {@code seekbar_thumb_material_anim}。
     */
    static void notePlayPage() {
        BgPeel.sPlayPageAt = System.currentTimeMillis();
        BgPeel.sPlayBindPending = true;
    }


    /**
     * ★★★ v0.1.37：每次 {@code View#draw} 都会过这里，但要先做一次 {@code instanceof} 短路 ——
     * 只有进度条才会往下走，其余 View 的成本就是一个类型判断。
     *
     * <p>把「活的进度条」记下来，作为「此刻在播放页」的<b>唯一</b>活体证据。
     * 细线进度（迷你播放器 / 下载条）用高度门槛排掉。
     */
    static void notePlaySeek(View v) {
        try {
            if (!(v instanceof android.widget.ProgressBar)) return;
            if (BgPeel.sSH <= 0) return;
            int h = v.getHeight();
            int w = v.getWidth();
            if (w <= 0 || h <= 0) return;
            // ★★★ v0.1.38（r43）：门槛从「≥2% 屏高」放到「≥6px + 够宽」。
            //   r43 整轮 `play seek` **一条都没有** —— 宿主这个进度条带滑块但整行只有
            //   二十来像素，被 2%（≈55px）挡掉了。于是「播放页还活着没有」这条事实
            //   从来就没生效过，只能靠 blurRadius 兜着。
            // ★★★ v0.1.40：**门槛必须夹在「细线和真进度条」之间**。
            //   v0.1.38 放到 6px 之后，宿主的「下载进度条 / 加载进度条」也全被记成
            //   「播放页活体证据」⇒ 列表页、详情页里有下载条的时候整个页面被当成
            //   播放页 ⇒ 背景铺播放档（糊）、封面又被按播放页规则处理。
            //   用户这一轮第 9 条「点开自己建的播放列表又糊了」、第 7 条封面不显示，
            //   都是这条误判把 `playContext()` 弄成常真的后果。
            //   真 seekbar 在 1272 宽屏上是 1153x54；细线进度（下载/加载）一般 ≤14px。
            // ★★★ v0.1.43：下限 20→36，把下载/加载条彻底排掉（它们会污染 playContext）。
            if (h < 36 || h > 200) return;
            if (w * 100 < (long) BgPeel.sSW * 55) return;       // 真进度条≈90% 屏宽
            if (v.getWindowToken() == null) return;
            if (!v.isShown()) return;
            // ★★★ v0.1.46（r46 用户第 3 条）：**迷你播放条的进度条必须排掉**。
            //
            //   r46 录屏+日志实证：「PurePlay 页上半区域 / 自建播放列表 / 自建摘选列表 /
            //   播放列表页上方导航栏」概率性一起出现高斯模糊 —— 这四处的共同点不是布局，
            //   是<b>底部都挂着迷你播放条</b>。迷你条里也有一支 ReactSlider（宽度同样
            //   ≥55% 屏宽、高度同样落在 36~200px 区间），于是只要迷你条在重画，
            //   {@link #isPlayLive} 就一直为真 ⇒ {@link #playContext} 对**任何页面**的
            //   整屏底图都返回 true ⇒ 全部铺成「播放档（壁纸+40% 模糊）」。
            //   又因为迷你条不是每帧都重画，700ms 保持窗一到就过期、下一帧又续上
            //   ⇒ 肉眼看到的就是「概率性、一会儿糊一会儿不糊」。
            //
            //   区分「播放页 seekbar」和「迷你条 seekbar」用两条**几何事实**：
            //   ① 播放页的 seekbar 在屏幕中部（上方是封面、下方还有一排传输键），
            //      迷你条的 seekbar 贴着屏幕底缘；
            //   ② 播放页 seekbar 的某层通宽祖先占满整屏，迷你条的通宽祖先只是一条
            //      矮条（≤35% 屏高）。
            int[] xy = new int[2];
            v.getLocationOnScreen(xy);
            int cy = xy[1] + h / 2;
            if (cy * 100 >= (long) BgPeel.sSH * 88) {           // ① 垂直中心落在屏幕底部 12%
                if (BgPeel.sSeekLogs2 < 2) {
                    BgPeel.sSeekLogs2++;
                    BgPeel.log("play seek skip（迷你条） -> " + BgPeel.name(v) + " " + w + "x" + h
                            + " y=" + xy[1] + ".." + (xy[1] + h) + "/" + BgPeel.sSH);
                }
                return;
            }
            if (barAncestor(v)) {                        // ② 通宽祖先只是一条矮条
                if (BgPeel.sSeekLogs2 < 2) {
                    BgPeel.sSeekLogs2++;
                    BgPeel.log("play seek skip（矮条容器） -> " + BgPeel.name(v) + " " + w + "x" + h);
                }
                return;
            }
            BgPeel.sPlaySeek = new java.lang.ref.WeakReference<>(v);
            // ★★★ v0.1.47：把这支进度条所在的页面锚点一起记下来。
            //   这是「到底哪一层才是播放页」的<b>唯一权威答案</b> ——
            //   往后 {@link #isPlayPage} 认错了页（比如绑定时机刚好赶上去赞 finer
            //   预览/迷你条拉 seekbar 资源那一瞬绑到了列表页），有它能当场纠正。
            BgPeel.sSeekPageAnchor = new java.lang.ref.WeakReference<>(pageAnchorOf(v));
            if (BgPeel.sPlaySeekLogs < BgPeel.PLAY_SEEK_LOG_BUDGET) {
                BgPeel.sPlaySeekLogs++;
                BgPeel.log("play seek -> " + BgPeel.name(v) + " " + w + "x" + h + " top=" + BgPeel.topOf(v));
            }
        } catch (Throwable ignored) {
        }
    }


    /** 这一层所属的「页面锚点」：在 Screen 容器里就是那个容器，否则是窗口根。 */
    static View pageAnchorOf(View v) {
        if (v == null) return null;
        View s = BgPeel.screenOf(v);
        if (s != null) return s;
        try {
            return v.getRootView();
        } catch (Throwable t) {
            return v;
        }
    }


    /**
     * 某个 drawable 背景要不要「换成壁纸」。
     *
     * <p>换的是<b>背景</b>，不是整层 View ⇒ 子内容（文字/图标/按钮）照常画。
     * 这是唯一「怎么换都不会多遮住东西」的落点，所以顶栏/底栏的毛玻璃都走这里。
     *
     * @return true = 壁纸已经画在这块底色的位置上，调用方不要再画宿主原来的颜色
     */
    /**
     * ★★★ v0.1.74（r88）：这一层该用哪一档壁纸 —— <b>播放页里一律播放档</b>。
     *
     * <p>背景：同一个屏幕上「播放档（壁纸+f2PlayBlur）」与「清晰壁纸」混用，
     * 只要有一层走错档，就会在画面里留下一块<b>清晰度不同</b>的矩形 ——
     * 用户 r88 第 1 条说的「色块只闪现在作品封面上方」就是清晰档画进了播放页。
     *
     * <p>判据按可靠度：`playContext`（活体进度条）→ `isPlayWindow`（窗口级）→
     * `inPlayTree`（纯结构：祖先链里有满屏氛围底图，头几帧/重建后依然成立）。
     * 三条都不成立才回落到清晰壁纸，所以列表页 / 作品页不受影响。
     */
    static Bitmap pageBmp(View v) {
        try {
            if (v != null && (playContext(v) || isPlayWindow(v) || inPlayTree(v))) {
                Bitmap pb = playBmp();
                if (pb != null) return pb;
            }
        } catch (Throwable ignored) {
        }
        return BgPeel.sWpBmp != null ? BgPeel.sWpBmp : BgPeel.sWpBarBmp;
    }


    /**
     * ★★ v0.1.29 用户第 1 条：满屏图片层<b>读不到位图</b>时，直接补一层播放页壁纸。
     *
     * <p>「启动宿主后第一次播放，播放页背景一定透明」这条修了三轮：
     * v0.1.25 保 armed、v0.1.27 改位图标记、v0.1.28 alpha 分三段 —— 都只覆盖了一部分。
     * r34 日志里还剩一个口子：那一层是 {@code ExpoImageView 1272x2772}，但
     * <b>拿不出 BitmapDrawable</b>（图还在解码 / 被 Glide 换成了别的 drawable），
     * 于是被判成「内容图」放行 ⇒ 宿主这一层什么都没画 ⇒ 全屏一个洞。
     *
     * <p>判据刻意收得很紧，保证不会碰到真照片：
     * <ul>
     *   <li>宽度满屏、高度 ≥92% 屏高（只有页面底图是这个几何）；</li>
     *   <li>是 {@code ImageView}，而且<b>当前没有可读位图</b>（有图就说明宿主画得出来）；</li>
     *   <li>只认 expo-image / RN 的图片类，SvgView 之类不碰。</li>
     * </ul>
     */
    static boolean paintPlayFallback(View v, Canvas c, int w, int h) {
        if (!BgPeel.sArmed) return false;
        if (w < BgPeel.sFullW) return false;
        if (h * 100 < (long) BgPeel.sSH * 92) return false;
        if (!(v instanceof android.widget.ImageView)) return false;
        try {
            String n = v.getClass().getName();
            if (!n.contains("Image") || n.contains("Svg")) return false;
        } catch (Throwable t) {
            return false;
        }
        if (BgPeel.imageBitmapOf(v) != null) return false;     // 有图 ⇒ 那是内容，交给宿主
        Bitmap bmp = BgPeel.sWpPlayBmp != null ? BgPeel.sWpPlayBmp : (BgPeel.sWpBmp != null ? BgPeel.sWpBmp : BgPeel.sWpBarBmp);
        if (bmp == null) return false;
        Rect r = BgPeel.dstRect(w, h);
        if (!PanelGlass.paintAligned(c, bmp, v, r, false)) return false;
        notePageBackdrop(v, h, bmp);
        BgPeel.sAtmos++;
        BgPeel.sPainted = true;
        BgDiag.hit("paint play fallback -> ", v, r);
        return true;
    }


    /** 播放页该用的底图：壁纸 + 用户设定的播放模糊；缺失时退回条带模糊 / 清晰壁纸。 */
    static Bitmap playBmp() {
        if (BgPeel.sWpPlayBmp != null && !BgPeel.sWpPlayBmp.isRecycled()) return BgPeel.sWpPlayBmp;
        if (BgPeel.sWpBarBmp != null && !BgPeel.sWpBarBmp.isRecycled()) return BgPeel.sWpBarBmp;
        return BgPeel.sWpBmp;
    }


    /**
     * 「这一层该按播放页处理吗」。
     *
     * <p>两条并列，任一成立即是：活体进度条（{@link #isPlayLive}，最硬）、
     * 或者 seekbar 刚加载过的 1.5 秒窗口（{@link #isPlayPage}，兜第一次进播放页那一帧 ——
     * 那时进度条可能还没 layout 完）。
     */
    /**
     * ★★★ v0.1.76（r79 第 1 条，`50th 20ms` 的真凶）：**一帧只算一次 `playContext`。**
     *
     * <p>`playContext` 内部是 {@code isPlayLive → samePage → pageAnchorOf}，
     * 而 `pageAnchorOf` 要<b>沿父链一路找到 Screen 容器</b>。绘制路径是「每一层每一帧」
     * 都问一次 ⇒ 迷你播放条存在（`BgPeel.sPlaySeek` 非空）时，一帧就是几千次父链遍历，
     * 直接把 CPU 帧时间顶到 20ms。
     *
     * <p>而「这一屏是不是播放页」这个问题<b>一帧内只有一个答案</b>。
     * 所以这里加 16ms 的结果缓存：同一帧内复用它，稳态下每帧只真正算一次。
     */
    static boolean playContext(View v) {
        long now = System.currentTimeMillis();
        // ★★★ v0.1.84（r108 路径 1）：**缓存从「一帧一个值」改成「一页一个值」**。
        //
        //   旧实现（r79 为了把 50th 从 20ms 压下来）是一帧一个全局答案。问题在
        //   「同一帧里可能同时存在两个页面」的时候：回前台重建的那一帧，被 rns 挪走的
        //   播放页仍在树里、仍在 draw，它先把缓存算成 true，紧接着**刚挂上来的
        //   作品页大层**直接吃到这个 true ⇒ 整页铺成播放档（壁纸 + 播放模糊），
        //   而这一层录进 RenderNode 之后再没人重画 ⇒ 一直糊着（用户 r106 第 1 条）。
        //
        //   现在：帧窗口（16ms）内按<b>页面锚点</b>分桶，一页一个答案。
        //   为了不回到 r79 那个「每层每帧都爬父链」的老路，加了按 View 记忆的
        //   帧内 memo（{@link #BgPeel.PC_ANCHOR}），每个 View 一帧最多爬一次父链。
        View anchor = anchorCached(v);
        if (now - BgPeel.sPcAt >= 16L) {
            BgPeel.sPcAt = now;
            BgPeel.PC_ANCHOR.clear();
            BgPeel.PC_BOOL.clear();
            if (BgPeel.PC_BOOL.size() > 64) BgPeel.PC_BOOL.clear();
        }
        Boolean cached = BgPeel.PC_BOOL.get(anchor);
        if (cached != null) return cached;
        boolean val;
        try {
            val = playContextRaw(v);
        } catch (Throwable t) {
            val = false;
        }
        BgPeel.PC_BOOL.put(anchor, val);
        return val;
    }


    static boolean playContextRaw(View v) {
        if (isPlayLive(v)) return true;
        // ★★★ v0.1.43（r14 用户第 5 / 6 / 7 条）：**删掉「height > 屏高 ⇒ 播放页」**。
        //   r14 日志：`blur bg r=0 1440x3328 pg=1 ->play` —— 列表/详情页的底图也比
        //   屏高（含安全区）更高一截，被这条误判成播放页 ⇒ 整页被铺成播放档模糊，
        //   表现就是「高斯模糊遮罩概率出现」。只留活体 seekbar + 短 TTL 绑定。
        return isPlayPage(v);
    }


    /**
     * ★★★ v0.1.47：两个 View 是否属于<b>同一个页面</b>。
     *
     * <p>锚点规则见 {@link #pageAnchorOf}：
     * 迷你播放条是全局悬浮的、不在任何 Screen 里 ⇒ 它的锚点是窗口根，
     * 而页面内容的锚点是各自的 Screen 容器 ⇒ 永远不相等 ——
     * 「挂着迷你条进列表页」不可能再把列表页判成播放页。
     * 宿主不用 Screens 时两边都落到窗口根，播放页内部照常成立。
     */
    static boolean samePage(View a, View b) {
        if (a == null || b == null) return false;
        try {
            return pageAnchorOf(a) == pageAnchorOf(b);
        } catch (Throwable t) {
            return false;
        }
    }


    static boolean scanFullBackdrop(View v, int depth) {
        if (v == null || depth > 5) return false;
        if (v instanceof android.widget.ImageView) {
            int w = v.getWidth();
            int h = v.getHeight();
            if (w * 100 >= (long) BgPeel.sSW * 95 && h * 100 >= (long) BgPeel.sSH * 90) return true;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n && i < 12; i++) {
                if (scanFullBackdrop(g.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }


    static boolean scanSeekShape(View v, int depth) {
        if (v == null || depth > 8) return false;
        try {
            if (v.isShown() && !(v instanceof android.widget.TextView)) {
                int w = v.getWidth();
                int h = v.getHeight();
                if (w > 0 && h > 0
                        && w * 100 >= (long) BgPeel.sSW * 40
                        && h * 100 >= (long) BgPeel.sSH * 2
                        && h * 100 <= (long) BgPeel.sSH * 9) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n && i < 12; i++) {
                if (scanSeekShape(g.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }


    /**
     * ★★★ v0.1.96：<b>播放页的满屏层一律当底处理</b>（只做一件事：保证壁纸露出来）。
     *
     * <p>两条出路：
     * <ol>
     *   <li>播放页根上已经挂了我们的底板（{@link #BgPeel.sPlayRootBgBmp}）⇒ 这层
     *       <b>什么都不用画</b>（{@code return true} 跳过宿主绘制），底板自然透出来；</li>
     *   <li>底板还没挂上（开页头几帧、回前台重建）⇒ 就地铺一张播放档壁纸，
     *       对齐用 {@link #paintAligned}（{@code getLocationOnScreen} 基准）。</li>
     * </ol>
     *
     * <p>为什么不再看 {@code blurRadius} / 位图：见调用点的注释 —— 宿主那张
     * 「放大糊封面」底图在换源 / 重建的那几帧里两条判据都可能读不到，
     * 而这正是它漏到屏幕上、把壁纸和作品封面一起盖住的时刻。
     *
     * @return true = 这一层已经处理完（调用方必须跳过宿主绘制）
     */
    static boolean showPlayVeil(View v, Canvas c, int w, int h) {
        try {
            Rect r = BgPeel.dstRect(w, h);
            if (BgPeel.sPlayRootBgBmp != null && underPlayRoot(v)) {
                BgPeel.sPainted = true;
                BgDiag.hit("drop play full -> ", v, r);
                return true;
            }
            Bitmap pb = playBmp();
            if (pb == null || pb.isRecycled()) pb = BgPeel.sWpBarBmp;
            if (pb == null || pb.isRecycled()) return false;
            if (!PanelGlass.paintAligned(c, pb, v, r, false)) return false;
            BgPeel.sBar++;
            BgPeel.sPainted = true;
            BgDiag.hit("paint play full -> ", v, r);
            if (BgPeel.sBlurLogs < BgPeel.BLUR_LOG_BUDGET) {
                BgPeel.sBlurLogs++;
                BgPeel.log("play full r=" + BgPeel.blurRadiusOf(v) + " " + w + "x" + h
                        + " top=" + BgPeel.topOf(v) + " ->veil");
            }
            notePageBackdrop(v, h, pb);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }


    static boolean underPlayRoot(View v) {
        View root = livePlayRoot();
        if (root == null || v == null || v == root) return false;
        try {
            View cur = v;
            for (int i = 0; i < 28 && cur != null; i++) {
                if (cur == root) return true;
                android.view.ViewParent p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }


    /**
     * ★★★ v0.1.73（r76 第 1 条，最终判据）：这一层**曾经被认成播放页底**。
     *
     * <p>为什么还需要这一条：回前台 / 切后台会让 RN 重建视图树，
     * `BgPeel.sPlaySeek` 指向的旧进度条 detach ⇒ `isShown()` false ⇒ 心跳不再刷新
     * `BgPeel.sPlayOnScreenAt`；`underPlayRoot` 也失效（根换了对象）。
     * 三条判据同时掉，档位就退回清晰壁纸 —— 这正是用户说的「回前台后背景不模糊了」。
     *
     * <p>而 {@link #BgPeel.PAINTER} 里记的「这一层上一次是被谁接管的」是<b>对象级记忆</b>，
     * 重建时同一个 View 不会被复用成别的页面（RN 的播放页根是独立 View，离开即销毁），
     * 所以「曾经是 paint play…」可以安全地当作「现在还是播放页」。
     */
    static boolean wasPaintedAsPlay(View v) {
        if (v == null) return false;
        try {
            String p = BgPeel.PAINTER.get(v);
            if (p == null || !p.startsWith("paint play")) return false;
            // ★★★ v0.1.80（r104 用户第 4 条）：**「曾经是播放页」必须和「哪一页」一起记。**
            //   BgPeel.PAINTER 是对象级记忆、没有过期时间；而「窗口是播放页」这个判据
            //   之前在离开播放页后还会持续 2.5 秒 ⇒ 主界面的层会在这段时间里被写进
            //   `paint play…`，然后**永远**保持播放档 ⇒ 主页时不时残留一层模糊。
            //   现在额外记下「画它的时候它在哪一页」，换页即失效。
            java.lang.ref.WeakReference<View> ref = BgPeel.PLAY_PAINT_PAGE.get(v);
            View painted = ref == null ? null : ref.get();
            if (painted == null) return true;      // 老记录（没有页信息）⇒ 保持旧行为
            return samePage(painted, v);
        } catch (Throwable ignored) {
            return false;
        }
    }

}
