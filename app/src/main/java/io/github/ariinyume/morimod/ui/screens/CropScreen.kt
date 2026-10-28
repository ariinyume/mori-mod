package io.github.ariinyume.morimod.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ariinyume.morimod.ui.S
import io.github.ariinyume.morimod.ui.components.BtnSize
import io.github.ariinyume.morimod.ui.components.BtnTone
import io.github.ariinyume.morimod.ui.components.ConnectedButtonGroup
import io.github.ariinyume.morimod.ui.components.MoriTopBar
import io.github.ariinyume.morimod.ui.components.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 背景裁切页（v0.1.11）。
 *
 * <h3>为什么必须有这一步</h3>
 * 宿主的背景是**整屏铺满**的（`CoverDrawable` 走 center-crop）。用户随手选一张横构图
 * 或带大片留白的图，落上去会被上下裁掉、或者只剩中间一条 —— 他没法预判哪块会露出来。
 * 这里让他自己按**屏幕比例**取景，取到的就是宿主上真正会显示的那块，所见即所得。
 *
 * <h3>交互</h3>
 * · 取景框固定为当前屏幕宽高比，框内区域 = 最终背景；
 * · 双指缩放（1x~6x，1x 已经是「铺满框」的 cover 状态，不会出现空白边）；
 * · 单指拖动平移，边界自动夹住，图片永远盖满框；
 * · 「跳过裁剪」= 直接用原图（老行为），「使用这块」= 按框裁出来落成新图。
 *
 * <h3>坐标系</h3>
 * 绘制与裁切共用同一套换算：`base = max(框宽/图宽, 框高/图高) × 用户缩放`，
 * 于是「框左上角在图片里的位置」= `(-(框宽 - 图宽×base)/2 - offsetX) / base`。
 * 两处只要用同一个 base，画出来的和裁出来的就必然一致。
 */
@Composable
internal fun CropDialog(
    sourcePath: String,
    onCancel: () -> Unit,
    onSkip: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    // 取景比例 = 当前屏幕比例（宿主是整屏铺的，按它裁最贴合）
    val conf = LocalConfiguration.current
    val ratio = remember(conf.screenWidthDp, conf.screenHeightDp) {
        val w = conf.screenWidthDp.coerceAtLeast(1)
        val h = conf.screenHeightDp.coerceAtLeast(1)
        w.toFloat() / h.toFloat()
    }

    val src by produceState<Bitmap?>(initialValue = null, sourcePath) {
        value = withContext(Dispatchers.IO) { decodeForCrop(sourcePath) }
    }
    val image: ImageBitmap? = remember(src) { src?.asImageBitmap() }

    var userScale by remember(src) { mutableStateOf(1f) }
    var offset by remember(src) { mutableStateOf(Offset.Zero) }
    var frame by remember(src) { mutableStateOf(Size.Zero) }
    val scope = rememberCoroutineScope()

    val canUse = image != null && frame.width > 0f

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                MoriTopBar(
                    title = S.CROP_TITLE,
                    onBack = onCancel,
                    titleTop = 8.dp,
                    titleBottom = 12.dp,
                    padV = 0.dp,
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    // 框：在可用区里按屏幕比例内接（宽/高哪边先满算哪边）
                    // ⚠️ 边距要在**算尺寸之前**扣掉：如果写成 `.size(w,h).padding(...)`，
                    //    size 固定的是外层，padding 会从里面缩，框反而比算出来的小一圈。
                    val availW = maxWidth - Space.ScreenH * 2
                    val availH = maxHeight - 16.dp
                    val fitByWidth = availW / ratio <= availH
                    val frameW = if (fitByWidth) availW else availH * ratio
                    val frameH = if (fitByWidth) availW / ratio else availH

                    Box(
                        modifier = Modifier
                            .size(frameW, frameH)
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black)
                            .onSizeChanged {
                                frame = Size(it.width.toFloat(), it.height.toFloat())
                            }
                            .pointerInput(image) {
                                if (image == null) return@pointerInput
                                val img = Size(image.width.toFloat(), image.height.toFloat())
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val box = Size(size.width.toFloat(), size.height.toFloat())
                                    if (box.width <= 0f || box.height <= 0f) return@detectTransformGestures
                                    val ns = (userScale * zoom).coerceIn(1f, 6f)
                                    val moved = offset + pan
                                    val lim = maxPan(ns, box, img)
                                    userScale = ns
                                    offset = Offset(
                                        moved.x.coerceIn(-lim.x, lim.x),
                                        moved.y.coerceIn(-lim.y, lim.y),
                                    )
                                }
                            },
                    ) {
                        val bmp = image
                        if (bmp != null) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val iw = bmp.width.toFloat()
                                val ih = bmp.height.toFloat()
                                if (iw <= 0f || ih <= 0f) return@Canvas
                                val base = max(size.width / iw, size.height / ih) * userScale
                                val dw = iw * base
                                val dh = ih * base
                                val left = (size.width - dw) / 2f + offset.x
                                val top = (size.height - dh) / 2f + offset.y
                                drawImage(
                                    image = bmp,
                                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                                    dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                                    filterQuality = FilterQuality.High,
                                )
                            }
                        } else {
                            // 解码还没回来（或失败）：给一块纯色，不留白
                            Box(Modifier.fillMaxSize().background(Color.Black))
                        }

                        // 取景框边线：让「哪块会被裁走」一眼可见
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(20.dp),
                                ),
                        )
                    }
                }

                Text(
                    text = S.CROP_HINT,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Space.ScreenH, end = Space.ScreenH, top = 16.dp),
                )

                Spacer(Modifier.height(14.dp))

                ConnectedButtonGroup(
                    labels = listOf(S.BTN_CROP_SKIP, S.BTN_CROP_USE),
                    onSelect = { i ->
                        if (i == 0) {
                            onSkip()
                        } else {
                            val bmp = src
                            if (bmp == null) {
                                // 解码失败：当作用户选了「跳过」，至少不空转
                                onSkip()
                            } else {
                                val box = frame
                                val s = userScale
                                val off = offset
                                scope.launch {
                                    val cut = cropToRatio(bmp, box, s, off)
                                    if (cut == null) onSkip() else onConfirm(cut)
                                }
                            }
                        }
                    },
                    modifier = Modifier.padding(
                        start = Space.ScreenH,
                        end = Space.ScreenH,
                        bottom = 20.dp,
                    ),
                    size = BtnSize.S,
                    tone = BtnTone.FILLED,
                    enabled = canUse,
                    stretchAll = true,
                )
            }
        }
    }
}

