package com.spwtb.taskbarlyrics.lyrics

import java.io.File
import java.io.IOException
import java.nio.charset.Charset

/**
 * 从音频文件里直接读内嵌歌词。
 *
 * 之所以要走这条路：SPW 1.18.x 只通过 `onLyricsLineUpdated` 吐「当前行」，
 * 插件拿不到完整时间轴。而绝大多数本地音乐把歌词内嵌在文件标签里，
 * 直接解析一次即可得到完整时间轴，也彻底避开了「边播边攒」的缺口。
 *
 * 实测本机音乐库（298 首：294 FLAC + 4 MP3）内嵌歌词覆盖率 100%，
 * 且没有任何同目录 .lrc，因此不存在与播放器显示不一致的歧义。
 *
 * 注意宿主 JVM 缺 `jdk.charsets` 模块，GBK/Big5 等扩展字符集不可用。
 * 实测数据的编码是 UTF-8（FLAC 规范强制）、UTF-16 与 ISO-8859-1，
 * 都在 java.base 里，因此不受影响。这里对未知编码做安全降级而不是抛异常。
 *
 * 说明：本对象是插件内部使用的工具，不对外构成 API。之所以不是 `internal`，
 * 是为了让 `tools/probe` 下的探针能直接调用它做全库机械校验——
 * 歌词解析的正确性（尤其是双语配对）只靠肉眼看渲染结果是查不出来的。
 */
object EmbeddedLyrics {


    private val LYRIC_KEYS = listOf(
        "SYNCEDLYRICS", "LYRICS", "UNSYNCEDLYRICS", "LYRIC", "UNSYNCED LYRICS"
    )

    /** 读取内嵌歌词原文；没有则返回 null。 */
    fun read(audioFile: File): String? = (
        try {
            when (audioFile.extension.lowercase()) {
                "flac" -> fromFlac(audioFile)
                "mp3" -> fromId3(audioFile)
                "m4a", "mp4", "aac" -> fromMp4(audioFile)
                // ogg / opus / wv / ape 暂未实现；这些格式命中率低，后续按需补
                else -> null
            }
        } catch (_: Throwable) {
            // 文件损坏、标签异常等都不应该影响播放，静默降级
            null
        }
        )?.takeIf { it.isNotBlank() }

    /** 同目录同名 .lrc（实测本机为 0 个，作为兜底保留） */
    fun readSidecar(audioFile: File): String? {
        val base = audioFile.nameWithoutExtension
        for (ext in listOf("lrc", "LRC")) {
            val candidate = File(audioFile.parentFile, "$base.$ext")
            if (candidate.isFile) {
                return try {
                    candidate.readText(Charset.forName("UTF-8")).takeIf { it.isNotBlank() }
                } catch (_: Throwable) {
                    null
                }
            }
        }
        return null
    }

    // ── FLAC ────────────────────────────────────────────────────────────────
    // 结构：fLaC + 连续 metadata block。每块头 4 字节：最后一块标志(1bit)
    // + 类型(7bit) + 长度(3字节 BE)。类型 4 = VORBIS_COMMENT。

    private fun fromFlac(file: File): String? {
        file.inputStream().buffered().use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4 || magic.decodeToString() != "fLaC") {
                return null
            }
            repeat(MAX_FLAC_BLOCKS) {
                val header = ByteArray(4)
                if (input.read(header) != 4) return null
                val isLast = (header[0].toInt() and 0x80) != 0
                val blockType = header[0].toInt() and 0x7F
                val length = ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)

                if (blockType == BLOCK_VORBIS_COMMENT) {
                    val data = ByteArray(length)
                    if (readFully(input, data) != length) return null
                    return pickLyric(parseVorbisComment(data))
                }

