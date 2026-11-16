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
 * 把用户的壁纸画进宿主自己的绘制流程（v0.10.0）。
 *
 * <h3>为什么是「六条钩子」而不是一条</h3>
 * 宿主同一屏里用 <b>六种</b> 完全不同的方式画底色 / 氛围，缺一条就有一块地方不生效：
 * <ol>
 *   <li>{@code BackgroundDrawable#draw} —— RN 的纯色底（{@code backgroundColor}）；</li>
 *   <li>{@code BackgroundImageDrawable#draw} —— RN 0.81 的 {@code backgroundImage} 渐变；</li>
 *   <li>{@code expo.modules.lineargradient.LinearGradientView#onDraw} ——
 *       {@code expo-linear-gradient} 的渐变层（<b>页面真背景</b>就是它，r22 实证）；</li>
 *   <li>{@code expo.modules.image.ExpoImageView#draw} —— ★ v0.1.20：
 *       半透明的「氛围叠加图」（PurePlay 顶部紫、播放页紫色底），它是<b>内容图</b>，
 *       不走 background、不走资源、不是渐变 ⇒ 只有这一条能碰到（报告定案）；</li>
 *   <li>{@code android.view.View#draw} —— 没有任何 drawable 的全屏装饰层（叶子）；</li>
 *   <li>（同上一处顺带）{@code TextView} 的<b>文字阴影</b>。</li>
 * </ol>
 *
 * <h3>v0.1.19 的关键修正：<b>透明的东西只能「不画」，不能「涂成不透明」</b></h3>
 * r24 截图实证：媒体库右上角的放大镜图标<b>上半截消失</b>。原因是宿主的顶部蒙层
 * （{@code LinearGradientView 1272x201}，原本是一条半透明渐变 scrim）盖在图标<b>后面画的</b>，
 * 我们把它整层涂成了不透明的模糊壁纸 ⇒ 蒙层把图标上半截糊死了。
 *
 * <p>⇒ 新的判据只有一条，但它是因果级的：
 * <ul>
 *   <li><b>宿主自己画了不透明的底</b>（背景 drawable 的 {@code getOpacity()==OPAQUE}）：
 *       替换它是<b>等价操作</b>，不会多遮住任何东西 ⇒ 可以换成模糊壁纸。</li>
 *   <li><b>宿主画的是半透明 / 没画底</b>（scrim、渐变蒙层）：涂成不透明就是<b>增加遮挡</b>
 *       ⇒ 只能<b>整层不画</b>，露出下面的壁纸。用户要的「播放列表导航栏直接透出背景」
 *       「PurePlay 紫遮罩消失」都是这一条。</li>
 * </ul>
 *
 * <h3>另一条铁律：容器永不跳过绘制</h3>
 * 跳过容器 = 连子内容一起不画（v0.1.17 的「按钮/选项/列表全消失」）。
 * 容器的底色只能通过它的<b>背景 drawable</b> 换 —— 子 View 画在背景之后，天然安全。
 */
public final class BgPeel {

    /*
     * ═══ 职责索引（维护导航；行号随版本漂移，以方法名为准）════════════════
     *  Drawable / 渐变换底 · hookDrawable / hookGradientView / dropGradient / swapLeaf
     *  封面判定与遮罩     · coverBlocked / paintCoverMask / rescueCover / hookExpoImage
     *  播放页识别         · isPlayPage / playContext / notePlaySeek / markPlayWindow
     *  文字阴影           · ensureShadow / refreshTextShadows / onLightSurface
     *  强调色重染         · retintTree / forceAccentSmallText / hookColorDrawable
     *  面板玻璃           · paintSheetPanel / paintDialogGlass / paintBlurBg
     *  诊断探针           · dump* / probe* / trace* / audit* / snap* / hit
     * ═══════════════════════════════════════════════════════════════════════
     */

    static final String TAG = "[MoriMod:BG]";

    /** RN 的纯色背景 Drawable（R8 保留了名字，见 2.22.0 dex）。 */
    static final String CLS_BG_DRAWABLE =
            "com.facebook.react.uimanager.drawable.BackgroundDrawable";

    /** RN 0.81 的 {@code backgroundImage} 落成的 Drawable。 */
    static final String CLS_BG_IMAGE =
            "com.facebook.react.uimanager.drawable.BackgroundImageDrawable";

    /** expo-linear-gradient 的渐变 View（页面真背景 + 各种渐变蒙层）。 */
    static final String CLS_GRADIENT_VIEW =
            "expo.modules.lineargradient.LinearGradientView";

    /**
     * ★ v0.1.20：expo-image 的图片 View（宿主所有「氛围叠加图」的落点）。
     *
     * <p>2.22.0 未混淆，dex 方法表里确有 {@code draw(Landroid/graphics/Canvas;)V}。
     * 报告《PurePlay 页面背景机制》定案：PurePlay 顶部那片紫色<b>不是遮罩、不是渐变、
     * 不是颜色资源</b>，而是 {@code assets_images_localbg.webp}（780×920 RGBA）当<b>内容图</b>
     * 画在最底层；播放页的 {@code playbackspbackground}（1081×1920 RGBA）同理。
     * 它们走 {@code ExpoImageView#draw}，此前所有钩子（background / 资源 / 渐变）全都碰不到。
     */
    static final String CLS_EXPO_IMAGE = "expo.modules.image.ExpoImageView";

    /**
     * 判定「氛围叠加图」的平均 alpha 上限（v0.1.20）。
     *
     * <p>报告 §4 的共同特征：{@code localbg / playbacksbackground / playback_card_bg /
     * player_background} 全是 <b>RGBA + 大面积 alpha</b>；唯一的不透明图
     * {@code main_visual} 是 <b>RGB</b>（主页立绘，不能碰）。
     * ⇒ 用「位图有没有 alpha 通道 + 采样平均 alpha」就能干净区分，不依赖比例魔数。
     */
    static final float VEIL_MAX_ALPHA = 0.92f;

    /**
     * ★★ v0.1.22（用户第 1 条）：还能「整层不画」的 alpha 上限。
     *
     * <h3>为什么必须把「半透明」和「几乎不透明」拆开</h3>
     * r27 日志：播放页底图是 {@code img 780x1688 avg=0.88 -> veil}，被整层不画了。
     * 「不画」= 在这一层位置<b>留一个洞</b>，洞后面有没有壁纸完全取决于别的层当帧画没画。
     * 正常进页面时运气好（下面的层刚画过），一旦切后台再回前台，
     * 宿主重新走一遍布局/绘制顺序，洞后就没有东西 ⇒ <b>播放页背后全透明</b>。
     *
     * <p>现在只有「真的半透明叠加」（{@code avg < 0.65}，例如 PurePlay 顶部
     * {@code localbg avg=0.50} 那种紫→透明的蒙层）才允许留洞 —— 那种层涂成不透明会
     * 凭空加遮挡。≥0.65 的一律当「底图」<b>原地换壁纸</b>：既不留洞，也不多遮任何东西。
     */
    static final float VEIL_DROP_MAX_ALPHA = 0.65f;

    /** 氛围图必须占屏幕高度的这个比例以上（排除小图标 / 头像）。 */
    static final float VEIL_MIN_H_RATIO = 0.18f;


    /**
     * 「缩放超过这个幅度才允许把清晰壁纸换成毛玻璃」（v0.1.27 用户第 4 条）。
     *
     * <p>2% 的门槛太灵敏：图片浏览翻到最后一张时的<b>回弹</b>、列表的轻微拉伸
     * 都能命中，于是「最后一张图片背后一定多一层高斯模糊」（r32 原话）。
     * 真正的转场是 0.8~0.9x / 1.1x 量级，8% 这条线正好把「真转场」和「轻微回弹」分开。
     */
    static final float ZOOM_BLUR_MIN = 0.08f;


    /**
     * ★★★ v0.1.34（r39 日志定案）：<b>半径分档已经不成立</b>。
     *
     * <p>r39 的 335 行日志里，{@code blur bg r=10 1272x2772 ->play} 出现了 8 次，
     * 而且用户截图证明其中至少两次是<b>播放列表详情页</b>（自己建的那个「测试」列表 +
     * 作品列表页），两张截图里整页背景都是<b>我的壁纸被模糊后的样子</b> ——
     * 也就是说 {@code paintBlurBg} 把列表页判成了播放页，铺了 {@code sWpPlayBmp}。
     *
     * <p>宿主对「播放列表 / 分类详情」用的也是 {@code blurRadius=10}，
     * 和 {@code PlayerBackground} 完全一样 ⇒ 半径里根本不携带「这是哪个页面」的信息。
     *
     * <p>换成本轮唯一可靠的信号：<b>页面身份</b>。
     * 播放页一定会加载 seekbar（{@code seekbar_track_material} /
     * {@code seekbar_thumb_material_anim}，r39 日志里就在播放页那一段），
     * 列表页不会。所以顺序是：
     * <ol>
     *   <li>seekbar 加载 ⇒ {@link #PlayPage.notePlayPage()} 打点 + 置「待绑定」；</li>
     *   <li>下一个整屏模糊底把它的 <b>Screen 容器</b>绑成「播放页容器」；</li>
     *   <li>之后只有<b>同一个容器</b>里的模糊底才算播放页 —— 列表页是另一个
     *       {@code Screen} 实例，第一帧就是清晰壁纸，不会再有「整页糊」。</li>
     * </ol>
     */
    static final long PLAY_PAGE_TTL_MS = 1500L;

    /** 最近一次「播放页特征」（seekbar 资源加载）的时间戳。 */
    static volatile long sPlayPageAt;

    /** 有待绑定的播放页容器：seekbar 刚加载过，下一块整屏模糊底就是播放页。 */
    static volatile boolean sPlayBindPending;

    /** 已绑定成「播放页」的容器（弱引用，页面销毁自动失效）。 */
    static volatile java.lang.ref.WeakReference<View> sPlayScreen;

    /** 播放页判定命中/失败各打一条，便于下一轮核对（每进程 6 条）。 */
    static volatile int sPlayJudgeLogs;
    static final int PLAY_JUDGE_LOG_BUDGET = 6;

    /**
     * ★★★ v0.1.37：**「播放页还活着」的活体判据** —— 一个真正贴在窗口上的进度条。
     *
     * <h3>为什么换成这个</h3>
     * v0.1.34 想用「seekbar 资源加载 ⇒ 把当前 Screen 绑成播放页」，r40 日志证明
     * 那条路根本绑不上：播放页那张整屏底图<b>永远挂在主窗口根</b>（{@code play page bind root}），
     * 根本不在任何 {@code ScreenContentWrapper} 里。于是绑定退化成「1.5 秒内所有页面一起糊」。
     *
     * <p>现在不再猜「哪块 View 属于播放页」，而是问一个<b>事实</b>：
     * 「屏幕上此刻有没有一个活着（attached + visible）的进度条」。
     * 播放页有进度条，播放列表 / 主界面 / PurePlay 列表页都没有；
     * 迷你播放器那条只是 1~2dp 的细线，用「高度 ≥ 2% 屏高」把它排掉。
     *
     * <p>离开播放页 ⇒ 视图被 RN 卸载 / 被 ScreenContainer 置为不可见 ⇒
     * {@code isShown()} 立刻变 false ⇒ 判定当天就失效，不需要 TTL。
     */
    static volatile java.lang.ref.WeakReference<View> sPlaySeek;

    /** 「播放页进度条」探测日志条数。 */
    static volatile int sPlaySeekLogs;
    static final int PLAY_SEEK_LOG_BUDGET = 4;

    /**
     * 判定「铺满窗口」的面积比例。
     *
     * <p>0.80：RN 根是 1272x2564，DecorView 是 1272x2772（差系统栏）⇒ 比值 0.925，
     * 页面容器换成「屏幕减底栏」也还有 0.86；而最大的卡片也就 0.15 上下。
     */
    static final float FULL_SCREEN_RATIO = 0.80f;

    /** 判定「铺满宽度」的比例。卡片有左右边距（≈4%），够不到这个数。 */
    static final float FULL_W_RATIO = 0.94f;

    /** 前台复核周期（只读配置 + 缓存，不遍历视图树）。 */
    /**
     * 前台配置轮询间隔（v0.1.87：800ms → 1500ms）。
     *
     * <p>这条轮询的唯一目的是「用户改了 Mori 设置后能跟上」，而 {@code RemoteConfig}
     * 本身有缓存、位图也按 key 复用，所以它并不重；但 800ms 一拍在主线程上仍是
     * 每秒 1.25 次的无谓唤醒（还会带着 {@code AccentColor.update} / 屏幕尺寸刷新）。
     * 1.5 秒足够——改设置时几乎感觉不到延迟。
     */
    static final long POLL_MS = 1500L;

    /** 日志上限（每进程）。★ v0.1.28：160 → 320（确认音让日志更完整）。 */
    static final int LOG_BUDGET = 320;

    /** 「满屏层清单」最多打多少行（每进程）。 */
    static final int BIG_LOG_BUDGET = 28;

    /** 顶栏判定：View 顶边落在「屏幕顶 + 这个高度」以内（dp）。 */
    static final float EDGE_TOP_DP = 110f;

    /** 底栏判定：View 底边落在「屏幕底 - 这个高度」以内（dp）。 */
    static final float EDGE_BOTTOM_DP = 190f;

    /** 条高度上限（dp）：再高的就不是顶栏/底栏，而是内容区。 */
    static final float BAR_MAX_H_DP = 170f;

    /**
     * 底部面板的最小高度（占屏高比例）。
     *
     * <p>25%：半屏面板（下载进度 / 信息面板）都远超这个数，而「满宽贴底的深色行 / 分隔条」
     * 只有几%（208px ≈ 7.5%）⇒ 一条线把两类彻底分开，不需要看颜色。
     */
    /**
     * 面板高度下限（占屏高比例）。
     *
     * <p>★ v0.1.23：0.25 → 0.12。「点击下载」那个进度面板只有十几% 屏高，
     * 卡 0.25 就落进了「比条高、比面板矮」的空档 ⇒ 永远是紫底（用户第 4 条）。
     * 12% 以下交给 {@link #isBarLike}（≤170dp 那条），两者首尾相接，不再有空档。
     */
    static final float SHEET_MIN_H_RATIO = 0.12f;

    /**
     * 面板的高度上限（占屏高比例）。
     *
     * <p>★ v0.1.27：0.80 → 0.92。用户连着两轮反馈「「概述」「下载音轨」「PurePlay
     * 简介页」「PurePlay 导入页」这四张卡片始终挂不上玻璃」。这四张都是<b>接近整屏</b>的
     * 底部面板（内容多），高度轻松越过 0.80 ⇒ 被判成「整页容器」⇒ 一张玻璃都没有。
     * 0.92 仍然把「真正的整页」（内容从屏幕顶端开始，顶边贴顶 ⇒ 下面那条
     * {@code loc[1] <= sEdgeTop} 判据会先挡掉）排除在外。
     */
    static final float SHEET_MAX_H_RATIO = 0.92f;

    /**
     * 面板宽度下限（占屏宽比例）。
     *
     * <p>★ v0.1.27：从「满宽 94%」放宽到 <b>86%</b>。宿主的底部面板带左右 16dp 圆角留白，
     * 宽度只有 90% 上下 —— 卡 94% 就永远不命中，那四张卡片于是永远没有玻璃。
     * 86% 依然能把列表里的大卡片（≤ 60% 屏宽）挡在外面。
     */
    static final float SHEET_MIN_W_RATIO = 0.86f;

    /**
     * 文字阴影半径占字号的比例（v0.1.20 用户指定 8%）。
     *
     * <p>只有「自定义壁纸生效时」才加（{@link #sArmed}）—— 恢复默认背景就回到宿主原样。
     */
    static final float TEXT_SHADOW_RADIUS_RATIO = 0.08f;

    /** 文字阴影强度（alpha 0xCC = 80%）。<b>色相</b>由 {@link #TextShadow.shadowColor()} 决定。 */
    static final int TEXT_SHADOW_ALPHA = 0xCC;

    /**
     * 判定「亮底」的亮度门槛（v0.1.20 用户第 6 条）。
     *
     * <p>PurePlay 的「导入 / 创建播放列表」、设定里的「同步已购买作品」这些按钮是<b>实心浅色底</b>，
     * 上面加黑阴影只会糊成一团 ⇒ 背景亮度过这个值就不加阴影（并把之前加过的清掉）。
     */
    static final float LIGHT_BG_LUMA = 0.60f;

    /**
     * 判定「文字自己是深色」的亮度上限（v0.1.21 用户第 4 条）。
     *
     * <p>深字压深底谁都看不见 ⇒ 只要文字是深色，它就一定坐在浅底上。
     * 这条不依赖任何 drawable 内部结构，是「白底按钮文字不要阴影」最稳的兜底判据
     * （宿主那批按钮的底色是 R8 混淆过的 {@code BackgroundDrawable}，读不到颜色字段）。
     */
    static final float DARK_TEXT_LUMA = 0.45f;

    /** 采样底色用的缩略图边长（8×8 足够分辨「白底 / 深底」，成本可忽略）。 */
    static final int PROBE_PX = 8;

    static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 绘制用的临时对象（draw 都在 UI 线程，复用避免每帧分配）。 */
    static final ThreadLocal<int[]> LOC = ThreadLocal.withInitial(() -> new int[2]);

    /**
     * ★★★ v0.1.98：<b>本层所在窗口的尺寸</b> {@code [w, h]}（每次绘制当场取，不用缓存值）。
     *
     * <p>见 {@link #rootGeometry}：壁纸从「铺在屏幕上」改成「铺在窗口上」——
     * 浮窗 / 分屏 / 自由窗口下窗口不再等于屏幕，缓存值一过期就是错位。
     */
    static final ThreadLocal<int[]> RGEO = ThreadLocal.withInitial(() -> new int[2]);
    static final ThreadLocal<Rect> SRC = ThreadLocal.withInitial(Rect::new);
    static final ThreadLocal<RectF> DST = ThreadLocal.withInitial(RectF::new);
    /** 整层 dst=(0,0,w,h)，避免绘制路径每帧 new Rect。 */
    static final ThreadLocal<Rect> RECT = ThreadLocal.withInitial(Rect::new);
    /** v0.1.22：面板圆角裁剪用的 Outline / Path。 */
    static final ThreadLocal<android.graphics.Outline> OUTLINE =
            ThreadLocal.withInitial(android.graphics.Outline::new);
    static final ThreadLocal<android.graphics.Path> PATH =
            ThreadLocal.withInitial(android.graphics.Path::new);


    /** 热路径复用的 (0,0,w,h) 矩形（ThreadLocal，勿跨调用缓存引用）。 */
    static Rect dstRect(int w, int h) {
        Rect r = RECT.get();
        r.set(0, 0, w, h);
        return r;
    }

    /** 位图绘制画笔（FILTER_BITMAP 保证缩放平滑）。 */
    static final Paint BMP_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** 条带压暗蒙层（{@link FeatureF2#BAR_MASK_PCT}）。 */
    static final Paint MASK_PAINT = new Paint();

    /**
     * ★★★ v0.1.44（r15 用户第 6 条）：宿主「防偷窥」封面模糊的<b>强调色罩</b>（画笔）。
     *
     * <p>宿主把封面交给 expo-image 的 {@code blurRadius} 做防偷窥，渲染时套了一层
     * 自家紫色调 —— 而这层紫是<b>烘进图像结果里的</b>：不走
     * {@code ColorPropConverter}、不走 {@code TextView#setTextColor}、
     * 也不是可以被 {@code Paint#setColor} 拦下的矢量填色，
     * 而且色相偏蓝紫（距宿主主色相 &gt; 窗口），按「宁可漏一个」的保护规则被放行。
     * ⇒ 换了强调色它依然是紫的。
     *
     * <p>识别方式复用模块已经跑了很多轮的<b>唯一可靠事实</b>：
     * {@link #BLUR_PENDING} 里那个 expo-image {@code blurRadius} prop
     * （{@code hookImageBlur} 在 setter 上记的账）。注意这里读的是<b>裸值</b>：
     * {@link #blurRadiusOf} 还带了「必须铺满屏幕才算装饰底」那道闸，
     * 而防偷窥要罩的正是<b>卡片级</b>的模糊封面，被那道闸挡在门外。
     * 在它<b>画完之后</b>叠一层强调色罩（{@link AccentColor#shadow}，≈19%），
     * 让防偷窥块的色相跟着强调色走，而封面本身的明暗结构保留。
     *
     * <p>排除项：满屏的「页面底图」（≥94% 屏宽且 ≥92% 屏高）由
     * {@link #paintBlurBg} 整张换成壁纸，不能被罩色；
     * 太小（&lt;18% 屏宽或 &lt;6% 屏高）的是头像 / 图标，不动。
     */
    static final Paint WASH_PAINT = new Paint();


    /** 记下一个「刚罩过色」的封面矩形。 */

    /** 「paint root bg」诊断日志条数（v0.1.59）。 */
    static volatile int sRootBgLogs;

    /** 「cover alpha fix」诊断日志条数（v0.1.64，限 3 条）。 */
    static volatile int sCoverFixLogs;

    /** 「play alpha rescue」诊断日志条数（v0.1.65，限 3 条）。 */
    static volatile int sAlphaFixLogs;

    /** 上一次「播放页层级审计」的时间（v0.1.67，每 3 秒最多一次）。 */
    static volatile long sAuditAt;

    /** 本次审计已输出行数（v0.1.67，限 70 行，防止刷爆日志）。 */
    static volatile int sAuditLines;

    /** 关着日志时的遍历节点计数（每次 dump 归零，v0.1.87）。 */
    static volatile int sAuditNodes;

    /** 最近一次铺「播放档满屏底」的时间（v0.1.63，窗口根兜底底板的信号源）。 */
    static volatile long sPlayPaintedAt;

    /**
     * ★★★ v0.1.51：轻量失效 —— 只让根窗口重新合成一帧，<b>不递归 invalidate 每个子 View</b>。
     *
     * <p>{@code invalidateTree} 会把整棵树上几百个 View 的 RenderNode 全部作废，
     * 下一次绘制要全部重录一遍（RN 页面动辄上千个节点）—— 这就是「播放页弹出卡顿」
     * 和「底部导航切换卡顿」的来源。这里只 {@code postInvalidateOnAnimation()}，
     * 代价和普通重绘一致；限流到 400ms 一次。
     */
    static void invalidateRootSoft(View v) {
        try {
            long now = System.currentTimeMillis();
            if (now - sSoftInvalidateAt < 400L) return;
            sSoftInvalidateAt = now;
            View root = v == null ? null : v.getRootView();
            if (root != null) root.postInvalidateOnAnimation();
        } catch (Throwable ignored) {
        }
    }

    /** {@link #invalidateRootSoft} 的上次时间。 */
    static volatile long sSoftInvalidateAt;




    /**
     * ★★★ v0.1.47：这张位图是不是<b>已经被糊过的</b>（防偷窥封面的像素级证据）。
     *
     * <p>做法：降采样到 48px 宽，算拉普拉斯（4 邻域）绝对值的均值 ——
     * 「高频细节能量」。模糊图的高频被抹掉 ⇒ 读数极低；清晰封面（插画 + 标题文字）
     * 读数高出好几倍。每张位图只算一次（WeakHashMap 缓存），绘制热路径零成本。
     *
     * <p>阈值 14 的标定来自 r47 录屏：模糊封面 2.9~7.5、清晰封面 46~52
     * （那还是被视频压缩 + 3 倍缩放「抹平过」的读数；真位图的差距只会更大）。
     */
    static final java.util.Map<Bitmap, Float> BLUR_SCORE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Bitmap, Float>());

    /** 低于该值视为「糊图」。 */
    // ★★★ v0.1.55（r55 用户第 4 条「蓝色色块」）：14 → **9**。
    //   真机标定：宿主防偷窥糊图 lap≈2.9~7.5；而「画风本身就柔」的作品封面
    //   会落在 10~20 这一段 —— 14 会把它们误判成防偷窥封面（于是出现蓝色块）。
    //   9 仍然对真糊图有 1.2 倍余量，同时把柔画风封面挡在外面。
    static final float BLUR_SCORE_MAX = 9f;

    static boolean isBlurredBitmap(Bitmap b) {
        if (b == null || b.isRecycled() || b.getWidth() < 24 || b.getHeight() < 24) return false;
        Float f = BLUR_SCORE.get(b);
        if (f == null) {
            Bitmap probe = b;
            try {
                // HARDWARE 位图无法 getPixels ⇒ 拷一份 ARGB_8888（每张位图只拷一次）
                if (b.getConfig() == Bitmap.Config.HARDWARE) {
                    probe = b.copy(Bitmap.Config.ARGB_8888, false);
                }
                f = probe == null ? -1f : computeBlurScore(probe);
            } catch (Throwable t) {
                f = -1f;
            } finally {
                if (probe != null && probe != b) {
                    try {
                        probe.recycle();
                    } catch (Throwable ignored) {
                    }
                }
            }
            BLUR_SCORE.put(b, f);
        }
        return f < BLUR_SCORE_MAX;
    }

    /**
     * ★★★ v0.1.47：只在防偷窥判据里用的「宽口径取图」。
     *
     * <p>{@link #imageBitmapOf} 只认最外层的 {@code BitmapDrawable}；expo-image
     * （Fresco 体系）外面通常会裹一层 {@code ScaleTypeDrawable / ForwardingDrawable /}
     * {@code RoundedDrawable}，所以那边常年取到 null。这里改成<b>穿透 drawable 树</b>
     * （≤4 层）揪 BitmapDrawable 出来。
     *
     * <p>⚠️ 只给防偷窥用，<b>不能</b>替换 {@link #imageBitmapOf} —— 那边一旦变灵敏，
     * {@code alphaAvgOf} 就会开始给内容图判出半透明，
     * 列表里的封面会被当成「氛围叠加图」顶掉（比现在这个 bug 严重得多）。
     */
    static Bitmap privacyBitmapOf(View v) {
        try {
            if (v instanceof ImageView) {
                Bitmap b = deepBitmap(((ImageView) v).getDrawable(), 0);
                if (b != null) return b;
            }
            Bitmap b = deepBitmap(v.getBackground(), 0);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 递归穿过 Wrapped 层找 BitmapDrawable（深度上限 4）。 */
    static Bitmap deepBitmap(Drawable d, int depth) {
        if (d == null || depth > 4) return null;
        try {
            if (d instanceof BitmapDrawable) {
                Bitmap b = ((BitmapDrawable) d).getBitmap();
                return b == null || b.isRecycled() ? null : b;
            }
            if (d instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.LayerDrawable l =
                        (android.graphics.drawable.LayerDrawable) d;
                for (int i = 0; i < l.getNumberOfLayers(); i++) {
                    Bitmap b = deepBitmap(l.getDrawable(i), depth + 1);
                    if (b != null) return b;
                }
                return null;
            }
            if (d instanceof android.graphics.drawable.InsetDrawable) {
                return deepBitmap(((android.graphics.drawable.InsetDrawable) d).getDrawable(),
                        depth + 1);
            }
            if (d instanceof android.graphics.drawable.ClipDrawable) {
                return deepBitmap(((android.graphics.drawable.ClipDrawable) d).getDrawable(),
                        depth + 1);
            }
            if (d instanceof android.graphics.drawable.ScaleDrawable) {
                return deepBitmap(((android.graphics.drawable.ScaleDrawable) d).getDrawable(),
                        depth + 1);
            }
            if (d instanceof android.graphics.drawable.StateListDrawable) {
                return deepBitmap(((android.graphics.drawable.StateListDrawable) d).getCurrent(),
                        depth + 1);
            }
            if (d instanceof android.graphics.drawable.RotateDrawable) {
                return deepBitmap(((android.graphics.drawable.RotateDrawable) d).getDrawable(),
                        depth + 1);
            }
            // 兜底：不知道是哪家的包装（Fresco ForwardingDrawable 等），
            // 找它身上任意一个「无参 + 返回 Drawable」的方法调一次（类名不猜）。
            java.lang.reflect.Method m = null;
            try {
                for (java.lang.reflect.Method cand : d.getClass().getDeclaredMethods()) {
                    if (cand.getParameterTypes().length == 0
                            && Drawable.class.isAssignableFrom(cand.getReturnType())) {
                        m = cand;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (m != null) {
                try {
                    m.setAccessible(true);
                    Object got = m.invoke(d);
                    if (got instanceof Drawable) return deepBitmap((Drawable) got, depth + 1);
                } catch (Throwable ignored) {
                    // 反射失败就当这层没有子 Drawable
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 拉普拉斯高频能量（越低越糊）；任何异常返回 -1（= 不当作糊图）。 */
    static float computeBlurScore(Bitmap src) {
        try {
            int w = 48;
            int h = Math.max(8, Math.round(src.getHeight() * (48f / src.getWidth())));
            Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
            if (small == null) return -1f;
            int[] pix = new int[w * h];
            try {
                small.getPixels(pix, 0, w, 0, 0, w, h);
            } finally {
                if (small != src) small.recycle();
            }
            long tot = 0;
            int n = 0;
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int c = pix[y * w + x];
                    int l = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                    int up = pix[(y - 1) * w + x], dn = pix[(y + 1) * w + x];
                    int lf = pix[y * w + x - 1], rt = pix[y * w + x + 1];
                    int cc = (l * 77 + g * 151 + b * 28) >> 8;
                    int cu = ((up >> 16) & 0xff) * 77 + ((up >> 8) & 0xff) * 151 + (up & 0xff) * 28;
                    int cd = ((dn >> 16) & 0xff) * 77 + ((dn >> 8) & 0xff) * 151 + (dn & 0xff) * 28;
                    int cl = ((lf >> 16) & 0xff) * 77 + ((lf >> 8) & 0xff) * 151 + (lf & 0xff) * 28;
                    int cr = ((rt >> 16) & 0xff) * 77 + ((rt >> 8) & 0xff) * 151 + (rt & 0xff) * 28;
                    int lap = 4 * cc - (cu >> 8) - (cd >> 8) - (cl >> 8) - (cr >> 8);
                    tot += Math.abs(lap);
                    n++;
                }
            }
            return n == 0 ? -1f : tot / (float) n;
        } catch (Throwable t) {
            return -1f;
        }
    }


    /**
     * 面板换底时的「重入闸门」（v0.1.24）。
     *
     * <p>面板要的效果是「模糊壁纸 + 宿主原本那层半透明白」的叠加，
     * 而宿主那层白的唯一来源就是它自己的 {@code draw()}。所以我们在钩子里画完模糊后
     * <b>再调一次 {@code d.draw(c)}</b> —— 必须有一个标志让那一次调用直接走原逻辑，
     * 否则就是无限递归。
     */
    static final ThreadLocal<Boolean> REENTRY = new ThreadLocal<>();

    /** 已经加过阴影的 TextView → 上次用的半径（WeakHashMap 不会拖住 Activity）。 */
    static final java.util.Map<TextView, Float> SHADOWED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** TextView → 是否坐在亮底上（只算一次；背景不变就一直有效）。 */
    static final java.util.Map<TextView, Boolean> LIGHT_BG =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 位图 → 它属于哪种图（{@link #IMG_VEIL} / {@link #IMG_ATMOS} / 内容图）。 */
    static final java.util.Map<Bitmap, Integer> VEIL_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * ★ v0.1.41：位图 → 平均 alpha（{@link #imgKind} 算过就顺手存一份）。
     *
     * <p>用户第 7 条「作品封面不显示」的判据就在这里：封面照片是<b>不透明</b>的
     * （avg ≈ 1.0，只是编码带 alpha 通道），而三张信息面板的底是<b>半透明紫</b>
     * （avg 0.6~0.9）。光看「有没有 alpha 通道」分不开它们 —— r12 日志里
     * {@code dialog glass -> ExpoImageView 1440x1080} 就是这么把封面铺成玻璃的。
     */
    static final java.util.Map<Bitmap, Float> AVG_ALPHA =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 位图分类：内容图（不碰）。 */
    static final int IMG_CONTENT = 0;

    /** 位图分类：半透明氛围叠加图（整层不画，露出壁纸）。 */
    static final int IMG_VEIL = 1;

    /** 位图分类：不透明氛围底图（播放页 {@code playbackspbackground} 这种）。 */
    static final int IMG_ATMOS = 2;

    /**
     * 位图分类：宿主的<b>装饰性模糊图</b>（v0.1.24 用户第 3 条）。
     *
     * <p>全包只有三处传了 {@code blurRadius}：播放页封面（10）、播放列表/分类详情封面（6）。
     * 它们的共同点是「一张预先糊过的封面铺满屏幕」—— 逐字还原 bundle 后证实，
     * 用户在播放页看到的「模糊背景有时是封面、有时是壁纸」就来自这里：
     * 有 {@code artwork} 时宿主画这张 10px 模糊封面，没有时它只画 {@code #000000}
     * （被我们的换壁纸逻辑顶掉）。
     *
     * <p>⇒ 只要 {@code blurRadius > 0}，这一层就是<b>装饰底</b>，不是内容，
     * 一律换成「自定义壁纸 + 用户设定的模糊」。
     */
    static final int IMG_BLUR = 3;

    /** expo-image 的 props 壳（{@code blurRadius} 只有它有，{@code ExpoImageView} 本身没有）。 */
    static final String CLS_EXPO_IMAGE_WRAPPER = "expo.modules.image.ExpoImageViewWrapper";

    /**
     * {@code ExpoImageView → blurRadius}（v0.1.24）。
     *
     * <p>为什么必须记这张表：{@code blurRadius} 是 JS prop，落在
     * {@code ExpoImageViewWrapper} 上（{@code setBlurRadius$expo_image_release(Integer)}），
     * 而真正被 {@code draw} 的是 {@code ExpoImageView}。两者之间只有
     * {@code getActiveView()} 一条线，所以在 setter 里把「这个 View 是模糊装饰图」记下来，
     * 绘制时直接查表 —— 比在世界里猜「这张图是不是糊的」可靠得多。
     *
     * <p>★★ v0.1.27 用户第 3 / 4 条：<b>这张表只当「待定标记」，真正生效的是 {@link #BLUR_BMP}</b>。
     * 只按 View 记会踩两个坑（r32 真机都出现了）：
     * <ul>
     *   <li><b>视图回收</b>：RN 把播放页那张「模糊封面」的 {@code ExpoImageView} 回收去显示
     *       图片浏览里的普通照片，标记还留着 ⇒ 那张照片被当成装饰底整层顶掉，
     *       用户看到「作品封面不显示」「最后一张图片背后多一层模糊」；</li>
     *   <li><b>prop 不再重设</b>：刷新页面时 {@code setBlurRadius} 不会重调，标记也不会自己消失。</li>
     * </ul>
     * 所以标记留在 View 上，但<b>真正判定用位图</b>：绘制时把「这一刻显示的位图」与当时
     * 的标记绑定到 {@link #BLUR_BMP}。位图是每张图各一份的，视图复用时位图变了 ⇒ 自然不命中。
     */
    static final java.util.Map<View, Long> BLUR_PENDING =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * 位图 → 宿主要求的模糊半径（v0.1.27）。
     *
     * <p>键是「这张位图」，不是「这个 View」—— 这才是「这张图是装饰底」的稳定身份。
     */
    static final java.util.Map<Bitmap, Integer> BLUR_BMP =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static volatile Activity sPoll;
    static volatile boolean sPolling;

    /** 钩子是否装上。没装上就完全没有效果。 */
    static volatile boolean sHooked;

    /** ★ 热路径快速闸门：true = 有壁纸可画（{@link #sWpBmp} 非空）。 */
    static volatile boolean sArmed;


    /** ★ 清晰壁纸位图（已含用户设置的遮罩 / 模糊）。 */
    static volatile Bitmap sWpBmp;

    /** ★ 顶栏 / 底栏用的模糊壁纸位图（30% 模糊）。 */
    static volatile Bitmap sWpBarBmp;

    /**
     * ★ 播放页用的壁纸位图（v0.1.21）：再按 {@link MoriConfig#f2PlayBlur} 糊一层。
     *
     * <p>播放页那张氛围底图是<b>不透明满屏</b>的，整层换成壁纸后如果还是清晰版，
     * 会盖住封面和歌词 ⇒ 单独一份更糊的位图专门给它用。
     */
    static volatile Bitmap sWpPlayBmp;

    /**
     * ★★★ v0.1.39：<b>「糊两遍」的玻璃底图已废弃</b>（删掉 {@code sWpGlassBmp}）。
     *
     * <p>v0.1.38 一度用它让面板「看得出是毛玻璃」，真机结果相反
     * —— 双次模糊把壁纸糊成色块，面板里完全失去背景轮廓，
     * 用户第 1 条反馈就是「概述页面的高斯模糊和之前的效果不一样了」。
     * 现在玻璃统一用 {@code sWpBarBmp}（壁纸 + 20% 单次模糊），见 {@link #glassBmp}。
     */

    /**
     * 本帧「整页底色」用的是哪张位图、到屏幕哪个 Y 结束（v0.1.25 用户第 4 条）。
     *
     * <p>作用只有一个：让<b>紧贴页面底边的那条底栏</b>接着画<b>同一张</b>图。
     * 以前底栏一律画 20% 毛玻璃，而播放页 / 播放列表的页面底色是「壁纸 + 用户模糊档」，
     * 两者模糊度差好几个档 ⇒ 交界处出现一条硬边，用户描述成
     * 「页面下边缘会透出一部分底部导航栏」。
     */
    static volatile int sPageBottom = -1;
    static volatile Bitmap sPageBmp;

    /** 屏幕（DecorView）尺寸：所有层都换算到这套坐标系里画。 */
    static volatile int sSW;
    static volatile int sSH;

    /** 满屏门槛（根视图面积 × {@link #FULL_SCREEN_RATIO}），一次乘法比较。 */
    static volatile long sMinArea = Long.MAX_VALUE;

    /** 顶栏 / 底栏的像素门槛（由 apply 按密度算好）。 */
    static volatile int sEdgeTop;
    static volatile int sEdgeBottom;
    static volatile int sBarMaxH;
    static volatile int sFullW;

    /** 上一帧是否真的把壁纸画上去过（只用于反馈 / 诊断）。 */
    static volatile boolean sPainted;

    /** 需要重新判一次并让视图树重画。 */
    static volatile boolean sDirty = true;

    /**
     * ★★★ v0.1.90：{@code armed} 由「开」掉到「关」的连续轮数（防抖）。
     *
     * <p>为什么需要：{@link #apply} 每 {@value #POLL_MS} 毫秒跑一次，判据是
     * {@code ready = want && 位图可用}。壁纸位图在切歌 / 新封面解码 / 系统内存压力下
     * 会被回收或正在重解码，于是 {@code ready} 会短暂掉成 false、下一轮又回来。
     * 旧版只要 {@code ready != sArmed} 就 {@link #invalidateTree} 整棵 DecorView
     * （最多 800 个节点）⇒ 那两轮各重录一次全树。
     *
     * <p>真机 trace 实测（2.22.2 / 60s / 跨作品切歌）：每次切歌都出现两帧
     * <b>266.8ms / 207.2ms</b>，间隔 1.73s ≈ 一个轮询周期；整棵树从 DecorView 到
     * {@code ReactScrollView} 每一层都是 261ms（首页 3 排横向列表全部重录）。
     * 关掉插件后同位置最长帧 <b>6.3ms</b>。
     *
     * <p>所以「想要壁纸、但这一轮暂时拿不到位图」绝不能当成「已经关掉了」——
     * 连续 {@value #ARM_DROP_CONFIRM} 轮（≈4.5 秒）都拿不到才认账。
     */
    static volatile int sArmDropTicks;

    /** {@link #sArmDropTicks} 需要连续几轮才真的翻转（4.5 秒，扛得住解码与 GC 抖动）。 */
    static final int ARM_DROP_CONFIRM = 3;

    /** v0.1.90 诊断：整窗重绘 / 轻量重绘各发生了几次（打日志用）。 */
    static volatile int sFullRedraws;
    static volatile int sLightRedraws;

    /** v0.1.90 诊断日志已打印条数（限量，别刷屏）。 */
    static volatile int sDbgRedraw;

    /** {@link #sDbgRedraw} 的条数上限。 */
    static final int DBG_REDRAW_BUDGET = 40;

    /**
     * ★★★ v0.1.92 诊断：{@code View#draw} 钩子里**我们自己**花掉的时间。
     *
     * <p>trace 里 174ms 全部落在 {@code ScreenStack} 自己的 {@code Record View#draw()}
     * 上，子 View 加起来不到 1ms ⇒ 不是绘制、不是子层重录，而是<b>钩子代码本身</b>。
     * 这个钩子对<b>每一个</b> View 都跑，一帧几百个 View，单次 0.2ms 就是 160ms。
     */
    static volatile int sSlowDraw;
    static final int SLOW_DRAW_BUDGET = 24;

    static int sLogs;
    static long sSeen;
    static long sSkipped;
    static long sLeaf;
    static long sBar;
    static long sGrad;
    static long sOverlay;
    static long sScrim;
    static long sVeil;
    static long sAtmos;

    /** 「封面模糊底图换成壁纸+模糊」次数（v0.1.24）。 */
    static long sBlurBg;
    static long sSheet;
    /** 因「缩放转场」改画毛玻璃的层数（v0.1.23）。 */
    static long sZoom;
    static long sLastSummaryAt;

    /** 每次命中最多打几条「画在哪一层」的日志。 */

    /** 满屏层 / 顶底栏清单已打的行数。 */
    static int sBigLogs;

    /** expo-image 位图分类已打的行数（v0.1.21，每个位图一次）。 */
    static int sImgLogs;
    static final int IMG_LOG_BUDGET = 10;

    /** 已经登记过的全宽图片层类名（v0.1.21 诊断）。 */
    static final java.util.Set<String> sImgSeen =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** 已登记过的层类名（避免同一类刷屏）。 */
    static final java.util.Set<String> sBigSeen =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private BgPeel() {
    }

    /** 装全部「把宿主满屏底原地换成壁纸 / 氛围图去紫 / 面板只糊自己」的钩子。 */
    public static void install(ClassLoader cl) {
        BgLeaf.hookDrawable(cl, CLS_BG_DRAWABLE, true);
        BgLeaf.hookDrawable(cl, CLS_BG_IMAGE, false);
        hookColorDrawable();
        BgLeaf.hookGradientView(cl);
        hookViewDraw(cl);
        CoverLayer.hookExpoImage(cl);
        // ★★★ v0.1.57（r57 真机层级 dump 定位）：**播放页那块横幅是 Skia 画布**。
        //
        //   `dumpsys activity top` 在播放页抓到：
        //   <pre>
        //   ReactViewGroup   0,0-1272,954  #8b8
        //   ReactViewGroup   0,0-1272,954  #2560
        //   SkiaPictureView  0,0-1272,954  #25d0      ← react-native-skia
        //   SkiaTextureView  0,0-1272,954
        //   ReactViewGroup   0,0-1272,954  #878  alpha=0.0
        //   </pre>
        //   它不是 {@code ExpoImageView}、也不是普通 View —— 它是
        //   {@code TextureView}，内容由 Skia 在自己的线程里渲染后
        //   <b>直接交给合成器</b>，根本不走 {@code View#draw}。
        //   所以前几版所有挂在 draw / blurRadius / 位图 / 几何上的判据<b>全部无效</b>
        //   （日志里 `paint play blur big` 始终 0 条就是这个原因）。
        //   唯一能拦住它的地方是 {@code TextureView#draw(Canvas)}（父层 dispatchDraw 会调）。
        BgLeaf.hookTextureView(cl);
        CoverLayer.hookDraweeCover(cl);
        hookImageBlur(cl);
        // ★★★ v0.1.42（r13）：**宿主自带的毛玻璃层（expo-blur）必须一起关掉**。
        //   这一条是「为什么有的面板/页面能做上、有的做不上」的最后一个缺口。
        hookExpoBlur(cl);
        // ★★ v0.1.24 用户第 1 条：强调色的唯一挂点（ColorPropConverter + 渐变数组）。
        //   以前挂 TextView#setTextColor 只能改字，宿主满屏的紫色底 / 开关 / 遮罩全漏。
        AccentLut.install(cl);
        refreshMaskPaint();
        // v0.1.70（r73）：播放页静止画面也能被 adb 读到层级（见 startProbeLoop）
        // v0.1.88（r101）：诊断循环停用（r88~r100 的取证到此为止，稳态零额外开销）
        // startProbeLoop();
        // ★★★ v0.1.80（r104 用户第 1 条）：作品文件夹树里的「系统返回键」= 上一层文件夹
        hookBackKey(cl);
        // ★★★ v0.1.85（r109 路径 2）：页面内容「挂载完成」这一事件上重绘一次
        hookScreenContentAdded(cl);
        // ★★★ v0.1.135：月度回顾 Dialog 入窗 → 确认后换自定义底（绕开「底图只录一次」）
        hookWindowAddView(cl);
        hookScreenSwitch(cl);
        hookPagerLight(cl);
        // ★★★ v0.1.77（r91）：空页看门狗（只观测，不改任何绘制）
        // startBlankWatch();
    }


    /**
     * ★★★ v0.1.74（r88 第 2 条「左/右划返回后页面空白」）：<b>页面切换事件 → 整窗补绘。</b>
     *
     * <h3>为什么是「补绘」而不是「少画」</h3>
     * 空白页的特征（用户录屏逐帧确认）：页面区域只剩壁纸，但<b>底部 tab 栏、迷你条都正常</b>，
     * 右边还能看到一条滚动条 ⇒ 页面容器在、内容层没画出来。
     * 这正是 r82 §1 定过的那条：<b>硬件加速下每层有自己的 RenderNode，
     * 我们（或宿主）在那一层「内容还是空的时候」录进了一份显示列表，
     * 之后内容回来了但没人让它重录 ⇒ 屏幕上永远停在空白</b>。
     * r82 给出的解法是「缺的是一次重绘」，而切后台再回来立刻恢复也印证了这点。
     *
     * <h3>为什么挂在 {@code Screen#setActivityState}</h3>
     * <ul>
     *   <li>它是 react-native-screens 每次「页面进出栈」<b>必定</b>调用的一个方法
     *       （手势返回、导航返回、tab 切换都走它），频率是「每次切换 1~2 次」，
     *       不是每帧 ⇒ 不存在 r47 那种「每次绑定都重绘整棵树」的卡顿；</li>
     *   <li>比猜手势可靠得多：左右划返回在 {@code View} 层面只表现为 translationX，
     *       而 translation 不改变 scale，转场探测（看 scale）根本发现不了。</li>
     * </ul>
     * 命中后交给心跳在接下来两拍里各做一次 {@link #invalidateTree}（逐层 invalidate
     * 才会让子层重录；只 invalidate 根的话，子层的 RenderNode 会被原样复用）。
     */
    // ══════════════════════════════════════════════════════════════════
    // ★★★ v0.1.80（r104 用户第 1 条）：系统返回键在「作品文件夹树」里要回上一层
    // ══════════════════════════════════════════════════════════════════

    /** 是否已经吞掉了这次返回键的 ACTION_DOWN（UP 也要一起吞，不能让宿主再处理一次）。 */
    static volatile boolean sBackEat;

    /** 返回键路径诊断日志条数（v0.1.80 定位期用）。 */
    static volatile int sBackKeyLogs;

    /** 「连按 3 次音量-」诊断热键的计数（v0.1.81）。 */
    static volatile int sVolDownHits;
    static volatile long sVolDownAt;

    static int sDumpNodes;
    static int sDumpShown;
    static String sDumpErr;

    /**
     * 把「系统返回键」翻译成「点一下作品页左上那颗返回按钮」。
     *
     * <h3>为什么必须这么绕</h3>
     * 作品页里的文件夹树（面包屑 作品 > 文件夹 > 子文件夹 …）<b>不是路由</b>：
     * 真机实测，在第四层按系统返回键，整页被 pop 回媒体库，
     * 而点左上那颗 `<` 才是「上一层文件夹」（uiautomator 实证它的 id 是
     * {@code work-back-button}，宿主自己实现了逐层上跳）。
     * 也就是说：<b>这颗按钮才是这一页的“返回上一层”</b>，系统返回键只是没人翻译。
     *
     * <h3>为什么不去改导航状态</h3>
     * r103 已经证明「改写 react-native-screens 的页面状态」会把底栏切换整个搞坏
     * （见 {@link #hookScreenSwitch}）。这里只做「把这次按键换成一次点击」，
     * 页面状态仍然全部由宿主自己维护。
     *
     * <h3>边界</h3>
     * <ul>
     *   <li>只有这颗按钮<b>存在且可见</b>时才接管（作品页以外一律原样放过）；</li>
     *   <li>树里只要有可见的<b>压暗遮罩</b>（弹层 / 模态）就让宿主自己处理 ——
     *       否则「返回键关弹层」会变成「返回键退页」；</li>
     *   <li>找不到按钮、id 变了、点击抛异常 ⇒ 一律回退到宿主行为（等于没装）。</li>
     * </ul>
     */
    static void hookBackKey(ClassLoader cl) {
        try {
            int n = 0;
            for (Method m : XposedCompat.findMethodsByName(android.app.Activity.class, "dispatchKeyEvent")) {
                XposedCompat.hookMethod(m, chain -> {
                    try {
                        Object self = chain.getThisObject();
                        Object a0 = chain.getArg(0);
                        if (sArmed && self instanceof Activity && a0 instanceof android.view.KeyEvent) {
                            android.view.KeyEvent ev = (android.view.KeyEvent) a0;
                            // ★ v0.1.81：诊断热键 —— 连按 3 次「音量-」dump 一次当前窗口层级
                            //   （uiautomator 抓不到：这宿主有常驻动画，永远 idle 不了）
                            if (ev.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
                                if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                                    long now = System.currentTimeMillis();
                                    if (now - sVolDownAt < 1200L) sVolDownHits++;
                                    else sVolDownHits = 1;
                                    sVolDownAt = now;
                                    if (sVolDownHits >= 3) {
                                        sVolDownHits = 0;
                                        BgDiag.dumpWindowTree((Activity) self);
                                    }
                                }
                            }
                            if (ev.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
                                int act = ev.getAction();
                                if (act == android.view.KeyEvent.ACTION_DOWN) {
                                    boolean armed = armBackTap((Activity) self);
                                    if (sBackKeyLogs < 6) {
                                        sBackKeyLogs++;
                                        log("back DOWN armed=" + armed);
                                    }
                                    if (armed) {
                                        sBackEat = true;
                                        return false;          // 吞掉 DOWN
                                    }
                                } else if (act == android.view.KeyEvent.ACTION_UP && sBackEat) {
                                    sBackEat = false;
                                    return false;          // 吞掉 UP
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                n++;
            }
            log("hooked " + n + " Activity#dispatchKeyEvent（作品页返回上一层）");
            // ★ v0.1.81：**弹窗窗口（Dialog）也挂一份诊断热键**。
            //   实测「月度回顾」那一页不是页面栈里的一屏，而是一个 Dialog 窗口：
            //   按键不会走到 Activity#dispatchKeyEvent ⇒ 上一版热键在那里一声不响。
            int dn = 0;
            try {
                for (Method m : XposedCompat.findMethodsByName(android.app.Dialog.class, "dispatchKeyEvent")) {
                    XposedCompat.hookMethod(m, chain -> {
                        try {
                            Object self = chain.getThisObject();
                            Object a0 = chain.getArg(0);
                            if (self instanceof android.app.Dialog && a0 instanceof android.view.KeyEvent) {
                                android.view.KeyEvent ev = (android.view.KeyEvent) a0;
                                if (ev.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                                        && ev.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                                    long now = System.currentTimeMillis();
                                    if (now - sVolDownAt < 1200L) sVolDownHits++;
                                    else sVolDownHits = 1;
                                    sVolDownAt = now;
                                    if (sVolDownHits >= 3) {
                                        sVolDownHits = 0;
                                        BgDiag.dumpDecorTree(((android.app.Dialog) self).getWindow().getDecorView());
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                    dn++;
                }
            } catch (Throwable ignored) {
            }
            log("hooked " + dn + " Dialog#dispatchKeyEvent（诊断热键）");
            // 诊断：宿主的返回到底走不走 onBackPressed（走 ⇒ 返回键不在 dispatchKeyEvent 生效）
            int bn = 0;
            for (Method m : XposedCompat.findMethodsByName(android.app.Activity.class, "onBackPressed")) {
                XposedCompat.hookMethod(m, new XposedCompat.VoidHook() {
                    @Override
                    protected void afterVoid(io.github.libxposed.api.XposedInterface.Chain chain) {
                        if (sBackKeyLogs < 6) {
                            sBackKeyLogs++;
                            log("onBackPressed reached");
                        }
                    }
                });
                bn++;
            }
            log("hooked " + bn + " Activity#onBackPressed（诊断）");
        } catch (Throwable t) {
            log("back key hook 失败: " + t);
        }
    }

    /** 找作品页那颗返回按钮；找不到返回 null。 */
    static View workBackButton(Activity a) {
        try {
            View decor = a.getWindow() == null ? null : a.getWindow().getDecorView();
            if (decor == null) return null;
            // ★ v0.1.80 修正：`nativeID` 不是宿主 APK 里的资源（`getIdentifier` 实测返回 0），
            //   它是 RN 运行时挂在 View 上的 tag / 可访问性 viewId。
            //   两条路都试：tag 快，可访问性兜底。
            View v = findByNativeId(decor, "work-back-button", 0);
            if (v == null || !v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0) return null;
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** RN 的 {@code nativeID} tag 资源 id（第一次解析后缓存；0 = 不存在）。 */
    static volatile int sNativeIdTag = -2;

    static int nativeIdTag(View v) {
        int t = sNativeIdTag;
        if (t != -2) return t;
        int got = 0;
        try {
            // RN 库的 R.id 并进宿主包资源表
            got = v.getResources()
                    .getIdentifier("view_tag_native_id", "id", v.getContext().getPackageName());
        } catch (Throwable ignored) {
        }
        sNativeIdTag = got;
        return got;
    }

    static View findByNativeId(View v, String want, int depth) {
        // ★ v0.1.80：深度上限 16 → 28。RN 这棵树到页头按钮要 18~22 层，
        //   16 会把整个内容区剪掉（实测「一条都没打印」就是这个）。
        if (v == null || depth > 28) return null;
        try {
            // 只看屏幕上方 20%：返回按钮不会在别处，避免整树开销
            int[] loc = LOC.get();
            v.getLocationOnScreen(loc);
            if (loc[1] > (int) (sSH * 0.22f)) return null;
            int tagId = nativeIdTag(v);
            if (tagId != 0) {
                Object tag = v.getTag(tagId);
                if (tag instanceof String && want.equals(tag)) return v;
            }
            try {
                android.view.accessibility.AccessibilityNodeInfo n = v.createAccessibilityNodeInfo();
                if (n != null) {
                    if (want.equals(n.getViewIdResourceName())) return v;
                }
            } catch (Throwable ignored) {
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                int n = g.getChildCount();
                for (int i = 0; i < n; i++) {
                    View r = findByNativeId(g.getChildAt(i), want, depth + 1);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 有可见的压暗遮罩 ⇒ 这次返回键必须交给宿主（弹层优先）。 */
    static boolean dimOverlayShown(Activity a) {
        try {
            View decor = a.getWindow() == null ? null : a.getWindow().getDecorView();
            return decor != null && scanDimOverlay(decor, 0);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean scanDimOverlay(View v, int depth) {
        if (v == null || depth > 12) return false;
        try {
            if (v.getVisibility() == View.VISIBLE && v.getAlpha() > 0.05f) {
                int w = v.getWidth();
                int h = v.getHeight();
                if (w * 100 >= (long) sSW * 90 && h * 100 >= (long) sSH * 70) {
                    Drawable bg = v.getBackground();
                    if (bg != null && isDimMask(bg)) return true;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                int n = g.getChildCount();
                for (int i = 0; i < n; i++) {
                    if (scanDimOverlay(g.getChildAt(i), depth + 1)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 返回键按下时先判「这一页有没有那颗返回按钮」：
     * 有 ⇒ 吞掉这次按键，并在 100ms 后补一次点击（避开按键处理那一帧）。
     */
    static boolean armBackTap(final Activity a) {
        try {
            if (dimOverlayShown(a)) return false;
            View v = workBackButton(a);
            if (v == null) {
                BgDiag.dumpHeaderOnce(a);
                return false;
            }
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        tapWorkBack(a);
                    } catch (Throwable ignored) {
                    }
                }
            }, 100L);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用一次「按下 + 抬起」代替点击（React 的按下态不走 OnClickListener，performClick 无效）。 */
    static boolean tapWorkBack(Activity a) {
        try {
            View v = workBackButton(a);
            if (v == null) return false;
            tapAt(v, a);
            if (sBackTapLogs < 3) {
                sBackTapLogs++;
                log("back key -> work-back-button " + v.getWidth() + "x" + v.getHeight());
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 在窗口根上派发一次「按下 + 抬起」。
     *
     * <p>★ v0.1.80 修正：直接 {@code v.dispatchTouchEvent(...)} 对 React 的 Pressable
     * <b>无效</b>（实测点了没反应）—— RN 的响应者系统是在<b>根视图</b>收到 DOWN 时
     * 用 {@code TouchTargetHelper} 选定目标 tag 的，绕过根就等于没按。
     * 这里改成从 DecorView 派发，坐标用屏幕坐标减去窗口原点换算回去。
     */
    static boolean tapAt(View target, Activity a) {
        try {
            View decor = a.getWindow() == null ? null : a.getWindow().getDecorView();
            if (decor == null) return false;
            int[] sl = new int[2];
            target.getLocationOnScreen(sl);
            int[] dl = new int[2];
            decor.getLocationOnScreen(dl);
            float x = sl[0] - dl[0] + target.getWidth() / 2f;
            float y = sl[1] - dl[1] + target.getHeight() / 2f;
            long t = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent down =
                    android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
            decor.dispatchTouchEvent(down);
            down.recycle();
            android.view.MotionEvent up =
                    android.view.MotionEvent.obtain(t, t + 40L, android.view.MotionEvent.ACTION_UP, x, y, 0);
            decor.dispatchTouchEvent(up);
            up.recycle();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 「返回键改点击」的留痕条数。 */
    static volatile int sBackTapLogs;

    static volatile int sBackDumps;

    // ══════════════════════════════════════════════════════════════════
    // ★★★ v0.1.85（r109 用户：方案 2）页面内容「挂载完成」→ 精确重绘这一页
    // ══════════════════════════════════════════════════════════════════

    /** 上一次「挂载重绘」的时刻（节流）。 */
    static volatile long sMountRepaintAt;

    /** 这条钩子打过的 log 条数。 */
    static volatile int sMountLogs;

    /**
     * 页面切换 / 返回时偶尔还能看到「壁纸是模糊的」——这是因为目标页的背景层在
     * <b>页面内容还没挂完</b>的那一瞬间就被画了一次，硬件加速把这次绘制记进了
     * 它自己的 RenderNode；内容随后挂上，但没有人再 invalidate 那一层 ⇒ 错的那一帧留着。
     *
     * <p>r106 试过「定时乱拍」（ON_TOP 后 160/420ms、回前台 300/700ms），
     * 结果是拍在封面还没挂上的时刻，把播放页封面打没了。
     * 这次换成**事件驱动**：直接把 react-native-screens 的
     * {@code ScreenContentWrapper#onViewAdded} 钩下来 —— 页面内容真正挂上去的那一刻，
     * 才是「内容已就绪」的准确时刻。
     *
     * <h3>护栏（一条都不能少）</h3>
     * <ul>
     *   <li>只重绘**这一页的子树**（不走整窗，底栏 / 迷你条不参与）；</li>
     *   <li>150ms 节流：一次导航里会连着挂好几个子 View，只认第一次；</li>
     *   <li>**播放页直接跳过**（{@link #isPlayWindow} / {@link #playContext}）——
     *       封面的救援路径不能被任何额外重绘打断，这是 r107 那次的教训；</li>
     *   <li>找不到 rns 的类就退回 {@code ViewGroup#onViewAdded} + 类名过滤，
     *       两者都拿不到就什么都不做（等于没装）。</li>
     * </ul>
     */
    static void hookScreenContentAdded(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> cw = null;
            try {
                cw = XposedCompat.findClass("com.swmansion.rnscreens.ScreenContentWrapper", cl);
            } catch (Throwable ignored) {
            }
            if (cw != null) {
                for (Method m : XposedCompat.findMethodsByName(cw, "onViewAdded")) {
                    XposedCompat.hookMethod(m, new XposedCompat.VoidHook() {
                        @Override
                        protected void afterVoid(io.github.libxposed.api.XposedInterface.Chain chain) {
                            try {
                                Object self = chain.getThisObject();
                                if (!(self instanceof View)) return;
                                // ★ r109 修正：ScreenContentWrapper 并没有 override 这个方法，
                                //   findMethodsByName 会回溯到 ViewGroup#onViewAdded ⇒ 这条钩子
                                //   对**所有** ViewGroup 生效（日志第一发就是 DecorView）。
                                //   必须自己按类名过滤，否则等于「每次 addView 都重绘一页」。
                                String nm = ((View) self).getClass().getSimpleName();
                                if (nm == null || !nm.contains("ScreenContentWrapper")) return;
                                repaintPageAfterMount((View) self);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    n++;
                }
            }
            if (n == 0) {
                // 兜底：框架的 ViewGroup#onViewAdded，自己按类名过滤
                for (Method m : XposedCompat.findMethodsByName(android.view.ViewGroup.class, "onViewAdded")) {
                    XposedCompat.hookMethod(m, new XposedCompat.VoidHook() {
                        @Override
                        protected void afterVoid(io.github.libxposed.api.XposedInterface.Chain chain) {
                            try {
                                Object self = chain.getThisObject();
                                if (!(self instanceof View)) return;
                                String nm = ((View) self).getClass().getSimpleName();
                                if (nm != null && nm.contains("ScreenContentWrapper")) {
                                    repaintPageAfterMount((View) self);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    n++;
                }
            }
            log("hooked " + n + " ScreenContentWrapper#onViewAdded（挂载后精确重绘）");
        } catch (Throwable t) {
            log("挂载重绘钩子失败: " + t);
        }
    }

    /** 页面内容挂上来的那一刻：只补文字阴影（v0.1.127），不做整页重绘。 */
    static void repaintPageAfterMount(final View page) {
        // v0.1.118 起整页 mount 重绘停用（20~40ms）。
        // v0.1.127：媒体库 / PurePlay / 设定等页首屏文字不进 arm 时那棵树，
        //   只靠 draw 钩里的 Paint 阴影又会被 RN setTextColor 冲掉 ——
        //   这里只排两拍 TextView 阴影刷新，不动列表容器。
        TextShadow.scheduleShadowRefresh(page, "mount");
    }

    /** 切页 / 挂载后排两拍文字阴影刷新（节流，播放页不碰）。 */
    static volatile long sShadowSchedAt;

    // ══════════════════════════════════════════════════════════════════
    // ★★★ v0.1.135：月度回顾 Dialog 蓝紫底 → 自定义壁纸
    //   入口是 WindowManagerGlobal#addView（Dialog#show 在该 RN 版本不触发）。
    //   底图只录一次 RenderNode，必须在确认后主动 invalidate 那一层再换画。
    // ══════════════════════════════════════════════════════════════════

    /** 已确认的月度回顾窗口 token（与 {@link #sReviewBgRef} 成对）。 */
    static volatile android.os.IBinder sReviewToken;
    /** 月度回顾满屏底图（ReactImageView / ExpoImageView）。 */
    static volatile java.lang.ref.WeakReference<View> sReviewBgRef;
    /** 最近一次入窗 / 兜底扫描时刻（节流）。 */
    static volatile long sReviewProbeAt;
    /** 上次看到的窗口数量（兜底：窗口数增加才扫）。 */
    static volatile int sReviewWinCount = -1;
    /** 入窗时还没 armed ⇒ 等壁纸就绪后再扫一遍。 */
    static volatile boolean sReviewRescan;
    /** 诊断日志条数上限。 */
    static volatile int sReviewLogs;
    static volatile int sReviewHitLogs;
    static final int REVIEW_LOG_BUDGET = 24;

    /**
     * Hook {@code WindowManagerGlobal#addView}：Dialog 真正入窗的时刻。
     * 不在这里立刻换图——文案 / CD 往往稍后才挂上，排 80/280ms 两拍确认。
     */
    static void hookWindowAddView(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal", false, cl);
            Method[] ms = wmg.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                Method m = ms[i];
                if (!"addView".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length < 2 || !View.class.isAssignableFrom(pt[0])) continue;
                m.setAccessible(true);
                XposedCompat.hookMethod(m, new XposedCompat.VoidHook() {
                    @Override
                    protected void afterVoid(io.github.libxposed.api.XposedInterface.Chain chain) {
                        try {
                            Object a0 = chain.getArg(0);
                            if (!(a0 instanceof View)) return;
                            maybeArmReviewProbe((View) a0, "addView");
                        } catch (Throwable ignored) {
                        }
                    }
                });
                n++;
            }
            log("hooked " + n + " WindowManagerGlobal#addView（月度回顾入窗）");
            if (n == 0) {
                log("review bg unsupported（addView 钩不到）");
            }
        } catch (Throwable t) {
            log("review bg unsupported（WindowManagerGlobal）: " + t);
        }
    }

    /**
     * TextView 画出月度回顾文案时调用：底图多半已经录进 RenderNode，
     * 立刻对所在窗做一次确认 + invalidate。
     */
    static void noteReviewCueText(TextView tv) {
        try {
            if (!sArmed || tv == null) return;
            if (!CoverLayer.cueHit(tv, MONTHLY_REVIEW_CUES)) return;
            if (!inDialogWindow(tv)) return;
            View root = tv.getRootView();
            if (root == null) return;
            android.os.IBinder tok = root.getWindowToken();
            View marked = sReviewBgRef == null ? null : sReviewBgRef.get();
            if (tok != null && tok == sReviewToken && marked != null) return;
            // 文案已在，直接探（不再干等 700ms）
            BgDiag.probeReviewDialog(root, "cueText");
        } catch (Throwable ignored) {
        }
    }

    /** addView 后：过滤候选 Dialog 根，排两拍确认。 */
    static void maybeArmReviewProbe(View root, String why) {
        if (root == null) return;
        try {
            if (!isReviewDialogCandidate(root)) return;
            if (PlayPage.isPlayWindow(root) || PlayPage.playContext(root)) return;
            // 壁纸可能还没就绪（冷启动开回顾）—— 仍排确认；probe 里会记 rescan。
            if (!sArmed) sReviewRescan = true;
            scheduleReviewProbe(root, why);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 非主窗口 +（Dialog 痕迹或近满屏）候选。
     * <p>addView 当下 DecorView 往往还没挂上 {@code DialogRootViewGroup}，
     * 不能只认类名；确认阶段再用 cues / 结构收紧。
     */
    static boolean isReviewDialogCandidate(View root) {
        if (root == null) return false;
        try {
            // Float 悬浮字幕 / 其它模块窗绝不是月度回顾。
            if (foreignModuleView(root) || isForeignOrPopupRoot(root)) return false;
            android.view.ViewGroup.LayoutParams lp = root.getLayoutParams();
            if (lp instanceof android.view.WindowManager.LayoutParams) {
                int t = ((android.view.WindowManager.LayoutParams) lp).type;
                if (t == android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION) {
                    return false;
                }
            }
            if (hasDialogNameNear(root, 0)) return true;
            int w = root.getWidth();
            int h = root.getHeight();
            // addView 当下可能还没 layout；宽度为 0 也先排确认。
            if (w <= 0 || h <= 0) return true;
            if (sSW > 0 && sSH > 0) {
                return w * 100 >= (long) sSW * 55 && h * 100 >= (long) sSH * 55;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Popup / Float 字幕窗：别当月度回顾候选（日志里大量 FloatingSubtitleView miss）。 */
    static boolean isForeignOrPopupRoot(View root) {
        if (root == null) return false;
        try {
            String n = root.getClass().getName();
            if (n == null) return false;
            if (n.contains("FloatingSubtitle") || n.contains("PopupDecorView")) return true;
            if (n.contains("PopupWindow")) return true;
            return foreignModuleView(root);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★★★ v0.1.146：帮助 / 隐私政策 / 使用条款里的 {@code WebView}。
     * 它 kids=0 但自己画 HTML —— 绝不能当「满屏叶子」铺壁纸盖住正文。
     */
    static boolean isWebContentView(View v) {
        if (v == null) return false;
        try {
            String n = v.getClass().getName();
            if (n == null) return false;
            return n.contains("WebView") || n.contains("WebChrome")
                    || n.endsWith("WebViewWrapper") || n.contains("RNCWebView");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 是不是「近满屏模态压暗」—— 短条字幕底 / 设定列表行绝不能当 dim 丢掉。
     * 日志：`drop dim bg -> ReactViewGroup 1152x166` 就是设定行被误杀。
     */
    static boolean isModalDimSurface(View v, Drawable d) {
        if (!isDimMask(d)) return false;
        if (v == null) return false;
        try {
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0) {
                Rect b = d.getBounds();
                if (b != null && !b.isEmpty()) {
                    w = b.width();
                    h = b.height();
                }
            }
            if (w <= 0 || h <= 0) return false;
            if (sSW <= 0 || sSH <= 0) {
                // 没度量时只认「很大」的 bounds，避免误杀字幕条
                return w >= 400 && h >= 400;
            }
            // 模态 scrim：近满宽 + 至少约半屏高（歌词条 / 设定行远不够）
            return w * 100 >= (long) sSW * 80 && h * 100 >= (long) sSH * 40;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 根及其近几层子节点类名是否含 Dialog。 */
    static boolean hasDialogNameNear(View v, int depth) {
        if (v == null || depth > 3) return false;
        try {
            String cn = v.getClass().getName();
            if (cn != null && cn.contains("Dialog")) return true;
        } catch (Throwable ignored) {
        }
        if (!(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        int n = Math.min(g.getChildCount(), 6);
        for (int i = 0; i < n; i++) {
            if (hasDialogNameNear(g.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }

    static void scheduleReviewProbe(final View root, final String why) {
        if (root == null) return;
        long now = System.currentTimeMillis();
        // 同一次打开不要刷一串；不同 why 仍允许（兜底扫描）
        if (now - sReviewProbeAt < 60L && "addView".equals(why)) return;
        sReviewProbeAt = now;
        // ★★★ v0.1.146：首拍立刻探（满屏底常比文案先挂），缩短紫底闪现；
        //   后几拍仍兜文案 / CD 晚到。旧 80/280/700/1400 首帧紫底能挂很久。
        final long[] delays = {0L, 48L, 160L, 400L, 900L};
        for (final long delay : delays) {
            final long d = delay;
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    BgDiag.probeReviewDialog(root, why + "@" + d);
                }
            }, delay);
        }
    }

    /** a11y 文案扫 MONTHLY_REVIEW_CUES（RN 字常挂在无障碍节点上）。 */
    static boolean a11yHasMonthlyCue(View v, int depth, int visited) {
        if (v == null || depth > 16 || visited > 160) return false;
        try {
            CharSequence desc = v.getContentDescription();
            if (CoverLayer.cueHit(desc, MONTHLY_REVIEW_CUES)) return true;
            android.view.accessibility.AccessibilityNodeInfo n = v.createAccessibilityNodeInfo();
            if (n != null) {
                try {
                    if (CoverLayer.cueHit(n.getText(), MONTHLY_REVIEW_CUES)) return true;
                    if (CoverLayer.cueHit(n.getContentDescription(), MONTHLY_REVIEW_CUES)) return true;
                } finally {
                    try {
                        n.recycle();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (!(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        int n = g.getChildCount();
        if (n > 12) n = 12;
        int next = visited + 1;
        for (int i = 0; i < n; i++) {
            if (a11yHasMonthlyCue(g.getChildAt(i), depth + 1, next + i)) return true;
        }
        return false;
    }

    /**
     * 结构兜底：Dialog 内一张贴顶满屏图 +（同窗有回顾 CD 形，或近满屏 Dialog）。
     * <p>★★★ v0.1.146：非中文语言文案对不上时，近满屏 Dialog + 满屏底图即可确认，
     * 不必干等 CD / 本地化文案；半屏弹层仍要 CD，挡住 PurePlay / 导入窗。
     */
    static boolean structuralMonthlyReview(View root) {
        if (root == null) return false;
        // addView 拿到的常是 DecorView；Dialog* 在子树里，不能只向上找。
        if (!inDialogWindow(root) && !isReviewDialogCandidate(root)) return false;
        if (isForeignOrPopupRoot(root)) return false;
        View bg = findFullscreenReviewBackdrop(root, 0, 0);
        if (bg == null) return false;
        if (findReviewCdInTree(root, 0, 0)) return true;
        // 近满屏 Dialog：月度回顾窗本身；导入/下载等半屏卡到不了这里
        try {
            int rw = root.getWidth();
            int rh = root.getHeight();
            if (sSW > 0 && sSH > 0 && rw > 0 && rh > 0
                    && rw * 100 >= (long) sSW * 88
                    && rh * 100 >= (long) sSH * 88) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean findReviewCdInTree(View v, int depth, int visited) {
        if (v == null || depth > 8 || visited > 48) return false;
        if (CoverLayer.isReviewCdShape(v) && CoverLayer.looksLikeCoverArt(v)) return true;
        if (!(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        int n = g.getChildCount();
        if (n > 10) n = 10;
        int next = visited + 1;
        for (int i = 0; i < n; i++) {
            if (findReviewCdInTree(g.getChildAt(i), depth + 1, next + i)) return true;
        }
        return false;
    }

    /** 找 Dialog 内 @0,0 且 ≥92%×92% 窗口的图片层。 */
    static View findFullscreenReviewBackdrop(View v, int depth, int visited) {
        if (v == null || depth > 10 || visited > 80) return null;
        if (isFullscreenReviewBackdrop(v)) return v;
        if (!(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        int n = g.getChildCount();
        if (n > 12) n = 12;
        int next = visited + 1;
        for (int i = 0; i < n; i++) {
            View hit = findFullscreenReviewBackdrop(g.getChildAt(i), depth + 1, next + i);
            if (hit != null) return hit;
        }
        return null;
    }

    static boolean isFullscreenReviewBackdrop(View v) {
        if (v == null) return false;
        try {
            if (!CoverLayer.looksLikeCoverArt(v)) return false;
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0) return false;
            View root = v.getRootView();
            if (root == null) return false;
            int rw = root.getWidth();
            int rh = root.getHeight();
            if (rw <= 0 || rh <= 0) return false;
            if (w * 100 < (long) rw * 92 || h * 100 < (long) rh * 92) return false;
            int[] loc = LOC.get();
            v.getLocationOnScreen(loc);
            int vx = loc[0];
            int vy = loc[1];
            root.getLocationOnScreen(loc);
            int dx = vx - loc[0];
            int dy = vy - loc[1];
            // 贴窗顶角（允许状态栏级偏差）
            return dx <= 8 && dy <= 24;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * apply 兜底：窗口数增加，或入窗时还没 armed 现在刚就绪，扫非主窗 Dialog。
     *
     * @param force true = 刚武装 / 需要补扫，忽略窗口数是否变化
     */
    static void scanReviewWindowsIfGrew(boolean force) {
        if (!sArmed) {
            clearReviewBgMark();
            return;
        }
        try {
            View[] roots = windowRoots();
            int n = roots.length;
            boolean grew = sReviewWinCount >= 0 && n > sReviewWinCount;
            sReviewWinCount = n;
            if (!force && !grew && !sReviewRescan) return;
            sReviewRescan = false;
            for (int i = 0; i < n; i++) {
                View r = roots[i];
                if (r == null || !r.isShown()) continue;
                if (!isReviewDialogCandidate(r)) continue;
                if (PlayPage.isPlayWindow(r) || PlayPage.playContext(r)) continue;
                scheduleReviewProbe(r, force ? "armScan" : "mViews");
            }
        } catch (Throwable ignored) {
        }
    }

    static View[] windowRoots() {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field f = wmg.getDeclaredField("mViews");
            f.setAccessible(true);
            Object v = f.get(inst);
            if (v instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) v;
                java.util.ArrayList<View> out = new java.util.ArrayList<>(list.size());
                for (int i = 0; i < list.size(); i++) {
                    Object o = list.get(i);
                    if (o instanceof View) out.add((View) o);
                }
                return out.toArray(new View[0]);
            }
        } catch (Throwable ignored) {
        }
        return new View[0];
    }

    static void clearReviewBgMark() {
        sReviewToken = null;
        sReviewBgRef = null;
        sReviewHitLogs = 0;
    }

    static void reviewLog(String msg) {
        if (sReviewLogs >= REVIEW_LOG_BUDGET) return;
        sReviewLogs++;
        log(msg);
    }

    /** @deprecated 挂载重绘已停用；保留原实现备查。 */
    @SuppressWarnings("unused")
    static void hookScreenSwitch(ClassLoader cl) {
        try {
            Class<?> sc = Class.forName("com.swmansion.rnscreens.Screen", false, cl);
            int n = 0;
            for (final Method m : sc.getDeclaredMethods()) {
                if (!"setActivityState".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 1) continue;
                try {
                    final Method mm = m;
                    android.util.Log.i("MoriSnap", "setActivityState sig="
                            + java.util.Arrays.toString(m.getParameterTypes()));
                    XposedCompat.hookMethod(m, new XposedCompat.VoidHook() {
                        @Override
                        protected void afterVoid(io.github.libxposed.api.XposedInterface.Chain chain) {
                            try {
                                Object self = chain.getThisObject();
                                Object arg = chain.getArg(0);
                                String st = arg instanceof Enum ? ((Enum<?>) arg).name() : String.valueOf(arg);
                                View screen = self instanceof View ? (View) self : null;
                                PlayPage.armPageRepaint(screen, st);
                                // ★★★ v0.1.127：屏到前台时补阴影（arm 时树还没这页）。
                                if (screen != null && st != null
                                        && (st.contains("ON_TOP") || st.contains("ACTIVE"))) {
                                    TextShadow.scheduleShadowRefresh(screen, "screen:" + st);
                                }
                                if (sActLeft > 0) {
                                    sActLeft--;
                                    android.util.Log.i("MoriSnap", "activityState " + st
                                            + (self instanceof View
                                                ? " " + ((View) self).getWidth() + "x"
                                                  + ((View) self).getHeight() : ""));
                                }
                                // ★★★ v0.1.80（r104）：**r103 的「强制留在 ON_TOP」已删除。**
                                //
                                //   r103 的做法：只要某一屏被 setActivityState(INACTIVE/BELOW)，
                                //   就立刻在 after 钩子里把它改回 ON_TOP，想让页面「一直挂在树里」
                                //   从而没有返回空窗。真机实测的后果（用户 r104 第 1 / 3 条）：
                                //   <pre>
                                //   点「设定」→ 立即收听也还是 ON_TOP；再点「媒体库」→ 设定又变回 ON_TOP
                                //   ⇒ ScreenContainer 里越挂越多（实测 kids=3 后停住），
                                //   而子 View 的绘制顺序按挂载索引走 ⇒ 最后访问的那一屏永远盖在最上面
                                //   ⇒ 底栏点左边没反应、作品夹从深处返回直接落到主页。
                                //   </pre>
                                //   即：这是**改写宿主导航状态机**，不是渲染层的事。
                                //   本插件只做视觉，导航状态一律交还 react-native-screens。
                                //   返回空窗（用户第 5 条）改用「不摘页」这条非侵入路径处理，
                                //   见 {@link #tryDetachOff}。
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
            log("screen switch hook -> " + n);
            android.util.Log.i("MoriSnap", "screen hook install n=" + n + " cls=" + sc.getName());
        } catch (Throwable t) {
            log("screen switch hook miss: " + t);
            android.util.Log.i("MoriSnap", "screen hook miss: " + t);
        }
    }

    /** 切页降载截止时间戳（uptime ms）。 */
    static volatile long sLightUntil;

    /**
     * 媒体库「作品 / 社团 / 声优 / …」横滑降载。
     * <p>★★★ v0.1.153：除 ViewPager(2) 外，再钩 RN {@code ReactHorizontalScrollView}
     *   （日志里媒体库横滑容器就是它）与系统 HorizontalScrollView；
     *   只在 MOVE 且横向位移过阈值后降载，避免 DOWN 空一帧闪当前页。
     */
    static void hookPagerLight(ClassLoader cl) {
        String[] names = {
                "androidx.viewpager2.widget.ViewPager2",
                "androidx.viewpager.widget.ViewPager",
                "android.support.v4.view.ViewPager",
                "com.facebook.react.views.scroll.ReactHorizontalScrollView",
                "android.widget.HorizontalScrollView",
        };
        int n = 0;
        for (String name : names) {
            try {
                Class<?> cls = Class.forName(name, false, cl);
                for (final java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    String mn = m.getName();
                    boolean scroll = "setCurrentItem".equals(mn)
                            || "onPageScrolled".equals(mn)
                            || "pageScrolled".equals(mn)
                            || "onScrollStateChanged".equals(mn)
                            || "onScrollChanged".equals(mn);
                    if (!scroll) continue;
                    try {
                        XposedCompat.hookMethod(m, new XposedCompat.VoidHook() {
                            @Override
                            protected void afterVoid(io.github.libxposed.api.XposedInterface.Chain chain) {
                                PlayPage.beginLightNav(280);
                            }
                        });
                        n++;
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    java.lang.reflect.Method touch = cls.getDeclaredMethod(
                            "onTouchEvent", android.view.MotionEvent.class);
                    XposedCompat.hookMethod(touch, (io.github.libxposed.api.XposedInterface.Hooker) chain -> {
                        try {
                            Object e = chain.getArg(0);
                            if (e instanceof android.view.MotionEvent) {
                                maybeLightFromHorizontalMove((android.view.MotionEvent) e);
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
            }
        }
        log("pager light hook -> " + n);
    }

    /** 横滑起手坐标（仅 ACTION_DOWN 记录）。 */
    static volatile float sLightDownX;
    static volatile float sLightDownY;
    static volatile boolean sLightDown;

    /**
     * 横向 MOVE 过阈值才开 lightNav（竖滑媒体库列表不误触）。
     */
    static void maybeLightFromHorizontalMove(android.view.MotionEvent e) {
        if (e == null) return;
        int a = e.getActionMasked();
        if (a == android.view.MotionEvent.ACTION_DOWN) {
            sLightDownX = e.getX();
            sLightDownY = e.getY();
            sLightDown = true;
            return;
        }
        if (a == android.view.MotionEvent.ACTION_UP
                || a == android.view.MotionEvent.ACTION_CANCEL) {
            sLightDown = false;
            return;
        }
        if (a != android.view.MotionEvent.ACTION_MOVE || !sLightDown) return;
        float dx = Math.abs(e.getX() - sLightDownX);
        float dy = Math.abs(e.getY() - sLightDownY);
        // 约 12dp：横向主导才降载（密度按本机 ~3 估，偏小无伤）
        float dens = sSW > 0 ? (sSW / 360f) : 3f;
        if (dens < 1.5f) dens = 3f;
        float slop = 12f * dens;
        if (dx >= slop && dx > dy * 1.2f) {
            PlayPage.beginLightNav(280);
        }
    }

    static String name0(Object o) {
        try {
            return o.getClass().getSimpleName();
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * 压暗蒙层的颜色：跟着强调色走，<b>alpha 与宿主原设定完全一致</b>
     * （{@link FeatureF2#BAR_MASK_PCT}，8%）。
     */
    static void refreshMaskPaint() {
        try {
            int a = (int) (FeatureF2.BAR_MASK_PCT / 100f * 255f);
            MASK_PAINT.setColor(AccentColor.on() ? AccentColor.shadow(a) : Color.BLACK);
            MASK_PAINT.setAlpha(a);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 强调色变了（换风格 / 换壁纸 / 开关切换）。
     *
     * <p>必须把所有「按旧颜色算过的东西」作废：阴影缓存是<b>按 View 记半径</b>的，
     * 不清掉就只会因为「半径没变」而跳过重设，颜色永远停在旧值上（这正是
     * 上一轮「白底按钮阴影修不掉」的同类陷阱）。
     */
    static void onAccentChanged() {
        try {
            synchronized (SHADOWED) {
                SHADOWED.clear();
            }
            synchronized (ACCENT_SEEN) {
                ACCENT_SEEN.clear();
            }
            LIGHT_BG.clear();
            refreshMaskPaint();
            sDirty = true;
            Activity a = sPoll;
            if (a != null) {
                View root = decorOf(a);
                // ★★ v0.1.26 用户第 4 条：**把已经烘死在 View 里的紫重新过一遍 LUT**。
                //   Paint#setColor 只在「设色那一刻」被调到：TextView 的文字色、
                //   背景 drawable 里的 int 都是**设置一次就一直用**。于是那些在
                //   「强调色还没生效」（壁纸偏黑 ⇒ seed none ⇒ off）时创建的界面，
                //   颜色永远停在宿主紫 —— 用户看到的就是「还有部分文字是淡紫色」。
                //   换壁纸 / 换风格 / 开开关都走这里，重新算一遍就干净了。
                // ★ v0.1.91：retintTree 现在**谁变了谁 invalidate**，
                //   这里不再整树重绘（那一下就是 800 个 View、200ms）。
                retintTree(root, 0);
                // ★★★ v0.1.126：强调色一变，阴影色也变 —— 主动给树上文字补阴影，
                //   不等人手滑动才触发 View#draw（用户报「字体阴影没有及时渲染」）。
                TextShadow.refreshTextShadows(root, "accent");
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 深度优先把树上「烘死的颜色」重新过一遍强调色 LUT（限深 40 / 限数 1200）。
     *
     * <p>只做两件事：TextView 的当前文字色、View 自己那块<b>实心</b>背景的 int 字段。
     * 二者都是幂等的（{@code sRev} 会把上一套强调色还原成宿主原色再重算），
     * 所以可以随便跑多少遍。
     */
    static int retintTree(View v, int depth) {
        if (v == null || depth > 40) return 0;
        int n = 0;
        try {
            if (v instanceof TextView) {
                TextView tv = (TextView) v;
                android.content.res.ColorStateList csl = tv.getTextColors();
                if (csl != null && !csl.isStateful()) {
                    // 单色（绝大多数正文 / 说明文字）：直接换掉整条 CSL 的唯一颜色。
                    int c = csl.getDefaultColor();
                    int m = AccentColor.remap(c);
                    if (m != c) {
                        tv.setTextColor(m);
                        // setTextColor → updateTextColors 只认 mShadow*；清缓存让
                        // 随后的 ensureShadow / refreshTextShadows 重新走 TextView API。
                        SHADOWED.remove(tv);
                        n++;
                    }
                } else {
                    // 有状态色（选中 / 按下会变色）：**绝不能** setTextColor，
                    // 那会把整条状态列表压成单色（选中态就废了）。只改当前这支画笔。
                    android.text.TextPaint p = tv.getPaint();
                    if (p != null) {
                        int c = p.getColor();
                        int m = AccentColor.remap(c);
                        if (m != c) {
                            p.setColor(m);
                            n++;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Drawable d = v.getBackground();
            if (d != null) {
                Integer cur = solidColorOf(d);
                if (cur != null) {
                    int m = AccentColor.remap(cur);
                    if (m != cur) {
                        writeColor(d, m);
                        n++;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // ★★★ v0.1.91：**改完就地作废这一层**。
        //   `TextPaint#setColor` 与 `writeColor` 都是「改那个 int」，不会触发
        //   View 的 display list 重录；旧版靠最后 invalidateTree(整棵 DecorView)
        //   兜底，一兜就是 800 个 View、200ms+。改成谁变了谁 invalidate，
        //   配置变更这条路上再也不用整树重绘。
        //   注意：此刻 n 里只有**本层**的改动数（子 View 的还没加进来）。
        if (n > 0) {
            try {
                v.invalidate();
            } catch (Throwable ignored) {
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int count;
            try {
                count = g.getChildCount();
            } catch (Throwable t) {
                return n;
            }
            for (int i = 0; i < count && n < 1200; i++) {
                try {
                    n += retintTree(g.getChildAt(i), depth + 1);
                } catch (Throwable ignored) {
                }
            }
        }
        return n;
    }

    /** 把颜色写回 drawable 的「那个 int 字段」，并让它重绘一次。 */
    static void writeColor(Object d, int argb) {
        java.lang.reflect.Field f = colorField(d.getClass());
        if (f == null) return;
        try {
            if (!f.isAccessible()) f.setAccessible(true);
            f.setInt(d, argb);
        } catch (Throwable ignored) {
            return;
        }
        try {
            ((Drawable) d).invalidateSelf();
        } catch (Throwable ignored) {
        }
    }

    /** drawable 类 → 它的背景色 int 字段（按类缓存，热路径不做反射扫描）。值是 Field 或 {@link #NONE}。 */
    static final java.util.Map<Class<?>, Object> COLOR_FIELD =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 「这个类找不到可用颜色字段」的哨兵（缓存下来，别每次都重新扫一遍）。 */
    static final Object NONE = new Object();

    /**
     * 找这个类「存背景色的那个 int 字段」。
     *
     * <p>优先名字里带 color 的（没被混淆的情况）；否则要求<b>实例 int 字段恰好只有一个</b>
     * —— {@code BackgroundDrawable} 正是如此（{@code h : I}）。多于一个且都不带 color
     * 就放弃：宁可这块底色不跟着变，也不能把 {@code strokeWidth} 之类的数值当颜色写坏。
     */
    static java.lang.reflect.Field colorField(Class<?> cls) {
        Object cached = COLOR_FIELD.get(cls);
        if (cached != null) return cached == NONE ? null : (java.lang.reflect.Field) cached;
        java.lang.reflect.Field only = null;
        int count = 0;
        try {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (f.getType() != int.class) continue;
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getName().toLowerCase(java.util.Locale.ROOT).contains("color")) {
                    f.setAccessible(true);
                    COLOR_FIELD.put(cls, f);
                    return f;
                }
                if (only == null) only = f;
                count++;
            }
            if (only != null && count == 1) {
                only.setAccessible(true);
                COLOR_FIELD.put(cls, only);
                return only;
            }
        } catch (Throwable ignored) {
            COLOR_FIELD.put(cls, NONE);
            return null;
        }
        COLOR_FIELD.put(cls, NONE);   // 不唯一 ⇒ 永久放弃这个类
        return null;
    }

    /**
     * 给一个 Drawable 类装「原地换壁纸」的钩子。
     *
     * @param requireOpaque 是否要求 {@code getOpacity() == OPAQUE}。
     *                      纯色底必须要求（半透明遮罩不能被顶掉）；
     *                      渐变层不能要求 —— {@code BackgroundImageDrawable} 的
     *                      {@code getOpacity()} 不一定报 OPAQUE，卡它等于白钩。
     */
    /**
     * ColorDrawable 也要拦：列表白底 / PurePlay 实心主题色经常不是 RN BackgroundDrawable，
     * 只钩后者会留下实心色块，半透遮罩永远被盖住。
     */
    static void hookColorDrawable() {
        try {
            Method draw = android.graphics.drawable.ColorDrawable.class
                    .getDeclaredMethod("draw", Canvas.class);
            draw.setAccessible(true);
            XposedCompat.hookMethod(draw, chain -> {
                try {
                    Object self = chain.getThisObject();
                    Object arg0 = chain.getArg(0);
                    if (self instanceof Drawable && arg0 instanceof Canvas) {
                        View plate = ownerView((Drawable) self);
                        if (plate != null && CoverLayer.opaqueCoverPlate(plate, (Drawable) self)) {
                            return null;
                        }
                        if (paintWallpaper((Drawable) self, (Canvas) arg0, true)) {
                            rememberBgOwner((Drawable) self);
                            return null;
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            log("hooked ColorDrawable#draw");
        } catch (Throwable t) {
            log("钩子未装 ColorDrawable: " + t);
        }
    }

    /**
     * ★ 兜底钩子：{@code android.view.View#draw(Canvas)}。
     *
     * <p>宿主的页面真背景是 {@code LinearGradientView}（{@code kids=0, bg=none}），
     * 它没有 background drawable；另外全屏的纯线条装饰层也只有这条能碰到。
     *
     * <p>顺带承担 <b>文字阴影</b>（v0.1.19 用户第 6 条）：TextView 的 draw 一定经过这里，
     * 在这里加阴影不用去猜 RN 调的是哪个 {@code setText} 重载。
     */
    static void hookViewDraw(ClassLoader cl) {
        try {
            Class<?> viewCls = XposedCompat.findClass("android.view.View", cl);
            Method draw = viewCls.getDeclaredMethod("draw", Canvas.class);
            draw.setAccessible(true);
            XposedCompat.hookMethod(draw, chain -> {
                // ★★★ v0.1.44：这三个要在 try 外面声明 —— 防偷窥的强调色罩
                //   必须在 chain.proceed() **之后**叠加，那时已经出了 try 块。
                View v = null;
                Canvas cv = null;
                /** 这一层是窗口根（DecorView）⇒ 播放页根底铺壁纸用。 */
                boolean veilRoot = false;
                final boolean timeDraw = RemoteConfig.debugLog();
                long tHook = timeDraw ? System.nanoTime() : 0L;
                try {
                    Object self = chain.getThisObject();
                    Object arg0 = chain.getArg(0);
                    v = self instanceof View ? (View) self : null;
                    cv = arg0 instanceof Canvas ? (Canvas) arg0 : null;
                    // ★★★ v0.1.151：横屏视频全屏 Activity 整窗放行（壁纸会盖住 SurfaceView）。
                    if (WindowBg.videoFullscreen()) {
                        return chain.proceed();
                    }
                    // v0.1.121：切底栏 / 媒体库选项卡时整树重画。小块、非文字、非进度条、
                    // 非根的层不可能走封面/大底/阴影 —— 直接放行，少跑一串判定。
                    if (v != null && cv != null && !drawWorkLikely(v)) {
                        elevateLibraryChrome(v);
                        return chain.proceed();
                    }
                    if (v != null) {
                        elevateLibraryChrome(v);
                    }
                    // ★★★ v0.1.153：lightNav 只跳过封面/回顾/玻璃等重活，壁纸绘制路径与稳态一致。
                    //   0.1.152 在这里额外 swapLeaf 会改 compositing ⇒ 「先闪当前页再滑」。
                    if (PlayPage.lightNav()) {
                        if (v != null) {
                            PlayPage.notePlaySeek(v);
                        }
                        if (cv != null && CoverLayer.looksLikeCoverArt(v)
                                && CoverLayer.coverBlocked(v)) {
                            CoverLayer.paintCoverMask(cv, v);
                            return null;
                        }
                        return chain.proceed();
                    }
                    // 播放页跑马灯：每帧钉死位移，挡住漏网的 native 动画。
                    if (v != null) {
                        TrackSwitchRelief.pinFromDraw(v);
                    }
                    // ★★★ v0.1.135：月度回顾满屏蓝紫底 → 自定义壁纸（事件驱动标记后画）。
                    //   ReactImageView 另有 Drawee 钩子；这里兜住走基类 draw 的层。
                    if (cv != null && PanelGlass.paintReviewBg(v, cv)) {
                        return null;
                    }
                    // 回顾文案一出现就补确认（须在 lightNav 短路之前）。
                    if (v instanceof TextView) {
                        noteReviewCueText((TextView) v);
                    }
                    // ★★★ v0.1.44（r15 用户第 6 条）：防偷窥封面块记下来，
                    //   等**宿主画完**再叠强调色罩（见 privacyWash）。
                    // 作品封面屏蔽：不画原图，按需要铺一层半透明主题色。
                    if (cv != null && CoverLayer.coverBlocked(v)) {
                        CoverLayer.paintCoverMask(cv, v);
                        return null;
                    }
                    // PurePlay / 单列：半透遮罩若叠在实心白/主题底上，肉眼仍是实心块。
                    // 这里暂时藏起 background，再强制铺半透；宽条（回顾文案底）绝不进这条。
                    if (cv != null && CoverLayer.coverBlockOn()
                            && CoverLayer.maybeCoverSlot(v)
                            && CoverLayer.opaqueCoverPlate(v) && !CoverLayer.splashWhitePlate(v)) {
                        Drawable bg = null;
                        boolean hid = false;
                        try {
                            bg = v.getBackground();
                            if (bg != null && bg.isVisible()) {
                                bg.setVisible(false, false);
                                hid = true;
                            }
                            Object out = chain.proceed();
                            CoverLayer.paintCoverMask(cv, v, true);
                            return out;
                        } catch (Throwable t) {
                            CoverLayer.paintCoverMask(cv, v, true);
                            return null;
                        } finally {
                            if (hid && bg != null) {
                                try {
                                    bg.setVisible(true, false);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                    veilRoot = v != null && cv != null && v.getRootView() == v;
                    // ★★★ v0.1.59（r59 adb 实测：`paint texture -> SkiaTextureView 1272x2772`
                    //   已经在打，画面却依旧透明）：说明**我们画的那些层本身被宿主
                    //   的透明度/转场吃掉了**（播放页是透明底的模态 Route，展开瞬间
                    //   整棵子树的 alpha 可能还是 0，我们画在子树里的东西自然看不见）。
                    //   唯一不受子树 alpha 影响的地方是**窗口根**：
                    //   在 `chain.proceed()` 之前先铺一层满屏壁纸，
                    //   等于给这个窗口发一张不透明底板 —— 之后子层怎么透明都透不过去。
                    //   窗口级判定用 isPlayWindow/playContext（120 秒 TTL），
                    //   离开播放页后自然失效，不会污染列表页。
                    // ★★★ v0.1.63：判定改用「这一秒刚铺过播放档底图」
                    //   （`sPlayPaintedAt`）—— 根这一层上 playContext/isPlayWindow
                    //   实测经常都是 false，这是 0.1.59 的 `paint root bg` 从未触发的真因。
                    // ★★★ v0.1.79（r93）：**切页空窗用「上一帧画面」盖住。**
                    //   r92 逐帧实证：左右划返回时，目标页要重渲染 ≈300ms，这 300ms 里
                    //   页面区只剩壁纸（tab 栏、滚动条都在）——用户看到的就是「空白页」。
                    //   宿主自己切 Activity 也是这么处理的（任务快照）。这里同源：
                    //   空窗期内把 900ms 前抓下的整窗缩略图按原尺寸盖回去。
                    // ★★★ v0.1.80（r94，按你的建议）：**跟着宿主做「向右划出」**。
                    //   你说得对：宿主返回时是「当前页向右划出」，而我们上一版是把缩略图
                    //   原地钉在窗口根上 —— 静态图不动、页面滑走，看起来还是一块空白，
                    //   只是消失得快点。现在缩略图**按同样的方向与时长（260ms）向右平移 +
                    //   淡出**，与宿主的滑动同向同行，视觉上就是「页面被划出去」。
                    // ★★★ v0.1.86（r99 用户实测：**返回后画面会左右抖一下**）：遮罩已停用。
                    //   原因：抓拍时机永远晚于「页面变空」那一刻（日志里 BLANK 与 snap cap 同毫秒），
                    //   遮罩拿到的是空白帧；它唯一的效果是把「空白」换成「一张会动的空白」，
                    //   于是你看到的是「到上一层之后又左右抖一次」，而空白并没有被盖住。
                    //   结论：遮罩这条路走不通，删掉，不再产生任何额外绘制/抖动。
                    if (veilRoot && sArmed
                            && (System.currentTimeMillis() - sPlayPaintedAt < 1000L)) {
                        try {
                            Bitmap pb = PlayPage.playBmp();
                            if (pb != null && !pb.isRecycled()) {
                                WASH_PAINT.setColor(0xFFFFFFFF);
                                WASH_PAINT.setAlpha(255);
                                WASH_PAINT.setFilterBitmap(true);
                                cv.drawBitmap(pb, null,
                                        dstRect(v.getWidth(), v.getHeight()),
                                        WASH_PAINT);
                                sPainted = true;
                                if (sRootBgLogs < 2) {
                                    sRootBgLogs++;
                                    log("paint root bg -> " + v.getWidth() + "x" + v.getHeight());
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    if (v != null && cv != null) {
                        // ★★★ v0.1.66（r69 回归）：**停用** v0.1.65 的「全屏大容器无差别拉 alpha」。
                        //   它确实让播放页可见了，但顺带把宿主**所有** alpha=0 的全屏容器
                        //   一起揭了出来 —— 包括 R18 提示模态和全屏触摸拦截层，
                        //   后果是「成人向作品被隐藏 + 按钮点不动 + 放不了」。
                        //   现在改成只沿**封面这条链**往上救（见 rescueCover 的 ③）。
                        //   rescuePlayAlpha 保留但不再调用。
                        // ★★★ v0.1.37：顺手确认「播放页还活着没有」。
                        //   这一次 instanceof 短路之外没有别的成本，但它是这一版
                        //   唯一能把播放页和列表页分开的**事实**（见 notePlaySeek）。
                        PlayPage.notePlaySeek(v);
                        // ★★ v0.1.26 用户第 3 条：**文字阴影恢复**。
                        //   v0.1.25 为了修「按下按钮时闪出阴影」把整条路砍了，结果
                        //   用户反馈「所有文字的阴影效果都消失了」—— 那才是更大的损失。
                        //   闪一帧的真因不是「有阴影」，而是「靠采样现成像素判断坐在什么
                        //   底色上」：按下时 ripple 一变，8×8 采样翻面 ⇒ 阴影突然出现。
                        //   现在判据换成<b>纯静态属性</b>（文字自身的亮度 + 自己那块不透明
                        //   底烘死的颜色），与按下状态无关 ⇒ 永远不会翻面。
                        if (v instanceof TextView) {
                            // ★★★ v0.1.47：**只接管宿主 Activity 窗口里的文字**（Float 模块
                            //   避让指令·改动 1 + 2）。加在 WindowManager 上的悬浮窗
                            //   （Float 模块的字幕行 / 开关胶囊）有自己的配色与阴影配置，
                            //   被我们改写就是「抢别人的控制权」—— 用户在那边调的
                            //   阴影颜色 / 强度 / 半径全部失效。
                            //   两道判据：① 结构级（窗口 type，见 inAppWindow）
                            //   ② 类名级（别的模块注入的 View，见 foreignModuleView）。
                            //   任何一道判不出来都按「是宿主的」处理（宁可多管，不少管）。
                            if (inAppWindow(v) && !foreignModuleView(v)) {
                                // ★★★ v0.1.127：先强调色、后阴影。
                                //   forceAccent 里的 setTextColor 会走 updateTextColors，
                                //   只认 TextView 自己的 mShadow*；若阴影写在前面，
                                //   颜色一改就把「只写在 Paint 上」的阴影冲掉。
                                forceAccentSmallText((TextView) v);
                                TextShadow.ensureShadow((TextView) v);
                            }
                        } else if (CoverLayer.rescueCover(v)) {
                            CoverLayer.noteCoverKept();
                        } else if (v.getWidth() * 100 >= (long) sSW * 70
                                || (long) v.getWidth() * v.getHeight() * 100
                                >= (long) sSW * sSH * 20) {
                            // 小块（列表单元格 / 按钮）不进 swapLeaf / sheet，切页少做无用功。
                            if (BgLeaf.swapLeaf(v, cv)) {
                                return null;
                            } else {
                                PanelGlass.paintSheetPanel(v, cv);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                long tPre = timeDraw ? System.nanoTime() : 0L;
                Object r = chain.proceed();
                // ★★★ v0.1.92 诊断：单次 >=10ms 就记一笔（含「我们」与 proceed 的分项）。
                if (timeDraw) {
                    try {
                        long tEnd = System.nanoTime();
                        long total = (tEnd - tHook) / 1000000L;
                        if (total >= 10L && sSlowDraw < SLOW_DRAW_BUDGET) {
                            sSlowDraw++;
                            long pre = (tPre - tHook) / 1000000L;
                            log("slowdraw " + total + "ms（我方=" + pre + "）"
                                    + (v == null ? "?" : name(v) + " " + v.getWidth() + "x" + v.getHeight()));
                        }
                    } catch (Throwable ignored) {
                    }
                }
                // ★★★ v0.1.95：封面档**每一层**都记一笔（View#draw 路径，
                //   所以 SkiaTextureView / ReactImageView 这些非 ExpoImageView 的
                //   候选也能抓到）。traceCover 自己按尺寸档 + 限流过滤。
                if (sCoverTraceLeft > 0) BgDiag.traceCover(v, "viewdraw");
                return r;
            });
            sHooked = true;
            log("hooked 1 android.view.View#draw");
        } catch (Throwable t) {
            log("钩子未装 android.view.View#draw: " + t);
        }
    }

    /**
     * v0.1.121：这一层值不值得进封面 / 大底 / 阴影 / 洗色热路径。
     * 文字、进度条、窗口根、封面尺寸候选、大底候选以外的小块直接放行。
     */
    static boolean drawWorkLikely(View v) {
        if (v instanceof TextView) return true;
        if (v instanceof android.widget.ProgressBar) return true;
        try {
            if (v.getRootView() == v) return true;
        } catch (Throwable ignored) {
        }
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) return false;
        // 封面图 / 挖实心底 / wash 外框的下限
        if (w >= 140 && h >= 96) return true;
        if (sSW <= 0 || sSH <= 0) return false;
        // swapLeaf / sheet 门槛（与 View#draw 里一致）
        return w * 100 >= (long) sSW * 70
                || (long) w * h * 100 >= (long) sSW * sSH * 20;
    }

    static void hookImageBlur(ClassLoader cl) {
        try {
            Class<?> cls = XposedCompat.findClass(CLS_EXPO_IMAGE_WRAPPER, cl);
            java.lang.reflect.Method active;
            try {
                active = cls.getDeclaredMethod("getActiveView");
                active.setAccessible(true);
            } catch (NoSuchMethodException e) {
                log("ExpoImageViewWrapper 没有 getActiveView（模糊图判据降级为纯几何）");
                return;
            }
            int hooked = 0;
            for (Method m : XposedCompat.findMethodsByName(cls, "setBlurRadius$expo_image_release")) {
                m.setAccessible(true);
                XposedCompat.hookMethod(m, chain -> {
                    Object ret = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        Object v = active.invoke(self);
                        Object arg = chain.getArg(0);
                        int r = arg instanceof Integer ? (Integer) arg : 0;
                        if (v instanceof View) {
                            if (r > 0) {
                                BLUR_PENDING.put((View) v,
                                        (System.currentTimeMillis() << 8) | (Math.min(r, 255) & 0xFF));
                            } else {
                                // 宿主要求 radius=0 ⇒ 这个 View 现在显示的是**内容图**：
                                // 标记和它当前那张位图的绑定一起撤掉（v0.1.27）。
                                BLUR_PENDING.remove(v);
                                Bitmap cur = imageBitmapOf((View) v);
                                if (cur != null) BLUR_BMP.remove(cur);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return ret;
                });
                hooked++;
            }
            sHooked = true;
            log("hooked " + hooked + " ExpoImageViewWrapper#setBlurRadius（装饰性模糊图识别）");
        } catch (Throwable t) {
            log("钩子未装 ExpoImageViewWrapper#setBlurRadius: " + t);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ★★★ v0.1.42（r13）：宿主自带的毛玻璃层（expo-blur）
    // ══════════════════════════════════════════════════════════════════

    /** expo-blur 的容器（内部第 0 个子 View 就是真正负责模糊/着色的那层）。 */
    static final String CLS_EXPO_BLUR = "expo.modules.blur.ExpoBlurView";
    /** eightbitlab 的 BlurView：expo-blur 在 Android 上的真正实现。 */
    static final String CLS_LIB_BLUR = "eightbitlab.com.blurview.BlurView";

    /**
     * ★★★ v0.1.42（r13 用户第 3 / 4 / 5① / 6 / 7 条）：<b>把宿主自己的毛玻璃层关掉</b>。
     *
     * <h3>为什么这是最后一个缺口</h3>
     * 宿主用 {@code expo-blur}。dex 实证（{@code _host2202}）：
     * <pre>
     *   expo.modules.blur.ExpoBlurView  (extends ExpoView)
     *     ├─ blurView : eightbitlab.com.blurview.BlurView   ← 构造函数里 addView() 加进来的
     *     ├─ blurRadius : F   tint : TintStyle   blurMethod : BlurMethod
     *     └─ applyTint() / applyBlurViewOverlayColorCompat(Z)V / setBlurRadius(F)V
     * </pre>
     * 这层是<b>宿主自己画的「模糊 + 深色着色」</b>，与我们铺的壁纸完全无关：
     * <ul>
     *   <li><b>第 5①条</b> 播放页背景是深色 —— 那是 BlurView 的 dark tint 盖在壁纸上；</li>
     *   <li><b>第 6 条</b>「PurePlay 上面的阴影遮罩又回来了，不是紫色的，是<b>高斯模糊</b>」——
     *       「是高斯模糊」四个字就是 BlurView 的白描；</li>
     *   <li><b>第 7 条</b>「点开自己建的播放列表，背景高斯模糊又回来了」同理；</li>
     *   <li><b>第 3 / 4 条</b>面板背后「深色底 / 先深色再变壁纸」——
     *       深色那一层就是 BlurView 先画、我们的玻璃后画。</li>
     * </ul>
     *
     * <h3>做法（两层保险，都不碰 RN 的子内容）</h3>
     * <ol>
     *   <li>{@code eightbitlab BlurView#draw} 直接<b>不画</b> —— 它自己没有内容子 View
     *       （RN 的内容挂在 {@code ExpoBlurView} 上，和它是兄弟），不画只会露出壁纸；</li>
     *   <li>{@code ExpoBlurView#onAttachedToWindow} 之后把那支 {@code blurView} 子 View
     *       置为 {@code INVISIBLE} —— API 31+ 上 eightbitlab 可能改用 RenderEffect 出图，
     *       那种情况不经过 {@code draw(Canvas)}，必须靠隐藏。</li>
     * </ol>
     */
    static void hookExpoBlur(ClassLoader cl) {
        // ① eightbitlab BlurView：不画（v0.1.43：取消 kids==0 限制）
        //   r14 整场 `drop blurview = 0`：eightbitlab 新版会在内部挂 controller 子 View，
        //   kids>0 时旧判据永不命中 ⇒ 深色 tint / 高斯模糊遮罩概率性回来（用户第 2/3/5①/6/7）。
        try {
            Class<?> cls = XposedCompat.findClass(CLS_LIB_BLUR, cl);
            for (String mn : new String[]{"draw", "dispatchDraw"}) {
                Method draw;
                try {
                    draw = cls.getDeclaredMethod(mn, Canvas.class);
                } catch (NoSuchMethodException e) {
                    continue;
                }
                draw.setAccessible(true);
                XposedCompat.hookMethod(draw, chain -> {
                    try {
                        Object self = chain.getThisObject();
                        if (sArmed && self instanceof View) {
                            View v = (View) self;
                            if (v.getWidth() > 0 && v.getHeight() > 0) {
                                BgDiag.hit("drop blurview -> ", v,
                                        dstRect(v.getWidth(), v.getHeight()));
                                return null;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
            }
            // API 31+：RenderEffect 那条路不经 draw —— 直接把 effect 清掉
            try {
                Method sre = View.class.getDeclaredMethod(
                        "setRenderEffect", android.graphics.RenderEffect.class);
                sre.setAccessible(true);
                XposedCompat.hookMethod(sre, chain -> {
                    try {
                        Object self = chain.getThisObject();
                        if (sArmed && self != null
                                && CLS_LIB_BLUR.equals(self.getClass().getName())) {
                            return chain.proceed(new Object[]{null});
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                log("hooked View#setRenderEffect（BlurView 清 RenderEffect）");
            } catch (Throwable ignored) {
                // API < 31 没有这个方法，忽略
            }
            log("hooked eightbitlab BlurView#draw/dispatchDraw（宿主毛玻璃层一律不画）");
        } catch (Throwable t) {
            log("钩子未装 BlurView#draw: " + t);
        }
        // ② ExpoBlurView：把内部那支 blurView / 所有 BlurView 子层隐藏
        try {
            Class<?> cls = XposedCompat.findClass(CLS_EXPO_BLUR, cl);
            java.util.List<java.lang.reflect.Field> viewFields = new java.util.ArrayList<>();
            for (java.lang.reflect.Field cand : cls.getDeclaredFields()) {
                if (!View.class.isAssignableFrom(cand.getType())) continue;
                if (java.lang.reflect.Modifier.isStatic(cand.getModifiers())) continue;
                cand.setAccessible(true);
                viewFields.add(cand);
            }
            final java.util.List<java.lang.reflect.Field> inners = viewFields;
            int hooked = 0;
            StringBuilder miss = new StringBuilder();
            for (String mn : new String[]{
                    "onAttachedToWindow", "configureBlurView",
                    "onLayout", "dispatchDraw", "draw"}) {
                for (Method m : XposedCompat.findMethodsByName(cls, mn)) {
                    try {
                        Class<?>[] pt = m.getParameterTypes();
                        // draw/dispatchDraw 只要 (Canvas)；其余无参或任意
                        if (("draw".equals(mn) || "dispatchDraw".equals(mn))
                                && (pt.length != 1 || pt[0] != Canvas.class)) continue;
                        m.setAccessible(true);
                        XposedCompat.hookMethod(m, chain -> {
                            Object ret = chain.proceed();
                            BgLeaf.hideBlurDescendants(chain.getThisObject(), inners);
                            return ret;
                        });
                        hooked++;
                    } catch (Throwable mt) {
                        // ★★★ v0.1.46：**单个方法失败不得打断整组**。
                        //   r46 真机日志：ViewGroup.onLayout 是抽象方法 → hook 抛
                        //   IllegalArgumentException → 整段 try 中断，
                        //   draw/dispatchDraw/onAttachedToWindow/setBlurRadius 全没挂上。
                        if (miss.length() < 200) {
                            if (miss.length() > 0) miss.append(", ");
                            miss.append(mn).append(':').append(mt.getClass().getSimpleName());
                        }
                    }
                }
            }
            // 半径置零：即使子层漏网，也只剩透明空层
            for (Method m : XposedCompat.findMethodsByName(cls, "setBlurRadius")) {
                try {
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length != 1 || pt[0] != float.class) continue;
                    m.setAccessible(true);
                    XposedCompat.hookMethod(m, chain -> {
                        Object ret = chain.proceed(new Object[]{0f});
                        BgLeaf.hideBlurDescendants(chain.getThisObject(), inners);
                        return ret;
                    });
                    hooked++;
                } catch (Throwable ignored) {
                }
            }
            log("hooked " + hooked + " ExpoBlurView（隐藏/清零宿主毛玻璃层）"
                    + (miss.length() > 0 ? " skip=[" + miss + "]" : ""));
        } catch (Throwable t) {
            log("钩子未装 ExpoBlurView: " + t);
        }
    }

    /** ★ v0.1.81：「播放档 → 内容图」档位翻转纠偏的上次触发时刻（全局节流）。 */
    static volatile long sHealAt;

    /** ★ v0.1.81：每个 View 的纠偏次数（WeakHashMap，View 回收即清）。 */
    static final java.util.Map<View, int[]> HEAL_CNT =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 「窗口里够大但不是内容根」的块登记条数（v0.1.28 诊断）。 */
    static volatile int sMidLogs;
    static final int MID_LOG_BUDGET = 6;

    /** 「模糊底分类日志条数」（v0.1.28）。 */
    static volatile int sBlurLogs;
    static final int BLUR_LOG_BUDGET = 8;

    /** ★ v0.1.72：底图层决策日志的上次输出时刻（1 秒 2 条限流）。 */
    static volatile long sBgTraceAt;

    /**
     * 弹层里那张半透明紫图叠回来的强度（0~1）。
     * <p>0.16：留一点点面板色，既不糊成一片，也不会重新变成紫遮罩。
     */
    static final float GLASS_IMG_TINT_PCT = 0.16f;

    /**
     * 弹层里「大块图片层」能被当成面板底的上限（平均 alpha）。
     * <p>≥ 0.90 视为不透明内容图（作品封面 / 预览大图）⇒ 不铺玻璃。
     */
    static final float GLASS_IMG_MAX_ALPHA = 0.90f;

    /** 弹层里「大块图片层」的宽度下限（% 屏宽）。 */
    static final int DIALOG_IMG_MIN_W_PCT = 60;
    /** 弹层里「大块图片层」的面积下限（% 屏面积）。 */
    static final int DIALOG_IMG_MIN_AREA_PCT = 25;

    /**
     * 登记一个「满宽 + 够高」的图片层（每个类一次）。
     *
     * <p>为什么要它：播放页背景到底是 {@code ExpoImageView} 还是 RN 自己的
     * {@code ReactImageView}（Fresco 另有一套 draw），只看代码猜不出来 —— r26 之前已经
     * 在「到底哪条路画的」上错了七版。这里把<b>真被画到的</b>类名 + 位图分类打出来，
     * 下一轮一眼就能定案。
     */
    static void noteImage(View v, int w, int h) {
        String key;
        try {
            key = v.getClass().getName();
        } catch (Throwable t) {
            return;
        }
        if (!sImgSeen.add(key)) return;
        if (sImgLogs >= IMG_LOG_BUDGET) return;
        sImgLogs++;
        int kind = CoverLayer.imgKind(v);
        log("imglayer " + name(v) + " " + w + "x" + h + " top=" + topOf(v)
                + " kind=" + (kind == IMG_VEIL ? "veil" : kind == IMG_ATMOS ? "atmos" : "content"));
    }

    /**
     * 封面救 alpha 的时间戳（View → 上次救的时刻）。
     *
     * <p>★ v0.1.31：原来是「每个 View 只救一次」的 {@code Set}。但 RN 会<b>复用</b>
     * 同一个 {@code ReactImageView} 播下一首 —— 用户说的「打开新音轨作品封面会消失」
     * 就是被这一次性标记挡掉的。现在改成「同一 View 800ms 内只救一次」：
     * 既能压住每帧 post 刷屏，换曲时又会重新救。
     */
    static final java.util.Map<View, Long> COVER_FIX =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Long>());

    static final long COVER_FIX_GAP_MS = 800L;

    /** 这个封面 View 现在允许救一次吗（800ms 节流）。 */
    static boolean coverFixAllowed(View v) {
        long now = System.currentTimeMillis();
        Long last = COVER_FIX.get(v);
        if (last != null && now - last < COVER_FIX_GAP_MS) return false;
        COVER_FIX.put(v, now);
        return true;
    }

    /**
     * ★★★ v0.1.30 用户第 2 条：把「卡在 alpha=0」的作品封面救回来。
     *
     * <p>r34 / r35 日志里那条一直没变过的证据：
     * <pre>
     *   big ReactImageView 2300x1726 top=0 kids=0 bg=none alpha=0.0
     * </pre>
     * 封面位图已经加载完了（2300×1726 就是它的真实尺寸），宿主却把这一层的
     * {@code setAlpha} 停在 0（入场淡入动画没跑完 / 被 JS 侧动画卡住）。
     * 结果就是用户连着几轮说的「作品封面不显示」——<b>不是我们把它挡掉了，
     * 是宿主自己画了 0 不透明度</b>（我们的钩子全程没碰过 alpha）。
     *
     * <p>判据收得很紧，只可能命中封面：
     * <ul>
     *   <li><b>只在播放页窗口里</b>（{@link #isPlayWindow}，靠 setBlurRadius(10) 认）；</li>
     *   <li>是 {@code ImageView} 且<b>位图已经就绪</b>（没图就没得救）；</li>
     *   <li>高度在 22%~88% 屏高之间 —— 满屏的是背景层（我们自己画），小的是图标；</li>
     *   <li>当前 alpha ≤ 0.35（正常显示的封面根本不会进这里）。</li>
     * </ul>
     * 每个 View 只救一次，且在主线程置 1（绘制线程改 View 属性不安全）。
     */
    /**
     * ★★★ v0.1.67（r70）：<b>播放页层级「自审计」—— 让代码自己把真相写进日志</b>。
     *
     * <h3>为什么必须这么做</h3>
     * 0.1.66 的日志里 {@code cover ancestor lift} <b>一条都没有</b>
     * ⇒ 说明 {@code rescueCover} 连门都没进（封面那一帧的 {@code View#draw} 可能压根没被调，
     * 或者被判据挡在外面）。而「让用户停在播放页、我这边 adb 去 dump」
     * 这条路已经反复失败（多数时候抓到的前台不是播放页）。
     * 与其再猜一轮，不如<b>在播放页被认出来的那一刻，由代码自己遍历窗口树</b>，
     * 把「类名 / 尺寸 / alpha / 里面有没有图」逐层写进日志 ——
     * 你只要照常发 zip，我就能拿到确定性数据。
     *
     * <h3>顺带修复（只在大容器 + 内含图片时才动手）</h3>
     * 遍历时若发现「alpha &lt; 0.05、宽 ≥50% 屏、高 ≥12% 屏、子树里有 ImageView、
     * 且自己不可点击」，就把 alpha 拉回 1。
     * 多加「不可点击」这条是为了<b>不再重演 0.1.65 那次事故</b>：
     * 上次揭出来害你点不动的那个全屏层带 {@code clickable}，
     * 而播放页内容容器（装封面/标题/按钮的那个）本身不接收点击。
     */
    // ══════════════════════════════════════════════════════════════════
    // ★★★ v0.1.70（r73）播放页「可 adb 直读」的层级探针
    // ══════════════════════════════════════════════════════════════════
    //
    // 前 70 个版本卡在同一件事上：**证据拿不到**。
    // LSPosed 的日志要用户手动导出 zip，往返一次十分钟，而且 `logOnce` 的 140 条
    // 预算经常在到达播放页之前就被列表页的 key 吃光 —— r72 那次「`audit lift` 一条都没有」
    // 就是预算被别的 key 占满造成的误判。
    //
    // 这一版把「播放页真实层级」直接写进 **logcat**（tag = MoriProbe）：
    // 我这边 `adb logcat -s MoriProbe` 就能实时看到，不需要用户配合导出。
    // 每一层都带三样以前查不到的东西：
    //   ① 窗口坐标 [l,t,r,b] —— 接缝 / 错位只能靠坐标定案；
    //   ② `P=` 这一层最后被哪个分支接管（见 {@link #PAINTER}），没被接管就没有这个字段；
    //   ③ 图片层的 drawable 类名 / 位图尺寸 / 是否已经是糊的 / blurRadius / imgKind
    //      —— 「封面到底是被我们盖掉了，还是宿主压根没画」一眼分开。

    /** 每层最后被哪个绘制分支接管（前缀名，见 {@link #hit}）。 */
    static final java.util.Map<View, String> PAINTER =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, String>());

    static volatile long sProbeAt;
    /** 层级探针额度（5 秒一次 ⇒ ≈17 分钟；用完自动停，不刷爆 logcat）。 */
    static volatile int sProbeLeft = 200;
    static volatile int sProbeSeq;
    static volatile int sProbeLines;

    /** 每块大图「这一刻的接管决策」的最后打印时刻（1 秒节流）。 */
    static final java.util.Map<View, Long> TRACE_AT =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Long>());

    static volatile int sTraceLeft = 200;

    // ================= v0.1.95 封面层诊断 =================

    /** {@link #traceCover} 剩余条数（每进程 60 条）。 */
    static volatile int sCoverTraceLeft = 60;

    /** 每个 View 的上次封面 trace 时刻（800ms 限流）。 */
    static final java.util.Map<View, Long> COVER_TRACE_AT =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Long>());

    /** ★ v0.1.74（r88）：大层快照的窗口终点 / 上次时刻 / 剩余条数。 */
    static volatile long sSnapUntil;
    static volatile long sSnapAt;
    static volatile int sSnapLeft;

    /** 快照自转开关（只在武装窗口里为 true）。 */
    static volatile boolean sSnapLoopOn;

    /** 本次武装的时刻（用来决定前 2 秒用 120ms 密采样）。 */
    static volatile long sSnapStart;

    static final Runnable SNAP_LOOP = new Runnable() {
        @Override
        public void run() {
            try {
                BgDiag.snapOnce();
            } catch (Throwable ignored) {
            }
            boolean more;
            try {
                more = System.currentTimeMillis() < sSnapUntil && sSnapLeft > 0;
            } catch (Throwable t) {
                more = false;
            }
            if (more) {
                // v0.1.76：切页后的**头 2 秒用 120ms**（用户实测「空白页闪得很快」，
                // 400ms 采样会漏），之后回到 400ms。
                long gap = System.currentTimeMillis() - sSnapStart < 2000L ? 250L : 400L;
                try {
                    MAIN.postDelayed(this, gap);
                } catch (Throwable ignored) {
                }
            } else {
                sSnapLoopOn = false;
            }
        }
    };

    // ══════════════════════════════════════════════════════════════════
    // ★★★ v0.1.77（r91）：空页看门狗
    // ══════════════════════════════════════════════════════════════════

    /** 最近一次页面切换的时刻（看门狗只在它之后 2 秒内做真判定）。 */
    static volatile long sPageSwitchAt;

    /** v0.1.79：整窗缩略图（1/3 缩放），只用来盖「切页空窗」，不含内容语义。 */
    static volatile Bitmap sPageSnap;

    /** 正在拍缩略图（此时不要往画布上盖缩略图，否则自噬）。 */
    static volatile boolean sCapturing;

    /** 「向右划出」动画的起点（页面切换那一刻）。 */
    static volatile long sSnapFrom;


    /** 最近一次判定出「页面区域没有可见内容」的时刻（alpha 救援用）。 */
    static volatile long sBlankNow;

    /** 看门狗上次检查 / 上次打印 / 已连中次数 / 剩余打印额度。 */
    static volatile long sBlankAt;
    static volatile long sBlankLogAt;
    static volatile int sBlankHits;
    static volatile int sBlankLeft = 20;

    /** 只尝试一次：把 react-native-screens 的「非活动页不挂载」关掉。 */
    static volatile boolean sAttachTried;

    /** v0.1.80：失败重试的节流时刻 / skip 日志条数。 */
    static volatile long sAttachAt;
    static volatile int sAttachLogs;

    /** activityState 观测日志的独立额度（不受图片 trace 的额度挤占）。 */
    static volatile int sActLeft = 120;

    /**
     * ★★★ v0.1.87（r100）：把 react-native-screens 的「非活动页不挂载」关掉。
     *
     * <p>这是本轮唯一还没被否掉的根治方向：只要目标页一直挂在树里，
     * 手势返回时就不存在「重新挂载 + 重渲染」的空窗（≈300ms）。
     *
     * <p>做法：从当前绘制中的 View 往上找到 `*ScreenContainer*` / `ScreenStack`，
     * 依次尝试几个不同版本的方法名与字段名，**成功任何一个就算成功**，并把结果写进
     * logcat（`MoriSnap detach OFF / fail: ...`）—— 这样我能在装完机后当场确认，
     * 不用再靠导日志包猜。
     */
    static void tryDetachOff(View any) {
        // ★ v0.1.80（r104）：**失败不再锁死。**
        //   旧实现一进来就把 sAttachTried 置真，于是 r100 那条「no container」
        //   （翻页那一瞬 Screen 已经离树，祖先链断了）把这条修复永久废掉 ——
        //   日志里只有一条 skip，之后再也没有任何尝试。
        //   现在：只在**真正调成功**时锁死；失败按 800ms 节流后重试，
        //   并且走「窗口根 → 首链」这条兜底路径去找容器。
        if (sAttachTried || any == null) return;
        long now = System.currentTimeMillis();
        if (now - sAttachAt < 800L) return;
        sAttachAt = now;
        View cur = any;
        View target = null;
        try {
            for (int i = 0; i < 16 && cur != null; i++) {
                String cn = name(cur);
                if (cn.contains("ScreenContainer") || cn.contains("ScreenStack")) {
                    target = cur;
                    break;
                }
                android.view.ViewParent p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
            }
            if (target == null) {
                View root = any.getRootView();
                if (root != null && root != any) target = screenContainerOf(root);
            }
        } catch (Throwable ignored) {
        }
        if (target == null) {
            if (sAttachLogs < 3) {
                sAttachLogs++;
                android.util.Log.i("MoriSnap", "detach OFF skip: no container");
            }
            return;
        }
        String cls = target.getClass().getName();
        String[] names = {"setDetachInactiveScreens", "setDetachScreens"};
        for (String n : names) {
            for (Class<?> c = target.getClass(); c != null && c != Object.class;
                 c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Method m = c.getDeclaredMethod(n, boolean.class);
                    m.setAccessible(true);
                    m.invoke(target, false);
                    sAttachTried = true;
                    android.util.Log.i("MoriSnap", "detach OFF ok " + cls + "#" + n);
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
        // 再退一步：有些版本是字段而不是方法
        String[] fields = {"detachInactiveScreens", "mDetachScreens"};
        for (String f : fields) {
            for (Class<?> c = target.getClass(); c != null && c != Object.class;
                 c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field fd = c.getDeclaredField(f);
                    fd.setAccessible(true);
                    fd.setBoolean(target, false);
                    sAttachTried = true;
                    android.util.Log.i("MoriSnap", "detach OFF field " + cls + "#" + f);
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
        android.util.Log.i("MoriSnap", "detach OFF fail: " + cls);
    }

    /** 缓存的 ScreenContainer（2 秒刷新一次，避免每拍遍历整棵树）。 */
    static volatile java.lang.ref.WeakReference<View> sBoxRef;
    static volatile long sBoxAt;

    /** 找页面容器：沿「首链」下潜，命中类名含 ScreenContainer 的节点。 */
    static View screenContainerOf(View root) {
        try {
            long now = System.currentTimeMillis();
            View cached = sBoxRef == null ? null : sBoxRef.get();
            if (cached != null && now - sBoxAt < 2000L) return cached;
            sBoxAt = now;
            View cur = root;
            for (int i = 0; i < 14 && cur != null; i++) {
                String cn = name(cur);
                if (cn.contains("ScreenContainer")) {
                    sBoxRef = new java.lang.ref.WeakReference<>(cur);
                    // v0.1.80（r104）：统一走「多名字 + 字段兜底」的那个版本 ——
                    // 旧路径用 getMethod（只找 public）失败后还会把 sAttachTried 置真，
                    // 于是真正的尝试再也不会发生。
                    tryDetachOff(cur);
                    return cur;
                }
                if (!(cur instanceof ViewGroup)) return null;
                ViewGroup g = (ViewGroup) cur;
                if (g.getChildCount() == 0) return null;
                cur = g.getChildAt(0);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 心跳计数（v0.1.72：150ms 一拍，重量级动作每 20 拍一次）。 */
    static volatile int sHeartTick;

    /** 上一拍是否处在「树上还有 scale」的转场里（v0.1.74：转场收尾补重绘）。 */
    static volatile boolean sWasTransit;

    /** 最近一次拿到过的窗口根（心跳里主动扫进度条用，v0.1.75）。 */
    static volatile java.lang.ref.WeakReference<View> sLastRoot;

    /** 「主动扫进度条」的上次时刻 / 本次扫描计数。 */
    static volatile long sSeekScanAt;
    static volatile int sSeekScanCount;

    /** 最近一次看到「满屏底图」的时刻（v0.1.76：只在这之后 3 秒内才去主动扫进度条）。 */
    static volatile long sFullBgSeenAt;

    /** 每次「播放页重新出现」允许补几次重绘（事件后最多补 3 拍（150ms × 3））。 */
    static final int REDO_BUDGET = 3;

    /**
     * ★ v0.1.72：最近一次「确认播放页在屏」的时刻（2 秒心跳刷新，与「铺过底图」分开记）。
     *
     * <p>为什么要和 {@link #sPlayPaintedAt} 分开：播放页静止时那几张底图
     * <b>4 秒才重绘一次</b>（r74 logcat `bgTrace` 时间戳 11:40:16/20/24/28），
     * 拿「3 秒内铺过底」当信号必漏；而心跳只要进度条还活着就一定是「在屏」。
     */
    static volatile long sPlayOnScreenAt;

    /**
     * 「补一次重绘」的剩余次数（v0.1.72）。
     *
     * <p>只在三个<b>事件</b>上充满（不按「铺过底图」充满 —— 播放页静止时底图
     * 4 秒才画一次，拿它当信号会让重绘变成永不停止的低频心跳）：
     * <ol>
     *   <li>{@link #apply} —— 宿主回到前台；</li>
     *   <li>心跳看到进度条 {@code isShown()} 由 false 变 true —— 播放页刚展开；</li>
     *   <li>心跳看到 {@link #sPlaySeek} 换了对象 —— 播放页被重建。</li>
     * </ol>
     * 每次事件给 {@value #REDO_BUDGET} 次，600ms 一拍 ⇒ 最多 3 秒内自愈，之后彻底安静。
     */
    static volatile int sRedoLeft = REDO_BUDGET;

    /** 上一次心跳看到的「播放页根是否可见（alpha ≥ 0.9）」（用于识别展开事件）。 */
    static volatile boolean sPlayWasVisible;

    /** 上一次心跳看到的进度条对象（用于识别播放页被重建）。 */
    static volatile java.lang.ref.WeakReference<View> sSeekSeen;

    /** {@link #apply} 里重置重绘额度的上次时刻（1.5 秒限流）。 */
    static volatile long sResumeRedoAt;

    /** 「补重绘」日志条数上限。 */
    static volatile int sRedoLogs;

    /** 往上 n 层的「类名 尺寸 a=alpha」，用来看是谁把这一层压掉了。 */
    static String chainOf(View v, int n) {
        StringBuilder sb = new StringBuilder();
        Object p = v.getParent();
        for (int i = 0; i < n && p instanceof View; i++) {
            View pv = (View) p;
            sb.append(" <- ").append(name(pv)).append(' ')
                    .append(pv.getWidth()).append('x').append(pv.getHeight())
                    .append(" a=").append(pv.getAlpha());
            p = pv.getParent();
        }
        return sb.toString();
    }

    /** 子树里有没有 ImageView（深度限 8，防止意外递归过深）。 */
    static boolean hasImageDeep(View v, int depth) {
        if (v == null || depth > 8) return false;
        if (v instanceof android.widget.ImageView) return true;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                if (hasImageDeep(g.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    /** 这一层挂上可画的 drawable 了吗（Drawee / BitmapDrawable / ColorDrawable 都算）。 */
    static boolean drawableReady(View v) {
        try {
            if (v instanceof android.widget.ImageView) {
                return ((android.widget.ImageView) v).getDrawable() != null;
            }
            return v.getBackground() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 这一层是不是「模态弹窗的压暗遮罩」（v0.1.23）。
     *
     * <p>判据只用背景色本身，不需要知道绘制顺序：
     * <ul>
     *   <li><b>纯黑 / 纯白</b>（彩度 &lt; 0.08）且 <b>半透明</b>（alpha &lt; 240）
     *       —— 这就是 {@code rgba(0,0,0,0.4)} 这一类 dim；</li>
     *   <li>拿不到实心颜色（复合 drawable / 渐变）⇒ <b>不是</b> dim，
     *       当作页面氛围层处理。</li>
     * </ul>
     *
     * <p>为什么必须区分：dim 遮罩画在弹窗内容<b>之上</b>，涂成不透明就是把弹窗糊死；
     * 而页面氛围层画在内容<b>之下</b>，替换掉只会露出壁纸。两者的正确动作完全相反。
     */
    static boolean isDimMask(Drawable d) {
        if (d == null) return true;      // 没背景的叶子本来就什么都不画，drop 无副作用
        Integer c = solidColorOf(d);
        if (c == null) return false;     // 复合 / 渐变背景 ⇒ 页面层
        int a = (c >>> 24) & 0xff;
        if (a >= 240) return false;      // 不透明 ⇒ 不是遮罩
        int r = (c >> 16) & 0xff;
        int g = (c >> 8) & 0xff;
        int b = c & 0xff;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        float chroma = (max - min) / 255f;
        float luma = luma(c);
        // ★★ v0.1.24 用户第 2 条：窗口必须放宽。
        //   宿主弹窗遮罩是 token `overlay/modal1 = #19122ccc` / `modal2 = #19122c66`
        //   —— 它<b>不是纯黑</b>，是带一点紫的深底：彩度 (0x2c-0x12)/255 ≈ 0.10。
        //   旧阈值 0.08 把它判成了「页面氛围层」⇒ 整屏被涂上模糊壁纸 ⇒
        //   用户看到的就是「面板一弹出来，背后的主界面跟着一起糊了」。
        //   新阈值 0.22 仍然能挡住宿主的紫面板（彩度 0.35 以上），
        //   而且「极暗（luma < 0.22）」这个条件本身就排除了所有彩色氛围层。
        return chroma < 0.22f && (luma < 0.22f || luma > 0.90f);
    }

    /** 从一个 drawable 里读实心颜色；读不到（复合/渐变）返回 null。 */
    static Integer solidColorOf(Drawable d) {
        java.lang.reflect.Field f = colorField(d.getClass());
        if (f == null) return null;
        try {
            return f.getInt(d);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 这一层「连它自己带所有祖先」的累计缩放（v0.1.23 用户第 3 条）。
     *
     * <h3>为什么不用 Canvas#getMatrix</h3>
     * v0.1.22 试过用画布矩阵的逆矩阵把坐标系拉回窗口空间，真机结果是整块画到屏幕外
     * （r28 血证：导航栏 / 迷你播放 / 面板全变透明，信息页向下错位）。硬件画布的矩阵
     * 并不包含父层的平移，拿它反算等于凭空加一次偏移。
     *
     * <p>{@code getScaleX/Y} 是 View 自己的属性，属性动画会实时更新，读它<b>一定准</b>。
     * 只要这一层在缩放（≈ 转场动画中），清晰壁纸就会跟着放大/错位 ⇒ 改画模糊图。
     */
    static float treeScale(View v) {
        float sc = 1f;
        try {
            View cur = v;
            // ★★★ v0.1.72（r74）：8 层不够 —— 播放页的 scale 挂得比这更深
            //   （DecorView→…→ReactSurfaceView→…→Screen 容器→播放页根）。
            // ★★★ v0.1.74（r77「上下滑动超级卡顿」）：**稳态只数 6 层**。
            //   这个方法在每次 {@code paintAligned} 里都要跑，列表页滚动时一帧几百层，
            //   16 层 ≈ 32 次 getter × 几百层 —— 纯属白烧 CPU（稳态根本没有 scale）。
            //   现在：近 600ms 内没见过任何 scale ⇒ 只数 6 层；一旦真的数出 scale，
            //   就置 {@link #sTransitAt} 并把窗口期改成 16 层完整遍历。
            int depth = System.currentTimeMillis() - sTransitAt < 600L ? 16 : 6;
            for (int i = 0; i < depth && cur != null; i++) {
                float sx = cur.getScaleX();
                float sy = cur.getScaleY();
                if (Math.abs(sx - sy) > 0.01f) return 1f;
                sc *= sx;
                android.view.ViewParent p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
            }
            if (Math.abs(sc - 1f) > 0.02f) sTransitAt = System.currentTimeMillis();
        } catch (Throwable ignored) {
            return 1f;
        }
        return sc;
    }

    /** 最近一次「树上有 scale」的时刻（v0.1.74：用于 treeScale 的快慢路径切换）。 */
    static volatile long sTransitAt;

    /**
     * 贴屏幕顶，或者本身就满屏（报告里两张氛围图各占一种）。
     */
    static boolean isTopAnchored(View v, int w, int h) {
        if ((long) w * h >= sMinArea) return true;
        int[] loc = LOC.get();
        try {
            v.getLocationInWindow(loc);
        } catch (Throwable t) {
            return false;
        }
        return loc[1] <= sEdgeTop;
    }

    /**
     * 给这张 expo-image 的位图分类（内容图 / 半透明叠加 / 不透明氛围底）。
     *
     * <p>判据只看位图本身，不看比例魔数：{@code fill} 会让 View 比例和位图比例毫无关系；
     * 而「有没有 alpha 通道 + 采样平均 alpha」是报告 §4 逐张核过的共同特征
     * （{@code localbg / playbackspbackground / playback_card_bg / player_background} 都是
     * RGBA；唯二的不透明图 {@code main_visual} 是 RGB）。
     *
     * <p>成本：每个位图对象采样一次 24×24，之后走 {@link #VEIL_CACHE}（WeakHashMap，
     * 不会拖住 Activity）。
     */
    /**
     * 这个 View 现在显示的那张位图（拿不到就 {@code null}）。
     *
     * <p>用于把「模糊装饰图」标记从 <b>View</b> 迁到 <b>位图</b>（v0.1.27）。
     */
    static Bitmap imageBitmapOf(View v) {
        try {
            if (!(v instanceof ImageView)) return null;
            Drawable d = ((ImageView) v).getDrawable();
            if (!(d instanceof BitmapDrawable)) return null;
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            return b == null || b.isRecycled() ? null : b;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 「模糊装饰图」标记的有效期（v0.1.28：8s → 10 分钟）。
     *
     * <p>r33 现象「播放页背景有时候会回到作品封面的模糊版」：宿主的
     * {@code setBlurRadius} 是 prop 更新才调，值没变就不会重调。用户停在播放页
     * 超过 8 秒后标记过期 ⇒ 我们不再认为那层是装饰底 ⇒ 宿主自己那张模糊封面照就露出来了。
     * 现在延到 10 分钟，并且真正兜底的是「满屏 + 一个 View 只绑一张位图」这两条。
     */
    static final long BLUR_PENDING_TTL_MS = 600000L;


    /**
     * 这一层是不是「铺满整屏的装饰底」（v0.1.28）。
     *
     * <p>宿主的装饰底只有两种，bundle 实证都是 {@code contentFit="cover"} 铺满：
     * 播放页 {@code PlayerBackground}（blurRadius=10）、播放列表 / 分类详情
     * {@code PlaylistDetailBackground}（blurRadius=6）。
     *
     * <p>0.85 屏高这条线把「封面卡片」「列表缩略图」「图片浏览里的照片」全挡在外面 ——
     * 它们的共同点是<b>没有占满整屏</b>，所以永远不会被当成底色顶掉。
     */
    static boolean isScreenFillDecor(View v) {
        try {
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0) return false;
            if (w >= sFullW && h >= (int) (sSH * 0.85f)) return true;
            // ★★★ v0.1.45（r16 用户第 5 条）：**弹层窗口按它自己的窗口算「满」**。
            //   「播放列表 / 摘选详情」整页底图跑在一个独立 Dialog 窗口里，而那个
            //   窗口本身就比屏幕窄（r16 实证：窗口与底图都是 1153px，屏幕 1272 ⇒
            //   90.6%，永远够不到 94% 屏宽那道线）⇒ {@link #blurRadiusOf} 读出 0、
            //   {@link #paintBlurBg} 第一道闸就把它放回宿主手里 ⇒
            //   用户看到的正是宿主那张 blurRadius=6 的模糊封面当背景。
            //   现在只要：这个窗口本身是个「页面」（≥60% 屏宽、≥50% 屏高），
            //   且这层铺满**自己窗口**的 94% × 85%，就同样算页面装饰底。
            //   blurRadius>0 这道硬条件不动 —— 只认宿主明确声明「这是模糊底」的层。
            View root = v.getRootView();
            if (root == null || root == v) return false;
            int rw = root.getWidth();
            int rh = root.getHeight();
            if (rw <= 0 || rh <= 0) return false;
            if (rw < (int) (sSW * 0.60f) || rh < (int) (sSH * 0.50f)) return false;
            return w * 100 >= (long) rw * 94 && h * 100 >= (long) rh * 85;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 这个 View 上「宿主要求的模糊半径」，0 表示不是装饰底（v0.1.27）。
     *
     * <p>规则：{@link #BLUR_PENDING} 里有一条 <b>8 秒内</b>设下的标记、且这一帧真的
     * 显示了位图 ⇒ 把「这张位图 = 装饰底」记进 {@link #BLUR_BMP} 并返回半径。
     * 视图被 RN 回收去显示别的图时，位图变了 ⇒ 查不到绑定 ⇒ 不再误判；
     * 同一张模糊图在页面重新挂载时又会重新绑定 ⇒ 不会丢效果。
     */
    static int blurRadiusOf(View v) {
        try {
            Long p = BLUR_PENDING.get(v);
            if (p == null) return 0;
            long t = p >>> 8;
            int r = (int) (p & 0xFF);
            boolean expired = System.currentTimeMillis() - t > BLUR_PENDING_TTL_MS;
            // ★★ v0.1.28：必须是「铺满整屏」的那一层（装饰底）。封面卡片、列表缩略图一律不算。
            if (!isScreenFillDecor(v)) return 0;
            // ★★★ v0.1.38（r43 用户第 2 条）：**分页容器里的满屏图一律不是装饰底**。
            //   「作品文件夹图片浏览」用 ViewPager2 复用同一个满屏 ExpoImageView 翻照片
            //   （r43 日志：`sheet miss RecyclerViewImpl 1272x2256 … parent=ViewPager2`）。
            //   以前靠「一个 View 只绑一张位图 + 3 秒新鲜期」两道闸挡它，副作用是
            //   **播放页那张底图翻歌 / 切后台回来也一起被挡掉** ⇒ 半径读成 0 ⇒
            //   背景被判成清晰壁纸 ⇒ 用户看到的「有时正常、有时背景透明」。
            //   现在把这两道闸换成一条结构判据：分页容器里的图永不算装饰底。
            if (insidePager(v)) return 0;
            Bitmap b = imageBitmapOf(v);
            // ★★★ v0.1.81（r113 用户第 1 条「偶尔播放页背景退回模糊版作品封面」）：
            //   **位图绑定还活着时，10 分钟 TTL 不再把它废掉。**
            //   旧顺序是「先查 TTL，过期直接 return 0」——而播放页一开就是几十分钟、
            //   后台一挂就是半小时，prop 不会重设（RN 只在 prop 变化时才调 setter），
            //   待定标记一过期，这张明明还绑着的模糊底图就读成 r=0 ⇒
            //   imgKind 判成 IMG_CONTENT ⇒ paintBlurBg 在
            //   `kind==IMG_CONTENT && !blurDeclared` 处整层放行
            //   ⇒ 宿主那张糊封面直接上屏，直到某次全树重画（比如开关一次封面屏蔽）才恢复。
            //   日志实证：`imglayer ExpoImageView 1272x2772 kind=content` 数十次。
            //   现在只要「这一刻显示的还是绑过的那张图」（待定标记仍在 ⇒ 视图还是
            //   当年被声明模糊的那个实例，不会把别的 View 拖下水），就续期并返回半径；
            //   结构闸（满屏 + 不在分页容器）照旧。
            if (!expired) {
                Integer fresh = b == null ? null : BLUR_BMP.get(b);
                if (fresh != null && fresh > 0) return fresh;
                if (b == null) return r;
                BLUR_BMP.put(b, r);
                return r;
            }
            // ★★★ v0.1.81：过期不再一律作废。底图换源（换歌 / 刷新）时 prop 不会重设，
            //   「待定标记过期 + 新位图未绑定」正是换歌后的第二条触发路径。
            //   只要它此刻仍在播放语境里且满屏，就当作同一张装饰底重绑并续期；
            //   非播放语境维持旧行为（过期即弃，防视图回收残留）。
            if (PlayPage.playContext(v) || PlayPage.isPlayWindow(v) || PlayPage.underPlayRoot(v)) {
                BLUR_PENDING.put(v, (System.currentTimeMillis() << 8) | r);
                if (b != null) BLUR_BMP.put(b, r);
                return r;
            }
            BLUR_PENDING.remove(v);
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * ★★★ v0.1.38：这一层在不在「分页容器」里（ViewPager2 / RecyclerView 横向翻页）。
     *
     * <p>宿主的「作品文件夹图片浏览」就是 {@code ViewPager2} 里塞一个满屏
     * {@code ExpoImageView}，翻一张照片复用同一个 View（r43 日志：
     * {@code sheet miss RecyclerViewImpl 1272x2256 top=0 … parent=ViewPager2 1272x2256}）。
     * 这类层**永远**不是装饰底，也不能当页面背景去换壁纸 —— 它显示的是用户要看的那张图。
     */
    static boolean insidePager(View v) {
        View p = v;
        for (int i = 0; i < 10 && p != null; i++) {
            String n;
            try {
                n = p.getClass().getName();
            } catch (Throwable t) {
                return false;
            }
            if (n.endsWith("ViewPager2") || n.contains("ViewPager2$")) return true;
            android.view.ViewParent par;
            try {
                par = p.getParent();
            } catch (Throwable t) {
                return false;
            }
            p = par instanceof View ? (View) par : null;
        }
        return false;
    }

    // ── 文字阴影 ────────────────────────────────────────────────────

    /**
     * 给一个 TextView 加「黑色、80% 强度、半径 = 字号 8%」的阴影（v0.1.20）。
     *
     * <p>只在自定义壁纸生效时加（{@link #sArmed}）⇒ 恢复默认背景后宿主文字回到原样。
     * 用 {@link #SHADOWED} 记住上次设的半径，<b>只在变化时</b>才
     * {@code setShadowLayer} + {@code invalidate} —— 否则每帧都 invalidate 会打成死循环
     * （v0.1.17 的教训：绘制路径里改状态必须收敛）。
     *
     * <p>★ v0.1.20 新增：坐在<b>亮底</b>上的文字不加阴影（用户第 6 条）——
     * PurePlay 的「导入 / 创建播放列表」、设定里的「同步已购买作品」都是实心浅色按钮，
     * 浅底上打黑阴影只会把字糊成一团。命中亮底时顺便把之前加过的清掉。
     */
    /** 文字阴影诊断条数 / 预算（v0.1.41 探针③）。 */
    static volatile int sShadowLogs;
    static final int SHADOW_LOG_BUDGET = 20;

    /** 相对亮度（0–1，Rec.601 近似，够用来判「浅底 / 深底」）。 */
    static float luma(int c) {
        float r = ((c >> 16) & 0xff) / 255f;
        float g = ((c >> 8) & 0xff) / 255f;
        float b = (c & 0xff) / 255f;
        return 0.299f * r + 0.587f * g + 0.114f * b;
    }

    // ── 热路径 ──────────────────────────────────────────────────────

    /** 「大块底色」清单（防止重复打）：class + 宽x高。 */
    static final java.util.Set<String> BIG_SURFACE =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    static volatile int sBigSurfLogs;
    static final int BIG_SURF_LOG_BUDGET = 72;

    /**
     * ★★★ v0.1.31 诊断：把所有「够大的底色」连形状一起打出来。
     *
     * <p>★★★ v0.1.111（S0）：额外输出 {@code kind=}（panel / mini / icon / tab / nav /
     * other）与 {@code leaf=}。<b>分类只写日志，不改绘制</b>。
     *
     * <p>★★★ v0.1.124 / v0.1.125（S0 真机修订）：
     * <ul>
     *   <li>宿主这版 {@code r} 恒为 0 —— mini/icon <b>不再依赖半径</b>；</li>
     *   <li>同时打 {@code top=} 与 {@code winY=}；</li>
     *   <li>v0.1.125：mini 必须 <b>不透明</b>（α≥0xE0）且 {@code winY} 落在屏内，
     *       挡掉半透明强调色卡片（{@code #1A……}）与动画错位帧。</li>
     * </ul>
     */
    static void noteBigSurface(View v, Drawable d, Rect b) {
        if (sBigSurfLogs >= BIG_SURF_LOG_BUDGET) return;
        if (sSW <= 0 || sSH <= 0) return;
        int w = b.width();
        int h = b.height();
        // S0：几何预筛（廉价）—— 面板尺寸 或 chrome 候选尺寸才继续
        boolean panelSized = w * 100 >= (long) sSW * GLASS_MIN_W_PCT
                && h * 100 >= (long) sSH * (GLASS_MIN_H_PCT - 2);
        boolean chromeSized = !panelSized && looksLikeChromeSize(v, b);
        if (!panelSized && !chromeSized) return;
        float dens = densityOf(v);
        int winY = -1;
        try {
            int[] loc = LOC.get();
            v.getLocationInWindow(loc);
            winY = loc[1];
        } catch (Throwable ignored) {
        }
        Integer solid = solidColorOf(d);
        String kind = classifySurfaceKind(v, d, b, panelSized, dens, winY, solid);
        String key;
        try {
            key = v.getClass().getSimpleName() + "/" + w + "x" + h + "/" + kind;
        } catch (Throwable t) {
            return;
        }
        if (!BIG_SURFACE.add(key)) return;
        sBigSurfLogs++;
        boolean leaf = false;
        try {
            leaf = !(v instanceof android.view.ViewGroup)
                    || ((android.view.ViewGroup) v).getChildCount() == 0;
        } catch (Throwable ignored) {
        }
        boolean glass = PanelGlass.isGlassSurface(v, d, b);
        if (glass) kind = "panel";
        float hDp = dens > 0.1f ? h / dens : 0f;
        log("surface " + key
                + " top=" + topOf(v)
                + " winY=" + winY
                + " hDp=" + (int) (hDp + 0.5f)
                + " dens=" + String.format(java.util.Locale.US, "%.2f", dens)
                + " op=" + d.getOpacity()
                + " r=" + (int) maxRadiusOf(d)
                + " c=" + (solid == null ? "?" : String.format("#%08X", solid))
                + " kind=" + kind + " leaf=" + (leaf ? 1 : 0)
                + " scr=" + (insideScreen(v) ? 1 : 0)
                + " bs=" + (insideBottomSheet(v) ? 1 : 0)
                + " dlg=" + (inDialogWindow(v) ? 1 : 0)
                + " cov=" + (PlayPage.coveredByGlass(v) ? 1 : 0)
                + " chain=" + chainOf(v)
                + (glass ? " => GLASS" : " => plain"));
    }

    static float densityOf(View v) {
        try {
            float d = v.getResources().getDisplayMetrics().density;
            if (d >= 0.5f) return d;
        } catch (Throwable ignored) {
        }
        return 3f;
    }

    /**
     * 廉价几何预筛：矮条 / 近满宽 chrome / 近方形按钮，才值得做分类。
     * 放宽到 ≥88% 宽（真机迷你条 1176/1272≈92.5%）。
     */
    static boolean looksLikeChromeSize(View v, Rect b) {
        if (v == null || b == null || sSW <= 0 || sSH <= 0) return false;
        int h = b.height();
        int w = b.width();
        if (h <= 0 || w <= 0) return false;
        // 高于 1/5 屏的不当 chrome
        if (h > sSH / 5) return false;
        float dens = densityOf(v);
        float hDp = h / dens;
        // 近满宽矮条（顶栏 / 底栏 / 迷你播放器）：32~90dp
        if (w * 100 >= (long) sSW * 88 && hDp >= 32f && hDp <= 90f) return true;
        // 近方形图标钮：36~52dp
        if (hDp >= 36f && hDp <= 52f && w >= h * 0.85f && w <= h * 1.15f) return true;
        // 矮胶囊图标条：高 ≤48dp、宽 36~140dp
        if (hDp <= 48f && hDp >= 32f) {
            float wDp = w / dens;
            if (wDp >= 36f && wDp <= 140f) return true;
        }
        return false;
    }

    /**
     * S0 面分类（方案 §2.6.1 / §2.7.1 + 2026-11-09 真机修订）。
     * 只用于日志，<b>不驱动绘制</b>。圆角字段在这版宿主恒为 0，禁止当判据。
     *
     * <p>v0.1.125：mini 加「不透明 + winY 在屏内」，避免把列表里的半透明强调色块
     * （{@code #1AC49933}）和动画飞出屏外的帧标成迷你播放器。
     */
    static String classifySurfaceKind(View v, Drawable d, Rect b,
                                              boolean panelSized, float dens, int winY,
                                              Integer solid) {
        if (v == null || d == null || b == null || b.isEmpty()) return "other";
        if (sSW <= 0 || sSH <= 0) return "other";
        int w = b.width();
        int h = b.height();
        if (dens < 0.5f) dens = 3f;
        float hDp = h / dens;
        float wDp = w / dens;
        boolean fullW = w * 100 >= (long) sSW * 94;
        boolean nearFullW = w * 100 >= (long) sSW * 88; // 迷你条实测 ≈92.5%
        // winY 必须落在本窗口内（允许 ±8px 贴边误差）；超出 = 动画/独立窗口错位
        boolean winInScreen = winY >= -8 && winY < sSH && winY + h <= sSH + 8;
        boolean bottom = false;
        boolean top = false;
        if (winY >= 0) {
            bottom = winInScreen && winY + h >= sSH - sEdgeBottom - 8;
            top = winInScreen && winY <= sEdgeTop + 8;
        } else {
            try {
                int[] loc = LOC.get();
                v.getLocationInWindow(loc);
                int y = loc[1];
                boolean in = y >= -8 && y < sSH && y + h <= sSH + 8;
                bottom = in && y + h >= sSH - sEdgeBottom - 8;
                top = in && y <= sEdgeTop + 8;
            } catch (Throwable ignored) {
            }
        }
        // 迷你播放器：贴底 + 近满宽 + 40~56dp + 不透明底（白/深色胶囊）
        //   真机误伤：半透明强调色 #1A…… α≈10% 的列表卡，滚动到贴底时被当成 mini。
        boolean opaqueEnough = solid != null && ((solid >>> 24) & 0xff) >= 0xE0;
        if (opaqueEnough && bottom && nearFullW && hDp >= 40f && hDp <= 56f) return "mini";
        // 底部 tab 栏容器：满宽 + 56~80dp（只标记，S4 默认不上玻璃）
        if (bottom && fullW && hDp >= 56f && hDp <= 80f) return "tab";
        // 顶部导航容器：满宽贴顶 + 32~110dp（真机顶条有 37dp 也有 ≈95~104dp）
        if (top && fullW && hDp >= 32f && hDp <= 110f) return "nav";
        // 圆钮 / 胶囊图标：不依赖半径；半透明色块不当 icon
        if (opaqueEnough || solid == null) {
            float aspect = wDp > 0.1f ? hDp / wDp : 0f;
            if (aspect >= 0.85f && aspect <= 1.15f && wDp >= 36f && wDp <= 52f) return "icon";
            if (hDp >= 32f && hDp <= 48f && wDp >= 36f && wDp <= 140f && !nearFullW) {
                return "icon";
            }
        }
        return panelSized ? "panel-cand" : "other";
    }

    /**
     * ★★★ v0.1.32 诊断：这块「没底色的内容根」为什么没被当成面板铺玻璃。
     *
     * <p>用户第 3 条「只有概述有模糊、其余紫底」每次都只能靠猜是哪一块没命中。
     * 这一行把它的宽高、位置、父层高宽、是否在页面里、祖先链一次性打出来
     * （每种尺寸只打一次，最多 24 条）。下一轮直接照着这行改，不用再试。
     */
    static void noteSheetMiss(View v, int w, int h) {
        if (sSheetMissLogs >= SHEET_MISS_BUDGET) return;
        // 只登记「有资格当面板」的块：够宽、够高、没有自己的底色。
        // 否则 24 条预算会被图标 / 分隔线 / 单行文字吃光。
        if (sSW <= 0 || sSH <= 0) return;
        if (w * 2 < sSW || h * 8 < sSH) return;
        try {
            if (v.getBackground() != null) return;
        } catch (Throwable t) {
            return;
        }
        try {
            String key = w + "x" + h + "@" + topOf(v);
            if (!SHEET_MISS_KEYS.add(key)) return;
            sSheetMissLogs++;
            View p = v.getParent() instanceof View ? (View) v.getParent() : null;
            int pw = 0;
            int ph = 0;
            int pt = 0;
            StringBuilder par = new StringBuilder("-");
            if (p != null) {
                pw = p.getWidth();
                ph = p.getHeight();
                pt = topOf(p);
                par.setLength(0);
                par.append(p.getClass().getSimpleName()).append(' ').append(pw).append('x').append(ph)
                        .append(" top=").append(pt).append(" bot=").append(isBottomAnchored(p) ? 1 : 0);
            }
            log("sheet miss " + name(v) + " " + w + "x" + h + " top=" + topOf(v)
                    + " inScr=" + (insideScreen(v) ? 1 : 0)
                    + " bs=" + (insideBottomSheet(v) ? 1 : 0)
                    + " dlg=" + (inDialogWindow(v) ? 1 : 0)
                    + " cov=" + (PlayPage.coveredByGlass(v) ? 1 : 0)
                    + " parent=" + par
                    + " chain=" + chainOf(v));
        } catch (Throwable ignored) {
        }
    }

    static final java.util.Set<String> SHEET_MISS_KEYS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    static volatile int sSheetMissLogs;
    static final int SHEET_MISS_BUDGET = 24;

    /**
     * ★★★ v0.1.32：这一层在不在 react-navigation 的<b>页面</b>里。
     *
     * <p>为什么终于不用几何判据了：v0.1.27~v0.1.31 连错五次，每一次都死在同一个事实上 ——
     * <b>「底部浮层」和「页面里的块」在宽 / 高 / 贴底 / 所属窗口上完全重合</b>：
     * <pre>
     *   概述浮层     ReactViewGroup 1272x2112 top=660  #FF252451  贴底
     *   详情页列表块  ReactViewGroup 1272x1492 top=1280 #FF252451  贴底
     *   播放列表整页  ScreenContentWrapper 1272x2564           整页
     * </pre>
     * 尺寸分不开、颜色分不开、窗口也分不开（宿主所有弹层都跑在同一个 1272x2564 的
     * react-native-screens 窗口里）。
     *
     * <h3>能分开它们的只有一件事：<b>它挂在谁下面</b></h3>
     * 页面里的每一块，祖先里一定有一个 react-navigation 的 {@code ScreenContentWrapper}；
     * 而 @gorhom 的 {@code BottomSheetModal} 是渲染在<b>根 portal</b> 上的 ——
     * 它是 {@code NavigationContainer} 的<b>兄弟</b>，祖先链里<b>没有</b>
     * {@code ScreenContentWrapper}。
     *
     * <p>所以：<b>在页面里 ⇒ 永远不是浮层 ⇒ 永远不糊</b>。
     * 用户连说三轮的「播放列表 / 摘选详情 / 文件列表块 / 作品文件夹显示区不该糊」
     * 全部落在这一条上；四条真正该糊的信息卡全部落在它的反面。
     */
    static boolean insideScreen(View v) {
        View p = v;
        for (int i = 0; i < 16 && p != null; i++) {
            try {
                if (p.getClass().getName().contains(SCREEN_MARK)) return true;
            } catch (Throwable ignored) {
            }
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
     * ★★★ v0.1.32：这一层是不是挂在<b>底部浮层</b>里（@gorhom 的 BottomSheetModal /
     * Compose 的 ModalBottomSheet）。
     *
     * <p>和 {@link #insideScreen} 一起用，顺序是<b>浮层优先</b>：
     * 浮层即使被渲染在某个页面里（很多 App 就是把它挂在当前屏幕的树里），
     * 它也仍然是浮层 —— 该糊。页面里那些「长得跟浮层一模一样的块」才不糊。
     *
     * <p>⚠️ 这里**不能**用宽泛的 "Sheet"：react-native-screens 的整页模态窗口叫
     * {@code RNSFormSheetHost}，一匹配就把整页也当成浮层了（那正是前五版翻车的地方）。
     */
    static boolean insideBottomSheet(View v) {
        View p = v;
        for (int i = 0; i < 16 && p != null; i++) {
            try {
                String n = p.getClass().getName();
                if (n.contains("BottomSheet") || n.contains("Bottomsheet")) return true;
            } catch (Throwable ignored) {
            }
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
     * ★★★ v0.1.33（r38 日志定案）：这一层在不在<b>独立 Dialog 窗口</b>里。
     *
     * <p>r38 日志里第一次把祖先链打全了，宿主的分层一刀切得干干净净：
     * <pre>
     *   信息弹层（概述 / 下载音轨 / 简介 / 导入）
     *     ReactViewGroup&lt;…&lt;DialogRootViewGroup&lt;FrameLayout&lt;FrameLayout&lt;LinearLayout
     *   页面（播放列表 / 摘选 / 作品详情）与主窗口里的文件夹区
     *     …&lt;SafeAreaProvider&lt;RNGestureHandlerRootView&lt;ReactSurfaceView&lt;ContentFrameLayout
     * </pre>
     * 只有第一条是「浮层」。这也是<b>唯一</b>能把「弹层」和「主窗口里的半透明大块」
     * 分开的信号 —— 尺寸 / 颜色 / 贴底在宿主上全都是重合的。
     */
    static boolean inDialogWindow(View v) {
        View p = v;
        for (int i = 0; i < 16 && p != null; i++) {
            try {
                String n = p.getClass().getName();
                if (n.contains("Dialog")) return true;
            } catch (Throwable ignored) {
            }
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

    /** 委托 PlayPage（B7 拆分）。 */
    public static void notePlayPage() {
        PlayPage.notePlayPage();
    }

    /** 「play seek skip」诊断日志条数（限 2 条，别刷屏）。 */
    static volatile int sSeekLogs2;

    /**
     * ★★★ v0.1.39（r44 用户第 4 / 5 条）：**播放页判定为什么要加「保持窗口」**。
     *
     * <h3>本轮找到的真凶：一道忘了删的旧门槛</h3>
     * {@link #notePlaySeek} 在 v0.1.38 已经把手高门槛从「≥2% 屏高」放到「≥6px」，
     * 但 {@link #isPlayLive} 里<b>还留着同一道 2% 检查</b>：
     * <pre>
     *   if (v.getHeight() * 100 &lt; (long) sSH * 2) return false;
     * </pre>
     * 宿主这个进度条只有 <b>54px</b> 高，屏高 2772 ⇒ 54×100 = 5400 &lt; 5544
     * ⇒ <b>永远返回 false</b>。r44 日志里 `play page miss root` 反复出现就是这个原因：
     * 「活体进度条」这条唯一的硬判据从头到尾没生效过，播放页只能靠
     * {@code blurRadius ≥ 8} 这一条逐层去猜 ⇒
     * 「有时背景透明 / 有时模糊不生效 / 有时封面不显示」全都在同一屏里同时出现。
     *
     * <h3>为什么还要加 1.6 秒的保持</h3>
     * 切后台 / 回来时宿主会把进度条短暂 detach 或 {@code isShown()} 翻 false，
     * 一帧判 false 就足够让某一层铺成清晰壁纸（用户看到的就是「混着来」）。
     * 只要在 1.6 秒内见过一次活进度条，整段时间都算播放页 —— 播放页每帧都在画，
     * 这个窗口会不停被刷新；真的离开播放页时，1.6 秒后自然过期。
     */
    static volatile long sPlayHoldUntil;

    /**
     * 「播放页」判定的保持窗口（ms）。
     *
     * <p>★★★ v0.1.43：1600 → 700。太长会让离开播放页后的列表/详情
     * 仍被当成播放页铺模糊（用户第 6 / 7 条「高斯模糊概率出现」）。
     */
    static final long PLAY_HOLD_MS = 700L;

    /** 播放页「保持窗口」的页面锚点：窗口是从哪个页面续的（v0.1.47）。 */
    static volatile java.lang.ref.WeakReference<View> sPlayHoldPage;

    /**
     * ★ 真进度条当时所在的页面锚点（v0.1.47）。
     *
     * <p>见 {@link #isPlayPage}：它是「哪个 Screen 才是播放页」的权威答案，
     * 用来纠正 {@code notePlayPage} 那一瞬可能绑错的 {@code sPlayScreen}。
     */
    static volatile java.lang.ref.WeakReference<View> sSeekPageAnchor;

    /** {@link #playContext} 的帧窗口时间戳。 */
    static volatile long sPcAt;

    /** 帧内 memo：View → 页面锚点（每帧清空）。 */
    static final java.util.Map<View, View> PC_ANCHOR =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<View, View>());

    /** 帧内 memo：页面锚点 → 这一页是不是播放页（每帧清空）。 */
    static final java.util.Map<View, Boolean> PC_BOOL =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<View, Boolean>());

    /**
     * ★★★ v0.1.43：小字强调色的「绘制前补一刀」。
     *
     * <p>只动 ≤18sp 的字；已经是强调色 / 近白 / 关着开关的一律不动。
     * 走 {@code setTextColor(int)} 会再进我们的钩子，收敛后 n==c 即停。
     */
    /** 小字强调色：同一 TextView 在同一 currentColor 上已判定过就不再算。 */
    static final java.util.Map<TextView, Integer> ACCENT_SEEN =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    static float sScaledDensity;

    static void forceAccentSmallText(TextView tv) {
        if (!AccentColor.on() || tv == null) return;
        try {
            float dens = sScaledDensity;
            if (dens <= 0f) {
                dens = tv.getResources().getDisplayMetrics().scaledDensity;
                if (dens > 0f) sScaledDensity = dens;
            }
            if (dens <= 0f) return;
            float sp = tv.getTextSize() / dens;
            if (sp > 18f) return;
            int c = tv.getCurrentTextColor();
            Integer seen = ACCENT_SEEN.get(tv);
            if (seen != null && seen == c) return;
            int n = AccentColor.remapSmallText(c);
            if (n == c) {
                ACCENT_SEEN.put(tv, c);
                return;
            }
            // 若当前是 ColorStateList，setTextColor(int) 会把它摊成单色 ——
            // 底栏选中态会丢。只在「此刻颜色仍是宿主紫族 / 中性半透明」时动手。
            int a = (c >>> 24) & 0xff;
            int r = (c >> 16) & 0xff;
            int g = (c >> 8) & 0xff;
            int b = c & 0xff;
            boolean neutral = (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b))) <= 6;
            boolean purpleish = b > r && r >= g;
            if (!neutral && !purpleish) {
                ACCENT_SEEN.put(tv, c);
                return;
            }
            if (neutral && a == 0xff) {   // 不透明纯黑正文
                ACCENT_SEEN.put(tv, c);
                return;
            }
            tv.setTextColor(n);
            ACCENT_SEEN.put(tv, n);
        } catch (Throwable ignored) {
        }
    }

    /**
     * ★★★ v0.1.51（r51 用户第 4 条）：**类名判定缓存**。
     *
     * <p>旧实现每一层都调一次 {@code getClass().getName().contains(SCREEN_MARK)} ——
     * {@code getName()} 每次都新建一个 String，而 {@link #screenOf} 是绘制热路径上
     * 被 {@code playContext} 反复调用的。上/下各 16 层的字符串分配，
     * 在底部导航切换这种「整屏重画」的时刻非常显眼。
     * 现在按 {@code Class} 缓存判定结果（WeakHashMap，不会泄漏）。
     */
    static final java.util.Map<Class<?>, Boolean> SCREEN_CLS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Class<?>, Boolean>());

    static boolean isScreenClass(Class<?> c) {
        if (c == null) return false;
        Boolean b = SCREEN_CLS.get(c);
        if (b == null) {
            try {
                b = c.getName().contains(SCREEN_MARK);
            } catch (Throwable t) {
                b = Boolean.FALSE;
            }
            SCREEN_CLS.put(c, b);
        }
        return b;
    }

    /** 最近的页面容器（react-native-screens 的 {@code Screen}）；主窗口根上没有 ⇒ null。 */
    static View screenOf(View v) {
        View p = v;
        for (int i = 0; i < 16 && p != null; i++) {
            try {
                if (isScreenClass(p.getClass())) return p;
            } catch (Throwable ignored) {
            }
            Object par;
            try {
                par = p.getParent();
            } catch (Throwable t) {
                return null;
            }
            p = par instanceof View ? (View) par : null;
        }
        return null;
    }

    /** 这一层是不是「浮层」（BottomSheet 或独立 Dialog 窗口）。玻璃只服务浮层。 */
    static boolean inFloatingLayer(View v) {
        return insideBottomSheet(v) || inDialogWindow(v) || mainWindowSheet(v);
    }

    // ══════════════════════════════════════════════════════════════════
    // ★★★ v0.1.47：避让 Float 模块（悬浮窗字幕 / 开关胶囊）
    // ══════════════════════════════════════════════════════════════════

    /** 其它 Xposed 模块注入的 View（它们的类不在宿主 APK 里）。 */
    static final String[] FOREIGN_PREFIX = {
            "io.github.ariinyume.dlsitesoundfloat.",   // Float 模块（悬浮窗字幕 / 胶囊）
    };

    /**
     * ★★★ 这个 View 是不是在<b>宿主的 Activity 窗口</b>里（而不是某个悬浮窗 / 面板窗口）。
     *
     * <p>判据是结构级的，不猜类名：顺着父链走到根 View，根 View 的 LayoutParams 若是
     * {@link android.view.WindowManager.LayoutParams}：
     * <ul>
     *   <li>{@code type == TYPE_BASE_APPLICATION(1)} ⇒ Activity 窗口，接管；</li>
     *   <li>其它（{@code TYPE_APPLICATION_PANEL(1000)} / {@code TYPE_APPLICATION_OVERLAY(2038)}
     *       / {@code TYPE_TOAST} …）⇒ 悬浮窗，<b>一律放过</b>。</li>
     * </ul>
     *
     * <p>为什么够准：Activity 的根 View（DecorView）的 LayoutParams 一定是
     * TYPE_BASE_APPLICATION；而任何 {@code WindowManager.addView} 加进来的窗口，
     * 根 LayoutParams 都是非 1 的 type。这是框架保证的，不依赖宿主的包结构。
     *
     * <p>拿不到 LayoutParams（极端情况）时返回 {@code true} —— <b>宁可多管、不要少管</b>，
     * 否则整个文字阴影 / 强调色功能会静默失效，那是比「多管一个浮窗」严重得多的回归。
     */
    static boolean inAppWindow(View v) {
        if (v == null) return true;
        try {
            View root = v.getRootView();
            if (root == null) return true;
            android.view.ViewGroup.LayoutParams lp = root.getLayoutParams();
            if (!(lp instanceof android.view.WindowManager.LayoutParams)) {
                return true;      // 还没 attach 到窗口 / 取不到 ⇒ 按「是」处理
            }
            int t = ((android.view.WindowManager.LayoutParams) lp).type;
            return t == android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * ★★★ 这个 View 是不是<b>别的模块</b>注入的。
     *
     * <p>判据：类名以 {@link #FOREIGN_PREFIX} 之一开头。这类 View 的配色 / 阴影是
     * 那个模块自己的配置项，被我们改写等于让用户在那边的设置页里调了没反应。
     *
     * <p>⚠️ 只挡<b>类名</b>，不挡 ClassLoader：Android 的 {@code TextView} 由宿主类加载器
     * 加载，Float 模块的胶囊用的就是普通 {@code TextView}，按 ClassLoader 判会漏。
     */
    static boolean foreignModuleView(View v) {
        if (v == null) return false;
        try {
            String n = v.getClass().getName();
            for (String p : FOREIGN_PREFIX) {
                if (n.startsWith(p)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * ★★★ v0.1.39（r44 用户第 2 / 3 条）：<b>主窗口里的底部弹层</b>。
     *
     * <h3>为什么必须补这条</h3>
     * r44 两段录屏里，「下载音轨 / 简介 / 导入」这三张卡背后是<b>一层淡紫色</b>，
     * 「点击三个点 / 新建播放列表 / 新建摘选播放列表」的模糊区<b>上方多一条淡紫横条</b>
     * —— 共同点是这些弹层<b>根本没有被铺玻璃</b>（截图里能清楚看到背后那张清晰插画），
     * 而我们所有面板判据的前提都是 {@code inDialogWindow / insideBottomSheet}。
     *
     * <p>r44 日志证明宿主的弹层并不总在独立 Dialog 窗口里：
     * {@code paint sheet leaf -> ReactViewGroup 1153x1246 top=1709 … dlg=0}。
     * 也就是说 react-native 的 {@code Modal} 这一版是<b>直接挂在 RN 根视图里</b>的，
     * 类名里既没有 Dialog 也没有 BottomSheet ⇒ {@code bs=0 dlg=0} 双双漏掉。
     *
     * <h3>判据（三条同时成立，缺一不可）</h3>
     * <ol>
     *   <li><b>不在页面里</b>：祖先链上没有 {@code ScreenContentWrapper}
     *       —— 页面永远在 Screen 里，弹层永远不在（这是结构级的差别，不是比例猜测）；</li>
     *   <li><b>贴底</b>：{@link #isBottomAnchored}，即从屏幕底部升起；</li>
     *   <li><b>够宽</b>：≥ {@link #SHEET_MIN_W_RATIO} 屏宽（弹层都是通栏，列表行不够宽）。</li>
     * </ol>
     *
     * <p>「作品详情页里那块文件列表」之所以安全，正是因为它<b>在</b> Screen 里；
     * 播放列表详情整页同理。所以这条不会重犯 v0.1.27~v0.1.30 那四次误判。
     */
    static boolean mainWindowSheet(View v) {
        if (v == null || sSW <= 0) return false;
        try {
            if (insideScreen(v)) return false;
            int w = v.getWidth();
            if (w * 100 < (long) sSW * (int) (SHEET_MIN_W_RATIO * 100)) return false;
            return isBottomAnchored(v);
        } catch (Throwable t) {
            return false;
        }
    }

    static int sCoverKeptLogs;

    /** 已经铺过玻璃的那些块（弱引用，View 回收后自动清掉）。 */
    static final java.util.Map<View, Boolean> GLASSED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Boolean>());

    /** 祖先链（含自己）的类名简写，用于诊断「这块到底挂在哪个浮层下」。 */
    static String chainOf(View v) {
        StringBuilder sb = new StringBuilder();
        View p = v;
        for (int i = 0; i < 9 && p != null; i++) {
            String n;
            try {
                n = p.getClass().getSimpleName();
            } catch (Throwable t) {
                break;
            }
            if (n == null || n.isEmpty()) n = "?";
            if (sb.length() > 0) sb.append('<');
            sb.append(n);
            Object par;
            try {
                par = p.getParent();
            } catch (Throwable t) {
                break;
            }
            p = par instanceof View ? (View) par : null;
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════
    // ★★★ v0.1.31：玻璃表面（面板 / 信息卡片）的判据
    // ══════════════════════════════════════════════════════════════════

    /** 玻璃表面：宽度下限（% 屏宽）。 */
    static final int GLASS_MIN_W_PCT = 70;
    /** 玻璃表面：高度下限（% 屏高）。底部导航栏 7.5% / 迷你播放器 6% 都在线下。 */
    static final int GLASS_MIN_H_PCT = 9;
    /**
     * ★★★ v0.1.42（r13 用户第 3 条）：<b>亮底的上限</b>。
     *
     * <p>只有「明显是浅色卡片 / 白色胶囊按钮」才排除。中间调（面板常用
     * {@code #FFECE6EF} 这类浅灰紫、或 {@code #FF252451} 深紫）一律当面板。
     */
    static final float GLASS_LIGHT_LUMA = 0.72f;
    /**
     * ★★★ v0.1.42：玻璃表面的<b>面积下限</b>（% 屏面积）。
     *
     * <p>12% 刚好包住「下载音轨」这类 1440×882（27.8%）的底部面板，
     * 又能把「一行 1440×40 的分隔条 / 一个 1076×466 的确认框（11%）」挡在外面。
     */
    static final int GLASS_MIN_AREA_PCT = 12;

    /**
     * ★★★ v0.1.40：**面板玻璃专用的模糊强度（%）**。
     *
     * <h3>为什么不能再用条带档的 20%</h3>
     * 面板底和页面底是<b>同一张壁纸</b>。20% 模糊之后两张图的色块分布<b>完全一致</b>，
     * 只是轮廓稍微柔了一点 ⇒ 肉眼看不出「这里是一块玻璃」，
     * 只能看出「背景被压暗了一点」。用户一直说的「背后还是一层淡阴影」就是这个。
     *
     * <p>38% 是拟态的平衡点：足以看出明显的高斯模糊，
     * 又不会像 v0.1.38「糊两遍」那样把壁纸糊成色块。
     */
    static final int GLASS_BLUR_PCT = 38;

    /** 面板玻璃底图（清晰壁纸模糊 {@link #GLASS_BLUR_PCT}% 后的缓存）。 */
    static volatile Bitmap sWpGlassBmp;


    /** 玻璃失败诊断的已打条数。 */
    static int sGlassFailLogs;

    /**
     * ★★★ v0.1.39（r44 用户第 2 / 3 条）：<b>面板底色的「叠回」比例大幅下调</b>。
     *
     * <h3>上一版为什么被用户看成「多了一层淡紫色的阴影」</h3>
     * v0.1.38 给不透明深色面板加了 34% 的 {@code keepTint}，给半透明面板 60%。
     * 真机结果：每一块面板 / 每一张卡片都变成「毛玻璃 + 一层紫色」——
     * 用户原话是「这些信息面板背后多了一层淡紫色的阴影」；
     * 更糟的是面板<b>头部那一块</b>（它和面板体不是同一个 View）拿到的叠回比例
     * 与面板体不同 ⇒ 顶部凭空多出<b>一条淡紫色横条</b>（第 3 条）。
     *
     * <p>玻璃底图本身（模糊壁纸）已经足够把面板和页面分开，叠回只需要
     * 「压一点点暗、让白字有对比」——6% / 12% 这个量级肉眼几乎只剩「面板更沉一点」，
     * 不会再被读成紫色阴影，也小到看不出条带。
     *
     * <p>顺手的好处：{@code keepTint} 走 {@code saveLayerAlpha}，
     * 每帧每块面板一次离屏层是很贵的开销；比例降低不改变成本，
     * 但配合 {@link #glassBmp} 回到单次模糊，面板入场时「先紫后糊」的分层感会明显减轻。
     */
    static final float GLASS_SOLID_TINT_PCT = 0.00f;
    /** 半透明底叠回去的强度（0~1）。★ v0.1.39：0.60 → 0.12（同上，紫色阴影的来源）。 */
    static final float GLASS_TINT_PCT = 0.10f;
    /** react-navigation 的屏幕容器类名片段：祖先里有它 ⇒ 这是页面，不是浮层。 */
    static final String SCREEN_MARK = "ScreenContentWrapper";

    /** 圆角字段缓存（按 Drawable 类）。 */
    static final java.util.Map<Class<?>, Object> RADII_FIELD =
            new java.util.concurrent.ConcurrentHashMap<>();
    static final Object NO_RADII = new Object();

    /**
     * RN 的 {@code BackgroundDrawable} 里那个 {@code float[8]}（四角 × 两个分量）。
     *
     * <p>按<b>类型 + 长度</b>找字段，不看名字 —— release 包 R8 会把字段名混淆掉
     * （颜色字段就变成了 {@code h}），但类型和长度不会变。
     */
    static java.lang.reflect.Field radiiField(Class<?> cls) {
        Object cached = RADII_FIELD.get(cls);
        if (cached != null) return cached == NO_RADII ? null : (java.lang.reflect.Field) cached;
        java.lang.reflect.Field hit = null;
        try {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (f.getType() != float[].class) continue;
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                hit = f;
                break;
            }
        } catch (Throwable t) {
            hit = null;
        }
        RADII_FIELD.put(cls, hit == null ? NO_RADII : hit);
        return hit;
    }

    /** 这块底的最大圆角（px）；读不到 = 0。 */
    static float maxRadiusOf(Drawable d) {
        if (d == null) return 0f;
        java.lang.reflect.Field f = radiiField(d.getClass());
        if (f == null) return 0f;
        try {
            float[] r = (float[]) f.get(d);
            if (r == null) return 0f;
            float m = 0f;
            for (float x : r) {
                if (x > m) m = x;
            }
            return m;
        } catch (Throwable t) {
            return 0f;
        }
    }

    static String scrOf(View v) {
        try {
            int[] loc = LOC.get();
            v.getLocationOnScreen(loc);
            return loc[0] + "," + loc[1];
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 玻璃成功日志条数 / 预算（v0.1.41 探针①）。 */
    static volatile int sGlassLogs;
    static final int GLASS_LOG_BUDGET = 12;

    /**
     * 把宿主原来那层底色按 {@link #GLASS_TINT_PCT} 叠回去（不改变 drawable 本身）。
     *
     * <p>用 {@code saveLayerAlpha} 压一层再画，属于「只影响这一次绘制」的非破坏性做法。
     * 全强度叠回去等于把下面的模糊盖死 —— 那正是「退回紫色底」。
     */
    static void keepTint(Canvas c, Drawable d, Rect b, float pct) {
        int a = (int) (Math.max(0f, Math.min(1f, pct)) * 255f);
        if (a <= 0) return;
        Boolean mark = REENTRY.get();
        int save = -1;
        try {
            save = c.saveLayerAlpha(b.left, b.top, b.right, b.bottom, a);
            REENTRY.set(Boolean.TRUE);
            d.draw(c);
        } catch (Throwable ignored) {
        } finally {
            REENTRY.set(mark);
            if (save >= 0) {
                try {
                    c.restoreToCount(save);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 播放页根上「对象级底图」的账：View → 当前挂着的位图（v0.1.72）。 */
    static final java.util.Map<View, Bitmap> ROOT_BG =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Bitmap>());

    /** 「换播放页根背景」是否正在进行中（防止 draw 里 setBackground 导致重入）。 */
    static volatile boolean sRootBgBusy;

    /** ★ v0.1.73：当前挂着「对象级底图」的播放页根（内层满屏底图据此 drop）。 */
    static volatile View sPlayRootBgView;

    /** ★ v0.1.73：那个根上挂的是哪张位图（换壁纸后才需要重设）。 */
    static volatile Bitmap sPlayRootBgBmp;


    /**
     * ★★★ v0.1.73（r76 第 1 条）：这一层是不是**播放页根底板那棵子树**里的。
     *
     * <p>为什么不能用 {@link #playContext}：回前台 / 切后台会让 RN 重建视图树，
     * {@code sPlaySeek} 的「同页」判定很容易失配 ⇒ playContext 变 false ⇒
     * 内层满屏底图掉回清晰档。而「根底板挂在哪个 View 上」是对象级事实，
     * 宿主重建时 {@link #ensurePlayRootBg} 会重新挂一次并更新引用，天然跟着走。
     */
    /** {@link #hasFullBackdropChild} 的单条缓存（同一层 300ms 内复用）。 */
    static volatile View sFbView;
    static volatile boolean sFbVal;
    static volatile long sFbAt;

    /** {@link #hasSeekShape} 的 300ms 缓存。 */
    static volatile View sSkView;
    static volatile boolean sSkVal;
    static volatile long sSkAt;

    /** ★ v0.1.83：心跳维护的「播放页根」（绘制路径只读它，不再自己遍历）。 */
    static volatile java.lang.ref.WeakReference<View> sPlayRootRef;

    /** 「找播放页根」的上次时刻（500ms 限流）。 */
    static volatile long sRootScanAt;

    /** 「这一层上一次被 paint play 画过时，坐在哪一页」（v0.1.80）。 */
    static final java.util.Map<View, java.lang.ref.WeakReference<View>> PLAY_PAINT_PAGE =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<View, java.lang.ref.WeakReference<View>>());

    /** 复用的临时矩形（避免每帧 new Rect）。 */
    static final ThreadLocal<Rect> CLIP0 = new ThreadLocal<Rect>() {
        @Override
        protected Rect initialValue() {
            return new Rect();
        }
    };

    static boolean paintWallpaper(Drawable d, Canvas c, boolean requireOpaque) {
        if (!sArmed) return false;
        if (WindowBg.videoFullscreen()) return false;
        if (sSW <= 0 || sSH <= 0) return false;
        // ★★★ v0.1.73：这一层的背景**已经是我们自己的底图**（{@link
        // io.github.ariinyume.morimod.view.CoverDrawable}，由 ensurePlayRootBg 挂上）⇒
        //   直接放行，让它自己画一次。否则「background 画一遍 + 这里 canvas 再画一遍」
        //   会叠出两层对齐基准不同的壁纸 —— 那正是用户 r75 看到的「背景位移 + 色块」。
        if (d instanceof io.github.ariinyume.morimod.view.CoverDrawable) return false;
        // 面板分支会「再调一次自己的 draw 把半透明底叠回来」，重入时必须直接放行，
        // 否则就是无限递归。
        if (Boolean.TRUE.equals(REENTRY.get())) return false;
        sSeen++;
        try {
            View v = ownerView(d);
            if (v == null) return false;
            // ★★★ v0.1.146：隐私政策 / 使用条款 WebView 不换底、不丢 dim。
            if (isWebContentView(v)) return false;
            Rect b = d.getBounds();
            if (b == null || b.isEmpty()) return false;
            // ★★★ v0.1.153：lightNav = 栏/页底仍铺壁纸，但跳过玻璃 / detach 重路径。
            //   搜索图标靠 paintTopMask 挖洞。
            int bw = b.width();
            int bh = b.height();
            boolean maybePage = bw * 100 >= (long) sSW * 90
                    || (long) bw * bh * 100 >= (long) sSW * sSH * 55;
            if (PlayPage.lightNav()) {
                if (isBarLike(v)) {
                    int[] loc = LOC.get();
                    v.getLocationInWindow(loc);
                    if (loc[1] <= sEdgeTop) {
                        if (paintTopMask(v, c, b)) {
                            sBar++;
                            BgDiag.hit("paint top fill -> ", v, b);
                            return true;
                        }
                    } else if (paintBottomMask(v, c, b)) {
                        sBar++;
                        BgDiag.hit("paint bar bg -> ", v, b);
                        return true;
                    }
                    return false;
                }
                if (maybePage && sWpBmp != null && !sWpBmp.isRecycled()) {
                    if (requireOpaque && d.getOpacity() != PixelFormat.OPAQUE) return false;
                    Bitmap page = PlayPage.pageBmp(v);
                    if (page == null || page.isRecycled()) page = sWpBmp;
                    if (PanelGlass.paintAligned(c, page, v, b, false)) {
                        sPainted = true;
                        return true;
                    }
                }
                return false;
            }
            // 先按尺寸挡掉小块，再做 detach / 记根 —— 切页时这条是热路径。
            boolean maybeGlass = bw * 100 >= (long) sSW * GLASS_MIN_W_PCT
                    && bh * 100 >= (long) sSH * GLASS_MIN_H_PCT;
            if (!maybePage && !maybeGlass) return false;
            try {
                if (!sAttachTried) tryDetachOff(v);
            } catch (Throwable ignored) {
            }
            try {
                java.lang.ref.WeakReference<View> lr = sLastRoot;
                if (lr == null || lr.get() == null) {
                    sLastRoot = new java.lang.ref.WeakReference<>(v.getRootView());
                }
            } catch (Throwable ignored) {
            }
            noteBigSurface(v, d, b);

            // ★★★ v0.1.142：开屏 SplashScreenView 的紫底（#1C1B3D）。
            //   自定义背景开启且窗口层已钉壁纸时，这一层只负责挡视线 ——
            //   不画紫底即可透出 DecorView 上的 CoverDrawable，S logo 仍是子 View。
            //   画路径兜底：宿主淡出动画里可能把紫底 ColorDrawable 又设回来，
            //   单靠 WindowBg#clearSplashPurple 的 setBackground 会和它打架。
            if (CoverLayer.isSplashView(v)) {
                Bitmap splashBmp = sWpBmp != null ? sWpBmp : sWpBarBmp;
                if (splashBmp != null) {
                    if (PanelGlass.paintAligned(c, splashBmp, v, b, false)) {
                        sSkipped++;
                        sPainted = true;
                        BgDiag.hit("paint splash -> ", v, b);
                        return true;
                    }
                    // 画失败也绝不再画紫：透出 DecorView 壁纸
                    sScrim++;
                    sPainted = true;
                    BgDiag.hit("drop splash -> ", v, b);
                    return true;
                }
            }

            // ★★★ v0.1.29 用户第 2 条（这是「信息卡片挂不上高斯模糊」的真凶）：
            //   面板分支<b>必须排在「满屏」之前</b>。面板窗口是 wrap 的
            //   —— 窗口就是面板本身 —— 所以面板自己的背景 drawable <b>正好铺满窗口</b>，
            //   {@link #coversScreen} 于是判它「满屏 ⇒ 换成清晰壁纸」，
            //   面板从此永远是一张不糊的壁纸，用户在「概述 / 下载音轨 / 简介 / 导入」
            //   四张卡上看到的透明感就是这么来的（r31~r34 连续四轮）。
            //   顺序换过来：先问「这是不是面板窗口里的表面」，再问「是不是满屏」。
            // ★★★ v0.1.31 用户第 1 / 2 / 3 条（第四次重写，这次不再看窗口、不再看比例）：
            //   面板 / 信息卡片一律按<b>这块底自己长什么样</b>认。理由很硬 ——
            //   同一块屏幕上的「作品详情页」「播放列表详情」「底部面板」全都跑在
            //   各自的独立 Dialog 窗口里（react-native-screens 的 RNSModalScreen、
            //   @gorhom 的 BottomSheetModal），窗口高度 / 屏幕比例<b>根本分不开它们</b>：
            //       · 概述面板  ReactScrollView 1272x2221 top=343（窗口 2564）
            //       · 作品详情    ScreenContentWrapper 1272x2564（窗口 2564，一模一样）
            //   v0.1.27 猜「20%~85% 屏高」、v0.1.28 猜「85% 窗口高」、v0.1.29 猜
            //   「窗口比屏幕矮」、v0.1.30 猜「windowToken」—— 四次都两头错。
            //   真正的差别在<b>画面</b>上：信息卡片是「有圆角 / 半透明的块」，
            //   页面容器是「不透明、没圆角、铺满整页」。见 {@link #isGlassSurface}。
            if (PanelGlass.paintDrawableGlass(d, c, v, b)) {
                rememberBgOwner(d);
                return true;
            }

            // ★★★ v0.1.41（r12 用户第 8 条）：**压暗遮罩的判据必须挪到这里**。
            //   v0.1.39 把它写在 swapLeaf 里，而 swapLeaf 开头有一条铁律
            //   「kids > 0 的容器永不跳过」—— PurePlay 那层紫遮罩恰恰是
            //   1440x3168、kids>0（r12 日志）⇒ 永远进不到那条分支
            //   （整份日志 `drop dim = 0` 就是这个）。但它的颜色是
            //   BackgroundDrawable 画出来的，一定经过这里。
            // ★★★ v0.1.146：只丢近满屏模态压暗；字幕条 / 设定行保持宿主自绘。
            if (isModalDimSurface(v, d) && d.getOpacity() != PixelFormat.OPAQUE) {
                sScrim++;
                sPainted = true;
                BgDiag.hit("drop dim bg -> ", v, b);
                return true;      // 不画 ⇒ 直接透出下面已经铺好的壁纸
            }

            if (coversScreen(v)) {
                Bitmap bmp = PlayPage.pageBmp(v);
                if (bmp == null) return false;
                // 半透明的一律放过：弹窗遮罩（overlay/modal1 = #19122ccc）不能被顶掉
                if (requireOpaque && d.getOpacity() != PixelFormat.OPAQUE) return false;
                // ★★★ v0.1.42（r13 用户第 8 条）：浮层里的「满屏底」一律不画。
                //   它一定在独立 Dialog 窗口里（窗口透明），铺上去只会盖住主窗口的
                //   App 界面 —— 而壁纸本来就在主窗口最底下，不画等于透出来。
                // ★★★ v0.1.44：**但这块自己就是玻璃面板时例外**。
                //   面板动画落位瞬间判定可能从「面板」翻成「满屏底」；
                //   若这里把它的底删了，面板就整块透明（r15 第 2 条）。
                //   上一帧已经给它铺过玻璃（{@link #GLASSED} 有账）⇒ 保留玻璃，
                //   宁可多铺一层也不能留洞。
                // ★★★ v0.1.71（r73）：**播放页这个满屏浮层不能「丢掉不画」**。
                //   r73 现场：播放页根 `/3` 的底就是在这里被丢的（`P=drop screen float`），
                //   全靠它下面那两张底图（1272x2772 / 1272x2913）撑着不透明。
                //   而「迷你条往返 → 再展开」这条路上，那两张图会经历一次卸载/重建，
                //   中间任何一帧没画出来，整页就直接透出后面那一页 —— 这正是用户反复
                //   说的「播放页背景依旧透明」。这里改成<b>自己铺一张播放档壁纸</b>：
                //   位图和下面两张底图完全同源同位置，肉眼无差，但从此不会再留洞。
                // ★★★ v0.1.72（r74 第 3 条）：**判据补一条「刚铺过播放档底图」。**
                //   r73 版只认 `playContext || isPlayWindow`，而这两条在
                //   「收成迷你条 → 再展开」的那几帧里会**同时失效**：
                //   迷你条状态下进度条被换成 1~2dp 的细线（{@link #notePlaySeek} 明确排掉），
                //   播放页那一帧又刚好没重绘 ⇒ 这一次 `drop screen float` 照旧执行
                //   ⇒ 满屏底又被丢掉 ⇒ 用户说「还是透明」。
                //   现在追加「3 秒内刚铺过播放档底图」（{@link #sPlayPaintedAt}，
                //   由每个播放页底图每帧刷新）作为短时信号；它只补洞、不换档：
                //   刚离开播放页的窗口用<b>清晰壁纸</b>，绝不会把列表页糊掉。
                if (inFloatingLayer(v) && !GLASSED.containsKey(v)) {
                    // ★★★ v0.1.75（r78 第 6 条：「信息卡弹出后，背后宿主页面不显示」）：
                    //   **判据收紧到「这一层自己就是播放页」，并彻底删掉两个时间窗口。**
                    //
                    //   上一版这里挂着 `PlayPage.playContext(v) || … || sPlayOnScreenAt < 800ms`
                    //   和另一条 10 秒窗口。前者是「整个窗口里有一条活着的进度条」——
                    //   迷你播放条存在时它对<b>任何页面</b>都成立；后者更宽。
                    //   于是「信息卡（独立 Dialog）+ 满屏底」也命中 ⇒ 我们在那个
                    //   <b>透明 Dialog 窗口</b>里铺了一张不透明壁纸 ⇒ 把主窗口的
                    //   宿主页面整个盖掉（r13 用户第 8 条那一幕）。
                    //
                    //   现在只认对象级事实：这一层被 `paint play…` 画过，或它就在
                    //   播放页根底板那棵子树里，或是播放窗口本身。信息卡三条都不满足 ⇒ drop。
                    // ★★★ v0.1.77（r80 第 2 条的真凶，probe 实证）：
                    //   `P=drop screen float` 一直挂在播放页根 `/3` 上 ——
                    //   因为播放页<b>也跑在 Dialog 窗口里</b>（react-native-screens 的 modal），
                    //   而我把 `playRecent` 的「Dialog 一律 drop」写成了硬规则 ⇒ 误伤播放页。
                    //   现在补一条**结构判据**：这一层的子树里装着那条活着的进度条 ⇒ 它就是播放页根。
                    //   信息卡里不可能有播放进度条 ⇒ 依然 drop，第 6/7 条不会被回退。
                    // ★★★ v0.1.78（r81 第 2 条：「退后台再回来 → 再展开 → 持续透明」）：
                    //   加了「后台往返」之后，`sPlaySeek` 指向的旧进度条 detach ⇒
                    //   `containsPlaySeek` 也失效 ⇒ 四条判据全灭 ⇒ 一直 drop。
                    //   补一条**纯结构**判据：这一层的直接子树里有没有「满宽 ≥95% +
                    //   高 ≥90% 屏」的图片层 —— 播放页一定有两张满屏氛围底图，
                    //   信息卡 / 普通 Dialog 的满屏底是 ViewGroup 而不是 ImageView，
                    //   所以不会误伤第 6/7 条。
                    // ★★★ v0.1.79（r82 架构重做）：**播放页只留一个绘制来源 = 对象级底板。**
                    //   十几轮的结论：在 View#draw 里逐层画壁纸，永远有两个死结 ——
                    //   ① 判据（活体进度条）在「退后台 / 树重建」时失效；
                    //   ② 这笔绘制录在各自 RenderNode 里，宿主不重绘那一层就永远停在
                    //      旧结果上（= 用户看到的透明 / 色块）。
                    //   现在改成：播放页根挂一个 CoverDrawable（对象级，跟着 View 走），
                    //   页面内所有满屏层一律 drop，canvas 这条路在播放页里彻底不画。
                    //   ⇒ 只留一个来源：没有两套对齐基准（结构上不会有色块/位移）；
                    //   ⇒ background 是对象状态：RenderNode 新建还是复用都会画（结构上不会透明）。
                    if (PlayPage.isPlayRoot(v)) {
                        Bitmap pb = PlayPage.playBmp();
                        if (pb != null) {
                            PlayPage.ensurePlayRootBg(v, pb);
                            if (ROOT_BG.get(v) != pb) {
                                // 底板还没挂上（异步 post 的那一两帧）：自己画一次当过渡。
                                // 静态帧下 coverRect 与 CoverDrawable 的居中裁剪逐像素等价。
                                // ★★★ v0.1.81（r84 第 1 条：「上半有色块、下半完全透明」）：
                                //   **裁剪必须用「本层整块」，不能用 drawable 的 bounds。**
                                //   播放页根这块 {@code CompositeBackgroundDrawable} 的 bounds
                                //   只到「音轨标题栏上方」（用户截图红框就是这个范围），
                                //   照着它裁 ⇒ 下半部分一像素都没铺 ⇒ 透出音轨列表。
                                //   整层裁剪只影响这一层（它现在只有一个绘制来源），
                                //   而顶部的压暗渐变是它的<b>子层</b>，画在它上面，不会被盖。
                                Rect full0 = CLIP0.get();
                                full0.set(0, 0, v.getWidth(), v.getHeight());
                                if (full0.isEmpty()) full0.set(0, 0, sSW, sSH);
                                if (PanelGlass.paintAligned(c, pb, v, full0, false)) {
                                    sPainted = true;
                                    BgDiag.hit("paint play screen bg -> ", v, full0);
                                    return true;
                                }
                            }
                            sPainted = true;
                            BgDiag.hit("drop play root bg -> ", v, b);
                            return true;
                        }
                    }
                    sScrim++;
                    sPainted = true;
                    BgDiag.hit("drop screen float -> ", v, b);
                    return true;
                }
                if (!PanelGlass.paintAligned(c, bmp, v, b, true)) return false;
                sSkipped++;
                sPainted = true;
                return true;
            }

            // ★ 全宽的渐变 drawable（RN backgroundImage: linear-gradient）
            //   ★★ v0.1.20：按透明度分开处理 —— 「替不透明像素是等价操作，涂半透明是凭空加遮挡」。
            if (!requireOpaque && isFullWidth(v)) {
                // ★★★ v0.1.74（r88 第 1 条的真凶）：**播放页子树里的满宽渐变一律放行。**
                //   现场（r88 用户录屏 f040，逐块拉普拉斯方差实测）：
                //   播放页刚打开的头几百毫秒，作品封面那块 1272×954 的位置上是一块
                //   <b>清晰壁纸</b>（lapvar 1066，而 1 秒后同一位置的糊封面只有 4.7）。
                //   它就是从这一支画出去的：封面的容器是一个「满宽 + 不透明渐变底」的层，
                //   而头几帧播放页那个 Dialog 窗口还没被 `inFloatingLayer` 认出来
                //   ⇒ 掉进下面这条 `paint grad bg`（用的是<b>清晰壁纸</b> sWpBmp）
                //   ⇒ 封面位置出现一块清晰色块，1~2 秒后窗口认出来了、宿主又把封面画上，
                //   色块自己消失 —— 与用户描述「上下铺满、只有封面上方闪一块、1~2 秒恢复」一致。
                //   放行的代价是零：这一层是内容容器的底，宿主本来就会画它自己的底色。
                if (PlayPage.inPlayTree(v)) return false;
                if (d.getOpacity() == PixelFormat.OPAQUE) {
                    Bitmap bmp = PlayPage.pageBmp(v);
                    if (bmp == null) return false;
                    // ★★★ v0.1.42（r13 用户第 8 条）：浮层里的满宽渐变同样不画。
                    if (inFloatingLayer(v)) {
                        sScrim++;
                        BgDiag.hit("drop grad float -> ", v, b);
                        return true;
                    }
                    if (!PanelGlass.paintAligned(c, bmp, v, b, false)) return false;
                    sGrad++;
                    sPainted = true;
                    BgDiag.hit("paint grad bg -> ", v, b);
                    return true;
                }
                sScrim++;
                BgDiag.hit("drop grad bg -> ", v, b);
                return true;
            }

            // ★ 顶栏 / 底栏的背景 drawable：换成同一块区域的模糊壁纸 ⇒ 毛玻璃。
            //   这里是**背景**，子 View 画在它之后 ⇒ 无论原来透不透明都不会遮住内容。
            if (isBarLike(v)) {
                int[] loc = LOC.get();
                v.getLocationInWindow(loc);
                // ★★ v0.1.20：顶部区的半透明底不进模糊分支。
                //   r25 实证：播放列表的 tab 栏（ReactViewGroup 1272x167 top=141）原先被涂成
                //   模糊壁纸，用户明确要求「直接透出显示背景」⇒ 背景层不画即可（子 View 照画）。
                // ★★★ v0.1.43（r14 用户第 5 条「上方黑条」）：**顶部区改成铺同款壁纸**。
                //   v0.1.42 一律 drop，切后台回来时底下那层还没画到 ⇒ 状态栏区域留洞
                //   ⇒ 黑条。铺「和页面同一张」的清晰/播放档壁纸 = 视觉上仍是透出背景，
                //   但不会再出现空洞；也绝不用 bar 档模糊图（那才会变成「强调色条」）。
                if (loc[1] <= sEdgeTop) {
                    // ★★★ v0.1.151：半透明顶 scrim 也铺壁纸（挡住上划穿透）；
                    //   搜索按钮 elevateLibraryChrome 抬到遮罩之上。
                    if (paintTopMask(v, c, b)) {
                        sBar++;
                        BgDiag.hit("paint top fill -> ", v, b);
                        return true;
                    }
                    return false;
                }
                // v0.1.26 / v0.1.150：底栏一律走 paintBottomMask（与 lightNav 同源）。
                if (paintBottomMask(v, c, b)) {
                    sBar++;
                    BgDiag.hit("paint bar bg -> ", v, b);
                    return true;
                }
                return false;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★★★ v0.1.30：这一层是不是坐在<b>主界面窗口</b>里。
     *
     * <p>为什么不能只比「root == Activity 的 DecorView」：r35 日志里
     * 「在窗口根上铺了一层玻璃 1272x2564」在<b>主界面</b>上连着出现 7 次 ——
     * 说明主界面的内容根被认成了「别的窗口」（root 对象和 {@code decorOf(sPoll)}
     * 拿到的 DecorView 不是同一个，但其实是同一个 Window）。于是主界面的块
     * 全被当成面板铺了一层模糊壁纸。
     *
     * <p>现在补一条 windowToken 兜底：<b>对象不同但 token 相同 = 同一个窗口</b>。
     * 只有在「对象和 token 都对不上」时才算独立窗口（Dialog / Popup）。
     */
    static boolean inMainWindow(View v) {
        try {
            View root = v.getRootView();
            if (root == null) return true;                  // 拿不到 ⇒ 保守当主窗口（画清晰壁纸）
            View ad = decorOf(sPoll);
            if (ad == null) return true;
            if (root == ad) return true;
            Object a = root.getWindowToken();
            Object b = ad.getWindowToken();
            return a != null && b != null && a == b;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 只在「播放页窗口」上铺过一次的位图（弱引用，不拖住窗口）。
     */
    static final java.util.Map<View, Bitmap> PLAY_BG =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Bitmap>());


    /** 播放页窗口（root → 最近一次见到播放档模糊的时间戳）。 */
    static final java.util.Map<View, Long> PLAY_WIN =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Long>());

    // ★★★ v0.1.60（r61 用户第 3 条：媒体库的封面变成了「壁纸的模糊色块」）：
    //   120 秒 → **2.5 秒**。
    //
    //   `PlayPage.isPlayWindow()` 一旦为真，就会把「播放档壁纸（壁纸+模糊）」铺到
    //   大块图片层上（`paintBlurBg` 的接管分支 / TextureView 分支都用它）。
    //   从播放页退回媒体库之后，这面旗还挂着 120 秒 ⇒ 媒体库里那些
    //   **防偷窥模糊封面**被换成了一小块播放档壁纸 —— 正是用户截图里
    //   「封面变成高斯模糊的壁纸色块」。
    //   播放页每帧都在 `markPlayWindow` 续期，所以 2.5 秒足够覆盖真正的播放页，
    //   页面一离开就会自然过期，不会再污染其它页面。
    static final long PLAY_WIN_TTL_MS = 2500L;

    /**
     * ★★★ v0.1.80（r104 用户第 2 / 4 条）：**「这是播放页窗口」必须带上「哪一页」。**
     *
     * <p>r61 起 {@code isPlayWindow} 只看「窗口根 + 2.5 秒」—— 而这个宿主的
     * 主界面、媒体库、作品夹、播放页<b>全都在同一个窗口根里</b>（react-native-screens
     * 的页面是同一个 DecorView 下的兄弟 View）。于是：
     * <pre>
     * 离开播放页后的 2.5 秒里，主界面的每一层都会被判成「播放页」
     *   ⇒ 铺成播放档（壁纸 + f2PlayBlur 的模糊）= 用户第 4 条「主页叠加播放页的模糊效果」；
     * 反过来进播放页时如果标记是从别的页续上的
     *   ⇒ 播放页的整屏底图被当「非播放页」放回宿主 = 用户第 2 条「背景是模糊版作品封面」。
     * </pre>
     * 现在标记里同时记下「这一页的 Screen 容器」：只有同一页（或根本不在任何 Screen 里，
     * 即那个宿主的根级播放层）才算播放页窗口。
     */
    static volatile java.lang.ref.WeakReference<View> sPlayWinPage;

    // ══════════════════════════════════════════════════════════════════
    // ★★★ v0.1.88（r112 用户第 2 条）：作品封面屏蔽
    // ══════════════════════════════════════════════════════════════════

    /**
     * 封面遮罩的不透明度。26/255 = 0x1A，和「类型」底框、播放页开始/暂停按钮同一档淡色。
     * 只铺这一层颜色，不在封面槽里再画壁纸（壁纸会跟着屏幕钉死，一滑就「卡住」）。
     */
    static final int COVER_VEIL_ALPHA = 26;

    /** 最近见到的主题色 RGB（不含 alpha）。 */
    static int sCoverThemeRgb;

    /** 首页回顾卡片用的短文案（只在卡片级容器里匹配；多语言）。 */
    static final String[] REVIEW_CARD_CUES = {
            // zh-CN
            "你听了", "你喜欢的类型",
            // zh-TW
            "你聽了", "你喜歡的類型",
            // ja
            "聴いた時間", "好きなジャンル",
            // en
            "You listened", "genres you like", "Favorite genres",
            // ko
            "들은 시간", "좋아하는 장르",
    };

    /**
     * 月度回顾整页文案（多语言）。
     * <p>绝不能含首页回顾卡片里的「你听了 / 喜欢的类型」这类短句，
     * 否则首页卡片会误触发整页壁纸替换。
     * <p>日语宿主把该功能叫「プレイバック」（见 playback.dlsound.jp）。
     */
    static final String[] MONTHLY_REVIEW_CUES = {
            // zh-CN
            "月度回顾", "分享月度", "正在分析播放",
            "最常听的作品", "根据播放记录",
            "播放时长",   // 首屏「10月的播放时长」—— CD / 推荐页文案还没挂时靠它
            // zh-TW
            "月度回顧", "分享月度", "正在分析播放",
            "最常聽的作品", "根據播放記錄", "播放時長",
            // ja（プレイバック）
            "プレイバック", "よく聴いた作品", "よく聴いた声優",
            "再生時間", "再生を分析", "プレイバックをシェア",
            // en
            "Monthly Playback", "Your Playback", "Listening time",
            "Most listened", "Top works", "Analyzing your",
            "Share your playback",
            // ko
            "월간 리뷰", "플레이백", "재생 시간",
            "가장 많이 들은", "재생을 분석",
    };

    /**
     * 只在「回顾卡片」或「月度回顾页 / Dialog」里放过。
     * 上一版对每个祖先都扫子树，首页 ScrollView 里有「回顾」标题后，
     * 立刻收听的封面也被当成回顾 ⇒ 一切页回来就再也不遮了。
     */
    /** 同帧内回顾判定缓存，底栏切换时避免每层 View 都扫子树。 */
    static long sReviewCacheFrame;
    static final android.util.SparseBooleanArray REVIEW_HIT =
            new android.util.SparseBooleanArray(48);
    static final android.util.SparseBooleanArray REVIEW_MISS =
            new android.util.SparseBooleanArray(48);

    /** 同帧按窗口根缓存：月度回顾里大 CD 离文案很远，祖先短扫会漏。 */
    static long sRootCueFrame;
    static int sRootCueId;
    static boolean sRootCueHit;

    /** 「没看过 / 同步已购买」一类文字按钮底板，绝不能当封面槽挖空。 */
    static final String[] BUTTON_CUES = {
            "没看过", "同步已购买", "导入", "创建播放列表", "看过",
    };

    /**
     * ★★★ v0.1.31 用户第 4 条：<b>只给播放页窗口</b>铺一层「壁纸 + 模糊」兜底。
     *
     * <p>播放页是淡入 + 局部重录最密集的页面：第一帧 / 从后台回前台那一下，
     * 宿主一个像素都还没画，我们注入在子层里的图也就还没被录进去 ⇒ 整屏一个洞
     * （「启动宿主后第一次播放一定透明」）。在窗口根上钉一张底，最坏也只是
     * 「一张（糊的）壁纸」，绝不会透底。
     *
     * <p>⚠️ v0.1.30 用 {@code BitmapDrawable} + CLAMP：它是按<b>固有尺寸拉伸</b>的，
     * 882×1920 的壁纸铺到 1272×2772 的根上 ⇒ 用户看到的「图片缩放」。
     * 现在换成 {@link io.github.ariinyume.morimod.view.CoverDrawable}（等比 center-crop），
     * 与页面里那些层的算法完全一致，不会再错位。
     *
     * <p>⚠️ 除播放页之外<b>任何窗口都不碰</b> —— 在别人的窗口根上换背景正是
     * 「主界面莫名模糊」的来源。
     */
    // ★★★ v0.1.38：ensurePlayWindowBg 已彻底删除。
    //   它在播放页把<b>窗口根</b>换成模糊壁纸、离开时没人还原 ⇒ r39 / r40 两次
    //   「整个主界面跟着一起糊」。现在播放页的底只画在页面底图那一层上。

    /**
     * 这条底栏是不是「紧贴页面底边」的那一条（±2% 屏高）。
     *
     * <p>是 ⇒ 它应该接着画页面那张图，而不是换一张模糊度不同的毛玻璃
     * （否则交界处一条硬边，看起来就像「底下透出半截导航栏」）。
     */
    static boolean barTop(int top) {
        if (sPageBottom <= 0 || sPageBmp == null) return false;
        int tol = Math.max(8, (int) (sSH * 0.02f));
        return Math.abs(top - sPageBottom) <= tol;
    }

    /**
     * ★★★ v0.1.98：<b>本层所在窗口的宽高</b>（当场取，不用 {@link #sSW}/{@link #sSH} 缓存）。
     *
     * <h3>为什么必须当场取</h3>
     * 浮窗 / 分屏 / 自由窗口（Android 自由窗口、ROM 小窗）下，窗口尺寸会在<b>任意时刻</b>变，
     * 而 {@link #apply} 的度量刷新只有 1.5 秒一轮轮询。以前每一层都按缓存的
     * {@code sSW×sSH} 算落点 ⇒ 窗口刚缩小的那几帧里，先重绘的层用新尺寸、
     * 还没重绘的层用旧尺寸 ⇒ 同一屏出现两块缩放/偏移都不同的壁纸 = 用户说的「割裂」。
     * 现在每层都读自己窗口的实时尺寸，任何一帧各层都对得上。
     *
     * <p>拿不到尺寸（未 attach）时回落到缓存值，绝不因此不画（不画就是透明 / 黑块）。
     *
     * @return false = 连缓存值都没有，调用方应当放弃本帧
     */
    static boolean rootGeometry(View v, int[] out) {
        int w = 0;
        int h = 0;
        try {
            View r = v == null ? null : v.getRootView();
            if (r != null) {
                w = r.getWidth();
                h = r.getHeight();
            }
        } catch (Throwable ignored) {
        }
        if (w <= 0 || h <= 0) {
            w = sSW;
            h = sSH;
        }
        if (w <= 0 || h <= 0) return false;
        out[0] = w;
        out[1] = h;
        return true;
    }

    /**
     * ★★★ v0.1.140 / v0.1.141：本层在窗口内的落点。
     *
     * <p>主窗口：优先 {@code getLocationInWindow}，飞出根外时回落布局累加。
     *
     * <p>★★★ v0.1.141：Dialog / 底部面板<b>一律用布局坐标</b>。
     * r181443 概述面板 {@code top=238} 却反复 {@code scr=0,492/575/2772} ——
     * 取样 Y 比真画布大一截时，{@code paintAligned} 的位图只盖住上半，
     * 底边一整条采空 ⇒ 透出主窗清晰壁纸 + 导航栏 =「下方空白」。
     * 布局 top 与 {@link #topOf} 同源，滑入前后稳定。
     */
    static boolean windowLoc(View v, int[] out) {
        if (v == null || out == null || out.length < 2) return false;
        try {
            if (inDialogWindow(v) || insideBottomSheet(v)) {
                return layoutLocInWindow(v, out);
            }
        } catch (Throwable ignored) {
        }
        int[] geo = RGEO.get();
        boolean geoOk = rootGeometry(v, geo);
        try {
            v.getLocationInWindow(out);
            if (geoOk && locSaneInRoot(out[0], out[1], v.getWidth(), v.getHeight(),
                    geo[0], geo[1])) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return layoutLocInWindow(v, out);
    }

    /** 与根有足够重叠，且坐标没有离谱到数屏之外。 */
    static boolean locSaneInRoot(int x, int y, int w, int h, int rw, int rh) {
        if (rw <= 0 || rh <= 0) return false;
        if (w <= 0) w = 1;
        if (h <= 0) h = 1;
        if (x + w < -8 || y + h < -8 || x > rw + 8 || y > rh + 8) return false;
        // 至少一半面积落在根内，挡住 y=2772 这种「只咬底边 1px」的假 sane
        int ovW = Math.min(x + w, rw) - Math.max(x, 0);
        int ovH = Math.min(y + h, rh) - Math.max(y, 0);
        if (ovW <= 0 || ovH <= 0) return false;
        if (ovH * 2 < Math.min(h, rh) || ovW * 2 < Math.min(w, rw)) return false;
        return Math.abs(x) <= rw * 3L && Math.abs(y) <= rh * 3L;
    }

    /** 沿父链累加 getLeft/getTop（减 scroll），得到布局意义上的窗口坐标。 */
    static boolean layoutLocInWindow(View v, int[] out) {
        if (v == null || out == null || out.length < 2) return false;
        int x = 0;
        int y = 0;
        View cur = v;
        View root;
        try {
            root = v.getRootView();
        } catch (Throwable t) {
            root = null;
        }
        try {
            for (int i = 0; i < 32 && cur != null; i++) {
                if (cur == root) break;
                x += cur.getLeft();
                y += cur.getTop();
                Object p = cur.getParent();
                if (!(p instanceof View)) break;
                View pv = (View) p;
                x -= pv.getScrollX();
                y -= pv.getScrollY();
                cur = pv;
            }
            out[0] = x;
            out[1] = y;
            return true;
        } catch (Throwable t) {
            out[0] = 0;
            out[1] = 0;
            return false;
        }
    }

    /** 条带压暗：在同一个屏幕矩形上叠一层 10% 黑，保证图标文字可读。 */
    static void drawMask(Canvas c, View v, Rect clip) {
        if (MASK_PAINT.getAlpha() <= 0) return;
        int[] loc = LOC.get();
        int[] geo = RGEO.get();
        try {
            // ★★★ v0.1.98：跟着 wallpaper 一起改成窗口坐标 + 本窗口尺寸，
            //   否则浮窗里「壁纸在窗口坐标、压暗层在屏幕坐标」⇒ 压暗块整体错位。
            v.getLocationInWindow(loc);
            if (!rootGeometry(v, geo)) return;
        } catch (Throwable t) {
            return;
        }
        RectF dst = DST.get();
        dst.set(-loc[0], -loc[1], -loc[0] + geo[0], -loc[1] + geo[1]);
        int save = c.save();
        try {
            c.clipRect(clip);
            c.drawRect(dst, MASK_PAINT);
        } catch (Throwable ignored) {
        } finally {
            c.restoreToCount(save);
        }
    }

    /**
     * ★★★ v0.1.72（r74）：本层画布相对<b>屏幕</b>的累计缩放（含自身与祖先）。
     *
     * <p>为什么要它：{@link #paintAligned} 用 {@code getLocationOnScreen()} 换算落点，
     * 而那个 API 返回的是「本层原点在屏幕上的<b>变换后</b>位置」，
     * 画布本身又已经被父层的 scale 缩过 —— 两者不配套就会错位。
     * 关系是：{@code screen = loc + scale · local}。
     *
     * <p>RN 的转场（播放页展开 / 收成迷你条）正是整棵子树 scale，
     * 于是 r74 用户在转场那一瞬看到的「封面上下的错位硬边」就是这里没除 scale。
     * 异常 / 怪异值一律按 1 处理 ⇒ 退化成旧行为，不会比现在更差。
     */
    static float canvasScale(View v) {
        // ★★★ v0.1.74（r77「上下滑动超级卡顿」的正解）：**稳态零开销。**
        //   这个方法在每次 {@code paintAligned} 里都跑，列表页滚动一帧几百层；
        //   哪怕只数 6 层祖先，也是「几百 × 十几」的 getter，白白吃掉渲染预算
        //   （实测 50th 21ms —— 每帧都超 16.6ms 预算）。
        //   而稳态下<b>根本没有 scale</b>：转场才是唯一的来源，且转场由心跳
        //   （150ms 一拍）去探测并置 {@link #sTransitAt}。
        //   所以这里只看一个时间戳：不在转场窗口内就直接返回 1，不做任何遍历。
        if (System.currentTimeMillis() - sTransitAt > 600L) return 1f;
        float ts = treeScale(v);
        // ★★★ 只补偿「真正的转场量级」（0.55 ~ 1.9 倍）。
        //   为什么必须夹：{@link #treeScale} 是把 16 层祖先的 scaleX 乘起来，
        //   只要链上任何一层残留了迷你条收缩动画那种 0.08 之类的值，
        //   乘出来的就是离谱值 —— 按它去「除」，壁纸会被缩成左上角一小块，
        //   整层看起来又是「透明」。宁可错位几像素，也绝不能把整层画飞。
        if (Float.isNaN(ts) || ts < 0.55f || ts > 1.9f) {
            if (ts != 1f && sScaleLogs < 6) {
                sScaleLogs++;
                log("scale skip " + ts + " on " + name(v));
            }
            return 1f;
        }
        return ts;
    }

    /** 「缩放补偿被跳过」的日志条数（诊断用，限 6 条）。 */
    static volatile int sScaleLogs;

    /** 这块 View 是不是「满宽」。 */
    static boolean isFullWidth(View v) {
        try {
            return v.getWidth() >= sFullW;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 这块 View 是不是「贴顶 / 贴底」的满宽条（顶栏、迷你播放控件、底部标签栏、顶部 scrim）。
     *
     * <p>这里<b>不判背景透明度</b> —— 透明度由调用方按「能不能安全涂」分别处理：
     * 背景层可以随便涂（子 View 在它之后画），整层跳过就必须先确认它是不透明的。
     */
    /** 已抬升过的搜索钮（弱引用，防泄漏）。 */
    static final java.util.Map<View, Boolean> CHROME_UP =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 媒体库搜索钮（弱引用）：顶栏遮罩挖洞用。 */
    static volatile java.lang.ref.WeakReference<View> sSearchChrome;

    /**
     * ★★★ v0.1.153：认搜索钮并记账。
     * <p>RN 合成顺序下 elevation / bringToFront 挡不住
     * {@code LinearGradientView 1272x201} 的实心顶遮罩（0.1.151/152 真机仍半截）。
     * 真正管用的是 {@link #paintTopMask} 里按搜索钮 bounds {@code clipOut}。
     */
    static void elevateLibraryChrome(View v) {
        if (v == null || !sArmed) return;
        Boolean known = CHROME_UP.get(v);
        if (Boolean.FALSE.equals(known)) return;
        if (known == null) {
            boolean hit = isLibrarySearchChrome(v);
            CHROME_UP.put(v, hit ? Boolean.TRUE : Boolean.FALSE);
            if (!hit) return;
        }
        try {
            View prev = sSearchChrome == null ? null : sSearchChrome.get();
            // 优先记带文案的 Button（比内部 Svg 大一圈，挖洞更稳）
            if (prev == null || matchSearchIdentity(v)
                    || v.getWidth() * v.getHeight() >= prev.getWidth() * prev.getHeight()) {
                sSearchChrome = new java.lang.ref.WeakReference<>(v);
            }
        } catch (Throwable ignored) {
            sSearchChrome = new java.lang.ref.WeakReference<>(v);
        }
        try {
            if (v.getElevation() < 24f) v.setElevation(24f);
            if (v.getTranslationZ() < 24f) v.setTranslationZ(24f);
            v.bringToFront();
            Object p = v.getParent();
            for (int i = 0; i < 5 && p instanceof ViewGroup; i++) {
                ViewGroup vg = (ViewGroup) p;
                try {
                    if (vg.getClipChildren()) vg.setClipChildren(false);
                    if (vg.getClipToPadding()) vg.setClipToPadding(false);
                    vg.bringChildToFront(v);
                } catch (Throwable ignored) {
                }
                p = vg.getParent();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 媒体库右上角放大镜：RN testID / 文案 / 资源名；Svg 子层认直接父钮。 */
    static boolean isLibrarySearchChrome(View v) {
        if (v == null) return false;
        if (matchSearchIdentity(v)) return true;
        // Svg 放大镜本身无文案：只向上看一层 Button
        try {
            Object p = v.getParent();
            if (p instanceof View && matchSearchIdentity((View) p)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean matchSearchIdentity(View v) {
        if (v == null) return false;
        try {
            CharSequence desc = v.getContentDescription();
            if (desc != null) {
                String s = desc.toString();
                // uidump：content-desc="搜索作品、社团、声优名"
                if (s.contains("搜索") || s.toLowerCase(java.util.Locale.ROOT).contains("search")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Object tag = v.getTag();
            if (tag != null) {
                String s = String.valueOf(tag);
                if (s.contains("library-search") || s.contains("search-button")
                        || s.contains("search_button")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                String name = v.getResources().getResourceEntryName(id);
                if (name != null && (name.contains("search-button")
                        || name.contains("search_button")
                        || name.contains("library-search"))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 顶栏 scrim 画之前：在近邻里找到搜索钮（渐变常比 Button 先 onDraw）。 */
    static void discoverSearchNear(View host) {
        try {
            java.lang.ref.WeakReference<View> wr = sSearchChrome;
            View cur = wr == null ? null : wr.get();
            if (cur != null && cur.isShown()) return;
            Object p = host == null ? null : host.getParent();
            for (int up = 0; up < 4 && p instanceof ViewGroup; up++) {
                ViewGroup vg = (ViewGroup) p;
                int n = vg.getChildCount();
                for (int i = 0; i < n; i++) {
                    View ch = vg.getChildAt(i);
                    if (ch != null && matchSearchIdentity(ch)) {
                        sSearchChrome = new java.lang.ref.WeakReference<>(ch);
                        return;
                    }
                }
                p = vg.getParent();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 顶栏遮罩画布上挖掉搜索钮区域（本地坐标，略放大）。
     * 遮罩是实心壁纸时，洞里透出下面的搜索钮（它在 Z 序上常被 scrim 压住）。
     */
    static void clipOutSearchChrome(Canvas c, View host) {
        if (c == null || host == null) return;
        discoverSearchNear(host);
        View search = null;
        try {
            java.lang.ref.WeakReference<View> wr = sSearchChrome;
            search = wr == null ? null : wr.get();
        } catch (Throwable ignored) {
        }
        if (search == null || !search.isShown()) return;
        try {
            int[] sl = new int[2];
            int[] hl = LOC.get();
            search.getLocationInWindow(sl);
            host.getLocationInWindow(hl);
            int pad = 6;
            int l = sl[0] - hl[0] - pad;
            int t = sl[1] - hl[1] - pad;
            int r = l + search.getWidth() + pad * 2;
            int b = t + search.getHeight() + pad * 2;
            if (r <= l || b <= t) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                c.clipOutRect(l, t, r, b);
            } else {
                c.clipRect(l, t, r, b, android.graphics.Region.Op.DIFFERENCE);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * ★★★ v0.1.153：顶栏遮罩铺与页面一致的壁纸，并挖掉搜索钮区域。
     * 替代宿主纯色 / 强调色顶条，同时挡住上划内容穿透；搜索半截靠 clipOut。
     */
    static boolean paintTopMask(View v, Canvas c, Rect r) {
        if (v == null || c == null || r == null || r.isEmpty()) return false;
        Bitmap top = PlayPage.playContext(v) ? PlayPage.playBmp() : sWpBmp;
        if (top == null || top.isRecycled()) top = sWpBmp;
        if (top == null || top.isRecycled()) return false;
        int save = c.save();
        try {
            clipOutSearchChrome(c, v);
            if (!PanelGlass.paintAligned(c, top, v, r, false)) return false;
            sPainted = true;
            return true;
        } finally {
            try {
                c.restoreToCount(save);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * ★★★ v0.1.150：底栏遮罩铺毛玻璃 / 页面同款壁纸，替代宿主强调色纯色条。
     * lightNav 与稳态 Drawable 路径共用，避免切页瞬间露强调色底栏。
     */
    static boolean paintBottomMask(View v, Canvas c, Rect r) {
        if (v == null || c == null || r == null || r.isEmpty()) return false;
        Bitmap bar = sWpBarBmp != null ? sWpBarBmp : sWpBmp;
        if (bar == null || bar.isRecycled()) return false;
        try {
            int[] loc = LOC.get();
            v.getLocationInWindow(loc);
            if (barTop(loc[1]) && sPageBmp != null && !sPageBmp.isRecycled()
                    && sPageBmp != sWpBmp) {
                bar = sPageBmp;
            }
        } catch (Throwable ignored) {
        }
        if (!PanelGlass.paintAligned(c, bar, v, r, false)) return false;
        drawMask(c, v, r);
        sPainted = true;
        return true;
    }

    static boolean isBarLike(View v) {
        int w = v.getWidth();
        int h = v.getHeight();
        if (w < sFullW || h <= 0 || h > sBarMaxH) return false;
        int[] loc = LOC.get();
        v.getLocationInWindow(loc);
        int top = loc[1];
        return top <= sEdgeTop || top + h >= sSH - sEdgeBottom;
    }

    /**
     * ★★ v0.1.21：这块 View 是不是「从底部升起来的面板」（用户第 3 条）。
     *
     * <h3>为什么不能用「满屏」或「条」的判据</h3>
     * 宿主的信息面板（下载进度 / 三点信息 / PurePlay 帮助）都是<b>满宽、贴底、但只有半屏高</b>：
     * <ul>
     *   <li>面积判据（{@code ≥80%}）永远够不到 ⇒ 紫色底原样留着；</li>
     *   <li>条判据卡了 {@code h ≤ 170dp}，半屏高的面板必被排除；</li>
     * </ul>
     * 两条都不命中 ⇒ 这就是「面板紫色底怎么改都不变」的结构性原因。
     *
     * <p>判据只有三条，全部与尺寸无关的硬指标：
     * <ol>
     *   <li>满宽（{@code ≥94%} 屏宽，面板左右不留边）；</li>
     *   <li>底边贴到屏幕底（{@code sEdgeBottom} 以内）；</li>
     *   <li>高度 ≥ {@link #SHEET_MIN_H_RATIO} 屏高（排除逐行的深色底/分隔条，
     *       它们也会满宽贴底，但只有几十像素高）。</li>
     * </ol>
     * ⚠️ 只在「换背景 drawable」和「叶子层」两条路上用：前者子内容画在背景之后、后者根本没有
     * 子内容 ⇒ 换掉绝不丢东西。<b>有子 View 的容器永不整层跳过</b>（v0.1.17 血证）。
     */
    static boolean isSheetLike(View v) {
        if (v == null) return false;
        int w = v.getWidth();
        int h = v.getHeight();
        // ⚠️ v0.1.22 血证：这里**绝不能**再卡 `h <= sBarMaxH`（170dp）——
        //   那句和「高度 ≥ 25% 屏高」互相矛盾，面板永远命中不了（r27 sheet=0 全场）。
        // ★ v0.1.27：宽度 94% → 86%（宿主面板带左右留白），高度上限 80% → 92%。
        // ★★★ v0.1.31：**去掉「必须是主窗口」这条**（宿主面板就跑在独立 Dialog 里），
        //   也**去掉「高度 ≥12% 屏高」以外的比例猜法**：这里只回答一句话 ——
        //   「这块东西是不是从屏幕底部升起来的一大片」。文件列表块那种「自己贴底但
        //   父层顶天立地」的，靠 {@link #paintSheetPanel} 里的父层判据挡掉。
        if (w * 100 < (long) sSW * (long) (SHEET_MIN_W_RATIO * 100)) return false;
        if (h <= 0) return false;
        if (h < (int) (sSH * SHEET_MIN_H_RATIO)) return false;
        if (h > (int) (sSH * SHEET_MAX_H_RATIO)) return false;
        return isBottomAnchored(v);
    }

    /**
     * ★★★ v0.1.97：<b>这一层自己是不是还要画一张图</b>（决定「叶子能不能整层不画」）。
     *
     * <p>见 {@code hookViewDraw} 里 `drop sheet leaf` 那段注释：作品封面那块
     * {@code ExpoImageView 1272x954} 因为「满宽 + 34% 屏高 + 贴底 + 在浮层里 + 无子 View」
     * 被判成面板叶子，整层 drop ⇒ 封面消失。凡是 {@code ImageView} 且手里有 drawable 的，
     * 它画的<b>就是内容</b>，不能省。
     *
     * <p>纯色叶子（面板本体那种 {@code ReactViewGroup} + 不透明 background）不是
     * {@code ImageView} ⇒ 这条闸对它们不生效，原来的面板处理完全不变。
     */
    static boolean paintsOwnImage(View v) {
        try {
            if (!(v instanceof android.widget.ImageView)) return false;
            android.graphics.drawable.Drawable d =
                    ((android.widget.ImageView) v).getDrawable();
            return d != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 这一层是不是「从屏幕底部升起来」的那一大片（v0.1.31 抽出来单独用）。
     *
     * <p>两条：<b>顶边不贴屏幕顶</b>（面板一定是从下面升上来的）、
     * <b>底边贴屏幕底</b>（±3% 屏高，容圆角留白 / 手势条）。
     */
    static boolean isBottomAnchored(View v) {
        if (v == null || sSH <= 0) return false;
        int h;
        int[] loc = LOC.get();
        try {
            h = v.getHeight();
            v.getLocationInWindow(loc);
        } catch (Throwable t) {
            return false;
        }
        if (h <= 0) return false;
        if (loc[1] <= sEdgeTop) return false;
        int tol = Math.max(sEdgeBottom, (int) (sSH * 0.03f));
        return loc[1] + h >= sSH - tol;
    }

    /** 视频/相机这类自己往 Surface 上出画面的，一律不碰（碰了就是黑屏）。 */
    static boolean isVideoSurface(View v) {
        try {
            if (v instanceof android.view.SurfaceView) return true;
            if (v instanceof android.view.TextureView) return true;
            String n = v.getClass().getName();
            return n.contains("Video") || n.contains("Surface");
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 内容层（图片 / 文字 / SVG）：画的是内容，不是底色，永远放行。
     *
     * <p>r23 血证：{@code ExpoImageView 1272x2913}、{@code ReactTextView 1153x3214}
     * 都被当成「满屏背景」整层换掉 ⇒ 一屏内容全没。
     */
    static boolean isImageLayer(View v) {
        try {
            if (v instanceof android.widget.ImageView) return true;
            if (v instanceof TextView) return true;
            String n = v.getClass().getName();
            return n.contains("ImageView") || n.contains("ImageSpanView")
                    || n.contains("TextView") || n.contains("SvgView");
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 从 Drawable 反查它属于哪个 View。
     *
     * <p>背景 drawable 可能被 {@code LayerDrawable} 包了一层，回调链是
     * 子 drawable → LayerDrawable → View，所以要往上走几级。
     */
    static View ownerView(Drawable d) {
        Object cb;
        try {
            cb = d.getCallback();
        } catch (Throwable t) {
            return null;
        }
        for (int i = 0; i < 4 && cb instanceof Drawable; i++) {
            try {
                cb = ((Drawable) cb).getCallback();
            } catch (Throwable t) {
                return null;
            }
        }
        return cb instanceof View ? (View) cb : null;
    }

    /** 这块 View 是否（几乎）铺满窗口。以根视图面积为基准，比屏幕更准。 */
    static boolean coversScreen(View v) {
        try {
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0) return false;
            View root = v.getRootView();
            if (root == null) return false;
            int rw = root.getWidth();
            int rh = root.getHeight();
            if (rw <= 0 || rh <= 0) return false;
            return (long) w * h >= (long) (FULL_SCREEN_RATIO * rw * rh);
        } catch (Throwable t) {
            return false;
        }
    }

    // ── 生命周期 ────────────────────────────────────────────────────

    /** 开始跟踪一个 Activity（onResume 调用）。 */
    public static void schedule(Activity a) {
        if (a == null) return;
        sPoll = a;
        MAIN.post(() -> {
            try {
                apply(a);
            } catch (Throwable ignored) {
            }
        });
        if (!sPolling) {
            sPolling = true;
            MAIN.postDelayed(POLL, POLL_MS);
        }
    }

    /** Activity 离开前台：停轮询，并立刻解除武装（别在别人的界面上继续画）。 */
    public static void onPause(Activity a) {
        if (a != null && a == sPoll) {
            // ★★ v0.1.26 用户第 6 条：以前这里把 sArmed 清成 false ⇒ 退到后台的
            //   那一帧开始所有绘制都放弃，系统抓的应用快照（最近任务 / 转场）里
            //   页面就是**透明的**（用户看到播放页卡片透出桌面）。
            //   现在只停轮询，不改 armed：位图还在，绘制继续，快照才是完整的。
            sPoll = null;
            // ★ r107：r105 在这里的 PlayPage.forgetPlayState() 已撤回。
            //   实测「退后台就清账」会让播放页从后台回来时封面不再显示
            //   （封面那一路判据依赖 PLAY_BG / sPlayWinPage 的记忆），
            //   用户要求先保证播放页正常，这里恢复成只停轮询。
        }
    }

    static final Runnable POLL = new Runnable() {
        @Override
        public void run() {
            boolean again = false;
            try {
                Activity a = sPoll;
                if (a != null && !a.isFinishing()) {
                    apply(a);
                    again = true;
                }
            } catch (Throwable ignored) {
                if (sPoll != null) again = true;
            }
            if (again) {
                MAIN.postDelayed(this, POLL_MS);
            } else {
                sPolling = false;
            }
        }
    };

    /**
     * 重新判一次「现在能不能画」，必要时让视图树重画一帧。
     *
     * <p>⚠️ 必须重画：硬件加速下每个子视图的绘制指令记在自己的 RenderNode 里，
     * 父层 invalidate 不会让子视图重跑 {@code draw} ⇒ 状态翻转了也看不出变化。
     */
    public static void apply(Activity a) {
        if (a == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> {
                try {
                    apply(a);
                } catch (Throwable ignored) {
                }
            });
            return;
        }
        MoriConfig cfg = RemoteConfig.get();
        Bitmap bmp = FeatureF2.wallpaperBitmap(cfg);
        boolean want = FeatureF2.hasWallpaper(cfg);
        // ★★★ v0.1.98：**几何先取**。浮窗缩放 / 全屏↔浮窗 / 旋转 / 分屏都只体现为
        //   「DecorView 尺寸变了」，而各层的壁纸落点是录在 display list 里的常量：
        //   不重新录，尺寸变了画面上还是旧落点 ⇒ 「拉大到全屏依旧移位」。
        //   放在最前面，是为了让下面任何一条分支（含 armed 抖动）都不会漏掉它。
        View decNow = decorOf(a);
        boolean geoChanged = updateMetrics(decNow);
        // ★★ v0.1.25 用户第 4 条「播放页有时候底色透明」：
        //   `wallpaperBitmap` 在「配置刚变 / 解码还没回来」时会返回 null，
        //   以前直接 `ready = want && bmp != null` ⇒ armed 掉成 false ⇒ 所有绘制分支都放弃
        //   ⇒ 宿主原来那层也不再画 ⇒ 那一帧就是「底色透明」。
        //   现在：只要用户仍然开着壁纸，就保住上一张还能用的位图 ——
        //   宁可晚一帧换新图，也绝不留一个洞。
        Bitmap prev = sWpBmp;
        Bitmap use = bmp;
        if (use == null || use.isRecycled()) {
            use = (want && prev != null && !prev.isRecycled()) ? prev : null;
        }
        // ★ 判据是「壁纸真的拿得到」，不是「用户想要」—— v0.1.10 发白就是因为混用了这两者
        boolean ready = want && use != null;
        boolean wasArmed = sArmed;
        // ★★★ v0.1.135：关壁纸时清掉月度回顾标记。
        if (!ready) {
            clearReviewBgMark();
        }
        // ★★★ v0.1.90：**「想要但这一轮拿不到位图」不是「关掉了」。**
        //   见 {@link #sArmDropTicks}：切歌 / 封面解码 / GC 都会让位图短暂不可用，
        //   旧版一抖就整树重绘 ⇒ 每次切歌两帧 200ms+。这里先记账，连续 3 轮
        //   （≈4.5 秒）都拿不到才真的翻。用户主动关壁纸走的是 {@code sDirty}，
        //   不受这道闸影响，仍然是立刻生效。
        if (!ready && sArmed && want) {
            int t = sArmDropTicks + 1;
            sArmDropTicks = t;
            if (t < ARM_DROP_CONFIRM) {
                if (sDbgRedraw < DBG_REDRAW_BUDGET) {
                    sDbgRedraw++;
                    log("arm drop pending " + t + "/" + ARM_DROP_CONFIRM
                            + " （位图瞬时缺失，本轮不重绘）");
                }
                return;      // 保持 sArmed / sWpBmp 原样，这一轮什么都不做
            }
        } else {
            sArmDropTicks = 0;
        }
        // ★ v0.1.90：把「配置真的变了」和「武装状态翻转」分开 —— 两者的代价差 40 倍。
        boolean cfgDirty = sDirty;
        boolean changed = (ready != sArmed) || cfgDirty || (geoChanged && ready);
        sArmed = ready;
        sWpBmp = ready ? use : null;
        sWpBarBmp = ready ? FeatureF2.barBitmap(cfg) : null;
        if (ready && sWpBarBmp == null) sWpBarBmp = sWpBmp;
        sWpPlayBmp = ready ? FeatureF2.playBgBitmap(cfg) : null;
        if (ready && sWpPlayBmp == null) sWpPlayBmp = sWpBarBmp;
        // ★★★ v0.1.39（r44 用户第 1 条）：**不再生成「糊两遍」的玻璃底图**。
        //   面板玻璃直接用 sWpBarBmp（壁纸 + 20% 单次模糊）—— 双次模糊把壁纸糊成
        //   色块，用户的原话是「概述页面的高斯模糊和之前的效果不一样了」。
        //   （v0.1.38 加这张图是为了让面板「看得出是毛玻璃」，但代价是整块失去背景轮廓。）
        // 配置变了 ⇒ 清掉面板磨砂缓存；F2 关时也清（避免旧壁纸磨砂残留）
        if (!ready || cfgDirty) {
            PanelGlass.recycleGlass(sWpGlassBmp);
            sWpGlassBmp = null;
        }
        sDirty = false;
        // ★ v0.1.22 用户第 4 条：强调色跟在「壁纸 + 风格 + 开关」后面。
        //   内部有指纹短路（同一张图同一风格不会重复采样），这里每轮调是安全的。
        //   ★★ v0.1.26：传的是 `use`（稳定位图）而不是 `bmp` —— `bmp` 在解码间隙为 null，
        //   传它会让指纹在 "none" 与真实图之间反复跳，强调色跟着开开关关（还会连带
        //   触发 retintTree 把整棵树重新染色，肉眼看到的就是颜色闪）。
        AccentColor.update(use, cfg);

        // ★★★ v0.1.135：窗口数增加 / 刚武装 / 入窗时未就绪 ⇒ 扫 Dialog。
        if (ready) {
            scanReviewWindowsIfGrew(!wasArmed || sReviewRescan);
        }

        // ★★★ v0.1.98：把这个窗口挂上「尺寸变化」监听 —— 轮询要等 1.5 秒，
        //   而用户缩放浮窗是即时的：靠轮询就会出现「缩完一秒多才归位」。
        watchGeometry(decNow);
        // ★★★ v0.1.76（r79 第 1 条）：**不再在 apply() 里充满重绘额度。**
        //   apply 会在切页面 / 配置变化时触发，额度被反复充满 ⇒ 心跳每 150ms 都有活干
        //   （invalidateTree 一棵<b>藏在迷你条后面</b>的播放页子树）——
        //   实测这就是「迷你条存在时首页滑动 99th 350ms」的极值帧来源。
        //   额度现在只由真正的事件充：seam（播放页刚出现）/ 转场收尾。
        sResumeRedoAt = 0L;

        if (changed) {
            // ★ 用户第 5 条：切回宿主默认背景（armed 掉下去）⇒ 立刻关掉所有文字阴影。
            if (!ready) TextShadow.clearAllShadows();
            // ★★★ v0.1.90：**分两档重绘**。见类字段 {@link #sArmDropTicks} 的实测数据：
            //   · 配置真的变了（换图 / 改模糊 / 开关）⇒ 一次性事件，整树重绘是对的，
            //     否则各层 RenderNode 里还留着旧底图；
            //   · 只是 armed 翻转 ⇒ 插件画的东西只有「满屏底图」那几层，
            //     整树重绘会把首页 3 排横向列表（几百个 View）一起作废，实测 261ms。
            View dec = decNow != null ? decNow : decorOf(a);
            if (cfgDirty) {
                invalidateTree(dec, "applyCfg");
            } else if (geoChanged) {
                // ★★★ v0.1.98：**窗口尺寸变了就必须整窗重录**。
                //   浮窗拖动缩放的每一帧、以及「浮窗 → 全屏」，各层的壁纸落点都变了；
                //   而落点是录在 display list 里的常量，不重录就一直是旧落点
                //   ⇒ 用户看到的「拉大到全屏依旧移位，只有前后台切换才恢复」。
                //   这是一次性事件（尺寸稳定后不再触发），不是热路径。
                invalidateTree(dec, "applyGeo");
            } else {
                repaintLayers(dec, "armFlip");
            }
            // ★★★ v0.1.126：armed 起来后立刻补文字阴影。
            //   armFlip / skipRedraw 不再作废 TextView ⇒ 阴影会一直等到下次自然 draw。
            //   这里只扫 TextView（≤240），不动列表容器。
            if (ready) TextShadow.refreshTextShadows(dec, cfgDirty ? "applyCfg" : "armFlip");
            // ★ v0.1.14：窗口层也要补。WindowBg 在 onResume 那一刻拿不到位图就再也不试，
            //   于是「底色被换成壁纸 + 窗口层空着」。这里每轮都叫它补一次（幂等）。
            WindowBg.retryIfMissing(a);
            if (sLogs < LOG_BUDGET) {
                sLogs++;
                log("apply want=" + (want ? 1 : 0) + " armed=" + (ready ? 1 : 0)
                        + " hooked=" + (sHooked ? 1 : 0) + " screen=" + sSW + "x" + sSH
                        + " wp=" + (bmp == null ? "null" : bmp.getWidth() + "x" + bmp.getHeight())
                        + " bar=" + (sWpBarBmp == null ? "null" : "ok")
                        + " play=" + (sWpPlayBmp == null ? "null" : "ok")
                        + " accent=" + (AccentColor.on() ? "on" : "off")
                        + "/" + AccentColor.styleName());
            }
        } else if (ready) {
            // ★★★ v0.1.142：armed 稳态下也扫开屏 —— Splash 常在首轮 apply 之后才挂上，
            //   changed=false 时 retryIfMissing 不会走，紫底会一直盖到淡出结束。
            WindowBg.clearSplashPurple(decNow != null ? decNow : decorOf(a));
        }
        BgDiag.maybeSummary();
    }

    /**
     * 刷新屏幕尺寸与各项像素门槛（每轮一次，旋转 / 分屏也能跟上）。
     *
     * @return true = 窗口尺寸<b>真的变了</b>（浮窗缩放 / 旋转 / 分屏 / 全屏↔浮窗）
     *         ⇒ 上层必须让视图树重录一遍，否则各层 display list 里还存着旧尺寸下
     *         算出来的壁纸落点 —— 这正是「拉大到全屏依旧移位，只有前后台切换才恢复」。
     */
    static boolean updateMetrics(View decor) {
        try {
            if (decor == null) {
                sMinArea = Long.MAX_VALUE;
                sSW = 0;
                sSH = 0;
                return false;
            }
            int rw = decor.getWidth();
            int rh = decor.getHeight();
            if (rw <= 0 || rh <= 0) {
                sMinArea = Long.MAX_VALUE;
                sSW = 0;
                sSH = 0;
                return false;
            }
            boolean geo = sSW > 0 && sSH > 0 && (rw != sSW || rh != sSH);
            sSW = rw;
            sSH = rh;
            sMinArea = (long) (FULL_SCREEN_RATIO * rw * rh);
            float d = decor.getResources().getDisplayMetrics().density;
            sEdgeTop = (int) (EDGE_TOP_DP * d);
            sEdgeBottom = (int) (EDGE_BOTTOM_DP * d);
            sBarMaxH = (int) (BAR_MAX_H_DP * d);
            sFullW = (int) (FULL_W_RATIO * rw);
            return geo;
        } catch (Throwable t) {
            sMinArea = Long.MAX_VALUE;
            return false;
        }
    }

    // ── ★★★ v0.1.98：窗口尺寸变化（浮窗 / 分屏 / 旋转）──────────────────

    /** 已经挂过「尺寸监听」的 DecorView（主窗口只有一个）。 */
    static volatile View sGeoWatch;

    /** 尺寸变化触发的重绘次数（诊断用）。 */
    static volatile int sGeoRedraws;

    /**
     * 给主窗口挂一个 {@code OnLayoutChangeListener}：<b>尺寸一变就立刻刷度量</b>。
     *
     * <p>为什么不能只靠 {@link #POLL_MS} 的轮询：用户把浮窗拉大 / 缩小时，
     * 画面在 1.5 秒内会一直按旧尺寸铺壁纸 —— 那就是「割裂」最刺眼的一段。
     * 监听是宿主自己回调的，零轮询代价，且只在真的改了尺寸时才做事。
     */
    static void watchGeometry(final View decor) {
        if (decor == null || decor == sGeoWatch) return;
        sGeoWatch = decor;
        try {
            decor.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b,
                                           int ol, int ot, int or, int ob) {
                    // 纯位移（拖浮窗）不用重画：壁纸按窗口铺，窗口挪了坐标基准不变。
                    if ((r - l) == (or - ol) && (b - t) == (ob - ot)) return;
                    onWindowResized(v);
                }
            });
            log("geo watch on " + decor.getWidth() + "x" + decor.getHeight());
        } catch (Throwable t) {
            log("geo watch FAILED: " + t);
        }
    }

    /** 窗口尺寸真的变了：刷新度量，并把「重绘」合并成尺寸稳定后的一次。 */
    static void onWindowResized(View decor) {
        try {
            int w = decor == null ? 0 : decor.getWidth();
            int h = decor == null ? 0 : decor.getHeight();
            if (w <= 0 || h <= 0) return;
            if (!updateMetrics(decor)) return;      // 尺寸没变（可能只是 padding 抖动）
            if (!sArmed) return;                    // 没在画壁纸，不用管
            MAIN.removeCallbacks(GEO_REPAINT);
            MAIN.postDelayed(GEO_REPAINT, 60L);     // 连续缩放时合并成最后一次
        } catch (Throwable ignored) {
        }
    }

    /**
     * 尺寸稳定后重录一次视图树。
     *
     * <p>⚠️ 必须走 {@link #invalidateTree}（全量）：轻量路径
     * {@code REPAINT_LAYERS_ENABLED=false} 时什么都不作废，而这里<b>所有</b>
     * 铺过壁纸的层都存着旧尺寸的落点，只作废一两个是不够的。
     */
    static final Runnable GEO_REPAINT = new Runnable() {
        @Override
        public void run() {
            try {
                View d = sGeoWatch;
                if (d == null) d = decorOf(sPoll);
                if (d == null) return;
                sGeoRedraws++;
                log("geo redraw " + d.getWidth() + "x" + d.getHeight()
                        + " #" + sGeoRedraws);
                invalidateTree(d, "geoLayout");
            } catch (Throwable ignored) {
            }
        }
    };

    /**
     * 登记一层「满屏」View（每个类只打一次，最多 {@link #BIG_LOG_BUDGET} 行）。
     *
     * <p>从 {@code View#draw} 里看到的是<b>真的在被画的那些层</b>，
     * 比「延迟遍历视图树」可靠（r18/r20 两次都是空清单）。
     */
    static void noteBig(View v, int w, int h, int kids) {
        if (sBigLogs >= BIG_LOG_BUDGET) return;
        String key;
        try {
            key = v.getClass().getName() + (kids > 0 ? "/g" : "/leaf");
        } catch (Throwable t) {
            return;
        }
        if (!sBigSeen.add(key)) return;
        sBigLogs++;
        log("big " + name(v) + " " + w + "x" + h + " top=" + topOf(v)
                + " kids=" + kids + " bg=" + desc(v.getBackground())
                + " alpha=" + alphaOf(v));
    }

    static int topOf(View v) {
        try {
            return v.getTop();
        } catch (Throwable t) {
            return -1;
        }
    }

    static String alphaOf(View v) {
        try {
            return String.valueOf(v.getAlpha());
        } catch (Throwable t) {
            return "?";
        }
    }

    static String desc(Drawable d) {
        if (d == null) return "none";
        String s = d.getClass().getSimpleName();
        try {
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                s = s + String.format("#%08X",
                        ((android.graphics.drawable.ColorDrawable) d).getColor());
            }
            if (d.getOpacity() == PixelFormat.OPAQUE) s = s + "/O";
        } catch (Throwable ignored) {
        }
        return s;
    }

    static String name(View v) {
        try {
            return v.getClass().getSimpleName();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** ★ v0.1.74（r88）：MoriHit 的去重表 + 剩余额度。 */
    static final java.util.Map<String, Long> HITLOG_AT =
            java.util.Collections.synchronizedMap(new java.util.HashMap<String, Long>());

    static volatile int sHitLogLeft = 900;

    /** 「这个 key 只打一次」的登记表（13 轮日志饥饿的解法，见 {@link #hit}）。 */
    static final java.util.Map<String, Boolean> ONCE =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    static volatile int sOnceTotal;
    static final int ONCE_TOTAL = 140;

    static int curColor(TextView tv) {
        try {
            return tv.getCurrentTextColor();
        } catch (Throwable t) {
            return 0;
        }
    }




    static int kidsOf(View v) {
        try {
            return v instanceof ViewGroup ? ((ViewGroup) v).getChildCount() : 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * ★★★ v0.1.46（r46 用户第 4② 条）：回到前台 / 播放页重新展开时重置播放页状态。
     *
     * <p>用户实测的触发点：全屏播放页收成迷你条 → 退后台 → 回前台 → 从迷你条再展开
     * ⇒ 播放页背景透明、作品封面不显示；而「带着全屏播放页退后台再回来」反而会自己好。
     * 差别在于：前者播放页是**收起状态下**经历的后台/前台切换，
     * {@code COVER_FIX} 的 800ms 节流窗口还记着旧时间戳，展开那一帧封面 alpha=0
     * 时 {@link #rescueCover} 被 {@code coverFixAllowed} 拦掉；播放页的整屏底图
     * 又因为各层缓存的「已画」标记没清，硬件加速下不会重跑 draw。
     *
     * <p>在 onResume（WindowBg 的钩子）里调一次：清掉封面救援节流 + 玻璃/大层缓存
     * 标记，让展开后的第一帧重新判、重新画。纯内存操作，无绘制开销。
     */
    public static void onForeground(Activity a) {
        try {
            COVER_FIX.clear();
            GLASSED.clear();
            sPainted = false;
            // ★★★ v0.1.51（r51 用户第 3 条）：**播放页判定状态一并作废**。
            //   「迷你条 → 后台 → 前台 → 展开」这条路里，宿主会把播放页的窗口/视图重建，
            //   而我们的 {@code PLAY_WIN}（120 秒）、{@code sPlayScreen}、
            //   {@code sSeekPageAnchor} 里记的还是<b>上一轮那些 View</b>。
            //   重建之后它们全都对不上 ⇒ 每一层都判 false ⇒ 谁都不铺 ⇒
            //   露出的就是宿主那一层（开防偷窥时就是带蒙层的封面）。
            //   回前台时清空这些账，让新的一轮重新认一次。
            // ★ v0.1.81：统一走 forgetPlayState（比这里原来那份多清了
            //   PLAY_PAINT_PAGE / PLAY_BG / sPlayWinPage / sPlayPaintedAt）。
            PlayPage.forgetPlayState();
            // ★★★ v0.1.47（r47 用户第 4 条）：**只清缓存不够，必须让整棵树重画**。
            //   「迷你条状态下退后台→回前台→再展开播放页」那一屏，各层的绘制指令
            //   还躺在硬件加速的 RenderNode 里 —— 状态翻转了也没有任何一层会重跑
            //   draw（这正是 v0.1.25 就写进 apply() 注释的那条铁律）。
            //   所以除了清掉封面救援节流 / 玻璃缓存，还要把 DecorView 下所有层
            //   invalidate 一遍，强制它们用此刻的状态重新录一帧。
            View decor = a == null ? null : decorOf(a);
            if (decor != null) repaintLayers(decor, "expandResume");
            // ★ r107：r106 补的 300 / 700ms 两拍已撤回（会把播放页封面弄丢）。
        } catch (Throwable ignored) {
        }
    }

    /** 配置变了（换图 / 改遮罩模糊 / 关掉开关）：下一轮重新判 + 重画。 */
    public static void invalidate() {
        sDirty = true;
        sPainted = false;
        sSeen = 0;
        sSkipped = 0;
        sLeaf = 0;
        sBar = 0;
        sGrad = 0;
        sOverlay = 0;
        sScrim = 0;
        sVeil = 0;
        sAtmos = 0;
        sBlurBg = 0;
        sSheet = 0;
        sZoom = 0;
        sLastSummaryAt = 0L;
        sLogs = 0;
        synchronized (sBigSeen) {
            sBigSeen.clear();
        }
        synchronized (sImgSeen) {
            sImgSeen.clear();
        }
        // ⚠️ 各日志预算不重置：否则每次配置同步都从 0 开始，日志被刷满（r18 实证）
    }

    /** 关掉功能 / 恢复默认：解除武装并让宿主把自己的底色画回来。 */
    public static void restoreAll() {
        sArmed = false;
        sWpBmp = null;
        sWpBarBmp = null;
        sWpPlayBmp = null;
        sWpGlassBmp = null;
        // ★ v0.1.73：换壁纸 / 关功能后，「对象级底图」的账也要作废，否则内层会继续
        //   drop 给一张已经不存在的根底板。
        sPlayRootBgView = null;
        sPlayRootBgBmp = null;
        ROOT_BG.clear();
        // ★ v0.1.41：Paint 字段缓存一并作废（换壁纸 / 关功能后重新认一次）。
        sPainted = false;
        // ★ v0.1.22：恢复默认背景 ⇒ 强调色一并关掉（用户第 4 条「开关关闭时还原宿主默认」）。
        AccentColor.reset();
        // ★ 用户第 5 条：切回宿主默认背景 ⇒ 所有文字阴影关闭、回到宿主原样。
        TextShadow.clearAllShadows();
        Activity a = sPoll;
        if (a != null) invalidateTree(decorOf(a), "reapplyOff");
    }

    /**
     * 让宿主重画一帧。
     *
     * <p>⚠️ 之所以要「遍历整棵树 invalidate」，见 {@link #apply} 的说明：
     * 子视图的绘制指令在自己的 RenderNode 里，父层重画不会重跑它们的 draw。
     */
    public static void repaint(View decor) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> repaint(decor));
            return;
        }
        invalidateTree(decor, "repaintCall");
    }

    /**
     * ★★★ v0.1.90：只作废「满屏层」的 RenderNode，<b>不碰列表里那几百个卡片</b>。
     *
     * <p>用来替代 {@link #invalidateTree} 处理「武装状态翻转」这类<b>非配置性</b>的重绘。
     * 插件真正画的东西只有满屏底图，那些层的面积一定接近整屏；而列表卡片、按钮、
     * 文字都不归我们画 ⇒ 它们被作废只会白重录。
     *
     * <p>实测依据（2.22.2 · 60s · 跨作品切歌 · 开插件）：
     * 一帧 266.8ms 里整棵树从 {@code DecorView} 到 {@code ReactScrollView} 每层都占满
     * 261ms，深度 27 那个 {@code ReactViewGroup 1272x4230}（高过屏幕的首页列表容器）
     * 下挂 3 条 {@code ReactHorizontalScrollView}，分别 107.9 / 75.9 / 23.2ms —— 全是白重录。
     * 关掉插件同位置最长帧 6.3ms。
     *
     * @return 实际作废的层数
     */
    /**
     * ★★★ v0.1.93：被我们接管过背景的 View（弱引用，最多 {@link #BG_OWNERS_MAX} 个）。
     *
     * <p>为什么要这张表：v0.1.91/92 的重绘是「按面积找满屏层」。实测这个判据<b>选错了人</b>——
     * {@code ScreenStack}（1272x2564，占屏 92%）也被选中、被 {@code invalidate()}，
     * 它的 display list 重录要遍历整棵子树的绘制命令，<b>纯 CPU 80ms 以上</b>：
     *
     * <pre>
     * 帧 1184268：ScreenStack 自己 84.6ms，它的子 View 加起来只有 0.4ms
     * 帧  214745：ScreenStack 自己 86.0ms，子项 0.4ms
     * 两帧都紧跟在 lightRedraw 之后 367 / 396ms；主线程窗口内 Running 91ms、Sleep 3.8ms
     * </pre>
     *
     * <p>而插件真正要重画的东西只有一件事：<b>宿主那个不透明的 {@code BackgroundDrawable}
     * 的 display list 里还存着旧底色命令</b>，得让它重录一遍（重录时我们的钩子返回 null，
     * 底色就没了，壁纸透出来）。那块 drawable 属于哪个 View，{@link #ownerView} 当场就能拿到。
     *
     * <p>⇒ 精确到「谁被接管就作废谁」，通常 1～2 个 View，容器与列表一概不碰。
     */
    static final java.util.ArrayList<java.lang.ref.WeakReference<View>> sBgOwners
            = new java.util.ArrayList<>();
    static final int BG_OWNERS_MAX = 8;

    /**
     * ★★★ v0.1.94 总开关：四条热路径（页面挂载 / 播放页重铺 / 回前台 / 武装翻转）是否还作废 View。
     *
     * <p>置 {@code false} 的依据见 {@link #repaintLayers}：armed 从未掉过 ⇒ 没有旧命令要清；
     * 而 invalidate 任何容器都是几十到两百毫秒。真出问题改成 {@code true} 能立刻回到
     * v0.1.93 的「精确制导作废」行为。
     */
    static final boolean REPAINT_LAYERS_ENABLED = false;

    /** 数一下当前表里有几个活着的 owner（只用于日志，不产生任何副作用）。 */
    static int countBgOwners() {
        int n = 0;
        try {
            synchronized (sBgOwners) {
                for (int i = sBgOwners.size() - 1; i >= 0; i--) {
                    if (sBgOwners.get(i).get() == null) sBgOwners.remove(i);
                    else n++;
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** 记录「这块背景归我们管」的 owner View（幂等，重复的不重复加）。 */
    static void rememberBgOwner(Drawable d) {
        try {
            View v = ownerView(d);
            if (v == null) return;
            synchronized (sBgOwners) {
                for (int i = sBgOwners.size() - 1; i >= 0; i--) {
                    View o = sBgOwners.get(i).get();
                    if (o == null) {
                        sBgOwners.remove(i);          // 顺手清死引用
                    } else if (o == v) {
                        return;                        // 已经在表里
                    }
                }
                if (sBgOwners.size() >= BG_OWNERS_MAX) sBgOwners.remove(0);
                sBgOwners.add(new java.lang.ref.WeakReference<>(v));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * ★★★ v0.1.93：**只作废被接管过的那几个 View**。
     *
     * @return 实际作废的个数
     */
    static int repaintBgOwners() {
        int n = 0;
        try {
            synchronized (sBgOwners) {
                for (int i = sBgOwners.size() - 1; i >= 0; i--) {
                    View v = sBgOwners.get(i).get();
                    if (v == null) {
                        sBgOwners.remove(i);
                        continue;
                    }
                    try {
                        v.invalidate();
                        n++;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /**
     * ★★★ v0.1.91：**只作废满屏层**的重绘入口（带来源标签）。
     *
     * <p>{@link #invalidateTree} 会 DFS 打最多 800 个 View 的 {@code invalidate()}，
     * 每个 View 的 RenderNode 都要重录 —— 实测一帧 200～280ms。而插件真正画的东西
     * 只有「满屏底图」那几层。所以凡是<b>非配置变更</b>的重绘都必须走这里。
     *
     * <p>为什么轻量够用：display list 是**每个 View 一份**，作废一个容器只会重录它
     * 自己，子 View 的 display list 仍然复用；而我们钩的 {@code BackgroundDrawable#draw}
     * 就挂在那些满屏容器/叶子层的绘制过程里。
     */
    static int repaintLayers(View root, String why) {
        // ★★★ v0.1.94 判据：**稳态下根本不需要作废任何东西**。
        //
        //   理由（实测，2.22.2 · 90s × 2 轮）：
        //   ① 所有 draw 日志的 armed 一直是 1，**从未翻转过** ⇒ 宿主 display list 里
        //      从来没存过「不透明的旧底色命令」，没有东西需要清。
        //   ② invalidate 的代价跟被作废 View 的子树规模成正比：作废 ScreenStack 84.6ms、
        //      作废 DecorView（窗口背景的 owner 就是它）整窗重录 169.7ms、整帧 231.3ms。
        //      而插件真正画的壁纸是一个常驻独立 View，画一次就固定，重绘它没有任何收益。
        //   ③ 真需要重画的只有「配置变更」（换图 / 改模糊 / 开关），那条走 applyCfg 整树，
        //      是一次性用户动作，留着。
        //
        //   ⇒ 页面挂载 / 播放页重铺 / 回前台 / 武装翻转 四条热路径一律不作废，只记日志，
        //     留下「本来会作废几个」这个数值便于回归时观察。
        if (!REPAINT_LAYERS_ENABLED) {
            int probe = countBgOwners();
            sLightRedraws++;
            if (sDbgRedraw < DBG_REDRAW_BUDGET) {
                sDbgRedraw++;
                log("skipRedraw ← " + why + " owner=" + probe + "（全量=" + sFullRedraws
                        + " 轻量=" + sLightRedraws + "）");
            }
            return 0;
        }
        // ★★★ v0.1.93：从「按面积挑满屏层」改成「只挑被接管过背景的 View」。
        //   面积判据会把 ScreenStack 选进来，而它重录一次 80ms+（纯 CPU、子项 0.4ms）。
        int n = repaintBgOwners();
        if (n == 0) {
            // 表还空（还没接管过任何背景：冷启动/刚换页）⇒ 退回满屏层，
            // 但 ScreenStack 这类容器一律跳过，它重录就是几十毫秒。
            n = invalidateFullScreenLayers(root, 0, 0);
        }
        sLightRedraws++;
        if (sDbgRedraw < DBG_REDRAW_BUDGET) {
            sDbgRedraw++;
            log("lightRedraw ← " + why + " " + n + " 层（全量=" + sFullRedraws
                    + " 轻量=" + sLightRedraws + "）");
        }
        return n;
    }

    /**
     * 按面积作废满屏层。★ v0.1.93：<b>跳过 {@code ScreenStack} 这类容器</b>。
     *
     * <p>{@code ScreenStack} 的 display list 里压着整棵子树的绘制命令，重录一次实测
     * 84.6 / 86.0ms，而它自己的子 View 加起来只有 0.4ms —— 作废它纯粹是白烧 CPU，
     * 且它并不是「我们画底图」的那一层（真正被接管的 View 由
     * {@link #rememberBgOwner} 精确记录）。宿主类名不受本模块 R8 影响，字面量稳定。
     */
    static int invalidateFullScreenLayers(View v, int depth, int count) {
        if (v == null || depth > 30 || count >= 20) return count;
        try {
            String cn = v.getClass().getSimpleName();
            if (cn.contains("ScreenStack") || cn.contains("ScreensCoordinatorLayout")) {
                return count;   // 容器重录几十毫秒、且不归我们画 ⇒ 直接跳过整支
            }
        } catch (Throwable ignored) {
        }
        try {
            int w = v.getWidth();
            int h = v.getHeight();
            // 与 {@link #topFullScreenAncestor} 同一套满屏判据：宽 ≥90%、高 ≥50%。
            if (w > 0 && h > 0 && sSW > 0 && sSH > 0
                    && (long) w * 100 >= (long) sSW * 90
                    && (long) h * 100 >= (long) sSH * 50) {
                v.invalidate();
                count++;
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int c;
            try {
                c = g.getChildCount();
            } catch (Throwable t) {
                return count;
            }
            for (int i = 0; i < c && count < 20; i++) {
                try {
                    count = invalidateFullScreenLayers(g.getChildAt(i), depth + 1, count);
                } catch (Throwable ignored) {
                }
            }
        }
        return count;
    }

    static void invalidateTree(View root) {
        invalidateTree(root, "?");
    }

    /**
     * ★★★ v0.1.91：**tag 由调用点自己给**。
     *   v0.1.90 用调用栈回溯，release 包被 R8 混淆后只能打出 `BgPeel.r0:1`，
     *   等于没定位到。字符串字面量是 R8 唯一不敢动的锚点 ⇒ 改成传 tag。
     */
    static void invalidateTree(View root, String why) {
        if (root == null) return;
        // ★★★ v0.1.90 诊断：**记录是谁在整树重绘**（限 40 条）。
        //   整窗重绘代价是 260ms 级（见 {@link #invalidateFullScreenLayers} 的实测），
        //   而调用点有 6 处 —— 不把来源钉死，改哪一处都是猜。
        //   栈只在预算内采集，稳态零开销。
        if (sDbgRedraw < DBG_REDRAW_BUDGET) {
            sDbgRedraw++;
            try {
                View v = root;
                String nm = (v.getWidth()) + "x" + (v.getHeight());
                log("invalidateTree ← " + why + " " + nm
                        + "（全量=" + (sFullRedraws + 1) + " 轻量=" + sLightRedraws + "）");
            } catch (Throwable ignored) {
            }
        }
        sFullRedraws++;
        try {
            root.postInvalidateOnAnimation();
        } catch (Throwable ignored) {
        }
        try {
            touch(root, 0);
        } catch (Throwable ignored) {
        }
    }

    /** 深度优先 invalidate（限深 40 / 限数 800，绝不递归到爆栈）。 */
    static int touch(View v, int depth) {
        if (v == null || depth > 40) return 0;
        int n = 0;
        try {
            v.invalidate();
            n = 1;
        } catch (Throwable ignored) {
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int c;
            try {
                c = g.getChildCount();
            } catch (Throwable t) {
                return n;
            }
            for (int i = 0; i < c && n < 800; i++) {
                n += touch(g.getChildAt(i), depth + 1);
            }
        }
        return n;
    }

    /** 取 Activity 的 DecorView（拿不到返回 null，绝不抛）。 */
    static View decorOf(Activity a) {
        try {
            return a == null || a.getWindow() == null ? null : a.getWindow().getDecorView();
        } catch (Throwable t) {
            return null;
        }
    }

    static void log(String msg) {
        XposedCompat.log(TAG + " " + msg);
    }
}
