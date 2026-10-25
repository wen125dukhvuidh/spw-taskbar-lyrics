@file:OptIn(UnstableSpwWorkshopApi::class)

package com.spwtb.taskbarlyrics.lyrics

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.io.File
import java.security.MessageDigest
import java.util.TreeMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 插件侧的歌词状态中心。播放扩展点写进来，渲染线程读出去。
 *
 * ## 取词策略：内嵌解析优先，累加兜底
 *
 * SPW 1.18.x 只通过 `onLyricsLineUpdated` 吐「当前行」，拿不到完整时间轴。
 * 但本地音乐普遍把歌词内嵌在文件标签里，插件可以直接解析文件——一次拿到完整时间轴，
 * 也就不存在「前半段采集不到」的问题。
 *
 * 实测本机音乐库（298 首：294 FLAC + 4 MP3）：
 * - 内嵌歌词覆盖率 **100%**
 * - 同目录 .lrc **0 个** → 不存在与播放器显示不一致的歧义
 * - 全部是行级 LRC，**没有字级标签**，逐字时间由 [SyllableSynthesizer] 合成
 *
 * 因此流程是：
 * 1. 换曲 → 异步解析内嵌歌词 → 命中则直接 READY（完整时间轴）
 * 2. 解析不到 → 回退到按 `onLyricLineUpdated` 边播边攒
 * 3. 攒也攒不到 → 超过时间窗后判定为 NONE
 *
 * ## 关于缓存
 *
 * **不做落盘缓存**：内嵌解析本身就能给出完整时间轴，落盘是多余的。
 * 只保留一层内存缓存（最近若干首），避免同一首歌反复读文件与解析。
 */
object LyricsStore {

    private val lock = Any()

    /** 累加兜底用的时间轴，按行开始时间索引 */
    private val accumulated = TreeMap<Long, LyricLine>()

