package com.spwtb.taskbarlyrics.render

/**
 * 逐字扫描进度。
 *
 * 来源：`TaskbarLyrics.Core/Services.LyricSyncService.cs` 的
 * `CalculateWordScanProgress`，外加 `Web/Lyrics/style.css` 里那条
 * `.line.word-scan-smoothing { transition: --word-scan-progress 90ms linear }`。
 *
 * 原版的进度是**两层**平滑，缺一层就会退化成「一次亮一个字」：
 *
 * 1. **字内部连续插值**。进度不是「这个字唱完没」这种布尔量，
 *    而是按**字符数**连续推进的浮点比例——一个字唱到一半，进度就走完这个字的一半。
 *    原版自己的测试 `GetDisplayFrameAsyncCalculatesContinuousWordScanProgress...`
 *    就把这条钉死了：8 字符的 `"Hi there"`，第一个 segment（0~1s，`"Hi"`）
 *    唱到 500ms 时进度是 `1/8`，不是 0。
 * 2. **90ms 线性追赶**。即便源头连续，宿主推送进度的时刻也可能很稀疏
 *    （SPW 的 `onPositionUpdated` 每秒才一次，中间靠外推，每次回调都会校正一次跳变）。
 *    原版用 CSS transition 把每次跳变摊到 90ms 内，这里用 [ScanSmoother] 等价实现。
 */
object WordScan {

    /** 原版 `.line.word-scan-smoothing`： `--word-scan-progress 90ms linear`。 */
    const val SMOOTH_MS = 90L

    /**
     * 原版 `setLineWordScanProgress` 里 `isContinuousProgress` 的上界：
     * 一次**前进**超过这个比例就不做平滑，直接跳。
     * 换行、切歌、拖拽进度条都属于这种情况——平滑它们只会看出「拖泥带水」。
     */
    const val MAX_SMOOTH_STEP = 0.35

    /** 同上，倒退的容差。倒退只可能是换了行或者 seek 回去，同样不该平滑。 */
    const val BACKWARD_TOLERANCE = 0.02

    /**
     * 扫描进度（0~1，相对**整行文本**），没有字级数据时返回 null。
     *
     * 算法逐条照抄原版，包括那几个看起来啰嗦但各有用处的分支：
     * - 按字符数而不是像素宽度计权（英文/数字下两者差别很明显）；
     * - `indexOf` 从 `searchIndex` 之后开始找，找不到就退到 `searchIndex`；
     *   ——这一步决定了 `"Hi there"` 里第二个字的基数是 3 而不是 2（要算上空格）；
     * - 时长为 0 的字（纯标点、拖尾）直接整段计入，不参与插值；
     * - 走完最后一个字之后一律返回 1，避免末尾留下一点永远填不满的残渣。
     *
     * @param displayText 实际显示出来的文本（可能被省略号截断过）
     */
    fun progress(frame: LyricsFrame, displayText: String): Double? {
        val cells = frame.cells
        if (cells.isEmpty()) {
            return null
        }

        val elapsed = frame.positionMs - frame.lineStartMs
        val length = displayText.length
        var scanned = 0.0
        var searchIndex = 0
        var hasUsableCell = false
        var lastUsableEnd = 0L

        for (cell in cells) {
            val text = cell.text
            if (text.isEmpty()) {
                continue
            }
            var cellLength = text.length
            var cellIndex = displayText.indexOf(text, searchIndex)
            if (cellIndex < 0) {
                cellIndex = minOf(searchIndex, length)
            }
            cellLength = minOf(cellLength, length - cellIndex)
            if (cellLength <= 0) {
                continue
            }

            hasUsableCell = true
            val offset = cell.startMs - frame.lineStartMs
            val duration = cell.endMs - cell.startMs
            lastUsableEnd = offset + if (duration > 0) duration else 0

            if (elapsed <= offset) {
                break
            }
            if (duration <= 0) {
                scanned = (cellIndex + cellLength).toDouble()
                searchIndex = cellIndex + cellLength
                continue
            }

            val cellProgress = ((elapsed - offset).toDouble() / duration.toDouble())
                .coerceIn(0.0, 1.0)
            scanned = cellIndex + cellLength * cellProgress
            if (cellProgress < 1.0) {
                break
            }
            searchIndex = cellIndex + cellLength
        }

        if (!hasUsableCell) {
            return null
        }
        if (scanned >= searchIndex && searchIndex > 0 && elapsed >= lastUsableEnd) {
            return 1.0
        }
        return (scanned / maxOf(1, length)).coerceIn(0.0, 1.0)
    }
}

/**
 * 90ms 线性追赶器，等价物是原版的 CSS transition。
 *
 * CSS transition 的语义是：目标值一变，就从**当前 computed value** 起，
 * 用指定时长线性走到新值；中途再变，就从当时的实际值重新起算。
 * 这里是同一套：每次 [retarget] 都以 [level] 的当前值作为新起点，
 * 而不是从上一个目标值——否则连续推进时会一点点累积滞后。
 *
 * 什么时候**不**平滑由 [retarget] 自己判断（见 [WordScan.MAX_SMOOTH_STEP]），
 * 调用方不用操心；但「这一行根本不做扫描」得调用方调 [reset] 告知，
 * 否则下一行的进度会从上一个值追过去，看着像扫描头自己滑了一整行。
 */
class ScanSmoother(private val durationMs: Long = WordScan.SMOOTH_MS) {

    /** -1 表示还没有任何目标，第一次必然是硬切（等价于原版 `previousProgress` 为 NaN）。 */
    private var target = -1.0
    private var from = 0.0
    private var startNanos = 0L

    /** 当前应当画出来的进度。 */
    fun level(nowNanos: Long = System.nanoTime()): Double {
        if (from == target) {
            return target
        }
        val elapsed = (nowNanos - startNanos) / 1_000_000.0
        val t = (elapsed / durationMs.toDouble()).coerceIn(0.0, 1.0)
        return from + (target - from) * t
    }

    /**
     * 设新目标。对相同目标幂等——不会每帧重启时间线，否则永远追不上。
     *
     * @param nowNanos 当前时刻；渲染循环里一帧只取一次，避免同一帧内算出两个进度
     */
    fun retarget(to: Double, nowNanos: Long = System.nanoTime()) {
        val previous = target
        if (previous >= 0 && kotlin.math.abs(to - previous) < 1e-9) {
            return
        }
        val continuous = previous >= 0 &&
            to >= previous - WordScan.BACKWARD_TOLERANCE &&
            to - previous <= WordScan.MAX_SMOOTH_STEP
        // 两端不做平滑：0 是这一行还没开始唱，1 是唱完了，中间没有可插的值
        val smooth = continuous && to > 0.0 && to < 1.0
        from = if (smooth) level(nowNanos) else to
        target = to
        startNanos = nowNanos
    }

    /** 这一行不做扫描：清掉状态，下次从头硬切。 */
    fun reset() {
        target = -1.0
        from = 0.0
        startNanos = System.nanoTime()
    }
}
