# MoriMod - DLsiteSound 美化模块

> LSPosed 运行时注入模块。为 **DLsiteSound** 提供自定义背景、强调色、毛玻璃面板与字体替换。
> 不修改宿主 APK、不影响宿主签名、不联网。

| | |
|---|---|
| 宿主 | `jp.co.eisys.dlsitesound`（实测 2.22.1） |
| 模块包名 | `io.github.ariinyume.morimod` |
| 当前版本 | `0.1.43` / versionCode `10052` |
| 框架 | libxposed API 102（LSPosed / 现代 Xposed） |
| 状态 | **开发调试中，稳定性欠佳，本仓库目前只作为代码备份，暂未发布 Release** |

## 功能

| | 内容 |
|---|---|
| **F1** 播放控件 | 系统媒体控件的封面 / 标题 / 艺术家自定义（暂停开发） |
| **F2** 背景 | 自定义壁纸（清晰 / 模糊 / 遮罩 / 页面透明度 / 跟随桌面壁纸）、强调色（按壁纸取色 + Material 风格）、透明背景 |
| **F4** 字体 | 全局强制字体替换，带图标字体黑名单（黑名单是编译期常量，不开放配置） |
| **F3 / F5** | 液态玻璃 / 主页布局 —— 目前只保存偏好，宿主侧尚未消费 |

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

分析文档统一放在 [`doc/`](doc/)：

| 文件 | 内容 |
|---|---|
| [`doc/NOTES_r14_毛玻璃与播放页真因与v0143.md`](doc/NOTES_r14_毛玻璃与播放页真因与v0143.md) | 最近一轮：毛玻璃层、播放页判定、顶部黑条、小字强调色的逐条根因（含日志人证） |
| [`doc/NOTES_r13_真因与v0142.md`](doc/NOTES_r13_真因与v0142.md) | r13 轮：v0.1.42 的根因与落地 |
| [`doc/NOTES_r13_路径A落地与验证.md`](doc/NOTES_r13_路径A落地与验证.md) | r13 轮：路径 A 的落地步骤与验证 |
| [`doc/ANALYSIS_r12_面板玻璃根因与多路径方案.md`](doc/ANALYSIS_r12_面板玻璃根因与多路径方案.md) | r12 轮：面板玻璃根因分析与多条候补路径 |

## 脱敏说明

本仓库**不含**：签名密钥库（`*.jks`）、签名口令、本机绝对路径、宿主 APK、构建产物。
所有本机相关配置走 `local.properties`（已 `.gitignore`）；`gradle.local.properties.example`
里只有占位符。

## 免责声明

1. 仅供个人学习、研究、测试与自用，不得用于违法违规、侵害他人权益或破坏平台正常服务，作者**不鼓励不建议**将其用于商业用途。
2. 本模块由 AI 辅助开发，仅能保证在开发过程中绝不包含人工主观恶意输入&恶意行为。 AI 产生的代码可能无法完全符合开发预期，请在使用前自行确认。
3. 本模块不修改、不提取受版权保护的音频/文本内容本体，请尊重内容版权方权益，请通过正规渠道购买与支持作品。
4. 本模块为独立开发的第三方工具，本模块与 DLsiteSound（eisys 株式会社）及各手机厂商**无任何隶属或合作关系**。
5. 使用本模块需要 **Root 与 Xposed 框架**，可能违反部分设备的保修条款或某些 ROM 的安全策略，可能带来系统不稳定、失去保修、安全风险等后果，**由此带来的任何风险由使用者自行承担**。
6. 因使用本模块导致的任何问题（包括但不限于账号异常、设备不稳定、数据丢失等），作者**不承担任何责任**，请提前备份重要数据并优先使用非重要账号和非关键场景测试。
7. 适配依赖目标 App 的内部实现，App 更新可能导致模块失效；作者**不保证**模块长期可用或对所有版本 / 机型都适配。
8. 下载、使用本模块即视为同意上述条款。

## 许可

- 代码：[GPL-3.0](LICENSE)
- 内置字体：Source Han Sans SC、Smiley Sans，均为 SIL OFL 1.1，
  授权声明逐字取自字体 `name` 表，见 `app/src/main/assets/fonts/NOTICE.txt` 与 `OFL-1.1.txt`
