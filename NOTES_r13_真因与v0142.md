# r13（v0.1.42）—— 这一轮查到的真因，以及为什么之前 12 轮都没修好

> 证据：`LSPosed_20261028_110637.zip`（三份 modules 日志，含 **0.1.41 / code 10050** 运行记录）
> + 录屏 `Record_2026-10-28-11-01-12` **8fps 抽帧 958 张**（`work/r13/f13`）
> + 逐像素对照（`work/r13/crop`）+ 宿主 dex（`_host2202/decoded`）

---

## 0. 先说结论：为什么「怎么改都不好」

前 12 轮的探针本身是坏的，不是判据不够准：

```
hit() 原来按前缀 60 秒限流 → 面板一打开玻璃连画上百帧，第一条就把时间戳占满
⇒ 之后整段录屏里所有面板、所有分支，一条日志都没有。
```

r13 的日志就是铁证：`glassA` 只有 12 条（全部集中在 11:01:15~16 这一秒），
`drop sheet leaf` 只有 1 条，而录屏里用户开了 **6 个面板**。
**没有观测就没有修复** —— 所以每一轮都是「猜一个判据 → 编译 → 用户说没变」。
v0.1.42 把 `hit()` 改成**按「动作 + 类名 + 尺寸 + 位置」去重**（上限 140 条），
一次录屏就能看全「哪些层被谁接管了、哪些没被接管、为什么」。

---

## 1. 一个之前完全漏掉的靶点：**宿主自己就有毛玻璃层（expo-blur）**

dex 实证（`_host2202/decoded/dex_classes2.dex.txt`）：

```
expo.modules.blur.ExpoBlurView  (extends ExpoView)
  ├─ blurView : eightbitlab.com.blurview.BlurView   ← 构造函数里 addView() 加进来的
  ├─ blurRadius : F      tint : TintStyle     blurMethod : BlurMethod
  └─ applyTint() / applyBlurViewOverlayColorCompat(Z)V / setBlurRadius(F)V
```

**我们十几版从来没碰过它**（`grep ExpoBlurView` 全工程 = 0 命中）。
而它恰好同时解释了 5 条反馈：

| 用户原话 | 真相 |
|---|---|
| 5① 「背景深色底」 | BlurView 的 **dark tint** 盖在我们的壁纸上 |
| 6 「阴影遮罩又回来了，**不是紫色的，是高斯模糊**」 | 「是高斯模糊」就是 BlurView 的白描 |
| 7 「点开自己建的播放列表，**背景高斯模糊又回来了**」 | 同上，页面里有一个 BlurView |
| 3 「面板背后变成了**深色底**」 | 深色那层是 BlurView，我们的玻璃画在它下面 |
| 4 「**先是深色底，再变成壁纸+模糊**」 | 第一帧 BlurView 先出图，第二帧我们的 shader 才生效 |

v0.1.42：`hookExpoBlur()` 双层保险
1. `eightbitlab BlurView#draw` → **不画**（它自己没有内容子 View，dex 已证）；
2. `ExpoBlurView#onAttachedToWindow / configureBlurView` → 把内部那支 `blurView` 置 `INVISIBLE`
   （API 31+ 上 eightbitlab 可能改用 RenderEffect，那条路不经过 `draw(Canvas)`）。

---

## 2. 第 8 条「弹窗后面宿主界面完全消失」—— 是我们的壁纸盖的

日志（`11:01:29.895`，正好是确认框出现的那一帧）：

```
paint leaf tint -> ReactViewGroup 1440x3358 top=0 kids=0 bg=CompositeBackgroundDrawable a=1.0
surface ReactViewGroup/1076x466 top=1271 dlg=1 c=#FFECE6EF   ← 白色确认框
```

v0.1.41 把这块 **1440×3358 / alpha=0.7** 的全屏叶子当成「α≥0.99 的底色」，
在上面**涂了一张不透明壁纸**。而它跑在**独立 Dialog 窗口**里、窗口是透明的
⇒ 那一笔直接盖在主窗口的 App 界面之上。

录屏逐帧对照：
- `f_0137`（t≈17s）：弹窗背后还能看到**很淡的**音轨列表文字 —— 被子 0.7 的壁纸洗淡；
- `f_0154`（t≈19s）：列表彻底没了，**只剩壁纸 + 一个白色确认框**。

v0.1.42 的铁律：**浮层里的任何「满屏底 / 满宽底 / 大叶子」一律不画**。
Dialog 窗口后面本来就是主窗口的壁纸，不画 = 自然透出来，而且不会再盖住任何东西。
新增 4 条落点：`drop screen float` / `drop leaf float` / `drop grad float` / `drop overlay`。

---

## 3. 第 3 条「三张信息面板背后是深色底」—— 判据里有一句「读不出颜色就保守不动」

```java
// v0.1.41
int op = d.getOpacity();
if (op != OPAQUE) return op != TRANSPARENT;
Integer solid = solidColorOf(d);
if (solid == null) return false;      // ← 元凶
return luma(solid) < GLASS_DARK_LUMA;
```

宿主的面板底大多是 `CompositeBackgroundDrawable`（LayerDrawable 包一层
`BackgroundDrawable`），打在钩子上的是**内层**那个 drawable —— 它的颜色字段
**读不出来是常态**。于是：

- 「概述 / 简介」这类能读出颜色的面板 → 正常上模糊（用户说「概述正常了」）；
- 「下载音轨 / 导入 / 三点菜单」读不出颜色 → 判成「不是玻璃」→ 宿主照自己那张
  深色 surface 画 → **深色底**。

**这就是用户问了很多轮的那句「为什么有的能做上、有的做不上」。**

v0.1.42 改成「在浮层里 + 够大 ⇒ 默认就是面板」：

