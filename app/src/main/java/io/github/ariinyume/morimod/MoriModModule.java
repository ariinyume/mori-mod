package io.github.ariinyume.morimod;

import android.content.Context;
import android.content.pm.PackageInfo;

import io.github.ariinyume.morimod.config.ConfigBus;
import io.github.ariinyume.morimod.config.MoriConfig;
import io.github.ariinyume.morimod.config.Protocol;
import io.github.ariinyume.morimod.config.RemoteConfig;
import io.github.ariinyume.morimod.hook.AssetSync;
import io.github.ariinyume.morimod.hook.FeatureF1;
import io.github.ariinyume.morimod.hook.Heartbeat;
import io.github.ariinyume.morimod.hook.FeatureF2;
import io.github.ariinyume.morimod.hook.FeatureF4;
import io.github.ariinyume.morimod.util.AppEnv;
import io.github.ariinyume.morimod.util.BuildInfo;
import io.github.ariinyume.morimod.util.DiagLog;
import io.github.ariinyume.morimod.util.XposedCompat;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块入口（libxposed 现代 API，API 102）。
 *
 * <p>回调拆成「加载期」与「就绪期」：
 * <ul>
 *   <li>{@link #onPackageLoaded} —— Application 尚未创建；只挂 {@code Application#attach}，
 *       好在拿到真实宿主 Context 的第一时刻装配置应答器（用 system Context 注册广播会
 *       抛 SecurityException）。</li>
 *   <li>{@link #onPackageReady} —— Application 已就绪；装全部能力层 hook。</li>
 * </ul>
 *
 * <p>⚠️ 作用域是**进程级**的：scope 内的进程里所有被加载的包都会触发回调，
 * 必须在回调里按包名过滤（{@link #isTarget}，FR-0.7）。
 */
public class MoriModModule extends XposedModule {

    private static volatile boolean sInitialized = false;
    private static volatile boolean sAppAttachHooked = false;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        // 框架把模块载入进程后最先调用：注入兼容层，让后续所有日志都能进 LSPosed 日志包。
        XposedCompat.attach(this);
        XposedCompat.log("[MoriMod] onModuleLoaded process=" + param.getProcessName()
                + " isSystemServer=" + param.isSystemServer());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!isTarget(param.getPackageName())) return;
        try {
            hookApplicationAttach(param.getDefaultClassLoader());
        } catch (Throwable t) {
            XposedCompat.log("[MoriMod] hookApplicationAttach failed: " + t.getMessage());
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!isTarget(pkg)) return;
        if (sInitialized) return;
        sInitialized = true;
        initHost(pkg, param.getClassLoader());
    }

    /** 作用域是进程级的，必须自己筛（FR-0.7 运行时包名二次校验）。 */
    private boolean isTarget(String pkg) {
        return Protocol.HOST_PKG.equals(pkg);
    }

    private void initHost(String pkg, ClassLoader cl) {
        XposedCompat.log("[MoriMod] Module loaded for " + pkg);
        // 版本核验锚点（常开、只留这一行）
        XposedCompat.log("[MoriMod] " + BuildInfo.banner());
        XposedCompat.log("[MoriMod] build applicationId=" + BuildConfig.APPLICATION_ID
                + " versionName=" + BuildConfig.VERSION_NAME);

        String protocolErr = Protocol.selfCheck();
        if (protocolErr != null) {
            XposedCompat.log("[MoriMod] PROTOCOL self-check FAILED: " + protocolErr);
        }

        Context systemCtx = systemContext(cl);
        // 宿主版本自检（FR-0.6）
        String hostVer = hostVersion(systemCtx);
        DiagLog.header("module=" + BuildInfo.versionName() + "/" + BuildInfo.versionCode()
                + " host=" + hostVer + " framework=" + frameworkInfo());
        DiagLog.event("boot", "process=" + pkg + " host=" + hostVer);

        // ★ 应答器兜底安装（FR-0.1）：正常路径是 Application#attach 钩子，
        //   但如果那个钩子因为任何原因没装上（宿主 ROM 改了 attach 签名 / 钩子时机偏后），
        //   设置页就永远收不到 pong ⇒ 状态卡显示「未运行」，用户看到的正是
        //   「宿主明明启动了却检测不到」。onPackageReady 时 Application 一定已经建好，
        //   这里用 ActivityThread.currentApplication() 再装一次（installResponder 幂等）。
        ensureResponderInstalled(pkg, cl);

        // 首次读配置（四源并读，取 savedAt 最新的一份，见 RemoteConfig#read）
        //
        // ⚠️ v0.0.9：本地两条源**必须**用宿主 Application 上下文，不能用 systemContext。
        //    systemContext 的包是 "android"，它的 getFilesDir()/getSharedPreferences()
        //    落不到宿主自己的数据目录 ⇒ 读到的永远是空 ⇒ source=defaults
        //    （真机日志 6/6 次都是这一行，配置因此从来只在广播那一瞬间生效）。
        // ★ v0.1.4：模块侧那两条源（remote prefs / remote file）不需要 ctx，所以
        //   即便此刻 currentApplication() 还是 null，也照样能读到**最新**配置 ——
        //   「先在设置页改、再开宿主」终于能在首帧之前生效。
        Context appCtx = currentApplication(cl);
        Context cfgCtx = appCtx != null ? appCtx : systemCtx;
        RemoteConfig.read(appCtx, true);
        XposedCompat.log("[MoriMod] config source=" + RemoteConfig.source()
                + " : " + RemoteConfig.get().summary());

        // 读不到真配置 ⇒ 主动向模块应用要一份（它会拉起模块进程应答，不依赖设置页）
        if ("defaults".equals(RemoteConfig.source())) {
            ConfigBus.requestConfigWithRetry(cfgCtx);
        }
        // ★ v0.1.3：**无条件**再核对一次（900ms 后）。
        //   原因见 ConfigBus#requestSync：宿主本地副本只有在「宿主活着时保存设置」才更新，
        //   而真实顺序几乎总是「先在设置页选好 → 再打开宿主」⇒ 读到的是上一次的配置。
        //   内容相同不会触发重载，所以这次核对几乎零成本。
        ConfigBus.requestSync(cfgCtx);

        // ⚠️ v0.1.2：**不再**尝试 bindService 找模块应用。
        //    真机每次进程启动都打 `[MoriMod:Remote] bind requested=false`
        //    —— Android 11+ 的包可见性对显式组件同样生效，缺 <queries><package/> 直接失败。
        //    留着它只会每进程白跑两次 + 刷两条误导性日志。

        // ★ v0.1.1 素材**主**通路：广播预取 → 落到宿主自己的 files/morimod_assets/。
        //   为什么不用 Provider / bindService：两条都被 Android 11+ 的包可见性否掉
        //   （真机日志 Failed to find provider info / bindService 返回 false），
        //   而显式广播是本机唯一被日志证实打通的跨 uid 通道。
        //   取完一个文件就重载配置 ⇒ F2/F4 缓存失效 ⇒ 下一次渲染即用户素材。
        AssetSync.setOnSynced(() -> {
            // ⚠️⚠️ v0.1.3 血证：**不能**用这里捕获的 cfgCtx。
            //   initHost 阶段 currentApplication() 还是 null（真机日志每次都有
            //   `responder fallback: currentApplication() == null`），于是 cfgCtx = systemContext
            //   （package=android）；素材同步完成时拿它去重读配置 ⇒ 读到空 ⇒ 回落默认 ⇒
            //   F2/F4 当场被关掉。真机日志 18:26:47 就是这条链：
            //     asset synced -> reapply config / config reloaded: F2=false … F4=false
            //   现在改成「取当下的宿主 Application 上下文」，拿不到就干脆不重读
            //   （素材已经落盘，下一次渲染自然会读到，不需要靠重载来生效）。
            Context live = AppEnv.app();
            if (live == null) live = currentApplication(cl);
            if (live == null) {
                XposedCompat.log("[MoriMod] asset synced, 但没有宿主上下文，跳过重读");
                return;
            }
            XposedCompat.log("[MoriMod] asset synced -> reapply config (ctx=" + live.getPackageName() + ")");
            try {
                RemoteConfig.reload(live);
            } catch (Throwable t) {
                XposedCompat.log("[MoriMod] asset reapply failed: " + t);
            }
        });
        // ★ v0.1.4：字体文件刚落地 ⇒ 强制重排一次文字。
        //   为什么不能只靠 reload：reload 只在 JSON 变化时才通知监听器，
        //   而「字体晚到」的场景是「配置早就对了、只是文件还没搬过来」——
        //   此时基准字体是空的（sBaseReady=false），重排会立刻重试并成功。
        AssetSync.setOnFileSynced(rel -> {
            if (rel == null || !rel.startsWith("fonts/")) return;
            XposedCompat.log("[MoriMod] font asset synced -> relayout text: " + rel);
            io.github.ariinyume.morimod.hook.UiRefresh.relayoutText();
        });

        // ⚠️ 只有拿到**真宿主**上下文才注册接收器。
        //    用 systemContext 注册会直接抛：
        //      SecurityException: Given caller package android is not running in process ...
        //    （真机日志每次启动都有这一条）。Application#attach 里会再注册一次（幂等）。
        if (appCtx != null) {
            AssetSync.register(appCtx);
            AssetSync.prefetchFor(appCtx, RemoteConfig.get());
            // ★ v0.1.2 心跳：让设置页用「最后见到宿主的时间」判断状态，
            //   而不是赌一次 ping 能不能在 1.2 秒内答上来。
            Heartbeat.start(appCtx);
        }

        // 装能力层（单项失败只降级自身，不影响其他 —— FR-0.6 规则 2）
        installSafely("F4", () -> FeatureF4.install(cl));
        installSafely("F2", () -> FeatureF2.install(cl));
        installSafely("F1", () -> FeatureF1.install(cl));

        RemoteConfig.addListener(() -> {
            MoriConfig c = RemoteConfig.get();
            XposedCompat.log("[MoriMod] config reloaded: " + c.summary());
            // ★ v0.1.3：把**素材文件名**也打出来（只留最后一段，不含目录）。
            //   上一轮排查最大的坑是「只知道 file=set，不知道是哪个文件、能不能读」。
            XposedCompat.log("[MoriMod] assets: bg=" + tailOf(c.f2HomeBg)
                    + " font=" + tailOf(c.f4FontFile) + " family=" + shortOf(c.f4FontFamily));
            DiagLog.event("config", "reloaded src=" + RemoteConfig.source());
        });

        XposedCompat.log("[MoriMod] hooks installed, ready");
    }

    private void installSafely(String name, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            DiagLog.degrade(name, "install failed: " + t.getClass().getSimpleName());
            XposedCompat.log("[MoriMod] " + name + " install failed: " + t);
        }
    }

    /** 只取路径最后一段（日志脱敏：不写完整目录）。 */
    private static String tailOf(String path) {
        if (path == null || path.isEmpty()) return "-";
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    private static String shortOf(String s) {
        return (s == null || s.isEmpty()) ? "-" : s;
    }

    private Context systemContext(ClassLoader cl) {
        try {
            Class<?> at = XposedCompat.findClass("android.app.ActivityThread", cl);
            Object thread = XposedCompat.callStaticMethod(at, "currentActivityThread");
            return (Context) XposedCompat.callMethod(thread, "getSystemContext");
        } catch (Throwable t) {
            XposedCompat.log("[MoriMod] getSystemContext failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * 兜底把配置应答器装到宿主 Application 上（幂等）。
     *
     * <p>首选路径是 {@code Application#attach} 钩子（{@link #hookApplicationAttach}），
     * 这里只是保险：{@code onPackageReady} 阶段 Application 必然已经创建，
     * {@code ActivityThread.currentApplication()} 能直接拿到它。
     */
    private void ensureResponderInstalled(String pkg, ClassLoader cl) {
        try {
            Context app = currentApplication(cl);
            if (app == null) {
                XposedCompat.log("[MoriMod] responder fallback: currentApplication() == null");
                return;
            }
            AppEnv.install(app);
            boolean ok = ConfigBus.installResponder(app);
            XposedCompat.log("[MoriMod] responder ready via onPackageReady=" + ok + " pkg=" + pkg);
        } catch (Throwable t) {
            XposedCompat.log("[MoriMod] ensureResponderInstalled failed: " + t);
        }
    }

    /** {@code ActivityThread.currentApplication()}（宿主进程里返回宿主 Application）。 */
    private Context currentApplication(ClassLoader cl) {
        try {
            Class<?> at = XposedCompat.findClass("android.app.ActivityThread", cl);
            Object app = XposedCompat.callStaticMethod(at, "currentApplication");
            return (Context) app;
        } catch (Throwable t) {
            return null;
        }
    }

    private String hostVersion(Context systemCtx) {
        try {
            if (systemCtx == null) return "unknown";
            PackageInfo pi = systemCtx.getPackageManager()
                    .getPackageInfo(Protocol.HOST_PKG, 0);
            return pi.versionName + "/" + pi.getLongVersionCode();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private String frameworkInfo() {
        try {
            XposedInterface api = XposedCompat.api();
            if (api == null) return "none";
            return api.getFrameworkName() + " " + api.getFrameworkVersion()
                    + " api=" + api.getApiVersion();
        } catch (Throwable t) {
            return "err";
        }
    }

    /**
     * 钩 {@code Application#attach(Context)}：在拿到真实宿主 Context 的第一时刻
     * 安装配置应答器 + 登记宿主上下文。
     *
     * <p>⚠️ 只给**宿主包**挂（参考项目 2.3.1 铁律：给别的进程挂会让应答器串进程，
     * 导致状态卡误判）。
     */
    private void hookApplicationAttach(ClassLoader cl) {
        if (sAppAttachHooked) return;
        sAppAttachHooked = true;
        try {
            Class<?> appCls = XposedCompat.findClass("android.app.Application", cl);
            java.lang.reflect.Method attach =
                    XposedCompat.findMethodExact(appCls, "attach", Context.class);
            XposedCompat.hookMethod(attach, new XposedCompat.VoidHook() {
                @Override
                protected void afterVoid(XposedInterface.Chain chain) {
                    try {
                        Context appCtx = (Context) chain.getArg(0);
                        io.github.ariinyume.morimod.util.AppEnv.install(appCtx);
                        ConfigBus.installResponder(appCtx);
                        // ⚠️ v0.1.2：这里必须用 reload（read 不会通知监听器）。
                        //    否则 FeatureF2/FeatureF4 的缓存（素材位图 / 字体基准）
                        //    停留在「initHost 时读到的 defaults」那一版，永远不失效。
                        RemoteConfig.reload(appCtx);
                        XposedCompat.log("[MoriMod] Application attached, config channel ready ("
                                + RemoteConfig.source() + ")");
                        // 应答器已就位 ⇒ 现在要配置一定收得到。本地副本读不到就主动向模块应用要。
                        if ("defaults".equals(RemoteConfig.source())) {
                            ConfigBus.requestConfigWithRetry(appCtx);
                        }
                        // 素材服务：initHost 时用的可能是 systemContext（Application 还没建好），
                        // 这里拿到真宿主上下文后再发一次绑定请求（幂等）。
                        // ★ v0.1.1 素材主通路：注册回包接收器 + 按配置预取到宿主本地缓存。
                        //   宿主自己的 files/morimod_assets/ 之后就是普通文件读，渲染热路径零等待。
                        AssetSync.register(appCtx);
                        AssetSync.prefetchFor(appCtx, RemoteConfig.get());
                        Heartbeat.start(appCtx);
                    } catch (Throwable t) {
                        XposedCompat.log("[MoriMod] onApplicationAttach body failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedCompat.log("[MoriMod] hookApplicationAttach failed: " + t.getMessage());
        }
    }
}
