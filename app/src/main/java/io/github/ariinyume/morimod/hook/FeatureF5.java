package io.github.ariinyume.morimod.hook;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.ariinyume.morimod.config.MoriConfig;
import io.github.ariinyume.morimod.config.RemoteConfig;
import io.github.ariinyume.morimod.util.XposedCompat;
import io.github.libxposed.api.XposedInterface;

/**
 * F5 · 宿主主页区块显隐与排序（路线 2）。
 *
 * <p>登录态主页上，标题行与横向封面轨经常是 ScrollView 下的<strong>兄弟节点</strong>。
 * 因此每个模块对应<strong>一组</strong>直接子 View（全部 GONE / 整组换序），
 * 不能只藏第一次命中的那一个。
 *
 * <p>无 {@code home-hero*}；目录见 {@link MoriConfig#F5_DEFAULT_IDS}。
 */
public final class FeatureF5 {

    private static final String TAG = "[MoriMod:F5]";

    private static final int MAX_WALK = 900;
    private static final long DEBOUNCE_MS = 60L;

    private static volatile boolean sOn = false;
    private static volatile boolean sHooked = false;

    /**
     * 宿主是否处于登录壳（见过 {@code tab-home}/{@code tab-library}）。
     * 访客首页强制不改布局；设置页靠心跳里的同名字段闸开关。
     */
    private static volatile boolean sLoggedIn = false;

    private static final ThreadLocal<Boolean> SKIP =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean sPending;

    private static final List<WeakReference<View>> sTouched = new ArrayList<>();

    private FeatureF5() {
    }

    public static void install(ClassLoader cl) {
        RemoteConfig.addListener(FeatureF5::onConfigChanged);
        apply(RemoteConfig.get());
    }

    /** 供心跳上报；未探测到过则 false。 */
    public static boolean isLoggedIn() {
        return sLoggedIn;
    }