```
透明                          → 不是面板
读得出颜色且 Luma > 0.72      → 白卡片 / 白胶囊按钮，不动
其余（深色 / 半透明 / 读不出）→ 玻璃
```
并新增**面积闸** `GLASS_MIN_AREA_PCT=12%`（包住 1440×882 的下载面板，挡住 1076×466 的确认框）。

另外给 `GlassPaint` 补了一条：**paint 的 alpha 被压到 <200 时抬到 255**
（RN 拿同一支 Paint 反复用，某些帧 alpha 很低，shader 一样被 alpha 调制
⇒ 玻璃被画成近乎透明 ⇒ 看起来「还是深色 / 融为一体」）。

### 顺带证明「上一版根本没画上」
用 `f_0205`（面板没弹出时）把那块区域按高斯糊一遍，得到「如果真是毛玻璃该长什么样」：
**上亮下暗的藕紫**；而实际面板是**均匀的暖深棕**（`s170.jpg`）。
两者不同 ⇒ 确认那块面板当时**完全没有玻璃**，只是宿主自己的深色 surface。

---

## 4. 第 1 条「底部导航小字一直是宿主紫」—— 探针读出来的是 `#8a000000`

```
tc csl px=12 sd=3.50 sp=3.4 small=1 #8a000000->#8a000000
```

- 宿主给这些辅助文字的是 **`rgba(0,0,0,0.54)` 这种半透明中性色**，不是紫；
- `remapSmallText` 第一道筛子是 `if (b <= r || r < g) return argb;`（「必须有紫倾向」）
  ⇒ 中性色**永远**被放行、永远不变色。
- 一加 15 上看起来正常，是因为那台机器的主题下发的是带紫的值；一加 12 给的是中性值。

v0.1.42：**中性 + 半透明 + 小字 ⇒ 上强调色**（保 alpha，L\* 抬到 ≥46，
否则 `#8a000000` 的 L\*=0，染什么色相都是黑）。不透明的纯黑/纯白仍不动（那是正文）。

---

## 5. 第 2 条「选中粗体没有阴影」—— 判据反了

```
shadow ts=49 r=0 light=1 dark=0
```

`onLightSurface()` 判「坐在亮底上」→ 半径 0 → `clearShadowLayer()`。
问题是**它量的是宿主声明的颜色**，而我们早就把这棵树的底色换成壁纸了 ——
那个颜色在屏幕上根本不存在。选中态一变，判断就翻面 ⇒ 选中粗体反而没有阴影。

v0.1.42：**阴影一律加**（黑、80%、半径 = 字号 8%），`onLightSurface` 只拿来打日志。
真浅底上黑阴影几乎看不出来，壁纸上却是可读性的关键，不是二选一。

---

## 6. 第 5 条「上方必定出现强调色条」

`paintWallpaper` 的顶栏分支以前只 drop **半透明**的条：

```java
if (loc[1] <= sEdgeTop && d.getOpacity() != OPAQUE) → drop
```

不透明的那条（宿主的「状态栏底色 / 顶部安全区底」）会被换成**模糊壁纸条**
⇒ 屏幕上多出一条和背景不一样的横条，位置正好是状态栏。
v0.1.42 改成**顶部区的条不区分透明度一律不画**（叶子没有子内容，不画不会丢东西）。

---

## 7. 这一版没敢动的

- **第 5② 「作品封面不显示」**：录屏里同一个页面有时有封面（`f_0430` 顶部）、有时没有
  （`f_0345` 整页空）。日志里那块 `ExpoImageView 1440x1080` 被判成 `content(solid)`
  并 `sheet miss`（没被接管），说明**不是我们画掉的**。要定位得靠
  `cover kept` / `cover alpha fix` 这两条在意向帧上的时间戳，下一轮专查。
- **第 7 条**：BlurView 关掉后应该消失；如果还在，说明是 `isPlayWindow` 把
  「播放列表详情」误绑成播放页（铺了 playBmp），日志里看 `play page bind/miss` 即可分辨。
- **`f2PageAlpha`（默认 55）目前只打印、从未使用** —— 页面级壁纸压暗没做，
  所以壁纸亮的地方白字偏灰。这是「可读性」而不是「bug」，要不要开需要你确认。

---

## 8. 这一版的四层验证

`bash tools/run_verify.sh` → **ALL GREEN**
APK 71 · **DEX 150** · RES 56 · DET 4，0 FAIL（确定性成立，两次出包字节一致）

产物：`DLsiteMoriMod-0.1.42-code10051-release.apk`，**23,084,355 B**，
sha256 `c8b5c96bb74282bf32aa07987854f2a2df3f27c9b9fa48addd55ac29cbf3c6dc`

---

## 9. 下一轮请只看这几行（日志都是新格式，不再被饿死）

```
drop blurview -> <view> ...             ← 宿主毛玻璃层被关掉（3/4/5①/6/7 看这条）
glass OK (composite) <view> WxH ...     ← 面板被认成玻璃（新判据）
glass NO (<why>) <view> WxH ...         ← 面板没被认成玻璃，括号里是原因
glassA -> <view> WxH scr=x,y b=l,t sh=1 bmp=WxH   ← shader 真的换上了
drop leaf float / drop screen float     ← 浮层整屏底被放弃（第 8 条看这条）
drop top bar bg -> ...                  ← 顶部条被放弃（第 5 条）
tc csl px=.. sp=.. small=1 #in->#out n=..        ← 第 1 条：IN 和 OUT 不一样才算生效
shadow ts=.. r=.. light=.. dark=..      ← 第 2 条：r 应当永远 > 0
map small #...->#... (neutral)          ← 中性小字被染色
```
