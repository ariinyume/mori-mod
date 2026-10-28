# r12 分析：为什么面板高斯模糊改了十几版还是上不去

> 输入：`LSPosed_20261028_100123 / 100415 / 100919.zip` + `Record_2026-10-28-09-58-49.mp4`（一加12，1440×3168）
> 宿主：`jp.co.eisys.dlsitesound` 2.22.0（code 593），运行时日志确认 `==== BUILD 0.1.40 / code 10049`
> 本轮**不改代码**，只做定因 + 方案。

---

## 0. 一句话结论

问题不在「判据又错了」，而在**实现路径选错了**：
我们一直在用「拦截整层绘制 → 自己画一张整屏位图 → 跳过宿主绘制」来做玻璃。
这条路径有三个不可控点（绘制坐标、裁剪区、RenderNode 录制时机），任何一点出问题，
结果都不是「模糊不够好看」，而是**面板整块消失**——这正是截图中看到的现象。

---

## 1. 三条关键证据（可复核）

### 证据 A：玻璃分支「成功」了，但画在看不见的地方

```
09:59:08  surface ReactViewGroup/1440x2888 top=280 op=-1 c=#FF252451 dlg=1 => GLASS
09:59:08  glass -> ReactViewGroup 1440x2888 top=280 kids=3 bg=CompositeBackgroundDrawable/O
```

同一时刻录屏帧（播放位置 00:19）：**面板区域完全没有面板**，只有一张清晰的壁纸 +
浮在上面的文字。既没有 #FF252451 的深色底，也没有模糊。

⇒ 结论：`paintWallpaper()` 返回 `true`（跳过宿主绘制），但我们那张位图**没有落进这个矩形**，
该矩形变成全透明，露出窗口壁纸。

### 证据 B：v0.1.40 新增的「失败必留痕」一次都没触发

整份日志：`glass MISS (bmp/paint)` = **0 条**，`cover kept` = **0 条**。

⇒ 说明玻璃分支既没在 `glassBmp()` 返回 null，也没在 `paintAligned()` 返回 false。
也就是说所有"失败兜底"都认为成功了 —— **失败发生在它们之后**：位图确实画进了 Canvas，
但落点/裁剪让它不可见，而这在代码里没有任何检查。

### 证据 C：同一块面板，滚动一次之后突然变模糊了

- 00:19（刚打开）：面板透明，无模糊
- 00:25（上划一次后）：**整屏强烈模糊**，文字清晰

⇒ 同一条代码路径，两帧结果完全不同 ⇒ 与「对齐用的坐标」强相关，而不是与「判据」相关。

---

## 2. 逐条定因

### 第 4 / 6 / 9 条 —— 三个信息面板没模糊 / 宿主界面"消失"（同一个 bug）

- `paintAligned()` 里决定位置只有一行：`RectF dst = coverRect(bmp, -loc[0], -loc[1]);`
  其中 `loc` 来自 `v.getLocationInWindow()`。
- 这块面板在**独立 Dialog 窗口**里（`dlg=1`，`top=280`）。Dialog 窗口的坐标原点 ≠ 屏幕原点，
  而这里把「窗口坐标」当「屏幕坐标」用 ⇒ 位图被整体推离面板。
- 推出去之后 `c.clipRect(bounds)` 一裁 ⇒ **一个像素都不剩**。
- `Canvas#drawBitmap` 不会因为"画在裁剪区外"抛异常 ⇒ `drew=true` ⇒ `ok=true` ⇒ 静默 `return true`。

这就是「要么全有、要么全无」的来源，也是第 9 条（用户说的"宿主界面完全消失"）的真相：
消失的不是宿主界面，是**面板自己那层不透明底**——它被跳过了，而替身没画上。

### 第 3 条 —— 信息卡区域卡住一块壁纸、上划不跟随

```
paint sheet leaf -> ReactViewGroup 1300x1464 top=1966
```

一块**内层卡片**被画上了「整屏壁纸的某个矩形」。这张位图在**录制那一刻**按
`getLocationInWindow` 定死；卡片之后跟着列表滚动，display list 里的 `dst` 不会重算 ⇒ 壁纸钉在原地。

⇒ 与第 4 条同源：**把「整屏壁纸」当局部背景用，这个方法本身不成立。**

### 第 5 条前半 —— "先出现淡阴影、再分层出现模糊"

一次信息面板**不是一个矩形**，而是三层各自画一次：

| 层 | 走哪条路 | 参数 |
|---|---|---|
| 面板容器（无底） | `paintSheetPanel` | 玻璃 |
| 每张卡片 `#FF252451` | `paintWallpaper → isGlassSurface` | 玻璃 |
| 卡片头 `LinearGradientView` | `dropGradient` | 玻璃 |

