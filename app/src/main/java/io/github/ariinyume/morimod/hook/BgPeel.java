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

    private static final String TAG = "[MoriMod:BG]";

    /** RN 的纯色背景 Drawable（R8 保留了名字，见 2.22.0 dex）。 */
    private static final String CLS_BG_DRAWABLE =
            "com.facebook.react.uimanager.drawable.BackgroundDrawable";

    /** RN 0.81 的 {@code backgroundImage} 落成的 Drawable。 */
    private static final String CLS_BG_IMAGE =
            "com.facebook.react.uimanager.drawable.BackgroundImageDrawable";

    /** expo-linear-gradient 的渐变 View（页面真背景 + 各种渐变蒙层）。 */
    private static final String CLS_GRADIENT_VIEW =
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
    private static final String CLS_EXPO_IMAGE = "expo.modules.image.ExpoImageView";

    /**
     * 判定「氛围叠加图」的平均 alpha 上限（v0.1.20）。
     *
     * <p>报告 §4 的共同特征：{@code localbg / playbacksbackground / playback_card_bg /
     * player_background} 全是 <b>RGBA + 大面积 alpha</b>；唯一的不透明图
     * {@code main_visual} 是 <b>RGB</b>（主页立绘，不能碰）。
     * ⇒ 用「位图有没有 alpha 通道 + 采样平均 alpha」就能干净区分，不依赖比例魔数。
     */
    private static final float VEIL_MAX_ALPHA = 0.92f;

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
    private static final float VEIL_DROP_MAX_ALPHA = 0.65f;

    /** 氛围图必须占屏幕高度的这个比例以上（排除小图标 / 头像）。 */
    private static final float VEIL_MIN_H_RATIO = 0.18f;

    /**
     * 判定「这一层正在缩放转场」的门槛（v0.1.23 用户第 3 条）。
     *
     * <p>2% 以内当作静止：宿主有些层会常驻 0.99x 的微缩放，卡太紧会让正常页面的
     * 清晰壁纸无故变糊。真实转场缩放都在 0.9 以下或 1.1 以上。
     */
    private static final float ZOOM_SCALE_EPS = 0.02f;

    /**
     * 「缩放超过这个幅度才允许把清晰壁纸换成毛玻璃」（v0.1.27 用户第 4 条）。
     *
     * <p>2% 的门槛太灵敏：图片浏览翻到最后一张时的<b>回弹</b>、列表的轻微拉伸
     * 都能命中，于是「最后一张图片背后一定多一层高斯模糊」（r32 原话）。
     * 真正的转场是 0.8~0.9x / 1.1x 量级，8% 这条线正好把「真转场」和「轻微回弹」分开。
     */
    private static final float ZOOM_BLUR_MIN = 0.08f;

    /**
     * 「这一张模糊底图属于播放页」的 {@code blurRadius} 门槛（v0.1.25 用户第 3 条）。
     *
     * <p>bundle 实证全包只有三处 {@code blurRadius}：
     * <ul>
     *   <li>{@code PlayerBackground} = <b>10</b> ⇒ 播放页，用户要「自定义背景 + 模糊」；</li>
     *   <li>{@code PlaylistDetailBackground} / {@code GenreDetailScreen} = <b>6</b>
     *       ⇒ 列表 / 详情，这些页面上会开信息面板；如果这里也铺全屏模糊，
     *       用户看到的就是「面板一出来，背后的主界面也跟着糊」。</li>
     * </ul>
     * 取 8 作分界，两档都留了余量（以后宿主微调一两档也不会互相误判）。
     */
    private static final int PLAY_BLUR_MIN = 8;

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
     *   <li>seekbar 加载 ⇒ {@link #notePlayPage()} 打点 + 置「待绑定」；</li>
     *   <li>下一个整屏模糊底把它的 <b>Screen 容器</b>绑成「播放页容器」；</li>
     *   <li>之后只有<b>同一个容器</b>里的模糊底才算播放页 —— 列表页是另一个
     *       {@code Screen} 实例，第一帧就是清晰壁纸，不会再有「整页糊」。</li>
     * </ol>
     */
    private static final long PLAY_PAGE_TTL_MS = 1500L;

    /** 最近一次「播放页特征」（seekbar 资源加载）的时间戳。 */
    private static volatile long sPlayPageAt;

    /** 有待绑定的播放页容器：seekbar 刚加载过，下一块整屏模糊底就是播放页。 */
    private static volatile boolean sPlayBindPending;

    /** 已绑定成「播放页」的容器（弱引用，页面销毁自动失效）。 */
    private static volatile java.lang.ref.WeakReference<View> sPlayScreen;

    /** 播放页判定命中/失败各打一条，便于下一轮核对（每进程 6 条）。 */
    private static volatile int sPlayJudgeLogs;
    private static final int PLAY_JUDGE_LOG_BUDGET = 6;

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
    private static volatile java.lang.ref.WeakReference<View> sPlaySeek;

    /** 「播放页进度条」探测日志条数。 */
    private static volatile int sPlaySeekLogs;
    private static final int PLAY_SEEK_LOG_BUDGET = 4;

    /**
     * 判定「铺满窗口」的面积比例。
     *
     * <p>0.80：RN 根是 1272x2564，DecorView 是 1272x2772（差系统栏）⇒ 比值 0.925，
     * 页面容器换成「屏幕减底栏」也还有 0.86；而最大的卡片也就 0.15 上下。
     */
    private static final float FULL_SCREEN_RATIO = 0.80f;

    /** 判定「铺满宽度」的比例。卡片有左右边距（≈4%），够不到这个数。 */
    private static final float FULL_W_RATIO = 0.94f;

    /** 前台复核周期（只读配置 + 缓存，不遍历视图树）。 */
    private static final long POLL_MS = 800L;

    /** 日志上限（每进程）。★ v0.1.28：160 → 320（确认音让日志更完整）。 */
    private static final int LOG_BUDGET = 320;

    /** 「满屏层清单」最多打多少行（每进程）。 */
    private static final int BIG_LOG_BUDGET = 28;

    /** 顶栏判定：View 顶边落在「屏幕顶 + 这个高度」以内（dp）。 */
    private static final float EDGE_TOP_DP = 110f;

    /** 底栏判定：View 底边落在「屏幕底 - 这个高度」以内（dp）。 */
    private static final float EDGE_BOTTOM_DP = 190f;

    /** 条高度上限（dp）：再高的就不是顶栏/底栏，而是内容区。 */
    private static final float BAR_MAX_H_DP = 170f;

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
    private static final float SHEET_MIN_H_RATIO = 0.12f;

    /**
     * 面板的高度上限（占屏高比例）。
     *
     * <p>★ v0.1.27：0.80 → 0.92。用户连着两轮反馈「「概述」「下载音轨」「PurePlay
     * 简介页」「PurePlay 导入页」这四张卡片始终挂不上玻璃」。这四张都是<b>接近整屏</b>的
     * 底部面板（内容多），高度轻松越过 0.80 ⇒ 被判成「整页容器」⇒ 一张玻璃都没有。
     * 0.92 仍然把「真正的整页」（内容从屏幕顶端开始，顶边贴顶 ⇒ 下面那条
     * {@code loc[1] <= sEdgeTop} 判据会先挡掉）排除在外。
     */
    private static final float SHEET_MAX_H_RATIO = 0.92f;

    /**
     * 面板宽度下限（占屏宽比例）。
     *
     * <p>★ v0.1.27：从「满宽 94%」放宽到 <b>86%</b>。宿主的底部面板带左右 16dp 圆角留白，
     * 宽度只有 90% 上下 —— 卡 94% 就永远不命中，那四张卡片于是永远没有玻璃。
     * 86% 依然能把列表里的大卡片（≤ 60% 屏宽）挡在外面。
     */
    private static final float SHEET_MIN_W_RATIO = 0.86f;

    /**
     * 文字阴影半径占字号的比例（v0.1.20 用户指定 8%）。
     *
     * <p>只有「自定义壁纸生效时」才加（{@link #sArmed}）—— 恢复默认背景就回到宿主原样。
     */
    private static final float TEXT_SHADOW_RADIUS_RATIO = 0.08f;

    /** 文字阴影强度（alpha 0xCC = 80%）。<b>色相</b>由 {@link #shadowColor()} 决定。 */
    private static final int TEXT_SHADOW_ALPHA = 0xCC;

    /**
     * 文字阴影颜色：默认纯黑；开了强调色就换成「同一 alpha 的强调色调」。
     *
     * <p>用户第 4 条要的是「阴影蒙层与强调色观感一致、透明度不变」——
     * 所以这里只换 RGB，alpha 固定 {@link #TEXT_SHADOW_ALPHA}。
     */
    private static int shadowColor() {
        return AccentColor.on() ? AccentColor.shadow(TEXT_SHADOW_ALPHA)
                : (TEXT_SHADOW_ALPHA << 24);
    }

    /**
     * 判定「亮底」的亮度门槛（v0.1.20 用户第 6 条）。
     *
     * <p>PurePlay 的「导入 / 创建播放列表」、设定里的「同步已购买作品」这些按钮是<b>实心浅色底</b>，
     * 上面加黑阴影只会糊成一团 ⇒ 背景亮度过这个值就不加阴影（并把之前加过的清掉）。
     */
    private static final float LIGHT_BG_LUMA = 0.60f;

    /**
     * 判定「文字自己是深色」的亮度上限（v0.1.21 用户第 4 条）。
     *
     * <p>深字压深底谁都看不见 ⇒ 只要文字是深色，它就一定坐在浅底上。
     * 这条不依赖任何 drawable 内部结构，是「白底按钮文字不要阴影」最稳的兜底判据
     * （宿主那批按钮的底色是 R8 混淆过的 {@code BackgroundDrawable}，读不到颜色字段）。
     */
    private static final float DARK_TEXT_LUMA = 0.45f;

    /** 采样底色用的缩略图边长（8×8 足够分辨「白底 / 深底」，成本可忽略）。 */
    private static final int PROBE_PX = 8;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 绘制用的临时对象（draw 都在 UI 线程，复用避免每帧分配）。 */
    private static final ThreadLocal<int[]> LOC = ThreadLocal.withInitial(() -> new int[2]);
    private static final ThreadLocal<Rect> SRC = ThreadLocal.withInitial(Rect::new);
    private static final ThreadLocal<RectF> DST = ThreadLocal.withInitial(RectF::new);
    /** v0.1.22：面板圆角裁剪用的 Outline / Path。 */
    private static final ThreadLocal<android.graphics.Outline> OUTLINE =
            ThreadLocal.withInitial(android.graphics.Outline::new);
    private static final ThreadLocal<android.graphics.Path> PATH =
            ThreadLocal.withInitial(android.graphics.Path::new);

    /** 位图绘制画笔（FILTER_BITMAP 保证缩放平滑）。 */
    private static final Paint BMP_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** 条带压暗蒙层（{@link FeatureF2#BAR_MASK_PCT}）。 */
    private static final Paint MASK_PAINT = new Paint();

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
    private static final Paint WASH_PAINT = new Paint();

    /**
     * ★★★ v0.1.48（r48 用户第 1 条）：防偷窥封面上那层强调色罩的不透明度。
     *
     * <p>48/255 ≈ 19% 在浅紫封面上肉眼几乎无感（这就是「看不出来」的一半原因，
     * 另一半是颜色本身是阴影色，见 {@link AccentColor#washTint}）。
     * 110/255 ≈ 43%：足够把封面明确染到强调色上，又还看得出底下是一张模糊图。
     */
    private static final int WASH_ALPHA = 230;

    /**
     * ★★★ v0.1.49（r49 用户第 1 条）：封面<b>外框整块</b>罩色的不透明度。
     *
     * <p>图片自己那层罩（{@link #WASH_ALPHA}）会被宿主随后画的蒙层 / 动画盖回去，
     * 所以在外框「所有子层画完」之后再罩一次。170/255 ≈ 67%：
     * 叠加总效果 ≈ 1-(1-0.43)(1-0.67) ≈ 81%，封面明确是强调色块，仍能看出是张图。
     */
    private static final int WASH_VEIL_ALPHA = 235;

    /** 「wash veil」诊断日志条数。 */
    private static volatile int sVeilLogs;

    /**
     * ★★★ v0.1.49：刚被罩过色的封面块的<b>屏幕矩形</b>（最多 8 条、1.5 秒有效期）。
     *
     * <p>用途：让「外框那一层」也能认出来。真机结构是
     * {@code ReactViewGroup 1153x357 > ExpoImageView 1153x357}，
     * 外框和图片尺寸一致、位置一致 ⇒ 按屏幕矩形对账最稳，不依赖类名。
     *
     * <p>存 {@code long[5] = {l, t, r, b, ts}}，只在 UI 线程读写。
     */
    private static final java.util.List<long[]> WASH_RECTS = new java.util.ArrayList<>();

    /** {@link #WASH_RECTS} 的有效条数（无锁读，用于热路径快速闸）。 */
    private static volatile int sWashRectCount;

    /** 记下一个「刚罩过色」的封面矩形。 */
    private static void rememberWashRect(View v) {
        try {
            if (v == null || !v.isAttachedToWindow()) return;
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0) return;
            int[] loc = LOC.get();
            v.getLocationOnScreen(loc);
            long now = System.currentTimeMillis();
            long[] e = {loc[0], loc[1], loc[0] + w, loc[1] + h, now};
            synchronized (WASH_RECTS) {
                for (int i = WASH_RECTS.size() - 1; i >= 0; i--) {
                    if (now - WASH_RECTS.get(i)[4] > 1500L) WASH_RECTS.remove(i);
                }
                if (WASH_RECTS.size() >= 8) WASH_RECTS.remove(0);
                WASH_RECTS.add(e);
                sWashRectCount = WASH_RECTS.size();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * ★★★ v0.1.51（r51 用户第 1 条）：**在窗口根上把封面矩形兜一次底**。
     *
     * <p>前两轮分别在「图片层」和「外框层」叠罩色，日志都证明执行了
     * （`wash privacy` / `wash veil`），但屏幕上看不出变化 —— 说明上面还有东西
     * （硬件层 / 兄弟层 / 转场遮罩 / 动画）把它盖住了。
     *
     * <p>窗口根（DecorView）的 {@code draw} 收尾是**整帧最后一个能画的地方**：
     * 此时所有子 View、所有叠加层都画完了，在这里按屏幕矩形填色，谁也盖不掉。
     * 最多 8 个矩形，开销可忽略。
     */
    private static void paintWashRectsOnRoot(Canvas c, View root) {
        if (c == null || root == null || sWashRectCount == 0) return;
        try {
            int[] loc = LOC.get();
            root.getLocationOnScreen(loc);
            int ox = loc[0];
            int oy = loc[1];
            WASH_PAINT.setColor(AccentColor.washTint(WASH_VEIL_ALPHA));
            long now = System.currentTimeMillis();
            int n = 0;
            synchronized (WASH_RECTS) {
                for (int i = WASH_RECTS.size() - 1; i >= 0; i--) {
                    long[] e = WASH_RECTS.get(i);
                    if (now - e[4] > 1500L) {
                        WASH_RECTS.remove(i);
                        sWashRectCount = WASH_RECTS.size();
                        continue;
                    }
                    c.drawRect(e[0] - ox, e[1] - oy, e[2] - ox, e[3] - oy, WASH_PAINT);
                    n++;
                }
            }
            if (n > 0 && sVeilRootLogs < 2) {
                sVeilRootLogs++;
                log("wash veil root -> " + n + " rect(s) " + WASH_VEIL_ALPHA);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 「wash veil root」诊断日志条数。 */
    private static volatile int sVeilRootLogs;

    /** 「paint root bg」诊断日志条数（v0.1.59）。 */
    private static volatile int sRootBgLogs;

    /** 「cover alpha fix」诊断日志条数（v0.1.64，限 3 条）。 */
    private static volatile int sCoverFixLogs;

    /** 「play alpha rescue」诊断日志条数（v0.1.65，限 3 条）。 */
    private static volatile int sAlphaFixLogs;

    /** 上一次「播放页层级审计」的时间（v0.1.67，每 3 秒最多一次）。 */
    private static volatile long sAuditAt;

    /** 本次审计已输出行数（v0.1.67，限 70 行，防止刷爆日志）。 */
    private static volatile int sAuditLines;

    /** 最近一次铺「播放档满屏底」的时间（v0.1.63，窗口根兜底底板的信号源）。 */
    private static volatile long sPlayPaintedAt;

    /**
     * ★★★ v0.1.51：轻量失效 —— 只让根窗口重新合成一帧，<b>不递归 invalidate 每个子 View</b>。
     *
     * <p>{@code invalidateTree} 会把整棵树上几百个 View 的 RenderNode 全部作废，
     * 下一次绘制要全部重录一遍（RN 页面动辄上千个节点）—— 这就是「播放页弹出卡顿」
     * 和「底部导航切换卡顿」的来源。这里只 {@code postInvalidateOnAnimation()}，
     * 代价和普通重绘一致；限流到 400ms 一次。
     */
    private static void invalidateRootSoft(View v) {
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
    private static volatile long sSoftInvalidateAt;

    /** 这一层的屏幕矩形是否正好是某个刚罩过色的封面（±10px）。 */
    private static boolean isWashedRect(View v) {
        // 热路径闸：绝大多数时刻这里是 0，连锁都不进（每个 ViewGroup 每帧都会问一次）
        if (sWashRectCount == 0) return false;
        try {
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 4 || h <= 4) return false;
            int[] loc = LOC.get();
            v.getLocationOnScreen(loc);
            int l = loc[0];
            int t = loc[1];
            int r = l + w;
            int b = t + h;
            long now = System.currentTimeMillis();
            synchronized (WASH_RECTS) {
                for (int i = WASH_RECTS.size() - 1; i >= 0; i--) {
                    long[] e = WASH_RECTS.get(i);
                    if (now - e[4] > 1500L) {
                        WASH_RECTS.remove(i);
                        sWashRectCount = WASH_RECTS.size();
                        continue;
                    }
                    if (Math.abs(e[0] - l) <= 10 && Math.abs(e[1] - t) <= 10
                            && Math.abs(e[2] - r) <= 10 && Math.abs(e[3] - b) <= 10) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * {@link #BLUR_PENDING} 的<b>裸读数</b>：只查「这个 View 被下发了
     * blurRadius&gt;0」，不带 {@link #blurRadiusOf} 那道「必须铺满屏幕」的闸。
     */
    private static int rawBlurRadius(View v) {
        try {
            Long p = lookupBlur(v);
            if (p == null) {
                // ★★★ v0.1.47（r47 用户第 1 条）：**允许往上找 6 层**。
                //   {@code setBlurRadius} 是挂在 {@code ExpoImageViewWrapper} 上的，
                //   expo-image 真正出来画的可能是它里面的子 View ——
                //   记账记的是 wrapper，绘制的是子 View ⇒ 身份对不上，
                //   {@code privacyWash} 就永远拿不到 r（这是 0.1.46 依旧零命中的
                //   第二种可能；第一种是下面那条位图判据要解决的「压根没走 prop」）。
                Object up = v.getParent();
                for (int i = 0; i < 6 && up instanceof View; i++) {
                    p = lookupBlur((View) up);
                    if (p != null) break;
                    up = ((View) up).getParent();
                }
                if (p == null) return 0;
            }
            return (int) (p & 0xFF);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 单层查表（含过期清理）。 */
    private static Long lookupBlur(View v) {
        Long p = BLUR_PENDING.get(v);
        if (p == null) return null;
        if (System.currentTimeMillis() - (p >>> 8) > BLUR_PENDING_TTL_MS) {
            BLUR_PENDING.remove(v);
            return null;
        }
        return p;
    }

    /**
     * 这一层是不是「正在被宿主模糊的内容图」（防偷窥封面 / 模糊缩略图）。
     * 真正的罩色在宿主画完之后叠（见 {@code hookViewDraw} 末尾）。
     */
    /**
     * ★★★ v0.1.61（r61 用户决定）：**防偷窥罩色整块功能关闭，代码备份在
     * {@code BgPeel.java.privacy-bak}**。
     *
     * <p>关掉的原因（重要，下一轮接手时先看这里）：
     * <ol>
     *   <li>这块封面的紫色**不是图片自己带的**，而是宿主在其上再叠的一层半透明蒙层
     *       （真机截图里 6 个不同作品的封面呈现<b>同一个淡紫</b>）；
     *   <li>我们先后试过：图片层罩色、外框层罩色、窗口根罩色、直接不画宿主图
     *       —— 前三种看不见（被后画的层盖住），第四种会把正常封面吃掉；
     *   <li>而且它和「播放页背景」互相污染：播放窗口的 120 秒旗子会让媒体库封面
     *       变成壁纸碎片（r61 用户第 3 条），关掉是最干净的隔离。
     * </ol>
     *
     * <p>恢复方式：删掉下面这行 {@code return false;} 即可（其余代码原样保留）。
     */
    private static boolean privacyWash(View v) {
        return false;   // ← 防偷窥罩色已关闭（见上方注释与 .privacy-bak 备份）
    }

    /** 旧的判定主体，保留以便随时恢复（当前不被调用）。 */
    private static boolean privacyWashLegacy(View v) {
        if (!sArmed || !AccentColor.on()) return false;
        try {
            // ★★★ v0.1.47（r47 用户第 1 条）：**第二条证据 —— 位图本身就是糊的**。
            //
            //   0.1.46 在 {@code ExpoImageView#draw} 上补了罩色，真机依旧一次都不命中。
            //   既然钩子确实装上了（同一条路里 {@code blur bg r=10} 每轮都打），
            //   那就只剩一个可能：这些封面**根本没有走 expo-image 的 blurRadius prop**
            //   （宿主的防偷窥是直接下发一张预模糊图 / 用别的组件糊的），
            //   {@code BLUR_PENDING} 里没有它们的账 ⇒ 判据永远为 false。
            //
            //   所以补一条与 prop 无关的判据：**这张位图自己就是糊的**。
            //   真机标定（r47 录屏，经视频压缩 + 缩放之后）：模糊封面 lap≈2.9~7.5，
            //   清晰封面 lap≈46~52 —— 中间隔着 6 倍，阈值取 14 有充分余量
            //   （真位图没有视频压缩，只会拉得更大）。
            // ★★★ v0.1.52（r52 用户第 1 条）：**尺寸闸必须放在最前面**。
            //
            //   0.1.51 真机日志：整段会话 `wash privacy` <b>零条</b>，而录屏里明明有大片
            //   紫色封面块（占画面 25~34%）。原因是我在 0.1.51 给像素判据加了
            //   「每秒 8 次」的限流，而 {@code privacyWash} 是<b>对每一个 View</b>
            //   调用的（{@code View#draw} 里无条件调用）—— 整屏几百个 View
            //   一帧就把额度吃光，真正的封面永远排不上队 ⇒ 一次都判不出来。
            //
            //   修法：先用「宽 ≥12% 屏宽、高 ≥4% 屏高」这两个 int 比较把绝大多数
            //   View 挡掉（零成本、零分配），只有像封面的块才会走到像素判据，
            //   额度就再也不会被无关的 View 抢走。
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0 || v.getAlpha() < 0.05f) return false;
            if (w >= sFullW && h * 100 >= (long) sSH * 92) return false;
            if (w * 100 < (long) sSW * 12 || h * 100 < (long) sSH * 4) return false;
            // ★★★ v0.1.53（r53 用户第 4 条「诡异的蓝色色块」）：**播放页的层一律不罩**。
            //
            //   r52 日志：`16:28:38.777 wash privacy -> ExpoImageView 1272x954`
            //   —— 尺寸闸前置之后，播放页那条 34% 屏高的模糊横幅也被当成「防偷窥封面」
            //   罩上了 92% 强调色 ⇒ 播放页中间出现一大块纯蓝矩形（用户第 4 条），
            //   用户第 2 条那个「越来越混乱」的截图就是它。
            //   播放页的模糊层是**底**，正确做法是换成播放档壁纸
            //   （见 paintBlurBg 的 `paint play blur big`），而不是罩色。
            if (playContext(v)) return false;
            // 面积上限：单个色块不得超过屏幕的 25%（封面 1153x357 = 11.7%、
            // 610x458 = 7.7%；那条 1272x954 = 34% 会被这条挡掉）。
            if ((long) w * h > (long) sSW * sSH / 4) return false;

            int rr = rawBlurRadius(v);
            boolean byProp = rr >= IMG_BLUR;
            boolean byPixel = false;
            if (!byProp) {
                // ★★★ v0.1.51：按 View 缓存 + 限流（见 {@link #cachedPixelBlur}）。
                byPixel = cachedPixelBlur(v);
                if (!byPixel) return false;
            }
            if (sWashLogs < 3) {
                sWashLogs++;
                log("wash privacy -> " + name(v) + " " + w + "x" + h
                        + " r=" + rr + (byProp ? " prop" : " px"));
            }
            // ★★★ v0.1.49：把这块的屏幕矩形记下来 —— 外层要靠它对账，
            //   才能在「整帧画完之后」再罩一次整块（见 paintWashRectsOnRoot）。
            rememberWashRect(v);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

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
    private static final java.util.Map<Bitmap, Float> BLUR_SCORE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Bitmap, Float>());

    /** 低于该值视为「糊图」。 */
    // ★★★ v0.1.55（r55 用户第 4 条「蓝色色块」）：14 → **9**。
    //   真机标定：宿主防偷窥糊图 lap≈2.9~7.5；而「画风本身就柔」的作品封面
    //   会落在 10~20 这一段 —— 14 会把它们误判成防偷窥封面（于是出现蓝色块）。
    //   9 仍然对真糊图有 1.2 倍余量，同时把柔画风封面挡在外面。
    private static final float BLUR_SCORE_MAX = 9f;

    /** ★ v0.1.51：位图模糊判定的按 View 缓存（值 = {Drawable 身份, 结论, 时间戳}）。 */
    private static final java.util.Map<View, Object[]> PIXEL_BLUR =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Object[]>());

    /** 每秒最多真实计算几次（限流用）。 */
    private static volatile long sPixelSecAt;
    private static volatile int sPixelCount;

    /**
     * ★★★ v0.1.51：带缓存与限流的「位图是不是糊的」判定。
     *
     * <p>三条约束，缺一不可：
     * <ol>
     *   <li><b>按 View 缓存 2 秒</b> —— 只有 drawable 换了才重算；</li>
     *   <li><b>每秒最多 8 次真实计算</b> —— 整屏重画时不会把 UI 线程拖住；</li>
     *   <li>额度用完时**沿用上一次的结论**（而不是直接判 false），
     *       否则封面会在「罩 / 不罩」之间闪。</li>
     * </ol>
     */
    private static boolean cachedPixelBlur(View v) {
        try {
            Drawable d = v instanceof ImageView
                    ? ((ImageView) v).getDrawable() : v.getBackground();
            Object[] e = PIXEL_BLUR.get(v);
            long now = System.currentTimeMillis();
            if (e != null && e[0] == d && e[2] != null
                    && now - (Long) e[2] < 2000L) {
                return Boolean.TRUE.equals(e[1]);
            }
            // ★ v0.1.52：先取位图 —— 取不到就直接记 false 返回，
            //   **不消耗限流额度**（否则一堆没有位图的 View 会把额度吃光，
            //   这正是 0.1.51 里 `wash privacy` 零条的直接原因）。
            Bitmap b = privacyBitmapOf(v);
            if (b == null) {
                PIXEL_BLUR.put(v, new Object[]{d, Boolean.FALSE, now});
                return false;
            }
            if (!takePixelBudget()) {
                return e != null && Boolean.TRUE.equals(e[1]);
            }
            boolean ok = isBlurredBitmap(b);
            PIXEL_BLUR.put(v, new Object[]{d, ok, now});
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean takePixelBudget() {
        long now = System.currentTimeMillis();
        if (now - sPixelSecAt >= 1000L) {
            sPixelSecAt = now;
            sPixelCount = 0;
        }
        if (sPixelCount >= 8) return false;
        sPixelCount++;
        return true;
    }

    private static boolean isBlurredBitmap(Bitmap b) {
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
    private static Bitmap privacyBitmapOf(View v) {
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
    private static Bitmap deepBitmap(Drawable d, int depth) {
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
    private static float computeBlurScore(Bitmap src) {
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

    /** 防偷窥罩色命中日志条数（诊断预算 2 条）。 */
    private static volatile int sWashLogs;

    /**
     * 面板换底时的「重入闸门」（v0.1.24）。
     *
     * <p>面板要的效果是「模糊壁纸 + 宿主原本那层半透明白」的叠加，
     * 而宿主那层白的唯一来源就是它自己的 {@code draw()}。所以我们在钩子里画完模糊后
     * <b>再调一次 {@code d.draw(c)}</b> —— 必须有一个标志让那一次调用直接走原逻辑，
     * 否则就是无限递归。
     */
    private static final ThreadLocal<Boolean> REENTRY = new ThreadLocal<>();

    /** 已经加过阴影的 TextView → 上次用的半径（WeakHashMap 不会拖住 Activity）。 */
    private static final java.util.Map<TextView, Float> SHADOWED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** TextView → 是否坐在亮底上（只算一次；背景不变就一直有效）。 */
    private static final java.util.Map<TextView, Boolean> LIGHT_BG =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 位图 → 它属于哪种图（{@link #IMG_VEIL} / {@link #IMG_ATMOS} / 内容图）。 */
    private static final java.util.Map<Bitmap, Integer> VEIL_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * ★ v0.1.41：位图 → 平均 alpha（{@link #imgKind} 算过就顺手存一份）。
     *
     * <p>用户第 7 条「作品封面不显示」的判据就在这里：封面照片是<b>不透明</b>的
     * （avg ≈ 1.0，只是编码带 alpha 通道），而三张信息面板的底是<b>半透明紫</b>
     * （avg 0.6~0.9）。光看「有没有 alpha 通道」分不开它们 —— r12 日志里
     * {@code dialog glass -> ExpoImageView 1440x1080} 就是这么把封面铺成玻璃的。
     */
    private static final java.util.Map<Bitmap, Float> AVG_ALPHA =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 位图分类：内容图（不碰）。 */
    private static final int IMG_CONTENT = 0;

    /** 位图分类：半透明氛围叠加图（整层不画，露出壁纸）。 */
    private static final int IMG_VEIL = 1;

    /** 位图分类：不透明氛围底图（播放页 {@code playbackspbackground} 这种）。 */
    private static final int IMG_ATMOS = 2;

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
    private static final int IMG_BLUR = 3;

    /** expo-image 的 props 壳（{@code blurRadius} 只有它有，{@code ExpoImageView} 本身没有）。 */
    private static final String CLS_EXPO_IMAGE_WRAPPER = "expo.modules.image.ExpoImageViewWrapper";

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
    private static final java.util.Map<View, Long> BLUR_PENDING =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * 位图 → 宿主要求的模糊半径（v0.1.27）。
     *
     * <p>键是「这张位图」，不是「这个 View」—— 这才是「这张图是装饰底」的稳定身份。
     */
    private static final java.util.Map<Bitmap, Integer> BLUR_BMP =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static volatile Activity sPoll;
    private static volatile boolean sPolling;

    /** 钩子是否装上。没装上就完全没有效果。 */
    private static volatile boolean sHooked;

    /** ★ 热路径快速闸门：true = 有壁纸可画（{@link #sWpBmp} 非空）。 */
    private static volatile boolean sArmed;

    /** ★ 清晰壁纸位图（已含用户设置的遮罩 / 模糊）。 */
    private static volatile Bitmap sWpBmp;

    /** ★ 顶栏 / 底栏用的模糊壁纸位图（30% 模糊）。 */
    private static volatile Bitmap sWpBarBmp;

    /**
     * ★ 播放页用的壁纸位图（v0.1.21）：再按 {@link MoriConfig#f2PlayBlur} 糊一层。
     *
     * <p>播放页那张氛围底图是<b>不透明满屏</b>的，整层换成壁纸后如果还是清晰版，
     * 会盖住封面和歌词 ⇒ 单独一份更糊的位图专门给它用。
     */
    private static volatile Bitmap sWpPlayBmp;

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
    private static volatile int sPageBottom = -1;
    private static volatile Bitmap sPageBmp;

    /** 屏幕（DecorView）尺寸：所有层都换算到这套坐标系里画。 */
    private static volatile int sSW;
    private static volatile int sSH;

    /** 满屏门槛（根视图面积 × {@link #FULL_SCREEN_RATIO}），一次乘法比较。 */
    private static volatile long sMinArea = Long.MAX_VALUE;

    /** 顶栏 / 底栏的像素门槛（由 apply 按密度算好）。 */
    private static volatile int sEdgeTop;
    private static volatile int sEdgeBottom;
    private static volatile int sBarMaxH;
    private static volatile int sFullW;

    /** 上一帧是否真的把壁纸画上去过（只用于反馈 / 诊断）。 */
    private static volatile boolean sPainted;

    /** 需要重新判一次并让视图树重画。 */
    private static volatile boolean sDirty = true;

    private static int sLogs;
    private static long sSeen;
    private static long sSkipped;
    private static long sLeaf;
    private static long sBar;
    private static long sGrad;
    private static long sOverlay;
    private static long sScrim;
    private static long sVeil;
    private static long sAtmos;

    /** 「封面模糊底图换成壁纸+模糊」次数（v0.1.24）。 */
    private static long sBlurBg;
    private static long sSheet;
    /** 因「缩放转场」改画毛玻璃的层数（v0.1.23）。 */
    private static long sZoom;
    private static long sLastSummaryAt;

    /** 每次命中最多打几条「画在哪一层」的日志。 */

    /** 满屏层 / 顶底栏清单已打的行数。 */
    private static int sBigLogs;

    /** expo-image 位图分类已打的行数（v0.1.21，每个位图一次）。 */
    private static int sImgLogs;
    private static final int IMG_LOG_BUDGET = 10;

    /** 已经登记过的全宽图片层类名（v0.1.21 诊断）。 */
    private static final java.util.Set<String> sImgSeen =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** 已登记过的层类名（避免同一类刷屏）。 */
    private static final java.util.Set<String> sBigSeen =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private BgPeel() {
    }

    /** 装全部「把宿主满屏底原地换成壁纸 / 氛围图去紫 / 面板只糊自己」的钩子。 */
    public static void install(ClassLoader cl) {
        hookDrawable(cl, CLS_BG_DRAWABLE, true);
        hookDrawable(cl, CLS_BG_IMAGE, false);
        hookGradientView(cl);
        hookViewDraw(cl);
        hookExpoImage(cl);
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
        hookTextureView(cl);
        hookImageBlur(cl);
        // ★★★ v0.1.42（r13）：**宿主自带的毛玻璃层（expo-blur）必须一起关掉**。
        //   这一条是「为什么有的面板/页面能做上、有的做不上」的最后一个缺口。
        hookExpoBlur(cl);
        // ★★ v0.1.24 用户第 1 条：强调色的唯一挂点（ColorPropConverter + 渐变数组）。
        //   以前挂 TextView#setTextColor 只能改字，宿主满屏的紫色底 / 开关 / 遮罩全漏。
        AccentLut.install(cl);
        refreshMaskPaint();
    }

    /**
     * 压暗蒙层的颜色：跟着强调色走，<b>alpha 与宿主原设定完全一致</b>
     * （{@link FeatureF2#BAR_MASK_PCT}，8%）。
     */
    private static void refreshMaskPaint() {
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
                retintTree(root, 0);
                invalidateTree(root);
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
    private static int retintTree(View v, int depth) {
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
    private static void writeColor(Object d, int argb) {
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
    private static final java.util.Map<Class<?>, Object> COLOR_FIELD =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 「这个类找不到可用颜色字段」的哨兵（缓存下来，别每次都重新扫一遍）。 */
    private static final Object NONE = new Object();

    /**
     * 找这个类「存背景色的那个 int 字段」。
     *
     * <p>优先名字里带 color 的（没被混淆的情况）；否则要求<b>实例 int 字段恰好只有一个</b>
     * —— {@code BackgroundDrawable} 正是如此（{@code h : I}）。多于一个且都不带 color
     * 就放弃：宁可这块底色不跟着变，也不能把 {@code strokeWidth} 之类的数值当颜色写坏。
     */
    private static java.lang.reflect.Field colorField(Class<?> cls) {
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
    private static void hookDrawable(ClassLoader cl, String clsName, boolean requireOpaque) {
        try {
            Class<?> cls = XposedCompat.findClass(clsName, cl);
            Method draw;
            try {
                draw = cls.getDeclaredMethod("draw", Canvas.class);
            } catch (NoSuchMethodException e) {
                log(clsName + " 没有 draw(Canvas)");
                return;
            }
            draw.setAccessible(true);
            XposedCompat.hookMethod(draw, chain -> {
                try {
                    Object self = chain.getThisObject();
                    Object arg0 = chain.getArg(0);
                    if (self instanceof Drawable && arg0 instanceof Canvas) {
                        // ★★★ v0.1.41（r12 路径 A）：**玻璃表面只换颜料，不接管绘制**。
                        //   命中 ⇒ 把宿主那支 Paint 的 shader 换成模糊壁纸，
                        //   然后照常 chain.proceed() —— 圆角、位置、裁剪全由宿主自己完成。
                        //   不跳过绘制 ⇒ 结构上不可能再出现「面板整块消失」。
                        GlassPaint.Token tk = glassToken((Drawable) self);
                        if (tk != null) {
                            try {
                                return chain.proceed();
                            } finally {
                                GlassPaint.end(tk);
                            }
                        }
                        if (paintWallpaper((Drawable) self, (Canvas) arg0, requireOpaque)) {
                            return null;   // 已经画好壁纸，宿主原本的纯色/渐变不画
                        }
                    }
                } catch (Throwable ignored) {
                    // 判定/绘制失败就原样画：宁可没效果，也绝不能白屏
                }
                // ⚠️ v0.1.24：这里**不再**反射改 drawable 的背景色字段。
                //   v0.1.23 那样做会「染两次」—— 宿主这块底的颜色本来就出自
                //   ColorPropConverter，LUT（AccentLut）已经把它换成强调色了，
                //   再按 LCh 转一遍色相就是二次映射（颜色会一路漂移）。
                return chain.proceed();
            });
            sHooked = true;
            log("hooked 1 " + clsName + "#draw");
        } catch (Throwable t) {
            log("钩子未装 " + clsName + ": " + t);
        }
    }

    /**
     * ★ v0.1.19 新增：钩 {@code expo-linear-gradient} 的 {@code onDraw}。
     *
     * <h3>为什么必须钩 onDraw，而不是 View#draw</h3>
     * 播放页的紫色渐变<b>带着子内容</b>（封面、进度条、按钮全在里面）。用
     * {@code View#draw} 只能「整层不画」，等于把内容一起丢了（v0.1.17 的血证）。
     * 而 {@code onDraw} 只负责画<b>渐变本身</b>，子 View 由 {@code dispatchDraw} 画 ——
     * 把它拦掉，渐变没了、内容一个不少。
     *
     * <p>判据只看「铺满宽度」（≥94% 屏宽）：卡片、按钮、进度条的渐变都够不到。
     */
    private static void hookGradientView(ClassLoader cl) {
        try {
            Class<?> cls = XposedCompat.findClass(CLS_GRADIENT_VIEW, cl);
            Method onDraw;
            try {
                onDraw = cls.getDeclaredMethod("onDraw", Canvas.class);
            } catch (NoSuchMethodException e) {
                log(CLS_GRADIENT_VIEW + " 没有 onDraw(Canvas)");
                return;
            }
            onDraw.setAccessible(true);
            XposedCompat.hookMethod(onDraw, chain -> {
                try {
                    Object self = chain.getThisObject();
                    Object arg0 = chain.getArg(0);
                    if (self instanceof View && arg0 instanceof Canvas
                            && dropGradient((View) self, (Canvas) arg0)) {
                        return null;   // 渐变换成壁纸，子内容照常由 dispatchDraw 画
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            sHooked = true;
            log("hooked 1 " + CLS_GRADIENT_VIEW + "#onDraw（渐变层原地换壁纸）");
        } catch (Throwable t) {
            log("钩子未装 " + CLS_GRADIENT_VIEW + ": " + t);
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
    private static void hookViewDraw(ClassLoader cl) {
        try {
            Class<?> viewCls = XposedCompat.findClass("android.view.View", cl);
            Method draw = viewCls.getDeclaredMethod("draw", Canvas.class);
            draw.setAccessible(true);
            XposedCompat.hookMethod(draw, chain -> {
                // ★★★ v0.1.44：这三个要在 try 外面声明 —— 防偷窥的强调色罩
                //   必须在 chain.proceed() **之后**叠加，那时已经出了 try 块。
                View v = null;
                Canvas cv = null;
                boolean wash = false;
                // ★★★ v0.1.49（r49 用户第 1 条）：封面<b>整块</b>罩色标记，见下面的收尾。
                boolean veil = false;
                /** 这一层是窗口根（DecorView）⇒ 收尾时把封面矩形兜一次底。 */
                boolean veilRoot = false;
                try {
                    Object self = chain.getThisObject();
                    Object arg0 = chain.getArg(0);
                    v = self instanceof View ? (View) self : null;
                    cv = arg0 instanceof Canvas ? (Canvas) arg0 : null;
                    // ★★★ v0.1.44（r15 用户第 6 条）：防偷窥封面块记下来，
                    //   等**宿主画完**再叠强调色罩（见 privacyWash）。
                    wash = v != null && cv != null && privacyWash(v);
                    // ★★★ v0.1.49：**封面外框那一层也要罩**（用户提议「直接替换掉宿主叠加的蒙层」）。
                    //   真机日志把结构打得很清楚：
                    //   <pre>
                    //   surface ReactViewGroup/1153x357 ... c=#1A52A2FF
                    //     └ ExpoImageView 1153x357        ← 模糊封面（wash 罩在它身上）
                    //     └ LottieAnimationView 654x654   ← 在它之后画
                    //   </pre>
                    //   只在图片自己身上叠，等于把罩色塞进「更早的那一层」，
                    //   宿主随后画的蒙层 / 动画 / 兄弟层都会盖回来 ⇒ 看起来一点没变。
                    //   所以按<b>屏幕矩形</b>认出封面外框，在它整个画完之后再罩一次整块。
                    veil = v instanceof ViewGroup && cv != null && isWashedRect((View) v);
                    // ★★★ v0.1.51（r51 用户第 1 条）：**整棵树画完之后，在根上兜一次底**。
                    //   前两轮把罩色画在「图片层」「外框层」上，日志都证明执行了
                    //   （`wash privacy` / `wash veil`），但屏幕上看不出变化 ——
                    //   说明在它之上还有东西（硬件层 / 兄弟层 / 转场遮罩）把它盖住。
                    //   根 View 的 draw 收尾是**整帧最后一个能画的地方**，
                    //   在这里按屏幕矩形直接罩，无论宿主怎么叠都盖不掉。
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
                    if (veilRoot && sArmed
                            && (System.currentTimeMillis() - sPlayPaintedAt < 1000L)) {
                        try {
                            Bitmap pb = playBmp();
                            if (pb != null && !pb.isRecycled()) {
                                WASH_PAINT.setColor(0xFFFFFFFF);
                                WASH_PAINT.setAlpha(255);
                                WASH_PAINT.setFilterBitmap(true);
                                cv.drawBitmap(pb, null,
                                        new Rect(0, 0, v.getWidth(), v.getHeight()),
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
                        notePlaySeek(v);
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
                                ensureShadow((TextView) v);
                                // ★★★ v0.1.43（r14 用户第 1 条）：绘制前再逼一次小字强调色。
                                //   底栏 / 白按钮里的字常常是 ColorStateList 设一次就再也不走
                                //   setTextColor —— 换壁纸 / 换风格后 Paint 里仍是宿主紫。
                                forceAccentSmallText((TextView) v);
                            }
                        } else if (rescueCover(v)) {
                            // ★★★ v0.1.40（r45 用户第 7 条）：**封面这条路绝不接管绘制**。
                            //   以前这里还会走 swapLeaf / paintSheetPanel —— 而
                            //   `swapLeaf` 对「大块图片层」会走 `paintAtmos` / `paintPlayFallback`，
                            //   于是**作品封面被替换成播放页的背景图**，
                            //   用户看到的正是「作品封面显示区域是没加模糊的自定义背景」，
                            //   再到这一轮就变成「封面死都不显示」。
                            //   封面是内容图，唯一该做的事就是把 alpha 修回 1。
                            noteCoverKept();
                        } else if (swapLeaf(v, cv)) {
                            return null;   // 壁纸已经画在这块位置上，宿主原来那层不画
                        } else {
                            // 面板容器（有子 View 的底部面板）：只把**面板这一块矩形**
                            // 涂成「背景 + 高斯模糊」，子内容照常由宿主画在上面。
                            paintSheetPanel(v, cv);
                        }
                    }
                } catch (Throwable ignored) {
                }
                Object r = chain.proceed();
                // ★★★ v0.1.44：防偷窥封面的强调色罩必须在**宿主画完之后**叠上去，
                //   否则会被图片本身盖掉（这正是它要罩的对象）。
                // ★★★ v0.1.48（r48 用户第 1 条）：换成 {@link AccentColor#washTint}。
                //   旧代码用的 {@code shadow(48)} 是**文字阴影色**（LCh 的 L 固定 18，
                //   近似黑），19% 叠在浅紫封面上只压暗不变色 ⇒ 看着「依旧是原来的紫色」。
                //   真机日志已证明判据没问题（`wash privacy -> ExpoImageView 1153x357 r=0 px`），
                //   所以这一轮改的是「染成什么色」：中亮度 + 足彩度 + 40% 不透明度。
                if (wash) {
                    try {
                        WASH_PAINT.setColor(AccentColor.washTint(WASH_ALPHA));
                        cv.drawRect(0, 0, v.getWidth(), v.getHeight(), WASH_PAINT);
                    } catch (Throwable ignored) {
                    }
                }
                // ★★★ v0.1.49：封面外框整块罩（画在**所有子层之后**，
                //   包含宿主后来叠的蒙层 / Lottie / 灰条，一次性盖掉）。
                if (veil) {
                    try {
                        WASH_PAINT.setColor(AccentColor.washTint(WASH_VEIL_ALPHA));
                        cv.drawRect(0, 0, v.getWidth(), v.getHeight(), WASH_PAINT);
                        if (sVeilLogs < 2) {
                            sVeilLogs++;
                            log("wash veil -> " + name(v) + " " + v.getWidth() + "x" + v.getHeight());
                        }
                    } catch (Throwable ignored) {
                    }
                }
                // ★★★ v0.1.51：窗口根兜底 —— 整帧最后一个能画的位置，谁也盖不掉。
                if (veilRoot) {
                    paintWashRectsOnRoot(cv, v);
                }
                return r;
            });
            sHooked = true;
            log("hooked 1 android.view.View#draw");
        } catch (Throwable t) {
            log("钩子未装 android.view.View#draw: " + t);
        }
    }

    /**
     * ★★ v0.1.20 核心新增：钩 {@code expo.modules.image.ExpoImageView#draw}。
     *
     * <h3>为什么前面六版都打不中 PurePlay 的紫遮罩 / 播放页的紫色底</h3>
     * 报告《PurePlay 页面背景机制》逐字还原了 bundle：那一层是
     * <pre>
     * &lt;Image source={LOCALBG} contentFit="fill" pointerEvents="none"
     *        style={[styles.backgroundOverlayImage, {width: W, height: W * 1.1795}]} /&gt;
     * </pre>
     * 也就是<b>一张普通内容图</b>（{@code assets_images_localbg.webp}，780×920 RGBA，
     * 自上而下紫→透明的 alpha 全烘在位图里）。它<b>不是</b> background、不走 Resources 取图、
     * 更不是渐变 View ⇒ 我们对 background drawable / 资源 / 渐变的全部钩子都碰不到它。
     *
     * <p>播放页的 {@code playbackspbackground}（1081×1920 RGBA）是同一套写法，
     * 这就是「播放页自定义背景不生效」的同一个病。
     *
     * <h3>判据（不依赖比例魔数）</h3>
     * <ol>
     *   <li>满宽（≥94% 屏宽）+ 高度 ≥18% 屏高 —— 排除图标 / 头像 / 封面；</li>
     *   <li>贴屏幕顶 或 满屏 —— 排除列表中间的卡片图；</li>
     *   <li><b>位图有 alpha 通道 + 采样平均 alpha &lt; 0.92</b> —— 这是报告 §4 说的
     *       「RGBA + 大面积 alpha」共同特征。{@code main_visual}（主页立绘）是 <b>RGB</b>，
     *       直接在这里被排除，绝不会像 r23 那样把整屏内容杀掉。</li>
     * </ol>
     *
     * <p>命中 ⇒ 整层不画（{@code return null}）。它本来就是「盖在壁纸上的氛围层」，
     * 不画 = 干净透出壁纸；最坏情况也只是露出宿主原本的底色，不会白屏、不会丢内容。
     */
    private static void hookExpoImage(ClassLoader cl) {
        try {
            Class<?> cls = XposedCompat.findClass(CLS_EXPO_IMAGE, cl);
            Method draw;
            try {
                draw = cls.getDeclaredMethod("draw", Canvas.class);
            } catch (NoSuchMethodException e) {
                log(CLS_EXPO_IMAGE + " 没有 draw(Canvas)");
                return;
            }
            draw.setAccessible(true);
            XposedCompat.hookMethod(draw, chain -> {
                // ★★★ v0.1.46（r46 用户第 2 条）：防偷窥罩色的变量必须在 try 外声明 ——
                //   罩色要等宿主**画完**再叠（和 hookViewDraw 里的写法一致）。
                View v = null;
                Canvas cv = null;
                boolean wash = false;
                try {
                    Object self = chain.getThisObject();
                    Object arg0 = chain.getArg(0);
                    if (self instanceof View) {
                        v = (View) self;
                        cv = arg0 instanceof Canvas ? (Canvas) arg0 : null;
                        // ★★★ v0.1.46：**防偷窥封面就是 ExpoImageView**。
                        //   r46 真机日志：整轮 `wash privacy` 一条都没有，但
                        //   `sheet miss ExpoImageView 655x491` 反复出现 —— 罩色判据
                        //   （privacyWash）只挂在 {@code View#draw} 钩子里，而
                        //   ExpoImageView **自己重写了 draw(Canvas)**，虚分派永远走不到
                        //   View.draw ⇒ 罩色这条路一次都没机会执行。
                        //   这里在宿主画完之后叠同一层强调色罩，与 hookViewDraw 等效。
                        wash = cv != null && privacyWash(v);
                        // ★★★ v0.1.56（r56 用户第 3② 条「封面怎么样都不显示了，关了防偷窥也一样」）：
                        //   **撤销 v0.1.55 的「不画宿主图」**。那条路只要误判一次，
                        //   正常封面就永久消失（用户实测：关掉防偷窥也看不到封面）。
                        //   改回「宿主照画 + 我们在上面叠色」，误判最坏只是颜色偏一点，
                        //   绝不会把内容吃掉。
                        // ① 宿主的「封面模糊底图」（blurRadius > 0）：换成 壁纸+用户模糊档
                        if (cv != null && paintBlurBg(v, cv)) {
                            wash = false;
                            return null;
                        }
                        // ★★★ v0.1.37：弹层里的大块图片底（半透明紫面板）→ 先铺玻璃，
                        //   否则那层紫会把底下已经铺好的玻璃重新盖掉（见 paintDialogGlass）。
                        // ★★★ v0.1.41：改成「局部铺玻璃 + 把宿主那张紫图按低透明度叠回」，
                        //   坐标一律用本层尺寸（不再用整屏位图对齐，见 paintLocal）。
                        if (cv != null && paintDialogGlass(v, cv, chain::proceed)) {
                            return null;
                        }
                        // ② 半透明氛围叠加图（PurePlay 顶部紫遮罩）：换成毛玻璃
                        if (cv != null && dropVeil(v, cv)) {
                            wash = false;
                            return null;
                        }
                        // ③ 不透明氛围底图（播放页 playbackspbackground）：换成本地壁纸（可按档位糊）
                        if (cv != null && paintAtmos(v, cv)) {
                            wash = false;
                            return null;
                        }
                    }
                } catch (Throwable ignored) {
                }
                Object r = chain.proceed();
                // ★★★ v0.1.46：宿主画完之后再叠强调色罩（罩的就是刚画上去的那张模糊图）
                // ★★★ v0.1.48：染色见 {@link AccentColor#washTint} 的注释
                //   （旧代码的 {@code shadow(48)} 是近黑的阴影色，只会压暗不变色相）。
                if (wash) {
                    try {
                        WASH_PAINT.setColor(AccentColor.washTint(WASH_ALPHA));
                        cv.drawRect(0, 0, v.getWidth(), v.getHeight(), WASH_PAINT);
                    } catch (Throwable ignored) {
                    }
                }
                return r;
            });
            sHooked = true;
            log("hooked 1 " + CLS_EXPO_IMAGE
                    + "#draw（模糊底图→壁纸+模糊 / 半透明→毛玻璃 / 不透明→换壁纸）");
        } catch (Throwable t) {
            log("钩子未装 " + CLS_EXPO_IMAGE + ": " + t);
        }
    }

    /**
     * ★★ v0.1.24 用户第 3 条：钩 {@code ExpoImageViewWrapper#setBlurRadius}。
     *
     * <p>宿主的「封面模糊背景」不是我们能从位图上认出来的（它就是一张普通照片，
     * 只是被 Glide 的模糊 transformation 预先糊过），唯一可靠的标志是<b>那个 prop</b>。
     * 这里在 setter 上把 {@code ExpoImageView} 记进 {@link #BLURRED}，
     * 绘制时 {@link #imgKind} 查到就直接判 {@link #IMG_BLUR}。
     *
     * <p>setBlurRadius 在 prop 更新时调用（不是每帧），成本可忽略。
     */
    /**
     * ★★★ v0.1.57（r57 真机层级 dump 定位）：<b>Skia 画布层</b>。
     *
     * <h3>为什么前六版全都没打中</h3>
     * 播放页那块「纯模糊占位 / 裁成几块 / 透明」的层，层级 dump 里长这样：
     * <pre>
     * ReactViewGroup   0,0-1272,954  #8b8
     * SkiaPictureView  0,0-1272,954  #25d0      ← react-native-skia
     * SkiaTextureView  0,0-1272,954
     * ReactViewGroup   0,0-1272,954  #878  alpha=0.0
     * </pre>
     * 它是 {@code TextureView}：内容由 Skia 在<b>自己的渲染线程</b>画进 SurfaceTexture，
     * 再由合成器直接贴上来 —— <b>根本不走 {@code View#draw}</b>。
     * 所以挂在 draw / blurRadius / 位图 / 几何上的判据对它一次都无效
     * （日志里 `paint play blur big` 始终 0 条就是这个原因）。
     *
     * <h3>唯一能拦住它的地方</h3>
     * {@code TextureView#draw(Canvas)} —— 父层的 dispatchDraw 会调它把纹理贴上去。
     * 在这里<b>铺我们的壁纸并 return null</b>，等于把它盖掉；
     * 而且按屏幕对齐，和下面各层同源同位，不会出现接缝。
     *
     * <p>适用范围收得很紧：只在「播放页 / 播放页窗口的根级层」上动手，
     * 尺寸要求 ≥55% 屏宽 且 ≥18% 屏高；其他页面（含列表页封面）一根手指都不碰。
     */
    private static void hookTextureView(ClassLoader cl) {
        try {
            Class<?> cls = XposedCompat.findClass("android.view.TextureView", cl);
            Method draw = cls.getDeclaredMethod("draw", Canvas.class);
            draw.setAccessible(true);
            XposedCompat.hookMethod(draw, chain -> {
                try {
                    Object self = chain.getThisObject();
                    Object a0 = chain.getArg(0);
                    if (sArmed && self instanceof View && a0 instanceof Canvas) {
                        View v = (View) self;
                        Canvas c = (Canvas) a0;
                        int w = v.getWidth();
                        int h = v.getHeight();
                        if (w > 0 && h > 0
                                && w * 100 >= (long) sSW * 40
                                && h * 100 >= (long) sSH * 12
                                && v.getAlpha() >= 0.05f
                                // ★★★ v0.1.58（r58 adb 实测）：这个宿主的播放页背景是一张
                                //   **满屏 SkiaTextureView（1272x2772）**。
                                //   满屏级的 Skia 画布按定义就是「底」，所以这一条
                                //   **不再依赖播放页判定** —— 只要它铺满整屏就直接接管。
                                //   这样「迷你条往返后透明」也不会再漏：那一屏里
                                //   playContext 常常还没成立，但 1272x2772 这张纹理在。
                                && (playContext(v) || isPlayWindow(v)
                                    || (w * 100 >= (long) sSW * 90 && h * 100 >= (long) sSH * 90))) {
                            // ★★★ v0.1.58（r58 用户第 1 / 3 条）：**改「整屏贴图」，并且必有兜底**。
                            //
                            //   · 用户第 1 条「封面上方那条是不带模糊的自定义背景底部」=
                            //     位图对齐偏了。`paintAligned` 依赖 `coverRect` 的中心裁切，
                            //     一旦这块纹理的画布原点/裁剪和屏幕不完全一致就整体错位。
                            //     这里改成最稳的写法：**把整张壁纸按「屏幕」映射到本地坐标**
                            //     （dst = -loc .. -loc+屏幕尺寸），不依赖任何中心裁切。
                            //   · 用户第 3 条「背景依旧透明」= 这块纹理没画、我们也没铺 ⇒
                            //     透过播放页看到了下层的作品详情页。所以**无论取不取到位图，
                            //     都至少填一层不透明色**，绝不留洞。
                            int[] loc = LOC.get();
                            v.getLocationOnScreen(loc);
                            Rect dst = new Rect(-loc[0], -loc[1],
                                    -loc[0] + sSW, -loc[1] + sSH);
                            // ★★★ v0.1.60：满屏 Skia 画布**总是接管**（否则宿主那层会露出来），
                            //   但用哪张底图要按「这一层是不是播放页」来选 ——
                            //   列表页/媒体库用清晰壁纸，播放页用播放档（壁纸+模糊）。
                            //   否则就会出现「媒体库封面变成播放档壁纸的模糊色块」（用户第 3 条）。
                            // ★★★ v0.1.62（r62 adb 层级实测）：**接缝的根因找到了**。
                            //
                            //   播放页上其实<b>三层叠着</b>（层级 dump 顺序 = 绘制顺序）：
                            //   <pre>
                            //   SkiaTextureView  1272x2772   ← 满屏底
                            //   ExpoImageView    1272x2913   ← 满屏模糊底（走 paintBlurBg）
                            //   SkiaTextureView  1272x954    ← 顶部横幅，画在最上面！
                            //   </pre>
                            //   原先这里按 {@code playContext(v)} 选底图 —— 而这块顶部横幅
                            //   是**窗口根级**的 Skia 层，逐层判定经常是 false，
                            //   于是它铺的是<b>清晰壁纸</b>，下面两层铺的是<b>播放档（壁纸+模糊）</b>
                            //   ⇒ 顶部那块和主体「不是同一张、模糊度也不同」= 用户说的
                            //   「另一截壁纸 / 越来越多色彩切片」。
                            //   现在：只要这个窗口是播放页窗口（2.5 秒旗子），一律用播放档，
                            //   三层同源同位，接缝自然消失。
                            boolean onPlay = playContext(v) || isPlayWindow(v);
                            Bitmap bmp = onPlay ? playBmp() : sWpBmp;
                            if (bmp != null && !bmp.isRecycled()) {
                                WASH_PAINT.setColor(0xFFFFFFFF);
                                WASH_PAINT.setAlpha(255);
                                WASH_PAINT.setFilterBitmap(true);
                                c.drawBitmap(bmp, null, dst, WASH_PAINT);
                            } else {
                                c.drawColor(0xFF14203A);   // 兜底：绝不留透明洞
                            }
                            sBar++;
                            sPainted = true;
                            hit("paint texture -> ", v, dst);
                            return null;   // 宿主这层 Skia 纹理不画
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            log("hooked TextureView#draw（Skia 渲染层 → 播放页换成壁纸）");
        } catch (Throwable t) {
            log("钩子未装 TextureView#draw: " + t);
        }
    }

    private static void hookImageBlur(ClassLoader cl) {
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
    private static final String CLS_EXPO_BLUR = "expo.modules.blur.ExpoBlurView";
    /** eightbitlab 的 BlurView：expo-blur 在 Android 上的真正实现。 */
    private static final String CLS_LIB_BLUR = "eightbitlab.com.blurview.BlurView";

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
    private static void hookExpoBlur(ClassLoader cl) {
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
                                hit("drop blurview -> ", v,
                                        new Rect(0, 0, v.getWidth(), v.getHeight()));
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
                            hideBlurDescendants(chain.getThisObject(), inners);
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
                        hideBlurDescendants(chain.getThisObject(), inners);
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

    /**
     * 把 {@code ExpoBlurView} 里所有毛玻璃子层置 INVISIBLE。
     *
     * <p>v0.1.43：不只靠一个反射字段 —— 同时扫字段 + 直接遍历子 View，
     * 并清 {@code RenderEffect}（API 31+）。
     */
    private static void hideBlurDescendants(Object self,
                                            java.util.List<java.lang.reflect.Field> fields) {
        if (!sArmed || self == null) return;
        if (fields != null) {
            for (java.lang.reflect.Field f : fields) {
                try {
                    Object o = f.get(self);
                    if (o instanceof View) muteBlurView((View) o);
                } catch (Throwable ignored) {
                }
            }
        }
        if (self instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) self;
            try {
                int n = vg.getChildCount();
                for (int i = 0; i < n; i++) {
                    View c = vg.getChildAt(i);
                    if (c == null) continue;
                    String cn;
                    try {
                        cn = c.getClass().getName();
                    } catch (Throwable t) {
                        continue;
                    }
                    if (cn.contains("blurview.BlurView") || cn.endsWith(".BlurView")) {
                        muteBlurView(c);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 单层：INVISIBLE + 清 RenderEffect + alpha=0（三保险）。 */
    private static void muteBlurView(View v) {
        if (v == null) return;
        try {
            boolean changed = false;
            if (v.getVisibility() != View.INVISIBLE) {
                v.setVisibility(View.INVISIBLE);
                changed = true;
            }
            if (v.getAlpha() > 0.01f) {
                v.setAlpha(0f);
                changed = true;
            }
            try {
                Method sre = View.class.getMethod("setRenderEffect",
                        android.graphics.RenderEffect.class);
                sre.invoke(v, new Object[]{null});
            } catch (Throwable ignored) {
            }
            if (changed) {
                log("blur layer hidden -> " + name(v) + " " + v.getWidth() + "x" + v.getHeight());
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把「宿主的封面模糊底图」换成「自定义壁纸 + 用户设定的模糊」（{@link MoriConfig#f2PlayBlur}）。
     *
     * <p>为什么整层换掉而不是叠一层：这张图在 bundle 里就是
     * {@code <ArtworkPrivacyImage contentFit="cover" blurRadius={10}>}，铺满整屏、位于内容之下，
     * 属于「底色」而不是内容。换掉它的效果<b>恰好等于</b>用户要的那句
     * 「固定为自定义背景再叠加模糊」，而且从此与「有没有封面」「是否隐私模式」彻底无关。
     */
    private static boolean paintBlurBg(View v, Canvas c) {
        if (!sArmed) return false;
        if (sSW <= 0 || sSH <= 0) return false;
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
        boolean playEarly = playContext(v);
        if (!isScreenFillDecor(v)) {
            // ★★★ v0.1.50（r50 用户第 2 / 3 条）：判据不能只看 blurRadius prop。
            //   r49 版这条分支<b>一次都没进</b>（日志里 `paint play blur big` 零条）——
            //   因为宿主这块横幅压根没设 blurRadius（和防偷窥封面一样，r=0），
            //   它是「直接下发一张糊好的图」。所以补上像素证据：
            //   <b>位图自己就是糊的 ⇒ 它是底，不是内容</b>。
            //   清晰的作品封面（评分≈46+）永远不会被这条命中。
            //   ★★★ v0.1.52：再加一条「窗口级」入口 —— `isPlayWindow(v) && 不在 Screen 里`。
            //   这个宿主的播放页摊在窗口根上（inScr=0），而列表页都在 Screen 里
            //   ⇒ 用「窗口是播放页窗口 + 这一层不在 Screen 内」就能精确覆盖播放页的
            //   根级横幅，又绝不会误伤列表页（r52 日志里那块 1272x954 就是根级的）。
            //   ★★★ v0.1.54（r54 adb 实测）：真机截图证明播放页顶部还留着<b>一整块</b>
            //   宿主的糊图（满宽 × ≈38% 屏高，正是那块 1272x954 的横幅），
            //   而位图判据对它取不到位图（`privacyBitmapOf` 返回 null）⇒ 上一条没进。
            //   于是再加一条**纯几何**兜底：播放页 / 播放页窗口里、
            //   <b>贴页顶 + 满宽 + ≥18% 屏高</b> 的层一定是「背景横幅」而不是内容
            //   （作品封面是 contain 居中，永远不贴页顶）。
            boolean topBanner = topOf(v) <= (int) (sSH * 0.06f);
            boolean bigBlur = (playEarly || (isPlayWindow(v) && screenOf(v) == null))
                    && w * 100 >= (long) sSW * 55 && h * 100 >= (long) sSH * 18
                    && (blurRadiusOf(v) >= IMG_BLUR
                        || isBlurredBitmap(privacyBitmapOf(v))
                        || topBanner);
            if (bigBlur) {
                Bitmap pb = playBmp();
                if (pb != null && paintAligned(c, pb, v, new Rect(0, 0, w, h), false)) {
                    sBar++;
                    sPainted = true;
                    hit("paint play blur big -> ", v, new Rect(0, 0, w, h));
                    return true;
                }
            }
            return false;
        }
        int radius = blurRadiusOf(v);
        int kind = imgKind(v);
        // 「铺满整屏 + 贴顶」就是页面背景（不管宿主有没有给 blurRadius）。
        boolean fillDecor = isScreenFillDecor(v) && topOf(v) <= 0;
        // ★★★ v0.1.38（r43 用户第 2 条）：**满屏底图一律归这里管，绝不放行**。
        //   r43 日志里同一个 {@code ExpoImageView 1272x2772} 有的帧走
        //   `blur bg r=10 ->play`，有的帧走 `paint veil clear`（dropVeil 那条），
        //   于是用户看到「有时正常、有时背景透明/模糊不生效」。
        //   现在只要「满宽 + 贴顶 + ≥92% 屏高」就一定是页面底图：
        //   播放页铺播放档，其余页铺清晰壁纸 —— <b>任何情况下都不会让这层回到宿主手里</b>。
        boolean fullBg = fillDecor && h * 100 >= (long) sSH * 92;
        if (kind != IMG_BLUR && !fullBg) {
            // veil / atmos 各有更精确的分支，这里不抢；
            // 只有「读不出像素的满屏底图」才兜底（r40 里 radius 时有时无，源头就是它）。
            if (kind != IMG_CONTENT || radius > 0 || !fillDecor || insideScreen(v)) return false;
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
        boolean play = playContext(v) || isPlayWindow(v);
        if (play) {
            markPlayWindow(v);
            // ★★★ v0.1.63：**记录「这一秒刚铺过播放档底图」**。
            //   窗口根的兜底底板用它当信号 —— 不再依赖 playContext/isPlayWindow
            //   在根那一层能不能判出来（实测那两条在根上经常都为 false，
            //   所以 0.1.59 的 `paint root bg` 一次都没打出来）。
            sPlayPaintedAt = System.currentTimeMillis();
            // v0.1.67：播放页被认出来 ⇒ 顺手把层级写进日志（每 3 秒最多一次）
            auditPlayTree((View) v);
        }
        Bitmap bmp = play ? playBmp() : sWpBmp;
        if (bmp == null) bmp = play ? sWpBarBmp : sWpBarBmp;
        if (bmp == null) return false;
        Rect r = new Rect(0, 0, w, h);
        if (!paintAligned(c, bmp, v, r, false)) return false;
        // ★★ v0.1.27 用户第 6 条：把「这个半径对应到哪个页面」留痕。
        //   ★★ v0.1.28 用户第 1 / 2 条：**每一张模糊底都记**（不再只记播放档）。
        //   下一轮日志里 `blur bg r=? ->play/clear` 就是判别「播放页 / 图片浏览 / 列表详情」
        //   的真凭实据：同一个 r 值出现在图片浏览里，就说明宿主用的是同一个组件，
        //   得换别的判据（不能只靠半径分档）。
        if (sBlurLogs < BLUR_LOG_BUDGET) {
            sBlurLogs++;
            log("blur bg r=" + radius + " " + w + "x" + h + " top=" + topOf(v)
                    + " pg=" + (play ? 1 : 0)
                    + (play ? " ->play" : " ->clear"));
        }
        notePageBackdrop(v, h, bmp);
        sBlurBg++;
        sPainted = true;
        hit(play ? "paint play blur -> " : "paint cover clear -> ", v, r);
        return true;
    }

    /** 「窗口里够大但不是内容根」的块登记条数（v0.1.28 诊断）。 */
    private static volatile int sMidLogs;
    private static final int MID_LOG_BUDGET = 6;

    /** 模糊底分类日志条数（v0.1.28）。 */
    private static volatile int sBlurLogs;
    private static final int BLUR_LOG_BUDGET = 8;

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
    private static boolean paintSheetPanel(View v, Canvas c) {
        if (!sArmed) return false;
        if (sSW <= 0 || sSH <= 0) return false;
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
        if (sMidLogs < MID_LOG_BUDGET && h * 4 > sSH && w * 2 > sSW
                && !inMainWindow(v)) {
            try {
                Activity sa = sPoll;
                View ad = sa == null ? null : decorOf(sa);
                View rt = v.getRootView();
                if (rt != null && ad != null && rt != ad) {
                    sMidLogs++;
                    log("mid block " + name(v) + " " + w + "x" + h
                            + " top=" + topOf(v) + " root=" + rt.getWidth() + "x" + rt.getHeight()
                            + " bg=" + desc(v.getBackground()));
                }
            } catch (Throwable ignored) {
            }
        }
        // ★★★ v0.1.31 用户第 1 / 2 条：这里**只保留「贴底升起」这一种几何**。
        //   窗口 / 内容根比例那套判据全部删掉 —— 它们把「作品详情页的文件列表块」
        //   和「播放列表详情整页」一起判成了面板（用户第 1、2 条就是这个）。
        //   现在「有自己底色的块」走 {@link #isGlassSurface}，这里只兜
        //   「<b>没有</b>底色的贴底容器」（宿主把底交给头部那张叶子的时候）。
        if (!isSheetLike(v)) {
            noteSheetMiss(v, w, h);
            return false;
        }
        // ★★★ v0.1.33：这一条和 isGlassSurface 共用同一套「浮层」判据 ——
        //   不在 Dialog 窗口里、或者已经被外层玻璃盖过的，一律不铺。
        // ★★★ v0.1.38：这里也不再因为「外层已经有玻璃」而跳过。
        //   没底色的容器铺一层玻璃是安全的：真正不透明的子块各自也会铺，
        //   透明子块则正好靠这一层显出玻璃 —— 两种情形都对。
        if (!inFloatingLayer(v)) return false;
        if (isVideoSurface(v) || isImageLayer(v)) return false;
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
        if (!isBottomAnchored((View) v.getParent())) return false;
        // 只铺「大块内容区」：面板里的单行（几十像素高）不铺，
        // 否则一个列表几十行，每行都画一遍全屏图，纯白烧 GPU。
        // ★ v0.1.29：12.5% 屏高（用户第 2 条里的「下载音轨」是一张小卡片，比 1/3 屏小）。
        if (h * 8 < sSH) return false;
        try {
            if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() == 0) return false;
        } catch (Throwable ignored) {
        }
        // 太窄的小块（图标 / 头像 / 分隔线）不铺：铺了反而像一块污渍。
        if (w * 2 < sSW) return false;
        // ★★★ v0.1.41（r12 用户第 3 / 5 条）：**容器不再铺底，这条路整个停用**。
        //
        //   旧做法是在容器 draw 之前，把「整屏壁纸的某一块」画在这个容器的矩形里。
        //   它有两个必然后果，两轮反馈都指向它：
        //     · 第 3 条「信息卡区域卡住一块壁纸，上划不跟随」——
        //       位图在录制那一刻按 getLocationInWindow 定死，卡片跟着列表滚动时
        //       display list 里的 dst 不会重算 ⇒ 壁纸钉在原地；
        //     · 第 5 条「先淡阴影、再分层出现模糊」——
        //       容器、每张卡片、卡片头各画各的，挂载顺序不同，完成时刻就不同。
        //
        //   路径 A 之后不需要它了：真正的不透明底（每张卡片）自己会换颜料，
        //   容器透明的地方本来就该露出页面壁纸。容器什么都不画 = 效果完全正确。
        noteSheetMiss(v, w, h);
        return false;
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
    private static boolean paintDialogGlass(View v, Canvas c, HostDraw host) {
        if (!sArmed) return false;
        if (sSW <= 0 || sSH <= 0) return false;
        if (c == null || host == null) return false;
        // ★★★ v0.1.39：主窗口里那种「挂在 RN 根视图上的弹层」也要接（见 #mainWindowSheet）。
        if (!inFloatingLayer(v)) return false;
        // ★★★ v0.1.41（r12 用户第 7 条）：**播放页里的图片层一律不接**。
        //   音轨播放页跑在独立窗口里（dlg=1），里面那块 {@code ExpoImageView 1440x1080}
        //   是<b>封面横幅</b>；把它当「半透明面板底」铺成玻璃 ⇒ 封面被换掉
        //   ⇒ 用户连着两轮说「封面死都不显示」。
        if (isPlayWindow(v) || playContext(v)) return false;
        int w;
        int h;
        try {
            w = v.getWidth();
            h = v.getHeight();
        } catch (Throwable t) {
            return false;
        }
        if (w <= 0 || h <= 0) return false;
        if (w * 100 < (long) sSW * DIALOG_IMG_MIN_W_PCT) return false;
        if ((long) w * h * 100 < (long) sSW * sSH * DIALOG_IMG_MIN_AREA_PCT) return false;
        if (isVideoSurface(v)) return false;
        // ⚠️ 必须是**半透明**的图（VEIL / ATMOS）。这一条是关键的安全阀：
        //   弹层里也可能有真照片（封面大图 / 预览图），它们是不透明 RGB ⇒ IMG_CONTENT
        //   ⇒ 这里一律不动。而「和背后内容融为一体」的那三张面板底恰恰就是
        //   半透明紫（用户的描述本身就是这个意思：能透出后面的内容）。
        int kind = imgKind(v);
        if (kind != IMG_VEIL && kind != IMG_ATMOS) return false;
        // ★★★ v0.1.41（r12 用户第 7 条）：**不透明的图绝不是面板底**。
        //   r12 日志里 `dialog glass -> ExpoImageView 1440x1080` 出现 3 次 ——
        //   那 1080px 高的一层是<b>作品封面</b>，被当成「半透明紫面板」铺成了玻璃，
        //   于是「封面死都不显示」。封面照片 avg ≈ 1.0（只是编码带 alpha 通道），
        //   三张面板底 avg 在 0.6~0.9 ⇒ 一条 0.90 的线正好把它们分开。
        float av = alphaAvgOf(v);
        if (av >= GLASS_IMG_MAX_ALPHA) {
            noteGlassFail("dlg opaque " + (int) (av * 100));
            return false;
        }
        Bitmap glass = glassBmp(v);
        if (glass == null) {
            noteGlassFail("dlg bmp");
            return false;
        }
        // ★★★ v0.1.41（r12 证据 A）：**位置一律用本层尺寸（0,0,w,h），不再用整屏位图对齐**。
        //   老代码走 paintAligned → coverRect(bmp, -loc) ，loc 来自 getLocationInWindow；
        //   面板所在的 Dialog 窗口 top=280 ⇒ 位图整体下移 280px 后被 clipRect 裁掉，
        //   而 drawBitmap 画在裁剪区外不抛异常 ⇒ 静默成功 ⇒ 面板整块消失。
        //   现在 paintLocal 用 getLocationOnScreen 只管「取哪一片壁纸」，
        //   dst 的落点永远是本地 (0,0,w,h) —— 落点不可能错。
        Rect r = new Rect(0, 0, w, h);
        int save = c.save();
        boolean ok;
        try {
            ok = paintLocal(c, glass, v, r);
            if (!ok) return false;
            // 宿主原本那张半透明紫图按 {@link #GLASS_IMG_TINT_PCT} 叠回来：
            // 完全不要它 ⇒ 面板和背景糊成一片、看不出边界；全要它 ⇒ 紫遮罩回来。
            int s2 = c.saveLayerAlpha(0, 0, w, h, (int) (GLASS_IMG_TINT_PCT * 255f));
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
        sSheet++;
        sPainted = true;
        GLASSED.put(v, Boolean.TRUE);
        hit("dialog glassA -> ", v, r);
        if (sGlassLogs < GLASS_LOG_BUDGET) {
            sGlassLogs++;
            log("glassA img -> " + name(v) + " " + w + "x" + h + " kind="
                    + (kind == IMG_VEIL ? "veil" : "atmos")
                    + " tint=" + (int) (GLASS_IMG_TINT_PCT * 100) + "%");
        }
        return true;
    }

    /** 「宿主自己那一笔」的抽象：paintDialogGlass 需要把宿主的图按低透明度叠回来。 */
    private interface HostDraw {
        Object draw() throws Throwable;
    }

    /**
     * 弹层里那张半透明紫图叠回来的强度（0~1）。
     * <p>0.16：留一点点面板色，既不糊成一片，也不会重新变成紫遮罩。
     */
    private static final float GLASS_IMG_TINT_PCT = 0.16f;

    /**
     * 弹层里「大块图片层」能被当成面板底的上限（平均 alpha）。
     * <p>≥ 0.90 视为不透明内容图（作品封面 / 预览大图）⇒ 不铺玻璃。
     */
    private static final float GLASS_IMG_MAX_ALPHA = 0.90f;

    /** 弹层里「大块图片层」的宽度下限（% 屏宽）。 */
    private static final int DIALOG_IMG_MIN_W_PCT = 60;
    /** 弹层里「大块图片层」的面积下限（% 屏面积）。 */
    private static final int DIALOG_IMG_MIN_AREA_PCT = 25;

    /**
     * 这张 expo-image 是不是「盖在壁纸上的半透明氛围图」。
     *
     * <p>★★ v0.1.23：以前返回「不画」（留洞），现在改成<b>画毛玻璃</b>。
     * 洞后面的内容靠绘制顺序，切后台回来一变就露白 ⇒ 用户第 1 条。
     * 用户对这一块的要求本来就是「贴合当前背景的高斯模糊」，画模糊壁纸正合适。
     */
    private static boolean dropVeil(View v, Canvas c) {
        if (!sArmed) return false;
        if (sSW <= 0 || sSH <= 0) return false;
        int w;
        int h;
        try {
            w = v.getWidth();
            h = v.getHeight();
        } catch (Throwable t) {
            return false;
        }
        if (w <= 0 || h <= 0) return false;
        if (w < sFullW) return false;
        // ★★★ v0.1.38：**满屏的那一层不归这里**（它是页面底图，由 paintBlurBg 负责）。
        //   r43 里同一个 1272x2772 的层被这条抢走，刷成清晰壁纸 ⇒ 播放页「背景透明」。
        if (h * 100 >= (long) sSH * 90) return false;
        // ★★★ v0.1.41（r12 用户第 7 条）：**横向的图片层不是氛围层**。
        //   氛围层（PurePlay 的 localbg 780x920、播放页的 playbackspbackground）都是
        //   <b>竖屏铺满</b>的；而 r12 日志里反复被换掉的那块
        //   {@code ExpoImageView 1440x1080}（宽/高 = 1.33）是<b>播放页的封面横幅</b>
        //   —— 它被铺成壁纸 ⇒ 用户看到「作品封面死都不显示」。
        //   1.2 这条线把 1440x1080(1.33) / 1300x975(1.33) 挡在外面，
        //   而 1440x1698(0.85) 这种竖向氛围层不受影响。
        if (w > h * 1.2f) return false;
        if (h < (int) (sSH * VEIL_MIN_H_RATIO)) return false;
        if (!isTopAnchored(v, w, h)) return false;
        if (insidePager(v)) return false;      // 图片浏览里的照片：一张都不许换
        int kind = imgKind(v);
        // ★★★ v0.1.36（r41 t0242 帧实证）：**ATMOS 也要接**。
        //   标题「PurePlay」下面那一大片紫色渐变，就是宿主那张
        //   {@code localbg.webp}（780x920 RGBA，自上而下紫→透明）渲染成的
        //   {@code ExpoImageView 1272x1500}。它的平均 alpha 落在 0.65~0.92 之间 ⇒
        //   {@link #imgKind} 判成 IMG_ATMOS（不是 VEIL）⇒ 这里直接放行 ⇒ 紫遮罩回来了。
        //   而 paintAtmos 又因为「高度只有 54% 屏高 < 80%」拒绝处理 ⇒ 两条路都不接。
        //   现在 VEIL / ATMOS 一律当「顶部氛围层」：原地画清晰壁纸。
        // ★★★ v0.1.38（r43 用户第 1 条）：**连 IMG_CONTENT 也接**。
        //   这一层只可能是 expo-image 画的贴顶通栏大图（宽度 ≥94% 屏宽、20%~90% 屏高），
        //   宿主在这个位置上放的就是装饰背景（PurePlay 的 localbg、作品详情页的头部底图）。
        //   以前卡「必须半透明」⇒ 那张图偶尔被解码成不透明 RGB（webp 走硬件解码时 alpha
        //   会丢）⇒ 判成 content ⇒ 放行 ⇒ 用户看到「紫遮罩有时候还是会显示」。
        //   内容图（主页立绘）是 {@code ReactImageView}，根本进不到这个钩子，不受影响。
        //   唯独装饰模糊底另有分支，不在这里抢。
        if (kind == IMG_BLUR) return false;
        // ★★ v0.1.25 用户补充需求：PurePlay 顶部那半屏「淡模糊」不要了。
        //   以前这里画的是**毛玻璃**（sWpBarBmp）—— 因为它原先是一层紫色氛围图，
        //   用户上一轮要的是「贴合背景的模糊」。这一轮明确说不需要这层模糊，
        //   于是改成画**清晰壁纸**：等于把那张氛围图原地换成背景本身，
        //   视觉上就是「顶部干干净净、没有任何叠加」。
        // ★★★ v0.1.37（r42 用户第 3 / 4 条）：**播放页例外**。
        //   播放页那张 {@code ExpoImageView 1272x2772}（整屏、带 alpha）走的正是这条路，
        //   r42 日志里 `paint veil clear -> ExpoImageView 1272x2772` 出现十几次 ——
        //   就是把播放页背景刷成了清晰壁纸，用户看到「背景直接透明 / 模糊不生效」。
        //   播放页那几张满屏氛围层一律用播放档（壁纸 + 播放页模糊档）。
        boolean play = playContext(v);
        Bitmap clear = play ? playBmp() : sWpBmp;
        if (clear == null) return false;
        Rect r = new Rect(0, 0, w, h);
        if (!paintAligned(c, clear, v, r, false)) return false;
        sVeil++;
        sPainted = true;
        hit(play ? "paint veil play -> " : "paint veil clear -> ", v, r);
        return true;
    }

    /**
     * 登记一个「满宽 + 够高」的图片层（每个类一次）。
     *
     * <p>为什么要它：播放页背景到底是 {@code ExpoImageView} 还是 RN 自己的
     * {@code ReactImageView}（Fresco 另有一套 draw），只看代码猜不出来 —— r26 之前已经
     * 在「到底哪条路画的」上错了七版。这里把<b>真被画到的</b>类名 + 位图分类打出来，
     * 下一轮一眼就能定案。
     */
    private static void noteImage(View v, int w, int h) {
        String key;
        try {
            key = v.getClass().getName();
        } catch (Throwable t) {
            return;
        }
        if (!sImgSeen.add(key)) return;
        if (sImgLogs >= IMG_LOG_BUDGET) return;
        sImgLogs++;
        int kind = imgKind(v);
        log("imglayer " + name(v) + " " + w + "x" + h + " top=" + topOf(v)
                + " kind=" + (kind == IMG_VEIL ? "veil" : kind == IMG_ATMOS ? "atmos" : "content"));
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
     * alpha 通道，在这里被判成 {@link #IMG_CONTENT} 直接放行。
     *
     * <p>糊的档位来自用户新加的「播放页背景模糊」（{@link MoriConfig#f2PlayBlur}）。
     */
    private static boolean paintAtmos(View v, Canvas c) {
        if (!sArmed) return false;
        if (sSW <= 0 || sSH <= 0) return false;
        int w;
        int h;
        try {
            w = v.getWidth();
            h = v.getHeight();
        } catch (Throwable t) {
            return false;
        }
        if (w <= 0 || h <= 0) return false;
        if (w < sFullW) return false;
        // ★★ v0.1.28：氛围底图必须**接近满屏**。r33 里播放页那张大封面
        //   （ReactImageView 2300x1726，62% 屏高）也满足「满宽 + 18% 高」，
        //   一旦它的位图被读成 RGBA 就会被当成氛围底整层换成壁纸 ⇒「作品封面不显示」。
        if (h * 100 < (long) sSH * 80) return false;
        if (!isTopAnchored(v, w, h)) return false;
        if (imgKind(v) != IMG_ATMOS) return false;
        // ★★ v0.1.27 用户第 4 / 6 条：这里以前画的是 {@code sWpPlayBmp}（播放页那张
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
        boolean playPage = playContext(v);
        Bitmap bmp;
        if (playPage) {
            bmp = sWpPlayBmp != null ? sWpPlayBmp : (sWpBmp != null ? sWpBmp : sWpBarBmp);
        } else {
            bmp = sWpBmp != null ? sWpBmp : sWpBarBmp;
        }
        if (bmp == null) return false;
        Rect r = new Rect(0, 0, w, h);
        if (!paintAligned(c, bmp, v, r, false)) return false;
        notePageBackdrop(v, h, bmp);
        sAtmos++;
        hit(playPage ? "paint atmos play -> " : "paint atmos clear -> ", v, r);
        return true;
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
    private static boolean paintPlayFallback(View v, Canvas c, int w, int h) {
        if (!sArmed) return false;
        if (w < sFullW) return false;
        if (h * 100 < (long) sSH * 92) return false;
        if (!(v instanceof android.widget.ImageView)) return false;
        try {
            String n = v.getClass().getName();
            if (!n.contains("Image") || n.contains("Svg")) return false;
        } catch (Throwable t) {
            return false;
        }
        if (imageBitmapOf(v) != null) return false;     // 有图 ⇒ 那是内容，交给宿主
        Bitmap bmp = sWpPlayBmp != null ? sWpPlayBmp : (sWpBmp != null ? sWpBmp : sWpBarBmp);
        if (bmp == null) return false;
        Rect r = new Rect(0, 0, w, h);
        if (!paintAligned(c, bmp, v, r, false)) return false;
        notePageBackdrop(v, h, bmp);
        sAtmos++;
        sPainted = true;
        hit("paint play fallback -> ", v, r);
        return true;
    }

    /**
     * 封面救 alpha 的时间戳（View → 上次救的时刻）。
     *
     * <p>★ v0.1.31：原来是「每个 View 只救一次」的 {@code Set}。但 RN 会<b>复用</b>
     * 同一个 {@code ReactImageView} 播下一首 —— 用户说的「打开新音轨作品封面会消失」
     * 就是被这一次性标记挡掉的。现在改成「同一 View 800ms 内只救一次」：
     * 既能压住每帧 post 刷屏，换曲时又会重新救。
     */
    private static final java.util.Map<View, Long> COVER_FIX =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Long>());

    private static final long COVER_FIX_GAP_MS = 800L;

    /** 这个封面 View 现在允许救一次吗（800ms 节流）。 */
    private static boolean coverFixAllowed(View v) {
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
    private static void auditPlayTree(final View any) {
        try {
            long now = System.currentTimeMillis();
            if (now - sAuditAt < 3000L) return;
            sAuditAt = now;
            final View root = any.getRootView();
            if (root == null) return;
            MAIN.post(() -> {
                try {
                    sAuditLines = 0;
                    log("=== play tree dump v0.1.67 ===");
                    walkPlay(root, 0);
                    log("=== play tree end ===");
                } catch (Throwable ignored) {
                }
            });
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
    private static void walkPlay(View v, int depth) {
        if (v == null || depth > 30 || sAuditLines > 120) return;
        int w = v.getWidth();
        int h = v.getHeight();
        if (w > 0 && h > 0) {
            long area = (long) w * (long) h;
            if (v instanceof android.widget.ImageView) {
                sAuditLines++;
                float a = v.getAlpha();
                log("img " + name(v) + " " + w + "x" + h + " a=" + a
                        + (drawableReady(v) ? " D" : " -") + chainOf(v, 4));
                // 内容图被淡到看不见 ⇒ 直接拉回 1（封面就是卡在这一步）
                if (a < 0.35f && w * 100 >= (long) sSW * 40
                        && h * 100 >= (long) sSH * 12) {
                    v.setAlpha(1f);
                    log("img lift -> " + name(v) + " " + w + "x" + h + " from a=" + a);
                }
            } else if (v.getAlpha() < 0.05f && area * 100L >= (long) sSW * (long) sSH * 3L) {
                // ★★★ v0.1.69（r72 实测）：**只记日志，绝不拉 alpha**。
                //   r72 日志实证：被我 0.1.68 的 `audit lift` 揭出来的那层是
                //   `dim ReactViewGroup 1272x2772 a=0.0 IMG` —— 它的子树里是
                //   LinearGradientView + 文本 + 图，正是**R18 提示模态**。
                //   ⇒「alpha=0 + 子树里有图 + 不可点击」这条判据根本不能用来区分
                //   「该显示的播放页」和「宿主故意藏起来的模态」：两者长得一模一样。
                //   所以这一类**永远不再动**（容器级 alpha 一律不碰），只记录。
                boolean img = hasImageDeep(v, 0);
                sAuditLines++;
                log("dim " + name(v) + " " + w + "x" + h + " a=" + v.getAlpha()
                        + (v.isClickable() ? " CLICK" : "") + (img ? " IMG" : "") + chainOf(v, 2));
            } else if (v.getAlpha() < 0.98f && !v.isClickable()
                    && w * 100 >= (long) sSW * 90
                    && h * 100 >= (long) sSH * 55) {
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
                sAuditLines++;
                log("mid " + name(v) + " " + w + "x" + h + " a=" + a + chainOf(v, 2));
                v.setAlpha(1f);
                log("mid lift -> " + name(v) + " " + w + "x" + h + " from a=" + a);
            } else if (depth >= 6 && area * 100L >= (long) sSW * (long) sSH * 20L) {
                sAuditLines++;
                log("big " + name(v) + " " + w + "x" + h + " a=" + v.getAlpha()
                        + (v.isClickable() ? " CLICK" : "") + chainOf(v, 2));
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n && sAuditLines <= 120; i++) {
                walkPlay(g.getChildAt(i), depth + 1);
            }
        }
    }

    /** 往上 n 层的「类名 尺寸 a=alpha」，用来看是谁把这一层压掉了。 */
    private static String chainOf(View v, int n) {
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
    private static boolean hasImageDeep(View v, int depth) {
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

    /**
     * ★★★ v0.1.65（r68 adb 层级实证）：<b>「播放页透明」和「作品封面不显示」
     * 是同一个病根 —— 播放页整屏容器的 alpha 被宿主压成了 0</b>。
     *
     * <p>把播放页停在前台（用户确认「背景透明 + 无作品封面」）时 dump 出来的真机层级：
     * <pre>
     * ReactViewGroup{2e6e998  0,0-1272,2772  #fe   alpha=0.0}   ← ★ 播放页内容容器
     *   └ ReactViewGroup{8d8577b  0,0-1272,2772  alpha=1.0}
     *       ├ LinearGradientView   0,0-1272,2772  alpha=1.0      ← 宿主渐变底
     *       └ ReactViewGroup{6b5fd48 0,0-1272,2772 alpha=1.0}
     *           ├ ReactImageView  drawableVisible=true           ← ★ 作品封面（就在这里！）
     *           ├ ReactTextView   447,1343-825,1424              ← 作品标题
     *           ├ ReactTextView   312,1441-960,1522
     *           └ ReactViewGroup  120,1723-1154,1854             ← 按钮
     * ReactViewGroup{b0df5f1  0,0-1272,2772  #104 alpha=0.0}    ← 播放页另一层
     * </pre>
     * 封面、标题、按钮<b>全都好端端在里面，连 Fresco 的 drawable 都挂上了</b>
     * （`drawableVisible=true`），只是它们共同的祖先 {@code alpha=0.0}
     * ⇒ 整棵子树一个像素都画不出来 ⇒ 屏幕上看到的就是「后面的那一页」= 用户说的透明。
     *
     * <p>所以 v0.1.64 那个「把封面自己的 alpha 拉回 1」注定无效：
     * 封面自己的 alpha 本来就是 1，是<b>它上面几层的容器</b>被压成了 0。
     * 这里直接在播放窗口里把「被压到 0 的大容器」拉回 1。
     * 判据收紧到：播放窗口内 + alpha<0.05 + 宽≥50% 屏 + 高≥12% 屏
     * ⇒ 只会碰到播放页那两三层全屏容器，不会误伤宿主有意隐藏的小控件。
     */
    private static boolean rescuePlayAlpha(View v) {
        try {
            if (!sArmed || sSH <= 0 || sSW <= 0) return false;
            boolean win = isPlayWindow(v);
            if (!win && System.currentTimeMillis() - sPlayPaintedAt > 2000L) return false;
            if (v.getAlpha() >= 0.05f) return false;
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0) return false;
            if (w * 100 < (long) sSW * 50) return false;
            if (h * 100 < (long) sSH * 12) return false;
            v.setAlpha(1f);
            if (sAlphaFixLogs < 3) {
                sAlphaFixLogs++;
                log("play alpha rescue -> " + name(v) + " " + w + "x" + h
                        + (win ? " win" : " sig"));
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean rescueCover(View v) {
        try {
            if (!sArmed || sSH <= 0) return false;
            if (!(v instanceof android.widget.ImageView)) return false;
            // ★★★ v0.1.46（r46 用户第 4② 条）：补一条兜底判据。
            //   「迷你条状态下退后台→回前台→再展开播放页」时，播放页窗口会被重建，
            //   {@link #isPlayWindow}（靠 setBlurRadius(10) 记账）很可能还没来得及
            //   重新记账 ⇒ 封面 alpha=0 这一帧救不回来 ⇒「作品封面不显示」。
            //   现在：播放页窗口认不出时，退而求其次用「当前确实是播放语境
            //   （{@link #playContext}，v0.1.46 已把迷你条从判据里排掉）+ 尺寸够大 +
            //   alpha 几乎为 0」这三条一起来认 —— 依然不可能碰到正常显示的图。
            // ★ v0.1.60：窗口内的层一律参与救援（见下方尺寸闸）。
            //   注：日志实证 `ReactImageView 2300x1726 alpha=3.3E-4` 时
            //   `cover alpha fix` 就已经打过了 —— 说明「把 alpha 拉回 1」这一步是成功的，
            //   封面仍然不显示的原因不在 alpha，而在**它所在的那棵子树整体 alpha=0**
            //   （和「播放页透明」同一个病根）。所以这一轮不再放宽这里，
            //   统一交给窗口根底板（`paint root bg`）去解决。
            // ★★★ v0.1.66（r69 回归修复）：补一条**窗口信号** ——
            //   「2 秒内刚铺过播放档底图」（`sPlayPaintedAt`）。
            //   封面自己 alpha 常常是 1、`isPlayWindow` 在封面这一层又判不出来，
            //   只靠下面那条 alpha≤0.05 的判据根本进不来，祖先救援也就跑不到。
            boolean win = isPlayWindow(v);
            boolean sig = win || (System.currentTimeMillis() - sPlayPaintedAt < 2000L);
            if (!win && !sig && !(playContext(v) && v.getAlpha() <= 0.05f)) return false;
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0) return false;
            if (w * 10 < (long) sFullW * 4) return false;
            if (h * 100 < (long) sSH * 22 || h * 100 > (long) sSH * 88) return false;
            // ★ v0.1.65：播放窗口里不再因「自己 alpha 正常」就放过 ——
            //   封面自己常常是 1.0，被压成 0 的是它上面几层的容器
            //   （r68 dump：`ReactViewGroup 1272x2772 alpha=0.0` 里面才是封面）。
            //   放它过去，下面的祖先救援才有机会执行。
            if (!win && !sig && v.getAlpha() > 0.35f) return false;
            // ★★★ v0.1.31 用户第 4 条：位图「就绪」的判据放宽到 drawable 非空。
            //   宿主的封面是 {@code ReactImageView}，它内部是 Drawee 那一套，
            //   {@link #imageBitmapOf} 只认 BitmapDrawable ⇒ 永远读不到（r35 里
            //   `cover alpha fix` 一条都没打出来，正是这个原因）。有 drawable
            //   就说明图已经挂上了，够我们判断「这不是占位层」。
            if (!drawableReady(v)) return false;
            // ★★★ v0.1.64（r64 用户第 4 条「先恢复播放页的作品封面显示」）：
            //   两个致命问题，都是我上一版留下的：
            //
            //   ① **改 alpha 被 post 到下一帧**。旧代码 `MAIN.post(() -> v.setAlpha(1f))`，
            //      而 `rescueCover` 是在 {@code View#draw} 里调的 —— 等于「这一帧先按
            //      alpha≈0 画完，再排队改」，宿主若每帧都把 alpha 压回 0（淡入动画没跑完 /
            //      或者它压根不想显示），我们就永远慢一帧，屏幕上永远是空的。
            //      现在**同步改**：就在宿主画它之前把 alpha 拉回 1，这一帧就是可见的。
            //
            //   ② **有节流**（`coverFixAllowed` 800ms 一次）。宿主若持续压 alpha，
            //      800ms 才校正一次 ⇒ 肉眼看到的就是「一直不显示」。
            //      播放页窗口里改为<b>每帧都校正</b>（只在 alpha 真的 <0.35 时才动手，
            //      改完就没下一次了，不会形成循环）。
            boolean allowFix = win || coverFixAllowed(v);
            if (!allowFix) return false;
            try {
                v.setAlpha(1f);
                if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
                // ③ **祖先也可能被压到 0** —— 这是 r68 实证的真病根：
                //    `ReactViewGroup 1272x2772 alpha=0.0` 里面才装着封面/标题/按钮。
                // ★★★ v0.1.66（r69 回归）：**只沿「封面这条链」往上走 5 层**，
                //   不再要求祖先自己也能判定成播放页（ReactViewGroup 上那条常为 false），
                //   改用窗口信号 `sig`。
                //   为什么必须收窄：v0.1.65 曾在「所有全屏大容器」上无差别拉 alpha，
                //   结果把宿主的**隐藏模态**（R18 提示页 / 全屏触摸拦截层）一起揭了出来
                //   ⇒ 用户侧表现为「提示成人向作品被隐藏 + 按钮点不动 + 放不了」。
                //   沿封面链走只可能碰到「装着封面/标题/按钮的那几个容器」，不会揭出无关模态。
                Object up = v.getParent();
                for (int i = 0; i < 5 && up instanceof View; i++) {
                    View pv = (View) up;
                    if (sig && pv.getAlpha() < 0.05f) {
                        pv.setAlpha(1f);
                        if (sAlphaFixLogs < 3) {
                            sAlphaFixLogs++;
                            log("cover ancestor lift -> " + name(pv) + " "
                                    + pv.getWidth() + "x" + pv.getHeight());
                        }
                    }
                    up = pv.getParent();
                }
                if (sCoverFixLogs < 3) {
                    sCoverFixLogs++;
                    log("cover alpha fix -> " + name(v) + " " + w + "x" + h
                            + (win ? " win" : " ctx"));
                }
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 这一层挂上可画的 drawable 了吗（Drawee / BitmapDrawable / ColorDrawable 都算）。 */
    private static boolean drawableReady(View v) {
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
    private static boolean isDimMask(Drawable d) {
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
    private static Integer solidColorOf(Drawable d) {
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
    private static float treeScale(View v) {
        float sc = 1f;
        try {
            View cur = v;
            for (int i = 0; i < 8 && cur != null; i++) {
                sc *= cur.getScaleX();
                android.view.ViewParent p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
            }
        } catch (Throwable ignored) {
            return 1f;
        }
        return sc;
    }

    /**
     * 贴屏幕顶，或者本身就满屏（报告里两张氛围图各占一种）。
     */
    private static boolean isTopAnchored(View v, int w, int h) {
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
    private static Bitmap imageBitmapOf(View v) {
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
    private static final long BLUR_PENDING_TTL_MS = 600000L;

    /**
     * 「新位图可以绑成装饰底」的时效（v0.1.29）。
     *
     * <p>宿主的 {@code ArtworkPrivacyImage} 挂载时才会调 {@code setBlurRadius}，
     * 那一刻起 3 秒内出现的位图就是它要的模糊底。超过 3 秒还冒出来的位图
     * ——尤其是图片浏览里翻过来的<b>照片</b>——一律不认。
     */
    private static final long BLUR_BIND_FRESH_MS = 3000L;

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
    private static boolean isScreenFillDecor(View v) {
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
    private static int blurRadiusOf(View v) {
        try {
            Long p = BLUR_PENDING.get(v);
            if (p == null) return 0;
            long t = p >>> 8;
            int r = (int) (p & 0xFF);
            if (System.currentTimeMillis() - t > BLUR_PENDING_TTL_MS) {
                BLUR_PENDING.remove(v);
                return 0;
            }
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
            // ★★★ v0.1.38：读不到位图也要认（expo-image 的 drawable 不一定是 BitmapDrawable）。
            //   以前这里 return 0 ⇒ 同一层「有时读得到、有时读不到」⇒ 播放页模糊时有时无。
            if (b == null) return r;
            Integer bound = BLUR_BMP.get(b);
            if (bound != null) return bound;
            // ★★★ v0.1.38：删掉「3 秒新鲜期」和「一个 View 只绑一张位图」两道闸。
            //   它们当初是为了挡「图片浏览翻页把照片认成装饰底」，代价是播放页那张底图
            //   换歌 / 切后台回来重新解码后**半径读成 0** ⇒ 播放页背景变清晰壁纸。
            //   r43 日志把这条钉死了：整轮 `blur bg` 只有 6 条且全是 r=10，
            //   其余帧同一个 1272x2772 层走的是 `paint veil clear`（清晰壁纸）。
            //   现在这两道闸由上面那条 `insidePager(v)` 接管 —— 结构判据比时间窗可靠。
            BLUR_BMP.put(b, r);
            return r;
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
    private static boolean insidePager(View v) {
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

    private static int imgKind(View v) {
        // ★★ v0.1.24 用户第 3 条：宿主明确要求过模糊（blurRadius > 0）⇒ 装饰底，
        //   和位图本身有没有 alpha 通道无关（封面照是 RGB，读不出 alpha）。
        //   ★ v0.1.27：判定改成「这张位图被标过」而不是「这个 View 被标过」。
        //   ★★ v0.1.28 用户第 1 / 2 条：**还必须真的是「铺满整屏」的那一层**。
        //   以前只要 View 宽过线就认 —— 播放页的<b>正面封面</b>（r33 日志
        //   `ReactImageView 2300x1726`，高只占 62%）和宿主那张 10px 模糊底共用同一张
        //   artwork 位图，位图级的标记一绑上，正面封面也被当成装饰底整层顶掉
        //   ⇒ 用户看到「作品封面不显示」「最后一张图片背后多一层模糊」。
        //   装饰底在 bundle 里就是 `PlayerBackground` / `PlaylistDetailBackground`，
        //   两者都是 `contentFit=cover` **铺满整屏**；封面卡片永远没有 85% 屏高。
        if (blurRadiusOf(v) > 0 && isScreenFillDecor(v)) return IMG_BLUR;
        Drawable d;
        try {
            if (!(v instanceof ImageView)) return IMG_CONTENT;
            d = ((ImageView) v).getDrawable();
        } catch (Throwable t) {
            return IMG_CONTENT;
        }
        if (!(d instanceof BitmapDrawable)) return IMG_CONTENT;
        Bitmap b;
        try {
            b = ((BitmapDrawable) d).getBitmap();
        } catch (Throwable t) {
            return IMG_CONTENT;
        }
        if (b == null || b.isRecycled()) return IMG_CONTENT;
        Integer cached = VEIL_CACHE.get(b);
        if (cached != null) return cached;
        int kind = IMG_CONTENT;
        float avg = 1f;
        boolean hasA = false;
        String cfg = "?";
        try {
            hasA = b.hasAlpha();
            cfg = String.valueOf(b.getConfig());
            // ★★★ v0.1.36：**硬件位图也要分析**。
            //   以前 `b.getConfig() == HARDWARE` 就直接当内容图放行（读不到像素），
            //   于是「PurePlay 紫遮罩 / 播放页显示封面模糊版 / 背景消失」这三种
            //   时有时无的症状全在这里漏掉 —— 同一张图走 HARDWARE 解码就漏、
            //   走 ARGB_8888 就中，表现成「有时候好有时候不好」。
            //   现在拷一份软件副本**只做分析**（一次性，结果进 VEIL_CACHE，不重复开销）。
            Bitmap probe = b;
            try {
                if (b.getConfig() == Bitmap.Config.HARDWARE) {
                    Bitmap soft = b.copy(Bitmap.Config.ARGB_8888, false);
                    if (soft != null) {
                        probe = soft;
                        hasA = soft.hasAlpha();
                    }
                }
            } catch (Throwable ignored) {
            }
            if (probe.getConfig() != Bitmap.Config.HARDWARE && hasA) {
                avg = avgAlpha(probe);
                // ★ v0.1.22：三段式。只有「真的半透明叠加」才允许留洞（整层不画），
                //   其余一律当底图原地换壁纸 —— 见 VEIL_DROP_MAX_ALPHA 的注释。
                kind = avg < VEIL_DROP_MAX_ALPHA ? IMG_VEIL : IMG_ATMOS;
            }
            if (probe != b) {
                try {
                    probe.recycle();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return IMG_CONTENT;
        }
        VEIL_CACHE.put(b, kind);
        AVG_ALPHA.put(b, avg);
        if (sImgLogs < IMG_LOG_BUDGET) {
            sImgLogs++;
            log("img " + b.getWidth() + "x" + b.getHeight() + " cfg=" + cfg
                    + " alpha=" + hasA + " avg=" + String.format(java.util.Locale.ROOT, "%.2f", avg)
                    + " -> " + (kind == IMG_VEIL ? "veil" : kind == IMG_ATMOS ? "atmos" : "content")
                    + (avg >= VEIL_MAX_ALPHA ? "(solid)" : ""));
        }
        return kind;
    }

    /** 采样 24×24 网格求平均 alpha（0–1）。硬件位图取不到像素 ⇒ 当不透明处理。 */    /**
     * ★ v0.1.41（r12 用户第 7 条）：这张图片层「有多不透明」。
     *
     * <p>{@link #imgKind} 算过就复用缓存；没算过（HARDWARE 位图 / 非 BitmapDrawable）
     * 就现算一次并记住。<b>读不出来一律当 1.0</b>（不透明）——
     * 宁可少铺一块玻璃，也绝不再把作品封面铺成模糊底（那正是「封面死都不显示」）。
     */
    private static float alphaAvgOf(View v) {
        Bitmap b = imageBitmapOf(v);
        if (b == null || b.isRecycled()) return 1f;
        Float c = AVG_ALPHA.get(b);
        if (c != null) return c;
        float a = 1f;
        Bitmap probe = null;
        try {
            probe = b;
            if (b.getConfig() == Bitmap.Config.HARDWARE) {
                probe = b.copy(Bitmap.Config.ARGB_8888, false);
            }
            if (probe != null && probe.getConfig() != Bitmap.Config.HARDWARE) {
                a = avgAlpha(probe);
            }
        } catch (Throwable ignored) {
        } finally {
            if (probe != null && probe != b) {
                try {
                    probe.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
        AVG_ALPHA.put(b, a);
        return a;
    }

    private static float avgAlpha(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        if (w <= 0 || h <= 0) return 1f;
        int stepX = Math.max(1, w / 24);
        int stepY = Math.max(1, h / 24);
        long sum = 0L;
        int n = 0;
        for (int y = stepY / 2; y < h; y += stepY) {
            for (int x = stepX / 2; x < w; x += stepX) {
                sum += (b.getPixel(x, y) >>> 24) & 0xff;
                n++;
            }
        }
        return n == 0 ? 1f : sum / (float) (n * 255f);
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
    private static volatile int sShadowLogs;
    private static final int SHADOW_LOG_BUDGET = 20;

    private static void ensureShadow(TextView tv) {
        if (!sArmed) return;
        try {
            float ts = tv.getTextSize();
            if (ts <= 0f) return;
            // ★★★ v0.1.42（r13 用户第 2 条）：**阴影不再看「坐在什么底上」**。
            //   原来那条判据是想「亮底上不打黑阴影」，但它量化的是<b>宿主声明的颜色</b>
            //   —— 而我们已经把这棵树的底色换成了壁纸，甚至把原来那层深色底 drop 掉了
            //   ⇒ 宿主那个颜色<b>在屏幕上根本不存在</b>。用它判断的直接后果就是：
            //   媒体页菜单栏被选中、变成粗体的那几个字被判成「坐在亮底上」⇒ r=0
            //   ⇒ 主动 clearShadowLayer() ⇒ 用户连着几轮说的「选中粗体没有阴影」。
            //   阴影只是「黑、80%、半径 = 字号 8%」，在真·浅底上几乎看不出来，
            //   在壁纸上却是可读性的关键 ⇒ 一律加，不再做二选一。
            boolean light = onLightSurface(tv);      // 只用于日志
            float radius = ts * TEXT_SHADOW_RADIUS_RATIO;
            // 探针③：把「这个 TextView 被判成什么」记下来（按签名去重，不会耗尽预算）。
            if (logOnce("sh:" + signature(tv), "shadow ts=" + (int) ts + " r=" + (int) radius
                    + " light=" + (light ? 1 : 0)
                    + " dark=" + (textIsDark(tv) ? 1 : 0)
                    + " tx=#" + String.format(java.util.Locale.ROOT, "%08x", curColor(tv))
                    + " n=" + textOf(tv))) {
                // 已记过
            }
            Float last = SHADOWED.get(tv);
            if (last != null && Math.abs(last - radius) < 0.01f) return;
            Paint p = tv.getPaint();
            if (p == null) return;
            if (radius <= 0f) {
                p.clearShadowLayer();
            } else {
                p.setShadowLayer(radius, 0f, 0f, shadowColor());
            }
            SHADOWED.put(tv, radius);
            tv.invalidate();
        } catch (Throwable ignored) {
        }
    }

    /** 诊断用：TextView 前 6 个字（看日志就知道是哪一行文字）。 */
    private static String textOf(TextView tv) {
        try {
            CharSequence cs = tv.getText();
            if (cs == null) return "-";
            String s = cs.toString().replace('\n', ' ');
            return s.length() > 6 ? s.substring(0, 6) : s;
        } catch (Throwable t) {
            return "-";
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
     *   <li><b>文字本身是深色</b>（{@code getCurrentTextColor()} 亮度 &lt; {@link #DARK_TEXT_LUMA}）
     *       ⇒ 它必然坐在浅底上（深字压深底谁都看不见），直接不加阴影；</li>
     *   <li>否则沿父链找最近一层<b>实心背景</b>，把它的 drawable <b>缩小画进 8×8 位图</b>求平均色
     *       （纯读操作，不改 bounds ⇒ 不会触发宿主的无限重绘）。
     *       ⚠️ 跳过「我们本来就会换掉的那几类」（满屏 / 条 / 面板），否则采到的是壁纸自己的颜色。</li>
     * </ol>
     *
     * <p>结果按 TextView 缓存（WeakHashMap），一支文字一辈子算一次。
     */
    private static boolean onLightSurface(TextView tv) {
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
            //     · 不透明 + 亮度 ≥ LIGHT_BG_LUMA（白胶囊按钮）⇒ 不需要；
            //     · 不透明 + 深色（深色卡片）⇒ 也不需要（深底上的浅字本来就有对比）。
            View cur = tv;
            for (int i = 0; i < 3 && cur != null; i++) {
                Drawable d = cur.getBackground();
                if (d != null) {
                    // 半透明底（玻璃卡片 / 压暗层）⇒ 壁纸透过来，仍然需要阴影
                    if (d.getOpacity() != PixelFormat.OPAQUE) return false;
                    Integer c = solidColorOf(d);             // RN BackgroundDrawable 里的 int
                    if (c != null) {
                        int a = (c >>> 24) & 0xff;
                        if (a < 200) return false;           // 实色但半透明：还是叠在图上
                        return luma(c) >= LIGHT_BG_LUMA;     // 亮实心底：不需要阴影
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

    /** 文字本身是不是深色（深字 ⇒ 只可能坐在浅底上）。 */
    private static boolean textIsDark(TextView tv) {
        try {
            int c = tv.getCurrentTextColor();
            if (Color.alpha(c) < 200) return false;   // 透明/半透明文字：不猜
            return luma(c) < DARK_TEXT_LUMA;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 某层 View 的「实心表面颜色」。
     *
     * @return ARGB；{@code alpha == 0} = 这层没铺底（继续往上找）
     */
    private static int surfaceColor(View v) {
        Drawable d;
        try {
            // 这几类我们本来就会换成壁纸 ⇒ 采到的会是壁纸颜色，直接跳过
            if (coversScreen(v) || isBarLike(v) || isSheetLike(v)) return 0;
            d = v.getBackground();
        } catch (Throwable t) {
            return 0;
        }
        if (d == null) return 0;
        try {
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                return ((android.graphics.drawable.ColorDrawable) d).getColor();
            }
        } catch (Throwable ignored) {
        }
        Rect b;
        Bitmap probe = null;
        try {
            b = d.getBounds();
            if (b == null || b.width() <= 0 || b.height() <= 0) return 0;
            probe = Bitmap.createBitmap(PROBE_PX, PROBE_PX, Bitmap.Config.ARGB_8888);
            Canvas pc = new Canvas(probe);
            pc.scale(PROBE_PX / (float) b.width(), PROBE_PX / (float) b.height());
            pc.translate(-b.left, -b.top);
            d.draw(pc);      // 纯绘制，不改 drawable 的任何状态
            long sa = 0L;
            long sr = 0L;
            long sg = 0L;
            long sb = 0L;
            for (int y = 0; y < PROBE_PX; y++) {
                for (int x = 0; x < PROBE_PX; x++) {
                    int px = probe.getPixel(x, y);
                    int al = (px >>> 24) & 0xff;
                    if (al == 0) continue;
                    sa += al;
                    sr += ((px >> 16) & 0xff) * al;
                    sg += ((px >> 8) & 0xff) * al;
                    sb += (px & 0xff) * al;
                }
            }
            if (sa == 0L) return 0;
            int n = PROBE_PX * PROBE_PX;
            return Color.argb((int) (sa / n),
                    (int) (sr / sa), (int) (sg / sa), (int) (sb / sa));
        } catch (Throwable t) {
            return 0;
        } finally {
            if (probe != null) {
                try {
                    probe.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 相对亮度（0–1，Rec.601 近似，够用来判「浅底 / 深底」）。 */
    private static float luma(int c) {
        float r = ((c >> 16) & 0xff) / 255f;
        float g = ((c >> 8) & 0xff) / 255f;
        float b = (c & 0xff) / 255f;
        return 0.299f * r + 0.587f * g + 0.114f * b;
    }

    // ── 热路径 ──────────────────────────────────────────────────────

    /** 「大块底色」清单（防止重复打）：class + 宽x高。 */
    private static final java.util.Set<String> BIG_SURFACE =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static volatile int sBigSurfLogs;
    private static final int BIG_SURF_LOG_BUDGET = 26;

    /**
     * ★★★ v0.1.31 诊断：把所有「够大的底色」连形状一起打出来（限 26 种）。
     *
     * <p>这一条是留给下一轮的<b>终局证据</b>：不管玻璃成没成，日志里都会有一行
     * 「这块底长什么样」——宽高 / 不透明度 / 圆角 / 颜色 / 是否命中
     * {@link #isGlassSurface}。以后要调阈值，看这一份表就够了，不用再靠猜。
     */
    private static void noteBigSurface(View v, Drawable d, Rect b) {
        if (sBigSurfLogs >= BIG_SURF_LOG_BUDGET) return;
        if (sSW <= 0 || sSH <= 0) return;
        int w = b.width();
        int h = b.height();
        if (w * 100 < (long) sSW * GLASS_MIN_W_PCT) return;
        if (h * 100 < (long) sSH * (GLASS_MIN_H_PCT - 2)) return;
        String key;
        try {
            key = v.getClass().getSimpleName() + "/" + w + "x" + h;
        } catch (Throwable t) {
            return;
        }
        if (!BIG_SURFACE.add(key)) return;
        sBigSurfLogs++;
        Integer solid = solidColorOf(d);
        log("surface " + key + " top=" + topOf(v) + " op=" + d.getOpacity()
                + " r=" + (int) maxRadiusOf(d)
                + " c=" + (solid == null ? "?" : String.format("#%08X", solid))
                + " scr=" + (insideScreen(v) ? 1 : 0)
                + " bs=" + (insideBottomSheet(v) ? 1 : 0)
                + " dlg=" + (inDialogWindow(v) ? 1 : 0)
                + " cov=" + (coveredByGlass(v) ? 1 : 0)
                + " chain=" + chainOf(v)
                + (isGlassSurface(v, d, b) ? " => GLASS" : " => plain"));
    }

    /**
     * ★★★ v0.1.32 诊断：这块「没底色的内容根」为什么没被当成面板铺玻璃。
     *
     * <p>用户第 3 条「只有概述有模糊、其余紫底」每次都只能靠猜是哪一块没命中。
     * 这一行把它的宽高、位置、父层高宽、是否在页面里、祖先链一次性打出来
     * （每种尺寸只打一次，最多 24 条）。下一轮直接照着这行改，不用再试。
     */
    private static void noteSheetMiss(View v, int w, int h) {
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
                    + " cov=" + (coveredByGlass(v) ? 1 : 0)
                    + " parent=" + par
                    + " chain=" + chainOf(v));
        } catch (Throwable ignored) {
        }
    }

    private static final java.util.Set<String> SHEET_MISS_KEYS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static volatile int sSheetMissLogs;
    private static final int SHEET_MISS_BUDGET = 24;

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
    private static boolean insideScreen(View v) {
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
    private static boolean insideBottomSheet(View v) {
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
    private static boolean inDialogWindow(View v) {
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

    /**
     * ★★★ v0.1.34：宿主<b>播放页</b>的 seekbar 加载时打点（由 {@code FeatureF2} 的资源探针调用）。
     *
     * <p>这是本轮唯一能把「播放页」和「播放列表 / 分类详情」分开的信号：
     * 两者背景图是同一个组件、同一个 {@code blurRadius=10}、同一个整屏尺寸，
     * 只有播放页会去拿 {@code seekbar_track_material} / {@code seekbar_thumb_material_anim}。
     */
    public static void notePlayPage() {
        sPlayPageAt = System.currentTimeMillis();
        sPlayBindPending = true;
    }

    /**
     * ★★★ v0.1.37：每次 {@code View#draw} 都会过这里，但要先做一次 {@code instanceof} 短路 ——
     * 只有进度条才会往下走，其余 View 的成本就是一个类型判断。
     *
     * <p>把「活的进度条」记下来，作为「此刻在播放页」的<b>唯一</b>活体证据。
     * 细线进度（迷你播放器 / 下载条）用高度门槛排掉。
     */
    private static void notePlaySeek(View v) {
        try {
            if (!(v instanceof android.widget.ProgressBar)) return;
            if (sSH <= 0) return;
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
            if (w * 100 < (long) sSW * 55) return;       // 真进度条≈90% 屏宽
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
            if (cy * 100 >= (long) sSH * 88) {           // ① 垂直中心落在屏幕底部 12%
                if (sSeekLogs2 < 2) {
                    sSeekLogs2++;
                    log("play seek skip（迷你条） -> " + name(v) + " " + w + "x" + h
                            + " y=" + xy[1] + ".." + (xy[1] + h) + "/" + sSH);
                }
                return;
            }
            if (barAncestor(v)) {                        // ② 通宽祖先只是一条矮条
                if (sSeekLogs2 < 2) {
                    sSeekLogs2++;
                    log("play seek skip（矮条容器） -> " + name(v) + " " + w + "x" + h);
                }
                return;
            }
            sPlaySeek = new java.lang.ref.WeakReference<>(v);
            // ★★★ v0.1.47：把这支进度条所在的页面锚点一起记下来。
            //   这是「到底哪一层才是播放页」的<b>唯一权威答案</b> ——
            //   往后 {@link #isPlayPage} 认错了页（比如绑定时机刚好赶上去赞 finer
            //   预览/迷你条拉 seekbar 资源那一瞬绑到了列表页），有它能当场纠正。
            sSeekPageAnchor = new java.lang.ref.WeakReference<>(pageAnchorOf(v));
            if (sPlaySeekLogs < PLAY_SEEK_LOG_BUDGET) {
                sPlaySeekLogs++;
                log("play seek -> " + name(v) + " " + w + "x" + h + " top=" + topOf(v));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 「play seek skip」诊断日志条数（限 2 条，别刷屏）。 */
    private static volatile int sSeekLogs2;

    /**
     * ★★★ v0.1.46：这支进度条是不是坐在一条「矮条」里（迷你播放条 / 底部操作条）。
     *
     * <p>沿祖先链往上找<b>通宽（≥90% 屏宽）</b>的容器：播放页的 seekbar 坐在整屏
     * 容器里，而迷你播放条/底部操作条的通宽容器只有一条矮条的高度。
     * 找到通宽且高度 ≥35% 屏高的容器 ⇒ 是正常页面 ⇒ 返回 false。
     *
     * <p>最多往上走 10 层，纯读布局属性，无分配、无绘制开销。
     */
    private static boolean barAncestor(View v) {
        if (sSH <= 0) return false;
        try {
            Object p = v.getParent();
            for (int i = 0; i < 10 && p instanceof View; i++) {
                View pv = (View) p;
                int pw = pv.getWidth();
                int ph = pv.getHeight();
                if (pw > 0 && ph > 0 && pw * 100 >= (long) sSW * 90) {
                    if (ph * 100 >= (long) sSH * 35) return false;   // 整页容器 ⇒ 正常
                    return true;                                     // 矮条 ⇒ 迷你条
                }
                p = pv.getParent();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

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
    private static volatile long sPlayHoldUntil;

    /**
     * 「播放页」判定的保持窗口（ms）。
     *
     * <p>★★★ v0.1.43：1600 → 700。太长会让离开播放页后的列表/详情
     * 仍被当成播放页铺模糊（用户第 6 / 7 条「高斯模糊概率出现」）。
     */
    private static final long PLAY_HOLD_MS = 700L;

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
     *  <pre>Bitmap top = playContext(v) ? playBmp() : sWpBmp;</pre>
     *  播放档位图（壁纸+重模糊）被铺到<b>列表页 / PurePlay / 播放列表页的顶部导航栏</b>上
     *  —— 而这些页面的页面底色走的是别的路径（铺清晰壁纸），于是用户看到的就是
     *  「只有页头上半区域是高斯模糊」。
     *
     *  <p>修法：活体进度条必须和当前这一层<b>同页</b>才算数（见 {@link #samePage}）。
     */
    private static boolean isPlayLive(View judge) {
        java.lang.ref.WeakReference<View> ref = sPlaySeek;
        if (ref == null) return holdActiveFor(judge);
        View v = ref.get();
        if (v == null) {
            sPlaySeek = null;
            return holdActiveFor(judge);
        }
        try {
            // ★★★ v0.1.39：删掉这里那道 `v.getHeight() * 100 < sSH * 2` 的死门槛。
            //   高度/宽度筛选在 notePlaySeek 记账时已经做过一次，这里再判一次
            //   只会把宿主那条 54px 的进度条永久判死（= 播放页判定从没生效）。
            if (v.getWindowToken() != null && v.isShown()) {
                sPlayHoldUntil = System.currentTimeMillis() + PLAY_HOLD_MS;
                sPlayHoldPage = new java.lang.ref.WeakReference<>(pageAnchorOf(v));
                // ★★★ v0.1.47：活体进度条在别的页面（迷你条 / 保活的播放页）
                //   不能给当前这一层当「播放页证据」。
                return samePage(v, judge);
            }
        } catch (Throwable t) {
            // 读属性失败：按「刚刚还活着」处理，不要让异常打断绘制
        }
        return holdActiveFor(judge);
    }

    /** 播放页「保持窗口」的页面锚点：窗口是从哪个页面续的（v0.1.47）。 */
    private static volatile java.lang.ref.WeakReference<View> sPlayHoldPage;

    /**
     * ★ 真进度条当时所在的页面锚点（v0.1.47）。
     *
     * <p>见 {@link #isPlayPage}：它是「哪个 Screen 才是播放页」的权威答案，
     * 用来纠正 {@code notePlayPage} 那一瞬可能绑错的 {@code sPlayScreen}。
     */
    private static volatile java.lang.ref.WeakReference<View> sSeekPageAnchor;

    /**
     * ★★★ v0.1.47：700ms 保持窗也必须<b>页面级</b>。
     *
     * <p>保持窗的本意是「切后台那一下进度条短暂 detach，别让播放页闪一帧清晰壁纸」。
     * 旧实现是全局时间戳 ⇒ 离开播放页后的 700ms 里，列表页的顶栏也会被判成播放页
     * （肉眼看到的就是「返回列表时页头糊一下再变清晰」）。现在把锚点一起记下来，
     * 只有和锚点同页的层才能吃到这个保持窗。
     */
    private static boolean holdActiveFor(View judge) {
        if (judge == null) return false;
        if (System.currentTimeMillis() >= sPlayHoldUntil) return false;
        java.lang.ref.WeakReference<View> ref = sPlayHoldPage;
        View anchor = ref == null ? null : ref.get();
        if (anchor == null) return false;      // 锚点已被回收 ⇒ 视为过期
        return samePage(anchor, judge);
    }

    /** 这一层所属的「页面锚点」：在 Screen 容器里就是那个容器，否则是窗口根。 */
    private static View pageAnchorOf(View v) {
        if (v == null) return null;
        View s = screenOf(v);
        if (s != null) return s;
        try {
            return v.getRootView();
        } catch (Throwable t) {
            return v;
        }
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
    private static boolean samePage(View a, View b) {
        if (a == null || b == null) return false;
        try {
            return pageAnchorOf(a) == pageAnchorOf(b);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 「这一层该按播放页处理吗」。
     *
     * <p>两条并列，任一成立即是：活体进度条（{@link #isPlayLive}，最硬）、
     * 或者 seekbar 刚加载过的 1.5 秒窗口（{@link #isPlayPage}，兜第一次进播放页那一帧 ——
     * 那时进度条可能还没 layout 完）。
     */
    private static boolean playContext(View v) {
        if (isPlayLive(v)) return true;
        // ★★★ v0.1.43（r14 用户第 5 / 6 / 7 条）：**删掉「height > 屏高 ⇒ 播放页」**。
        //   r14 日志：`blur bg r=0 1440x3328 pg=1 ->play` —— 列表/详情页的底图也比
        //   屏高（含安全区）更高一截，被这条误判成播放页 ⇒ 整页被铺成播放档模糊，
        //   表现就是「高斯模糊遮罩概率出现」。只留活体 seekbar + 短 TTL 绑定。
        return isPlayPage(v);
    }

    /**
     * ★★★ v0.1.43：小字强调色的「绘制前补一刀」。
     *
     * <p>只动 ≤18sp 的字；已经是强调色 / 近白 / 关着开关的一律不动。
     * 走 {@code setTextColor(int)} 会再进我们的钩子，收敛后 n==c 即停。
     */
    private static void forceAccentSmallText(TextView tv) {
        if (!AccentColor.on() || tv == null) return;
        try {
            float dens = tv.getResources().getDisplayMetrics().scaledDensity;
            if (dens <= 0f) return;
            float sp = tv.getTextSize() / dens;
            if (sp > 18f) return;
            int c = tv.getCurrentTextColor();
            int n = AccentColor.remapSmallText(c);
            if (n == c) return;
            // 若当前是 ColorStateList，setTextColor(int) 会把它摊成单色 ——
            // 底栏选中态会丢。只在「此刻颜色仍是宿主紫族 / 中性半透明」时动手。
            int a = (c >>> 24) & 0xff;
            int r = (c >> 16) & 0xff;
            int g = (c >> 8) & 0xff;
            int b = c & 0xff;
            boolean neutral = (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b))) <= 6;
            boolean purpleish = b > r && r >= g;
            if (!neutral && !purpleish) return;
            if (neutral && a == 0xff) return;   // 不透明纯黑正文
            tv.setTextColor(n);
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
    private static final java.util.Map<Class<?>, Boolean> SCREEN_CLS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Class<?>, Boolean>());

    private static boolean isScreenClass(Class<?> c) {
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
    private static View screenOf(View v) {
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

    /**
     * 这块整屏模糊底是不是<b>播放页</b>的。
     *
     * <p>规则（见 {@link #PLAY_PAGE_TTL_MS}）：seekbar 刚加载过 ⇒ 把<b>当前这块所在的
     * 页面容器</b>绑定成播放页；此后只有同一个容器算播放页。
     * 列表页是另一个 {@code Screen} 实例 ⇒ 第一帧就返回 false，铺清晰壁纸。
     */
    private static boolean isPlayPage(View v) {
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
        //   而旧实现 `sPlayScreen = sc == null ? null : …` 在 sc==null 时**存了个 null**
        //   ⇒ 和「没有绑定」完全无法区分 ⇒ 每次判都落到 miss。
        //   于是播放页打开后的前几百毫秒（进度条还没画第一帧、素材刚加载）里，
        //   整屏底图被铺成<b>清晰壁纸</b>，别的层（封面横幅 / 面板）还在宿主手里
        //   ⇒ 用户看到的就是「背景被裁剪成几块」+「封面是纯模糊占位」，
        //   而等进度条那一路判定生效（{@link #isPlayLive}）画面才统一。
        //
        //   现在统一用 {@link #pageAnchorOf}：能找到 Screen 就是那个 Screen，
        //   找不到就是<b>窗口根</b> —— 永不为 null，也就永远不会退化。
        View pg = pageAnchorOf(v);
        java.lang.ref.WeakReference<View> ref = sPlayScreen;
        View bound = ref == null ? null : ref.get();
        if (pg != null && bound == pg) return true;

        // ★★★ v0.1.47：**已经见过真进度条时，它的页面锚点才是权威答案**。
        //   旧实现一旦把页面绑错（绑的那一帧恰好不在播放页上 ——
        //   比如宿主在列表页偷偷拉 seekbar 素材、或走了迷你条那只样式同类的
        //   ProgressBar），这个错误绑定会<b>一直沿用</b>，
        //   于是列表页从头就被当成播放页 ⇒ 页头顶栏整条糊（r47 用户第 2 条）。
        java.lang.ref.WeakReference<View> aref = sSeekPageAnchor;
        View anchor = aref == null ? null : aref.get();
        // ⚠️ 只有落在**真正的页面容器**（Screen）上的锚点才拿来纠错。
        //   落在窗口根上的锚点不能当判据：这个宿主的播放页摊在窗口根上，
        //   但它同时还有一批 {@code inScr=1} 的层，拿「窗口根」当唯一答案会
        //   在同一页里出现 true/false 抖动 —— 那正是「背景被裁成几块」的来源。
        if (anchor != null && anchor.getRootView() != anchor) {
            if (pg != null && anchor == pg) return true;
            judgePlayPage("off-page " + (pg == null ? "null" : name(pg)));
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
        //   {@code sPlayBindPending} 消耗掉，并且绑到<b>那一刻它所在的页面</b>——
        //   而那一层很可能还在上一页（转场动画里两页同时存在）⇒ 绑错 ⇒
        //   真正的播放页反而被判 off-page。
        //   现在改成：宽限期内<b>所有层</b>都算播放页（整段窗口一起算），
        //   等真进度条出现，锚点会再把范围收窄到它所在的那一页。
        boolean grace = now - sPlayPageAt <= PLAY_PAGE_TTL_MS;
        if (sPlayBindPending && grace) {
            sPlayBindPending = false;
            sPlayScreen = new java.lang.ref.WeakReference<View>(pg);
            judgePlayPage("bind " + (pg == null ? "null" : name(pg)));
            // ★★★ v0.1.51（r51 用户第 2 / 4 条）：**这里不再整树重绘**。
            //   r47 加的 `invalidateTree(整棵 DecorView)` 每绑定一次就把几百个 View
            //   的 RenderNode 全部作废重录 —— 播放页弹出时的卡顿、以及底部导航切换的
            //   卡顿，主要就是它。实际上「第一帧铺错」这件事已经被下面的宽限期
            //   + 根层兜底解决，不需要靠蛮力重绘。
            invalidateRootSoft(v);
            return true;
        }
        // ★★★ v0.1.50：宽限期内**每一层都算播放页**（不管谁先问、问几次）。
        //   这是「播放页第一帧就整屏一致」的唯一可靠办法 ——
        //   素材加载那一刻就是播放页真正挂载的时刻（宿主只在播放页拉这些图）。
        if (grace) {
            judgePlayPage("grace " + (pg == null ? "null" : name(pg)));
            return true;
        }
        // 换了页面 ⇒ 旧绑定作废，否则「从播放页返回列表页」会一直沿用旧的 true。
        if (bound != null && pg != bound) sPlayScreen = null;
        judgePlayPage("miss " + (pg == null ? "null" : name(pg)));
        return false;
    }

    /** 播放页判定的留痕（每进程 6 条，够看清「为什么这块没糊」）。 */
    private static void judgePlayPage(String s) {
        if (sPlayJudgeLogs >= PLAY_JUDGE_LOG_BUDGET) return;
        sPlayJudgeLogs++;
        log("play page " + s);
    }

    /** 这一块已经被某个玻璃祖先盖过了（只画最外层，避免一格一格画）。 */
    private static boolean coveredByGlass(View v) {
        View p = null;
        try {
            Object par = v.getParent();
            p = par instanceof View ? (View) par : null;
        } catch (Throwable t) {
            return false;
        }
        for (int i = 0; i < 12 && p != null; i++) {
            if (GLASSED.containsKey(p)) return true;
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

    /** 这一层是不是「浮层」（BottomSheet 或独立 Dialog 窗口）。玻璃只服务浮层。 */
    private static boolean inFloatingLayer(View v) {
        return insideBottomSheet(v) || inDialogWindow(v) || mainWindowSheet(v);
    }

    // ══════════════════════════════════════════════════════════════════
    // ★★★ v0.1.47：避让 Float 模块（悬浮窗字幕 / 开关胶囊）
    // ══════════════════════════════════════════════════════════════════

    /** 其它 Xposed 模块注入的 View（它们的类不在宿主 APK 里）。 */
    private static final String[] FOREIGN_PREFIX = {
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
    private static boolean inAppWindow(View v) {
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
    private static boolean foreignModuleView(View v) {
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
    private static boolean mainWindowSheet(View v) {
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

    /**
     * ★ v0.1.40：玻璃失败诊断。
     *
     * <p>这条日志是为了回答那个问题：「为什么有的面板能上模糊、有的上不去？」
     * —— 以前这条路上<b>失败也不报错</b>，静静地就变成了「铺一张清晰壁纸」，
     * 所以十来个版本都没查出来。现在失败必须留痕（限 8 条）。
     */
    /**
     * ★ v0.1.40：封面被「只保绘制、不接管」这条规则放行的次数（诊断用，限 3 条）。
     */
    private static void noteCoverKept() {
        if (sCoverKeptLogs >= 3) return;
        sCoverKeptLogs++;
        log("cover kept (不接管绘制)");
    }

    private static int sCoverKeptLogs;

    private static void noteGlassFail(String why) {
        if (sGlassFailLogs >= 8) return;
        sGlassFailLogs++;
        log("glass MISS (" + why + ") → 保留宿主原底色");
    }

    /** 已经铺过玻璃的那些块（弱引用，View 回收后自动清掉）。 */
    private static final java.util.Map<View, Boolean> GLASSED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Boolean>());

    /** 祖先链（含自己）的类名简写，用于诊断「这块到底挂在哪个浮层下」。 */
    private static String chainOf(View v) {
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
    private static final int GLASS_MIN_W_PCT = 70;
    /** 玻璃表面：高度下限（% 屏高）。底部导航栏 7.5% / 迷你播放器 6% 都在线下。 */
    private static final int GLASS_MIN_H_PCT = 9;
    /**
     * 玻璃表面：高度上限（% 屏高）。
     *
     * <p>★ v0.1.35 曾从 95 收到 <b>85</b>，想靠它把弹层那张 91.4% 的「背景板」踢出玻璃档。
     * <b>★ v0.1.37 撤销：改回 100（等于不设上限）。</b>
     * 原因来自 r42 用户第 1 条 ——「概述页面的高斯模糊失效」。
     * 概述面板就是那块 1272x2534（91.4%），被上限踢出去以后走的是
     * {@code coversScreen} ⇒ 铺<b>清晰壁纸</b> ⇒ 面板和背后的主界面用同一张图
     * ⇒ 用户看到「完全融为一体」。
     * 高度这个维度在宿主上根本分不出「面板」和「页面」（两者都可能是整屏），
     * 真正的分界是<b>在不在 Dialog 窗口里</b>（见 {@link #inDialogWindow}），
     * 所以上限不再承担任何判别职责。
     */
    private static final int GLASS_MAX_H_PCT = 100;
    /** 暗面板的亮度上限（超过它就算「亮底」，亮底是按钮，绝不能换成壁纸）。 */
    private static final float GLASS_DARK_LUMA = 0.42f;
    /**
     * ★★★ v0.1.42（r13 用户第 3 条）：<b>亮底的上限</b>。
     *
     * <p>只有「明显是浅色卡片 / 白色胶囊按钮」才排除。中间调（面板常用
     * {@code #FFECE6EF} 这类浅灰紫、或 {@code #FF252451} 深紫）一律当面板。
     */
    private static final float GLASS_LIGHT_LUMA = 0.72f;
    /**
     * ★★★ v0.1.42：玻璃表面的<b>面积下限</b>（% 屏面积）。
     *
     * <p>12% 刚好包住「下载音轨」这类 1440×882（27.8%）的底部面板，
     * 又能把「一行 1440×40 的分隔条 / 一个 1076×466 的确认框（11%）」挡在外面。
     */
    private static final int GLASS_MIN_AREA_PCT = 12;

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
    private static final int GLASS_BLUR_PCT = 38;

    /** 面板玻璃底图（清晰壁纸模糊 {@link #GLASS_BLUR_PCT}% 后的缓存）。 */
    private static volatile Bitmap sWpGlassBmp;

    /** 玻璃失败诊断的已打条数。 */
    private static int sGlassFailLogs;

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
    private static final float GLASS_SOLID_TINT_PCT = 0.00f;
    /** 半透明底叠回去的强度（0~1）。★ v0.1.39：0.60 → 0.12（同上，紫色阴影的来源）。 */
    private static final float GLASS_TINT_PCT = 0.10f;
    /** react-navigation 的屏幕容器类名片段：祖先里有它 ⇒ 这是页面，不是浮层。 */
    private static final String SCREEN_MARK = "ScreenContentWrapper";

    /** 圆角字段缓存（按 Drawable 类）。 */
    private static final java.util.Map<Class<?>, Object> RADII_FIELD =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object NO_RADII = new Object();

    /**
     * RN 的 {@code BackgroundDrawable} 里那个 {@code float[8]}（四角 × 两个分量）。
     *
     * <p>按<b>类型 + 长度</b>找字段，不看名字 —— release 包 R8 会把字段名混淆掉
     * （颜色字段就变成了 {@code h}），但类型和长度不会变。
     */
    private static java.lang.reflect.Field radiiField(Class<?> cls) {
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
    private static float maxRadiusOf(Drawable d) {
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
    private static boolean isGlassSurface(View v, Drawable d, Rect b) {
        if (d == null || b == null || b.isEmpty()) return false;
        if (sSW <= 0 || sSH <= 0) return false;
        int w = b.width();
        int h = b.height();
        if (w * 100 < (long) sSW * GLASS_MIN_W_PCT) return false;
        if (h * 100 < (long) sSH * GLASS_MIN_H_PCT) return false;
        // ★★★ v0.1.42（r13 用户第 3 条）：**加一条「面积」闸**，让短而宽的面板也能进来。
        //   「下载音轨 / 导入」这类底部面板只有 26~28% 屏高，以前靠
        //   `GLASS_MIN_H_PCT=9` 是能过的 —— 真正把它们挡在外面的是下面那条
        //   「读不出 solid 颜色就保守不动」。面积闸只用来把「一行 40px 的分隔条」
        //   这类东西挡掉，不会再和面板尺寸打架。
        if ((long) w * h * 100 < (long) sSW * sSH * GLASS_MIN_AREA_PCT) return false;
        if (v instanceof android.widget.TextView) return false;
        if (isVideoSurface(v) || isImageLayer(v)) return false;
        // ★★★ v0.1.37（r42 用户第 1 / 2 条）：**唯一的判别式只在「窗口」上**。
        //   r42 的 surface 日志把四张信息卡、面板背景板、以及「新建播放列表」对话框
        //   全都标成了 `dlg=1`，而页面（播放列表详情 / 摘选详情 / 作品详情）全是 `dlg=0`。
        //   于是判据收敛成一句：<b>不在独立 Dialog 窗口里 ⇒ 永远不是玻璃</b>。
        //   v0.1.32 的 `insideScreen` 兜底、v0.1.33 的 `!insideBottomSheet` 组合
        //   全部删掉 —— 它们只是在同一个判据上叠补丁，而且 r38/r40 的 `bs=0` 已经
        //   证明宿主根本不用 BottomSheet 那套类名。
        //   （@gorhom 的浮层万一被渲染在页面树里，`insideBottomSheet` 仍然放行。）
        if (!inFloatingLayer(v)) return false;
        // ★★★ v0.1.39：**只靠 {@link #mainWindowSheet} 命中时要更高**。
        //   Dialog 窗口里的块已经被 r42 日志反复确认过属性，
        //   但「主窗口 + 不在 Screen 里 + 贴底」这条是本轮新开的，万一宿主在
        //   页面外层另有一个贴底容器，9% 的高度下限就太宽松了。
        //   真正的底部弹层少说也占 1/4 屏高（底部面板本体），
        //   靠这一条把「页面里的单行 / 分隔条」彻底挡在外面。
        if (!inDialogWindow(v) && !insideBottomSheet(v)) {
            if (h * 100 < (long) sSH * 20) return false;
        }
        // 弹窗后面那层压暗遮罩（#9919122C / #19122ccc）也在 Dialog 窗口里，
        // 但它必须保持「压暗」的语义 —— 铺成模糊玻璃会把弹窗内容压在自己下面。
        if (isDimMask(d)) return false;
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
        Integer solid = solidColorOf(d);
        if (solid != null && luma(solid) > GLASS_LIGHT_LUMA) return false;
        // ★★★ v0.1.43（r14 用户第 8 条）：**近全屏 + 贴顶 = Dialog 背板，不是面板**。
        //   把背板当玻璃 ⇒ 独立 Dialog 窗口里铺满模糊壁纸 ⇒ 透过透明窗口看到的
        //   不再是主窗口的 App 界面，而是一张静态壁纸（「弹窗后面宿主界面完全消失」）。
        //
        // ★★★ v0.1.44（r15 用户第 2 / 3 / 4 / 5 条）：**判定值必须与动画无关**。
        //   r15 两台机器的日志把这一条钉死了：概述面板（一加12: 1440x2888 top=280；
        //   一加15: 1272x2534 top=238，都是 91% 屏高）在<b>滑入动画中</b>整块被
        //   平移到屏幕外 —— 此刻 {@code getLocationInWindow} 返回 3168/3010，
        //   大于 sEdgeTop ⇒ 闸门放行 ⇒ 铺玻璃；260ms 后动画落位，
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
            int[] loc = LOC.get();
            v.getLocationOnScreen(loc);
            if (loc[0] < -w || loc[0] >= sSW || loc[1] > sSH * 3) {
                glassNo("offscreen-window", v, w, h);
                return false;
            }
        } catch (Throwable ignored) {
        }
        if (topOf(v) <= 4 && h * 100 >= (long) sSH * 97) {
            glassNo("fullscreen-backdrop", v, w, h);
            return false;
        }
        if (solid == null) glassWhy("composite", v, w, h);
        else glassWhy("panel", v, w, h);
        return true;
    }

    /** 玻璃判定的「拒绝原因」留痕（每个签名一次，方便下一轮对账）。 */
    private static void glassNo(String why, View v, int w, int h) {
        logOnce("gn:" + why + signature(v),
                "glass NO (" + why + ") " + name(v) + " " + w + "x" + h
                        + " top=" + topOf(v) + " scr=" + scrOf(v));
    }

    /** 玻璃判定的「接受原因」留痕。 */
    private static void glassWhy(String why, View v, int w, int h) {
        logOnce("gy:" + why + signature(v),
                "glass OK (" + why + ") " + name(v) + " " + w + "x" + h
                        + " top=" + topOf(v) + " scr=" + scrOf(v));
    }

    private static String scrOf(View v) {
        try {
            int[] loc = LOC.get();
            v.getLocationOnScreen(loc);
            return loc[0] + "," + loc[1];
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * 玻璃表面该用哪张底图：<b>单次模糊的条带档</b>，其余按两级兜底。
     *
     * <p>★★★ v0.1.39（r44 用户第 1 条）：<b>撤销 v0.1.38 的「糊两遍」</b>。
     *
     * <p>v0.1.38 为了「看得出是毛玻璃」把面板底换成 {@code FeatureF2.glassBitmap}
     * —— 同一张壁纸<主>连续糊两遍</b>（等效半径翻倍还多）。真机效果被用户一句话否掉：
     * 「概述页面的高斯模糊和之前的效果不一样了」。
     * 双次模糊把壁纸糊成了色块，面板里除了文字什么结构都看不出来，
     * 反而失去了「透过毛玻璃能看到背景轮廓」的质感。
     *
     * <p>⇒ 回到 v0.1.37 及之前一直在用的 {@code sWpBarBmp}
     * （壁纸 + {@link FeatureF2#BAR_BLUR_PCT} 单次模糊，20%），
     * 也就是用户认可的那一版观感。{@code sWpGlassBmp} 不再参与绘制。
     */
    private static Bitmap glassBmp(View v) {
        Bitmap cached = sWpGlassBmp;
        if (cached != null && !cached.isRecycled()) return cached;
        Bitmap base = isPlayWindow(v) ? playBmp() : sWpBmp;
        if (base == null || base.isRecycled()) base = sWpBmp;
        if (base == null || base.isRecycled()) return null;
        Bitmap g = null;
        try {
            g = io.github.ariinyume.morimod.util.BlurUtil.apply(base, GLASS_BLUR_PCT);
        } catch (Throwable ignored) {
        }
        // ⚠️ BlurUtil 失败时是**原样返回 src** —— 那就是「清晰壁纸」。
        //   拿它去铺面板 = 面板与背景逐像素相同 ⇒ 用户看到「这块没有面板」。
        //   宁可返回 null（让宿主自己画原本那层深色底），也绝不铺清晰壁纸。
        if (g == null || g.isRecycled() || g == base) return null;
        sWpGlassBmp = g;
        return g;
    }

    /** 玻璃成功日志条数 / 预算（v0.1.41 探针①）。 */
    private static volatile int sGlassLogs;
    private static final int GLASS_LOG_BUDGET = 12;

    /**
     * ★★★ v0.1.41（r12 路径 A）：<b>玻璃表面的唯一落点</b>。
     *
     * <p>只做一件事：把这块背景 drawable 那支 {@code Paint} 的 shader 换成模糊壁纸
     * （见 {@link GlassPaint}）。<b>不改 bounds、不改颜色字段、不接管绘制</b>。
     *
     * @return null = 不是玻璃表面 / 拿不到位图 / 找不到 Paint ⇒ 调用方走老路
     */
    private static GlassPaint.Token glassToken(Drawable d) {
        if (!sArmed || sSW <= 0 || sSH <= 0) return null;
        if (Boolean.TRUE.equals(REENTRY.get())) return null;
        View v = ownerView(d);
        if (v == null) return null;
        Rect b = d.getBounds();
        if (b == null || b.isEmpty()) return null;
        if (!isGlassSurface(v, d, b)) return null;
        Bitmap g = glassBmp(v);
        if (g == null) {
            noteGlassFail("bmp");
            return null;
        }
        GlassPaint.Token tk = GlassPaint.begin(d, v, b, g, sSW, sSH);
        if (tk == null) {
            noteGlassFail("paint");
            return null;
        }
        sSheet++;
        sPainted = true;
        GLASSED.put(v, Boolean.TRUE);
        // 探针①：把「宿主到底拿这支 Paint 画了多大一块、在哪」记下来。
        //   以前这里只能看到 glass -> 的条数，而 hit() 是按前缀 60 秒限流的
        //   ⇒ 条数 ≠ 次数，前几轮据此判「没走到」全错。这条日志不走 hit()，用独立预算。
        if (sGlassLogs < GLASS_LOG_BUDGET) {
            sGlassLogs++;
            int[] loc = LOC.get();
            int x = -1;
            int y = -1;
            int rawY = -1;
            try {
                v.getLocationOnScreen(loc);
                rawY = loc[1];
                GlassPaint.stableScreenLoc(v, loc, sSW, sSH);
                x = loc[0];
                y = loc[1];
            } catch (Throwable ignored) {
            }
            log("glassA -> " + name(v) + " " + b.width() + "x" + b.height()
                    + " scr=" + x + "," + y
                    + (rawY != y ? " rawY=" + rawY : "")
                    + " b=" + b.left + "," + b.top
                    + " sh=1 bmp=" + g.getWidth() + "x" + g.getHeight());
        }
        hit("glassA -> ", v, b);
        return tk;
    }

    /**
     * 把宿主原来那层底色按 {@link #GLASS_TINT_PCT} 叠回去（不改变 drawable 本身）。
     *
     * <p>用 {@code saveLayerAlpha} 压一层再画，属于「只影响这一次绘制」的非破坏性做法。
     * 全强度叠回去等于把下面的模糊盖死 —— 那正是「退回紫色底」。
     */
    private static void keepTint(Canvas c, Drawable d, Rect b, float pct) {
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

    /**
     * 某个 drawable 背景要不要「换成壁纸」。
     *
     * <p>换的是<b>背景</b>，不是整层 View ⇒ 子内容（文字/图标/按钮）照常画。
     * 这是唯一「怎么换都不会多遮住东西」的落点，所以顶栏/底栏的毛玻璃都走这里。
     *
     * @return true = 壁纸已经画在这块底色的位置上，调用方不要再画宿主原来的颜色
     */
    private static boolean paintWallpaper(Drawable d, Canvas c, boolean requireOpaque) {
        if (!sArmed) return false;
        if (sSW <= 0 || sSH <= 0) return false;
        // 面板分支会「再调一次自己的 draw 把半透明底叠回来」，重入时必须直接放行，
        // 否则就是无限递归。
        if (Boolean.TRUE.equals(REENTRY.get())) return false;
        sSeen++;
        try {
            View v = ownerView(d);
            if (v == null) return false;
            Rect b = d.getBounds();
            if (b == null || b.isEmpty()) return false;
            noteBigSurface(v, d, b);

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
            // ★★★ v0.1.41（r12 路径 A）：**玻璃表面在这里什么都不做，直接放行**。
            //   BackgroundDrawable 那支 Paint 的 shader 已经在 {@link #glassToken}
            //   里换成了模糊壁纸，宿主接下来自己 drawPath / drawRoundRect 就是玻璃，
            //   而且圆角、位置、裁剪全部由宿主自己完成。
            //   这里如果再自己画一遍（v0.1.31~v0.1.40 的老做法），就回到
            //   「整屏位图 + 自己裁圆角」那条路上去了 —— 那正是「面板整块消失」的根因。
            if (isGlassSurface(v, d, b)) return false;

            // ★★★ v0.1.41（r12 用户第 8 条）：**压暗遮罩的判据必须挪到这里**。
            //   v0.1.39 把它写在 swapLeaf 里，而 swapLeaf 开头有一条铁律
            //   「kids > 0 的容器永不跳过」—— PurePlay 那层紫遮罩恰恰是
            //   1440x3168、kids>0（r12 日志）⇒ 永远进不到那条分支
            //   （整份日志 `drop dim = 0` 就是这个）。但它的颜色是
            //   BackgroundDrawable 画出来的，一定经过这里。
            if (isDimMask(d) && d.getOpacity() != PixelFormat.OPAQUE) {
                sScrim++;
                sPainted = true;
                hit("drop dim bg -> ", v, b);
                return true;      // 不画 ⇒ 直接透出下面已经铺好的壁纸
            }

            if (coversScreen(v)) {
                Bitmap bmp = sWpBmp;
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
                if (inFloatingLayer(v) && !GLASSED.containsKey(v)) {
                    sScrim++;
                    sPainted = true;
                    hit("drop screen float -> ", v, b);
                    return true;
                }
                if (!paintAligned(c, bmp, v, b, true)) return false;
                sSkipped++;
                sPainted = true;
                return true;
            }

            // ★ 全宽的渐变 drawable（RN backgroundImage: linear-gradient）
            //   ★★ v0.1.20：按透明度分开处理 —— 「替不透明像素是等价操作，涂半透明是凭空加遮挡」。
            if (!requireOpaque && isFullWidth(v)) {
                if (d.getOpacity() == PixelFormat.OPAQUE) {
                    Bitmap bmp = sWpBmp;
                    if (bmp == null) return false;
                    // ★★★ v0.1.42（r13 用户第 8 条）：浮层里的满宽渐变同样不画。
                    if (inFloatingLayer(v)) {
                        sScrim++;
                        hit("drop grad float -> ", v, b);
                        return true;
                    }
                    if (!paintAligned(c, bmp, v, b, false)) return false;
                    sGrad++;
                    sPainted = true;
                    hit("paint grad bg -> ", v, b);
                    return true;
                }
                sScrim++;
                hit("drop grad bg -> ", v, b);
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
                    Bitmap top = playContext(v) ? playBmp() : sWpBmp;
                    if (top == null) top = sWpBmp;
                    if (top != null && paintAligned(c, top, v, b, false)) {
                        sBar++;
                        sPainted = true;
                        hit("paint top fill -> ", v, b);
                        return true;
                    }
                    sScrim++;
                    hit("drop top bar bg -> ", v, b);
                    return true;
                }
                // v0.1.26：毛玻璃位图暂时拿不到就退回清晰壁纸 —— 宁可少一层模糊，
                // 也绝不「什么都不画」（那在切页瞬间就是一块透明）。
                Bitmap bar = sWpBarBmp != null ? sWpBarBmp : sWpBmp;
                if (bar == null) return false;
                // v0.1.25 用户第 4 条：紧贴页面底边的底栏接着画页面那张图（无缝）
                if (barTop(loc[1]) && sPageBmp != null && sPageBmp != sWpBmp) bar = sPageBmp;
                if (!paintAligned(c, bar, v, b, false)) return false;
                drawMask(c, v, b);
                sBar++;
                sPainted = true;
                hit("paint bar bg -> ", v, b);
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★ 全宽渐变层（{@code LinearGradientView#onDraw}）：把渐变换成壁纸。
     *
     * <p>拦的是 {@code onDraw}，子 View 由 {@code dispatchDraw} 画 ⇒ 内容零损失。
     * 这条是「播放页背景不生效」（§1）和「PurePlay 紫遮罩」（§3）的落点。
     */
    private static boolean dropGradient(View v, Canvas c) {
        if (!sArmed) return false;
        if (sSW <= 0 || sSH <= 0) return false;
        try {
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0) return false;
            if (!isFullWidth(v)) return false;
            Rect r = new Rect(0, 0, w, h);
            // ★★★ v0.1.41（r12 路径 A）：**面板头部的渐变直接不画**。
            //   用户第 5 条前半「模糊区上方多出一行淡阴影的条状区域，再分层出现模糊」：
            //   这块渐变和面板体不是同一个 View，两层各自完成时刻不同 ⇒ 分层；
            //   它本身就是一条半透明紫 ⇒ 被看成「淡阴影条」。
            //   路径 A 之后，面板的玻璃来自每块自己的底色 Paint，
            //   这条渐变纯属多余 ⇒ drop（不画）＝ 直接透出下面的玻璃。
            if (isSheetLike(v) && inFloatingLayer(v)) {
                sScrim++;
                sPainted = true;
                hit("drop sheet grad -> ", v, r);
                return true;
            }
            Bitmap bmp = sWpBmp;
            if (bmp == null) return false;
            if (!paintAligned(c, bmp, v, r, false)) return false;
            sGrad++;
            sPainted = true;
            hit("drop gradient -> ", v, r);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★ 满屏层 / 顶底栏叶子：要么换成壁纸，要么整层不画。
     *
     * <h3>判据顺序（每一条都是真机血证）</h3>
     * <ol>
     *   <li>{@code TextView}：只加阴影，别的都不动（{@link #ensureShadow}）。</li>
     *   <li>满屏 + <b>有子 View</b>：放行。跳了子内容全没（v0.1.17）。</li>
     *   <li>视频 / 图片 / 文字层：放行（它们画的是内容）。</li>
     *   <li>满屏 + 叶子 + 半透明（View 或背景）：<b>不画</b> ⇒ 露出壁纸（遮罩消失）。</li>
     *   <li>满屏 + 叶子 + 不透明：换成壁纸。</li>
     *   <li>全宽叶子条 + <b>不透明底</b>：换成模糊壁纸（毛玻璃）。</li>
     *   <li>全宽叶子条 + <b>透明/半透明</b>：<b>不画</b>。宿主本来就在这上面画半透明
     *       scrim，涂成不透明会把画在它前面的图标糊掉（r24「放大镜上半截消失」）；
     *       不画则直接透出壁纸（用户要的「导航栏直接透出背景」）。</li>
     * </ol>
     */
    private static boolean swapLeaf(View v, Canvas c) {
        if (!sArmed) return false;
        if (sSW <= 0 || sSH <= 0) return false;
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) return false;
        int kids = 0;
        try {
            kids = v instanceof ViewGroup ? ((ViewGroup) v).getChildCount() : 0;
        } catch (Throwable ignored) {
        }
        Rect r = new Rect(0, 0, w, h);
        try {
            if ((long) w * h >= sMinArea) {
                noteBig(v, w, h, kids);
                // ★ 铁律①：容器永不跳过（r23「按钮/选项/列表消失」的唯一原因）
                if (kids > 0) return false;
                if (isVideoSurface(v)) return false;
                // ★ 图片层不碰：它画的是内容，不是底色
                //   ★★ v0.1.21：唯一例外 —— **满屏不透明氛围底图**（播放页那张
                //   playbackspbackground）。它不是内容，是铺在最底下的底色，
                //   换成壁纸只可能露出壁纸；RGB 内容图（主页立绘）在 imgKind 里被判成
                //   content 直接放行。顺手留一条 imglayer 诊断，下一轮能直接看到
                //   播放页到底是不是这条路画的。
                if (isImageLayer(v)) {
                    if (w >= sFullW && h >= (int) (sSH * VEIL_MIN_H_RATIO)) noteImage(v, w, h);
                    // ★★★ v0.1.41：弹层玻璃**只留在 {@code ExpoImageView#draw} 那条钩子里**
                    //   （那里才有 chain，才能把宿主那张紫图按低透明度叠回来）。
                    //   这里如果再处理一次，就是「同一块图被两条路各画一遍」——
                    //   v0.1.40 的「分层出现模糊」有它一份。
                    if (paintAtmos(v, c)) return true;
                    // ★★ v0.1.29 用户第 1 条：满屏图片层「这张图压根读不出来」时补一层
                    //   播放页壁纸 —— 见 paintPlayFallback 的注释。
                    if (paintPlayFallback(v, c, w, h)) return true;
                    return false;
                }

                // ★★ v0.1.23 用户第 1 条：满屏叶子层**永远不留洞**。
                //   v0.1.19~v0.1.22 遇到「满屏 + 半透明」就整层不画 —— 那是个洞。
                //   正常切页面时洞后面刚有别的层画过（侥幸），切后台再回前台绘制顺序一变，
                //   洞后面什么都没有 ⇒ 播放页背后「全透明」（用户连着反馈两轮）。
                //   现在改成**画壁纸**：它下面是壁纸，画同一份像素等于「透出壁纸」，
                //   但没有任何洞，也不多遮任何东西（它是叶子，没有子内容可丢）。
                boolean translucent = v.getAlpha() < 0.99f;
                Drawable bg = v.getBackground();
                if (!translucent && bg != null && bg.getOpacity() != PixelFormat.OPAQUE) {
                    translucent = true;
                }

                // ★★ v0.1.23：**模态弹窗的压暗遮罩必须继续放过**（原样由宿主画）。
                //   它画在弹窗内容之上，一旦涂成不透明毛玻璃，弹窗后面的东西就全被糊掉了
                //   —— 这正是 v0.1.23 用户第 2 条「面板一出来，背后的主界面也跟着糊」的机制。
                //   判据看背景色本身：极暗 / 极亮 + 低彩度 + 半透明 = 宿主的标准 dim 遮罩
                //   （token `overlay/modal1 = #19122ccc`）。
                //   ★ v0.1.24：这里从「不画」改成「放行」—— 不画会留洞（切后台回来露白），
                //   而放行等于交给宿主自己压暗，颜色已经被 LUT 换成强调色，观感一致且零风险。
                if (translucent && isDimMask(bg)) {
                    // ★★★ v0.1.39（r44 用户第 2 / 3 条）：**从「放行」改成「不画」**。
                    //   宿主的弹层遮罩是 token `overlay/modal1 = #9919122C`
                    //   —— 它不是中性灰，是**带紫的深蓝黑**；
                    //   按 60% 带 alpha 铺在亮壁纸上，肉眼就是一层
                    //   「淡紫色的阴影」（用户这一轮的原话）。
                    //   而且它会把**面板周围整片区域**都变成紫灰，
                    //   与“背景应该是壁纸”完全矛盾。
                    //
                    //   v0.1.24 之所以改成放行，担心的是「不画 = 留洞（切后台回来露白）」。
                    //   但这一层是**弹层遮罩**，它在页面内容上方：不画就直接透出
                    //   下面已经铺好的壁纸 / 玻璃，不是一个空洞。
                    //   （用户上一轮已经明确说过「背后的内容淡化效果没了（这个没问题）」。）
                    sScrim++;
                    sPainted = true;
                    hit("drop dim -> ", v, r);
                    return true;
                }

                // ★★ v0.1.23 用户第 3 条：这一层正在做**缩放转场**（打开作品信息页）时，
                //   父层把 scale concat 进了画布，清晰壁纸会跟着放大 / 错位。
                //   用户对这个场景的要求就是「这一块走贴合当前背景的高斯模糊」——
                //   模糊图本来就看不出接缝，缩放几个像素完全自然。
                //   ★ v0.1.27：门槛用 ZOOM_BLUR_MIN（8%）而不是 2% —— 翻图片、
                //   轻微回弹这些「2% 级」的缩放不该把整屏换成毛玻璃。
                if (Math.abs(treeScale(v) - 1f) > ZOOM_BLUR_MIN) {
                    Bitmap zoomBmp = sWpBarBmp;
                    if (zoomBmp == null) return false;
                    if (!paintAligned(c, zoomBmp, v, r, false)) return false;
                    sZoom++;
                    sPainted = true;
                    hit("paint zoom -> ", v, r);
                    return true;
                }

                // ★★ v0.1.26 用户第 2 条：这里以前是「半透明 ⇒ 画毛玻璃」。真机后果是
                //   **整个主界面被糊了一层**（打开作品信息页 / 面板时，这一层是全屏的
                //   半透明底，画什么全屏图就等于给全屏加了一层），用户连着两轮说
                //   「主界面还是会弱模糊，不要这个弱模糊」。
                //   它本来就是「页面底色 / 压暗层」⇒ 应该画**清晰壁纸**（= 透出壁纸），
                //   而不是毛玻璃。模糊只留给真的需要玻璃质感的条与面板。
                // ★★ v0.1.27 用户第 1 条（这是本轮最关键的一条）：
                //   「页面显示的内容（作品封面、音轨信息等）呈现内容显示淡化」。
                //   真因不是模糊，而是这一层：宿主给整页盖了一层 **alpha < 1** 的叶子
                //   （`big ReactViewGroup 1272x2772 kids=0 alpha=0.7`，r32 日志实证）。
                //   View 的 alpha 是通过 `saveLayerAlpha` 施加在**整层画布**上的 ⇒
                //   我们在这里画一张不透明壁纸，等于把壁纸按 0.7 叠在**已经画好的内容上**
                //   ⇒ 内容被「洗淡」，而壁纸反而透出来。
                //   正确动作只有一个：**放行**，让宿主自己画它那层压暗/淡出。
                //   （放行不会留洞：页面底色在上面那条不透明的叶子分支里已经画过了。）
                // ★★ v0.1.28 用户第 1 条「启动宿主第一次播放，播放页背景一定透明」：
                //   上面那句「放行不会留洞」只在 **alpha 已经接近 1** 时成立。
                //   播放页是淡入进场的：这一层的 alpha 从 0 → 1 走动画，
                //   动画前几帧它几乎什么都没画，我们一放行 ⇒ 屏幕上一个洞 ⇒ 全屏透明。
                //   现在按 alpha 三段处理：
                //     · α ≥ 0.99（只是背景半透明）：画壁纸 —— 等价替换，不洗任何内容；
                //     · 0.5 ≤ α < 0.99（压暗 / 淡出层）：放行 —— 洗淡就得靠宿主自己画；
                //     · α < 0.5（淡入前段 / 几乎全透明）：画壁纸 —— 它本来没画出东西，
                //       补上壁纸才没有洞。
                if (translucent) {
                    // ★★★ v0.1.42（r13 用户第 8 条）：**半透明的大叶子一律不画**。
                    //   「弹窗后面的宿主界面完全消失」的元凶就是这里：宿主那个
                    //   RN Modal 在 Dialog 窗口里带一层 1440×3358 / alpha=0.7 的全屏叶子，
                    //   v0.1.41 把它当成「α≥0.99 的底色」整块涂上了<b>不透明壁纸</b>
                    //   —— 那层在 Dialog 窗口里、盖在主窗口之上 ⇒ 弹窗后面的 App 界面
                    //   被一张壁纸整个盖掉（帧 f_0154：只剩壁纸 + 一个白色确认框）。
                    //   Dialog 窗口是透明的，壁纸本来就在主窗口最底下 ⇒ 不画 = 直接透出来。
                    sOverlay++;
                    sPainted = true;
                    hit("drop overlay -> ", v, r);
                    return true;
                }

                Bitmap bmp = sWpBmp != null ? sWpBmp : sWpBarBmp;
                if (bmp == null) return false;
                // ★★★ v0.1.42（r13 用户第 8 条）：**浮层里的整屏叶子绝不铺壁纸**。
                //   浮层都跑在独立 Dialog 窗口里，那个窗口是<b>透明</b>的 ——
                //   我们在这里画的每一笔都在「主窗口的 App 界面」之上；
                //   铺一张不透明壁纸 = 把弹窗背后的宿主界面整块盖掉。
                //   （用户第 8 条「显示弹窗后面的宿主 app 界面依旧完全消失」就是它。）
                //   不画才是正确的：壁纸在主窗口 DecorView 上，自然透出来。
                if (inFloatingLayer(v)) {
                    sScrim++;
                    hit("drop leaf float -> ", v, r);
                    return true;
                }
                if (!paintAligned(c, bmp, v, r, false)) return false;
                // ★★ v0.1.25 用户第 4 条「页面下边缘透出一部分底部导航栏」：
                //   记下「整页底色用的是哪张图 + 它到哪儿结束」，好让紧挨着的底部栏
                //   接着画同一张图（见 barTop 那段），不出现一条模糊度不同的接缝。
                notePageBackdrop(v, h, bmp);
                sLeaf++;
                hit("paint leaf -> ", v, r);
                sPainted = true;
                return true;
            }

            // ★ 全宽叶子条（顶栏 / 底栏 / 迷你播放 / 顶部 scrim）
            if (kids == 0 && isBarLike(v)) {
                if (isVideoSurface(v) || isImageLayer(v)) return false;
                int[] loc = LOC.get();
                v.getLocationInWindow(loc);
                boolean atTop = loc[1] <= sEdgeTop;

                // ★★★ v0.1.43：顶部叶子条同样改「铺同款壁纸」—— 见 paintWallpaper 同名分支。
                //   半透明 scrim 仍 drop（涂实心会糊住前面的图标，r24 血证）；
                //   不透明顶条则补壁纸，避免切后台后的黑条空洞。
                if (atTop) {
                    Drawable bgTop = v.getBackground();
                    boolean opaqueTop = bgTop != null
                            && bgTop.getOpacity() == PixelFormat.OPAQUE
                            && v.getAlpha() >= 0.99f;
                    if (opaqueTop) {
                        Bitmap top = playContext(v) ? playBmp() : sWpBmp;
                        if (top == null) top = sWpBmp;
                        if (top != null && paintAligned(c, top, v, r, false)) {
                            sBar++;
                            sPainted = true;
                            hit("paint top fill -> ", v, r);
                            return true;
                        }
                    }
                    sScrim++;
                    hit("drop top bar -> ", v, r);
                    return true;
                }

                // ★★ v0.1.20 修「迷你播放器偶尔变成全透明」：
                //   底部区（迷你播放 / 底部标签栏）**绝不能 drop** —— drop 就是留个洞，
                //   洞后面不一定有壁纸（那一帧可能还没画）⇒ 一瞬间全透明。
                //   本来不透明 ⇒ 换模糊壁纸；本来透明 ⇒ 原样放行（宿主透明就别动它）。
                Drawable bg = v.getBackground();
                boolean opaque = bg != null && bg.getOpacity() == PixelFormat.OPAQUE
                        && v.getAlpha() >= 0.99f;
                if (!opaque) return false;
                // v0.1.26：毛玻璃位图暂时拿不到就退回清晰壁纸 —— 宁可少一层模糊，
                // 也绝不「什么都不画」（那在切页瞬间就是一块透明）。
                Bitmap bar = sWpBarBmp != null ? sWpBarBmp : sWpBmp;
                if (bar == null) return false;
                // ★★ v0.1.25 用户第 4 条：紧贴页面底边的这条栏，改用**页面自己那张图**。
                //   播放页 / 播放列表的页面底色是「壁纸+模糊」，而底栏默认画的是 20% 的
                //   毛玻璃 —— 两者模糊度不同，交界处就是一条硬边，用户看到的就是
                //   「页面下边缘透出一部分底部导航栏」。接着画同一张图 ⇒ 完全连续。
                if (barTop(loc[1]) && sPageBmp != null && sPageBmp != sWpBmp) bar = sPageBmp;
                if (!paintAligned(c, bar, v, r, false)) return false;
                drawMask(c, v, r);
                sBar++;
                sPainted = true;
                hit("paint bar leaf -> ", v, r);
                return true;
            }

            // ★★ v0.1.21 用户第 3 条：底部面板本体如果是<b>叶子</b>（一张纯色/图片铺成，
            //   内容在兄弟层上），同样要处理。叶子没有子内容 ⇒ drop 绝不丢东西。
            //   ★★★ v0.1.41（r12 路径 A）：这里从「铺玻璃」改成 **drop**。
            //   铺玻璃＝画一张整屏位图，落点靠 getLocationInWindow 算 ⇒ 就是
            //   「卡住一块壁纸」和「面板消失」的那条路。现在面板的玻璃来自
            //   它自己的底色 Paint（见 glassToken），这条叶子层不画即可。
            if (kids == 0 && !isVideoSurface(v) && isSheetLike(v)
                && inFloatingLayer(v)) {
                sScrim++;
                sPainted = true;
                hit("drop sheet leaf -> ", v, r);
                return true;
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    /**
     * 记下「整页底色」用的是什么图、到哪儿结束（v0.1.25 用户第 4 条）。
     */
    private static void notePageBackdrop(View v, int h, Bitmap bmp) {
        try {
            int[] loc = LOC.get();
            v.getLocationInWindow(loc);
            sPageBottom = loc[1] + h;
            sPageBmp = bmp;
        } catch (Throwable ignored) {
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
    private static boolean inMainWindow(View v) {
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
    private static final java.util.Map<View, Bitmap> PLAY_BG =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Bitmap>());

    /** 播放页窗口铺底的留痕条数。 */
    private static volatile int sPlayBgLogs;
    private static final int PLAY_BG_LOG_BUDGET = 4;

    /** 播放页窗口（root → 最近一次见到播放档模糊的时间戳）。 */
    private static final java.util.Map<View, Long> PLAY_WIN =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Long>());

    // ★★★ v0.1.60（r61 用户第 3 条：媒体库的封面变成了「壁纸的模糊色块」）：
    //   120 秒 → **2.5 秒**。
    //
    //   `isPlayWindow()` 一旦为真，就会把「播放档壁纸（壁纸+模糊）」铺到
    //   大块图片层上（`paintBlurBg` 的接管分支 / TextureView 分支都用它）。
    //   从播放页退回媒体库之后，这面旗还挂着 120 秒 ⇒ 媒体库里那些
    //   **防偷窥模糊封面**被换成了一小块播放档壁纸 —— 正是用户截图里
    //   「封面变成高斯模糊的壁纸色块」。
    //   播放页每帧都在 `markPlayWindow` 续期，所以 2.5 秒足够覆盖真正的播放页，
    //   页面一离开就会自然过期，不会再污染其它页面。
    private static final long PLAY_WIN_TTL_MS = 2500L;

    /** 记下「这个窗口是播放页」——{@code setBlurRadius(10)} 只会出现在播放页。 */
    private static void markPlayWindow(View v) {
        try {
            View root = v.getRootView();
            boolean first = false;
            if (root != null) {
                first = PLAY_WIN.put(root, System.currentTimeMillis()) == null;
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
                if (first) invalidateRootSoft(root);
            }
            // ★★★ v0.1.39：把「播放页」的保持窗口一起续上。
            //   有整屏 blurRadius≥8 的底图 = 铁证在播放页；这样即使进度条那一帧
            //   被宿主 detach（切后台最常见），同一屏里其余的层也不会各自判成清晰壁纸
            //   ——「背景正常但封面区域是没加模糊的自定义背景」就是这么来的。
            //   ★ v0.1.47：保持窗同样带页面锚点（不再全局污染别的页面）。
            sPlayHoldUntil = System.currentTimeMillis() + PLAY_HOLD_MS;
            sPlayHoldPage = new java.lang.ref.WeakReference<>(pageAnchorOf(v));
        } catch (Throwable ignored) {
        }
    }

    /** 这个窗口是不是播放页窗口（v0.1.30）。 */
    private static boolean isPlayWindow(View v) {
        try {
            View root = v.getRootView();
            if (root == null) return false;
            Long t = PLAY_WIN.get(root);
            return t != null && System.currentTimeMillis() - t < PLAY_WIN_TTL_MS;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 播放页该用的底图：壁纸 + 用户设定的播放模糊；缺失时退回条带模糊 / 清晰壁纸。 */
    private static Bitmap playBmp() {
        if (sWpPlayBmp != null && !sWpPlayBmp.isRecycled()) return sWpPlayBmp;
        if (sWpBarBmp != null && !sWpBarBmp.isRecycled()) return sWpBarBmp;
        return sWpBmp;
    }

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
    private static boolean barTop(int top) {
        if (sPageBottom <= 0 || sPageBmp == null) return false;
        int tol = Math.max(8, (int) (sSH * 0.02f));
        return Math.abs(top - sPageBottom) <= tol;
    }

    /**
     * ★ 所有层共用的绘制：把位图铺在<b>屏幕坐标系</b>上，再用这一层自己的范围裁剪。
     *
     * <p>Canvas 在 {@code View#draw} 里已经被平移到该 View 的左上角，所以「屏幕矩形」
     * 在本地坐标里就是 {@code (-locX, -locY, ...)}。不论哪一层、尺寸差多少，
     * 画出来的都是<b>同一份像素</b> ⇒ 叠多少层都严丝合缝（修 v0.1.16 的重影）。
     *
     * <p>⚠️ 只用 {@code drawBitmap}，<b>不碰 Drawable 实例</b> —— 同一个实例可能同时挂在
     * {@code DecorView} 背景上，{@code setBounds} 会 {@code invalidateSelf} 打成无限重绘
     * （v0.1.17 播放页闪屏 / 黑屏的根因）。
     */
    private static boolean paintAligned(Canvas c, Bitmap bmp, View v, Rect clip,
                                        boolean zoomDegrade) {
        if (bmp == null || bmp.isRecycled() || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) return false;
        // ★★ v0.1.23 用户第 3 条：只要是「清晰壁纸」而这一层正在<b>大跨度</b>缩放转场
        //   （作品信息页打开/关闭那一下），就自动降级成毛玻璃，免得清晰壁纸跟着放大、
        //   和底下 WindowBg 那张对不上。
        //   ★★ v0.1.27 用户第 4 条：阈值从 2% 抬到 8%。2% 太小了 —— 图片浏览翻到
        //   最后一张时的回弹、列表的轻微缩放都会命中，结果「最后一张图片背后多一层
        //   模糊」。真正的转场缩放是 20%+，8% 这条线正好把两者分开。
        //   ★★ v0.1.28 用户第 2 条：**降级只给显式要求的调用方**（zoomDegrade）。
        //   以前是「只要画的是清晰壁纸就自动降级」，于是图片浏览翻页的回弹、
        //   播放页封面切换的微缩放，都会把<b>内容图</b>连带换成毛玻璃 ——
        //   用户看到的就是「最后一张图片背后一层接近全屏的高斯模糊」。
        //   现在内容图那条路完全不参与降级。
        if (zoomDegrade && bmp == sWpBmp && sWpBarBmp != null
                && Math.abs(treeScale(v) - 1f) > ZOOM_BLUR_MIN) {
            bmp = sWpBarBmp;
        }
        int[] loc = LOC.get();
        try {
            // ★★★ v0.1.41（r12 证据 A）：**改用 getLocationOnScreen**。
            //   getLocationInWindow 给的是「本窗口内坐标」，而信息面板跑在独立 Dialog
            //   窗口里（r12 日志 top=280）⇒ 位图整体被推离面板，再被 clipRect 裁干净，
            //   而 drawBitmap 画在裁剪区外不抛异常 ⇒ 静默成功 ⇒ 面板整块消失。
            //   主窗口 windowLeft/Top 恒为 0 ⇒ 这一改对主界面 / 底栏 / 迷你播放零影响。
            v.getLocationOnScreen(loc);
        } catch (Throwable t) {
            return false;
        }
        // ★★ v0.1.23 定案（r28）：画布的坐标系 = 「本层的本地坐标系」。
        //   View#draw 收到的 Canvas 已经被父层平移到这一层的原点，所以「屏幕坐标」
        //   在这里就是 (屏幕坐标 − 本层在窗口里的位置)。v0.1.22 试图用
        //   Canvas#getMatrix 的逆矩阵把坐标系拉回窗口空间 —— 硬件画布上那个矩阵
        //   并不包含父层的平移（实测 r28：底部导航栏 / 迷你播放 / 面板全部画到屏幕外，
        //   信息页则整体向下错位 loc.y 个像素）。这里退回 r23~r27 用的、被真机验证过的算法。
        RectF dst = coverRect(bmp, -loc[0], -loc[1]);
        Rect src = SRC.get();
        src.set(0, 0, bmp.getWidth(), bmp.getHeight());
        int save = c.save();
        boolean drew = false;
        try {
            c.clipRect(clip);          // clip 本来就是本地坐标，直接用
            c.drawBitmap(bmp, src, dst, BMP_PAINT);
            drew = true;
        } catch (Throwable ignored) {
        } finally {
            c.restoreToCount(save);
        }
        return drew;
    }

    /**
     * ★★★ v0.1.41（r12 路径 A）：<b>局部绘制</b> —— 落点永远错不了的那一版。
     *
     * <h3>和 {@link #paintAligned} 的唯一差别</h3>
     * <ul>
     *   <li>位置来源：这里用 {@code getLocationOnScreen()}（把窗口偏移算进去，
     *       Dialog / Popup 里也准）；{@code paintAligned} 用的是
     *       {@code getLocationInWindow()}（Dialog 窗口原点是窗口自己的 0，不是屏幕 0）。</li>
     *   <li>裁剪：这里的 {@code clip} 是<b>本层局部矩形</b> {@code (0,0,w,h)}，
     *       不是屏幕矩形 ⇒ 位图不可能被推到裁剪区外。</li>
     * </ul>
     *
     * <p>为什么这一条能吃掉「面板整块消失」：老路径里 {@code dst} 由 {@code -loc} 算出，
     * {@code clipRect} 用的是 local 矩形，两者一旦不是同一个坐标系，位图就整块落在
     * 裁剪区外 —— 而 {@code drawBitmap} <b>不抛异常</b>，于是 {@code drew=true}、
     * 静默成功、面板变透明。现在落点和裁剪同在本地坐标系，结构上不可能错。
     */
    private static boolean paintLocal(Canvas c, Bitmap bmp, View v, Rect clip) {
        if (bmp == null || bmp.isRecycled() || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) {
            return false;
        }
        if (clip == null || clip.isEmpty()) return false;
        int[] loc = LOC.get();
        try {
            v.getLocationOnScreen(loc);
        } catch (Throwable t) {
            return false;
        }
        RectF dst = coverRect(bmp, -loc[0], -loc[1]);
        Rect src = SRC.get();
        src.set(0, 0, bmp.getWidth(), bmp.getHeight());
        int save = c.save();
        boolean drew = false;
        try {
            c.clipRect(clip);
            c.drawBitmap(bmp, src, dst, BMP_PAINT);
            drew = true;
        } catch (Throwable ignored) {
        } finally {
            c.restoreToCount(save);
        }
        return drew;
    }

    /**
     * 面板换底时**保住圆角**（v0.1.22）。
     *
     * <p>宿主的底部面板是圆角矩形，而我们的「换底」是画一个矩形 —— 不裁就会在
     * 两个上角露出方角壁纸，比原来的紫底更难看。这里直接问 drawable 要 Outline：
     * 有半径就按圆角裁，没有（或拿不到）就整块矩形（等于和原来一样）。
     */
    private static void clipByOutline(Canvas c, Drawable d, Rect bounds) {
        if (d == null || bounds == null) return;
        float rad = 0f;
        try {
            android.graphics.Outline o = OUTLINE.get();
            o.setEmpty();
            // ⚠️ Drawable#getOutline(Outline) 返回 void（会往传入的 Outline 里写），
            //    只有 View#getOutline 才返回 boolean —— 这里必须判 isEmpty()。
            d.getOutline(o);
            if (!o.isEmpty()) rad = o.getRadius();
        } catch (Throwable ignored) {
        }
        float[] rr = null;
        if (rad <= 0.5f) {
            // ★★★ v0.1.40：**2.22.0 上 getOutline 读不出圆角**（r44 日志里所有面板
            //   都是 `r=0`）⇒ 面板被我们自己铺成直角矩形，四角糊成方块，
            //   用户的「圆角裁切也没到位」就是这个。
            //   这里改为直接去 drawable 里读 `float[]` 圆角数组（RN 的
            //   BackgroundDrawable 用一个 float[8] 存四角 x/y 半径）。
            rr = cornerRadiiOf(d);
            if (rr == null) return;
        }
        try {
            android.graphics.Path p = PATH.get();
            p.reset();
            RectF rf = new RectF(bounds);
            if (rr == null) {
                p.addRoundRect(rf, rad, rad, android.graphics.Path.Direction.CW);
            } else {
                p.addRoundRect(rf, rr, android.graphics.Path.Direction.CW);
            }
            c.clipPath(p);
        } catch (Throwable ignored) {
        }
    }

    /**
     * ★★★ v0.1.40：从 drawable 里取四角圆角（长度 8，顺序
     * {@code [tlX,tlY,trX,trY,brX,brY,blX,blY]}）；读不到返回 null。
     *
     * <p>为什么不用 {@code getOutline}：宿主 2.22.0 的 {@code BackgroundDrawable}
     * 根本没把四角半径写进 Outline（r44 日志里所有面板 {@code r=0}）——
     * 于是我们自己铺的玻璃永远是直角矩形，四角糊成方块。
     * 内部那个 {@code float[]} 一定存在（否则圆角根本画不出来），直接读它。
     */
    private static float[] cornerRadiiOf(Drawable d) {
        if (d == null) return null;
        java.lang.reflect.Field f = radiiField(d.getClass());
        if (f == null) return null;
        try {
            Object v = f.get(d);
            if (!(v instanceof float[])) return null;
            float[] a = (float[]) v;
            float[] out = new float[8];
            if (a.length >= 8) {
                System.arraycopy(a, 0, out, 0, 8);
            } else if (a.length == 4) {
                for (int i = 0; i < 4; i++) {
                    out[i * 2] = a[i];
                    out[i * 2 + 1] = a[i];
                }
            } else {
                return null;
            }
            float max = 0f;
            for (float x : out) max = Math.max(max, x);
            return max > 0.5f ? out : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 条带压暗：在同一个屏幕矩形上叠一层 10% 黑，保证图标文字可读。 */
    private static void drawMask(Canvas c, View v, Rect clip) {
        if (MASK_PAINT.getAlpha() <= 0) return;
        int[] loc = LOC.get();
        try {
            v.getLocationInWindow(loc);
        } catch (Throwable t) {
            return;
        }
        RectF dst = DST.get();
        dst.set(-loc[0], -loc[1], -loc[0] + sSW, -loc[1] + sSH);
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
     * center-crop：把位图等比放大到刚好覆盖「整个屏幕」，再居中裁剪。
     *
     * <p>与 {@code CoverDrawable} 的算法一致 —— 顶底栏和页面必须用同一个缩放，
     * 否则毛玻璃和清晰区会错位。
     */
    private static RectF coverRect(Bitmap bmp, float left, float top) {
        RectF dst = DST.get();
        int iw = bmp.getWidth();
        int ih = bmp.getHeight();
        float scale = Math.max((float) sSW / iw, (float) sSH / ih);
        float dw = iw * scale;
        float dh = ih * scale;
        dst.set(left + (sSW - dw) / 2f, top + (sSH - dh) / 2f,
                left + (sSW + dw) / 2f, top + (sSH + dh) / 2f);
        return dst;
    }

    /** 这块 View 是不是「满宽」。 */
    private static boolean isFullWidth(View v) {
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
    private static boolean isBarLike(View v) {
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
    private static boolean isSheetLike(View v) {
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
     * 这一层是不是「从屏幕底部升起来」的那一大片（v0.1.31 抽出来单独用）。
     *
     * <p>两条：<b>顶边不贴屏幕顶</b>（面板一定是从下面升上来的）、
     * <b>底边贴屏幕底</b>（±3% 屏高，容圆角留白 / 手势条）。
     */
    private static boolean isBottomAnchored(View v) {
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
    private static boolean isVideoSurface(View v) {
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
    private static boolean isImageLayer(View v) {
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
    private static View ownerView(Drawable d) {
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
    private static boolean coversScreen(View v) {
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
        }
    }

    private static final Runnable POLL = new Runnable() {
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
        boolean changed = (ready != sArmed) || sDirty;
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
        sDirty = false;
        // ★ v0.1.22 用户第 4 条：强调色跟在「壁纸 + 风格 + 开关」后面。
        //   内部有指纹短路（同一张图同一风格不会重复采样），这里每轮调是安全的。
        //   ★★ v0.1.26：传的是 `use`（稳定位图）而不是 `bmp` —— `bmp` 在解码间隙为 null，
        //   传它会让指纹在 "none" 与真实图之间反复跳，强调色跟着开开关关（还会连带
        //   触发 retintTree 把整棵树重新染色，肉眼看到的就是颜色闪）。
        AccentColor.update(use, cfg);

        updateMetrics(decorOf(a));

        if (changed) {
            // ★ 用户第 5 条：切回宿主默认背景（armed 掉下去）⇒ 立刻关掉所有文字阴影。
            if (!ready) clearAllShadows();
            invalidateTree(decorOf(a));
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
        }
        maybeSummary();
    }

    /** 刷新屏幕尺寸与各项像素门槛（每轮一次，旋转 / 分屏也能跟上）。 */
    private static void updateMetrics(View decor) {
        try {
            if (decor == null) {
                sMinArea = Long.MAX_VALUE;
                sSW = 0;
                sSH = 0;
                return;
            }
            int rw = decor.getWidth();
            int rh = decor.getHeight();
            if (rw <= 0 || rh <= 0) {
                sMinArea = Long.MAX_VALUE;
                sSW = 0;
                sSH = 0;
                return;
            }
            sSW = rw;
            sSH = rh;
            sMinArea = (long) (FULL_SCREEN_RATIO * rw * rh);
            float d = decor.getResources().getDisplayMetrics().density;
            sEdgeTop = (int) (EDGE_TOP_DP * d);
            sEdgeBottom = (int) (EDGE_BOTTOM_DP * d);
            sBarMaxH = (int) (BAR_MAX_H_DP * d);
            sFullW = (int) (FULL_W_RATIO * rw);
        } catch (Throwable t) {
            sMinArea = Long.MAX_VALUE;
        }
    }

    /**
     * 登记一层「满屏」View（每个类只打一次，最多 {@link #BIG_LOG_BUDGET} 行）。
     *
     * <p>从 {@code View#draw} 里看到的是<b>真的在被画的那些层</b>，
     * 比「延迟遍历视图树」可靠（r18/r20 两次都是空清单）。
     */
    private static void noteBig(View v, int w, int h, int kids) {
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

    private static int topOf(View v) {
        try {
            return v.getTop();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String alphaOf(View v) {
        try {
            return String.valueOf(v.getAlpha());
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 这一层的 alpha（读不到就当 1 = 完全不透明，走最保守的「画壁纸」分支）。 */
    private static float alphaVal(View v) {
        try {
            return v.getAlpha();
        } catch (Throwable t) {
            return 1f;
        }
    }

    private static String desc(Drawable d) {
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

    private static String name(View v) {
        try {
            return v.getClass().getSimpleName();
        } catch (Throwable t) {
            return "?";
        }
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
    private static void hit(String prefix, View v, Rect r) {
        if (!logOnce("hit:" + prefix + signature(v), null)) return;
        log(prefix + name(v) + " " + r.width() + "x" + r.height()
                + " top=" + topOf(v) + " kids=" + kidsOf(v) + " bg=" + desc(v.getBackground())
                + " a=" + alphaOf(v));
    }

    /** 「这个 key 只打一次」的登记表（13 轮日志饥饿的解法，见 {@link #hit}）。 */
    private static final java.util.Map<String, Boolean> ONCE =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    private static volatile int sOnceTotal;
    private static final int ONCE_TOTAL = 140;

    /**
     * 同一个 key 只执行一次。
     *
     * @param msg 非 null 时顺带打印这一行
     * @return true = 这次是新 key（已登记）
     */
    private static boolean logOnce(String key, String msg) {
        if (sOnceTotal >= ONCE_TOTAL) return false;
        if (key != null) {
            if (ONCE.containsKey(key)) return false;
            ONCE.put(key, Boolean.TRUE);
        }
        sOnceTotal++;
        if (msg != null) log(msg);
        return true;
    }

    /** 视图签名：类名 + 尺寸 + 窗口内位置（同一块界面重复绘制时签名稳定）。 */
    private static String signature(View v) {
        try {
            int[] loc = LOC.get();
            v.getLocationInWindow(loc);
            return name(v) + "@" + v.getWidth() + "x" + v.getHeight()
                    + "+" + loc[0] + "," + loc[1];
        } catch (Throwable t) {
            return "?";
        }
    }

    private static int curColor(TextView tv) {
        try {
            return tv.getCurrentTextColor();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 每个命中动作的统计：[次数, 上次打印时刻]（v0.1.28）。 */
    private static final java.util.Map<String, long[]> HIT_STAT =
            new java.util.HashMap<>();

    /** 同一动作的日志间隔（v0.1.28）。 */
    private static final long HIT_LABEL_GAP_MS = 60000L;

    private static volatile int sHitTotal;
    private static final int HIT_TOTAL = 400;

    private static int kidsOf(View v) {
        try {
            return v instanceof ViewGroup ? ((ViewGroup) v).getChildCount() : 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 每 10 秒最多一条：这一轮到底画了多少层（判断「生效没生效」的最快线索）。 */
    private static void maybeSummary() {
        long now = System.currentTimeMillis();
        if (now - sLastSummaryAt < 10000L) return;
        if (sSeen == 0 && sSkipped == 0 && sLeaf == 0 && sBar == 0
                && sGrad == 0 && sOverlay == 0 && sScrim == 0 && sVeil == 0) return;
        sLastSummaryAt = now;
        if (sLogs >= LOG_BUDGET) return;
        sLogs++;
        log("draw seen=" + sSeen + " wall=" + sSkipped + " leaf=" + sLeaf
                + " bar=" + sBar + " grad=" + sGrad + " mask=" + sOverlay
                + " scrim=" + sScrim + " veil=" + sVeil + " atmos=" + sAtmos
                + " play=" + sBlurBg + " sheet=" + sSheet + " zoom=" + sZoom
                + " armed=" + (sArmed ? 1 : 0) + " live=" + (sPainted ? 1 : 0));
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
    }

    /** 当前是否真的在替宿主画壁纸（只读）。 */
    public static boolean painted() {
        return sPainted;
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
            PLAY_WIN.clear();
            sPlayScreen = null;
            sSeekPageAnchor = null;
            sPlayHoldUntil = 0L;
            sPlayHoldPage = null;
            // ★★★ v0.1.47（r47 用户第 4 条）：**只清缓存不够，必须让整棵树重画**。
            //   「迷你条状态下退后台→回前台→再展开播放页」那一屏，各层的绘制指令
            //   还躺在硬件加速的 RenderNode 里 —— 状态翻转了也没有任何一层会重跑
            //   draw（这正是 v0.1.25 就写进 apply() 注释的那条铁律）。
            //   所以除了清掉封面救援节流 / 玻璃缓存，还要把 DecorView 下所有层
            //   invalidate 一遍，强制它们用此刻的状态重新录一帧。
            View decor = a == null ? null : decorOf(a);
            if (decor != null) invalidateTree(decor);
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
        // ★ v0.1.41：Paint 字段缓存一并作废（换壁纸 / 关功能后重新认一次）。
        GlassPaint.reset();
        sPainted = false;
        // ★ v0.1.22：恢复默认背景 ⇒ 强调色一并关掉（用户第 4 条「开关关闭时还原宿主默认」）。
        AccentColor.reset();
        // ★ 用户第 5 条：切回宿主默认背景 ⇒ 所有文字阴影关闭、回到宿主原样。
        clearAllShadows();
        Activity a = sPoll;
        if (a != null) invalidateTree(decorOf(a));
    }

    /**
     * 把加过的文字阴影全部撤掉（v0.1.20 用户第 5 条）。
     *
     * <p>{@link #ensureShadow} 在 {@link #sArmed} 为假时直接 return，所以不会自己清 ——
     * 必须在这里主动遍历（只有真的加过阴影的那批 TextView 在表里，量很小）。
     */
    private static void clearAllShadows() {
        java.util.List<TextView> list;
        synchronized (SHADOWED) {
            if (SHADOWED.isEmpty()) {
                LIGHT_BG.clear();
                return;
            }
            list = new java.util.ArrayList<>(SHADOWED.keySet());
        }
        for (TextView tv : list) {
            try {
                Paint p = tv.getPaint();
                if (p != null) p.clearShadowLayer();
                SHADOWED.remove(tv);
                tv.invalidate();
            } catch (Throwable ignored) {
            }
        }
        LIGHT_BG.clear();
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
        invalidateTree(decor);
    }

    private static void invalidateTree(View root) {
        if (root == null) return;
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
    private static int touch(View v, int depth) {
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
    private static View decorOf(Activity a) {
        try {
            return a == null || a.getWindow() == null ? null : a.getWindow().getDecorView();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void log(String msg) {
        XposedCompat.log(TAG + " " + msg);
    }
}