                if (skipFully(input, length.toLong()) != length.toLong()) return null
                if (isLast) return null
            }
        }
        return null
    }

    private fun parseVorbisComment(data: ByteArray): Map<String, String> {
        val tags = LinkedHashMap<String, String>()
        try {
            var off = 0
            val vendorLength = readLe32(data, off); off += 4
            off += vendorLength
            val count = readLe32(data, off); off += 4
            repeat(count) {
                if (off + 4 > data.size) return@repeat
                val itemLength = readLe32(data, off); off += 4
                if (off + itemLength > data.size) return@repeat
                val item = data.decodeToString(off, off + itemLength)
                off += itemLength
                val eq = item.indexOf('=')
                if (eq > 0) {
                    // Vorbis Comment 规范要求键为 ASCII、值为 UTF-8
                    tags[item.substring(0, eq).uppercase()] = item.substring(eq + 1)
                }
            }
        } catch (_: Throwable) {
            // 解析异常时返回已读到的部分
        }
        return tags
    }

    // ── MP3 / ID3v2 ─────────────────────────────────────────────────────────

    private fun fromId3(file: File): String? {
        file.inputStream().buffered().use { input ->
            val header = ByteArray(10)
            if (input.read(header) != 10) return null
            if (header.decodeToString(0, 3) != "ID3") return null

            val major = header[3].toInt() and 0xFF
            val flags = header[5].toInt() and 0xFF
            val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                ((header[7].toInt() and 0x7F) shl 14) or
                ((header[8].toInt() and 0x7F) shl 7) or
                (header[9].toInt() and 0x7F)

            val body = ByteArray(minOf(tagSize, MAX_ID3_BYTES))
            val read = readFully(input, body)
            if (read <= 0) return null

            var off = 0
            // v2.3/v2.4 的扩展头需要跳过
            if ((flags and 0x40) != 0 && off + 4 <= read) {
                off += if (major >= 4) syncSafe(body, off) else readBe32(body, off)
            }

            var uslt: String? = null
            var txxxLyrics: String? = null

            while (off + FRAME_HEADER_SIZE <= read) {
                val idBytes = body.decodeToString(off, off + 4)
                if (idBytes[0] == '\u0000' || !idBytes.all { it.isLetterOrDigit() }) break

                val frameSize = if (major >= 4) {
                    syncSafe(body, off + 4)
                } else {
                    readBe32(body, off + 4)
                }
                if (frameSize <= 0 || off + FRAME_HEADER_SIZE + frameSize > read) break

                val frame = off + FRAME_HEADER_SIZE
                when (idBytes) {
                    "USLT", "SYLT" -> if (uslt == null) {
                        // 编码(1) + 语言(3) + 描述(终止符) + 正文
                        val encoding = body[frame].toInt() and 0xFF
                        val afterLang = frame + 4
                        val textStart = skipTerminated(body, afterLang, encoding)
                        uslt = decode(body, textStart, frame + frameSize, encoding)
                    }

                    "TXXX" -> if (txxxLyrics == null) {
                        val encoding = body[frame].toInt() and 0xFF
                        val descStart = frame + 1
                        val descEnd = indexOfTerminator(body, descStart, encoding)
                        val desc = decode(body, descStart, descEnd, encoding)
                        if (desc.lowercase().contains("lyric")) {
                            txxxLyrics = decode(
                                body,
                                skipTerminated(body, descStart, encoding),
                                frame + frameSize,
                                encoding
                            )
                        }
                    }
                }

                off += FRAME_HEADER_SIZE + frameSize
            }

            return (uslt ?: txxxLyrics)
        }
    }

    // ── MP4 / M4A ───────────────────────────────────────────────────────────
    // 本机音乐库没有 m4a，这里用最简方式定位 ©lyr 原子，属尽力而为。

    private fun fromMp4(file: File): String? {
        val limit = minOf(file.length(), MAX_MP4_SCAN.toLong()).toInt()
        val data = ByteArray(limit)
        file.inputStream().use { if (readFully(it, data) <= 0) return null }

        val marker = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())
        val index = indexOf(data, marker)
        if (index < 0) return null

        val window = index + 4..minOf(index + 4 + 8192, data.size - 12)
        val dataAtom = indexOf(data, "data".toByteArray(Charsets.US_ASCII), window.first)
        if (dataAtom < 0 || dataAtom > window.last) return null

        // 'data' 原子：size(4) + 'data'(4) + 类型(4) + 语言(4) + 内容
        val start = dataAtom + 12
        if (start >= data.size) return null
        return data.decodeToString(start, data.size).trimEnd('\u0000')
    }

    // ── 公共工具 ────────────────────────────────────────────────────────────

    private fun pickLyric(tags: Map<String, String>): String? {
        for (key in LYRIC_KEYS) {
            val value = tags[key]
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    /** 按 ID3 文本编码字节解码；遇到宿主不支持的字符集时降级而非抛异常。 */
    private fun decode(data: ByteArray, from: Int, to: Int, encoding: Int): String {
        if (from >= to || from < 0 || to > data.size) return ""
        val raw = data.copyOfRange(from, to)
        // 去掉尾部填充的 0
        var end = raw.size
        while (end > 0 && raw[end - 1].toInt() == 0) end--
        if (end == 0) return ""

        val charsetName = when (encoding) {
            0 -> "ISO-8859-1"
            1 -> "UTF-16"          // 带 BOM
            2 -> "UTF-16BE"
            3 -> "UTF-8"
            else -> "UTF-8"
        }

        return try {
            String(raw, 0, end, Charset.forName(charsetName))
        } catch (_: Throwable) {
            // 宿主缺 jdk.charsets 时会走到这里，退化为 ISO-8859-1 保证不崩
            String(raw, 0, end, Charsets.ISO_8859_1)
        }
    }

    /** 找到终止符之后的位置。UTF-16 用双字节 0，其余用单字节 0。 */
    private fun skipTerminated(data: ByteArray, from: Int, encoding: Int): Int {
        val end = indexOfTerminator(data, from, encoding)
        return if (encoding == 1 || encoding == 2) end + 2 else end + 1
    }

    private fun indexOfTerminator(data: ByteArray, from: Int, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var i = from
            while (i + 1 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i
                i += 2
            }
        } else {
            var i = from
            while (i < data.size) {
                if (data[i].toInt() == 0) return i
                i++
            }
        }
        return data.size
    }

    private fun readLe32(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xFF) or
            ((data[off + 1].toInt() and 0xFF) shl 8) or
            ((data[off + 2].toInt() and 0xFF) shl 16) or
            ((data[off + 3].toInt() and 0xFF) shl 24)

    private fun readBe32(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xFF) shl 24) or
            ((data[off + 1].toInt() and 0xFF) shl 16) or
            ((data[off + 2].toInt() and 0xFF) shl 8) or
            (data[off + 3].toInt() and 0xFF)

    private fun syncSafe(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0x7F) shl 21) or
            ((data[off + 1].toInt() and 0x7F) shl 14) or
            ((data[off + 2].toInt() and 0x7F) shl 7) or
            (data[off + 3].toInt() and 0x7F)

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun skipFully(input: java.io.InputStream, count: Long): Long {
        var remaining = count
        while (remaining > 0) {
            val n = input.skip(remaining)
            if (n <= 0) {
                if (input.read() < 0) break
                remaining--
            } else {
                remaining -= n
            }
        }
        return count - remaining
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private const val BLOCK_VORBIS_COMMENT = 4
    private const val MAX_FLAC_BLOCKS = 64
    private const val MAX_ID3_BYTES = 4 * 1024 * 1024
    private const val MAX_MP4_SCAN = 8 * 1024 * 1024
    private const val FRAME_HEADER_SIZE = 10
}

