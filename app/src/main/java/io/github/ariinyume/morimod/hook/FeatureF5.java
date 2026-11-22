package io.github.ariinyume.morimod.hook;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.ariinyume.morimod.config.MoriConfig;
import io.github.ariinyume.morimod.config.RemoteConfig;
import io.github.ariinyume.morimod.util.XposedCompat;
import io.github.libxposed.api.XposedInterface;

/**
 * F5 · 宿主主页区块显隐与排序（路线 2）。
 *
 * <h3>v0.2.8</h3>
 * <p>RN 触控按 <b>Yoga 布局坐标</b>选目标，不管 translation / offset。
 * 所以：
 * <ul>
 *   <li>视觉收拢用 {@code translationY}（Yoga 不认原生 GONE，隐藏必留空洞）</li>
 *   <li>隐藏：{@code INVISIBLE} + 下方模块上移；并钩 {@code setVisibility} 防 RN 冲回</li>
 *   <li>点击：在 {@code ReactRootView} / content / ScrollView 的
 *       {@code dispatchTouchEvent} 里把坐标从「视觉位置」映回「Yoga 位置」</li>
 *   <li>滑动中跳过 pack，避免布局调整模式上下滑卡顿</li>
 *   <li>恢复默认：清 translation + 高度缓存，短窗口只恢复不 pack</li>
 * </ul>
 */
public final class FeatureF5 {

    private static final String TAG = "[MoriMod:F5]";
    private static final int MAX_WALK = 900;
    private static final long DEBOUNCE_MS = 120L;
    private static final long LOG_EVERY_MS = 2500L;
    private static final long GLOBAL_PACK_MIN_MS = 500L;
    private static final long SETTLE_MS = 450L;
    private static final int TAG_H = 0x4d4f5249; // MORI cached height

    private static volatile boolean sOn = false;
    private static volatile boolean sHooked = false;
    private static volatile boolean sLoggedIn = false;
    private static volatile boolean sLoginSticky = false;
    /** 手指正在竖滑主页：跳过 onLayout/globalLayout pack，防抖动。 */
    private static volatile boolean sScrolling = false;

    private static final ThreadLocal<Boolean> SKIP =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** 同一 MotionEvent 只 remap 一次，避免 Root→Child 双偏移。 */
    private static final ThreadLocal<MotionEvent> REMAPPED = new ThreadLocal<>();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean sPending;
    private static volatile long sLastLogAt;
    private static volatile long sLastGlobalPackAt;
    private static volatile long sSettleUntil;

    private static final List<WeakReference<View>> sTouched = new ArrayList<>();
    private static volatile WeakReference<ViewGroup> sHomeContent;
    private static volatile Method sSetMeasuredDimension;
    private static volatile ClassLoader sCl;

    /** view → 当前 translationY（触控重映射用）。 */
    private static final WeakHashMap<View, Float> sTy = new WeakHashMap<>();
    /** 强制保持 INVISIBLE 的隐藏模块根。 */
    private static final WeakHashMap<View, Boolean> sForceInvisible = new WeakHashMap<>();
    private static final Set<View> sPackedSync = new HashSet<>();

    private FeatureF5() {
    }

    public static void install(ClassLoader cl) {
        sCl = cl;
        RemoteConfig.addListener(FeatureF5::onConfigChanged);
        apply(RemoteConfig.get());
        XposedCompat.log(TAG + " install registered");
    }

    public static boolean isLoggedIn() {
        return sLoggedIn || sLoginSticky;
    }

    public static void probeLoginShell() {
        try {
            refreshLoginFlag();
        } catch (Throwable ignored) {
        }
    }

    private static void onConfigChanged() {
        try {
            apply(RemoteConfig.get());
        } catch (Throwable t) {
            XposedCompat.log(TAG + " onConfigChanged failed: " + t);
        }
    }

    private static void apply(MoriConfig cfg) {
        boolean want = cfg != null && cfg.f5Enabled;
        if (!want) {
            if (sOn) {
                sOn = false;
                MAIN.post(FeatureF5::restoreAllNow);
                XposedCompat.log(TAG + " off → restore");
            }
            return;
        }
        if (!sHooked && !hookAll()) {
            XposedCompat.log(TAG + " hooks missing, skip");
            return;
        }
        if (!sOn) {
            sOn = true;
            XposedCompat.log(TAG + " on (ty-pack + touch-remap v0.2.8)");
        }
        // 配置变成「全显 + 默认序」时先进入 settle，避免旧 ty 与 Yoga 打架挤成一团
        if (cfg != null && looksLikeDefaultAllVisible(cfg)) {
            beginSettle();
            MAIN.post(FeatureF5::clearPackVisuals);
        }
        schedule();
    }

    private static boolean looksLikeDefaultAllVisible(MoriConfig cfg) {
        if (cfg.f5Modules == null || cfg.f5Modules.isEmpty()) return false;
        String[] def = MoriConfig.F5_DEFAULT_IDS;
        if (cfg.f5Modules.size() < def.length) return false;
        for (int i = 0; i < def.length; i++) {
            MoriConfig.F5Entry e = cfg.f5Modules.get(i);
            if (e == null || !def[i].equals(e.id) || !e.visible) return false;
        }
        return true;
    }

    private static void beginSettle() {
        sSettleUntil = System.currentTimeMillis() + SETTLE_MS;
    }

