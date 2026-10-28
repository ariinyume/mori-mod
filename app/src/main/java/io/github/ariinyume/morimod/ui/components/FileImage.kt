package io.github.ariinyume.morimod.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ariinyume.morimod.R

/**
 * 从本地文件读一张图（带下采样），供封面矩阵 / 背景预览使用。
 *
 * 刻意不引 Coil：本模块只解码用户手选的少量图片，
 * 用 `BitmapFactory` + `inSampleSize` 已经足够，且不给模块再加一个运行时依赖。
 *
 * @param targetPx 期望的最长边像素（按 2 倍密度留余量），用于算 inSampleSize。
 */
@Composable
fun rememberFileImage(path: String?, targetPx: Int = 512): ImageBitmap? {
    return remember(path, targetPx) {
        if (path.isNullOrEmpty()) return@remember null
        decodeSampled(path, targetPx)
    }
}

private fun decodeSampled(path: String, targetPx: Int): ImageBitmap? {
    return try {
        // 第一遍：只读尺寸，算采样率（避免把 4000px 大图整张读进内存）。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > targetPx || bounds.outHeight / sample > targetPx) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
    } catch (t: Throwable) {
        // 文件被删 / 不是图片 / OOM：一律当作「没有图」，由调用方走空状态。
        null
    }
}

/**
 * 应用 logo（`mori-logo` 里已经做好的那套，按日夜两套放在
 * `drawable-nodpi` 与 `drawable-night-nodpi`，系统自动选）。
 *
 * 用 painterResource 而不是解码文件：它是打包进 APK 的资源，
 * 深浅色限定符由资源系统接管，Compose 侧零判断。
 */
@Composable
fun MoriLogo(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    cornerRadius: Dp = 12.dp,
) {
    Image(
        painter = painterResource(R.drawable.mori_logo),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius)),
    )
}

/** 没有图时的占位（用色槽画一个中性底，不引任何图片资源）。 */
@Composable
fun ImagePlaceholder(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(cornerRadius),
        modifier = modifier,
    ) {}
}