三层挂载顺序不同，完成时刻就不同 ⇒ 肉眼看到"分层出现"。
v0.1.40 只把叠回色降到 0%（解决了"颜色"），没解决"分层"。

### 第 5 条后半 —— 圆角裁切没到位（本次有 dex 硬证据）

宿主 2.22.0 的 `com.facebook.react.uimanager.drawable.BackgroundDrawable`
实例字段一共 10 个（dex 实证）：

```
a:Context  b:nb.i  c:ca.m  d:F  e:RectF  f:nb.o  g:Z  h:I(颜色)  i:RectF  j:Path  k:Paint
```

**没有 `float[]`。** v0.1.40 新写的 `cornerRadiiOf()` 按 `float[].class` 找字段，
永远找不到 ⇒ 返回 null ⇒ `clipByOutline()` 直接 return ⇒ **一次都没有裁过圆角**。

圆角其实就在它自己的 `j:Path` 里，宿主最后一笔是：

```
canvas.drawRoundRect(rectF, r, r, paint)   或
canvas.drawPath(path, paint)
```

⇒ **不要自己裁。让宿主自己画，我们只换它那支 `k:Paint`。**

### 第 8 条 —— PurePlay 紫色遮罩有时回来

```
surface ReactViewGroup/1440x3168 top=0 op=-3 c=#9919122C dlg=0 => plain
整份日志：drop dim = 0，skip dim = 0
```

v0.1.39 把「不画遮罩」写在 `swapLeaf()` 里，而 `swapLeaf()` 开头有一条铁律：

```
if (kids > 0) return false;   // 容器永不跳过
```

这层遮罩正好是**带子 View 的容器**（1440×3168，`kids>0`）⇒ 永远进不到那条分支。

而 `#9919122C` 这个颜色是通过 `BackgroundDrawable` 画出来的（`noteBigSurface` 正是从那里打的）
⇒ 判据应该放在 `paintWallpaper()` 里，不在 `swapLeaf()` 里。

### 第 7 条 —— 播放页封面不显示 + 概率性黑条

- `cover kept` = 0 ⇒ `rescueCover()` 一次都没进来。
  它的前置是 `isPlayWindow(v)`（必须命中 `PLAY_WIN`）+ `alpha ≤ 0.35` + 高度 22%~88% 屏高。
- 同一段日志里：`dialog glass -> ExpoImageView 1440x1080`、`paint sheet leaf -> ExpoImageView 1440x1080`
  出现 3 次 —— **封面所在的那一层被当成"面板叶子"铺了玻璃**。
- 黑条：`paint veil play -> ExpoImageView 1440x1080` 只覆盖 1080px，而屏幕 3168px，
  对齐用的又是 `-loc`（错位）⇒ 上方留出未绘制的黑区。

⇒ 封面不是"没画"，是**被我们自己铺的东西盖掉/换掉**。

### 第 2 条 —— 选中的粗体没有阴影（这条可以确定）

```java
private static boolean onLightSurface(TextView tv) {
    if (textIsDark(tv)) return true;        // 深字 ⇒ 认为坐在亮底上
    ...
}
// 调用处：radius = onLightSurface(tv) ? 0f : ts * ratio;
//        radius <= 0 ⇒ p.clearShadowLayer();
```

选中态的文案是**强调色**（remap 后是 `#ff8a4a49` / `#ffc79592` 这类偏深色），
`textIsDark()` 为 true ⇒ 返回 true ⇒ `radius=0` ⇒ **主动清掉阴影**。
未选中的浅灰字不满足 `textIsDark` ⇒ 正常加阴影。

⇒ 与"只有选中的粗体没有阴影"完全对上。规则反了：深色字更需要阴影。

### 第 1 条 —— 一加12 小字不变色（不确定，需要一条探针）

- 两个挂点都装上了：`hooked 1 TextView#setTextColor` + `setTextColor(ColorStateList)`，
  `LUT installed: text=1`。
- 但 `remapSmallText()` 的日志一条都没有 —— 注意 `sMapLogs` 预算只有 8 条，
  已被 `paint map` 用光，所以"没打"**不能**证明"没调用"。这是本轮日志的一个盲点。
- 两机差异只在分辨率/密度（1440×3168 vs 1272×2772）。`remapSmallText` 有三道门：
  `b>r>=g`（紫倾向）、`lch[1] >= 1.5`、`|Δhue| <= HUE_WINDOW`。任一道不过就原样返回。

⇒ 这条必须先打探针（argb + px + scaledDensity），不能再猜。

---

## 3. 一个必须记住的日志陷阱

`hit()` 是**按前缀 60 秒限流**的（`HIT_LABEL_GAP_MS`），`log()` 有 320 条总预算。
所以「`glass ->` 只出现 7 次」不能推出「只画了 7 次」。前几轮我有几次据此判断"没走到"，
这个推断方式是错的，必须纠正：**日志只能证明"走到过"，不能证明"没走到"。**

