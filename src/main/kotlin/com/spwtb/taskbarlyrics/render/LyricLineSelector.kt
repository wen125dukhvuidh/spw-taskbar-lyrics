package com.spwtb.taskbarlyrics.render

import com.spwtb.taskbarlyrics.lyrics.LyricLine

/** 某一时刻该显示的内容：主行 + （可能的）第二行。 */
data class LyricSelection(
    /** 主行在时间轴里的下标。 */
    val index: Int,
    val mainText: String,
    val secondText: String?,
    val secondKind: SecondLineKind
)

/**
 * 从完整时间轴里挑出「这一刻要显示的一到两行」。
 *
 * 单独抽出来是因为这段是**显示错位的常见来源**，必须能被探针直接调用验证：
 * 双语歌词如果在解析阶段没把「原文 + 译文」配成一对，两条记录会有相同的 `startMs`，
 * 而 [indexOfLine] 取的是「最后一个 `startMs <= 当前位置`」——正好取到后一条（译文），
 * 于是主行显示译文、第二行显示下一句原文，整体错位一行。
 *
 * 换句话说：[pick] 本身是对的，它依赖 [LyricLine] 的时间轴是「一行一次」的。
 * 前提由 `LrcParser` 的双语配对保证。
 */
object LyricLineSelector {

    /** 最后一个 `startMs <= positionMs` 的行；位置早于首行时返回 0。 */
    fun indexOfLine(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.size - 1
        var answer = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startMs <= positionMs) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }

    /** 下一句可显示的内容，跳过空行——LRC 里常用空行标间奏。 */
    fun nextLineText(lines: List<LyricLine>, index: Int): String? {
        for (i in index + 1 until lines.size) {
            val text = lines[i].text
            if (text.isNotBlank()) {
                return text
            }
        }
        return null
    }

    /**
     * 组装这一帧的歌词内容；返回 null 表示「什么都不显示」。
     *
     * 第二行按 [SecondLineMode] 取内容，并且要把「类型」一起带回给绘制端——
     * 原版对译文、下一句、占位符用了三档不同的透明度（见 [SecondLineKind]）。
     */
    /**
     * @param trackHasTranslation 整首是否有翻译。生产路径由 `LyricsStore` 在解析时
     *     算好传进来，避免每帧把整首歌词扫一遍；默认值只为探针/单元测试保留。
     */
    @JvmOverloads
    fun pick(
        lines: List<LyricLine>,
        positionMs: Long,
        mode: SecondLineMode,
        trackHasTranslation: Boolean = lines.any { !it.translation.isNullOrBlank() }
    ): LyricSelection? {
        if (lines.isEmpty()) {
            return null
        }

        val index = indexOfLine(lines, positionMs)
        val line = lines[index]
        if (line.text.isBlank()) {
            return null
        }

        val translation = line.translation?.takeIf { it.isNotBlank() }
        // 原版只有「整首歌词里有翻译」时才进入翻译模式：
        // `MainWindow.xaml.cs`: translationMode = ShowLyricTranslation && HasTrackTranslation

        val secondText: String?
        val secondKind: SecondLineKind
        when (mode) {
            SecondLineMode.NONE -> {
                secondText = null
                secondKind = SecondLineKind.NEXT
            }

            SecondLineMode.TRANSLATION -> {
                // 翻译模式下原版**只取本句翻译，不回落成下一句**
                // （app.js: visibleSecondary = 翻译模式 ? currentTranslation : next）。
                // 本句没有翻译时渲染成 `…` 占位，透明度 0.70 × 0.55。
                secondText = translation ?: ELLIPSIS.takeIf { trackHasTranslation }
                secondKind = if (translation != null) {
                    SecondLineKind.TRANSLATION
                } else {
                    SecondLineKind.PLACEHOLDER
                }
            }

            SecondLineMode.NEXT -> {
                secondText = nextLineText(lines, index)
                secondKind = SecondLineKind.NEXT
            }

            SecondLineMode.AUTO -> when {
                // 本句有翻译 → 显示译文
                translation != null -> {
                    secondText = translation
                    secondKind = SecondLineKind.TRANSLATION
                }

                // 整首有翻译但本句没有（LRC 里通常写作 `[mm:ss.xx]//`）→ 原版显示 `…` 占位。
                // 不回落成下一句，否则第二行会在「译文」和「下一句」之间来回跳。
                trackHasTranslation -> {
                    secondText = ELLIPSIS
                    secondKind = SecondLineKind.PLACEHOLDER
                }

                // 整首都没有翻译 → 等价于原版的 SINGLE 布局，第二行就是下一句
                // （`.next-line { color: var(--secondary) 0.60; opacity: 0.72 }`）
                else -> {
                    secondText = nextLineText(lines, index)
                    secondKind = SecondLineKind.NEXT
                }
            }
        }

        return LyricSelection(
            index = index,
            mainText = line.text,
            secondText = secondText,
            secondKind = secondKind
        )
    }

    /** 原版 `resolveTranslationDisplay()` 在译文为空时用的占位符。 */
    private const val ELLIPSIS = "…"
}
