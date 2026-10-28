package io.github.ariinyume.morimod.hook;

import android.graphics.Bitmap;
import android.graphics.drawable.Icon;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.ariinyume.morimod.config.ConfigBus;
import io.github.ariinyume.morimod.config.MoriConfig;
import io.github.ariinyume.morimod.config.RemoteConfig;
import io.github.ariinyume.morimod.data.MediaPool;
import io.github.ariinyume.morimod.data.SessionAssignment;
import io.github.ariinyume.morimod.util.DiagLog;
import io.github.ariinyume.morimod.util.XposedCompat;

/**
 * F1 · 播放控件自定义（仅面 A：系统媒体控件，D-02）。
 *
 * <h3>技术路线</h3>
 * <ul>
 *   <li><b>A-1 改名称</b>：hook {@code expo.modules.audio.Metadata} 的 6 参构造器，
 *       在字段写入点替换 {@code title} / {@code artist}（字段名明文、PUBLIC 非 final，
 *       跨宿主发版稳定）。</li>
 *   <li><b>A-2/A-4 改封面</b>：hook {@code Notification.Builder} /
 *       {@code NotificationCompat.Builder} 的 {@code setLargeIcon}，
 *       先由 {@code setStyle(MediaStyle)} 把「这是媒体通知」标出来，再替换大图标
 *       —— 与宿主内部实现完全解耦，抗混淆性最好（PRD §7.1.4 双保险）。</li>
 * </ul>
 *
 * <p>会话映射（BR-1.2 / §9.3）：会话内稳定、跨会话随机、不持久化。
 */
public final class FeatureF1 {

    private static final String TAG = "[MoriMod:F1]";
    private static final String META_CLASS = "expo.modules.audio.Metadata";

    private static final int MAX_TITLE_CHARS = 40;
    private static final int MAX_ARTIST_CHARS = 32;

    private static volatile Field fTitle, fArtist, fAlbum, fArtwork;

    /** 被标记为「媒体通知」的 Builder（WeakHashMap 防泄漏）。 */
    private static final Map<Object, Boolean> MEDIA_BUILDERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile Bitmap sCoverCache;
    private static volatile String sCoverKey = "";
    private static volatile boolean sFirstNameLogged, sFirstIconLogged;

    private FeatureF1() {
    }

    public static void install(ClassLoader cl) {
        installMetadata(cl);
        installNotification(cl);
        // 「重新随机本曲目」广播 → 会话级重随机
        ConfigBus.sRerollHandler = SessionAssignment::reroll;
        RemoteConfig.addListener(FeatureF1::invalidate);
    }

    // ======================================================================
    // A-1 名称（Metadata 构造器）
    // ======================================================================

    private static void installMetadata(ClassLoader cl) {
        try {
            Class<?> meta = XposedCompat.findClass(META_CLASS, cl);
            fTitle = XposedCompat.findField(meta, "title");
            fArtist = XposedCompat.findField(meta, "artist");
            try {
                fAlbum = XposedCompat.findField(meta, "albumTitle");
                fArtwork = XposedCompat.findField(meta, "artworkUrl");
            } catch (Throwable ignored) {
            }
            int ok = 0;
            for (Constructor<?> c : meta.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                // 只钩 6 参「真实数据」构造器，跳过 Kotlin 合成桥接构造器
                if (p.length == 6 && p[0] == String.class && p[4] == Double.class) {
                    c.setAccessible(true);
                    XposedCompat.hookMethod(c, new XposedCompat.VoidHook() {
                        @Override
                        protected void afterVoid(io.github.libxposed.api.XposedInterface.Chain chain) {
                            try {
                                onMetadataBuilt(chain.getThisObject());
                            } catch (Throwable t) {
                                // 绝不抛回宿主
                            }
                        }
                    });
                    ok++;
                }
            }
            DiagLog.install("F1:Metadata", ok > 0, ok > 0 ? null : "no 6-arg ctor");
        } catch (Throwable t) {
            DiagLog.install("F1:Metadata", false, t.getClass().getSimpleName());
        }
    }

    private static void onMetadataBuilt(Object meta) {
        if (meta == null) return;
        MoriConfig cfg = RemoteConfig.get();
        if (!cfg.f1Enabled) return;

        String origTitle = (String) safeGet(fTitle, meta);
        String origArtist = (String) safeGet(fArtist, meta);
        // 会话标识 = 宿主原始 metadata 身份（本会话内稳定 ⇒ 防闪烁）
        SessionAssignment.ensure(String.valueOf(origTitle) + "\u0001" + origArtist);

        if (!SessionAssignment.isAssigned()) {
            MoriConfig.MediaItem cover = cfg.f1Cover ? MediaPool.pickMedia("cover") : null;
            MoriConfig.NameItem t = cfg.f1Title ? MediaPool.pickName("track") : null;
            MoriConfig.NameItem a = cfg.f1Artist ? MediaPool.pickName("artist") : null;
            SessionAssignment.assign(
                    cover == null ? null : cover.path,
                    t == null ? null : MediaPool.truncate(t.text, MAX_TITLE_CHARS),
                    a == null ? null : MediaPool.truncate(a.text, MAX_ARTIST_CHARS));
            DiagLog.trigger("F1:assign");
        }

        if (cfg.f1Title && SessionAssignment.title() != null) {
            safeSet(fTitle, meta, SessionAssignment.title());
        }
        if (cfg.f1Artist && SessionAssignment.artist() != null) {
            safeSet(fArtist, meta, SessionAssignment.artist());
        }
        if (!sFirstNameLogged) {
            sFirstNameLogged = true;
            DiagLog.trigger("F1:Metadata");
            XposedCompat.log(TAG + " metadata replaced (cover="
                    + (SessionAssignment.coverPath() == null ? "no" : "yes") + ")");
        }
    }

