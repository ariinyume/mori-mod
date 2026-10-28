package io.github.ariinyume.morimod.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.github.ariinyume.morimod.config.MoriConfig;
import io.github.ariinyume.morimod.config.RemoteConfig;

/**
 * 图库（Media Pool）与名称库（Name Pool）的取用逻辑（BR-1.3 / BR-1.4）。
 *
 * <p>规则：仅「已启用」项参与随机；池为空或全部停用时相关替换项自动失效
 * （由调用方回落宿主原值，不显示空白）。
 */
public final class MediaPool {

    private static final Random RND = new Random();

    private MediaPool() {
    }

    /** 已启用的图库项（type = cover / background）。 */
    public static List<MoriConfig.MediaItem> enabledMedia(String type) {
        List<MoriConfig.MediaItem> out = new ArrayList<>();
        for (MoriConfig.MediaItem m : RemoteConfig.get().mediaPool) {
            if (m.enabled && type.equals(m.type) && m.path != null && !m.path.isEmpty()) {
                out.add(m);
            }
        }
        return out;
    }

    /** 已启用的名称库项（type = track / artist）。 */
    public static List<MoriConfig.NameItem> enabledNames(String type) {
        List<MoriConfig.NameItem> out = new ArrayList<>();
        for (MoriConfig.NameItem n : RemoteConfig.get().namePool) {
            if (n.enabled && type.equals(n.type) && n.text != null && !n.text.isEmpty()) {
                out.add(n);
            }
        }
        return out;
    }

    /** 随机取一项（无可用项返回 null）。 */
    public static MoriConfig.MediaItem pickMedia(String type) {
        List<MoriConfig.MediaItem> l = enabledMedia(type);
        if (l.isEmpty()) return null;
        return l.get(RND.nextInt(l.size()));
    }

    public static MoriConfig.NameItem pickName(String type) {
        List<MoriConfig.NameItem> l = enabledNames(type);
        if (l.isEmpty()) return null;
        return l.get(RND.nextInt(l.size()));
    }

    /** 名称库文本截断（BR-1.7）：超长文本按字符数截断并加省略号，避免撑破布局。 */
    public static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(1, max - 1)) + "…";
    }
}
