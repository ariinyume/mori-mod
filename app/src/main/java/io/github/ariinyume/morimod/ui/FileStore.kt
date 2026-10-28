package io.github.ariinyume.morimod.ui

import android.content.Context
import android.net.Uri
import io.github.ariinyume.morimod.config.MoriConfig
import io.github.ariinyume.morimod.ui.BackupManager

/**
 * 文件选择结果的落地工具。
 *
 * 素材按「引用路径」记录，不复制二进制（备份体积可控，见 BackupManager 注释）。
 * 这里把 `content://` 复制到模块自有目录，返回一个绝对路径字符串存进配置。
 */
internal object FileStore {

    /** 模块自有素材目录名（落在 filesDir 下）。 */
    private const val DIR_MEDIA = "media"
    private const val DIR_FONT = "fonts"

    /**
     * 把 [uri] 复制进模块目录，返回绝对路径。
     *
     * 复制失败（文件不存在 / 无权限 / 空间不足）一律返回 null，由调用方给提示。
     * 用「先写临时文件再 rename」避免半截文件被写进配置。
     */
    fun importUri(ctx: Context, uri: Uri, kind: String): String? {
        val dirName = if (kind == KIND_FONT) DIR_FONT else DIR_MEDIA
        val dir = java.io.File(ctx.filesDir, dirName)
        if (!dir.exists() && !dir.mkdirs()) return null

        val ext = guessExt(ctx, uri, kind)
        // ★ v0.1.5：字体**保留原文件名**。
        //   旧实现一律存成 `m<base36时间戳>.ttf`，「我的字体」卡片里就只能显示
        //   `mmvkvi89b.ttf` 这种鬼东西 —— 用户完全认不出自己导的是哪一款。
        //   现在用 SAF 给的 DISPLAY_NAME（净化后）当文件名，重名才加 `-2` 后缀；
        //   重复导入同一个文件（大小一致）直接复用已有路径，不产生 -2/-3 垃圾。
        val tmpName = "import_" + System.currentTimeMillis().toString(36) + ext
        val tmp = java.io.File(dir, tmpName)

        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        output.write(buf, 0, r)
                    }
                    output.flush()
                    output.fd.sync()
                }
            } ?: return null
            val dst = if (kind == KIND_FONT) {
                pickFontTarget(ctx, uri, dir, ext, tmp.length())
            } else {
                java.io.File(dir, "m" + System.currentTimeMillis().toString(36) + ext)
            }
            if (dst.exists() && dst.length() == tmp.length()) {
                // 同名同长 ⇒ 就是同一个文件，复用，别把 tmp 留成垃圾
                tmp.delete()
                return dst.absolutePath
            }
            if (!tmp.renameTo(dst)) {
                // rename 失败（跨状态 / 已存在）：退化成复制覆盖
                try {
                    tmp.copyTo(dst, overwrite = true)
                    tmp.delete()
                } catch (t: Throwable) {
                    tmp.delete()
                    return null
                }
            }
            dst.absolutePath
        } catch (t: Throwable) {
            tmp.delete()
            null
        }
    }

    /**
     * 给字体挑一个**人类看得懂**的文件名：`思源黑体.ttf`、`AlibabaPuHuiTi-3-55-Regular.otf`…
     *
     * <p>规则：净化 DISPLAY_NAME（去掉路径分隔符与控制字符，限长 48），保留扩展名；
     * 重名时依次尝试 `name-2.ext`、`name-3.ext`。同名同大小视为同一份文件（复用）。
     */
    private fun pickFontTarget(
        ctx: Context,
        uri: Uri,
        dir: java.io.File,
        ext: String,
        size: Long,
    ): java.io.File {
        val raw = displayName(ctx, uri)
        val stem0 = raw.substringBeforeLast('.', raw)
        val stem = cleanStem(stem0)
        // ⚠️⚠️ v0.1.6 真凶：`ext` **已经带点**（guessExt 返回 ".ttf"），
        //   旧代码又拼了一个 "." ⇒ 文件名变成 `DingTalkJinBuTi-Regular..ttf`。
        //   后果有两层（真机日志逐条对上）：
        //     ① 素材广播的路径校验 `rel.contains("..")` 把它当成**目录穿越**直接拒绝
        //        → `[MoriMod:Sync] asset denied fonts/DingTalkJinBuTi-Regular..ttf`
        //        → 宿主永远拿不到字体文件 → 静默回落系统字体（用户看到的就是"换了没用"）；
        //     ② 界面上 `substringBeforeLast('.')` 留下一截尾巴 → 每一项后面都挂一个点。
        //   所以这里**不能**再补点，直接拼。
        var i = 1
        while (true) {
            val name = if (i == 1) "$stem$ext" else "$stem-$i$ext"
            val f = java.io.File(dir, name)
            if (!f.exists()) return f
            if (f.length() == size) return f      // 同一份文件重复导入
            i++
            if (i > 99) return java.io.File(dir, "$stem-${System.currentTimeMillis().toString(36)}$ext")
        }
    }

    /**
     * 把展示名净化成安全的文件名主干（**不含扩展名，也不含结尾的点**）。
     *
     * <p>`..` 必须折叠：连续两个点会被任何「防目录穿越」的校验当成父目录，
     * 轻则文件读不到，重则整条素材通道失效（v0.1.6 踩过）。
     */
    fun cleanStem(raw: String): String = raw
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
        .replace(Regex("\\.{2,}"), ".")
        .replace(Regex("\\.+$"), "")
        .trim()
        .take(48)
        .ifEmpty { "font" }

    /**
     * 只做「把连续的点收成一个」的展示用清洗（v0.1.6）。
     *
     * <p>旧版本导入的字体在磁盘上叫 `xxx..ttf`，界面原来直接把文件名甩出来，
     * 于是每一项后面都挂一个点。改名迁移会处理磁盘，这里再兜一层：
     * 就算有人从备份里导入了旧路径，显示也不会带那个尾巴。
     */
    fun tidyName(name: String): String =
        if (name.contains("..")) name.replace(Regex("\\.{2,}"), ".") else name

    /**
     * 修掉旧版本留在磁盘上的 `xxx..ttf`（v0.1.6 迁移）。
     *
     * <p>只在设置页启动时跑一次：`files/fonts` 里名字含连续点的改成正常名字，
     * 返回「旧绝对路径 → 新绝对路径」，调用方拿它去同步 `f4FontFile` / `f4FontLib`。
     * 文件名改不动（被占用等）就不返回，配置保持原样 —— 宁可不动，也不要指向空路径。
     */
    fun repairFontFileNames(ctx: Context): Map<String, String> {
        val dir = java.io.File(ctx.filesDir, DIR_FONT)
        val files = dir.listFiles() ?: return emptyMap()
        val out = HashMap<String, String>()
        for (f in files) {
            if (!f.isFile) continue
            val name = f.name
            if (!name.contains("..")) continue
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            val stem = cleanStem(base)
            val fixed = stem + ext
            if (fixed == name) continue
            var target = java.io.File(dir, fixed)
            var n = 2
            while (target.exists() && target.length() != f.length()) {
                target = java.io.File(dir, "$stem-$n$ext")
                n++
            }
            if (target.exists()) {
                // 同名同长 ⇒ 就是同一份文件，删掉这份坏的，把引用指过去
                if (f.delete()) out[f.absolutePath] = target.absolutePath
                continue
            }
            if (f.renameTo(target)) out[f.absolutePath] = target.absolutePath
        }
        return out
    }

    /**
     * 把一张位图落成素材文件（裁切结果用，v0.1.11）。
     *
     * <p>命名必须走 [cleanStem] 那套规矩：**不能出现连续点** —— 素材通道的路径校验
     * 逐段判断 `..`，`bg..jpg` 会被当成目录穿越直接拒掉（v0.1.6 字体踩过同一个坑）。
     *
     * @return 绝对路径；写失败返回 null（调用方给提示，绝不静默）
     */
    fun saveBitmap(ctx: Context, bmp: android.graphics.Bitmap, prefix: String): String? {
        if (bmp.isRecycled) return null
        val dir = java.io.File(ctx.filesDir, DIR_MEDIA)
        if (!dir.exists() && !dir.mkdirs()) return null
        val name = prefix + System.currentTimeMillis().toString(36) + ".jpg"
        val f = java.io.File(dir, name)
        return try {
            java.io.FileOutputStream(f).use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 94, out)
                out.flush()
                out.fd.sync()
            }
            if (f.length() <= 0L) {
                f.delete()
                null
            } else {
                f.absolutePath
            }
        } catch (t: Throwable) {
            f.delete()
            null
        }
    }

    /** 删掉一个素材文件（裁切后原图就没用了；失败无所谓，别把异常甩给调用方）。 */
    fun delete(path: String): Boolean {
        if (path.isEmpty()) return false
        return try {
            java.io.File(path).delete()
        } catch (t: Throwable) {
            false
        }
    }

    /** 读回导入的文本（备份导入用）。 */
    fun readText(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * 把文本写进用户通过 SAF 选定的目标（「导出列表」用）。
     *
     * 返回是否成功 —— 失败时由调用方 toast，不静默吞掉。
     */
    fun writeText(ctx: Context, uri: Uri, text: String): Boolean = try {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        }
        true
    } catch (t: Throwable) {
        false
    }

    /** 展示用文件名（取不到就退回路径尾段）。 */
    fun displayName(ctx: Context, uri: Uri): String {
        val q = ctx.contentResolver.query(uri, null, null, null, null)
        q?.use {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) {
                val n = it.getString(idx)
                if (!n.isNullOrEmpty()) return n
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    }

    /** 从备份文本恢复。校验不通过返回 null（调用方不得覆盖现有配置）。 */
    fun importBackup(text: String?): MoriConfig? =
        if (text.isNullOrEmpty()) null else BackupManager.importText(text)

    private fun guessExt(ctx: Context, uri: Uri, kind: String): String {
        // ★ v0.1.4：字体**保留真实扩展名**。
        //   旧实现无条件返回 ".ttf"，于是用户导入的 .otf 被存成 xxx.ttf ——
        //   虽然 Typeface 按内容嗅探能读，但「我的字体」列表里全是 ttf，
        //   而且没法据扩展名做类型校验（下面 fontExt 就靠它）。
        if (kind == KIND_FONT) return "." + (fontExt(ctx, uri) ?: "ttf")
        val t = uri.lastPathSegment?.substringAfterLast('.', "") ?: ""
        return if (t.isEmpty()) ".img" else ".$t"
    }

    /** 字体扩展名白名单（只认 ttf / otf）。 */
    private val FONT_EXTS = setOf("ttf", "otf")

    /**
     * 取文件的字体扩展名（ttf / otf），**不是字体就返回 null**。
     *
     * <p>判据顺序：① SAF 的 DISPLAY_NAME 扩展名；② uri 路径尾段；③ MIME 类型。
     * 三条都判不出来才算「不认识」—— 宁可放过合法字体，也不能因为某些
     * 文件管理器不给 DISPLAY_NAME 就把用户的 ttf 拒掉。
     */
    fun fontExt(ctx: Context, uri: Uri): String? {
        val byName = extOf(displayName(ctx, uri)) ?: extOf(uri.lastPathSegment ?: "")
        val mime = try {
            ctx.contentResolver.getType(uri)
        } catch (t: Throwable) {
            null
        }?.lowercase()
        val byMime = when {
            mime == null -> null
            mime.contains("opentype") || mime.contains("otf") -> "otf"
            mime.contains("truetype") || mime.contains("ttf") -> "ttf"
            else -> null
        }
        val ext = byName ?: byMime ?: return null
        // ★ v0.1.5：扩展名/MIME 只是"声称"，最后一定要看**文件头**。
        //   用户改过名的 zip / apk / 文档，扩展名同样是 .ttf，只看名字会放进来，
        //   然后在宿主里静默渲染成系统字体 —— 比直接拒绝糟糕得多。
        return if (looksLikeFont(ctx, uri)) ext else null
    }

    /**
     * 读前 4 字节判断是不是字体容器（TrueType / OpenType / TTC）。
     *
     * <p>魔数：`0x00010000`、`OTTO`（CFF OpenType）、`true`、`typ1`、`ttcf`（集合）。
     * 读不到（权限 / 空文件）按"不是字体"处理 —— 反正后面也导入不了。
     */
    private fun looksLikeFont(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(4)
            var n = 0
            while (n < 4) {
                val r = input.read(head, n, 4 - n)
                if (r <= 0) break
                n += r
            }
            n == 4 && isFontMagic(head)
        } ?: false
    } catch (t: Throwable) {
        false
    }

    private fun isFontMagic(b: ByteArray): Boolean {
        if (b.size < 4) return false
        val tag = String(b, 0, 4, Charsets.ISO_8859_1)
        return when {
            b[0] == 0.toByte() && b[1] == 1.toByte() && b[2] == 0.toByte() && b[3] == 0.toByte() -> true
            tag == "OTTO" || tag == "true" || tag == "typ1" || tag == "ttcf" -> true
            else -> false
        }
    }

    private fun extOf(name: String): String? {
        val e = name.substringAfterLast('.', "").lowercase()
        return if (e in FONT_EXTS) e else null
    }

    const val KIND_IMAGE = "image"
    const val KIND_FONT = "font"
}