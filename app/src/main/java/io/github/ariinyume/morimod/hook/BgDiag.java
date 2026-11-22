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
 * BgPeel 职责拆分（v0.1.146 B7）· BgDiag。
 * 共享 static 状态仍在 BgPeel；本类只承载本职责的方法。
 */
final class BgDiag {

    private BgDiag() {
    }

    static void auditPlayTree(final View any) {
        try {
            long now = System.currentTimeMillis();
            if (now - BgPeel.sAuditAt < 3000L) return;
            BgPeel.sAuditAt = now;
            final View root = any.getRootView();
            if (root == null) return;
            BgPeel.MAIN.post(() -> {
                try {
                    BgPeel.sAuditLines = 0;
                    BgPeel.sAuditNodes = 0;
                    final boolean dbg = RemoteConfig.debugLog();
                    if (dbg) BgPeel.log("=== play tree dump v0.1.67 ===");
                    walkPlay(root, 0);
                    if (dbg) BgPeel.log("=== play tree end ===");
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }


    /**
     * ★★★ v0.1.72（r74 第 3 条）：这层子树里是不是装着「那条活着的播放页进度条」。
     *
     * <p>这是本轮唯一新加的「结构性」判据，用来把两件外观完全相同的事分开：
     * <ul>
     *   <li><b>播放页内容容器被压成 alpha=0</b> —— 必须救，否则整页透出后面那一页；</li>
     *   <li><b>宿主有意藏起来的模态</b>（R18 提示 / 触摸拦截层）—— 绝不能碰。</li>
     * </ul>
     * 两者的 alpha 都是 0、都是满屏、都装着图，靠属性<b>不可分</b>（r69/r72 两次踩过）。
     * 但「播放页的进度条」是唯一的，R18 模态里不可能有它 ⇒ 走结构必然正确。
     */
    static boolean containsPlaySeek(View v) {
        if (v == null) return false;
        java.lang.ref.WeakReference<View> ref = BgPeel.sPlaySeek;
        if (ref == null) return false;
        View sk = ref.get();
        if (sk == null) return false;
        try {
            View cur = sk;
            for (int i = 0; i < 40 && cur != null; i++) {
                if (cur == v) return true;
                android.view.ViewParent p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }


    static void dumpDecorTree(final View decor0) {
        BgPeel.MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    View decor = decor0;
                    if (decor == null) return;
                    BgPeel.sDumpNodes = 0;
                    BgPeel.sDumpShown = 0;
                    BgPeel.sDumpErr = null;
                    StringBuilder sb = new StringBuilder("tree dump decor="
                            + decor.getWidth() + "x" + decor.getHeight()
                            + " shown=" + decor.isShown() + " winVis=" + decor.getWindowVisibility()
                            + " vis=" + decor.getVisibility());
                    walkDump(decor, 0, sb);
                    sb.append("\n#nodes=").append(BgPeel.sDumpNodes).append(" shown=").append(BgPeel.sDumpShown)
                            .append(" err=").append(BgPeel.sDumpErr);
                    // logcat 单条上限约 4KB —— 分段打，否则树一长就被截断（r105 踩过）
                    String all = sb.toString();
                    int step = 2600;
                    int part = 0;
                    for (int i = 0; i < all.length(); i += step, part++) {
                        android.util.Log.i("MoriSnap", "§" + part + " "
                                + all.substring(i, Math.min(all.length(), i + step)));
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }


    static void dumpHeader(View v, int depth, StringBuilder sb, int lines) {
        if (v == null || depth > 28 || lines > 24) return;
        try {
            int[] loc = BgPeel.LOC.get();
            v.getLocationOnScreen(loc);
            if (loc[1] > (int) (BgPeel.sSH * 0.22f)) return;
            if (v.isShown() && v.getWidth() > 0) {
                String id = null;
                try {
                    android.view.accessibility.AccessibilityNodeInfo n = v.createAccessibilityNodeInfo();
                    if (n != null) id = n.getViewIdResourceName();
                } catch (Throwable ignored) {
                }
                if (id != null || v.isClickable()) {
                    sb.append('\n').append(' ').append(depth).append(' ').append(BgPeel.name(v))
                            .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                            .append(" @").append(loc[0]).append(',').append(loc[1])
                            .append(" click=").append(v.isClickable() ? 1 : 0)
                            .append(" a11y=").append(id == null ? "-" : id);
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                int n = g.getChildCount();
                for (int i = 0; i < n; i++) {
                    dumpHeader(g.getChildAt(i), depth + 1, sb, lines + 1);
                }
            }
        } catch (Throwable ignored) {
        }
    }


    /** 找不到返回按钮时把页头那几条打一次（每条：类名 / 尺寸 / 坐标 / 可访问性 id）。 */
    static void dumpHeaderOnce(Activity a) {
        if (BgPeel.sBackDumps >= 3) return;
        BgPeel.sBackDumps++;
        try {
            View decor = a.getWindow() == null ? null : a.getWindow().getDecorView();
            if (decor == null) return;
            StringBuilder sb = new StringBuilder("header dump tagId=" + BgPeel.sNativeIdTag);
            dumpHeader(decor, 0, sb, 0);
            BgPeel.log(sb.toString());
        } catch (Throwable ignored) {
        }
    }


    /**
     * 诊断：把当前窗口的树打一行（类名 / 尺寸 / 屏幕坐标 / 背景 / 位图种类 / 模糊半径）。
     *
     * <p>为什么需要它：这个宿主有常驻动画（迷你条 + 回顾 CD 旋转），
     * {@code uiautomator dump} 永远报 {@code could not get idle state}，
     * 只能从进程内部自己抓。
     */
    static void dumpWindowTree(Activity a) {
        dumpDecorTree(a == null || a.getWindow() == null ? null : a.getWindow().getDecorView());
    }


    /**
     * 命中留痕。
     *
     * <p>★★★ v0.1.42（r13）：<b>不再按时间限流，改按「视图签名」去重</b>。
     *
     * <p>60 秒限流是这十几轮都查不出问题的<b>直接原因</b>：面板一打开，玻璃连着画
     * 上百帧，第一条把前缀的时间戳占满 ⇒ 后面所有面板、所有分支<b>一条日志都没有</b>
     * （r12 的日志里 {@code glassA} 只有 12 条、{@code drop sheet leaf} 只有 1 条，
     * 全是这个造成的误判）。现在同一个「动作 + 类名 + 尺寸 + 位置」只打一次，
     * 总上限 120 条 —— 一次录屏就能看出「哪些层被谁接管了、哪些没被接管」。
     */
    static void hit(String prefix, View v, Rect r) {
        // ★★★ v0.1.70（r73）：「这块是被谁接管的」不再靠日志预算 —— 记在 BgPeel.PAINTER 里，
        //   由 {@link #probePlayTree} 连同坐标一起打到 logcat。
        //   前几轮 `paint play blur big` 到底有没有命中封面那层，就是因为
        //   logOnce 的 140 条预算被别的 key 吃光而查不出来。
        try {
            if (v != null) {
                // ★★★ v0.1.87（r111）：**诊断记账降到「调试日志」开关后面。**
                //   这里原来每画一层、每帧都要做一次 String.replace + WeakHashMap.put，
                //   在大列表里就是每帧几百次分配 —— 对宿主是纯负担，而它只服务于
                //   probePlayTree 的日志。BgPeel.PAINTER 只在 debugLog 打开时才维护。
                if (RemoteConfig.debugLog()) {
                    BgPeel.PAINTER.put(v, prefix.replace(" -> ", "").trim());
                }
                // ★ v0.1.80：记下「画它的时候它在哪一页」，换页即作废（见 wasPaintedAsPlay）
                //   ★ 这条是**功能**（判档用），不能关。
                if (prefix.startsWith("paint play")) {
                    BgPeel.PLAY_PAINT_PAGE.put(v, new java.lang.ref.WeakReference<>(PlayPage.pageAnchorOf(v)));
                }
            }
        } catch (Throwable ignored) {
        }
        // ★ v0.1.74（r88）：只要出现「播放页」相关命中，就武装 8 秒的大层快照窗口
        //   —— 首次打开那一下色块、返回那一瞬的空白，都在这 8 秒里。
        try {
            if (prefix != null && prefix.startsWith("paint play")) snapArm();
        } catch (Throwable ignored) {
        }
        // ★ v0.1.74（r88）：命中序列（logcat tag = MoriHit，按「动作+签名」400ms 去重）。
        //   这是唯一能看清「转场那几帧里，谁被 drop、谁被 paint」的通道。
        try {
            if (BgPeel.sHitLogLeft > 0 && RemoteConfig.debugLog()) {
                String key = prefix + signature(v);
                Long last = BgPeel.HITLOG_AT.get(key);
                long now = System.currentTimeMillis();
                if (last == null || now - last > 400L) {
                    BgPeel.HITLOG_AT.put(key, now);
                    BgPeel.sHitLogLeft--;
                    android.util.Log.i("MoriHit", (now % 100000) + " " + prefix
                            + BgPeel.name(v) + " " + r.width() + "x" + r.height()
                            + " a=" + BgPeel.alphaOf(v) + " kids=" + BgPeel.kidsOf(v));
                }
            }
        } catch (Throwable ignored) {
        }
        if (!logOnce("hit:" + prefix + signature(v), null)) return;
        BgPeel.log(prefix + BgPeel.name(v) + " " + r.width() + "x" + r.height()
                + " top=" + BgPeel.topOf(v) + " kids=" + BgPeel.kidsOf(v) + " bg=" + BgPeel.desc(v.getBackground())
                + " a=" + BgPeel.alphaOf(v));
    }


    /**
     * 同一个 key 只执行一次。
     *
     * @param msg 非 null 时顺带打印这一行
     * @return true = 这次是新 key（已登记）
     */
    static boolean logOnce(String key, String msg) {
        if (BgPeel.sOnceTotal >= BgPeel.ONCE_TOTAL) return false;
        if (key != null) {
            if (BgPeel.ONCE.containsKey(key)) return false;
            BgPeel.ONCE.put(key, Boolean.TRUE);
        }
        BgPeel.sOnceTotal++;
        if (msg != null) BgPeel.log(msg);
        return true;
    }


    /** 每 10 秒最多一条：这一轮到底画了多少层（判断「生效没生效」的最快线索）。 */
    static void maybeSummary() {
        // ★ v0.1.87：这是纯诊断（每 10 秒一行的统计），只在「调试日志」打开时打
        if (!RemoteConfig.debugLog()) return;
        long now = System.currentTimeMillis();
        if (now - BgPeel.sLastSummaryAt < 10000L) return;
        if (BgPeel.sSeen == 0 && BgPeel.sSkipped == 0 && BgPeel.sLeaf == 0 && BgPeel.sBar == 0
                && BgPeel.sGrad == 0 && BgPeel.sOverlay == 0 && BgPeel.sScrim == 0 && BgPeel.sVeil == 0) return;
        BgPeel.sLastSummaryAt = now;
        if (BgPeel.sLogs >= BgPeel.LOG_BUDGET) return;
        BgPeel.sLogs++;
        BgPeel.log("draw seen=" + BgPeel.sSeen + " wall=" + BgPeel.sSkipped + " leaf=" + BgPeel.sLeaf
                + " bar=" + BgPeel.sBar + " grad=" + BgPeel.sGrad + " mask=" + BgPeel.sOverlay
                + " scrim=" + BgPeel.sScrim + " veil=" + BgPeel.sVeil + " atmos=" + BgPeel.sAtmos
                + " play=" + BgPeel.sBlurBg + " sheet=" + BgPeel.sSheet + " zoom=" + BgPeel.sZoom
                + " armed=" + (BgPeel.sArmed ? 1 : 0) + " live=" + (BgPeel.sPainted ? 1 : 0));
        BgPeel.sSeen = 0;
        BgPeel.sSkipped = 0;
        BgPeel.sLeaf = 0;
        BgPeel.sBar = 0;
        BgPeel.sGrad = 0;
        BgPeel.sOverlay = 0;
        BgPeel.sScrim = 0;
        BgPeel.sVeil = 0;
        BgPeel.sAtmos = 0;
        BgPeel.sBlurBg = 0;
        BgPeel.sSheet = 0;
        BgPeel.sZoom = 0;
    }


    static void probeNow(final View any) {
        final View root;
        try {
            root = any.getRootView();
        } catch (Throwable t) {
            return;
        }
        if (root == null) return;
        final int seq = ++BgPeel.sProbeSeq;
        BgPeel.sProbeLines = 0;
        BgPeel.MAIN.post(() -> {
            try {
                android.util.Log.i("MoriProbe", "==== probe #" + seq
                        + " root=" + root.getWidth() + "x" + root.getHeight()
                        + " sw=" + BgPeel.sSW + " sh=" + BgPeel.sSH + " fullW=" + BgPeel.sFullW
                        + " playCtx=" + PlayPage.playContext(any)
                        + " playWin=" + PlayPage.isPlayWindow(any));
                probeWalk(root, 0, "");
                android.util.Log.i("MoriProbe", "==== probe #" + seq + " end lines=" + BgPeel.sProbeLines);
            } catch (Throwable t) {
                android.util.Log.i("MoriProbe", "probe fail " + t);
            }
        });
    }


    /** 探针触发点：播放页被认出来的地方（见 paintBlurBg）。 */
    static void probePlayTree(final View any) {
        try {
            // ★★★ v0.1.87（r111）：探针只在「调试日志」打开时跑。
            //   它要遍历整棵树、拼几十行字符串、再走 LSPosed 的日志通道 ——
            //   在播放页静止时每 1.5 秒一次，属于纯诊断开销。
            if (!RemoteConfig.debugLog()) return;
            if (any == null) return;
            long now = System.currentTimeMillis();
            if (now - BgPeel.sProbeAt < 1500L) return;
            if (BgPeel.sProbeLeft <= 0) return;
            BgPeel.sProbeAt = now;
            BgPeel.sProbeLeft--;
            probeNow(any);
        } catch (Throwable ignored) {
        }
    }


    /**
     * 确认是月度回顾后：标记满屏底图并只 invalidate 那一层。
     * 已标记同一 token 则跳过（第二拍短路）。
     */
    static void probeReviewDialog(View root, String why) {
        try {
            if (root == null) return;
            if (!BgPeel.sArmed) {
                BgPeel.sReviewRescan = true;
                return;
            }
            if (root.getWindowToken() == null || !root.isShown()) return;
            if (PlayPage.isPlayWindow(root) || PlayPage.playContext(root)) return;
            android.os.IBinder tok = root.getWindowToken();
            View marked = BgPeel.sReviewBgRef == null ? null : BgPeel.sReviewBgRef.get();
            if (tok != null && tok == BgPeel.sReviewToken && marked != null
                    && marked.getWindowToken() == tok) {
                return;
            }
            boolean ok = CoverLayer.rootHasReviewCue(root)
                    || BgPeel.a11yHasMonthlyCue(root, 0, 0)
                    || BgPeel.structuralMonthlyReview(root);
            if (!ok) {
                BgPeel.reviewLog("review bg miss（未确认）" + why + " " + BgPeel.name(root)
                        + " " + root.getWidth() + "x" + root.getHeight());
                return;
            }
            View bg = BgPeel.findFullscreenReviewBackdrop(root, 0, 0);
            if (bg == null) {
                BgPeel.reviewLog("review bg miss（无满屏底）" + why + " " + BgPeel.name(root));
                return;
            }
            BgPeel.sReviewToken = tok;
            BgPeel.sReviewBgRef = new java.lang.ref.WeakReference<>(bg);
            BgPeel.sReviewUiRoot = new java.lang.ref.WeakReference<>(root);
            BgPeel.sReviewRescan = false;
            BgPeel.reviewLog("review bg arm " + why + " -> " + BgPeel.name(bg)
                    + " " + bg.getWidth() + "x" + bg.getHeight());
            try {
                bg.invalidate();
                ViewParent p = bg.getParent();
                if (p instanceof View) ((View) p).invalidate();
            } catch (Throwable ignored) {
            }
            // ★ v0.2.7：arm 成功后立刻刷整窗阴影（addView 路径以前漏了）
            try {
                TextShadow.scheduleShadowRefresh(root, "reviewArm:" + why);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            BgPeel.reviewLog("review bg probe err: " + t);
        }
    }


    /** 逐层 dump：深度 + 子索引路径 + 类名 + 窗口矩形 + alpha/vis/clk + 接管者 + 位图身份。 */
    static void probeWalk(View v, int depth, String path) {
        if (v == null || depth > 24) return;
        if (BgPeel.sProbeLines > 90) return;
        try {
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0) return;
            int[] loc = BgPeel.LOC.get();
            v.getLocationInWindow(loc);
            StringBuilder sb = new StringBuilder(220);
            sb.append(depth).append(' ').append(path).append(' ').append(BgPeel.name(v))
                    .append(" [").append(loc[0]).append(',').append(loc[1]).append(',')
                    .append(loc[0] + w).append(',').append(loc[1] + h).append(']')
                    .append(" a=").append(v.getAlpha())
                    .append(" vis=").append(v.getVisibility())
                    .append(" clk=").append(v.isClickable() ? 1 : 0);
            android.graphics.drawable.Drawable bg = v.getBackground();
            if (bg != null) sb.append(" bg=").append(bg.getClass().getSimpleName());
            String painter = BgPeel.PAINTER.get(v);
            if (painter != null) sb.append(" P=").append(painter);
            if (v instanceof android.widget.ImageView) {
                android.graphics.drawable.Drawable d =
                        ((android.widget.ImageView) v).getDrawable();
                sb.append(" drw=").append(d == null ? "null" : d.getClass().getSimpleName())
                        .append('/').append(d == null ? 0 : d.getIntrinsicWidth())
                        .append('x').append(d == null ? 0 : d.getIntrinsicHeight());
                Bitmap b = BgPeel.privacyBitmapOf(v);
                if (b != null) {
                    sb.append(" bmp=").append(b.getWidth()).append('x').append(b.getHeight())
                            .append('/').append(b.getConfig())
                            .append(" blurred=").append(BgPeel.isBlurredBitmap(b) ? 1 : 0);
                } else {
                    sb.append(" bmp=none");
                }
                sb.append(" r=").append(BgPeel.blurRadiusOf(v))
                        .append(" kind=").append(CoverLayer.imgKind(v));
            }
            android.util.Log.i("MoriProbe", sb.toString());
            BgPeel.sProbeLines++;
            if (v instanceof android.view.ViewGroup) {
                // 底下的 react-navigation 页面（列表/详情）整棵跳过：r73 实测它一次就吃掉
                // 80 行，把真正的播放页挤出输出（探针 #1 全是列表页就是这个原因）。
                String cn = BgPeel.name(v);
                if ("ScreenContainer".equals(cn) || "ReactScrollView".equals(cn)
                        || "RecyclerViewImpl".equals(cn) || "ViewPager2".equals(cn)) {
                    return;
                }
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                int n = g.getChildCount();
                for (int i = 0; i < n; i++) {
                    probeWalk(g.getChildAt(i), depth + 1, path + "/" + i);
                }
            }
        } catch (Throwable ignored) {
        }
    }


    /** 视图签名：类名 + 尺寸 + 窗口内位置（同一块界面重复绘制时签名稳定）。 */
    static String signature(View v) {
        try {
            int[] loc = BgPeel.LOC.get();
            v.getLocationInWindow(loc);
            return BgPeel.name(v) + "@" + v.getWidth() + "x" + v.getHeight()
                    + "+" + loc[0] + "," + loc[1];
        } catch (Throwable t) {
            return "?";
        }
    }


    /**
     * ★★★ v0.1.74（r88）：**开/关播放页那几秒的「大层时间线」**（logcat tag = MoriSnap）。
     *
     * <p>为什么要它：r88 的两个问题都发生在「一秒以内的一次转场」里 ——
     * 「首次打开封面位置闪一下色块」「返回后页面空白」。层级探针（MoriProbe）
     * 是事后拍的，永远拍不到那一瞬；而 r76 因为性能把心跳里的层级 dump 删掉了。
     * 这里改成<b>按需</b>：只要出现一次 {@code paint play…} 命中就武装 8 秒窗口，
     * 每 250ms 打一条「所有大块层」的摘要（类名 / 尺寸 / 屏内 top / alpha / 可见性 /
     * 画笔归属），最多 28 条。稳态零开销（窗口外直接返回）。
     */
    /**
     * 武装大层快照。
     *
     * @param force 页面切换事件用 true：**每次都重新给满 12 条**。
     *              r89 是「用完了才补」，于是用户复现那一下只剩 1 条额度 ——
     *              正好只录到「切换那一瞬」（DecorView INVISIBLE），看不到之后有没有恢复。
     *              绘制路径（{@code hit}）用 false，避免播放页每帧命中把额度重置掉。
     */
    static void snapArm(boolean force) {
        // ★ v0.1.80（r104）：稳定优先 —— 诊断只在 debugLog 打开时跑。
        if (!RemoteConfig.debugLog()) return;
        long now = System.currentTimeMillis();
        // ★ v0.1.87：**6 条 → 2 条**。这条只在「调试日志」打开时跑，但一次切页 6 次全树
        //   dump 就是几百行 logcat（实测 15 秒 485 行），既是主线程开销也淹掉有用信息。
        //   2 条足够看清「切页那一瞬」和「稳定后」。
        if (force || now >= BgPeel.sSnapUntil) BgPeel.sSnapLeft = 2;
        // 日志额度也要给切页留一份（BgPeel.sTraceLeft 会被图片 trace 吃光）
        if (force) BgPeel.sTraceLeft = Math.max(BgPeel.sTraceLeft, 20);
        BgPeel.sSnapUntil = now + 4000L;
        if (force) BgPeel.sSnapStart = now;
        // ★★★ v0.1.76（r90 实测教训）：**心跳是 3 秒一拍**（`postDelayed(this, 3000L)`），
        //   挂在心跳里的快照 6 秒只能出 2 条 ⇒ 永远拍不到「空白稳定下来」的那一刻。
        //   这里给它自己一条 400ms 的自转，只在武装窗口里跑，窗口一过自动停。
        if (!BgPeel.sSnapLoopOn) {
            BgPeel.sSnapLoopOn = true;
            BgPeel.MAIN.postDelayed(BgPeel.SNAP_LOOP, 40L);
        }
    }


    static void snapArm() {
        snapArm(false);
    }


    /** 打一条大层快照（由 {@link #BgPeel.SNAP_LOOP} 每 400ms 调一次）。 */
    static void snapOnce() {
        try {
            long now = System.currentTimeMillis();
            if (now > BgPeel.sSnapUntil || BgPeel.sSnapLeft <= 0) return;
            if (now - BgPeel.sSnapAt < 300L) return;
            BgPeel.sSnapAt = now;
            BgPeel.sSnapLeft--;
            View root = BgPeel.sLastRoot == null ? null : BgPeel.sLastRoot.get();
            if (root == null) return;
            // v0.1.76（r90）：把「整个窗口还在不在」也写进头行 —— r89 那一发只拿到
            // `DecorView vis=4`（INVISIBLE）+ ScreenContainer 无子节点，分不清是
            // 「窗口被宿主藏了」还是「页面被卸载了」。这三个值一起看才有结论。
            StringBuilder sb = new StringBuilder("t=" + (now % 100000)
                    + " winVis=" + root.getWindowVisibility()
                    + " rootVis=" + root.getVisibility()
                    + " shown=" + (root.isShown() ? 1 : 0));
            snapWalk(root, 0, sb, 0);
            android.util.Log.i("MoriSnap", sb.toString());
        } catch (Throwable ignored) {
        }
    }


    static void snapWalk(View v, int depth, StringBuilder sb, int lines) {
        // ★ v0.1.87：单条快照 2200 → 900 字符（约 40 行 → 16 行）。
        //   这条只在调试日志打开时跑，但一次切页仍可能打出上百行，把真正要看的东西冲掉。
        if (v == null || depth > 16 || sb.length() > 900) return;
        int w;
        int h;
        try {
            w = v.getWidth();
            h = v.getHeight();
        } catch (Throwable t) {
            return;
        }
        if (w <= 0 || h <= 0) return;
        // 只关心「一眼能看出画面变了」的大块：≥50% 屏宽 或 ≥12% 屏高
        boolean big = w * 100 >= (long) BgPeel.sSW * 50 || h * 100 >= (long) BgPeel.sSH * 12;
        String painter = null;
        try {
            painter = BgPeel.PAINTER.get(v);
        } catch (Throwable ignored) {
        }
        String cn = BgPeel.name(v);
        // 页面容器（Screen*）无论大小都要打 —— 空白页要看的正是「它还在不在、有几个孩子」
        boolean pageNode = cn.startsWith("Screen") || cn.contains("ContentWrapper");
        if (big || painter != null || pageNode) {
            int[] loc = BgPeel.LOC.get();
            try {
                v.getLocationInWindow(loc);
            } catch (Throwable t) {
                loc[0] = -1;
                loc[1] = -1;
            }
            sb.append('\n').append(' ').append(depth).append(' ').append(cn)
                    .append(' ').append(w).append('x').append(h)
                    .append(" @").append(loc[0]).append(',').append(loc[1])
                    .append(" a=").append(BgPeel.alphaOf(v))
                    .append(" vis=").append(v.getVisibility())
                    .append(" kids=").append(v instanceof ViewGroup
                            ? ((ViewGroup) v).getChildCount() : 0)
                    .append(painter == null ? "" : " P=").append(painter == null ? "" : painter);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                snapWalk(g.getChildAt(i), depth + 1, sb, lines);
            }
        }
    }


    /**
     * ★★★ v0.1.95（r117）：<b>「播放页封面那一块」到底有没有被画。</b>
     *
     * <p>用户第 1 条的后半句是「作品封面本身不显示」。日志里
     * {@code ExpoImageView 1272x954 ... -> host} 说明这一层<b>交还给了宿主</b>，
     * 但屏幕上那块区域只有背景的糊图。两种可能：
     * <ol>
     *   <li>这一层被执行了，但 Drawable 是空的 / 被同位置的别层盖住；</li>
     *   <li>这一层根本没进绘制（不可见、尺寸 0、或被宿主跳过）。</li>
     * </ol>
     * 所以这里把「每次真的走到这一层 draw」这件事本身记下来，带屏幕矩形、
     * Drawable 类型、位图尺寸 —— 有这条就能分清上面两种，不用再猜。
     *
     * <p>尺寸档取「宽度 ≥80% 屏宽 + 高度 20%~70% 屏高」：播放页封面正好是
     * 1272x954（34%），而满屏底图（100%/105%）和列表卡片都落在这个窗口之外。
     */
    static void traceCover(View v, String who) {
        try {
            if (BgPeel.sCoverTraceLeft <= 0) return;
            if (BgPeel.sSW <= 0 || BgPeel.sSH <= 0) return;
            int w = v.getWidth();
            int h = v.getHeight();
            if (w * 100 < (long) BgPeel.sSW * 80) return;
            if (h * 100 < (long) BgPeel.sSH * 20 || h * 100 > (long) BgPeel.sSH * 70) return;
            Long last = BgPeel.COVER_TRACE_AT.get(v);
            long now = System.currentTimeMillis();
            if (last != null && now - last < 800L) return;
            BgPeel.COVER_TRACE_AT.put(v, now);
            BgPeel.sCoverTraceLeft--;
            Drawable d = null;
            String dcls = "-";
            String bmp = "-";
            try {
                if (v instanceof ImageView) d = ((ImageView) v).getDrawable();
                if (d != null) {
                    dcls = d.getClass().getSimpleName();
                    Bitmap b = BgPeel.deepBitmap(d, 0);
                    if (b == null) {
                        bmp = "none";
                    } else {
                        bmp = b.getWidth() + "x" + b.getHeight()
                                + (BgPeel.isBlurredBitmap(b) ? "/blur" : "/sharp");
                    }
                } else {
                    dcls = "null";
                }
            } catch (Throwable ignored) {
            }
            int[] loc = new int[2];
            try {
                v.getLocationOnScreen(loc);
            } catch (Throwable ignored) {
            }
            StringBuilder path = new StringBuilder();
            try {
                View c = v;
                for (int i = 0; i < 5 && c != null; i++) {
                    path.append('/').append(BgPeel.name(c));
                    Object p = c.getParent();
                    c = p instanceof View ? (View) p : null;
                }
            } catch (Throwable ignored) {
            }
            android.util.Log.i("MoriCover", BgPeel.name(v) + " " + w + "x" + h
                    + " scr=" + loc[0] + "," + loc[1]
                    + " a=" + BgPeel.alphaOf(v) + " vis=" + v.getVisibility()
                    + " drawable=" + dcls + " bmp=" + bmp
                    + " playCtx=" + (PlayPage.playContext(v) ? 1 : 0)
                    + " playWin=" + (PlayPage.isPlayWindow(v) ? 1 : 0)
                    + " -> " + who + "  path=" + path);
        } catch (Throwable ignored) {
        }
    }


    /**
     * v0.1.71（r73）：ExpoImageView 的分支决策实时留痕（logcat tag = MoriTrace）。
     *
     * <p>为什么需要：层级探针里的 {@code P=paint play blur big} 只是<b>历史归属</b>，
     * 分不清「此刻仍在接管」还是「动画中途接管过一次」。而「作品封面不见了」
     * 这个结论必须落在「此刻谁没让宿主画」上，所以这里每帧把四个分支的结果
     * 连同判据输入（尺寸 / top / blurRadius / 位图是否糊 / 是否播放页）一起打出来。
     */
    static void traceExpo(View v, String who) {
        try {
            if (BgPeel.sTraceLeft <= 0) return;
            if (BgPeel.sSW <= 0 || BgPeel.sSH <= 0) return;
            int w = v.getWidth();
            int h = v.getHeight();
            if (w * 100 < (long) BgPeel.sSW * 55 || h * 100 < (long) BgPeel.sSH * 18) return;
            Long last = BgPeel.TRACE_AT.get(v);
            long now = System.currentTimeMillis();
            if (last != null && now - last < 1000L) return;
            BgPeel.TRACE_AT.put(v, now);
            BgPeel.sTraceLeft--;
            Bitmap b = BgPeel.privacyBitmapOf(v);
            android.util.Log.i("MoriTrace", BgPeel.name(v) + " " + w + "x" + h
                    + " top=" + BgPeel.topOf(v)
                    + " a=" + BgPeel.alphaOf(v)
                    + " r=" + BgPeel.blurRadiusOf(v)
                    + " kind=" + CoverLayer.imgKind(v)
                    + " bmp=" + (b == null ? "none"
                        : b.getWidth() + "x" + b.getHeight() + (BgPeel.isBlurredBitmap(b) ? "/blur" : "/sharp"))
                    + " playCtx=" + (PlayPage.playContext(v) ? 1 : 0)
                    + " playWin=" + (PlayPage.isPlayWindow(v) ? 1 : 0)
                    + " float=" + (BgPeel.inFloatingLayer(v) ? 1 : 0)
                    + " -> " + who);
        } catch (Throwable ignored) {
        }
    }


    static void walkDump(View v, int depth, StringBuilder sb) {
        if (v == null || depth > 45 || sb.length() > 400000) return;
        BgPeel.sDumpNodes++;
        try {
            int[] loc = BgPeel.LOC.get();
            v.getLocationOnScreen(loc);
            int w = v.getWidth();
            int h = v.getHeight();
            if (w > 0 && h > 0) {
                if (v.isShown()) BgPeel.sDumpShown++;
                StringBuilder bg = new StringBuilder("-");
                try {
                    Drawable d = v.getBackground();
                    if (d != null) {
                        Integer c = BgPeel.solidColorOf(d);
                        bg.setLength(0);
                        bg.append(c == null ? BgPeel.name0(d) : "#" + Integer.toHexString(c));
                    }
                } catch (Throwable ignored) {
                }
                sb.append('\n').append(depth).append(' ').append(BgPeel.name(v))
                        .append(' ').append(w).append('x').append(h)
                        .append(" @").append(loc[0]).append(',').append(loc[1])
                        .append(" a=").append(String.format(java.util.Locale.US, "%.2f", v.getAlpha()))
                        .append(" r=").append(BgPeel.blurRadiusOf(v))
                        .append(" k=").append(CoverLayer.imgKind(v))
                        .append(" bg=").append(bg);
                // 文字层把内容也打出来（定位「月度回顾」页靠它）
                if (v instanceof android.widget.TextView) {
                    try {
                        CharSequence cs = ((android.widget.TextView) v).getText();
                        String s = cs == null ? "" : cs.toString();
                        if (s.length() > 18) s = s.substring(0, 18);
                        sb.append(" t='").append(s).append('\'');
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                int n = g.getChildCount();
                for (int i = 0; i < n; i++) walkDump(g.getChildAt(i), depth + 1, sb);
            }
        } catch (Throwable ignored) {
        }
    }


    /**
     * ★★★ v0.1.68（r71）：上一版这套审计**只扫到第 9 层就停了**，
     * 日志里每次都是同一条 9 层骨架（DecorView→…→ReactViewGroup 1272x2772），
     * 里面真正关心的层（播放页内容 / 封面）全在更深处 —— 什么也没看到。
     * 现在：深度放到 30，行数放到 120，并且**只挑三类关键层**，不再逐行铺骨架：
     * <ul>
     *   <li>{@code img}：所有 ImageView —— 类名/尺寸/alpha + 往上 4 层的 alpha 链
     *       （封面到底被谁压住，一行就能看出来）；</li>
     *   <li>{@code dim}：alpha&lt;0.05 的大层（透明那屏的真凶）；</li>
     *   <li>{@code big}：占屏 ≥20% 的大层（看绘制顺序与底图归属）。</li>
     * </ul>
     * 并且**边遍历边修**：这里不依赖 {@code View#draw} 被调用，
     * 所以「祖先 alpha=0 导致钩子根本不跑」那条死路也绕开了。
     */
    static void walkPlay(View v, int depth) {
        if (v == null || depth > 30 || BgPeel.sAuditLines > 120) return;
        // ★★★ v0.1.87（r111）：**日志部分挂到「调试日志」开关后面，修复部分照旧跑。**
        //   这个方法每 3 秒在主线程遍历整棵树；原来即使只是「记录」，
        //   也要为每一层拼 BgPeel.name()/BgPeel.chainOf() 字符串、再走一次 logcat 通道。
        final boolean dbg = RemoteConfig.debugLog();
        // 关着日志时用节点数兜一个遍历上限（原来靠 BgPeel.sAuditLines 的 120 行封顶）
        if (!dbg && ++BgPeel.sAuditNodes > 400) return;
        int w = v.getWidth();
        int h = v.getHeight();
        if (w > 0 && h > 0) {
            long area = (long) w * (long) h;
            if (v instanceof android.widget.ImageView) {
                float a = v.getAlpha();
                if (dbg) {
                    BgPeel.sAuditLines++;
                    BgPeel.log("img " + BgPeel.name(v) + " " + w + "x" + h + " a=" + a
                            + (BgPeel.drawableReady(v) ? " D" : " -") + BgPeel.chainOf(v, 4));
                }
                // 内容图被淡到看不见 ⇒ 直接拉回 1（封面就是卡在这一步）
                if (a < 0.35f && w * 100 >= (long) BgPeel.sSW * 40
                        && h * 100 >= (long) BgPeel.sSH * 12) {
                    v.setAlpha(1f);
                    if (dbg) BgPeel.log("img lift -> " + BgPeel.name(v) + " " + w + "x" + h + " from a=" + a);
                }
            } else if (v.getAlpha() < 0.05f && area * 100L >= (long) BgPeel.sSW * (long) BgPeel.sSH * 3L) {
                // ★★★ v0.1.69（r72 实测）：**只记日志，绝不拉 alpha**。
                //   r72 日志实证：被我 0.1.68 的 `audit lift` 揭出来的那层是
                //   `dim ReactViewGroup 1272x2772 a=0.0 IMG` —— 它的子树里是
                //   LinearGradientView + 文本 + 图，正是**R18 提示模态**。
                //   ⇒「alpha=0 + 子树里有图 + 不可点击」这条判据根本不能用来区分
                //   「该显示的播放页」和「宿主故意藏起来的模态」：两者长得一模一样。
                //   所以这一类**永远不再动**（容器级 alpha 一律不碰），只记录。
                if (dbg) {
                    boolean img = BgPeel.hasImageDeep(v, 0);
                    BgPeel.sAuditLines++;
                    BgPeel.log("dim " + BgPeel.name(v) + " " + w + "x" + h + " a=" + v.getAlpha()
                            + (v.isClickable() ? " CLICK" : "") + (img ? " IMG" : "") + BgPeel.chainOf(v, 2));
                }
                // ★★★ v0.1.72（r74 第 3 条）：**这一类里有一个子集必须救回来。**
                //   r72 的结论「alpha=0 一律不动」是为了不揭开 R18 提示模态，
                //   但「播放页内容容器被压成 0」和它长得一模一样 —— 而那是
                //   「收成迷你条 → 再展开之后整页透出后面那一页」的<b>唯一</b>成因
                //   （r68 dump 原文：`ReactViewGroup 0,0-1272,2772 alpha=0.0`，
                //   封面/标题/按钮全在里面且都好好的）。
                //   现在用一条**结构性**判据把它们分开，不再靠 alpha 猜：
                //   只有「<b>这层子树里装着那条活着的进度条</b>」才拉回来。
                //   R18 模态里不可能有播放页的进度条 ⇒ 结构上不可能误揭；
                //   反过来，播放页的内容容器必然包着进度条 ⇒ 该救的一定救得到。
                if (containsPlaySeek(v)) {
                    float a0 = v.getAlpha();
                    v.setAlpha(1f);
                    if (dbg) {
                        BgPeel.log("play dim lift -> " + BgPeel.name(v) + " " + w + "x" + h
                                + " from a=" + a0);
                    }
                } else if (false) {
                    // ★★★ v0.1.82（r96 用户实测：**R18 限制页被揭开了**）：这条已彻底删除。
                    //   我 r92 想用「切页后 2.5 秒内把满屏 alpha=0 的容器拉回 1」去救空白，
                    //   而 R18 限制页<b>正好就是</b>「满屏 + alpha=0 + 子树有内容 + 不可点击」——
                    //   判据在结构上和「被压成 0 的页面容器」完全同形，r69/r72 已经踩过这个坑，
                    //   我又踩了一次。空白改用「缩略图遮罩」，**容器级 alpha 一律不再碰**。
                }
            } else if (v.getAlpha() < 0.98f && !v.isClickable()
                    && w * 100 >= (long) BgPeel.sSW * 90
                    && h * 100 >= (long) BgPeel.sSH * 55) {
                // ★★★ v0.1.69（r72 实测的真凶）：**播放页容器卡在 alpha=0.41**。
                //   r72 日志原文：
                //   <pre>
                //   big ReactViewGroup 1272x2560 a=0.41108444
                //     └ ReactViewGroup 1272x954 a=1.0 CLICK      ← 播放页顶部横幅
                //   </pre>
                //   0.41108444 是宿主淡入动画被打断后的**残留值**（不是 0，也不是 1），
                //   效果就是你看到的「迷你条往返后整页发虚/透明」：
                //   播放页自己只画 41%，后面的作品详情页自然透上来。
                //   ★ 和上面那类「alpha=0」必须严格区分：
                //     宿主有意藏起来的模态是**恰好 0**，这里是**0 和 1 之间**。
                //     只把「中途值补完」，绝不去碰 0 —— 这样不会重演 r69/r72 的揭模态事故。
                float a = v.getAlpha();
                if (dbg) {
                    BgPeel.sAuditLines++;
                    BgPeel.log("mid " + BgPeel.name(v) + " " + w + "x" + h + " a=" + a + BgPeel.chainOf(v, 2));
                }
                v.setAlpha(1f);
                if (dbg) BgPeel.log("mid lift -> " + BgPeel.name(v) + " " + w + "x" + h + " from a=" + a);
            } else if (depth >= 6 && area * 100L >= (long) BgPeel.sSW * (long) BgPeel.sSH * 20L) {
                if (dbg) {
                    BgPeel.sAuditLines++;
                    BgPeel.log("big " + BgPeel.name(v) + " " + w + "x" + h + " a=" + v.getAlpha()
                            + (v.isClickable() ? " CLICK" : "") + BgPeel.chainOf(v, 2));
                }
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n && BgPeel.sAuditLines <= 120; i++) {
                walkPlay(g.getChildAt(i), depth + 1);
            }
        }
    }

}
