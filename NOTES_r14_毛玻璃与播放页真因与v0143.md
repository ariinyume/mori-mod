# r14（v0.1.43）—— 逐条根因与落地

> 证据：`LSPosed_20261028_142649.zip`（Modules 日志，进程 13:56 / 14:09 两次冷启动，跑到 14:26:27）
> + 录屏 `Record_2026-10-28-14-24-52` 对应的那一段（14:10:07 ~ 14:26:27，321 条 MoriMod 日志）
> + 宿主 DLsiteSound 2.22.1

先移除第 8 条（弹窗后面宿主界面消失）后需处理的 6 类问题编号沿用用户编号：1 / 2 / 3 / 5 / 6 / 7。

---

## 0. 一条最常用的判据：日志里有没有「人证」

r13 把 `hit()` 从「前缀 + 60 秒限流」改成「动作 + 类名 + 尺寸 + 位置」去重之后，
这一版第一次能**看全**整段操作。最有价值的一条结论是：

```
全轮 `drop blurview` = 0 条（0.1.42 曾据此断言「expo-blur 已关掉」）
```

**钩子装上了，一次都没触发。** 后面 §1 整节都是这条读数推出来的。

---

## 1. 第 2 / 3 / 5① 条：深色底与「先深色再变壁纸」——两个各自独立的因

### 1.1 `BlurView#draw` 的判据把自家 TARGET 自己筛掉了（2 / 5①）

v0.1.42 写的是「`kids == 0` 才认定为 expo-blur 的 BlurView」：

```java
// v0.1.42
if (sArmed && self instanceof View && ((View) self).getChildCount() == 0) { … }
```

而 r14 日志里 **eightbitlab 新版 BlurView 内部会挂 controller 子 View**，
`kids > 0` ⇒ 判据永不命中 ⇒ BlurView 照常出图 ⇒ 深色 tint 盖在我们的壁纸之上：

- 第 2 条「信息面板背后变成深色底」；
- 第 5① 条「背景深色底」；
- 第 6 / 7 条里那层「不是紫的、是高斯模糊」的遮罩 —— 「高斯模糊」四个字就是 BlurView 的白描。

**v0.1.43**：`BgPeel#hookExpoBlur` 三重保险，且②里同时「扫字段 + 直接遍历子 View + 清 RenderEffect」：

| 保险 | 落点 | 覆盖 |
|---|---|---|
| ① `BlurView#draw` / `#dispatchDraw` 不画 | `BgPeel:1044~1090` | 去掉 `kids == 0`，只看 `w>0 && h>0` |
| ② `ExpoBlurView` 在 attach/layout/draw/setBlurRadius 后隐藏全部 BlurView 后代 | `BgPeel:1094~1141`、`hideBlurDescendants` | 反射字段拿不到的那一支走 child 遍历兜住 |
| ③ `View#setRenderEffect` 对 BlurView 传 null | `BgPeel:1071~1089`、`muteBlurView` | API 31+ 的 RenderEffect 通路不经 `draw(Canvas)` |

### 1.2 玻璃第一帧就已经「画歪了」（3 / 2）

`GlassPaint` 用 `getLocationOnScreen()` 给 `BitmapShader` 对齐。日志铁证：

```
14:11:16.246  glassA -> ReactViewGroup 1440x2888 scr=0,3448 …      ← 越界
14:11:28.585  glassA -> ReactViewGroup 1440x2888 scr=0,280  …      ← 同一块的终态
14:11:33.053  glassA -> ReactViewGroup 1440x686  scr=0,5650 …      ← 越界更远
```

屏高只有 **3168**，而 `scrY` 是 3448 / 5650 —— 那个数字里混进了
**RN Modal / BottomSheet 的滑入动画偏移**。结果有两档：

- 动画播完 → 后面的帧 scrY 回到 280 → 先是深色（第 1 帧取到 CLAMP 边缘的深色像素），再变壁纸 ⇒ **第 3 条**；
- 窗口停在「贴底偏移」态 → scrY 一直越界 ⇒ 一直是 CLAMP 边缘的深色 ⇒ **第 2 条**。