    // ======================================================================
    // A-4 封面（通知大图标）
    // ======================================================================

    private static void installNotification(ClassLoader cl) {
        int ok = 0;
        // ① framework Notification.Builder
        try {
            Class<?> b = XposedCompat.findClass("android.app.Notification$Builder", cl);
            Class<?> style = XposedCompat.findClass("android.app.Notification$Style", cl);
            ok += hookMarkStyle(b, style);
            ok += hookLargeIconBitmap(b);
            try {
                Class<?> icon = XposedCompat.findClass("android.graphics.drawable.Icon", cl);
                ok += hookLargeIconIcon(b, icon);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            XposedCompat.log(TAG + " framework Builder hook failed: " + t.getMessage());
        }
        // ② androidx NotificationCompat.Builder（Expo 常用）
        try {
            Class<?> b = XposedCompat.findClass("androidx.core.app.NotificationCompat$Builder", cl);
            Class<?> style = XposedCompat.findClass("androidx.core.app.NotificationCompat$Style", cl);
            ok += hookMarkStyle(b, style);
            ok += hookLargeIconBitmap(b);
        } catch (Throwable ignored) {
            // 宿主可能不带 androidx
        }
        DiagLog.install("F1:Notification", ok > 0, ok > 0 ? null : "no entry hooked");
    }

    private static int hookMarkStyle(Class<?> builder, Class<?> style) {
        try {
            Method m = XposedCompat.findMethodExact(builder, "setStyle", style);
            XposedCompat.hookMethod(m, new XposedCompat.VoidHook() {
                @Override
                protected void afterVoid(io.github.libxposed.api.XposedInterface.Chain chain) {
                    try {
                        Object s = chain.getArg(0);
                        if (s != null && s.getClass().getName().contains("MediaStyle")) {
                            MEDIA_BUILDERS.put(chain.getThisObject(), Boolean.TRUE);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookLargeIconBitmap(Class<?> builder) {
        try {
            Method m = XposedCompat.findMethodExact(builder, "setLargeIcon", Bitmap.class);
            XposedCompat.hookMethod(m, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(io.github.libxposed.api.XposedInterface.Chain chain,
                                       Object result) throws Throwable {
                    try {
                        Bitmap bmp = coverBitmap();
                        if (bmp != null && isMedia(chain.getThisObject())) {
                            return chain.proceed(new Object[]{bmp});
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookLargeIconIcon(Class<?> builder, Class<?> iconCls) {
        try {
            Method m = XposedCompat.findMethodExact(builder, "setLargeIcon", iconCls);
            XposedCompat.hookMethod(m, new XposedCompat.SimpleHook() {
                @Override
                protected Object after(io.github.libxposed.api.XposedInterface.Chain chain,
                                       Object result) throws Throwable {
                    try {
                        Bitmap bmp = coverBitmap();
                        if (bmp != null && isMedia(chain.getThisObject())) {
                            return chain.proceed(new Object[]{Icon.createWithBitmap(bmp)});
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean isMedia(Object builder) {
        if (builder == null) return false;
        return Boolean.TRUE.equals(MEDIA_BUILDERS.get(builder));
    }

    /** 本次会话的封面 Bitmap（按会话路径缓存，避免重复解码）。 */
    private static Bitmap coverBitmap() {
        MoriConfig cfg = RemoteConfig.get();
        if (!cfg.f1Enabled || !cfg.f1Cover) return null;
        String path = SessionAssignment.coverPath();
        if (path == null || path.isEmpty()) return null;
        if (path.equals(sCoverKey) && sCoverCache != null && !sCoverCache.isRecycled()) {
            return sCoverCache;
        }
        Bitmap b = ModuleAssets.decodeBitmap(path);
        if (b == null) {
            DiagLog.degrade("F1", "封面素材不可用，回落宿主原值（BR-1.5）");
            return null;
        }
        sCoverCache = b;
        sCoverKey = path;
        if (!sFirstIconLogged) {
            sFirstIconLogged = true;
            DiagLog.trigger("F1:Notification");
            XposedCompat.log(TAG + " media notification large icon replaced");
        }
        return b;
    }

    private static void invalidate() {
        sCoverCache = null;
        sCoverKey = "";
        sFirstNameLogged = false;
        sFirstIconLogged = false;
    }

    // ── 反射小工具 ────────────────────────────────────────────────────

    private static Object safeGet(Field f, Object o) {
        if (f == null || o == null) return null;
        try {
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void safeSet(Field f, Object o, Object v) {
        if (f == null || o == null) return;
        try {
            f.set(o, v);
        } catch (Throwable ignored) {
        }
    }
}