    private static synchronized boolean hookAll() {
        if (sHooked) return true;
        boolean okAdd = XposedCompat.hookMethod(
                ViewGroup.class, "addView",
                new Class<?>[]{View.class, int.class, ViewGroup.LayoutParams.class},
                FeatureF5::afterAddView, TAG);
        XposedCompat.hookMethod(
                ViewGroup.class, "addView",
                new Class<?>[]{View.class, int.class},
                FeatureF5::afterAddView, TAG);

        // RN 会不断 setVisibility(VISIBLE) 冲掉我们的隐藏
        XposedCompat.hookMethod(
                View.class, "setVisibility",
                new Class<?>[]{int.class},
                FeatureF5::aroundSetVisibility, TAG);

        int layoutHooks = 0;
        int touchHooks = 0;
        String[] layoutCls = {
                "com.facebook.react.views.view.ReactViewGroup",
                "com.facebook.react.views.scroll.ReactScrollView",
                "com.facebook.react.views.scroll.ReactNestedScrollView",
        };
        ClassLoader cl = sCl;
        for (String cn : layoutCls) {
            try {
                Class<?> c = Class.forName(cn, false,
                        cl != null ? cl : FeatureF5.class.getClassLoader());
                if (XposedCompat.hookMethod(c, "onLayout",
                        new Class<?>[]{boolean.class, int.class, int.class, int.class, int.class},
                        FeatureF5::afterOnLayout, TAG)) {
                    layoutHooks++;
                }
                if (XposedCompat.hookMethod(c, "dispatchTouchEvent",
                        new Class<?>[]{MotionEvent.class},
                        FeatureF5::aroundDispatchTouch, TAG)) {
                    touchHooks++;
                }
            } catch (Throwable ignored) {
            }
        }
        // ★ v0.2.8：Fabric/Paper 都在 ReactRootView 上做 Yoga 命中，必须在这里 remap
        String[] rootCls = {
                "com.facebook.react.ReactRootView",
                "com.facebook.react.runtime.ReactSurfaceView",
        };
        for (String cn : rootCls) {
            try {
                Class<?> c = Class.forName(cn, false,
                        cl != null ? cl : FeatureF5.class.getClassLoader());
                if (XposedCompat.hookMethod(c, "dispatchTouchEvent",
                        new Class<?>[]{MotionEvent.class},
                        FeatureF5::aroundDispatchTouch, TAG)) {
                    touchHooks++;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!okAdd && layoutHooks == 0) return false;
        sHooked = true;
        XposedCompat.log(TAG + " hooked addView=" + okAdd
                + " rnOnLayout=" + layoutHooks + " touch=" + touchHooks);
        return true;
    }

    private static Object aroundSetVisibility(XposedInterface.Chain chain) throws Throwable {
        if (Boolean.TRUE.equals(SKIP.get()) || !sOn) return chain.proceed();
        Object thiz = chain.getThisObject();
        if (!(thiz instanceof View)) return chain.proceed();
        View v = (View) thiz;
        if (!Boolean.TRUE.equals(sForceInvisible.get(v))) return chain.proceed();
        Object arg = chain.getArg(0);
        int want = arg instanceof Integer ? (Integer) arg : View.VISIBLE;
        if (want == View.VISIBLE || want == View.INVISIBLE) {
            // 强制保持 INVISIBLE（不用 GONE：Yoga/RN 更爱把它冲掉且几何更乱）
            SKIP.set(Boolean.TRUE);
            try {
                Object[] args = chain.getArgs().toArray();
                // 无法 setArg 时：先 proceed 再立刻改回
                Object r = chain.proceed();
                if (v.getVisibility() != View.INVISIBLE) {
                    v.setVisibility(View.INVISIBLE);
                }
                return r;
            } finally {
                SKIP.set(Boolean.FALSE);
            }
        }
        return chain.proceed();
    }

    /**
     * 把触点从「视觉模块」映回「Yoga 模块」。
     * <p>MotionEvent 就地 {@code offsetLocation}，proceed 后还原。
     */
    private static Object aroundDispatchTouch(XposedInterface.Chain chain) throws Throwable {
        if (Boolean.TRUE.equals(SKIP.get()) || !sOn) return chain.proceed();
        Object thiz = chain.getThisObject();
        if (!(thiz instanceof ViewGroup)) return chain.proceed();
        ViewGroup g = (ViewGroup) thiz;
        ViewGroup content = homeContentOrNull();
        if (content == null) return chain.proceed();
        // content / 其 Scroll 父 / 含 content 的 ReactRootView
        if (g != content && !isScrollParentOf(g, content) && !isAncestorOf(g, content)) {
            return chain.proceed();
        }

        Object arg = chain.getArg(0);
        if (!(arg instanceof MotionEvent)) return chain.proceed();
        MotionEvent ev = (MotionEvent) arg;

        // 竖滑中跳过 pack（见 afterOnLayout），松手再排一次
        int act = ev.getActionMasked();
        if (act == MotionEvent.ACTION_MOVE) {
            sScrolling = true;
        } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            if (sScrolling) {
                sScrolling = false;
                schedule();
            }
        }

        // 祖先已 remap 过同一事件 → 直接放行，防双偏移
        if (REMAPPED.get() == ev) return chain.proceed();

        float adj = 0f;
        boolean marked = false;
        try {
            adj = remapDeltaY(content, g, ev);
            if (Math.abs(adj) > 0.5f) {
                ev.offsetLocation(0f, adj);
                REMAPPED.set(ev);
                marked = true;
                maybeLog("touch remap dy=" + (int) adj);
            }
            return chain.proceed();
        } finally {
            if (marked) {
                try {
                    ev.offsetLocation(0f, -adj);
                } catch (Throwable ignored) {
                }
                if (REMAPPED.get() == ev) REMAPPED.remove();
            }
        }
    }

    private static boolean isScrollParentOf(ViewGroup scroll, ViewGroup content) {
        ViewParent p = content.getParent();
        int guard = 0;
        while (p instanceof View && guard++ < 6) {
            if (p == scroll) return true;
            p = ((View) p).getParent();
        }
        return false;
    }

    /** {@code ancestor} 是否是 content 的祖先（含 ReactRootView）。 */
    private static boolean isAncestorOf(ViewGroup ancestor, View descendant) {
        if (ancestor == null || descendant == null) return false;
        ViewParent p = descendant.getParent();
        int guard = 0;
        while (p instanceof View && guard++ < 16) {
            if (p == ancestor) return true;
            p = ((View) p).getParent();
        }
        return false;
    }

    /**
     * @return 应加到事件 Y 上的增量（通常为 -translationY），使触点落到 Yoga 框内
     */
    private static float remapDeltaY(ViewGroup content, ViewGroup eventHost, MotionEvent ev) {
        float x;
        float y;
        try {
            x = ev.getX();
            y = ev.getY();
        } catch (Throwable t) {
            return 0f;
        }
        // 事件坐标在 eventHost 空间；若 host 是 ScrollView，先换到 content 空间
        float cx = x;
        float cy = y;
        if (eventHost != content) {
            int[] hl = new int[2];
            int[] cl = new int[2];
            try {
                eventHost.getLocationOnScreen(hl);
                content.getLocationOnScreen(cl);
                cx = x + hl[0] - cl[0];
                cy = y + hl[1] - cl[1];
            } catch (Throwable t) {
                return 0f;
            }
        }

        int n = content.getChildCount();
        View hit = null;
        float hitTy = 0f;
        // 后画的在上：从后往前找视觉命中
        for (int i = n - 1; i >= 0; i--) {
            View child = content.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;
            Float tyObj = sTy.get(child);
            float ty = tyObj != null ? tyObj : child.getTranslationY();
            float left = child.getLeft();
            float top = child.getTop() + ty;
            float right = child.getRight();
            float bottom = top + child.getHeight();
            if (cx >= left && cx < right && cy >= top && cy < bottom) {
                hit = child;
                hitTy = ty;
                break;
            }
        }
        if (hit == null || Math.abs(hitTy) < 0.5f) return 0f;
        // 视觉 Y = Yoga Y + ty  ⇒ 要把事件 Y 减 ty 才落到 Yoga 框
        return -hitTy;
    }

    private static Object afterAddView(XposedInterface.Chain chain) throws Throwable {
        Object r = chain.proceed();
        if (Boolean.TRUE.equals(SKIP.get()) || !sOn) return r;
        try {
            Object thiz = chain.getThisObject();
            if (thiz instanceof ViewGroup && thiz == homeContentOrNull()) {
                schedule();
            }
        } catch (Throwable ignored) {
        }
        return r;
    }

    private static Object afterOnLayout(XposedInterface.Chain chain) throws Throwable {
        Object r = chain.proceed();
        if (Boolean.TRUE.equals(SKIP.get()) || !sOn) return r;
        Object thiz;
        try {
            thiz = chain.getThisObject();
        } catch (Throwable t) {
            return r;
        }
        if (!(thiz instanceof ViewGroup)) return r;
        ViewGroup g = (ViewGroup) thiz;
        if (g != homeContentOrNull()) return r;
        if (sScrolling) return r;
        try {
            int n = applyTransforms(g, RemoteConfig.get());
            maybeLog("onLayout pack n=" + n + " kids=" + g.getChildCount());
        } catch (Throwable t) {
            XposedCompat.log(TAG + " afterOnLayout failed: " + t);
        }
        return r;
    }

    private static void schedule() {
        if (sPending) return;
        sPending = true;
        MAIN.postDelayed(() -> {
            sPending = false;
            if (!sOn) return;
            try {
                applyNow();
            } catch (Throwable t) {
                XposedCompat.log(TAG + " apply failed: " + t);
            }
        }, DEBOUNCE_MS);
    }

    private static void applyNow() {
        MoriConfig cfg = RemoteConfig.get();
        if (cfg == null || !cfg.f5Enabled) return;
        cfg.ensureF5Defaults();
        refreshLoginFlag();
        if (!isLoggedIn()) {
            maybeLog("applyNow skip: not logged in");
            return;
        }

        int applied = 0;
        sPackedSync.clear();
        for (View root : windowRoots()) {
            if (!looksLikeLoggedInShell(root) && !looksLikeHomeModules(root)) continue;
            ViewGroup content = findHomeScrollContent(root);
            if (content == null || scoreDirectModules(content) < 2) continue;
            sHomeContent = new WeakReference<>(content);
            try {
                int n = applyTransforms(content, cfg);
                applied++;
                maybeLog("applyNow pack n=" + n + " kids=" + content.getChildCount());
            } catch (Throwable t) {
                XposedCompat.log(TAG + " applyNow failed: " + t);
            }
            ensureGlobalLayoutHook(content);
            applied += applySyncOutside(root, content, cfg);
        }
        maybeLog("applyNow roots=" + applied + " loggedIn=" + isLoggedIn());
    }

    private static final WeakHashMap<ViewGroup, Boolean> sGlobalHooked = new WeakHashMap<>();

    private static void ensureGlobalLayoutHook(ViewGroup content) {
        if (content == null) return;
        try {
            if (Boolean.TRUE.equals(sGlobalHooked.get(content))) return;
            sGlobalHooked.put(content, Boolean.TRUE);
            content.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (!sOn || sScrolling) return;
                if (homeContentOrNull() != content) return;
                long now = System.currentTimeMillis();
                if (now - sLastGlobalPackAt < GLOBAL_PACK_MIN_MS) return;
                sLastGlobalPackAt = now;
                try {
                    applyTransforms(content, RemoteConfig.get());
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static ViewGroup homeContentOrNull() {
        return sHomeContent == null ? null : sHomeContent.get();
    }

    private static int applyTransforms(ViewGroup content, MoriConfig cfg) {
        if (content == null || cfg == null || !cfg.f5Enabled) return 0;
        cfg.ensureF5Defaults();

        // 恢复默认后的短窗口：只清视觉，不 pack
        if (System.currentTimeMillis() < sSettleUntil) {
            clearPackOnContent(content);
            return 0;
        }

        int n = content.getChildCount();
        if (n < 2) return 0;

        List<View> snapshot = new ArrayList<>(n);
        Map<View, String> viewToId = new HashMap<>();
        for (int i = 0; i < n; i++) {
            View child = content.getChildAt(i);
            if (child == null) continue;
            snapshot.add(child);
            String id = classifySection(child);
            if (id != null) viewToId.put(child, id);
        }
        associateOrphansForwardOnly(snapshot, viewToId);

        Map<String, List<View>> idToViews = new LinkedHashMap<>();
        for (View child : snapshot) {
            String id = viewToId.get(child);
            if (id == null) continue;
            List<View> list = idToViews.get(id);
            if (list == null) {
                list = new ArrayList<>();
                idToViews.put(id, list);
            }
            list.add(child);
        }
        if (idToViews.size() < 2) return 0;

        // 几何：用布局 top（不受 translationY 影响）
        Map<View, float[]> geo = new HashMap<>();
        for (View v : snapshot) {
            if (!viewToId.containsKey(v)) continue;
            float top = v.getTop();
            float h = Math.max(v.getHeight(), v.getMeasuredHeight());
            Object tag = null;
            try {
                tag = v.getTag(TAG_H);
            } catch (Throwable ignored) {
            }
            if (h > 1f) {
                try {
                    v.setTag(TAG_H, new int[]{Math.round(top), Math.round(h)});
                } catch (Throwable ignored) {
                }
            } else if (tag instanceof int[]) {
                int[] th = (int[]) tag;
                if (th.length >= 2) {
                    if (top <= 0f) top = th[0];
                    h = Math.max(h, th[1]);
                }
            }
            geo.put(v, new float[]{top, Math.max(h, 1f)});
        }

        Map<String, float[]> span = new LinkedHashMap<>();
        for (Map.Entry<String, List<View>> e : idToViews.entrySet()) {
            float minT = Float.MAX_VALUE;
            float maxB = Float.MIN_VALUE;
            boolean any = false;
            for (View v : e.getValue()) {
                float[] g = geo.get(v);
                if (g == null) continue;
                any = true;
                minT = Math.min(minT, g[0]);
                maxB = Math.max(maxB, g[0] + g[1]);
            }
            if (any) span.put(e.getKey(), new float[]{minT, Math.max(0f, maxB - minT)});
        }

        float gap = estimateGap(cfg, span);
        float anchor = Float.MAX_VALUE;
        for (float[] s : span.values()) {
            anchor = Math.min(anchor, s[0]);
        }
        if (anchor == Float.MAX_VALUE) anchor = content.getPaddingTop();

        boolean anyHidden = false;
        for (MoriConfig.F5Entry e : cfg.f5Modules) {
            if (e != null && !e.visible && idToViews.containsKey(e.id)) {
                anyHidden = true;
                break;
            }
        }

        List<String> byTop = new ArrayList<>(span.keySet());
        byTop.sort((a, b) -> Float.compare(span.get(a)[0], span.get(b)[0]));
        List<String> wantVisible = new ArrayList<>();
        for (MoriConfig.F5Entry e : cfg.f5Modules) {
            if (e == null || !e.visible) continue;
            if (span.containsKey(e.id)) wantVisible.add(e.id);
        }
        boolean orderMatches = wantVisible.equals(byTop);
        // Yoga 不塌缩隐藏模块 ⇒ 有隐藏时即使顺序一致也必须上移收拢
        boolean needPack = anyHidden || !orderMatches;

        SKIP.set(Boolean.TRUE);
        int packed = 0;
        float y = anchor;
        float contentBottom = anchor;
        sForceInvisible.clear();
        try {
            // 先处理隐藏
            for (MoriConfig.F5Entry e : cfg.f5Modules) {
                List<View> group = idToViews.get(e.id);
                if (group == null) continue;
                if (!e.visible) {
                    for (View v : group) {
                        remember(v);
                        sForceInvisible.put(v, Boolean.TRUE);
                        sTy.remove(v);
                        try {
                            if (Math.abs(v.getTranslationY()) > 0.5f) {
                                v.setTranslationY(0f);
                            }
                            if (v.getVisibility() != View.INVISIBLE) {
                                v.setVisibility(View.INVISIBLE);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }

            if (!needPack) {
                for (String id : wantVisible) {
                    packed++;
                    float[] sp = span.get(id);
                    if (sp != null) {
                        contentBottom = Math.max(contentBottom, sp[0] + sp[1]);
                    }
                    List<View> group = idToViews.get(id);
                    if (group == null) continue;
                    for (View v : group) {
                        remember(v);
                        sForceInvisible.remove(v);
                        sTy.remove(v);
                        try {
                            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
                            if (Math.abs(v.getTranslationY()) > 0.5f) {
                                v.setTranslationY(0f);
                            }
                        } catch (Throwable ignored) {
                        }
                        if ("sync".equals(id)) sPackedSync.add(v);
                    }
                }
            } else {
                for (MoriConfig.F5Entry e : cfg.f5Modules) {
                    if (e == null || !e.visible) continue;
                    List<View> group = idToViews.get(e.id);
                    if (group == null || group.isEmpty()) continue;
                    float[] sp = span.get(e.id);
                    if (sp == null) continue;
                    packed++;
                    float base = sp[0];
                    float modH = sp[1];
                    for (View v : group) {
                        remember(v);
                        sForceInvisible.remove(v);
                        float[] g = geo.get(v);
                        float vTop = g != null ? g[0] : v.getTop();
                        float rel = vTop - base;
                        float wantTop = y + rel;
                        float ty = wantTop - vTop;
                        try {
                            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
                            // 已到位则不写，减少滚动中 layout↔translation 抖振
                            if (Math.abs(v.getTranslationY() - ty) > 0.5f) {
                                v.setTranslationY(ty);
                            }
                            sTy.put(v, ty);
                        } catch (Throwable ignored) {
                        }
                        float vh = g != null ? g[1] : Math.max(v.getHeight(), 0);
                        contentBottom = Math.max(contentBottom, wantTop + vh);
                        if ("sync".equals(e.id)) sPackedSync.add(v);
                    }
                    y += modH + gap;
                }
            }

            // 缩滚动范围，去掉 Yoga 留给隐藏模块的尾部空洞
            int wantH = Math.round(contentBottom + content.getPaddingBottom() + 8f);
            if (wantH > 0) {
                int curH = Math.max(content.getMeasuredHeight(), content.getHeight());
                if (Math.abs(curH - wantH) > 2) {
                    forceMeasuredHeight(content, wantH);
                }
            }
        } finally {
            SKIP.set(Boolean.FALSE);
        }
        return packed;
    }

    private static void clearPackOnContent(ViewGroup content) {
        if (content == null) return;
        SKIP.set(Boolean.TRUE);
        try {
            int n = content.getChildCount();
            for (int i = 0; i < n; i++) {
                View c = content.getChildAt(i);
                if (c == null) continue;
                sTy.remove(c);
                sForceInvisible.remove(c);
                try {
                    c.setTranslationY(0f);
                    if (c.getVisibility() != View.VISIBLE) c.setVisibility(View.VISIBLE);
                    c.setTag(TAG_H, null);
                } catch (Throwable ignored) {
                }
            }
        } finally {
            SKIP.set(Boolean.FALSE);
        }
    }

    private static void clearPackVisuals() {
        ViewGroup g = homeContentOrNull();
        if (g != null) clearPackOnContent(g);
        for (WeakReference<View> wr : sTouched) {
            View v = wr == null ? null : wr.get();
            if (v == null) continue;
            sTy.remove(v);
            sForceInvisible.remove(v);
            try {
                v.setTranslationY(0f);
                if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
                v.setTag(TAG_H, null);
            } catch (Throwable ignored) {
            }
        }
    }

    private static float estimateGap(MoriConfig cfg, Map<String, float[]> span) {
        List<float[]> list = new ArrayList<>();
        for (MoriConfig.F5Entry e : cfg.f5Modules) {
            if (e == null || !e.visible) continue;
            float[] s = span.get(e.id);
            if (s != null) list.add(new float[]{s[0], s[0] + s[1]});
        }
        list.sort((a, b) -> Float.compare(a[0], b[0]));
        float sum = 0f;
        int cnt = 0;
        for (int i = 0; i + 1 < list.size(); i++) {
            float g = list.get(i + 1)[0] - list.get(i)[1];
            if (g >= 0f && g < 240f) {
                sum += g;
                cnt++;
            }
        }
        if (cnt > 0) return sum / cnt;
        return 24f;
    }

    private static void associateOrphansForwardOnly(List<View> snapshot,
                                                   Map<View, String> viewToId) {
        String current = null;
        for (View v : snapshot) {
            String id = viewToId.get(v);
            if (id != null) {
                current = id;
            } else if (current != null) {
                viewToId.put(v, current);
            }
        }
    }

    private static void forceMeasuredHeight(View v, int height) {
        if (v == null || height < 0) return;
        try {
            Method m = sSetMeasuredDimension;
            if (m == null) {
                m = View.class.getDeclaredMethod("setMeasuredDimension", int.class, int.class);
                m.setAccessible(true);
                sSetMeasuredDimension = m;
            }
            int w = v.getMeasuredWidth();
            if (w <= 0) w = v.getWidth();
            m.invoke(v, w, height);
        } catch (Throwable t) {
            XposedCompat.log(TAG + " setMeasuredDimension failed: " + t);
        }
    }

    private static int applySyncOutside(View root, ViewGroup content, MoriConfig cfg) {
        MoriConfig.F5Entry e = cfg.findF5("sync");
        if (e == null) return 0;
        List<View> hits = new ArrayList<>();
        int[] c = {0};
        walk(root, c, 0, v -> {
            String id = viewIdName(v);
            if ("sync-button".equals(id)) {
                View rootish = climbForSyncCluster(v);
                if (rootish != null && !hits.contains(rootish)) hits.add(rootish);
                return false;
            }
            if (v instanceof TextView) {
                CharSequence t = ((TextView) v).getText();
                if (t != null && "sync".equals(textToModule(t.toString()))) {
                    String raw = t.toString();
                    if (raw.startsWith("最后同步") || raw.startsWith("最後同步")
                            || raw.startsWith("最終更新") || raw.contains("에 동기화")
                            || raw.toLowerCase(Locale.ROOT).startsWith("synchronized:")) {
                        return false;
                    }
                    View rootish = climbForSyncCluster(v);
                    if (rootish != null && !hits.contains(rootish)) hits.add(rootish);
                }
            }
            return false;
        });

        float packBottom = -1f;
        if (content != null && e.visible) {
            try {
                int[] loc = new int[2];
                content.getLocationInWindow(loc);
                float y = content.getPaddingTop();
                float gap = 24f;
                Map<String, Float> modH = new HashMap<>();
                int n = content.getChildCount();
                for (int i = 0; i < n; i++) {
                    View ch = content.getChildAt(i);
                    if (ch == null || ch.getVisibility() != View.VISIBLE) continue;
                    String id = classifySection(ch);
                    if (id == null) continue;
                    float h = Math.max(ch.getHeight(), ch.getMeasuredHeight());
                    Float prev = modH.get(id);
                    modH.put(id, prev == null ? h : Math.max(prev, h));
                }
                for (MoriConfig.F5Entry fe : cfg.f5Modules) {
                    if (fe == null || !fe.visible) continue;
                    if ("sync".equals(fe.id)) break;
                    Float h = modH.get(fe.id);
                    if (h != null) y += h + gap;
                }
                packBottom = loc[1] + y;
            } catch (Throwable ignored) {
            }
        }

        int n = 0;
        SKIP.set(Boolean.TRUE);
        try {
            for (View v : hits) {
                if (v == null) continue;
                if (sPackedSync.contains(v) || isDescendant(v, content)) continue;
                remember(v);
                if (!e.visible) {
                    sForceInvisible.put(v, Boolean.TRUE);
                    if (v.getVisibility() != View.INVISIBLE) {
                        v.setVisibility(View.INVISIBLE);
                        n++;
                    }
                    sTy.remove(v);
                    v.setTranslationY(0f);
                    continue;
                }
                sForceInvisible.remove(v);
                if (v.getVisibility() != View.VISIBLE) {
                    v.setVisibility(View.VISIBLE);
                    n++;
                }
                if (packBottom >= 0f) {
                    try {
                        int[] vl = new int[2];
                        v.getLocationInWindow(vl);
                        float natural = vl[1] - v.getTranslationY();
                        float ty = packBottom - natural;
                        v.setTranslationY(ty);
                        sTy.put(v, ty);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } finally {
            SKIP.set(Boolean.FALSE);
        }
        return n;
    }

    private static boolean isDescendant(View v, ViewGroup ancestor) {
        if (v == null || ancestor == null) return false;
        View cur = v;
        int guard = 0;
        while (cur != null && guard++ < 48) {
            if (cur == ancestor) return true;
            Object p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return false;
    }

    private static View climbForSyncCluster(View v) {
        View cur = v;
        for (int i = 0; i < 4 && cur != null; i++) {
            Object p = cur.getParent();
            if (!(p instanceof ViewGroup)) break;
            ViewGroup g = (ViewGroup) p;
            try {
                if (g.getHeight() > 0 && g.getWidth() > 0
                        && g.getHeight() > g.getResources().getDisplayMetrics().heightPixels / 3) {
                    break;
                }
            } catch (Throwable ignored) {
                break;
            }
            cur = g;
        }
        return cur;
    }

    private static void restoreAllNow() {
        beginSettle();
        SKIP.set(Boolean.TRUE);
        try {
            sForceInvisible.clear();
            sTy.clear();
            sPackedSync.clear();
            for (WeakReference<View> wr : sTouched) {
                View v = wr == null ? null : wr.get();
                if (v == null) continue;
                try {
                    v.setTranslationY(0f);
                    v.setTag(TAG_H, null);
                    if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
                } catch (Throwable ignored) {
                }
            }
            sTouched.clear();
            ViewGroup g = homeContentOrNull();
            if (g != null) {
                clearPackOnContent(g);
                requestLayoutChain(g);
            } else {
                for (View root : windowRoots()) {
                    ViewGroup content = findHomeScrollContent(root);
                    if (content != null) {
                        clearPackOnContent(content);
                        requestLayoutChain(content);
                    }
                }
            }
        } finally {
            SKIP.set(Boolean.FALSE);
            sHomeContent = null;
        }
    }

    private static void requestLayoutChain(View v) {
        if (v == null) return;
        try {
            v.requestLayout();
            v.invalidate();
        } catch (Throwable ignored) {
        }
        ViewParent p = v.getParent();
        int guard = 0;
        while (p instanceof View && guard++ < 8) {
            View pv = (View) p;
            try {
                pv.requestLayout();
                pv.invalidate();
            } catch (Throwable ignored) {
            }
            p = pv.getParent();
        }
    }

    private static void remember(View v) {
        for (WeakReference<View> wr : sTouched) {
            if (wr != null && wr.get() == v) return;
        }
        sTouched.add(new WeakReference<>(v));
        while (sTouched.size() > 80) sTouched.remove(0);
    }

    private static void maybeLog(String msg) {
        long now = System.currentTimeMillis();
        if (now - sLastLogAt < LOG_EVERY_MS) return;
        sLastLogAt = now;
        XposedCompat.log(TAG + " " + msg);
    }

    private static void refreshLoginFlag() {
        boolean sawLoginTab = false;
        boolean sawGuest = false;
        boolean sawHomeMods = false;
        for (View root : windowRoots()) {
            int[] c = {0};
            final boolean[] login = {false};
            final boolean[] guest = {false};
            final boolean[] home = {false};
            walk(root, c, 0, v -> {
                String id = viewIdName(v);
                if ("tab-home".equals(id) || "tab-library".equals(id)
                        || "tab-playlist".equals(id) || "tab-setting".equals(id)) {
                    login[0] = true;
                }
                CharSequence desc = null;
                try {
                    desc = v.getContentDescription();
                } catch (Throwable ignored) {
                }
                if (desc != null) {
                    String d = desc.toString();
                    if (d.contains("根据播放记录推荐") || d.contains("根據播放記錄推薦")
                            || d.contains("Based on your listening")) {
                        guest[0] = true;
                    }
                }
                if (v instanceof TextView) {
                    CharSequence t = ((TextView) v).getText();
                    if (t != null && textToModule(t.toString()) != null) home[0] = true;
                }
                return login[0] && (guest[0] || home[0]);
            });
            if (login[0]) sawLoginTab = true;
            if (guest[0] && !login[0]) sawGuest = true;
            if (home[0]) sawHomeMods = true;
        }
        if (sawLoginTab || sawHomeMods) {
            sLoggedIn = true;
            sLoginSticky = true;
        } else if (sawGuest && !sLoginSticky) {
            sLoggedIn = false;
        }
    }

    private static boolean looksLikeLoggedInShell(View root) {
        int[] c = {0};
        boolean[] hit = {false};
        walk(root, c, 0, v -> {
            String id = viewIdName(v);
            if ("tab-home".equals(id) || "tab-library".equals(id)) {
                hit[0] = true;
                return true;
            }
            return false;
        });
        return hit[0];
    }

    private static boolean looksLikeHomeModules(View root) {
        int[] c = {0};
        final Set<String> hit = new HashSet<>();
        walk(root, c, 0, v -> {
            if (v instanceof TextView) {
                CharSequence t = ((TextView) v).getText();
                if (t != null) {
                    String mod = textToModule(t.toString());
                    if (mod != null) hit.add(mod);
                }
            }
            return hit.size() >= 2;
        });
        return hit.size() >= 2;
    }

    private static ViewGroup findHomeScrollContent(View root) {
        ViewGroup best = null;
        int bestScore = 0;
        int[] c = {0};
        List<ViewGroup> candidates = new ArrayList<>();
        walk(root, c, 0, v -> {
            if (!(v instanceof ViewGroup)) return false;
            String cn = v.getClass().getName();
            boolean scrollish = v instanceof ScrollView
                    || cn.contains("ReactScrollView")
                    || cn.contains("ReactNestedScrollView");
            if (!scrollish || v instanceof HorizontalScrollView) return false;
            ViewGroup g = (ViewGroup) v;
            if (g.getChildCount() == 1 && g.getChildAt(0) instanceof ViewGroup) {
                ViewGroup inner = (ViewGroup) g.getChildAt(0);
                if (inner.getChildCount() >= 3) candidates.add(inner);
            } else if (g.getChildCount() >= 3) {
                candidates.add(g);
            }
            return false;
        });
        for (ViewGroup cand : candidates) {
            int s = scoreDirectModules(cand);
            if (s > bestScore) {
                bestScore = s;
                best = cand;
            }
        }
        return bestScore >= 2 ? best : null;
    }

    private static int scoreDirectModules(ViewGroup g) {
        if (g == null) return 0;
        Set<String> hit = new HashSet<>();
        int n = g.getChildCount();
        for (int i = 0; i < n; i++) {
            View child = g.getChildAt(i);
            if (child == null) continue;
            String id = classifySection(child);
            if (id != null) hit.add(id);
        }
        return hit.size();
    }

    private static String classifySection(View section) {
        int[] c = {0};
        final String[] found = {null};
        final int[] prio = {0};
        walk(section, c, 0, v -> {
            String id = viewIdName(v);
            if (id != null) {
                String mod = idToModule(id);
                if (mod != null && prio[0] < 3) {
                    found[0] = mod;
                    prio[0] = 3;
                }
            }
            try {
                CharSequence desc = v.getContentDescription();
                if (desc != null && prio[0] < 2) {
                    String mod = textToModule(desc.toString());
                    if (mod != null) {
                        found[0] = mod;
                        prio[0] = 2;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (v instanceof TextView && prio[0] < 1) {
                CharSequence t = ((TextView) v).getText();
                if (t != null) {
                    String mod = textToModule(t.toString());
                    if (mod != null) {
                        found[0] = mod;
                        prio[0] = 1;
                    }
                }
            }
            return prio[0] >= 3;
        });
        return found[0];
    }

    private static String idToModule(String id) {
        if (id == null) return null;
        if (id.startsWith("genre-item") || "genre-shuffle-button".equals(id)) return "genre";
        if (id.startsWith("recent-purchase") || "recent-purchase-section".equals(id)
                || "recent-purchase-see-all".equals(id)) {
            return "recent_purchase";
        }
        if (id.startsWith("recent-played") || "recent-played-section".equals(id)) {
            return "recent_played";
        }
        if (id.startsWith("my-sound") || "my-sound-section".equals(id)) return "my_sound";
        if ("sync-button".equals(id)) return "sync";
        if ("playback-list".equals(id)) return "recap";
        return null;
    }

    private static String textToModule(String s) {
        if (s == null || s.isEmpty()) return null;
        String lower = s.toLowerCase(Locale.ROOT);
        if ("类型".equals(s) || "類型".equals(s) || "ジャンル".equals(s) || "장르".equals(s)
                || "genres".equals(lower) || "genre".equals(lower)) {
            return "genre";
        }
        if ("最近购买".equals(s) || "最近購買".equals(s) || "最近購入".equals(s)
                || "最近の購入".equals(s) || "최근 구입".equals(s)
                || "recently purchased".equals(lower)
                || s.contains("最近購入") || s.contains("最近购买") || s.contains("最近購買")) {
            return "recent_purchase";
        }
        if (s.contains("最近听过") || s.contains("最近聽過") || s.contains("最近聴いた")
                || s.contains("최근 들은") || lower.contains("recently listened")
                || "最近聴いた作品".equals(s)) {
            return "recent_played";
        }
        if ("稍后再听".equals(s) || "稍後再聽".equals(s) || "あとで聴く".equals(s)
                || "나중에 듣기".equals(s) || "listen later".equals(lower)) {
            return "my_sound";
        }
        if ("回顾".equals(s) || "回顧".equals(s) || "プレイバック".equals(s)
                || "playback".equals(lower) || "PlayBack".equals(s) || "회고".equals(s)) {
            return "recap";
        }
        if (s.contains("你听了") || s.contains("你聽了") || s.contains("月度回顾")
                || s.contains("月度回顧") || (s.contains("聴いた") && s.contains("月"))) {
            return "recap";
        }
        if ("同步媒体库".equals(s) || "同步媒體庫".equals(s) || "ライブラリを同期".equals(s)
                || "라이브러리 동기화".equals(s) || "synchronize library".equals(lower)
                || "同步已购买的作品".equals(s) || "同步已購買的作品".equals(s)
                || "購入した作品を同期".equals(s) || "구입한 작품 동기화".equals(s)
                || "sync purchased works".equals(lower)
                || s.startsWith("最后同步") || s.startsWith("最後同步")
                || s.startsWith("最終更新") || s.contains("에 동기화")
                || lower.startsWith("synchronized:")) {
            return "sync";
        }
        return null;
    }

    private static String viewIdName(View v) {
        if (v == null) return null;
        try {
            android.view.accessibility.AccessibilityNodeInfo n = v.createAccessibilityNodeInfo();
            if (n != null) {
                String name = n.getViewIdResourceName();
                if (name != null && !name.isEmpty()) {
                    int slash = name.lastIndexOf('/');
                    return slash >= 0 ? name.substring(slash + 1) : name;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            int tagId = nativeIdTag(v);
            if (tagId != 0) {
                Object tag = v.getTag(tagId);
                if (tag instanceof String) {
                    String s = (String) tag;
                    if (!s.isEmpty()) return s;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                String name = v.getResources().getResourceEntryName(id);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static volatile int sNativeIdTag = -2;

    private static int nativeIdTag(View v) {
        int t = sNativeIdTag;
        if (t != -2) return t;
        int got = 0;
        try {
            got = v.getResources()
                    .getIdentifier("view_tag_native_id", "id", v.getContext().getPackageName());
        } catch (Throwable ignored) {
        }
        sNativeIdTag = got;
        return got;
    }

    private interface Visitor {
        boolean visit(View v);
    }

    private static void walk(View v, int[] count, int depth, Visitor vis) {
        if (v == null || count[0] >= MAX_WALK || depth > 48) return;
        count[0]++;
        if (vis.visit(v)) return;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int nc = g.getChildCount();
            for (int i = 0; i < nc && count[0] < MAX_WALK; i++) {
                walk(g.getChildAt(i), count, depth + 1, vis);
            }
        }
    }

    private static View[] windowRoots() {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field f = wmg.getDeclaredField("mViews");
            f.setAccessible(true);
            Object v = f.get(inst);
            if (v instanceof List) {
                List<?> list = (List<?>) v;
                List<View> out = new ArrayList<>(list.size());
                for (Object o : list) {
                    if (o instanceof View) out.add((View) o);
                }
                return out.toArray(new View[0]);
            }
        } catch (Throwable ignored) {
        }
        return new View[0];
    }
}