**v0.1.43**：新增 `GlassPaint#stableScreenLoc()` —— 先把到根为止的 `translationX/Y` 累计减掉，
得到**布局终态**屏幕坐标；仍越界时按「窗口终态原点」兜底（近全屏 → `(0,0)`，矮窗口 → `(0, 屏高 - 根高)`）。
第一帧就对准壁纸，不再有「闪一下深色」这个过程。探针同步加上：`glassA … scr=x,y rawY=<未去抖的值>`，
下一轮只要 `rawY` 与 `scr` 不一致且 `scr` 落进屏内，就是生效了。

---

## 2. 第 6 / 7 条：播放页判定里有一条「过度兜底」

### 2.1 「比屏还高 ⇒ 播放页」必须删

```java
// v0.1.42
if (v != null && sSH > 0 && v.getHeight() > sSH) return true;
```

日志：列表 / 播放列表详情的页面底图是 **1440x3328**，而 `sSH` 是 **3168**：

```
14:25:10.653  blur bg r=0 1440x3328 top=0 pg=1 ->play     ← 详情/列表页被判成播放页
14:25:10.653  play seek -> ReactSlider 1300x63 top=1685
```

宿主详情页的底图本来就比「含安全区的屏高」更高一截 ⇒ 整段被当成播放页
⇒ 铺播放档（糊）⇒ 表现就是「PurePlay 上面的高斯模糊遮罩**概率**出现」「自己建的播放列表 background
**概率**出模糊」。「概率」的来源很简单：是否恰好能看到一个活 seekbar。

**v0.1.43**：`playContext()` 只剩两条硬判据 —— 活体 seekbar（`isPlayLive`）+ seekbar 刚加载的短窗口（`isPlayPage`）。

### 2.2 保持窗口 1600ms → 700ms

1.6 秒足够让用户从播放页滑到列表页而页面还带着旧判定 ⇒ 模糊跟着跑过去。
**v0.1.43**：`PLAY_HOLD_MS = 700L`。

### 2.3 顺带把下载条排掉

真正让人不想缩水 hold 的是「切后台一帧判 false」。`notePlaySeek` 的下限从 20 抬到 **36**：
日志里真 seekbar 是 `1300x63`（够高），下载 / 加载条一般 ≤14px（排掉）。
这样即使用 700ms 的短窗口也不会在播放页内部翻面。

---

## 3. 第 5 条：两种随机状态的拆解

用户描述：不断切后台，播放页出现两种状态，且**两种都会有**概率性黑条。

| 观测到的现象 | 归到哪一节 |
|---|---|
| ① 背景深色底 | §1.1 BlurView 没被关掉 |
| ① 作品封面不显示 | 封面本来就走 `rescueCover → noteCoverKept`（日志 `cover kept` / `cover alpha fix -> ReactImageView 2706x2030` 在 14:25:55 连续出现，说明层没丢）；真正盖住它的是上面那层 BlurView 的深色 tint |
| ①/② 上方黑条 | v0.1.42 把顶部区**一律 drop**，切后台回来那一帧底下那层还没画到 ⇒ 留洞 ⇒ 黑条 |

**v0.1.43**：顶部区从「一律 drop」改成「铺**同款**壁纸」，两处（`BgPeel:3402` 的 bar 分支、
`BgPeel:3653` 的叶子分支）：

```java
Bitmap top = playContext(v) ? playBmp() : sWpBmp;    // 与页面背景同一张
if (top != null && paintAligned(c, top, v, b, false)) { … hit("paint top fill -> ", v, b); }
```

这里刻意**不用**条带档模糊图 —— 那是 r13 里「状态栏位置多出一条强调色条」的成因。
半透明 scrim 仍然 drop（实心会在图标前面糊一层，r24 血证）。

---

## 4. 第 1 条：小字不变强调色，真正的两处漏

### 4.1 字号门槛 15sp 太低

