package io.github.ariinyume.morimod.hook;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import io.github.ariinyume.morimod.util.XposedCompat;

/**
 * 字体「晚到」之后，把已经画好的文字重新排一次（v0.1.4）。
 *
 * <h3>为什么必须有这个东西</h3>
 * 字体替换是在 {@code Paint#setTypeface} 这一层做的（见 {@code FeatureF4}）。这个方法的调用
 * 时机是「文本要布局的时候」，而 Android 的 {@code TextView} 会把布局结果
 * （{@code Layout} / {@code TextLine}，里面已经算好每个字的宽度和字体度量）缓存住，
 * **之后不再重算**。
 *
 * <p>于是出现一个只在特定顺序下才出现的现象（真机日志 2026-10-23 19:09:47 那次）：
 * <ol>
 *   <li>宿主冷启动，先读到的是本地旧配置 ⇒ 首帧用旧字体画完；</li>
 *   <li>几秒后广播把新配置送到 ⇒ 新字体加载成功（日志有 {@code user font file loaded}）；</li>
 *   <li>但界面已经画好了，没有新的文本布局 ⇒ <b>用户看不到任何变化</b>；</li>
 *   <li>只有切页面/下拉刷新让 RN 重新渲染时，才突然变成新字体。</li>
 * </ol>
 * 用户侧的感受就是「在后台改字体是实时生效的，改完再开 App 却不生效」—— 其实两次都生效了，
 * 差别只在「配置到得早不早」。
 *
 * <h3>做法与克制</h3>
 * 对每个 {@code TextView} 重设一次自己的文本（{@code setText(getText())}）—— 这一步会强制
 * {@code makeNewLayout}，重新走一遍 {@code Paint#setTypeface}，于是新字体连同正确的字宽一起生效。
 * <ul>
 *   <li>只在**基准字体对象真的换了**之后触发一次，不是每次配置变更都跑；</li>
 *   <li>{@code EditText} 一律跳过（重设文本会打断输入法/光标位置）；</li>
 *   <li>延时 300ms、只跑一次、上限 600 个视图，任何异常都吞掉 —— 这只是「让用户看见」的补丁，
 *       绝不能因为它把宿主搞崩。</li>
 * </ul>
 */
public final class UiRefresh {

    private static final String TAG = "[MoriMod:UI]";

    /** 视图上限（超大列表页防御）。 */
    private static final int MAX_VIEWS = 600;

    /** 合并同一时刻的多次请求（配置可能连发好几条广播）。 */
    private static volatile boolean sPending;

    /** 还需要再跑几次（字体可能还没加载完，晚一点再重排一次就能赶上）。 */
    private static volatile int sRunsLeft;

    /** 单次请求最多重复几次（防止有人误传一个巨大的数把主线程拖住）。 */
    private static final int MAX_RUNS = 4;

    private UiRefresh() {
    }

    /** 请求一次「重排所有文字」（幂等；内部自己合并）。 */
    public static void relayoutText() {
        relayoutText(1, 300L);
    }

    /**
     * 重复重排（v0.1.10）。
     *
     * <h3>为什么需要重复</h3>
     * 新字体是**异步**解析的（{@code FeatureF4#prewarm} 在后台读文件/解压 APK）。
     * 配置到达时先排一次重排，很可能那一刻基准字体还是空的 —— 这次 setText 等于白做，
     * 而这正是用户报的「选了字体不生效、要重新拨一次开关」。
     *
     * <p>现在按固定间隔多试几次：只要中途字体就绪，下一次重排就会把文字换过来。
     * 次数上限 {@link #MAX_RUNS}，且与已有的待执行请求**合并**（不会越排越多）。
     *
     * @param times      想跑的次数（会被夹到 1..{@link #MAX_RUNS}）
     * @param intervalMs 两次之间的间隔
     */
    public static void relayoutText(int times, long intervalMs) {
        sRunsLeft = Math.min(MAX_RUNS, Math.max(sRunsLeft, Math.max(1, times)));
        if (sPending) return;             // 已经有排队的了，它会接着跑完剩下的次数
        sPending = true;
        runLater(Math.max(0L, intervalMs));
    }

    private static void runLater(long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            sPending = false;
            try {
                int n = touch();
                if (n > 0) XposedCompat.log(TAG + " relayout after font change: " + n + " views");
            } catch (Throwable t) {
                XposedCompat.log(TAG + " relayout failed: " + t);
            }
            if (sRunsLeft > 0) sRunsLeft--;
            if (sRunsLeft > 0) {
                sPending = true;
                runLater(delayMs);
            }
        }, delayMs);
    }

    private static int touch() {
        int[] count = {0};
        for (View root : roots()) {
            walk(root, count, 0);
        }
        return count[0];
    }

    /**
     * 拿当前所有窗口的根视图。
     *
     * <p>用 {@code WindowManagerGlobal.mViews}（框架内部字段，宿主进程里读得到）。
     * 拿不到就返回空数组 —— 这个功能是「锦上添花」，失败不做任何降级处理。
     */
    private static View[] roots() {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field f = wmg.getDeclaredField("mViews");
            f.setAccessible(true);
            Object v = f.get(inst);
            if (v instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) v;
                java.util.List<View> out = new java.util.ArrayList<>(list.size());
                for (Object o : list) {
                    if (o instanceof View) out.add((View) o);
                }
                return out.toArray(new View[0]);
            }
        } catch (Throwable ignored) {
        }
        return new View[0];
    }

    private static void walk(View v, int[] count, int depth) {
        if (v == null || count[0] >= MAX_VIEWS || depth > 60) return;
        if (v instanceof TextView) {
            count[0]++;
            TextView tv = (TextView) v;
            // EditText 跳过：重设文本会重置光标/输入法状态，用户正在输入时尤其明显。
            if (!(v instanceof EditText)) {
                try {
                    CharSequence t = tv.getText();
                    if (t != null && t.length() > 0) tv.setText(t);
                } catch (Throwable ignored) {
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n && count[0] < MAX_VIEWS; i++) {
                walk(g.getChildAt(i), count, depth + 1);
            }
        }
    }
}