/**
 * LRC 解析。
 *
 * 实测本机歌词的特征（200 首抽样）：
 * - 时间标签形如 `[00:00.52]`，也有 `[00:00.000]` 与 `[mm:ss:xx]` 两种变体
 * - 元信息标签：ti / ar / al / by / offset / kuwo / ver / hash / sign / total
 * - 45/200 带 `[offset:]`
 * - **0/200 带字级标签 `<mm:ss.xx>`**，所以逐字时间需要合成
 * - 平均 57.6 行
 *
 * 另一条同样重要的实测（全库 294 首）：
 * - **18 首（6%）是双语歌词**，写法是「同一时间戳写两行」，原文在前、译文在后
 * - 译文位常有占位符 `//`（34 处），表示这句没有翻译
 * - 另有 2 处把 3～4 行制作信息挤在同一个时间戳
 *
 * 双语那一条必须在这里配对成「原文 + 译文」，否则选行时会取到译文，整体错位一行。
 *
 * 说明：本对象是插件内部使用的工具，不对外构成 API。之所以不是 `internal`，
 * 是为了让 `tools/probe` 下的探针能直接调用它做全库机械校验——
 * 歌词解析的正确性（尤其是双语配对）只靠肉眼看渲染结果是查不出来的。
 */
object LrcParser {