    /**
     * 内存缓存：播放过的歌曲 → 已解析的完整时间轴。LRU，条数有上限。
     *
     * 每行都带着逐字单元（一个字一个对象），一首歌的额外对象轻松上百 KB，
     * 所以不按“越多越好”设：8 首（约一张专辑）足够覆盖“切走再切回来”，
     * 再多只是白占播放器进程的堆。
     */
    private val memoryCache = object : LinkedHashMap<String, List<LyricLine>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LyricLine>>): Boolean =
            size > MEMORY_CACHE_ENTRIES
    }

    @Volatile private var songValue: LyricSong? = null
    @Volatile private var songRevisionValue: Long = 0L
    @Volatile private var lyricsStateValue: LyricsState = LyricsState.NONE
    @Volatile private var lyricsOriginValue: LyricsOrigin = LyricsOrigin.NONE
    @Volatile private var lyricsRevisionValue: Long = 0L
    @Volatile private var linesValue: List<LyricLine> = emptyList()
    @Volatile private var hasTranslationValue: Boolean = false
    @Volatile private var playingValue: Boolean = false
    @Volatile private var positionMsValue: Long = 0L
    @Volatile private var positionAtNanosValue: Long = 0L
    @Volatile private var songChangedAtMs: Long = 0L

    /** 上一次位置回调的值，用于判断位置是否真的在前进（补启动时漏掉的 isPlaying=true）。 */
    @Volatile private var lastPositionMs: Long = -1L

    /** 内嵌歌词没拿到时的重试计时/次数（换曲清零）。 */
    @Volatile private var embeddedRetryCount: Int = 0
    @Volatile private var embeddedRetryAtMs: Long = 0L

    /**
     * 内嵌歌词解析线程。
     *
     * 每次插件启用时重建（[start]），停用时在 [clear] 里关掉：这个线程会持有
     * 所在插件的 classloader，宿主热重载插件时若不关，旧 classloader 会一直被
     * 一条 daemon 线程拽着。
     */
    @Volatile
    private var ioExecutor: ExecutorService = newIoExecutor()

    private fun newIoExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "spw-taskbar-lyrics-io").apply { isDaemon = true }
        }

    /** 插件启用时调用：重建已被 [clear] 关掉的解析线程。 */
    fun start() {
        synchronized(lock) {
            if (ioExecutor.isShutdown) {
                ioExecutor = newIoExecutor()
            }
        }
    }

    /**
     * 插件启动时补一次「当前歌曲」查询。
     *
     * 宿主 1.19 的 `getCurrentMediaItem()` 需要 `LIBRARY_READ`。拿到真实 MediaItem 后，
     * 首曲也能读内嵌封面/歌词（不再依赖临时会话）。权限未授予或查询失败时静默降级，
     * 仍由 [onLyricsLinesReplayed] / 行回调的临时会话显示歌词。
     */
    fun requestCurrentSong(onReady: () -> Unit = {}) {
        val executor = ioExecutor
        try {
            executor.execute {
                val item = runCatching {
                    if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ)) {
                        null
                    } else {
                        WorkshopApi.playback.getCurrentMediaItem()
                    }
                }.getOrNull()
                if (item != null) {
                    onSongChanged(item)
                }
                onReady()
            }
        } catch (_: RejectedExecutionException) {
            // 插件刚好在停用
        }
    }

    val song: LyricSong? get() = songValue
    val songRevision: Long get() = songRevisionValue
    val lyricsState: LyricsState get() = lyricsStateValue
    val lyricsOrigin: LyricsOrigin get() = lyricsOriginValue
    val lyricsRevision: Long get() = lyricsRevisionValue
    val lines: List<LyricLine> get() = linesValue

    /** 整首歌词里是否至少有一句带翻译。解析时算一次，避免渲染线程逐帧全表扫描。 */
    val hasTranslation: Boolean get() = hasTranslationValue
    val playing: Boolean get() = playingValue
    val positionMs: Long get() = positionMsValue

    /** 换曲。SPW 在歌词加载前会先调这里。 */
    fun onSongChanged(mediaItem: PlaybackExtensionPoint.MediaItem) {
        // 注意：1.18.x 的 MediaItem 只有 title/artist/album/albumArtist/path 五个字段，
        // 没有 id 也没有 duration，不要引用。
        val key = songKeyOf(mediaItem)
        val sameSong = songValue?.key == key

        synchronized(lock) {
            songValue = LyricSong(
                key = key,
                title = mediaItem.title,
                artist = mediaItem.artist,
                album = mediaItem.album,
                path = mediaItem.path
            )
            if (!sameSong) {
                songRevisionValue++
                accumulated.clear()
                linesValue = emptyList()
                hasTranslationValue = false
                lyricsOriginValue = LyricsOrigin.NONE
                lyricsStateValue = LyricsState.PENDING
                lyricsRevisionValue++
            }
            songChangedAtMs = System.currentTimeMillis()
            positionMsValue = 0L
            lastPositionMs = -1L
        }

        if (!sameSong) {
            embeddedRetryCount = 0
            embeddedRetryAtMs = 0L
            loadEmbeddedAsync(key, mediaItem.path)
        } else if (lyricsOriginValue != LyricsOrigin.EMBEDDED &&
            lyricsOriginValue != LyricsOrigin.SIDECAR
        ) {
            // 同一首歌被重新加载、而之前没能解析出内嵌歌词：再试一次。
            // 走 [retryEmbeddedIfNeeded] 是为了复用同一套限流（宿主可能频繁回调这里）。
            retryEmbeddedIfNeeded()
        }
    }

    /** 当前行变化。内嵌/完整时间轴不可用时作为兜底数据源。 */
    fun onLyricLineUpdated(line: PlaybackExtensionPoint.LyricsLine?) {
        if (line == null) return
        // 已经拿到完整时间轴时，忽略累加数据，避免两种来源混在一起
        if (lyricsOriginValue == LyricsOrigin.EMBEDDED || lyricsOriginValue == LyricsOrigin.SIDECAR) return

        val mapped = toLyricLine(line)

        synchronized(lock) {
            // 插件启动晚于首曲的 onBeforeLoadLyrics 时，song 是 null。
            // 这里补一个临时会话，让当前行和后续行能显示出来；
            // 宿主 1.19 的 onLyricsLinesUpdated 重放会补上更完整的时间轴。
            if (songValue == null) {
                songValue = provisionalSong()
                songRevisionValue++
                songChangedAtMs = System.currentTimeMillis()
                lyricsStateValue = LyricsState.READY
                lyricsOriginValue = LyricsOrigin.ACCUMULATED
                lyricsRevisionValue++
            }
            // 没有位置回调时，用当前行的开始时间当提示，先把它显示出来
            if (songValue?.key == PROVISIONAL_KEY && positionAtNanosValue == 0L) {
                positionMsValue = mapped.startMs
                positionAtNanosValue = System.nanoTime()
                lastPositionMs = mapped.startMs
            }
            if (accumulated[mapped.startMs] != mapped) {
                accumulated[mapped.startMs] = mapped
                val lines = accumulated.values.toList()
                linesValue = lines
                hasTranslationValue = anyTranslation(lines)
                lyricsOriginValue = LyricsOrigin.ACCUMULATED
                lyricsStateValue = LyricsState.READY
                lyricsRevisionValue++
            }
        }
    }

    /**
     * SPW 1.19+ 在插件启动/重新启用后异步重放的完整歌词时间轴。
     *
     * 这是「首曲没有歌词」的正解：首曲的 onBeforeLoadLyrics 可能在扩展注册前
     * 就发生过了，但宿主会把当前完整列表再推一次。此时拿不到 MediaItem，
     * 先用 [provisionalSong] 建一个临时会话，歌词即可显示（暂无封面）；
     * 下一次换曲的 onBeforeLoadLyrics 会把它替换成真实歌曲。
     */
    fun onLyricsLinesReplayed(lines: List<PlaybackExtensionPoint.LyricsLine>) {
        if (lines.isEmpty()) return
        val mapped = lines.map { toLyricLine(it) }
        synchronized(lock) {
            val provisional = songValue?.key == PROVISIONAL_KEY
            // 已经有真实歌曲：不覆盖插件自己的内嵌解析结果
            if (songValue != null && !provisional) return
            if (songValue == null) {
                songValue = provisionalSong()
                songRevisionValue++
                songChangedAtMs = System.currentTimeMillis()
            }
            accumulated.clear()
            for (line in mapped) accumulated[line.startMs] = line
            val timeline = accumulated.values.toList()
            linesValue = timeline
            hasTranslationValue = anyTranslation(timeline)
            lyricsOriginValue = LyricsOrigin.ACCUMULATED
            lyricsStateValue = LyricsState.READY
            lyricsRevisionValue++
        }
    }

    private fun provisionalSong(): LyricSong =
        LyricSong(PROVISIONAL_KEY, "", "", "", "")

    /** 把宿主的歌词行转成插件模型；没有字级数据时按字数合成。 */
    private fun toLyricLine(line: PlaybackExtensionPoint.LyricsLine): LyricLine {
        val cells = line.lyricsCells.map {
            LyricCell(it.startTime, it.endTime, it.text)
        }.ifEmpty {
            SyllableSynthesizer.synthesize(line.pureMainText, line.startTime, line.endTime)
        }
        return LyricLine(
            startMs = line.startTime,
            endMs = line.endTime,
            text = line.pureMainText,
            translation = line.pureSubText?.takeIf { it.isNotBlank() },
            cells = cells
        )
    }

    /**
     * 判定「确实没有歌词」。
     *
     * SPW 不会明确告知加载失败，所以只能用时间窗推断：
     * 换曲后超过窗口仍没有任何来源的歌词，就认为没有。
     */
    fun onSongSettled() {
        synchronized(lock) {
            if (lyricsStateValue == LyricsState.PENDING &&
                accumulated.isEmpty() &&
                System.currentTimeMillis() - songChangedAtMs >= PENDING_GRACE_MS
            ) {
                lyricsStateValue = LyricsState.NONE
                lyricsOriginValue = LyricsOrigin.NONE
                lyricsRevisionValue++
            }
        }
    }

    fun onIsPlayingChanged(isPlaying: Boolean) {
        // 暂停→恢复时，positionAtNanosValue 还停在暂停前最后一次位置回调的时刻。
        // 不刷新的话，estimatedPositionMs() 会把整段暂停时间也算进外推
        // （被 MAX_EXTRAPOLATION_MS 截到 2 秒），歌词会先跳两秒再被下一次回调拽回来。
        if (isPlaying && !playingValue) {
            positionAtNanosValue = System.nanoTime()
        }
        playingValue = isPlaying
    }

    fun onPositionUpdated(position: Long) {
        // 插件启动时可能错过 onIsPlayingChanged(true)。位置在推进，说明确实在播放。
        // 暂停时宿主即使继续回调，位置也是同一个值，不会误判。
        val advanced = lastPositionMs >= 0 && position != lastPositionMs
        lastPositionMs = position
        if (advanced && !playingValue) {
            playingValue = true
        }
        positionMsValue = position
        // 顺带兜一次内嵌歌词：没拿到就会停在「累加模式」，第二行永远是空的
        retryEmbeddedIfNeeded()
        // 记下这次的系统时刻：SPW 每秒才回调一次，两次之间要靠外推补出平滑进度
        positionAtNanosValue = System.nanoTime()
    }

    /**
     * 跳转。位置会跳变，不能当成「正在播放」的证据，所以先清掉上一次位置。
     */
    fun onSeekTo(position: Long) {
        lastPositionMs = -1L
        onPositionUpdated(position)
    }

    /**
     * 估算的当前播放位置（毫秒）。
     *
     * SPW 的 `onPositionUpdated` **每秒才回调一次**，直接用会在任务栏上一秒一跳。
     * 这里按两次回调之间的真实流逝时间外推，得到平滑的进度。
     *
     * 上限 [MAX_EXTRAPOLATION_MS] 是必要的：万一宿主在播放中被卡住不再回调，
     * 没有这个上限位置会一直往前跑，歌词会飘到歌曲结尾。
     */
    fun estimatedPositionMs(): Long {
        val base = positionMsValue
        if (!playingValue) {
            return base
        }
        val at = positionAtNanosValue
        if (at == 0L) {
            return base
        }
        val elapsed = (System.nanoTime() - at) / 1_000_000L
        if (elapsed <= 0L) {
            return base
        }
        return base + elapsed.coerceAtMost(MAX_EXTRAPOLATION_MS)
    }

    fun clear() {
        synchronized(lock) {
            songValue = null
            lyricsStateValue = LyricsState.NONE
            lyricsOriginValue = LyricsOrigin.NONE
            accumulated.clear()
            linesValue = emptyList()
            hasTranslationValue = false
            playingValue = false
            positionMsValue = 0L
            positionAtNanosValue = 0L
            songChangedAtMs = 0L
            lastPositionMs = -1L
            embeddedRetryCount = 0
            embeddedRetryAtMs = 0L
            songRevisionValue++
            lyricsRevisionValue++
        }
        synchronized(memoryCache) { memoryCache.clear() }
        ioExecutor.shutdownNow()
    }

    /** 稳定的歌曲标识：优先文件路径，其次标题+歌手+专辑。 */
    fun songKeyOf(mediaItem: PlaybackExtensionPoint.MediaItem): String {
        val source = if (mediaItem.path.isNotBlank()) {
            "path:" + mediaItem.path
        } else {
            "meta:" + mediaItem.title + "|" + mediaItem.artist + "|" + mediaItem.album
        }
        return sha256Prefix(source)
    }

    // ── 内嵌解析 ────────────────────────────────────────────────────────────

    private fun loadEmbeddedAsync(key: String, path: String, force: Boolean = false) {
        if (path.isBlank()) {
            return
        }

        if (!force) {
            synchronized(memoryCache) {
                memoryCache[key]?.let { cached ->
                    // 空结果不进缓存，所以这里命中就一定是有内容的
                    applyLines(key, cached, LyricsOrigin.EMBEDDED)
                    return
                }
            }
        }

        val executor = ioExecutor
        try {
            executor.execute {
                val audioFile = File(path)
                if (!audioFile.isFile) {
                    return@execute
                }

                val parsed = parseFrom(audioFile)

                // ⚠️ 解析结果为空时**不要**写缓存：读取可能会因为文件被占用、标签解析异常
                // 而偶发失败，缓存了空结果就等于这首歌以后永远没有内嵌时间轴了。
                if (parsed.lines.isNotEmpty()) {
                    synchronized(memoryCache) { memoryCache[key] = parsed.lines }
                }
                applyLines(key, parsed.lines, parsed.origin)
            }
        } catch (_: RejectedExecutionException) {
            // 插件刚好在停用：任务丢掉即可，下次启用会重新解析
        }
    }

    /**
     * 内嵌歌词没拿到时的兜底重试（由每秒的位置回调驱动）。
     *
     * 症状很具体：某些歌会一直停在 [LyricsOrigin.ACCUMULATED]——宿主只把**当前行**
     * 推过来，下一句要等它变成当前行才知道，于是第二行永远是空的；切到下一首再切回来
     * 会重新加载一次，看起来就"好了"。
     *
     * 这里不再指望用户去切歌：最多重试 [MAX_EMBEDDED_RETRY] 次、每次间隔 4 秒，
     * 拿到完整时间轴后自然停止（来源变成 EMBEDDED）。
     */
    private fun retryEmbeddedIfNeeded() {
        if (lyricsOriginValue == LyricsOrigin.EMBEDDED || lyricsOriginValue == LyricsOrigin.SIDECAR) {
            return
        }
        val song = songValue ?: return
        if (song.path.isBlank()) {
            return
        }
        if (embeddedRetryCount >= MAX_EMBEDDED_RETRY) {
            return
        }
        val now = System.currentTimeMillis()
        if (embeddedRetryAtMs != 0L && now - embeddedRetryAtMs < EMBEDDED_RETRY_INTERVAL_MS) {
            return
        }
        embeddedRetryAtMs = now
        embeddedRetryCount++
        loadEmbeddedAsync(song.key, song.path, force = true)
    }

    private class ParseResult(val lines: List<LyricLine>, val origin: LyricsOrigin)

    private fun parseFrom(audioFile: File): ParseResult {
        // 1) 内嵌标签
        try {
            val embedded = EmbeddedLyrics.read(audioFile)
            if (!embedded.isNullOrBlank()) {
                val lines = LrcParser.parse(embedded)
                if (lines.isNotEmpty()) {
                    return ParseResult(lines, LyricsOrigin.EMBEDDED)
                }
            }
        } catch (_: Throwable) {
            // 解析失败继续尝试下一种来源
        }

        // 2) 同目录 .lrc（实测本机为 0，保留作兜底）
        try {
            val sidecar = EmbeddedLyrics.readSidecar(audioFile)
            if (!sidecar.isNullOrBlank()) {
                val lines = LrcParser.parse(sidecar)
                if (lines.isNotEmpty()) {
                    return ParseResult(lines, LyricsOrigin.SIDECAR)
                }
            }
        } catch (_: Throwable) {
        }

        // 3) 都没有 → 交给累加兜底
        return ParseResult(emptyList(), LyricsOrigin.NONE)
    }

    private fun applyLines(key: String, lines: List<LyricLine>, origin: LyricsOrigin) {
        synchronized(lock) {
            // 期间可能已经换歌，丢弃过期结果
            if (songValue?.key != key) {
                return
            }
            if (lines.isEmpty()) {
                // 保持 PENDING，让累加兜底有机会补上
                return
            }
            linesValue = lines
            hasTranslationValue = anyTranslation(lines)
            lyricsOriginValue = origin
            lyricsStateValue = LyricsState.READY
            lyricsRevisionValue++
            embeddedRetryCount = 0
        }
    }

    private fun anyTranslation(lines: List<LyricLine>): Boolean =
        lines.any { !it.translation.isNullOrBlank() }

    private fun sha256Prefix(value: String): String = try {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            val b = digest[i].toInt() and 0xFF
            sb.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
        }
        sb.toString()
    } catch (_: Throwable) {
        Integer.toHexString(value.hashCode())
    }

    private const val PENDING_GRACE_MS = 5000L

    /**
     * 宿主没有给出 MediaItem 时的临时会话 key（首曲启动竞态）。
     * 真实歌曲的 key 是路径/元数据的 SHA-256，不会与它撞。
     */
    private const val PROVISIONAL_KEY = "provisional-current"

    /** 内存里最多留多少首歌的完整时间轴。8 首约等于一张专辑。 */
    private const val MEMORY_CACHE_ENTRIES = 8

    /** 内嵌歌词失败后的重试节奏：每 4 秒一次，最多 5 次（约 20 秒后放弃）。 */
    private const val EMBEDDED_RETRY_INTERVAL_MS = 4_000L
    private const val MAX_EMBEDDED_RETRY = 5

    /** 位置外推上限：超过这个时长就认为回调已经停了，不再往前推。 */
    private const val MAX_EXTRAPOLATION_MS = 2000L
    private val HEX = "0123456789abcdef".toCharArray()
}
