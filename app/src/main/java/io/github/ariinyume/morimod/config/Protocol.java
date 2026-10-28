package io.github.ariinyume.morimod.config;

/**
 * 跨进程广播契约的唯一字符串真源（FR-0.3）。
 *
 * <p>本模块 v1.0 只有两个进程在说话：
 * <ul>
 *   <li><b>设置页</b>（own process，launcher Activity）</li>
 *   <li><b>宿主</b> DLsiteSound（被注入进程）</li>
 * </ul>
 *
 * <p>⚠️ 铁律：只许加，不许改值。已装机的模块 action 已固化，改一个字符就静默断链
 * （广播发出去没人收、不报错、不崩溃，只表现为「配置不生效」）。
 */
public final class Protocol {

    /** 契约版本。只在新增/废弃 action 时 +1。 */
    public static final int PROTOCOL_VERSION = 4;

    /** action 统一前缀，跟 applicationId 同源。 */
    public static final String ACTION_PREFIX = "io.github.ariinyume.morimod.action.";

    // ── 设置页 → 宿主 ──────────────────────────────────────────────────
    /** 配置已变更，请重载。 */
    public static final String ACTION_CONFIG_CHANGED = ACTION_PREFIX + "CONFIG_CHANGED";
    /** 作用域探测。 */
    public static final String ACTION_HOST_PING = ACTION_PREFIX + "SCOPE_HOST_PING";
    /** 探测应答。 */
    public static final String ACTION_HOST_PONG = ACTION_PREFIX + "SCOPE_HOST_PONG";
    /** 重新随机本曲目（F1 会话级）。 */
    public static final String ACTION_REROLL = ACTION_PREFIX + "REROLL_CURRENT";
    /** 查询诊断日志。 */
    public static final String ACTION_DIAG_REQ = ACTION_PREFIX + "DIAG_REQ";
    /** 诊断日志应答。 */
    public static final String ACTION_DIAG_RESP = ACTION_PREFIX + "DIAG_RESP";
    /** 清空诊断日志。 */
    public static final String ACTION_DIAG_CLEAR = ACTION_PREFIX + "DIAG_CLEAR";

    // ── 宿主 → 设置页 ──────────────────────────────────────────────────
    /**
     * v0.0.9：宿主启动时**主动来要配置**。
     *
     * <p>⚠️ 真机日志铁证（2026-10-23 13:2x）：宿主每次进程启动都打
     * {@code config source=defaults} —— 跨进程读设置页写的那份配置**从来没成功过**
     * （LSPosed 2.2.x 的 {@code getRemotePreferences} 对注入进程是只读且常读到空，
     * 而宿主自身目录里那份本地副本在进程被杀后也没留下）。
     * 于是配置只在「设置页当场保存 → 广播命中活着的宿主」这一刻生效，
     * 宿主一重启就全部归零 —— 表现就是「功能完全不生效」。
     *
     * <p>这条 action 由模块应用里**清单注册**的 {@code ConfigQueryReceiver} 处理：
     * 清单接收器能把已经不在运行的模块进程拉起来应答，因此完全不依赖设置页是否开着。
     */
    public static final String ACTION_CONFIG_REQ = ACTION_PREFIX + "CONFIG_REQ";

    /**
     * v0.1.1：宿主向模块应用**索要一个素材文件**（背景图 / 用户导入的字体）。
     *
     * <p>为什么只能走广播：宿主是另一个 uid，按 SELinux 打不开本模块的
     * {@code files/media}；而「按 authority 访问 ContentProvider」被 Android 11+
     * 的包可见性挡死（真机日志 {@code Failed to find provider info}），
     * 「bindService 显式组件」同样受包可见性约束（缺 {@code <queries>} 就返回 false）。
     * <b>显式广播是唯一在本机被日志证实打通的通道</b>（{@code CONFIG_REQ} 应答成功）。
     *
     * <p>字节用分片回传（{@link #ACTION_ASSET_CHUNK}），因为 Binder 单次事务上限约 1MB。
     */
    public static final String ACTION_ASSET_REQ = ACTION_PREFIX + "ASSET_REQ";

    /** 模块应用 → 宿主：一个素材分片（或「文件不存在」）。 */
    public static final String ACTION_ASSET_CHUNK = ACTION_PREFIX + "ASSET_CHUNK";