    /**
     * 时间标签。
     *
     * 毫秒位**不能限死 1~3 位**：有些压制工具会写 5~6 位（`[01:47.490005]`、
     * `[03:25.54001]`）。原来写的是 `\d{1,3}`，这些行整个匹配不上，于是被
     * 当成「没有时间标签的行」直接丢掉——实测本机 298 首里有 18 首、共 63 行
     * 因此消失，而且每次播那首歌都丢同一句（《天下无双》丢 2 行）。
     */
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d+))?]""")
    private val META_TAG = Regex(
        """^\[(ti|ar|al|by|offset|kuwo|ver|kugou|total|length|hash|sign|re|ve|tool|artist|album|title):[^\]]*]""",
        RegexOption.IGNORE_CASE
    )

    /** 解析成按时间排序的歌词行。空白行会被剔除（见下方说明）。 */
    fun parse(raw: String, synthesizedCells: Boolean = true): List<LyricLine> {
        val offsetMs = readOffset(raw)
        val staged = ArrayList<Pair<Long, String>>()

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val tags = TIME_TAG.findAll(trimmed).toList()
            if (tags.isEmpty()) continue

            var text = trimmed.substring(tags.last().range.last + 1)
            text = text.trim()
            // 形如 [00:01.00] 后面还跟着元信息标签的，去掉
            if (META_TAG.containsMatchIn(text)) {
                text = text.replace(META_TAG, "").trim()
            }

            // ANYNC 的 ParsedLyricLine 在文本为空时会抛 ArgumentException
            // （"Line text cannot be empty."），所以空白行必须在插件侧剔除，
            // 否则渲染器在投影歌词时会异常。
            if (text.isEmpty()) continue

            for (tag in tags) {
                val ms = toMillis(tag) + offsetMs
                if (ms >= 0) {
                    staged.add(ms to text)
                }
            }
        }

        if (staged.isEmpty()) return emptyList()
        staged.sortBy { it.first }

        val groups = groupByTimestamp(staged)

        val lines = ArrayList<LyricLine>(groups.size)
        for (g in groups.indices) {
            val startMs = groups[g].first
            val naturalEnd = groups.getOrNull(g + 1)?.first ?: (startMs + TAIL_DURATION_MS)
            for ((text, translation) in groups[g].second) {
                lines.add(
                    LyricLine(
                        startMs = startMs,
                        endMs = naturalEnd,
                        text = text,
                        translation = translation,
                        cells = if (synthesizedCells) {
                            SyllableSynthesizer.synthesize(text, startMs, naturalEnd)
                        } else {
                            emptyList()
                        }
                    )
                )
            }
        }
        return lines
    }

    /**
     * 把时间轴按时间戳分组，顺带合并「原文 + 译文」。
     *
     * 双语 LRC 的固定写法就是把原文和译文用**两个完全一样的时间标签**写在一起：
     * ```
     * [00:13.68]I'm sittin' here in the boring room
     * [00:13.68]我呆坐在这了无生趣的房间
     * ```
     *
     * 不合并的后果很具体：两条记录 `startMs` 相同，选行时取「最后一个
     * `startMs <= 当前位置`」会取到**后一条（译文）**，于是主行显示译文、
     * 第二行显示下一句原文——整体错位一行。
     *
     * 实测本机音乐库：294 首里 18 首（6%）是双语，全部是「原文在前、译文在后」。
     */
    private fun groupByTimestamp(
        staged: List<Pair<Long, String>>
    ): List<Pair<Long, List<Pair<String, String?>>>> {
        // 先把「同一时间戳」切成区块，再判断这份歌词到底是不是双语。
        //
        // 判据用**比例**，而不是「只要出现两行同时间戳就配对」：
        // 实测本机 298 首，「同时间戳恰两行」的区块占比在真双语里是 61.5%~100%，
        // 在非双语里只有 1.8%~20%——而后者全是制作人员名单
        // （`[03:38.27]Keyboards, Synths & Programming : Gary Tong` +
        //  `[03:38.27]Produced by Anthony Sun / Gary Tong / Eason Chan`、
        //  `[00:00.11]作曲：Eric Kwok` + `[00:00.11]编曲：Eric Kwok`），不是译文。
        //
        // 误判的代价很大：只要有一行被当成译文，整首歌就进入「有翻译」模式，
        // 其余每一行的第二行都显示「…」占位——用户看到的就是「第二行不显示」。
        val blocks = ArrayList<IntArray>() // 每块是 [start, end)
        var cursor = 0
        while (cursor < staged.size) {
            val startMs = staged[cursor].first
            var end = cursor
            while (end < staged.size && staged[end].first == startMs) end++
            blocks.add(intArrayOf(cursor, end))
            cursor = end
        }
        if (blocks.isEmpty()) return emptyList()

        val pairBlocks = blocks.count { it[1] - it[0] == 2 }
        val bilingual = pairBlocks.toDouble() / blocks.size >= BILINGUAL_MIN_PAIR_RATIO

        val groups = ArrayList<Pair<Long, List<Pair<String, String?>>>>(blocks.size)
        for (b in blocks) {
            val startMs = staged[b[0]].first
            val texts = (b[0] until b[1]).map { staged[it].second }
            val entries: List<Pair<String, String?>> = when {
                texts.size == 1 -> listOf(texts[0] to null)

                // 双语文件：同一时间戳的前后两行就是「原文 + 译文」
                bilingual && texts.size == 2 ->
                    listOf(pairUpOriginalAndTranslation(texts[0], texts[1]))

                // 其余情况（两行制作信息，或 3~4 行挤在同一个时间戳）：拼成一行。
                // 不能留成多条同时间戳的行：[LyricLineSelector.indexOfLine] 取的是
                // 「最后一个 startMs <= 位置」，前面几条永远显示不出来，等于丢句。
                else -> listOf(texts.joinToString(" ") to null)
            }
            groups.add(startMs to entries)
        }
        return groups
    }

    private fun pairUpOriginalAndTranslation(first: String, second: String): Pair<String, String?> {
        if (isPlaceholder(first)) {
            // 极少数写法会把占位符放在前面；此时第二行才是真正要显示的内容
            return second to null
        }
        return first to second.takeIf { !isPlaceholder(it) }
    }

    /**
     * 是否只是「这句没有翻译」的占位符。
     *
     * 实测翻译位上的占位符只有一种：QQ 音乐的 `//`（本机出现 34 次）。
     * 判定放宽到「很短、且不含任何字母数字或汉字」——`//`、`…`、`♪` 这类都算，
     * 否则第二行会老老实实把 `//` 显示出来。
     */
    private fun isPlaceholder(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (t.length > MAX_PLACEHOLDER_LENGTH) return false
        return t.none { it.isLetterOrDigit() }
    }

    /** `[offset:]` 单位毫秒。注意各家播放器对该值的正负方向实现不一致，此处按「加到时间轴」处理。 */
    private fun readOffset(raw: String): Long {
        val match = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE).find(raw) ?: return 0L
        return match.groupValues[1].toLongOrNull() ?: 0L
    }

    private fun toMillis(tag: MatchResult): Long {
        val minutes = tag.groupValues[1].toLongOrNull() ?: 0L
        val seconds = tag.groupValues[2].toLongOrNull() ?: 0L
        val fraction = tag.groupValues[3]
        val millis = when {
            fraction.isEmpty() -> 0L
            fraction.length == 1 -> fraction.toLong() * 100
            fraction.length == 2 -> fraction.toLong() * 10
            else -> fraction.substring(0, 3).toLong()
        }
        return minutes * 60_000 + seconds * 1_000 + millis
    }

    private const val TAIL_DURATION_MS = 5_000L

    /**
     * 「同时间戳恰两行」的区块占全部区块的最少比例，达到才按双语处理。
     *
     * 实测本机：真双语 61.5%~100%，假配对（制作人员名单）1.8%~20%，中间是空的。
     */
    private const val BILINGUAL_MIN_PAIR_RATIO = 0.5

    /** 超过这个长度就不再当成「无翻译」占位符——真歌词也可能只有一个符号。 */
    private const val MAX_PLACEHOLDER_LENGTH = 6
}

/**
 * 合成字级时间。
 *
 * 内嵌歌词是纯行级 LRC，没有「第几个字在第几毫秒」的信息，
 * 因此按字符权重把整行时长分配下去，得到可用于逐字高亮的单元。
 *
 * 这只能近似：节奏快的段落会略有偏差，适合当视觉增强，不适合当精确卡拉OK。
 * 如果宿主或歌词里带了真实字级数据（SPW 的 `lyricsCells` 非空），应当优先用真实数据。
 *
 * 说明：本对象是插件内部使用的工具，不对外构成 API。之所以不是 `internal`，
 * 是为了让 `tools/probe` 下的探针能直接调用它做全库机械校验——
 * 歌词解析的正确性（尤其是双语配对）只靠肉眼看渲染结果是查不出来的。
 */
object SyllableSynthesizer {


    fun synthesize(text: String, startMs: Long, naturalEndMs: Long): List<LyricCell> {
        if (text.isEmpty()) return emptyList()

        // 行与行之间可能有长间奏。如果直接铺满，最后一个字会长时间停住不动，
        // 观感很差。这里给单个字设上限，超出的时间让扫描提前结束。
        val maxSpan = maxOf(MIN_LINE_SPAN_MS, text.length * MAX_MS_PER_CHAR)
        val endMs = minOf(naturalEndMs, startMs + maxSpan).coerceAtLeast(startMs + 1)

        val weights = text.map { weight(it) }
        val totalWeight = weights.sum().coerceAtLeast(1)
        val span = endMs - startMs

        val cells = ArrayList<LyricCell>(text.length)
        var cursor = startMs
        var consumed = 0
        for (i in text.indices) {
            consumed += weights[i]
            val boundary = startMs + span * consumed / totalWeight
            val cellEnd = if (i == text.lastIndex) endMs else boundary.coerceAtLeast(cursor + 1)
            cells.add(LyricCell(startMs = cursor, endMs = cellEnd, text = text[i].toString()))
            cursor = cellEnd
        }
        return cells
    }

    /** 粗略的「这个字符要唱多久」权重。CJK 一字一音节，拉丁字母更快。 */
    private fun weight(ch: Char): Int = when {
        ch.isWhitespace() -> 1
        ch.code in 0x4E00..0x9FFF -> 4          // 中日韩统一表意文字
        ch.code in 0x3040..0x30FF -> 4          // 日文假名
        ch.code in 0xAC00..0xD7AF -> 4          // 韩文
        ch.isLetterOrDigit() -> 2               // 拉丁字母与数字
        else -> 1                               // 标点
    }

    private const val MAX_MS_PER_CHAR = 400L
    private const val MIN_LINE_SPAN_MS = 800L
}
