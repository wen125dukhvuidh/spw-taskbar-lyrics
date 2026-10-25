package com.spwtb.taskbarlyrics.render

import java.awt.geom.Rectangle2D

/**
 * 版式计算。
 *
 * 这些公式**必须由绘制端与取图端共用**。
 *
 * 原因很具体：渲染器要提前把封面缩放成位图（缩放在 IO 侧的缓存里做，
 * 不能每帧做），而绘制端按同样的公式算贴图矩形。只要两边算出来差 1 像素，
 * `drawImage` 就会多做一次隐式缩放——画面立刻变糊，而且很难查。
 *
 * 基准：以 48 逻辑像素的任务栏高度为 1.0，再乘 DPI 缩放与用户的整体缩放。
 */
object LyricsLayout {

    /** 综合缩放系数：DPI（150% 时是 1.5）× 用户的整体缩放。 */
    fun scale(dpiScale: Double, settings: RenderSettings): Double =
        dpiScale * settings.layoutScalePercent / 100.0

    /** 背景胶囊与任务栏上下边缘之间的留白。 */
    fun verticalInset(layout: Double): Double = (2.0 * layout).coerceAtLeast(1.0)

    fun boxTop(layout: Double): Double = verticalInset(layout)

    fun boxHeight(windowHeight: Int, layout: Double): Double =
        (windowHeight - verticalInset(layout) * 2).coerceAtLeast(1.0)

    /** 封面在屏幕上的**实际像素边长**。取图与贴图都以此为准。 */
    fun coverSizePx(settings: RenderSettings, layout: Double, boxHeight: Double): Int =
        (settings.coverSize * layout).coerceIn(8.0, boxHeight).toInt()

    /** 封面圆角**半径**（像素）。圆角在取图时烘焙进位图，所以这里要和取图用同一个值。 */
    fun coverRadiusPx(settings: RenderSettings, layout: Double): Int =
        (settings.coverCornerRadius * layout).toInt().coerceAtLeast(0)

    // ── 以下常量全部来自原版 LyricsLayoutMetrics.cs（单位：DIP）──────────────

    /** 宿主窗口的水平内边距，封面就从这个位置开始。 */
    const val HOST_HORIZONTAL_PADDING = 8.0

    /** 歌词面板的左内边距。 */
    const val PANE_LEFT_PADDING = 2.0

    private const val HOST_VERTICAL_PADDING = 3.0
    private const val PANE_TOP_PADDING = 3.0
    private const val VIEWPORT_DESCENDER_BUFFER = 2.0
    private const val PRIMARY_OFFSET_Y = 1.0
    private const val SECONDARY_OFFSET_Y = 1.0

    /**
     * 两行歌词的垂直中心（窗口坐标，像素）。
     *
     * 复刻原版的算法（`LyricsLayoutMetrics` 的常量 + 前端 `updateMetrics()`）：
     * 先扣掉上下边距、面板顶距与下伸部缓冲，剩下的高度**两行平分**，
     * 每行在自己的格子里垂直居中，再各加一点纵向偏移。
     *
     * 用模型而不是拍脑袋的百分比，好处是换任一行高/边距都能自洽地跟着变。
     */
    fun rowCenters(windowHeightPx: Int, layout: Double): Pair<Double, Double> {
        if (layout <= 0) return 0.0 to 0.0
        val windowHeightDp = windowHeightPx / layout
        val available = (
            windowHeightDp - HOST_VERTICAL_PADDING * 2 - PANE_TOP_PADDING - VIEWPORT_DESCENDER_BUFFER
            ).coerceAtLeast(2.0)
        val rowHeight = kotlin.math.floor(available / 2.0)
        val rowGap = available - rowHeight * 2
        val firstTop = HOST_VERTICAL_PADDING + PANE_TOP_PADDING
        val first = firstTop + rowHeight / 2.0 + PRIMARY_OFFSET_Y
        val second = firstTop + rowHeight + rowGap + rowHeight / 2.0 + SECONDARY_OFFSET_Y
        return first * layout to second * layout
    }

    /**
     * 歌词视口（物理像素）。
     *
     * 原版的 `.viewport { overflow: hidden }`：歌词轨道只有两行高，
     * 换行滚动时旧行是从视口上边缘**被裁掉**的，不是淡出到看不见。
     * 少了这刀裁剪，滚出去的行会在窗口顶部再露出半截。
     */
    fun viewportRect(windowWidthPx: Int, windowHeightPx: Int, layout: Double): Rectangle2D.Double {
        if (layout <= 0) return Rectangle2D.Double(0.0, 0.0, 0.0, 0.0)
        val windowHeightDp = windowHeightPx / layout
        val top = (HOST_VERTICAL_PADDING + PANE_TOP_PADDING) * layout
        val bottom = (windowHeightDp - HOST_VERTICAL_PADDING) * layout
        return Rectangle2D.Double(
            0.0, top, windowWidthPx.toDouble(), (bottom - top).coerceAtLeast(1.0)
        )
    }
}
