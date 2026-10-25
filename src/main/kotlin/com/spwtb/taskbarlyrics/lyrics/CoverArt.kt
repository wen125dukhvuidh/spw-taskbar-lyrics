package com.spwtb.taskbarlyrics.lyrics

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import javax.imageio.ImageIO

/**
 * 内嵌封面读取。
 *
 * 本机音乐库实测：294 个 FLAC **全部**带 PICTURE 元数据块，
 * 所以封面可以直接从文件里拿，不需要联网、也不需要曲库权限。
 *
 * 两级缓存：按歌曲缓存**解码后的原图**（长边最多 512），再按 (尺寸, 圆角)
 * 缓存缩放结果。任务栏上封面只有几十像素，既不能每帧缩放原图，也不能
 * 每换一个尺寸就重读文件、重新解码。
 */
object CoverArt {

    private const val MAX_ENTRIES = 8

    /** 原图缓存条数：当前歌 + 上一首，够覆盖连续切歌。 */
    private const val SOURCE_ENTRIES = 2

    /**
     * 原图长边上限（像素）。
     *
     * 任务栏上的封面最大也就任务栏那么高（几十到一百像素），没必要把
     * 1500×1500 的原图整张留在堆里。512 对最高档设置也绰绰有余，
     * 单张 ARGB 约 1MB，两张缓存总共约 2MB。
     */
    private const val MAX_SOURCE_SIDE = 512

    /** 所有缓存共用一把锁：渲染线程与探针都可能调用。 */
    private val lock = Any()

