# MoriMod · DLsiteSound 美化 / 功能优化模块

> 一个 LSPosed / Xposed 模块，为 **DLsiteSound** 提供自定义背景、强调色、毛玻璃面板与字体替换等功能（或者其他什么梦到的功能）。
>
> 不修改宿主 APK、不影响宿主签名、不联网、不采集用户信息。
>
> 兴趣驱动开发的项目，薛定谔的稳定性，随时断更跑路。

| | |
|---|---|
| 宿主 | `jp.co.eisys.dlsitesound`（实测 2.24.0） |
| 模块包名 | `io.github.ariinyume.morimod` |
| 当前版本 | `0.2.11` / versionCode `10166` |
| 测试环境 | ColorOS 真机（Android 15+） |
| 框架 | libxposed API 102（LSPosed / 现代 Xposed） |
| 状态 | **持续开发中，暂不发布 Release** |

## 功能

| | 内容 |
|---|---|
| **F2** 背景 | 自定义壁纸；**主背景模糊 / 播放页背景模糊 / 信息控件背景模糊** 三档（均 0–50%、步进 5%）；强调色（按壁纸取色 + Material 风格）；作品封面屏蔽 |
| **F4** 字体 | 全局强制字体替换；内置圆构黑体 CHS、普力士（SIL OFL 1.1）；图标字体黑名单（编译期常量） |
| **F5** 主页布局 | 登录态主页区块显隐 / 排序（translationY 收拢 + `ReactRootView` 触控重映射） |
| **F6** 字幕调整 | 总开关；播放页宿主字幕字号 ×1.0–1.5；字幕背景透明 |
| **切歌优化** | **【实验性】** 打开后播放页曲名/作品名停止滚动；关闭时不注册 hook |
| ~~F1 播放控件~~ | 已移除（宿主自带；配置字段仅保留旧备份兼容） |
| ~~F3 液体玻璃~~ | 已移除（效果不佳） |

> **信息控件模糊**覆盖：迷你播放条、导航底栏，以及概述 / 下载 / 菜单 / PurePlay 导入 / 创建播放列表等浮层毛玻璃（与主背景、播放页模糊独立）。
>
> **F5** 走宿主 RN 视图树：隐藏不会改 Yoga，靠上移收拢；点击在 `ReactRootView` 把坐标映回 Yoga 框；滑动中跳过 pack 减卡顿。
>
> **字体（F4）**：2.24.0 起宿主正文改走 `res/font`；主通路仍钩 `Paint#setTypeface`。内置字体面向简体中文阅读，其他语言环境可能出现异体字或缺字。

## 分层铁律

```
hook/  config/  data/  util/  view/   →  Java ，跑在**宿主进程**里
ui/                                   →  Kotlin + Compose ，只跑在**模块自己的设置页进程**
```

- 前者**绝不允许**引用 Compose / AndroidX 视图体系（会一并被注入宿主，风险极高）。
- 两层唯一的耦合点是 `config/` 里的纯数据类（`MoriConfig` 等，纯 Java 无 Android 依赖）。
- LSPosed 三份元数据 `META-INF/xposed/{java_init.list,scope.list,module.prop}`
  **不许有注释行、必须 LF**（裸解析器，混进注释会静默不加载）。

## 构建

工具链：Gradle 8.13 · AGP 8.12.3 · Kotlin 2.2.0 · JDK 17 · compileSdk 36 · buildTools 36.0.0

```bash
cp gradle.local.properties.example local.properties   # 填签名口令与 SDK 路径
./gradlew assembleRelease
# → app/build/outputs/apk/release/DLsiteMoriMod-<ver>-code<code>-release.apk
```

`local.properties` 已在 `.gitignore` 里，**签名口令与 `.jks` 永不进仓库**。

## 验证

出包后跑三层硬验证 + 确定性核对（两次 clean 构建，逐条目比对内容 sha256）：

```bash
python tools/run_verify.py          # 跨平台
bash  tools/run_verify.sh           # 有 bash 的环境
```

| 层 | 脚本 | 看什么 |
|---|---|---|
| 1 | `tools/verify_apk.py` | APK 结构 / 版本号三处一致 / xposed 元数据 / 常量池 |
| 2 | `tools/verify_dex.py` | 关键类与日志锚点真的编进 dex；**读源码**校验判据顺序没被后续改动挤掉 |
| 3 | `tools/verify_res.py` | 资源层：主题 parent、夜间限定符、adaptive-icon |
| 4 | `tools/verify_determinism.py` | 除签名块外逐字节可复现 |

> release 走 v2/v3 签名，签名块含密码学随机量 ⇒ 两次构建的**整体** sha256 必然不同，
> 判据是「除签名块外一致」，不是比整包。

## 文档

逆向分析笔记与规划稿（`doc/`、`*_PRD_*.md`）含内部路径与真机日志片段，**不随仓库分发**，仅本地留存。

已沉淀到源码注释里的结论以 `hook/BgPeel.java` 为准。

## 脱敏说明

本仓库**不含**：签名密钥库（`*.jks`）、签名口令、本机绝对路径、宿主 APK、构建产物、
内部分析笔记（`doc/`）与规划稿（`*_PRD_*.md`）、LSPosed / logcat 原始导出。
所有本机相关配置走 `local.properties`（已 `.gitignore`）；`gradle.local.properties.example`
里只有占位符。README 不写具体机型序列号与完整 ROM 构建号。

## 许可

- 代码：[GPL-3.0](LICENSE)
- 内置字体：圆构黑体 Geource Sans CHS、普力士 Plix Regular，均为 SIL OFL 1.1，
  授权声明见 `app/src/main/assets/fonts/NOTICE.txt` 与 `OFL-1.1.txt`