---

## 4. 多路径方案

### 路径 A（推荐 · 主方案）：换掉"背景的颜料"，不换绘制

在 `BackgroundDrawable#draw` 之前：

1. 反射取字段 `k: Paint`；
2. `k.setShader(new BitmapShader(blurBmp, CLAMP, CLAMP))`，
   `shader.setLocalMatrix(translate(-b.left, -b.top))`（**用 drawable 自己的 bounds 对齐，不用 getLocationInWindow**）；
3. `chain.proceed()` —— **让宿主自己 `drawRoundRect` / `drawPath`**。

为什么这条路能一次解决 3/4/5/6/9：

- 不跳过宿主绘制 ⇒ **不可能出现"面板消失"**；最差也只是模糊图错位，仍然是可见的模糊；
- 圆角由宿主自己的 `j:Path` 完成 ⇒ 第 5 条后半自动解决（且不再需要那个不存在的 `float[]`）；
- 只有"背景"被替换，子内容与滚动完全交给 RN ⇒ 第 3 条"卡住壁纸"消失；
- 每块面板只有一处颜料 ⇒ 天然统一（第 6 条）；
- 不涉及 `clipRect`、不涉及 `drawBitmap` 落点 ⇒ 证据 A/B 那类静默失败不可能再发生。

要处理的三件事：
- `Paint#setColor` 的强调色钩子在有 shader 时不影响填充，需要在 `AccentColor.remapPaint` 里跳过带 shader 的 paint；
- 原 shader 用 `WeakHashMap<Drawable, Shader>` 记住并还原，避免影响别的 drawable；
- 位图按 drawable 的 `w×h` 生成（不是整屏），彻底摆脱对齐问题。

### 路径 B（更彻底 · API 31+）：RenderEffect 实时模糊

先把面板底换成"清晰壁纸"（等价于透出壁纸），再给那一层的 RenderNode 加
`RenderEffect.createBlurEffect(r, r, CLAMP)`。

- 优点：模糊由 GPU 对**已绘制的真实内容**做，与滚动/动画/位置完全同步，不存在对齐问题；
- 缺点：会连该层的子内容一起糊 ⇒ 只能挂在"只负责背景的那一层"（这类面板正好存在：那张 `#FF252451` 的叶子）；
- 需要反射 `View#getRenderNode`（@hide）+ `RenderNode#setRenderEffect`（API 31）。两台机 A16/A17 都满足。

这条是"真·背景模糊"，但风险高于 A，建议作为 A 稳定后的升级项。

### 路径 C（保守兜底）：高反差玻璃

不做"整屏对齐"，只按面板自身 `w×h` 画一张固定尺寸的位图：
壁纸糊 38% + 12% 深紫 + 1px 亮边。**同样不能跳过宿主绘制**（用 `chain.proceed()` 之前铺底，
或按键 A 的 shader 方式）。

### 明确废弃

- 用 `View#draw` 接管来画"整屏壁纸的局部"（第 3/4/9 条根因）；
- 自己 `clipPath` 做圆角（宿主没有 `float[]` 半径字段，有 dex 证据）；
- `glassBmp()` 任何"返回原图"的兜底。

---

## 5. 下一轮落地顺序

| 步 | 内容 | 目的 |
|---|---|---|
| 0 | 加 3 条探针：①玻璃分支画完打 `clip=` / `dst=` / `drew=`；②`setTextColor` 打 `argb/px/scaledDensity`（限 20）；③`ensureShadow` 打 `ts/radius/textIsDark`（限 20） | 把第 1 条从"猜"变成"读数" |
| 1 | 按路径 A 重写玻璃（一个方法，约 60 行），**同时删掉现有三条玻璃分支** | 统一，去分层 |
| 2 | 遮罩判据从 `swapLeaf` 挪到 `paintWallpaper`（`isDimMask(d) → return true`） | 第 8 条 |
| 3 | 封面路径：`rescueCover` 改用活体 seekbar 判据、`alpha` 修复后**不再参与任何铺图**；`1440x1080` 这类卡片尺寸的 `ExpoImageView` 从"面板叶子"里排除 | 第 7 条 |
| 4 | `onLightSurface`：深色字**必加**阴影；只有"字浅 + 坐在不透明亮底"才清 | 第 2 条 |

---

## 6. 风险与回滚

- 路径 A 的失败模式是"模糊图错位"，不再是"面板消失"——比现在安全一个数量级；
- 保留 `prefs` 开关可一键关掉玻璃，出问题直接退回"宿主原底色"；
- 第 2 步（遮罩）与第 3 步（封面）互相独立，可分别回滚。