白胶囊按钮里的标签通常是 **14~16sp**，卡在 `SMALL_TEXT_SP = 15f` 边缘 ⇒ 漏。
**v0.1.43**：`SMALL_TEXT_SP = 18f`（正文 / 标题一般 ≥20sp，不受影响）。

### 4.2 只有 setTextColor 一次是不够的

底栏与按钮的小字常常是 `ColorStateList` 设一次就再也不走 `setTextColor`；
换壁纸 / 换风格之后 **Paint 里存的还是宿主紫**（`retintTree` 只在 *accent 变化* 那一刻跑，
而「字数多、晚布局到账」的那批 View 是在那之后才画的）。

**v0.1.43**：`BgPeel#forceAccentSmallText()`，在 `View#draw` 里绘制前再逼一次（≤18sp）：

```
中性 && alpha<255  → remapSmallText（保 alpha，L* 抬到 ≥46，否则 #8a000000 染什么色相都是黑）
紫族(b>r && r>=g)  → remapSmallText
不透明纯黑/纯白    → 不动（那是正文）
```

幂等：`remapSmallText(out)` 对产出值返回原值（`purpleish` 预筛会把 b<=r 的强调色直接放回），
所以不会每帧重复 `setTextColor`。

---

## 5. 第 8 条为什么还是动了一下

用户后续确认「弹窗后面宿主界面消失」已经不再复现。保留一条结构性保险（`BgPeel:3126`：近全屏 + 贴顶 = Dialog **背板**，不是面板），
以免将来又把「背板当成面板」铺满模糊壁纸，重新长出同一条问题。

---

## 6. 改了哪些文件

| 文件 | 条目 |
|---|---|
| `hook/BgPeel.java` | `hookExpoBlur` 三重保险（① 去掉 kids==0 限制 ② 后代遍历隐藏 ③ 清 RenderEffect）；`hideBlurDescendants` / `muteBlurView`；`forceAccentSmallText`；`notePlaySeek` 下限 36；`PLAY_HOLD_MS=700`；`playContext` 删掉「比屏还高」；顶部区改 `paint top fill`；Dialog 背板 `glassNo("fullscreen-backdrop")` |
| `hook/GlassPaint.java` | `stableScreenLoc()` —— 去位移动画 + 窗口终态原点兜底；`shader()` 改用它 |
| `hook/AccentLut.java` | `SMALL_TEXT_SP` 15 → 18 |
| `tools/verify_dex.py` | 旧断言随删除项更新 3 条；新增 10 条 v0.1.43 断言（其中 1 条为「比屏还高」的反向断言） |
| `tools/run_verify.py` | 新增：`run_verify.sh` 的跨平台替代（本机无 bash 时的执行入口），APK 名仍从 `app/build.gradle` 推导 |

---

## 7. 验证

`python tools/run_verify.py`（两次 clean 构建 + 四层硬验证）：

```
APK=71  DEX=160  RES=56  DET=4       0 FAIL
确定性：92 个条目内容逐个 sha256 一致 + 中央目录逐字节一致（仅 v2/v3 签名块不同，属预期）
```

产物：`dist/DLsiteMoriMod-0.1.43-code10052-release.apk`
**23,084,354 B**，sha256 `1b44d8ef8b61af7333c5e6b3327c0326a15b7b33443a16402ee30bfc56f1bb68`

---

## 8. 下一轮只看这几行

```
blur layer hidden -> <view> WxH          ← 1.1：宿主毛玻璃层真的被掐了（2 / 5① / 6 / 7）
drop blurview -> <view> WxH              ← 同上，r14 是 0 条，v0.1.43 应该有
glassA -> <view> WxH scr=x,y rawY=…      ← 1.2：scr 必须落进屏内（≤3168）；rawY 不同才打印
blur bg r=? WxH pg=? ->play|clear        ← 2：pg=1 只应出现在真播放页
play seek -> <view> WxH                  ← 2.3：真 seekbar 1300x63 要出现，下载条不许出现
tc <way> px=.. sp=.. #in->#out           ← 4：OUT ≠ IN 才算生效
paint top fill -> <view> WxH             ← 3：顶部区回填了壁纸（不该再有黑条）
```
