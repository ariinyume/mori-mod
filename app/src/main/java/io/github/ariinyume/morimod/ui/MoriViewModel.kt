package io.github.ariinyume.morimod.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import io.github.ariinyume.morimod.config.ConfigBus
import io.github.ariinyume.morimod.config.ConfigStore
import io.github.ariinyume.morimod.config.MoriConfig
import io.github.ariinyume.morimod.status.HostStatusReceiver
import io.github.ariinyume.morimod.util.XPrefs

/**
 * 设置页状态中枢。
 *
 * 刻意**不新建**配置类：直接持有 Java 层的 [MoriConfig]，两层靠这一个纯数据类解耦
 * （见 build.gradle 里的「分层铁律」）。
 *
 * 持久化：每次改动都「写本地 prefs + 原子写 JSON + 广播通知宿主」，
 * 所以重启宿主与重启模块进程后配置都还在（提示词：数据当真实数据，重启保留）。
 */
class MoriViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * ★★★ v0.1.39：这次启动**一份配置都没读到**（回落到了默认值）。
     *
     * <p>这种状态下直接落盘 = 把用户的壁纸 / 素材池 / 字体清单一次性清空，
     * 所以 {@link #persist} 会先做一次「关键字段回补」（见 {@link #restoreCritical}）。
     *
     * <p>⚠️ 必须声明在 [config] **之前**：Kotlin 的属性按声明顺序初始化，
     * 否则 {@code loadInitial()} 里刚置的 true 会被后面的 {@code = false} 覆盖。
     */
    private var loadDegraded = false

    /**
     * ★★★ v0.1.39：用户<b>明确</b>要清掉背景（「恢复默认背景 / 清空背景池」）时为 true。
     *
     * <p>没有这个标志就没法区分「用户要清空」和「我们读丢了」——
     * 而这两者的正确动作正好相反（一个要清，一个必须保住）。
     */
    private var bgClearedByUser = false

    /**
     * 当前配置。
     *
     * ⚠️ **每次改动都会换成一份深拷贝**（见 [persist] 与 [touch]）。
     *    原因：Compose 的 `mutableStateOf` 只认「值的身份变化」，而 [MoriConfig] 是普通
     *    Java 类、字段都是就地改 —— 只改字段的话 `vm.config` 读出来还是同一个对象，
     *    任何「读 `cfg.xxx` 再渲染」的作用域都不会重组：真机表现就是
     *    **开关点下去界面纹丝不动**（v0.0.4 的 bug）。
     */
    var config: MoriConfig by mutableStateOf(loadInitial())
        private set

    /** 宿主作用域探测结果：null = 检测中。 */
    var hostScopeAlive: Boolean? by mutableStateOf(null)
        private set

    /**
     * 最后一次「确认宿主活着」的墙上时间。
     *
     * <p>两个来源：① 设置页 ping 的 pong；② 宿主的 20 秒心跳（由模块应用的清单接收器
     * 落到 prefs，见 {@code status.HostStatusReceiver}）。
     * 判定不再依赖「某一次问答有没有在 1.2 秒内赶到」——真机上宿主在后台被冻结时
     * 那个截止时间经常赶不上，用户看到的就是「明明开着却写未运行」。
     */
    private var hostLastSeenWall: Long = 0L

    /** 本页开始探测的时刻（用于「一直没见到宿主」时的终态兜底）。 */
    private val probeStartElapsed: Long = SystemClock.elapsedRealtime()

    /** 一次性提示文案（Snackbar 用）；消费后置 null。 */
    var toast: String? by mutableStateOf(null)

    /** 桌面图标别名组件（「隐藏模块桌面图标」作用对象）。 */
    private val launcherAlias = ComponentName(
        app.packageName, "io.github.ariinyume.morimod.ui.LauncherAlias",
    )

    init {
        // 语言随配置走：ViewModel 一建出来就先应用一次，
        // 保证首帧渲染的就是用户上次选的语言。
        Strings.apply(Lang.of(config.lang))
        // v0.1.6：修掉旧版本导出的 `xxx..ttf`（见方法注释），必须在任何读取之前跑，
        // 否则「我的字体」列表里那一项会因为路径失效直接消失。
        repairFontFileNames()
        // 桌面图标状态与配置对齐（配置可能来自导入的备份 / 另一台设备的配置）。
        syncLauncherIcon(persist = false)
    }

    /**
     * 把旧版本留下的 `xxx..ttf` 改成正常文件名，并同步配置里的所有引用（v0.1.6）。
     *
     * <p>真机证据：
     * <pre>
     *   [MoriMod:Sync] asset req sent fonts/DingTalkJinBuTi-Regular..ttf
     *   [MoriMod:Sync] asset denied fonts/DingTalkJinBuTi-Regular..ttf
     * </pre>
     * 名字里的 `..` 会被素材通道当成父目录直接拒绝 ⇒ 宿主永远拿不到字体 ⇒
     * 「重新导入的本地字体点了没用、回到系统字体」，而且列表里每项后面还挂一个点。
     * 这里把磁盘上的名字改对、把配置指过去，用户**不用重新导入**。
     */
    private fun repairFontFileNames() {
        val ctx = getApplication<Application>()
        val map = try {
            FileStore.repairFontFileNames(ctx)
        } catch (t: Throwable) {
            emptyMap()
        }
        if (map.isEmpty()) return
        var changed = false
        val cur = config.f4FontFile
        if (cur.isNotEmpty() && map.containsKey(cur)) {
            config.f4FontFile = map.getValue(cur)
            changed = true
        }
        for (i in config.f4FontLib.indices) {
            val p = config.f4FontLib[i]
            val n = map[p]
            if (n != null) {
                config.f4FontLib[i] = n
                changed = true
            }
        }
        if (changed) persist()
    }

    // ── 载入 ────────────────────────────────────────────────────────

    private fun loadInitial(): MoriConfig {
        val ctx = getApplication<Application>()
        // ★★★ v0.1.39（r44 一加 12 第 2 条「重启宿主壁纸丢失」）：
        //   旧实现是「prefs 优先」，只要 prefs 里还剩一份（哪怕是上次写失败留下的旧值），
        //   就再也不看 JSON 文件；而宿主进程读的恰恰是 JSON 文件 ⇒ 两条通道分叉后，
        //   这里一保存就用旧值把新值覆盖掉，壁纸/素材路径永久消失。
        //   现在改成「两份都解析，取 savedAt 更大的那一份」（见 ConfigStore.loadBest）。
        val best = ConfigStore.loadBest(ctx)
        if (best != null) return best
        // 两份都读不到：可能是历史遗留/被清过。先试上一份留档，避免直接回落默认值。
        val prev = try {
            ConfigStore.loadPrevious(ctx)
        } catch (t: Throwable) {
            null
        }
        if (prev != null) {
            prev.clamp()
            return prev
        }
        loadDegraded = true
        return MoriConfig()
    }

    /**
     * ★★★ v0.1.39：这次启动**一份配置都没读到**（回落到了默认值）。
     *
     * <p>这种状态下直接落盘 = 把用户的壁纸 / 素材池 / 字体清单一次性清空。
     * 所以 {@link #persist} 会先做一次「关键字段回补」（见 {@link #restoreCritical}）。
     */

    /**
     * 用户<b>明确</b>要清掉背景（「恢复默认背景 / 清空背景池」）时为 true。
     *
     * <p>没有这个标志就没法区分「用户要清空」和「我们读丢了」——
     * 而这两者的正确动作正好相反（一个要清，一个必须保住）。
     */

    /**
     * ★★★ v0.1.39：保存前把「莫名变空」的关键字段补回来。
     *
     * <p>只补三个会真正让用户察觉到丢失的字段：当前背景、背景素材池、字体清单。
     * 判断顺序：本次读到的上一份（JSON 留档）→ 什么都没有就算了。
     * 用户明确清空（{@link #bgClearedByUser}）时一律不补。
     */
    private fun restoreCritical() {
        if (bgClearedByUser) return
        // 三个关键字段都还在 ⇒ 完全不用读留档（这条路是每次保存都会走的，能省就省）
        if (config.f2HomeBg.isNotEmpty() && config.mediaPool.isNotEmpty()
            && config.f4FontLib.isNotEmpty()) return
        var prev: MoriConfig? = null
        try {
            prev = ConfigStore.loadPrevious(getApplication())
        } catch (t: Throwable) {
        }
        val p = prev ?: return
        var changed = false
        // ① 当前背景：内存里空了、上一份里有、而且那个文件真的还在盘上 ⇒ 补回
        if (config.f2HomeBg.isEmpty() && p.f2HomeBg.isNotEmpty()) {
            val f = java.io.File(p.f2HomeBg)
            if (f.exists() && f.length() > 0) {
                config.f2HomeBg = p.f2HomeBg
                changed = true
            }
        }
        // ② 背景素材池：整体为空时补回（逐个比 path，避免把用户新加的挤掉）
        if (config.mediaPool.isEmpty() && p.mediaPool.isNotEmpty()) {
            p.mediaPool.forEach { config.mediaPool.add(it) }
            changed = true
        }
        // ③ 字体清单 / 当前字体
        if (config.f4FontLib.isEmpty() && p.f4FontLib.isNotEmpty()) {
            p.f4FontLib.forEach { config.f4FontLib.add(it) }
            changed = true
        }
        if (config.f4FontFile.isEmpty() && p.f4FontFile.isNotEmpty()) {
            val f = java.io.File(p.f4FontFile)
            if (f.exists()) {
                config.f4FontFile = p.f4FontFile
                changed = true
            }
        }
        if (changed) {
            Log.i("MoriMod", "[MoriMod] critical config restored from previous: "
                + "bg=" + (config.f2HomeBg.isNotEmpty()) + " media=" + config.mediaPool.size
                + " fonts=" + config.f4FontLib.size)
        }
    }

    /** 用导入的备份整体替换配置（仅在 [FileStore.importBackup] 校验通过后调用）。 */
    fun applyRestored(restored: MoriConfig) {
        config = restored
        config.clamp()
        Strings.apply(Lang.of(config.lang))
        persist()
        syncLauncherIcon(persist = false)
    }

    /**
     * 落盘并广播。
     *
     * @return 是否成功（失败时 toast 已给出提示）。
     */
    private fun persist(): Boolean {
        val ctx = getApplication<Application>()
        // ★★★ v0.1.39：**保存前先把「莫名变空」的关键字段补回来**。
        //   r44 一加 12 的现场是「磁盘上那份配置自己就没有 bg 了」，然后在设置页
        //   随便点一下（调试日志开关）就落盘 ⇒ 用户看到的就是「重启后壁纸丢失」。
        //   这里在写之前做一次兜底：只要上一份留档里还有背景 / 素材池 / 字体清单，
        //   而这一份是空的、用户又没明确要清空，就补回去再写。
        restoreCritical()
        // ★ v0.1.4：打上保存时间戳。宿主进程能读到 4 份配置（模块 remote prefs / 模块 JSON /
        //   宿主本地 JSON / 宿主本地 prefs），读取时**取 savedAt 最大的一份**，
        //   这样「先在设置页改字体 → 再开宿主」也能拿到最新那份，不再靠猜哪条通道可靠。
        config.savedAt = System.currentTimeMillis()
        val okPrefs = ConfigStore.savePrefs(ctx, config)
        val okFile = ConfigStore.saveFile(ctx, config)
        // 即使本地写失败也广播，宿主那次仍可能生效；但要如实回报失败。
        ConfigBus.sendConfigChanged(ctx, config.toJson())
        // ⚠️ 换成深拷贝：让所有读 vm.config 的作用域（含 LazyColumn 的每一项）真正失效，
        //    否则就地改字段在 Compose 里是「隐身」的（见 config 字段注释）。
        config = config.copy()
        // 落盘成功了 ⇒ 这次读到的内容就是可信的，降级标志解除
        loadDegraded = false
        bgClearedByUser = false
        if (!okPrefs || !okFile) {
            toast = S.TOAST_SAVE_FAILED
            return false
        }
        return true
    }

    /** 不落盘的即时改动（拖动滑块等），同样要换身份才能让界面跟上手指。 */
    private fun touch() {
        config = config.copy()
    }

    // ── 界面偏好 ────────────────────────────────────────────────────

    /** 切换语言（简中 → 繁中 → EN 循环），立刻落盘并生效。 */
    fun cycleLanguage() {
        val next = Lang.of(config.lang).next()
        config.lang = next.code
        Strings.apply(next)
        persist()
    }

    /** 液态玻璃（第二批功能，仅记录偏好）。 */
    fun setF3Enabled(v: Boolean) {
        config.f3Enabled = v
        persist()
        toast = S.TOAST_GLASS_SAVED
    }

    /**
     * 调试日志开关。
     *
     * ⚠️ 刻意**不弹任何提示**（用户明确要求）：这个开关只影响日志详略，
     *    开关本身的状态变化已经是全部反馈，弹 Snackbar 反而挡视线。
     */
    /** ★ r112：作品封面屏蔽开关。 */
    fun setCoverBlock(v: Boolean) {
        config.coverBlock = v
        config.clamp()
        if (persist()) toast = S.TOAST_SAVED
    }

    fun setDebugLog(v: Boolean) {
        config.debugLog = v
        persist()
    }

    /**
     * 隐藏 / 显示桌面图标。
     *
     * 真实作用于 manifest 里的 `LauncherAlias`（`setComponentEnabledSetting`），
     * 状态写在 PackageManager 中，**重启后依然保持**。
     */
    fun setHideLauncherIcon(hide: Boolean): Boolean {
        config.hideLauncherIcon = hide
        val ok = syncLauncherIcon(persist = true)
        toast = when {
            !ok -> S.TOAST_ICON_HIDE_FAILED
            hide -> S.TOAST_ICON_HIDDEN
            else -> S.TOAST_ICON_SHOWN
        }
        return ok
    }

    private fun syncLauncherIcon(persist: Boolean): Boolean {
        val ctx = getApplication<Application>()
        return try {
            val pm = ctx.packageManager
            val state = if (config.hideLauncherIcon) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            pm.setComponentEnabledSetting(launcherAlias, state, PackageManager.DONT_KILL_APP)
            if (persist) persist()
            true
        } catch (t: Throwable) {
            false
        }
    }

    // ── F1 播放控件 ──────────────────────────────────────────────────

    fun setF1Enabled(v: Boolean) {
        config.f1Enabled = v
        config.clamp()
        if (persist()) toast = S.TOAST_SAVED
    }

    fun setF1Cover(v: Boolean) {
        config.f1Cover = v
        if (persist()) toast = S.TOAST_SAVED
    }

    fun setF1Title(v: Boolean) {
        config.f1Title = v
        if (persist()) toast = S.TOAST_SAVED
    }

    fun setF1Artist(v: Boolean) {
        config.f1Artist = v
        if (persist()) toast = S.TOAST_SAVED
    }

    // ── F2 背景 ─────────────────────────────────────────────────────

    fun setF2Enabled(v: Boolean) {
        config.f2Enabled = v
        config.clamp()
        if (persist()) toast = S.TOAST_SAVED
    }

    /**
     * 播放页背景模糊（v0.1.21 用户第 2 条，取代已删除的「遮罩浓度」滑条）。
     *
     * <p>播放页的底色原本是宿主自己那张「已经糊过的紫色背景图」，现在被换成用户壁纸，
     * 这个档位控制那一层的糊度（0–100%，整数步进 = 1%）。只影响播放页，
     * 不改变主界面/其它页面的清晰壁纸。
     *
     * <p>拖动过程中不落盘（避免每秒一次广播），松手由 [commitF2PlayBlur] 提交。
     */
    fun setF2PlayBlur(v: Int) {
        config.f2PlayBlur = v.coerceIn(0, 100)
        touch()
    }

    fun commitF2PlayBlur() {
        if (persist()) toast = S.TOAST_SAVED
    }

    /**
     * 强调色跟自定义背景取色（v0.1.22 用户第 4 条）。
     *
     * <p>只存偏好，真正的取色/改色在宿主进程里做（`hook/AccentColor`）。
     * 关掉时宿主侧立刻清空，视觉上完全回到默认。
     */
    fun setF2Accent(v: Boolean) {
        config.f2Accent = v
        config.clamp()
        if (persist()) toast = S.TOAST_SAVED
    }

    /** 强调色风格（九个名字直接沿用 Material 3 Expressive，不做翻译）。 */
    fun setF2AccentStyle(name: String) {
        if (!MoriConfig.validAccentStyle(name)) return
        if (config.f2AccentStyle == name) return
        config.f2AccentStyle = name
        if (persist()) toast = S.TOAST_SAVED
    }

    /**
     * 背景模糊强度（v0.1.6）。
     *
     * <p>宿主自己在背景上盖了一层全屏渐变（我们已经在宿主侧把它剥掉了，见 `hook/BgPeel`），
     * 壁纸露出来后往往"太清楚、抢内容"，所以给一个**自己控制**的模糊档位。
     * 拖动过程中不落盘，松手由 [commitF2Blur] 提交。
     */
    fun setF2Blur(v: Int) {
        config.f2Blur = v.coerceIn(0, 100)
        touch()
    }

    fun commitF2Blur() {
        if (persist()) toast = S.TOAST_SAVED
    }

    /**
     * ⚠️ v0.1.9：`f2PageAlpha` 已停用（滑块也删了）。
     *
     * <p>它服务于 v0.1.8「把宿主页面底色的 alpha 调低」那条路，而真机日志
     * （`pass want=0 hits=4 changed=4 tracked=0`，每轮都 changed）证明宿主每帧
     * 都会把自己的 background drawable 重设回来，alpha 根本留不住。
     * 现在壁纸由 `hook/BgPeel` 直接画在页面层上，这个字段只为兼容旧配置保留。
     */
    fun setF2PageAlpha(v: Int) {
        config.f2PageAlpha = v.coerceIn(0, 100)
        touch()
    }

    fun setF2Background(path: String) {
        config.f2HomeBg = path
        // 历史字段「全透明」一律清掉：它的实现是清空宿主背景，真机表现为整屏发白
        config.f2Transparent = false
        // 选了图就默认启用：否则用户选完「什么都没发生」，很像功能坏了（v0.0.5 真机体感）
        if (path.isNotEmpty()) config.f2Enabled = true
        config.clamp()
        if (persist()) toast = S.TOAST_SAVED
    }


    /**
     * 裁切结果落成背景（v0.1.11）。
     *
     * <p>用户选完图会先进裁切页（按屏幕比例取景），确认后走到这里：
     * ①把裁好的位图写成新的素材文件；②入库；③设为当前背景；
     * ④删掉 [sourcePath] 那张**未裁切的原图** —— 不删的话「我的背景」里会同时
     * 躺着原图和裁切图两张几乎一样的东西，用户根本分不清哪张是生效的。
     *
     * @return true = 已生效；false = 写文件失败（会把原图留在库里，不假装成功）
     */
    fun commitCroppedBackground(sourcePath: String, bmp: android.graphics.Bitmap, label: String): Boolean {
        val saved = FileStore.saveBitmap(getApplication(), bmp, "bg")
        if (saved == null) {
            // 裁切结果写不进去：保留原图并直接用它，至少功能不空转
            if (sourcePath.isNotEmpty()) {
                addMedia(sourcePath, "background", label)
                setF2Background(sourcePath)
            }
            toast = S.TOAST_CROP_FAILED
            return false
        }
        addMedia(saved, "background", label)
        setF2Background(saved)
        if (sourcePath.isNotEmpty() && sourcePath != saved) FileStore.delete(sourcePath)
        return true
    }

    /** 裁切页点了「跳过」：原图直接用（不裁）。 */
    fun useUncroppedBackground(sourcePath: String, label: String) {
        if (sourcePath.isEmpty()) {
            toast = S.TOAST_PICK_FAILED
            return
        }
        addMedia(sourcePath, "background", label)
        setF2Background(sourcePath)
    }

    /** 裁切页被关掉：把刚导入的原图删掉，别在库里留垃圾。 */
    fun discardImported(path: String) {
        if (path.isNotEmpty()) FileStore.delete(path)
    }

    /** F2「恢复默认」：清背景图 / 桌面壁纸 + 遮罩/模糊归零 + 关掉替换开关。 */    fun restoreBackgroundDefault() {
        clearBackground()
        persist()
        toast = S.TOAST_DEFAULT_RESTORED
    }

    /** 把 F2 相关字段恢复成「宿主默认」（不落盘，由调用方决定什么时候 persist）。 */
    private fun clearBackground() {
        // ★★★ v0.1.39：这是用户**明确要清掉**背景，记一下意图。
        //   否则 persist() 的「关键字段回补」会把刚清掉的背景又填回来（用户点「恢复默认」没反应）。
        bgClearedByUser = true
        config.f2HomeBg = ""
        config.f2Transparent = false
        config.f2MaskAlpha = 0
        config.f2Blur = 0
        config.f2Enabled = false
    }

    // ── F4 字体 ─────────────────────────────────────────────────────

    fun setF4Enabled(v: Boolean) {
        config.f4Enabled = v
        config.clamp()
        if (persist()) toast = S.TOAST_SAVED
    }

    fun setF4Family(family: String) {
        config.f4FontFamily = family
        // 选定字体即启用（与背景同一条产品逻辑：选完必须要看到变化）
        if (family.isNotEmpty()) config.f4Enabled = true
        if (persist()) toast = S.TOAST_SAVED
    }

    fun setF4File(path: String) {
        config.f4FontFile = path
        if (path.isNotEmpty()) {
            config.f4Enabled = true
            rememberFont(path)
        }
        if (persist()) toast = S.TOAST_SAVED
    }

    /**
     * 把「用户自己导入的字体」记进「我的字体」清单（v0.1.2）。
     *
     * <p>只收**模块 files/fonts 下的副本**：内置字体（{@code asset:} 前缀）本来就一直在，
     * {@code /system/fonts/...} 属于「系统字体」那张卡。这样清单里每一条都能安全删除 ——
     * 删的永远是本模块复制出来的那份，不会动用户原文件。
     */
    private fun rememberFont(path: String) {
        if (path.isEmpty()) return
        if (path.startsWith(io.github.ariinyume.morimod.hook.ModuleAssets.ASSET_PREFIX)) return
        if (!path.contains("/files/fonts/")) return
        if (!config.f4FontLib.contains(path)) config.f4FontLib.add(path)
    }

    /**
     * 从「我的字体」里删掉一项。
     *
     * <p>三件事一起做，缺一件都会留下「看得见但用不了」的残影：
     * ① 从清单里去掉；② 如果它正是当前字体就清掉当前选择（否则宿主每次取字体都读一个已删文件）；
     * ③ 删掉模块目录里的那份副本（只删 files/fonts 下的，别的路径一律不动）。
     */
    fun removeFontLib(path: String) {
        if (path.isEmpty()) return
        while (config.f4FontLib.remove(path)) { /* 去重残留 */ }
        if (config.f4FontFile == path) {
            config.f4FontFile = ""
            if (config.f4FontFamily.isEmpty()) config.f4Enabled = false
        }
        deleteFontCopy(path)
        config.clamp()
        persist()
        toast = S.TOAST_FONT_DELETED
    }

    /** 删模块目录里的字体副本（越界路径一律忽略 —— 绝不误删用户文件）。 */
    private fun deleteFontCopy(path: String) {
        try {
            val dir = java.io.File(getApplication<Application>().filesDir, "fonts")
            val f = java.io.File(path)
            val parent = f.parentFile ?: return
            if (parent.canonicalPath != dir.canonicalPath) return
            if (f.isFile) f.delete()
        } catch (t: Throwable) {
            // 删不掉不影响功能（那条已经从清单里移除了），不打扰用户
        }
    }

    /**
     * 一次性选定字体来源（文件 或 系统族，二者互斥）。
     *
     * <p>v0.0.9：原来界面里是 `setF4Family(...)` 紧跟 `setF4File("")`，
     * 会落盘两次、广播两轮，且宿主侧可能读到「族名还在 + 文件已空」的中间态。
     * 合成一次写、一次广播。
     */
    fun setF4Choice(filePath: String, family: String) {
        config.f4FontFile = filePath
        config.f4FontFamily = if (filePath.isEmpty()) family else ""
        if (filePath.isNotEmpty() || family.isNotEmpty()) config.f4Enabled = true
        if (persist()) toast = S.TOAST_SAVED
    }

    /** F4「恢复默认」：两个来源一起清 + 关掉开关，回到宿主自带字体。 */
    fun restoreFontDefault() {
        config.f4FontFamily = ""
        config.f4FontFile = ""
        config.f4Enabled = false
        persist()
        toast = S.TOAST_DEFAULT_RESTORED
    }

    // ── 素材库增删改查（真实数据，空态有提示，删除有确认）────────────

    fun addMedia(path: String, type: String, label: String) {
        if (path.isEmpty()) {
            toast = S.TOAST_PICK_FAILED
            return
        }
        val item = MoriConfig.MediaItem().apply {
            id = "m" + System.currentTimeMillis().toString(36) + config.mediaPool.size
            this.type = type
            this.path = path
            this.label = label.ifEmpty { path.substringAfterLast('/') }
            enabled = true
        }
        config.mediaPool.add(item)
        persist()
    }

    /**
     * 删除素材库里的一项。
     *
     * <p>★ v0.1.10：如果删掉的正是**当前正在用的背景图**，同时把背景恢复成宿主默认。
     * 否则会留下一个「配置里指着一个已经删掉的文件」的悬空状态 —— 宿主那边
     * 读不到素材就回落原图，但壁纸层已经贴上去了，屏幕上会出现「壁纸停在最后一张
     * 缓存上、怎么点都没反应」，用户以为功能坏了。
     */
    fun removeMedia(id: String) {
        val item = config.mediaPool.firstOrNull { it.id == id }
        val removed = config.mediaPool.removeAll { it.id == id }
        if (!removed) {
            toast = S.TOAST_SAVE_FAILED
            return
        }
        val wasCurrent = item != null
                && item.path.isNotEmpty()
                && item.path == config.f2HomeBg
        if (wasCurrent) {
            clearBackground()
            persist()
            toast = S.TOAST_BG_RESTORED
            return
        }
        persist()
        toast = S.TOAST_DELETED
    }

    fun toggleMedia(id: String) {
        val item = config.mediaPool.firstOrNull { it.id == id } ?: return
        item.enabled = !item.enabled
        persist()
    }

    /**
     * 批量添加名称：按行拆分，逐行去空白，跳过空行；
     * 返回真正加进去的条数（0 条时给提示，不静默失败）。
     */
    fun addNames(text: String, type: String): Int {
        val lines = text.split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (lines.isEmpty()) {
            toast = S.TOAST_NO_SELECTION
            return 0
        }
        var added = 0
        lines.forEachIndexed { i, line ->
            val item = MoriConfig.NameItem().apply {
                id = "n" + System.currentTimeMillis().toString(36) + i + config.namePool.size
                this.type = type
                this.text = line
                enabled = true
            }
            config.namePool.add(item)
            added++
        }
        persist()
        toast = "${S.TOAST_ADDED} $added"
        return added
    }

    fun removeName(id: String) {
        val removed = config.namePool.removeAll { it.id == id }
        if (!removed) {
            toast = S.TOAST_SAVE_FAILED
            return
        }
        persist()
        toast = S.TOAST_DELETED
    }

    fun toggleName(id: String) {
        val item = config.namePool.firstOrNull { it.id == id } ?: return
        item.enabled = !item.enabled
        persist()
    }

    /** 「恢复默认」= 把该类型的素材全部重新启用，**不删除**任何条目。 */
    fun restorePool(type: String, isMedia: Boolean) {
        if (isMedia) {
            config.mediaPool.filter { it.type == type }.forEach { it.enabled = true }
        } else {
            config.namePool.filter { it.type == type }.forEach { it.enabled = true }
        }
        persist()
        toast = S.TOAST_POOL_RESTORED
    }

    /** 「清空列表」= 真删（UI 侧必须先弹确认）。 */
    fun clearPool(type: String, isMedia: Boolean) {
        if (isMedia) {
            config.mediaPool.removeAll { it.type == type }
            if (type == "background") bgClearedByUser = true
            if (type == "background" && config.f2HomeBg.isNotEmpty()) {
                // 背景池被清空时同步清掉当前选择，避免指向不存在的文件
                val stillThere = config.mediaPool.any { it.path == config.f2HomeBg }
                if (!stillThere) config.f2HomeBg = ""
            }
        } else {
            config.namePool.removeAll { it.type == type }
        }
        persist()
        toast = S.TOAST_POOL_CLEARED
    }

    /** 导出用文本（每行一条，只导该类型）。 */
    fun exportText(type: String, isMedia: Boolean): String = if (isMedia) {
        config.mediaPool.filter { it.type == type }
            .joinToString("\n") { it.label.ifEmpty { it.path.substringAfterLast('/') } }
    } else {
        config.namePool.filter { it.type == type }.joinToString("\n") { it.text }
    }

    // ── 按类型取素材 ────────────────────────────────────────────────

    fun mediaOf(type: String): List<MoriConfig.MediaItem> =
        config.mediaPool.filter { it.type == type }

    fun namesOf(type: String): List<MoriConfig.NameItem> =
        config.namePool.filter { it.type == type }

    // ── 作用域探测 ──────────────────────────────────────────────────

    /** 向宿主发一次 ping；结果由 [onHostPong] 回填。 */
    fun pingHost() {
        ConfigBus.sendHostScopePing(getApplication())
    }

    /** 宿主应答到达时调用。 */
    fun onHostPong(alive: Boolean) {
        if (!alive) {
            hostScopeAlive = false
            return
        }
        hostLastSeenWall = System.currentTimeMillis()
        hostScopeAlive = true
    }

    /**
     * 每个轮询周期调一次：把「pong」与「心跳时间戳」合成一个状态。
     *
     * <p>判据：最后一次见到宿主距现在不到 [HOST_ALIVE_WINDOW_MS] 就算在运行。
     * 这样后台冻结（广播排队 / 解冻慢）不会立刻误判，而真正被杀掉的宿主
     * 会在宽限窗口之后自然落到「未运行」。
     *
     * <p>终态兜底：从打开页面起 [HOST_ALIVE_GRACE_MS] 内连一次心跳都没读到，
     * 就直接落「未运行」，不会永远停在「检测中」。
     */
    fun tickHostStatus() {
        val app = getApplication<Application>()
        val beat = XPrefs.getLong(app, HostStatusReceiver.PREFS,
            HostStatusReceiver.K_LAST_ALIVE, 0L)
        if (beat > hostLastSeenWall) hostLastSeenWall = beat

        val now = System.currentTimeMillis()
        if (hostLastSeenWall > 0L) {
            val age = now - hostLastSeenWall
            // 时钟回拨 / 未来时间戳：当成「刚刚见过」，别因为差值为负就判死
            hostScopeAlive = age < HOST_ALIVE_WINDOW_MS || age < 0L
            return
        }
        // 一次都没见过：给宿主一个足够的宽限窗口，然后落终态
        if (SystemClock.elapsedRealtime() - probeStartElapsed > HOST_ALIVE_GRACE_MS) {
            hostScopeAlive = false
        }
    }

    /** 宿主是否「在这台机器上运行过」（用于状态卡副标题的时间提示）。 */
    fun hostLastSeenText(): String? {
        if (hostLastSeenWall <= 0L) return null
        val sec = (System.currentTimeMillis() - hostLastSeenWall) / 1000L
        return if (sec < 60) "${sec}s" else "${sec / 60}min"
    }

    // ── 导出 / 导入（走剪贴板，不再弹「导出与备份」对话框）────────────

    /**
     * 导出：把整份配置渲染成 `MORIMOD-BACKUP 1` 单文件文本并**复制到剪贴板**。
     *
     * 用户明确要求：点「导出」不要弹窗，直接复制配置文本。
     */
    fun exportToClipboard(): Boolean {
        val text = BackupManager.export(config)
        val ok = writeClipboard(text)
        toast = if (ok) S.TOAST_EXPORT_COPIED else S.TOAST_EXPORT_FAILED
        return ok
    }

    /**
     * 导入：读**剪贴板**里的配置文本，校验通过才覆盖现有配置。
     *
     * 与「导出 = 复制文本」对称：贴入的是文本，不再是文件选择器。
     * 校验不通过时保持现有配置不变（与 PRD FR-0.5 一致）。
     */
    fun importFromClipboard(): Boolean {
        val text = readClipboard()
        if (text.isNullOrBlank()) {
            toast = S.TOAST_IMPORT_EMPTY
            return false
        }
        val restored = FileStore.importBackup(text)
        if (restored == null) {
            toast = S.TOAST_IMPORT_FORMAT
            return false
        }
        applyRestored(restored)
        toast = S.TOAST_IMPORTED
        return true
    }

    /**
     * 长按主页状态卡：把完整版本信息复制到剪贴板（v0.1.23 用户第 6 条）。
     *
     * <p>为什么从「关于」页挪到这里：关于页那个「复制版本信息」按钮占了一格宽度，
     * 而这段信息本来就是「关于这个模块」的内容，长按状态卡更顺手 —— 用户明确要求。
     * 内容仍然是「模块版本 + 包名 + 宿主版本 + 系统版本」四项，方便报 bug 时一次贴全。
     */
    fun copyVersionInfo() {
        val ctx = getApplication<Application>()
        val hostVer = try {
            val info = ctx.packageManager.getPackageInfo(
                io.github.ariinyume.morimod.config.Protocol.HOST_PKG, 0,
            )
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} (code $code)"
        } catch (t: Throwable) {
            S.STATUS_HOST_MISSING
        }
        val text = buildString {
            append("MoriMod ").append(io.github.ariinyume.morimod.util.BuildInfo.versionName())
            append(" (code ")
            append(io.github.ariinyume.morimod.util.BuildInfo.versionCode()).append(")\n")
            append(io.github.ariinyume.morimod.util.BuildInfo.applicationId()).append("\n")
            append("Host: ").append(hostVer).append("\n")
            append("Android: ").append(android.os.Build.VERSION.RELEASE)
            append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")")
        }
        toast = if (writeClipboard(text)) S.TOAST_COPIED else S.TOAST_SAVE_FAILED
    }

    private fun writeClipboard(text: String): Boolean = try {
        val cm = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MoriMod", text))
        true
    } catch (t: Throwable) {
        false
    }

    private fun readClipboard(): String? = try {
        val cm = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) null
        else clip.getItemAt(0).coerceToText(getApplication()).toString()
    } catch (t: Throwable) {
        null
    }

    // ── 工具 ────────────────────────────────────────────────────────

    fun consumeToast() {
        toast = null
    }

    /** 直接给一条提示（用于文件读取失败等非配置类反馈）。 */
    fun notify(msg: String) {
        toast = msg
    }

    /** 文件读取失败提示。 */
    fun reportPickFailed() {
        toast = S.TOAST_PICK_FAILED
    }

    /** 用户取消选择：清掉可能残留的旧提示，不弹任何东西。 */
    fun reportPickCanceled() {
        toast = null
    }

    /** 选了非字体文件（只允许 .ttf / .otf，v0.1.4）。 */
    fun reportUnsupportedFont() {
        toast = S.TOAST_FONT_UNSUPPORTED
    }

    /**
     * 通用一次性提示（v0.1.5）。
     *
     * <p>二级页（{@code PageActivity}）没有 SnackbarHost，只设 {@code toast} 是**看不见**的；
     * 那边先用系统 Toast 显示，再把同一句话记进 `toast` 供日志/主页补显。
     */
    fun reportToast(msg: String) {
        toast = msg
    }

    fun context(): Context = getApplication()

    /** 从 SAF 的 Uri 导入一个文件；失败返回 null（调用方给提示）。 */
    fun importUri(uri: Uri, kind: String): String? =
        FileStore.importUri(getApplication(), uri, kind)
}

/**
 * 「最后一次见到宿主」距今多久还算在运行。
 *
 * 45 秒 = 两个多心跳周期：够扛住后台冻结时的广播排队与解冻延迟，
 * 又能在宿主真被杀掉后半分钟内落到「未运行」。
 */
private const val HOST_ALIVE_WINDOW_MS = 45_000L

/** 打开设置页后「一直没见到宿主」的宽限窗口；过了就落终态，不永远停在检测中。 */
private const val HOST_ALIVE_GRACE_MS = 4_000L
