# ============================================================================
#  DLsiteSound MoriMod —— ProGuard / R8 规则
#
#  ⚠️ 三条必须记住的约束（都是本项目踩过的坑）：
#
#  1. libxposed API 由框架在运行时提供（compileOnly），必须在dex 里留下类定义，
#     否则宿主注入时 NoClassDefFoundError。
#  2. hook 层（hook/ config/ data/ util/ view/）是**反射式注册**：
#     用 findClass / findMethodExact + 字符串方法名。所以：
#       · 被hook 的目标（宿主类 + framework 类）不能被改名；
#       · hook 层自己的类虽然只被 MoriModModule 静态引用，但 R8 可能把
#         FeatureF1/F2/F4 的类名改掉，导致日志/诊断里对不上 ⇒ 保留类名。
#     注意：**framework 类（android.* / androidx.*）本来就不在 R8 改名范围内**
#     （它们来自 bootclasspath / AAR，R8 只处理应用自身类），所以真正需要保的是
#     「我们自己的 hook 层类名」+「libxposed」。
#  3. Compose 需要它自己的一整套 keep（由compose 自带的 consumer rules 提供，
#     但 R8 full mode 下仍要显式保住 @Composable lambda 与 Metadata）。
# ============================================================================

# ── libxposed现代 API（框架运行时提供，必须留定义）────────────────────
-keep class io.github.libxposed.** { *; }
-keep interface io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.**

# ── 模块自身：保留 hook 层与数据层的类名与成员 ────────────────────────
# 诊断日志/备份会按类名定位，混淆后无法排查。
-keep class io.github.ariinyume.morimod.hook.** { *; }
-keep class io.github.ariinyume.morimod.config.** { *; }
-keep class io.github.ariinyume.morimod.data.** { *; }
-keep class io.github.ariinyume.morimod.util.** { *; }
-keep class io.github.ariinyume.morimod.view.** { *; }
-keep class io.github.ariinyume.morimod.MoriModModule { *; }

# SettingsActivity 是 Manifest 里声明的组件（AGP 自带规则会保，但显式写更稳）。
-keep class io.github.ariinyume.morimod.ui.SettingsActivity { *; }

# ── Compose ─────────────────────────────────────────────────────────
# @Composable 方法体在 full mode 下默认可能被内联/移除，保住签名。
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}
# Compose 用的 lambda（ComposableLambda / remember 等）。
-keep,allowobfuscation class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── Kotlin 元数据 ──────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations

# ── org.json 由平台提供，不需要额外 keep（宿主侧用）──────────────────
-dontwarn org.json.**