    private val cache = object : LinkedHashMap<String, BufferedImage?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage?>?): Boolean =
            size > MAX_ENTRIES
    }

    /**
     * 已解码、已居中裁成正方形的原图。
     *
     * 键只到歌曲，不含尺寸——所以拖「封面尺寸 / 圆角 / 整体缩放」时，
     * 只是从这张图重新缩放，不必再打开音频文件、重新解码。
     * 值为 null 表示这首歌确实没有内嵌封面；同样要缓存，否则每一帧都会重新尝试。
     */
    private val sources = object : LinkedHashMap<String, BufferedImage?>(2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage?>?): Boolean =
            size > SOURCE_ENTRIES
    }

    /**
     * 取已缩放到 [targetSize]、且圆角已烘焙好的封面；没有则返回 null。
     *
     * [cornerRadius] 是**半径**（像素）。圆角在这里烘焙进位图，而不是绘制时用 clip 裁——
     * 原因：Java2D 的 `Graphics2D.clip` 对非矩形形状**不做抗锯齿**，
     * 裁出来的角是硬边、发毛。用 `AlphaComposite.DstIn` 乘一张抗锯齿蒙版才是光滑的。
     * 而且烘焙后绘制端只要 1:1 贴图，每帧也省了一次合成。
     *
     * **第一次遇到一首歌时同步读盘 + 解码**：这样换歌后的第一帧歌词和封面是
     * 同一帧切换的（1.4.10 的观感）。原图缓存在 [sources] 里，之后拖动
     * 「封面尺寸 / 圆角 / 整体缩放」只是从内存原图重新缩放，不会重读文件。
     */
    fun forSong(
        songKey: String,
        path: String,
        targetSize: Int,
        cornerRadius: Int
    ): BufferedImage? {
        if (path.isBlank() || targetSize <= 0) return null
        val key = "$songKey@$targetSize@$cornerRadius"
        synchronized(lock) {
            if (cache.containsKey(key)) return cache[key]

            val source = if (sources.containsKey(songKey)) {
                sources[songKey]
            } else {
                // 第一次遇到这首歌：同步解码一次。之后尺寸/圆角变化都从这张原图缩放。
                val decoded = runCatching { decodeSource(File(path)) }.getOrNull()
                sources[songKey] = decoded
                decoded
            }
            if (source == null) {
                cache[key] = null
                return null
            }
            // 缩放/磨角理论上也可能异常（超大图 OOM 等）：兜住，
            // 最坏是这首歌暂时没有封面，不能让渲染线程跟着挂掉。
            val scaled = runCatching {
                roundCorners(scaleSquare(source, targetSize), cornerRadius)
            }.getOrNull()
            cache[key] = scaled
            return scaled
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
            sources.clear()
        }
    }

    /** 读文件 + 解码 + 居中裁成正方形，只做一次，结果进 [sources]。 */
    private fun decodeSource(file: File): BufferedImage? {
        if (!file.isFile) return null
        val bytes = readEmbeddedPicture(file) ?: return null
        val raw = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val side = minOf(minOf(raw.width, raw.height), MAX_SOURCE_SIDE).coerceAtLeast(1)
        return scaleSquare(raw, side)
    }

    /**
     * 把圆角烘焙进 alpha 通道。
     *
     * **不用 `Graphics2D.clip`，也不用 `AlphaComposite.DstIn`**：
     * 前者对非矩形形状不做抗锯齿，角上是硬边；
     * 后者的实现只在自己的图元包围盒内做混合，形状之外不会被清成透明
     * （实测 alpha 全是 255，圆角等于没做）。
     *
     * 这里改成**逐像素解析计算圆角矩形的覆盖率**，每像素 8×8 超采样，
     * 得到 65 级 alpha，边缘足够平滑，而且结果完全由我们控制。
     */
    private fun roundCorners(src: BufferedImage, cornerRadius: Int): BufferedImage {
        val w = src.width
        val h = src.height
        val r = cornerRadius.coerceAtMost(minOf(w, h) / 2)
        if (r <= 0) {
            return src
        }

        val pixels = IntArray(w * h)
        src.getRGB(0, 0, w, h, pixels, 0, w)

        // 8×8 超采样 → alpha 有 65 级，过渡比 4×4（17 级）细腻得多。
        // 代价是每张封面几毫秒，而且只在换歌/改设置时算一次（结果进缓存）。
        val samples = 8
        val total = samples * samples
        val rSquared = (r * r).toDouble()

        for (y in 0 until h) {
            for (x in 0 until w) {
                var covered = 0
                for (sy in 0 until samples) {
                    for (sx in 0 until samples) {
                        val px = x + (sx + 0.5) / samples
                        val py = y + (sy + 0.5) / samples
                        if (insideRounded(px, py, w, h, r, rSquared)) {
                            covered++
                        }
                    }
                }
                if (covered == total) {
                    continue
                }
                val index = y * w + x
                val pixel = pixels[index]
                val alpha = (pixel ushr 24) and 0xFF
                val newAlpha = alpha * covered / total
                pixels[index] = (newAlpha shl 24) or (pixel and 0x00FFFFFF)
            }
        }

        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
        out.setRGB(0, 0, w, h, pixels, 0, w)
        return out
    }

    /**
     * 点是否落在圆角矩形内。
     *
     * 做法：把点夹到「四个圆心的可行域」内矩形里，再判断到最近圆心的距离。
     * 点在平直区域时夹取后距离为 0，必然通过；只在四角才真正起约束作用。
     */
    private fun insideRounded(px: Double, py: Double, w: Int, h: Int, r: Int, rSquared: Double): Boolean {
        val cx = px.coerceIn(r.toDouble(), (w - r).toDouble())
        val cy = py.coerceIn(r.toDouble(), (h - r).toDouble())
        val dx = px - cx
        val dy = py - cy
        return dx * dx + dy * dy <= rSquared
    }

    /**
     * 居中裁剪成正方形，再**多级降采样**到目标尺寸。
     *
     * 为什么要多级：封面原始尺寸通常在 500–1500px，而任务栏上只有 50px 上下，
     * 是 10–30 倍的缩小。Java2D 的双线性插值**一步缩到 1/20 时会严重欠采样**
     * ——结果不是平滑，而是又糊又脏：一部分细节被整个丢掉，
     * 另一部分高频退化成了噪点。
     *
     * 反复减半能把每一级的采样范围收窄到 2×2，逐级累积后效果接近区域平均，
     * 肉眼上明显更锐利。
     */
    private fun scaleSquare(src: BufferedImage, size: Int): BufferedImage {
        val side = minOf(src.width, src.height)
        if (side <= 0) {
            return BufferedImage(size.coerceAtLeast(1), size.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        }
        val sx = (src.width - side) / 2
        val sy = (src.height - side) / 2

        var current = toArgb(src.getSubimage(sx, sy, side, side))
        // 逐级减半，直到最后只剩一小步（不超过 2 倍）。
        //
        // 循环条件必须是 `> size * 2` 而不是 `/2 >= size`：
        // 后者在 800→400→200→100→50 时会停在 50，比目标 51 还小，
        // 于是又要放大回去，白白糊一层。
        while (current.width > size * 2) {
            current = resize(current, maxOf(size, current.width / 2))
        }
        if (current.width != size) {
            current = resize(current, size)
        }

        // 缩得太狠时补一次轻微锐化。
        // 这一步不是可有可无的：正确的降采样（区域平均）天生会压低局部对比，
        // 结果看上去反而比「欠采样的原做法」更糊——所以要把它找回来。
        return if (side.toDouble() / size >= SHARPEN_THRESHOLD) {
            sharpen(current, SHARPEN_AMOUNT)
        } else {
            current
        }
    }

    /**
     * 非锐化掩模（unsharp mask）：`结果 = 原图 + 强度 × (原图 − 3×3 均值)`。
     *
     * 只在目标图（几十像素见方）上做，代价可以忽略。
     * 强度刻意压得比较低，避免出现明显的边缘白边。
     */
    private fun sharpen(image: BufferedImage, amount: Double): BufferedImage {
        val w = image.width
        val h = image.height
        val src = IntArray(w * h)
        image.getRGB(0, 0, w, h, src, 0, w)

        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var count = 0
                for (dy in -1..1) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    for (dx in -1..1) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        val p = src[ny * w + nx]
                        sumR += (p shr 16) and 0xFF
                        sumG += (p shr 8) and 0xFF
                        sumB += p and 0xFF
                        count++
                    }
                }
                val center = src[y * w + x]
                val alpha = (center ushr 24) and 0xFF
                out[y * w + x] = (alpha shl 24) or
                    (boost((center shr 16) and 0xFF, sumR / count, amount) shl 16) or
                    (boost((center shr 8) and 0xFF, sumG / count, amount) shl 8) or
                    boost(center and 0xFF, sumB / count, amount)
            }
        }

        val result = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        result.setRGB(0, 0, w, h, out, 0, w)
        return result
    }

    private fun boost(value: Int, blurred: Int, amount: Double): Int =
        (value + amount * (value - blurred)).toInt().coerceIn(0, 255)

    private fun resize(src: BufferedImage, target: Int): BufferedImage {
        val out = BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            // 用双三次而不是双线性：这里每一级都是恰好减半，
            // 双三次的 4×4 邻域能保留更多局部对比，缩出来的小图更"立得住"。
            // 双线性在大幅缩小后偏软，看着就是糊。
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC
            )
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(
                RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY
            )
            g.drawImage(src, 0, 0, target, target, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /** 统一成 TYPE_INT_ARGB：`getSubimage` 会共享父图 raster，先拷贝一份更可控。 */
    private fun toArgb(src: BufferedImage): BufferedImage {
        if (src.type == BufferedImage.TYPE_INT_ARGB) {
            return src
        }
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.drawImage(src, 0, 0, null)
        } finally {
            g.dispose()
        }
        return out
    }

    // ── 容器解析 ────────────────────────────────────────────────────────

    /** 读内嵌图片的原始字节。支持 FLAC PICTURE 块与 MP3 的 ID3v2 APIC。 */
    fun readEmbeddedPicture(file: File): ByteArray? = when (file.extension.lowercase()) {
        "flac" -> fromFlac(file)
        "mp3" -> fromId3(file)
        else -> null
    }

    /** FLAC：遍历元数据块，找类型 6（PICTURE）。 */
    private fun fromFlac(file: File): ByteArray? {
        RandomAccessFile(file, "r").use { raf ->
            val magic = readFully(raf, 4) ?: return null
            if (String(magic, Charsets.ISO_8859_1) != "fLaC") return null
            while (true) {
                val header = readFully(raf, 4) ?: return null
                val last = (header[0].toInt() and 0x80) != 0
                val type = header[0].toInt() and 0x7F
                val length = ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
                if (type == PICTURE_BLOCK) {
                    val block = readFully(raf, length) ?: return null
                    return parseFlacPicture(block)
                }
                if (last) return null
                raf.seek(raf.filePointer + length)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return null
    }

    /** 区块：类型(4) + MIME(4+len) + 描述(4+len) + 宽高深色(16) + 数据长度(4) + 数据 */
    private fun parseFlacPicture(block: ByteArray): ByteArray? {
        var p = 0
        fun need(n: Int): Boolean = p + n <= block.size
        fun u32(): Long {
            val v = ((block[p].toLong() and 0xFF) shl 24) or
                ((block[p + 1].toLong() and 0xFF) shl 16) or
                ((block[p + 2].toLong() and 0xFF) shl 8) or
                (block[p + 3].toLong() and 0xFF)
            p += 4
            return v
        }
        if (!need(8)) return null
        u32()                                     // picture type
        val mimeLen = u32().toInt()
        if (mimeLen < 0 || !need(mimeLen)) return null
        p += mimeLen
        val descLen = u32().toInt()
        if (descLen < 0 || !need(descLen)) return null
        p += descLen
        if (!need(16)) return null
        repeat(4) { u32() }                       // width / height / depth / colors
        if (!need(4)) return null
        val dataLen = u32().toInt()
        if (dataLen <= 0 || !need(dataLen)) return null
        return block.copyOfRange(p, p + dataLen)
    }

    /** MP3：读 ID3v2 帧，找 APIC。 */
    private fun fromId3(file: File): ByteArray? {
        RandomAccessFile(file, "r").use { raf ->
            val head = readFully(raf, 10) ?: return null
            if (head[0] != 'I'.code.toByte() ||
                head[1] != 'D'.code.toByte() ||
                head[2] != '3'.code.toByte()
            ) {
                return null
            }
            val version = head[3].toInt() and 0xFF
            val size = syncSafe(head, 6)
            if (size <= 0) return null
            val body = readFully(raf, size) ?: return null
            var p = 0
            while (p + 10 <= body.size) {
                val id = String(body, p, 4, Charsets.ISO_8859_1)
                if (id[0] == '\u0000') break
                // v2.4 帧长是 syncsafe，v2.3 是普通大端
                val frameSize = if (version >= 4) syncSafe(body, p + 4) else readU32(body, p + 4)
                val dataStart = p + 10
                if (frameSize <= 0 || dataStart + frameSize > body.size) break
                if (id == "APIC") {
                    return parseApic(body, dataStart, frameSize)
                }
                p = dataStart + frameSize
            }
            return null
        }
    }

    /**
     * APIC：编码(1) + MIME(latin1, NUL 结尾) + 图片类型(1) + 描述(按编码决定结尾) + 图片数据。
     *
     * 描述字段的结尾在 UTF-16 与单字节编码下规则不同，容易踩坑，
     * 所以这里**直接从当前位置找图片魔数**（JPEG `FF D8 FF` / PNG `89 50 4E 47`），
     * 绕开描述解析的不确定性。
     */
    private fun parseApic(body: ByteArray, start: Int, size: Int): ByteArray? {
        val end = (start + size).coerceAtMost(body.size)
        if (start >= end) return null
        val jpeg = indexOf(body, JPEG_MAGIC, start, end)
        val png = indexOf(body, PNG_MAGIC, start, end)
        val from = listOfNotNull(jpeg, png).minOrNull() ?: return null
        return body.copyOfRange(from, end)
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int, end: Int): Int? {
        var i = from
        while (i + pattern.size <= end) {
            var ok = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    ok = false
                    break
                }
            }
            if (ok) return i
            i++
        }
        return null
    }

    /**
     * 精确读 n 字节；读不满返回 null。
     * 注意 `RandomAccessFile` **没有** `readNBytes`（那是 InputStream 的），
     * 只能用 `readFully` 并自己兜住 EOF。
     */
    private fun readFully(raf: RandomAccessFile, count: Int): ByteArray? {
        if (count <= 0) return null
        val buffer = ByteArray(count)
        return try {
            raf.readFully(buffer)
            buffer
        } catch (_: Throwable) {
            null
        }
    }

    private fun syncSafe(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)

    private fun readU32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private const val PICTURE_BLOCK = 6

    /** 缩小倍数超过这个值才锐化：小图缩放本来就不怎么损失对比，加了反而起白边。 */
    private const val SHARPEN_THRESHOLD = 3.0

    /** 锐化强度，压得比较低以免出现明显 halo。 */
    private const val SHARPEN_AMOUNT = 0.3

    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()
    )
}