    /**
     * v0.1.2：宿主**活着就在喊**的心跳（每 20s 一次，进程起来时立刻一次）。
     *
     * <h3>为什么需要它</h3>
     * 设置页原来的做法是「发 ping，1.2 秒内没 pong 就判定未运行」，真机上经常
     * 误判：宿主在后台被系统冻结（cached + frozen）时，广播虽然会被投递，
     * 但排队 + 解冻的延迟轻易超过 1.2 秒；再叠加设置页自己也要抢 CPU，
     * 用户看到的就是「宿主明明开着，状态卡却写未运行」。
     *
     * <p>心跳把判定从「一次问答有没有赶上截止时间」变成「最后一次见到宿主距今多久」：
     * 模块应用的清单接收器把时间戳落到自己的 prefs 里（不需要设置页开着），
     * 设置页用一个 45 秒宽限窗口判断。真正被杀掉的宿主自然会过期。
     */
    public static final String ACTION_HOST_ALIVE = ACTION_PREFIX + "HOST_ALIVE";

    // ── EXTRA key ───────────────────────────────────────────────────────
    public static final String EXTRA_BUILD = "build";
    public static final String EXTRA_TAG = "tag";
    public static final String EXTRA_HOST_ALIVE = "host_alive";
    public static final String EXTRA_CONFIG_JSON = "config_json";
    public static final String EXTRA_TRIGGER_SUMMARY = "trigger_summary";
    public static final String EXTRA_DIAG_TEXT = "diag_text";
    public static final String EXTRA_REROLL_OK = "reroll_ok";
    /** 素材相对名（{@code media/xxx.jpg} / {@code fonts/xxx.ttf}）。 */
    public static final String EXTRA_ASSET_REL = "asset_rel";
    /** 一次请求的序号（同一文件可能被多个请求交叉，回包靠它归位）。 */
    public static final String EXTRA_ASSET_RID = "asset_rid";
    /** 分片下标（0 起）。 */
    public static final String EXTRA_ASSET_SEQ = "asset_seq";
    /** 分片总数；{@code 0} = 该文件不存在/不可读（一次性否定应答）。 */
    public static final String EXTRA_ASSET_N = "asset_n";
    /** 分片字节。 */
    public static final String EXTRA_ASSET_DATA = "asset_data";
    /** 心跳时间戳（宿主侧 System.currentTimeMillis()）。 */
    public static final String EXTRA_ALIVE_TS = "alive_ts";

    /** EXTRA_TAG 取值：宿主。 */
    public static final String TAG_HOST = "host";

    /** 宿主包名（LSPosed 作用域，唯一）。 */
    public static final String HOST_PKG = "jp.co.eisys.dlsitesound";

    private Protocol() {
    }

    /** 契约自检：null = 通过。 */
    public static String selfCheck() {
        String[] actions = {
                ACTION_CONFIG_CHANGED, ACTION_HOST_PING, ACTION_HOST_PONG,
                ACTION_REROLL, ACTION_DIAG_REQ, ACTION_DIAG_RESP, ACTION_DIAG_CLEAR,
                ACTION_CONFIG_REQ, ACTION_ASSET_REQ, ACTION_ASSET_CHUNK,
                ACTION_HOST_ALIVE,
        };
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String a : actions) {
            if (a == null || !a.startsWith(ACTION_PREFIX)) {
                return "action 缺统一前缀: " + a;
            }
            if (!seen.add(a)) {
                return "action 重复: " + a;
            }
        }
        String[] extras = {
                EXTRA_BUILD, EXTRA_TAG, EXTRA_HOST_ALIVE, EXTRA_CONFIG_JSON,
                EXTRA_TRIGGER_SUMMARY, EXTRA_DIAG_TEXT, EXTRA_REROLL_OK,
                EXTRA_ASSET_REL, EXTRA_ASSET_RID, EXTRA_ASSET_SEQ, EXTRA_ASSET_N,
                EXTRA_ASSET_DATA, EXTRA_ALIVE_TS,
        };
        seen.clear();
        for (String e : extras) {
            if (e == null || e.isEmpty()) {
                return "extra key 为空";
            }
            if (!seen.add(e)) {
                return "extra key 重复: " + e;
            }
        }
        return null;
    }
}
