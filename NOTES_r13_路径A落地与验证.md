# r13 落地：路径 A（换颜料，不接管绘制）+ Step 0 三条探针

> v0.1.41 / code 10050 · 宿主 `jp.co.eisys.dlsitesound` 2.22.0 / 593
> 前一版 v0.1.40 / code 10049 的九条反馈见 `ANALYSIS_r12_面板玻璃根因与多路径方案.md`

---

## 1. 结构性改动：玻璃不再「自己画」

| | v0.1.40 及之前 | v0.1.41 |
|---|---|---|
| 落点 | `View#draw` / `Drawable#draw` 拦下来，自己 `drawBitmap` 整屏壁纸，`return true` 跳过宿主 | 反射取 `BackgroundDrawable` 里那支 `Paint`（dex: `k:Paint`），`setShader(BitmapShader)`，然后 **`chain.proceed()` 让宿主自己画** |
| 坐标 | `coverRect(bmp, -getLocationInWindow())` | shader 的 `localMatrix` 用 **`getLocationOnScreen()`** 对齐；主窗口里两者等价，Dialog 里只有它是对的 |
| 圆角 | 自己 `clipPath`（读 `float[]` 半径，2.22.0 上根本没有 ⇒ 恒不裁） | 宿主自己的 `j:Path` 完成 |
| 失败模式 | 位图被裁掉 + `drawBitmap` 不抛异常 ⇒ **面板整块消失**（用户第 4/9 条） | 最差是模糊图错位，**不可能消失** |

新文件：`app/src/main/java/io/github/ariinyume/morimod/hook/GlassPaint.java`（`begin/end/reset`）。

## 2. 删掉的四条老路（都写进了 verify 的反向断言）

- `paintWallpaper` 里的玻璃分支 → 改成 `if (isGlassSurface(v, d, b)) return false;`（放行）
- `paintSheetPanel` 的容器铺底 → 整个停用（第 3 条「卡住一块壁纸」、第 5 条「分层」）
- 面板头部渐变 `paint sheet grad` → **drop**（第 5 条「多一行淡阴影条」）
- 面板叶子 `paint sheet leaf` → **drop**

## 3. 九条反馈的对应改法

| 条 | 改法 |
|---|---|
| 1 一加12 小字不变色 | 探针② + **状态色列表保留宿主原有全部状态**（反射 `mStateSpecs`/`mColors` 逐项换色）。旧实现只按 8 个「猜的」状态重建 ⇒ 宿主用 `state_focused` 时选中态颜色丢失 |
| 2 选中粗体无阴影 | `onLightSurface` 删掉「深色字 ⇒ 坐在亮底上」那条反了的规则；改成只看「坐在什么底上」：半透明 ⇒ 加阴影，不透明亮底 ⇒ 不加 |
| 3 卡住壁纸 | 容器不再铺整屏位图（同 §2） |
| 4 三张面板没模糊 | 走路径 A：面板底自己的 Paint 换 shader |
| 5 淡阴影条 + 分层 + 圆角 | 渐变条 drop；圆角交给宿主 Path；全流程无 `saveLayer`/位图上传 ⇒ 天然同帧 |
| 6 能不能统一 | 现在只有一个落点 `glassToken()` |
| 7 封面不显示 + 黑条 | `paintDialogGlass` 排除播放页 + 排除 `avg ≥ 0.90` 的不透明图；`dropVeil` 排除横向图层（`w > h*1.2`，那块 1440×1080 是封面横幅）；全屏对齐改用 onScreen 后不再留黑条 |
| 8 紫遮罩回来 | `isDimMask` 的 drop 判据从 `swapLeaf`（`kids>0` 早退，永远空转）挪到 `paintWallpaper` |
| 9 宿主界面消失 | 与第 4 条同因，路径 A 后不可能再发生 |

## 4. 三条探针（下一轮直接看这三行）

```
glassA -> <view> <w>x<h> scr=<x>,<y> b=<l>,<t> sh=1 bmp=<W>x<H>      # 预算 12
glassA img -> <view> <w>x<h> kind=veil|atmos tint=16%                # 预算 12
glass MISS (<why>) → 保留宿主原底色                                   # 预算 8
shadow ts=<px> r=<px> light=<0|1> dark=<0|1> tx=#aarrggbb n=<前6字>  # 预算 20
tc <int|csl<N>> px=<px> sd=<d> sp=<s> small=<0|1> #in->#out n=<前6字> # 预算 20
```

## 5. 验证

`bash tools/run_verify.sh`（两次 clean 构建 + 四层硬验证）

- APK 71 / DEX **142**（+1：状态表保留）/ RES 56 / DET 4，**0 FAIL**
- 新增反向断言：不再自己画整屏位图、不再 `clipByOutline` 裁圆角、不再 `paint sheet grad/leaf`

## 6. 下一轮若还有问题，优先看

1. `glassA` 一条都没有 ⇒ `GlassPaint.paintField()` 没找到 Paint 字段（把 `glass MISS (paint)` 一起看）
2. `glassA` 有但面板还是紫 ⇒ 命中的是「不透明亮底」以外的分支，看 `surface … => GLASS` 有没有打
3. `tc cslN` 里 `N` 很小（1/2）⇒ 宿主的列表本来就单色，问题在 `remapSmallText` 的门槛
4. `shadow … light=1` 出现在选中 tab 上 ⇒ 那块底被判成「不透明亮底」，看 `tx` 的亮度