/** 当前缩放下，图片中心最多能偏多少（保证框里不会露底）。 */
private fun maxPan(scale: Float, box: Size, img: Size): Offset {
    if (box.width <= 0f || box.height <= 0f || img.width <= 0f || img.height <= 0f) {
        return Offset.Zero
    }
    val base = max(box.width / img.width, box.height / img.height) * scale
    return Offset(
        max(0f, (img.width * base - box.width) / 2f),
        max(0f, (img.height * base - box.height) / 2f),
    )
}

/**
 * 把「框内那块」从原图里裁出来。
 *
 * @param box   取景框的**像素**尺寸
 * @param scale 用户缩放（1 = 已铺满框）
 * @param off   用户平移（像素）
 * @return 裁好的位图；参数不合法或解码失败返回 null（调用方回落「用原图」）
 */
private suspend fun cropToRatio(src: Bitmap, box: Size, scale: Float, off: Offset): Bitmap? =
    withContext(Dispatchers.Default) {
        try {
            if (src.isRecycled) return@withContext null
            val iw = src.width
            val ih = src.height
            if (iw <= 0 || ih <= 0 || box.width <= 0f || box.height <= 0f) return@withContext null

            val base = max(box.width / iw, box.height / ih) * scale
            if (base <= 0f) return@withContext null

            // 框左上角在图片坐标系里的位置
            val left = (box.width - iw * base) / 2f + off.x
            val top = (box.height - ih * base) / 2f + off.y

            var w = (box.width / base).roundToInt().coerceIn(1, iw)
            var h = (box.height / base).roundToInt().coerceIn(1, ih)
            var x = (-left / base).roundToInt().coerceIn(0, iw - w)
            var y = (-top / base).roundToInt().coerceIn(0, ih - h)

            val cut = Bitmap.createBitmap(src, x, y, w, h)

            // 输出最长边压到 2048：再大在手机屏上完全看不出，只是白白吃内存
            val longer = max(w, h)
            if (longer <= OUTPUT_MAX_SIDE) {
                cut
            } else {
                val k = OUTPUT_MAX_SIDE.toFloat() / longer
                val nw = (w * k).roundToInt().coerceAtLeast(1)
                val nh = (h * k).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(cut, nw, nh, true)
                if (scaled !== cut) cut.recycle()
                scaled
            }
        } catch (t: Throwable) {
            null
        }
    }

/** 输出位图的最长边上限。 */
private const val OUTPUT_MAX_SIDE = 2048

/** 解码待裁原图：最长边压到 2560，够裁出清晰背景又不至于 OOM。 */
private fun decodeForCrop(path: String): Bitmap? {
    if (path.isEmpty()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        var longer = max(bounds.outWidth, bounds.outHeight)
        while (longer / sample > 2560) sample *= 2
        BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    } catch (t: Throwable) {
        null
    }
}