    /** 心跳前探测登录壳（不依赖 F5 开关）。 */
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
                MAIN.post(FeatureF5::restoreTouched);
                XposedCompat.log(TAG + " off");
            }
            return;
        }
        if (!sHooked && !hookAll()) {
            XposedCompat.log(TAG + " hooks missing, skip");
            return;
        }
        if (!sOn) {
            sOn = true;
            XposedCompat.log(TAG + " on (route2 view-tree)");
        }
        schedule();
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
        boolean okVis = XposedCompat.hookMethod(
                View.class, "setVisibility",
                new Class<?>[]{int.class},
                FeatureF5::afterSetVisibility, TAG);
        if (!okAdd && !okVis) return false;
        sHooked = true;
        XposedCompat.log(TAG + " hooked addView=" + okAdd + " setVisibility=" + okVis);
        return true;
    }

    private static Object afterAddView(XposedInterface.Chain chain) throws Throwable {
        Object r = chain.proceed();
        if (Boolean.TRUE.equals(SKIP.get()) || !sOn) return r;
        schedule();
        return r;
    }

    private static Object afterSetVisibility(XposedInterface.Chain chain) throws Throwable {
        Object r = chain.proceed();
        if (Boolean.TRUE.equals(SKIP.get()) || !sOn) return r;
        schedule();
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
        if (!sLoggedIn) {
            // 访客：不改布局；若此前藏过则还原
            restoreTouched();
            return;
        }

        int applied = 0;
        for (View root : windowRoots()) {
            if (!looksLikeLoggedInShell(root)) continue;
            ViewGroup content = findHomeScrollContent(root);
            if (content == null) continue;
            applied += applyOnContent(content, cfg);
            applied += applySyncOutside(root, cfg);
        }
        if (cfg.debugLog) {
            XposedCompat.log(TAG + " applied=" + applied + " loggedIn=" + sLoggedIn);
        }
    }

    private static void refreshLoginFlag() {
        boolean sawLoginTab = false;
        boolean sawGuest = false;
        for (View root : windowRoots()) {
            int[] c = {0};
            walk(root, c, 0, v -> {
                String id = viewIdName(v);
                if ("tab-home".equals(id) || "tab-library".equals(id)
                        || "tab-playlist".equals(id) || "tab-setting".equals(id)) {
                    // 闭包不能写外部非 final；用数组
                }
                return false;
            });
            c[0] = 0;
            final boolean[] login = {false};
            final boolean[] guest = {false};
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
                return login[0] && guest[0];
            });
            if (login[0]) sawLoginTab = true;
            if (guest[0] && !login[0]) sawGuest = true;
        }
        if (sawLoginTab) sLoggedIn = true;
        else if (sawGuest) sLoggedIn = false;
    }

    private static void restoreTouched() {
        SKIP.set(Boolean.TRUE);
        try {
            for (WeakReference<View> wr : sTouched) {
                View v = wr == null ? null : wr.get();
                if (v == null) continue;
                try {
                    if (v.getVisibility() == View.GONE) v.setVisibility(View.VISIBLE);
                } catch (Throwable ignored) {
                }
            }
            sTouched.clear();
        } finally {
            SKIP.set(Boolean.FALSE);
        }
    }

    /**
     * 对 ScrollView 内容：每个直接子节点归类到模块（可多节点同模块），
     * 显隐整组；按模块组换序。
     */
    private static int applyOnContent(ViewGroup content, MoriConfig cfg) {
        int n = content.getChildCount();
        if (n <= 0) return 0;

        List<View> snapshot = new ArrayList<>(n);
        Map<View, String> viewToId = new HashMap<>();

        for (int i = 0; i < n; i++) {
            View child = content.getChildAt(i);
            if (child == null) continue;
            snapshot.add(child);
            String id = classifySection(child);
            if (id != null) viewToId.put(child, id);
        }
        // 标题与横向封面轨 / 间隔常是兄弟节点：未归类子节点并入上一个模块，
        // 避免「只藏标题、封面还在」以及「GONE 后下方不顶上来」。
        associateOrphanSiblings(snapshot, viewToId);

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
        if (idToViews.isEmpty()) return 0;

        int touched = 0;
        for (MoriConfig.F5Entry e : cfg.f5Modules) {
            List<View> group = idToViews.get(e.id);
            if (group == null) continue;
            int want = e.visible ? View.VISIBLE : View.GONE;
            for (View v : group) {
                if (v.getVisibility() != want) {
                    setVis(v, want);
                    touched++;
                }
                remember(v);
            }
        }

        // 排序：按模块组（首次出现占位）重排
        List<String> presentOrder = new ArrayList<>();
        for (View v : snapshot) {
            String id = viewToId.get(v);
            if (id != null && !presentOrder.contains(id)) presentOrder.add(id);
        }
        List<String> wantOrder = new ArrayList<>();
        for (MoriConfig.F5Entry e : cfg.f5Modules) {
            if (idToViews.containsKey(e.id)) wantOrder.add(e.id);
        }
        if (wantOrder.size() >= 2 && !wantOrder.equals(presentOrder)) {
            if (reorderGroups(content, snapshot, viewToId, idToViews, wantOrder)) {
                touched += wantOrder.size();
            }
        }
        try {
            content.requestLayout();
            content.invalidate();
        } catch (Throwable ignored) {
        }
        return touched;
    }

    /**
     * 把未识别的直接子 View 挂到相邻模块：先跟「上一个已识别模块」，
     * 开头的孤儿再挂到「下一个模块」。这样封面轨、空白间隔与标题同组。
     */
    private static void associateOrphanSiblings(List<View> snapshot,
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
        String next = null;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            View v = snapshot.get(i);
            String id = viewToId.get(v);
            if (id != null) {
                next = id;
            } else if (next != null) {
                viewToId.put(v, next);
            }
        }
    }

    /** 同步条可能在 ScrollView 外；单独按 id/文案藏。 */
    private static int applySyncOutside(View root, MoriConfig cfg) {
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
                if (t != null && textToModule(t.toString()) != null
                        && "sync".equals(textToModule(t.toString()))) {
                    View rootish = climbForSyncCluster(v);
                    if (rootish != null && !hits.contains(rootish)) hits.add(rootish);
                }
            }
            return false;
        });
        int want = e.visible ? View.VISIBLE : View.GONE;
        int n = 0;
        for (View v : hits) {
            if (v.getVisibility() != want) {
                setVis(v, want);
                n++;
            }
            remember(v);
        }
        return n;
    }

    /** 同步按钮 + 下方「最后同步于」尽量一起藏：上溯几层小容器。 */
    private static View climbForSyncCluster(View v) {
        View cur = v;
        for (int i = 0; i < 4 && cur != null; i++) {
            Object p = cur.getParent();
            if (!(p instanceof ViewGroup)) break;
            ViewGroup g = (ViewGroup) p;
            // 太高（接近全屏）就停
            if (g.getHeight() > 0 && g.getWidth() > 0
                    && g.getHeight() > g.getResources().getDisplayMetrics().heightPixels / 3) {
                break;
            }
            cur = g;
        }
        return cur;
    }

    private static boolean reorderGroups(ViewGroup content, List<View> snapshot,
                                         Map<View, String> viewToId,
                                         Map<String, List<View>> idToViews,
                                         List<String> wantOrder) {
        // 槽位：未分类 View 原样；每个模块只在「首次出现」处占一个槽
        List<Object> slots = new ArrayList<>(); // View or String(moduleId)
        Set<String> seen = new HashSet<>();
        for (View v : snapshot) {
            String id = viewToId.get(v);
            if (id == null) {
                slots.add(v);
            } else if (seen.add(id)) {
                slots.add(id);
            }
        }
        // 把模块槽按 wantOrder 重排（只动模块槽，相对非模块槽位置不变）
        List<String> moduleSlots = new ArrayList<>();
        for (Object s : slots) {
            if (s instanceof String) moduleSlots.add((String) s);
        }
        if (moduleSlots.equals(wantOrder)) return false;

        int mi = 0;
        List<View> result = new ArrayList<>(snapshot.size());
        for (Object s : slots) {
            if (s instanceof View) {
                result.add((View) s);
            } else {
                String id = wantOrder.get(mi++);
                List<View> group = idToViews.get(id);
                if (group != null) result.addAll(group);
            }
        }
        SKIP.set(Boolean.TRUE);
        try {
            content.removeAllViews();
            for (View v : result) content.addView(v);
            content.requestLayout();
            return true;
        } catch (Throwable t) {
            XposedCompat.log(TAG + " reorder failed: " + t);
            return false;
        } finally {
            SKIP.set(Boolean.FALSE);
        }
    }

    private static void setVis(View v, int vis) {
        SKIP.set(Boolean.TRUE);
        try {
            v.setVisibility(vis);
            // GONE 后强制重新测量，避免 RN 子树仍占位导致下方模块不上移
            try {
                v.forceLayout();
            } catch (Throwable ignored) {
            }
            Object p = v.getParent();
            if (p instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) p;
                g.forceLayout();
                g.requestLayout();
            } else if (p instanceof View) {
                ((View) p).requestLayout();
            }
        } catch (Throwable ignored) {
        } finally {
            SKIP.set(Boolean.FALSE);
        }
    }

    private static void remember(View v) {
        for (WeakReference<View> wr : sTouched) {
            if (wr != null && wr.get() == v) return;
        }
        sTouched.add(new WeakReference<>(v));
        while (sTouched.size() > 48) sTouched.remove(0);
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
                candidates.add((ViewGroup) g.getChildAt(0));
            } else if (g.getChildCount() > 1) {
                candidates.add(g);
            }
            return false;
        });
        for (ViewGroup cand : candidates) {
            int s = scoreHomeContent(cand);
            if (s > bestScore) {
                bestScore = s;
                best = cand;
            }
        }
        return bestScore > 0 ? best : null;
    }

    private static int scoreHomeContent(ViewGroup g) {
        if (g == null) return 0;
        int[] c = {0};
        final Set<String> hit = new HashSet<>();
        walk(g, c, 0, v -> {
            String mod = null;
            String id = viewIdName(v);
            if (id != null) mod = idToModule(id);
            if (mod == null) {
                try {
                    CharSequence desc = v.getContentDescription();
                    if (desc != null) mod = textToModule(desc.toString());
                } catch (Throwable ignored) {
                }
            }
            if (mod == null && v instanceof TextView) {
                CharSequence t = ((TextView) v).getText();
                if (t != null) mod = textToModule(t.toString());
            }
            if (mod != null) hit.add(mod);
            return hit.size() >= 3;
        });
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

        // genre
        if ("类型".equals(s) || "類型".equals(s) || "ジャンル".equals(s) || "장르".equals(s)
                || "genres".equals(lower) || "genre".equals(lower)) {
            return "genre";
        }
        // recent purchase
        if ("最近购买".equals(s) || "最近購買".equals(s) || "最近の購入".equals(s)
                || "최근 구입".equals(s) || "recently purchased".equals(lower)) {
            return "recent_purchase";
        }
        // recent played
        if (s.contains("最近听过") || s.contains("最近聽過") || s.contains("最近聴いた")
                || s.contains("최근 들은") || lower.contains("recently listened")) {
            return "recent_played";
        }
        // my sound / listen later
        if ("稍后再听".equals(s) || "稍後再聽".equals(s) || "あとで聴く".equals(s)
                || "나중에 듣기".equals(s) || "listen later".equals(lower)) {
            return "my_sound";
        }
        // recap / PlayBack
        if ("回顾".equals(s) || "回顧".equals(s) || "プレイバック".equals(s)
                || "playback".equals(lower) || "PlayBack".equals(s)) {
            return "recap";
        }
        if (s.contains("你听了") || s.contains("你聽了") || s.contains("月度回顾")
                || s.contains("月度回顧") || s.contains("聴いた") && s.contains("月")) {
            return "recap";
        }
        // sync
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
            int n = g.getChildCount();
            for (int i = 0; i < n && count[0] < MAX_WALK; i++) {
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
