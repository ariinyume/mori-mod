# MoriMod · DLsiteSound 美化 / 功能优化模块

> 一个 LSPosed / Xposed 模块，为 **DLsiteSound** 提供自定义背景、强调色、毛玻璃面板与字体替换等功能（或者其他什么梦到的功能）。
>
> 不修改宿主 APK、不影响宿主签名、不联网、不采集用户信息。
>
> 兴趣驱动开发的项目，薛定谔的稳定性，随时断更跑路。

| | |
|---|---|
| 宿主 | `jp.co.eisys.dlsitesound`（实测 2.23.0） |
| 模块包名 | `io.github.ariinyume.morimod` |
| 当前版本 | `0.1.99` / versionCode `10100` |
| 测试设备 | 一加 15 ColorOS 17.0.0.105 |
| 框架 | libxposed API 102（LSPosed / 现代 Xposed） |
| 状态 | **持续开发中，暂不发布 Release** |

## 功能

| | 内容 |
|---|---|
| **F1** 播放控件 | 系统媒体控件的封面 / 标题 / 艺术家自定义（原定功能，暂不开发） |
| **F2** 背景 | 自定义壁纸（含页面模糊 / 播放页模糊两档）、强调色（按壁纸取色 + Material 风格）、作品封面屏蔽 |
| **F4** 字体 | 全局强制字体替换，带图标字体黑名单（黑名单是编译期常量，不开放配置） |
| **F3 / F5** | 液态玻璃 / 主页布局 —— 目前只保存偏好，宿主侧尚未消费 |
| **F6** 切歌卡顿优化 | **【实验性 · 开发中】** 当前只上线只读诊断探针（统计宿主「谁在重绘 / 谁在失效 / 面积多大」），**暂不改变宿主表现**；后续动画层独立 RenderNode、播放时长缓存等改动统一归这一个开关 |

> F6 是这条线上的**唯一总开关**：关闭时连 hook 都不注册（不是注册后空转）。
> 它针对的是宿主侧卡顿（播放页每帧全屏重绘、GPU 满载），不是本模块自身的开销。

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
内部分析笔记（`doc/`）与规划稿（`*_PRD_*.md`）。
所有本机相关配置走 `local.properties`（已 `.gitignore`）；`gradle.local.properties.example`
里只有占位符。

## 许可

- 代码：[GPL-3.0](LICENSE)
- 内置字体：Source Han Sans SC、Smiley Sans，均为 SIL OFL 1.1，
  授权声明逐字取自字体 `name` 表，见 `app/src/main/assets/fonts/NOTICE.txt` 与 `OFL-1.1.txt`
